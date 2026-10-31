package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.visit.job.FollowUpReminderJob;
import com.petcare.support.MutableClock;

/**
 * T41.3 — E2E nhánh khám theo kịch bản của docs/testcases/T41-be.md §9: khách đặt lịch khám (hủy nhắc tái khám chờ gửi
 * của bệnh án cũ) → lễ tân check-in → VET chẩn đoán, hẹn tái khám, kê một thuốc đủ tồn và một thuốc mua ngoài → hoàn tất
 * (khóa bệnh án) → lễ tân mở ca, thu tiền (trừ thuốc FEFO) → ST04 nhắc tái khám. Mọi bước qua endpoint / job thật,
 * <b>không stand-in, không {@code UPDATE} trạng thái</b>; chỉ dữ liệu nền chèn bằng SQL. Rollback của từng chuỗi đã có IT
 * riêng (T41-be.md §8, cùng {@code PrescriptionIT}, {@code FollowUpReminderIT}).
 * <p>
 * Era <b>năm 2030</b>, tương lai so với mọi era khác ({@code MainFlowE2EIT} giữ 2029): job không cận dưới của IT khác
 * chỉ quét tới đồng hồ của chúng, còn ST04 ở đây có cận dưới là hôm nay. Ngày khám {@code D} = 2030-03-13 (thứ Tư); chi
 * nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MainFlowExamIT.TestBeans.class})
class MainFlowExamIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate D = LocalDate.of(2030, 3, 13);
    private static final LocalDate FOLLOW_UP = D.plusDays(14);
    /** Giá dịch vụ ({@code AppointmentItSupport.service}), thuốc ({@code VaccinationItSupport.prescriptionDrug}). */
    private static final long S = 150_000;
    private static final long A = 30_000;
    private static final long TOTAL = S + 4 * A;
    private static final String EXAMINATION = "Nôn 2 lần, bụng chướng nhẹ, thân nhiệt 39°C";
    private static final String DIAGNOSIS = "Viêm dạ dày ruột cấp";
    private static final String TREATMENT = "Kháng sinh 5 ngày, men tiêu hóa, ăn cháo loãng";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(D.minusDays(3), 10, 0));
        }
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FollowUpReminderJob followUpJob;

    private AppointmentItSupport data;
    private VaccinationItSupport catalog;
    private TransactionTemplate tx;
    private long branch;
    private String branchName;
    private long service;
    private long receptionist;
    private long vet;
    private long account;
    private String accountEmail;
    private long customer;
    private long pet;
    private long drugA;
    private long drugB;
    private long lotA0;
    private long lotA1;
    private long lotA2;
    private long lotB0;
    private long oldVisit;
    private String customerToken;
    private String receptionistToken;
    private String vetToken;

    // trạng thái của mạch, ghi ở từng bước
    private Instant bookedAt;
    private String oldRecordXmin;
    private long appointment;
    private long visit;
    private long order;
    private long itemA;
    private long itemB;
    private long shift;
    private long payment;

    @BeforeEach
    void setUp() {
        clock.set(at(D.minusDays(3), 10, 0));
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        catalog = new VaccinationItSupport(jdbc);

        branch = data.branch("ACTIVE");
        branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        data.openingHours(branch, LocalDate.of(2030, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        service = data.service("MEDICAL");
        assertThat(jdbc.queryForObject("SELECT medical_type FROM services WHERE id = ?", String.class, service))
                .as("dịch vụ khám").isEqualTo("EXAM");
        data.branchService(branch, service, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);

        account = data.customerAccount();
        accountEmail = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, account);
        customer = data.onlineCustomer(account, "Khách E2E khám " + UUID.randomUUID().toString().substring(0, 8));
        pet = data.pet(customer);
        jdbc.update("UPDATE pets SET birth_date = ? WHERE id = ?", D.minusYears(2), pet);

        drugA = catalog.prescriptionDrug("Amoxicillin E2E");
        lotA0 = catalog.lot(branch, drugA, D.minusDays(1), 5);
        lotA1 = catalog.lot(branch, drugA, D.plusDays(200), 3);
        lotA2 = catalog.lot(branch, drugA, D.plusDays(30), 2);
        drugB = catalog.prescriptionDrug("Metronidazole E2E");
        lotB0 = catalog.lot(branch, drugB, D.minusDays(1), 5);

        // bệnh án cũ D−20 đã khóa, hẹn tái khám D+2, ST04 chưa nhắc (chưa vào cửa sổ 3 ngày)
        oldVisit = FollowUpItSupport.record(jdbc, branch, customer, pet, vet, at(D.minusDays(20), 9, 0),
                D.plusDays(2), true, false);

        openTokens();
    }

    @AfterEach
    void tearDown() {
        // dòng đơn thuốc trỏ tới dòng Order, bệnh án và thuốc; bệnh án trỏ tới lượt (FK RESTRICT)
        catalog.deletePrescriptionsOfPet(pet);
        FollowUpItSupport.cleanUp(jdbc, pet);
        jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                branch);
        jdbc.update("""
                DELETE FROM order_lines WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", branch);
        // orders.payment_id → payments, payments.cashier_shift_id → cashier_shifts (FK RESTRICT)
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM payments WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM cashier_shifts WHERE branch_id = ?", branch);
        // dòng EMAIL không có account id (ReminderChannels); dòng IN_APP do data.cleanUp() xóa theo tài khoản
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_email = ?", accountEmail);
        catalog.deleteDoses();
        catalog.deleteCatalog();
        data.cleanUp();

        for (String table : List.of("care_tasks", "visits", "appointments", "orders")) {
            String sql = table.equals("orders")
                    ? "SELECT count(*) FROM orders WHERE customer_id = ?"
                    : "SELECT count(*) FROM " + table + " WHERE pet_id = ?";
            assertThat(jdbc.queryForObject(sql, Long.class, table.equals("orders") ? customer : pet))
                    .as("còn sót ở %s", table).isZero();
        }
    }

    @Test
    void examFlowFromBookingToFollowUpReminder() {
        step01BookingCancelsTheOldPendingFollowUp();
        step02ReceptionChecksIn();
        step03ReceptionAssignsTheVet();
        step04VetCalls();
        step05CompletionWithoutDiagnosisIsRejected();
        step06VetWritesDiagnosisAndFollowUp();
        step07PrescriptionInStockAddsADrugLine();
        step08PrescriptionOutOfStockNeedsAChoiceThenGoesExternal();
        step09CompletionLocksTheRecord();
        step10ReceptionOpensAShift();
        step11PreviewShowsTheTotal();
        step12PaymentIssuesDrugFefo();
        step13FollowUpJobOutsideTheWindowWritesNothing();
        step14FollowUpJobSendsInAppAndEmail();
        step15FollowUpJobDoesNotResend();
        finalInvariants();
    }

    // ================================================================ bước

    /**
     * 1 — A02 đặt khung 09:00 ngày D lúc D−3 10:00 (Lịch hẹn#1); nhóm MEDICAL hủy nhắc tái khám chờ gửi của bệnh án đã
     * khóa cũ (Care Task#6, BR-TB-06; docs/adr/0084).
     */
    private void step01BookingCancelsTheOldPendingFollowUp() {
        bookedAt = clock.instant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("serviceId", service);
        body.put("slotDate", D.toString());
        body.put("slotStart", "09:00");
        appointment = id(created(post(customerToken, "/api/appointments", body), "bước 1"), "appointmentId");

        assertThat(jdbc.queryForMap("SELECT status, channel, service_group FROM appointments WHERE id = ?",
                appointment)).as("bước 1 lịch hẹn").containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                .containsEntry("service_group", "MEDICAL");
        assertThat(FollowUpItSupport.cancelledAt(jdbc, oldVisit)).as("bước 1 nhắc tái khám cũ bị hủy")
                .isEqualTo(bookedAt);
        assertThat(oldRemindedAt()).as("bước 1 nhắc cũ chưa gửi").isNull();
        assertThat(count("SELECT count(*) FROM care_tasks WHERE pet_id = ?", pet)).as("bước 1 Care Task").isZero();
        assertThat(customerOutbox()).as("bước 1 outbox").isZero();
        oldRecordXmin = xmin("medical_records", "visit_id", oldVisit);
    }

    /**
     * 2 — A06 check-in lúc D 08:40 (Visit#1 → Lịch hẹn#3, Order#1). Dịch vụ EXAM gọi lại {@code FollowUpApi}: nhắc cũ đã
     * hủy ở bước 1, nên bệnh án cũ không bị ghi lần nữa.
     */
    private void step02ReceptionChecksIn() {
        moveTo(at(D, 8, 40));
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        visit = id(created(post(receptionistToken, "/api/visits", body), "bước 2"), "visitId");
        order = jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visit);

        assertThat(appointmentStatus()).as("bước 2 lịch hẹn").isEqualTo("CHECKED_IN");
        assertThat(jdbc.queryForMap("SELECT status, priority_class, assignee_id FROM visits WHERE id = ?", visit))
                .as("bước 2 Visit").containsEntry("status", "WAITING").containsEntry("priority_class", "APPOINTMENT")
                .containsEntry("assignee_id", null);
        assertThat(jdbc.queryForMap("SELECT source, status, total_amount FROM orders WHERE id = ?", order))
                .as("bước 2 Order").containsEntry("source", "VISIT").containsEntry("status", "OPEN")
                .containsEntry("total_amount", S);
        assertThat(lines()).as("bước 2 dòng Order").extracting(l -> l.get("line_type"), l -> l.get("service_id"),
                        l -> l.get("unit_price"), l -> l.get("is_auto_generated"))
                .containsExactly(tuple("SERVICE", service, S, true));
        assertThat(xmin("medical_records", "visit_id", oldVisit)).as("bước 2 bệnh án cũ không đổi")
                .isEqualTo(oldRecordXmin);
        assertThat(lotQuantities()).as("bước 2 kho").isEqualTo(List.of(5, 3, 2, 5));
    }

    /** 3 — A06 gán VET (Visit#2; BR-TN-05). */
    private void step03ReceptionAssignsTheVet() {
        moveTo(at(D, 8, 45));
        ok(post(receptionistToken, "/api/visits/" + visit + "/assign", Map.of("assigneeId", vet)), "bước 3");

        assertThat(jdbc.queryForMap("SELECT status, assignee_id FROM visits WHERE id = ?", visit)).as("bước 3")
                .containsEntry("status", "WAITING").containsEntry("assignee_id", vet);
    }

    /** 4 — VET gọi lượt (Visit#3). */
    private void step04VetCalls() {
        moveTo(at(D, 9, 0));
        ok(post(vetToken, "/api/visits/" + visit + "/call", null), "bước 4");

        assertThat(visitStatus()).as("bước 4 Visit").isEqualTo("IN_PROGRESS");
        assertThat(orderStatus()).as("bước 4 Order").isEqualTo("OPEN");
    }

    /** 5 — lượt có dịch vụ Khám chưa có chẩn đoán: hoàn tất → 400 BR-KB-02, không ghi gì. */
    private void step05CompletionWithoutDiagnosisIsRejected() {
        moveTo(at(D, 9, 2));
        String visitBefore = xmin("visits", "id", visit);
        String orderBefore = orderXmin();
        String appointmentBefore = xmin("appointments", "id", appointment);

        ResponseEntity<Map<String, Object>> response = post(vetToken, "/api/visits/" + visit + "/complete", null);

        assertThat(response.getStatusCode()).as("bước 5 → %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).as("bước 5 lỗi").containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).as("bước 5 rule").endsWith("(BR-KB-02)");
        assertThat(xmin("visits", "id", visit)).as("bước 5 Visit không đổi").isEqualTo(visitBefore);
        assertThat(orderXmin()).as("bước 5 Order không đổi").isEqualTo(orderBefore);
        assertThat(xmin("appointments", "id", appointment)).as("bước 5 lịch hẹn không đổi")
                .isEqualTo(appointmentBefore);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = ?", visit)).as("bước 5 bệnh án")
                .isZero();
    }

    /** 6 — VET ghi khám, chẩn đoán, hướng điều trị và hẹn tái khám D+14 (visit-v1 #13; BR-KB-01, 02, 06). */
    private void step06VetWritesDiagnosisAndFollowUp() {
        moveTo(at(D, 9, 5));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("examination", EXAMINATION);
        body.put("diagnosis", DIAGNOSIS);
        body.put("treatmentPlan", TREATMENT);
        body.put("followUpDate", FOLLOW_UP.toString());
        ok(exchange(HttpMethod.PUT, vetToken, "/api/visits/" + visit + "/medical-record", body), "bước 6");

        assertThat(jdbc.queryForMap("""
                SELECT diagnosis, follow_up_date, follow_up_reminded_at, follow_up_cancelled_at, locked_at
                FROM medical_records WHERE visit_id = ?""", visit)).as("bước 6 bệnh án")
                .containsEntry("diagnosis", DIAGNOSIS).containsEntry("follow_up_date", java.sql.Date.valueOf(FOLLOW_UP))
                .containsEntry("follow_up_reminded_at", null).containsEntry("follow_up_cancelled_at", null)
                .containsEntry("locked_at", null);
        Map<String, Object> record = data(exchange(HttpMethod.GET, vetToken, "/api/visits/" + visit + "/medical-record",
                null), "bước 6 GET");
        assertThat(record).as("bước 6 GET").containsEntry("examination", EXAMINATION)
                .containsEntry("diagnosis", DIAGNOSIS).containsEntry("treatmentPlan", TREATMENT)
                .containsEntry("followUpDate", FOLLOW_UP.toString()).containsEntry("locked", false);
    }

    /** 7 — kê thuốc A × 4, khả dụng 5 (A1 + A2, không tính lô hết hạn): dòng Order DRUG, chưa trừ kho (BR-KB-03). */
    private void step07PrescriptionInStockAddsADrugLine() {
        moveTo(at(D, 9, 10));
        Map<String, Object> item = created(post(vetToken, "/api/visits/" + visit + "/prescriptions",
                prescription(drugA, 4, "Uống 1 viên × 2 lần/ngày sau ăn", null)), "bước 7");
        itemA = id(item, "itemId");

        assertThat(item).as("bước 7 dòng đơn").containsEntry("quantity", 4)
                .containsEntry("isExternalPurchase", false).containsEntry("externalReason", null);
        assertThat(id(item, "orderLineId")).as("bước 7 dòng Order của dòng đơn").isEqualTo(lineId("DRUG"));
        assertThat(lines()).as("bước 7 dòng Order").extracting(l -> l.get("line_type"), l -> l.get("product_id"),
                        l -> l.get("quantity"), l -> l.get("unit_price"))
                .containsExactly(tuple("SERVICE", null, 1, S), tuple("DRUG", drugA, 4, A));
        assertThat(orderTotal()).as("bước 7 tổng").isEqualTo(S + 4 * A);
        assertThat(lotQuantities()).as("bước 7 kho chưa trừ").isEqualTo(List.of(5, 3, 2, 5));
    }

    /**
     * 8 — thuốc B chỉ còn lô hết hạn: kê không chọn cách xử lý → 400 BR-KB-03, không ghi gì; chọn mua ngoài → dòng đơn
     * mua ngoài, không dòng Order (BR-KB-03, visit-v1 A5).
     */
    private void step08PrescriptionOutOfStockNeedsAChoiceThenGoesExternal() {
        moveTo(at(D, 9, 12));
        String orderBefore = orderXmin();
        ResponseEntity<Map<String, Object>> rejected = post(vetToken, "/api/visits/" + visit + "/prescriptions",
                prescription(drugB, 2, "Uống 1 viên × 2 lần/ngày", null));

        assertThat(rejected.getStatusCode()).as("bước 8a → %s", rejected.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) rejected.getBody().get("message")).as("bước 8a rule").endsWith("(BR-KB-03)");
        assertThat(count("SELECT count(*) FROM prescription_items WHERE medical_record_id = ?", visit))
                .as("bước 8a dòng đơn").isOne();
        assertThat(orderXmin()).as("bước 8a Order không đổi").isEqualTo(orderBefore);

        moveTo(at(D, 9, 13));
        Map<String, Object> item = created(post(vetToken, "/api/visits/" + visit + "/prescriptions",
                prescription(drugB, 2, "Uống 1 viên × 2 lần/ngày", "EXTERNAL_PURCHASE")), "bước 8b");
        itemB = id(item, "itemId");

        assertThat(item).as("bước 8b dòng đơn").containsEntry("quantity", 2).containsEntry("isExternalPurchase", true)
                .containsEntry("externalReason", "OUT_OF_STOCK_AT_PRESCRIBE").containsEntry("orderLineId", null);
        assertThat(orderXmin()).as("bước 8b Order không đổi").isEqualTo(orderBefore);
        assertThat(orderTotal()).as("bước 8b tổng").isEqualTo(TOTAL);
        assertThat(lotQuantities()).as("bước 8b kho").isEqualTo(List.of(5, 3, 2, 5));
    }

    /** 9 — hoàn tất (Visit#5 → Lịch hẹn#7, Order#4, khóa bệnh án; BR-KB-01, BR-KB-02 có chẩn đoán). */
    private void step09CompletionLocksTheRecord() {
        moveTo(at(D, 9, 20));
        ok(post(vetToken, "/api/visits/" + visit + "/complete", null), "bước 9");

        assertThat(visitStatus()).as("bước 9 Visit").isEqualTo("COMPLETED");
        assertThat(appointmentStatus()).as("bước 9 lịch hẹn").isEqualTo("COMPLETED");
        assertThat(orderStatus()).as("bước 9 Order").isEqualTo("PENDING");
        Timestamp completedAt = jdbc.queryForObject("SELECT completed_at FROM visits WHERE id = ?", Timestamp.class,
                visit);
        assertThat(jdbc.queryForObject("SELECT locked_at FROM medical_records WHERE visit_id = ?", Timestamp.class,
                visit)).as("bước 9 bệnh án khóa").isNotNull().isEqualTo(completedAt);
        Map<String, Object> record = data(exchange(HttpMethod.GET, vetToken, "/api/visits/" + visit + "/medical-record",
                null), "bước 9 GET");
        assertThat(record).as("bước 9 GET").containsEntry("locked", true);
        assertThat(list(record.get("prescriptions"))).as("bước 9 đơn thuốc")
                .extracting(p -> ((Number) p.get("itemId")).longValue(), p -> p.get("isExternalPurchase"))
                .containsExactlyInAnyOrder(tuple(itemA, false), tuple(itemB, true));
        assertThat(lotQuantities()).as("bước 9 kho").isEqualTo(List.of(5, 3, 2, 5));
        assertThat(customerOutbox()).as("bước 9 outbox").isZero();
    }

    /** 10 — lễ tân mở ca thường (Ca thu ngân#1; BR-TG-01, 05). */
    private void step10ReceptionOpensAShift() {
        moveTo(at(D, 9, 25));
        shift = id(created(post(receptionistToken, "/api/cashier-shifts", Map.of("afterHours", false)), "bước 10"),
                "shiftId");

        assertThat(jdbc.queryForMap("SELECT branch_id, cashier_id FROM cashier_shifts WHERE id = ?", shift))
                .as("bước 10 ca").containsEntry("branch_id", branch).containsEntry("cashier_id", receptionist);
    }

    /** 11 — xem trước phiếu thu: tổng S + 4A (thuốc mua ngoài không tính), không thiếu hàng, không ghi gì. */
    private void step11PreviewShowsTheTotal() {
        moveTo(at(D, 9, 26));
        String before = orderXmin();
        Map<String, Object> preview = data(post(receptionistToken, "/api/payments/preview",
                Map.of("orderIds", List.of(order))), "bước 11");

        assertThat(((Number) preview.get("totalAmount")).longValue()).as("bước 11 tổng").isEqualTo(TOTAL);
        assertThat(list(preview.get("shortages"))).as("bước 11 thiếu hàng").isEmpty();
        assertThat(orderXmin()).as("bước 11 Order không đổi").isEqualTo(before);
    }

    /**
     * 12 — thu tiền mặt đúng tổng: Order {@code PAID}, thuốc A trừ FEFO qua 2 lô còn hạn, lô hết hạn và thuốc mua ngoài
     * không bị đụng, audit {@code ORDER_PAID} (Order#8; BR-TG-01…04, BR-BH-04, BR-KO-05).
     */
    private void step12PaymentIssuesDrugFefo() {
        moveTo(at(D, 9, 27));
        payment = id(created(post(receptionistToken, "/api/payments",
                Map.of("orderIds", List.of(order), "method", "CASH", "amount", TOTAL)), "bước 12"), "paymentId");

        assertThat(jdbc.queryForMap("SELECT status, payment_id FROM orders WHERE id = ?", order)).as("bước 12 Order")
                .containsEntry("status", "PAID").containsEntry("payment_id", payment);
        assertThat(jdbc.queryForMap("""
                SELECT amount, method, cashier_shift_id, received_by, customer_id FROM payments WHERE id = ?""", payment))
                .as("bước 12 phiếu thu").containsEntry("amount", TOTAL).containsEntry("method", "CASH")
                .containsEntry("cashier_shift_id", shift).containsEntry("received_by", receptionist)
                .containsEntry("customer_id", customer);
        assertThat(lotQuantities()).as("bước 12 kho").isEqualTo(List.of(5, 1, 0, 5));
        assertThat(jdbc.queryForList("""
                SELECT stock_lot_id, quantity_delta, balance_after, movement_type FROM stock_movements
                WHERE stock_lot_id IN (?, ?, ?, ?) ORDER BY balance_after""", lotA0, lotA1, lotA2, lotB0))
                .as("bước 12 movement").extracting(m -> m.get("stock_lot_id"), m -> m.get("quantity_delta"),
                        m -> m.get("balance_after"), m -> m.get("movement_type"))
                .containsExactly(tuple(lotA2, -2, 0, "SALE"), tuple(lotA1, -2, 1, "SALE"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_PAID' AND entity_type = 'orders' AND entity_id = ?""",
                Long.class, order)).as("bước 12 audit").isOne();
        assertThat(jdbc.queryForObject("""
                SELECT is_external_purchase FROM prescription_items WHERE id = ?""", Boolean.class, itemA))
                .as("bước 12 dòng đơn đủ tồn vẫn không mua ngoài").isFalse();
        assertThat(customerOutbox()).as("bước 12 outbox").isZero();
    }

    /** 13 — D+10: hẹn D+14 nằm ngoài cửa sổ D+10…D+13, không ghi gì (cận của {@code DueDateWindow}). */
    private void step13FollowUpJobOutsideTheWindowWritesNothing() {
        clock.set(at(D.plusDays(10), 7, 40));
        followUpJob.run();

        assertThat(remindedAt()).as("bước 13 cờ").isNull();
        assertThat(customerOutbox()).as("bước 13 outbox").isZero();
    }

    /** 14 — D+11: nhắc đúng 3 ngày trước hẹn, IN_APP + EMAIL tới tài khoản, không Care Task (ST04; BR-TB-02, 06). */
    private void step14FollowUpJobSendsInAppAndEmail() {
        clock.set(at(D.plusDays(11), 7, 40));
        followUpJob.run();

        assertThat(remindedAt()).as("bước 14 cờ bệnh án mới").isNotNull();
        assertThat(reminderOutbox()).as("bước 14 outbox").extracting(r -> r.get("template_code"),
                        r -> r.get("channel"), r -> r.get("recipient_account_id"), r -> r.get("recipient_email"),
                        r -> r.get("link_url"), r -> r.get("ngay_tai_kham"))
                .containsExactlyInAnyOrder(
                        tuple("FOLLOW_UP_REMINDER_APP", "IN_APP", account, null,
                                "/booking?petId=%d&branchId=%d".formatted(pet, branch), "27/03/2030"),
                        tuple("FOLLOW_UP_REMINDER", "EMAIL", null, accountEmail, null, "27/03/2030"));
        assertThat(count("SELECT count(*) FROM care_tasks WHERE pet_id = ?", pet)).as("bước 14 không Care Task")
                .isZero();
        assertThat(oldRemindedAt()).as("bước 14 nhắc cũ đã hủy không gửi").isNull();
        assertThat(FollowUpItSupport.cancelledAt(jdbc, oldVisit)).as("bước 14 cờ hủy giữ nguyên").isEqualTo(bookedAt);
    }

    /** 15 — chạy lại: không gửi trùng (ADR-0082). */
    private void step15FollowUpJobDoesNotResend() {
        clock.set(at(D.plusDays(11), 8, 10));
        followUpJob.run();

        assertThat(reminderOutbox()).as("bước 15 outbox").hasSize(2);
    }

    /** T41-be.md §9.4. */
    private void finalInvariants() {
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM stock_lots WHERE id IN (?, ?, ?)", Long.class, lotA0,
                lotA1, lotA2)).as("tổng lô thuốc A").isEqualTo(6L);
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Long.class, lotB0))
                .as("lô thuốc B").isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_lots WHERE id IN (?, ?, ?, ?) AND quantity < 0""", Long.class, lotA0, lotA1,
                lotA2, lotB0)).as("không lô âm").isZero();

        long lineSum = jdbc.queryForObject("SELECT sum(line_total) FROM order_lines WHERE order_id = ?", Long.class,
                order);
        assertThat(lineSum).as("Σ dòng = tổng Order = phiếu thu").isEqualTo(orderTotal()).isEqualTo(TOTAL)
                .isEqualTo(jdbc.queryForObject("SELECT amount FROM payments WHERE id = ?", Long.class, payment));

        assertThat(count("SELECT count(*) FROM appointments WHERE pet_id = ?", pet)).as("lịch hẹn").isOne();
        assertThat(count("SELECT count(*) FROM visits WHERE pet_id = ? AND id <> " + oldVisit, pet)).as("Visit")
                .isOne();
        assertThat(count("SELECT count(*) FROM orders WHERE customer_id = ?", customer)).as("Order").isOne();
        assertThat(count("SELECT count(*) FROM payments WHERE customer_id = ?", customer)).as("phiếu thu").isOne();
        assertThat(jdbc.queryForList("""
                SELECT product_id, quantity, is_external_purchase, external_reason, order_line_id IS NULL AS no_line
                FROM prescription_items WHERE medical_record_id = ? ORDER BY id""", visit)).as("dòng đơn thuốc")
                .extracting(p -> p.get("product_id"), p -> p.get("quantity"), p -> p.get("is_external_purchase"),
                        p -> p.get("external_reason"), p -> p.get("no_line"))
                .containsExactly(tuple(drugA, 4, false, null, false),
                        tuple(drugB, 2, true, "OUT_OF_STOCK_AT_PRESCRIBE", true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_ADDED'
                AND entity_type = 'prescription_items' AND entity_id IN (?, ?)""", Long.class, itemA, itemB))
                .as("audit kê đơn").isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = ? AND locked_at IS NOT NULL", visit))
                .as("bệnh án mới khóa").isOne();
        assertThat(count("SELECT count(*) FROM care_tasks WHERE pet_id = ?", pet)).as("Care Task").isZero();
        assertThat(customerOutbox()).as("outbox").isEqualTo(2);
    }

    // ================================================================ helpers — dữ liệu

    private static Map<String, Object> prescription(long productId, int quantity, String dosage, String resolution) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("quantity", quantity);
        body.put("dosageInstructions", dosage);
        if (resolution != null) {
            body.put("shortageResolution", resolution);
        }
        return body;
    }

    /** A0, A1, A2 (thuốc A) rồi B0 (thuốc B, hết hạn). */
    private List<Integer> lotQuantities() {
        return List.of(lotA0, lotA1, lotA2, lotB0).stream()
                .map(lot -> jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Integer.class, lot))
                .toList();
    }

    private List<Map<String, Object>> lines() {
        return jdbc.queryForList("""
                SELECT id, line_type, service_id, product_id, quantity, unit_price, line_total, is_auto_generated
                FROM order_lines WHERE order_id = ? ORDER BY id""", order);
    }

    private long lineId(String type) {
        return jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND line_type = ?", Long.class, order,
                type);
    }

    private long orderTotal() {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, order);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    private String orderXmin() {
        return xmin("orders", "id", order);
    }

    private String visitStatus() {
        return jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit);
    }

    private String appointmentStatus() {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointment);
    }

    private String xmin(String table, String key, long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM " + table + " WHERE " + key + " = ?", String.class, id);
    }

    private Timestamp remindedAt() {
        return jdbc.queryForObject("SELECT follow_up_reminded_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
    }

    private Timestamp oldRemindedAt() {
        return jdbc.queryForObject("SELECT follow_up_reminded_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, oldVisit);
    }

    /** Mọi dòng outbox gửi tới khách (IN_APP theo tài khoản, EMAIL theo email tài khoản). */
    private long customerOutbox() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? OR recipient_email = ?""",
                Long.class, account, accountEmail);
    }

    private List<Map<String, Object>> reminderOutbox() {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, recipient_email, payload->>'link_url' AS link_url,
                       payload->>'ngay_tai_kham' AS ngay_tai_kham
                FROM notification_outbox
                WHERE template_code IN ('FOLLOW_UP_REMINDER', 'FOLLOW_UP_REMINDER_APP') AND payload->>'chi_nhanh' = ?
                ORDER BY id""", branchName);
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    // ================================================================ helpers — phiên, đồng hồ

    /** Dời đồng hồ rồi mở phiên mới: phiên cũ hết hạn theo cùng {@code Clock} khi nhảy ngày (ADR-0003). */
    private void moveTo(Instant instant) {
        clock.set(instant);
        openTokens();
    }

    private void openTokens() {
        customerToken = token(account);
        receptionistToken = token(receptionist);
        vetToken = token(vet);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "MainFlowExamIT")).accessToken();
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(VN).toInstant();
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> post(String bearer, String path, Object body) {
        return exchange(HttpMethod.POST, bearer, path, body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private static void ok(ResponseEntity<Map<String, Object>> response, String step) {
        assertThat(response.getStatusCode()).as("%s → %s", step, response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, String step) {
        ok(response, step);
        return map(response.getBody().get("data"));
    }

    private static Map<String, Object> created(ResponseEntity<Map<String, Object>> response, String step) {
        assertThat(response.getStatusCode()).as("%s → %s", step, response.getBody()).isEqualTo(HttpStatus.CREATED);
        return map(response.getBody().get("data"));
    }

    private static long id(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }
}
