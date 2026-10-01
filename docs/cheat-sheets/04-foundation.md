# Cheat Sheet 4 — Foundation code

Shared code every module uses: a base class for entities, error handling, Swagger setup and temporary open security.

> **Imports are left out of all Java code.** Paste the class, then put the cursor on each red name and press **⌥ + Enter → Import class**. When asked to choose, pick the `jakarta.*`, `org.springframework.*`, `java.*` or `lombok.*` option.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-4-foundation
   ```

2. Create the packages: right-click `com.luxstay.hms` → **New → Package**, and type each name:
   ```
   config  controller  dto.request  dto.response  entity  enums  event
   exception  integration  mapper  repository  security  service  service.impl  util
   ```
   - *Git ignores empty folders. They get committed once they contain classes.*

3. Create `entity/BaseEntity.java`:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @MappedSuperclass
   @EntityListeners(AuditingEntityListener.class)
   public abstract class BaseEntity {

       @Id
       @GeneratedValue(strategy = GenerationType.IDENTITY)
       private Long id;

       @CreatedDate
       @Column(nullable = false, updatable = false)
       private Instant createdAt;

       @LastModifiedDate
       @Column(nullable = false)
       private Instant updatedAt;

       @Version
       private Long version;
   }
   ```
   - *Every entity extends this and inherits `id`, `created_at`, `updated_at` and `version`.*
   - *`@Version` stops two users silently overwriting each other's changes.*

4. Create `config/JpaAuditingConfig.java`:
   ```java
   package com.luxstay.hms.config;

   @Configuration
   @EnableJpaAuditing
   public class JpaAuditingConfig {
   }
   ```
   - *This fills `createdAt` and `updatedAt` automatically.*

5. Create `config/WebConfig.java`:
   ```java
   package com.luxstay.hms.config;

   @Configuration
   @EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
   public class WebConfig {
   }
   ```
   - *This gives paged lists a stable JSON shape: `content` plus `page`.*

6. Create `config/SecurityConfig.java` (temporary):
   ```java
   package com.luxstay.hms.config;

   @Configuration
   @EnableWebSecurity
   public class SecurityConfig {

       // TEMPORARY: open access. Replaced by JWT security in Step 7.
       @Bean
       SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
           http.csrf(csrf -> csrf.disable())
               .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
           return http.build();
       }
   }
   ```
   - *Without this, Spring Security blocks every request until login exists.*

7. Create `config/OpenApiConfig.java`:
   ```java
   package com.luxstay.hms.config;

   @Configuration
   public class OpenApiConfig {

       @Bean
       OpenAPI luxStayOpenApi() {
           return new OpenAPI()
               .info(new Info()
                   .title("LuxStay HMS API")
                   .version("v1")
                   .description("Luxury hotel management system"))
               .components(new Components().addSecuritySchemes("bearerAuth",
                   new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
               .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
       }
   }
   ```
   - *Import the `io.swagger.v3.oas.models.*` versions of `OpenAPI`, `Info`, `Components`, `SecurityScheme` and `SecurityRequirement`.*
   - *This adds the **Authorize** button that Step 7 uses.*

8. Create the exceptions in `exception/`:
   ```java
   package com.luxstay.hms.exception;

   public class ResourceNotFoundException extends RuntimeException {
       public ResourceNotFoundException(String resource, Object id) {
           super(resource + " " + id + " not found");
       }
   }
   ```
   ```java
   package com.luxstay.hms.exception;

   public class BusinessRuleException extends RuntimeException {
       public BusinessRuleException(String message) {
           super(message);
       }
   }
   ```
   - *`ResourceNotFoundException` becomes a 404 response; `BusinessRuleException` becomes a 409.*

9. Create `exception/GlobalExceptionHandler.java`:
   ```java
   package com.luxstay.hms.exception;

   @Slf4j
   @RestControllerAdvice
   public class GlobalExceptionHandler {

       @ExceptionHandler(ResourceNotFoundException.class)
       public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
           return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
       }

       @ExceptionHandler(BusinessRuleException.class)
       public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
           log.warn("Business rule rejected: {}", ex.getMessage());
           return problem(HttpStatus.CONFLICT, "Business rule violated", ex.getMessage());
       }

       @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
       public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
           return problem(HttpStatus.CONFLICT, "Concurrent update",
               "The record was changed by someone else. Reload and try again.");
       }

       @ExceptionHandler(MethodArgumentNotValidException.class)
       public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
           ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
           Map<String, String> errors = new LinkedHashMap<>();
           ex.getBindingResult().getFieldErrors()
               .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
           pd.setProperty("fieldErrors", errors);
           return pd;
       }

       @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
       public ProblemDetail handleBadInput(Exception ex) {
           return problem(HttpStatus.BAD_REQUEST, "Malformed request", "The request body or a parameter has the wrong format");
       }

       private ProblemDetail problem(HttpStatus status, String title, String detail) {
           ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
           pd.setTitle(title);
           pd.setProperty("timestamp", Instant.now());
           return pd;
       }
   }
   ```
   - *Every error becomes the same JSON shape (RFC 7807 Problem Details) instead of a stack trace.*

10. In `src/test/java/com/luxstay/hms/TestcontainersConfiguration.java`, change the image to match production:
    ```java
    new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
    ```
    - *Initializr uses `postgres:latest`. Pinning 16 keeps tests identical to the real database.*

11. Run `HmsApplication`, then open:
    - `http://localhost:8080/swagger-ui.html`: Swagger UI loads (no endpoints yet)
    - `http://localhost:8080/actuator/health`: `{"status":"UP"}`
    - *If the app won't start, check Docker is running: `docker compose ps`.*

12. Commit:
    ```bash
    git add src
    git commit -m "feat: Step 4 – add base entity, error handling, Swagger config and temporary open security" -m "- BaseEntity: id, createdAt, updatedAt (JPA auditing), @Version
    - GlobalExceptionHandler returns ProblemDetail: 404, 409, 400 with fieldErrors
    - SecurityConfig permits everything until Step 7
    - Swagger UI: http://localhost:8080/swagger-ui.html"
    git push -u origin feature/step-4-foundation
    ```

13. Tick Step 4 in `README.md`, commit, then open the pull request and merge it:
    - *PR title: `feat: Step 4 – foundation code`.*
    - *Then clean up: `git switch main && git pull && git branch -D feature/step-4-foundation`.*
