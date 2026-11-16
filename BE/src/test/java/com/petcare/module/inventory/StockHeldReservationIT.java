package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderRef;
import com.petcare.module.inventory.api.StockApi.OrderSale;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Task 117 phần kho (BR-BH-08, BR-BH-09; ADR-0144 P2, P3) trên Postgres 17 thật: mọi nơi tính tồn khả dụng trừ phần
 * {@code HELD}, bỏ qua {@code RELEASED} / {@code CONSUMED}; thu quầy / tiêm không xuất vào phần HELD;
 * {@code lockAvailableQuantity} khóa lô {@code FOR NO KEY UPDATE} một câu; {@code reserve} chỉ chèn HELD khi đủ hàng.
 * Đồng hồ ứng dụng thật (hạn dùng tính theo ngày hiện tại, như {@code StockApiIT}), nên không cần một era riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockHeldReservationIT extends InventoryItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private StockApi stock;
    @Autowired private StockQueryApi queries;

    private long branch;
    private long otherBranch;
    private long actor;
    private long product;
    private long customerId;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> onlineOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        branch = newBranch();
        otherBranch = newBranch();
        actor = staff("BRANCH_MANAGER", branch);
        product = newProduct("GOODS", false);
        customerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khach giu hang', '0901234567', 'COUNTER')
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanReservations() {
        if (!onlineOrderIds.isEmpty()) {
            jdbc.update("DELETE FROM stock_reservations WHERE online_order_id IN (" + ids(onlineOrderIds) + ")");
            jdbc.update("DELETE FROM online_orders WHERE id IN (" + ids(onlineOrderIds) + ")");
        }
        if (!orderIds.isEmpty()) {
            jdbc.update("DELETE FROM orders WHERE id IN (" + ids(orderIds) + ")");
        }
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        onlineOrderIds.clear();
        orderIds.clear();
    }

    private static String ids(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    private static String inDays(int days) {
        return LocalDate.now(ZONE).plusDays(days).toString();
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    /** Một Order ONLINE + online_orders thật (FK của stock_reservations); trả id của online_orders. */
    private long onlineOrder(long atBranch) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, total_amount, created_by, pending_at)
                VALUES (?, 'ONLINE', ?, ?, 'PENDING', 0, ?, now()) RETURNING id
                """, Long.class, "T117-" + tag, customerId, atBranch, actor);
        orderIds.add(orderId);
        long onlineId = jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name, receiver_phone,
                                           ship_address, shipping_fee, payment_due_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 'A', '0901234567', 'B', 0, now() + interval '1 day') RETURNING id
                """, Long.class, "T117-" + tag, orderId, customerId, atBranch);
        onlineOrderIds.add(onlineId);
        return onlineId;
    }

    private long held(long atBranch, long productId, int quantity, String status) {
        long online = onlineOrder(atBranch);
        jdbc.update("""
                INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status)
                VALUES (?, ?, ?, ?, ?)
                """, online, atBranch, productId, quantity, status);
        return online;
    }

    private long heldTotal(long atBranch, long productId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations
                WHERE branch_id = ? AND product_id = ? AND status = 'HELD'
                """, Long.class, atBranch, productId);
    }

    private long reservationRows(long productId) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE product_id = ?", Long.class, productId);
    }

    // ---------------------------------------------------------------- (a) availability

    @Test
    void everyAvailabilityQuerySubtractsHeldAndIgnoresReleasedAndConsumed() {
        lot(branch, product, "A", inDays(30), 10, actor);
        lot(branch, product, "EXP", inDays(-1), 50, actor);
        lot(otherBranch, product, "B", inDays(30), 10, actor);
        held(branch, product, 4, "HELD");
        held(branch, product, 3, "RELEASED");
        held(branch, product, 2, "CONSUMED");
        held(otherBranch, product, 1, "HELD");

        assertThat(queries.availableQuantity(branch, product)).as("10 - 4").isEqualTo(6);
        assertThat(queries.availableQuantities(branch, List.of(product))).containsEntry(product, 6);
        assertThat(queries.findAvailableQuantity(branch, Set.of(product))).containsEntry(product, 6);
        assertThat(queries.findAvailableQuantity(otherBranch, Set.of(product))).containsEntry(product, 9);
        assertThat(queries.branchIdsWithAvailableStock(product)).contains(branch, otherBranch);
        assertThat(queries.branchIdsWithAvailableStock(List.of(product)).get(product)).contains(branch, otherBranch);
        assertThat(queries.productIdsWithAvailableStock(branch)).contains(product);

        Map<String, Object> item = data(call(HttpMethod.GET, "/api/inventory/" + product, token(actor), null),
                HttpStatus.OK);
        assertThat(item).containsEntry("availableQuantity", 6);
        assertThat(item.get("totalQuantity")).as("tổng kho vẫn gồm lô hết hạn, không trừ HELD").isEqualTo(60);
    }

    @Test
    void aBranchWhoseWholeStockIsHeldDropsOutOfTheInStockLists() {
        lot(branch, product, "A", inDays(30), 5, actor);
        lot(otherBranch, product, "B", inDays(30), 5, actor);
        held(branch, product, 5, "HELD");

        assertThat(queries.availableQuantity(branch, product)).isZero();
        assertThat(queries.branchIdsWithAvailableStock(product)).contains(otherBranch).doesNotContain(branch);
        assertThat(queries.branchIdsWithAvailableStock(List.of(product)).get(product)).contains(otherBranch)
                .doesNotContain(branch);
        assertThat(queries.productIdsWithAvailableStock(branch)).doesNotContain(product);
    }

    @Test
    void anOverHeldProductIsNeverNegative() {
        lot(branch, product, "A", inDays(30), 2, actor);
        held(branch, product, 5, "HELD");

        assertThat(queries.availableQuantity(branch, product)).isZero();
        assertThat(queries.findAvailableQuantity(branch, Set.of(product))).containsEntry(product, 0);
    }

    // ---------------------------------------------------------------- (d) zero lots / empty input

    @Test
    void productsWithoutLotsAreKeyedWithZeroAndEmptyInputGivesEmptyMap() {
        long noLots = newProduct("GOODS", false);

        assertThat(queries.findAvailableQuantity(branch, Set.of(noLots))).containsOnlyKeys(noLots)
                .containsEntry(noLots, 0);
        assertThat(queries.findAvailableQuantity(branch, List.of())).isEmpty();
        assertThat(inTx(() -> stock.lockAvailableQuantity(branch, Set.of(noLots)))).containsEntry(noLots, 0);
        assertThat(inTx(() -> stock.lockAvailableQuantity(branch, List.of()))).isEmpty();
    }

    // ---------------------------------------------------------------- (b) issue paths cannot take HELD

    @Test
    void paymentCannotIssueHeldQuantityAndReportsAShortage() {
        long lotId = lot(branch, product, "A", inDays(30), 10, actor);
        held(branch, product, 7, "HELD");

        List<Shortage> tooMuch = inTx(() -> stock.issueForPayment(branch,
                List.of(new OrderSale(900L, List.of(new SaleLine(product, 4)))), actor));

        assertThat(tooMuch).containsExactly(new Shortage(product, 4, 3));
        assertThat(lotQuantity(lotId)).as("không trừ gì").isEqualTo(10);

        List<Shortage> fits = inTx(() -> stock.issueForPayment(branch,
                List.of(new OrderSale(901L, List.of(new SaleLine(product, 3)))), actor));
        assertThat(fits).isEmpty();
        assertThat(lotQuantity(lotId)).isEqualTo(7);
    }

    @Test
    void releasedAndConsumedReservationsDoNotBlockPayment() {
        long lotId = lot(branch, product, "A", inDays(30), 10, actor);
        held(branch, product, 6, "RELEASED");
        held(branch, product, 3, "CONSUMED");

        assertThat(inTx(() -> stock.issueForPayment(branch,
                List.of(new OrderSale(902L, List.of(new SaleLine(product, 10)))), actor))).isEmpty();
        assertThat(lotQuantity(lotId)).isZero();
    }

    @Test
    void lockFefoLotCannotPickQuantityHeldForOnlineOrders() {
        long vaccine = newProduct("VACCINE", true);
        long lotId = lot(branch, vaccine, "V", inDays(30), 5, actor);
        held(branch, vaccine, 4, "HELD");

        assertThatThrownBy(() -> inTx(() -> stock.lockFefoLot(branch, vaccine, 2)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("tồn khả dụng 1");
        assertThat(inTx(() -> stock.lockFefoLot(branch, vaccine, 1))).isEqualTo(lotId);
    }

    // ---------------------------------------------------------------- lockAvailableQuantity / reserve

    @Test
    void lockAvailableQuantityReturnsNonExpiredLotsMinusHeld() {
        lot(branch, product, "A", inDays(30), 10, actor);
        lot(branch, product, "EXP", inDays(-1), 99, actor);
        held(branch, product, 4, "HELD");
        held(branch, product, 9, "RELEASED");

        assertThat(inTx(() -> stock.lockAvailableQuantity(branch, List.of(product)))).containsEntry(product, 6);
    }

    @Test
    void reserveInsertsOneHeldRowPerProductOnlyWhenEverythingFits() {
        long second = newProduct("GOODS", false);
        lot(branch, product, "A", inDays(30), 10, actor);
        lot(branch, second, "S", inDays(30), 3, actor);
        long online = onlineOrder(branch);

        List<Shortage> shortages = inTx(() -> stock.reserve(branch, new OrderRef(online, 1L),
                List.of(new SaleLine(product, 4), new SaleLine(second, 3), new SaleLine(product, 2))));

        assertThat(shortages).isEmpty();
        assertThat(heldTotal(branch, product)).isEqualTo(6);
        assertThat(heldTotal(branch, second)).isEqualTo(3);
        assertThat(reservationRows(product)).as("một dòng mỗi sản phẩm").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations WHERE online_order_id = ? AND product_id = ?",
                String.class, online, second)).isEqualTo("HELD");
        assertThat(queries.availableQuantity(branch, product)).isEqualTo(4);
        assertThat(queries.availableQuantity(branch, second)).isZero();
    }

    @Test
    void reserveWritesNothingWhenOneLineIsShortAndSeesEarlierHeld() {
        long second = newProduct("GOODS", false);
        lot(branch, product, "A", inDays(30), 10, actor);
        lot(branch, second, "S", inDays(30), 3, actor);
        held(branch, product, 8, "HELD");
        long online = onlineOrder(branch);

        List<Shortage> shortages = inTx(() -> stock.reserve(branch, new OrderRef(online, 1L),
                List.of(new SaleLine(product, 3), new SaleLine(second, 3))));

        assertThat(shortages).containsExactly(new Shortage(product, 3, 2));
        assertThat(reservationRows(second)).isZero();
        assertThat(heldTotal(branch, product)).as("chỉ phần HELD cũ").isEqualTo(8);
    }

    @Test
    void reserveIgnoresExpiredLots() {
        lot(branch, product, "EXP", inDays(-1), 50, actor);
        long online = onlineOrder(branch);

        assertThat(inTx(() -> stock.reserve(branch, new OrderRef(online, 1L), List.of(new SaleLine(product, 1)))))
                .containsExactly(new Shortage(product, 1, 0));
    }

    @Test
    void newMethodsRequireTheCallersTransaction() {
        assertThatThrownBy(() -> stock.lockAvailableQuantity(branch, List.of(product)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.reserve(branch, new OrderRef(1L, 1L), List.of(new SaleLine(product, 1))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.release(new OrderRef(1L, 1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- (b2) release (task 125, ADR-0145)

    @Test
    void releaseTurnsOnlyThatOrdersHeldRowsIntoReleasedAndIsIdempotent() {
        lot(branch, product, "A", inDays(30), 10, actor);
        long mine = held(branch, product, 4, "HELD");
        long other = held(branch, product, 3, "HELD");
        assertThat(queries.availableQuantity(branch, product)).isEqualTo(3);

        inTx(() -> {
            stock.release(new OrderRef(mine, 1L));
            return null;
        });

        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations WHERE online_order_id = ?", String.class,
                mine)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations WHERE online_order_id = ?", String.class,
                other)).isEqualTo("HELD");
        assertThat(queries.availableQuantity(branch, product)).as("10 - 3").isEqualTo(7);

        inTx(() -> {
            stock.release(new OrderRef(mine, 1L));
            return null;
        });
        assertThat(heldTotal(branch, product)).isEqualTo(3);
    }

    @Test
    void releaseNeverTouchesConsumedRowsAndRefusesNullReferences() {
        long consumed = held(branch, product, 2, "CONSUMED");

        inTx(() -> {
            stock.release(new OrderRef(consumed, 1L));
            return null;
        });

        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations WHERE online_order_id = ?", String.class,
                consumed)).isEqualTo("CONSUMED");
        assertThatThrownBy(() -> inTx(() -> {
            stock.release(new OrderRef(null, 1L));
            return null;
        })).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- (c) lock strength and race

    @Test
    void lockAvailableQuantityLocksLotsForNoKeyUpdateSoAShareHolderBlocksIt() throws Exception {
        long lotId = lot(branch, product, "A", inDays(30), 10, actor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM stock_lots WHERE id = ? FOR SHARE", lotId);
                holding.countDown();
                await(release);
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Map<Long, Integer>> locker = pool.submit(() -> inTx(() -> stock.lockAvailableQuantity(branch,
                    List.of(product))));
            waitUntilLockWaitOnStockLots();
            assertThat(locker.isDone()).as("FOR SHARE chặn FOR NO KEY UPDATE").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(locker.get(10, TimeUnit.SECONDS)).containsEntry(product, 10);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void twoConcurrentReservationsForTheLastUnitsLetExactlyOneWin() throws Exception {
        lot(branch, product, "A", inDays(30), 5, actor);
        long first = onlineOrder(branch);
        long second = onlineOrder(branch);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<Shortage>> a = pool.submit(() -> {
                await(start);
                return inTx(() -> stock.reserve(branch, new OrderRef(first, 1L), List.of(new SaleLine(product, 5))));
            });
            Future<List<Shortage>> b = pool.submit(() -> {
                await(start);
                return inTx(() -> stock.reserve(branch, new OrderRef(second, 2L), List.of(new SaleLine(product, 5))));
            });
            start.countDown();
            List<Shortage> ra = a.get(20, TimeUnit.SECONDS);
            List<Shortage> rb = b.get(20, TimeUnit.SECONDS);

            assertThat(List.of(ra.isEmpty(), rb.isEmpty())).containsExactlyInAnyOrder(true, false);
            assertThat(heldTotal(branch, product)).isEqualTo(5);
            assertThat(queries.availableQuantity(branch, product)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void paymentRacingAReservationNeverIssuesHeldQuantity() throws Exception {
        long lotId = lot(branch, product, "A", inDays(30), 5, actor);
        long online = onlineOrder(branch);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<Shortage>> reserve = pool.submit(() -> {
                await(start);
                return inTx(() -> stock.reserve(branch, new OrderRef(online, 1L), List.of(new SaleLine(product, 5))));
            });
            Future<List<Shortage>> pay = pool.submit(() -> {
                await(start);
                return inTx(() -> stock.issueForPayment(branch,
                        List.of(new OrderSale(950L, List.of(new SaleLine(product, 5)))), actor));
            });
            start.countDown();
            boolean reserved = reserve.get(20, TimeUnit.SECONDS).isEmpty();
            boolean paid = pay.get(20, TimeUnit.SECONDS).isEmpty();

            assertThat(reserved ^ paid).as("đúng một bên lấy được 5 cái cuối").isTrue();
            assertThat(lotQuantity(lotId)).isEqualTo(paid ? 0 : 5);
            assertThat(heldTotal(branch, product)).isEqualTo(reserved ? 5 : 0);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Chờ tới khi có một câu khóa lô đang đợi khóa (pg_stat_activity), thay vì ngủ cố định. */
    private void waitUntilLockWaitOnStockLots() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%FOR NO KEY UPDATE%' AND query ILIKE '%stock_lots%'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("không thấy câu khóa lô nào đang chờ");
    }
}
