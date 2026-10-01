# Cheat Sheet 15 — Room service and restaurant

A menu, in-room orders that move from kitchen to door and land on the guest's bill, and restaurant table bookings with a capacity limit.

1. Create the branch:
   ```bash
   git switch main && git pull
   git switch -c feature/step-15-dining
   ```

2. Create `db/migration/V9__create_dining.sql`:
   ```sql
   CREATE TABLE menu_item (
       id          BIGSERIAL     PRIMARY KEY,
       name        VARCHAR(100)  NOT NULL UNIQUE,
       category    VARCHAR(20)   NOT NULL,
       price       NUMERIC(10,2) NOT NULL,
       available   BOOLEAN       NOT NULL DEFAULT TRUE,
       created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version     BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE room_service_order (
       id              BIGSERIAL     PRIMARY KEY,
       reservation_id  BIGINT        NOT NULL REFERENCES reservation (id),
       status          VARCHAR(20)   NOT NULL,
       total           NUMERIC(12,2) NOT NULL,
       notes           VARCHAR(255),
       placed_at       TIMESTAMPTZ   NOT NULL,
       delivered_at    TIMESTAMPTZ,
       created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version         BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE order_line (
       id            BIGSERIAL     PRIMARY KEY,
       order_id      BIGINT        NOT NULL REFERENCES room_service_order (id),
       menu_item_id  BIGINT        NOT NULL REFERENCES menu_item (id),
       quantity      INT           NOT NULL CHECK (quantity > 0),
       unit_price    NUMERIC(10,2) NOT NULL,
       created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version       BIGINT        NOT NULL DEFAULT 0
   );

   CREATE TABLE table_reservation (
       id           BIGSERIAL     PRIMARY KEY,
       guest_name   VARCHAR(200)  NOT NULL,
       party_size   INT           NOT NULL,
       reserved_at  TIMESTAMP     NOT NULL,
       notes        VARCHAR(255),
       created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
       updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
       version      BIGINT        NOT NULL DEFAULT 0
   );

   CREATE INDEX idx_order_status ON room_service_order (status);
   CREATE INDEX idx_table_reserved_at ON table_reservation (reserved_at);
   ```
   - *`unit_price` copies the menu price at order time, so a later price change doesn't rewrite old bills.*
   - *`reserved_at` is a local time with no time zone (`TIMESTAMP`), because a dinner booking at 20:00 means 20:00 at the hotel.*

3. Create `db/migration/V10__seed_menu.sql`:
   ```sql
   INSERT INTO menu_item (name, category, price) VALUES
       ('Continental Breakfast',      'BREAKFAST', 38.00),
       ('Eggs Benedict',              'BREAKFAST', 32.00),
       ('Burrata & Heirloom Tomato',  'STARTER',   28.00),
       ('Oscietra Caviar, 30g',       'STARTER',  145.00),
       ('Wagyu Beef Burger',          'MAIN',      54.00),
       ('Dover Sole Meunière',        'MAIN',      78.00),
       ('Truffle Risotto',            'MAIN',      62.00),
       ('Chocolate Fondant',          'DESSERT',   22.00),
       ('Fresh Orange Juice',         'DRINK',     12.00),
       ('Champagne, half bottle',     'DRINK',     85.00);
   ```
   - ***You need to change** the menu to match your own restaurant!*

4. Create the enums:
   ```java
   package com.luxstay.hms.enums;

   public enum MenuCategory {
       BREAKFAST, STARTER, MAIN, DESSERT, DRINK
   }
   ```
   ```java
   package com.luxstay.hms.enums;

   public enum OrderStatus {
       PLACED, PREPARING, DELIVERED, CANCELLED;

       public boolean canMoveTo(OrderStatus next) {
           return switch (this) {
               case PLACED -> next == PREPARING || next == CANCELLED;
               case PREPARING -> next == DELIVERED || next == CANCELLED;
               case DELIVERED, CANCELLED -> false;
           };
       }
   }
   ```

5. Create the entities:
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "menu_item")
   public class MenuItem extends BaseEntity {

       @Column(nullable = false, unique = true, length = 100)
       private String name;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private MenuCategory category;

       @Column(nullable = false, precision = 10, scale = 2)
       private BigDecimal price;

       @Column(nullable = false)
       private boolean available = true;
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "room_service_order")
   public class RoomServiceOrder extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "reservation_id")
       private Reservation reservation;

       @Enumerated(EnumType.STRING)
       @Column(nullable = false, length = 20)
       private OrderStatus status;

       @Column(nullable = false, precision = 12, scale = 2)
       private BigDecimal total;

       private String notes;

       @Column(nullable = false)
       private Instant placedAt;

       private Instant deliveredAt;

       @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
       private List<OrderLine> lines = new ArrayList<>();

       public void addLine(OrderLine line) {
           line.setOrder(this);
           lines.add(line);
       }
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "order_line")
   public class OrderLine extends BaseEntity {

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "order_id")
       private RoomServiceOrder order;

       @ManyToOne(fetch = FetchType.LAZY, optional = false)
       @JoinColumn(name = "menu_item_id")
       private MenuItem menuItem;

       @Column(nullable = false)
       private Integer quantity;

       @Column(nullable = false, precision = 10, scale = 2)
       private BigDecimal unitPrice;

       public BigDecimal lineTotal() {
           return unitPrice.multiply(BigDecimal.valueOf(quantity));
       }
   }
   ```
   ```java
   package com.luxstay.hms.entity;

   @Getter
   @Setter
   @NoArgsConstructor
   @Entity
   @Table(name = "table_reservation")
   public class TableReservation extends BaseEntity {

       @Column(nullable = false, length = 200)
       private String guestName;

       @Column(nullable = false)
       private Integer partySize;

       @Column(nullable = false)
       private LocalDateTime reservedAt;

       private String notes;
   }
   ```

6. Create the repositories:
   ```java
   package com.luxstay.hms.repository;

   public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
       List<MenuItem> findByAvailableTrueOrderByCategoryAscNameAsc();
   }
   ```
   ```java
   package com.luxstay.hms.repository;

   public interface RoomServiceOrderRepository extends JpaRepository<RoomServiceOrder, Long> {

       @EntityGraph(attributePaths = {"reservation", "reservation.room", "lines", "lines.menuItem"})
       List<RoomServiceOrder> findByStatusInOrderByPlacedAtAsc(Collection<OrderStatus> statuses);

       @Override
       @EntityGraph(attributePaths = {"reservation", "reservation.room", "lines", "lines.menuItem"})
       Optional<RoomServiceOrder> findById(Long id);
   }
   ```
   ```java
   package com.luxstay.hms.repository;

   public interface TableReservationRepository extends JpaRepository<TableReservation, Long> {

       @Query("select coalesce(sum(t.partySize), 0) from TableReservation t where t.reservedAt = :slot")
       long coversAt(@Param("slot") LocalDateTime slot);

       List<TableReservation> findByReservedAtBetweenOrderByReservedAtAsc(LocalDateTime from, LocalDateTime to);
   }
   ```

7. Add the restaurant capacity under `app:` in `application.yaml`:
   ```yaml
   app:
     # ...jwt and billing unchanged...
     dining:
       covers-per-slot: 40
   ```
   - *A "cover" is one diner. At most 40 people can be booked in each 30-minute slot.*

8. Create the DTOs:
   ```java
   package com.luxstay.hms.dto.request;

   public record OrderLineRequest(
       @NotNull @Schema(example = "5") Long menuItemId,
       @Min(1) @Max(20) @Schema(example = "1") int quantity) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record PlaceOrderRequest(
       @NotNull @Schema(example = "1") Long reservationId,
       @NotEmpty List<@Valid OrderLineRequest> lines,
       @Size(max = 255) @Schema(example = "No onions, please") String notes) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record UpdateOrderStatusRequest(@NotNull @Schema(example = "PREPARING") OrderStatus status) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.request;

   public record TableReservationRequest(
       @NotBlank @Size(max = 200) @Schema(example = "Hart, party of 2") String guestName,
       @Min(1) @Max(12) @Schema(example = "2") int partySize,
       @NotNull @Future @Schema(example = "2026-12-20T20:00:00") LocalDateTime reservedAt,
       @Size(max = 255) String notes) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record MenuItemResponse(Long id, String name, MenuCategory category, BigDecimal price) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record OrderLineResponse(String menuItemName, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record OrderResponse(
       Long id,
       Long reservationId,
       String roomNumber,
       OrderStatus status,
       List<OrderLineResponse> lines,
       BigDecimal total,
       String notes,
       Instant placedAt,
       Instant deliveredAt) {
   }
   ```
   ```java
   package com.luxstay.hms.dto.response;

   public record TableReservationResponse(Long id, String guestName, Integer partySize,
                                          LocalDateTime reservedAt, String notes) {
   }
   ```

9. Create `mapper/DiningMapper.java`:
   ```java
   package com.luxstay.hms.mapper;

   @Mapper(componentModel = "spring")
   public interface DiningMapper {

       MenuItemResponse toResponse(MenuItem item);

       @Mapping(target = "reservationId", source = "reservation.id")
       @Mapping(target = "roomNumber", source = "reservation.room.number")
       OrderResponse toResponse(RoomServiceOrder order);

       @Mapping(target = "menuItemName", source = "menuItem.name")
       @Mapping(target = "lineTotal", expression = "java(line.lineTotal())")
       OrderLineResponse toResponse(OrderLine line);

       TableReservationResponse toResponse(TableReservation table);
   }
   ```

10. Create `service/DiningService.java`:
    ```java
    package com.luxstay.hms.service;

    public interface DiningService {
        List<MenuItemResponse> getMenu();
        OrderResponse placeOrder(PlaceOrderRequest request);
        OrderResponse updateStatus(Long orderId, OrderStatus next);
        List<OrderResponse> getOpenOrders();
        TableReservationResponse bookTable(TableReservationRequest request);
        List<TableReservationResponse> getTables(LocalDate date);
    }
    ```

11. Create `service/impl/DiningServiceImpl.java`:
    ```java
    package com.luxstay.hms.service.impl;

    @Slf4j
    @Service
    @Transactional(readOnly = true)
    public class DiningServiceImpl implements DiningService {

        private final MenuItemRepository menuItemRepository;
        private final RoomServiceOrderRepository orderRepository;
        private final TableReservationRepository tableRepository;
        private final ReservationRepository reservationRepository;
        private final FolioService folioService;
        private final DiningMapper diningMapper;
        private final Clock clock;
        private final int coversPerSlot;

        public DiningServiceImpl(MenuItemRepository menuItemRepository, RoomServiceOrderRepository orderRepository,
                                 TableReservationRepository tableRepository, ReservationRepository reservationRepository,
                                 FolioService folioService, DiningMapper diningMapper, Clock clock,
                                 @Value("${app.dining.covers-per-slot:40}") int coversPerSlot) {
            this.menuItemRepository = menuItemRepository;
            this.orderRepository = orderRepository;
            this.tableRepository = tableRepository;
            this.reservationRepository = reservationRepository;
            this.folioService = folioService;
            this.diningMapper = diningMapper;
            this.clock = clock;
            this.coversPerSlot = coversPerSlot;
        }

        @Override
        public List<MenuItemResponse> getMenu() {
            return menuItemRepository.findByAvailableTrueOrderByCategoryAscNameAsc()
                .stream().map(diningMapper::toResponse).toList();
        }

        @Override
        @Transactional
        public OrderResponse placeOrder(PlaceOrderRequest request) {
            Reservation reservation = reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", request.reservationId()));
            if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
                throw new BusinessRuleException("Room service is only for checked-in guests");
            }
            RoomServiceOrder order = new RoomServiceOrder();
            order.setReservation(reservation);
            order.setStatus(OrderStatus.PLACED);
            order.setNotes(request.notes());
            order.setPlacedAt(clock.instant());
            for (OrderLineRequest lineRequest : request.lines()) {
                MenuItem item = menuItemRepository.findById(lineRequest.menuItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item", lineRequest.menuItemId()));
                if (!item.isAvailable()) {
                    throw new BusinessRuleException(item.getName() + " is not available");
                }
                OrderLine line = new OrderLine();
                line.setMenuItem(item);
                line.setQuantity(lineRequest.quantity());
                line.setUnitPrice(item.getPrice());
                order.addLine(line);
            }
            order.setTotal(order.getLines().stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
            RoomServiceOrder saved = orderRepository.save(order);
            log.info("Room service order {} placed for room {}, total {}", saved.getId(),
                reservation.getRoom().getNumber(), saved.getTotal());
            return diningMapper.toResponse(saved);
        }

        @Override
        @Transactional
        public OrderResponse updateStatus(Long orderId, OrderStatus next) {
            RoomServiceOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
            if (!order.getStatus().canMoveTo(next)) {
                throw new BusinessRuleException("Order " + orderId + " can't go from " + order.getStatus() + " to " + next);
            }
            order.setStatus(next);
            if (next == OrderStatus.DELIVERED) {
                order.setDeliveredAt(clock.instant());
                folioService.postCharge(order.getReservation().getId(), ChargeOutlet.ROOM_SERVICE,
                    "Room service order #" + order.getId(), order.getTotal());
            }
            log.info("Order {} is now {}", orderId, next);
            return diningMapper.toResponse(order);
        }

        @Override
        public List<OrderResponse> getOpenOrders() {
            return orderRepository.findByStatusInOrderByPlacedAtAsc(List.of(OrderStatus.PLACED, OrderStatus.PREPARING))
                .stream().map(diningMapper::toResponse).toList();
        }

        @Override
        @Transactional
        public TableReservationResponse bookTable(TableReservationRequest request) {
            LocalDateTime slot = request.reservedAt().withSecond(0).withNano(0);
            if (slot.getMinute() % 30 != 0) {
                throw new BusinessRuleException("Tables are booked on the hour or half hour");
            }
            long booked = tableRepository.coversAt(slot);
            if (booked + request.partySize() > coversPerSlot) {
                throw new BusinessRuleException("Only " + (coversPerSlot - booked) + " covers left at " + slot.toLocalTime());
            }
            TableReservation table = new TableReservation();
            table.setGuestName(request.guestName());
            table.setPartySize(request.partySize());
            table.setReservedAt(slot);
            table.setNotes(request.notes());
            TableReservation saved = tableRepository.save(table);
            log.info("Table for {} booked at {}", saved.getPartySize(), slot);
            return diningMapper.toResponse(saved);
        }

        @Override
        public List<TableReservationResponse> getTables(LocalDate date) {
            return tableRepository.findByReservedAtBetweenOrderByReservedAtAsc(date.atStartOfDay(), date.atTime(LocalTime.MAX))
                .stream().map(diningMapper::toResponse).toList();
        }
    }
    ```
    - *This class writes its constructor by hand (no `@RequiredArgsConstructor`) because one argument comes from YAML via `@Value`.*
    - *The guest pays for room service only when it's **delivered**; a cancelled order never reaches the bill.*

12. Create `controller/DiningController.java`:
    ```java
    package com.luxstay.hms.controller;

    @RestController
    @RequestMapping("/api/v1/dining")
    @RequiredArgsConstructor
    @Tag(name = "Dining", description = "Menu, room service and restaurant tables")
    public class DiningController {

        private final DiningService diningService;

        @GetMapping("/menu")
        @Operation(summary = "Available menu items")
        public List<MenuItemResponse> menu() {
            return diningService.getMenu();
        }

        @PostMapping("/orders")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','FNB_STAFF')")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Place a room service order for a checked-in guest")
        public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
            return diningService.placeOrder(request);
        }

        @GetMapping("/orders/open")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FNB_STAFF')")
        @Operation(summary = "Kitchen queue: placed and preparing orders, oldest first")
        public List<OrderResponse> openOrders() {
            return diningService.getOpenOrders();
        }

        @PatchMapping("/orders/{id}/status")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FNB_STAFF')")
        @Operation(summary = "Move an order along; DELIVERED posts it to the guest's folio")
        public OrderResponse updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateOrderStatusRequest request) {
            return diningService.updateStatus(id, request.status());
        }

        @PostMapping("/tables")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','FNB_STAFF','CONCIERGE')")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Book a restaurant table (on the hour or half hour)")
        public TableReservationResponse bookTable(@Valid @RequestBody TableReservationRequest request) {
            return diningService.bookTable(request);
        }

        @GetMapping("/tables")
        @PreAuthorize("hasAnyRole('ADMIN','MANAGER','RECEPTIONIST','FNB_STAFF','CONCIERGE')")
        @Operation(summary = "Table bookings for a day")
        public List<TableReservationResponse> tables(
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
            return diningService.getTables(date);
        }
    }
    ```

13. Try it in Swagger: check a guest in (Step 12), then as `fnb_staff@luxstay.test`:
    - `GET /api/v1/dining/menu` → 10 items
    - `POST /api/v1/dining/orders` → `PLACED`; move it to `PREPARING`, then `DELIVERED`
    - The guest's folio now shows a `ROOM_SERVICE` charge plus tax
    - `PATCH …/status` from `DELIVERED` back to `PREPARING` → **409**

14. Create `src/test/java/com/luxstay/hms/unit/DiningServiceImplTest.java`:
    ```java
    @ExtendWith(MockitoExtension.class)
    class DiningServiceImplTest {

        @Mock MenuItemRepository menuItemRepository;
        @Mock RoomServiceOrderRepository orderRepository;
        @Mock TableReservationRepository tableRepository;
        @Mock ReservationRepository reservationRepository;
        @Mock FolioService folioService;
        DiningServiceImpl service;
        Reservation reservation;
        MenuItem burger;

        @BeforeEach
        void setUp() {
            Clock clock = Clock.fixed(Instant.parse("2026-10-01T19:00:00Z"), ZoneOffset.UTC);
            service = new DiningServiceImpl(menuItemRepository, orderRepository, tableRepository, reservationRepository,
                folioService, Mappers.getMapper(DiningMapper.class), clock, 40);
            Room room = new Room();
            room.setNumber("412");
            reservation = new Reservation();
            reservation.setId(5L);
            reservation.setRoom(room);
            reservation.setStatus(ReservationStatus.CHECKED_IN);
            burger = new MenuItem();
            burger.setId(5L);
            burger.setName("Wagyu Beef Burger");
            burger.setPrice(new BigDecimal("54.00"));
        }

        @Test
        void placeOrder_calculatesTotalFromMenuPrices() {
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(reservation));
            when(menuItemRepository.findById(5L)).thenReturn(Optional.of(burger));
            when(orderRepository.save(any(RoomServiceOrder.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderResponse response = service.placeOrder(
                new PlaceOrderRequest(5L, List.of(new OrderLineRequest(5L, 2)), null));

            assertThat(response.total()).isEqualByComparingTo("108.00");
            assertThat(response.status()).isEqualTo(OrderStatus.PLACED);
        }

        @Test
        void placeOrder_forGuestNotCheckedIn_isRejected() {
            reservation.setStatus(ReservationStatus.CONFIRMED);
            when(reservationRepository.findById(5L)).thenReturn(Optional.of(reservation));

            assertThatThrownBy(() -> service.placeOrder(
                    new PlaceOrderRequest(5L, List.of(new OrderLineRequest(5L, 1)), null)))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void delivered_postsChargeToFolio() {
            RoomServiceOrder order = new RoomServiceOrder();
            order.setId(9L);
            order.setReservation(reservation);
            order.setStatus(OrderStatus.PREPARING);
            order.setTotal(new BigDecimal("108.00"));
            when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

            service.updateStatus(9L, OrderStatus.DELIVERED);

            verify(folioService).postCharge(5L, ChargeOutlet.ROOM_SERVICE, "Room service order #9", new BigDecimal("108.00"));
            assertThat(order.getDeliveredAt()).isNotNull();
        }

        @Test
        void bookTable_overCapacity_isRejected() {
            when(tableRepository.coversAt(any())).thenReturn(38L);

            assertThatThrownBy(() -> service.bookTable(new TableReservationRequest("Hart", 4,
                    LocalDateTime.of(2026, 12, 20, 20, 0), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only 2 covers left");
        }

        @Test
        void bookTable_offSlot_isRejected() {
            assertThatThrownBy(() -> service.bookTable(new TableReservationRequest("Hart", 2,
                    LocalDateTime.of(2026, 12, 20, 20, 15), null)))
                .isInstanceOf(BusinessRuleException.class);
        }
    }
    ```

15. Commit, tick Step 15, push and merge:
    ```bash
    git add src README.md
    git commit -m "feat: Step 15 – add menu, room service orders and restaurant tables" -m "- V9 creates menu_item, room_service_order, order_line, table_reservation; V10 seeds 10 menu items
    - Orders only for CHECKED_IN guests; PLACED -> PREPARING -> DELIVERED (or CANCELLED)
    - DELIVERED posts a ROOM_SERVICE charge to the folio
    - Tables on :00 or :30, max app.dining.covers-per-slot diners per slot"
    git push -u origin feature/step-15-dining
    ```
    - *PR title: `feat: Step 15 – room service and restaurant`.*
