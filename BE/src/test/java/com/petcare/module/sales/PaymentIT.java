package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.petcare.support.MutableClock;

/**
 * Thu tiền (sales-v1 #14; UC70; Order#8; BR-TG-01…04, BR-BH-04, 06; docs/adr/0064) trên Postgres 17 thật qua HTTP:
 * thu gộp trừ kho FEFO cùng transaction, thứ tự lỗi, rollback khi audit lỗi và các race trên khóa ca / Order / lô.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class PaymentIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    private static final Instant OPENED = Instant.parse("2005-06-15T01:00:00Z");

    @Autowired
    private MutableClock clock;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long customer;
    private long otherCustomer;
    private long shift;
    private long goods1;
    private long goods2;
    private String tReceptionist;
    private String tManager;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        tReceptionist = token(receptionist);
        tManager = token(manager);
        customer = newCustomer();
        otherCustomer = newCustomer();
        shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        goods1 = newPricedProduct("GOODS", false, 100_000);
        goods2 = newPricedProduct("GOODS", false, 50_000);
        defaultLot(branch, goods1, 10, manager);
        defaultLot(branch, goods2, 10, manager);
    }

    private ResponseEntity<Map<String, Object>> pay(String token, Object orderIds, String method, Object amount) {
        return call(HttpMethod.POST, "/api/payments", token, Map.of("orderIds", orderIds, "method", method, "amount",
                amount));
    }

    private ResponseEntity<Map<String, Object>> payCash(long amount, Long... orderIds) {
        return pay(tReceptionist, List.of(orderIds), "CASH", amount);
    }

    private long order(long forCustomer, LineSpec... lines) {
        return pendingOrderSql(branch, forCustomer, receptionist, lines);
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, HttpStatus status, String rule) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat((String) response.getBody().get("message")).endsWith("(" + rule + ")");
    }

    private long paymentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE branch_id = ?", Long.class, branch);
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void collectsCombinedOrdersDeductsFefoAndAudits() {
        long a = order(customer, LineSpec.goods(goods1, "Hạt", 2, 100_000));
        long b = order(customer, LineSpec.goods(goods2, "Cát", 3, 50_000));

        Map<String, Object> payment = data(payCash(350_000, a, b), HttpStatus.CREATED);

        assertThat(payment).containsOnlyKeys("paymentId", "code", "branchId", "customerId", "cashierShiftId",
                "method", "amount", "receivedBy", "paidAt", "orders");
        assertThat(payment).containsEntry("amount", 350_000).containsEntry("method", "CASH")
                .containsEntry("cashierShiftId", (int) shift).containsEntry("receivedBy", (int) receptionist)
                .containsEntry("customerId", (int) customer).containsEntry("branchId", (int) branch);
        assertThat((String) payment.get("code")).matches("^PT-\\d{4}-\\d{5}$");
        long paymentId = ((Number) payment.get("paymentId")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) payment.get("orders");
        assertThat(summaries).extracting(s -> s.get("status")).containsOnly("PAID");
        assertThat(summaries).extracting(s -> ((Number) s.get("orderId")).longValue()).containsExactly(a, b);

        assertThat(orderStatus(a)).isEqualTo("PAID");
        assertThat(orderStatus(b)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT payment_id FROM orders WHERE id = ?", Long.class, a))
                .isEqualTo(paymentId);
        assertThat(stockOf(branch, goods1)).isEqualTo(8);
        assertThat(stockOf(branch, goods2)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements WHERE movement_type = 'SALE' AND source_type = 'orders'
                  AND source_id IN (?, ?)""", Long.class, a, b)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_PAID' AND entity_id IN (?, ?)""", Long.class,
                a, b)).isEqualTo(2L);
    }

    @Test
    void vaccineAndServiceLinesAreNotDeductedAgain() {
        long vaccine = newPricedProduct("VACCINE", false, 200_000);
        defaultLot(branch, vaccine, 5, manager);
        long service = newService("MEDICAL", 150_000);
        long o = order(customer, new LineSpec("VACCINE", null, vaccine, "Vaccine", 1, 200_000),
                new LineSpec("SERVICE", service, null, "Khám", 1, 150_000), LineSpec.goods(goods1, "Hạt", 1, 100_000));

        data(payCash(450_000, o), HttpStatus.CREATED);

        assertThat(stockOf(branch, vaccine)).as("vaccine đã trừ khi tiêm, không trừ lại").isEqualTo(5);
        assertThat(stockOf(branch, goods1)).isEqualTo(9);
    }

    @Test
    void zeroAmountOrderIsCollected() {
        long service = newService("MEDICAL", 0);
        long free = order(customer, new LineSpec("SERVICE", service, null, "Khám miễn phí", 1, 0));

        Map<String, Object> payment = data(payCash(0, free), HttpStatus.CREATED);

        assertThat(payment).containsEntry("amount", 0);
        assertThat(orderStatus(free)).isEqualTo("PAID");
    }

    @Test
    void transferMethodIsRecordedAndSummedSeparately() {
        long o = order(customer, LineSpec.goods(goods2, "Cát", 1, 50_000));

        data(pay(tReceptionist, List.of(o), "TRANSFER", 50_000), HttpStatus.CREATED);

        assertThat(jdbc.queryForObject("SELECT method FROM payments WHERE cashier_shift_id = ?", String.class, shift))
                .isEqualTo("TRANSFER");
    }

    // ---------------------------------------------------------------- lỗi

    @Test
    void noOpenShiftIs400BrTg01() {
        String tOther = token(receptionist(branch));
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertRule(pay(tOther, List.of(o), "CASH", 100_000), HttpStatus.BAD_REQUEST, "BR-TG-01");
        assertThat(orderStatus(o)).isEqualTo("PENDING");
    }

    @Test
    void otherCustomersOrdersAreBrTg03() {
        long a = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));
        long b = order(otherCustomer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertRule(payCash(200_000, a, b), HttpStatus.BAD_REQUEST, "BR-TG-03");
        assertThat(paymentCount()).isZero();
    }

    @Test
    void orderOfAnotherBranchIs403AndMissingIs404() {
        long foreign = pendingOrderSql(otherBranch, customer, receptionist(otherBranch),
                LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertThat(payCash(100_000, foreign).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(payCash(1, Long.MAX_VALUE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(orderStatus(foreign)).isEqualTo("PENDING");
    }

    @Test
    void amountMismatchIs400BrTg02() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertRule(payCash(99_999, o), HttpStatus.BAD_REQUEST, "BR-TG-02");
        assertThat(stockOf(branch, goods1)).isEqualTo(10);
    }

    @Test
    void shortageIs400BrBh04WritesNothingAndExpiredLotsDoNotCount() {
        long drug = newPricedProduct("DRUG", false, 80_000);
        lot(branch, drug, "OLD", "2000-01-01", 50, manager); // đã hết hạn: không tính
        lot(branch, drug, "NEW", "2099-01-01", 1, manager);
        long o = order(customer, new LineSpec("DRUG", null, drug, "Thuốc", 2, 80_000));
        long ok = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        ResponseEntity<Map<String, Object>> response = payCash(260_000, o, ok);

        assertRule(response, HttpStatus.BAD_REQUEST, "BR-BH-04");
        assertThat((String) response.getBody().get("message")).contains("Thuốc");
        assertThat(orderStatus(o)).isEqualTo("PENDING");
        assertThat(orderStatus(ok)).isEqualTo("PENDING");
        assertThat(stockOf(branch, goods1)).as("không trừ gì khi một dòng thiếu").isEqualTo(10);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void validationErrorsAre400() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertThat(pay(tReceptionist, List.of(), "CASH", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(tReceptionist, List.of(o, o), "CASH", 200_000).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(tReceptionist, List.of(o), "CASH", -1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(tReceptionist, List.of(o), "BITCOIN", 100_000).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callRaw(HttpMethod.POST, "/api/payments", tReceptionist,
                "{\"orderIds\":[" + o + "],\"method\":\"CASH\",\"amount\":100000.5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            many.add(i);
        }
        assertThat(pay(tReceptionist, many, "CASH", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orderStatus(o)).isEqualTo("PENDING");
    }

    @Test
    void otherRolesAre403AndAnonymousIs401() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));

        assertThat(pay(tManager, List.of(o), "CASH", 100_000).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pay(null, List.of(o), "CASH", 100_000).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- race

    /** Review Focus 3: bấm "Thu" hai lần cùng lúc → một phiếu thu, kho trừ một lần, lần sau 409. */
    @Test
    void doubleSubmitPaysOnceAndSecondIs409() throws Exception {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 2, 100_000));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<Map<String, Object>>> task = () -> payCash(200_000, o);
            List<Future<ResponseEntity<Map<String, Object>>>> results = pool.invokeAll(List.of(task, task));
            List<Integer> statuses = List.of(results.get(0).get(30, TimeUnit.SECONDS).getStatusCode().value(),
                    results.get(1).get(30, TimeUnit.SECONDS).getStatusCode().value());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(paymentCount()).isEqualTo(1L);
        assertThat(stockOf(branch, goods1)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE source_type = 'orders' "
                + "AND source_id = ?", Long.class, o)).isEqualTo(1L);
    }

    @Test
    void secondSubmitAfterSuccessIs409() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));
        data(payCash(100_000, o), HttpStatus.CREATED);

        assertThat(payCash(100_000, o).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paymentCount()).isEqualTo(1L);
    }

    /** Review Focus 1: hai quầy thu gộp chéo sản phẩm theo thứ tự ngược nhau không deadlock, tồn đúng. */
    @Test
    void crossProductCombinedPaymentsDoNotDeadlock() throws Exception {
        long second = receptionist(branch);
        openShiftSql(branch, second, false, DAY, OPENED);
        String tSecond = token(second);
        int rounds = 12;
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> first = () -> {
            for (int i = 0; i < rounds; i++) {
                long x = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));
                long y = order(customer, LineSpec.goods(goods2, "Cát", 1, 50_000));
                barrier.await(20, TimeUnit.SECONDS);
                assertThat(pay(tReceptionist, List.of(x, y), "CASH", 150_000).getStatusCode().value()).isEqualTo(201);
            }
            return null;
        };
        Callable<Void> opposite = () -> {
            for (int i = 0; i < rounds; i++) {
                long x = pendingOrderSql(branch, otherCustomer, second, LineSpec.goods(goods2, "Cát", 1, 50_000));
                long y = pendingOrderSql(branch, otherCustomer, second, LineSpec.goods(goods1, "Hạt", 1, 100_000));
                barrier.await(20, TimeUnit.SECONDS);
                assertThat(pay(tSecond, List.of(x, y), "CASH", 150_000).getStatusCode().value()).isEqualTo(201);
            }
            return null;
        };
        lot(branch, goods1, "BIG1", "2099-01-01", 100, manager);
        lot(branch, goods2, "BIG2", "2099-01-01", 100, manager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<Void> result : pool.invokeAll(List.of(first, opposite))) {
                result.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockOf(branch, goods1)).isEqualTo(110 - 2 * rounds);
        assertThat(stockOf(branch, goods2)).isEqualTo(110 - 2 * rounds);
    }

    /** Review Focus 2: chốt ca chờ phiếu thu đang chạy rồi tính cả phiếu đó vào tiền mặt kỳ vọng. */
    @Test
    void closeWaitsForInFlightPaymentAndCountsIt() throws Exception {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));
        long lotId = jdbc.queryForObject("""
                SELECT l.id FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND i.product_id = ?""", Long.class, branch, goods1);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM stock_lots WHERE id = ? FOR UPDATE", Long.class, lotId);
                    locked.complete(null);
                    release.join();
                }));
        try {
            locked.get(5, TimeUnit.SECONDS);
            CompletableFuture<ResponseEntity<Map<String, Object>>> payment = CompletableFuture
                    .supplyAsync(() -> payCash(100_000, o));
            awaitLockWait(1, "stock_lots");
            CompletableFuture<ResponseEntity<Map<String, Object>>> close = CompletableFuture.supplyAsync(
                    () -> call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/close", tReceptionist,
                            Map.of("countedCash", 100_000)));
            awaitLockWait(1, "cashier_shifts", "for no key update");
            release.complete(null);
            holder.get(5, TimeUnit.SECONDS);

            data(payment.get(30, TimeUnit.SECONDS), HttpStatus.CREATED);
            Map<String, Object> closed = data(close.get(30, TimeUnit.SECONDS), HttpStatus.OK);
            assertThat(closed).containsEntry("expectedCash", 100_000).containsEntry("paymentCount", 1);
        } finally {
            release.complete(null);
        }
    }

    @Test
    void paymentAfterTheShiftWasClosedWhileWaitingIsRejected() throws Exception {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000));
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM cashier_shifts WHERE id = ? FOR UPDATE", Long.class, shift);
                    locked.complete(null);
                    release.join();
                    jdbc.update("""
                            UPDATE cashier_shifts SET status = 'CLOSED', closed_at = now(), counted_cash = 0,
                                   expected_cash = 0, expected_transfer = 0 WHERE id = ?""", shift);
                }));
        try {
            locked.get(5, TimeUnit.SECONDS);
            CompletableFuture<ResponseEntity<Map<String, Object>>> payment = CompletableFuture
                    .supplyAsync(() -> payCash(100_000, o));
            awaitLockWait(1, "cashier_shifts", "for share");
            release.complete(null);
            holder.get(5, TimeUnit.SECONDS);

            assertRule(payment.get(30, TimeUnit.SECONDS), HttpStatus.BAD_REQUEST, "BR-TG-01");
        } finally {
            release.complete(null);
        }
        assertThat(orderStatus(o)).isEqualTo("PENDING");
        assertThat(paymentCount()).isZero();
    }

    /** Review Focus 5: lễ tân khác xóa một dòng sau khi xem tổng → thu số cũ bị 400 BR-TG-02. */
    @Test
    void lineRemovedAfterTheTotalWasReadMakesAmountMismatch() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 1, 100_000), LineSpec.goods(goods2, "Cát", 1, 50_000));
        long staleTotal = 150_000;
        long lineId = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND product_id = ?",
                Long.class, o, goods2);
        assertThat(call(HttpMethod.DELETE, "/api/orders/" + o + "/lines/" + lineId, tReceptionist, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertRule(payCash(staleTotal, o), HttpStatus.BAD_REQUEST, "BR-TG-02");
        data(payCash(100_000, o), HttpStatus.CREATED);
    }

    @Test
    void failureWhileAuditingRollsBackEverything() {
        long o = order(customer, LineSpec.goods(goods1, "Hạt", 2, 100_000));
        Long counterBefore = jdbc.queryForList("SELECT last_value FROM document_counters WHERE doc_type = 'payments'",
                Long.class).stream().findFirst().orElse(null);

        whileInsertsFail("audit_logs", "NEW.action = 'ORDER_PAID'", () -> assertThat(
                payCash(200_000, o).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(orderStatus(o)).isEqualTo("PENDING");
        assertThat(paymentCount()).isZero();
        assertThat(stockOf(branch, goods1)).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT last_value FROM document_counters WHERE doc_type = 'payments'",
                Long.class).stream().findFirst().orElse(null)).isEqualTo(counterBefore);
    }
}
