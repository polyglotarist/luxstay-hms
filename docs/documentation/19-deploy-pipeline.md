# Step 19 Documentation — Automatic deployment with GitHub Actions

Every merge to `main` is tested, packaged and deployed to staging automatically. A version tag (`v1.0.0`) deploys to production after your approval.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c ci/step-19-deploy
   ```

2. On github.com: **Settings → Environments → New environment** → `staging` → **Configure environment**.
   Under **Environment variables**, add (values from `terraform output` in the `staging` workspace):

   | Name | Value |
   | --- | --- |
   | `AWS_REGION` | `us-east-1` |
   | `AWS_DEPLOY_ROLE_ARN` | `deploy_role_arn` |
   | `ECR_REPOSITORY` | `ecr_repository` |
   | `ECS_CLUSTER` | `ecs_cluster` |
   | `ECS_SERVICE` | `ecs_service` |
   | `TASK_FAMILY` | `task_family` |
   | `APP_URL` | `app_url` |

   - *These are environment-scoped: production gets its own set with the same names in Step 20, and the workflow picks the right one.*
   - *None of these are secrets. AWS access comes from the OIDC role, not stored keys.*
   - *Faster with the GitHub CLI (`brew install gh`, then `gh auth login`), from `infra/`:*
     ```bash
     for v in ecs_cluster ecs_service task_family app_url ecr_repository; do
       gh variable set $(echo $v | tr a-z A-Z) --env staging --body "$(terraform output -raw $v)"
     done
     gh variable set AWS_DEPLOY_ROLE_ARN --env staging --body "$(terraform output -raw deploy_role_arn)"
     gh variable set AWS_REGION --env staging --body us-east-1
     ```

3. Create `.github/workflows/deploy.yml`:
   ```yaml
   name: Deploy

   on:
     push:
       branches: [main]
       tags: ['v*']

   permissions:
     id-token: write      # lets the job request an OIDC token from GitHub
     contents: read

   concurrency:
     group: deploy-${{ github.ref }}
     cancel-in-progress: false

   jobs:
     deploy:
       runs-on: ubuntu-latest
       environment: ${{ startsWith(github.ref, 'refs/tags/v') && 'production' || 'staging' }}
       steps:
         - uses: actions/checkout@v4

         - uses: actions/setup-java@v4
           with:
             distribution: corretto
             java-version: '21'
             cache: maven

         - name: Verify (never deploy untested code)
           run: ./mvnw -B verify

         - name: Log in to AWS with OIDC
           uses: aws-actions/configure-aws-credentials@v4
           with:
             role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}
             aws-region: ${{ vars.AWS_REGION }}

         - id: ecr
           uses: aws-actions/amazon-ecr-login@v2

         - id: image
           name: Build and push the image
           env:
             REGISTRY: ${{ steps.ecr.outputs.registry }}
           run: |
             IMAGE="$REGISTRY/${{ vars.ECR_REPOSITORY }}:${{ github.sha }}"
             docker build -t "$IMAGE" .
             docker push "$IMAGE"
             echo "image=$IMAGE" >> "$GITHUB_OUTPUT"

         - name: Download the current task definition
           run: aws ecs describe-task-definition --task-definition ${{ vars.TASK_FAMILY }} --query taskDefinition > task-def.json

         - id: taskdef
           uses: aws-actions/amazon-ecs-render-task-definition@v1
           with:
             task-definition: task-def.json
             container-name: app
             image: ${{ steps.image.outputs.image }}

         - name: Deploy to ECS and wait until healthy
           uses: aws-actions/amazon-ecs-deploy-task-definition@v2
           with:
             task-definition: ${{ steps.taskdef.outputs.task-definition }}
             service: ${{ vars.ECS_SERVICE }}
             cluster: ${{ vars.ECS_CLUSTER }}
             wait-for-service-stability: true

         - name: Smoke test
           run: |
             curl --fail --silent --retry 10 --retry-delay 10 --retry-all-errors \
               "${{ vars.APP_URL }}/actuator/health" | grep '"status":"UP"'
   ```
   - *Each image is tagged with the commit SHA, so you always know exactly which code is running.*
   - *"Render" copies the current task definition with only the image changed; "deploy" registers it and rolls it out task by task.*
   - *If the new version fails health checks, ECS rolls back by itself (the circuit breaker from Step 18) and this job fails red.*

4. Commit, push and open the pull request:
   ```bash
   git add .github
   git commit -m "ci: Step 19 – deploy main to staging and v* tags to production via OIDC" -m "- Environment chosen from the ref: main -> staging, v* tag -> production
   - Image tagged with the commit SHA; ECS rolling deploy waits for stability; smoke test on /actuator/health
   - Environment variables come from terraform output (gh variable set ... --env staging)"
   git push -u origin ci/step-19-deploy
   ```
   - *The deploy doesn't run on the pull request itself, only after merging.*

5. **Squash and merge**, then watch **Actions → Deploy**:
   - *The run takes about 8–12 minutes: tests, image build, then the rollout.*
   - *If **Log in to AWS** fails with `Not authorized to perform sts:AssumeRoleWithWebIdentity`, the `github_repo` or environment name in Terraform doesn't match the repository exactly.*

6. Check staging runs the new version:
   ```bash
   aws ecs describe-services --cluster luxstay-staging --services luxstay-staging-app \
     --query "services[0].deployments[0].taskDefinition"
   curl http://<app_url>/actuator/health
   ```

7. From now on, every merged pull request reaches staging automatically. Tick Step 19 in `README.md` and add the staging URL there (next pull request).
