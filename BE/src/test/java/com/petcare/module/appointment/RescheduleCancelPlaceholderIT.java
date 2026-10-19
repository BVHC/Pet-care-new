package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;

/**
<<<<<<< HEAD
 * Đổi khung / hủy trên bean thật của app hiện tại (docs/adr/0049). Từ 2026-10-20 {@code CustomerQueryApi},
 * {@code PetQueryApi} của BE-2 là bản thật (nợ D010, D031 đã trả) nên đổi khung / hủy sớm chạy thật; còn
 * {@code BoardingQueryApi.countViolations} (nợ D033) ném lỗi: hủy muộn trả 500 và <b>không để lại gì</b> (UPDATE đã flush
 * bị rollback, không dòng hạn chế, không khóa advisory). Nhánh dừng trước đó vẫn trả đúng mã (409, 400).
=======
 * Đổi khung / hủy trên bean thật của app hiện tại (docs/adr/0045): {@code BoardingQueryApi} (nợ D033) của BE-2 còn là
 * placeholder ném lỗi; {@code CustomerQueryApi}, {@code PetQueryApi} là bản thật từ 2026-10-19 (D010, D031 đã trả), nên
 * đổi khung và hủy sớm của lễ tân chạy được. Nhánh dừng trước khi cần
 * chúng trả đúng mã (409, 400); nhánh cần chúng trả 500 và <b>không để lại gì</b>: lịch vẫn y nguyên (UPDATE đã flush bị
 * rollback), không có dòng hạn chế, không còn khóa advisory.
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RescheduleCancelPlaceholderIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private long branch;
    private long medical;
    private long receptionist;
    private long customer;
    private String token;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer();
        token = token(receptionist);
        day = LocalDate.now(TimeConfig.BUSINESS_ZONE).plusDays(3);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    /** D010, D031 đã trả (2026-10-19): lễ tân đổi khung trên bean thật, không còn khóa advisory treo. */
    @Test
<<<<<<< HEAD
    void rescheduleRunsOnRealBeans() {
=======
    void receptionistReschedulesOnTheRealBeans() {
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
        long id = booked(day, LocalTime.of(8, 30));

        ResponseEntity<Map<String, Object>> response = post(token, id, "reschedule",
                "{\"slotDate\":\"" + day + "\",\"slotStart\":\"09:00\"}");

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
<<<<<<< HEAD
        assertThat(jdbc.queryForObject("SELECT slot_start::text FROM appointments WHERE id = ?", String.class, id))
                .isEqualTo("09:00:00");
=======
        assertThat(jdbc.queryForMap("SELECT slot_start::text AS slot_start, reschedule_count FROM appointments "
                + "WHERE id = ?", id)).containsEntry("slot_start", "09:00:00").containsEntry("reschedule_count", 1);
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'", Long.class))
                .isZero();
    }

    /** Hủy sớm (≥ 12 giờ) không cần đếm vi phạm lưu trú nên chạy được trên bean thật. */
    @Test
<<<<<<< HEAD
    void earlyCancelRunsOnRealBeans() {
=======
    void receptionistEarlyCancelOnTheRealBeans() {
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
        long id = booked(day, LocalTime.of(8, 30));

        ResponseEntity<Map<String, Object>> response = post(token, id, "cancel", null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
<<<<<<< HEAD
        assertThat(jdbc.queryForMap("SELECT status, late_cancel FROM appointments WHERE id = ?", id))
                .containsEntry("status", "CANCELLED").containsEntry("late_cancel", false);
=======
        assertThat(jdbc.queryForMap("SELECT status, cancel_source, late_cancel FROM appointments WHERE id = ?", id))
                .containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false);
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    }

    /** D033: hủy muộn cần đếm vi phạm lưu trú — placeholder ném lỗi → cả lần hủy rollback, không lưu cờ hủy muộn. */
    @Test
    void lateCancelRollsBackWhenBoardingIsNotBuilt() {
        ZonedDateTime soon = ZonedDateTime.now(TimeConfig.BUSINESS_ZONE).plusHours(2).truncatedTo(ChronoUnit.MINUTES);
        long id = booked(soon.toLocalDate(), soon.toLocalTime());

        ResponseEntity<Map<String, Object>> response = post(token, id, "cancel", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForMap("SELECT status, late_cancel FROM appointments WHERE id = ?", id))
                .containsEntry("status", "BOOKED").containsEntry("late_cancel", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                customer)).isZero();
    }

    @Test
    void branchesThatStopBeforeThePlaceholdersAnswerNormally() {
        long cancelled = booked(day, LocalTime.of(10, 0));
        jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_source = 'STAFF' WHERE id = ?", cancelled);
        assertThat(post(token, cancelled, "cancel", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(token, cancelled, "reschedule", "{\"slotDate\":\"" + day + "\",\"slotStart\":\"09:00\"}")
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        long full = booked(day, LocalTime.of(11, 0));
        jdbc.update("UPDATE appointments SET reschedule_count = 3 WHERE id = ?", full);
        ResponseEntity<Map<String, Object>> rule = post(token, full, "reschedule",
                "{\"slotDate\":\"" + day + "\",\"slotStart\":\"09:00\"}");
        assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) rule.getBody().get("message")).endsWith("(BR-LH-06)");

        assertThat(post(token(data.staff("VET", branch)), full, "cancel", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void customerAlwaysGets500AtTheOwnProfileLookup() {
        long id = booked(day, LocalTime.of(8, 30));

        assertThat(post(token(data.customerAccount()), id, "cancel", null).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id))
                .isEqualTo("BOOKED");
    }

    private long booked(LocalDate date, LocalTime start) {
        return data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", date, start, "BOOKED",
                receptionist);
    }

<<<<<<< HEAD

=======
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "RescheduleCancelPlaceholderIT"))
                .accessToken();
    }

    private ResponseEntity<Map<String, Object>> post(String token, long id, String action, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments/" + id + "/" + action),
                HttpMethod.POST, new HttpEntity<>(json, headers), MAP);
    }
}
