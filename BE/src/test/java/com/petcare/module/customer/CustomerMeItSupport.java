package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

/**
 * Dữ liệu và HTTP dùng chung cho IT của {@code /api/me/customer-profile}, {@code /api/me/addresses} (docs/adr/0028).
 * Tài khoản, hồ sơ chèn bằng SQL với email riêng mỗi test (không đụng dữ liệu của IT khác); token mở bằng
 * {@link SessionService#open} như {@code StaffProfileIT}.
 */
abstract class CustomerMeItSupport {

    static final AtomicInteger SEQ = new AtomicInteger();
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    /** {@code PESSIMISTIC_WRITE} trên PostgreSQL: Hibernate 6 sinh {@code FOR NO KEY UPDATE}. */
    static final String WAITING_ON_CUSTOMER_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from customers%for no key update%'
            """;

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionService sessions;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ---------------------------------------------------------------- dữ liệu

    /** Tài khoản CUSTOMER {@code ACTIVE} (không có SĐT — CHECK {@code ck_accounts_phone_by_role}). */
    long customerAccount(String email) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    long onlineProfile(long accountId, String fullName, String phone, String email, String avatarUrl,
            boolean linkPending) {
        return jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, email, avatar_url, created_channel,
                                       link_decision_pending)
                VALUES (?, ?, ?, ?, ?, 'ONLINE', ?) RETURNING id
                """, Long.class, accountId, fullName, phone, email, avatarUrl, linkPending);
    }

    long counterProfile(Long accountId, String fullName, String phone, String email) {
        return jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, email, created_channel)
                VALUES (?, ?, ?, ?, 'COUNTER') RETURNING id
                """, Long.class, accountId, fullName, phone, email);
    }

    long address(long customerId, String receiverName, boolean isDefault) {
        return jdbc.queryForObject("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, ward, province,
                                       is_default)
                VALUES (?, ?, '0901234567', 'Số 1', 'Phường 1', 'Hà Nội', ?) RETURNING id
                """, Long.class, customerId, receiverName, isDefault);
    }

    /** Nhân viên có SĐT và dòng {@code staff_profiles} (thiếu là dữ liệu sai). */
    long staffAccount() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'hash', 'SUPER_MANAGER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name) VALUES (?, 'Quản lý')", id);
        return id;
    }

    static String newEmail() {
        return "customer-me-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    String open(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "CustomerMeIT")).accessToken();
    }

    // ---------------------------------------------------------------- HTTP

    ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, bearer(token)), MAP);
    }

    ResponseEntity<Map<String, Object>> callRaw(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return map(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    static long number(Object value) {
        return ((Number) value).longValue();
    }

    static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }

    // ---------------------------------------------------------------- đồng thời

    /**
     * Transaction A khóa dòng {@code customers} ({@code FOR UPDATE}); các request chạy song song ở thread khác và phải
     * cùng đứng chờ khóa (thấy trong {@code pg_stat_activity}); sau đó A chạy {@code beforeCommit} rồi commit.
     */
    List<ResponseEntity<Map<String, Object>>> whileCustomerLocked(long customerId, Runnable beforeCommit,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM customers WHERE id = ? FOR UPDATE", Long.class,
                                customerId);
                        held.countDown();
                        await(proceed);
                        beforeCommit.run();
                    }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
            }
            awaitWaitingOnCustomerLock(requests.size());
            assertThat(calls).as("request phải chờ khóa dòng customers").noneMatch(Future::isDone);
            proceed.countDown();

            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                responses.add(call.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            proceed.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaitingOnCustomerLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_CUSTOMER_LOCK, Long.class) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("không đủ " + expected + " request chờ khóa customers; pg_stat_activity = "
                        + jdbc.queryForList("""
                                SELECT wait_event_type, state, query FROM pg_stat_activity
                                WHERE datname = current_database() AND pid <> pg_backend_pid()"""));
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Trigger tạm ném lỗi khi {@code condition} đúng (mẫu {@code LinkProfileIT.withTrigger}). */
    void withTrigger(String table, String timing, String condition, Runnable action) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_customer_fail_" + suffix;
        String trigger = "trg_it_customer_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN
                        RAISE EXCEPTION 'IT: lỗi giả lập';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, timing, table, function));
        try {
            action.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }
}
