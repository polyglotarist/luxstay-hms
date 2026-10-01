# Cheat Sheet 22 — Concierge requests (Phase 2)

Track guest requests (airport transfers, tours, theatre tickets, restaurant bookings elsewhere) from "new" to "done", with due times.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-22-concierge
   ```

2. Create `db/migration/V12__create_concierge_request.sql`:
   ```sql
   CREATE TABLE concierge_request (
       id              BIGSERIAL     PRIMARY KEY,
       guest_id        BIGINT        NOT NULL REFERENCES guest (id),
       reservation_id  BIGINT        REFERENCES reservation (id),
       type            VARCHAR(30)   NOT NULL,
       details         VARCHAR(1000) NOT NULL,
       due_at          TIMESTAMP     NOT NULL,
       status          VARCHAR(20)   NOT NULL,
       assigned_to_id  BIGINT        REFERENCES employee (id),
       cost            NUMERIC(10,2),
       created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version         BIGINT        NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_concierge_status_due ON concierge_request (status, due_at);
   ```

3. Build the module with the usual recipe (Cheat Sheet 5):
   - Enums `ConciergeRequestType { TRANSFER, TOUR, TICKETS, RESTAURANT, LIMOUSINE, OTHER }` and `ConciergeStatus { NEW, IN_PROGRESS, DONE, CANCELLED }`
   - Entity, repository, DTOs, mapper, `ConciergeService`, `ConciergeController` at `/api/v1/concierge/requests`
   - Roles: `ADMIN`, `MANAGER`, `CONCIERGE`, `RECEPTIONIST`

4. Key rules:
   - *`due_at` must be in the future when created.*
   - *Status moves NEW → IN_PROGRESS → DONE, or to CANCELLED from NEW or IN_PROGRESS. Reuse the `canMoveTo` pattern from Step 15.*
   - *When DONE with a `cost` and a reservation, post an `OTHER` charge to the folio: "Airport transfer".*
   - *`GET /api/v1/concierge/requests/overdue` lists open requests whose `due_at` has passed:*
     ```java
     List<ConciergeRequest> findByStatusInAndDueAtBefore(Collection<ConciergeStatus> statuses, LocalDateTime now);
     ```

5. Tests: invalid transition → 409; DONE with cost posts a charge; overdue query returns only open, past-due requests.

6. Commit, tick Step 22, push and merge:
   ```bash
   git commit -m "feat: Step 22 – add concierge requests with due times and folio charges"
   ```
