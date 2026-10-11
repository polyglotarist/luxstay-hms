# Step 6 Documentation — CI with GitHub Actions and the 80% coverage gate ✅

Every pull request automatically builds the app, runs all tests (unit + Testcontainers integration) and fails if line coverage drops below 80%.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c ci/step-6-github-actions
   ```

2. Add **Failsafe** in `pom.xml`, inside `<build><plugins>`, just above `</plugins>`:
   ```xml
   <plugin>
       <groupId>org.apache.maven.plugins</groupId>
       <artifactId>maven-failsafe-plugin</artifactId>
       <executions>
           <execution>
               <goals>
                   <goal>integration-test</goal>
                   <goal>verify</goal>
               </goals>
           </execution>
       </executions>
   </plugin>
   ```
   - *No `<version>`: the Spring Boot parent manages it.*
   - *Surefire runs `*Test`/`*Tests` in the `test` phase; Failsafe runs `*IT` after `package`, in its own JVM.*

   **Sync Maven**, then run:
   ```bash
   ./mvnw clean verify
   ```
   - *Two summaries: Surefire `Tests run: 13`, then Failsafe `Tests run: 2`, then BUILD SUCCESS.*

   Commit:
   ```bash
   git add pom.xml
   git commit -m "build: Step 6.1 – add Failsafe so verify runs *IT integration tests"
   ```

3. Add **JaCoCo** in `pom.xml`, **below** Failsafe (so integration tests finish before coverage is checked):
   ```xml
   <plugin>
       <groupId>org.jacoco</groupId>
       <artifactId>jacoco-maven-plugin</artifactId>
       <version>0.8.13</version>
       <configuration>
           <excludes>
               <exclude>com/luxstay/hms/HmsApplication.class</exclude>
               <exclude>com/luxstay/hms/config/**</exclude>
               <exclude>com/luxstay/hms/dto/**</exclude>
               <exclude>com/luxstay/hms/entity/**</exclude>
               <exclude>com/luxstay/hms/enums/**</exclude>
               <exclude>**/*MapperImpl.class</exclude>
           </excludes>
       </configuration>
       <executions>
           <execution>
               <id>prepare-agent</id>
               <goals><goal>prepare-agent</goal></goals>
           </execution>
           <execution>
               <id>report</id>
               <phase>verify</phase>
               <goals><goal>report</goal></goals>
           </execution>
           <execution>
               <id>check</id>
               <phase>verify</phase>
               <goals><goal>check</goal></goals>
               <configuration>
                   <rules>
                       <rule>
                           <element>BUNDLE</element>
                           <limits>
                               <limit>
                                   <counter>LINE</counter>
                                   <value>COVEREDRATIO</value>
                                   <minimum>0.80</minimum>
                               </limit>
                           </limits>
                       </rule>
                   </rules>
               </configuration>
           </execution>
       </executions>
   </plugin>
   ```
   - *JaCoCo isn't managed by Spring Boot, so it needs a version. A newer 0.8.x is fine (**⌃ + Space** inside `<version>`).*
   - *Excluded: code with no logic (config, DTOs, entities, enums, MapStruct-generated classes).*
   - *If `maven-surefire-plugin` ever gets an `<argLine>`, start it with `@{argLine}` so JaCoCo's agent is kept.*

   **Sync Maven**, then:
   ```bash
   ./mvnw clean verify
   ```
   - *Ends with `All coverage checks have been met` and BUILD SUCCESS.*
   - *If it fails with `lines covered ratio is 0.7x, but expected minimum is 0.80`, add tests for the red lines (next item).*

4. Open the coverage report:
   ```bash
   open target/site/jacoco/index.html
   ```
   - *The gate checks **lines** (the `Lines` / `Missed` columns), not the `Cov.` instructions column.*
   - *Click into a package, then a class: green = ran in tests, red = never ran, yellow = only one side of an `if` ran.*
   - *First run here: 87% lines, with `controller` at 48%. `GET /api/v1/rooms` and the successful `PATCH …/status` were never exercised.*

5. Close the gap: add these two tests to `RoomControllerTest` (**the test file** in `src/test/java/.../api/`, not `RoomController`):
   ```java
   @Test
   void getAll_withStatus_returnsPageOfRooms() throws Exception {
       when(roomService.getAll(eq(RoomStatus.VACANT_CLEAN), any(Pageable.class))).thenReturn(
           new PageImpl<>(List.of(
               new RoomResponse(1L, "101", 1, "CITY", RoomStatus.VACANT_CLEAN, "DLX", "Deluxe King"))));

       mockMvc.perform(get("/api/v1/rooms").param("status", "VACANT_CLEAN").param("size", "5"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].number").value("101"));
   }

   @Test
   void updateStatus_withValidStatus_returns200() throws Exception {
       when(roomService.updateStatus(1L, RoomStatus.OUT_OF_ORDER)).thenReturn(
           new RoomResponse(1L, "101", 1, "CITY", RoomStatus.OUT_OF_ORDER, "DLX", "Deluxe King"));

       mockMvc.perform(patch("/api/v1/rooms/1/status")
               .contentType(MediaType.APPLICATION_JSON)
               .content("""
                   {"status":"OUT_OF_ORDER"}
                   """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OUT_OF_ORDER"));
   }
   ```
   - *Imports: `org.springframework.data.domain.PageImpl`, `org.springframework.data.domain.Pageable`, static `org.mockito.ArgumentMatchers.eq`.*
   - *When one argument uses a Mockito matcher (`any(...)`), all arguments must (`eq(...)`).*
   - *If IntelliJ offers `ConditionalAuthorizationManager.when`, the code is in the wrong file: Mockito is only available in `src/test`.*
   - *Result: controller 48% → 85%, total lines 87% → 91%.*

6. Commit (two commits keep the trail readable) and push:
   ```bash
   git add pom.xml
   git commit -m "build: Step 6.2 – add JaCoCo with an 80% line coverage gate"
   git add src/test/java/com/luxstay/hms/api/RoomControllerTest.java
   git commit -m "test: Step 6.3 – cover room list and status update success paths"
   git push -u origin ci/step-6-github-actions
   ```

7. Create the workflow:
   ```bash
   mkdir -p .github/workflows
   ```
   Right-click `.github/workflows` → **New → File** → `ci.yml`:
   ```yaml
   name: CI

   on:
     pull_request:
     push:
       branches: [main]

   permissions:
     contents: read

   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - name: Check out the code
           uses: actions/checkout@v5

         - name: Set up Java 21 (Corretto)
           uses: actions/setup-java@v5
           with:
             distribution: corretto
             java-version: '21'
             cache: maven

         - name: Build, test and check coverage
           run: ./mvnw -B verify

         - name: Upload coverage report
           if: always()
           uses: actions/upload-artifact@v6
           with:
             name: jacoco-report
             path: target/site/jacoco
   ```
   - *The job is named `build`; item 15 uses that name.*
   - *GitHub's runners have Docker, so Testcontainers works there. No `.env` is needed.*
   - *Use `upload-artifact@v6` or newer: v4 shows a "Node.js 20 is deprecated" warning.*

   Check `mvnw` is executable (Linux runners need it):
   ```bash
   git ls-files -s mvnw
   ```
   - *Must start with `100755`. If it shows `100644`: `git update-index --chmod=+x mvnw` and commit it.*

   ```bash
   git add .github/workflows/ci.yml
   git commit -m "ci: Step 6.4 – run ./mvnw verify on every pull request"
   git push
   ```
   - *Nothing runs yet: the workflow triggers on pull requests and on pushes to `main`.*

8. Open the pull request now (don't merge), so you can watch CI work:
   - *Title: `ci: Step 6 – GitHub Actions with 80% coverage gate`.*
   - *At the bottom of the PR, **CI / build** turns from a yellow dot to a green ✓ (first run about 1.5 min; later runs faster thanks to the Maven cache). Click **Details** to see the log.*
   - *Every later `git push` to this branch adds the commits to the same PR and re-runs CI automatically. **Don't** open a new PR. "Re-run all jobs" repeats the same commit.*
   - *For PR runs, the run page shows the **PR title** and a run number (#1, #2, …), not the commit message.*

9. *(Only if you used `upload-artifact@v4`.)* Bump it to `@v6`, then commit and push:
   ```bash
   git commit -am "ci: Step 6.5 – bump upload-artifact to v6 (Node 24)"
   git push
   ```

10. Create `.github/dependabot.yml`. It goes directly in **`.github/`**, not in `workflows/`:
    ```yaml
    version: 2
    updates:
      - package-ecosystem: maven
        directory: /
        schedule:
          interval: weekly

      - package-ecosystem: github-actions
        directory: /
        schedule:
          interval: weekly
    ```
    - *Dependabot opens weekly PRs for newer Maven libraries and Actions. Merge them only when CI is green.*
    - *It starts working once this file is on `main`.*

    ```bash
    git add .github/dependabot.yml
    git commit -m "ci: Step 6.6 – add Dependabot for weekly Maven and Actions updates"
    git push
    ```

11. Add the CI badge as the third line of `README.md` (after the title and a blank line):
    ```markdown
    ![CI](https://github.com/polyglotarist/luxstay-hms/actions/workflows/ci.yml/badge.svg)
    ```
    - ***You need to change** `polyglotarist/luxstay-hms` to your own user and repository!*
    - *It shows "no status" until CI has run on `main`, i.e. after the merge.*

12. Tick Step 6 in `README.md`:
    ```bash
    sed -i '' 's/^- \[ \] Step 6 /- [x] Step 6 /' README.md
    grep -n "Step 6" README.md
    ```

13. Mark this page ✅ in `docs/documentation/00-index.md`, then commit and push:
    ```bash
    git add README.md docs/documentation
    git commit -m "docs: Step 6 – CI badge, tick Step 6, record CI setup"
    git push
    ```

14. On the PR, wait for **CI / build** to turn green.

15. Make CI required: repo **Settings → Rules → Rulesets** → open your `main` ruleset → tick **Require status checks to pass** → **Add checks** → type `build` → select it → **Save changes**.
    - *From now on a red build blocks the merge, even for you.*

16. **Squash and merge** the pull request.

17. Clean up:
    ```bash
    git switch main && git pull
    git branch -D ci/step-6-github-actions
    ```
