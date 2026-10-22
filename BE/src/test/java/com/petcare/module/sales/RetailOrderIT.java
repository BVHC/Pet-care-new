package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

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
import com.petcare.module.sales.api.VisitOrderApi;

/**
 * Bán lẻ tại quầy (sales-v1 #1, #2, #7, #8, #10; Order#2, #5, #7, #10; BR-BH-01, 04, 05, 06, BR-SP-01; docs/adr/0051)
 * trên Postgres 17 thật qua HTTP: hình dạng hợp đồng, ma trận vai trò / chi nhánh, chốt rỗng, hủy chưa chốt, hủy khách
 * không thanh toán (lý do, audit, kho không đổi, Order lưu trú khi thú còn trong chuồng), danh sách và các race trên
 * khóa dòng {@code orders}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RetailOrderIT extends SalesItSupport {

    @Autowired
    private VisitOrderApi visitOrders;

    private final List<Long> bookings = new ArrayList<>();
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long vet;
    private long customer;
    private long otherCustomer;
    private long goods;
    private long goodsLot;
    private long prescriptionDrug;
    private long vaccineProduct;
    private String tReceptionist;
    private String tOtherReceptionist;
    private String tVet;
    private String tCaretaker;
    private String tManager;
    private String tAdmin;
    private String tOutsider;
    private String tOutsideManager;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = staff("RECEPTIONIST", branch);
        manager = staff("BRANCH_MANAGER", branch);
        tReceptionist = token(receptionist);
        tOtherReceptionist = token(staff("RECEPTIONIST", branch));
        vet = staff("VET", branch);
        tVet = token(vet);
        tCaretaker = token(staff("CARETAKER", branch));
        tManager = token(manager);
        tAdmin = token(staff("ADMIN", null));
        tOutsider = token(staff("RECEPTIONIST", otherBranch));
        tOutsideManager = token(staff("BRANCH_MANAGER", otherBranch));

        customer = newCustomer();
        otherCustomer = newCustomer();
        goods = newPricedProduct("GOODS", false, 40_000);
        prescriptionDrug = newPricedProduct("DRUG", true, 30_000);
        vaccineProduct = newPricedProduct("VACCINE", false, 200_000);
        goodsLot = defaultLot(branch, goods, 2, receptionist);
    }

    @AfterEach
    void cleanBookings() {
        if (!bookings.isEmpty()) {
            String ids = String.join(", ", bookings.stream().map(String::valueOf).toList());
            jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE boarding_booking_id IN ("
                    + ids + "))");
            jdbc.update("DELETE FROM orders WHERE boarding_booking_id IN (" + ids + ")");
            jdbc.update("DELETE FROM boarding_bookings WHERE id IN (" + ids + ")");
            bookings.clear();
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<Map<String, Object>> create(String token, Object customerId) {
        return call(HttpMethod.POST, "/api/orders", token, Map.of("customerId", customerId));
    }

    private long createAsReceptionist() {
        return number(data(create(tReceptionist, customer), HttpStatus.CREATED).get("orderId"));
    }

    private ResponseEntity<Map<String, Object>> addGoods(String token, long orderId, long productId, int quantity) {
        return call(HttpMethod.POST, "/api/orders/" + orderId + "/lines", token,
                Map.of("lineType", "GOODS", "productId", productId, "quantity", quantity));
    }

    private ResponseEntity<Map<String, Object>> post(String token, long orderId, String action, Object body) {
        return call(HttpMethod.POST, "/api/orders/" + orderId + "/" + action, token, body);
    }

    private long pendingRetailOrder() {
        long orderId = createAsReceptionist();
        data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED);
        data(post(tReceptionist, orderId, "close", null), HttpStatus.OK);
        return orderId;
    }

    private long auditRows(long orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ORDER_CANCELLED_UNPAID' "
                + "AND entity_type = 'orders' AND entity_id = ?", Long.class, orderId);
    }

    /**
     * Đặt chỗ lưu trú + Order BOARDING PENDING; FK tới loại chuồng / chuồng bỏ qua bằng {@code replica}. Mỗi lần một
     * khoảng ngày và một chuồng giả riêng ({@code uq_boarding_bookings_kennel_in_use}, EXCLUDE theo thú).
     */
    private long pendingBoardingOrder(String bookingStatus) {
        int offset = 10 * bookings.size();
        long pet = newPet(customer);
        long booking = tx.execute(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            return jdbc.queryForObject("""
                    INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                                                   check_out_date, nightly_price, status, channel, booked_by, kennel_id,
                                                   end_reason)
                    VALUES (?, ?, ?, ?, 999999, current_date - 3 - ?, current_date - ?, 100000, ?, 'COUNTER', ?,
                            CASE WHEN ? IN ('CHECKED_IN', 'OVERDUE', 'CHECKED_OUT') THEN ?::bigint END,
                            CASE WHEN ? = 'CHECKED_OUT' THEN 'PET_DECEASED' END)
                    RETURNING id
                    """, Long.class, "B-" + UUID.randomUUID().toString().substring(0, 12), customer, pet, branch,
                    offset, offset, bookingStatus, receptionist, bookingStatus, 800_000L + SEQ.incrementAndGet(), bookingStatus);
        });
        bookings.add(booking);
        return jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, boarding_booking_id, status, total_amount,
                                    created_by, pending_at)
                VALUES (?, 'BOARDING', ?, ?, ?, 'PENDING', 300000, ?, now()) RETURNING id
                """, Long.class, "DH-T-" + UUID.randomUUID().toString().substring(0, 10), customer, branch, booking,
                receptionist);
    }

    // ---------------------------------------------------------------- #1 tạo

    @Test
    void createReturnsAnEmptyOpenRetailOrderInTheContractShape() {
        Map<String, Object> order = data(create(tReceptionist, customer), HttpStatus.CREATED);

        assertThat(order).containsOnlyKeys("orderId", "code", "source", "status", "customerId", "customerName",
                "branchId", "visitId", "boardingBookingId", "totalAmount", "lines", "createdAt", "pendingAt", "paidAt",
                "paymentId", "cancelType", "cancelReason", "cancelledAt", "deliveryAllowed");
        assertThat(order).containsEntry("source", "RETAIL").containsEntry("status", "OPEN")
                .containsEntry("customerId", (int) customer).containsEntry("branchId", (int) branch)
                .containsEntry("totalAmount", 0).containsEntry("deliveryAllowed", false)
                .containsEntry("visitId", null).containsEntry("boardingBookingId", null)
                .containsEntry("pendingAt", null).containsEntry("cancelType", null);
        assertThat((List<?>) order.get("lines")).isEmpty();
        assertThat((String) order.get("code")).matches("DH-\\d{4}-\\d{5}");
        assertThat((String) order.get("customerName")).startsWith("Khách sales IT");
        long id = number(order.get("orderId"));
        assertThat(jdbc.queryForMap("SELECT source, status, created_by, visit_id FROM orders WHERE id = ?", id))
                .containsEntry("source", "RETAIL").containsEntry("status", "OPEN")
                .containsEntry("created_by", receptionist).containsEntry("visit_id", null);
    }

    @Test
    void createValidatesTheCustomerAndTheRole() {
        assertError(create(tReceptionist, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(callRaw(HttpMethod.POST, "/api/orders", tReceptionist, "{}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, "/api/orders", tReceptionist, "{\"customerId\": 1.5}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        for (String token : List.of(tVet, tCaretaker, tManager, tAdmin)) {
            assertError(create(token, customer), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = ?", Long.class, customer))
                .isZero();
    }

    // ---------------------------------------------------------------- luồng bán lẻ

    @Test
    void sellWarnOnShortStockBlockPrescriptionCloseAndKeepStockUntilPaid() {
        long orderId = createAsReceptionist();

        Map<String, Object> added = data(addGoods(tReceptionist, orderId, goods, 3), HttpStatus.CREATED);
        assertThat(map(added.get("stockWarning"))).containsEntry("productId", (int) goods)
                .containsEntry("requested", 3).containsEntry("available", 2);
        assertRule(addGoods(tReceptionist, orderId, prescriptionDrug, 1), "BR-SP-01");
        assertRule(addGoods(tReceptionist, orderId, vaccineProduct, 1), "BR-KB-04");

        Map<String, Object> closed = data(post(tOtherReceptionist, orderId, "close", null), HttpStatus.OK);
        assertThat(closed).containsEntry("status", "PENDING").containsEntry("totalAmount", 120_000)
                .containsEntry("deliveryAllowed", false);
        assertThat(closed.get("pendingAt")).isNotNull();

        Map<String, Object> addedWhilePending = data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED);
        assertThat(map(addedWhilePending.get("order"))).containsEntry("status", "PENDING")
                .containsEntry("totalAmount", 160_000);
        assertThat(lotQuantity(goodsLot)).as("BR-BH-04: chỉ trừ kho khi PAID").isEqualTo(2);
        assertThat(movementCount(goodsLot)).isEqualTo(1);
    }

    @Test
    void closeNeedsAtLeastOneLineEvenAfterTheLastWasRemoved() {
        long orderId = createAsReceptionist();
        assertRule(post(tReceptionist, orderId, "close", null), "BR-BH-01");

        long lineId = number(data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED).get("lineId"));
        data(call(HttpMethod.DELETE, "/api/orders/" + orderId + "/lines/" + lineId, tReceptionist, null),
                HttpStatus.OK);
        assertRule(post(tReceptionist, orderId, "close", null), "BR-BH-01");
        assertThat(orderStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    void closeAndCancelOnlyApplyToOpenRetailOrders() {
        long pending = pendingRetailOrder();
        assertError(post(tReceptionist, pending, "close", null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(post(tReceptionist, pending, "cancel", null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        long service = newService("MEDICAL", 150_000);
        enableService(branch, service);
        long visit = newVisit(branch, customer, newPet(customer), "MEDICAL", "IN_PROGRESS", vet, receptionist);
        long visitOrder = tx.execute(s -> visitOrders.openForVisit(visit, branch, customer, service, vet,
                receptionist)).orderId();
        assertError(post(tReceptionist, visitOrder, "close", null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(post(tReceptionist, visitOrder, "cancel", null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(orderStatus(visitOrder)).isEqualTo("OPEN");
    }

    @Test
    void cancelUnclosedOrderKeepsItsLinesAndFreezesIt() {
        long orderId = createAsReceptionist();
        data(addGoods(tReceptionist, orderId, goods, 2), HttpStatus.CREATED);

        Map<String, Object> cancelled = data(post(tOtherReceptionist, orderId, "cancel", null), HttpStatus.OK);

        assertThat(cancelled).containsEntry("status", "CANCELLED").containsEntry("cancelType", "RETAIL_UNCLOSED")
                .containsEntry("cancelReason", null).containsEntry("totalAmount", 80_000);
        assertThat((List<?>) cancelled.get("lines")).hasSize(1);
        assertError(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(post(tReceptionist, orderId, "cancel", null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(auditRows(orderId)).as("hủy chưa chốt không ghi audit").isZero();
    }

    @Test
    void otherBranchAndOtherRolesCannotTouchTheOrder() {
        long orderId = createAsReceptionist();
        data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED);

        assertError(post(tOutsider, orderId, "close", null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(post(tOutsider, orderId, "cancel", null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        for (String token : List.of(tVet, tCaretaker, tManager, tAdmin)) {
            assertError(post(token, orderId, "close", null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(post(token, orderId, "cancel", null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(post(tReceptionist, Long.MAX_VALUE, "close", null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(orderStatus(orderId)).isEqualTo("OPEN");
    }

    // ---------------------------------------------------------------- #10 hủy khách không thanh toán

    @Test
    void managerCancelsUnpaidOrderWithReasonAuditAndNoStockChange() {
        long orderId = pendingRetailOrder();
        int stockBefore = lotQuantity(goodsLot);

        Map<String, Object> cancelled = data(post(tManager, orderId, "cancel-unpaid",
                Map.of("reason", "  Khách bỏ về không thanh toán  ")), HttpStatus.OK);

        assertThat(cancelled).containsEntry("status", "CANCELLED").containsEntry("cancelType", "UNPAID")
                .containsEntry("cancelReason", "Khách bỏ về không thanh toán").containsEntry("deliveryAllowed", false);
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", Long.class, orderId))
                .isEqualTo(manager);
        assertThat(lotQuantity(goodsLot)).isEqualTo(stockBefore);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data::text AS before, after_data::text AS after
                FROM audit_logs WHERE action = 'ORDER_CANCELLED_UNPAID' AND entity_id = ?""", orderId);
        assertThat(audit).containsEntry("actor_account_id", manager)
                .containsEntry("reason", "Khách bỏ về không thanh toán");
        assertThat((String) audit.get("before")).contains("\"status\": \"PENDING\"");
        assertThat((String) audit.get("after")).contains("\"cancelType\": \"UNPAID\"");

        assertError(post(tManager, orderId, "cancel-unpaid", Map.of("reason", "lần hai")), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertThat(auditRows(orderId)).isEqualTo(1);
    }

    @Test
    void cancelUnpaidChecksRoleBranchStateAndReason() {
        long open = createAsReceptionist();
        long pending = pendingRetailOrder();

        for (String token : List.of(tReceptionist, tVet, tCaretaker, tAdmin)) {
            assertError(post(token, pending, "cancel-unpaid", Map.of("reason", "x")), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        assertError(post(tOutsideManager, pending, "cancel-unpaid", Map.of("reason", "x")), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(post(tManager, open, "cancel-unpaid", Map.of("reason", "x")), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertRule(post(tManager, pending, "cancel-unpaid", Map.of("reason", "   ")), "BR-BH-05");
        assertRule(post(tManager, pending, "cancel-unpaid", Map.of()), "BR-BH-05");
        assertError(post(tManager, pending, "cancel-unpaid", Map.of("reason", "x".repeat(301))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, "/api/orders/" + pending + "/cancel-unpaid", tManager, ""),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(orderStatus(pending)).isEqualTo("PENDING");
        assertThat(auditRows(pending)).isZero();
    }

    @Test
    void visitOrderCancelledAsUnpaidLeavesTheVisitAlone() {
        long service = newService("MEDICAL", 150_000);
        enableService(branch, service);
        long visit = newVisit(branch, customer, newPet(customer), "MEDICAL", "COMPLETED", vet, receptionist);
        long orderId = tx.execute(s -> {
            long id = visitOrders.openForVisit(visit, branch, customer, service, vet, receptionist).orderId();
            visitOrders.markPendingByVisit(visit);
            return id;
        });

        data(post(tManager, orderId, "cancel-unpaid", Map.of("reason", "Khám xong bỏ về")), HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT cancel_type FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit))
                .isEqualTo("COMPLETED");
    }

    @Test
    void boardingOrderCanOnlyBeCancelledAsUnpaidAfterThePetLeft() {
        long inKennel = pendingBoardingOrder("CHECKED_IN");
        long overdue = pendingBoardingOrder("OVERDUE");
        long left = pendingBoardingOrder("CHECKED_OUT");

        assertRule(post(tManager, inKennel, "cancel-unpaid", Map.of("reason", "x")), "BR-BH-05");
        assertRule(post(tManager, overdue, "cancel-unpaid", Map.of("reason", "x")), "BR-BH-05");
        data(post(tManager, left, "cancel-unpaid", Map.of("reason", "Thú mất, khách không trả")), HttpStatus.OK);

        assertThat(orderStatus(inKennel)).isEqualTo("PENDING");
        assertThat(orderStatus(overdue)).isEqualTo("PENDING");
        assertThat(orderStatus(left)).isEqualTo("CANCELLED");
    }

    // ---------------------------------------------------------------- race trên khóa orders

    @Test
    void closeWaitingBehindTheRemovalOfTheLastLineIsRejected() throws Exception {
        long orderId = createAsReceptionist();
        data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED);
        List<Callable<Object>> actions = List.of(() -> post(tReceptionist, orderId, "close", null));

        List<Outcome> outcomes = whileOrderLocked(orderId, () -> {
            jdbc.update("DELETE FROM order_lines WHERE order_id = ?", orderId);
            jdbc.update("UPDATE orders SET total_amount = 0 WHERE id = ?", orderId);
        }, actions);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) outcomes.get(0).value();
        assertRule(response, "BR-BH-01");
        assertThat(orderStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    void concurrentCloseAndCancelExactlyOneWins() throws Exception {
        long orderId = createAsReceptionist();
        data(addGoods(tReceptionist, orderId, goods, 1), HttpStatus.CREATED);
        List<Callable<Object>> actions = List.of(() -> post(tReceptionist, orderId, "close", null),
                () -> post(tOtherReceptionist, orderId, "cancel", null));

        List<Outcome> outcomes = whileOrderLocked(orderId, () -> { }, actions);

        List<HttpStatus> statuses = outcomes.stream().map(o -> {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) o.value();
            return HttpStatus.valueOf(r.getStatusCode().value());
        }).toList();
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(orderStatus(orderId)).isIn("PENDING", "CANCELLED");
    }

    @Test
    void doubleCancelUnpaidWritesOneAuditRow() throws Exception {
        long orderId = pendingRetailOrder();
        List<Callable<Object>> actions = List.of(
                () -> post(tManager, orderId, "cancel-unpaid", Map.of("reason", "một")),
                () -> post(tManager, orderId, "cancel-unpaid", Map.of("reason", "hai")));

        List<Outcome> outcomes = whileOrderLocked(orderId, () -> { }, actions);

        long ok = outcomes.stream().filter(o -> {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) o.value();
            return r.getStatusCode().value() == 200;
        }).count();
        assertThat(ok).isEqualTo(1);
        assertThat(auditRows(orderId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- #2 danh sách

    @Test
    void listShowsOnlyOwnBranchWithFiltersAndNames() {
        long first = createAsReceptionist();
        long pending = pendingRetailOrder();
        long other = number(data(create(tReceptionist, otherCustomer), HttpStatus.CREATED).get("orderId"));
        long outside = number(data(create(tOutsider, customer), HttpStatus.CREATED).get("orderId"));

        List<Map<String, Object>> all = content(call(HttpMethod.GET, "/api/orders?customerId=" + customer, tVet,
                null));
        assertThat(all).extracting(m -> number(m.get("orderId"))).containsExactly(pending, first)
                .doesNotContain(outside, other);
        assertThat(all.get(0)).containsOnlyKeys("orderId", "code", "source", "status", "customerId", "customerName",
                "branchId", "totalAmount", "createdAt", "pendingAt", "paidAt")
                .containsEntry("status", "PENDING").containsEntry("totalAmount", 40_000);
        assertThat((String) all.get(0).get("customerName")).startsWith("Khách sales IT");

        assertThat(content(call(HttpMethod.GET, "/api/orders?status=PENDING&source=RETAIL&customerId=" + customer,
                tManager, null))).extracting(m -> number(m.get("orderId"))).containsExactly(pending);
        assertThat(content(call(HttpMethod.GET, "/api/orders?source=VISIT&customerId=" + customer, tCaretaker, null)))
                .isEmpty();
        String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString();
        assertThat(content(call(HttpMethod.GET, "/api/orders?date=" + today + "&customerId=" + otherCustomer,
                tReceptionist, null))).extracting(m -> number(m.get("orderId"))).containsExactly(other);
        assertThat(content(call(HttpMethod.GET, "/api/orders?date=2001-01-01&customerId=" + otherCustomer,
                tReceptionist, null))).isEmpty();
        assertThat(content(call(HttpMethod.GET, "/api/orders?customerId=" + customer, tOutsider, null)))
                .extracting(m -> number(m.get("orderId"))).containsExactly(outside);
    }

    @Test
    void listPendingOverdueIsTheOldestClosedBeforeTodayFirst() {
        long fresh = pendingRetailOrder();
        long older = pendingRetailOrder();
        long oldest = pendingRetailOrder();
        Instant midnight = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant();
        jdbc.update("UPDATE orders SET pending_at = ? WHERE id = ?", java.sql.Timestamp.from(midnight.minusSeconds(1)),
                older);
        jdbc.update("UPDATE orders SET pending_at = ? WHERE id = ?",
                java.sql.Timestamp.from(midnight.minusSeconds(86_400 * 3)), oldest);

        List<Map<String, Object>> overdue = content(call(HttpMethod.GET,
                "/api/orders?pendingOverdue=true&customerId=" + customer, tManager, null));

        assertThat(overdue).extracting(m -> number(m.get("orderId"))).containsExactly(oldest, older)
                .doesNotContain(fresh);
        assertThat(content(call(HttpMethod.GET, "/api/orders?pendingOverdue=false&customerId=" + customer, tManager,
                null))).hasSize(3);
    }

    @Test
    void listRejectsStaffOutsideABranchAndBadParameters() {
        assertError(call(HttpMethod.GET, "/api/orders", tAdmin, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, "/api/orders?status=DONE", tManager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(call(HttpMethod.GET, "/api/orders?date=19-10-2026", tManager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        Map<String, Object> page = data(call(HttpMethod.GET, "/api/orders?size=1000&page=-1", tManager, null),
                HttpStatus.OK);
        assertThat(page).containsEntry("size", 100).containsEntry("page", 0);
    }

    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) data(response, HttpStatus.OK).get("content");
        return content;
    }
}
