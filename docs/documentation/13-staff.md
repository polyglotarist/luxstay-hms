# Step 13 Documentation — Staff and shifts

Employees belong to departments, are linked to their login, and work shifts. Housekeeping tasks (Step 14) are assigned to these employees.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-13-staff
   ```

2. Create `db/migration/V7__create_staff.sql`:
   ```sql
   CREATE TABLE employee (
       id           BIGSERIAL    PRIMARY KEY,
       first_name   VARCHAR(100) NOT NULL,
       last_name    VARCHAR(100) NOT NULL,
       department   VARCHAR(30)  NOT NULL,
       position     VARCHAR(100) NOT NULL,
       app_user_id  BIGINT       UNIQUE REFERENCES app_user (id),
       active       BOOLEAN      NOT NULL DEFAULT TRUE,
       created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version      BIGINT       NOT NULL DEFAULT 0
   );

   CREATE TABLE shift (
       id           BIGSERIAL    PRIMARY KEY,
       employee_id  BIGINT       NOT NULL REFERENCES employee (id),
       shift_date   DATE         NOT NULL,
       start_time   TIME         NOT NULL,
       end_time     TIME         NOT NULL,
       notes        VARCHAR(255),
       created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version      BIGINT       NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_shift_employee_date ON shift (employee_id, shift_date);
   ```
   - *`app_user_id` links an employee to their login; it's optional because not every employee uses the system.*

3. Create `enums/Department.java`:
   ```java
   package com.luxstay.hms.enums;

   public enum Department {
       MANAGEMENT, FRONT_OFFICE, HOUSEKEEPING, FOOD_AND_BEVERAGE, SPA, CONCIERGE
   }
   ```

4. Create the entities:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "employee")
   public class Employee extends BaseEntity {

       @Column(nullable = false, length = 100)
       private String firstName;

       @Column(nullable = false, length = 100)
       private String lastName;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 30)
       private Department department;

       @Column(nullable = false, length = 100)
       private String position;

       @OneToOne(fetch = FetchType.LAZY)
       @JoinColumn(name = "app_user_id")
       private AppUser appUser;

       @Column(nullable = false)
       private boolean active = true;
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "shift")
   public class Shift extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "employee_id")
       private Employee employee;

       @Column(nullable = false)
       private LocalDate shiftDate;

       @Column(nullable = false)
       private LocalTime startTime;

       @Column(nullable = false)
       private LocalTime endTime;

       private String notes;
   }
   ```
   - *The field is `shiftDate`, not `date`, because `date` is a reserved word in SQL.*

5. Create the repositories:
   ```java
   package com.luxstay.hms.repository;

   public interface EmployeeRepository extends JpaRepository<Employee, Long> {

       Optional<Employee> findByAppUserEmail(String email);

       boolean existsByAppUserId(Long appUserId);

       Page<Employee> findByDepartment(Department department, Pageable pageable);
   }
   ```
   ```java
   package com.luxstay.hms.repository;

   public interface ShiftRepository extends JpaRepository<Shift, Long> {

       @EntityGraph(attributePaths = "employee")
       List<Shift> findByShiftDateBetweenOrderByShiftDateAscStartTimeAsc(LocalDate from, LocalDate to);

       List<Shift> findByEmployeeIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(Long employeeId, LocalDate from, LocalDate to);

       @Query("""
           select case when count(s) > 0 then true else false end
           from Shift s
           where s.employee.id = :employeeId and s.shiftDate = :day
             and s.startTime < :endTime and s.endTime > :startTime
           """)
       boolean existsOverlapping(@Param("employeeId") Long employeeId, @Param("day") LocalDate day,
                                 @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime);
   }
   ```
   - *`findByAppUserEmail` follows the link `employee → appUser → email`. It finds "me" from the logged-in user.*

6. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record EmployeeRequest(
       @NotBlank @Size(max = 100) @Schema(example = "Sofia") String firstName,
       @NotBlank @Size(max = 100) @Schema(example = "Marin") String lastName,
       @NotNull @Schema(example = "HOUSEKEEPING") Department department,
       @NotBlank @Size(max = 100) @Schema(example = "Room Attendant") String position,
       @Schema(example = "4") Long appUserId) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record ShiftRequest(
       @NotNull @Schema(example = "1") Long employeeId,
       @NotNull @FutureOrPresent @Schema(example = "2026-12-20") LocalDate date,
       @NotNull @Schema(example = "07:00") LocalTime startTime,
       @NotNull @Schema(example = "15:00") LocalTime endTime,
       @Size(max = 255) String notes) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record EmployeeResponse(Long id, String firstName, String lastName, Department department,
                                  String position, String email, boolean active) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record ShiftResponse(Long id, Long employeeId, String employeeName, LocalDate date,
                               LocalTime startTime, LocalTime endTime, String notes) {
   }
   ```

7. Create `mapper/StaffMapper.java`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface StaffMapper {

       @Mapping(target = "email", source = "appUser.email")
       EmployeeResponse toResponse(Employee employee);

       @Mapping(target = "employeeId", source = "employee.id")
       @Mapping(target = "date", source = "shiftDate")
       @Mapping(target = "employeeName", expression = "java(s.getEmployee().getFirstName() + \" \" + s.getEmployee().getLastName())")
       ShiftResponse toResponse(Shift s);
   }
   ```

8. Create `service/StaffService.java`:
   ```java
   package com.luxstay.hms.service;

   public interface StaffService {
       EmployeeResponse create(EmployeeRequest request);
       EmployeeResponse update(Long id, EmployeeRequest request);
       EmployeeResponse deactivate(Long id);
       Page<EmployeeResponse> getAll(Department department, Pageable pageable);
       ShiftResponse addShift(ShiftRequest request);
       List<ShiftResponse> getRoster(LocalDate from, LocalDate to);
       List<ShiftResponse> getMyShifts(String email, LocalDate from, LocalDate to);
   }
   ```

9. Create `service/impl/StaffServiceImpl.java`:
   ```java
   package com.luxstay.hms.service.impl;

   @Slf4j
   @Service
   @RequiredArgsConstructor
   @Transactional(readOnly = true)
   public class StaffServiceImpl implements StaffService {

       private final EmployeeRepository employeeRepository;
       private final ShiftRepository shiftRepository;
       private final AppUserRepository userRepository;
       private final StaffMapper staffMapper;

       @Override
       @Transactional
       public EmployeeResponse create(EmployeeRequest request) {
           Employee employee = new Employee();
           apply(employee, request);
           Employee saved = employeeRepository.save(employee);
           log.info("Employee {} created in {}", saved.getId(), saved.getDepartment());
           return staffMapper.toResponse(saved);
       }

       @Override
       @Transactional
       public EmployeeResponse update(Long id, EmployeeRequest request) {
           Employee employee = findEmployee(id);
           apply(employee, request);
           return staffMapper.toResponse(employee);
       }

       @Override
       @Transactional
       public EmployeeResponse deactivate(Long id) {
           Employee employee = findEmployee(id);
           employee.setActive(false);
           log.info("Employee {} deactivated", id);
           return staffMapper.toResponse(employee);
       }

       @Override
       public Page<EmployeeResponse> getAll(Department department, Pageable pageable) {
           Page<Employee> page = department == null
               ? employeeRepository.findAll(pageable)
               : employeeRepository.findByDepartment(department, pageable);
           return page.map(staffMapper::toResponse);
       }

       @Override
       @Transactional
       public ShiftResponse addShift(ShiftRequest request) {
           if (!request.endTime().isAfter(request.startTime())) {
               throw new BusinessRuleException("A shift must end after it starts");
           }
           Employee employee = findEmployee(request.employeeId());
           if (!employee.isActive()) {
               throw new BusinessRuleException("Inactive employees can't be rostered");
           }
           if (shiftRepository.existsOverlapping(employee.getId(), request.date(),
                   request.startTime(), request.endTime())) {
               throw new BusinessRuleException("The employee already has an overlapping shift");
           }
           Shift shift = new Shift();
           shift.setEmployee(employee);
           shift.setShiftDate(request.date());
           shift.setStartTime(request.startTime());
           shift.setEndTime(request.endTime());
           shift.setNotes(request.notes());
           return staffMapper.toResponse(shiftRepository.save(shift));
       }

       @Override
       public List<ShiftResponse> getRoster(LocalDate from, LocalDate to) {
           return shiftRepository.findByShiftDateBetweenOrderByShiftDateAscStartTimeAsc(from, to)
               .stream().map(staffMapper::toResponse).toList();
       }

       @Override
       public List<ShiftResponse> getMyShifts(String email, LocalDate from, LocalDate to) {
           Employee me = employeeRepository.findByAppUserEmail(email)
               .orElseThrow(() -> new ResourceNotFoundException("Employee for user", email));
           return shiftRepository.findByEmployeeIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(me.getId(), from, to)
               .stream().map(staffMapper::toResponse).toList();
       }

       private void apply(Employee employee, EmployeeRequest request) {
           employee.setFirstName(request.firstName());
           employee.setLastName(request.lastName());
           employee.setDepartment(request.department());
           employee.setPosition(request.position());
           if (request.appUserId() != null) {
               employee.setAppUser(userRepository.findById(request.appUserId())
                   .orElseThrow(() -> new ResourceNotFoundException("User", request.appUserId())));
           }
       }

       private Employee findEmployee(Long id) {
           return employeeRepository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
       }
   }
   ```
   - *Employees are deactivated, never deleted, so their history stays intact.*

10. Create `controller/StaffController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/staff")
    @RequiredArgsConstructor
    @Tag(name = "Staff", description = "Employees and shift roster")
    public class StaffController {

        private final StaffService staffService;

        @PostMapping("/employees")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Add an employee")
        public EmployeeResponse create(@Valid @RequestBody EmployeeRequest request) {
            return staffService.create(request);
        }

        @PutMapping("/employees/{id}")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "Update an employee")
        public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
            return staffService.update(id, request);
        }

        @PatchMapping("/employees/{id}/deactivate")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "Deactivate an employee who has left")
        public EmployeeResponse deactivate(@PathVariable Long id) {
            return staffService.deactivate(id);
        }

        @GetMapping("/employees")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "List employees, optionally by department")
        public Page<EmployeeResponse> getAll(@RequestParam(required = false) Department department,
                                             @ParameterObject Pageable pageable) {
            return staffService.getAll(department, pageable);
        }

        @PostMapping("/shifts")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Add a shift to the roster")
        public ShiftResponse addShift(@Valid @RequestBody ShiftRequest request) {
            return staffService.addShift(request);
        }

        @GetMapping("/shifts")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
        @Operation(summary = "Roster for a date range")
        public List<ShiftResponse> roster(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
            return staffService.getRoster(from, to);
        }

        @GetMapping("/me/shifts")
        @PreAuthorize("!hasRole('GUEST')")
        @Operation(summary = "My own shifts")
        public List<ShiftResponse> myShifts(Authentication authentication,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
            return staffService.getMyShifts(authentication.getName(), from, to);
        }
    }
    ```
    - *`Authentication` is filled in by Spring Security; `getName()` is the email stored in the token.*

11. Extend `DevDataSeeder` so each staff login also has an employee record. Add `private final EmployeeRepository employeeRepository;` and, after the user loop in `run`:
    ```java
    userRepository.findAll().stream()
        .filter(u -> u.getRole() != Role.GUEST && !employeeRepository.existsByAppUserId(u.getId()))
        .forEach(u -> {
            Employee e = new Employee();
            e.setFirstName(u.getRole().name().charAt(0) + u.getRole().name().substring(1).toLowerCase());
            e.setLastName("Tester");
            e.setDepartment(switch (u.getRole()) {
                case HOUSEKEEPER -> Department.HOUSEKEEPING;
                case RECEPTIONIST -> Department.FRONT_OFFICE;
                case FNB_STAFF -> Department.FOOD_AND_BEVERAGE;
                case SPA_STAFF -> Department.SPA;
                case CONCIERGE -> Department.CONCIERGE;
                default -> Department.MANAGEMENT;
            });
            e.setPosition(u.getRole().name());
            e.setAppUser(u);
            employeeRepository.save(e);
        });
    ```

12. Create `src/test/java/com/luxstay/hms/unit/StaffServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class StaffServiceImplTest {

        @Mock EmployeeRepository employeeRepository;
        @Mock ShiftRepository shiftRepository;
        @Mock AppUserRepository userRepository;
        @Spy StaffMapper staffMapper = Mappers.getMapper(StaffMapper.class);
        @InjectMocks StaffServiceImpl service;

        private Employee sofia() {
            Employee e = new Employee();
            e.setId(1L);
            e.setFirstName("Sofia");
            e.setLastName("Marin");
            e.setDepartment(Department.HOUSEKEEPING);
            return e;
        }

        private ShiftRequest shift(String start, String end) {
            return new ShiftRequest(1L, LocalDate.now().plusDays(1), LocalTime.parse(start), LocalTime.parse(end), null);
        }

        @Test
        void addShift_valid_saves() {
            when(employeeRepository.findById(1L)).thenReturn(Optional.of(sofia()));
            when(shiftRepository.existsOverlapping(any(), any(), any(), any())).thenReturn(false);
            when(shiftRepository.save(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));

            ShiftResponse response = service.addShift(shift("07:00", "15:00"));

            assertThat(response.employeeName()).isEqualTo("Sofia Marin");
        }

        @Test
        void addShift_endingBeforeStart_throws() {
            assertThatThrownBy(() -> service.addShift(shift("15:00", "07:00")))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void addShift_overlapping_throws() {
            when(employeeRepository.findById(1L)).thenReturn(Optional.of(sofia()));
            when(shiftRepository.existsOverlapping(any(), any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.addShift(shift("07:00", "15:00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("overlapping");
        }

        @Test
        void addShift_forInactiveEmployee_throws() {
            Employee inactive = sofia();
            inactive.setActive(false);
            when(employeeRepository.findById(1L)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> service.addShift(shift("07:00", "15:00")))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void getMyShifts_withoutEmployeeRecord_throwsNotFound() {
            when(employeeRepository.findByAppUserEmail("guest@luxstay.test")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMyShifts("guest@luxstay.test", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
    ```

13. Commit, tick Step 13, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 13 – add employees and shift roster" -m "- V7 creates employee (optional link to app_user) and shift
    - Overlapping shifts for one employee -> 409; employees are deactivated, not deleted
    - GET /api/v1/staff/me/shifts uses the logged-in user's email; dev seeder links each staff login to an employee"
    git push -u origin feature/step-13-staff
    ```
    - *PR title: `feat: Step 13 – staff and shifts`.*
