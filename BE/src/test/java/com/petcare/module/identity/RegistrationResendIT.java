package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.sql.Timestamp;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;

/**
 * {@code POST /api/auth/register/resend-otp} (UC02 — gửi lại OTP, BR-TK-04, 05, 07) qua HTTP trên Postgres 17 thật.
 * Đăng ký thật qua {@code /api/auth/register}, mã gốc lấy từ outbox (nợ D003). {@code otp_tokens.created_at} theo bean
 * {@code Clock} (docs/adr/0015): BR-TK-07 kiểm được bằng cách dịch {@link MutableClock}
 * ({@code quotaFollowsInjectedClockWithoutBackdating}); các case khác đặt {@code created_at} tuyệt đối cho gọn.
 * Khóa dòng {@code accounts} (docs/adr/0011): resend song song resend, resend song song verify.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RegistrationResendIT.TestBeans.class})
class RegistrationResendIT {

    private static final Instant T0 = Instant.parse("2026-10-06T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** Clock dịch được (như {@code RegistrationVerificationIT}). */
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

    /** Không phải proxy AOP nên spy được; {@code issueOtp} gọi {@code encode} sau khi vô hiệu mã cũ (OtpService). */
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
        doAnswer(invocation -> false).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void resendAfterIntervalIssuesNewCodeAndOldCodeStopsWorking() {
        String email = register();
        String oldCode = latestCode(email);
        Instant accountCreatedAt = accountCreatedAt(email);
        long auditBefore = auditCount(email);
        sentSecondsAgo(email, 60);

        ResponseEntity<Map<String, Object>> response = resend(email, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200)
                .containsEntry("message", "Đã gửi lại mã OTP, vui lòng kiểm tra email");
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("resendAvailableAt", "maskedEmail").containsEntry("maskedEmail", null);
        assertThat(Instant.parse((String) data.get("resendAvailableAt"))).as("BR-TK-07: mốc gửi lại = now + 60 s")
                .isEqualTo(T0.plusSeconds(60));

        List<Map<String, Object>> codes = codes(email);
        assertThat(codes).hasSize(2);
        assertThat(((Timestamp) codes.get(0).get("invalidated_at")).toInstant())
                .as("BR-TK-05: mã cũ mất hiệu lực ngay").isEqualTo(T0);
        assertThat(codes.get(1).get("invalidated_at")).isNull();
        assertThat(((Timestamp) codes.get(1).get("expires_at")).toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(5)));

        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT template_code, channel, recipient_account_id, payload::text AS payload
                FROM notification_outbox WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, email);
        assertThat(outbox).containsEntry("template_code", "OTP_REGISTER").containsEntry("channel", "EMAIL")
                .containsEntry("recipient_account_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT array_to_string(ARRAY(SELECT jsonb_object_keys(payload) ORDER BY 1), ',')
                FROM notification_outbox WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, String.class, email)).as("BR-QT-14: không có ten_khach").isEqualTo("ma_otp,thoi_han_phut");
        assertThat(outboxCount(email)).isEqualTo(2);

        assertThat(status(email)).as("resend không chuyển trạng thái").isEqualTo("PENDING");
        assertThat(accountCreatedAt(email)).as("BR-TK-08: không kéo dài hạn PENDING").isEqualTo(accountCreatedAt);
        assertThat(auditCount(email)).as("BR-QT-15: không audit").isEqualTo(auditBefore);

        assertError(verify(email, oldCode), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(verify(email, latestCode(email)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(email)).isEqualTo("ACTIVE");
    }

    @Test
    void emailIsLowercasedLikeRegistration() {
        String email = register();
        sentSecondsAgo(email, 60);

        assertThat(resend(email.toUpperCase(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(email)).hasSize(2);
    }

    @Test
    void publicPathIgnoresGarbageToken() {
        String email = register();
        sentSecondsAgo(email, 60);

        assertThat(resend(email, "not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resendRevivesFlowAfterCodeCancelledByWrongAttempts() {
        String email = register();
        String code = latestCode(email);
        for (int attempt = 1; attempt <= 5; attempt++) {
            verify(email, wrong(code));
        }
        assertThat(codes(email).get(0).get("invalidated_at")).as("BR-TK-06: mã đã bị hủy").isNotNull();
        sentSecondsAgo(email, 60);

        assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(email).get(1)).containsEntry("failed_attempts", 0);
        assertThat(verify(email, latestCode(email)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resendAfterExpiryWorks() {
        String email = register();
        clock.advance(Duration.ofMinutes(5));
        assertError(verify(email, latestCode(email)), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "(BR-TK-05)");
        sentSecondsAgo(email, 60);

        assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify(email, latestCode(email)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- BR-TK-07

    @Test
    void resendAt59SecondsIsRejectedAndNothingWritten() {
        String email = register();
        String code = latestCode(email);
        sentSecondsAgo(email, 59);

        assertError(resend(email, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Vui lòng chờ 1 giây trước khi gửi lại mã OTP (BR-TK-07)");

        assertThat(codes(email)).hasSize(1);
        assertThat(codes(email).get(0).get("invalidated_at")).as("rollback: mã cũ còn hiệu lực").isNull();
        assertThat(outboxCount(email)).isEqualTo(1);
        assertThat(verify(email, code).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sixthCodeInWindowIsRejected() {
        String email = register();
        for (int sent = 2; sent <= 5; sent++) {
            sentSecondsAgo(email, 61);
            assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(codes(email)).hasSize(5);
        sentSecondsAgo(email, 61);

        // 5 mã cùng gửi cách đây 61 s: mã cũ nhất ra khỏi cửa sổ 60 phút sau 59 phút (làm tròn lên).
        assertError(resend(email, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Đã gửi quá nhiều mã OTP tới email này, vui lòng thử lại sau 59 phút (BR-TK-07)");
        assertThat(codes(email)).hasSize(5);
        assertThat(outboxCount(email)).isEqualTo(5);
    }

    /** D004 đã trả: khoảng cách 60 s và cửa sổ 60 phút đi theo clock của app, không cần sửa {@code created_at}. */
    @Test
    void quotaFollowsInjectedClockWithoutBackdating() {
        String email = register(); // mã 1 tại T
        clock.advance(Duration.ofSeconds(59));
        assertError(resend(email, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Vui lòng chờ 1 giây trước khi gửi lại mã OTP (BR-TK-07)");

        clock.advance(Duration.ofSeconds(1));
        assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK); // mã 2 tại T + 60 s
        for (int sent = 3; sent <= 5; sent++) {
            clock.advance(Duration.ofSeconds(60));
            assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        clock.advance(Duration.ofSeconds(60)); // T + 300 s: mã 1 ra khỏi cửa sổ sau 3300 s = 55 phút
        assertError(resend(email, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Đã gửi quá nhiều mã OTP tới email này, vui lòng thử lại sau 55 phút (BR-TK-07)");

        clock.advance(Duration.ofSeconds(3300)); // T + 60 phút: mã 1 không còn được đếm
        assertThat(resend(email, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(email)).hasSize(6);
    }

    // ---------------------------------------------------------------- 404 — kiểm trước BR-TK-07

    @Test
    void unknownEmailIsNotFound() {
        String email = newEmail();

        assertError(resend(email, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy tài khoản chờ xác thực #" + email);
    }

    @Test
    void activeAccountIsNotFoundEvenWithinResendInterval() {
        String email = register();
        assertThat(verify(email, latestCode(email)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // mã vừa gửi < 60 s: nếu kiểm quota trước tồn tại thì sẽ ra BR-TK-07 thay vì 404 (00-method §3.5)
        assertError(resend(email, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
        assertThat(codes(email)).hasSize(1);
    }

    // ---------------------------------------------------------------- hình thức

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MISSING       | email: Email không được để trống",
            "EMPTY         | email: Email không được để trống",
            "SPACES        | email: Email không được để trống",
            "khong-la-email| email: Email không đúng định dạng",
            "LONG          | email: Email tối đa 255 ký tự"})
    void invalidEmailIsRejectedWithVietnameseMessage(String input, String expected) {
        Map<String, Object> body = new HashMap<>();
        switch (input) {
            case "MISSING" -> { }
            case "EMPTY" -> body.put("email", "");
            case "SPACES" -> body.put("email", "   ");
            case "LONG" -> body.put("email", "a".repeat(243) + "@petcare.test");
            default -> body.put("email", input);
        }
        long codesBefore = jdbc.queryForObject("SELECT count(*) FROM otp_tokens", Long.class);
        long outboxBefore = jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class);

        ResponseEntity<Map<String, Object>> response = resendRaw(write(body), null);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_tokens", Long.class)).isEqualTo(codesBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class))
                .isEqualTo(outboxBefore);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"email\": ",
            "{\"email\": [\"a@petcare.test\"]}",
            "''"})
    void malformedRequestIsRejected(String body) {
        assertError(resendRaw(body, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
    }

    // ---------------------------------------------------------------- lỗi giữa chừng

    @Test
    void failureAfterInvalidatingOldCodeRollsBackAndOldCodeStillWorks() {
        String email = register();
        String code = latestCode(email);
        sentSecondsAgo(email, 60);
        // lỗi sau lệnh UPDATE vô hiệu mã cũ, trước INSERT mã mới và outbox
        doThrow(new IllegalStateException("lỗi giữa chừng")).when(passwordEncoder).encode(any());

        assertError(resend(email, null), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);

        assertThat(codes(email)).as("rollback cả mã mới").hasSize(1);
        assertThat(codes(email).get(0).get("invalidated_at")).as("rollback lệnh vô hiệu mã cũ").isNull();
        assertThat(outboxCount(email)).isEqualTo(1);
        assertThat(verify(email, code).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- song song (docs/adr/0011)

    @Test
    void concurrentResendsIssueExactlyOneCode() throws Exception {
        String email = register();
        sentSecondsAgo(email, 60);
        doAnswer(invocation -> {
            Thread.sleep(300); // giữ transaction (và khóa) sau khi vô hiệu mã cũ, để request kia chạy chồng lên
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        List<ResponseEntity<Map<String, Object>>> responses = runConcurrently(
                () -> resend(email, null), () -> resend(email, null));

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(1);
        ResponseEntity<Map<String, Object>> loser = responses.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.OK).findFirst().orElseThrow();
        assertError(loser, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-07)");
        assertThat(codes(email)).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM otp_tokens
                WHERE target_email = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Long.class, email)).as("BR-TK-05: đúng 1 mã còn hiệu lực").isEqualTo(1);
        assertThat(outboxCount(email)).isEqualTo(2);
    }

    @Test
    void resendWaitsForConcurrentVerifyAndGetsNotFound() throws Exception {
        String email = register();
        String code = latestCode(email);
        sentSecondsAgo(email, 60);
        CountDownLatch verifyInsideTransaction = new CountDownLatch(1);
        doAnswer(invocation -> {
            verifyInsideTransaction.countDown();
            Thread.sleep(500); // verify đã khóa tài khoản và dùng mã, chưa commit
            return false;
        }).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<ResponseEntity<Map<String, Object>>> verifying = pool.submit(() -> verify(email, code));
            assertThat(verifyInsideTransaction.await(30, TimeUnit.SECONDS)).isTrue();
            ResponseEntity<Map<String, Object>> resent = resend(email, null);

            assertThat(verifying.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertError(resent, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(email)).isEqualTo("ACTIVE");
        List<Map<String, Object>> codes = codes(email);
        assertThat(codes).as("resend chờ khóa nên không sinh mã").hasSize(1);
        assertThat(codes.get(0).get("consumed_at")).isNotNull();
        assertThat(codes.get(0).get("invalidated_at")).isNull();
    }

    // ---------------------------------------------------------------- helpers

    @SafeVarargs
    private static List<ResponseEntity<Map<String, Object>>> runConcurrently(
            java.util.concurrent.Callable<ResponseEntity<Map<String, Object>>>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<ResponseEntity<Map<String, Object>>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

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

    /**
     * Đặt mọi mã đã gửi tới email thành "gửi cách đây {@code seconds} giây" theo {@link MutableClock}: gọn hơn việc
     * dịch giờ khi chỉ cần một mốc (cả hai cách đều đúng từ khi {@code created_at} theo {@code Clock}, docs/adr/0015).
     */
    private void sentSecondsAgo(String email, long seconds) {
        jdbc.update("UPDATE otp_tokens SET created_at = ? WHERE target_email = ?",
                Timestamp.from(clock.instant().minusSeconds(seconds)), email);
    }

    /** Mã gốc mới nhất trong outbox (ADR-0009, nợ D003). */
    private String latestCode(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, String.class, email);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private List<Map<String, Object>> codes(String email) {
        return jdbc.queryForList("SELECT * FROM otp_tokens WHERE target_email = ? AND purpose = 'REGISTER' ORDER BY id",
                email);
    }

    private long outboxCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ?", Long.class,
                email);
    }

    private String status(String email) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE email = ?", String.class, email);
    }

    private Instant accountCreatedAt(String email) {
        return jdbc.queryForObject("SELECT created_at FROM accounts WHERE email = ?", Timestamp.class, email)
                .toInstant();
    }

    private long auditCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = "
                + "(SELECT id FROM accounts WHERE email = ?)", Long.class, email);
    }

    private ResponseEntity<Map<String, Object>> resend(String email, String token) {
        return resendRaw(write(Map.of("email", email)), token);
    }

    private ResponseEntity<Map<String, Object>> resendRaw(String body, String token) {
        return post("/api/auth/register/resend-otp", body, token);
    }

    private ResponseEntity<Map<String, Object>> verify(String email, String code) {
        return post("/api/auth/register/verify", write(Map.of("email", email, "code", code)), null);
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
        return "resend-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@petcare.test";
    }
}
