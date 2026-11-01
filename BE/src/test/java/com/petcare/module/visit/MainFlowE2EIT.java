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
import com.petcare.module.visit.job.VaccineReminderJob;
import com.petcare.support.MutableClock;

/**
 * T41.2 — E2E luồng chính theo kịch bản 16 bước của docs/testcases/T41-be.md §3: khách đặt lịch tiêm → lễ tân check-in
 * → VET tiêm (thay mũi cũ, hủy Care Task của nó) và ghi bệnh án → hoàn tất (khóa bệnh án) → lễ tân thêm hàng, mở ca, thu
 * tiền (FEFO) → ST04 nhắc tái chủng. Mọi bước qua endpoint / job thật, <b>không stand-in, không {@code UPDATE} trạng
 * thái</b>; chỉ dữ liệu nền (§2) chèn bằng SQL. Rollback của từng chuỗi đã có IT riêng (T41-be.md §8).
 * <p>
 * Era <b>năm 2029</b>, tương lai so với mọi era khác: job không cận dưới của IT khác (ST02, ST05, ST18) chỉ quét tới
 * đồng hồ của chúng, còn ST04 ở đây có cận dưới là hôm nay. Ngày tiêm {@code D} = 2029-03-14; chi nhánh mở 08:00–12:00 và
 * 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MainFlowE2EIT.TestBeans.class})
class MainFlowE2EIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate D = LocalDate.of(2029, 3, 14);
    /** Giá dịch vụ ({@code AppointmentItSupport.service}), vaccine ({@code VaccinationItSupport.product}), hàng. */
    private static final long S = 150_000;
    private static final long V = 150_000;
    private static final long G = 40_000;
    private static final long TOTAL = S + V + 3 * G;
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
    private VaccineReminderJob vaccineJob;

    private AppointmentItSupport data;
    private VaccinationItSupport vaccines;
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
    private long dogType;
    private long dose2;
    private long vaccine;
    private long lotExpired;
    private long lotFar;
    private long lotNear;
    private long goodsCategory;
    private long goods;
    private long goodsExpired;
    private long goodsFar;
    private long goodsNear;
    private long oldVisit;
    private long oldDose;
    private long oldTask;
    private String customerToken;
    private String receptionistToken;
    private String vetToken;

    // trạng thái của mạch, ghi ở từng bước
    private long appointment;
    private long visit;
    private long order;
    private long dose;
    private long shift;
    private long payment;

    @BeforeEach
    void setUp() {
        clock.set(at(D.minusDays(3), 10, 0));
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);

        branch = data.branch("ACTIVE");
        branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        data.openingHours(branch, LocalDate.of(2029, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        service = data.service("MEDICAL");
        jdbc.update("UPDATE services SET medical_type = 'VACCINE' WHERE id = ?", service);
        data.branchService(branch, service, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);

        account = data.customerAccount();
        accountEmail = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, account);
        customer = data.onlineCustomer(account, "Khách E2E " + UUID.randomUUID().toString().substring(0, 8));
        pet = data.pet(customer);
        jdbc.update("UPDATE pets SET birth_date = ? WHERE id = ?", D.minusYears(1), pet);

        dogType = vaccines.vaccineType("DOG", "Dại E2E");
        long dose1 = vaccines.protocol("DOG", dogType, 1, 21, false);
        dose2 = vaccines.protocol("DOG", dogType, 2, 21, false);
        vaccine = vaccines.product(dogType, "Rabisin E2E", true);
        lotExpired = vaccines.lot(branch, vaccine, D.minusDays(1), 5);
        lotFar = vaccines.lot(branch, vaccine, D.plusDays(300), 5);
        lotNear = vaccines.lot(branch, vaccine, D.plusDays(60), 5);

        goodsCategory = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                "Hàng E2E " + UUID.randomUUID());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        goods = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'GOODS', true, 'gói', ?) RETURNING id""", Long.class, goodsCategory, "GDS-" + suffix,
                "Pate E2E " + suffix, G);
        goodsExpired = vaccines.lot(branch, goods, D.minusDays(1), 5);
        goodsFar = vaccines.lot(branch, goods, D.plusDays(300), 10);
        goodsNear = vaccines.lot(branch, goods, D.plusDays(60), 2);

        // mũi 1 tiêm ở lượt cũ D−30, đã quá hạn D−9: ST18 đã sinh task quá hạn (khách có email nên ST04 không sinh task)
        oldVisit = completedVisitSql(D.minusDays(30));
        oldDose = vaccines.dose(oldVisit, dogType, dose1, 1, vaccine, lotExpired, D.minusDays(30), D.minusDays(9), vet,
                null);
        jdbc.update("UPDATE vaccinations SET overdue_handled_at = ? WHERE id = ?",
                Timestamp.from(at(D.minusDays(2), 7, 25)), oldDose);
        oldTask = vaccines.careTask(oldDose, pet, "VACCINE_OVERDUE", "OPEN");

        openTokens();
    }

    @AfterEach
    void tearDown() {
        vaccines.deleteDosesOfPet(pet);
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
        vaccines.deleteDoses();
        vaccines.deleteCatalog();
        jdbc.update("DELETE FROM products WHERE id = ?", goods);
        jdbc.update("DELETE FROM product_categories WHERE id = ?", goodsCategory);
        data.cleanUp();

        for (String table : List.of("vaccinations", "care_tasks", "visits", "appointments", "orders")) {
            String sql = table.equals("orders")
                    ? "SELECT count(*) FROM orders WHERE customer_id = ?"
                    : "SELECT count(*) FROM " + table + " WHERE pet_id = ?";
            assertThat(jdbc.queryForObject(sql, Long.class, table.equals("orders") ? customer : pet))
                    .as("còn sót ở %s", table).isZero();
        }
    }

    @Test
    void mainFlowFromBookingToVaccineReminder() {
        step01CustomerBooks();
        step02ReceptionChecksIn();
        step03ReceptionAssignsTheVet();
        step04VetCalls();
        step05VaccinationOptionsSuggestTheNextDose();
        step06VaccinationSupersedesTheOldDose();
        step07VetWritesTheMedicalRecord();
        step08CompletionLocksTheRecord();
        step09LockedRecordRejectsEdits();
        step10ReceptionAddsGoods();
        step11ReceptionOpensAShift();
        step12PreviewShowsTheTotal();
        step13PaymentIssuesGoodsFefo();
        step14ReminderJobOutsideTheWindowWritesNothing();
        step15ReminderJobSendsInAppAndEmail();
        step16ReminderJobDoesNotResend();
        finalInvariants();
    }

    // ================================================================ bước

    /** 1 — A02 đặt khung 09:00 ngày D lúc D−3 10:00 (Lịch hẹn#1; BR-LH-01…05). */
    private void step01CustomerBooks() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("serviceId", service);
        body.put("slotDate", D.toString());
        body.put("slotStart", "09:00");
        appointment = id(created(post(customerToken, "/api/appointments", body), "bước 1"), "appointmentId");

        assertThat(jdbc.queryForMap("""
                SELECT status, channel, reschedule_count, late_cancel, booked_by, service_group FROM appointments
                WHERE id = ?""", appointment)).as("bước 1 lịch hẹn")
                .containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                .containsEntry("reschedule_count", 0).containsEntry("late_cancel", false)
                .containsEntry("booked_by", account).containsEntry("service_group", "MEDICAL");
        assertThat(code("appointments", appointment)).as("bước 1 mã").startsWith("LH-");
        assertThat(customerOutbox()).as("bước 1 outbox").isZero();
    }

    /** 2 — A06 check-in lúc D 08:40 (Visit#1 → Lịch hẹn#3, Order#1; BR-TN-01, 02). */
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
        assertThat(code("visits", visit)).as("bước 2 mã").startsWith("TN");
        assertThat(jdbc.queryForMap("SELECT source, status, total_amount FROM orders WHERE id = ?", order))
                .as("bước 2 Order").containsEntry("source", "VISIT").containsEntry("status", "OPEN")
                .containsEntry("total_amount", S);
        assertThat(lines()).as("bước 2 dòng Order").extracting(l -> l.get("line_type"), l -> l.get("service_id"),
                        l -> l.get("unit_price"), l -> l.get("is_auto_generated"))
                .containsExactly(tuple("SERVICE", service, S, true));
        assertThat(lotQuantities()).as("bước 2 kho").isEqualTo(List.of(5, 5, 5, 5, 10, 2));
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

    /** 5 — gợi ý mũi kế tiếp sau mũi cũ; tồn khả dụng không tính lô hết hạn (BR-KB-04, BR-KO-01). */
    private void step05VaccinationOptionsSuggestTheNextDose() {
        moveTo(at(D, 9, 5));
        Map<String, Object> options = data(exchange(HttpMethod.GET, vetToken,
                "/api/visits/" + visit + "/vaccination-options?vaccineTypeId=" + dogType, null), "bước 5");

        assertThat(id(options, "suggestedProtocolId")).as("bước 5 gợi ý").isEqualTo(dose2);
        assertThat(map(options.get("lastDose"))).as("bước 5 mũi gần nhất")
                .containsEntry("vaccinationId", (int) oldDose).containsEntry("doseNumber", 1);
        assertThat(list(options.get("products"))).as("bước 5 sản phẩm")
                .extracting(p -> ((Number) p.get("productId")).longValue(), p -> p.get("availableQuantity"))
                .containsExactly(tuple(vaccine, 10));
    }

    /**
     * 6 — ghi mũi 2: trừ lô FEFO còn hạn gần nhất, thêm dòng VACCINE, mũi cũ bị thay thế và Care Task quá hạn của nó bị
     * hủy (Phụ lục 03 "ghi nhận mũi tiêm", Care Task#5; BR-KB-04, BR-KO-05, BR-TB-03).
     */
    private void step06VaccinationSupersedesTheOldDose() {
        moveTo(at(D, 9, 10));
        Instant recordedAt = clock.instant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vaccineTypeId", dogType);
        body.put("protocolId", dose2);
        body.put("productId", vaccine);
        created(post(vetToken, "/api/visits/" + visit + "/vaccinations", body), "bước 6");
        dose = jdbc.queryForObject("SELECT id FROM vaccinations WHERE visit_id = ?", Long.class, visit);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT dose_number, protocol_id, stock_lot_id, administered_on, next_due_date, superseded_at,
                       due_reminded_at, order_line_id FROM vaccinations WHERE id = ?""", dose);
        assertThat(row).as("bước 6 mũi mới").containsEntry("dose_number", 2).containsEntry("protocol_id", dose2)
                .containsEntry("stock_lot_id", lotNear).containsEntry("administered_on", java.sql.Date.valueOf(D))
                .containsEntry("next_due_date", java.sql.Date.valueOf(D.plusDays(21)))
                .containsEntry("superseded_at", null).containsEntry("due_reminded_at", null);
        assertThat(lines()).as("bước 6 dòng Order").extracting(l -> l.get("line_type"), l -> l.get("unit_price"))
                .containsExactly(tuple("SERVICE", S), tuple("VACCINE", V));
        assertThat(((Number) row.get("order_line_id")).longValue()).as("bước 6 dòng VACCINE của mũi")
                .isEqualTo(lineId("VACCINE"));
        assertThat(orderTotal()).as("bước 6 tổng").isEqualTo(S + V);
        assertThat(lotQuantities()).as("bước 6 kho").isEqualTo(List.of(5, 5, 4, 5, 10, 2));
        assertThat(jdbc.queryForList("""
                SELECT stock_lot_id, quantity_delta, balance_after, source_id FROM stock_movements
                WHERE movement_type = 'VACCINATION' AND stock_lot_id IN (?, ?, ?)""", lotExpired, lotFar, lotNear))
                .as("bước 6 movement").extracting(m -> m.get("stock_lot_id"), m -> m.get("quantity_delta"),
                        m -> m.get("balance_after"), m -> m.get("source_id"))
                .containsExactly(tuple(lotNear, -1, 4, dose));

        assertThat(jdbc.queryForObject("SELECT superseded_at FROM vaccinations WHERE id = ?", Timestamp.class, oldDose)
                .toInstant()).as("bước 6 mũi cũ bị thay thế").isEqualTo(recordedAt);
        assertThat(jdbc.queryForMap("SELECT status, cancel_reason, handled_by FROM care_tasks WHERE id = ?", oldTask))
                .as("bước 6 Care Task mũi cũ").containsEntry("status", "CANCELLED")
                .containsEntry("cancel_reason", "Đã tiêm lại (BR-TB-03)").containsEntry("handled_by", null);
        assertThat(customerOutbox()).as("bước 6 outbox").isZero();
    }

    /** 7 — VET ghi bệnh án; lượt chỉ tiêm không bắt buộc chẩn đoán (visit-v1 #13; BR-KB-01, 02). */
    private void step07VetWritesTheMedicalRecord() {
        moveTo(at(D, 9, 15));
        ok(exchange(HttpMethod.PUT, vetToken, "/api/visits/" + visit + "/medical-record",
                Map.of("examination", "Thân nhiệt 38,5°C, khỏe, đủ điều kiện tiêm")), "bước 7");

        assertThat(jdbc.queryForMap("SELECT examination, diagnosis, locked_at FROM medical_records WHERE visit_id = ?",
                visit)).as("bước 7 bệnh án").containsEntry("examination", "Thân nhiệt 38,5°C, khỏe, đủ điều kiện tiêm")
                .containsEntry("diagnosis", null).containsEntry("locked_at", null);
    }

    /** 8 — hoàn tất (Visit#5 → Lịch hẹn#7, Order#4, khóa bệnh án; BR-KB-01, BR-KB-02 lượt chỉ tiêm có ≥ 1 mũi). */
    private void step08CompletionLocksTheRecord() {
        moveTo(at(D, 9, 20));
        ok(post(vetToken, "/api/visits/" + visit + "/complete", null), "bước 8");

        assertThat(visitStatus()).as("bước 8 Visit").isEqualTo("COMPLETED");
        assertThat(appointmentStatus()).as("bước 8 lịch hẹn").isEqualTo("COMPLETED");
        assertThat(orderStatus()).as("bước 8 Order").isEqualTo("PENDING");
        Timestamp completedAt = jdbc.queryForObject("SELECT completed_at FROM visits WHERE id = ?", Timestamp.class,
                visit);
        assertThat(jdbc.queryForObject("SELECT locked_at FROM medical_records WHERE visit_id = ?", Timestamp.class,
                visit)).as("bước 8 bệnh án khóa").isNotNull().isEqualTo(completedAt);
        assertThat(lotQuantities()).as("bước 8 kho").isEqualTo(List.of(5, 5, 4, 5, 10, 2));
        assertThat(customerOutbox()).as("bước 8 outbox").isZero();
    }

    /** 9 — bệnh án đã khóa: sửa trực tiếp → 409, không ghi gì; xem thấy {@code locked} (BR-KB-01, ADR-0070). */
    private void step09LockedRecordRejectsEdits() {
        moveTo(at(D, 9, 21));
        String before = recordXmin();

        ResponseEntity<Map<String, Object>> edit = exchange(HttpMethod.PUT, vetToken,
                "/api/visits/" + visit + "/medical-record", Map.of("examination", "Sửa sau khi khóa"));

        assertThat(edit.getStatusCode()).as("bước 9 PUT → %s", edit.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(edit.getBody()).as("bước 9 lỗi").containsEntry("errorCode", "INVALID_STATE_TRANSITION");
        assertThat(recordXmin()).as("bước 9 bệnh án không đổi").isEqualTo(before);
        Map<String, Object> record = data(exchange(HttpMethod.GET, vetToken, "/api/visits/" + visit + "/medical-record",
                null), "bước 9 GET");
        assertThat(record).as("bước 9 GET").containsEntry("locked", true)
                .containsEntry("examination", "Thân nhiệt 38,5°C, khỏe, đủ điều kiện tiêm");
    }

    /** 10 — lễ tân thêm 3 gói hàng vào Order {@code PENDING}; chưa trừ kho (BR-BH-02, 04). */
    private void step10ReceptionAddsGoods() {
        moveTo(at(D, 9, 25));
        Map<String, Object> result = created(post(receptionistToken, "/api/orders/" + order + "/lines",
                Map.of("lineType", "GOODS", "productId", goods, "quantity", 3)), "bước 10");

        assertThat(result).as("bước 10 cảnh báo tồn").containsEntry("stockWarning", null);
        assertThat(orderStatus()).as("bước 10 Order").isEqualTo("PENDING");
        assertThat(lines()).as("bước 10 dòng Order").extracting(l -> l.get("line_type"), l -> l.get("quantity"),
                        l -> l.get("unit_price"), l -> l.get("line_total"))
                .containsExactly(tuple("SERVICE", 1, S, S), tuple("VACCINE", 1, V, V), tuple("GOODS", 3, G, 3 * G));
        assertThat(orderTotal()).as("bước 10 tổng").isEqualTo(TOTAL);
        assertThat(lotQuantities()).as("bước 10 kho chưa trừ").isEqualTo(List.of(5, 5, 4, 5, 10, 2));
    }

    /** 11 — lễ tân mở ca thường (Ca thu ngân#1; BR-TG-01, 05). */
    private void step11ReceptionOpensAShift() {
        moveTo(at(D, 9, 28));
        shift = id(created(post(receptionistToken, "/api/cashier-shifts", Map.of("afterHours", false)), "bước 11"),
                "shiftId");

        assertThat(jdbc.queryForMap("SELECT branch_id, cashier_id FROM cashier_shifts WHERE id = ?", shift))
                .as("bước 11 ca").containsEntry("branch_id", branch).containsEntry("cashier_id", receptionist);
    }

    /** 12 — xem trước phiếu thu: tổng đúng, không thiếu hàng, không ghi gì. */
    private void step12PreviewShowsTheTotal() {
        moveTo(at(D, 9, 30));
        String before = orderXmin();
        Map<String, Object> preview = data(post(receptionistToken, "/api/payments/preview",
                Map.of("orderIds", List.of(order))), "bước 12");

        assertThat(((Number) preview.get("totalAmount")).longValue()).as("bước 12 tổng").isEqualTo(TOTAL);
        assertThat(list(preview.get("shortages"))).as("bước 12 thiếu hàng").isEmpty();
        assertThat(orderXmin()).as("bước 12 Order không đổi").isEqualTo(before);
    }

    /**
     * 13 — thu tiền mặt đúng tổng: Order {@code PAID}, hàng trừ FEFO qua 2 lô còn hạn, vaccine không bị trừ lần 2,
     * audit {@code ORDER_PAID} (Order#8; BR-TG-01…04, BR-BH-04, BR-KO-05).
     */
    private void step13PaymentIssuesGoodsFefo() {
        moveTo(at(D, 9, 31));
        payment = id(created(post(receptionistToken, "/api/payments",
                Map.of("orderIds", List.of(order), "method", "CASH", "amount", TOTAL)), "bước 13"), "paymentId");

        assertThat(jdbc.queryForMap("SELECT status, payment_id FROM orders WHERE id = ?", order)).as("bước 13 Order")
                .containsEntry("status", "PAID").containsEntry("payment_id", payment);
        assertThat(jdbc.queryForMap("""
                SELECT amount, method, cashier_shift_id, received_by, customer_id FROM payments WHERE id = ?""", payment))
                .as("bước 13 phiếu thu").containsEntry("amount", TOTAL).containsEntry("method", "CASH")
                .containsEntry("cashier_shift_id", shift).containsEntry("received_by", receptionist)
                .containsEntry("customer_id", customer);
        assertThat(lotQuantities()).as("bước 13 kho").isEqualTo(List.of(5, 5, 4, 5, 9, 0));
        assertThat(jdbc.queryForList("""
                SELECT stock_lot_id, quantity_delta, balance_after FROM stock_movements
                WHERE movement_type = 'SALE' AND stock_lot_id IN (?, ?, ?) ORDER BY balance_after""",
                goodsExpired, goodsFar, goodsNear)).as("bước 13 movement")
                .extracting(m -> m.get("stock_lot_id"), m -> m.get("quantity_delta"), m -> m.get("balance_after"))
                .containsExactly(tuple(goodsNear, -2, 0), tuple(goodsFar, -1, 9));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements WHERE stock_lot_id IN (?, ?, ?) AND movement_type <> 'VACCINATION'""",
                Long.class, lotExpired, lotFar, lotNear)).as("bước 13 vaccine không trừ lần 2").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_PAID' AND entity_type = 'orders' AND entity_id = ?""",
                Long.class, order)).as("bước 13 audit").isOne();
        assertThat(customerOutbox()).as("bước 13 outbox").isZero();
    }

    /** 14 — D+13: hạn D+21 nằm ngoài cửa sổ D+13…D+20, không ghi gì (cận của {@code DueDateWindow}). */
    private void step14ReminderJobOutsideTheWindowWritesNothing() {
        clock.set(at(D.plusDays(13), 7, 10));
        vaccineJob.run();

        assertThat(dueRemindedAt(dose)).as("bước 14 cờ").isNull();
        assertThat(customerOutbox()).as("bước 14 outbox").isZero();
    }

    /** 15 — D+14: nhắc đúng 7 ngày trước hạn, IN_APP + EMAIL tới tài khoản, không Care Task (ST04; BR-TB-01, 02). */
    private void step15ReminderJobSendsInAppAndEmail() {
        clock.set(at(D.plusDays(14), 7, 10));
        vaccineJob.run();

        assertThat(dueRemindedAt(dose)).as("bước 15 cờ mũi mới").isNotNull();
        assertThat(reminderOutbox()).as("bước 15 outbox").extracting(r -> r.get("template_code"),
                        r -> r.get("channel"), r -> r.get("recipient_account_id"), r -> r.get("recipient_email"),
                        r -> r.get("link_url"))
                .containsExactlyInAnyOrder(
                        tuple("VACCINE_REMINDER_APP", "IN_APP", account, null,
                                "/booking?petId=%d&branchId=%d&serviceType=VACCINE".formatted(pet, branch)),
                        tuple("VACCINE_REMINDER", "EMAIL", null, accountEmail, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE pet_id = ? AND status = 'OPEN'",
                Long.class, pet)).as("bước 15 không Care Task").isZero();
        // kiểm phụ: hạn D−9 của mũi cũ vốn ngoài cửa sổ, bộ lọc superseded_at do VaccineReminderIT chứng minh
        assertThat(dueRemindedAt(oldDose)).as("bước 15 mũi cũ").isNull();
    }

    /** 16 — chạy lại: không gửi trùng (ADR-0082). */
    private void step16ReminderJobDoesNotResend() {
        clock.set(at(D.plusDays(14), 7, 40));
        vaccineJob.run();

        assertThat(reminderOutbox()).as("bước 16 outbox").hasSize(2);
    }

    /** T41-be.md §4. */
    private void finalInvariants() {
        assertThat(jdbc.queryForObject("""
                SELECT sum(quantity) FROM stock_lots WHERE id IN (?, ?, ?)""", Long.class, lotExpired, lotFar, lotNear))
                .as("§4 tổng lô vaccine").isEqualTo(14L);
        assertThat(jdbc.queryForObject("""
                SELECT sum(quantity) FROM stock_lots WHERE id IN (?, ?, ?)""", Long.class, goodsExpired, goodsFar,
                goodsNear)).as("§4 tổng lô hàng").isEqualTo(14L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_lots WHERE id IN (?, ?, ?, ?, ?, ?) AND quantity < 0""", Long.class, lotExpired,
                lotFar, lotNear, goodsExpired, goodsFar, goodsNear)).as("§4 không lô âm").isZero();

        long lineSum = jdbc.queryForObject("SELECT sum(line_total) FROM order_lines WHERE order_id = ?", Long.class,
                order);
        assertThat(lineSum).as("§4 Σ dòng = tổng Order = phiếu thu").isEqualTo(orderTotal()).isEqualTo(TOTAL)
                .isEqualTo(jdbc.queryForObject("SELECT amount FROM payments WHERE id = ?", Long.class, payment));

        assertThat(count("SELECT count(*) FROM appointments WHERE pet_id = ?", pet)).as("§4 lịch hẹn").isOne();
        assertThat(count("SELECT count(*) FROM visits WHERE pet_id = ? AND id <> " + oldVisit, pet)).as("§4 Visit")
                .isOne();
        assertThat(count("SELECT count(*) FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.pet_id = ? AND v.id <> "
                + oldVisit, pet)).as("§4 Order").isOne();
        assertThat(count("SELECT count(*) FROM payments WHERE customer_id = ?", customer)).as("§4 phiếu thu").isOne();
        assertThat(count("SELECT count(*) FROM vaccinations WHERE pet_id = ? AND id <> " + oldDose, pet))
                .as("§4 mũi mới").isOne();
        assertThat(count("SELECT count(*) FROM medical_records m JOIN visits v ON v.id = m.visit_id WHERE v.pet_id = ?"
                + " AND m.locked_at IS NOT NULL", pet)).as("§4 bệnh án khóa").isOne();
        assertThat(jdbc.queryForList("SELECT id, status FROM care_tasks WHERE pet_id = ?", pet)).as("§4 Care Task")
                .extracting(t -> ((Number) t.get("id")).longValue(), t -> t.get("status"))
                .containsExactly(tuple(oldTask, "CANCELLED"));
        assertThat(customerOutbox()).as("§4 outbox").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, oldVisit))
                .as("§4 Order nền không đổi").isEqualTo("OPEN");
    }

    // ================================================================ helpers — dữ liệu

    /** Lượt cũ đã hoàn tất của thú (dữ liệu nền, mẫu {@code VaccinationRecordIT.visit}). */
    private long completedVisitSql(LocalDate day) {
        Timestamp at = Timestamp.from(at(day, 9, 0));
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', 'COMPLETED', 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branch, customer, pet, at, vet, receptionist,
                at, at, at);
    }

    /** L0, L1, L2 (vaccine) rồi G0, G1, G2 (hàng). */
    private List<Integer> lotQuantities() {
        return List.of(lotExpired, lotFar, lotNear, goodsExpired, goodsFar, goodsNear).stream()
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
        return jdbc.queryForObject("SELECT xmin::text FROM orders WHERE id = ?", String.class, order);
    }

    private String visitStatus() {
        return jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit);
    }

    private String appointmentStatus() {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointment);
    }

    private String recordXmin() {
        return jdbc.queryForObject("SELECT xmin::text FROM medical_records WHERE visit_id = ?", String.class, visit);
    }

    private String code(String table, long id) {
        return jdbc.queryForObject("SELECT code FROM " + table + " WHERE id = ?", String.class, id);
    }

    private Timestamp dueRemindedAt(long vaccinationId) {
        return jdbc.queryForObject("SELECT due_reminded_at FROM vaccinations WHERE id = ?", Timestamp.class,
                vaccinationId);
    }

    /** Mọi dòng outbox gửi tới khách (IN_APP theo tài khoản, EMAIL theo email tài khoản). */
    private long customerOutbox() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ? OR recipient_email = ?""",
                Long.class, account, accountEmail);
    }

    private List<Map<String, Object>> reminderOutbox() {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, recipient_email, payload->>'link_url' AS link_url
                FROM notification_outbox
                WHERE template_code IN ('VACCINE_REMINDER', 'VACCINE_REMINDER_APP') AND payload->>'chi_nhanh' = ?
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
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "MainFlowE2EIT")).accessToken();
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
