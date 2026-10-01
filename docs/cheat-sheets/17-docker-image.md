# Cheat Sheet 17 — Production Docker image

Package the app as a small, secure container image: the exact thing AWS will run. Then run the whole system (app + database) with one command.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c build/step-17-docker-image
   ```

2. Create `Dockerfile` in the project root:
   ```dockerfile
   # ---- build stage: compile and package ----
   FROM maven:3.9-amazoncorretto-21 AS build
   WORKDIR /build
   COPY pom.xml .
   RUN mvn -B -q dependency:go-offline
   COPY src src
   RUN mvn -B -q package -DskipTests \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

   # ---- runtime stage: only what's needed to run ----
   FROM amazoncorretto:21-alpine
   RUN addgroup -S app && adduser -S app -G app
   WORKDIR /app
   COPY --from=build /build/extracted/dependencies/ ./
   COPY --from=build /build/extracted/spring-boot-loader/ ./
   COPY --from=build /build/extracted/snapshot-dependencies/ ./
   COPY --from=build /build/extracted/application/ ./
   USER app
   EXPOSE 8080
   ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
   HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
     CMD wget -qO- http://localhost:8080/actuator/health || exit 1
   ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
   ```
   - *Two stages: the first has Maven and the full JDK to build; the second has only Java to run, so the image is smaller and has less to attack.*
   - *Layers: dependencies change rarely and your code often, so they're copied separately and rebuilds only replace your code.*
   - *`USER app` runs the app as a non-root user.*
   - *`-DskipTests`: tests already ran in CI before the image is built.*

3. Create `.dockerignore`:
   ```
   target/
   .git/
   .idea/
   .github/
   docs/
   *.iml
   .env
   ```
   - *Keeps build output and secrets out of the image. `.env` must **never** be in an image.*

4. Add the app to `docker-compose.yml`, under `services:` at the same indent as `postgres:`:
   ```yaml
     app:
       build: .
       container_name: luxstay-app
       depends_on:
         postgres:
           condition: service_healthy
       env_file: .env
       environment:
         SPRING_PROFILES_ACTIVE: local
         POSTGRES_HOST: postgres
         POSTGRES_PORT: 5432
       ports:
         - "8080:8080"
   ```
   - *Inside Docker the database's address is the service name `postgres`, not `localhost`. That's why Step 3 made `POSTGRES_HOST` a variable.*
   - *`POSTGRES_PORT: 5432` is the port inside Docker's network, even if your `.env` maps a different port on your Mac.*

5. Stop the app in IntelliJ, then build and run everything:
   ```bash
   docker compose up -d --build
   docker compose ps
   docker compose logs -f app
   ```
   - *The first build takes a few minutes while Maven downloads dependencies. Press **Ctrl + C** to stop following the logs.*
   - *Swagger works as before at `http://localhost:8080/swagger-ui.html`.*

6. Check the image:
   ```bash
   docker images | grep hms
   docker compose exec app whoami
   ```
   - *The size should be around 300–350 MB; `whoami` should print `app`, not `root`.*

7. Back to IntelliJ development:
   ```bash
   docker compose stop app
   ```
   - *For day-to-day coding, run only `postgres` in Docker and the app from IntelliJ, which is faster.*

8. Build the image in CI too. In `.github/workflows/ci.yml`, add a last step:
   ```yaml
         - name: Build Docker image
           run: docker build -t luxstay-hms:ci .
   ```
   - *This catches a broken Dockerfile in the pull request, before it reaches AWS.*

9. Update **Getting started** in `README.md`:
   ````markdown
   ```bash
   cp .env.example .env              # then set POSTGRES_PASSWORD and APP_JWT_SECRET
   docker compose up -d --build      # app + database at http://localhost:8080/swagger-ui.html
   ```
   ````

10. Commit, tick Step 17, push and merge:
    ```bash
    git add Dockerfile .dockerignore docker-compose.yml .github README.md
    git commit -m "build: Step 17 – multi-stage Docker image on Amazon Corretto 21" -m "- Build stage maven:3.9-amazoncorretto-21; runtime amazoncorretto:21-alpine as non-root user
    - Layered jar for fast rebuilds; HEALTHCHECK on /actuator/health
    - docker compose up -d --build runs app + database; CI also builds the image"
    git push -u origin build/step-17-docker-image
    ```
    - *PR title: `build: Step 17 – production Docker image`.*
