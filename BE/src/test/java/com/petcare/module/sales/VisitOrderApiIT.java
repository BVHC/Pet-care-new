package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.sales.exception.OrderAmountOverflowException;
import com.petcare.module.sales.exception.OrderLineAccessDeniedException;
import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * {@link VisitOrderApi} thật (docs/adr/0044; Order#1, #4, #6; BR-BH-01…03, BR-TN-01, BR-TN-08, BR-KB-03, 04) trên
 * Postgres 17 thật. Mỗi lời gọi chạy trong transaction riêng như Visit của BE-1 sẽ làm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitOrderApiIT extends SalesItSupport {

    @Autowired
    private VisitOrderApi visitOrders;

    private TransactionTemplate tx;
    private long branch;
    private long receptionist;
    private long vet;
    private long otherVet;
    private long customer;
    private long visit;
    private long examService;
    private long drug;
    private long vaccine;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branch = newBranch();
        receptionist = staff("RECEPTIONIST", branch);
        vet = staff("VET", branch);
        otherVet = staff("VET", branch);
        customer = newCustomer();
        long pet = newPet(customer);
        visit = newVisit(branch, customer, pet, "MEDICAL", "WAITING", null, receptionist);
        examService = newService("MEDICAL", 150_000);
        drug = newPricedProduct("DRUG", true, 30_000);
        vaccine = newPricedProduct("VACCINE", false, 200_000);
    }

    private LineRef open(Long owner) {
        return tx.execute(s -> visitOrders.openForVisit(visit, branch, customer, examService, owner, receptionist));
    }

    // ---------------------------------------------------------------- Order#1

    @Test
    void openForVisitCreatesOpenOrderWithOneAutoLine() {
        LineRef ref = open(null);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", ref.orderId());
        assertThat(order).containsEntry("source", "VISIT").containsEntry("status", "OPEN")
                .containsEntry("visit_id", visit).containsEntry("customer_id", customer)
                .containsEntry("branch_id", branch).containsEntry("created_by", receptionist)
                .containsEntry("total_amount", 150_000L);
        assertThat((String) order.get("code")).matches("DH-\\d{4}-\\d{5}");
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM order_lines WHERE id = ?", ref.orderLineId());
        assertThat(line).containsEntry("line_type", "SERVICE").containsEntry("service_id", examService)
                .containsEntry("quantity", 1).containsEntry("unit_price", 150_000L)
                .containsEntry("line_total", 150_000L).containsEntry("is_auto_generated", true)
                .containsEntry("added_by", receptionist).containsEntry("owner_account_id", receptionist);
        assertThat(line.get("description")).isEqualTo(
                jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, examService));
    }

    @Test
    void openForVisitGivesTheLineToTheAssignee() {
        LineRef ref = open(vet);

        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE id = ?", Long.class,
                ref.orderLineId())).isEqualTo(vet);
    }

    @Test
    void secondOrderForTheSameVisitIsRefusedAndNothingIsLeftBehind() {
        open(null);
        long before = jdbc.queryForObject("SELECT count(*) FROM orders WHERE visit_id = ?", Long.class, visit);

        assertThatThrownBy(() -> open(null)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(before).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE visit_id = ?", Long.class, visit))
                .isEqualTo(1);
    }

    @Test
    void consecutiveOrdersGetConsecutiveCodes() {
        LineRef first = open(null);
        long pet2 = newPet(customer);
        long visit2 = newVisit(branch, customer, pet2, "MEDICAL", "WAITING", null, receptionist);
        LineRef second = tx.execute(s -> visitOrders.openForVisit(visit2, branch, customer, examService, null,
                receptionist));

        String a = jdbc.queryForObject("SELECT code FROM orders WHERE id = ?", String.class, first.orderId());
        String b = jdbc.queryForObject("SELECT code FROM orders WHERE id = ?", String.class, second.orderId());
        assertThat(Integer.parseInt(b.substring(b.length() - 5))).isEqualTo(Integer.parseInt(a.substring(a.length() - 5)) + 1);
    }

    @Test
    void rollbackOfTheCallerLeavesNoOrderAndNoCodeGap() {
        String before = nextCodePreview();
        assertThatThrownBy(() -> tx.execute(s -> {
            visitOrders.openForVisit(visit, branch, customer, examService, null, receptionist);
            throw new IllegalStateException("visit thất bại sau khi mở Order");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE visit_id = ?", Long.class, visit)).isZero();
        assertThat(nextCodePreview()).isEqualTo(before);
    }

    /** Mã kế tiếp sẽ cấp, đọc trong transaction rồi hủy (bộ đếm khóa dòng nên không để lỗ). */
    private String nextCodePreview() {
        return tx.execute(s -> {
            LineRef ref = visitOrders.openForVisit(visit, branch, customer, examService, null, receptionist);
            String code = jdbc.queryForObject("SELECT code FROM orders WHERE id = ?", String.class, ref.orderId());
            s.setRollbackOnly();
            return code;
        });
    }

    // ---------------------------------------------------------------- dòng thuốc / vaccine, BR-BH-03

    @Test
    void drugAndVaccineLinesAreSnapshottedAndTotalFollows() {
        LineRef ref = open(vet);

        LineRef drugLine = tx.execute(s -> visitOrders.addDrugLine(visit, drug, 3, vet));
        LineRef vaccineLine = tx.execute(s -> visitOrders.addVaccineLine(visit, vaccine, vet));

        assertThat(drugLine.orderId()).isEqualTo(ref.orderId());
        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000 + 90_000 + 200_000);
        assertThat(orderTotal(ref.orderId())).isEqualTo(sumOfLines(ref.orderId()));
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM order_lines WHERE id = ?", drugLine.orderLineId());
        assertThat(d).containsEntry("line_type", "DRUG").containsEntry("product_id", drug)
                .containsEntry("quantity", 3).containsEntry("unit_price", 30_000L)
                .containsEntry("owner_account_id", vet).containsEntry("is_auto_generated", false);
        Map<String, Object> v = jdbc.queryForMap("SELECT * FROM order_lines WHERE id = ?", vaccineLine.orderLineId());
        assertThat(v).containsEntry("line_type", "VACCINE").containsEntry("quantity", 1);
    }

    @Test
    void priceChangeAfterAddingDoesNotTouchExistingLinesButNewLinesUseTheNewPrice() {
        LineRef ref = open(vet);
        LineRef first = tx.execute(s -> visitOrders.addDrugLine(visit, drug, 2, vet));

        jdbc.update("UPDATE products SET price = 99000 WHERE id = ?", drug);
        jdbc.update("UPDATE services SET price = 1 WHERE id = ?", examService);
        LineRef second = tx.execute(s -> visitOrders.addDrugLine(visit, drug, 1, vet));

        assertThat(unitPrice(first.orderLineId())).as("BR-BH-03: dòng cũ giữ giá").isEqualTo(30_000);
        assertThat(unitPrice(ref.orderLineId())).isEqualTo(150_000);
        assertThat(unitPrice(second.orderLineId())).isEqualTo(99_000);
        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000 + 60_000 + 99_000);
    }

    private long unitPrice(long lineId) {
        return jdbc.queryForObject("SELECT unit_price FROM order_lines WHERE id = ?", Long.class, lineId);
    }

    @Test
    void removeDrugLineByOwnerOnlyAndOnlyWhileOpen() {
        LineRef ref = open(vet);
        LineRef drugLine = tx.execute(s -> visitOrders.addDrugLine(visit, drug, 2, vet));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> visitOrders.removeDrugLine(drugLine.orderLineId(), otherVet)))
                .isInstanceOf(OrderLineAccessDeniedException.class);
        assertThat(sumOfLines(ref.orderId())).isEqualTo(210_000);

        tx.executeWithoutResult(s -> visitOrders.removeDrugLine(drugLine.orderLineId(), vet));
        assertThat(sumOfLines(ref.orderId())).isEqualTo(150_000);
        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000);
    }

    @Test
    void removeVaccineLineDropsItAndItsAmount() {
        LineRef ref = open(vet);
        LineRef vaccineLine = tx.execute(s -> visitOrders.addVaccineLine(visit, vaccine, vet));

        tx.executeWithoutResult(s -> visitOrders.removeVaccineLine(vaccineLine.orderLineId()));

        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE id = ?", Long.class,
                vaccineLine.orderLineId())).isZero();
    }

    @Test
    void overflowRollsTheWholeCallBackAndKeepsTheOrderIntact() {
        LineRef ref = open(vet);
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", Long.MAX_VALUE / 2, drug);

        assertThatThrownBy(() -> tx.execute(s -> visitOrders.addDrugLine(visit, drug, 3, vet)))
                .isInstanceOf(OrderAmountOverflowException.class);

        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class,
                ref.orderId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- Visit#2, #4: chủ dòng

    @Test
    void assigningTheVisitMovesTheAutoLineAndReassigningMovesEveryLineOfTheOldOwner() {
        LineRef ref = open(null);
        tx.executeWithoutResult(s -> visitOrders.setAutoLineOwner(visit, vet));
        LineRef drugLine = tx.execute(s -> visitOrders.addDrugLine(visit, drug, 1, vet));
        assertThat(owner(ref.orderLineId())).isEqualTo(vet);

        tx.executeWithoutResult(s -> visitOrders.transferLineOwnership(visit, vet, otherVet));

        assertThat(owner(ref.orderLineId())).as("BR-TN-08").isEqualTo(otherVet);
        assertThat(owner(drugLine.orderLineId())).isEqualTo(otherVet);
        assertThat(jdbc.queryForObject("SELECT added_by FROM order_lines WHERE id = ?", Long.class,
                drugLine.orderLineId())).as("added_by không đổi").isEqualTo(vet);
    }

    private long owner(long lineId) {
        return jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE id = ?", Long.class, lineId);
    }

    // ---------------------------------------------------------------- Order#4, #6

    @Test
    void markPendingThenNoMoreEditsAndNoSecondPending() {
        LineRef ref = open(vet);

        tx.executeWithoutResult(s -> visitOrders.markPendingByVisit(visit));

        Map<String, Object> order = jdbc.queryForMap("SELECT status, pending_at FROM orders WHERE id = ?",
                ref.orderId());
        assertThat(order.get("status")).isEqualTo("PENDING");
        assertThat(order.get("pending_at")).isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> visitOrders.markPendingByVisit(visit)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> tx.execute(s -> visitOrders.addDrugLine(visit, drug, 1, vet)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> visitOrders.cancelByVisit(visit, receptionist)))
                .as("Order#6 chỉ từ OPEN").isInstanceOf(InvalidStateTransitionException.class);
        assertThat(orderStatus(ref.orderId())).isEqualTo("PENDING");
    }

    @Test
    void cancelByVisitRecordsVisitCancelledAndKeepsTheLines() {
        LineRef ref = open(null);

        tx.executeWithoutResult(s -> visitOrders.cancelByVisit(visit, receptionist));

        Map<String, Object> order = jdbc.queryForMap(
                "SELECT status, cancel_type, cancelled_by, cancelled_at, cancel_reason FROM orders WHERE id = ?",
                ref.orderId());
        assertThat(order).containsEntry("status", "CANCELLED").containsEntry("cancel_type", "VISIT_CANCELLED")
                .containsEntry("cancelled_by", receptionist).containsEntry("cancel_reason", null);
        assertThat(order.get("cancelled_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class,
                ref.orderId())).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> visitOrders.markPendingByVisit(visit)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void findServiceIdsListsServiceLinesOnly() {
        open(vet);
        tx.execute(s -> visitOrders.addDrugLine(visit, drug, 1, vet));

        List<Long> serviceIds = tx.execute(s -> visitOrders.findServiceIds(visit));

        assertThat(serviceIds).containsExactly(examService);
    }

    @Test
    void everyMethodRefusesToRunWithoutTheCallersTransaction() {
        assertThatThrownBy(() -> visitOrders.markPendingByVisit(visit)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> visitOrders.openForVisit(visit, branch, customer, examService, null, receptionist))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- đồng thời

    @Test
    void parallelDrugLinesNeverLoseAnUpdateOfTheTotal() throws Exception {
        LineRef ref = open(vet);
        int threads = 12;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<LineRef>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int quantity = i + 1;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return tx.execute(s -> visitOrders.addDrugLine(visit, drug, quantity, vet));
                }));
            }
            for (Future<LineRef> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        long expected = 150_000 + 30_000L * (threads * (threads + 1) / 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class,
                ref.orderId())).isEqualTo(threads + 1L);
        assertThat(orderTotal(ref.orderId())).isEqualTo(expected).isEqualTo(sumOfLines(ref.orderId()));
    }

    @Test
    void addingWhileTheOrderIsBeingClosedWaitsThenSeesTheNewStatus() throws Exception {
        LineRef ref = open(vet);

        List<Callable<Object>> actions = List.of(
                () -> tx.execute(s -> visitOrders.addDrugLine(visit, drug, 1, vet)),
                () -> tx.execute(s -> visitOrders.addVaccineLine(visit, vaccine, vet)));
        List<Outcome> outcomes = whileOrderLocked(ref.orderId(),
                () -> jdbc.update("UPDATE orders SET status = 'PENDING', pending_at = now() WHERE id = ?",
                        ref.orderId()),
                actions);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isInstanceOf(InvalidStateTransitionException.class));
        assertThat(sumOfLines(ref.orderId())).as("không dòng nào lọt vào Order đã PENDING").isEqualTo(150_000);
        assertThat(orderTotal(ref.orderId())).isEqualTo(150_000);
    }

    @Test
    void cancelByVisitWaitingBehindALockerSeesTheFinalStatus() throws Exception {
        LineRef ref = open(vet);

        List<Outcome> outcomes = whileOrderLocked(ref.orderId(),
                () -> jdbc.update("UPDATE orders SET status = 'PENDING', pending_at = now() WHERE id = ?",
                        ref.orderId()),
                List.of(() -> {
                    tx.executeWithoutResult(s -> visitOrders.cancelByVisit(visit, receptionist));
                    return null;
                }));

        assertThat(outcomes.get(0).error()).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(orderStatus(ref.orderId())).isEqualTo("PENDING");
    }
}
