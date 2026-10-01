# Cheat Sheet 5 — Rooms module (the recipe every module follows)

Your first complete feature: entity → repository → DTOs → mapper → service → controller → tests. Every later module repeats this order.

The tables already exist from Step 3 (`room_type`, `room`), so there's no migration in this step.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-5-rooms
   ```

2. In `pom.xml`, make sure these test dependencies are present (add any that are missing, without a version), then **Sync**:
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-webmvc-test</artifactId>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-data-jpa-test</artifactId>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-security-test</artifactId>
       <scope>test</scope>
   </dependency>
   ```
   - *Spring Boot 4 splits test support per module. These provide `@WebMvcTest`, `@DataJpaTest` and `@WithMockUser`.*
   - *If Sync can't find one, remove that entry and tell me which.*

3. Create `enums/RoomStatus.java`:
   ```java
   package com.luxstay.hms.enums;

   public enum RoomStatus {
       VACANT_CLEAN, VACANT_DIRTY, OCCUPIED, OUT_OF_ORDER
   }
   ```

4. Create `entity/RoomType.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "room_type")
   public class RoomType extends BaseEntity {

       @Column(nullable = false, unique = true, length = 20)
       private String code;

       @Column(nullable = false, length = 100)
       private String name;

       @Column(nullable = false)
       private Integer maxOccupancy;

       @Column(nullable = false, precision = 10, scale = 2)
       private BigDecimal baseRate;

       @Column(length = 500)
       private String description;
   }
   ```
   - *Each field maps to the column of the same name in snake_case: `maxOccupancy` → `max_occupancy`.*

5. Create `entity/Room.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "room")
   public class Room extends BaseEntity {

       @Column(nullable = false, unique = true, length = 10)
       private String number;

       @Column(nullable = false)
       private Integer floor;

       @Column(length = 30)
       private String viewType;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private RoomStatus status;

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "room_type_id")
       private RoomType roomType;
   }
   ```
   - *`@ManyToOne` is the `room_type_id` foreign key: many rooms share one type.*
   - *`EnumType.STRING` stores `VACANT_CLEAN` as text, never as a number.*

6. Create the repositories in `repository/`:
   ```java
   package com.luxstay.hms.repository;

   public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {
       Optional<RoomType> findByCode(String code);
   }
   ```
   ```java
   package com.luxstay.hms.repository;

   public interface RoomRepository extends JpaRepository<Room, Long> {

       boolean existsByNumber(String number);

       @EntityGraph(attributePaths = "roomType")
       Page<Room> findByStatus(RoomStatus status, Pageable pageable);

       @Override
       @EntityGraph(attributePaths = "roomType")
       Page<Room> findAll(Pageable pageable);
   }
   ```
   - *Spring writes the SQL from the method names.*
   - *`@EntityGraph` loads each room's type in the same query, avoiding one extra query per room.*

7. Create the DTOs (Java records):
   ```java
   package com.luxstay.hms.dto.request;

   public record CreateRoomRequest(
       @NotBlank @Size(max = 10) @Schema(example = "613") String number,
       @NotNull @Min(1) @Schema(example = "6") Integer floor,
       @Size(max = 30) @Schema(example = "SEA") String viewType,
       @NotNull @Schema(example = "1") Long roomTypeId) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record UpdateRoomStatusRequest(
       @NotNull @Schema(example = "OUT_OF_ORDER") RoomStatus status) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record RoomResponse(
       Long id,
       String number,
       Integer floor,
       String viewType,
       RoomStatus status,
       String roomTypeCode,
       String roomTypeName) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record RoomTypeResponse(
       Long id,
       String code,
       String name,
       Integer maxOccupancy,
       BigDecimal baseRate,
       String description) {
   }
   ```
   - *Requests carry validation rules (`@NotBlank`, `@Min`); `@Schema(example=…)` pre-fills Swagger.*
   - *Controllers only ever return these records, never entities.*

8. Create the mappers in `mapper/`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface RoomMapper {

       @Mapping(target = "roomTypeCode", source = "roomType.code")
       @Mapping(target = "roomTypeName", source = "roomType.name")
       RoomResponse toResponse(Room room);
   }
   ```
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface RoomTypeMapper {
       RoomTypeResponse toResponse(RoomType roomType);
   }
   ```
   - *MapStruct writes the implementation (`RoomMapperImpl`) when you compile. Fields with the same name map automatically.*

9. Create `service/RoomService.java`:
   ```java
   package com.luxstay.hms.service;

   public interface RoomService {
       RoomResponse create(CreateRoomRequest request);
       RoomResponse getById(Long id);
       Page<RoomResponse> getAll(RoomStatus status, Pageable pageable);
       RoomResponse updateStatus(Long id, RoomStatus status);
       List<RoomTypeResponse> getRoomTypes();
   }
   ```

10. Create `service/impl/RoomServiceImpl.java`:
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public class RoomServiceImpl implements RoomService {

        private final RoomRepository roomRepository;
        private final RoomTypeRepository roomTypeRepository;
        private final RoomMapper roomMapper;
        private final RoomTypeMapper roomTypeMapper;

        @Override
        @Transactional
        public RoomResponse create(CreateRoomRequest request) {
            if (roomRepository.existsByNumber(request.number())) {
                throw new BusinessRuleException("Room " + request.number() + " already exists");
            }
            RoomType type = roomTypeRepository.findById(request.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Room type", request.roomTypeId()));

            Room room = new Room();
            room.setNumber(request.number());
            room.setFloor(request.floor());
            room.setViewType(request.viewType());
            room.setStatus(RoomStatus.VACANT_CLEAN);
            room.setRoomType(type);

            Room saved = roomRepository.save(room);
            log.info("Room {} created", saved.getNumber());
            return roomMapper.toResponse(saved);
        }

        @Override
        public RoomResponse getById(Long id) {
            return roomMapper.toResponse(findRoom(id));
        }

        @Override
        public Page<RoomResponse> getAll(RoomStatus status, Pageable pageable) {
            Page<Room> rooms = (status == null)
                ? roomRepository.findAll(pageable)
                : roomRepository.findByStatus(status, pageable);
            return rooms.map(roomMapper::toResponse);
        }

        @Override
        @Transactional
        public RoomResponse updateStatus(Long id, RoomStatus status) {
            Room room = findRoom(id);
            RoomStatus previous = room.getStatus();
            room.setStatus(status);
            log.info("Room {} status {} -> {}", room.getNumber(), previous, status);
            return roomMapper.toResponse(room);
        }

        @Override
        public List<RoomTypeResponse> getRoomTypes() {
            return roomTypeRepository.findAll().stream().map(roomTypeMapper::toResponse).toList();
        }

        private Room findRoom(Long id) {
            return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room", id));
        }
    }
    ```
    - *`@Transactional(readOnly = true)` on the class; write methods override it with `@Transactional`.*
    - *In `updateStatus`, changing a loaded entity is enough. JPA saves it when the transaction ends.*

11. Create the controllers in `controller/`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/rooms")
    @RequiredArgsConstructor
    @Tag(name = "Rooms", description = "Room inventory and status")
    public class RoomController {

        private final RoomService roomService;

        @PostMapping
        @Operation(summary = "Create a room")
        @ApiResponse(responseCode = "201", description = "Room created")
        @ApiResponse(responseCode = "409", description = "Room number already exists")
        public ResponseEntity<RoomResponse> create(@Valid @RequestBody CreateRoomRequest request) {
            RoomResponse created = roomService.create(request);
            return ResponseEntity.created(URI.create("/api/v1/rooms/" + created.id())).body(created);
        }

        @GetMapping("/{id}")
        @Operation(summary = "Get a room by id")
        public RoomResponse getById(@PathVariable Long id) {
            return roomService.getById(id);
        }

        @GetMapping
        @Operation(summary = "List rooms, optionally filtered by status")
        public Page<RoomResponse> getAll(@RequestParam(required = false) RoomStatus status,
                                         @ParameterObject Pageable pageable) {
            return roomService.getAll(status, pageable);
        }

        @PatchMapping("/{id}/status")
        @Operation(summary = "Change a room's status")
        public RoomResponse updateStatus(@PathVariable Long id,
                                         @Valid @RequestBody UpdateRoomStatusRequest request) {
            return roomService.updateStatus(id, request.status());
        }
    }
    ```
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/room-types")
    @RequiredArgsConstructor
    @Tag(name = "Rooms")
    public class RoomTypeController {

        private final RoomService roomService;

        @GetMapping
        @Operation(summary = "List room types")
        public List<RoomTypeResponse> getAll() {
            return roomService.getRoomTypes();
        }
    }
    ```
    - *For `@ApiResponse`, import the `io.swagger.v3.oas.annotations.responses` version.*
    - *`@ParameterObject` shows `page`, `size` and `sort` as separate fields in Swagger.*

12. Run the app and try it in Swagger (`http://localhost:8080/swagger-ui.html`):
    - `GET /api/v1/rooms?status=VACANT_CLEAN&size=5` → 5 rooms
    - `POST /api/v1/rooms` with the example body → **201**; send it again → **409**
    - `GET /api/v1/rooms/9999` → **404**
    - `PATCH /api/v1/rooms/1/status` with `OUT_OF_ORDER` → status changes (check in DBeaver)

13. Create the unit test `src/test/java/com/luxstay/hms/unit/RoomServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class RoomServiceImplTest {

        @Mock RoomRepository roomRepository;
        @Mock RoomTypeRepository roomTypeRepository;
        @Spy RoomMapper roomMapper = Mappers.getMapper(RoomMapper.class);
        @Spy RoomTypeMapper roomTypeMapper = Mappers.getMapper(RoomTypeMapper.class);
        @InjectMocks RoomServiceImpl roomService;

        @Test
        void create_whenNumberIsNew_savesVacantCleanRoom() {
            RoomType deluxe = new RoomType();
            deluxe.setId(1L);
            deluxe.setCode("DLX");
            deluxe.setName("Deluxe King");
            when(roomRepository.existsByNumber("613")).thenReturn(false);
            when(roomTypeRepository.findById(1L)).thenReturn(Optional.of(deluxe));
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            RoomResponse response = roomService.create(new CreateRoomRequest("613", 6, "SEA", 1L));

            assertThat(response.status()).isEqualTo(RoomStatus.VACANT_CLEAN);
            assertThat(response.roomTypeCode()).isEqualTo("DLX");
        }

        @Test
        void create_whenNumberExists_throwsBusinessRuleException() {
            when(roomRepository.existsByNumber("613")).thenReturn(true);

            assertThatThrownBy(() -> roomService.create(new CreateRoomRequest("613", 6, "SEA", 1L)))
                .isInstanceOf(BusinessRuleException.class);
            verify(roomRepository, never()).save(any());
        }

        @Test
        void create_whenRoomTypeMissing_throwsNotFound() {
            when(roomRepository.existsByNumber("613")).thenReturn(false);
            when(roomTypeRepository.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.create(new CreateRoomRequest("613", 6, "SEA", 9L)))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void updateStatus_changesStatus() {
            Room room = new Room();
            room.setNumber("101");
            room.setStatus(RoomStatus.VACANT_CLEAN);
            when(roomRepository.findById(1L)).thenReturn(Optional.of(room));

            RoomResponse response = roomService.updateStatus(1L, RoomStatus.OUT_OF_ORDER);

            assertThat(response.status()).isEqualTo(RoomStatus.OUT_OF_ORDER);
        }

        @Test
        void getAll_withStatus_usesStatusQuery() {
            when(roomRepository.findByStatus(eq(RoomStatus.OCCUPIED), any(Pageable.class)))
                .thenReturn(Page.empty());

            roomService.getAll(RoomStatus.OCCUPIED, PageRequest.of(0, 10));

            verify(roomRepository).findByStatus(eq(RoomStatus.OCCUPIED), any(Pageable.class));
        }

        @Test
        void getById_whenMissing_throwsNotFound() {
            when(roomRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
    ```
    - *Unit tests use Mockito fakes instead of a database, so they run in milliseconds.*
    - *Static imports: `assertThat`, `assertThatThrownBy` (AssertJ) and `when`, `verify`, `any`, `eq`, `never` (Mockito).*

14. Create the controller test `src/test/java/com/luxstay/hms/api/RoomControllerTest.java`:
    ```java
    @WebMvcTest({RoomController.class, RoomTypeController.class})
    @Import(SecurityConfig.class)
    class RoomControllerTest {

        @Autowired MockMvc mockMvc;
        @MockitoBean RoomService roomService;

        @Test
        void create_withValidBody_returns201WithLocation() throws Exception {
            when(roomService.create(any())).thenReturn(
                new RoomResponse(1L, "613", 6, "SEA", RoomStatus.VACANT_CLEAN, "DLX", "Deluxe King"));

            mockMvc.perform(post("/api/v1/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"number":"613","floor":6,"viewType":"SEA","roomTypeId":1}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/rooms/1"))
                .andExpect(jsonPath("$.status").value("VACANT_CLEAN"));
        }

        @Test
        void create_withBlankNumber_returns400WithFieldError() throws Exception {
            mockMvc.perform(post("/api/v1/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"number":"","floor":6,"roomTypeId":1}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.number").exists());
        }

        @Test
        void create_whenDuplicate_returns409() throws Exception {
            when(roomService.create(any())).thenThrow(new BusinessRuleException("Room 613 already exists"));

            mockMvc.perform(post("/api/v1/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"number":"613","floor":6,"roomTypeId":1}
                        """))
                .andExpect(status().isConflict());
        }

        @Test
        void getById_whenMissing_returns404() throws Exception {
            when(roomService.getById(99L)).thenThrow(new ResourceNotFoundException("Room", 99L));

            mockMvc.perform(get("/api/v1/rooms/99"))
                .andExpect(status().isNotFound());
        }

        @Test
        void updateStatus_withUnknownStatus_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/rooms/1/status")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"status":"SPARKLING"}
                        """))
                .andExpect(status().isBadRequest());
        }

        @Test
        void getRoomTypes_returns200() throws Exception {
            when(roomService.getRoomTypes()).thenReturn(List.of(
                new RoomTypeResponse(1L, "DLX", "Deluxe King", 2, new BigDecimal("450.00"), null)));

            mockMvc.perform(get("/api/v1/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("DLX"));
        }
    }
    ```
    - *`@WebMvcTest` starts only the web layer; `@MockitoBean` replaces the real service.*
    - *Static imports: `post`, `get`, `patch` (MockMvcRequestBuilders) and `status`, `header`, `jsonPath` (MockMvcResultMatchers).*

15. Create the integration test `src/test/java/com/luxstay/hms/integration/RoomRepositoryIT.java`:
    ```java
    @DataJpaTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
    class RoomRepositoryIT {

        @Autowired RoomRepository roomRepository;

        @Test
        void flywaySeed_creates60Rooms() {
            assertThat(roomRepository.count()).isEqualTo(60);
        }

        @Test
        void findByStatus_returnsOnlyMatchingRooms() {
            Page<Room> page = roomRepository.findByStatus(RoomStatus.VACANT_CLEAN, PageRequest.of(0, 100));

            assertThat(page.getContent())
                .isNotEmpty()
                .allMatch(room -> room.getStatus() == RoomStatus.VACANT_CLEAN);
        }
    }
    ```
    - *This runs Flyway against a real PostgreSQL started by Testcontainers, so Docker Desktop must be running.*
    - *Names ending in `IT` are integration tests; Step 6 makes Maven run them in `verify`.*

16. Run all tests: right-click `src/test/java` → **Run 'All Tests'**.
    - *All green. The first run downloads the PostgreSQL test image.*

17. Commit:
    ```bash
    git add src pom.xml
    git commit -m "feat: Step 5 – add rooms module with entity, service, controller and tests" -m "- GET/POST /api/v1/rooms, GET /{id}, PATCH /{id}/status, GET /api/v1/room-types
    - Duplicate number -> 409; unknown id -> 404; invalid body -> 400 with fieldErrors
    - Tests: Mockito unit, @WebMvcTest slice, Testcontainers repository IT"
    git push -u origin feature/step-5-rooms
    ```

18. Tick Step 5 in `README.md`, commit, then open the pull request and merge it:
    - *PR title: `feat: Step 5 – rooms module`.*
