package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.platform.security.BranchScope;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code POST /api/auth/login} (UC03, ST01 — docs/adr/0019) qua HTTP trên Postgres 17 thật. Tài khoản chèn bằng SQL
 * với hash BCrypt thật; thời gian điều khiển bằng {@link MutableClock}. Kiểm: bộ đếm commit khi trả 401, hai lần sai
 * song song đều được đếm (khóa dòng), cửa sổ / hết khóa theo clock, mọi kiểu sai trả cùng một 401 (BR-TK-10), nhánh
 * 400 ghi audit sau khi transaction đã rollback (không giữ khóa / connection), BCrypt chạy khi không giữ connection,
 * validation không đếm, mật khẩu không lọt vào log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LoginIT.TestBeans.class, LoginIT.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class LoginIT {

    /** 09:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");
    private static final String PASSWORD = "matkhau123";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final PasswordEncoder FAST = new BCryptPasswordEncoder(4);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Endpoint cần token, chỉ có trong test: chứng minh token đăng nhập dùng được. */
    @RestController
    static class ProbeController {
        private final BranchScope scope;

        ProbeController(BranchScope scope) {
            this.scope = scope;
        }

        @GetMapping("/api/test/login-probe")
        Map<String, Object> probe() {
            return Map.of("accountId", scope.current().accountId());
        }
    }

    @MockitoBean
    private CustomerQueryApi customerQueryApi;

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
    private SystemConfigService configs;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long branchId;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "LoginIT chi nhánh " + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void successResponseMatchesContractAndTokenWorks() {
        String email = staff("RECEPTIONIST", "ACTIVE");

        ResponseEntity<Map<String, Object>> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200).containsEntry("message", "Đăng nhập thành công");
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("accessToken", "expiresAt", "account", "linkDecisionPending")
                .containsEntry("expiresAt", "2026-10-08T14:00:00Z")
                .containsEntry("linkDecisionPending", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) data.get("account");
        assertThat(account).containsOnlyKeys("id", "email", "role", "status", "isLocked", "mustChangePassword")
                .containsEntry("email", email).containsEntry("role", "RECEPTIONIST")
                .containsEntry("status", "ACTIVE").containsEntry("isLocked", false)
                .containsEntry("mustChangePassword", false);
        long accountId = ((Number) account.get("id")).longValue();

        ResponseEntity<Map<String, Object>> probe = get("/api/test/login-probe", (String) data.get("accessToken"));
        assertThat(probe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(probe.getBody()).containsEntry("accountId", (int) accountId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND ip_address = '127.0.0.1'",
                Long.class, accountId)).isEqualTo(1);
        List<Map<String, Object>> audits = audits(email);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "LOGIN_SUCCEEDED")
                .containsEntry("actor_account_id", accountId).containsEntry("entity_type", "accounts")
                .containsEntry("entity_id", accountId).containsEntry("ip_address", "127.0.0.1");
        verifyNoInteractions(customerQueryApi);
    }

    @Test
    void emailIsCaseInsensitive() {
        String email = staff("VET", "ACTIVE");

        assertThat(login(email.toUpperCase(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void mustChangePasswordStillLogsInAndFlagReturned() {
        String email = staff("CARETAKER", "ACTIVE");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE email = ?", email);

        ResponseEntity<Map<String, Object>> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) data(response).get("account");
        assertThat(account).containsEntry("mustChangePassword", true);
        assertError(get("/api/test/login-probe", (String) data(response).get("accessToken")),
                HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-17)");
    }

    @Test
    void customerLoginReturnsLinkDecisionPending() {
        String email = customer("ACTIVE");
        long accountId = accountId(email);
        doAnswer(invocation -> Optional.of(800L)).when(customerQueryApi).findCustomerIdByAccountId(accountId);
        doAnswer(invocation -> Optional.of(new CustomerContact(800L, "Khách", "0901234567", email, accountId, true)))
                .when(customerQueryApi).findContact(800L);

        ResponseEntity<Map<String, Object>> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response)).containsEntry("linkDecisionPending", true);
    }

    @Test
    void customerWithoutProfileIs500AndNothingCommitted() {
        String email = customer("ACTIVE");
        doAnswer(invocation -> Optional.empty()).when(customerQueryApi).findCustomerIdByAccountId(anyLong());
        jdbc.update("UPDATE accounts SET failed_login_count = 2, first_failed_login_at = ? WHERE email = ?",
                at(T0.minusSeconds(30)), email);

        assertError(login(email, PASSWORD), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);

        assertThat(counter(email)).containsEntry("failed_login_count", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ?", Long.class,
                accountId(email))).isZero();
        assertThat(audits(email)).isEmpty();
    }

    // ---------------------------------------------------------------- sai mật khẩu, ST01

    @Test
    void failureCounterCommittedOn401() {
        String email = staff("VET", "ACTIVE");

        assertError(login(email, "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        assertThat(counter(email)).containsEntry("failed_login_count", 1);
        assertThat(instant(counter(email).get("first_failed_login_at"))).isEqualTo(T0);
        List<Map<String, Object>> audits = audits(email);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "LOGIN_FAILED").containsEntry("reason", "BAD_CREDENTIALS")
                .containsEntry("entity_type", "accounts");
        assertThat(jdbc.queryForObject("SELECT after_data ->> 'failedLoginCount' FROM audit_logs WHERE actor_email = ?",
                String.class, email)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT before_data ->> 'failedLoginCount' FROM audit_logs WHERE actor_email = ?",
                String.class, email)).isEqualTo("0");
    }

    @Test
    void fifthFailureLocksEnqueuesWarningAndCorrectPasswordGets400WithRetryTime() {
        String email = staff("VET", "ACTIVE");
        for (int i = 0; i < 5; i++) {
            clock.set(T0.plusSeconds(60L * i));
            assertError(login(email, "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }

        Instant until = T0.plusSeconds(240).plus(Duration.ofMinutes(15));   // 09:19 VN
        assertThat(instant(counter(email).get("locked_until"))).isEqualTo(until);
        assertThat(counter(email)).containsEntry("failed_login_count", 0);
        List<Map<String, Object>> outbox = outbox(email);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)).containsEntry("template_code", "LOGIN_LOCKED_WARNING")
                .containsEntry("channel", "EMAIL");
        assertThat(outbox.get(0).get("recipient_account_id")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'thoi_diem_mo_khoa' || '|' || (payload ->> 'so_lan_sai')
                FROM notification_outbox WHERE recipient_email = ?""", String.class, email))
                .isEqualTo("09:19 08/10/2026|5");
        assertThat(jdbc.queryForObject("""
                SELECT after_data ->> 'lockedUntil' FROM audit_logs
                WHERE actor_email = ? ORDER BY id DESC LIMIT 1""", String.class, email)).isNotNull();

        ResponseEntity<Map<String, Object>> correct = login(email, PASSWORD);
        assertError(correct, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
        assertThat((String) correct.getBody().get("message")).contains("09:19 08/10/2026");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ?", Long.class,
                accountId(email))).isZero();
    }

    @Test
    void wrongWhileLockedIsNotCountedAndSendsNoSecondWarning() {
        String email = lockedStaff();

        assertError(login(email, "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        assertThat(counter(email)).containsEntry("failed_login_count", 0);
        assertThat(outbox(email)).hasSize(1);
        assertThat(lastAudit(email)).containsEntry("reason", "TEMPORARILY_LOCKED");
    }

    @Test
    void lockExpiresByClock() {
        String email = lockedStaff();
        Instant until = instant(counter(email).get("locked_until"));

        clock.set(until.minusMillis(1));
        assertError(login(email, PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");

        clock.set(until);
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(counter(email).get("locked_until")).isNull();
        assertThat(counter(email)).containsEntry("failed_login_count", 0);
    }

    @Test
    void windowRestartsAfterWindow() {
        String email = staff("VET", "ACTIVE");
        for (int i = 0; i < 4; i++) {
            login(email, "sai12345");
        }
        clock.set(T0.plus(Duration.ofMinutes(15)));

        login(email, "sai12345");

        assertThat(counter(email)).containsEntry("failed_login_count", 1);
        assertThat(counter(email).get("locked_until")).isNull();
        assertThat(outbox(email)).isEmpty();
    }

    @Test
    void relockAfterExpirySendsSecondWarning() {
        String email = lockedStaff();
        clock.set(instant(counter(email).get("locked_until")));

        for (int i = 0; i < 5; i++) {
            login(email, "sai12345");
        }

        assertThat(outbox(email)).hasSize(2);
        assertThat(instant(counter(email).get("locked_until"))).isAfter(clock.instant());
    }

    /** BR-QT-13: {@code locked_until} chốt vào dòng; đổi {@code login.lock_minutes} chỉ áp dụng cho lần khóa sau. */
    @Test
    void configChangeAppliesToNewLockOnly() {
        String email = lockedStaff();
        Instant firstUntil = instant(counter(email).get("locked_until"));
        try {
            jdbc.update("UPDATE system_configs SET value = '30' WHERE key = 'login.lock_minutes'");
            configs.reload();

            assertThat(instant(counter(email).get("locked_until"))).isEqualTo(firstUntil);
            clock.set(firstUntil);
            for (int i = 0; i < 5; i++) {
                login(email, "sai12345");
            }
            assertThat(instant(counter(email).get("locked_until"))).isEqualTo(firstUntil.plus(Duration.ofMinutes(30)));
        } finally {
            jdbc.update("UPDATE system_configs SET value = '15' WHERE key = 'login.lock_minutes'");
            configs.reload();
        }
    }

    @Test
    void passwordOver72BytesIsWrong() {
        String password72 = "a1".repeat(36);
        String email = staffWithPassword("VET", "ACTIVE", password72);
        assertThat(login(email, password72).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(login(email, password72 + "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        assertThat(counter(email)).containsEntry("failed_login_count", 1);
        assertThat(lastAudit(email)).containsEntry("reason", "BAD_CREDENTIALS");
    }

    // ---------------------------------------------------------------- trạng thái (BR-TK-08, 11)

    @Test
    void pendingAccountGets400BrTk08AndAuditSurvivesRollback() {
        String email = customer("PENDING");
        jdbc.update("UPDATE accounts SET failed_login_count = 2, first_failed_login_at = ? WHERE email = ?",
                at(T0.minusSeconds(30)), email);

        ResponseEntity<Map<String, Object>> response = login(email, PASSWORD);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-08)");
        assertThat(response.getBody()).containsEntry("message",
                "Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email (BR-TK-08)");
        assertThat(counter(email)).as("bị chặn: không reset").containsEntry("failed_login_count", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ?", Long.class,
                accountId(email))).isZero();
        assertThat(lastAudit(email)).containsEntry("action", "LOGIN_FAILED").containsEntry("reason", "PENDING")
                .containsEntry("actor_account_id", accountId(email));
        verifyNoInteractions(customerQueryApi);
    }

    @Test
    void pendingWithWrongPasswordGets401NotBrTk08() {
        String email = customer("PENDING");

        assertError(login(email, "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertThat(counter(email)).containsEntry("failed_login_count", 1);
    }

    @Test
    void lockedPendingGetsBrTk09First() {
        String email = customer("PENDING");
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE email = ?", at(T0.plusSeconds(60)), email);

        assertError(login(email, PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
    }

    @Test
    void lockedAccountGets400BrTk11() {
        String email = staff("VET", "ACTIVE");
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE email = ?", email);

        ResponseEntity<Map<String, Object>> response = login(email, PASSWORD);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-11)");
        assertThat(response.getBody()).containsEntry("message",
                "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)");
        assertThat(lastAudit(email)).containsEntry("reason", "LOCKED");
    }

    @Test
    void disabledStaffGets400BrTk11() {
        String email = staff("VET", "DISABLED");

        assertError(login(email, PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-11)");
        assertThat(lastAudit(email)).containsEntry("reason", "DISABLED");
    }

    // ---------------------------------------------------------------- BR-TK-10

    @Test
    void allFailureKindsReturnIdentical401() {
        String unknown = "khong-ton-tai-" + UUID.randomUUID() + "@petcare.test";
        String wrong = staff("VET", "ACTIVE");
        String locked = lockedStaff();
        String pending = customer("PENDING");
        String disabled = staff("VET", "DISABLED");

        List<ResponseEntity<Map<String, Object>>> responses = List.of(
                login(unknown, PASSWORD), login(wrong, "sai12345"), login(locked, "sai12345"),
                login(pending, "sai12345"), login(disabled, "sai12345"), login(wrong, PASSWORD + "x".repeat(80)));

        Map<String, Object> first = withoutVolatileFields(responses.get(0));
        assertThat(first).containsEntry("errorCode", "UNAUTHENTICATED").containsEntry("statusCode", 401)
                .containsEntry("message", "Email hoặc mật khẩu không đúng");
        for (ResponseEntity<Map<String, Object>> response : responses) {
            assertError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
            assertThat(withoutVolatileFields(response)).isEqualTo(first);
        }
        assertThat(lastAudit(unknown)).containsEntry("reason", "UNKNOWN_EMAIL");
        assertThat(lastAudit(unknown).get("actor_account_id")).isNull();
        assertThat(lastAudit(unknown).get("entity_type")).isNull();
    }

    // ---------------------------------------------------------------- đồng thời, transaction, connection

    @Test
    void concurrentWrongPasswordsAreBothCounted() throws Exception {
        String email = staff("VET", "ACTIVE");
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
            Future<ResponseEntity<Map<String, Object>>> a = pool.submit(() -> login(email, marker));
            Future<ResponseEntity<Map<String, Object>>> b = pool.submit(() -> login(email, marker));
            assertThat(a.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(b.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter(email)).containsEntry("failed_login_count", 2);
        assertThat(audits(email)).hasSize(2);
    }

    /** Luồng OTP / khóa tài khoản đang giữ dòng {@code accounts}: đăng nhập chờ rồi đếm đúng, không lỗi. */
    @Test
    void loginWaitsForOtherTransactionHoldingAccountLock() throws Exception {
        String email = staff("VET", "ACTIVE");
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE email = ? FOR UPDATE", Long.class, email);
                locked.countDown();
                sleep(500);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();

            assertError(login(email, "sai12345"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(250));
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(counter(email)).containsEntry("failed_login_count", 1);
    }

    /** docs/adr/0019 mục 4: so BCrypt khi không giữ connection nào của pool. */
    @Test
    void bcryptRunsWithoutHoldingConnection() {
        String email = staff("VET", "ACTIVE");
        HikariDataSource hikari = hikari();
        AtomicInteger activeDuringBcrypt = new AtomicInteger(-1);
        doAnswer(invocation -> {
            if (PASSWORD.equals(invocation.getArgument(0))) {
                activeDuringBcrypt.set(hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(activeDuringBcrypt.get()).isZero();
    }

    /**
     * docs/adr/0019 mục 6: audit của nhánh 400 ghi khi transaction đăng nhập đã kết thúc (khóa đã nhả, connection đã
     * trả). Trigger tạm đếm các phiên Postgres đang "idle in transaction" lúc INSERT audit: nếu audit ghi trong lúc
     * transaction đăng nhập còn mở thì có 1 và trigger làm lệnh ghi lỗi → mất dòng audit.
     */
    @Test
    void rejected400AuditIsWrittenAfterLoginTransactionEnded() {
        String email = customer("PENDING");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_login_no_open_tx_" + suffix;
        String trigger = "trg_it_login_no_open_tx_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.actor_email = '%s' AND EXISTS (
                            SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database() AND pid <> pg_backend_pid()
                              AND state LIKE 'idle in transaction%%') THEN
                        RAISE EXCEPTION 'IT: transaction đăng nhập vẫn mở khi ghi audit';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(function, email));
        jdbc.execute("CREATE TRIGGER %s BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, function));
        try {
            assertError(login(email, PASSWORD), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-08)");
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON audit_logs");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        assertThat(lastAudit(email)).containsEntry("reason", "PENDING");
    }

    // ---------------------------------------------------------------- hình thức (không đếm, không audit)

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MISSING_EMAIL    | email: Email không được để trống",
            "EMPTY_EMAIL      | email: Email không được để trống",
            "SPACES_EMAIL     | email: Email không được để trống",
            "BAD_EMAIL        | email: Email không đúng định dạng",
            "PADDED_EMAIL     | email: Email không đúng định dạng",
            "LONG_EMAIL       | email: Email tối đa 255 ký tự",
            "MISSING_PASSWORD | password: Mật khẩu không được để trống",
            "EMPTY_PASSWORD   | password: Mật khẩu không được để trống",
            "SPACES_PASSWORD  | password: Mật khẩu không được để trống",
            "BOTH_MISSING     | password: Mật khẩu không được để trống"})
    void invalidBodiesRejectedWithoutSideEffects(String kind, String expected) {
        String email = staff("VET", "ACTIVE");
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "sai12345");
        switch (kind) {
            case "MISSING_EMAIL" -> body.remove("email");
            case "EMPTY_EMAIL" -> body.put("email", "");
            case "SPACES_EMAIL" -> body.put("email", "   ");
            case "BAD_EMAIL" -> body.put("email", "khong-la-email");
            case "PADDED_EMAIL" -> body.put("email", " " + email + " ");
            case "LONG_EMAIL" -> body.put("email", "a".repeat(243) + "@petcare.test");
            case "MISSING_PASSWORD" -> body.remove("password");
            case "EMPTY_PASSWORD" -> body.put("password", "");
            case "SPACES_PASSWORD" -> body.put("password", "   ");
            case "BOTH_MISSING" -> body.clear();
            default -> throw new IllegalArgumentException(kind);
        }

        ResponseEntity<Map<String, Object>> response = loginRaw(write(body), MediaType.APPLICATION_JSON, null);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains(expected);
        if (kind.equals("BOTH_MISSING")) {
            assertThat((String) response.getBody().get("message")).contains("email: Email không được để trống");
        }
        assertNoSideEffects(email);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"email\": ",
            "''",
            "{\"email\": \"a@petcare.test\"",
            "{\"email\": [\"a@petcare.test\"], \"password\": \"x1234567\"}",
            "{\"email\": \"a@petcare.test\", \"password\": {\"x\": 1}}"})
    void malformedRequestIsRejected(String body) {
        assertError(loginRaw(body, MediaType.APPLICATION_JSON, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                null);
    }

    @Test
    void unsupportedMediaTypeAndMethodAreRejected() {
        String email = staff("VET", "ACTIVE");

        assertError(loginRaw("email=" + email, MediaType.TEXT_PLAIN, null), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", null);
        assertError(http.exchange("/api/auth/login", HttpMethod.GET, null, MAP), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertNoSideEffects(email);
    }

    @Test
    void unknownFieldsAndGarbageTokenAreIgnored() {
        String email = staff("VET", "ACTIVE");
        Map<String, Object> body = Map.of("email", email, "password", PASSWORD, "rememberMe", true);

        ResponseEntity<Map<String, Object>> response = loginRaw(write(body), MediaType.APPLICATION_JSON, "rac.rac.rac");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Convention 08 L9: mật khẩu không xuất hiện trong log ở mọi nhánh lỗi. */
    @Test
    void passwordNeverAppearsInLogs(CapturedOutput output) {
        String marker = "Mk-" + UUID.randomUUID() + "1";
        String email = staff("VET", "ACTIVE");
        String lockedEmail = staff("VET", "ACTIVE");
        jdbc.update("UPDATE accounts SET password_hash = ?, is_locked = true WHERE email = ?",
                FAST.encode(marker), lockedEmail);

        loginRaw("{\"email\": \"" + email + "\", \"password\": \"" + marker + "\", ", MediaType.APPLICATION_JSON, null);
        loginRaw("{\"email\": \"" + email + "\", \"password\": {\"v\": \"" + marker + "\"}}",
                MediaType.APPLICATION_JSON, null);
        login(email, marker);
        login(lockedEmail, marker);

        assertThat(output.getAll()).doesNotContain(marker);
    }

    // ---------------------------------------------------------------- dữ liệu

    private String staff(String role, String status) {
        return staffWithPassword(role, status, PASSWORD);
    }

    private String staffWithPassword(String role, String status, String password) {
        String email = email();
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, ?, ?) RETURNING id
                """, Long.class, email, FAST.encode(password), role, status);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return email;
    }

    private String customer(String status) {
        String email = email();
        jdbc.update("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, ?, 'CUSTOMER', ?, ?)
                """, email, FAST.encode(PASSWORD), status,
                status.equals("PENDING") ? at(T0.plus(Duration.ofHours(24))) : null);
        return email;
    }

    /** Nhân viên vừa bị khóa tạm qua 5 lần sai thật (1 email cảnh báo trong outbox). */
    private String lockedStaff() {
        String email = staff("VET", "ACTIVE");
        for (int i = 0; i < 5; i++) {
            login(email, "sai12345");
        }
        assertThat(counter(email).get("locked_until")).isNotNull();
        return email;
    }

    private static String email() {
        return "login-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", Long.class, email);
    }

    private Map<String, Object> counter(String email) {
        return jdbc.queryForMap("""
                SELECT failed_login_count, first_failed_login_at, locked_until FROM accounts WHERE email = ?
                """, email);
    }

    private List<Map<String, Object>> audits(String email) {
        return jdbc.queryForList("""
                SELECT action, reason, actor_account_id, entity_type, entity_id, ip_address
                FROM audit_logs WHERE actor_email = ? ORDER BY id
                """, email.toLowerCase());
    }

    private Map<String, Object> lastAudit(String email) {
        List<Map<String, Object>> rows = audits(email);
        assertThat(rows).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private List<Map<String, Object>> outbox(String email) {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id FROM notification_outbox
                WHERE recipient_email = ? ORDER BY id
                """, email);
    }

    private void assertNoSideEffects(String email) {
        assertThat(counter(email)).containsEntry("failed_login_count", 0);
        assertThat(audits(email)).isEmpty();
        assertThat(outbox(email)).isEmpty();
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((OffsetDateTime) value).toInstant();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return loginRaw(write(Map.of("email", email, "password", password)), MediaType.APPLICATION_JSON, null);
    }

    private ResponseEntity<Map<String, Object>> loginRaw(String body, MediaType contentType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange("/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> withoutVolatileFields(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> copy = new HashMap<>(response.getBody());
        copy.remove("timestamp");
        copy.remove("traceId");
        return copy;
    }

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
