package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.support.MutableClock;

/**
 * Xử lý và giao đơn online (UC65, BR-BH-11; docs/adr/0146) qua HTTP trên Postgres 17 thật: mọi chuyển trạng thái
 * #5–#9, mọi từ-trạng-thái sai là 409, thiếu đầu vào là 400, giao lại tối đa [CFG], phạm vi chi nhánh và vai trò, thông
 * báo đủ biến, không đụng kho, và các cuộc đua với khóa {@code online_orders}. Era <b>năm 2051</b> (không job nào quét
 * tới); chi nhánh, sản phẩm, khách riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OnlineOrderFulfillmentIT.TestBeans.class})
class OnlineOrderFulfillmentIT extends CashierItSupport {

    private static final String ME = "/api/me/online-orders";
    private static final String STAFF = "/api/online-orders";
    private static final Instant START = Instant.parse("2051-06-15T03:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private MutableClock clock;

    private long branch;
    private long otherBranch;
    private long goods;
    private long manager;
    private String rec;
    private String branchManager;
    private String otherRec;
    private String otherManager;
    private String superManager;
    private Shopper a;

    private record Shopper(long accountId, long customerId, long addressId, String token, String email) {
    }

    private record Placed(long id, long orderId, String code) {
    }

    @BeforeEach
    void setUp() {
        clock.set(START);
        branch = newBranch();
        otherBranch = newBranch();
        manager = staff("BRANCH_MANAGER", branch);
        goods = newPricedProduct("GOODS", false, 100_000);
        defaultLot(branch, goods, 1000, manager);
        defaultLot(otherBranch, goods, 1000, manager);
        rec = token(receptionist(branch));
        branchManager = token(manager);
        otherRec = token(receptionist(otherBranch));
        otherManager = token(staff("BRANCH_MANAGER", otherBranch));
        superManager = token(staff("SUPER_MANAGER", null));
        a = shopper();
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 30000, free_threshold = 0 WHERE id = 1");
    }

    @AfterEach
    void cleanOnline() {
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 0, free_threshold = 0 WHERE id = 1");
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ? OR recipient_email = ?",
                a.accountId(), a.email());
        String branches = branch + ", " + otherBranch;
        jdbc.update("DELETE FROM payment_transactions WHERE online_order_id IN "
                + "(SELECT id FROM online_orders WHERE branch_id IN (" + branches + "))");
        jdbc.update("DELETE FROM stock_reservations WHERE branch_id IN (" + branches + ")");
        jdbc.update("DELETE FROM online_orders WHERE branch_id IN (" + branches + ")");
        jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE branch_id IN (" + branches
                + "))");
        jdbc.update("DELETE FROM orders WHERE branch_id IN (" + branches + ")");
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id = ?)",
                a.customerId());
        jdbc.update("DELETE FROM carts WHERE customer_id = ?", a.customerId());
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", a.customerId());
        jdbc.update("DELETE FROM customers WHERE id = ?", a.customerId());
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id = ?)",
                a.accountId());
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", a.accountId());
        jdbc.update("DELETE FROM accounts WHERE id = ?", a.accountId());
    }

    // ================================================================ dữ liệu

    private Shopper shopper() {
        String email = "fulfil-it-" + UUID.randomUUID() + "@example.com";
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'x', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, email);
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, 'Khách giao hàng', 'ONLINE')
                RETURNING id""", Long.class, account);
        long address = jdbc.queryForObject("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, ward, province, is_default)
                VALUES (?, 'Người nhận', '0901234567', '12 Lê Lợi', 'P. Bến Nghé', 'TP.HCM', false) RETURNING id""",
                Long.class, customer);
        return new Shopper(account, customer, address, token(account), email);
    }

    /** Đặt đơn thật qua HTTP tại {@code branchId} rồi (nếu {@code status} khác PENDING_PAYMENT) đưa tới trạng thái bằng SQL. */
    private Placed order(long branchId, String status) {
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + goods, a.token(), Map.of("quantity", 2))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> placed = data(call(HttpMethod.POST, ME, a.token(),
                Map.of("branchId", branchId, "addressId", a.addressId())), HttpStatus.CREATED);
        Placed order = new Placed(number(placed.get("onlineOrderId")), number(placed.get("salesOrderId")),
                (String) placed.get("code"));
        force(order.id(), status);
        return order;
    }

    private Placed order(String status) {
        return order(branch, status);
    }

    /** Đưa đơn tới một trạng thái bằng SQL, thỏa mọi CHECK của {@code online_orders}. */
    private void force(long id, String status) {
        Timestamp t = Timestamp.from(START);
        switch (status) {
            case "PENDING_PAYMENT" -> {
            }
            case "PAID", "PROCESSING" -> jdbc.update("UPDATE online_orders SET status = ?, paid_at = ? WHERE id = ?",
                    status, t, id);
            case "SHIPPED", "DELIVERY_FAILED" -> jdbc.update("""
                    UPDATE online_orders SET status = ?, paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                             tracking_code = 'GHN1' WHERE id = ?""", status, t, t, id);
            case "DELIVERED" -> jdbc.update("""
                    UPDATE online_orders SET status = ?, paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                             tracking_code = 'GHN1', delivered_at = ? WHERE id = ?""", status, t, t, t, id);
            case "COMPLETED" -> jdbc.update("""
                    UPDATE online_orders SET status = ?, paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                             tracking_code = 'GHN1', delivered_at = ?, completed_at = ? WHERE id = ?""",
                    status, t, t, t, t, id);
            case "CANCELLED" -> jdbc.update(
                    "UPDATE online_orders SET status = ?, cancelled_at = ?, cancel_reason = 'x' WHERE id = ?", status, t,
                    id);
            default -> throw new IllegalArgumentException(status);
        }
    }

    private ResponseEntity<Map<String, Object>> post(String token, long id, String action, Object body) {
        return call(HttpMethod.POST, STAFF + "/" + id + "/" + action, token, body);
    }

    private ResponseEntity<Map<String, Object>> start(String token, long id) {
        return post(token, id, "start-processing", null);
    }

    private ResponseEntity<Map<String, Object>> ship(String token, long id, String carrier, String tracking) {
        return post(token, id, "ship", Map.of("carrier", carrier, "trackingCode", tracking));
    }

    private ResponseEntity<Map<String, Object>> deliver(String token, long id) {
        return post(token, id, "mark-delivered", null);
    }

    private ResponseEntity<Map<String, Object>> fail(String token, long id, String reason) {
        return post(token, id, "fail-delivery", Map.of("reason", reason));
    }

    private ResponseEntity<Map<String, Object>> redeliver(String token, long id, String carrier, String tracking) {
        return post(token, id, "redeliver", Map.of("carrier", carrier, "trackingCode", tracking));
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM online_orders WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?", id);
    }

    private static Instant at(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    private List<Map<String, Object>> outbox(String codePrefix, String code) {
        return jdbc.queryForList("""
                SELECT channel, template_code, recipient_account_id, recipient_email, CAST(payload AS text) AS payload
                FROM notification_outbox WHERE template_code LIKE ? AND payload ->> 'ma_don' = ?
                ORDER BY id""", codePrefix + "%", code);
    }

    /** Thông báo của khách trừ ONLINE_ORDER_PLACED (do lúc đặt đơn, không thuộc UC65). */
    private long outboxCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE (recipient_account_id = ? OR recipient_email = ?)
                  AND template_code NOT LIKE 'ONLINE_ORDER_PLACED%'""", Long.class, a.accountId(), a.email());
    }

    // ================================================================ đường chính

    @Test
    @SuppressWarnings("unchecked")
    void thePaidOrderGoesThroughProcessingShippedAndDeliveredAndNeverTouchesStockOrTheSalesOrder() {
        Placed order = order("PAID");
        long lotsBefore = stockOf(branch, goods);

        clock.set(START.plusSeconds(60));
        Map<String, Object> processing = data(start(rec, order.id()), HttpStatus.OK);
        assertThat(processing).containsEntry("status", "PROCESSING").containsEntry("onlineOrderId", (int) order.id())
                .containsEntry("total", 230_000);
        assertThat(processing.get("shippedAt")).isNull();
        assertThat(outboxCount()).as("#5 không có thông báo").isZero();

        clock.set(START.plusSeconds(120));
        Map<String, Object> shipped = data(ship(rec, order.id(), "  Giao hàng nhanh ", " GHN123 "), HttpStatus.OK);
        assertThat(shipped).containsEntry("status", "SHIPPED").containsEntry("carrier", "Giao hàng nhanh")
                .containsEntry("trackingCode", "GHN123").containsEntry("shippedAt", "2051-06-15T03:02:00Z");
        Map<String, Object> shippedRow = row(order.id());
        assertThat(at(shippedRow.get("shipped_at"))).isEqualTo(START.plusSeconds(120));
        assertThat(outboxCount()).as("#6: IN_APP + EMAIL").isEqualTo(2);

        clock.set(START.plusSeconds(180));
        Map<String, Object> delivered = data(deliver(rec, order.id()), HttpStatus.OK);
        assertThat(delivered).containsEntry("status", "DELIVERED").containsEntry("deliveredAt", "2051-06-15T03:03:00Z");
        assertThat(at(row(order.id()).get("delivered_at"))).isEqualTo(START.plusSeconds(180));
        assertThat(outboxCount()).as("#7 không có thông báo").isEqualTo(2);

        assertThat(stockOf(branch, goods)).as("không xuất / nhập kho ở xử lý và giao").isEqualTo(lotsBefore);
        assertThat(jdbc.queryForObject("SELECT string_agg(DISTINCT status, ',') FROM stock_reservations "
                + "WHERE online_order_id = ?", String.class, order.id())).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE source_type = 'orders' AND source_id = ?", Long.class,
                order.orderId())).isZero();
    }

    @Test
    void shipNotifiesTheCustomerOnBothChannelsWithEveryRequiredVariable() {
        Placed order = order("PROCESSING");

        data(ship(rec, order.id(), "J&T Express", "JT0001"), HttpStatus.OK);

        List<Map<String, Object>> rows = outbox("ONLINE_ORDER_SHIPPED", order.code());
        assertThat(rows).extracting(r -> r.get("template_code")).containsExactly("ONLINE_ORDER_SHIPPED_APP",
                "ONLINE_ORDER_SHIPPED");
        assertThat(rows.get(0)).containsEntry("channel", "IN_APP").containsEntry("recipient_account_id", a.accountId());
        assertThat(rows.get(1)).containsEntry("channel", "EMAIL").containsEntry("recipient_email", a.email());
        for (Map<String, Object> notification : rows) {
            assertThat((String) notification.get("payload")).contains("\"ma_don\"", order.code(),
                    "\"don_vi_van_chuyen\"", "J&T Express", "\"ma_van_don\"", "JT0001", "\"ten_khach\"",
                    "Khách giao hàng");
        }
    }

    @Test
    void failDeliveryStoresTheReasonAndNotifiesWithTheBranchNameAndPhone() {
        Placed order = order("SHIPPED");
        String branchPhone = jdbc.queryForObject("SELECT phone FROM branches WHERE id = ?", String.class, branch);
        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        clock.set(START.plusSeconds(60));

        Map<String, Object> body = data(fail(rec, order.id(), "  Khách không nghe máy  "), HttpStatus.OK);

        assertThat(body).containsEntry("status", "DELIVERY_FAILED").containsEntry("redeliveryCount", 0)
                .doesNotContainKey("deliveryFailureReason");
        assertThat(row(order.id())).containsEntry("delivery_failure_reason", "Khách không nghe máy");
        List<Map<String, Object>> rows = outbox("ONLINE_ORDER_DELIVERY_FAILED", order.code());
        assertThat(rows).hasSize(2).extracting(r -> r.get("channel")).containsExactly("IN_APP", "EMAIL");
        for (Map<String, Object> notification : rows) {
            assertThat((String) notification.get("payload")).contains("\"ly_do\"", "Khách không nghe máy",
                    "\"chi_nhanh\"", branchName, "\"so_dien_thoai_chi_nhanh\"", branchPhone, "\"ma_don\"", order.code());
        }
    }

    @Test
    void redeliverAddsOneAttemptReplacesCarrierAndTrackingKeepsTheReasonAndSendsNoNotification() {
        Placed order = order("PROCESSING");
        data(ship(rec, order.id(), "GHN", "A1"), HttpStatus.OK);
        clock.set(START.plusSeconds(600));
        data(fail(rec, order.id(), "Sai địa chỉ"), HttpStatus.OK);
        long notifications = outboxCount();
        clock.set(START.plusSeconds(1200));

        Map<String, Object> body = data(redeliver(rec, order.id(), " J&T ", " B2 "), HttpStatus.OK);

        assertThat(body).containsEntry("status", "SHIPPED").containsEntry("redeliveryCount", 1)
                .containsEntry("carrier", "J&T").containsEntry("trackingCode", "B2")
                .containsEntry("shippedAt", "2051-06-15T03:20:00Z");
        assertThat(row(order.id())).containsEntry("redelivery_count", 1)
                .containsEntry("delivery_failure_reason", "Sai địa chỉ");
        assertThat(outboxCount()).as("08 §B #9 không có thông báo").isEqualTo(notifications);
    }

    @Test
    void redeliveryStopsAtTheConfiguredMaximumAfterTwoAttemptsAndKeepsTheOrderFailed() {
        Placed order = order("PROCESSING");
        data(ship(rec, order.id(), "GHN", "A0"), HttpStatus.OK);
        for (int attempt = 1; attempt <= 2; attempt++) {
            data(fail(rec, order.id(), "Lần " + attempt), HttpStatus.OK);
            Map<String, Object> body = data(redeliver(rec, order.id(), "GHN", "A" + attempt), HttpStatus.OK);
            assertThat(body).containsEntry("redeliveryCount", attempt);
        }
        data(fail(rec, order.id(), "Lần ba"), HttpStatus.OK);

        ResponseEntity<Map<String, Object>> response = redeliver(rec, order.id(), "GHN", "A3");

        assertRule(response, "BR-BH-11");
        assertThat(status(order.id())).isEqualTo("DELIVERY_FAILED");
        assertThat(row(order.id())).containsEntry("redelivery_count", 2).containsEntry("tracking_code", "A2");
    }

    // ================================================================ từ-trạng-thái sai và đầu vào thiếu

    @Test
    void everyTransitionFromAnyOtherStateIs409AndChangesNothing() {
        Map<String, String> allowedFrom = Map.of("start-processing", "PAID", "ship", "PROCESSING", "mark-delivered",
                "SHIPPED", "fail-delivery", "SHIPPED", "redeliver", "DELIVERY_FAILED");
        Map<String, Object> bodies = Map.of("ship", Map.of("carrier", "GHN", "trackingCode", "T"), "redeliver",
                Map.of("carrier", "GHN", "trackingCode", "T"), "fail-delivery", Map.of("reason", "Lý do"));
        for (String status : List.of("PENDING_PAYMENT", "PAID", "PROCESSING", "SHIPPED", "DELIVERY_FAILED", "DELIVERED",
                "COMPLETED", "CANCELLED")) {
            Placed order = order(status);
            Map<String, Object> before = row(order.id());
            for (Map.Entry<String, String> entry : allowedFrom.entrySet()) {
                if (entry.getValue().equals(status)) {
                    continue;
                }
                assertError(post(rec, order.id(), entry.getKey(), bodies.get(entry.getKey())), HttpStatus.CONFLICT,
                        "INVALID_STATE_TRANSITION");
            }
            assertThat(row(order.id())).as(status).isEqualTo(before);
        }
        assertThat(outboxCount()).isZero();
    }

    @Test
    void aSecondIdenticalCallIs409BecauseTheStateAlreadyMoved() {
        Placed order = order("PAID");
        data(start(rec, order.id()), HttpStatus.OK);

        assertError(start(rec, order.id()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        data(ship(rec, order.id(), "GHN", "T"), HttpStatus.OK);
        assertError(ship(rec, order.id(), "GHN", "T2"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(row(order.id())).containsEntry("tracking_code", "T");
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void shipOrRedeliverWithoutCarrierOrTrackingCodeIs400BrBh11AndWritesNothing() {
        Placed processing = order("PROCESSING");
        Placed failed = order("DELIVERY_FAILED");
        List<Object> bad = List.of(Map.of(), Map.of("carrier", "GHN"), Map.of("trackingCode", "T"),
                Map.of("carrier", "  ", "trackingCode", "T"), Map.of("carrier", "GHN", "trackingCode", " "));
        for (Object body : bad) {
            assertRule(post(rec, processing.id(), "ship", body), "BR-BH-11");
            assertRule(post(rec, failed.id(), "redeliver", body), "BR-BH-11");
        }

        assertThat(status(processing.id())).isEqualTo("PROCESSING");
        assertThat(row(processing.id())).containsEntry("carrier", null).containsEntry("shipped_at", null);
        assertThat(row(failed.id())).containsEntry("redelivery_count", 0).containsEntry("tracking_code", "GHN1");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void tooLongOrUnreadableBodiesAre400AndWriteNothing() {
        Placed processing = order("PROCESSING");
        Placed shipped = order("SHIPPED");

        assertError(ship(rec, processing.id(), "x".repeat(101), "T"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(ship(rec, processing.id(), "GHN", "x".repeat(101)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(fail(rec, shipped.id(), "x".repeat(301)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, STAFF + "/" + processing.id() + "/ship", rec, ""), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(callRaw(HttpMethod.POST, STAFF + "/" + shipped.id() + "/fail-delivery", rec, ""),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");

        assertThat(status(processing.id())).isEqualTo("PROCESSING");
        assertThat(status(shipped.id())).isEqualTo("SHIPPED");
    }

    @Test
    void failDeliveryWithoutAReasonIs400AndKeepsTheOrderShipped() {
        Placed order = order("SHIPPED");

        assertError(post(rec, order.id(), "fail-delivery", Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(fail(rec, order.id(), "   "), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(status(order.id())).isEqualTo("SHIPPED");
        assertThat(row(order.id())).containsEntry("delivery_failure_reason", null);
        assertThat(outboxCount()).isZero();
    }

    // ================================================================ vai trò và phạm vi

    @Test
    void aReceptionistOfAnotherBranchIs403EverywhereAndNothingChanges() {
        Placed paid = order("PAID");
        Placed processing = order("PROCESSING");
        Placed shipped = order("SHIPPED");
        Placed failed = order("DELIVERY_FAILED");
        Map<String, Object> before = row(paid.id());

        assertError(call(HttpMethod.GET, STAFF + "/" + paid.id(), otherRec, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(start(otherRec, paid.id()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(ship(otherRec, processing.id(), "GHN", "T"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(deliver(otherRec, shipped.id()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(fail(otherRec, shipped.id(), "Lý do"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(redeliver(otherRec, failed.id(), "GHN", "T"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.GET, STAFF + "/" + paid.id(), otherManager, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");

        assertThat(row(paid.id())).isEqualTo(before);
        assertThat(status(processing.id())).isEqualTo("PROCESSING");
        assertThat(status(shipped.id())).isEqualTo("SHIPPED");
        assertThat(status(failed.id())).isEqualTo("DELIVERY_FAILED");
    }

    @Test
    void aBranchManagerMayViewButNotActAndOthersAreRefusedByRole() {
        Placed paid = order("PAID");
        Placed processing = order("PROCESSING");
        Placed shipped = order("SHIPPED");
        Placed failed = order("DELIVERY_FAILED");

        assertThat(call(HttpMethod.GET, STAFF + "/" + paid.id(), branchManager, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, STAFF, branchManager, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String token : List.of(branchManager, a.token(), superManager)) {
            assertError(start(token, paid.id()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(ship(token, processing.id(), "GHN", "T"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(deliver(token, shipped.id()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(fail(token, shipped.id(), "Lý do"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(redeliver(token, failed.id(), "GHN", "T"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String token : List.of(a.token(), superManager)) {
            assertError(call(HttpMethod.GET, STAFF, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, STAFF + "/" + paid.id(), token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(call(HttpMethod.GET, STAFF, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(start(null, paid.id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(status(paid.id())).isEqualTo("PAID");
        assertThat(status(processing.id())).isEqualTo("PROCESSING");
        assertThat(status(shipped.id())).isEqualTo("SHIPPED");
        assertThat(status(failed.id())).isEqualTo("DELIVERY_FAILED");
    }

    @Test
    void aMissingOrderIs404ForEveryEndpoint() {
        assertError(call(HttpMethod.GET, STAFF + "/-1", rec, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(start(rec, -1), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(ship(rec, -1, "GHN", "T"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(deliver(rec, -1), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(fail(rec, -1, "Lý do"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(redeliver(rec, -1, "GHN", "T"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ================================================================ xem

    @Test
    @SuppressWarnings("unchecked")
    void listDefaultsToTheToDoStatusesOfTheCallersBranchOldestFirstAndPages() {
        Placed paid = order("PAID");
        clock.set(START.plusSeconds(60));
        Placed processing = order("PROCESSING");
        clock.set(START.plusSeconds(120));
        Placed shipped = order("SHIPPED");
        clock.set(START.plusSeconds(180));
        Placed failed = order("DELIVERY_FAILED");
        clock.set(START.plusSeconds(240));
        Placed pending = order("PENDING_PAYMENT");
        Placed foreign = order(otherBranch, "PAID");

        Map<String, Object> all = data(call(HttpMethod.GET, STAFF, rec, null), HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) all.get("content");
        assertThat(content).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(paid.id(), processing.id(), failed.id())
                .doesNotContain(shipped.id(), pending.id(), foreign.id());
        assertThat(all).containsEntry("totalElements", 3).containsEntry("page", 0).containsEntry("size", 20);
        assertThat(content.get(0)).containsEntry("salesOrderId", (int) paid.orderId()).containsEntry("code", paid.code())
                .containsEntry("status", "PAID").containsEntry("itemCount", 1).containsEntry("total", 230_000);

        Map<String, Object> pageOne = data(call(HttpMethod.GET, STAFF + "?page=1&size=2", branchManager, null),
                HttpStatus.OK);
        assertThat((List<Map<String, Object>>) pageOne.get("content")).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(failed.id());

        Map<String, Object> onlyShipped = data(call(HttpMethod.GET, STAFF + "?status=SHIPPED", rec, null), HttpStatus.OK);
        assertThat((List<Map<String, Object>>) onlyShipped.get("content")).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(shipped.id());

        Map<String, Object> other = data(call(HttpMethod.GET, STAFF, otherRec, null), HttpStatus.OK);
        assertThat((List<Map<String, Object>>) other.get("content")).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(foreign.id());
        assertError(call(HttpMethod.GET, STAFF + "?status=BOGUS", rec, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailShowsTheOrderWithItsLinesAndTheTransitionResponsesAreTheSameShape() {
        Placed order = order("PAID");

        Map<String, Object> detail = data(call(HttpMethod.GET, STAFF + "/" + order.id(), rec, null), HttpStatus.OK);
        Map<String, Object> started = data(start(rec, order.id()), HttpStatus.OK);

        assertThat(detail).containsEntry("status", "PAID").containsEntry("receiverName", "Người nhận")
                .containsEntry("subtotal", 200_000).containsEntry("shippingFee", 30_000).containsEntry("total", 230_000)
                .containsEntry("branchId", (int) branch);
        assertThat((List<Map<String, Object>>) detail.get("lines")).hasSize(1);
        assertThat(started.keySet()).isEqualTo(detail.keySet());
    }

    // ================================================================ giao dịch

    @Test
    void aNotificationFailureRollsTheWholeTransitionBack() {
        Placed order = order("PROCESSING");

        whileInsertsFail("notification_outbox", "NEW.template_code = 'ONLINE_ORDER_SHIPPED'", () -> {
            ResponseEntity<Map<String, Object>> response = ship(rec, order.id(), "GHN", "T");
            assertThat(response.getStatusCode().is5xxServerError()).as("%s", response.getBody()).isTrue();
        });

        assertThat(status(order.id())).isEqualTo("PROCESSING");
        assertThat(row(order.id())).containsEntry("carrier", null).containsEntry("tracking_code", null)
                .containsEntry("shipped_at", null);
        assertThat(outboxCount()).isZero();
        data(ship(rec, order.id(), "GHN", "T"), HttpStatus.OK);
    }

    // ================================================================ đua với khóa online_orders

    private ResponseEntity<Map<String, Object>> whileHeld(long onlineOrderId, String lockClause, Runnable beforeCommit,
            Supplier<ResponseEntity<Map<String, Object>>> action) throws Exception {
        return whileHeld(onlineOrderId, lockClause, beforeCommit, List.of(action)).get(0);
    }

    /**
     * Một transaction khác giữ khóa dòng {@code online_orders} ({@code lockClause}); mỗi {@code action} chạy ở thread
     * khác, phải đứng chờ khóa ({@code pg_stat_activity}) rồi mới chạy khi khóa được nhả.
     */
    private List<ResponseEntity<Map<String, Object>>> whileHeld(long onlineOrderId, String lockClause,
            Runnable beforeCommit, List<Supplier<ResponseEntity<Map<String, Object>>>> actions) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM online_orders WHERE id = ? " + lockClause, Long.class,
                        onlineOrderId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                beforeCommit.run();
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (Supplier<ResponseEntity<Map<String, Object>>> action : actions) {
                futures.add(pool.submit(action::get));
                awaitLockWait(futures.size(), "from online_orders", "for no key update");
            }
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                assertThat(future.isDone()).as("thao tác phải chờ khóa online_orders, không được chạy xuyên khóa")
                        .isFalse();
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aTransactionHoldingTheOrderMakesStartProcessingWaitThenSeeTheNewStateAnd409() throws Exception {
        Placed order = order("PAID");

        ResponseEntity<Map<String, Object>> response = whileHeld(order.id(), "FOR NO KEY UPDATE",
                () -> jdbc.update("UPDATE online_orders SET status = 'PROCESSING' WHERE id = ?", order.id()),
                () -> start(rec, order.id()));

        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(status(order.id())).isEqualTo("PROCESSING");
    }

    @Test
    void aSharedLockOnTheOrderStillBlocksEveryTransitionBecauseTheLockMustBeNoKeyUpdate() throws Exception {
        Placed paid = order("PAID");
        Placed processing = order("PROCESSING");
        Placed shipped = order("SHIPPED");
        Placed failed = order("DELIVERY_FAILED");

        ResponseEntity<Map<String, Object>> started = whileHeld(paid.id(), "FOR SHARE", () -> {
        }, () -> start(rec, paid.id()));
        ResponseEntity<Map<String, Object>> shippedNow = whileHeld(processing.id(), "FOR SHARE", () -> {
        }, () -> ship(rec, processing.id(), "GHN", "T"));
        ResponseEntity<Map<String, Object>> delivered = whileHeld(shipped.id(), "FOR SHARE", () -> {
        }, () -> deliver(rec, shipped.id()));
        ResponseEntity<Map<String, Object>> redelivered = whileHeld(failed.id(), "FOR SHARE", () -> {
        }, () -> redeliver(rec, failed.id(), "GHN", "T2"));

        for (ResponseEntity<Map<String, Object>> response : List.of(started, shippedNow, delivered, redelivered)) {
            assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        }
        assertThat(status(paid.id())).isEqualTo("PROCESSING");
        assertThat(status(processing.id())).isEqualTo("SHIPPED");
        assertThat(status(shipped.id())).isEqualTo("DELIVERED");
        assertThat(status(failed.id())).isEqualTo("SHIPPED");
    }

    @Test
    void aSharedLockOnTheOrderStillBlocksFailDelivery() throws Exception {
        Placed shipped = order("SHIPPED");

        ResponseEntity<Map<String, Object>> response = whileHeld(shipped.id(), "FOR SHARE", () -> {
        }, () -> fail(rec, shipped.id(), "Lý do"));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(status(shipped.id())).isEqualTo("DELIVERY_FAILED");
    }

    @Test
    void twoConcurrentStartProcessingCallsExactlyOneWinsAndTheOtherIs409() throws Exception {
        Placed order = order("PAID");

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(order.id(), "FOR NO KEY UPDATE", () -> {
        }, List.of(() -> start(rec, order.id()), () -> start(rec, order.id())));

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsExactlyInAnyOrder(HttpStatus.OK,
                HttpStatus.CONFLICT);
        assertThat(status(order.id())).isEqualTo("PROCESSING");
    }

    @Test
    void twoConcurrentFailDeliveryCallsSendOneNotificationPair() throws Exception {
        Placed order = order("SHIPPED");

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(order.id(), "FOR NO KEY UPDATE", () -> {
        }, List.of(() -> fail(rec, order.id(), "Một"), () -> fail(rec, order.id(), "Hai")));

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsExactlyInAnyOrder(HttpStatus.OK,
                HttpStatus.CONFLICT);
        assertThat(outbox("ONLINE_ORDER_DELIVERY_FAILED", order.code())).hasSize(2);
    }
}
