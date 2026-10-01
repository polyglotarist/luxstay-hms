# Step 9 Documentation — Guest profiles (CRM)

Store guests with their preferences, search them, and anonymise them on request (GDPR-style "right to be forgotten").

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-9-guests
   ```

2. Create `db/migration/V4__create_guest.sql`:
   ```sql
   CREATE TABLE guest (
       id                BIGSERIAL    PRIMARY KEY,
       first_name        VARCHAR(100) NOT NULL,
       last_name         VARCHAR(100) NOT NULL,
       email             VARCHAR(255) NOT NULL UNIQUE,
       phone             VARCHAR(30),
       nationality       VARCHAR(2),
       date_of_birth     DATE,
       vip               BOOLEAN      NOT NULL DEFAULT FALSE,
       preferred_pillow  VARCHAR(30),
       allergies         VARCHAR(255),
       special_requests  VARCHAR(1000),
       anonymised        BOOLEAN      NOT NULL DEFAULT FALSE,
       created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version           BIGINT       NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_guest_last_name ON guest (lower(last_name));
   ```
   - *`nationality` is a 2-letter country code, e.g. `GB`.*
   - *The index makes case-insensitive name search fast.*

3. Create `entity/Guest.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "guest")
   public class Guest extends BaseEntity {

       @Column(nullable = false, length = 100)
       private String firstName;

       @Column(nullable = false, length = 100)
       private String lastName;

       @Column(nullable = false, unique = true)
       private String email;

       @Column(length = 30)
       private String phone;

       @Column(length = 2)
       private String nationality;

       private LocalDate dateOfBirth;

       @Column(nullable = false)
       private boolean vip;

       @Column(length = 30)
       private String preferredPillow;

       private String allergies;

       @Column(length = 1000)
       private String specialRequests;

       @Column(nullable = false)
       private boolean anonymised;
   }
   ```

4. Create `repository/GuestRepository.java`:
   ```java
   package com.luxstay.hms.repository;

   public interface GuestRepository extends JpaRepository<Guest, Long> {

       boolean existsByEmailIgnoreCase(String email);

       Optional<Guest> findByEmailIgnoreCase(String email);

       @Query("""
           select g from Guest g
           where lower(g.lastName) like lower(concat('%', :q, '%'))
              or lower(g.firstName) like lower(concat('%', :q, '%'))
              or lower(g.email) like lower(concat('%', :q, '%'))
           """)
       Page<Guest> search(@Param("q") String query, Pageable pageable);
   }
   ```
   - *`@Query` holds JPQL: queries written against entity and field names, not tables.*

5. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record GuestRequest(
       @NotBlank @Size(max = 100) @Schema(example = "Amelia") String firstName,
       @NotBlank @Size(max = 100) @Schema(example = "Hart") String lastName,
       @NotBlank @Email @Schema(example = "amelia.hart@example.com") String email,
       @Size(max = 30) @Schema(example = "+44 20 7946 0000") String phone,
       @Size(min = 2, max = 2) @Schema(example = "GB") String nationality,
       @Past @Schema(example = "1985-04-12") LocalDate dateOfBirth,
       boolean vip,
       @Size(max = 30) @Schema(example = "FEATHER") String preferredPillow,
       @Size(max = 255) @Schema(example = "Shellfish") String allergies,
       @Size(max = 1000) @Schema(example = "High floor, away from the lift") String specialRequests) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record GuestResponse(
       Long id,
       String firstName,
       String lastName,
       String email,
       String phone,
       String nationality,
       LocalDate dateOfBirth,
       boolean vip,
       String preferredPillow,
       String allergies,
       String specialRequests,
       boolean anonymised) {
   }
   ```
   - *One request record is used for both create (POST) and update (PUT).*

6. Create `mapper/GuestMapper.java`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
   public interface GuestMapper {

       GuestResponse toResponse(Guest guest);

       Guest toEntity(GuestRequest request);

       void update(@MappingTarget Guest guest, GuestRequest request);
   }
   ```
   - *`@MappingTarget` copies the request onto an existing entity, for updates.*
   - *`ReportingPolicy.IGNORE` silences warnings about `id`, `version` and the other fields a request never sets.*

7. Create `service/GuestService.java`:
   ```java
   package com.luxstay.hms.service;

   public interface GuestService {
       GuestResponse create(GuestRequest request);
       GuestResponse getById(Long id);
       Page<GuestResponse> search(String query, Pageable pageable);
       GuestResponse update(Long id, GuestRequest request);
       void anonymise(Long id);
   }
   ```

8. Create `service/impl/GuestServiceImpl.java`:
   ```java
   package com.luxstay.hms.service.impl;

   @Slf4j
   @Service
   @RequiredArgsConstructor
   @Transactional(readOnly = true)
   public class GuestServiceImpl implements GuestService {

       private final GuestRepository guestRepository;
       private final GuestMapper guestMapper;

       @Override
       @Transactional
       public GuestResponse create(GuestRequest request) {
           if (guestRepository.existsByEmailIgnoreCase(request.email())) {
               throw new BusinessRuleException("A guest with this email already exists");
           }
           Guest saved = guestRepository.save(guestMapper.toEntity(request));
           log.info("Guest {} created", saved.getId());
           return guestMapper.toResponse(saved);
       }

       @Override
       public GuestResponse getById(Long id) {
           return guestMapper.toResponse(findGuest(id));
       }

       @Override
       public Page<GuestResponse> search(String query, Pageable pageable) {
           Page<Guest> guests = (query == null || query.isBlank())
               ? guestRepository.findAll(pageable)
               : guestRepository.search(query.trim(), pageable);
           return guests.map(guestMapper::toResponse);
       }

       @Override
       @Transactional
       public GuestResponse update(Long id, GuestRequest request) {
           Guest guest = findGuest(id);
           if (guest.isAnonymised()) {
               throw new BusinessRuleException("Anonymised guests cannot be edited");
           }
           if (!guest.getEmail().equalsIgnoreCase(request.email())
                   && guestRepository.existsByEmailIgnoreCase(request.email())) {
               throw new BusinessRuleException("A guest with this email already exists");
           }
           guestMapper.update(guest, request);
           log.info("Guest {} updated", id);
           return guestMapper.toResponse(guest);
       }

       @Override
       @Transactional
       public void anonymise(Long id) {
           Guest guest = findGuest(id);
           guest.setFirstName("Anonymised");
           guest.setLastName("Guest");
           guest.setEmail("anonymised-" + id + "@deleted.invalid");
           guest.setPhone(null);
           guest.setDateOfBirth(null);
           guest.setAllergies(null);
           guest.setSpecialRequests(null);
           guest.setPreferredPillow(null);
           guest.setAnonymised(true);
           log.info("Guest {} anonymised", id);
       }

       private Guest findGuest(Long id) {
           return guestRepository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Guest", id));
       }
   }
   ```
   - *Anonymising keeps the row, so past bookings and invoices stay valid, but removes the personal data.*
   - *Logs use the guest id, never the email.*

9. Create `controller/GuestController.java`:
   ```java
   package com.luxstay.hms.controller;

   @RestController
   @RequestMapping("/api/v1/guests")
   @RequiredArgsConstructor
   @Tag(name = "Guests", description = "Guest profiles and preferences")
   @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','CONCIERGE')")
   public class GuestController {

       private final GuestService guestService;

       @PostMapping
       @Operation(summary = "Create a guest profile")
       public ResponseEntity<GuestResponse> create(@Valid @RequestBody GuestRequest request) {
           GuestResponse created = guestService.create(request);
           return ResponseEntity.created(URI.create("/api/v1/guests/" + created.id())).body(created);
       }

       @GetMapping("/{id}")
       @Operation(summary = "Get a guest (also serves as the GDPR data export)")
       public GuestResponse getById(@PathVariable Long id) {
           return guestService.getById(id);
       }

       @GetMapping
       @Operation(summary = "Search guests by name or email")
       public Page<GuestResponse> search(@RequestParam(required = false) String query,
                                         @ParameterObject Pageable pageable) {
           return guestService.search(query, pageable);
       }

       @PutMapping("/{id}")
       @Operation(summary = "Update a guest profile")
       public GuestResponse update(@PathVariable Long id, @Valid @RequestBody GuestRequest request) {
           return guestService.update(id, request);
       }

       @DeleteMapping("/{id}")
       @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
       @Operation(summary = "Anonymise a guest (right to be forgotten)")
       public ResponseEntity<Void> anonymise(@PathVariable Long id) {
           guestService.anonymise(id);
           return ResponseEntity.noContent().build();
       }
   }
   ```
   - *`@PreAuthorize` on the class applies to every method; a method-level one overrides it.*

10. Try it in Swagger (log in as `receptionist@luxstay.test`, then **Authorize**):
    - `POST /api/v1/guests` with the example → 201, then again → 409
    - `GET /api/v1/guests?query=har` → finds Amelia Hart
    - `DELETE /api/v1/guests/{id}` as receptionist → 403; as manager → 204

11. Create `src/test/java/com/luxstay/hms/unit/GuestServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class GuestServiceImplTest {

        @Mock GuestRepository guestRepository;
        @Spy GuestMapper guestMapper = Mappers.getMapper(GuestMapper.class);
        @InjectMocks GuestServiceImpl guestService;

        private GuestRequest request(String email) {
            return new GuestRequest("Amelia", "Hart", email, null, "GB", null, true, null, null, null);
        }

        @Test
        void create_withNewEmail_saves() {
            when(guestRepository.existsByEmailIgnoreCase("a@x.com")).thenReturn(false);
            when(guestRepository.save(any(Guest.class))).thenAnswer(inv -> inv.getArgument(0));

            GuestResponse response = guestService.create(request("a@x.com"));

            assertThat(response.lastName()).isEqualTo("Hart");
            assertThat(response.vip()).isTrue();
        }

        @Test
        void create_withExistingEmail_throws() {
            when(guestRepository.existsByEmailIgnoreCase("a@x.com")).thenReturn(true);

            assertThatThrownBy(() -> guestService.create(request("a@x.com")))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void update_whenAnonymised_throws() {
            Guest guest = new Guest();
            guest.setAnonymised(true);
            when(guestRepository.findById(1L)).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> guestService.update(1L, request("a@x.com")))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void update_withNewUnusedEmail_updates() {
            Guest guest = new Guest();
            guest.setEmail("old@x.com");
            when(guestRepository.findById(1L)).thenReturn(Optional.of(guest));
            when(guestRepository.existsByEmailIgnoreCase("new@x.com")).thenReturn(false);

            GuestResponse response = guestService.update(1L, request("new@x.com"));

            assertThat(response.email()).isEqualTo("new@x.com");
        }

        @Test
        void anonymise_removesPersonalData() {
            Guest guest = new Guest();
            guest.setFirstName("Amelia");
            guest.setEmail("a@x.com");
            guest.setPhone("123");
            when(guestRepository.findById(7L)).thenReturn(Optional.of(guest));

            guestService.anonymise(7L);

            assertThat(guest.getEmail()).isEqualTo("anonymised-7@deleted.invalid");
            assertThat(guest.getPhone()).isNull();
            assertThat(guest.isAnonymised()).isTrue();
        }

        @Test
        void search_withBlankQuery_returnsAll() {
            when(guestRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

            guestService.search(" ", PageRequest.of(0, 10));

            verify(guestRepository).findAll(any(Pageable.class));
        }
    }
    ```

12. Create `src/test/java/com/luxstay/hms/api/GuestControllerTest.java`:
    ```java
    @WebMvcTest(GuestController.class)
    @Import({SecurityConfig.class, JwtConfig.class})
    @WithMockUser(roles = "RECEPTIONIST")
    class GuestControllerTest {

        @Autowired MockMvc mockMvc;
        @MockitoBean GuestService guestService;

        @Test
        void create_withInvalidEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/guests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"firstName":"Amelia","lastName":"Hart","email":"not-an-email"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
        }

        @Test
        void anonymise_asReceptionist_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/guests/1"))
                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        void anonymise_asManager_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/guests/1"))
                .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "HOUSEKEEPER")
        void search_asHousekeeper_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/guests"))
                .andExpect(status().isForbidden());
        }
    }
    ```

13. Commit, tick Step 9, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 9 – add guest profiles with search and anonymisation" -m "- V4 creates guest; email unique (409 on duplicate)
    - GET /api/v1/guests?query= searches name and email; DELETE anonymises (ADMIN/MANAGER only)
    - Logs use guest ids, never emails"
    git push -u origin feature/step-9-guests
    ```
    - *PR title: `feat: Step 9 – guest profiles`.*
