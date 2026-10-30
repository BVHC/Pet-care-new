package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.support.MutableClock;

/**
 * T45 — các chuyển của bảng "5. Order" (docs/03-state-machines.md) có nguồn {@code VISIT} và {@code RETAIL}: #1, #4, #6
 * (Visit#1, #5, #6), #2, #5, #7 (bán lẻ), #8 (thu tiền gộp), #10 (hủy Order khách không thanh toán), cùng cặp sai có
 * đường HTTP → 409 và không ghi gì, <b>không stand-in nào</b>. Order {@code BOARDING} (#3, #9, #10) cần đặt chỗ lưu
 * trú nên được kiểm trong {@code boarding/BoardingBookingFsmIT}. Lượt khám luôn có lịch hẹn đặt qua
 * {@code POST /api/appointments}; trạng thái nguồn của Order đi bằng API. Era <b>năm 2033</b> (không job nào quét tới);
 * chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OrderFsmIT.TestBeans.class})
class OrderFsmIT extends CashierItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2033, 3, 15);
    private static final long PRICE = 100_000;
    private static final int STOCK = 100;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(8, 30));
        }
    }

    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private long branch;
    private long medical;
    private long receptionist;
    private long vet;
    private long manager;
    private long customer;
    private long goods;
    private long lot;
    private String receptionistToken;
    private int nextSlot;
    private String vetToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        nextSlot = 0;
        clock.set(at(8, 30));
        data = new AppointmentItSupport(jdbc);
        branch = newBranch();
        data.openingHours(branch, LocalDate.of(2033, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        medical = data.service("MEDICAL");
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        customer = data.counterCustomer();
        receptionistToken = token(receptionist);
        vetToken = token(vet);
        managerToken = token(manager);
        goods = newPricedProduct("GOODS", false, PRICE);
        lot = defaultLot(branch, goods, STOCK, manager);
        data(call(HttpMethod.POST, "/api/cashier-shifts", receptionistToken, Map.of("afterHours", false)),
                HttpStatus.CREATED);
    }

    @AfterEach
    void tearDown() {
        // ca / phiếu thu / Order trỏ tới tài khoản, lượt khám và chi nhánh (FK RESTRICT): xóa trước khi dọn dữ liệu nền
        jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM orders WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM payments WHERE branch_id = ?", branch);
        jdbc.update("""
                DELETE FROM stock_movements WHERE stock_lot_id IN (SELECT l.id FROM stock_lots l
                JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id = ?)""", branch);
        jdbc.update("DELETE FROM cashier_shifts WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                branch);
        jdbc.update("DELETE FROM branch_services WHERE branch_id = ?", branch);
        data.cleanUp();
    }

    // ================================================================ chuyển hợp lệ — Order của Visit

    /** #1 — check-in: Order {@code VISIT} {@code OPEN} kèm một dòng tự sinh. */
    @Test
    void checkInOpensTheVisitOrderWithOneAutoLine() {
        long visit = checkedIn();

        assertThat(visitOrderStatus(visit)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? AND l.is_auto_generated""", Long.class, visit)).isOne();
    }

    /** #4 — hoàn tất lượt: Order {@code PENDING}; #6 — hủy lượt chưa gọi: Order {@code CANCELLED}. */
    @Test
    void completingPendsTheVisitOrderAndCancellingCancelsIt() {
        long completed = completed();
        long cancelled = checkedIn();
        data(post(receptionistToken, "/api/visits/" + cancelled + "/cancel", Map.of("reason", "Khách bỏ về")),
                HttpStatus.OK);

        assertThat(visitOrderStatus(completed)).isEqualTo("PENDING");
        assertThat(visitOrderStatus(cancelled)).isEqualTo("CANCELLED");
    }

    // ================================================================ chuyển hợp lệ — Order bán lẻ

    /** #2 — tạo Order bán lẻ: {@code OPEN}, chưa có dòng. */
    @Test
    void retailOrderOpensEmpty() {
        long order = retailOpen();

        assertThat(orderStatus(order)).isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM order_lines WHERE order_id = ?", order)).isZero();
    }

    /** #5 — chốt Order có ≥ 1 dòng: {@code PENDING}, tổng = Σ dòng, kho chưa đổi (chưa giao hàng). */
    @Test
    void closingARetailOrderWithLinesPendsIt() {
        long order = retailOpen();
        addGoods(order, 3);

        data(call(HttpMethod.POST, "/api/orders/" + order + "/close", receptionistToken, null), HttpStatus.OK);

        assertThat(orderStatus(order)).isEqualTo("PENDING");
        assertThat(orderTotal(order)).isEqualTo(3 * PRICE).isEqualTo(sumOfLines(order));
        assertThat(lotQuantity(lot)).isEqualTo(STOCK);
    }

    @Test
    void closingAnEmptyRetailOrderIs400() {
        long order = retailOpen();

        assertRule(call(HttpMethod.POST, "/api/orders/" + order + "/close", receptionistToken, null), "BR-BH-01");

        assertThat(orderStatus(order)).isEqualTo("OPEN");
    }

    /** #7 — hủy Order chưa chốt: {@code CANCELLED}, dòng giữ làm lịch sử, kho không đổi. */
    @Test
    void cancellingAnOpenRetailOrderKeepsItsLinesAndStock() {
        long order = retailOpen();
        addGoods(order, 2);

        data(call(HttpMethod.POST, "/api/orders/" + order + "/cancel", receptionistToken, null), HttpStatus.OK);

        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM order_lines WHERE order_id = ?", order)).isOne();
        assertThat(lotQuantity(lot)).isEqualTo(STOCK);
    }

    // ================================================================ #8 thu tiền, #10 hủy khách không thanh toán

    /** #8 — thu gộp Order bán lẻ và Order Visit của cùng khách: cả hai {@code PAID}, cùng phiếu thu, trừ kho đúng lô. */
    @Test
    void payingSeveralOrdersOfOneCustomerMarksThemPaidAndIssuesStock() {
        long retail = retailPending(2);
        long visit = completed();
        long visitOrder = visitOrder(visit);
        long amount = orderTotal(retail) + orderTotal(visitOrder);

        data(call(HttpMethod.POST, "/api/payments", receptionistToken,
                Map.of("orderIds", List.of(retail, visitOrder), "method", "CASH", "amount", amount)),
                HttpStatus.CREATED);

        assertThat(orderStatus(retail)).isEqualTo("PAID");
        assertThat(orderStatus(visitOrder)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT payment_id FROM orders WHERE id = ?", Long.class, retail))
                .isEqualTo(jdbc.queryForObject("SELECT payment_id FROM orders WHERE id = ?", Long.class, visitOrder));
        assertThat(lotQuantity(lot)).isEqualTo(STOCK - 2);
        assertThat(movementCount(lot)).isEqualTo(2); // phiếu nhập giả lập + xuất 2
        assertThat(orderTotal(retail)).isEqualTo(sumOfLines(retail));
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'ORDER_PAID' AND entity_id = ?", retail))
                .isOne();
    }

    /** #10 — BRANCH_MANAGER hủy Order khách không thanh toán (nguồn Visit và bán lẻ): kho, lịch hẹn không đổi, có audit. */
    @Test
    void managerCancelsUnpaidVisitAndRetailOrders() {
        long retail = retailPending(1);
        long visit = completed();
        long visitOrder = visitOrder(visit);

        data(call(HttpMethod.POST, "/api/orders/" + retail + "/cancel-unpaid", managerToken,
                Map.of("reason", "Khách không thanh toán")), HttpStatus.OK);
        data(call(HttpMethod.POST, "/api/orders/" + visitOrder + "/cancel-unpaid", managerToken,
                Map.of("reason", "Khách bỏ về")), HttpStatus.OK);

        assertThat(orderStatus(retail)).isEqualTo("CANCELLED");
        assertThat(orderStatus(visitOrder)).isEqualTo("CANCELLED");
        assertThat(lotQuantity(lot)).isEqualTo(STOCK);
        assertThat(jdbc.queryForObject("""
                SELECT a.status FROM appointments a JOIN visits v ON v.appointment_id = a.id WHERE v.id = ?""",
                String.class, visit)).isEqualTo("COMPLETED");
        assertThat(count("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_CANCELLED_UNPAID' AND entity_id = ?""", retail))
                .isOne();
    }

    @Test
    void cancellingUnpaidWithoutAReasonIs400AndWritesNothing() {
        long retail = retailPending(1);
        Map<String, Object> before = footprint(retail);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/orders/" + retail + "/cancel-unpaid",
                managerToken, Map.of("reason", "  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(footprint(retail)).isEqualTo(before);
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /**
     * Mỗi nguồn: các thao tác không có cạnh tương ứng trong 03. 409 đứng trước mọi 400 (ADR-0051 mục 3, ADR-0064 mục 4):
     * chốt Order Visit {@code OPEN} cũng 409 vì chốt chỉ dành cho Order bán lẻ.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RETAIL_OPEN", "VISIT_OPEN", "RETAIL_PENDING", "PAID", "CANCELLED"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long order = reach(source);
        Map<String, Object> before = footprint(order);

        List<String> tried = switch (source) {
            case "RETAIL_OPEN" -> List.of("cancel-unpaid", "payment");
            case "VISIT_OPEN" -> List.of("close", "cancel", "cancel-unpaid", "payment");
            case "RETAIL_PENDING" -> List.of("close", "cancel");
            default -> List.of("close", "cancel", "cancel-unpaid", "payment");
        };
        for (String operation : tried) {
            ResponseEntity<Map<String, Object>> response = attempt(operation, order);
            assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("statusCode", 409);
        }

        assertThat(footprint(order)).isEqualTo(before);
    }

    // ================================================================ helpers — luồng

    private long reach(String source) {
        return switch (source) {
            case "RETAIL_OPEN" -> {
                long order = retailOpen();
                addGoods(order, 1);
                yield order;
            }
            case "VISIT_OPEN" -> visitOrder(checkedIn());
            case "RETAIL_PENDING" -> retailPending(1);
            case "PAID" -> {
                long order = retailPending(1);
                pay(order);
                yield order;
            }
            case "CANCELLED" -> {
                long order = retailOpen();
                addGoods(order, 1);
                data(call(HttpMethod.POST, "/api/orders/" + order + "/cancel", receptionistToken, null),
                        HttpStatus.OK);
                yield order;
            }
            default -> throw new IllegalArgumentException(source);
        };
    }

    private ResponseEntity<Map<String, Object>> attempt(String operation, long order) {
        String path = "/api/orders/" + order + "/" + operation;
        return switch (operation) {
            case "close", "cancel" -> call(HttpMethod.POST, path, receptionistToken, null);
            case "cancel-unpaid" -> call(HttpMethod.POST, path, managerToken, Map.of("reason", "Thử"));
            case "payment" -> call(HttpMethod.POST, "/api/payments", receptionistToken,
                    Map.of("orderIds", List.of(order), "method", "CASH", "amount", PRICE));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private long retailOpen() {
        return number(data(call(HttpMethod.POST, "/api/orders", receptionistToken, Map.of("customerId", customer)),
                HttpStatus.CREATED).get("orderId"));
    }

    private void addGoods(long order, int quantity) {
        data(call(HttpMethod.POST, "/api/orders/" + order + "/lines", receptionistToken,
                Map.of("lineType", "GOODS", "productId", goods, "quantity", quantity)), HttpStatus.CREATED);
    }

    private long retailPending(int quantity) {
        long order = retailOpen();
        addGoods(order, quantity);
        data(call(HttpMethod.POST, "/api/orders/" + order + "/close", receptionistToken, null), HttpStatus.OK);
        return order;
    }

    private void pay(long order) {
        data(call(HttpMethod.POST, "/api/payments", receptionistToken,
                Map.of("orderIds", List.of(order), "method", "CASH", "amount", orderTotal(order))), HttpStatus.CREATED);
    }

    /**
     * Lễ tân đặt một khung riêng (09:30, 10:00, … — mỗi khung 1 chỗ, BR-LH-03) hôm nay lúc 08:30, rồi check-in trước
     * khung 20 phút (Visit#1).
     */
    private long checkedIn() {
        LocalTime slot = LocalTime.of(9, 30).plusMinutes(30L * nextSlot++);
        long pet = data.pet(customer);
        clock.set(at(8, 30));
        Map<String, Object> booking = new HashMap<>();
        booking.put("petId", pet);
        booking.put("branchId", branch);
        booking.put("serviceId", medical);
        booking.put("slotDate", TODAY.toString());
        booking.put("slotStart", slot.toString());
        long appointment = number(data(call(HttpMethod.POST, "/api/appointments", receptionistToken, booking),
                HttpStatus.CREATED).get("appointmentId"));
        clock.set(TODAY.atTime(slot.minusMinutes(20)).atZone(VN).toInstant());
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        return number(data(call(HttpMethod.POST, "/api/visits", receptionistToken, body), HttpStatus.CREATED)
                .get("visitId"));
    }

    /** Gán {@code vet}, gọi, ghi chẩn đoán (BR-KB-02), hoàn tất: Order {@code PENDING}. */
    private long completed() {
        long visit = checkedIn();
        data(post(receptionistToken, "/api/visits/" + visit + "/assign", Map.of("assigneeId", vet)), HttpStatus.OK);
        data(post(vetToken, "/api/visits/" + visit + "/call", null), HttpStatus.OK);
        data(call(HttpMethod.PUT, "/api/visits/" + visit + "/medical-record", vetToken,
                Map.of("examination", "Khám", "diagnosis", "Viêm da")), HttpStatus.OK);
        data(post(vetToken, "/api/visits/" + visit + "/complete", null), HttpStatus.OK);
        return visit;
    }

    private ResponseEntity<Map<String, Object>> post(String bearer, String path, Object body) {
        return call(HttpMethod.POST, path, bearer, body);
    }

    private long visitOrder(long visit) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visit);
    }

    private String visitOrderStatus(long visit) {
        return orderStatus(visitOrder(visit));
    }

    /** Dấu "không ghi gì": phiên bản dòng Order, số dòng, số phiếu thu của chi nhánh, tồn, số movement, audit của Order. */
    private Map<String, Object> footprint(long order) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("order", jdbc.queryForObject("SELECT xmin::text FROM orders WHERE id = ?", String.class, order));
        rows.put("lines", count("SELECT count(*) FROM order_lines WHERE order_id = ?", order));
        rows.put("payments", count("SELECT count(*) FROM payments WHERE branch_id = ?", branch));
        rows.put("stock", lotQuantity(lot));
        rows.put("movements", movementCount(lot));
        rows.put("audits", count("SELECT count(*) FROM audit_logs WHERE entity_type = 'orders' AND entity_id = ?",
                order));
        return rows;
    }

    private long count(String sql, long arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
