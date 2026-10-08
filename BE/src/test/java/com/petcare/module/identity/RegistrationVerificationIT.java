package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;

/**
 * {@code POST /api/auth/register/verify} (UC02, Tài khoản#2) qua HTTP trên Postgres 17 thật. Đăng ký thật qua
 * {@code /api/auth/register}, lấy mã gốc từ {@code notification_outbox.payload->>'ma_otp'} (nợ D003: chưa có worker
 * gửi). {@link CustomerApi} là mock (nợ D001): {@code createOnlineProfile} chèn {@code customers} thật,
 * {@code flagLinkDecisionIfPhoneMatches} trả {@code true} và ghi cờ thật — cùng transaction, nên kiểm được rollback.
 * Trọng tâm: lần nhập sai trả 400 <b>và</b> bộ đếm vẫn được commit (docs/adr/0010).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RegistrationVerificationIT.TestBeans.class})
class RegistrationVerificationIT {

    private static final Instant T0 = Instant.parse("2026-10-06T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** Clock dịch được (như {@code AuthenticationIT}) để kiểm hạn mã. */
    static final class MutableClock extends Clock {
        private volatile Instant now = T0;

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Ho_Chi_Minh");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @MockitoBean
    private CustomerApi customerApi;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        doAnswer(invocation -> jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, ?, 'ONLINE') RETURNING id
                """, Long.class, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                .when(customerApi).createOnlineProfile(any(), any(), any());
        doAnswer(invocation -> {
            jdbc.update("UPDATE customers SET link_decision_pending = true WHERE account_id = ?",
                    (Object) invocation.getArgument(0));
            return true;
        }).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void correctCodeActivatesAccountConsumesCodeAndFlagsLinkDecision() {
        String email = register();
        String code = code(email);

        ResponseEntity<Map<String, Object>> response = verify(email, code, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200)
                .containsEntry("message", "Xác thực tài khoản thành công");
        Map<String, Object> data = data(response);
        assertThat(((Number) data.get("accountId")).longValue()).isEqualTo(accountId(email));
        assertThat(data).containsEntry("status", "ACTIVE").containsEntry("linkDecisionPending", true);

        assertThat(status(email)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT pending_expires_at FROM accounts WHERE email = ?", java.sql.Timestamp.class,
                email)).as("hết PENDING thì bỏ hạn (ck_accounts_pending_expiry)").isNull();
        Map<String, Object> otp = otp(email);
        assertThat(((java.sql.Timestamp) otp.get("consumed_at")).toInstant()).isEqualTo(T0);
        assertThat(otp).containsEntry("failed_attempts", 0);
        assertThat(otp.get("invalidated_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT link_decision_pending FROM customers WHERE account_id = ?",
                Boolean.class, accountId(email))).as("BR-TK-19: hệ quả commit cùng transaction").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?",
                Long.class, accountId(email))).as("UC02 không ghi audit").isZero();
    }

    @Test
    void linkDecisionPendingFalseIsReturnedAsIs() {
        doReturn(false).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());
        String email = register();

        assertThat(data(verify(email, code(email), null))).containsEntry("linkDecisionPending", false);
    }

    @Test
    void emailIsLowercasedLikeRegistration() {
        String email = register();

        assertThat(verify(email.toUpperCase(), code(email), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(email)).isEqualTo("ACTIVE");
    }

    @Test
    void publicPathIgnoresGarbageToken() {
        String email = register();

        assertThat(verify(email, code(email), "not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- BR-TK-05, 06: bộ đếm phải được commit

    @Test
    void wrongCodeIsRejectedWith400AndFailedAttemptIsCommitted() {
        String email = register();

        assertError(verify(email, wrong(code(email)), null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "OTP không đúng hoặc đã hết hạn (BR-TK-05)");

        assertThat(otp(email)).as("docs/adr/0010: lần sai còn lại sau khi request lỗi")
                .containsEntry("failed_attempts", 1);
        assertThat(otp(email).get("invalidated_at")).isNull();
        assertThat(otp(email).get("consumed_at")).isNull();
        assertThat(status(email)).isEqualTo("PENDING");
    }

    @Test
    void fifthWrongCodeCancelsCodeSoCorrectCodeNoLongerWorks() {
        String email = register();
        String code = code(email);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(verify(email, wrong(code), null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                    "(BR-TK-05)");
            assertThat(otp(email)).containsEntry("failed_attempts", attempt);
        }
        assertError(verify(email, wrong(code), null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Nhập sai mã OTP quá 5 lần, mã đã bị hủy. Vui lòng yêu cầu mã mới (BR-TK-06)");
        assertThat(otp(email)).containsEntry("failed_attempts", 5);
        assertThat(((java.sql.Timestamp) otp(email).get("invalidated_at")).toInstant()).isEqualTo(T0);

        assertError(verify(email, code, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(otp(email)).as("mã đã hủy: không đếm thêm").containsEntry("failed_attempts", 5);
        assertThat(status(email)).isEqualTo("PENDING");
    }

    @Test
    void codeWorksUntilJustBeforeExpiry() {
        String email = register();
        clock.advance(Duration.ofMinutes(5).minusMillis(1));

        assertThat(verify(email, code(email), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void codeIsExpiredAtExactlyTtl() {
        String email = register();
        clock.advance(Duration.ofMinutes(5));

        assertError(verify(email, code(email), null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(otp(email)).containsEntry("failed_attempts", 0);
        assertThat(status(email)).isEqualTo("PENDING");
    }

    @Test
    void concurrentWrongCodesAreBothCounted() throws Exception {
        String email = register();
        String wrongCode = wrong(code(email));
        doAnswer(invocation -> {
            Thread.sleep(300); // giữ transaction sau khi đọc mã, trước khi ghi bộ đếm
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return verify(email, wrongCode, null);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                assertError(future.get(30, TimeUnit.SECONDS), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                        "(BR-TK-05)");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(otp(email)).as("docs/adr/0011: khóa tài khoản nên không mất lần đếm (BR-TK-06)")
                .containsEntry("failed_attempts", 2);
    }

    // ---------------------------------------------------------------- 404

    @Test
    void secondVerifyAfterSuccessIsNotFound() {
        String email = register();
        String code = code(email);
        assertThat(verify(email, code, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(verify(email, code, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
    }

    @Test
    void unknownEmailIsNotFound() {
        assertError(verify(newEmail(), "123456", null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
    }

    // ---------------------------------------------------------------- hình thức

    @Test
    void malformedCodeIsRejectedBeforeCountingAnAttempt() throws Exception {
        String email = register();

        ResponseEntity<Map<String, Object>> nonDigits = verifyRaw(json.writeValueAsString(
                Map.of("email", email, "code", "abc")), null);
        assertError(nonDigits, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) nonDigits.getBody().get("message")).contains("code: Mã OTP chỉ gồm tối đa 8 chữ số");

        ResponseEntity<Map<String, Object>> noEmail = verifyRaw(json.writeValueAsString(Map.of("code", "123456")),
                null);
        assertError(noEmail, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) noEmail.getBody().get("message")).contains("email: Email không được để trống");

        assertThat(otp(email)).containsEntry("failed_attempts", 0);
    }

    // ---------------------------------------------------------------- lỗi giữa chừng

    @Test
    void failureAfterConsumingCodeRollsBackEverythingAndCodeStaysUsable() {
        String email = register();
        String code = code(email);
        doAnswer(invocation -> {
            jdbc.update("UPDATE customers SET link_decision_pending = true WHERE account_id = ?",
                    (Object) invocation.getArgument(0));
            throw new UnsupportedOperationException("CustomerApi chưa được cài — nợ D001");
        }).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());

        assertError(verify(email, code, null), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);
        assertThat(status(email)).isEqualTo("PENDING");
        assertThat(otp(email).get("consumed_at")).as("rollback cả lệnh dùng mã").isNull();
        assertThat(jdbc.queryForObject("SELECT link_decision_pending FROM customers WHERE account_id = ?",
                Boolean.class, accountId(email))).isFalse();

        doReturn(false).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());
        assertThat(verify(email, code, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(email)).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- helpers

    /** Đăng ký qua HTTP, trả email (chữ thường). */
    private String register() {
        String email = newEmail();
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "abc12345");
        body.put("fullName", "Nguyễn Văn A");
        body.put("phone", "0901234567");
        body.put("isAdult", true);
        body.put("termsAccepted", true);
        ResponseEntity<Map<String, Object>> response = post("/api/auth/register", write(body), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    /** Mã gốc nằm trong payload outbox (ADR-0009, nợ D003). */
    private String code(String email) {
        return jdbc.queryForObject("SELECT payload->>'ma_otp' FROM notification_outbox WHERE recipient_email = ?",
                String.class, email);
    }

    /** Mã khác mã đúng, cùng độ dài. */
    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private ResponseEntity<Map<String, Object>> verify(String email, String code, String token) {
        return verifyRaw(write(Map.of("email", email, "code", code)), token);
    }

    private ResponseEntity<Map<String, Object>> verifyRaw(String body, String token) {
        return post("/api/auth/register/verify", body, token);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", Long.class, email);
    }

    private String status(String email) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE email = ?", String.class, email);
    }

    private Map<String, Object> otp(String email) {
        return jdbc.queryForMap("SELECT * FROM otp_tokens WHERE target_email = ? AND purpose = 'REGISTER'", email);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    /** Envelope lỗi đủ 6 trường; {@code traceId} trùng header {@code X-Trace-Id} (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode, String messageSuffix) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
        if (messageSuffix != null) {
            assertThat((String) body.get("message")).endsWith(messageSuffix);
        }
    }

    private static String newEmail() {
        return "verify-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@petcare.test";
    }
}
