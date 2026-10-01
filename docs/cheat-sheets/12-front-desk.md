# Cheat Sheet 12 — Front desk: check-in, check-out and room changes

The rules that tie rooms, reservations and bills together: check in only to a clean room, check out only with a paid bill, and tell housekeeping when a room is vacated.

There's no migration in this step; it only uses existing tables.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-12-front-desk
   ```

2. Create the simulated key-card system:
   ```java
   package com.luxstay.hms.integration;

   public interface KeyCardService {
       String issueKey(String roomNumber, LocalDate validUntil);
   }
   ```
   ```java
   package com.luxstay.hms.integration;

   @Slf4j
   @Component
   public class MockKeyCardService implements KeyCardService {

       private final SecureRandom random = new SecureRandom();

       @Override
       public String issueKey(String roomNumber, LocalDate validUntil) {
           String code = "KEY-" + roomNumber + "-" + (100000 + random.nextInt(900000));
           log.info("Key card issued for room {} valid until {}", roomNumber, validUntil);
           return code;
       }
   }
   ```
   - *A real hotel would call its lock vendor here (ASSA ABLOY, dormakaba). The interface keeps that swappable.*

3. Create the event that check-out publishes, `event/GuestCheckedOutEvent.java`:
   ```java
   package com.luxstay.hms.event;

   public record GuestCheckedOutEvent(Long reservationId, Long roomId) {
   }
   ```
   - *Front desk announces "room vacated" without knowing who listens. Housekeeping subscribes in Step 14.*

4. Add to `ReservationRepository`, for room changes:
   ```java
   @Query("""
       select case when count(res) > 0 then true else false end
       from Reservation res
       where res.room.id = :roomId
         and res.id <> :excludeId
         and res.status in :statuses
         and res.checkIn < :checkOut
         and res.checkOut > :checkIn
       """)
   boolean existsOverlappingExcluding(@Param("roomId") Long roomId,
                                      @Param("checkIn") LocalDate checkIn,
                                      @Param("checkOut") LocalDate checkOut,
                                      @Param("statuses") Collection<ReservationStatus> statuses,
                                      @Param("excludeId") Long excludeId);
   ```
   - *Same overlap check as before, but ignoring the reservation being moved.*

5. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record ChangeRoomRequest(@NotNull @Schema(example = "12") Long roomId) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record CheckInResponse(ReservationResponse reservation, String keyCardCode) {
   }
   ```

6. Create `service/FrontDeskService.java`:
   ```java
   package com.luxstay.hms.service;

   public interface FrontDeskService {
       CheckInResponse checkIn(Long reservationId);
       ReservationResponse checkOut(Long reservationId);
       ReservationResponse changeRoom(Long reservationId, Long newRoomId);
   }
   ```

7. Create `service/impl/FrontDeskServiceImpl.java`:
   ```java
   package com.luxstay.hms.service.impl;

   @Slf4j
   @Service
   @RequiredArgsConstructor
   @Transactional
   public class FrontDeskServiceImpl implements FrontDeskService {

       private final ReservationRepository reservationRepository;
       private final RoomRepository roomRepository;
       private final FolioService folioService;
       private final KeyCardService keyCardService;
       private final ReservationMapper reservationMapper;
       private final ApplicationEventPublisher events;
       private final Clock clock;

       @Override
       public CheckInResponse checkIn(Long reservationId) {
           Reservation res = find(reservationId);
           if (res.getStatus() != ReservationStatus.CONFIRMED) {
               throw new BusinessRuleException("Only confirmed reservations can check in");
           }
           LocalDate today = LocalDate.now(clock);
           if (today.isBefore(res.getCheckIn())) {
               throw new BusinessRuleException("Check-in opens on " + res.getCheckIn());
           }
           Room room = res.getRoom();
           if (room.getStatus() != RoomStatus.VACANT_CLEAN) {
               throw new BusinessRuleException("Room " + room.getNumber() + " is not ready (" + room.getStatus() + ")");
           }

           res.setStatus(ReservationStatus.CHECKED_IN);
           room.setStatus(RoomStatus.OCCUPIED);
           folioService.postCharge(res.getId(), ChargeOutlet.ROOM,
               "Room " + room.getNumber() + ", " + res.nights() + " nights", res.getTotalAmount());
           String key = keyCardService.issueKey(room.getNumber(), res.getCheckOut());

           log.info("Reservation {} checked in to room {}", res.getConfirmationCode(), room.getNumber());
           return new CheckInResponse(reservationMapper.toResponse(res), key);
       }

       @Override
       public ReservationResponse checkOut(Long reservationId) {
           Reservation res = find(reservationId);
           if (res.getStatus() != ReservationStatus.CHECKED_IN) {
               throw new BusinessRuleException("Only checked-in reservations can check out");
           }
           BigDecimal balance = folioService.balanceOf(res.getId());
           if (balance.signum() > 0) {
               throw new BusinessRuleException("Folio has an unpaid balance of " + balance);
           }

           res.setStatus(ReservationStatus.CHECKED_OUT);
           res.getRoom().setStatus(RoomStatus.VACANT_DIRTY);
           folioService.settle(res.getId());
           events.publishEvent(new GuestCheckedOutEvent(res.getId(), res.getRoom().getId()));

           log.info("Reservation {} checked out of room {}", res.getConfirmationCode(), res.getRoom().getNumber());
           return reservationMapper.toResponse(res);
       }

       @Override
       public ReservationResponse changeRoom(Long reservationId, Long newRoomId) {
           Reservation res = find(reservationId);
           if (!ReservationStatus.ACTIVE.contains(res.getStatus())) {
               throw new BusinessRuleException("Only confirmed or checked-in reservations can change room");
           }
           Room newRoom = roomRepository.findByIdForUpdate(newRoomId)
               .orElseThrow(() -> new ResourceNotFoundException("Room", newRoomId));
           if (newRoom.getStatus() == RoomStatus.OUT_OF_ORDER) {
               throw new BusinessRuleException("Room " + newRoom.getNumber() + " is out of order");
           }
           if (reservationRepository.existsOverlappingExcluding(newRoomId, res.getCheckIn(), res.getCheckOut(),
                   ReservationStatus.ACTIVE, res.getId())) {
               throw new RoomUnavailableException("Room " + newRoom.getNumber() + " is booked for these dates");
           }

           Room oldRoom = res.getRoom();
           if (res.getStatus() == ReservationStatus.CHECKED_IN) {
               if (newRoom.getStatus() != RoomStatus.VACANT_CLEAN) {
                   throw new BusinessRuleException("Room " + newRoom.getNumber() + " is not ready");
               }
               oldRoom.setStatus(RoomStatus.VACANT_DIRTY);
               newRoom.setStatus(RoomStatus.OCCUPIED);
           }
           res.setRoom(newRoom);
           log.info("Reservation {} moved from room {} to {}", res.getConfirmationCode(),
               oldRoom.getNumber(), newRoom.getNumber());
           return reservationMapper.toResponse(res);
       }

       private Reservation find(Long id) {
           return reservationRepository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
       }
   }
   ```
   - *Check-in posts the whole stay to the bill, so the guest can settle any time before leaving.*
   - *Moving a checked-in guest marks the old room dirty and the new one occupied. The price stays the same, which is how a complimentary upgrade works.*

8. Create `controller/FrontDeskController.java`:
   ```java
   package com.luxstay.hms.controller;

   @RestController
   @RequestMapping("/api/v1/front-desk")
   @RequiredArgsConstructor
   @Tag(name = "Front desk", description = "Check-in, check-out and room moves")
   @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST')")
   public class FrontDeskController {

       private final FrontDeskService frontDeskService;

       @PostMapping("/check-in/{reservationId}")
       @Operation(summary = "Check a guest in: room must be VACANT_CLEAN; posts the stay to the folio and issues a key")
       public CheckInResponse checkIn(@PathVariable Long reservationId) {
           return frontDeskService.checkIn(reservationId);
       }

       @PostMapping("/check-out/{reservationId}")
       @Operation(summary = "Check a guest out: folio must be fully paid")
       public ReservationResponse checkOut(@PathVariable Long reservationId) {
           return frontDeskService.checkOut(reservationId);
       }

       @PatchMapping("/reservations/{reservationId}/room")
       @Operation(summary = "Move a reservation to another room (upgrade or room change)")
       public ReservationResponse changeRoom(@PathVariable Long reservationId,
                                             @Valid @RequestBody ChangeRoomRequest request) {
           return frontDeskService.changeRoom(reservationId, request.roomId());
       }
   }
   ```

9. Try the whole stay in Swagger (as `receptionist@luxstay.test`):
   1. Create a guest, then book a room **starting today**
   2. `POST /api/v1/front-desk/check-in/{id}` → `CHECKED_IN` and a `keyCardCode`; the room shows `OCCUPIED`
   3. `POST /api/v1/front-desk/check-out/{id}` → **409** "unpaid balance"
   4. Pay the folio's balance with `tok_visa`, then check out again → `CHECKED_OUT`; the room shows `VACANT_DIRTY`

10. Create `src/test/java/com/luxstay/hms/unit/FrontDeskServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class FrontDeskServiceImplTest {

        private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

        @Mock ReservationRepository reservationRepository;
        @Mock RoomRepository roomRepository;
        @Mock FolioService folioService;
        @Mock KeyCardService keyCardService;
        @Mock ApplicationEventPublisher events;
        FrontDeskServiceImpl service;

        Room room;
        Reservation res;

        @BeforeEach
        void setUp() {
            Clock clock = Clock.fixed(TODAY.atTime(14, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
            service = new FrontDeskServiceImpl(reservationRepository, roomRepository, folioService, keyCardService,
                Mappers.getMapper(ReservationMapper.class), events, clock);

            Guest guest = new Guest();
            guest.setFirstName("Amelia");
            guest.setLastName("Hart");
            room = new Room();
            room.setId(101L);
            room.setNumber("101");
            room.setStatus(RoomStatus.VACANT_CLEAN);
            res = new Reservation();
            res.setId(5L);
            res.setGuest(guest);
            res.setRoom(room);
            res.setCheckIn(TODAY);
            res.setCheckOut(TODAY.plusDays(2));
            res.setStatus(ReservationStatus.CONFIRMED);
            res.setTotalAmount(new BigDecimal("900.00"));
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(res));
        }

        @Test
        void checkIn_cleanRoom_occupiesRoomPostsStayAndIssuesKey() {
            when(keyCardService.issueKey("101", TODAY.plusDays(2))).thenReturn("KEY-101-123456");

            CheckInResponse response = service.checkIn(5L);

            assertThat(response.reservation().status()).isEqualTo(ReservationStatus.CHECKED_IN);
            assertThat(response.keyCardCode()).isEqualTo("KEY-101-123456");
            assertThat(room.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
            verify(folioService).postCharge(eq(5L), eq(ChargeOutlet.ROOM), anyString(), eq(new BigDecimal("900.00")));
        }

        @Test
        void checkIn_dirtyRoom_isRejected() {
            room.setStatus(RoomStatus.VACANT_DIRTY);

            assertThatThrownBy(() -> service.checkIn(5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not ready");
        }

        @Test
        void checkIn_beforeArrivalDate_isRejected() {
            res.setCheckIn(TODAY.plusDays(1));

            assertThatThrownBy(() -> service.checkIn(5L)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void checkOut_withUnpaidBalance_isRejected() {
            res.setStatus(ReservationStatus.CHECKED_IN);
            when(folioService.balanceOf(5L)).thenReturn(new BigDecimal("12.50"));

            assertThatThrownBy(() -> service.checkOut(5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unpaid balance");
            verify(events, never()).publishEvent(any());
        }

        @Test
        void checkOut_paid_marksRoomDirtyAndPublishesEvent() {
            res.setStatus(ReservationStatus.CHECKED_IN);
            room.setStatus(RoomStatus.OCCUPIED);
            when(folioService.balanceOf(5L)).thenReturn(BigDecimal.ZERO);

            ReservationResponse response = service.checkOut(5L);

            assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_OUT);
            assertThat(room.getStatus()).isEqualTo(RoomStatus.VACANT_DIRTY);
            verify(folioService).settle(5L);
            verify(events).publishEvent(new GuestCheckedOutEvent(5L, 101L));
        }

        @Test
        void changeRoom_whenCheckedIn_swapsRoomStatuses() {
            res.setStatus(ReservationStatus.CHECKED_IN);
            room.setStatus(RoomStatus.OCCUPIED);
            Room suite = new Room();
            suite.setId(112L);
            suite.setNumber("112");
            suite.setStatus(RoomStatus.VACANT_CLEAN);
            when(roomRepository.findByIdForUpdate(112L)).thenReturn(Optional.of(suite));
            when(reservationRepository.existsOverlappingExcluding(any(), any(), any(), any(), any())).thenReturn(false);

            ReservationResponse response = service.changeRoom(5L, 112L);

            assertThat(response.roomNumber()).isEqualTo("112");
            assertThat(room.getStatus()).isEqualTo(RoomStatus.VACANT_DIRTY);
            assertThat(suite.getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        }

        @Test
        void changeRoom_toBookedRoom_isRejected() {
            Room other = new Room();
            other.setId(102L);
            other.setNumber("102");
            other.setStatus(RoomStatus.VACANT_CLEAN);
            when(roomRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(other));
            when(reservationRepository.existsOverlappingExcluding(any(), any(), any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.changeRoom(5L, 102L)).isInstanceOf(RoomUnavailableException.class);
        }
    }
    ```
    - *If Mockito complains about unnecessary stubbing in a test that doesn't use `findById`, add `@MockitoSettings(strictness = Strictness.LENIENT)` to the class.*

11. Commit, tick Step 12, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 12 – add check-in, check-out and room changes" -m "- Check-in: CONFIRMED, arrival date reached, room VACANT_CLEAN -> OCCUPIED; posts stay to folio; mock key card
    - Check-out: CHECKED_IN and balance 0 -> room VACANT_DIRTY, folio SETTLED, GuestCheckedOutEvent
    - Room change re-checks overlap excluding the reservation itself"
    git push -u origin feature/step-12-front-desk
    ```
    - *PR title: `feat: Step 12 – front desk`.*
