# Cheat Sheet 23 — Loyalty programme (Phase 2)

Guests earn points when they check out, move up tiers automatically, and redeem points against their bill.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-23-loyalty
   ```

2. Create `db/migration/V13__create_loyalty.sql`:
   ```sql
   CREATE TABLE loyalty_account (
       id              BIGSERIAL    PRIMARY KEY,
       guest_id        BIGINT       NOT NULL UNIQUE REFERENCES guest (id),
       tier            VARCHAR(20)  NOT NULL,
       points_balance  BIGINT       NOT NULL DEFAULT 0,
       lifetime_points BIGINT       NOT NULL DEFAULT 0,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version         BIGINT       NOT NULL DEFAULT 0
   );

   CREATE TABLE loyalty_transaction (
       id              BIGSERIAL    PRIMARY KEY,
       account_id      BIGINT       NOT NULL REFERENCES loyalty_account (id),
       reservation_id  BIGINT       REFERENCES reservation (id),
       points          BIGINT       NOT NULL,
       reason          VARCHAR(255) NOT NULL,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version         BIGINT       NOT NULL DEFAULT 0
   );
   ```
   - *Every point movement is a transaction row (+ earned, − redeemed), so the balance can always be explained.*

3. Create the tier enum:
   ```java
   package com.luxstay.hms.enums;

   @Getter
   @RequiredArgsConstructor
   public enum LoyaltyTier {
       SILVER(0, new BigDecimal("1.0")),
       GOLD(5_000, new BigDecimal("1.25")),
       PLATINUM(20_000, new BigDecimal("1.5"));

       private final long lifetimePointsNeeded;
       private final BigDecimal earnMultiplier;

       public static LoyaltyTier forLifetimePoints(long points) {
           LoyaltyTier result = SILVER;
           for (LoyaltyTier tier : values()) {
               if (points >= tier.lifetimePointsNeeded) {
                   result = tier;
               }
           }
           return result;
       }
   }
   ```
   - *Higher tiers earn faster: Gold earns 25% more points per dollar.*

4. Earn points on check-out. Create `event/LoyaltyEventListener.java`:
   ```java
   @Component
   @RequiredArgsConstructor
   public class LoyaltyEventListener {

       private final LoyaltyService loyaltyService;

       @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
       public void onGuestCheckedOut(GuestCheckedOutEvent event) {
           loyaltyService.earnForStay(event.reservationId());
       }
   }
   ```
   And in `LoyaltyServiceImpl.earnForStay`:
   ```java
   Reservation res = reservationRepository.findById(reservationId).orElseThrow();
   LoyaltyAccount account = accountRepository.findByGuestId(res.getGuest().getId())
       .orElseGet(() -> openAccount(res.getGuest()));
   long points = res.getTotalAmount()
       .multiply(account.getTier().getEarnMultiplier())
       .setScale(0, RoundingMode.DOWN)
       .longValue();                       // 1 point per dollar × tier multiplier
   account.setPointsBalance(account.getPointsBalance() + points);
   account.setLifetimePoints(account.getLifetimePoints() + points);
   account.setTier(LoyaltyTier.forLifetimePoints(account.getLifetimePoints()));
   recordTransaction(account, res, points, "Stay " + res.getConfirmationCode());
   ```
   - *The same `GuestCheckedOutEvent` from Step 12 now has two listeners. Housekeeping and loyalty don't know about each other.*
   - *Points are earned only **after** the stay, so cancelled bookings never earn.*

5. Redeem: `POST /api/v1/loyalty/{guestId}/redeem { reservationId, points }`:
   - *100 points = $1. Add `LOYALTY_REDEMPTION(false)` to the `ChargeOutlet` enum (not taxed), post a **negative** charge with it, and record a −points transaction.*
   - *Reject if the balance is too low, or if the folio isn't open.*
   - *Allow negative charge amounts only from this internal path; `PostChargeRequest` keeps `@DecimalMin("0.01")`.*

6. Tier benefit example: in `FrontDeskServiceImpl.checkIn`, if the guest is PLATINUM, log "Platinum guest – welcome amenity" and add a note for housekeeping. Keep benefits small and visible in logs.

7. Tests: points rounding per tier; tier upgrade at 5,000 lifetime points; redeeming more than the balance → 409; the listener calls `earnForStay`.

8. Commit, tick Step 23, push and merge:
   ```bash
   git commit -m "feat: Step 23 – add loyalty accounts, tiers and point redemption" -m "- Points on check-out: 1 per dollar x tier multiplier (Silver 1.0, Gold 1.25, Platinum 1.5)
   - Tier from lifetime points; redemption 100 points = \$1 as an untaxed negative LOYALTY_REDEMPTION charge"
   ```
