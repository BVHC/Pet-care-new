package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 * {@code POST /api/me/password} (UC05, BR-TK-03, 09, 11, 14, 17 — docs/adr/0022) qua HTTP trên Postgres 17 thật.
 * Tài khoản chèn bằng SQL với hash BCrypt thật, phiên mở bằng {@link SessionService#open}; thời gian theo
 * {@link MutableClock}. Kiểm: 400 BR-TK-14 vẫn commit bộ đếm (dùng chung với đăng nhập), khóa tạm + email + audit khi
 * chạm ngưỡng, đang khóa thì không BCrypt / không đếm, thành công hủy phiên khác và gỡ BR-TK-17, không vượt được khóa
 * đăng nhập, hình thức không đếm, hai lần sai song song đều đếm, BCrypt không giữ connection, không deadlock với đăng
 * xuất, mật khẩu không lọt log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ChangePasswordIT.TestBeans.class, ChangePasswordIT.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class ChangePasswordIT {

    /** 09:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");
    private static final String PASSWORD = "matkhau123";
    private static final String NEW_PASSWORD = "matkhaumoi9";
    private static final String MISMATCH = "Mật khẩu hiện tại không đúng (BR-TK-14)";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final PasswordEncoder FAST = new BCryptPasswordEncoder(4);

    /** {@code findByIdForUpdate}: Hibernate 6 sinh {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE} trên Postgres. */
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

        @GetMapping("/api/test/change-password-probe")
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
                """, Long.class, "ChangePasswordIT chi nhánh " + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- thành công (BR-TK-14, 17)

    @Test
    void successRevokesOtherSessionsKeepsCurrentAndResetsCounter() {
        long id = staff("VET");
        String current = open(id).accessToken();
        OpenedSession other = open(id);
        jdbc.update("UPDATE accounts SET failed_login_count = 2, first_failed_login_at = ? WHERE id = ?",
                Timestamp.from(T0.minusSeconds(60)), id);
        clock.advance(Duration.ofMinutes(1));

        ResponseEntity<Map<String, Object>> response = change(current, PASSWORD, NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(probe(current).getStatusCode()).as("phiên đang dùng giữ nguyên").isEqualTo(HttpStatus.OK);
        assertError(probe(other.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM sessions WHERE id = ?", Timestamp.class,
                other.sessionId()).toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(counter(id)).containsEntry("failed_login_count", 0);
        assertThat(counter(id).get("first_failed_login_at")).isNull();
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
        assertThat(audits(id)).as("thành công không audit (docs/adr/0022)").isEmpty();
        assertThat(outbox(id)).as("không gửi email (docs/adr/0022)").isEmpty();

        assertThat(login(email(id), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(login(email(id), PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
    }

    @Test
    void mustChangePasswordIsClearedAndOtherApisUnblocked() {
        long id = staff("RECEPTIONIST");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);
        String token = open(id).accessToken();
        assertError(probe(token), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-17)");

        assertThat(change(token, PASSWORD, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("SELECT must_change_password FROM accounts WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(probe(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Không gọi customer (D010 không ảnh hưởng): placeholder ném lỗi thì request này sẽ 500. */
    @Test
    void customerCanChangePassword() {
        long id = customer();
        String token = open(id).accessToken();

        assertThat(change(token, PASSWORD, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
    }

    // ---------------------------------------------------------------- BR-TK-14 → BR-TK-09

    @Test
    void wrongCurrentIs400AndCounterCommitted() {
        long id = staff("VET");
        String token = open(id).accessToken();
        String before = hash(id);

        ResponseEntity<Map<String, Object>> response = change(token, "sai12345", NEW_PASSWORD);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-14)");
        assertThat(response.getBody()).containsEntry("message", MISMATCH);
        assertThat(counter(id)).containsEntry("failed_login_count", 1);
        assertThat(instant(counter(id).get("first_failed_login_at"))).isEqualTo(T0);
        assertThat(hash(id)).isEqualTo(before);
        assertThat(audits(id)).isEmpty();
        assertThat(probe(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** BR-TK-14 trước BR-TK-03: mật khẩu mới yếu không giúp lần sai thoát bộ đếm. */
    @Test
    void wrongCurrentWithInvalidNewIsStillBrTk14AndCounted() {
        long id = staff("VET");

        assertError(change(open(id).accessToken(), "sai12345", "yeu"), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "(BR-TK-14)");
        assertThat(counter(id)).containsEntry("failed_login_count", 1);
    }

    @Test
    void currentPasswordOver72BytesIsWrongAndCounted() {
        String password72 = "a1".repeat(36);
        long id = staffWithPassword("VET", password72);

        assertError(change(open(id).accessToken(), password72 + "x", NEW_PASSWORD), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "(BR-TK-14)");
        assertThat(counter(id)).containsEntry("failed_login_count", 1);
    }

    @Test
    void fifthWrongLocksSendsWarningAuditsAndKeepsSession() {
        long id = staff("VET");
        String token = open(id).accessToken();
        ResponseEntity<Map<String, Object>> last = null;
        for (int i = 0; i < 5; i++) {
            clock.set(T0.plusSeconds(60L * i));
            last = change(token, "sai12345", NEW_PASSWORD);
            assertError(last, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-14)");
        }

        Instant until = T0.plusSeconds(240).plus(Duration.ofMinutes(15));   // 09:19 VN
        assertThat(last.getBody()).containsEntry("message", "Mật khẩu hiện tại không đúng. Bạn đã nhập sai quá nhiều"
                + " lần, tài khoản tạm khóa đăng nhập tới 09:19 08/10/2026 (BR-TK-14)");
        assertThat(instant(counter(id).get("locked_until"))).isEqualTo(until);
        assertThat(counter(id)).containsEntry("failed_login_count", 0);

        List<Map<String, Object>> outbox = outbox(id);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)).containsEntry("template_code", "LOGIN_LOCKED_WARNING")
                .containsEntry("channel", "EMAIL").containsEntry("so_lan_sai", "5")
                .containsEntry("thoi_diem_mo_khoa", "09:19 08/10/2026");

        List<Map<String, Object>> audits = audits(id);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "ACCOUNT_TEMPORARILY_LOCKED")
                .containsEntry("actor_account_id", id).containsEntry("actor_email", email(id))
                .containsEntry("entity_type", "accounts").containsEntry("entity_id", id)
                .containsEntry("before_count", "4").containsEntry("after_count", "0");
        assertThat(audits.get(0).get("after_locked_until")).isNotNull();

        assertThat(probe(token).getStatusCode()).as("khóa tạm chỉ chặn đăng nhập").isEqualTo(HttpStatus.OK);
        assertError(login(email(id), PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
    }

    /** Bộ đếm dùng chung (BR-TK-14 "tính vào bộ đếm của BR-TK-09"): 3 lần sai ở đây + 2 lần sai đăng nhập = khóa. */
    @Test
    void wrongCurrentPasswordCountsTowardLoginLock() {
        long id = staff("VET");
        String token = open(id).accessToken();
        for (int i = 0; i < 3; i++) {
            change(token, "sai12345", NEW_PASSWORD);
        }
        for (int i = 0; i < 2; i++) {
            assertError(login(email(id), "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }

        assertThat(counter(id).get("locked_until")).isNotNull();
        assertThat(outbox(id)).hasSize(1);
        assertError(login(email(id), PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
    }

    // ---------------------------------------------------------------- đang khóa tạm (docs/adr/0022 mục 2)

    @Test
    void whileTemporarilyLockedIs400BrTk09WithoutBcryptOrCounting() {
        long id = staff("VET");
        String token = open(id).accessToken();
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(T0.plusSeconds(90)), id);
        String before = hash(id);
        clearInvocations(passwordEncoder);

        ResponseEntity<Map<String, Object>> correct = change(token, PASSWORD, NEW_PASSWORD);
        ResponseEntity<Map<String, Object>> wrong = change(token, "sai12345", NEW_PASSWORD);

        for (ResponseEntity<Map<String, Object>> response : List.of(correct, wrong)) {
            assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
            assertThat(response.getBody()).containsEntry("message",
                    "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 09:02 08/10/2026 (BR-TK-09)");
        }
        verify(passwordEncoder, never()).matches(any(), any());
        assertThat(counter(id)).containsEntry("failed_login_count", 0);
        assertThat(hash(id)).isEqualTo(before);
        assertThat(audits(id)).isEmpty();
    }

    /** Đổi mật khẩu không gỡ được khóa đăng nhập còn hiệu lực; hết khóa thì đổi được và xóa mốc khóa cũ. */
    @Test
    void cannotBypassLoginLockByChangingPassword() {
        long id = staff("VET");
        String token = open(id).accessToken();
        for (int i = 0; i < 5; i++) {
            login(email(id), "sai12345");
        }
        Instant until = instant(counter(id).get("locked_until"));

        assertError(change(token, PASSWORD, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "(BR-TK-09)");
        assertThat(instant(counter(id).get("locked_until"))).isEqualTo(until);

        clock.set(until);
        assertThat(change(token, PASSWORD, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(counter(id).get("locked_until")).isNull();
    }

    // ---------------------------------------------------------------- BR-TK-03 (mật khẩu mới)

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SHORT     | Mật khẩu phải có ít nhất 8 ký tự (BR-TK-03)",
            "NO_DIGIT  | Mật khẩu phải có cả chữ và số (BR-TK-03)",
            "NO_LETTER | Mật khẩu phải có cả chữ và số (BR-TK-03)",
            "TOO_LONG  | Mật khẩu quá dài (tối đa 72 byte) (BR-TK-03)",
            "SAME      | Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)"})
    void invalidNewPasswordIs400BrTk03WithoutSideEffects(String kind, String message) {
        long id = staff("VET");
        String token = open(id).accessToken();
        OpenedSession other = open(id);
        String before = hash(id);
        String newPassword = switch (kind) {
            case "SHORT" -> "abc1234";
            case "NO_DIGIT" -> "abcdefgh";
            case "NO_LETTER" -> "12345678";
            case "TOO_LONG" -> "a1" + "x".repeat(71);
            case "SAME" -> PASSWORD;
            default -> throw new IllegalArgumentException(kind);
        };

        ResponseEntity<Map<String, Object>> response = change(token, PASSWORD, newPassword);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-03)");
        assertThat(response.getBody()).containsEntry("message", message);
        assertThat(hash(id)).isEqualTo(before);
        assertNoSideEffects(id);
        assertThat(probe(other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- hình thức, xác thực (không đếm)

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MISSING_CURRENT | currentPassword: Mật khẩu hiện tại không được để trống",
            "EMPTY_CURRENT   | currentPassword: Mật khẩu hiện tại không được để trống",
            "SPACES_CURRENT  | currentPassword: Mật khẩu hiện tại không được để trống",
            "MISSING_NEW     | newPassword: Mật khẩu mới không được để trống",
            "SPACES_NEW      | newPassword: Mật khẩu mới không được để trống",
            "BOTH_MISSING    | currentPassword: Mật khẩu hiện tại không được để trống"})
    void invalidBodiesRejectedWithoutSideEffects(String kind, String expected) {
        long id = staff("VET");
        String token = open(id).accessToken();
        Map<String, Object> body = new HashMap<>();
        body.put("currentPassword", "sai12345");
        body.put("newPassword", NEW_PASSWORD);
        switch (kind) {
            case "MISSING_CURRENT" -> body.remove("currentPassword");
            case "EMPTY_CURRENT" -> body.put("currentPassword", "");
            case "SPACES_CURRENT" -> body.put("currentPassword", "   ");
            case "MISSING_NEW" -> body.remove("newPassword");
            case "SPACES_NEW" -> body.put("newPassword", "   ");
            case "BOTH_MISSING" -> body.clear();
            default -> throw new IllegalArgumentException(kind);
        }

        ResponseEntity<Map<String, Object>> response = changeRaw(write(body), MediaType.APPLICATION_JSON, token);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(expected);
        if (kind.equals("BOTH_MISSING")) {
            assertThat((String) response.getBody().get("message"))
                    .contains("newPassword: Mật khẩu mới không được để trống");
        }
        assertNoSideEffects(id);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"currentPassword\": ",
            "''",
            "{\"currentPassword\": [\"x\"], \"newPassword\": \"matkhaumoi9\"}"})
    void malformedRequestIsRejected(String body) {
        long id = staff("VET");

        assertError(changeRaw(body, MediaType.APPLICATION_JSON, open(id).accessToken()), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertNoSideEffects(id);
    }

    @Test
    void unsupportedMediaTypeAndMethodAreRejected() {
        long id = staff("VET");
        String token = open(id).accessToken();

        assertError(changeRaw("currentPassword=x", MediaType.TEXT_PLAIN, token), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", null);
        assertError(http.exchange("/api/me/password", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
        assertNoSideEffects(id);
    }

    @Test
    void unknownFieldsAreIgnoredAndEmailIsNotChanged() {
        long id = staff("VET");
        String email = email(id);
        Map<String, Object> body = Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD,
                "email", "khac@petcare.test");

        assertThat(changeRaw(write(body), MediaType.APPLICATION_JSON, open(id).accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(email(id)).isEqualTo(email);
    }

    @Test
    void missingGarbageOrRevokedTokenIs401() {
        long id = staff("VET");
        OpenedSession revoked = open(id);
        tx.executeWithoutResult(s -> sessions.revoke(revoked.sessionId()));

        for (String token : new String[] {null, "rac.rac.rac", revoked.accessToken()}) {
            assertError(change(token, PASSWORD, NEW_PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }
        assertNoSideEffects(id);
    }

    @Test
    void lockedOrDisabledAccountIs401FromFilter() {
        long locked = staff("VET");
        long disabled = staff("VET");
        String lockedToken = open(locked).accessToken();
        String disabledToken = open(disabled).accessToken();
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);

        assertError(change(lockedToken, PASSWORD, NEW_PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(change(disabledToken, PASSWORD, NEW_PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertNoSideEffects(locked);
        assertNoSideEffects(disabled);
    }

    // ---------------------------------------------------------------- đồng thời, connection

    @Test
    void concurrentWrongCurrentPasswordsAreBothCounted() throws Exception {
        long id = staff("VET");
        String token = open(id).accessToken();
        String marker = "sai-dongthoi-1";
        CyclicBarrier bothInsideBcrypt = new CyclicBarrier(2);
        doAnswer(invocation -> {
            if (marker.equals(invocation.getArgument(0))) {
                bothInsideBcrypt.await(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> a = pool.submit(() -> change(token, marker, NEW_PASSWORD));
            Future<ResponseEntity<Map<String, Object>>> b = pool.submit(() -> change(token, marker, NEW_PASSWORD));
            assertThat(a.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(b.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter(id)).containsEntry("failed_login_count", 2);
    }

    /** docs/adr/0022 mục 1: cả hai lần BCrypt (so mật khẩu hiện tại, mã hóa mật khẩu mới) không giữ connection. */
    @Test
    void bcryptRunsWithoutHoldingConnection() {
        long id = staff("VET");
        String token = open(id).accessToken();
        HikariDataSource hikari = hikari();
        AtomicInteger activeDuringMatches = new AtomicInteger(-1);
        AtomicInteger activeDuringEncode = new AtomicInteger(-1);
        doAnswer(invocation -> {
            if (PASSWORD.equals(invocation.getArgument(0))) {
                activeDuringMatches.set(hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
        doAnswer(invocation -> {
            if (NEW_PASSWORD.equals(invocation.getArgument(0))) {
                activeDuringEncode.set(hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertThat(change(token, PASSWORD, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(activeDuringMatches.get()).isZero();
        assertThat(activeDuringEncode.get()).isZero();
    }

    /**
     * Mật khẩu vừa bị đổi giữa bước đọc và bước khóa dòng (phiên khác / đặt lại mật khẩu): so lại dưới khóa với hash
     * mới → mật khẩu cũ giờ là sai, không ghi đè hash vừa đổi.
     */
    @Test
    void passwordChangedConcurrentlyIsRecheckedUnderLock() {
        long id = staff("VET");
        String token = open(id).accessToken();
        String concurrentHash = FAST.encode("doisongsong1");
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (PASSWORD.equals(invocation.getArgument(0)) && fired.compareAndSet(false, true)) {
                jdbc.update("UPDATE accounts SET password_hash = ? WHERE id = ?", concurrentHash, id);
            }
            return result;
        }).when(passwordEncoder).matches(any(), any());

        assertError(change(token, PASSWORD, NEW_PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "(BR-TK-14)");

        assertThat(hash(id)).isEqualTo(concurrentHash);
        assertThat(counter(id)).containsEntry("failed_login_count", 1);
    }

    /** Đăng nhập đọc hash cũ; đổi mật khẩu commit trước khi đăng nhập khóa dòng → đăng nhập so lại với hash mới. */
    @Test
    void loginDuringChangeUsesNewHash() {
        long id = staff("VET");
        String token = open(id).accessToken();
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<HttpStatus> changeStatus = new AtomicReference<>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (PASSWORD.equals(invocation.getArgument(0)) && fired.compareAndSet(false, true)) {
                // Đang ở bước BCrypt của đăng nhập (không giữ connection): đổi mật khẩu chạy trọn rồi mới trả về.
                changeStatus.set(HttpStatus.valueOf(change(token, PASSWORD, NEW_PASSWORD).getStatusCode().value()));
            }
            return result;
        }).when(passwordEncoder).matches(any(), any());

        assertError(login(email(id), PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        assertThat(changeStatus.get()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
        assertThat(counter(id)).containsEntry("failed_login_count", 1);
    }

    /**
     * docs/adr/0021: đổi mật khẩu khóa {@code accounts → sessions} như đăng xuất. (1) Một transaction kiểu đăng xuất
     * nhân viên đang giữ {@code accounts} rồi {@code sessions}: đổi mật khẩu chờ ở bước khóa dòng. (2) Đăng xuất khách
     * ({@code last_seen_at} NULL) chỉ giữ dòng {@code sessions}: đổi mật khẩu chờ ở câu hủy phiên. Cả hai đều xong, không
     * deadlock.
     */
    @ParameterizedTest
    @CsvSource({"ACCOUNTS_THEN_SESSION", "SESSION_ONLY"})
    void changePasswordDoesNotDeadlockWithLogoutOfOtherSession(String shape) throws Exception {
        long id = staff("VET");
        String token = open(id).accessToken();
        OpenedSession other = open(id);
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

            Future<ResponseEntity<Map<String, Object>>> change =
                    pool.submit(() -> change(token, PASSWORD, NEW_PASSWORD));
            awaitWaiting(shape.equals("ACCOUNTS_THEN_SESSION") ? WAITING_ON_ACCOUNT_LOCK : WAITING_ON_SESSIONS_UPDATE);
            release.countDown();

            assertThat(logout.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(change.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(FAST.matches(NEW_PASSWORD, hash(id))).isTrue();
        assertThat(probe(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Convention 08 L9: mật khẩu không xuất hiện trong log ở mọi nhánh. */
    @Test
    void passwordsNeverAppearInLogs(CapturedOutput output) {
        String currentMarker = "Cu-" + UUID.randomUUID() + "1";
        String newMarker = "Moi-" + UUID.randomUUID() + "1";
        long id = staffWithPassword("VET", currentMarker);
        String token = open(id).accessToken();

        changeRaw("{\"currentPassword\": \"" + currentMarker + "\", ", MediaType.APPLICATION_JSON, token);
        change(token, currentMarker + "x", newMarker);
        change(token, currentMarker, "ngan1");
        change(token, currentMarker, newMarker);

        assertThat(output.getAll()).doesNotContain(currentMarker).doesNotContain(newMarker);
    }

    // ---------------------------------------------------------------- dữ liệu

    private long staff(String role) {
        return staffWithPassword(role, PASSWORD);
    }

    private long staffWithPassword(String role, String password) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), FAST.encode(password), role);
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

    private static String newEmail() {
        return "change-pw-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private OpenedSession open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "ChangePasswordIT"));
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, id);
    }

    private String hash(long id) {
        return jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id);
    }

    private Map<String, Object> counter(long id) {
        return jdbc.queryForMap("""
                SELECT failed_login_count, first_failed_login_at, locked_until FROM accounts WHERE id = ?
                """, id);
    }

    private List<Map<String, Object>> audits(long id) {
        return jdbc.queryForList("""
                SELECT action, actor_account_id, actor_email, entity_type, entity_id,
                       before_data ->> 'failedLoginCount' AS before_count,
                       after_data ->> 'failedLoginCount' AS after_count,
                       after_data ->> 'lockedUntil' AS after_locked_until
                FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ? AND action <> 'LOGIN_FAILED'
                  AND action <> 'LOGIN_SUCCEEDED'
                ORDER BY id
                """, id);
    }

    private List<Map<String, Object>> outbox(long id) {
        return jdbc.queryForList("""
                SELECT template_code, channel, payload ->> 'so_lan_sai' AS so_lan_sai,
                       payload ->> 'thoi_diem_mo_khoa' AS thoi_diem_mo_khoa
                FROM notification_outbox WHERE recipient_email = ? ORDER BY id
                """, email(id));
    }

    private void assertNoSideEffects(long id) {
        assertThat(counter(id)).containsEntry("failed_login_count", 0);
        assertThat(counter(id).get("locked_until")).isNull();
        assertThat(audits(id)).isEmpty();
        assertThat(outbox(id)).isEmpty();
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.OffsetDateTime) value).toInstant();
    }

    private void awaitWaiting(String query) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(query, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("đổi mật khẩu phải đứng chờ khóa; pg_stat_activity = " + jdbc.queryForList("""
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

    private ResponseEntity<Map<String, Object>> change(String token, String current, String newPassword) {
        return changeRaw(write(Map.of("currentPassword", current, "newPassword", newPassword)),
                MediaType.APPLICATION_JSON, token);
    }

    private ResponseEntity<Map<String, Object>> changeRaw(String body, MediaType contentType, String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(contentType);
        return http.exchange("/api/me/password", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(write(Map.of("email", email, "password", password)), headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> probe(String token) {
        return http.exchange("/api/test/change-password-probe", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
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
