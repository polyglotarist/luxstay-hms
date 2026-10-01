# Step 11 Documentation — Billing: folios, charges and payments

Every reservation gets a **folio** (the guest's bill). Any outlet can post charges to it; tax is added automatically; payments go through a simulated card gateway.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-11-billing
   ```

2. Create `db/migration/V6__create_billing.sql`:
   ```sql
   CREATE TABLE folio (
       id              BIGSERIAL    PRIMARY KEY,
       reservation_id  BIGINT       NOT NULL UNIQUE REFERENCES reservation (id),
       status          VARCHAR(20)  NOT NULL,
       currency        VARCHAR(3)   NOT NULL,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
       version         BIGINT       NOT NULL DEFAULT 0
   );

   CREATE TABLE charge (
       id           BIGSERIAL     PRIMARY KEY,
       folio_id     BIGINT        NOT NULL REFERENCES folio (id),
       outlet       VARCHAR(20)   NOT NULL,
       description  VARCHAR(255)  NOT NULL,
       amount       NUMERIC(12,2) NOT NULL,
       tax_amount   NUMERIC(12,2) NOT NULL,
       posted_at    TIMESTAMPTZ   NOT NULL,
       created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version      BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE payment (
       id               BIGSERIAL     PRIMARY KEY,
       folio_id         BIGINT        NOT NULL REFERENCES folio (id),
       method           VARCHAR(10)   NOT NULL,
       amount           NUMERIC(12,2) NOT NULL,
       transaction_ref  VARCHAR(64)   NOT NULL,
       paid_at          TIMESTAMPTZ   NOT NULL,
       created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version          BIGINT        NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_charge_folio ON charge (folio_id);
   CREATE INDEX idx_payment_folio ON payment (folio_id);

   INSERT INTO folio (reservation_id, status, currency)
   SELECT id, 'OPEN', 'USD' FROM reservation;
   ```
   - *The last statement gives reservations you made in Step 10 a folio too.*

3. Add billing settings under the existing `app:` key in `application.yaml`:
   ```yaml
   app:
     jwt:
       # ...unchanged...
     billing:
       tax-rate: 0.10
       currency: USD
   ```
   - *YAML allows only **one** `app:` key per file. Add `billing:` beside `jwt:`, not as a second `app:`.*
   - ***You need to change** the tax rate and currency to match your own hotel!*

4. Create the enums:
   ```java
   package com.luxstay.hms.enums;

   public enum FolioStatus {
       OPEN, SETTLED
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   @Getter
   @RequiredArgsConstructor
   public enum ChargeOutlet {
       ROOM(true), ROOM_SERVICE(true), RESTAURANT(true), SPA(true), MINIBAR(true), PENALTY(false), OTHER(true);

       private final boolean taxable;
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   public enum PaymentMethod {
       CARD, CASH
   }
   ```

5. Create the entities:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "folio")
   public class Folio extends BaseEntity {

       @OneToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "reservation_id")
       private Reservation reservation;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private FolioStatus status;

       @Column(nullable = false, length = 3)
       private String currency;

       @OneToMany(mappedBy = "folio", cascade = CascadeType.ALL, orphanRemoval = true)
       @OrderBy("postedAt")
       private List<Charge> charges = new ArrayList<>();

       @OneToMany(mappedBy = "folio", cascade = CascadeType.ALL, orphanRemoval = true)
       @OrderBy("paidAt")
       private List<Payment> payments = new ArrayList<>();

       public void addCharge(Charge charge) {
           charge.setFolio(this);
           charges.add(charge);
       }

       public void addPayment(Payment payment) {
           payment.setFolio(this);
           payments.add(payment);
       }

       public BigDecimal totalCharges() {
           return charges.stream()
               .map(c -> c.getAmount().add(c.getTaxAmount()))
               .reduce(BigDecimal.ZERO, BigDecimal::add);
       }

       public BigDecimal totalPaid() {
           return payments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
       }

       public BigDecimal balance() {
           return totalCharges().subtract(totalPaid());
       }
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "charge")
   public class Charge extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "folio_id")
       private Folio folio;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private ChargeOutlet outlet;

       @Column(nullable = false)
       private String description;

       @Column(nullable = false, precision = 12, scale = 2)
       private BigDecimal amount;

       @Column(nullable = false, precision = 12, scale = 2)
       private BigDecimal taxAmount;

       @Column(nullable = false)
       private Instant postedAt;
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "payment")
   public class Payment extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "folio_id")
       private Folio folio;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 10)
       private PaymentMethod method;

       @Column(nullable = false, precision = 12, scale = 2)
       private BigDecimal amount;

       @Column(nullable = false, length = 64)
       private String transactionRef;

       @Column(nullable = false)
       private Instant paidAt;
   }
   ```
   - *`cascade = ALL` means saving the folio also saves new charges and payments added to its lists.*
   - *The balance is always calculated from charges and payments, never stored, so it can't drift.*

6. Create `repository/FolioRepository.java`:
   ```java
   package com.luxstay.hms.repository;

   public interface FolioRepository extends JpaRepository<Folio, Long> {

       @EntityGraph(attributePaths = "reservation")
       Optional<Folio> findByReservationId(Long reservationId);

       @Override
       @EntityGraph(attributePaths = "reservation")
       Optional<Folio> findById(Long id);
   }
   ```

7. Create the billing settings and the simulated card gateway:
   ```java
   package com.luxstay.hms.config;

   @ConfigurationProperties(prefix = "app.billing")
   public record BillingProperties(BigDecimal taxRate, String currency) {
   }
   ```
   ```java
   package com.luxstay.hms.config;

   @Configuration
   @EnableConfigurationProperties(BillingProperties.class)
   public class BillingConfig {
   }
   ```
   ```java
   package com.luxstay.hms.integration;

   public record PaymentResult(boolean approved, String transactionRef, String message) {
   }
   ```
   ```java
   package com.luxstay.hms.integration;

   public interface PaymentGateway {
       PaymentResult charge(BigDecimal amount, String currency, String cardToken);
   }
   ```
   ```java
   package com.luxstay.hms.integration;

   @Slf4j
   @Component
   public class MockPaymentGateway implements PaymentGateway {

       @Override
       public PaymentResult charge(BigDecimal amount, String currency, String cardToken) {
           if (cardToken == null || cardToken.isBlank()) {
               return new PaymentResult(false, null, "A card token is required");
           }
           if (cardToken.equals("tok_decline")) {
               return new PaymentResult(false, null, "Card declined by issuer");
           }
           String ref = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
           log.info("Mock gateway approved {} {} ({})", amount, currency, ref);
           return new PaymentResult(true, ref, "Approved");
       }
   }
   ```
   - *`@ConfigurationProperties` reads `app.billing.*` from YAML into a record.*
   - *The interface lets you swap in a real provider such as Stripe later without touching the billing code.*
   - *Test card tokens: `tok_visa` is approved; `tok_decline` is declined.*

8. Create `exception/PaymentDeclinedException.java`, and add its handler to `GlobalExceptionHandler`:
   ```java
   package com.luxstay.hms.exception;

   public class PaymentDeclinedException extends RuntimeException {
       public PaymentDeclinedException(String message) {
           super(message);
       }
   }
   ```
   ```java
   @ExceptionHandler(PaymentDeclinedException.class)
   public ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
       return problem(HttpStatus.PAYMENT_REQUIRED, "Payment declined", ex.getMessage());
   }
   ```

9. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record PostChargeRequest(
       @NotNull @Schema(example = "MINIBAR") ChargeOutlet outlet,
       @NotBlank @Size(max = 255) @Schema(example = "Champagne, half bottle") String description,
       @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) @Schema(example = "85.00") BigDecimal amount) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record PaymentRequest(
       @NotNull @Schema(example = "CARD") PaymentMethod method,
       @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) @Schema(example = "100.00") BigDecimal amount,
       @Schema(example = "tok_visa") String cardToken) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record ChargeResponse(Long id, ChargeOutlet outlet, String description,
                                BigDecimal amount, BigDecimal taxAmount, Instant postedAt) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record PaymentResponse(Long id, PaymentMethod method, BigDecimal amount,
                                 String transactionRef, Instant paidAt) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record FolioResponse(
       Long id,
       Long reservationId,
       String confirmationCode,
       FolioStatus status,
       String currency,
       List<ChargeResponse> charges,
       List<PaymentResponse> payments,
       BigDecimal totalCharges,
       BigDecimal totalPaid,
       BigDecimal balance) {
   }
   ```

10. Create `mapper/FolioMapper.java`:
    ```java
    package com.luxstay.hms.mapper;

    @Mapper(componentModel = "spring")
    public interface FolioMapper {

        @Mapping(target = "reservationId", source = "reservation.id")
        @Mapping(target = "confirmationCode", source = "reservation.confirmationCode")
        @Mapping(target = "totalCharges", expression = "java(folio.totalCharges())")
        @Mapping(target = "totalPaid", expression = "java(folio.totalPaid())")
        @Mapping(target = "balance", expression = "java(folio.balance())")
        FolioResponse toResponse(Folio folio);

        ChargeResponse toResponse(Charge charge);

        PaymentResponse toResponse(Payment payment);
    }
    ```
    - *MapStruct uses the second and third methods automatically for the `charges` and `payments` lists.*

11. Create `service/FolioService.java`:
    ```java
    package com.luxstay.hms.service;

    public interface FolioService {
        FolioResponse getById(Long folioId);
        FolioResponse getByReservation(Long reservationId);
        FolioResponse postCharge(Long folioId, PostChargeRequest request);
        FolioResponse pay(Long folioId, PaymentRequest request);

        // used by other modules
        void openFor(Reservation reservation);
        void postCharge(Long reservationId, ChargeOutlet outlet, String description, BigDecimal amount);
        BigDecimal balanceOf(Long reservationId);
        void settle(Long reservationId);
    }
    ```

12. Create `service/impl/FolioServiceImpl.java`:
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Service
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public class FolioServiceImpl implements FolioService {

        private final FolioRepository folioRepository;
        private final PaymentGateway paymentGateway;
        private final FolioMapper folioMapper;
        private final BillingProperties billing;
        private final Clock clock;

        @Override
        public FolioResponse getById(Long folioId) {
            return folioMapper.toResponse(find(folioId));
        }

        @Override
        public FolioResponse getByReservation(Long reservationId) {
            return folioMapper.toResponse(findByReservation(reservationId));
        }

        @Override
        @Transactional
        public FolioResponse postCharge(Long folioId, PostChargeRequest request) {
            Folio folio = find(folioId);
            addCharge(folio, request.outlet(), request.description(), request.amount());
            return folioMapper.toResponse(folio);
        }

        @Override
        @Transactional
        public void postCharge(Long reservationId, ChargeOutlet outlet, String description, BigDecimal amount) {
            addCharge(findByReservation(reservationId), outlet, description, amount);
        }

        @Override
        @Transactional
        public FolioResponse pay(Long folioId, PaymentRequest request) {
            Folio folio = find(folioId);
            ensureOpen(folio);
            if (request.amount().compareTo(folio.balance()) > 0) {
                throw new BusinessRuleException("Payment exceeds the balance of " + folio.balance());
            }
            PaymentResult result = request.method() == PaymentMethod.CASH
                ? new PaymentResult(true, "CASH-" + clock.millis(), "Cash received")
                : paymentGateway.charge(request.amount(), folio.getCurrency(), request.cardToken());
            if (!result.approved()) {
                log.warn("Payment declined on folio {}: {}", folioId, result.message());
                throw new PaymentDeclinedException(result.message());
            }
            Payment payment = new Payment();
            payment.setMethod(request.method());
            payment.setAmount(request.amount());
            payment.setTransactionRef(result.transactionRef());
            payment.setPaidAt(clock.instant());
            folio.addPayment(payment);
            log.info("Payment {} of {} on folio {}", result.transactionRef(), request.amount(), folioId);
            return folioMapper.toResponse(folio);
        }

        @Override
        @Transactional
        public void openFor(Reservation reservation) {
            Folio folio = new Folio();
            folio.setReservation(reservation);
            folio.setStatus(FolioStatus.OPEN);
            folio.setCurrency(billing.currency());
            folioRepository.save(folio);
        }

        @Override
        public BigDecimal balanceOf(Long reservationId) {
            return findByReservation(reservationId).balance();
        }

        @Override
        @Transactional
        public void settle(Long reservationId) {
            findByReservation(reservationId).setStatus(FolioStatus.SETTLED);
        }

        private void addCharge(Folio folio, ChargeOutlet outlet, String description, BigDecimal amount) {
            ensureOpen(folio);
            BigDecimal tax = outlet.isTaxable()
                ? amount.multiply(billing.taxRate()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            Charge charge = new Charge();
            charge.setOutlet(outlet);
            charge.setDescription(description);
            charge.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            charge.setTaxAmount(tax);
            charge.setPostedAt(clock.instant());
            folio.addCharge(charge);
            log.info("Charge {} {} + tax {} posted to folio {}", outlet, charge.getAmount(), tax, folio.getId());
        }

        private void ensureOpen(Folio folio) {
            if (folio.getStatus() != FolioStatus.OPEN) {
                throw new BusinessRuleException("Folio " + folio.getId() + " is already settled");
            }
        }

        private Folio find(Long folioId) {
            return folioRepository.findById(folioId)
                .orElseThrow(() -> new ResourceNotFoundException("Folio", folioId));
        }

        private Folio findByReservation(Long reservationId) {
            return folioRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Folio for reservation", reservationId));
        }
    }
    ```
    - *A guest can't overpay, and a settled bill can't take new charges.*
    - *Penalties aren't taxed (`PENALTY(false)` in the enum).*

13. Connect reservations to billing. In `ReservationServiceImpl`:
    - Add `private final FolioService folioService;` directly **above** `private final Clock clock;`
    - In `create`, right after `reservationRepository.save(...)`:
      ```java
      folioService.openFor(saved);
      ```
    - In `cancel`, just before the `log.info`:
      ```java
      if (reservation.getCancellationFee().signum() > 0) {
          folioService.postCharge(reservation.getId(), ChargeOutlet.PENALTY,
              "Late cancellation fee", reservation.getCancellationFee());
      }
      ```
    - In `ReservationServiceImplTest`, add `@Mock FolioService folioService;` and pass `folioService` as the **second-to-last** constructor argument (before `clock`).
    - *Now every new booking opens a bill, and a late cancellation posts its fee to it.*

14. Create `controller/FolioController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/folios")
    @RequiredArgsConstructor
    @Tag(name = "Billing", description = "Guest folios, charges and payments")
    public class FolioController {

        private final FolioService folioService;

        @GetMapping("/{id}")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST')")
        @Operation(summary = "Get a folio with all charges, payments and the balance")
        public FolioResponse getById(@PathVariable Long id) {
            return folioService.getById(id);
        }

        @GetMapping("/by-reservation/{reservationId}")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','FNB_STAFF','SPA_STAFF','CONCIERGE')")
        @Operation(summary = "Find the folio of a reservation")
        public FolioResponse getByReservation(@PathVariable Long reservationId) {
            return folioService.getByReservation(reservationId);
        }

        @PostMapping("/{id}/charges")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','FNB_STAFF','SPA_STAFF','CONCIERGE')")
        @Operation(summary = "Post a charge (tax is added automatically)")
        public FolioResponse postCharge(@PathVariable Long id, @Valid @RequestBody PostChargeRequest request) {
            return folioService.postCharge(id, request);
        }

        @PostMapping("/{id}/payments")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST')")
        @Operation(summary = "Take a payment (card tokens: tok_visa approves, tok_decline declines)")
        @ApiResponse(responseCode = "402", description = "Card declined")
        public FolioResponse pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
            return folioService.pay(id, request);
        }
    }
    ```

15. Try it in Swagger (as `receptionist@luxstay.test`):
    - Book a room → `GET /api/v1/folios/by-reservation/{reservationId}` → empty folio, balance 0
    - `POST /api/v1/folios/{id}/charges` with the example → amount 85.00 + tax 8.50, balance 93.50
    - Pay 93.50 with `tok_decline` → **402**; with `tok_visa` → balance 0

16. Create `src/test/java/com/luxstay/hms/unit/FolioServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class FolioServiceImplTest {

        @Mock FolioRepository folioRepository;
        @Mock PaymentGateway paymentGateway;
        FolioServiceImpl service;
        Folio folio;

        @BeforeEach
        void setUp() {
            Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
            service = new FolioServiceImpl(folioRepository, paymentGateway, Mappers.getMapper(FolioMapper.class),
                new BillingProperties(new BigDecimal("0.10"), "USD"), clock);
            folio = new Folio();
            folio.setId(1L);
            folio.setStatus(FolioStatus.OPEN);
            folio.setCurrency("USD");
            folio.setReservation(new Reservation());
        }

        @Test
        void postCharge_addsTenPercentTax() {
            when(folioRepository.findById(1L)).thenReturn(Optional.of(folio));

            FolioResponse response = service.postCharge(1L,
                new PostChargeRequest(ChargeOutlet.MINIBAR, "Champagne", new BigDecimal("85.00")));

            assertThat(response.charges().getFirst().taxAmount()).isEqualByComparingTo("8.50");
            assertThat(response.balance()).isEqualByComparingTo("93.50");
        }

        @Test
        void penalty_isNotTaxed() {
            when(folioRepository.findByReservationId(9L)).thenReturn(Optional.of(folio));

            service.postCharge(9L, ChargeOutlet.PENALTY, "Late cancellation fee", new BigDecimal("450.00"));

            assertThat(folio.balance()).isEqualByComparingTo("450.00");
        }

        @Test
        void pay_withApprovedCard_reducesBalance() {
            when(folioRepository.findById(1L)).thenReturn(Optional.of(folio));
            service.postCharge(1L, new PostChargeRequest(ChargeOutlet.ROOM, "Room", new BigDecimal("100.00")));
            when(paymentGateway.charge(any(), any(), eq("tok_visa")))
                .thenReturn(new PaymentResult(true, "MOCK-1", "Approved"));

            FolioResponse response = service.pay(1L,
                new PaymentRequest(PaymentMethod.CARD, new BigDecimal("110.00"), "tok_visa"));

            assertThat(response.balance()).isEqualByComparingTo("0");
        }

        @Test
        void pay_whenDeclined_throws() {
            when(folioRepository.findById(1L)).thenReturn(Optional.of(folio));
            service.postCharge(1L, new PostChargeRequest(ChargeOutlet.ROOM, "Room", new BigDecimal("100.00")));
            when(paymentGateway.charge(any(), any(), eq("tok_decline")))
                .thenReturn(new PaymentResult(false, null, "Card declined by issuer"));

            assertThatThrownBy(() -> service.pay(1L,
                    new PaymentRequest(PaymentMethod.CARD, new BigDecimal("10.00"), "tok_decline")))
                .isInstanceOf(PaymentDeclinedException.class);
        }

        @Test
        void pay_moreThanBalance_throws() {
            when(folioRepository.findById(1L)).thenReturn(Optional.of(folio));

            assertThatThrownBy(() -> service.pay(1L,
                    new PaymentRequest(PaymentMethod.CASH, new BigDecimal("10.00"), null)))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void settledFolio_rejectsCharges() {
            folio.setStatus(FolioStatus.SETTLED);
            when(folioRepository.findById(1L)).thenReturn(Optional.of(folio));

            assertThatThrownBy(() -> service.postCharge(1L,
                    new PostChargeRequest(ChargeOutlet.MINIBAR, "Water", new BigDecimal("5.00"))))
                .isInstanceOf(BusinessRuleException.class);
        }
    }
    ```

17. Commit, tick Step 11, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 11 – add folios with charges, tax and mock card payments" -m "- V6 creates folio, charge, payment; existing reservations get an OPEN folio
    - Every new reservation opens a folio; late cancellation posts an untaxed PENALTY charge
    - Tax from app.billing.tax-rate; balance = charges + tax - payments (never stored)
    - Test cards: tok_visa approves, tok_decline -> 402"
    git push -u origin feature/step-11-billing
    ```
    - *PR title: `feat: Step 11 – billing`.*
