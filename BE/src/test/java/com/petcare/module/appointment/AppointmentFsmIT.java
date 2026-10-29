package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * T40.4 — đủ 8 chuyển của bảng "3. Lịch hẹn" (docs/03-state-machines.md) và 12 cặp sai (đổi giờ / hủy / check-in ×
 * {@code CHECKED_IN}, {@code COMPLETED}, {@code CANCELLED}, {@code NO_SHOW}) qua endpoint / job thật, **không stand-in
 * nào**: {@code CustomerQueryApi}, {@code PetQueryApi}, {@code BoardingQueryApi} (đếm vi phạm BR-LH-09), visit, sales
 * đều là bean thật (IT cũ của từng luồng còn {@code JdbcPetCustomerModule} / mock, docs/testcases/T40-be.md §2.2).
 * Mọi lịch hẹn đặt qua {@code POST /api/appointments}; catalog / chi nhánh / nhân viên / khách là dữ liệu tham chiếu
 * chèn bằng {@link AppointmentItSupport}. Cặp sai trả 409 trước mọi 400 (docs/adr/0049, 0050) và không ghi gì.
 * <p>
 * Era <b>năm 1997</b>: ST05 ({@code findOverdueBookedIds}) không có cận dưới, nên job chạy ở 1997 chỉ chạm lịch của lớp
 * này. Chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AppointmentFsmIT.TestBeans.class})
class AppointmentFsmIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(1997, 6, 10);
    private static final LocalDate LATER = TODAY.plusDays(2);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(TODAY, 8, 30));
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
    private NoShowJob noShowJob;
    @Autowired
    private SystemConfigApi configs;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long vet;
    private long customerAccount;
    private String customerEmail;
    private long onlineCustomer;
    private long counterCustomer;
    private String receptionistToken;
    private String vetToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        clock.set(at(TODAY, 8, 30));
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        data.openingHours(branch, LocalDate.of(1997, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        medical = data.service("MEDICAL");
        data.branchService(branch, medical, true);
        long receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        customerEmail = "fsm-appt-" + UUID.randomUUID() + "@example.com";
        customerAccount = data.customerAccount(customerEmail, "ACTIVE");
        onlineCustomer = data.onlineCustomer(customerAccount, "Khách online " + UUID.randomUUID());
        counterCustomer = data.counterCustomer();
        receptionistToken = token(receptionist);
        vetToken = token(vet);
        customerToken = token(customerAccount);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                branch);
        jdbc.update("""
                DELETE FROM order_lines WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", branch);
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ? OR recipient_email = ?",
                customerAccount, customerEmail);
        data.cleanUp();
    }

    // ================================================================ chuyển hợp lệ

    /** #1, #2 — khách đặt online rồi đổi khung giờ: vẫn {@code BOOKED}, tăng số lần đổi, giữ mã. */
    @Test
    void customerBooksAndReschedules() {
        long pet = data.pet(onlineCustomer);

        long appointment = book(customerToken, pet, LATER, LocalTime.of(9, 0));
        Map<String, Object> booked = appointment(appointment);
        assertThat(booked).containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                .containsEntry("reschedule_count", 0);

        ok(post(customerToken, "/api/appointments/" + appointment + "/reschedule", slot(LATER, LocalTime.of(10, 0))));

        assertThat(appointment(appointment)).containsEntry("status", "BOOKED").containsEntry("reschedule_count", 1)
                .containsEntry("slot_start", "10:00:00")
                .containsEntry("code", booked.get("code"));
    }

    /** #4 — khách hủy: {@code late_cancel} chỉ đúng khi còn dưới mốc [CFG] (BR-LH-07). */
    @Test
    void customerCancelSetsLateCancelAtTheTwelveHourMark() {
        long pet = data.pet(onlineCustomer);
        int lateHours = configs.getInt(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS);

        long early = book(customerToken, pet, LATER, LocalTime.of(9, 0));
        ok(post(customerToken, "/api/appointments/" + early + "/cancel", Map.of()));
        assertThat(appointment(early)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CUSTOMER")
                .containsEntry("late_cancel", false);

        long late = book(customerToken, pet, LATER, LocalTime.of(10, 0));
        clock.set(at(LATER, 10, 0).minus(Duration.ofHours(lateHours)).plus(Duration.ofMinutes(1)));
        String freshToken = token(customerAccount); // phiên cũ đã hết hạn sau ~1,5 ngày đồng hồ
        ok(post(freshToken, "/api/appointments/" + late + "/cancel", Map.of()));
        assertThat(appointment(late)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CUSTOMER")
                .containsEntry("late_cancel", true);
    }

    /** #5 — phòng khám hủy khi thú được đánh dấu đã mất (BR-KH-05): không tính hủy muộn. */
    @Test
    void petDeceasedCancelsItsBookedAppointment() {
        long pet = data.pet(counterCustomer);
        long appointment = book(receptionistToken, pet, LATER, LocalTime.of(9, 0));

        ok(post(receptionistToken, "/api/pets/" + pet + "/mark-deceased",
                Map.of("deceasedOn", TODAY.toString(), "confirmed", true)));

        assertThat(appointment(appointment)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC")
                .containsEntry("late_cancel", false);
    }

    /** #6 — ST05: quá giờ hẹn [CFG] phút mà chưa check-in thì {@code NO_SHOW}; lịch chưa tới mốc giữ nguyên. */
    @Test
    void st05MarksAnOverdueBookingNoShow() {
        long overdue = book(receptionistToken, data.pet(counterCustomer), TODAY, LocalTime.of(9, 0));
        long notYet = book(receptionistToken, data.pet(counterCustomer), TODAY, LocalTime.of(10, 0));
        clock.set(at(TODAY, 9, 0).plus(Duration.ofMinutes(
                configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES) + 1)));

        noShowJob.run();

        assertThat(appointment(overdue)).containsEntry("status", "NO_SHOW");
        assertThat(appointment(notYet)).containsEntry("status", "BOOKED");
    }

    /** #3 — Visit#1: check-in tạo Visit {@code WAITING} và Order {@code OPEN}. */
    @Test
    void checkInMovesTheBookingToCheckedIn() {
        long pet = data.pet(counterCustomer);
        long appointment = book(receptionistToken, pet, TODAY, LocalTime.of(9, 30));

        long visit = checkIn(pet, appointment);

        assertThat(appointment(appointment)).containsEntry("status", "CHECKED_IN");
        assertThat(text("SELECT status FROM visits WHERE id = ?", visit)).isEqualTo("WAITING");
        assertThat(text("SELECT status FROM orders WHERE visit_id = ?", visit)).isEqualTo("OPEN");
    }

    /** #7 — Visit#5: hoàn tất lượt (có chẩn đoán, BR-KB-02) thì lịch {@code COMPLETED}, Order {@code PENDING}. */
    @Test
    void completingTheVisitCompletesTheAppointment() {
        long pet = data.pet(counterCustomer);
        long appointment = book(receptionistToken, pet, TODAY, LocalTime.of(9, 30));

        long visit = complete(checkIn(pet, appointment));

        assertThat(appointment(appointment)).containsEntry("status", "COMPLETED");
        assertThat(text("SELECT status FROM orders WHERE visit_id = ?", visit)).isEqualTo("PENDING");
    }

    /** #8 — Visit#6: hủy lượt chưa gọi thì lịch {@code CANCELLED}, không tính hủy muộn; Order {@code CANCELLED}. */
    @Test
    void cancellingTheWaitingVisitCancelsTheAppointment() {
        long pet = data.pet(counterCustomer);
        long appointment = book(receptionistToken, pet, TODAY, LocalTime.of(9, 30));
        long visit = checkIn(pet, appointment);

        ok(post(receptionistToken, "/api/visits/" + visit + "/cancel", Map.of("reason", "Khách bỏ về")));

        assertThat(appointment(appointment)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false);
        assertThat(text("SELECT status FROM visits WHERE id = ?", visit)).isEqualTo("CANCELLED");
        assertThat(text("SELECT status FROM orders WHERE visit_id = ?", visit)).isEqualTo("CANCELLED");
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /**
     * Đổi giờ, hủy, check-in một lịch đã rời {@code BOOKED} → 409. Trạng thái nguồn đi bằng API; mọi lần thử trong giờ
     * mở cửa, cùng chi nhánh, thú còn sống, để các guard 400 của check-in (đứng trước, {@code VisitCheckInService})
     * không che 409. Hủy lịch {@code CHECKED_IN} cũng 409: cạnh #8 chỉ dành cho Visit#6.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void leavingBookedRejectsRescheduleCancelAndCheckIn(String source) {
        long pet = data.pet(counterCustomer);
        long appointment = reach(source, pet);
        assertThat(appointment(appointment)).containsEntry("status", source);
        String xmin = text("SELECT xmin::text FROM appointments WHERE id = ?", appointment);
        long visits = count("SELECT count(*) FROM visits WHERE pet_id = ?", pet);
        long orders = count("SELECT count(*) FROM orders WHERE customer_id = ?", counterCustomer);

        assertConflict(post(receptionistToken, "/api/appointments/" + appointment + "/reschedule",
                slot(TODAY, LocalTime.of(16, 0))));
        assertConflict(post(receptionistToken, "/api/appointments/" + appointment + "/cancel", Map.of()));
        assertConflict(post(receptionistToken, "/api/visits", checkInBody(pet, appointment)));

        assertThat(text("SELECT xmin::text FROM appointments WHERE id = ?", appointment)).isEqualTo(xmin);
        assertThat(appointment(appointment)).containsEntry("status", source);
        assertThat(count("SELECT count(*) FROM visits WHERE pet_id = ?", pet)).isEqualTo(visits);
        assertThat(count("SELECT count(*) FROM orders WHERE customer_id = ?", counterCustomer)).isEqualTo(orders);
    }

    /** Đưa một lịch mới đặt (hôm nay 09:30) tới {@code source} chỉ bằng endpoint / job thật. */
    private long reach(String source, long pet) {
        long appointment = book(receptionistToken, pet, TODAY, LocalTime.of(9, 30));
        switch (source) {
            case "CHECKED_IN" -> checkIn(pet, appointment);
            case "COMPLETED" -> complete(checkIn(pet, appointment));
            case "CANCELLED" -> ok(post(receptionistToken, "/api/appointments/" + appointment + "/cancel", Map.of()));
            case "NO_SHOW" -> {
                clock.set(at(TODAY, 9, 30).plus(Duration.ofMinutes(
                        configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES) + 1)));
                noShowJob.run();
            }
            default -> throw new IllegalArgumentException(source);
        }
        return appointment;
    }

    // ================================================================ helpers — luồng

    private long book(String bearer, long pet, LocalDate date, LocalTime start) {
        Map<String, Object> body = new LinkedHashMap<>(slot(date, start));
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        ResponseEntity<Map<String, Object>> response = post(bearer, "/api/appointments", body);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return ((Number) map(response.getBody().get("data")).get("appointmentId")).longValue();
    }

    /** Visit#1 lúc 09:10 (trong cửa sổ check-in của khung 09:30). */
    private long checkIn(long pet, long appointment) {
        clock.set(at(TODAY, 9, 10));
        ResponseEntity<Map<String, Object>> response = post(receptionistToken, "/api/visits",
                checkInBody(pet, appointment));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return ((Number) map(response.getBody().get("data")).get("visitId")).longValue();
    }

    /** Visit#5: gán VET → gọi → ghi chẩn đoán → hoàn tất. */
    private long complete(long visit) {
        ok(post(receptionistToken, "/api/visits/" + visit + "/assign", Map.of("assigneeId", vet)));
        ok(post(vetToken, "/api/visits/" + visit + "/call", null));
        ok(exchange(HttpMethod.PUT, vetToken, "/api/visits/" + visit + "/medical-record",
                Map.of("examination", "Khám", "diagnosis", "Viêm da")));
        ok(post(vetToken, "/api/visits/" + visit + "/complete", null));
        return visit;
    }

    private static Map<String, Object> checkInBody(long pet, long appointment) {
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        return body;
    }

    private static Map<String, Object> slot(LocalDate date, LocalTime start) {
        return Map.of("slotDate", date.toString(), "slotStart", start.toString());
    }

    // ================================================================ helpers — dữ liệu

    private Map<String, Object> appointment(long id) {
        return jdbc.queryForMap("""
                SELECT code, status, channel, reschedule_count::int AS reschedule_count, slot_start::text AS slot_start, late_cancel, cancel_source
                FROM appointments WHERE id = ?""", id);
    }

    private String text(String sql, long id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AppointmentFsmIT")).accessToken();
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(VN).toInstant();
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

    private static void ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
    }

    private static void assertConflict(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("errorCode", "INVALID_STATE_TRANSITION").containsEntry("statusCode", 409);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
