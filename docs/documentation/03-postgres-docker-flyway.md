# Step 3 Documentation — PostgreSQL with Docker Compose and Flyway

1. In your terminal, create a branch and check that port 5432 is free:
   ```bash
   git switch main && git pull
   git switch -c feature/step-3-postgres-docker-compose
   lsof -i :5432
   ```
   - *No output means the port is free.*
   - *If it's busy, find and stop the container using it:*
     ```bash
     docker ps --format "table {{.Names}}\t{{.Ports}}"
     docker stop <name>
     ```

2. Create `.env` in the project root:
   ```
   POSTGRES_DB=luxstay
   POSTGRES_USER=luxstay
   POSTGRES_PASSWORD=choose-a-password
   POSTGRES_PORT=5432
   ```
   - *This holds your password. **Never commit it.** When IntelliJ asks "Add file to Git?", click **Cancel**.*
   - ***You need to change** the database name, user and password to match your own application!*

3. Add `.env` to the end of `.gitignore`:
   ```
   ### Local secrets ###
   .env
   ```
   - *Check with `git status`: `.env` must not appear.*

4. Create `.env.example` with the same keys and a fake password:
   ```
   POSTGRES_DB=luxstay
   POSTGRES_USER=luxstay
   POSTGRES_PASSWORD=change-me
   POSTGRES_PORT=5432
   ```
   - *This one is committed, so others know which variables to set.*

5. Create `docker-compose.yml`:
   ```yaml
   services:
     postgres:
       image: postgres:16
       container_name: luxstay-postgres
       restart: unless-stopped
       environment:
         POSTGRES_DB: ${POSTGRES_DB}
         POSTGRES_USER: ${POSTGRES_USER}
         POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
       ports:
         - "${POSTGRES_PORT:-5432}:5432"
       volumes:
         - pgdata:/var/lib/postgresql/data
       healthcheck:
         test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
         interval: 5s
         timeout: 5s
         retries: 10

   volumes:
     pgdata:
   ```
   - *`${…}` values are read from `.env` automatically.*
   - *The `pgdata` volume keeps your data when the container stops.*

6. Start the database:
   ```bash
   docker compose up -d postgres
   docker compose ps
   ```
   - *`STATUS` should show `Up … (healthy)`.*

7. In DBeaver, connect: **Database → New Database Connection → PostgreSQL**
   - Host `localhost` · Port `5432` · Database `luxstay` · User `luxstay` · Password from `.env`
   - *Click **Test Connection** and wait for **Connected**.*

8. Commit:
   ```bash
   git add .gitignore .env.example docker-compose.yml
   git commit -m "build: Step 3.2 – run PostgreSQL 16 locally with Docker Compose"
   git push -u origin feature/step-3-postgres-docker-compose
   ```

9. Replace `src/main/resources/application.yaml` with:
   ```yaml
   spring:
     application:
       name: luxstay-hms
     config:
       import: optional:file:.env[.properties]
     profiles:
       default: local
   ```
   - *This makes Spring read `.env` and use the `local` settings by default.*

10. Create `src/main/resources/application-local.yaml`:
    ```yaml
    spring:
      datasource:
        url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:luxstay}
        username: ${POSTGRES_USER:luxstay}
        password: ${POSTGRES_PASSWORD:}
      jpa:
        hibernate:
          ddl-auto: validate
        open-in-view: false
      flyway:
        enabled: true
    ```
    - *The value after `:` is a fallback for when the variable is missing, as in tests and CI.*
    - *`ddl-auto: validate` means Hibernate only checks the tables; Flyway creates them.*

11. In `src/main/resources/db/migration`, create `V1__create_room_tables.sql`:
    ```sql
    CREATE TABLE room_type (
        id             BIGSERIAL     PRIMARY KEY,
        code           VARCHAR(20)   NOT NULL UNIQUE,
        name           VARCHAR(100)  NOT NULL,
        max_occupancy  INT           NOT NULL,
        base_rate      NUMERIC(10,2) NOT NULL,
        description    VARCHAR(500),
        created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
        updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
        version        BIGINT        NOT NULL DEFAULT 0
    );

    CREATE TABLE room (
        id            BIGSERIAL    PRIMARY KEY,
        number        VARCHAR(10)  NOT NULL UNIQUE,
        floor         INT          NOT NULL,
        view_type     VARCHAR(30),
        status        VARCHAR(20)  NOT NULL,
        room_type_id  BIGINT       NOT NULL REFERENCES room_type (id),
        created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
        updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
        version       BIGINT       NOT NULL DEFAULT 0
    );

    CREATE INDEX idx_room_room_type ON room (room_type_id);
    CREATE INDEX idx_room_status ON room (status);
    ```
    - *The file name must be exactly `V<number>__<description>.sql`, with **two** underscores.*
    - *IntelliJ shows the folder as `db.migration`.*
    - *Never edit a migration after it has run. Add a new `V3__…` file instead.*

12. In the same folder, create `V2__seed_rooms.sql`:
    ```sql
    INSERT INTO room_type (code, name, max_occupancy, base_rate, description) VALUES
        ('DLX', 'Deluxe King',        2,  450.00, 'King bed, marble bathroom, city or sea view'),
        ('JRS', 'Junior Suite',       3,  750.00, 'Separate lounge area and soaking tub'),
        ('PRS', 'Presidential Suite', 4, 3500.00, 'Two bedrooms, private terrace, butler service');

    INSERT INTO room (number, floor, view_type, status, room_type_id)
    SELECT (f * 100 + n)::text,
           f,
           CASE WHEN n % 2 = 0 THEN 'SEA' ELSE 'CITY' END,
           'VACANT_CLEAN',
           (SELECT id FROM room_type
             WHERE code = CASE WHEN n <= 8 THEN 'DLX' WHEN n <= 11 THEN 'JRS' ELSE 'PRS' END)
    FROM generate_series(1, 5) AS f, generate_series(1, 12) AS n;
    ```
    - *This adds 3 room types and 60 rooms (5 floors × 12).*

13. Run `HmsApplication` (the green ▶ next to `main`).
    - *The log should show `Successfully applied 2 migrations` and `Started HmsApplication`.*
    - *Run `HmsApplication` in `src/main`, not `TestHmsApplication`.*

14. In DBeaver, refresh the connection and run:
    ```sql
    SELECT rt.name, count(*) AS rooms
    FROM room r JOIN room_type rt ON rt.id = r.room_type_id
    GROUP BY rt.name
    ORDER BY rooms DESC;
    ```
    - *Expected: Deluxe King 40, Junior Suite 15, Presidential Suite 5.*

15. Stop the app (red ■) and commit:
    ```bash
    git add src/main/resources
    git commit -m "feat: Step 3.4 – connect Spring to PostgreSQL and create room tables with Flyway"
    git push
    ```

16. In `README.md`, tick `- [x] Step 3` and add under **Getting started**:
    ````markdown
    ```bash
    cp .env.example .env              # then set POSTGRES_PASSWORD
    docker compose up -d postgres     # start PostgreSQL 16
    ./mvnw spring-boot:run            # Flyway creates and seeds the tables
    ```
    ````
    ```bash
    git add README.md
    git commit -m "docs: Step 3.5 – document local database setup and tick Step 3"
    git push
    ```

17. On github.com, open the pull request and merge it:
    **Pull requests → New pull request → base `main` ← `feature/step-3-postgres-docker-compose` → Create → Squash and merge → Confirm**
    - *Title: `build: Step 3 – PostgreSQL with Docker Compose and Flyway`.*
    - *If it's stuck on "Checking for the ability to merge…", reload the page (⌘ + R).*

18. Clean up:
    ```bash
    git switch main
    git pull
    git branch -D feature/step-3-postgres-docker-compose
    ```

19. Now your app has a PostgreSQL database with tables managed by Flyway! To start from scratch at any time:
    ```bash
    docker compose down -v
    docker compose up -d postgres
    ```
    - *`-v` deletes the data. Flyway recreates the tables on the next app start.*
