# Cheat Sheet 14 — Housekeeping

Cleaning tasks appear automatically when guests check out and every morning for occupied rooms. Managers assign them, housekeepers work them, and an inspected room becomes sellable again.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-14-housekeeping
   ```

2. Create `db/migration/V8__create_housekeeping_task.sql`:
   ```sql
   CREATE TABLE housekeeping_task (
       id              BIGSERIAL    PRIMARY KEY,
       room_id         BIGINT       NOT NULL REFERENCES room (id),
       type            VARCHAR(20)  NOT NULL,
       priority        VARCHAR(10)  NOT NULL,
       status          VARCHAR(20)  NOT NULL,
       assigned_to_id  BIGINT       REFERENCES employee (id),
       scheduled_for   DATE         NOT NULL,
       notes           VARCHAR(500),
       completed_at    TIMESTAMPTZ,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version         BIGINT       NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_task_date_status ON housekeeping_task (scheduled_for, status);
   CREATE INDEX idx_task_assignee ON housekeeping_task (assigned_to_id);
   ```

3. Create the enums:
   ```java
   package com.luxstay.hms.enums;

   public enum TaskType {
       DEPARTURE_CLEAN, STAYOVER, TURNDOWN, MAINTENANCE
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   public enum TaskPriority {
       LOW, NORMAL, HIGH
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   public enum TaskStatus {
       PENDING, IN_PROGRESS, DONE, INSPECTED;

       public boolean canMoveTo(TaskStatus next) {
           return next.ordinal() == this.ordinal() + 1;
       }
   }
   ```
   - *`canMoveTo` allows only one step forward: PENDING → IN_PROGRESS → DONE → INSPECTED.*

4. Create `entity/HousekeepingTask.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "housekeeping_task")
   public class HousekeepingTask extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "room_id")
       private Room room;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private TaskType type;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 10)
       private TaskPriority priority;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private TaskStatus status;

       @ManyToOne(fetch = FetchType.LAZY)
       @JoinColumn(name = "assigned_to_id")
       private Employee assignedTo;

       @Column(nullable = false)
       private LocalDate scheduledFor;

       @Column(length = 500)
       private String notes;

       private Instant completedAt;
   }
   ```

5. Create `repository/HousekeepingTaskRepository.java`:
   ```java
   package com.luxstay.hms.repository;

   public interface HousekeepingTaskRepository extends JpaRepository<HousekeepingTask, Long> {

       @EntityGraph(attributePaths = {"room", "assignedTo"})
       Page<HousekeepingTask> findByScheduledFor(LocalDate date, Pageable pageable);

       @EntityGraph(attributePaths = {"room", "assignedTo"})
       Page<HousekeepingTask> findByScheduledForAndStatus(LocalDate date, TaskStatus status, Pageable pageable);

       @EntityGraph(attributePaths = {"room", "assignedTo"})
       List<HousekeepingTask> findByAssignedToAppUserEmailAndStatusIn(String email, Collection<TaskStatus> statuses);

       boolean existsByRoomIdAndTypeAndScheduledFor(Long roomId, TaskType type, LocalDate date);

       @Override
       @EntityGraph(attributePaths = {"room", "assignedTo"})
       Optional<HousekeepingTask> findById(Long id);
   }
   ```
   - *Add to `RoomRepository`: `List<Room> findByStatus(RoomStatus status);`. The scheduler uses it.*

6. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record AssignTaskRequest(@NotNull @Schema(example = "4") Long employeeId) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record MaintenanceRequest(
       @NotNull @Schema(example = "1") Long roomId,
       @NotBlank @Size(max = 500) @Schema(example = "Air conditioning leaking") String notes) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record TaskResponse(
       Long id,
       Long roomId,
       String roomNumber,
       TaskType type,
       TaskPriority priority,
       TaskStatus status,
       Long assignedToId,
       String assignedToName,
       LocalDate scheduledFor,
       String notes,
       Instant completedAt) {
   }
   ```

7. Create `mapper/TaskMapper.java`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface TaskMapper {

       @Mapping(target = "roomId", source = "room.id")
       @Mapping(target = "roomNumber", source = "room.number")
       @Mapping(target = "assignedToId", source = "assignedTo.id")
       @Mapping(target = "assignedToName", expression = "java(t.getAssignedTo() == null ? null : t.getAssignedTo().getFirstName() + \" \" + t.getAssignedTo().getLastName())")
       TaskResponse toResponse(HousekeepingTask t);
   }
   ```

8. Create `service/HousekeepingService.java`:
   ```java
   package com.luxstay.hms.service;

   public interface HousekeepingService {
       TaskResponse createTask(Long roomId, TaskType type, TaskPriority priority, LocalDate date, String notes);
       Page<TaskResponse> getTasks(LocalDate date, TaskStatus status, Pageable pageable);
       List<TaskResponse> getMyOpenTasks(String email);
       TaskResponse assign(Long taskId, Long employeeId);
       TaskResponse start(Long taskId);
       TaskResponse complete(Long taskId);
       TaskResponse inspect(Long taskId);
       TaskResponse reportMaintenance(Long roomId, String notes);
   }
   ```

9. Create `service/impl/HousekeepingServiceImpl.java`:
   ```java
   package com.luxstay.hms.service.impl;

   @Slf4j
   @Service
   @RequiredArgsConstructor
   @Transactional(readOnly = true)
   public class HousekeepingServiceImpl implements HousekeepingService {

       private final HousekeepingTaskRepository taskRepository;
       private final RoomRepository roomRepository;
       private final EmployeeRepository employeeRepository;
       private final TaskMapper taskMapper;
       private final Clock clock;

       @Override
       @Transactional
       public TaskResponse createTask(Long roomId, TaskType type, TaskPriority priority, LocalDate date, String notes) {
           Room room = roomRepository.findById(roomId)
               .orElseThrow(() -> new ResourceNotFoundException("Room", roomId));
           HousekeepingTask task = new HousekeepingTask();
           task.setRoom(room);
           task.setType(type);
           task.setPriority(priority);
           task.setStatus(TaskStatus.PENDING);
           task.setScheduledFor(date);
           task.setNotes(notes);
           HousekeepingTask saved = taskRepository.save(task);
           log.info("Housekeeping task {} {} created for room {}", saved.getId(), type, room.getNumber());
           return taskMapper.toResponse(saved);
       }

       @Override
       public Page<TaskResponse> getTasks(LocalDate date, TaskStatus status, Pageable pageable) {
           LocalDate day = date != null ? date : LocalDate.now(clock);
           Page<HousekeepingTask> page = status == null
               ? taskRepository.findByScheduledFor(day, pageable)
               : taskRepository.findByScheduledForAndStatus(day, status, pageable);
           return page.map(taskMapper::toResponse);
       }

       @Override
       public List<TaskResponse> getMyOpenTasks(String email) {
           return taskRepository.findByAssignedToAppUserEmailAndStatusIn(email,
                   List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS))
               .stream()
               .sorted(Comparator.comparing(HousekeepingTask::getPriority).reversed())
               .map(taskMapper::toResponse)
               .toList();
       }

       @Override
       @Transactional
       public TaskResponse assign(Long taskId, Long employeeId) {
           HousekeepingTask task = findTask(taskId);
           Employee employee = employeeRepository.findById(employeeId)
               .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
           if (!employee.isActive() || employee.getDepartment() != Department.HOUSEKEEPING) {
               throw new BusinessRuleException("Tasks can only be assigned to active housekeeping staff");
           }
           task.setAssignedTo(employee);
           return taskMapper.toResponse(task);
       }

       @Override
       @Transactional
       public TaskResponse start(Long taskId) {
           return moveTo(findTask(taskId), TaskStatus.IN_PROGRESS);
       }

       @Override
       @Transactional
       public TaskResponse complete(Long taskId) {
           HousekeepingTask task = findTask(taskId);
           task.setCompletedAt(clock.instant());
           return moveTo(task, TaskStatus.DONE);
       }

       @Override
       @Transactional
       public TaskResponse inspect(Long taskId) {
           HousekeepingTask task = findTask(taskId);
           TaskResponse response = moveTo(task, TaskStatus.INSPECTED);
           Room room = task.getRoom();
           if (room.getStatus() != RoomStatus.OCCUPIED) {
               room.setStatus(RoomStatus.VACANT_CLEAN);
               log.info("Room {} inspected and ready to sell", room.getNumber());
           }
           return response;
       }

       @Override
       @Transactional
       public TaskResponse reportMaintenance(Long roomId, String notes) {
           Room room = roomRepository.findById(roomId)
               .orElseThrow(() -> new ResourceNotFoundException("Room", roomId));
           if (room.getStatus() != RoomStatus.OCCUPIED) {
               room.setStatus(RoomStatus.OUT_OF_ORDER);
           }
           log.warn("Maintenance reported for room {}", room.getNumber());
           return createTask(roomId, TaskType.MAINTENANCE, TaskPriority.HIGH, LocalDate.now(clock), notes);
       }

       private TaskResponse moveTo(HousekeepingTask task, TaskStatus next) {
           if (!task.getStatus().canMoveTo(next)) {
               throw new BusinessRuleException("Task " + task.getId() + " can't go from " + task.getStatus() + " to " + next);
           }
           task.setStatus(next);
           log.info("Task {} for room {} is now {}", task.getId(), task.getRoom().getNumber(), next);
           return taskMapper.toResponse(task);
       }

       private HousekeepingTask findTask(Long id) {
           return taskRepository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Housekeeping task", id));
       }
   }
   ```
   - *Only an **inspected** room goes back to `VACANT_CLEAN`, which is the status check-in requires (Step 12).*
   - *A broken room is taken out of order immediately, unless a guest is in it.*

10. Create `event/HousekeepingEventListener.java`:
    ```java
    package com.luxstay.hms.event;

    @Component
    @RequiredArgsConstructor
    public class HousekeepingEventListener {

        private final HousekeepingService housekeepingService;
        private final Clock clock;

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void onGuestCheckedOut(GuestCheckedOutEvent event) {
            housekeepingService.createTask(event.roomId(), TaskType.DEPARTURE_CLEAN, TaskPriority.HIGH,
                LocalDate.now(clock), "Guest departed");
        }
    }
    ```
    - *`BEFORE_COMMIT` runs inside the check-out transaction, so check-out and its cleaning task succeed or fail together.*

11. Create the daily scheduler:
    ```java
    package com.luxstay.hms.config;

    @Configuration
    @EnableScheduling
    public class SchedulingConfig {
    }
    ```
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Component
    @RequiredArgsConstructor
    public class HousekeepingScheduler {

        private final RoomRepository roomRepository;
        private final HousekeepingTaskRepository taskRepository;
        private final HousekeepingService housekeepingService;
        private final Clock clock;

        @Scheduled(cron = "0 0 8 * * *")
        public void createStayoverTasks() {
            createForOccupiedRooms(TaskType.STAYOVER, TaskPriority.NORMAL);
        }

        @Scheduled(cron = "0 0 18 * * *")
        public void createTurndownTasks() {
            createForOccupiedRooms(TaskType.TURNDOWN, TaskPriority.LOW);
        }

        void createForOccupiedRooms(TaskType type, TaskPriority priority) {
            LocalDate today = LocalDate.now(clock);
            int created = 0;
            for (Room room : roomRepository.findByStatus(RoomStatus.OCCUPIED)) {
                if (!taskRepository.existsByRoomIdAndTypeAndScheduledFor(room.getId(), type, today)) {
                    housekeepingService.createTask(room.getId(), type, priority, today, null);
                    created++;
                }
            }
            log.info("Scheduler created {} {} tasks", created, type);
        }
    }
    ```
    - *Cron `0 0 8 * * *` means every day at 08:00. The `exists…` check stops duplicates if the app restarts.*
    - *Each `createTask` call runs in its own transaction (the one on the service method).*

12. Create `controller/HousekeepingController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/housekeeping")
    @RequiredArgsConstructor
    @Tag(name = "Housekeeping", description = "Cleaning tasks, inspections and maintenance")
    public class HousekeepingController {

        private final HousekeepingService housekeepingService;

        @GetMapping("/tasks")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HOUSEKEEPER')")
        @Operation(summary = "Tasks for a day (default today), optionally by status")
        public Page<TaskResponse> getTasks(
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                @RequestParam(required = false) TaskStatus status,
                @ParameterObject Pageable pageable) {
            return housekeepingService.getTasks(date, status, pageable);
        }

        @GetMapping("/tasks/mine")
        @PreAuthorize("hasRole('HOUSEKEEPER')")
        @Operation(summary = "My open tasks, highest priority first")
        public List<TaskResponse> myTasks(Authentication authentication) {
            return housekeepingService.getMyOpenTasks(authentication.getName());
        }

        @PatchMapping("/tasks/{id}/assign")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "Assign a task to a housekeeper")
        public TaskResponse assign(@PathVariable Long id, @Valid @RequestBody AssignTaskRequest request) {
            return housekeepingService.assign(id, request.employeeId());
        }

        @PatchMapping("/tasks/{id}/start")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HOUSEKEEPER')")
        @Operation(summary = "Start a task")
        public TaskResponse start(@PathVariable Long id) {
            return housekeepingService.start(id);
        }

        @PatchMapping("/tasks/{id}/complete")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HOUSEKEEPER')")
        @Operation(summary = "Mark a task done")
        public TaskResponse complete(@PathVariable Long id) {
            return housekeepingService.complete(id);
        }

        @PatchMapping("/tasks/{id}/inspect")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "Inspect a finished task; the room becomes VACANT_CLEAN")
        public TaskResponse inspect(@PathVariable Long id) {
            return housekeepingService.inspect(id);
        }

        @PostMapping("/maintenance")
        @PreAuthorize("!hasRole('GUEST')")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Report a maintenance issue; takes the room out of order")
        public TaskResponse reportMaintenance(@Valid @RequestBody MaintenanceRequest request) {
            return housekeepingService.reportMaintenance(request.roomId(), request.notes());
        }
    }
    ```

13. Try the full cycle in Swagger:
    1. Check a guest out (Step 12) → `GET /api/v1/housekeeping/tasks` shows a HIGH `DEPARTURE_CLEAN` task
    2. As manager: `assign` it to the housekeeper's employee id (see `GET /api/v1/staff/employees?department=HOUSEKEEPING`)
    3. As `housekeeper@luxstay.test`: `GET …/tasks/mine` → `start` → `complete`
    4. As manager: `inspect` → the room is `VACANT_CLEAN` again
    - *`inspect` straight from `PENDING` → **409**, because steps can't be skipped.*

14. Create `src/test/java/com/luxstay/hms/unit/HousekeepingServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class HousekeepingServiceImplTest {

        @Mock HousekeepingTaskRepository taskRepository;
        @Mock RoomRepository roomRepository;
        @Mock EmployeeRepository employeeRepository;
        HousekeepingServiceImpl service;
        Room room;
        HousekeepingTask task;

        @BeforeEach
        void setUp() {
            Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
            service = new HousekeepingServiceImpl(taskRepository, roomRepository, employeeRepository,
                Mappers.getMapper(TaskMapper.class), clock);
            room = new Room();
            room.setId(101L);
            room.setNumber("101");
            room.setStatus(RoomStatus.VACANT_DIRTY);
            task = new HousekeepingTask();
            task.setId(1L);
            task.setRoom(room);
            task.setType(TaskType.DEPARTURE_CLEAN);
            task.setStatus(TaskStatus.PENDING);
        }

        @Test
        void fullCycle_endsWithCleanRoom() {
            when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

            service.start(1L);
            service.complete(1L);
            TaskResponse response = service.inspect(1L);

            assertThat(response.status()).isEqualTo(TaskStatus.INSPECTED);
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(room.getStatus()).isEqualTo(RoomStatus.VACANT_CLEAN);
        }

        @Test
        void skippingSteps_isRejected() {
            when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

            assertThatThrownBy(() -> service.inspect(1L)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void assign_toNonHousekeeper_isRejected() {
            Employee chef = new Employee();
            chef.setDepartment(Department.FOOD_AND_BEVERAGE);
            when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
            when(employeeRepository.findById(9L)).thenReturn(Optional.of(chef));

            assertThatThrownBy(() -> service.assign(1L, 9L)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void reportMaintenance_takesRoomOutOfOrder() {
            when(roomRepository.findById(101L)).thenReturn(Optional.of(room));
            when(taskRepository.save(any(HousekeepingTask.class))).thenAnswer(inv -> inv.getArgument(0));

            TaskResponse response = service.reportMaintenance(101L, "Leak");

            assertThat(room.getStatus()).isEqualTo(RoomStatus.OUT_OF_ORDER);
            assertThat(response.type()).isEqualTo(TaskType.MAINTENANCE);
            assertThat(response.priority()).isEqualTo(TaskPriority.HIGH);
        }
    }
    ```

15. Create `src/test/java/com/luxstay/hms/unit/HousekeepingEventListenerTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class HousekeepingEventListenerTest {

        @Mock HousekeepingService housekeepingService;

        @Test
        void checkOut_createsHighPriorityDepartureClean() {
            Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
            HousekeepingEventListener listener = new HousekeepingEventListener(housekeepingService, clock);

            listener.onGuestCheckedOut(new GuestCheckedOutEvent(5L, 101L));

            verify(housekeepingService).createTask(101L, TaskType.DEPARTURE_CLEAN, TaskPriority.HIGH,
                LocalDate.of(2026, 10, 1), "Guest departed");
        }
    }
    ```

16. Commit, tick Step 14, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 14 – add housekeeping tasks, inspections and maintenance" -m "- V8 creates housekeeping_task; PENDING -> IN_PROGRESS -> DONE -> INSPECTED, no skipping
    - GuestCheckedOutEvent creates a HIGH DEPARTURE_CLEAN task in the same transaction
    - Daily 08:00 STAYOVER and 18:00 TURNDOWN tasks for occupied rooms (@Scheduled)
    - Inspection sets the room VACANT_CLEAN; maintenance report sets OUT_OF_ORDER"
    git push -u origin feature/step-14-housekeeping
    ```
    - *PR title: `feat: Step 14 – housekeeping`.*
