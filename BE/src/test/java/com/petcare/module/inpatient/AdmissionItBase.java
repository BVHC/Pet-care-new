package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.identity.service.SessionService;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Nền chung của các IT nhập viện / đọc ca (inpatient-v1 #1–#3, docs/adr/0149 mục 16–21): dữ liệu qua
 * {@link InpatientItSupport}, token qua {@code SessionService.open} trong transaction, HTTP thật với body JSON thô. Mỗi
 * lớp con có era riêng (docs/architecture/testing-notes.md) và gọi {@link #begin} / {@link #finish}; {@link #finish}
 * dọn cả ba bộ đếm mã ({@code admissions}, {@code visits}, {@code ORDER}) của tháng era.
 */
abstract class AdmissionItBase {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    protected static final String ADMISSIONS = "/api/admissions";
    protected static final List<String> ADMISSION_KEYS = List.of("admissionId", "code", "visitId", "petId", "petName",
            "branchId", "boardingBookingId", "kennelId", "serviceId", "serviceName", "unitPrice", "status",
            "admittedAt", "billedDays", "estimatedAmount", "dischargeOutcome", "dischargeSummary", "dischargedAt",
            "orderLineId");
    protected static final List<String> SUMMARY_KEYS = List.of("admissionId", "code", "petName", "status",
            "admittedAt", "kennelCode", "billedDays", "estimatedAmount");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyMM");

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected SessionService sessions;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected MutableClock clock;

    protected InpatientItSupport data;
    protected TransactionTemplate tx;
    private String period;
    private final List<Long> looseAccounts = new ArrayList<>();

    protected void begin(Instant now) {
        clock.set(now);
        period = now.atZone(TimeConfig.BUSINESS_ZONE).format(PERIOD);
        data = new InpatientItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
    }

    protected void finish() {
        looseAccounts.forEach(this::deleteAccount);
        looseAccounts.clear();
        data.cleanUp();
        for (String docType : List.of(Admission.CODE_DOC_TYPE, "visits", OnlineSalesOrderApi.ORDER_DOC_TYPE)) {
            jdbc.update("DELETE FROM document_counters WHERE doc_type = ? AND period = ?", docType, period);
        }
    }

    protected String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AdmissionIT")).accessToken();
    }

    /** Body JSON thô ({@code null} = không có body); nháy đơn đổi sang nháy kép. */
    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String body = json == null ? null : json.replace('\'', '"');
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected ResponseEntity<Map<String, Object>> admit(String token, String json) {
        return call(HttpMethod.POST, ADMISSIONS, token, json);
    }

    protected ResponseEntity<Map<String, Object>> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    protected static String fromVisit(long visitId, long serviceId, long kennelId) {
        return "{'visitId':" + visitId + ",'serviceId':" + serviceId + ",'kennelId':" + kennelId + "}";
    }

    protected static String fromBooking(long bookingId, long serviceId) {
        return "{'boardingBookingId':" + bookingId + ",'serviceId':" + serviceId + "}";
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> contentOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) dataOf(response).get("content");
    }

    protected static String errorCode(ResponseEntity<Map<String, Object>> response) {
        return String.valueOf(response.getBody().get("errorCode"));
    }

    protected static String message(ResponseEntity<Map<String, Object>> response) {
        return String.valueOf(response.getBody().get("message"));
    }

    protected static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(message(response)).endsWith("(" + ruleId + ")");
    }

    protected static void assertStatus(ResponseEntity<Map<String, Object>> response, int status, String errorCode) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(errorCode(response)).isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------- dữ liệu

    /** Tài khoản khách (không hồ sơ nhân viên), dọn cùng sessions trong {@link #finish}. */
    protected long customerAccount() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'x', 'CUSTOMER', 'ACTIVE') RETURNING id""", Long.class,
                "ad-" + UUID.randomUUID() + "@example.com");
        looseAccounts.add(id);
        return id;
    }

    private void deleteAccount(long accountId) {
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id = ?)",
                accountId);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
    }

    /** Dịch vụ nội trú đang bật tại chi nhánh. */
    protected long enabledInpatientService(long branchId, long dailyPrice) {
        long service = data.inpatientService(dailyPrice);
        data.enable(branchId, service, true);
        return service;
    }

    protected int admissionCount(long petId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admissions WHERE pet_id = ?", Integer.class, petId);
    }

    protected int visitCount(long petId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visits WHERE pet_id = ?", Integer.class, petId);
    }

    protected int orderCount(long petId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ?)""",
                Integer.class, petId);
    }

    protected String kennelStatus(long kennelId) {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, kennelId);
    }

    protected String bookingStatus(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM boarding_bookings WHERE id = ?", String.class, bookingId);
    }

    protected long bookingKennel(long bookingId) {
        return jdbc.queryForObject("SELECT kennel_id FROM boarding_bookings WHERE id = ?", Long.class, bookingId);
    }

    protected String kennelCode(long kennelId) {
        return jdbc.queryForObject("SELECT code FROM kennels WHERE id = ?", String.class, kennelId);
    }

    protected Integer counter(String docType) {
        List<Integer> values = jdbc.queryForList(
                "SELECT last_value FROM document_counters WHERE doc_type = ? AND period = ?", Integer.class, docType,
                period);
        return values.isEmpty() ? 0 : values.get(0);
    }

    /** Dấu vết ghi của một thú: số ca, số lượt, số Order — dùng cho "không ghi gì". */
    protected String footprint(long petId) {
        return admissionCount(petId) + "/" + visitCount(petId) + "/" + orderCount(petId);
    }

    protected static long idOf(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }
}
