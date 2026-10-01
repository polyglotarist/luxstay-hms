# Cheat Sheet 20 — Production and release v1.0.0

Create the production environment, document the project, tag `v1.0.0`, approve the production deployment and publish the release.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c docs/step-20-release
   ```

2. Create production infrastructure (same as staging, other workspace):
   ```bash
   cd infra
   terraform workspace new prod
   terraform apply -var-file=envs/prod.tfvars -target=aws_ecr_repository.app
   cd ..
   REPO=$(terraform -chdir=infra output -raw ecr_repository_url)
   aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${REPO%%/*}
   docker build --platform linux/amd64 -t $REPO:latest .
   docker push $REPO:latest
   cd infra
   terraform apply -var-file=envs/prod.tfvars
   cd ..
   ```
   - *`create_github_oidc_provider = false` in `prod.tfvars` reuses the GitHub connection staging created; an AWS account can have only one.*
   - *Production runs 2 tasks, a Multi-AZ database and no Swagger, so it costs roughly twice staging. Destroy it after the demo if it's only for your portfolio.*

3. On github.com: **Settings → Environments → New environment** → `production`:
   - Add the same 7 variables as staging, using `terraform output` from the **prod** workspace (`terraform workspace select prod`).
   - Tick **Required reviewers** and add yourself.
   - Under **Deployment branches and tags** → **Selected branches and tags** → add the tag rule `v*`.
   - *Now production deploys only from version tags, and only after you click **Approve**.*
   - *Required reviewers on a **private** repository needs a paid GitHub plan; on a public repository it's free.*

4. Add an architecture diagram to `README.md`. GitHub draws Mermaid diagrams automatically:
   ````markdown
   ## Architecture

   ```mermaid
   flowchart LR
       Dev[Developer] -->|pull request| GH[GitHub]
       GH -->|Actions: test + build| ECR[(Amazon ECR)]
       User[Staff / Guests] -->|HTTPS| ALB[Load balancer]
       ALB --> ECS[ECS Fargate<br/>Spring Boot]
       ECR --> ECS
       ECS --> RDS[(RDS PostgreSQL)]
       ECS --> SM[Secrets Manager]
       ECS --> CW[CloudWatch logs]
   ```
   ````

5. Add the database diagram: in DBeaver, right-click **public** → **View Diagram**, then **File → Save As** → `docs/er-diagram.png`. Reference it in `README.md`:
   ```markdown
   ## Data model

   ![ER diagram](docs/er-diagram.png)
   ```

6. Finish `README.md`:
   - **Live demo:** the staging URL and `/swagger-ui.html`
   - **Swagger walkthrough:** log in → Authorize → create guest → book → check in → charge → pay → check out
   - **Running tests:** `./mvnw verify`, with the coverage report location
   - **Deploying:** merge to `main` → staging; tag `vX.Y.Z` → production (approval required)
   - **Tearing down:** `terraform destroy -var-file=envs/<env>.tfvars` in each workspace
   - Tick every remaining Progress item.

7. Walk through the spec's acceptance checklist and fix anything missing:
   - [ ] `./mvnw verify` passes from a clean clone; coverage ≥ 80%
   - [ ] Required folders: `entity`, `repository`, `service`, `service/impl`, `dto/request`, `dto/response`, `controller`, `mapper`, `exception`, `config`, `enums`, `security`
   - [ ] No controller returns an entity
   - [ ] Every endpoint is in Swagger, including **Authorize**
   - [ ] JSON logs with correlation IDs in production; no secrets in logs
   - [ ] `docker compose up -d --build` works on a clean machine
   - [ ] CI runs on every PR; `main` deploys to staging automatically
   - [ ] Staging answers `/actuator/health` with `UP`
   - [ ] `main` is protected; history uses Conventional Commits
   - [ ] `git log -p | grep -i "password\|secret"` shows no real secrets

8. Commit and merge the documentation:
   ```bash
   git add README.md docs
   git commit -m "docs: Step 20 – architecture and ER diagrams, Swagger walkthrough, deployment guide"
   git push -u origin docs/step-20-release
   ```
   - *Merge it with **Squash and merge**. Staging redeploys automatically.*

9. Tag the release:
   ```bash
   git switch main && git pull
   git tag -a v1.0.0 -m "LuxStay HMS v1.0.0 – MVP modules, 80%+ coverage, deployed on AWS"
   git push origin v1.0.0
   ```
   - *The tag starts the **Deploy** workflow for the `production` environment.*

10. Approve production: **Actions → Deploy (v1.0.0) → Review deployments → production → Approve and deploy**.
    - *After about 10 minutes, the production URL's `/actuator/health` says `UP`.*
    - *Get the production admin password with `aws secretsmanager get-secret-value --secret-id luxstay-prod/admin-password …`.*

11. Publish the GitHub release: **Releases → Draft a new release → Choose a tag: v1.0.0 → Generate release notes → Publish release**.
    - *The generated notes list every merged pull request, which reads as the build story from Step 2 to Step 20.*

12. Record a 10-minute demo of the full guest journey in Swagger, from login to the housekeeping task after check-out. Link it in the README.

13. Now you have a tested, documented hotel management system running on AWS, with a commit history and cheat sheets that show how it was built step by step!
