package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.security.BranchScope;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code POST /api/auth/password/forgot} và {@code /reset} (UC04; BR-TK-03, 05, 06, 07, 10, 12, 13 — docs/adr/0023)
 * qua HTTP trên Postgres 17 thật. Tài khoản chèn bằng SQL với hash BCrypt thật; mã OTP đọc từ
 * {@code notification_outbox.payload->>'ma_otp'}; thời gian theo {@link MutableClock}. Kiểm: quên mật khẩu trả cùng
 * một body ở mọi trường hợp và chỉ phát mã cho tài khoản đủ điều kiện; BR-TK-07 / lỗi validate không ghi gì; đặt lại
 * thành công gỡ khóa tạm, BR-TK-17, online và hủy mọi phiên; mã sai vẫn commit bộ đếm; trùng mật khẩu hiện tại không
 * tiêu mã; mọi BCrypt chạy khi không giữ connection; các race giữa bước đọc và bước khóa; không deadlock với đăng xuất;
 * mật khẩu và mã không lọt log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PasswordResetIT.TestBeans.class, PasswordResetIT.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIT {

    /** 09:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");
    private static final String PASSWORD = "matkhau123";
    private static final String NEW_PASSWORD = "matkhaumoi9";
    private static final String INVALID_OTP = "OTP không đúng hoặc đã hết hạn (BR-TK-05)";
    private static final String SAME_AS_CURRENT = "Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final PasswordEncoder FAST = new BCryptPasswordEncoder(4);

    /** {@code findByIdForUpdate}: Hibernate 6 sinh {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE}. */
    private static final String WAITING_ON_ACCOUNT_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
            """;
    private static final String WAITING_ON_SESSIONS_UPDATE = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE 'update sessions%'
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Endpoint cần token, chỉ có trong test: phiên còn dùng được không, BR-TK-17 còn chặn không. */
    @RestController
    static class ProbeController {
        private final BranchScope scope;

        ProbeController(BranchScope scope) {
            this.scope = scope;
        }

        @GetMapping("/api/test/password-reset-probe")
        Map<String, Object> probe() {
            return Map.of("accountId", scope.current().accountId());
        }
    }

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

    @Autowired
    private SessionService sessions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long branchId;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "PasswordResetIT chi nhánh " + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- quên mật khẩu (BR-TK-10, 12, 07)

    /** BR-TK-10, 12: cùng một body cho mọi loại email; chỉ tài khoản ACTIVE (kể cả đang khóa tạm) nhận mã. */
    @Test
    void forgotReturnsIdenticalBodyForEveryKindOfEmailAndSendsOnlyToEligible() {
        long active = staff("VET");
        long temporarilyLocked = customer();
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(T0.plusSeconds(600)),
                temporarilyLocked);
        long pending = pending();
        long adminLocked = staff("CARETAKER");
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'PasswordResetIT' WHERE id = ?",
                adminLocked);
        long disabled = staff("RECEPTIONIST");
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        List<String> emails = List.of(email(active), email(temporarilyLocked), email(pending), email(adminLocked),
                email(disabled), newEmail());

        List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
        for (String email : emails) {
            responses.add(forgot(email));
        }

        Map<String, Object> first = responses.get(0).getBody();
        assertThat(first).containsOnlyKeys("data", "message", "code").containsEntry("code", 202)
                .containsEntry("message", "Nếu email đã đăng ký, mã OTP đã được gửi tới hộp thư");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) first.get("data");
        assertThat(data).containsOnlyKeys("resendAvailableAt", "maskedEmail")
                .containsEntry("resendAvailableAt", "2026-10-08T02:01:00Z");
        assertThat(data.get("maskedEmail")).isNull();
        for (ResponseEntity<Map<String, Object>> response : responses) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).as("BR-TK-10: không phân biệt được email").isEqualTo(first);
        }

        assertThat(resetOtpCount(email(active))).isEqualTo(1);
        assertThat(resetOtpCount(email(temporarilyLocked))).as("BR-TK-12: khóa tạm vẫn được").isEqualTo(1);
        assertThat(outboxCount(email(active), "OTP_PASSWORD_RESET")).isEqualTo(1);
        assertThat(outboxCount(email(temporarilyLocked), "OTP_PASSWORD_RESET")).isEqualTo(1);
        for (long ineligible : List.of(pending, adminLocked, disabled)) {
            assertThat(resetOtpCount(email(ineligible))).isZero();
            assertThat(outboxCount(email(ineligible), "OTP_PASSWORD_RESET")).isZero();
        }
        assertThat(codeOf(email(active))).matches("\\d{6}");
    }

    /** BR-TK-07: chặn gửi lại vẫn trả 202 cùng body (bắt sau rollback, không thành 500) và không ghi gì. */
    @Test
    void quotaViolationsStillReturn202WithoutWriting() {
        String email = email(staff("VET"));
        Map<String, Object> expected = forgot(email).getBody();

        ResponseEntity<Map<String, Object>> tooSoon = forgot(email);
        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(tooSoon.getBody()).isEqualTo(expected);
        assertThat(resetOtpCount(email)).isEqualTo(1);

        for (int sent = 2; sent <= 5; sent++) {
            clock.advance(Duration.ofSeconds(61));
            assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(resetOtpCount(email)).isEqualTo(sent);
        }
        clock.advance(Duration.ofSeconds(61));
        assertThat(forgot(email).getStatusCode()).as("lần thứ 6 trong 1 giờ").isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resetOtpCount(email)).isEqualTo(5);
        assertThat(outboxCount(email, "OTP_PASSWORD_RESET")).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM otp_tokens
                WHERE target_email = ? AND purpose = 'RESET_PASSWORD' AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Long.class, email)).as("BR-TK-05: chỉ mã mới nhất còn hiệu lực").isEqualTo(1);
    }

    // ---------------------------------------------------------------- đặt lại mật khẩu (BR-TK-13)

    @Test
    void resetSucceedsAndClearsLockMustChangeOnlineAndAllSessions() {
        long id = staff("RECEPTIONIST");
        jdbc.update("""
                UPDATE accounts SET must_change_password = true, locked_until = ?, failed_login_count = 2,
                       first_failed_login_at = ?, last_seen_at = ?
                WHERE id = ?
                """, Timestamp.from(T0.plusSeconds(600)), Timestamp.from(T0.minusSeconds(60)),
                Timestamp.from(T0.minusSeconds(30)), id);
        OpenedSession a = open(id);
        OpenedSession b = open(id);
        assertError(login(email(id), PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
        forgot(email(id));

        ResponseEntity<Map<String, Object>> response = reset(email(id), codeOf(email(id)), NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT password_hash, must_change_password, failed_login_count, first_failed_login_at, locked_until,
                       last_seen_at
                FROM accounts WHERE id = ?
                """, id);
        assertThat(FAST.matches(NEW_PASSWORD, (String) row.get("password_hash"))).isTrue();
        assertThat(row).containsEntry("must_change_password", false).containsEntry("failed_login_count", 0);
        assertThat(row.get("first_failed_login_at")).isNull();
        assertThat(row.get("locked_until")).as("BR-TK-13: gỡ khóa tạm").isNull();
        assertThat(row.get("last_seen_at")).as("BR-TN-06: offline ngay").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, id)).as("BR-TK-13: hủy mọi phiên").isZero();
        assertError(probe(a.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(probe(b.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertThat(resetOtp(email(id)).get("consumed_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'thoi_diem' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'PASSWORD_CHANGED'
                """, String.class, email(id))).isEqualTo("09:00 08/10/2026");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?"
                + " AND action NOT IN ('LOGIN_FAILED', 'LOGIN_SUCCEEDED')", Long.class, id))
                .as("đặt lại không audit (docs/adr/0023)").isZero();

        ResponseEntity<Map<String, Object>> relogin = login(email(id), NEW_PASSWORD);
        assertThat(relogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(probe(accessToken(relogin)).getStatusCode()).as("BR-TK-17 không còn chặn").isEqualTo(HttpStatus.OK);
        assertError(login(email(id), PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(reset(email(id), codeOf(email(id)), "matkhaukhac1"), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "(BR-TK-05)");
    }

    /** Đặt lại không gọi module customer (placeholder D001/D010): khách đặt lại được ngay. */
    @Test
    void customerCanResetPassword() {
        long id = customer();
        forgot(email(id));

        assertThat(reset(email(id), codeOf(email(id)), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
    }

    @Test
    void wrongCodeIs400AndCounterCommittedUntilFifthCancelsCode() {
        long id = staff("VET");
        forgot(email(id));
        String wrong = wrongCode(codeOf(email(id)));

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(reset(email(id), wrong, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                    INVALID_OTP);
            assertThat(resetOtp(email(id))).containsEntry("failed_attempts", attempt);
        }
        assertError(reset(email(id), wrong, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "(BR-TK-06)");
        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 5);
        assertThat(resetOtp(email(id)).get("invalidated_at")).isNotNull();
        assertError(reset(email(id), codeOf(email(id)), NEW_PASSWORD), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", INVALID_OTP);
        assertThat(FAST.matches(PASSWORD, hash(id))).isTrue();
    }

    @Test
    void expiredCodeIsRejectedWithoutCounting() {
        long id = staff("VET");
        forgot(email(id));
        clock.advance(Duration.ofMinutes(5));

        assertError(reset(email(id), codeOf(email(id)), NEW_PASSWORD), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", INVALID_OTP);
        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 0);
        assertThat(FAST.matches(PASSWORD, hash(id))).isTrue();
    }

    /** BR-TK-03 trùng mật khẩu hiện tại: chỉ kiểm khi mã đúng, rollback cả {@code consumed_at} → mã dùng lại được. */
    @Test
    void sameAsCurrentIs400AndCodeStaysUsable() {
        long id = staff("VET");
        forgot(email(id));
        String code = codeOf(email(id));

        assertError(reset(email(id), code, PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                SAME_AS_CURRENT);
        assertThat(resetOtp(email(id)).get("consumed_at")).isNull();
        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 0);

        assertThat(reset(email(id), code, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** BR-TK-10, 12: email lạ, tài khoản không đủ điều kiện, không có mã → cùng message với mã sai. */
    @Test
    void unknownIneligibleOrCodelessGetSameGenericBrTk05() {
        long locked = staff("VET");
        forgot(email(locked));
        String code = codeOf(email(locked));
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'PasswordResetIT' WHERE id = ?", locked);
        long codeless = staff("VET");

        assertError(reset(newEmail(), "123456", NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                INVALID_OTP);
        assertError(reset(email(locked), code, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                INVALID_OTP);
        assertError(reset(email(codeless), "123456", NEW_PASSWORD), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", INVALID_OTP);
        assertThat(resetOtp(email(locked))).containsEntry("failed_attempts", 0);
        assertThat(resetOtp(email(locked)).get("consumed_at")).isNull();
        assertThat(FAST.matches(PASSWORD, hash(locked))).isTrue();
    }

    /** Thứ tự kiểm: chính sách BR-TK-03 trước OTP — mật khẩu yếu không tiêu lượt nhập mã. */
    @ParameterizedTest
    @CsvSource({"ngan1", "chicochu", "12345678"})
    void weakPasswordIsBrTk03BeforeCodeIsChecked(String weak) {
        long id = staff("VET");
        forgot(email(id));

        assertError(reset(email(id), wrongCode(codeOf(email(id))), weak), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "(BR-TK-03)");
        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 0);
    }

    // ---------------------------------------------------------------- validate & handler

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"email\": \"\"}|email",
            "{\"email\": \"khong-phai-email\"}|email",
            "{}|email"})
    void invalidForgotBodiesAreRejectedWithoutWriting(String body, String field) {
        long before = jdbc.queryForObject("SELECT count(*) FROM otp_tokens", Long.class);

        ResponseEntity<Map<String, Object>> response = forgotRaw(body, MediaType.APPLICATION_JSON, null);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(field);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_tokens", Long.class)).isEqualTo(before);
    }

    @Test
    void tooLongForgotEmailIsRejected() {
        String label = "x".repeat(63);
        String email = "a".repeat(64) + "@" + label + "." + label + "." + label + ".petcare.test";

        assertError(forgotRaw(write(Map.of("email", email)), MediaType.APPLICATION_JSON, null), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MISSING_CODE|code",
            "LETTERS_CODE|code",
            "LONG_CODE|code",
            "BLANK_PASSWORD|newPassword",
            "BAD_EMAIL|email"})
    void invalidResetBodiesAreRejectedWithoutTouchingTheCode(String kind, String field) {
        long id = staff("VET");
        forgot(email(id));
        String code = codeOf(email(id));
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email(id), "code", code,
                "newPassword", NEW_PASSWORD));
        switch (kind) {
            case "MISSING_CODE" -> body.remove("code");
            case "LETTERS_CODE" -> body.put("code", "12ab");
            case "LONG_CODE" -> body.put("code", "123456789");
            case "BLANK_PASSWORD" -> body.put("newPassword", "   ");
            default -> body.put("email", "khong-phai-email");
        }

        ResponseEntity<Map<String, Object>> response = resetRaw(write(body), MediaType.APPLICATION_JSON, null);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(field);
        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 0);
        assertThat(resetOtp(email(id)).get("consumed_at")).isNull();
        assertThat(FAST.matches(PASSWORD, hash(id))).isTrue();
    }

    @Test
    void malformedJsonUnsupportedMediaTypeAndMethodAreRejected() {
        assertError(forgotRaw("{\"email\": ", MediaType.APPLICATION_JSON, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertError(resetRaw("", MediaType.APPLICATION_JSON, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(forgotRaw("email=a@b.c", MediaType.TEXT_PLAIN, null), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", null);
        assertError(http.exchange("/api/auth/password/reset", HttpMethod.GET, HttpEntity.EMPTY, MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
    }

    /** docs/adr/0005: path public luôn ẩn danh — token của nhân viên còn BR-TK-17 không chặn quên / đặt lại. */
    @Test
    void mustChangePasswordTokenDoesNotBlockForgotOrReset() {
        long id = staff("VET");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);
        String token = open(id).accessToken();
        assertError(probe(token), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-17)");

        assertThat(forgotRaw(write(Map.of("email", email(id))), MediaType.APPLICATION_JSON, token).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resetRaw(write(Map.of("email", email(id), "code", codeOf(email(id)), "newPassword", NEW_PASSWORD)),
                MediaType.APPLICATION_JSON, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------- nghiệp vụ khác

    /** Forgot của tài khoản PENDING không ghi gì và không đụng mã đăng ký đang mở (khác mục đích). */
    @Test
    void pendingAccountKeepsItsRegistrationCode() {
        long id = pending();
        jdbc.update("""
                INSERT INTO otp_tokens (account_id, purpose, target_email, code_hash, expires_at)
                VALUES (?, 'REGISTER', ?, ?, ?)
                """, id, email(id), FAST.encode("111111"), Timestamp.from(T0.plusSeconds(300)));

        assertThat(forgot(email(id)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForList("SELECT purpose, invalidated_at FROM otp_tokens WHERE target_email = ?",
                email(id))).singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("purpose", "REGISTER");
                    assertThat(row.get("invalidated_at")).isNull();
                });
    }

    /** Mã đặt lại mới chỉ hủy mã đặt lại cũ, không hủy mã khác mục đích của cùng email (BR-TK-05). */
    @Test
    void newResetCodeInvalidatesOnlyPreviousResetCode() {
        long id = staff("VET");
        jdbc.update("""
                INSERT INTO otp_tokens (account_id, purpose, target_email, code_hash, expires_at, created_at)
                VALUES (?, 'CHANGE_EMAIL', ?, ?, ?, ?)
                """, id, email(id), FAST.encode("111111"), Timestamp.from(T0.plusSeconds(300)),
                Timestamp.from(T0.minusSeconds(120)));

        forgot(email(id));

        assertThat(jdbc.queryForObject("""
                SELECT invalidated_at FROM otp_tokens WHERE target_email = ? AND purpose = 'CHANGE_EMAIL'
                """, Timestamp.class, email(id))).isNull();
    }

    // ---------------------------------------------------------------- connection, đồng thời

    /** docs/adr/0023: mọi BCrypt của quên / đặt lại chạy khi không giữ connection (cũng không giữ khóa dòng). */
    @Test
    void bcryptRunsWithoutHoldingConnection() {
        long id = staff("VET");
        HikariDataSource hikari = hikari();
        List<Integer> activeDuringBcrypt = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            activeDuringBcrypt.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
        doAnswer(invocation -> {
            activeDuringBcrypt.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertThat(forgot(email(id)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(forgot(newEmail()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertError(reset(newEmail(), "123456", NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                INVALID_OTP);
        assertThat(reset(email(id), codeOf(email(id)), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // forgot ×2: 1 encode mỗi lần; reset email lạ: 2 matches; reset thành công: 2 matches + 1 encode.
        assertThat(activeDuringBcrypt).hasSize(7).containsOnly(0);
    }

    @Test
    void concurrentWrongCodesAreBothCounted() throws Exception {
        long id = staff("VET");
        forgot(email(id));
        String wrong = wrongCode(codeOf(email(id)));
        CyclicBarrier bothRead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            if (wrong.equals(invocation.getArgument(0))) {
                bothRead.await(10, TimeUnit.SECONDS);   // cả hai đã đọc cùng otpId, chưa ai khóa dòng
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> a = pool.submit(() -> reset(email(id), wrong, NEW_PASSWORD));
            Future<ResponseEntity<Map<String, Object>>> b = pool.submit(() -> reset(email(id), wrong, NEW_PASSWORD));
            assertThat(a.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(b.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            pool.shutdownNow();
        }

        assertThat(resetOtp(email(id))).containsEntry("failed_attempts", 2);
    }

    /** Mã mới được phát giữa bước đọc và bước khóa: lần so thuộc mã cũ → BR-TK-05, không phạt mã mới. */
    @Test
    void codeReplacedBetweenReadAndLockIsRejectedWithoutPenalty() {
        long id = staff("VET");
        forgot(email(id));
        String oldCode = codeOf(email(id));
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<HttpStatus> reissue = new AtomicReference<>();
        doAnswer(invocation -> {
            if (oldCode.equals(invocation.getArgument(0)) && fired.compareAndSet(false, true)) {
                clock.advance(Duration.ofSeconds(61));
                reissue.set(HttpStatus.valueOf(forgot(email(id)).getStatusCode().value()));
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        assertError(reset(email(id), oldCode, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                INVALID_OTP);

        assertThat(reissue.get()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resetOtp(email(id))).as("mã mới").containsEntry("failed_attempts", 0);
        assertThat(resetOtp(email(id)).get("consumed_at")).isNull();
        assertThat(FAST.matches(PASSWORD, hash(id))).isTrue();
    }

    /** Mật khẩu bị đổi giữa bước đọc và bước khóa: so lại "trùng mật khẩu hiện tại" dưới khóa với hash mới. */
    @Test
    void passwordChangedBetweenReadAndLockIsRecheckedUnderLock() {
        long id = staff("VET");
        forgot(email(id));
        String code = codeOf(email(id));
        String concurrentHash = FAST.encode(NEW_PASSWORD);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (NEW_PASSWORD.equals(invocation.getArgument(0)) && fired.compareAndSet(false, true)) {
                jdbc.update("UPDATE accounts SET password_hash = ? WHERE id = ?", concurrentHash, id);
            }
            return result;
        }).when(passwordEncoder).matches(any(), any());

        assertError(reset(email(id), code, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                SAME_AS_CURRENT);

        assertThat(hash(id)).isEqualTo(concurrentHash);
        assertThat(resetOtp(email(id)).get("consumed_at")).as("rollback cả consumed_at").isNull();
    }

    /**
     * docs/adr/0021: đặt lại khóa {@code accounts → sessions} như đăng xuất. (1) Transaction kiểu đăng xuất nhân viên giữ
     * {@code accounts} rồi {@code sessions}: đặt lại chờ ở bước khóa dòng. (2) Đăng xuất khách chỉ giữ dòng
     * {@code sessions}: đặt lại chờ ở câu hủy phiên. Cả hai đều xong, không deadlock.
     */
    @ParameterizedTest
    @CsvSource({"ACCOUNTS_THEN_SESSION", "SESSION_ONLY"})
    void resetDoesNotDeadlockWithLogout(String shape) throws Exception {
        long id = staff("VET");
        OpenedSession other = open(id);
        forgot(email(id));
        String code = codeOf(email(id));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> logout = pool.submit(() -> tx.execute(status -> {
                if (shape.equals("ACCOUNTS_THEN_SESSION")) {
                    jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, id);
                }
                int revoked = jdbc.update("UPDATE sessions SET revoked_at = ?, updated_at = ? WHERE id = ?",
                        Timestamp.from(T0), Timestamp.from(T0), other.sessionId());
                held.countDown();
                await(release);
                return revoked;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> reset =
                    pool.submit(() -> reset(email(id), code, NEW_PASSWORD));
            awaitWaiting(shape.equals("ACCOUNTS_THEN_SESSION") ? WAITING_ON_ACCOUNT_LOCK : WAITING_ON_SESSIONS_UPDATE);
            release.countDown();

            assertThat(logout.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(reset.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
    }

    /**
     * Convention 08 L9: mật khẩu không xuất hiện trong log ở mọi nhánh (cả body JSON hỏng). Mã OTP 6 chữ số không kiểm
     * bằng chuỗi con vì dễ trùng ngẫu nhiên với số trong log (port, hash kết nối); {@code ResetPasswordRequest} che mã
     * trong {@code toString} và không chỗ nào log request.
     */
    @Test
    void passwordsNeverAppearInLogs(CapturedOutput output) {
        long id = staff("VET");
        String marker = "Moi-" + UUID.randomUUID() + "1";
        forgot(email(id));
        String code = codeOf(email(id));

        resetRaw("{\"email\": \"" + email(id) + "\", \"code\": \"" + code + "\", \"newPassword\": \"" + marker + "\"",
                MediaType.APPLICATION_JSON, null);
        reset(email(id), wrongCode(code), marker);
        reset(email(id), code, PASSWORD);
        reset(email(id), code, marker);

        assertThat(output.getAll()).doesNotContain(marker);
    }

    // ---------------------------------------------------------------- dữ liệu

    private long staff(String role) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), FAST.encode(PASSWORD), role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return id;
    }

    private long customer() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, ?, 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), FAST.encode(PASSWORD));
    }

    private long pending() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, ?, 'CUSTOMER', 'PENDING', ?) RETURNING id
                """, Long.class, newEmail(), FAST.encode(PASSWORD), Timestamp.from(T0.plus(Duration.ofHours(24))));
    }

    private static String newEmail() {
        return "reset-pw-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private static String wrongCode(String code) {
        return code.equals("999999") ? "888888" : "999999";
    }

    private OpenedSession open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "PasswordResetIT"));
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, id);
    }

    private String hash(long id) {
        return jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id);
    }

    /** Mã gốc của lần quên mật khẩu gần nhất (worker ST20 không chạy trong test nên payload chưa bị xóa mã). */
    private String codeOf(String email) {
        return jdbc.queryForObject("""
                SELECT payload ->> 'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_PASSWORD_RESET' ORDER BY id DESC LIMIT 1
                """, String.class, email);
    }

    private Map<String, Object> resetOtp(String email) {
        return jdbc.queryForMap("""
                SELECT id, failed_attempts, consumed_at, invalidated_at FROM otp_tokens
                WHERE target_email = ? AND purpose = 'RESET_PASSWORD' ORDER BY id DESC LIMIT 1
                """, email);
    }

    private long resetOtpCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE target_email = ? AND purpose = 'RESET_PASSWORD'",
                Long.class, email);
    }

    private long outboxCount(String email, String template) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ? AND template_code = ?",
                Long.class, email, template);
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void awaitWaiting(String query) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(query, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("đặt lại mật khẩu phải đứng chờ khóa; pg_stat_activity = " + jdbc.queryForList("""
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

    // ---------------------------------------------------------------- HTTP

    private ResponseEntity<Map<String, Object>> forgot(String email) {
        return forgotRaw(write(Map.of("email", email)), MediaType.APPLICATION_JSON, null);
    }

    private ResponseEntity<Map<String, Object>> forgotRaw(String body, MediaType contentType, String token) {
        return post("/api/auth/password/forgot", body, contentType, token);
    }

    private ResponseEntity<Map<String, Object>> reset(String email, String code, String newPassword) {
        return resetRaw(write(Map.of("email", email, "code", code, "newPassword", newPassword)),
                MediaType.APPLICATION_JSON, null);
    }

    private ResponseEntity<Map<String, Object>> resetRaw(String body, MediaType contentType, String token) {
        return post("/api/auth/password/reset", body, contentType, token);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String body, MediaType contentType, String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(contentType);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/api/auth/login", write(Map.of("email", email, "password", password)), MediaType.APPLICATION_JSON,
                null);
    }

    @SuppressWarnings("unchecked")
    private static String accessToken(ResponseEntity<Map<String, Object>> login) {
        return (String) ((Map<String, Object>) login.getBody().get("data")).get("accessToken");
    }

    private ResponseEntity<Map<String, Object>> probe(String token) {
        return http.exchange("/api/test/password-reset-probe", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
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
}
