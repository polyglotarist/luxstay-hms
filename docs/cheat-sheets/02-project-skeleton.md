# Cheat Sheet 2 — Spring Boot project skeleton on GitHub

1. On **start.spring.io**, choose:
   - Maven · Java · Spring Boot **4.1.1** (newest without SNAPSHOT/M) · Jar · **YAML** · Java **21**
   - Group `com.luxstay` · Artifact `hms` · Package name `com.luxstay.hms`
   - Dependencies: **Spring Web, Spring Data JPA, Validation, Spring Security, Spring Boot Actuator, PostgreSQL Driver, Flyway Migration, Lombok, Testcontainers**
   - *Click **GENERATE**, then unzip `hms.zip` into your projects folder.*
   - ***You need to change** the group, artifact and package to match your own application!*

2. In IntelliJ, open the project:
   **File → Open → `pom.xml` → Open as Project → Trust Project**

3. Set the JDK:
   - **File → Project Structure → Project** → SDK **corretto-21**, Language level **21**
   - **Settings → Build Tools → Maven → Runner** → JRE **corretto-21**
   - **Settings → Compiler → Annotation Processors** → tick **Enable annotation processing**
   - *Annotation processing is required by Lombok and MapStruct.*

4. Check it compiles: **Maven panel → Lifecycle → compile**
   - *The output should end with **BUILD SUCCESS**.*

5. In the Terminal, make the first commit **before** changing anything:
   ```bash
   git init -b main
   git config user.email "you@example.com"
   git status
   git add .
   git commit -m "chore: Step 2.1 – generate project with Spring Initializr"
   ```
   - *`git config user.email` without `--global` sets the email for this project only.*
   - *`git status` must not list `.idea/` or `target/`.*

6. In `pom.xml`, rename the artifact:
   ```xml
   <artifactId>luxstay-hms</artifactId>
   <name>luxstay-hms</name>
   ```
   - *This is the project's own `artifactId` near the top, under `<groupId>`.*

7. In `pom.xml`, add the versions inside `<properties>`:
   ```xml
   <springdoc.version>3.0.0</springdoc.version>
   <mapstruct.version>1.6.3</mapstruct.version>
   <rest-assured.version>6.0.1</rest-assured.version>
   ```
   - *Press **Ctrl + Space** inside a version to pick the newest. Spring Boot 4 needs springdoc **3.x**.*

8. In `pom.xml`, add these inside `<dependencies>`:
   ```xml
   <dependency>
       <groupId>org.springdoc</groupId>
       <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
       <version>${springdoc.version}</version>
   </dependency>
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
   </dependency>
   <dependency>
       <groupId>org.mapstruct</groupId>
       <artifactId>mapstruct</artifactId>
       <version>${mapstruct.version}</version>
   </dependency>
   <dependency>
       <groupId>io.rest-assured</groupId>
       <artifactId>rest-assured</artifactId>
       <version>${rest-assured.version}</version>
       <scope>test</scope>
   </dependency>
   ```
   - *These add Swagger, JWT security, MapStruct mapping and REST Assured API tests.*
   - *REST Assured **must** have a version. Without it, Sync fails with `rest-assured:pom:unknown`.*

9. In `pom.xml`, in `maven-compiler-plugin` → execution **`default-compile`**, set:
   ```xml
   <annotationProcessorPaths>
       <path>
           <groupId>org.projectlombok</groupId>
           <artifactId>lombok</artifactId>
           <version>${lombok.version}</version>
       </path>
       <path>
           <groupId>org.projectlombok</groupId>
           <artifactId>lombok-mapstruct-binding</artifactId>
           <version>0.2.0</version>
       </path>
       <path>
           <groupId>org.mapstruct</groupId>
           <artifactId>mapstruct-processor</artifactId>
           <version>${mapstruct.version}</version>
       </path>
   </annotationProcessorPaths>
   ```
   - *Keep this order: Lombok first, then the binding, then MapStruct.*
   - *Leave the `default-testCompile` execution unchanged.*

10. Reload and compile: **Maven panel → Sync All Maven Projects**, then **Lifecycle → compile**.
    - *Use **Reload All Maven Projects** only if Sync seems stuck.*

11. Commit:
    ```bash
    git add pom.xml
    git commit -m "build: Step 2.6 – add Swagger, JWT, MapStruct and REST Assured dependencies"
    ```

12. Create the GitHub repository from the **macOS menu bar**:
    **Git → GitHub → Share Project on GitHub** → name `luxstay-hms` → **Share**
    - *The Git menu is at the top of the screen, not inside the IntelliJ window.*
    - *GitHub's file list shows only the first line of each commit message. Click a commit to see the rest.*

13. Create `README.md` in the project root with: title, one-line description, tech stack table, prerequisites, getting started (`git clone …`, `./mvnw compile`), package structure and a **Progress** checklist.
    ```bash
    git add README.md
    git commit -m "docs: Step 2.9 – add README with overview, stack and progress checklist"
    git push
    ```
    - ***You need to change** the content to describe your own application!*

14. On github.com, go to **Settings → General → Pull Requests**:
    - Allow **squash merging** only, default message **Pull request title and commit details**
    - Tick **Automatically delete head branches**
    - *"Commit details" keeps all your commit messages inside the merged commit.*

15. On github.com, go to **Settings → Rules → Rulesets → New branch ruleset**:
    - Name `Protect main` · Active · Include default branch
    - Tick **Restrict deletions**, **Block force pushes**, **Require a pull request** (0 approvals)
    - *Use 0 approvals because you can't approve your own pull request.*

16. Create a branch and the pull request template `.github/pull_request_template.md`:
    ```bash
    git switch -c chore/pr-issue-templates
    ```
    ```markdown
    ## What
    <!-- One or two sentences: what does this PR change? -->

    ## Why
    <!-- Which build step or issue does it complete? -->

    ## How it was tested
    - [ ] `./mvnw verify` passes locally
    - [ ] New or changed endpoints tried in Swagger UI
    - [ ] Tests added or updated

    ## Screenshots
    ```

17. Create the issue templates `.github/ISSUE_TEMPLATE/feature.md` and `.github/ISSUE_TEMPLATE/bug.md`:
    ```markdown
    ---
    name: Feature / build step
    about: A module or build step to implement
    title: "Step <n> – <short description>"
    labels: enhancement
    ---

    ## Goal

    ## Tasks
    - [ ] 

    ## Acceptance criteria
    - [ ] Tests cover the new code (coverage stays ≥ 80%)
    - [ ] Endpoints documented in Swagger
    ```
    ```markdown
    ---
    name: Bug report
    about: Something doesn't work as expected
    title: "fix: <short description>"
    labels: bug
    ---

    ## What happened

    ## Expected behaviour

    ## Steps to reproduce

    ## Logs / correlation ID
    ```

18. Commit, push and merge your first pull request:
    ```bash
    git add .github README.md
    git commit -m "chore: Step 2.10 – add pull request and issue templates"
    git push -u origin chore/pr-issue-templates
    ```
    - *On github.com, click **Compare & pull request → Create pull request → Squash and merge → Confirm**.*

19. Clean up:
    ```bash
    git switch main
    git pull
    git branch -D chore/pr-issue-templates
    git config --global fetch.prune true
    ```
    - *The warning "not yet merged to HEAD" is normal after a squash merge.*

20. Now you have a compiling Spring Boot project on GitHub with a protected `main` branch. Check with:
    ```bash
    git log --oneline
    ```
    ```
    chore: Step 2.10 – add pull request and issue templates (#1)
    docs: Step 2.9 – add README with overview, stack and progress checklist
    build: Step 2.6 – add Swagger, JWT, MapStruct and REST Assured dependencies
    chore: Step 2.1 – generate project with Spring Initializr
    ```
