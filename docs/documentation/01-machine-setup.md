# Step 1 Documentation — Machine setup (macOS)

1. Install these tools:
   - **Amazon Corretto 21** (Java): aws.amazon.com/corretto → macOS aarch64 `.pkg`, or `brew install --cask corretto@21`
   - **IntelliJ IDEA Community Edition**: jetbrains.com/idea/download
   - **DBeaver Community**: dbeaver.io/download
   - **Docker Desktop**: docker.com/products/docker-desktop, then start it once
   - **Git**: run `git --version` and accept the install prompt if one appears
   - *Corretto is Amazon's Java build. Any Java 21 works, but Corretto matches AWS.*

2. In your terminal, check Java:
   ```bash
   java -version
   echo $JAVA_HOME
   ```
   - *Both should show Corretto 21.*
   - *If not, add this line to `~/.zshrc` and open a new terminal:*
     ```bash
     export JAVA_HOME=$(/usr/libexec/java_home -v 21)
     ```

3. On github.com, go to **Settings → Emails** and make sure the email you want on your commits is listed.
   - *Tick **Keep my email addresses private** to use GitHub's `noreply` address instead.*

4. In IntelliJ, connect GitHub:
   **Settings (⌘ + ,) → Version Control → GitHub → + → Log In via GitHub**
   - *This lets IntelliJ create repositories and push for you.*

5. Turn on auto-save in IntelliJ:
   **Settings → Appearance & Behavior → System Settings → Autosave** → tick both options.
   - *Files are then saved whenever you switch to the Terminal.*
