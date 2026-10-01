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

Step-by-step build instructions: [docs/documentation](docs/documentation/00-index.md)
- [x] Step 1 – Machine setup
- [x] Step 2 – Project skeleton, GitHub, PR templates
- [x] Step 3 – PostgreSQL with Docker Compose and Flyway
- [ ] Step 4 – Foundation code
- [ ] Step 5 – Rooms module
- [ ] Step 6 – CI with GitHub Actions and 80% coverage gate
- [ ] Step 7 – JWT security and roles
- [ ] Step 8 – Logging and production profile
- [ ] Step 9 – Guests
- [ ] Step 10 – Reservations
- [ ] Step 11 – Billing
- [ ] Step 12 – Front desk
- [ ] Step 13 – Staff
- [ ] Step 14 – Housekeeping
- [ ] Step 15 – Room service and restaurant
- [ ] Step 16 – End-to-end tests
- [ ] Step 17 – Production Docker image
- [ ] Step 18 – AWS infrastructure
- [ ] Step 19 – Deploy pipeline
- [ ] Step 20 – Release v1.0.0
- [ ] Steps 21–24 – Phase 2: spa, concierge, loyalty, reports

## Commit convention

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) and reference the build step, so the history reads as a step-by-step log:

```
<type>: Step <n>.<item> – <what was done>
```

Types used: `feat`, `fix`, `build`, `test`, `docs`, `chore`, `ci`, `refactor`.