# Step 7 Documentation — JWT security, users and roles

Users log in with email and password, receive a token (JWT), and send it with every request. Each endpoint allows only certain roles.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-7-jwt-security
   ```

2. Generate a secret for signing tokens:
   ```bash
   openssl rand -base64 32
   ```
   Add it to `.env`:
   ```
   APP_JWT_SECRET=<paste the output>
   ```
   Add a placeholder to `.env.example`:
   ```
   APP_JWT_SECRET=generate-with-openssl-rand-base64-32
   ```
   - *Anyone with this secret can forge tokens, so it stays in `.env` only.*

3. Append to `src/main/resources/application.yaml`:
   ```yaml
   app:
     jwt:
       secret: ${APP_JWT_SECRET}
       ttl: PT2H
   ```
   And append to `application-local.yaml`:
   ```yaml
   app:
     jwt:
       secret: ${APP_JWT_SECRET:dGVzdC1zZWNyZXQtZm9yLWxvY2FsLWFuZC1jaS0zMmI=}
   ```
   - *`PT2H` means tokens expire after 2 hours.*
   - *The local fallback is a non-secret test key, so tests and CI run without `.env`. Production gets the real key from AWS.*

4. Create `src/main/resources/db/migration/V3__create_app_user.sql`:
   ```sql
   CREATE TABLE app_user (
       id             BIGSERIAL    PRIMARY KEY,
       email          VARCHAR(255) NOT NULL UNIQUE,
       password_hash  VARCHAR(100) NOT NULL,
       role           VARCHAR(30)  NOT NULL,
       enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
       created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version        BIGINT       NOT NULL DEFAULT 0
   );
   ```
   - *Passwords are stored only as BCrypt hashes, never as plain text.*

5. Create `enums/Role.java`:
   ```java
   package com.luxstay.hms.enums;

   public enum Role {
       ADMIN, MANAGER, RECEPTIONIST, HOUSEKEEPER, CONCIERGE, FNB_STAFF, SPA_STAFF, GUEST
   }
   ```

6. Create `entity/AppUser.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "app_user")
   public class AppUser extends BaseEntity {

       @Column(nullable = false, unique = true)
       private String email;

       @Column(nullable = false, length = 100)
       private String passwordHash;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 30)
       private Role role;

       @Column(nullable = false)
       private boolean enabled = true;
   }
   ```

7. Create `repository/AppUserRepository.java`:
   ```java
   package com.luxstay.hms.repository;

   public interface AppUserRepository extends JpaRepository<AppUser, Long> {
       Optional<AppUser> findByEmail(String email);
       boolean existsByEmail(String email);
   }
   ```

8. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record LoginRequest(
       @NotBlank @Email @Schema(example = "admin@luxstay.test") String email,
       @NotBlank @Schema(example = "Admin123!") String password) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
   }
   ```

9. Create `config/JwtConfig.java`:
   ```java
   package com.luxstay.hms.config;

   @Configuration
   public class JwtConfig {

       @Bean
       SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
           return new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
       }

       @Bean
       JwtEncoder jwtEncoder(SecretKey key) {
           return new NimbusJwtEncoder(new ImmutableSecret<>(key));
       }

       @Bean
       JwtDecoder jwtDecoder(SecretKey key) {
           return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
       }

       @Bean
       JwtAuthenticationConverter jwtAuthenticationConverter() {
           JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
           roles.setAuthoritiesClaimName("roles");
           roles.setAuthorityPrefix("ROLE_");
           JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
           converter.setJwtGrantedAuthoritiesConverter(roles);
           return converter;
       }

       @Bean
       PasswordEncoder passwordEncoder() {
           return new BCryptPasswordEncoder();
       }
   }
   ```
   - *The encoder signs tokens at login; the decoder checks them on every request.*
   - *The converter turns the token's `roles` claim into `ROLE_MANAGER` and so on, which `@PreAuthorize` checks.*

10. Create `security/TokenService.java`:
    ```java
    package com.luxstay.hms.security;

    @Service
    @RequiredArgsConstructor
    public class TokenService {

        private final JwtEncoder jwtEncoder;

        @Value("${app.jwt.ttl:PT2H}")
        private Duration ttl;

        public LoginResponse issue(AppUser user) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("luxstay-hms")
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .build();
            JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
            String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
            return new LoginResponse(token, "Bearer", ttl.toSeconds());
        }
    }
    ```
    - *`subject` is the user's email. Later code reads it as `authentication.getName()`.*

11. Create `service/AuthService.java` and `service/impl/AuthServiceImpl.java`:
    ```java
    package com.luxstay.hms.service;

    public interface AuthService {
        LoginResponse login(LoginRequest request);
    }
    ```
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public class AuthServiceImpl implements AuthService {

        private final AppUserRepository userRepository;
        private final PasswordEncoder passwordEncoder;
        private final TokenService tokenService;

        @Override
        public LoginResponse login(LoginRequest request) {
            AppUser user = userRepository.findByEmail(request.email())
                .filter(AppUser::isEnabled)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
            log.info("User {} logged in", user.getId());
            return tokenService.issue(user);
        }
    }
    ```
    - *The same error for a wrong email or a wrong password, so attackers can't tell which emails exist.*

12. Create `controller/AuthController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/auth")
    @RequiredArgsConstructor
    @Tag(name = "Authentication")
    public class AuthController {

        private final AuthService authService;

        @PostMapping("/login")
        @Operation(summary = "Log in and receive a JWT", security = {})
        public LoginResponse login(@Valid @RequestBody LoginRequest request) {
            return authService.login(request);
        }
    }
    ```

13. Add to `GlobalExceptionHandler`:
    ```java
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", ex.getMessage());
    }
    ```

14. Replace `config/SecurityConfig.java` completely:
    ```java
    package com.luxstay.hms.config;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    public class SecurityConfig {

        private static final String[] PUBLIC = {
            "/api/v1/auth/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/health"
        };

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                JwtAuthenticationConverter converter) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC).permitAll()
                    .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
            return http.build();
        }
    }
    ```
    - *Stateless: no server sessions, so every request must carry its token.*
    - *`@EnableMethodSecurity` switches on `@PreAuthorize`.*

15. Create `config/DevDataSeeder.java`:
    ```java
    package com.luxstay.hms.config;

    @Slf4j
    @Component
    @Profile("local")
    @RequiredArgsConstructor
    public class DevDataSeeder implements CommandLineRunner {

        private final AppUserRepository userRepository;
        private final PasswordEncoder passwordEncoder;

        @Override
        public void run(String... args) {
            for (Role role : Role.values()) {
                String email = role.name().toLowerCase() + "@luxstay.test";
                if (!userRepository.existsByEmail(email)) {
                    AppUser user = new AppUser();
                    user.setEmail(email);
                    user.setPasswordHash(passwordEncoder.encode("Admin123!"));
                    user.setRole(role);
                    userRepository.save(user);
                    log.info("Seeded user {}", email);
                }
            }
        }
    }
    ```
    - *Creates one test user per role, e.g. `manager@luxstay.test` / `Admin123!`, in the `local` profile only.*

16. Protect the rooms endpoints. In `RoomController`, add above `create` and `updateStatus`:
    ```java
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    ```
    - *Reading rooms just needs a valid token; changing them needs ADMIN or MANAGER.*

17. Update `RoomControllerTest`:
    ```java
    @WebMvcTest({RoomController.class, RoomTypeController.class})
    @Import({SecurityConfig.class, JwtConfig.class})
    @WithMockUser(roles = "MANAGER")
    class RoomControllerTest {
        // ... existing tests unchanged ...

        @Test
        @WithAnonymousUser
        void getAll_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "GUEST")
        void create_asGuest_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"number":"613","floor":6,"roomTypeId":1}
                        """))
                .andExpect(status().isForbidden());
        }
    }
    ```
    - *`@WithMockUser` on the class runs every test as a MANAGER; single tests override it.*
    - *401 means no or invalid token; 403 means a valid token with the wrong role.*

18. Create `src/test/java/com/luxstay/hms/unit/AuthServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class AuthServiceImplTest {

        @Mock AppUserRepository userRepository;
        @Mock PasswordEncoder passwordEncoder;
        @Mock TokenService tokenService;
        @InjectMocks AuthServiceImpl authService;

        @Test
        void login_withCorrectPassword_returnsToken() {
            AppUser user = new AppUser();
            user.setEmail("manager@luxstay.test");
            user.setPasswordHash("hash");
            user.setRole(Role.MANAGER);
            when(userRepository.findByEmail("manager@luxstay.test")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Admin123!", "hash")).thenReturn(true);
            when(tokenService.issue(user)).thenReturn(new LoginResponse("jwt", "Bearer", 7200));

            LoginResponse response = authService.login(new LoginRequest("manager@luxstay.test", "Admin123!"));

            assertThat(response.accessToken()).isEqualTo("jwt");
        }

        @Test
        void login_withWrongPassword_throwsBadCredentials() {
            AppUser user = new AppUser();
            user.setPasswordHash("hash");
            when(userRepository.findByEmail("manager@luxstay.test")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("manager@luxstay.test", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        void login_withUnknownEmail_throwsBadCredentials() {
            when(userRepository.findByEmail("nobody@luxstay.test")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@luxstay.test", "x")))
                .isInstanceOf(BadCredentialsException.class);
        }
    }
    ```

19. Create `src/test/java/com/luxstay/hms/integration/AuthIT.java`:
    ```java
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class AuthIT {

        @Autowired MockMvc mockMvc;

        @Test
        void login_thenCallProtectedEndpoint() throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"manager@luxstay.test","password":"Admin123!"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            String token = JsonPath.read(body, "$.accessToken");

            mockMvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        }

        @Test
        void login_withWrongPassword_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"manager@luxstay.test","password":"wrong"}
                        """))
                .andExpect(status().isUnauthorized());
        }
    }
    ```
    - *This starts the whole app with a real database and tests real token signing and checking.*
    - *`JsonPath` is `com.jayway.jsonpath.JsonPath`. `@AutoConfigureMockMvc` comes from the webmvc-test module.*

20. Try it in Swagger:
    - `POST /api/v1/auth/login` with `admin@luxstay.test` / `Admin123!` → copy `accessToken`
    - Click **Authorize** (top right), paste the token, then click **Authorize → Close**
    - `GET /api/v1/rooms` → 200. Log out via **Authorize → Logout** → 401
    - *Log in as `guest@luxstay.test` and try `POST /api/v1/rooms` → 403.*

21. Commit, tick Step 7 in `README.md`, push and merge:
    ```bash
    git add src .env.example README.md
    git commit -m "feat: Step 7 – secure the API with JWT and role-based access" -m "- POST /api/v1/auth/login returns a 2h HS256 token; secret from APP_JWT_SECRET (openssl rand -base64 32)
    - 8 roles; dev users <role>@luxstay.test / Admin123! in the local profile
    - Swagger: login -> Authorize -> paste token; no token -> 401, wrong role -> 403"
    git push -u origin feature/step-7-jwt-security
    ```
    - *PR title: `feat: Step 7 – JWT security and roles`.*
