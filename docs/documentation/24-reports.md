# Step 24 Documentation — Management reports (Phase 2)

The three numbers every hotel manager checks daily: **occupancy**, **ADR** (average daily rate) and **RevPAR** (revenue per available room), plus revenue by outlet.

| Metric | Formula |
| --- | --- |
| Occupancy | rooms occupied ÷ rooms available |
| ADR | room revenue ÷ rooms occupied |
| RevPAR | room revenue ÷ rooms available (= occupancy × ADR) |

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-24-reports
   ```

2. Create `repository/ReportRepository.java` (read-only native SQL, no entity of its own):
   ```java
   package com.luxstay.hms.repository;

   @Repository
   @RequiredArgsConstructor
   public class ReportRepository {

       private final JdbcClient jdbc;

       public long roomsAvailable() {
           return jdbc.sql("SELECT count(*) FROM room WHERE status <> 'OUT_OF_ORDER'")
               .query(Long.class).single();
       }

       public record NightStats(long roomsOccupied, BigDecimal roomRevenue) {
       }

       public NightStats nightStats(LocalDate night) {
           return jdbc.sql("""
                   SELECT count(*)                          AS rooms_occupied,
                          coalesce(sum(nightly_rate), 0)    AS room_revenue
                   FROM reservation
                   WHERE status IN ('CHECKED_IN', 'CHECKED_OUT')
                     AND check_in <= :night AND check_out > :night
                   """)
               .param("night", night)
               .query((rs, i) -> new NightStats(rs.getLong("rooms_occupied"), rs.getBigDecimal("room_revenue")))
               .single();
       }

       public record OutletRevenue(String outlet, BigDecimal revenue) {
       }

       public List<OutletRevenue> revenueByOutlet(LocalDate from, LocalDate to) {
           return jdbc.sql("""
                   SELECT outlet, sum(amount) AS revenue
                   FROM charge
                   WHERE posted_at >= :from AND posted_at < :to
                   GROUP BY outlet
                   ORDER BY revenue DESC
                   """)
               .param("from", from.atStartOfDay())
               .param("to", to.plusDays(1).atStartOfDay())
               .query((rs, i) -> new OutletRevenue(rs.getString("outlet"), rs.getBigDecimal("revenue")))
               .list();
       }
   }
   ```
   - *`JdbcClient` (built into Spring) runs plain SQL. Reports read across many tables, so SQL is clearer here than JPA.*
   - *A guest occupies a room on night N when `check_in <= N < check_out`.*
   - *Revenue is pre-tax (`amount`, not `tax_amount`), which is how hotels report.*

3. Create `dto/response/OccupancyReport.java`:
   ```java
   package com.luxstay.hms.dto.response;

   public record OccupancyReport(
       LocalDate date,
       long roomsAvailable,
       long roomsOccupied,
       BigDecimal occupancyPercent,
       BigDecimal adr,
       BigDecimal revPar,
       BigDecimal roomRevenue) {
   }
   ```

4. Create `service/impl/ReportServiceImpl.java` (with interface `ReportService`):
   ```java
   @Service
   @RequiredArgsConstructor
   @Transactional(readOnly = true)
   public class ReportServiceImpl implements ReportService {

       private final ReportRepository reportRepository;

       @Override
       public OccupancyReport occupancy(LocalDate date) {
           long available = reportRepository.roomsAvailable();
           ReportRepository.NightStats stats = reportRepository.nightStats(date);
           BigDecimal occupied = BigDecimal.valueOf(stats.roomsOccupied());
           BigDecimal rooms = BigDecimal.valueOf(available);

           BigDecimal occupancy = available == 0 ? BigDecimal.ZERO
               : occupied.multiply(BigDecimal.valueOf(100)).divide(rooms, 1, RoundingMode.HALF_UP);
           BigDecimal adr = stats.roomsOccupied() == 0 ? BigDecimal.ZERO
               : stats.roomRevenue().divide(occupied, 2, RoundingMode.HALF_UP);
           BigDecimal revPar = available == 0 ? BigDecimal.ZERO
               : stats.roomRevenue().divide(rooms, 2, RoundingMode.HALF_UP);

           return new OccupancyReport(date, available, stats.roomsOccupied(), occupancy, adr, revPar,
               stats.roomRevenue());
       }

       @Override
       public List<ReportRepository.OutletRevenue> revenueByOutlet(LocalDate from, LocalDate to) {
           return reportRepository.revenueByOutlet(from, to);
       }
   }
   ```
   - *Always guard against dividing by zero; an empty hotel has 0% occupancy, not an error.*

5. Create `controller/ReportController.java` at `/api/v1/reports`:
   - `GET /occupancy?date=2026-12-20` → `OccupancyReport`
   - `GET /revenue?from=…&to=…` → revenue by outlet
   - `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` on the class
   - *Use `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` on the date parameters.*

6. Tests:
   - *Unit: 30 of 60 rooms at $450 → occupancy 50.0, ADR 450.00, RevPAR 225.00; zero rooms → zeros, no exception.*
   - *Integration (`ReportRepositoryIT`): insert two checked-in reservations covering a date and one cancelled one; `nightStats` counts 2.*

7. Commit, tick Step 24, push and merge:
   ```bash
   git commit -m "feat: Step 24 – add occupancy, ADR, RevPAR and revenue-by-outlet reports" -m "- JdbcClient native SQL; occupied night = check_in <= night < check_out
   - Occupancy = occupied / available; ADR = revenue / occupied; RevPAR = revenue / available"
   ```

8. Release it: tag `v1.1.0` (Step 20 documentation, items 9–11) to ship Phase 2 to production.
