package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.platform.config.TimeConfig;

/**
 * {@code POST /api/auth/register} (UC01, Tài khoản#1) qua HTTP trên Postgres 17 thật. {@link CustomerApi} chưa có
 * implementation (nợ D001, docs/dept): thay bằng mock chèn {@code customers} thật bằng {@link JdbcTemplate} — cùng
 * connection với transaction JPA của use case, nên kiểm được rollback toàn bộ. Mỗi test dùng email riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RegistrationIT.TestBeans.class})
class RegistrationIT {

    private static final Instant T0 = Instant.parse("2026-10-06T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(T0, TimeConfig.BUSINESS_ZONE);
        }
    }

    @MockitoBean
    private CustomerApi customerApi;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void stubCustomerApi() {
        when(customerApi.createOnlineProfile(any(), any(), any())).thenAnswer(invocation -> jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, ?, 'ONLINE') RETURNING id
                """, Long.class, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void registersPendingAccountWithProfileOtpAndOutboxInOneTransaction() {
        String email = newEmail();

        ResponseEntity<Map<String, Object>> response = postRaw(body(email.toUpperCase(), "abc12345", "Nguyễn Văn A",
                "0901234567", true, true), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("code", 201)
                .containsEntry("message", "Đăng ký thành công, vui lòng kiểm tra email để lấy mã OTP");
        Map<String, Object> data = data(response);
        long accountId = ((Number) data.get("accountId")).longValue();
        assertThat(data).containsEntry("email", email).containsEntry("status", "PENDING");
        assertThat(Instant.parse((String) data.get("otpResendAvailableAt"))).isEqualTo(T0.plusSeconds(60));

        Map<String, Object> account = jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", accountId);
        assertThat(account).containsEntry("email", email).containsEntry("role", "CUSTOMER")
                .containsEntry("status", "PENDING").containsEntry("is_locked", false)
                .containsEntry("must_change_password", false);
        assertThat(account.get("phone")).as("BR-TK-01: tài khoản khách không lưu SĐT").isNull();
        assertThat(((java.sql.Timestamp) account.get("pending_expires_at")).toInstant())
                .as("BR-TK-08: hạn chốt theo Clock + account.pending_ttl_hours [CFG]")
                .isEqualTo(T0.plusSeconds(24 * 3600));
        assertThat(passwordEncoder.matches("abc12345", (String) account.get("password_hash"))).isTrue();

        assertThat(jdbc.queryForMap("SELECT full_name, phone, created_channel FROM customers WHERE account_id = ?",
                accountId)).containsEntry("full_name", "Nguyễn Văn A").containsEntry("phone", "0901234567")
                .containsEntry("created_channel", "ONLINE");

        Map<String, Object> otp = jdbc.queryForMap("SELECT * FROM otp_tokens WHERE account_id = ?", accountId);
        assertThat(otp).containsEntry("purpose", "REGISTER").containsEntry("target_email", email)
                .containsEntry("failed_attempts", 0);
        assertThat(otp.get("customer_id")).isNull();
        assertThat(otp.get("consumed_at")).isNull();
        assertThat(otp.get("invalidated_at")).isNull();
        assertThat(((java.sql.Timestamp) otp.get("expires_at")).toInstant()).isEqualTo(T0.plusSeconds(5 * 60));

        Map<String, Object> outbox = jdbc.queryForMap("SELECT * FROM notification_outbox WHERE recipient_email = ?",
                email);
        assertThat(outbox).containsEntry("template_code", "OTP_REGISTER").containsEntry("channel", "EMAIL")
                .containsEntry("status", "PENDING");
        assertThat(outbox.get("recipient_account_id")).isNull();
        assertThat(((java.sql.Timestamp) outbox.get("next_attempt_at")).toInstant()).isEqualTo(T0);
        Map<String, Object> payload = payload(email);
        assertThat(payload).containsEntry("ten_khach", "Nguyễn Văn A").containsEntry("thoi_han_phut", 5);
        String code = (String) payload.get("ma_otp");
        assertThat(code).matches("\\d{6}");
        assertThat((String) otp.get("code_hash")).isNotEqualTo(code);
        assertThat(passwordEncoder.matches(code, (String) otp.get("code_hash"))).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE (entity_type = 'accounts' AND entity_id = ?) OR actor_email = ?",
                Long.class, accountId, email)).as("BR-QT-15: đăng ký không ghi audit").isZero();
    }

    @Test
    void phoneIsOptional() {
        String email = newEmail();

        ResponseEntity<Map<String, Object>> response = postRaw(body(email, "abc12345", "A", null, true, true), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT c.phone FROM customers c JOIN accounts a ON a.id = c.account_id "
                + "WHERE a.email = ?", String.class, email)).isNull();
    }

    @Test
    void publicPathIgnoresGarbageToken() {
        ResponseEntity<Map<String, Object>> response = postRaw(body(newEmail(), "abc12345", "A", null, true, true),
                "not-a-jwt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------------------------------------------------------------- BR

    @Test
    void sameEmailAgainIsRejectedEvenWhilePending() {
        String email = newEmail();
        assertThat(postRaw(body(email, "abc12345", "A", null, true, true), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> again = postRaw(body(email.toUpperCase(), "xyz98765", "B", null, true, true),
                null);

        assertError(again, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Email đã được sử dụng. Vui lòng đăng nhập hoặc dùng chức năng quên mật khẩu (BR-TK-01)");
        assertThat(count("accounts", email)).isEqualTo(1);
        assertThat(count("notification_outbox", email)).isEqualTo(1);
    }

    @Test
    void emailOfActiveStaffAccountIsRejected() {
        String email = newEmail();
        jdbc.update("INSERT INTO accounts (email, phone, password_hash, role, status) "
                + "VALUES (?, '0900000000', 'hash', 'VET', 'ACTIVE')", email);

        assertError(postRaw(body(email, "abc12345", "A", null, true, true), null), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "(BR-TK-01)");
    }

    @Test
    void notAdultIsRejectedAndNothingWritten() {
        String email = newEmail();

        assertError(postRaw(body(email, "abc12345", "A", null, false, true), null), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng (BR-TK-02)");
        assertNothingWritten(email);
    }

    @Test
    void weakPasswordIsRejectedAndNothingWritten() {
        String email = newEmail();

        assertError(postRaw(body(email, "abcdefgh", "A", null, true, true), null), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "Mật khẩu phải có cả chữ và số (BR-TK-03)");
        assertNothingWritten(email);
    }

    // ---------------------------------------------------------------- hình thức

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "email                     | email: Email không được để trống",
            "email=khong-phai-email    | email: Email không đúng định dạng",
            "password                  | password: Mật khẩu không được để trống",
            "fullName                  | fullName: Họ tên không được để trống",
            "fullName=LONG             | fullName: Họ tên tối đa 100 ký tự",
            "phone=+84901234567        | phone: Số điện thoại phải có dạng 0xxxxxxxxx",
            "isAdult                   | isAdult: Thiếu xác nhận đủ 18 tuổi",
            "termsAccepted             | termsAccepted: Thiếu xác nhận đồng ý điều khoản sử dụng"})
    void invalidFieldIsRejectedWithVietnameseMessage(String change, String expected) throws Exception {
        String email = newEmail();
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email, "password", "abc12345",
                "fullName", "A", "isAdult", true, "termsAccepted", true));
        String[] parts = change.split("=", 2);
        if (parts.length == 1) {
            body.remove(parts[0]);
        } else {
            body.put(parts[0], "LONG".equals(parts[1]) ? "a".repeat(101) : parts[1]);
        }

        ResponseEntity<Map<String, Object>> response = postRaw(json.writeValueAsString(body), null);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(expected);
        assertNothingWritten(email);
    }

    @Test
    void malformedJsonIsRejected() {
        assertError(postRaw("{\"email\": \"a@petcare.test\", \"isAdult\": \"abc\"", null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
    }

    // ---------------------------------------------------------------- lỗi giữa chừng, song song

    @Test
    void failureInCustomerApiRollsBackAccount() {
        doThrow(new UnsupportedOperationException("CustomerApi chưa được cài — nợ D001"))
                .when(customerApi).createOnlineProfile(any(), any(), any());
        String email = newEmail();

        ResponseEntity<Map<String, Object>> response = postRaw(body(email, "abc12345", "A", null, true, true), null);

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);
        assertThat((String) response.getBody().get("message")).doesNotContain("D001");
        assertNothingWritten(email);
    }

    @Test
    void failureAfterProfileInsertRollsBackEverything() {
        // customers đã chèn xong rồi mới lỗi: phải mất cả accounts lẫn customers
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE')",
                    invocation.getArgument(0), invocation.getArgument(1));
            throw new IllegalStateException("lỗi sau khi ghi hồ sơ");
        }).when(customerApi).createOnlineProfile(any(), any(), any());
        String email = newEmail();

        assertError(postRaw(body(email, "abc12345", "Hồ sơ mồ côi", null, true, true), null),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);
        assertNothingWritten(email);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE full_name = 'Hồ sơ mồ côi'", Long.class))
                .isZero();
    }

    @Test
    void concurrentSameEmailCreatesExactlyOneAccount() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(300); // giữ transaction mở để request kia vượt qua kiểm tra BR-TK-01 và chạm unique
            return jdbc.queryForObject("INSERT INTO customers (account_id, full_name, created_channel) "
                    + "VALUES (?, ?, 'ONLINE') RETURNING id", Long.class, invocation.getArgument(0),
                    invocation.getArgument(1));
        }).when(customerApi).createOnlineProfile(any(), any(), any());
        String email = newEmail();
        String body = body(email, "abc12345", "A", null, true, true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postRaw(body, null);
                }));
            }
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
            ResponseEntity<Map<String, Object>> loser = responses.stream()
                    .filter(r -> r.getStatusCode() != HttpStatus.CREATED).findFirst().orElseThrow();
            if (loser.getStatusCode() == HttpStatus.CONFLICT) {
                assertError(loser, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", null);
                assertThat((String) loser.getBody().get("message")).doesNotContain("uq_");
            } else {
                assertError(loser, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-01)");
            }
            assertThat(count("accounts", email)).isEqualTo(1);
            assertThat(count("notification_outbox", email)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<Map<String, Object>> postRaw(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/api/auth/register", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private String body(String email, String password, String fullName, String phone, boolean isAdult,
            boolean termsAccepted) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("fullName", fullName);
        body.put("phone", phone);
        body.put("isAdult", isAdult);
        body.put("termsAccepted", termsAccepted);
        try {
            return json.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private Map<String, Object> payload(String email) {
        String raw = jdbc.queryForObject("SELECT payload::text FROM notification_outbox WHERE recipient_email = ?",
                String.class, email);
        try {
            return json.readValue(raw, MAP_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

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

    private void assertNothingWritten(String email) {
        assertThat(count("accounts", email)).as("accounts").isZero();
        assertThat(count("otp_tokens", email)).as("otp_tokens").isZero();
        assertThat(count("notification_outbox", email)).as("notification_outbox").isZero();
    }

    private long count(String table, String email) {
        String column = switch (table) {
            case "accounts" -> "email";
            case "otp_tokens" -> "target_email";
            case "notification_outbox" -> "recipient_email";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class,
                email.toLowerCase());
    }

    private static String newEmail() {
        return "reg-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }
}
