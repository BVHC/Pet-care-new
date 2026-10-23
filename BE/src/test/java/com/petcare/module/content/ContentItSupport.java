package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * Dữ liệu và HTTP dùng chung cho IT module content (docs/adr/0071), era năm 2004. Mỗi lớp IT tạo chi nhánh, tài khoản,
 * khách riêng (tên / email UUID) bằng SQL; {@link #cleanContent()} xóa con trước theo FK, chỉ những gì lớp IT đã tạo,
 * và trả bốn trang tĩnh về bản seed của V21.
 */
public abstract class ContentItSupport {

    /** Đồng hồ cho IT content: {@link #START} = 2004-03-10 (thứ tư) 10:00 giờ Việt Nam. */
    @TestConfiguration
    public static class ContentClockConfig {
        public static final Instant START = Instant.parse("2004-03-10T03:00:00Z");

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    protected static final AtomicInteger SEQ = new AtomicInteger();
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

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

    private final List<Long> branches = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();
    private final List<Long> customers = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();

    @BeforeEach
    void resetContentClock() {
        clock.set(ContentClockConfig.START);
    }

    @AfterEach
    void cleanContent() {
        if (!customers.isEmpty()) {
            jdbc.update("DELETE FROM feedbacks WHERE customer_id IN (" + ids(customers) + ")");
        }
        if (!accounts.isEmpty()) {
            jdbc.update("DELETE FROM feedbacks WHERE resolved_by IN (" + ids(accounts) + ")");
            jdbc.update("DELETE FROM articles WHERE created_by IN (" + ids(accounts) + ")");
        }
        if (!categories.isEmpty()) {
            jdbc.update("DELETE FROM articles WHERE category_id IN (" + ids(categories) + ")");
            jdbc.update("DELETE FROM article_categories WHERE id IN (" + ids(categories) + ")");
        }
        jdbc.update("DELETE FROM article_categories WHERE name LIKE 'ContentIT %'");
        jdbc.update("UPDATE page_contents SET title = NULL, content = '{}'::jsonb, updated_by = NULL");
        if (!customers.isEmpty()) {
            jdbc.update("DELETE FROM customers WHERE id IN (" + ids(customers) + ")");
        }
        if (!accounts.isEmpty()) {
            String a = ids(accounts);
            jdbc.update("DELETE FROM sessions WHERE account_id IN (" + a + ")");
            jdbc.update("DELETE FROM staff_profiles WHERE account_id IN (" + a + ")");
            jdbc.update("DELETE FROM accounts WHERE id IN (" + a + ")");
        }
        if (!branches.isEmpty()) {
            jdbc.update("DELETE FROM branches WHERE id IN (" + ids(branches) + ")");
        }
        branches.clear();
        accounts.clear();
        customers.clear();
        categories.clear();
    }

    private static String ids(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    // ---------------------------------------------------------------- dữ liệu

    protected long newBranch(String status) {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, ?) RETURNING id
                """, Long.class, "ContentIT chi nhánh " + UUID.randomUUID(), status);
        branches.add(id);
        return id;
    }

    /** Nhân viên {@code ACTIVE}; A05–A08 thuộc {@code branchId}, ADMIN / SUPER_MANAGER không có chi nhánh. */
    protected long staff(String role, Long branchId) {
        boolean scoped = role.equals("BRANCH_MANAGER") || role.equals("RECEPTIONIST") || role.equals("VET")
                || role.equals("CARETAKER");
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "content-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test",
                role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                "Content IT " + role, scoped ? branchId : null);
        accounts.add(id);
        return id;
    }

    /** Khách có tài khoản {@code ACTIVE} + hồ sơ ONLINE; trả {@code [accountId, customerId]}. */
    protected long[] onlineCustomer(boolean linkDecisionPending) {
        String email = "content-it-c-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email);
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, email, created_channel, link_decision_pending)
                VALUES (?, ?, ?, 'ONLINE', ?) RETURNING id
                """, Long.class, account, "Khách content IT " + SEQ.get(), email, linkDecisionPending);
        accounts.add(account);
        customers.add(customer);
        return new long[] {account, customer};
    }

    protected long newCategory(String suffix, boolean hidden) {
        long id = jdbc.queryForObject("""
                INSERT INTO article_categories (name, slug, is_hidden) VALUES (?, ?, ?) RETURNING id
                """, Long.class, "ContentIT " + suffix + " " + UUID.randomUUID(),
                "ct-" + UUID.randomUUID().toString().substring(0, 12), hidden);
        categories.add(id);
        return id;
    }

    protected String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "ContentIT")).accessToken();
    }

    // ---------------------------------------------------------------- HTTP

    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected ResponseEntity<Map<String, Object>> callRaw(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return map(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> list(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> pageContent(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    protected static long number(Object value) {
        return ((Number) value).longValue();
    }

    protected static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    protected static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }
}
