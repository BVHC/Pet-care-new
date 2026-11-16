package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.AiReadFixtures;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;

/**
 * Giỏ hàng qua HTTP trên Postgres thật (shop-v1 {@code getMyCart}, {@code setCartItem}, {@code removeCartItem},
 * {@code clearMyCart}; UC61, BR-BH-07; docs/adr/0141). Hạn dùng của lô tính theo ngày thật (đồng hồ ứng dụng, không có
 * job quét theo đồng hồ) nên không cần era {@code MutableClock}. Sản phẩm, chi nhánh, lô, phần giữ do
 * {@link AiReadFixtures} tạo (id sản phẩm và chi nhánh thật, riêng mỗi test); giỏ do test này xóa trước khi fixture xóa sản phẩm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CartIT {

    private static final String TAG = "CartIT";
    private static final String CART = "/api/me/cart";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private java.time.Clock clock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport support;
    private AiReadFixtures fx;
    private TransactionTemplate tx;
    private long customerId;
    private String customer;
    private long otherCustomerId;
    private String otherCustomer;
    private long branch;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        support = new AppointmentItSupport(jdbc);
        fx = new AiReadFixtures(jdbc, transactionManager);
        long account = support.customerAccount("cart-it-" + java.util.UUID.randomUUID() + "@example.com", "ACTIVE");
        customerId = support.onlineCustomer(account, "Khách giỏ A");
        customer = token(account);
        long otherAccount = support.customerAccount("cart-it-" + java.util.UUID.randomUUID() + "@example.com", "ACTIVE");
        otherCustomerId = support.onlineCustomer(otherAccount, "Khách giỏ B");
        otherCustomer = token(otherAccount);
        branch = fx.branch("CartIT ", "ACTIVE", false);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id IN (?, ?))",
                customerId, otherCustomerId);
        jdbc.update("DELETE FROM carts WHERE customer_id IN (?, ?)", customerId, otherCustomerId);
        support.cleanUp();
        fx.cleanup();
    }

    // ================================================================ dữ liệu

    /** Sản phẩm GOODS không kê đơn đang kinh doanh, giá {@code price}, ảnh {@code image} (hoặc null), đơn vị "hộp". */
    private long goods(long price, String image) {
        long id = fx.product("GOODS", false, true);
        jdbc.update("UPDATE products SET price = ?, image_url = ?, unit = 'hộp' WHERE id = ?", price, image, id);
        return id;
    }

    private long goods(long price) {
        return goods(price, null);
    }

    // ================================================================ xem

    @Test
    void aCustomerWithoutACartSeesAnEmptyCartAndNothingIsWritten() {
        ResponseEntity<Map<String, Object>> response = call(customer, HttpMethod.GET, CART, null);

        Map<String, Object> cart = data(response);
        assertThat((List<?>) cart.get("items")).isEmpty();
        assertThat(((Number) cart.get("subtotal")).longValue()).isZero();
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void putAddsALineAndTheCartShowsCurrentPriceNameUnitImageAndSubtotal() {
        long a = goods(185_000, "https://cdn.example/a.jpg");
        long b = goods(40_000);
        fx.lot(branch, a, 10, null);
        fx.lot(branch, b, 10, null);

        data(put(customer, a, 2));
        Map<String, Object> cart = data(put(customer, b, 3));

        List<Map<String, Object>> items = items(cart);
        assertThat(items).extracting(i -> ((Number) i.get("productId")).longValue()).containsExactly(a, b);
        assertThat(items.get(0)).containsEntry("unit", "hộp").containsEntry("unitPrice", 185_000)
                .containsEntry("quantity", 2).containsEntry("imageUrl", "https://cdn.example/a.jpg")
                .containsEntry("available", true);
        assertThat(items.get(0).get("name")).isEqualTo(jdbc.queryForObject("SELECT name FROM products WHERE id = ?",
                String.class, a));
        assertThat(items.get(1)).containsEntry("imageUrl", null).containsEntry("quantity", 3);
        assertThat(((Number) cart.get("subtotal")).longValue()).isEqualTo(2 * 185_000 + 3 * 40_000);
        assertThat(data(call(customer, HttpMethod.GET, CART, null))).isEqualTo(cart);
    }

    @Test
    void theCartShowsTheCurrentCatalogPriceNotAFrozenOne() {
        long a = goods(100_000);
        data(put(customer, a, 2));

        jdbc.update("UPDATE products SET price = 120000 WHERE id = ?", a);

        Map<String, Object> cart = data(call(customer, HttpMethod.GET, CART, null));
        assertThat(items(cart).get(0)).containsEntry("unitPrice", 120_000);
        assertThat(((Number) cart.get("subtotal")).longValue()).isEqualTo(240_000);
    }

    @Test
    void aProductDeactivatedAfterBeingAddedIsHiddenOnGetWithoutAWriteThenPurgedByTheNextWrite() {
        long a = goods(100_000);
        long b = goods(50_000);
        data(put(customer, a, 2));
        data(put(customer, b, 1));
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", a);

        Map<String, Object> cart = data(call(customer, HttpMethod.GET, CART, null));

        assertThat(items(cart)).extracting(i -> ((Number) i.get("productId")).longValue()).containsExactly(b);
        assertThat(((Number) cart.get("subtotal")).longValue()).isEqualTo(50_000);
        assertThat(lines(customerId)).as("GET không xóa dòng ẩn").containsExactlyInAnyOrder(a, b);

        long c = goods(10_000);
        data(put(customer, c, 1));
        assertThat(lines(customerId)).as("ghi kế tiếp dọn dòng ẩn").containsExactlyInAnyOrder(b, c);
    }

    @Test
    void aProductTurnedIntoANonGoodsOrPrescriptionProductIsHiddenToo() {
        long drug = goods(1_000);
        long prescription = goods(1_000);
        long ok = goods(1_000);
        data(put(customer, drug, 1));
        data(put(customer, prescription, 1));
        data(put(customer, ok, 1));
        jdbc.update("UPDATE products SET product_type = 'DRUG', tracks_expiry = true WHERE id = ?", drug);
        jdbc.update("UPDATE products SET product_type = 'DRUG', is_prescription = true, tracks_expiry = true WHERE id = ?",
                prescription);

        Map<String, Object> cart = data(call(customer, HttpMethod.GET, CART, null));

        assertThat(items(cart)).extracting(i -> ((Number) i.get("productId")).longValue()).containsExactly(ok);
        assertThat(((Number) cart.get("subtotal")).longValue()).isEqualTo(1_000);
    }

    // ================================================================ thêm / đặt số lượng

    @Test
    void putIsAnIdempotentUpsertOfTheQuantity() {
        long a = goods(1_000);
        fx.lot(branch, a, 99, null);

        data(put(customer, a, 2));
        Map<String, Object> again = data(put(customer, a, 2));
        Map<String, Object> changed = data(put(customer, a, 7));

        assertThat(items(again)).hasSize(1);
        assertThat(items(changed)).hasSize(1);
        assertThat(items(changed).get(0)).containsEntry("quantity", 7);
        assertThat(count("SELECT count(*) FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id = ?)",
                customerId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isEqualTo(1);
    }

    @Test
    void anIdenticalPutStillWritesTheRow() {
        long a = goods(1_000);
        data(put(customer, a, 2));
        Instant first = updatedAt(a);

        data(put(customer, a, 2));

        assertThat(updatedAt(a)).isAfter(first);
    }

    @Test
    void aNonGoodsOrPrescriptionProductIsBrBh07AndCreatesNoCart() {
        long drug = fx.product("DRUG", false, true);
        long vaccine = fx.product("VACCINE", false, true);
        long prescription = fx.product("DRUG", true, true);

        for (long id : new long[] {drug, vaccine, prescription}) {
            ResponseEntity<Map<String, Object>> response = put(customer, id, 1);
            assertThat(response.getStatusCode().value()).as("sản phẩm %d", id).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).contains("(BR-BH-07)");
        }
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void aMissingOrInactiveProductIs404() {
        long inactive = fx.product("GOODS", false, false);

        assertThat(put(customer, inactive, 1).getStatusCode().value()).isEqualTo(404);
        assertThat(put(customer, 9_000_000_000L + inactive, 1).getStatusCode().value()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void aQuantityOutsideOneToNinetyNineIsValidationFailedAndAMalformedOneIsMalformed() {
        long a = goods(1_000);

        for (String json : List.of("{\"quantity\":0}", "{\"quantity\":100}", "{\"quantity\":-1}", "{}",
                "{\"quantity\":null}")) {
            ResponseEntity<Map<String, Object>> response = call(customer, HttpMethod.PUT, CART + "/items/" + a, json);
            assertThat(response.getStatusCode().value()).as(json).isEqualTo(400);
            assertThat(response.getBody()).as(json).containsEntry("errorCode", "VALIDATION_FAILED");
        }
        for (String json : List.of("{\"quantity\":1.5}", "{\"quantity\":\"x\"}")) {
            ResponseEntity<Map<String, Object>> response = call(customer, HttpMethod.PUT, CART + "/items/" + a, json);
            assertThat(response.getStatusCode().value()).as(json).isEqualTo(400);
        }
        assertThat(call(customer, HttpMethod.PUT, CART + "/items/" + a, "{\"quantity\":1.5}").getBody())
                .containsEntry("errorCode", "MALFORMED_REQUEST");
        assertThat(data(put(customer, a, 1))).isNotNull();
        assertThat(data(put(customer, a, 99))).isNotNull();
        assertThat(lines(customerId)).containsExactly(a);
    }

    // ================================================================ giới hạn 30 dòng

    @Test
    void thirtyLinesAreAllowedTheThirtyFirstIsBrBh07AndAnExistingLineCanStillBeUpdated() {
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            products.add(goods(1_000));
        }
        for (int i = 0; i < 30; i++) {
            data(put(customer, products.get(i), 1));
        }

        ResponseEntity<Map<String, Object>> thirtyFirst = put(customer, products.get(30), 1);
        assertThat(thirtyFirst.getStatusCode().value()).isEqualTo(400);
        assertThat((String) thirtyFirst.getBody().get("message")).contains("(BR-BH-07)");
        assertThat(lines(customerId)).hasSize(30).doesNotContain(products.get(30));

        Map<String, Object> updated = data(put(customer, products.get(0), 9));
        assertThat(items(updated)).hasSize(30);
        assertThat(items(updated).get(0)).containsEntry("quantity", 9);
    }

    @Test
    void hiddenLinesDoNotCountTowardsTheThirtyLineLimit() {
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            products.add(goods(1_000));
        }
        for (int i = 0; i < 30; i++) {
            data(put(customer, products.get(i), 1));
        }
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", products.get(0));

        Map<String, Object> cart = data(put(customer, products.get(30), 1));

        assertThat(items(cart)).hasSize(30);
        assertThat(lines(customerId)).hasSize(30).doesNotContain(products.get(0)).contains(products.get(30));
    }

    // ================================================================ xóa

    @Test
    void removeDeletesTheLineAndIsIdempotent() {
        long a = goods(1_000);
        long b = goods(2_000);
        data(put(customer, a, 1));
        data(put(customer, b, 1));

        Map<String, Object> afterRemove = data(call(customer, HttpMethod.DELETE, CART + "/items/" + a, null));
        Map<String, Object> again = data(call(customer, HttpMethod.DELETE, CART + "/items/" + a, null));

        assertThat(items(afterRemove)).extracting(i -> ((Number) i.get("productId")).longValue()).containsExactly(b);
        assertThat(again).isEqualTo(afterRemove);
    }

    @Test
    void removingFromACustomerWithoutACartReturnsAnEmptyCartAndCreatesNone() {
        long a = goods(1_000);

        Map<String, Object> cart = data(call(customer, HttpMethod.DELETE, CART + "/items/" + a, null));

        assertThat(items(cart)).isEmpty();
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void clearIs204IdempotentAndKeepsTheCartRow() {
        long a = goods(1_000);
        assertThat(call(customer, HttpMethod.DELETE, CART, null).getStatusCode().value()).as("không có giỏ").isEqualTo(204);
        data(put(customer, a, 1));

        ResponseEntity<Map<String, Object>> cleared = call(customer, HttpMethod.DELETE, CART, null);
        ResponseEntity<Map<String, Object>> again = call(customer, HttpMethod.DELETE, CART, null);

        assertThat(cleared.getStatusCode().value()).isEqualTo(204);
        assertThat(again.getStatusCode().value()).isEqualTo(204);
        assertThat(items(data(call(customer, HttpMethod.GET, CART, null)))).isEmpty();
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isEqualTo(1);
        assertThat(lines(customerId)).isEmpty();
    }

    // ================================================================ còn hàng toàn chuỗi

    @Test
    void availableIsComputedChainWideFromNonExpiredLotsMinusHeldReservations() {
        long b2 = fx.branch("CartIT2 ", "ACTIVE", false);
        long inStock = goods(1_000);
        long noLots = goods(1_000);
        long expiredOnly = goods(1_000);
        long heldAway = goods(1_000);
        long splitAcrossBranches = goods(1_000);
        long released = goods(1_000);
        long notEnough = goods(1_000);
        LocalDate today = LocalDate.now(clock);
        fx.lot(branch, inStock, 5, null);
        fx.lot(branch, expiredOnly, 50, today.minusDays(1));
        fx.lot(branch, heldAway, 5, null);
        fx.reservation(branch, heldAway, 4, "HELD");
        fx.lot(branch, splitAcrossBranches, 2, null);
        fx.lot(b2, splitAcrossBranches, 3, today.plusDays(10));
        fx.lot(branch, released, 5, null);
        fx.reservation(branch, released, 5, "RELEASED");
        fx.lot(branch, notEnough, 3, null);
        for (long id : new long[] {inStock, noLots, expiredOnly, heldAway, notEnough}) {
            data(put(customer, id, 4));
        }
        data(put(customer, splitAcrossBranches, 5));
        Map<String, Object> cart = data(put(customer, released, 5));

        Map<Long, Object> available = new java.util.HashMap<>();
        items(cart).forEach(i -> available.put(((Number) i.get("productId")).longValue(), i.get("available")));
        assertThat(available).containsEntry(inStock, true)
                .containsEntry(noLots, false)
                .containsEntry(expiredOnly, false)
                .containsEntry(heldAway, false)
                .containsEntry(splitAcrossBranches, true)
                .containsEntry(released, true)
                .containsEntry(notEnough, false);
    }

    // ================================================================ phân quyền / phạm vi

    @Test
    void onlyACustomerMayUseTheCart() {
        long a = goods(1_000);
        String receptionist = token(support.staff("RECEPTIONIST", support.branch("ACTIVE")));
        String superManager = token(support.staff("SUPER_MANAGER", null));

        for (String bearer : List.of(receptionist, superManager)) {
            assertThat(call(bearer, HttpMethod.GET, CART, null).getStatusCode().value()).isEqualTo(403);
            assertThat(call(bearer, HttpMethod.DELETE, CART, null).getStatusCode().value()).isEqualTo(403);
            assertThat(put(bearer, a, 1).getStatusCode().value()).isEqualTo(403);
            assertThat(call(bearer, HttpMethod.DELETE, CART + "/items/" + a, null).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(call(null, HttpMethod.GET, CART, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(null, HttpMethod.DELETE, CART, null).getStatusCode().value()).isEqualTo(401);
        assertThat(put(null, a, 1).getStatusCode().value()).isEqualTo(401);
        assertThat(call(null, HttpMethod.DELETE, CART + "/items/" + a, null).getStatusCode().value()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM carts WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void aCustomerNeverSeesOrChangesAnotherCustomersCart() {
        long a = goods(1_000);
        data(put(customer, a, 3));

        assertThat(items(data(call(otherCustomer, HttpMethod.GET, CART, null)))).isEmpty();
        data(call(otherCustomer, HttpMethod.DELETE, CART + "/items/" + a, null));
        assertThat(call(otherCustomer, HttpMethod.DELETE, CART, null).getStatusCode().value()).isEqualTo(204);

        assertThat(items(data(call(customer, HttpMethod.GET, CART, null)))).hasSize(1);
        data(put(otherCustomer, a, 1));
        assertThat(items(data(call(customer, HttpMethod.GET, CART, null))).get(0)).containsEntry("quantity", 3);
        assertThat(lines(otherCustomerId)).containsExactly(a);
    }

    @Test
    void aCustomerAccountWithoutAProfileIsADataFault500() {
        long account = support.customerAccount();
        String bearer = token(account);

        assertThat(call(bearer, HttpMethod.GET, CART, null).getStatusCode().value()).isEqualTo(500);
    }

    // ================================================================ khóa giỏ

    /**
     * Giỏ đã có 29 dòng; một giao dịch khác giữ khóa dòng giỏ. Hai PUT hai sản phẩm mới khác nhau cùng đến: cả hai phải chờ
     * khóa, sau đó chạy lần lượt và đúng một cái thành công (dòng thứ 30), cái kia nhận 400 BR-BH-07. Bỏ khóa thì cả hai chạy
     * ngay (không chờ) và test đỏ.
     */
    @Test
    void twoConcurrentPutsOfDifferentNewProductsAt29LinesLetExactlyOneIn() throws Exception {
        for (int i = 0; i < 29; i++) {
            data(put(customer, goods(1_000), 1));
        }
        long x = goods(1_000);
        long y = goods(1_000);
        long cartId = jdbc.queryForObject("SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM carts WHERE id = ? FOR NO KEY UPDATE", cartId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> putX = pool.submit(() -> put(customer, x, 1));
            Future<ResponseEntity<Map<String, Object>>> putY = pool.submit(() -> put(customer, y, 1));
            awaitWaiting(2);
            assertThat(putX.isDone() || putY.isDone()).as("cả hai PUT phải chờ khóa giỏ").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<Integer> statuses = new ArrayList<>(List.of(putX.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                    putY.get(30, TimeUnit.SECONDS).getStatusCode().value()));
            java.util.Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 400);
            assertThat(lines(customerId)).hasSize(30);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** FOR SHARE chặn FOR NO KEY UPDATE nhưng không chặn FOR SHARE khác (hay FOR KEY SHARE): khóa yếu hơn thì test đỏ. */
    @Test
    void putTakesAStrongEnoughLockToWaitBehindAShareLockOnTheCartRow() throws Exception {
        long a = goods(1_000);
        long b = goods(1_000);
        data(put(customer, a, 1));
        long cartId = jdbc.queryForObject("SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM carts WHERE id = ? FOR SHARE", cartId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(customer, b, 1));
            awaitWaiting(1);
            assertThat(put.isDone()).as("PUT phải chờ khóa").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(put.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ================================================================ helpers

    private List<Long> lines(long forCustomer) {
        return jdbc.queryForList("""
                SELECT product_id FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE customer_id = ?)""",
                Long.class, forCustomer);
    }

    private Instant updatedAt(long productId) {
        return jdbc.queryForObject("""
                SELECT updated_at FROM cart_items WHERE product_id = ?
                  AND cart_id IN (SELECT id FROM carts WHERE customer_id = ?)""", Timestamp.class, productId,
                customerId).toInstant();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void awaitWaiting(int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from carts%'""", Long.class);
            if (waiting != null && waiting >= atLeast) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy đủ " + atLeast + " PUT chờ khóa giỏ");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private ResponseEntity<Map<String, Object>> put(String bearer, long productId, int quantity) {
        return call(bearer, HttpMethod.PUT, CART + "/items/" + productId, "{\"quantity\":" + quantity + "}");
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(json, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s", response.getBody()).isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> cart) {
        return (List<Map<String, Object>>) cart.get("items");
    }
}
