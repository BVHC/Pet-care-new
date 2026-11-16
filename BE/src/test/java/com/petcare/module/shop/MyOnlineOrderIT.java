package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.petcare.module.shop.api.OnlineOrderQueryApi;
import com.petcare.support.MutableClock;

/**
 * Đơn online của tôi (UC64, BR-BH-09, BR-BH-10; docs/adr/0145) qua HTTP trên Postgres 17 thật: danh sách (phân trang, thứ
 * tự, lọc, chỉ đơn của mình), chi tiết, hủy đơn chưa thanh toán (nhả giữ hàng, hủy giao dịch PENDING, Order CANCELLED),
 * xác nhận đã nhận, và các cuộc đua với khóa {@code online_orders} (một bên giữ khóa ở transaction khác như callback
 * thanh toán). Era <b>năm 2050</b> (không job nào quét tới); chi nhánh, sản phẩm, khách riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MyOnlineOrderIT.TestBeans.class})
class MyOnlineOrderIT extends CashierItSupport {

    private static final String BASE = "/api/me/online-orders";
    private static final Instant START = Instant.parse("2050-06-15T03:00:00Z");

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
    @Autowired
    private OnlineOrderQueryApi query;
    @Autowired
    private com.petcare.module.payment.api.PaymentTransactionApi payments;

    private long branch;
    private long manager;
    private long goods;
    private long cheap;
    private Shopper a;
    private Shopper b;

    /** Khách online (tài khoản + hồ sơ + địa chỉ) và token. */
    private record Shopper(long accountId, long customerId, long addressId, String token) {
    }

    /** Đơn vừa đặt: id đơn online, id Order, mã. */
    private record Placed(long id, long orderId, String code) {
    }

    @BeforeEach
    void setUp() {
        clock.set(START);
        branch = newBranch();
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
        String as = a.accountId() + ", " + b.accountId();
        String cs = a.customerId() + ", " + b.customerId();
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (" + as + ") "
                + "OR recipient_email LIKE 'myorders-it-%'");
        jdbc.update("DELETE FROM payment_transactions WHERE online_order_id IN "
                + "(SELECT id FROM online_orders WHERE branch_id = " + branch + ")");
        jdbc.update("DELETE FROM stock_reservations WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM online_orders WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM orders WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id IN (" + cs + "))");
        jdbc.update("DELETE FROM carts WHERE customer_id IN (" + cs + ")");
        jdbc.update("DELETE FROM addresses WHERE customer_id IN (" + cs + ")");
        jdbc.update("DELETE FROM customers WHERE id IN (" + cs + ")");
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id IN ("
                + as + "))");
        jdbc.update("DELETE FROM sessions WHERE account_id IN (" + as + ")");
        jdbc.update("DELETE FROM accounts WHERE id IN (" + as + ")");
    }

    // ================================================================ dữ liệu

    private Shopper shopper(String name) {
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'x', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, "myorders-it-" + UUID.randomUUID() + "@example.com");
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE') RETURNING id""",
                Long.class, account, "Khách đơn " + name);
        long address = jdbc.queryForObject("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, ward, province, is_default)
                VALUES (?, ?, '0901234567', '12 Lê Lợi', 'P. Bến Nghé', 'TP.HCM', false) RETURNING id""", Long.class,
                customer, "Nhận " + name);
        return new Shopper(account, customer, address, token(account));
    }

    /** Bỏ hàng vào giỏ rồi đặt đơn tại chi nhánh của test. */
    private Placed placeOrder(Shopper s, long productId, int quantity) {
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + productId, s.token(), Map.of("quantity", quantity))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> order = data(call(HttpMethod.POST, BASE, s.token(),
                Map.of("branchId", branch, "addressId", s.addressId())), HttpStatus.CREATED);
        return new Placed(number(order.get("onlineOrderId")), number(order.get("salesOrderId")),
                (String) order.get("code"));
    }

    private Placed placeDefault(Shopper s) {
        defaultLot(branch, goods, 10, manager);
        return placeOrder(s, goods, 2);
    }

    private ResponseEntity<Map<String, Object>> cancel(Shopper s, long id, String reason) {
        return call(HttpMethod.POST, BASE + "/" + id + "/cancel", s.token(), Map.of("reason", reason));
    }

    private ResponseEntity<Map<String, Object>> confirm(Shopper s, long id) {
        return call(HttpMethod.POST, BASE + "/" + id + "/confirm-received", s.token(), null);
    }

    private long held(long productId) {
        return jdbc.queryForObject("SELECT COALESCE(sum(quantity), 0) FROM stock_reservations "
                + "WHERE branch_id = ? AND product_id = ? AND status = 'HELD'", Long.class, branch, productId);
    }

    private String reservationStatus(long onlineOrderId) {
        return jdbc.queryForObject("SELECT string_agg(DISTINCT status, ',') FROM stock_reservations "
                + "WHERE online_order_id = ?", String.class, onlineOrderId);
    }

    private String onlineStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM online_orders WHERE id = ?", String.class, id);
    }

    private long transaction(long onlineOrderId, String status) {
        boolean succeeded = "SUCCEEDED".equals(status);
        return jdbc.queryForObject("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status, expires_at, paid_at)
                VALUES (?, ?, 'VNPAY', 230000, ?, ?, ?) RETURNING id""", Long.class,
                "TXN-IT-" + UUID.randomUUID().toString().substring(0, 12), onlineOrderId, status,
                Timestamp.from(START.plusSeconds(3600)), succeeded ? Timestamp.from(START) : null);
    }

    /** Đưa đơn (và Order) tới một trạng thái bằng SQL, thỏa mọi CHECK của {@code online_orders}. */
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

    // ================================================================ hủy: đường chính

    @Test
    void cancelPendingPaymentReleasesTheReservationCancelsThePendingTransactionAndTheOrder() {
        Placed order = placeDefault(a);
        Placed other = placeOrder(b, goods, 3);
        transaction(order.id(), "PENDING");
        assertThat(held(goods)).isEqualTo(5);
        clock.set(START.plusSeconds(600));

        ResponseEntity<Map<String, Object>> response = cancel(a, order.id(), "  Khách đổi ý ");

        Map<String, Object> body = data(response, HttpStatus.OK);
        assertThat(body).containsEntry("status", "CANCELLED").containsEntry("cancelReason", "Khách đổi ý")
                .containsEntry("onlineOrderId", (int) order.id()).containsEntry("subtotal", 200_000)
                .containsEntry("total", 230_000);
        assertThat(body.get("cancelledAt")).isEqualTo("2050-06-15T03:10:00Z");
        assertThat(body.get("refundStatus")).isNull();
        Map<String, Object> online = jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?", order.id());
        assertThat(online).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", a.accountId())
                .containsEntry("cancel_reason", "Khách đổi ý");
        Map<String, Object> sales = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.orderId());
        assertThat(sales).containsEntry("status", "CANCELLED").containsEntry("cancel_type", "ONLINE_CUSTOMER")
                .containsEntry("cancelled_by", a.accountId()).containsEntry("cancel_reason", "Khách đổi ý");
        assertThat(((Timestamp) sales.get("cancelled_at")).toInstant()).isEqualTo(START.plusSeconds(600));
        assertThat(reservationStatus(order.id())).isEqualTo("RELEASED");
        assertThat(reservationStatus(other.id())).as("đơn khác không bị nhả").isEqualTo("HELD");
        assertThat(held(goods)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE online_order_id = ?",
                String.class, order.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? "
                + "AND template_code LIKE 'ONLINE_ORDER_CANCELLED%'", Long.class, a.accountId()))
                .as("khách tự hủy không có thông báo").isZero();
    }

    @Test
    void theReleasedUnitsAreAvailableAgainForTheNextCustomer() {
        long lot = defaultLot(branch, goods, 5, manager);
        Placed first = placeOrder(a, goods, 5);
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + goods, b.token(), Map.of("quantity", 1))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, BASE, b.token(), Map.of("branchId", branch, "addressId", b.addressId()))
                .getStatusCode()).as("hết hàng vì phần giữ").isEqualTo(HttpStatus.BAD_REQUEST);

        data(cancel(a, first.id(), "Đổi ý"), HttpStatus.OK);

        data(call(HttpMethod.POST, BASE, b.token(), Map.of("branchId", branch, "addressId", b.addressId())),
                HttpStatus.CREATED);
        assertThat(lotQuantity(lot)).as("giữ và nhả không đổi tồn lô").isEqualTo(5);
    }

    @Test
    void cancelTwiceIs409AndTheSecondChangesNothing() {
        Placed order = placeDefault(a);
        data(cancel(a, order.id(), "Lần một"), HttpStatus.OK);

        assertError(cancel(a, order.id(), "Lần hai"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM online_orders WHERE id = ?", String.class,
                order.id())).isEqualTo("Lần một");
    }

    @Test
    void cancelOfAPaidOrAnyOtherNonPendingStateIs409AndKeepsTheReservationAndTheOrder() {
        defaultLot(branch, cheap, 50, manager);
        for (String status : List.of("PAID", "PROCESSING", "SHIPPED", "DELIVERY_FAILED", "DELIVERED", "COMPLETED",
                "CANCELLED")) {
            Placed order = placeOrder(a, cheap, 1);
            force(order.id(), status);

            assertError(cancel(a, order.id(), "Đổi ý"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

            assertThat(onlineStatus(order.id())).as(status).isEqualTo(status);
            assertThat(reservationStatus(order.id())).as(status).isEqualTo("HELD");
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                    .as(status).isEqualTo("PENDING");
        }
    }

    @Test
    void cancelWithASucceededTransactionIs409AndWritesNothing() {
        Placed order = placeDefault(a);
        transaction(order.id(), "SUCCEEDED");
        long pending = transaction(order.id(), "PENDING");

        assertError(cancel(a, order.id(), "Đổi ý"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(onlineStatus(order.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(reservationStatus(order.id())).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class, pending))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo("PENDING");
    }

    @Test
    void aMissingBlankOrTooLongReasonIs400AndWritesNothing() {
        Placed order = placeDefault(a);
        String path = BASE + "/" + order.id() + "/cancel";

        assertError(call(HttpMethod.POST, path, a.token(), Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, path, a.token(), Map.of("reason", "   ")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, path, a.token(), Map.of("reason", "x".repeat(301))), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, path, a.token(), ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");

        assertThat(onlineStatus(order.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(reservationStatus(order.id())).isEqualTo("HELD");
    }

    @Test
    void someoneElsesOrAMissingOrderIs404ForGetCancelAndConfirmAndNothingChanges() {
        Placed order = placeDefault(a);
        force(order.id(), "DELIVERED");

        assertError(call(HttpMethod.GET, BASE + "/" + order.id(), b.token(), null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(cancel(b, order.id(), "Đổi ý"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(confirm(b, order.id()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.GET, BASE + "/-1", a.token(), null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(cancel(a, -1, "Đổi ý"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(confirm(a, -1), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(onlineStatus(order.id())).isEqualTo("DELIVERED");
    }

    // ================================================================ xác nhận đã nhận

    @Test
    void confirmReceivedMovesDeliveredToCompletedAndStampsCompletedAt() {
        Placed order = placeDefault(a);
        force(order.id(), "DELIVERED");
        clock.set(START.plusSeconds(3600));

        Map<String, Object> body = data(confirm(a, order.id()), HttpStatus.OK);

        assertThat(body).containsEntry("status", "COMPLETED");
        assertThat(body.get("completedAt")).isEqualTo("2050-06-15T04:00:00Z");
        assertThat(body.get("deliveredAt")).isNotNull();
        assertThat(onlineStatus(order.id())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM online_orders WHERE id = ?", Timestamp.class,
                order.id()).toInstant()).isEqualTo(START.plusSeconds(3600));
        assertError(confirm(a, order.id()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void confirmReceivedInAnyOtherStateIs409AndChangesNothing() {
        defaultLot(branch, cheap, 50, manager);
        for (String status : List.of("PENDING_PAYMENT", "PAID", "PROCESSING", "SHIPPED", "DELIVERY_FAILED",
                "COMPLETED", "CANCELLED")) {
            Placed order = placeOrder(a, cheap, 1);
            force(order.id(), status);

            assertError(confirm(a, order.id()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

            assertThat(onlineStatus(order.id())).as(status).isEqualTo(status);
        }
    }

    // ================================================================ xem

    @Test
    @SuppressWarnings("unchecked")
    void detailShowsTheSnapshotLinesTheDisplayUnitAndNoRefund() {
        defaultLot(branch, goods, 10, manager);
        defaultLot(branch, cheap, 10, manager);
        placeOrder(a, goods, 1);
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + cheap, a.token(), Map.of("quantity", 3))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + goods, a.token(), Map.of("quantity", 2))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> placed = data(call(HttpMethod.POST, BASE, a.token(),
                Map.of("branchId", branch, "addressId", a.addressId())), HttpStatus.CREATED);
        jdbc.update("UPDATE products SET price = 999000 WHERE id = ?", goods);

        Map<String, Object> body = data(call(HttpMethod.GET, BASE + "/" + placed.get("onlineOrderId"), a.token(), null),
                HttpStatus.OK);

        assertThat(body).containsEntry("status", "PENDING_PAYMENT").containsEntry("branchId", (int) branch)
                .containsEntry("receiverName", "Nhận An").containsEntry("shipAddress", "12 Lê Lợi, P. Bến Nghé, TP.HCM")
                .containsEntry("subtotal", 350_000).containsEntry("shippingFee", 30_000)
                .containsEntry("total", 380_000).containsEntry("redeliveryCount", 0);
        assertThat(body.get("refundStatus")).isNull();
        assertThat((String) body.get("branchName")).startsWith("KhoIT chi nhánh");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat((String) line.get("unit")).isNotNull());
        assertThat(lines).anySatisfy(line -> assertThat(line).containsEntry("productId", (int) goods)
                .containsEntry("unitPrice", 100_000).containsEntry("quantity", 2).containsEntry("lineTotal", 200_000));
        assertThat(lines).anySatisfy(line -> assertThat(line).containsEntry("productId", (int) cheap)
                .containsEntry("unitPrice", 50_000).containsEntry("quantity", 3).containsEntry("lineTotal", 150_000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIsNewestFirstWithIdAsTieBreakerPagedFilterableAndOnlyMine() {
        defaultLot(branch, goods, 50, manager);
        Placed first = placeOrder(a, goods, 1);
        clock.set(START.plusSeconds(60));
        Placed second = placeOrder(a, goods, 2);
        Placed third = placeOrder(a, goods, 3);   // cùng thời điểm với đơn 2: hơn nhau bằng id giảm dần
        clock.set(START.plusSeconds(120));
        Placed foreign = placeOrder(b, goods, 4);
        data(cancel(a, first.id(), "Đổi ý"), HttpStatus.OK);

        Map<String, Object> all = data(call(HttpMethod.GET, BASE, a.token(), null), HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) all.get("content");
        assertThat(content).extracting(row -> number(row.get("onlineOrderId")))
                .containsExactly(third.id(), second.id(), first.id()).doesNotContain(foreign.id());
        assertThat(all).containsEntry("page", 0).containsEntry("size", 20).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 1);
        Map<String, Object> row = content.get(0);
        assertThat(row).containsEntry("salesOrderId", (int) third.orderId()).containsEntry("code", third.code())
                .containsEntry("status", "PENDING_PAYMENT").containsEntry("itemCount", 1)
                .containsEntry("total", 330_000);
        assertThat((String) row.get("branchName")).startsWith("KhoIT chi nhánh");
        assertThat(row.get("createdAt")).isEqualTo("2050-06-15T03:01:00Z");

        Map<String, Object> pageOne = data(call(HttpMethod.GET, BASE + "?page=1&size=2", a.token(), null),
                HttpStatus.OK);
        assertThat(((List<Map<String, Object>>) pageOne.get("content"))).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(first.id());
        assertThat(pageOne).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 2);

        Map<String, Object> cancelled = data(call(HttpMethod.GET, BASE + "?status=CANCELLED", a.token(), null),
                HttpStatus.OK);
        assertThat(((List<Map<String, Object>>) cancelled.get("content"))).extracting(r -> number(r.get("onlineOrderId")))
                .containsExactly(first.id());
        assertThat(cancelled).containsEntry("totalElements", 1);

        Map<String, Object> none = data(call(HttpMethod.GET, BASE + "?status=SHIPPED", a.token(), null), HttpStatus.OK);
        assertThat((List<?>) none.get("content")).isEmpty();
        assertThat(none).containsEntry("totalElements", 0);

        Map<String, Object> clamped = data(call(HttpMethod.GET, BASE + "?size=1000", a.token(), null), HttpStatus.OK);
        assertThat(clamped).containsEntry("size", 100);
        assertError(call(HttpMethod.GET, BASE + "?status=NOPE", a.token(), null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void anEmptyListIsAnEmptyPage() {
        Map<String, Object> page = data(call(HttpMethod.GET, BASE, b.token(), null), HttpStatus.OK);

        assertThat((List<?>) page.get("content")).isEmpty();
        assertThat(page).containsEntry("totalElements", 0);
    }

    @Test
    void staffIs403AndAnonymousIs401OnAllFourOperations() {
        Placed order = placeDefault(a);
        String staff = token(manager);

        for (String token : new String[] {staff, null}) {
            HttpStatus expected = token == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            assertThat(call(HttpMethod.GET, BASE, token, null).getStatusCode()).isEqualTo(expected);
            assertThat(call(HttpMethod.GET, BASE + "/" + order.id(), token, null).getStatusCode()).isEqualTo(expected);
            assertThat(call(HttpMethod.POST, BASE + "/" + order.id() + "/cancel", token, Map.of("reason", "x"))
                    .getStatusCode()).isEqualTo(expected);
            assertThat(call(HttpMethod.POST, BASE + "/" + order.id() + "/confirm-received", token, null)
                    .getStatusCode()).isEqualTo(expected);
        }
        assertThat(onlineStatus(order.id())).isEqualTo("PENDING_PAYMENT");
    }

    // ================================================================ OnlineOrderQueryApi

    @Test
    void findDeliveredAtByOrderIdIsEmptyUntilDeliveredAndForNonOnlineOrders() {
        Placed order = placeDefault(a);

        assertThat(deliveredAt(order.orderId())).isEmpty();
        force(order.id(), "DELIVERED");
        assertThat(deliveredAt(order.orderId())).contains(START);
        force(order.id(), "COMPLETED");
        assertThat(deliveredAt(order.orderId())).contains(START);
        assertThat(deliveredAt(-1L)).isEmpty();
    }

    private Optional<Instant> deliveredAt(long orderId) {
        return new TransactionTemplate(transactionManager).execute(s -> query.findDeliveredAtByOrderId(orderId));
    }

    // ================================================================ đua với khóa online_orders

    /**
     * Một transaction khác giữ khóa dòng {@code online_orders} ({@code lockClause}) như callback thanh toán; {@code action}
     * chạy ở thread khác, phải đứng chờ khóa ({@code pg_stat_activity}) rồi mới chạy khi khóa được nhả. {@code beforeCommit}
     * chạy trong transaction giữ khóa, trước khi commit.
     */
    private ResponseEntity<Map<String, Object>> whileHeld(long onlineOrderId, String lockClause, Runnable beforeCommit,
            Supplier<ResponseEntity<Map<String, Object>>> action) throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(onlineOrderId, lockClause, beforeCommit,
                List.of(action));
        return results.get(0);
    }

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
                // đợi thao tác này vào hàng chờ khóa dòng online_orders rồi mới thêm thao tác kế tiếp
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
    void aPaymentLockOnTheOnlineOrderMakesCancelWaitThenSeePaidAndAnswer409() throws Exception {
        Placed order = placeDefault(a);

        ResponseEntity<Map<String, Object>> response = whileHeld(order.id(), "FOR NO KEY UPDATE", () -> {
            // thanh toán thành công xong rồi mới nhả khóa: đơn và Order sang PAID
            jdbc.update("UPDATE online_orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                    order.id());
            jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                    order.orderId());
        }, () -> cancel(a, order.id(), "Đổi ý"));

        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(onlineStatus(order.id())).isEqualTo("PAID");
        assertThat(reservationStatus(order.id())).as("không nhả phần giữ của đơn đã thanh toán").isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo("PAID");
    }

    @Test
    void aSharedLockOnTheOnlineOrderStillBlocksCancelBecauseTheLockMustBeNoKeyUpdate() throws Exception {
        Placed order = placeDefault(a);

        ResponseEntity<Map<String, Object>> response = whileHeld(order.id(), "FOR SHARE", () -> {
        }, () -> cancel(a, order.id(), "Đổi ý"));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(onlineStatus(order.id())).isEqualTo("CANCELLED");
    }

    @Test
    void aSharedLockOnTheOnlineOrderStillBlocksConfirmReceived() throws Exception {
        Placed order = placeDefault(a);
        force(order.id(), "DELIVERED");

        ResponseEntity<Map<String, Object>> response = whileHeld(order.id(), "FOR SHARE", () -> {
        }, () -> confirm(a, order.id()));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(onlineStatus(order.id())).isEqualTo("COMPLETED");
    }

    @Test
    void twoConcurrentCancelsOfTheSameOrderExactlyOneWinsAndTheOtherIs409() throws Exception {
        Placed order = placeDefault(a);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(order.id(), "FOR NO KEY UPDATE", () -> {
        }, List.of(() -> cancel(a, order.id(), "Một"), () -> cancel(a, order.id(), "Hai")));

        assertThat(results).extracting(r -> r.getStatusCode()).containsExactly(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM online_orders WHERE id = ?", String.class,
                order.id())).isEqualTo("Một");
        assertThat(reservationStatus(order.id())).isEqualTo("RELEASED");
    }

    // ================================================================ PaymentTransactionApi

    @Test
    void cancelPendingForOnlineOrderTouchesOnlyPendingTransactionsOfThatOrderAndIsIdempotent() {
        Placed mine = placeDefault(a);
        Placed other = placeOrder(b, goods, 1);
        long pending = transaction(mine.id(), "PENDING");
        long succeeded = transaction(mine.id(), "SUCCEEDED");
        long foreign = transaction(other.id(), "PENDING");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer first = tx.execute(s -> payments.cancelPendingForOnlineOrder(mine.id()));
        Integer second = tx.execute(s -> payments.cancelPendingForOnlineOrder(mine.id()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(transactionStatus(pending)).isEqualTo("CANCELLED");
        assertThat(transactionStatus(succeeded)).isEqualTo("SUCCEEDED");
        assertThat(transactionStatus(foreign)).isEqualTo("PENDING");
        Boolean mineSucceeded = tx.execute(s -> payments.hasSucceededForOnlineOrder(mine.id()));
        Boolean otherSucceeded = tx.execute(s -> payments.hasSucceededForOnlineOrder(other.id()));
        assertThat(mineSucceeded).isTrue();
        assertThat(otherSucceeded).isFalse();
    }

    @Test
    void paymentTransactionApiRefusesToRunWithoutACallerTransaction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> payments.cancelPendingForOnlineOrder(1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> payments.hasSucceededForOnlineOrder(1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private String transactionStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class, id);
    }
}
