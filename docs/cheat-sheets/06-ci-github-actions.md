# Cheat Sheet 6 — CI with GitHub Actions and the 80% coverage gate

Every pull request automatically builds the app, runs all tests and fails if line coverage drops below 80%.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c ci/step-6-github-actions
   ```

2. In `pom.xml`, add inside `<build><plugins>`:
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
   - *Failsafe runs the `*IT` integration tests. JaCoCo measures coverage of unit and integration tests together.*
   - *Keep Failsafe **above** JaCoCo, so integration tests finish before coverage is checked.*
   - *Press **Ctrl + Space** inside `<version>` to pick the newest JaCoCo 0.8.x.*
   - *If your `pom.xml` already configures `maven-surefire-plugin` with an `<argLine>`, make sure it starts with `@{argLine}` so JaCoCo's agent is kept.*

3. Run the full build locally (Docker Desktop must be running):
   ```bash
   ./mvnw verify
   ```
   - *It should end with **BUILD SUCCESS** and `All coverage checks have been met`.*
   - *If it fails with `lines covered ratio is 0.7x, but expected minimum is 0.80`, open the report and add tests for the red lines.*

4. Open the coverage report: right-click `target/site/jacoco/index.html` → **Open In → Browser**.
   - *Green lines are tested, red lines aren't, yellow lines are partly tested.*
   - *In IntelliJ: right-click `src/test/java` → **More Run/Debug → Run 'All Tests' with Coverage**.*

5. Create `.github/workflows/ci.yml`:
   ```yaml
   name: CI

   on:
     pull_request:
     push:
       branches: [main]

   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4

         - uses: actions/setup-java@v4
           with:
             distribution: corretto
             java-version: '21'
             cache: maven

         - name: Build, test and check coverage
           run: ./mvnw -B verify

         - name: Upload coverage report
           if: always()
           uses: actions/upload-artifact@v4
           with:
             name: jacoco-report
             path: target/site/jacoco
   ```
   - *The job is named `build`. That name is used in step 9.*
   - *GitHub's runners have Docker, so Testcontainers works there too.*
   - *There's no `.env` in CI. The fallback values in `application-local.yaml` and Testcontainers cover it.*

6. Create `.github/dependabot.yml`:
   ```yaml
   version: 2
   updates:
     - package-ecosystem: maven
       directory: /
       schedule: { interval: weekly }
     - package-ecosystem: github-actions
       directory: /
       schedule: { interval: weekly }
   ```
   - *GitHub then opens weekly pull requests to update dependencies. Merge them only when CI is green.*

7. Add the CI badge as the second line of `README.md`, and tick Step 6:
   ```markdown
   ![CI](https://github.com/polyglotarist/luxstay-hms/actions/workflows/ci.yml/badge.svg)
   ```
   - ***You need to change** `polyglotarist/luxstay-hms` to your own user and repository!*

8. Commit, push and open the pull request:
   ```bash
   git add pom.xml .github README.md
   git commit -m "ci: Step 6 – run tests on every PR with an 80% JaCoCo coverage gate" -m "- Failsafe runs *IT tests in verify; JaCoCo merges unit + IT coverage
   - ci.yml: Corretto 21, ./mvnw -B verify, uploads jacoco-report
   - Local check: ./mvnw verify; report at target/site/jacoco/index.html"
   git push -u origin ci/step-6-github-actions
   ```
   - *On the PR page, the **build** check runs for about 2–4 minutes and should turn green.*
   - *If it's red, click **Details** to see the log; the coverage report is under **Summary → Artifacts**.*

9. Make CI required: **Settings → Rules → Rulesets → Protect main → Require status checks to pass → Add checks →** type `build` → select it → **Save changes**.
   - *From now on, a red build blocks the merge.*

10. **Squash and merge** the pull request, then clean up:
    ```bash
    git switch main && git pull
    git branch -D ci/step-6-github-actions
    ```
    - *PR title: `ci: Step 6 – GitHub Actions with 80% coverage gate`.*
