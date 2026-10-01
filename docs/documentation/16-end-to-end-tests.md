# Step 16 Documentation — End-to-end tests and coverage top-up

One test drives a whole guest stay through real HTTP calls against a real database, the way a user would. Then fill any coverage gaps.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c test/step-16-end-to-end
   ```

2. Create `src/test/java/com/luxstay/hms/integration/GuestJourneyIT.java`:
   ```java
   @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
   @Import(TestcontainersConfiguration.class)
   class GuestJourneyIT {

       @LocalServerPort int port;

       @BeforeEach
       void setUp() {
           RestAssured.port = port;
           RestAssured.basePath = "/api/v1";
       }

       private String login(String email) {
           return given().contentType(ContentType.JSON)
               .body(Map.of("email", email, "password", "Admin123!"))
               .post("/auth/login")
               .then().statusCode(200)
               .extract().path("accessToken");
       }

       @Test
       void bookCheckInChargePayCheckOut() {
           String desk = login("receptionist@luxstay.test");
           String manager = login("manager@luxstay.test");
           LocalDate today = LocalDate.now();

           // 1. Create a guest
           long guestId = given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("firstName", "Journey", "lastName", "Tester",
                            "email", "journey-" + System.nanoTime() + "@example.com"))
               .post("/guests")
               .then().statusCode(201)
               .extract().jsonPath().getLong("id");

           // 2. Book a Deluxe room (room type 1) for two nights from today
           long reservationId = given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("guestId", guestId, "roomTypeId", 1,
                            "checkIn", today.toString(), "checkOut", today.plusDays(2).toString(),
                            "adults", 2, "children", 0, "ratePlan", "BEST_AVAILABLE"))
               .post("/reservations")
               .then().statusCode(201)
               .body("status", equalTo("CONFIRMED"))
               .extract().jsonPath().getLong("id");

           // 3. Check in: room charge posted, key issued
           String roomNumber = given().auth().oauth2(desk)
               .post("/front-desk/check-in/" + reservationId)
               .then().statusCode(200)
               .body("reservation.status", equalTo("CHECKED_IN"))
               .body("keyCardCode", startsWith("KEY-"))
               .extract().path("reservation.roomNumber");

           // 4. Check-out is refused while the bill is unpaid
           given().auth().oauth2(desk)
               .post("/front-desk/check-out/" + reservationId)
               .then().statusCode(409);

           // 5. Minibar charge, then pay the full balance
           long folioId = given().auth().oauth2(desk)
               .get("/folios/by-reservation/" + reservationId)
               .then().statusCode(200)
               .extract().jsonPath().getLong("id");

           String balance = given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("outlet", "MINIBAR", "description", "Sparkling water", "amount", 9.00))
               .post("/folios/" + folioId + "/charges")
               .then().statusCode(200)
               .extract().jsonPath().getString("balance");

           given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("method", "CARD", "amount", new BigDecimal(balance), "cardToken", "tok_visa"))
               .post("/folios/" + folioId + "/payments")
               .then().statusCode(200)
               .body("balance", equalTo(0.0f));

           // 6. Check out
           given().auth().oauth2(desk)
               .post("/front-desk/check-out/" + reservationId)
               .then().statusCode(200)
               .body("status", equalTo("CHECKED_OUT"));

           // 7. Housekeeping got a departure clean for that room
           given().auth().oauth2(manager)
               .get("/housekeeping/tasks?status=PENDING&size=100")
               .then().statusCode(200)
               .body("content.findAll { it.roomNumber == '" + roomNumber + "' }.type", hasItem("DEPARTURE_CLEAN"));
       }

       @Test
       void guestCannotSeeSomeoneElsesReservation() {
           String desk = login("receptionist@luxstay.test");
           String guestUser = login("guest@luxstay.test");

           long guestId = given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("firstName", "Other", "lastName", "Person",
                            "email", "other-" + System.nanoTime() + "@example.com"))
               .post("/guests").then().statusCode(201).extract().jsonPath().getLong("id");

           long reservationId = given().auth().oauth2(desk).contentType(ContentType.JSON)
               .body(Map.of("guestId", guestId, "roomTypeId", 1,
                            "checkIn", LocalDate.now().plusDays(60).toString(),
                            "checkOut", LocalDate.now().plusDays(61).toString(),
                            "adults", 1, "children", 0, "ratePlan", "BEST_AVAILABLE"))
               .post("/reservations").then().statusCode(201).extract().jsonPath().getLong("id");

           given().auth().oauth2(guestUser)
               .get("/reservations/" + reservationId)
               .then().statusCode(403);
       }
   }
   ```
   - *`RANDOM_PORT` starts the real server on a free port; REST Assured calls it over HTTP.*
   - *Static imports: `given` (RestAssured) and `equalTo`, `startsWith`, `hasItem` (Hamcrest `Matchers`).*
   - *Dates are sent as strings (`today.toString()` → `"2026-10-01"`) so they serialize the same everywhere.*
   - *REST Assured reads JSON numbers as `Float`, hence `equalTo(0.0f)`.*

3. Run it: right-click `GuestJourneyIT` → **Run** (Docker must be running).
   - *If step 7 fails, check that check-out really published the event (Step 12) and that the listener exists (Step 14).*

4. Check the spec's mandatory tests all exist:
   - [ ] Double booking → 409 (`ReservationServiceImplTest`, `ReservationRepositoryIT`)
   - [ ] Check-in on a room that isn't `VACANT_CLEAN` → rejected (`FrontDeskServiceImplTest`)
   - [ ] Check-out with an unpaid folio → rejected (`FrontDeskServiceImplTest`, `GuestJourneyIT`)
   - [ ] Check-out creates a housekeeping task (`HousekeepingEventListenerTest`, `GuestJourneyIT`)
   - [ ] Late cancellation costs one night (`ReservationServiceImplTest`)
   - [ ] No token → 401, wrong role → 403 (`RoomControllerTest`, `GuestControllerTest`)

5. Top up coverage:
   ```bash
   ./mvnw verify
   ```
   Open `target/site/jacoco/index.html`, click into `com.luxstay.hms.service.impl`, and sort by **Missed Lines**.
   - *Red lines are usually error branches: "not found", "already settled", "inactive employee". Add one small test per red branch.*
   - *Aim for 85% or more, so the next feature doesn't immediately drop you under the 80% gate.*

6. Keep CI fast:
   - *All `*IT` classes use the same `TestcontainersConfiguration`, so Spring reuses one PostgreSQL container and one app context for the whole run.*
   - *If CI takes over 10 minutes, check the logs for "Starting HmsApplication" many times. Every different `@MockitoBean` set or extra annotation creates a new context.*

7. Commit, tick Step 16, push and merge:
   ```bash
   git add src README.md
   git commit -m "test: Step 16 – add end-to-end guest journey and raise coverage" -m "- GuestJourneyIT: login, guest, booking, check-in, unpaid check-out 409, charge, pay, check-out, housekeeping task
   - Guest role cannot read another guest's reservation (403)
   - Coverage report: target/site/jacoco/index.html, sorted by Missed Lines"
   git push -u origin test/step-16-end-to-end
   ```
   - *PR title: `test: Step 16 – end-to-end tests`.*
