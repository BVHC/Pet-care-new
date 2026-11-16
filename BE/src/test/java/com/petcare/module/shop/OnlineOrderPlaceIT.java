package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.sales.CashierClockConfig;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.support.MutableClock;

/**
 * Đặt hàng online (UC62, BR-BH-08, BR-BH-09, ST09; docs/adr/0144) qua HTTP trên Postgres 17 thật: chi nhánh xử lý, báo
 * giá, đặt đơn (Order ONLINE + online_orders + giữ hàng + dọn giỏ + thông báo) và các cuộc đua "hàng cuối" — hai khách
 * đặt cùng lúc, đặt đơn so với thu tiền quầy. Đồng hồ: {@link CashierClockConfig} (era 2005, dùng chung với IT thu
 * ngân vì cần ca thu ngân cho cuộc đua với thu tiền); chi nhánh, sản phẩm, khách riêng nên không đụng IT khác.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class OnlineOrderPlaceIT extends CashierItSupport {

    private static final String PLACE = "/api/me/online-orders";
    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    private static final Instant NOW = CashierClockConfig.START;

    @Autowired
    private MutableClock clock;
    @Autowired
    private SystemConfigService configService;

    private long branch;
    private long branch2;
    private long manager;
    private long goods;
    private long cheap;
    private Shopper a;
    private Shopper b;
    private final List<Long> extraBranches = new ArrayList<>();

    /** Khách online (tài khoản + hồ sơ + một địa chỉ), token và giỏ riêng. */
    private record Shopper(long accountId, long customerId, long addressId, String token) {
    }

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        branch2 = newBranch();
        manager = staff("BRANCH_MANAGER", branch);
        goods = newPricedProduct("GOODS", false, 100_000);
        cheap = newPricedProduct("GOODS", false, 50_000);
        a = shopper("An");
        b = shopper("Bình");
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 30000, free_threshold = 0 WHERE id = 1");
    }

    @AfterEach
    void cleanOnline() {
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 0, free_threshold = 0 WHERE id = 1");
        jdbc.update("UPDATE system_configs SET value = '24' WHERE key = 'shop.payment_window_hours'");
        configService.reload();
        List<Long> all = new ArrayList<>(List.of(branch, branch2));
        all.addAll(extraBranches);
        String bs = String.join(", ", all.stream().map(String::valueOf).toList());
        List<Long> accountIds = List.of(a.accountId(), b.accountId());
        String as = accountIds.get(0) + ", " + accountIds.get(1);
        String cs = a.customerId() + ", " + b.customerId();
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (" + as + ") "
                + "OR recipient_email LIKE 'online-it-%'");
        jdbc.update("DELETE FROM stock_reservations WHERE branch_id IN (" + bs + ")");
        jdbc.update("DELETE FROM online_orders WHERE branch_id IN (" + bs + ")");
        jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE branch_id IN (" + bs + "))");
        jdbc.update("DELETE FROM orders WHERE branch_id IN (" + bs + ")");
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id IN (" + cs + "))");
        jdbc.update("DELETE FROM carts WHERE customer_id IN (" + cs + ")");
        jdbc.update("DELETE FROM addresses WHERE customer_id IN (" + cs + ")");
        jdbc.update("DELETE FROM customers WHERE id IN (" + cs + ")");
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id IN ("
                + as + "))");
        jdbc.update("DELETE FROM sessions WHERE account_id IN (" + as + ")");
        jdbc.update("DELETE FROM accounts WHERE id IN (" + as + ")");
        if (!extraBranches.isEmpty()) {
            jdbc.update("DELETE FROM branches WHERE id IN (" + String.join(", ",
                    extraBranches.stream().map(String::valueOf).toList()) + ")");
        }
    }

    // ================================================================ dữ liệu

    private Shopper shopper(String name) {
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'x', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, "online-it-" + UUID.randomUUID() + "@example.com");
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE') RETURNING id""",
                Long.class, account, "Khách online " + name);
        long address = address(customer, "Nhận " + name);
        return new Shopper(account, customer, address, token(account));
    }

    private long address(long customerId, String receiver) {
        return jdbc.queryForObject("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, ward, province, is_default)
                VALUES (?, ?, '0901234567', '12 Lê Lợi', 'P. Bến Nghé', 'TP.HCM', false) RETURNING id""", Long.class,
                customerId, receiver);
    }

    private void cart(Shopper s, long productId, int quantity) {
        ResponseEntity<Map<String, Object>> r = call(HttpMethod.PUT, "/api/me/cart/items/" + productId, s.token(),
                Map.of("quantity", quantity));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> place(Shopper s, long branchId) {
        return place(s, branchId, s.addressId());
    }

    private ResponseEntity<Map<String, Object>> place(Shopper s, long branchId, long addressId) {
        return call(HttpMethod.POST, PLACE, s.token(), Map.of("branchId", branchId, "addressId", addressId));
    }

    private ResponseEntity<Map<String, Object>> quote(Shopper s, long branchId, long addressId) {
        return call(HttpMethod.GET, PLACE + "/quote?branchId=" + branchId + "&addressId=" + addressId, s.token(), null);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long onlineOrders(long branchId) {
        return count("SELECT count(*) FROM online_orders WHERE branch_id = ?", branchId);
    }

    private long ordersOf(long branchId) {
        return count("SELECT count(*) FROM orders WHERE branch_id = ?", branchId);
    }

    private long held(long branchId, long productId) {
        return count("SELECT COALESCE(sum(quantity), 0) FROM stock_reservations "
                + "WHERE branch_id = ? AND product_id = ? AND status = 'HELD'", branchId, productId);
    }

    private long cartLines(Shopper s) {
        return count("SELECT count(*) FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id = ?)",
                s.customerId());
    }

    /** Giá trị hiện tại của dãy mã (null nếu chưa có dòng). */
    private Long counter(String docType) {
        return jdbc.queryForList("SELECT last_value FROM document_counters WHERE doc_type = ? AND period = '0506'",
                Long.class, docType).stream().findFirst().orElse(null);
    }

    private void assertNothingPlaced(long branchId) {
        assertThat(onlineOrders(branchId)).isZero();
        assertThat(ordersOf(branchId)).isZero();
        assertThat(held(branchId, goods) + held(branchId, cheap)).isZero();
    }

    // ================================================================ đặt đơn: đường chính

    @Test
    void placeCreatesTheOnlineOrderTheOnlineOrderRowTheReservationsAndClearsTheCart() {
        long lot = defaultLot(branch, goods, 10, manager);
        defaultLot(branch, cheap, 10, manager);
        cart(a, goods, 2);
        cart(a, cheap, 1);

        ResponseEntity<Map<String, Object>> response = place(a, branch);

        Map<String, Object> order = data(response, HttpStatus.CREATED);
        assertThat(order).containsEntry("status", "PENDING_PAYMENT").containsEntry("branchId", (int) branch)
                .containsEntry("receiverName", "Nhận An").containsEntry("receiverPhone", "0901234567")
                .containsEntry("shipAddress", "12 Lê Lợi, P. Bến Nghé, TP.HCM").containsEntry("subtotal", 250_000)
                .containsEntry("shippingFee", 30_000).containsEntry("total", 280_000)
                .containsEntry("redeliveryCount", 0);
        assertThat((String) order.get("code")).matches("DO-0506-\\d{5}");
        assertThat((String) order.get("branchName")).startsWith("KhoIT chi nhánh");
        assertThat(order.get("paymentDueAt")).isEqualTo("2005-06-16T03:00:00Z");
        assertThat(order.get("paidAt")).isNull();
        assertThat(order.get("carrier")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines).anySatisfy(line -> assertThat(line).containsEntry("productId", (int) goods)
                .containsEntry("unitPrice", 100_000).containsEntry("quantity", 2).containsEntry("lineTotal", 200_000));

        long orderId = number(order.get("salesOrderId"));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(row).containsEntry("source", "ONLINE").containsEntry("status", "PENDING")
                .containsEntry("total_amount", 250_000L).containsEntry("customer_id", a.customerId())
                .containsEntry("branch_id", branch).containsEntry("created_by", a.accountId());
        assertThat((String) row.get("code")).matches("DH-0506-\\d{5}");
        assertThat(sumOfLines(orderId)).isEqualTo(250_000);

        Map<String, Object> online = jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?",
                number(order.get("onlineOrderId")));
        assertThat(online).containsEntry("status", "PENDING_PAYMENT").containsEntry("order_id", orderId)
                .containsEntry("shipping_fee", 30_000L).containsEntry("customer_id", a.customerId());
        assertThat(held(branch, goods)).isEqualTo(2);
        assertThat(held(branch, cheap)).isEqualTo(1);
        assertThat(lotQuantity(lot)).as("giữ hàng chưa trừ kho").isEqualTo(10);
        assertThat(cartLines(a)).isZero();
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? "
                + "AND template_code = 'ONLINE_ORDER_PLACED_APP'", a.accountId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE template_code = 'ONLINE_ORDER_PLACED' "
                + "AND payload ->> 'ma_don' = ?", order.get("code"))).isEqualTo(1);
    }

    @Test
    void onlyTheOrderedLinesLeaveTheCartAndAnIneligibleLineStaysInIt() {
        defaultLot(branch, goods, 10, manager);
        defaultLot(branch, cheap, 10, manager);
        cart(a, goods, 1);
        cart(a, cheap, 1);
        jdbc.update("UPDATE products SET product_type = 'DRUG', tracks_expiry = true WHERE id = ?", cheap);

        Map<String, Object> order = data(place(a, branch), HttpStatus.CREATED);

        assertThat(order).containsEntry("subtotal", 100_000);
        assertThat(held(branch, cheap)).isZero();
        assertThat(cartLines(a)).as("dòng không bán được ở lại giỏ, ẩn khỏi khách").isEqualTo(1);
    }

    @Test
    void theOrderSnapshotsThePriceAtPlacementAndTheShippingFeeAndTheDueDate() {
        defaultLot(branch, goods, 10, manager);
        cart(a, goods, 2);
        jdbc.update("UPDATE products SET price = 120000 WHERE id = ?", goods);
        jdbc.update("UPDATE system_configs SET value = '48' WHERE key = 'shop.payment_window_hours'");
        configService.reload();

        Map<String, Object> order = data(place(a, branch), HttpStatus.CREATED);

        assertThat(order).containsEntry("subtotal", 240_000).containsEntry("shippingFee", 30_000);
        assertThat(order.get("paymentDueAt")).isEqualTo("2005-06-17T03:00:00Z");
        long orderId = number(order.get("salesOrderId"));
        jdbc.update("UPDATE products SET price = 150000 WHERE id = ?", goods);
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 99999 WHERE id = 1");
        jdbc.update("UPDATE system_configs SET value = '24' WHERE key = 'shop.payment_window_hours'");
        configService.reload();
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_lines WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(120_000);
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM online_orders WHERE order_id = ?", Long.class,
                orderId)).isEqualTo(30_000);
        assertThat(jdbc.queryForObject("SELECT payment_due_at FROM online_orders WHERE order_id = ?",
                java.sql.Timestamp.class, orderId).toInstant()).isEqualTo(Instant.parse("2005-06-17T03:00:00Z"));
    }

    // ================================================================ đặt đơn: từ chối, không ghi gì

    @Test
    void anEmptyCartAndACartWithOnlyIneligibleProductsAre400AndWriteNothing() {
        assertRule(place(a, branch), "BR-BH-08");

        defaultLot(branch, goods, 10, manager);
        cart(a, goods, 1);
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", goods);
        assertRule(place(a, branch), "BR-BH-08");

        assertNothingPlaced(branch);
        assertThat(cartLines(a)).isEqualTo(1);
    }

    @Test
    void aMissingBranchIs404ADraftBranchIs400AndAForeignOrMissingAddressIs404() {
        defaultLot(branch, goods, 10, manager);
        cart(a, goods, 1);
        long draft = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'DRAFT') RETURNING id
                """, Long.class, "OnlineIT nháp " + UUID.randomUUID());
        extraBranches.add(draft);

        assertThat(place(a, -1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertRule(place(a, draft), "BR-BH-08");
        assertThat(place(a, branch, b.addressId()).getStatusCode()).as("địa chỉ của khách khác")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(place(a, branch, -1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertNothingPlaced(branch);
        assertThat(cartLines(a)).isEqualTo(1);
    }

    @Test
    void aShortLineIs400NamingItAndNothingIsWrittenNorConsumed() {
        defaultLot(branch, goods, 10, manager);
        defaultLot(branch, cheap, 2, manager);
        cart(a, goods, 1);
        cart(a, cheap, 3);
        Long orderCounter = counter("ORDER");
        Long onlineCounter = counter("ONLINE_ORDER");

        ResponseEntity<Map<String, Object>> response = place(a, branch);

        assertRule(response, "BR-BH-08");
        assertThat((String) response.getBody().get("message")).contains(
                jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, cheap));
        assertNothingPlaced(branch);
        assertThat(cartLines(a)).isEqualTo(2);
        assertThat(counter("ORDER")).as("mã chứng từ chưa tiêu").isEqualTo(orderCounter);
        assertThat(counter("ONLINE_ORDER")).isEqualTo(onlineCounter);
    }

    @Test
    void expiredLotsAreIgnoredAndHeldQuantityOfOtherOrdersIsSubtracted() {
        lot(branch, goods, "HET-HAN", "2005-06-14", 50, manager);
        defaultLot(branch, goods, 5, manager);
        cart(a, goods, 3);
        cart(b, goods, 3);

        data(place(a, branch), HttpStatus.CREATED);

        assertRule(place(b, branch), "BR-BH-08");
        cart(b, goods, 2);
        data(place(b, branch), HttpStatus.CREATED);
        assertThat(held(branch, goods)).isEqualTo(5);
        assertThat(onlineOrders(branch)).isEqualTo(2);
    }

    // ================================================================ báo giá, chi nhánh

    @Test
    void quoteWritesNothingAndFlagsFreeShippingAtTheThresholdBoundary() {
        defaultLot(branch, goods, 10, manager);
        cart(a, goods, 2);
        Long orderCounter = counter("ORDER");

        Map<String, Object> quote = data(quote(a, branch, a.addressId()), HttpStatus.OK);
        assertThat(quote).containsEntry("subtotal", 200_000).containsEntry("shippingFee", 30_000)
                .containsEntry("freeShippingApplied", false).containsEntry("total", 230_000)
                .containsEntry("branchId", (int) branch);

        jdbc.update("UPDATE shipping_fee_configs SET free_threshold = 200000 WHERE id = 1");
        quote = data(quote(a, branch, a.addressId()), HttpStatus.OK);
        assertThat(quote).containsEntry("shippingFee", 0).containsEntry("freeShippingApplied", true)
                .containsEntry("total", 200_000);
        jdbc.update("UPDATE shipping_fee_configs SET free_threshold = 200001 WHERE id = 1");
        quote = data(quote(a, branch, a.addressId()), HttpStatus.OK);
        assertThat(quote).containsEntry("shippingFee", 30_000).containsEntry("freeShippingApplied", false);

        assertNothingPlaced(branch);
        assertThat(cartLines(a)).isEqualTo(1);
        assertThat(counter("ORDER")).isEqualTo(orderCounter);
    }

    @Test
    void quoteRejectsAnEmptyCartAShortBranchAndABadAddressOrBranch() {
        assertRule(quote(a, branch, a.addressId()), "BR-BH-08");

        defaultLot(branch, goods, 1, manager);
        cart(a, goods, 2);
        assertRule(quote(a, branch, a.addressId()), "BR-BH-08");
        assertThat(quote(a, branch, b.addressId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(quote(a, -1, a.addressId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fulfillmentBranchesListOnlyActiveBranchesWithEveryLineInStockNetOfHeld() {
        defaultLot(branch, goods, 5, manager);
        defaultLot(branch2, goods, 1, manager);
        assertThat(list(call(HttpMethod.GET, PLACE + "/fulfillment-branches", a.token(), null), HttpStatus.OK))
                .as("giỏ rỗng").isEmpty();
        cart(a, goods, 2);

        assertThat(branchIds()).contains(branch).doesNotContain(branch2);

        cart(b, goods, 4);
        data(place(b, branch), HttpStatus.CREATED);
        assertThat(branchIds()).as("còn 1 sau khi trừ phần giữ").doesNotContain(branch, branch2);
    }

    private List<Long> branchIds() {
        return list(call(HttpMethod.GET, PLACE + "/fulfillment-branches", a.token(), null), HttpStatus.OK).stream()
                .map(x -> number(x.get("branchId"))).toList();
    }

    // ================================================================ quyền

    @Test
    void staffIs403AndAnonymousIs401OnAllThreeOperations() {
        String staffToken = token(manager);
        String body = "{\"branchId\":" + branch + ",\"addressId\":" + a.addressId() + "}";
        for (String token : new String[] {staffToken, null}) {
            HttpStatus expected = token == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            assertThat(call(HttpMethod.GET, PLACE + "/fulfillment-branches", token, null).getStatusCode())
                    .isEqualTo(expected);
            assertThat(call(HttpMethod.GET, PLACE + "/quote?branchId=" + branch + "&addressId=" + a.addressId(),
                    token, null).getStatusCode()).isEqualTo(expected);
            assertThat(callRaw(HttpMethod.POST, PLACE, token, body).getStatusCode()).isEqualTo(expected);
        }
    }

    // ================================================================ cuộc đua "hàng cuối" (Xong khi)

    @Test
    void twoCustomersPlacingTheLastUnitsConcurrentlyExactlyOneWinsAndHeldNeverExceedsOnHand() throws Exception {
        defaultLot(branch, goods, 5, manager);
        cart(a, goods, 5);
        cart(b, goods, 5);

        List<ResponseEntity<Map<String, Object>>> results = whileLotsLocked(
                () -> place(a, branch), () -> place(b, branch));

        long created = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        List<ResponseEntity<Map<String, Object>>> failed = results.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.CREATED).toList();
        assertThat(created).as("%s", results).isEqualTo(1);
        assertThat(failed).hasSize(1);
        assertRule(failed.get(0), "BR-BH-08");
        assertThat(held(branch, goods)).isEqualTo(5).isLessThanOrEqualTo(stockOf(branch, goods));
        assertThat(onlineOrders(branch)).isEqualTo(1);
        assertThat(ordersOf(branch)).isEqualTo(1);
        assertThat(cartLines(a) + cartLines(b)).as("chỉ giỏ của người thắng được dọn").isEqualTo(1);
    }

    @Test
    void placementQueuedBeforeACounterPaymentForTheLastUnitsWinsAndThePaymentCannotIssueTheHeldUnits() throws Exception {
        placementVersusCounterPayment(true);
    }

    @Test
    void aCounterPaymentQueuedBeforeAPlacementForTheLastUnitsWinsAndThePlacementIsRejected() throws Exception {
        placementVersusCounterPayment(false);
    }

    /** Hai bên xếp hàng chờ khóa lô theo đúng thứ tự {@code placementFirst}; đúng một bên thắng. */
    private void placementVersusCounterPayment(boolean placementFirst) throws Exception {
        defaultLot(branch, goods, 5, manager);
        long receptionist = receptionist(branch);
        String tReceptionist = token(receptionist);
        openShiftSql(branch, receptionist, false, DAY, NOW);
        long counterCustomer = newCustomer();
        long counterOrder = pendingOrderSql(branch, counterCustomer, receptionist,
                LineSpec.goods(goods, "SP", 5, 100_000));
        cart(a, goods, 5);
        java.util.function.Supplier<ResponseEntity<Map<String, Object>>> placeAction = () -> place(a, branch);
        java.util.function.Supplier<ResponseEntity<Map<String, Object>>> payAction = () -> call(HttpMethod.POST,
                "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(counterOrder), "method", "CASH", "amount", 500_000));

        List<ResponseEntity<Map<String, Object>>> results = placementFirst
                ? whileLotsLocked(placeAction, payAction) : whileLotsLocked(payAction, placeAction);

        ResponseEntity<Map<String, Object>> placement = results.get(placementFirst ? 0 : 1);
        ResponseEntity<Map<String, Object>> payment = results.get(placementFirst ? 1 : 0);
        boolean placed = placement.getStatusCode() == HttpStatus.CREATED;
        boolean paid = payment.getStatusCode().is2xxSuccessful();
        assertThat(placed).as("người xếp hàng trước thắng: %s / %s", placement.getBody(), payment.getBody())
                .isEqualTo(placementFirst);
        assertThat(paid).isEqualTo(!placementFirst);
        if (placementFirst) {
            assertThat(held(branch, goods)).isEqualTo(5);
            assertThat(stockOf(branch, goods)).as("quầy không xuất được phần đã giữ").isEqualTo(5);
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(orderStatus(counterOrder)).isEqualTo("PENDING");
        } else {
            assertRule(placement, "BR-BH-08");
            assertThat(stockOf(branch, goods)).isZero();
            assertThat(held(branch, goods)).isZero();
            assertThat(orderStatus(counterOrder)).isEqualTo("PAID");
        }
        assertThat(held(branch, goods)).isLessThanOrEqualTo(stockOf(branch, goods));
    }

    /**
     * Giữ khóa mọi lô của sản phẩm tại chi nhánh ở một transaction khác, chạy các việc song song, chờ tới khi cả hai
     * cùng đang đợi khóa lô ({@code pg_stat_activity}) rồi mới nhả: hai bên thật sự tranh nhau ở đúng điểm khóa.
     */
    @SafeVarargs
    private List<ResponseEntity<Map<String, Object>>> whileLotsLocked(
            java.util.function.Supplier<ResponseEntity<Map<String, Object>>>... actions) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.length + 1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("""
                        SELECT l.id FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                        WHERE i.branch_id = ? AND i.product_id = ? ORDER BY l.id FOR NO KEY UPDATE OF l""", branch, goods);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (var action : actions) {
                futures.add(pool.submit(action::get));
                // xếp hàng lần lượt: thứ tự tới hàng đợi khóa = thứ tự truyền vào, nên kết quả xác định
                awaitLockWait(futures.size(), "stock_lots", "for no key update");
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
