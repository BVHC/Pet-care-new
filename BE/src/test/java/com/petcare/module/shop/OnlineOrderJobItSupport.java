package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.sales.CashierItSupport;
import com.petcare.support.MutableClock;

/**
 * Dữ liệu dùng chung cho IT của ST10 / ST12 (docs/adr/0146): chi nhánh, sản phẩm, hai khách online, đơn đặt qua HTTP,
 * ép trạng thái bằng SQL, giữ khóa {@code online_orders} ở transaction khác và trigger làm hỏng đúng một đơn. Lớp con
 * khai báo {@code MutableClock} {@code @Primary} của era <b>cũ</b> (job quét không có cận dưới).
 */
abstract class OnlineOrderJobItSupport extends CashierItSupport {

    @Autowired
    protected MutableClock clock;

    protected long branch;
    protected long manager;
    protected long goods;
    protected Shopper a;
    protected Shopper b;

    /** Khách online (tài khoản + hồ sơ + địa chỉ) và token. */
    protected record Shopper(long accountId, long customerId, long addressId, String token) {
    }

    /** Đơn vừa đặt: id đơn online, id Order, mã. */
    protected record Placed(long id, long orderId, String code) {
    }

    protected abstract Instant start();

    @BeforeEach
    void setUpShop() {
        clock.set(start());
        branch = newBranch();
        manager = staff("BRANCH_MANAGER", branch);
        goods = newPricedProduct("GOODS", false, 100_000);
        a = shopper("An");
        b = shopper("Bình");
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 30000, free_threshold = 0 WHERE id = 1");
        defaultLot(branch, goods, 100, manager);
    }

    @AfterEach
    void cleanOnline() {
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 0, free_threshold = 0 WHERE id = 1");
        dropFailTrigger();
        String as = a.accountId() + ", " + b.accountId();
        String cs = a.customerId() + ", " + b.customerId();
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (" + as + ") "
                + "OR recipient_email LIKE 'jobs-it-%'");
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
                RETURNING id""", Long.class, "jobs-it-" + UUID.randomUUID() + "@example.com");
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE') RETURNING id""",
                Long.class, account, "Khách đơn " + name);
        long address = jdbc.queryForObject("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, ward, province, is_default)
                VALUES (?, ?, '0901234567', '12 Lê Lợi', 'P. Bến Nghé', 'TP.HCM', false) RETURNING id""", Long.class,
                customer, "Nhận " + name);
        return new Shopper(account, customer, address, token(account));
    }

    /** Đặt đơn tại chi nhánh của test ở giờ hiện tại của đồng hồ; hạn thanh toán = giờ đó + [CFG] payment_window_hours. */
    protected Placed place(Shopper s, int quantity) {
        assertThat(call(HttpMethod.PUT, "/api/me/cart/items/" + goods, s.token(), Map.of("quantity", quantity))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> order = data(call(HttpMethod.POST, "/api/me/online-orders", s.token(),
                Map.of("branchId", branch, "addressId", s.addressId())), HttpStatus.CREATED);
        return new Placed(number(order.get("onlineOrderId")), number(order.get("salesOrderId")),
                (String) order.get("code"));
    }

    protected String onlineStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM online_orders WHERE id = ?", String.class, id);
    }

    protected String salesStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected String reservationStatus(long onlineOrderId) {
        return jdbc.queryForObject("SELECT string_agg(DISTINCT status, ',') FROM stock_reservations "
                + "WHERE online_order_id = ?", String.class, onlineOrderId);
    }

    protected long notices(long accountId, String templatePrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? "
                + "AND template_code LIKE ?", Long.class, accountId, templatePrefix + "%");
    }

    protected long emails(String email, String templatePrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ? "
                + "AND template_code LIKE ?", Long.class, email, templatePrefix + "%");
    }

    protected String accountEmail(Shopper s) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, s.accountId());
    }

    protected long transaction(long onlineOrderId, String status, Instant at) {
        boolean succeeded = "SUCCEEDED".equals(status);
        return jdbc.queryForObject("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status, expires_at, paid_at)
                VALUES (?, ?, 'VNPAY', 230000, ?, ?, ?) RETURNING id""", Long.class,
                "TXN-IT-" + UUID.randomUUID().toString().substring(0, 12), onlineOrderId, status,
                Timestamp.from(at.plusSeconds(3600)), succeeded ? Timestamp.from(at) : null);
    }

    protected String transactionStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class, id);
    }

    // ================================================================ khóa và lỗi

    /**
     * Một transaction khác giữ khóa dòng {@code online_orders} ({@code FOR NO KEY UPDATE}, như callback thanh toán);
     * {@code action} chạy ở thread khác và phải xong trong 10 giây <b>mà không chờ khóa</b> (SKIP LOCKED); {@code duringHold}
     * chạy trong transaction giữ khóa, trước khi commit.
     */
    protected void whileHeld(long onlineOrderId, Runnable duringHold, Runnable action) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM online_orders WHERE id = ? FOR NO KEY UPDATE", Long.class,
                        onlineOrderId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                duringHold.run();
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> run = pool.submit(action);
            try {
                run.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("job phải bỏ qua đơn đang bị khóa (SKIP LOCKED), không được chờ khóa", e);
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Trigger làm UPDATE của đúng một đơn online lỗi (bản ghi hỏng không được chặn cả lô). */
    protected void failUpdatesOf(long onlineOrderId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_online_order() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'it: ghi đơn online lỗi'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER it_fail_online_order BEFORE UPDATE ON online_orders FOR EACH ROW "
                + "WHEN (NEW.id = " + onlineOrderId + ") EXECUTE FUNCTION it_fail_online_order()");
    }

    protected void dropFailTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_online_order ON online_orders");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_online_order()");
    }
}
