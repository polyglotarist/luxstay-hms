# Step 21 Documentation — Spa and amenities (Phase 2)

Treatments with therapists and treatment rooms, booked without double-booking either. This reuses the overlap check you built for reservations.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-21-spa
   ```

2. Create `db/migration/V11__create_spa.sql`:
   ```sql
   CREATE TABLE spa_treatment (
       id                BIGSERIAL     PRIMARY KEY,
       name              VARCHAR(100)  NOT NULL UNIQUE,
       duration_minutes  INT           NOT NULL,
       price             NUMERIC(10,2) NOT NULL,
       active            BOOLEAN       NOT NULL DEFAULT TRUE,
       created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version           BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE spa_booking (
       id              BIGSERIAL    PRIMARY KEY,
       treatment_id    BIGINT       NOT NULL REFERENCES spa_treatment (id),
       therapist_id    BIGINT       NOT NULL REFERENCES employee (id),
       guest_id        BIGINT       NOT NULL REFERENCES guest (id),
       reservation_id  BIGINT       REFERENCES reservation (id),
       starts_at       TIMESTAMP    NOT NULL,
       ends_at         TIMESTAMP    NOT NULL,
       status          VARCHAR(20)  NOT NULL,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version         BIGINT       NOT NULL DEFAULT 0,
       CONSTRAINT no_therapist_double_booking EXCLUDE USING gist (
           therapist_id WITH =,
           tsrange(starts_at, ends_at) WITH &&
       ) WHERE (status = 'BOOKED')
   );

   INSERT INTO spa_treatment (name, duration_minutes, price) VALUES
       ('Signature Massage', 90, 260.00),
       ('Hot Stone Therapy', 75, 220.00),
       ('Hydrating Facial',  60, 180.00);
   ```
   - *Same idea as `no_double_booking` in Step 10, but on time ranges (`tsrange`) per therapist.*

3. Build the module with the usual recipe (Step 5 documentation):
   - Enum `SpaBookingStatus { BOOKED, COMPLETED, CANCELLED }`
   - Entities `SpaTreatment`, `SpaBooking`; repositories; DTOs `SpaBookingRequest(treatmentId, therapistId, guestId, reservationId, startsAt)` and `SpaBookingResponse`
   - `SpaService`: `getTreatments()`, `book(request)`, `complete(id)`, `cancel(id)`, `getDay(date)`
   - `SpaController` at `/api/v1/spa`, roles `ADMIN`, `MANAGER`, `SPA_STAFF`, `CONCIERGE`

4. Key rules in `SpaServiceImpl.book`:
   ```java
   LocalDateTime start = request.startsAt();
   LocalDateTime end = start.plusMinutes(treatment.getDurationMinutes());
   if (therapist.getDepartment() != Department.SPA) {
       throw new BusinessRuleException("Only spa staff can be booked as therapists");
   }
   if (bookingRepository.existsOverlapping(therapist.getId(), start, end)) {
       throw new BusinessRuleException("The therapist is busy at that time");
   }
   ```
   - *`existsOverlapping` is the same pattern as reservations: `b.startsAt < :end and b.endsAt > :start and b.status = BOOKED`.*

5. On `complete(id)`, if the booking has a reservation, post it to the bill:
   ```java
   if (booking.getReservation() != null) {
       folioService.postCharge(booking.getReservation().getId(), ChargeOutlet.SPA,
           booking.getTreatment().getName(), booking.getTreatment().getPrice());
   }
   ```

6. Tests: therapist busy → 409; non-spa employee → 409; completing posts a `SPA` charge (Mockito `verify`); a repository IT proving the exclusion constraint.

7. Commit, tick Step 21, push and merge:
   ```bash
   git commit -m "feat: Step 21 – add spa treatments and therapist bookings" -m "- V11: spa_treatment, spa_booking with tsrange exclusion per therapist
   - Completed treatments post a SPA charge to the guest's folio"
   ```
