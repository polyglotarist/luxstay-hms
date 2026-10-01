# LuxStay HMS — Documentation

Step-by-step instructions to build this app from an empty folder. Follow them in order: **page number = step number = the `Step N` in commit messages**.

✅ = built and verified · 📝 = planned (corrected when that step is built)

**Setup**
1. ✅ [Machine setup](01-machine-setup.md): Java 21, IntelliJ, DBeaver, Docker, Git
2. ✅ [Project skeleton](02-project-skeleton.md): Spring Boot project on GitHub
3. ✅ [PostgreSQL with Docker and Flyway](03-postgres-docker-flyway.md): database and first tables

**Core**

4. ✅ [Foundation code](04-foundation.md): base entity, error handling, Swagger
5. 📝 [Rooms module](05-rooms.md): the recipe every module follows
6. 📝 [CI with GitHub Actions](06-ci-github-actions.md): tests on every PR, 80% coverage gate
7. 📝 [JWT security](07-jwt-security.md): login, roles, protected endpoints
8. 📝 [Logging and profiles](08-logging-and-profiles.md): correlation IDs, JSON logs, prod settings

**Hotel modules**

9. 📝 [Guests](09-guests.md): profiles, search, anonymisation
10. 📝 [Reservations](10-reservations.md): availability, booking, no double bookings
11. 📝 [Billing](11-billing.md): folios, charges, tax, payments
12. 📝 [Front desk](12-front-desk.md): check-in, check-out, room changes
13. 📝 [Staff](13-staff.md): employees and shifts
14. 📝 [Housekeeping](14-housekeeping.md): cleaning tasks, inspections, maintenance
15. 📝 [Dining](15-dining.md): menu, room service, restaurant tables

**Ship it**

16. 📝 [End-to-end tests](16-end-to-end-tests.md): the full guest journey over HTTP
17. 📝 [Docker image](17-docker-image.md): production container
18. 📝 [AWS infrastructure](18-aws-infrastructure.md): Terraform for VPC, RDS, ECS, ALB
19. 📝 [Deploy pipeline](19-deploy-pipeline.md): merge → staging, tag → production
20. 📝 [Release v1.0.0](20-release-v1.md): production, docs, demo

**Phase 2 (optional)**

21. 📝 [Spa](21-spa.md)
22. 📝 [Concierge](22-concierge.md)
23. 📝 [Loyalty](23-loyalty.md)
24. 📝 [Reports](24-reports.md): occupancy, ADR, RevPAR

---

## Conventions

- **Imports are left out of Java code.** Paste the class, then press **⌥ + Enter** on each red name → **Import class**.
- **Terminal** means IntelliJ's Terminal tab (**⌥ + F12**) in the project folder.
- When IntelliJ asks **"Add file to Git?"**, click **Cancel**. We stage with `git add`.
- **Every module follows the same order:** migration → enum → entity → repository → DTOs → mapper → service → controller → tests → try in Swagger → commit.

## The Git loop (repeat for every step)

1. Start a branch from the latest `main`:
   ```bash
   git switch main && git pull
   git switch -c feature/step-N-short-name
   ```

2. After each piece that works, commit it:
   ```bash
   git status
   git add <files>
   git commit -m "type: Step N – what was done" -m "- key command or check"
   git push -u origin feature/step-N-short-name
   ```
   - *`git status` must never list `.env` or `*.tfstate`.*
   - *Types: `feat`, `fix`, `build`, `ci`, `test`, `docs`, `refactor`, `chore`.*

3. Tick the step in `README.md`, then on github.com open a pull request → wait for the green **build** check → **Squash and merge → Confirm**.

4. Clean up locally:
   ```bash
   git switch main && git pull
   git branch -D feature/step-N-short-name
   ```
   - *Capital `-D` is needed after a squash merge.*
