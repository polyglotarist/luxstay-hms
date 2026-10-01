# Step 8 Documentation — Logging, health checks and the production profile

Every log line of a request carries one correlation ID. Production writes JSON logs and reads its settings from environment variables.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-8-logging
   ```

2. Create `config/CorrelationIdFilter.java`:
   ```java
   package com.luxstay.hms.config;

   @Component
   @Order(Ordered.HIGHEST_PRECEDENCE)
   public class CorrelationIdFilter extends OncePerRequestFilter {

       public static final String HEADER = "X-Correlation-Id";
       public static final String MDC_KEY = "correlationId";

       @Override
       protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                       FilterChain chain) throws ServletException, IOException {
           String id = Optional.ofNullable(request.getHeader(HEADER))
               .filter(h -> !h.isBlank())
               .orElse(UUID.randomUUID().toString());
           MDC.put(MDC_KEY, id);
           response.setHeader(HEADER, id);
           try {
               chain.doFilter(request, response);
           } finally {
               MDC.remove(MDC_KEY);
           }
       }
   }
   ```
   - *If a caller sends `X-Correlation-Id`, it's reused; otherwise a new one is created. Either way it's returned in the response.*
   - *MDC is a per-request store that the logger adds to each line.*

3. Append to `src/main/resources/application.yaml`:
   ```yaml
   logging:
     pattern:
       correlation: "[%X{correlationId:-}] "
     level:
       com.luxstay.hms: INFO

   management:
     endpoints:
       web:
         exposure:
           include: health,info,metrics
     endpoint:
       health:
         probes:
           enabled: true
   ```
   - *Local log lines now include `[3f2a…]`, the request's correlation ID.*
   - *The AWS load balancer uses `/actuator/health`.*

4. Create `src/main/resources/application-prod.yaml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}
       username: ${DB_USERNAME}
       password: ${DB_PASSWORD}
     jpa:
       hibernate:
         ddl-auto: validate
       open-in-view: false
     flyway:
       enabled: true

   logging:
     structured:
       format:
         console: logstash

   springdoc:
     api-docs:
       enabled: ${SWAGGER_ENABLED:false}
     swagger-ui:
       enabled: ${SWAGGER_ENABLED:false}
   ```
   - *`logstash` writes each log line as JSON, which CloudWatch can search by field, including `correlationId`.*
   - *Swagger is off in production unless `SWAGGER_ENABLED=true`; staging turns it on.*

5. Try the production profile locally (database running):
   ```bash
   SPRING_PROFILES_ACTIVE=prod DB_HOST=localhost DB_NAME=luxstay DB_USERNAME=luxstay \
   DB_PASSWORD=<your password> APP_JWT_SECRET=$(openssl rand -base64 32) SWAGGER_ENABLED=true \
   ./mvnw spring-boot:run
   ```
   - *The log is now JSON lines. Stop with **Ctrl + C**.*
   - *The dev users aren't created in `prod`, so the seeder never runs there.*

6. Create `src/test/java/com/luxstay/hms/unit/CorrelationIdFilterTest.java`:
   ```java
   class CorrelationIdFilterTest {

       private final CorrelationIdFilter filter = new CorrelationIdFilter();

       @Test
       void reusesIncomingCorrelationId() throws Exception {
           MockHttpServletRequest request = new MockHttpServletRequest();
           request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
           MockHttpServletResponse response = new MockHttpServletResponse();

           filter.doFilter(request, response, new MockFilterChain());

           assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
       }

       @Test
       void generatesCorrelationIdWhenMissing() throws Exception {
           MockHttpServletResponse response = new MockHttpServletResponse();

           filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

           assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
           assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
       }
   }
   ```
   - *The last line checks the ID is cleared after the request, so it can't leak into the next one.*

7. Logging rules for all later steps:
   - *`log.info` for business events: "Reservation LX7K2P9Q created".*
   - *`log.warn` for rejected business rules: "Double booking attempt on room 412".*
   - *`log.error` only for failures someone must act on.*
   - *Never log passwords, tokens, card numbers or full emails; log ids instead.*

8. Commit, tick Step 8, push and merge:
   ```bash
   git add src README.md
   git commit -m "feat: Step 8 – correlation IDs, health probes and JSON logs in the prod profile" -m "- X-Correlation-Id header reused or generated; added to every log line via MDC
   - application-prod.yaml: DB_* env vars, logstash JSON logs, Swagger off unless SWAGGER_ENABLED=true
   - Try prod locally: SPRING_PROFILES_ACTIVE=prod DB_HOST=localhost ... ./mvnw spring-boot:run"
   git push -u origin feature/step-8-logging
   ```
   - *PR title: `feat: Step 8 – logging and production profile`.*
