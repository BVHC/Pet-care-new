package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.support.MutableClock;
import com.petcare.TestcontainersConfiguration;

/**
 * Xuất viện (inpatient-v1 #4; docs/adr/0149 mục 22–25) trên Postgres thật: số ngày ở các ca biên (cùng ngày, qua nửa
 * đêm giờ Việt Nam, nhiều ngày), đơn giá đã chốt khi giá danh mục đổi, hai đường (chuồng điều trị được trả / đặt chỗ giữ
 * nguyên), kết cục {@code DECEASED}, chặn khi còn y lệnh {@code ORDERED}, 409 lần hai, hoàn tất lượt sau xuất viện, và
 * dòng nội trú không sửa / xóa tay được ở sales (BR-LT-18). Ma trận vai trò / đầu vào ở cuối file. Era <b>năm 2100</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionDischargeIT.TestBeans.class})
class AdmissionDischargeIT extends AdmissionItBase {

    /** 09:00 ngày 14/3/2100 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2100-03-14T02:00:00Z");
    private static final long PRICE = 250_000L;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long vet;
    private long customer;
    private long service;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, PRICE);
        token = token(vet);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- số ngày (BR-LT-14)

    @Test
    void sameDayDischargeBillsOneDayAndCommitsLineKennelAndAdmissionTogether() {
        Admitted a = admitFromVisit();
        moveTo(NOW.plusSeconds(3600));

        ResponseEntity<Map<String, Object>> response = discharge(a.id, token, "HOME", "  Ăn uống tốt, cho về  ");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ADMISSION_KEYS);
        assertThat(body).containsEntry("status", "DISCHARGED").containsEntry("dischargeOutcome", "HOME")
                .containsEntry("dischargeSummary", "Ăn uống tốt, cho về").containsEntry("billedDays", 1)
                .containsEntry("estimatedAmount", (int) PRICE).containsEntry("unitPrice", (int) PRICE)
                .containsEntry("kennelId", (int) a.kennel).containsEntry("visitId", (int) a.visit);
        assertThat(Instant.parse((String) body.get("dischargedAt"))).isEqualTo(NOW.plusSeconds(3600));
        long line = idOf(body, "orderLineId");

        // đọc ngoài transaction: đã commit thật, cả ba nơi cùng nhau
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT quantity, unit_price, line_total, line_type, service_id, added_by, owner_account_id
                FROM order_lines WHERE id = ?""", line);
        assertThat(row).containsEntry("quantity", 1).containsEntry("unit_price", PRICE)
                .containsEntry("line_total", PRICE).containsEntry("line_type", "SERVICE")
                .containsEntry("service_id", service).containsEntry("added_by", vet)
                .containsEntry("owner_account_id", vet);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders "
                + "WHERE visit_id = ?)", Integer.class, a.visit)).as("đúng một dòng").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE visit_id = ?", Long.class, a.visit))
                .isEqualTo(PRICE);
        assertThat(kennelStatus(a.kennel)).isEqualTo("AVAILABLE");
        Map<String, Object> admission = jdbc.queryForMap("""
                SELECT status, discharge_outcome, discharged_by, order_line_id FROM admissions WHERE id = ?""", a.id);
        assertThat(admission).containsEntry("status", "DISCHARGED").containsEntry("discharge_outcome", "HOME")
                .containsEntry("discharged_by", vet).containsEntry("order_line_id", line);
    }

    @Test
    void dischargeJustAfterMidnightInVietnamBillsTwoDaysEvenThoughOnlyTwentyMinutesPassed() {
        // nhập 23:50 ngày 14 giờ VN (16:50Z), xuất 00:10 ngày 15 giờ VN (17:10Z)
        moveTo(Instant.parse("2100-03-14T16:50:00Z"));
        Admitted a = admitFromVisit();
        moveTo(Instant.parse("2100-03-14T17:10:00Z"));

        ResponseEntity<Map<String, Object>> response = discharge(a.id, token, "HOME", "Qua đêm");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("billedDays", 2).containsEntry("estimatedAmount", (int) (2 * PRICE));
        assertThat(lineQuantity(a.visit)).isEqualTo(2);
        assertThat(orderTotal(a.visit)).isEqualTo(2 * PRICE);
    }

    @Test
    void dischargeFourCalendarDaysLaterBillsFourDaysBothEndsIncluded() {
        Admitted a = admitFromVisit();
        moveTo(NOW.plusSeconds(3 * 86_400L + 3600));

        ResponseEntity<Map<String, Object>> response = discharge(a.id, token, "HOME", "Bốn ngày");

        assertThat(dataOf(response)).containsEntry("billedDays", 4).containsEntry("estimatedAmount", (int) (4 * PRICE));
        assertThat(lineQuantity(a.visit)).isEqualTo(4);
        assertThat(orderTotal(a.visit)).isEqualTo(4 * PRICE);
    }

    @Test
    void theLockedPriceIsBilledEvenAfterTheCatalogPriceChangesAndTheServiceIsSwitchedOffAtTheBranch() {
        Admitted a = admitFromVisit();
        jdbc.update("UPDATE services SET price = ? WHERE id = ?", PRICE * 4, service);
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                service);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", service);
        moveTo(NOW.plusSeconds(86_400));

        ResponseEntity<Map<String, Object>> response = discharge(a.id, token, "HOME", "Giá đã đổi");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("unitPrice", (int) PRICE).containsEntry("billedDays", 2);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_lines WHERE id = ?", Long.class,
                idOf(dataOf(response), "orderLineId"))).isEqualTo(PRICE);
        assertThat(orderTotal(a.visit)).isEqualTo(2 * PRICE);
    }

    // ---------------------------------------------------------------- đường b, kết cục

    @Test
    void dischargeOfAStayAdmissionKeepsTheKennelAndTheBookingUntouched() {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        long stayKennel = bookingKennel(booking);
        long id = idOf(dataOf(admit(token, fromBooking(booking, service))), "admissionId");
        long visit = jdbc.queryForObject("SELECT visit_id FROM admissions WHERE id = ?", Long.class, id);

        ResponseEntity<Map<String, Object>> response = discharge(id, token, "HOME", "Trở lại lưu trú");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(kennelStatus(stayKennel)).as("thú vẫn ở chuồng lưu trú").isEqualTo("OCCUPIED");
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_IN");
        assertThat(lineQuantity(visit)).isEqualTo(1);
        assertThat(dataOf(response)).containsEntry("boardingBookingId", (int) booking).containsEntry("kennelId", null);
    }

    @Test
    void deceasedOutcomeOnlyRecordsTheOutcomeAndDoesNotMarkThePetOrEndTheStay() {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        long id = idOf(dataOf(admit(token, fromBooking(booking, service))), "admissionId");

        ResponseEntity<Map<String, Object>> response = discharge(id, token, "DECEASED", "Thú mất lúc 3h");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("dischargeOutcome", "DECEASED").containsEntry("status", "DISCHARGED");
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", java.sql.Date.class, pet))
                .as("không tự đánh dấu thú mất (BR-LT-14)").isNull();
        assertThat(bookingStatus(booking)).as("không tự kết thúc lưu trú").isEqualTo("CHECKED_IN");
    }

    // ---------------------------------------------------------------- y lệnh, trạng thái

    @Test
    void anOrderedOrderBlocksDischargeAndWritesNothingButDoneAndCancelledOrdersDoNot() {
        Admitted a = admitFromVisit();
        long open = insertOrder(a.id, "ORDERED");
        insertOrder(a.id, "DONE");
        insertOrder(a.id, "CANCELLED");

        ResponseEntity<Map<String, Object>> blocked = discharge(a.id, token, "HOME", "Còn y lệnh");

        assertRule(blocked, "BR-LT-14");
        assertThat(message(blocked)).contains("1 y lệnh");
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");
        assertThat(kennelStatus(a.kennel)).isEqualTo("OCCUPIED");
        assertThat(lineCount(a.visit)).isZero();

        jdbc.update("UPDATE admission_orders SET status = 'CANCELLED', cancel_reason = 'Bỏ' WHERE id = ?", open);
        ResponseEntity<Map<String, Object>> done = discharge(a.id, token, "HOME", "Hết y lệnh");

        assertThat(done.getStatusCode().value()).as(String.valueOf(done.getBody())).isEqualTo(200);
        assertThat(lineCount(a.visit)).isEqualTo(1);
    }

    @Test
    void aSecondDischargeIs409AndDoesNotBillTwice() {
        Admitted a = admitFromVisit();
        assertThat(discharge(a.id, token, "HOME", "Lần 1").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> again = discharge(a.id, token, "HOME", "Lần 2");

        assertStatus(again, 409, "INVALID_STATE_TRANSITION");
        assertThat(lineCount(a.visit)).isEqualTo(1);
        assertThat(orderTotal(a.visit)).isEqualTo(PRICE);
    }

    @Test
    void completingTheVisitAfterDischargeSucceedsAndTheOrderTotalIsDaysTimesLockedPrice() {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        long id = idOf(dataOf(admit(token, fromBooking(booking, service))), "admissionId");
        long visit = jdbc.queryForObject("SELECT visit_id FROM admissions WHERE id = ?", Long.class, id);
        moveTo(NOW.plusSeconds(2 * 86_400L));
        assertThat(discharge(id, token, "HOME", "Ba ngày").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> complete = call(HttpMethod.POST, "/api/visits/" + visit + "/complete",
                token, null);

        assertThat(complete.getStatusCode().value()).as(String.valueOf(complete.getBody())).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT status, total_amount FROM orders WHERE visit_id = ?", visit))
                .containsEntry("status", "PENDING").containsEntry("total_amount", 3 * PRICE);
    }

    // ---------------------------------------------------------------- dòng nội trú ở sales (BR-LT-18)

    @Test
    void theDischargeLineCannotBeLoweredOrDeletedByHand() {
        Admitted a = admitFromVisit();
        moveTo(NOW.plusSeconds(2 * 86_400L));
        long line = idOf(dataOf(discharge(a.id, token, "HOME", "Ba ngày")), "orderLineId");
        long order = jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, a.visit);
        String path = "/api/orders/" + order + "/lines/" + line;

        ResponseEntity<Map<String, Object>> patch = call(HttpMethod.PATCH, path, token, "{'quantity':1}");
        ResponseEntity<Map<String, Object>> delete = call(HttpMethod.DELETE, path, token, null);

        assertRule(patch, "BR-LT-18");
        assertRule(delete, "BR-LT-18");
        assertThat(lineQuantity(a.visit)).isEqualTo(3);
        assertThat(orderTotal(a.visit)).isEqualTo(3 * PRICE);
        assertThat(lineCount(a.visit)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- vai trò, đầu vào, thứ tự lỗi

    @Test
    void everyRoleExceptTheAssignedVetIsRefusedAndNothingChanges() {
        Admitted a = admitFromVisit();
        for (String role : List.of("RECEPTIONIST", "CARETAKER", "BRANCH_MANAGER")) {
            ResponseEntity<Map<String, Object>> response =
                    discharge(a.id, token(data.staff(role, branch)), "HOME", "Không được");
            assertStatus(response, 403, "ACCESS_DENIED");
        }
        assertThat(discharge(a.id, token(customerAccount()), "HOME", "Khách").getStatusCode().value()).isEqualTo(403);
        assertThat(discharge(a.id, null, "HOME", "Ẩn danh").getStatusCode().value()).isEqualTo(401);
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");
        assertThat(lineCount(a.visit)).isZero();
        assertThat(kennelStatus(a.kennel)).isEqualTo("OCCUPIED");
    }

    @Test
    void aVetWhoDoesNotRunTheVisitIsForbiddenAndAVetOfAnotherBranchToo() {
        Admitted a = admitFromVisit();
        String sameBranch = token(data.staff("VET", branch));
        String otherBranch = token(data.staff("VET", data.branch()));

        assertStatus(discharge(a.id, sameBranch, "HOME", "Không phụ trách"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(discharge(a.id, otherBranch, "HOME", "Chi nhánh khác"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");
        assertThat(lineCount(a.visit)).isZero();
    }

    @Test
    void notFoundBeatsTheAssigneeCheckAndTheAssigneeCheckBeatsTheStateConflict() {
        Admitted a = admitFromVisit();
        assertThat(discharge(a.id, token, "HOME", "Xong").getStatusCode().value()).isEqualTo(200);
        String stranger = token(data.staff("VET", branch));

        assertStatus(discharge(a.id + 1_000_000, stranger, "HOME", "x"), 404, "RESOURCE_NOT_FOUND");
        assertStatus(discharge(a.id, stranger, "HOME", "x"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(discharge(a.id, token, "HOME", "x"), 409, "INVALID_STATE_TRANSITION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "'   '", "'　'", "'\\u3000 \\u2003'"})
    void blankSummaryIs400BrLt14AndWritesNothing(String summary) {
        Admitted a = admitFromVisit();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, dischargePath(a.id), token,
                "{'outcome':'HOME','summary':" + summary + "}");

        assertRule(response, "BR-LT-14");
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");
        assertThat(lineCount(a.visit)).isZero();
    }

    @Test
    void malformedAndInvalidBodiesAre400BeforeAnyRead() {
        Admitted a = admitFromVisit();
        String path = dischargePath(a.id);

        assertStatus(call(HttpMethod.POST, path, token, null), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'outcome':'HOME'"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'outcome':'GONE','summary':'x'}"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'outcome':'HOME','summary':['x']}"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'summary':'x'}"), 400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{'outcome':'HOME'}"), 400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{'outcome':null,'summary':null}"), 400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, "/api/admissions/abc/discharge", token,
                "{'outcome':'HOME','summary':'x'}"), 400, "MALFORMED_REQUEST");
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");
        assertThat(lineCount(a.visit)).isZero();
    }

    @Test
    void theSummaryLimitIs2000CharactersCountedOnTheRawText() {
        Admitted a = admitFromVisit();
        String path = dischargePath(a.id);

        assertStatus(call(HttpMethod.POST, path, token,
                "{'outcome':'HOME','summary':'" + "a".repeat(2001) + "'}"), 400, "VALIDATION_FAILED");
        assertThat(admissionStatus(a.id)).isEqualTo("ADMITTED");

        ResponseEntity<Map<String, Object>> ok = call(HttpMethod.POST, path, token,
                "{'outcome':'HOME','summary':'" + "a".repeat(2000) + "'}");

        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat((String) dataOf(ok).get("dischargeSummary")).hasSize(2000);
    }

    // ---------------------------------------------------------------- helpers

    private record Admitted(long id, long visit, long kennel) {}

    /** Đổi giờ rồi mở lại phiên của VET: phiên hết hạn theo cùng {@code Clock} (access token 10 phút). */
    private void moveTo(Instant instant) {
        clock.set(instant);
        token = token(vet);
    }

    /** Nhập viện đường (a) bằng API thật; {@code clock} đang ở giờ nhập viện. */
    private Admitted admitFromVisit() {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        ResponseEntity<Map<String, Object>> response = admit(token, fromVisit(visit, service, kennel));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return new Admitted(idOf(dataOf(response), "admissionId"), visit, kennel);
    }

    private ResponseEntity<Map<String, Object>> discharge(long admissionId, String bearer, String outcome,
            String summary) {
        return call(HttpMethod.POST, dischargePath(admissionId), bearer,
                "{'outcome':'" + outcome + "','summary':'" + summary + "'}");
    }

    private static String dischargePath(long admissionId) {
        return ADMISSIONS + "/" + admissionId + "/discharge";
    }

    private long insertOrder(long admissionId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO admission_orders (admission_id, order_type, content, scheduled_at, status, created_by,
                        done_by, done_at, done_note, cancel_reason)
                VALUES (?, 'FEEDING', 'Cho ăn', now(), ?, ?,
                        CASE WHEN ? = 'DONE' THEN ? END, CASE WHEN ? = 'DONE' THEN now() END,
                        CASE WHEN ? = 'DONE' THEN 'Xong' END, CASE WHEN ? = 'CANCELLED' THEN 'Hủy' END)
                RETURNING id""", Long.class, admissionId, status, vet, status, vet, status, status, status);
    }

    private String admissionStatus(long admissionId) {
        return jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, admissionId);
    }

    private int lineCount(long visit) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, visit);
    }

    private int lineQuantity(long visit) {
        return jdbc.queryForObject("""
                SELECT quantity FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, visit);
    }

    private long orderTotal(long visit) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE visit_id = ?", Long.class, visit);
    }
}
