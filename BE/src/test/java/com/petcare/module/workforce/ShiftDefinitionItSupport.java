package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.petcare.support.MutableClock;

/**
 * Nền chung của các IT ca làm việc (workforce-v1 #1–3, docs/adr/0149): dữ liệu qua {@link WorkforceItSupport}, token
 * qua {@code SessionService.open} trong transaction, gọi HTTP thật với body JSON thô. Mỗi lớp con có era riêng (xem
 * docs/architecture/testing-notes.md) và gọi {@link #begin} / {@link #finish}.
 */
abstract class ShiftDefinitionItSupport {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    protected static final String PATH = "/api/shift-definitions";

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

    protected WorkforceItSupport data;
    protected TransactionTemplate tx;
    private final List<Long> looseAccounts = new ArrayList<>();

    protected void begin(Instant now) {
        clock.set(now);
        data = new WorkforceItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
    }

    /** Xóa tài khoản tạo riêng, rồi dòng workforce và chi nhánh (mọi FK là RESTRICT). */
    protected void finish() {
        for (Long id : looseAccounts) {
            jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id = ?)", id);
            jdbc.update("DELETE FROM sessions WHERE account_id = ?", id);
            jdbc.update("DELETE FROM accounts WHERE id = ?", id);
        }
        looseAccounts.clear();
        data.cleanUp();
    }

    protected String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "ShiftDefinitionIT")).accessToken();
    }

    /** Tài khoản khách (không có hồ sơ nhân viên); xóa trong {@link #finish}. */
    protected long customerAccount() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'x', 'CUSTOMER', 'ACTIVE') RETURNING id""", Long.class,
                "sd-" + UUID.randomUUID() + "@example.com");
        looseAccounts.add(id);
        return id;
    }

    /** Gọi với body JSON thô ({@code null} = không có body). Dùng dấu nháy đơn trong chuỗi, đổi sang nháy kép. */
    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String body = json == null ? null : json.replace('\'', '"');
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token) {
        return call(method, path, token, null);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> listOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    protected static String errorCode(ResponseEntity<Map<String, Object>> response) {
        return String.valueOf(response.getBody().get("errorCode"));
    }

    protected static String message(ResponseEntity<Map<String, Object>> response) {
        return String.valueOf(response.getBody().get("message"));
    }

    protected long definition(long branchId, String name, String start, String end, boolean active) {
        return data.definition(branchId, name, LocalTime.parse(start), LocalTime.parse(end), active);
    }

    protected String xmin(long definitionId) {
        return jdbc.queryForObject("SELECT xmin::text FROM shift_definitions WHERE id = ?", String.class, definitionId);
    }

    protected int auditCount(long definitionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'shift_definitions' AND entity_id = ?""",
                Integer.class, definitionId);
    }

    protected int definitionCount(long branchId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_definitions WHERE branch_id = ?", Integer.class, branchId);
    }

    protected int auditCountForBranch(long branchId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs a JOIN shift_definitions d ON d.id = a.entity_id
                WHERE a.entity_type = 'shift_definitions' AND d.branch_id = ?""", Integer.class, branchId);
    }

    protected Map<String, Object> row(long definitionId) {
        return jdbc.queryForMap("""
                SELECT branch_id, name, start_time::text AS start_time, end_time::text AS end_time, is_active
                FROM shift_definitions WHERE id = ?""", definitionId);
    }

    protected long scheduled(long branchId, long staffId, long definitionId, LocalDate day) {
        return data.assignment(branchId, staffId, definitionId, day, "SCHEDULED", "MANAGER", null);
    }

    protected static void assertBusinessRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(message(response)).endsWith("(" + ruleId + ")");
    }
}
