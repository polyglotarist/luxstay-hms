# Step 10 Documentation — Reservations and availability

Search free rooms, book them without ever double-booking (even when two people click at the same time), and cancel with a late-cancellation fee.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-10-reservations
   ```

2. Create `db/migration/V5__create_reservation.sql`:
   ```sql
   CREATE EXTENSION IF NOT EXISTS btree_gist;

   CREATE TABLE reservation (
       id                 BIGSERIAL     PRIMARY KEY,
       confirmation_code  VARCHAR(12)   NOT NULL UNIQUE,
       guest_id           BIGINT        NOT NULL REFERENCES guest (id),
       room_id            BIGINT        NOT NULL REFERENCES room (id),
       check_in           DATE          NOT NULL,
       check_out          DATE          NOT NULL,
       adults             INT           NOT NULL,
       children           INT           NOT NULL DEFAULT 0,
       status             VARCHAR(20)   NOT NULL,
       rate_plan          VARCHAR(30)   NOT NULL,
       nightly_rate       NUMERIC(10,2) NOT NULL,
       total_amount       NUMERIC(12,2) NOT NULL,
       cancellation_fee   NUMERIC(10,2),
       cancelled_at       TIMESTAMPTZ,
       created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version            BIGINT        NOT NULL DEFAULT 0,
       CONSTRAINT chk_reservation_dates CHECK (check_out > check_in),
       CONSTRAINT no_double_booking EXCLUDE USING gist (
           room_id WITH =,
           daterange(check_in, check_out) WITH &&
       ) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'))
   );

   CREATE INDEX idx_reservation_guest ON reservation (guest_id);
   CREATE INDEX idx_reservation_room_dates ON reservation (room_id, check_in, check_out);
   ```
   - *`no_double_booking` makes **PostgreSQL itself** refuse two active bookings of one room with overlapping dates. It's the last line of defence.*
   - *`daterange(check_in, check_out)` excludes the check-out day, so one guest can leave the same day the next arrives.*

3. Create the enums:
   ```java
   package com.luxstay.hms.enums;

   public enum ReservationStatus {
       CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW;

       public static final List<ReservationStatus> ACTIVE = List.of(CONFIRMED, CHECKED_IN);
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   @Getter
   @RequiredArgsConstructor
   public enum RatePlan {
       BEST_AVAILABLE(new BigDecimal("1.00"), true),
       NON_REFUNDABLE(new BigDecimal("0.90"), false),
       BREAKFAST_INCLUDED(new BigDecimal("1.15"), true);

       private final BigDecimal multiplier;
       private final boolean refundable;
   }
   ```
   - *The nightly price is the room type's base rate × the plan's multiplier.*

4. Create `entity/Reservation.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "reservation")
   public class Reservation extends BaseEntity {

       @Column(nullable = false, unique = true, length = 12)
       private String confirmationCode;

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "guest_id")
       private Guest guest;

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "room_id")
       private Room room;

       @Column(nullable = false)
       private LocalDate checkIn;

       @Column(nullable = false)
       private LocalDate checkOut;

       @Column(nullable = false)
       private Integer adults;

       @Column(nullable = false)
       private Integer children;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private ReservationStatus status;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 30)
       private RatePlan ratePlan;

       @Column(nullable = false, precision = 10, scale = 2)
       private BigDecimal nightlyRate;

       @Column(nullable = false, precision = 12, scale = 2)
       private BigDecimal totalAmount;

       @Column(precision = 10, scale = 2)
       private BigDecimal cancellationFee;

       private Instant cancelledAt;

       public long nights() {
           return ChronoUnit.DAYS.between(checkIn, checkOut);
       }
   }
   ```

5. Create `util/ConfirmationCodes.java` and `config/ClockConfig.java`:
   ```java
   package com.luxstay.hms.util;

   public final class ConfirmationCodes {

       private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
       private static final SecureRandom RANDOM = new SecureRandom();

       private ConfirmationCodes() {
       }

       public static String next() {
           StringBuilder code = new StringBuilder("LX");
           for (int i = 0; i < 8; i++) {
               code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
           }
           return code.toString();
       }
   }
   ```
   ```java
   package com.luxstay.hms.config;

   @Configuration
   public class ClockConfig {

       @Bean
       Clock clock() {
           return Clock.systemDefaultZone();
       }
   }
   ```
   - *Codes look like `LX7K2P9QAB`. Letters like O/0 and I/1 are left out so they can't be misread.*
   - *Services read "today" from the `Clock` bean, so tests can fix the date.*

6. Create `repository/ReservationRepository.java`:
   ```java
   package com.luxstay.hms.repository;

   public interface ReservationRepository extends JpaRepository<Reservation, Long> {

       @Query("""
           select case when count(res) > 0 then true else false end
           from Reservation res
           where res.room.id = :roomId
             and res.status in :statuses
             and res.checkIn < :checkOut
             and res.checkOut > :checkIn
           """)
       boolean existsOverlapping(@Param("roomId") Long roomId,
                                 @Param("checkIn") LocalDate checkIn,
                                 @Param("checkOut") LocalDate checkOut,
                                 @Param("statuses") Collection<ReservationStatus> statuses);

       @EntityGraph(attributePaths = {"guest", "room", "room.roomType"})
       Page<Reservation> findByGuestId(Long guestId, Pageable pageable);

       @Override
       @EntityGraph(attributePaths = {"guest", "room", "room.roomType"})
       Optional<Reservation> findById(Long id);
   }
   ```
   - *Two stays overlap when one starts before the other ends **and** ends after the other starts.*

7. Add to `repository/RoomRepository.java`:
   ```java
   @Query("""
       select r from Room r
       where r.roomType.id = :typeId
         and r.status <> :outOfOrder
         and not exists (
             select res.id from Reservation res
             where res.room = r
               and res.status in :statuses
               and res.checkIn < :checkOut
               and res.checkOut > :checkIn)
       order by r.number
       """)
   List<Room> findAvailable(@Param("typeId") Long typeId,
                            @Param("checkIn") LocalDate checkIn,
                            @Param("checkOut") LocalDate checkOut,
                            @Param("outOfOrder") RoomStatus outOfOrder,
                            @Param("statuses") Collection<ReservationStatus> statuses);

   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select r from Room r where r.id = :id")
   Optional<Room> findByIdForUpdate(@Param("id") Long id);
   ```
   - *`PESSIMISTIC_WRITE` locks the room row until the transaction ends, so a second booking for the same room waits, then sees the first one.*

8. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record CreateReservationRequest(
       @NotNull @Schema(example = "1") Long guestId,
       @NotNull @Schema(example = "1") Long roomTypeId,
       @NotNull @FutureOrPresent @Schema(example = "2026-12-20") LocalDate checkIn,
       @NotNull @Future @Schema(example = "2026-12-23") LocalDate checkOut,
       @NotNull @Min(1) @Schema(example = "2") Integer adults,
       @NotNull @Min(0) @Schema(example = "0") Integer children,
       @NotNull @Schema(example = "BEST_AVAILABLE") RatePlan ratePlan) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record ReservationResponse(
       Long id,
       String confirmationCode,
       Long guestId,
       String guestName,
       String guestEmail,
       Long roomId,
       String roomNumber,
       String roomTypeCode,
       LocalDate checkIn,
       LocalDate checkOut,
       Integer adults,
       Integer children,
       ReservationStatus status,
       RatePlan ratePlan,
       BigDecimal nightlyRate,
       BigDecimal totalAmount,
       BigDecimal cancellationFee) {
   }
   ```

9. Create `mapper/ReservationMapper.java`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface ReservationMapper {

       @Mapping(target = "guestId", source = "guest.id")
       @Mapping(target = "guestName", expression = "java(r.getGuest().getFirstName() + \" \" + r.getGuest().getLastName())")
       @Mapping(target = "guestEmail", source = "guest.email")
       @Mapping(target = "roomId", source = "room.id")
       @Mapping(target = "roomNumber", source = "room.number")
       @Mapping(target = "roomTypeCode", source = "room.roomType.code")
       ReservationResponse toResponse(Reservation r);
   }
   ```

10. Create `exception/RoomUnavailableException.java`, and add a handler to `GlobalExceptionHandler`:
    ```java
    package com.luxstay.hms.exception;

    public class RoomUnavailableException extends BusinessRuleException {
        public RoomUnavailableException(String message) {
            super(message);
        }
    }
    ```
    ```java
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflicting data",
            "The request conflicts with existing data (for example, an overlapping booking)");
    }
    ```
    - *Because it extends `BusinessRuleException`, `RoomUnavailableException` is already returned as 409.*
    - *The new handler turns a database constraint error, such as `no_double_booking`, into a 409 too.*

11. Create `service/ReservationService.java`:
    ```java
    package com.luxstay.hms.service;

    public interface ReservationService {
        List<RoomResponse> searchAvailability(Long roomTypeId, LocalDate checkIn, LocalDate checkOut);
        ReservationResponse create(CreateReservationRequest request);
        ReservationResponse getById(Long id);
        Page<ReservationResponse> getByGuest(Long guestId, Pageable pageable);
        ReservationResponse cancel(Long id);
    }
    ```

12. Create `service/impl/ReservationServiceImpl.java`:
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public class ReservationServiceImpl implements ReservationService {

        static final Duration CANCELLATION_WINDOW = Duration.ofHours(48);

        private final ReservationRepository reservationRepository;
        private final RoomRepository roomRepository;
        private final RoomTypeRepository roomTypeRepository;
        private final GuestRepository guestRepository;
        private final ReservationMapper reservationMapper;
        private final RoomMapper roomMapper;
        private final Clock clock;

        @Override
        public List<RoomResponse> searchAvailability(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
            validateDates(checkIn, checkOut);
            return roomRepository.findAvailable(roomTypeId, checkIn, checkOut,
                    RoomStatus.OUT_OF_ORDER, ReservationStatus.ACTIVE)
                .stream().map(roomMapper::toResponse).toList();
        }

        @Override
        @Transactional
        public ReservationResponse create(CreateReservationRequest req) {
            validateDates(req.checkIn(), req.checkOut());
            Guest guest = guestRepository.findById(req.guestId())
                .orElseThrow(() -> new ResourceNotFoundException("Guest", req.guestId()));
            RoomType type = roomTypeRepository.findById(req.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Room type", req.roomTypeId()));
            if (req.adults() + req.children() > type.getMaxOccupancy()) {
                throw new BusinessRuleException(type.getName() + " sleeps at most " + type.getMaxOccupancy());
            }

            List<Room> candidates = roomRepository.findAvailable(type.getId(), req.checkIn(), req.checkOut(),
                RoomStatus.OUT_OF_ORDER, ReservationStatus.ACTIVE);

            for (Room candidate : candidates) {
                Room room = roomRepository.findByIdForUpdate(candidate.getId()).orElseThrow();
                if (reservationRepository.existsOverlapping(room.getId(), req.checkIn(), req.checkOut(),
                        ReservationStatus.ACTIVE)) {
                    log.warn("Room {} was taken while booking; trying the next one", room.getNumber());
                    continue;
                }
                Reservation saved = reservationRepository.save(newReservation(req, guest, room, type));
                log.info("Reservation {} created: room {}, {} nights", saved.getConfirmationCode(),
                    room.getNumber(), saved.nights());
                return reservationMapper.toResponse(saved);
            }
            throw new RoomUnavailableException("No " + type.getName() + " is available for these dates");
        }

        @Override
        public ReservationResponse getById(Long id) {
            return reservationMapper.toResponse(findReservation(id));
        }

        @Override
        public Page<ReservationResponse> getByGuest(Long guestId, Pageable pageable) {
            return reservationRepository.findByGuestId(guestId, pageable).map(reservationMapper::toResponse);
        }

        @Override
        @Transactional
        public ReservationResponse cancel(Long id) {
            Reservation reservation = findReservation(id);
            if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
                throw new BusinessRuleException("Only confirmed reservations can be cancelled");
            }
            Instant now = clock.instant();
            Instant freeUntil = reservation.getCheckIn().atStartOfDay(clock.getZone())
                .toInstant().minus(CANCELLATION_WINDOW);
            boolean chargeable = now.isAfter(freeUntil) || !reservation.getRatePlan().isRefundable();

            reservation.setStatus(ReservationStatus.CANCELLED);
            reservation.setCancelledAt(now);
            reservation.setCancellationFee(chargeable ? reservation.getNightlyRate() : BigDecimal.ZERO);
            log.info("Reservation {} cancelled, fee {}", reservation.getConfirmationCode(),
                reservation.getCancellationFee());
            return reservationMapper.toResponse(reservation);
        }

        private Reservation newReservation(CreateReservationRequest req, Guest guest, Room room, RoomType type) {
            BigDecimal nightly = type.getBaseRate().multiply(req.ratePlan().getMultiplier())
                .setScale(2, RoundingMode.HALF_UP);
            Reservation r = new Reservation();
            r.setConfirmationCode(ConfirmationCodes.next());
            r.setGuest(guest);
            r.setRoom(room);
            r.setCheckIn(req.checkIn());
            r.setCheckOut(req.checkOut());
            r.setAdults(req.adults());
            r.setChildren(req.children());
            r.setRatePlan(req.ratePlan());
            r.setStatus(ReservationStatus.CONFIRMED);
            r.setNightlyRate(nightly);
            r.setTotalAmount(nightly.multiply(BigDecimal.valueOf(r.nights())));
            return r;
        }

        private void validateDates(LocalDate checkIn, LocalDate checkOut) {
            if (!checkOut.isAfter(checkIn)) {
                throw new BusinessRuleException("Check-out must be after check-in");
            }
            if (checkIn.isBefore(LocalDate.now(clock))) {
                throw new BusinessRuleException("Check-in cannot be in the past");
            }
        }

        private Reservation findReservation(Long id) {
            return reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
        }
    }
    ```
    - *Booking: find free rooms → lock one → re-check it's still free → save. If someone took it meanwhile, try the next room.*
    - *Cancelling within 48 hours of arrival, or on a non-refundable rate, costs one night. Step 11 posts that fee to the bill.*
    - *Money uses `BigDecimal` with explicit rounding, never `double`.*

13. Create `controller/ReservationController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/reservations")
    @RequiredArgsConstructor
    @Tag(name = "Reservations", description = "Availability, booking and cancellation")
    public class ReservationController {

        private final ReservationService reservationService;

        @GetMapping("/availability")
        @Operation(summary = "Find rooms of a type that are free for the dates")
        public List<RoomResponse> availability(
                @RequestParam Long roomTypeId,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
            return reservationService.searchAvailability(roomTypeId, checkIn, checkOut);
        }

        @PostMapping
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST')")
        @Operation(summary = "Book a room")
        @ApiResponse(responseCode = "201", description = "Reservation created")
        @ApiResponse(responseCode = "409", description = "No room available or invalid dates")
        public ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request) {
            ReservationResponse created = reservationService.create(request);
            return ResponseEntity.created(URI.create("/api/v1/reservations/" + created.id())).body(created);
        }

        @GetMapping("/{id}")
        @PostAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','CONCIERGE') or returnObject.guestEmail() == authentication.name")
        @Operation(summary = "Get a reservation (guests can only see their own)")
        public ReservationResponse getById(@PathVariable Long id) {
            return reservationService.getById(id);
        }

        @GetMapping
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','CONCIERGE')")
        @Operation(summary = "A guest's stay history")
        public Page<ReservationResponse> getByGuest(@RequestParam Long guestId,
                                                    @ParameterObject Pageable pageable) {
            return reservationService.getByGuest(guestId, pageable);
        }

        @PatchMapping("/{id}/cancel")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST')")
        @Operation(summary = "Cancel a reservation (late cancellation costs one night)")
        public ReservationResponse cancel(@PathVariable Long id) {
            return reservationService.cancel(id);
        }
    }
    ```
    - *`@PostAuthorize` checks **after** loading: staff see any reservation, a guest only one whose email matches their login.*
    - *`@DateTimeFormat(iso = DATE)` makes query parameters like `checkIn=2026-12-20` parse as dates.*

14. Try it in Swagger (as `receptionist@luxstay.test`):
    - Create a guest (Step 9), then `GET /api/v1/reservations/availability?roomTypeId=3&checkIn=…&checkOut=…` → 5 Presidential Suites
    - Book 5 Presidential Suites for the same dates, then try a sixth → **409**
    - `PATCH /api/v1/reservations/{id}/cancel` → `CANCELLED` with a fee of 0 (if more than 48 hours ahead)

15. Create `src/test/java/com/luxstay/hms/unit/ReservationServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class ReservationServiceImplTest {

        private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

        @Mock ReservationRepository reservationRepository;
        @Mock RoomRepository roomRepository;
        @Mock RoomTypeRepository roomTypeRepository;
        @Mock GuestRepository guestRepository;
        ReservationServiceImpl service;

        private final Guest guest = new Guest();
        private final RoomType deluxe = new RoomType();
        private final Room room101 = new Room();

        @BeforeEach
        void setUp() {
            Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(10).toInstant(), ZoneOffset.UTC);
            service = new ReservationServiceImpl(reservationRepository, roomRepository, roomTypeRepository,
                guestRepository, Mappers.getMapper(ReservationMapper.class), Mappers.getMapper(RoomMapper.class), clock);

            guest.setId(1L);
            guest.setFirstName("Amelia");
            guest.setLastName("Hart");
            deluxe.setId(1L);
            deluxe.setName("Deluxe King");
            deluxe.setMaxOccupancy(2);
            deluxe.setBaseRate(new BigDecimal("450.00"));
            room101.setId(101L);
            room101.setNumber("101");
            room101.setRoomType(deluxe);
        }

        private CreateReservationRequest request(int adults, RatePlan plan) {
            return new CreateReservationRequest(1L, 1L, TODAY.plusDays(10), TODAY.plusDays(13), adults, 0, plan);
        }

        @Test
        void create_whenRoomFree_booksThreeNightsAtPlanRate() {
            when(guestRepository.findById(1L)).thenReturn(Optional.of(guest));
            when(roomTypeRepository.findById(1L)).thenReturn(Optional.of(deluxe));
            when(roomRepository.findAvailable(any(), any(), any(), any(), any())).thenReturn(List.of(room101));
            when(roomRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(room101));
            when(reservationRepository.existsOverlapping(any(), any(), any(), any())).thenReturn(false);
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

            ReservationResponse response = service.create(request(2, RatePlan.NON_REFUNDABLE));

            assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(response.nightlyRate()).isEqualByComparingTo("405.00");
            assertThat(response.totalAmount()).isEqualByComparingTo("1215.00");
            assertThat(response.confirmationCode()).startsWith("LX").hasSize(10);
        }

        @Test
        void create_whenRoomTakenMeanwhile_andNoOtherRoom_throws409() {
            when(guestRepository.findById(1L)).thenReturn(Optional.of(guest));
            when(roomTypeRepository.findById(1L)).thenReturn(Optional.of(deluxe));
            when(roomRepository.findAvailable(any(), any(), any(), any(), any())).thenReturn(List.of(room101));
            when(roomRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(room101));
            when(reservationRepository.existsOverlapping(any(), any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.create(request(2, RatePlan.BEST_AVAILABLE)))
                .isInstanceOf(RoomUnavailableException.class);
            verify(reservationRepository, never()).save(any());
        }

        @Test
        void create_withTooManyGuests_throws() {
            when(guestRepository.findById(1L)).thenReturn(Optional.of(guest));
            when(roomTypeRepository.findById(1L)).thenReturn(Optional.of(deluxe));

            assertThatThrownBy(() -> service.create(request(3, RatePlan.BEST_AVAILABLE)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("sleeps at most 2");
        }

        @Test
        void create_withCheckOutBeforeCheckIn_throws() {
            CreateReservationRequest bad = new CreateReservationRequest(1L, 1L, TODAY.plusDays(5), TODAY.plusDays(5), 2, 0,
                RatePlan.BEST_AVAILABLE);

            assertThatThrownBy(() -> service.create(bad))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void cancel_moreThan48HoursAhead_isFree() {
            Reservation r = confirmed(TODAY.plusDays(10), RatePlan.BEST_AVAILABLE);
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(r));

            ReservationResponse response = service.cancel(5L);

            assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(response.cancellationFee()).isEqualByComparingTo("0");
        }

        @Test
        void cancel_within48Hours_costsOneNight() {
            Reservation r = confirmed(TODAY.plusDays(1), RatePlan.BEST_AVAILABLE);
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(r));

            ReservationResponse response = service.cancel(5L);

            assertThat(response.cancellationFee()).isEqualByComparingTo("450.00");
        }

        @Test
        void cancel_nonRefundable_alwaysCostsOneNight() {
            Reservation r = confirmed(TODAY.plusDays(30), RatePlan.NON_REFUNDABLE);
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(r));

            assertThat(service.cancel(5L).cancellationFee()).isEqualByComparingTo("450.00");
        }

        @Test
        void cancel_whenCheckedIn_throws() {
            Reservation r = confirmed(TODAY, RatePlan.BEST_AVAILABLE);
            r.setStatus(ReservationStatus.CHECKED_IN);
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancel(5L)).isInstanceOf(BusinessRuleException.class);
        }

        private Reservation confirmed(LocalDate checkIn, RatePlan plan) {
            Reservation r = new Reservation();
            r.setGuest(guest);
            r.setRoom(room101);
            r.setCheckIn(checkIn);
            r.setCheckOut(checkIn.plusDays(2));
            r.setRatePlan(plan);
            r.setStatus(ReservationStatus.CONFIRMED);
            r.setNightlyRate(new BigDecimal("450.00"));
            return r;
        }
    }
    ```
    - *The service is built by hand so it gets a **fixed** clock: "today" is always 1 Oct 2026 in these tests.*
    - *`isEqualByComparingTo` compares money by value, so `405.00` equals `405.0`.*

16. Create `src/test/java/com/luxstay/hms/integration/ReservationRepositoryIT.java`:
    ```java
    @DataJpaTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
    class ReservationRepositoryIT {

        @Autowired ReservationRepository reservationRepository;
        @Autowired RoomRepository roomRepository;
        @Autowired GuestRepository guestRepository;

        @Test
        void database_rejectsOverlappingActiveBookings() {
            Guest guest = new Guest();
            guest.setFirstName("Test");
            guest.setLastName("Guest");
            guest.setEmail("it-" + System.nanoTime() + "@example.com");
            guestRepository.save(guest);
            Room room = roomRepository.findAll().getFirst();
            LocalDate in = LocalDate.now().plusDays(30);

            reservationRepository.saveAndFlush(booking(guest, room, in, in.plusDays(3)));

            assertThatThrownBy(() -> reservationRepository.saveAndFlush(booking(guest, room, in.plusDays(1), in.plusDays(4))))
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void sameDayTurnover_isAllowed() {
            Guest guest = new Guest();
            guest.setFirstName("Test");
            guest.setLastName("Guest");
            guest.setEmail("it-" + System.nanoTime() + "@example.com");
            guestRepository.save(guest);
            Room room = roomRepository.findAll().get(1);
            LocalDate in = LocalDate.now().plusDays(40);

            reservationRepository.saveAndFlush(booking(guest, room, in, in.plusDays(2)));
            reservationRepository.saveAndFlush(booking(guest, room, in.plusDays(2), in.plusDays(4)));

            assertThat(reservationRepository.existsOverlapping(room.getId(), in, in.plusDays(4), ReservationStatus.ACTIVE))
                .isTrue();
        }

        private Reservation booking(Guest guest, Room room, LocalDate in, LocalDate out) {
            Reservation r = new Reservation();
            r.setConfirmationCode(ConfirmationCodes.next());
            r.setGuest(guest);
            r.setRoom(room);
            r.setCheckIn(in);
            r.setCheckOut(out);
            r.setAdults(2);
            r.setChildren(0);
            r.setStatus(ReservationStatus.CONFIRMED);
            r.setRatePlan(RatePlan.BEST_AVAILABLE);
            r.setNightlyRate(new BigDecimal("450.00"));
            r.setTotalAmount(new BigDecimal("1350.00"));
            return r;
        }
    }
    ```
    - *This proves the database itself blocks double bookings, even if the Java check were bypassed.*

17. Commit, tick Step 10, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 10 – add reservations with availability search and double-booking protection" -m "- V5 creates reservation with a btree_gist exclusion constraint: no overlapping active bookings per room
    - Booking locks the room row (PESSIMISTIC_WRITE) and re-checks overlap before saving
    - Price = base rate x rate plan multiplier x nights; cancel within 48h or non-refundable = one night fee
    - Guests can only read their own reservation (@PostAuthorize)"
    git push -u origin feature/step-10-reservations
    ```
    - *PR title: `feat: Step 10 – reservations and availability`.*
