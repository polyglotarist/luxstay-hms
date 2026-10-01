# LuxStay HMS

A backend REST platform that simulates the software of a modern luxury hotel: reservations, guest profiles, rooms, front desk, housekeeping, billing, dining and more.

> **Status:** early development — project skeleton and dependencies are in place. See the [progress checklist](#progress).

## Tech stack

| Area | Technology |
| --- | --- |
| Language | Java 21 (Amazon Corretto) |
| Framework | Spring Boot 4.1 (Web, Data JPA, Validation, Security, Actuator) |
| Build | Maven (wrapper included) |
| Database | PostgreSQL 16, Flyway migrations |
| API docs | Swagger UI via springdoc-openapi 3 |
| Mapping | MapStruct + Lombok |
| Security | Spring Security with JWT (OAuth2 Resource Server) |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers, REST Assured, JaCoCo (≥ 80% coverage) |
| Containers | Docker, Docker Compose |
| CI/CD | GitHub Actions |
| Cloud | AWS (ECR, ECS Fargate, RDS, CloudWatch) |

## Prerequisites

- JDK 21 (Amazon Corretto recommended)
- Docker Desktop
- Git
- IntelliJ IDEA (Community Edition works) and DBeaver for database inspection

## Getting started

```bash
git clone https://github.com/<your-username>/luxstay-hms.git
cd luxstay-hms
./mvnw compile
```

```bash
   cp .env.example .env              # then set POSTGRES_PASSWORD
   docker compose up -d postgres     # start PostgreSQL 16
   ./mvnw spring-boot:run            # Flyway creates and seeds the tables
```
## Project structure

```
src/main/java/com/luxstay/hms/
├── config/        # Security, OpenAPI, CORS configuration
├── controller/    # REST controllers (DTOs in, DTOs out)
├── dto/request/   # Incoming payloads with validation
├── dto/response/  # Outgoing payloads
├── entity/        # JPA entities
├── enums/         # Status and role enums
├── exception/     # Custom exceptions + global handler
├── mapper/        # MapStruct entity <-> DTO mappers
├── repository/    # Spring Data JPA repositories
├── security/      # JWT and user details
├── service/       # Service interfaces
│   └── impl/      # Service implementations
├── util/          # Helpers
└── integration/   # Mock payment, key-card and channel-manager services
```

## Progress

- [x] Step 2.1 – Generate project with Spring Initializr
- [x] Step 2.6 – Add Swagger, JWT, MapStruct and REST Assured dependencies
- [x] Step 2.9 – Add README
- [x] Step 2.10 – Protect `main` branch, PR and issue templates
- [x] Step 3 – PostgreSQL with Docker Compose and Flyway
- [ ] Step 4 – CI with GitHub Actions and 80% coverage gate
- [ ] Step 5 – Cross-cutting code: base entity, errors, logging, Swagger config
- [ ] Steps 6–14 – Security and hotel modules
- [ ] Steps 15–16 – End-to-end tests and production Docker image
- [ ] Steps 17–18 – AWS deployment
- [ ] Step 19 – Release v1.0.0

## Commit convention

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) and reference the build step, so the history reads as a step-by-step log:

```
<type>: Step <n>.<item> – <what was done>
```

Types used: `feat`, `fix`, `build`, `test`, `docs`, `chore`, `ci`, `refactor`.