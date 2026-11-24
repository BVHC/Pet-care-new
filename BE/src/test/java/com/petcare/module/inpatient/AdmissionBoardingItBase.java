package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.petcare.platform.config.TimeConfig;

/**
 * Nền chung của các IT "nội trú chạm sang lưu trú" (T69 N7, docs/adr/0149 mục 37–41): trả thú / thú mất / nhận thú khi
 * thú còn ca nội trú (BR-LT-17) và chuỗi thú mất bốn bước. Chi nhánh mở cửa cả ngày (trả thú / nhận thú chỉ trong giờ mở
 * cửa), một VET, một lễ tân, một dịch vụ nội trú đang bật. Ngày nhận của đặt chỗ lấy theo đồng hồ của era
 * ({@code MutableClock} ở năm xa), không theo {@code current_date} của DB. Mọi lệnh gọi qua HTTP thật.
 */
abstract class AdmissionBoardingItBase extends AdmissionItBase {

    protected static final long PRICE = 200_000L;
    protected static final String STAY_BLOCKED = "(BR-LT-17)";

    protected long branch;
    protected long vet;
    protected long receptionist;
    protected long customer;
    protected long service;
    protected String vetToken;
    protected String receptionistToken;

    protected void setUpBoarding(Instant now) {
        begin(now);
        branch = data.branch();
        data.openAllDay(branch);
        vet = data.staff("VET", branch);
        receptionist = data.staff("RECEPTIONIST", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, PRICE);
        reopenSessions();
    }

    /** Phiên hết hạn theo cùng đồng hồ: mở lại token sau khi đổi giờ. */
    protected void reopenSessions() {
        vetToken = token(vet);
        receptionistToken = token(receptionist);
    }

    /** Ngày Việt Nam của đồng hồ era. */
    protected LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TimeConfig.BUSINESS_ZONE);
    }

    // ---------------------------------------------------------------- dữ liệu

    /** Đặt chỗ {@code CHECKED_IN} nhận hôm qua, trả dự kiến sau 2 ngày (không quá hạn đón, không quá 30 đêm). */
    protected long stay(long pet) {
        return data.checkedInBooking(branch, customer, pet, receptionist, today().minusDays(1), today().plusDays(2));
    }

    /** Nhập viện đường (b) bằng API thật; trả id ca. */
    protected long admitFromStay(long booking) {
        ResponseEntity<Map<String, Object>> response = admit(vetToken, fromBooking(booking, service));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return idOf(dataOf(response), "admissionId");
    }

    /** Nhập viện đường (a) bằng API thật: lượt khám của VET, chuồng điều trị mới; trả {admissionId, visitId, kennelId}. */
    protected long[] admitFromVisit(long pet) {
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        ResponseEntity<Map<String, Object>> response = admit(vetToken, fromVisit(visit, service, kennel));
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return new long[] {idOf(dataOf(response), "admissionId"), visit, kennel};
    }

    protected long visitOf(long admissionId) {
        return jdbc.queryForObject("SELECT visit_id FROM admissions WHERE id = ?", Long.class, admissionId);
    }

    // ---------------------------------------------------------------- lệnh gọi

    protected ResponseEntity<Map<String, Object>> post(String path, String bearer, String json) {
        return call(HttpMethod.POST, path, bearer, json);
    }

    protected ResponseEntity<Map<String, Object>> discharge(long admissionId, String outcome) {
        return post(ADMISSIONS + "/" + admissionId + "/discharge", vetToken,
                "{'outcome':'" + outcome + "','summary':'Tóm tắt điều trị'}");
    }

    protected ResponseEntity<Map<String, Object>> startCheckout(long booking) {
        return post("/api/boarding-bookings/" + booking + "/start-checkout", receptionistToken, null);
    }

    protected ResponseEntity<Map<String, Object>> handOver(long booking) {
        return post("/api/boarding-bookings/" + booking + "/hand-over", receptionistToken, null);
    }

    protected ResponseEntity<Map<String, Object>> endDeceased(long booking) {
        return post("/api/boarding-bookings/" + booking + "/end-deceased", receptionistToken,
                "{'note':'Thú mất lúc 9h, bác sĩ cấp cứu không qua khỏi'}");
    }

    protected ResponseEntity<Map<String, Object>> boardingCheckIn(long booking, long kennel) {
        return post("/api/boarding-bookings/" + booking + "/check-in", receptionistToken,
                "{'kennelId':" + kennel + ",'weightKg':4.3,'healthCondition':'Tỉnh táo','emergencyPhone':'0901234567'}");
    }

    protected ResponseEntity<Map<String, Object>> completeVisit(long visit) {
        return post("/api/visits/" + visit + "/complete", vetToken, null);
    }

    protected ResponseEntity<Map<String, Object>> markDeceased(long pet) {
        return post("/api/pets/" + pet + "/mark-deceased", receptionistToken,
                "{'deceasedOn':'" + today() + "','confirmed':true}");
    }

    /** Thu tiền một Order lưu trú bằng API thật trong ca mở của lễ tân. */
    protected void pay(long orderId, long amount) {
        data.openShift(branch, receptionist, today(), clock.instant().minusSeconds(3600));
        ResponseEntity<Map<String, Object>> response = post("/api/payments", receptionistToken,
                "{'orderIds':[" + orderId + "],'method':'CASH','amount':" + amount + "}");
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
    }

    // ---------------------------------------------------------------- khẳng định

    /** 409 {@code INVALID_STATE_TRANSITION}, message kết thúc bằng mã rule BR-LT-17 (ADR-0149 mục 37). */
    protected static void assertStayBlocked(ResponseEntity<Map<String, Object>> response) {
        assertStatus(response, 409, "INVALID_STATE_TRANSITION");
        assertThat(message(response)).endsWith(STAY_BLOCKED);
    }

    protected int boardingOrders(long booking) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE boarding_booking_id = ?", Integer.class, booking);
    }

    protected String admissionStatus(long admissionId) {
        return jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, admissionId);
    }

    protected String visitStatus(long visit) {
        return jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit);
    }

    protected String endReason(long booking) {
        return jdbc.queryForObject("SELECT end_reason FROM boarding_bookings WHERE id = ?", String.class, booking);
    }

    protected Object deceasedOn(long pet) {
        return jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, pet);
    }

    protected List<Map<String, Object>> ordersOfVisit(long visit) {
        return jdbc.queryForList("SELECT source, status, total_amount FROM orders WHERE visit_id = ?", visit);
    }
}
