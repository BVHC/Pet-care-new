package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * Trả thú, hủy phiên trả thú, thú mất (boarding-v1 #16–18, sales-v1 #9; Đặt chỗ#9–#11, Order#3, #9; docs/adr/0086).
 * Era 2009: đặt chỗ nhận 08/06, trả dự kiến 12/06; mỗi test đặt đồng hồ rồi mở lại token (phiên theo đồng hồ).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
@SuppressWarnings("unchecked")
class BoardingCheckoutIT extends BoardingItSupport {

    private static final String NOON_PLUS = "2009-06-10T06:00:00Z";     // 13:00 VN, sau giờ trả quy định
    private static final String MORNING = "2009-06-10T03:00:00Z";       // 10:00 VN

    private long branch;
    private long type;
    private long kennel;
    private long receptionistId;
    private String receptionist;
    private long customerId;
    private long pet;
    private long stay;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        kennel = newKennel(branch, type, "C-01", "OCCUPIED");
        receptionistId = newStaff("RECEPTIONIST", branch);
        long[] online = newOnlineCustomer();
        customerId = online[1];
        pet = newPet(customerId, "DOG");
        stay = newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-08"), LocalDate.parse("2009-06-12"),
                "CHECKED_IN", kennel, receptionistId);
        at(MORNING);
    }

    /** Đặt đồng hồ rồi mở lại token (phiên hết hạn theo đồng hồ). */
    private void at(String instant) {
        clock.set(Instant.parse(instant));
        receptionist = token(receptionistId);
    }

    private ResponseEntity<Map<String, Object>> start(String token, long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/start-checkout", token, null);
    }

    private ResponseEntity<Map<String, Object>> handOver(String token, long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/hand-over", token, null);
    }

    private ResponseEntity<Map<String, Object>> deceased(String token, long id, String note) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/end-deceased", token, Map.of("note", note));
    }

    private ResponseEntity<Map<String, Object>> abort(String token, long orderId, String reason) {
        return call(HttpMethod.POST, "/api/orders/" + orderId + "/abort-checkout", token, Map.of("reason", reason));
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE boarding_booking_id = ?", Integer.class, stay);
    }

    private String kennelStatus() {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, kennel);
    }

    /** Ca thu ngân mở của lễ tân (thu tiền qua API cần ca). */
    private long openShift() {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, DATE '2009-06-10', 'OPEN', ?) RETURNING id""", Long.class, branch, receptionistId,
                java.sql.Timestamp.from(Instant.parse("2009-06-10T01:00:00Z")));
    }

    private void pay(long orderId, long amount) {
        openShift();
        Map<String, Object> body = Map.of("orderIds", List.of(orderId), "method", "CASH", "amount", amount);
        assertThat(call(HttpMethod.POST, "/api/payments", receptionist, body).getStatusCode().is2xxSuccessful())
                .isTrue();
    }

    // ---------------------------------------------------------------- bắt đầu trả thú (#16)

    @Test
    void startCheckoutCreatesPendingBoardingOrderAndShowsActiveOrder() {
        at(NOON_PLUS);

        Map<String, Object> data = data(start(receptionist, stay), HttpStatus.OK);

        assertThat(data).containsEntry("nights", 3).containsEntry("amount", 450_000);   // 08→10 = 2, +1 vì sau 12:00
        long orderId = number(data.get("orderId"));
        assertThat(jdbc.queryForMap("SELECT source, status, total_amount FROM orders WHERE id = ?", orderId))
                .containsEntry("source", "BOARDING").containsEntry("status", "PENDING")
                .containsEntry("total_amount", 450_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'BOARDING'"
                + " AND quantity = 3 AND unit_price = 150000", Integer.class, orderId)).isEqualTo(1);
        assertThat(number(((Map<String, Object>) data.get("booking")).get("activeOrderId"))).isEqualTo(orderId);
        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
    }

    @Test
    void secondStartReusesTheSameOrderAndRecomputesNights() {
        at(MORNING);
        long first = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        at(NOON_PLUS);

        Map<String, Object> second = data(start(receptionist, stay), HttpStatus.OK);

        assertThat(number(second.get("orderId"))).isEqualTo(first);
        assertThat(second).containsEntry("nights", 3);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void startOutsideOpeningHoursIsBrLt04() {
        at("2009-06-10T14:00:00Z");              // 21:00 VN

        assertRule(start(receptionist, stay), "BR-LT-04");
        assertThat(orderCount()).isZero();
    }

    @Test
    void startAfterPaymentIsConflictAndStillOneOrder() {
        long orderId = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        pay(orderId, 300_000);

        assertError(start(receptionist, stay), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void startCheckoutAfterConcurrentPaymentIsConflictAndCreatesNoSecondOrder() throws Exception {
        long orderId = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        long shift = openShift();
        long payment = jdbc.queryForObject("""
                INSERT INTO payments (code, branch_id, customer_id, cashier_shift_id, method, amount, received_by,
                                      paid_at)
                VALUES (?, ?, ?, ?, 'CASH', 300000, ?, now()) RETURNING id""", Long.class,
                "PT-IT-" + java.util.UUID.randomUUID().toString().substring(0, 12), branch, customerId, shift,
                receptionistId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR NO KEY UPDATE", Long.class, orderId);
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    jdbc.update("UPDATE orders SET status = 'PAID', payment_id = ?, paid_at = now() WHERE id = ?",
                            payment, orderId);
                }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ResponseEntity<Map<String, Object>>> second =
                CompletableFuture.supplyAsync(() -> start(receptionist, stay));
        for (int i = 0; i < 100 && jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                  AND query ILIKE '%from orders%for no key update%'""", Integer.class) == 0; i++) {
            Thread.sleep(100);
        }
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertError(second.get(30, TimeUnit.SECONDS), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(orderCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- giao thú (#17)

    @Test
    void handOverBeforePaymentIsBrLt09AndChangesNothing() {
        data(start(receptionist, stay), HttpStatus.OK);

        assertRule(handOver(receptionist, stay), "BR-LT-09");

        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus()).isEqualTo("OCCUPIED");
    }

    @Test
    void payThenHandOverChecksOutAndFreesKennel() {
        long orderId = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        pay(orderId, 300_000);

        Map<String, Object> data = data(handOver(receptionist, stay), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CHECKED_OUT").containsEntry("endReason", "RETURNED");
        assertThat(data.get("actualCheckOutAt")).isNotNull();
        assertThat(data.get("activeOrderId")).isNull();
        assertThat(kennelStatus()).isEqualTo("AVAILABLE");
        assertError(handOver(receptionist, stay), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    // ---------------------------------------------------------------- hủy phiên trả thú (sales #9)

    @Test
    void abortCheckoutKeepsStayAndNextStartCreatesNewOrder() {
        long first = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));

        Map<String, Object> aborted = data(abort(receptionist, first, "Khách chưa mang đủ tiền"), HttpStatus.OK);

        assertThat(aborted).containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForMap("SELECT cancel_type, cancel_reason FROM orders WHERE id = ?", first))
                .containsEntry("cancel_type", "CHECKOUT_ABORTED")
                .containsEntry("cancel_reason", "Khách chưa mang đủ tiền");
        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
        long second = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        assertThat(second).isNotEqualTo(first);
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    void abortCheckoutValidationAndRoles() {
        long orderId = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        String manager = token(newStaff("BRANCH_MANAGER", branch));
        String foreign = token(newStaff("RECEPTIONIST", newBranch()));

        assertRule(abort(receptionist, orderId, "   "), "BR-LT-09");
        assertError(abort(manager, orderId, "x"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(abort(foreign, orderId, "x"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(abort(receptionist, 999_999_999L, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PENDING");
    }

    @Test
    void abortCheckoutOfVisitOrPaidOrderIsConflict() {
        long orderId = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        pay(orderId, 300_000);
        assertError(abort(receptionist, orderId, "x"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        long retail = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, total_amount, created_by, pending_at)
                VALUES (?, 'RETAIL', ?, ?, 'PENDING', 0, ?, now()) RETURNING id""", Long.class,
                "DH-IT-" + java.util.UUID.randomUUID().toString().substring(0, 10), customerId, branch, receptionistId);
        assertError(abort(receptionist, retail, "x"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void abortCheckoutAfterDeceasedEndIsBrLt09() {
        Map<String, Object> ended = data(deceased(receptionist, stay, "Mất lúc 9h"), HttpStatus.OK);
        long orderId = number(ended.get("orderId"));

        assertRule(abort(receptionist, orderId, "x"), "BR-LT-09");
    }

    // ---------------------------------------------------------------- thú mất (#18)

    @Test
    void endDeceasedEndsStayChargesUntilTodayAndAllowsMarkDeceased() {
        at(NOON_PLUS);

        Map<String, Object> data = data(deceased(receptionist, stay, "  Mất lúc 9h  "), HttpStatus.OK);

        assertThat(data).containsEntry("nights", 2).containsEntry("amount", 300_000);   // không cộng đêm trễ
        Map<String, Object> booking = (Map<String, Object>) data.get("booking");
        assertThat(booking).containsEntry("status", "CHECKED_OUT").containsEntry("endReason", "PET_DECEASED");
        assertThat(jdbc.queryForObject("SELECT end_note FROM boarding_bookings WHERE id = ?", String.class, stay))
                .isEqualTo("Mất lúc 9h");
        assertThat(kennelStatus()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class,
                number(data.get("orderId")))).isEqualTo("PENDING");
        ResponseEntity<Map<String, Object>> mark = call(HttpMethod.POST, "/api/pets/" + pet + "/mark-deceased",
                receptionist, Map.of("deceasedOn", "2009-06-10", "confirmed", true));
        assertThat(mark.getStatusCode()).as("%s", mark.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void endDeceasedReusesPendingOrderAndPaidOrderIsReturnedAsIs() {
        long pending = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));
        pay(pending, 300_000);

        Map<String, Object> data = data(deceased(receptionist, stay, "Mất"), HttpStatus.OK);

        assertThat(number(data.get("orderId"))).isEqualTo(pending);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void endDeceasedReusesTheUnpaidPendingOrder() {
        long pending = number(data(start(receptionist, stay), HttpStatus.OK).get("orderId"));

        Map<String, Object> data = data(deceased(receptionist, stay, "Mất"), HttpStatus.OK);

        assertThat(number(data.get("orderId"))).isEqualTo(pending);
        assertThat(data).containsEntry("nights", 2);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void endDeceasedNoteIsRequiredAndBounded() {
        assertError(deceased(receptionist, stay, "  "), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(deceased(receptionist, stay, "x".repeat(2001)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
    }

    @Test
    void failureInsertingTheOrderRollsBackTheDeceasedEnd() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION checkout_it_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'checkout it forced failure'; END $$""");
        jdbc.execute("CREATE TRIGGER checkout_it_fail BEFORE INSERT ON orders FOR EACH ROW "
                + "EXECUTE FUNCTION checkout_it_fail()");
        try {
            ResponseEntity<Map<String, Object>> response = deceased(receptionist, stay, "Mất");

            assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
            assertThat(kennelStatus()).isEqualTo("OCCUPIED");
        } finally {
            jdbc.execute("DROP TRIGGER checkout_it_fail ON orders");
            jdbc.execute("DROP FUNCTION checkout_it_fail()");
        }
    }

    // ---------------------------------------------------------------- phân quyền và trạng thái

    @Test
    void authorizationAndState() {
        String caretaker = token(newStaff("CARETAKER", branch));
        String foreign = token(newStaff("RECEPTIONIST", newBranch()));
        long booked = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-20"),
                LocalDate.parse("2009-06-22"), "BOOKED", receptionistId);

        for (var call : List.<java.util.function.BiFunction<String, Long, ResponseEntity<Map<String, Object>>>>of(
                this::start, this::handOver, (t, id) -> deceased(t, id, "x"))) {
            assertError(call.apply(caretaker, stay), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call.apply(foreign, stay), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
            assertError(call.apply(receptionist, 999_999_999L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
        assertError(start(receptionist, booked), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(handOver(receptionist, booked), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(deceased(receptionist, booked, "x"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }
}
