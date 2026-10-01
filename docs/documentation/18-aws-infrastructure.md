# Step 18 Documentation — AWS infrastructure with Terraform

Describe the whole AWS setup in code, so one command creates it and another destroys it: network, database, container registry, load balancer, ECS service, secrets, logs and a deploy role for GitHub.

```
Internet → Load balancer (public) → ECS Fargate task (Spring Boot) → RDS PostgreSQL (private)
                                       ↑ image from ECR    ↑ secrets from Secrets Manager    → logs to CloudWatch
```

> **Cost:** staging costs roughly **$55–70 a month while it runs** (load balancer, one Fargate task, a small database, public IPs). New AWS accounts get some RDS hours free for 12 months. Run `terraform destroy` (step 16) when you're not using it.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c infra/step-18-aws
   ```

2. Install Terraform and check AWS access:
   ```bash
   brew tap hashicorp/tap && brew install hashicorp/tap/terraform
   terraform -version
   aws configure                      # access key of your IAM admin user, region us-east-1
   aws sts get-caller-identity        # prints your account id
   ```
   - *Never use the AWS **root** user's keys; use the IAM admin user from the Step 1 documentation.*

3. Let the app create its first admin in production (there are no dev users there). Create `config/AdminBootstrap.java`:
   ```java
   package com.luxstay.hms.config;

   @Slf4j
   @Component
   @RequiredArgsConstructor
   public class AdminBootstrap implements CommandLineRunner {

       private final AppUserRepository userRepository;
       private final PasswordEncoder passwordEncoder;

       @Value("${app.admin.email:}")
       private String email;

       @Value("${app.admin.password:}")
       private String password;

       @Override
       public void run(String... args) {
           if (email.isBlank() || password.isBlank() || userRepository.existsByEmail(email)) {
               return;
           }
           AppUser admin = new AppUser();
           admin.setEmail(email);
           admin.setPasswordHash(passwordEncoder.encode(password));
           admin.setRole(Role.ADMIN);
           userRepository.save(admin);
           log.info("Bootstrap admin user created");
       }
   }
   ```
   And under `app:` in `application.yaml`:
   ```yaml
     admin:
       email: ${APP_ADMIN_EMAIL:}
       password: ${APP_ADMIN_PASSWORD:}
   ```
   - *It does nothing unless both variables are set, and only once. AWS provides them from Secrets Manager.*

4. Keep Terraform's local files out of Git. Append to `.gitignore`:
   ```
   ### Terraform ###
   infra/.terraform/
   infra/terraform.tfstate*
   infra/terraform.tfstate.d/
   ```
   - *The state file records everything Terraform created, **including passwords**. Never commit it.*
   - *Do commit `infra/.terraform.lock.hcl`; it pins provider versions.*

5. Create `infra/versions.tf`:
   ```hcl
   terraform {
     required_version = ">= 1.6"
     required_providers {
       aws    = { source = "hashicorp/aws", version = "~> 6.0" }
       random = { source = "hashicorp/random", version = "~> 3.6" }
     }
   }

   provider "aws" {
     region = var.region
     default_tags {
       tags = {
         Project     = "luxstay-hms"
         Environment = var.environment
         ManagedBy   = "terraform"
       }
     }
   }
   ```

6. Create `infra/variables.tf`:
   ```hcl
   variable "region"            { default = "us-east-1" }
   variable "app_name"          { default = "luxstay" }
   variable "environment"       { type = string }               # staging | prod
   variable "github_repo"       { default = "polyglotarist/luxstay-hms" }
   variable "github_environment" { type = string }              # staging | production
   variable "create_github_oidc_provider" { default = true }    # only one per AWS account
   variable "db_instance_class" { default = "db.t4g.micro" }
   variable "desired_count"     { default = 1 }
   variable "task_cpu"          { default = 512 }               # 0.5 vCPU
   variable "task_memory"       { default = 1024 }              # 1 GB
   variable "swagger_enabled"   { default = false }
   variable "admin_email"       { default = "admin@luxstay.example" }
   variable "certificate_arn"   { default = "" }                # set to enable HTTPS

   locals {
     name = "${var.app_name}-${var.environment}"
   }
   ```
   - ***You need to change** `github_repo` and `admin_email` to your own!*

7. Create `infra/network.tf`:
   ```hcl
   data "aws_availability_zones" "available" {
     state = "available"
   }

   module "vpc" {
     source  = "terraform-aws-modules/vpc/aws"
     version = "~> 6.0"

     name             = local.name
     cidr             = "10.0.0.0/16"
     azs              = slice(data.aws_availability_zones.available.names, 0, 2)
     public_subnets   = ["10.0.1.0/24", "10.0.2.0/24"]
     database_subnets = ["10.0.21.0/24", "10.0.22.0/24"]

     create_database_subnet_group = true
     enable_nat_gateway           = false
     enable_dns_hostnames         = true
   }

   resource "aws_security_group" "alb" {
     name   = "${local.name}-alb"
     vpc_id = module.vpc.vpc_id

     ingress {
       from_port   = 80
       to_port     = 80
       protocol    = "tcp"
       cidr_blocks = ["0.0.0.0/0"]
     }
     ingress {
       from_port   = 443
       to_port     = 443
       protocol    = "tcp"
       cidr_blocks = ["0.0.0.0/0"]
     }
     egress {
       from_port   = 0
       to_port     = 0
       protocol    = "-1"
       cidr_blocks = ["0.0.0.0/0"]
     }
   }

   resource "aws_security_group" "app" {
     name   = "${local.name}-app"
     vpc_id = module.vpc.vpc_id

     ingress {
       from_port       = 8080
       to_port         = 8080
       protocol        = "tcp"
       security_groups = [aws_security_group.alb.id]
     }
     egress {
       from_port   = 0
       to_port     = 0
       protocol    = "-1"
       cidr_blocks = ["0.0.0.0/0"]
     }
   }

   resource "aws_security_group" "db" {
     name   = "${local.name}-db"
     vpc_id = module.vpc.vpc_id

     ingress {
       from_port       = 5432
       to_port         = 5432
       protocol        = "tcp"
       security_groups = [aws_security_group.app.id]
     }
   }
   ```
   - *Two Availability Zones (separate data centres), so one failing doesn't take the app down.*
   - *Only the load balancer accepts traffic from the internet; only the app can reach the database.*
   - *No NAT gateway (about $32/month saved): tasks sit in public subnets with a public IP, but their security group only lets the load balancer in.*

8. Create `infra/ecr.tf`:
   ```hcl
   resource "aws_ecr_repository" "app" {
     name                 = local.name
     image_tag_mutability = "MUTABLE"
     force_delete         = true

     image_scanning_configuration {
       scan_on_push = true
     }
   }

   resource "aws_ecr_lifecycle_policy" "app" {
     repository = aws_ecr_repository.app.name
     policy = jsonencode({
       rules = [{
         rulePriority = 1
         description  = "Keep the last 10 images"
         selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
         action       = { type = "expire" }
       }]
     })
   }
   ```
   - *ECR is AWS's private Docker registry; every pushed image is scanned for known vulnerabilities.*

9. Create `infra/database.tf`:
   ```hcl
   resource "aws_db_instance" "main" {
     identifier        = local.name
     engine            = "postgres"
     engine_version    = "16"
     instance_class    = var.db_instance_class
     allocated_storage = 20
     storage_encrypted = true

     db_name                     = "luxstay"
     username                    = "luxstay"
     manage_master_user_password = true

     db_subnet_group_name   = module.vpc.database_subnet_group_name
     vpc_security_group_ids = [aws_security_group.db.id]
     publicly_accessible    = false

     backup_retention_period   = 7
     multi_az                  = var.environment == "prod"
     deletion_protection       = var.environment == "prod"
     skip_final_snapshot       = var.environment != "prod"
     final_snapshot_identifier = "${local.name}-final"
   }
   ```
   - *`manage_master_user_password` makes AWS generate the password and keep it in Secrets Manager. Nobody types or sees it.*
   - *Production gets a standby copy in a second zone (`multi_az`) and can't be deleted by accident.*

10. Create `infra/secrets.tf`:
    ```hcl
    resource "random_bytes" "jwt" {
      length = 32
    }

    resource "aws_secretsmanager_secret" "jwt" {
      name                    = "${local.name}/jwt-secret"
      recovery_window_in_days = 0
    }

    resource "aws_secretsmanager_secret_version" "jwt" {
      secret_id     = aws_secretsmanager_secret.jwt.id
      secret_string = random_bytes.jwt.base64
    }

    resource "random_password" "admin" {
      length  = 20
      special = false
    }

    resource "aws_secretsmanager_secret" "admin" {
      name                    = "${local.name}/admin-password"
      recovery_window_in_days = 0
    }

    resource "aws_secretsmanager_secret_version" "admin" {
      secret_id     = aws_secretsmanager_secret.admin.id
      secret_string = random_password.admin.result
    }
    ```
    - *The JWT key is 32 random bytes in Base64, exactly what `openssl rand -base64 32` made locally.*
    - *`recovery_window_in_days = 0` lets you destroy and recreate without waiting 7 days.*

11. Create `infra/ecs.tf`:
    ```hcl
    resource "aws_cloudwatch_log_group" "app" {
      name              = "/ecs/${local.name}"
      retention_in_days = 30
    }

    data "aws_iam_policy_document" "ecs_tasks_assume" {
      statement {
        actions = ["sts:AssumeRole"]
        principals {
          type        = "Service"
          identifiers = ["ecs-tasks.amazonaws.com"]
        }
      }
    }

    resource "aws_iam_role" "execution" {
      name               = "${local.name}-execution"
      assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
    }

    resource "aws_iam_role_policy_attachment" "execution" {
      role       = aws_iam_role.execution.name
      policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
    }

    resource "aws_iam_role_policy" "execution_secrets" {
      role = aws_iam_role.execution.id
      policy = jsonencode({
        Version = "2012-10-17"
        Statement = [{
          Effect = "Allow"
          Action = ["secretsmanager:GetSecretValue"]
          Resource = [
            aws_db_instance.main.master_user_secret[0].secret_arn,
            aws_secretsmanager_secret.jwt.arn,
            aws_secretsmanager_secret.admin.arn,
          ]
        }]
      })
    }

    resource "aws_ecs_cluster" "main" {
      name = local.name
    }

    resource "aws_ecs_task_definition" "app" {
      family                   = "${local.name}-app"
      requires_compatibilities = ["FARGATE"]
      network_mode             = "awsvpc"
      cpu                      = var.task_cpu
      memory                   = var.task_memory
      execution_role_arn       = aws_iam_role.execution.arn

      runtime_platform {
        operating_system_family = "LINUX"
        cpu_architecture        = "X86_64"
      }

      container_definitions = jsonencode([{
        name         = "app"
        image        = "${aws_ecr_repository.app.repository_url}:latest"
        essential    = true
        portMappings = [{ containerPort = 8080, protocol = "tcp" }]
        environment = [
          { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
          { name = "DB_HOST", value = aws_db_instance.main.address },
          { name = "DB_NAME", value = aws_db_instance.main.db_name },
          { name = "DB_USERNAME", value = aws_db_instance.main.username },
          { name = "SWAGGER_ENABLED", value = tostring(var.swagger_enabled) },
          { name = "APP_ADMIN_EMAIL", value = var.admin_email },
        ]
        secrets = [
          { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
          { name = "APP_JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn },
          { name = "APP_ADMIN_PASSWORD", valueFrom = aws_secretsmanager_secret.admin.arn },
        ]
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group"         = aws_cloudwatch_log_group.app.name
            "awslogs-region"        = var.region
            "awslogs-stream-prefix" = "app"
          }
        }
      }])
    }

    resource "aws_ecs_service" "app" {
      name                              = "${local.name}-app"
      cluster                           = aws_ecs_cluster.main.id
      task_definition                   = aws_ecs_task_definition.app.arn
      desired_count                     = var.desired_count
      launch_type                       = "FARGATE"
      health_check_grace_period_seconds = 120

      network_configuration {
        subnets          = module.vpc.public_subnets
        security_groups  = [aws_security_group.app.id]
        assign_public_ip = true
      }

      load_balancer {
        target_group_arn = aws_lb_target_group.app.arn
        container_name   = "app"
        container_port   = 8080
      }

      deployment_circuit_breaker {
        enable   = true
        rollback = true
      }

      lifecycle {
        ignore_changes = [task_definition, desired_count]
      }

      depends_on = [aws_lb_listener.http]
    }
    ```
    - *The **execution role** lets ECS pull the image, read the three secrets and write logs, and nothing else.*
    - *`secrets` are injected as environment variables when the container starts, so they never appear in the task definition.*
    - *`ignore_changes = [task_definition]`: after the first apply, GitHub Actions deploys new versions (Step 19); Terraform won't undo them.*
    - *The circuit breaker rolls back automatically if a new version fails its health checks.*

12. Create `infra/load_balancer.tf`:
    ```hcl
    resource "aws_lb" "main" {
      name               = local.name
      load_balancer_type = "application"
      security_groups    = [aws_security_group.alb.id]
      subnets            = module.vpc.public_subnets
    }

    resource "aws_lb_target_group" "app" {
      name                 = local.name
      port                 = 8080
      protocol             = "HTTP"
      target_type          = "ip"
      vpc_id               = module.vpc.vpc_id
      deregistration_delay = 30

      health_check {
        path                = "/actuator/health"
        matcher             = "200"
        interval            = 30
        healthy_threshold   = 2
        unhealthy_threshold = 3
      }
    }

    resource "aws_lb_listener" "http" {
      load_balancer_arn = aws_lb.main.arn
      port              = 80
      protocol          = "HTTP"

      default_action {
        type             = "forward"
        target_group_arn = aws_lb_target_group.app.arn
      }
    }

    resource "aws_lb_listener" "https" {
      count             = var.certificate_arn == "" ? 0 : 1
      load_balancer_arn = aws_lb.main.arn
      port              = 443
      protocol          = "HTTPS"
      ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
      certificate_arn   = var.certificate_arn

      default_action {
        type             = "forward"
        target_group_arn = aws_lb_target_group.app.arn
      }
    }
    ```
    - *HTTPS needs a domain name. Without one, staging runs on plain HTTP at the load balancer's address; see step 17.*

13. Create `infra/monitoring.tf`:
    ```hcl
    resource "aws_cloudwatch_metric_alarm" "http_5xx" {
      alarm_name          = "${local.name}-5xx"
      namespace           = "AWS/ApplicationELB"
      metric_name         = "HTTPCode_Target_5XX_Count"
      dimensions          = { LoadBalancer = aws_lb.main.arn_suffix }
      statistic           = "Sum"
      period              = 300
      evaluation_periods  = 1
      threshold           = 10
      comparison_operator = "GreaterThanThreshold"
      treat_missing_data  = "notBreaching"
    }

    resource "aws_cloudwatch_metric_alarm" "unhealthy" {
      alarm_name          = "${local.name}-unhealthy-hosts"
      namespace           = "AWS/ApplicationELB"
      metric_name         = "UnHealthyHostCount"
      dimensions = {
        LoadBalancer = aws_lb.main.arn_suffix
        TargetGroup  = aws_lb_target_group.app.arn_suffix
      }
      statistic           = "Maximum"
      period              = 60
      evaluation_periods  = 3
      threshold           = 0
      comparison_operator = "GreaterThanThreshold"
    }

    resource "aws_appautoscaling_target" "app" {
      count              = var.environment == "prod" ? 1 : 0
      min_capacity       = 2
      max_capacity       = 4
      resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
      scalable_dimension = "ecs:service:DesiredCount"
      service_namespace  = "ecs"
    }

    resource "aws_appautoscaling_policy" "cpu" {
      count              = var.environment == "prod" ? 1 : 0
      name               = "${local.name}-cpu"
      policy_type        = "TargetTrackingScaling"
      resource_id        = aws_appautoscaling_target.app[0].resource_id
      scalable_dimension = aws_appautoscaling_target.app[0].scalable_dimension
      service_namespace  = aws_appautoscaling_target.app[0].service_namespace

      target_tracking_scaling_policy_configuration {
        target_value = 70
        predefined_metric_specification {
          predefined_metric_type = "ECSServiceAverageCPUUtilization"
        }
      }
    }
    ```
    - *Alarms turn red in **CloudWatch → Alarms** when there are more than 10 server errors in 5 minutes, or when a task fails health checks.*
    - *Production adds tasks when average CPU goes above 70%, between 2 and 4 tasks.*

14. Create `infra/github_oidc.tf`:
    ```hcl
    resource "aws_iam_openid_connect_provider" "github" {
      count          = var.create_github_oidc_provider ? 1 : 0
      url            = "https://token.actions.githubusercontent.com"
      client_id_list = ["sts.amazonaws.com"]
    }

    data "aws_iam_openid_connect_provider" "github" {
      count = var.create_github_oidc_provider ? 0 : 1
      url   = "https://token.actions.githubusercontent.com"
    }

    locals {
      github_oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
    }

    data "aws_iam_policy_document" "github_assume" {
      statement {
        actions = ["sts:AssumeRoleWithWebIdentity"]
        principals {
          type        = "Federated"
          identifiers = [local.github_oidc_arn]
        }
        condition {
          test     = "StringEquals"
          variable = "token.actions.githubusercontent.com:aud"
          values   = ["sts.amazonaws.com"]
        }
        condition {
          test     = "StringEquals"
          variable = "token.actions.githubusercontent.com:sub"
          values   = ["repo:${var.github_repo}:environment:${var.github_environment}"]
        }
      }
    }

    resource "aws_iam_role" "github_deploy" {
      name               = "${local.name}-github-deploy"
      assume_role_policy = data.aws_iam_policy_document.github_assume.json
    }

    resource "aws_iam_role_policy" "github_deploy" {
      role = aws_iam_role.github_deploy.id
      policy = jsonencode({
        Version = "2012-10-17"
        Statement = [
          { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
          {
            Effect = "Allow"
            Action = [
              "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
              "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
            ]
            Resource = aws_ecr_repository.app.arn
          },
          { Effect = "Allow", Action = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"], Resource = "*" },
          { Effect = "Allow", Action = ["ecs:UpdateService", "ecs:DescribeServices"], Resource = aws_ecs_service.app.id },
          { Effect = "Allow", Action = "iam:PassRole", Resource = aws_iam_role.execution.arn },
        ]
      })
    }
    ```
    - *OIDC lets a GitHub Actions run prove "I'm repo X, environment Y" and get **temporary** AWS credentials. No AWS keys are stored in GitHub.*
    - *The role can push images and update this one service, and nothing else.*

15. Create `infra/outputs.tf` and the environment files:
    ```hcl
    output "app_url"            { value = "http://${aws_lb.main.dns_name}" }
    output "ecr_repository_url" { value = aws_ecr_repository.app.repository_url }
    output "ecr_repository"     { value = aws_ecr_repository.app.name }
    output "ecs_cluster"        { value = aws_ecs_cluster.main.name }
    output "ecs_service"        { value = aws_ecs_service.app.name }
    output "task_family"        { value = aws_ecs_task_definition.app.family }
    output "deploy_role_arn"    { value = aws_iam_role.github_deploy.arn }
    output "admin_secret_name"  { value = aws_secretsmanager_secret.admin.name }
    ```
    `infra/envs/staging.tfvars`:
    ```hcl
    environment                 = "staging"
    github_environment          = "staging"
    create_github_oidc_provider = true
    desired_count               = 1
    swagger_enabled             = true
    ```
    `infra/envs/prod.tfvars`:
    ```hcl
    environment                 = "prod"
    github_environment          = "production"
    create_github_oidc_provider = false
    desired_count               = 2
    swagger_enabled             = false
    db_instance_class           = "db.t4g.small"
    ```

16. Create staging. First the registry, then push one image, then everything else:
    ```bash
    cd infra
    terraform init
    terraform workspace new staging
    terraform apply -var-file=envs/staging.tfvars -target=aws_ecr_repository.app
    ```
    ```bash
    cd ..
    REPO=$(terraform -chdir=infra output -raw ecr_repository_url)
    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${REPO%%/*}
    docker build --platform linux/amd64 -t $REPO:latest .
    docker push $REPO:latest
    ```
    ```bash
    cd infra
    terraform plan  -var-file=envs/staging.tfvars
    terraform apply -var-file=envs/staging.tfvars
    ```
    - *Workspaces keep a separate state per environment: `terraform workspace select staging` or `prod`.*
    - *`--platform linux/amd64` is needed because your Mac is ARM and Fargate runs x86 here.*
    - *The full apply takes 10–15 minutes, mostly the database. **Read the plan** before typing `yes`.*
    - *To remove everything: `terraform destroy -var-file=envs/staging.tfvars`.*

17. Check it's live:
    ```bash
    URL=$(terraform output -raw app_url)
    curl $URL/actuator/health
    aws secretsmanager get-secret-value --secret-id $(terraform output -raw admin_secret_name) --query SecretString --output text
    ```
    - *Health should say `{"status":"UP"}`. If not, see **ECS → Clusters → luxstay-staging → Tasks → Logs**, or **CloudWatch → Log groups → /ecs/luxstay-staging**.*
    - *Open `$URL/swagger-ui.html` and log in with `admin_email` and the printed password.*
    - *HTTPS (optional): buy a domain in **Route 53**, request a certificate in **ACM** for `staging.yourdomain.com`, add `certificate_arn = "arn:…"` to `staging.tfvars`, apply, then point the domain at the load balancer.*

18. Inspect the AWS database with DBeaver (optional): RDS isn't reachable from the internet, so start a tunnel through a temporary EC2 instance with the SSM agent:
    ```bash
    aws ssm start-session --target <ec2-instance-id> \
      --document-name AWS-StartPortForwardingSessionToRemoteHost \
      --parameters host=<rds-endpoint>,portNumber=5432,localPortNumber=15432
    ```
    - *In DBeaver connect to `localhost:15432`. Get the password with `aws secretsmanager get-secret-value` on the RDS secret.*

19. Commit, tick Step 18, push and merge:
    ```bash
    cd ..
    git add infra .gitignore src README.md
    git status                    # no *.tfstate files may appear
    git commit -m "build: Step 18 – AWS staging with Terraform: VPC, RDS, ECR, ALB, ECS Fargate, secrets, OIDC" -m "- terraform workspace staging; apply ECR first, push linux/amd64 image, then full apply
    - Secrets Manager: RDS-managed DB password, JWT key, bootstrap admin password
    - GitHub deploy role via OIDC, limited to this repo + environment
    - Destroy when idle: terraform destroy -var-file=envs/staging.tfvars"
    git push -u origin infra/step-18-aws
    ```
    - *PR title: `build: Step 18 – AWS infrastructure`.*
