package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;

/**
 * Nhật ký chăm sóc, bổ sung, báo bất thường (boarding-v1 #19–22; Đặt chỗ#8; docs/adr/0085). Era 2009, mọi thao tác trong
 * cùng ngày để phiên không hết hạn; hạn sửa 60 phút [CFG] kiểm bằng cách đẩy đồng hồ vài phút.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
@SuppressWarnings("unchecked")
class CareLogIT extends BoardingItSupport {

    @Autowired
    private SystemConfigService configs;

    private long branch;
    private long type;
    private long kennel;
    private long vetId;
    private String vet;
    private String caretaker;
    private long caretakerId;
    private long receptionistA;
    private long receptionistB;
    private long customerAccount;
    private long customerId;
    private long pet;
    private long stay;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        kennel = newKennel(branch, type, "C-01", "OCCUPIED");
        vetId = newStaff("VET", branch);
        vet = token(vetId);
        caretakerId = newStaff("CARETAKER", branch);
        caretaker = token(caretakerId);
        receptionistA = newStaff("RECEPTIONIST", branch);
        receptionistB = newStaff("RECEPTIONIST", branch);
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        customerId = online[1];
        pet = newPet(customerId, "DOG");
        stay = newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-09"), LocalDate.parse("2009-06-12"),
                "CHECKED_IN", kennel, receptionistA);
        jdbc.update("UPDATE boarding_bookings SET actual_check_in_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2009-06-09T03:00:00Z")), stay);
    }

    private Map<String, Object> body(Object abnormal, String... urls) {
        Map<String, Object> body = new HashMap<>();
        body.put("eating", "Ăn hết");
        body.put("drinking", "Uống đủ");
        body.put("note", "Ổn");
        body.put("photoUrls", List.of(urls));
        body.put("isAbnormal", abnormal);
        return body;
    }

    private ResponseEntity<Map<String, Object>> create(String token, long bookingId, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + bookingId + "/care-logs", token, body);
    }

    private ResponseEntity<Map<String, Object>> getLogs(String token, long bookingId) {
        return call(HttpMethod.GET, "/api/boarding-bookings/" + bookingId + "/care-logs", token, null);
    }

    private List<Map<String, Object>> logs(String token, long bookingId) {
        return list(getLogs(token, bookingId), HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> update(String token, long logId, Map<String, Object> body) {
        return call(HttpMethod.PATCH, "/api/care-logs/" + logId, token, body);
    }

    private ResponseEntity<Map<String, Object>> addendum(String token, long logId, String content) {
        return call(HttpMethod.POST, "/api/care-logs/" + logId + "/addenda", token, Map.of("content", content));
    }

    private long createdLog(String token) {
        return number(data(create(token, stay, body(false)), HttpStatus.CREATED).get("careLogId"));
    }

    private List<Map<String, Object>> outbox(String code) {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, recipient_email FROM notification_outbox
                WHERE payload ->> 'ma_dat_cho' = ?""", code);
    }

    private String bookingCode() {
        return jdbc.queryForObject("SELECT code FROM boarding_bookings WHERE id = ?", String.class, stay);
    }

    // ---------------------------------------------------------------- ghi

    @Test
    void createRecordsTheLogAndClearsTheMissingTodayFlag() {
        Map<String, Object> before = data(call(HttpMethod.GET, "/api/boarding-bookings/" + stay, vet, null),
                HttpStatus.OK);
        assertThat(before).containsEntry("missingCareLogToday", true);

        Map<String, Object> data = data(create(caretaker, stay, body(false, "https://cdn.test/a.jpg")),
                HttpStatus.CREATED);

        assertThat(data).containsEntry("logDate", "2009-06-10").containsEntry("isAbnormal", false)
                .containsEntry("eating", "Ăn hết").containsEntry("recordedByName", "Boarding IT CARETAKER");
        assertThat(number(data.get("recordedBy"))).isEqualTo(caretakerId);
        assertThat((List<Object>) data.get("photoUrls")).containsExactly("https://cdn.test/a.jpg");
        assertThat(Instant.parse((String) data.get("editableUntil")))
                .isEqualTo(Instant.parse((String) data.get("createdAt")).plus(Duration.ofMinutes(60)));
        Map<String, Object> after = data(call(HttpMethod.GET, "/api/boarding-bookings/" + stay, vet, null),
                HttpStatus.OK);
        assertThat(after).containsEntry("missingCareLogToday", false);
        assertThat(outbox(bookingCode())).isEmpty();
    }

    @Test
    void abnormalLogNotifiesTheCustomerAndEveryReceptionist() {
        data(create(vet, stay, body(true)), HttpStatus.CREATED);

        List<Map<String, Object>> rows = outbox(bookingCode());
        assertThat(rows).extracting(r -> r.get("template_code")).containsExactlyInAnyOrder("CARE_LOG_ABNORMAL",
                "CARE_LOG_ABNORMAL_APP", "CARE_LOG_ABNORMAL_APP", "CARE_LOG_ABNORMAL_APP");
        assertThat(rows).filteredOn(r -> "IN_APP".equals(r.get("channel")))
                .extracting(r -> ((Number) r.get("recipient_account_id")).longValue())
                .containsExactlyInAnyOrder(customerAccount, receptionistA, receptionistB);
    }

    @Test
    void rolesAndScopeOfCreate() {
        String receptionist = token(receptionistA);
        String foreign = token(newStaff("CARETAKER", newBranch()));

        assertError(create(receptionist, stay, body(false)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(create(token(customerAccount), stay, body(false)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(create(foreign, stay, body(false)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(create(vet, 999_999_999L, body(false)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void bookingMustBeInStay() {
        long booked = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-20"),
                LocalDate.parse("2009-06-22"), "BOOKED", receptionistA);
        long out = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-01"),
                LocalDate.parse("2009-06-03"), "CHECKED_OUT", kennel, receptionistA);

        assertError(create(vet, booked, body(false)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(create(vet, out, body(false)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void photosAndContentAndDateRules() {
        assertRule(create(vet, stay, body(false, "https://a/1", "https://a/2", "https://a/3", "https://a/4",
                "https://a/5", "https://a/6")), "BR-LT-11");
        data(create(vet, stay, body(false, "https://a/1", "https://a/2", "https://a/3", "https://a/4", "https://a/5")),
                HttpStatus.CREATED);
        assertError(create(vet, stay, body(false, "javascript:alert(1)")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        Map<String, Object> empty = new HashMap<>();
        empty.put("photoUrls", List.of());
        assertError(create(vet, stay, empty), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        Map<String, Object> future = body(false);
        future.put("logDate", "2009-06-11");
        assertError(create(vet, stay, future), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        Map<String, Object> beforeStay = body(false);
        beforeStay.put("logDate", "2009-06-08");
        assertError(create(vet, stay, beforeStay), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        Map<String, Object> yesterday = body(false);
        yesterday.put("logDate", "2009-06-09");
        data(create(vet, stay, yesterday), HttpStatus.CREATED);
    }

    @Test
    void failureWhileEnqueueingRollsTheLogBack() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION care_log_it_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'care log it forced failure'; END $$""");
        jdbc.execute("CREATE TRIGGER care_log_it_fail BEFORE INSERT ON notification_outbox FOR EACH ROW "
                + "EXECUTE FUNCTION care_log_it_fail()");
        try {
            ResponseEntity<Map<String, Object>> response = create(vet, stay, body(true));

            assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM care_logs WHERE boarding_booking_id = ?",
                    Integer.class, stay)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER care_log_it_fail ON notification_outbox");
            jdbc.execute("DROP FUNCTION care_log_it_fail()");
        }
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void authorEditsWithinTheWindowKeepingUnsentFieldsAndBlankClears() {
        long id = createdLog(vet);
        clock.advance(Duration.ofMinutes(59).plusSeconds(59));

        Map<String, Object> patch = new HashMap<>();
        patch.put("eating", "Bỏ ăn");
        patch.put("note", "  ");
        Map<String, Object> data = data(update(vet, id, patch), HttpStatus.OK);

        assertThat(data).containsEntry("eating", "Bỏ ăn").containsEntry("drinking", "Uống đủ")
                .containsEntry("isAbnormal", false);
        assertThat(data.get("note")).isNull();
    }

    @Test
    void editAtExactlyTheWindowIsConflictBrLt11AndOthersAreForbidden() {
        long id = createdLog(vet);

        assertError(update(caretaker, id, Map.of("eating", "x")), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        clock.advance(Duration.ofMinutes(60));
        ResponseEntity<Map<String, Object>> late = update(vet, id, Map.of("eating", "x"));

        assertError(late, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat((String) late.getBody().get("message")).endsWith("(BR-LT-11)");
        assertError(update(vet, 999_999_999L, Map.of("eating", "x")), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void lowerPhotoLimitDoesNotBlockEditingOtherFields() {
        data(create(vet, stay, body(false, "https://a/1", "https://a/2", "https://a/3")), HttpStatus.CREATED);
        long id = number(logs(vet, stay).get(0).get("careLogId"));
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'care_log.max_photos'",
                String.class);
        try {
            jdbc.update("UPDATE system_configs SET value = '1' WHERE key = 'care_log.max_photos'");
            configs.reload();

            data(update(vet, id, Map.of("eating", "Đổi")), HttpStatus.OK);
            assertRule(update(vet, id, Map.of("photoUrls", List.of("https://a/1", "https://a/2"))), "BR-LT-11");
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'care_log.max_photos'", original);
            configs.reload();
        }
    }

    // ---------------------------------------------------------------- bổ sung và xem

    @Test
    void addendumIsAllowedAfterTheEditWindowAndListedInOrder() {
        long id = createdLog(vet);
        clock.advance(Duration.ofMinutes(61));
        String caretakerLater = token(caretakerId);

        Map<String, Object> first = data(addendum(caretakerLater, id, " Chiều ăn lại "), HttpStatus.CREATED);
        data(addendum(token(vetId), id, "Đã kiểm tra"), HttpStatus.CREATED);

        assertThat(first).containsEntry("content", "Chiều ăn lại");
        assertThat(number(first.get("createdBy"))).isEqualTo(caretakerId);
        List<Map<String, Object>> logs = logs(token(vetId), stay);
        assertThat(((List<?>) ((Map<?, ?>) logs.get(0)).get("addenda"))).hasSize(2);
        assertError(addendum(token(vetId), id, "  "), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(addendum(token(newStaff("CARETAKER", newBranch())), id, "x"), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(addendum(token(vetId), 999_999_999L, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void customerSeesOwnLogsImmediatelyOthersGetNotFound() {
        createdLog(vet);
        String other = token(newOnlineCustomer()[0]);

        List<Map<String, Object>> own = logs(token(customerAccount), stay);
        assertThat(own).hasSize(1);
        assertError(getLogs(other, stay), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(getLogs(token(newStaff("VET", newBranch())), stay), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(logs(token(receptionistA), stay)).hasSize(1);
    }

}
