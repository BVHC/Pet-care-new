package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * T14 phần 4 — sửa hộ email nhân viên: {@code POST /api/staff/{id}/email-change} (#23) và
 * {@code …/email-change/confirm} (#24) qua HTTP trên Postgres 17 thật (identity-v1; BR-TK-01, 04…07, 15, 16, BR-QT-01,
 * 15; docs/adr/0034). Không mock module nào: đăng nhập, quên / đặt lại mật khẩu, tạo nhân viên, PATCH hồ sơ, kích hoạt
 * lại đều chạy thật trong các ca tương tác. {@link MutableClock} điều khiển hạn mã và quota BR-TK-07. Mỗi test tự tạo
 * tài khoản với email ngẫu nhiên; audit tra theo actor / entity riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffEmailChangeIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class StaffEmailChangeIT {

    /** 03:00 UTC = 10:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-12T03:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Petcare123";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> STAFF_KEYS = Set.of("accountId", "email", "phone", "fullName", "role", "status",
            "isLocked", "branchId", "specialty", "bio", "mustChangePassword", "online");
    private static final String WAITING_ON_ANY_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND datname = current_database()
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private AccountRepository accountRepository;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long branchB;
    private long superManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        tag = "E" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch();
        branchB = branch();
        superManager = staff("SUPER_MANAGER", null);
    }

    // ================================================================ #23 gửi mã

    @Test
    void startSendsCodeOnlyToNormalizedNewEmailAndReturnsContractShape(CapturedOutput output) {
        long vet = staff("VET", branchA);
        String oldEmail = email(vet);
        String newEmail = newEmail();

        assertError(start(token(superManager), vet, " " + newEmail + " "), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        Map<String, Object> data = map(ok(start(token(superManager), vet, newEmail.toUpperCase())));

        assertThat(data).containsOnlyKeys("resendAvailableAt", "maskedEmail").containsEntry("maskedEmail", null);
        assertThat(Instant.parse((String) data.get("resendAvailableAt"))).isEqualTo(T0.plusSeconds(60));
        String code = code(newEmail);
        assertThat(code).matches("\\d{6}");
        assertThat(data.toString()).doesNotContain(code);
        assertThat(output.getAll()).doesNotContain(code);

        Map<String, Object> otp = otps(vet).get(0);
        assertThat(otp).containsEntry("target_email", newEmail).containsEntry("customer_id", null)
                .containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
        assertThat(email(vet)).as("BR-TK-16: chưa xác thực thì giữ email cũ").isEqualTo(oldEmail);
        assertThat(outboxTo(oldEmail)).isZero();
        assertThat(auditsOn(vet)).as("Q4: gửi mã không audit").isZero();
        assertPayloadMatchesTemplate("OTP_EMAIL_CHANGE", newEmail);
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'thoi_han_phut' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_EMAIL_CHANGE'""", String.class, newEmail))
                .isEqualTo("5");
        assertNotLocked(vet);
    }

    /** Q5: email mới trùng email hiện tại (khác hoa thường) → BR-TK-01 message riêng, không mã, không quota. */
    @Test
    void sameAsCurrentEmailIsBrTk01WithOwnMessage() {
        long vet = staff("VET", branchA);

        ResponseEntity<Map<String, Object>> response = start(token(superManager), vet, email(vet).toUpperCase());

        assertRule(response, "BR-TK-01");
        assertThat(response.getBody().get("message")).isEqualTo("Email mới trùng email hiện tại (BR-TK-01)");
        assertThat(otps(vet)).isEmpty();
        assertThat(outboxTo(email(vet))).isZero();
    }

    @Test
    void emailOfAnotherAccountIncludingPendingIsBrTk01() {
        long vet = staff("VET", branchA);
        long other = staff("CARETAKER", branchB);
        String pendingEmail = newEmail();
        jdbc.update("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, 'hash', 'CUSTOMER', 'PENDING', ?)""", pendingEmail,
                java.sql.Timestamp.from(T0.plus(Duration.ofHours(24))));

        for (String taken : List.of(email(other), pendingEmail)) {
            ResponseEntity<Map<String, Object>> response = start(token(superManager), vet, taken);
            assertRule(response, "BR-TK-01");
            assertThat(response.getBody().get("message")).isEqualTo("Email đã được sử dụng (BR-TK-01)");
            assertThat(outboxTo(taken)).isZero();
        }
        assertThat(otps(vet)).isEmpty();
    }

    /** BR-TK-07: 60 s giữa hai lần gửi tới cùng email; mã thứ 6 trong 1 giờ bị chặn; quota tính theo email nhận. */
    @Test
    void resendQuotaFollowsTargetEmailAndNewCodeCancelsOld() {
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(superManager);

        ok(start(token, vet, newEmail));
        clock.advance(Duration.ofSeconds(30));
        assertRule(start(token, vet, newEmail), "BR-TK-07");
        assertThat(otps(vet)).hasSize(1);

        for (int sent = 2; sent <= 5; sent++) {
            clock.advance(Duration.ofSeconds(61));
            ok(start(token, vet, newEmail));
        }
        clock.advance(Duration.ofSeconds(61));
        assertRule(start(token, vet, newEmail), "BR-TK-07");

        List<Map<String, Object>> codes = otps(vet);
        assertThat(codes).hasSize(5);
        assertThat(codes.subList(0, 4)).allSatisfy(row -> assertThat(row.get("invalidated_at")).isNotNull());
        assertThat(codes.get(4).get("invalidated_at")).as("BR-TK-05: chỉ mã mới nhất còn sống").isNull();
    }

    /** Q3: mã mới của nhân viên hủy mã cũ của chính nhân viên đó, kể cả khi đổi sang email khác. */
    @Test
    void newCodeForAnotherEmailCancelsOwnPreviousCodeAndOnlyLatestEmailCanBeConfirmed() {
        long vet = staff("VET", branchA);
        String first = newEmail();
        String second = newEmail();
        String token = token(superManager);
        ok(start(token, vet, first));
        String firstCode = code(first);
        ok(start(token, vet, second));

        ResponseEntity<Map<String, Object>> stale = confirm(token, vet, firstCode);
        if (!firstCode.equals(code(second))) {
            assertRule(stale, "BR-TK-05");
        }
        assertThat(email(vet)).isNotEqualTo(first);
        assertThat(otps(vet).get(0).get("invalidated_at")).isNotNull();

        Map<String, Object> data = map(ok(confirm(token, vet, code(second))));
        assertThat(data).containsEntry("email", second);
    }

    /** Q3: tài khoản khác xin mã tới cùng email không hủy được mã của X; ai xác nhận trước thắng (R8). */
    @Test
    void otherAccountCannotCancelCodeAndFirstConfirmWins() {
        long x = staff("VET", branchA);
        long y = staff("VET", branchB);
        String target = newEmail();
        String token = token(superManager);

        ok(start(token, x, target));
        String codeX = code(target);
        clock.advance(Duration.ofSeconds(61));
        ok(start(token, y, target));
        String codeY = code(target);
        assertThat(otps(x).get(0).get("invalidated_at")).as("mã của X không bị Y hủy").isNull();

        assertThat(map(ok(confirm(token, x, codeX)))).containsEntry("email", target);
        ResponseEntity<Map<String, Object>> loser = confirm(token, y, codeY);
        assertRule(loser, "BR-TK-01");
        assertThat(loser.getBody().get("message")).isEqualTo("Email đã được sử dụng (BR-TK-01)");
        assertThat(otps(y).get(0)).as("BR-TK-01 sau settle → rollback cả consumed_at")
                .containsEntry("consumed_at", null).containsEntry("failed_attempts", 0);
        assertThat(email(y)).isNotEqualTo(target);
    }

    // ================================================================ #24 xác nhận — thành công

    @Test
    void confirmChangesEmailNotifiesOldAndNewAuditsAndEqualsGet() {
        long vet = staff("VET", branchA);
        String oldEmail = email(vet);
        String newEmail = newEmail();
        String token = token(superManager);
        String vetToken = token(vet);
        ok(start(token, vet, newEmail));
        Map<String, Object> before = map(ok(get("/api/staff/" + vet, token)));

        Map<String, Object> data = map(ok(confirm(token, vet, code(newEmail))));

        assertThat(data).containsOnlyKeys(STAFF_KEYS).containsEntry("email", newEmail);
        Map<String, Object> expected = new HashMap<>(before);
        expected.put("email", newEmail);
        assertThat(data).as("chỉ email đổi").isEqualTo(expected);
        assertThat(map(ok(get("/api/staff/" + vet, token)))).isEqualTo(data);
        assertThat(email(vet)).isEqualTo(newEmail);
        assertThat(otps(vet).get(0).get("consumed_at")).isNotNull();

        for (String recipient : List.of(oldEmail, newEmail)) {
            Map<String, Object> notice = jdbc.queryForMap("""
                    SELECT payload->>'email_cu' AS email_cu, payload->>'email_moi' AS email_moi,
                           payload->>'thoi_diem' AS thoi_diem
                    FROM notification_outbox WHERE recipient_email = ? AND template_code = 'EMAIL_CHANGED_NOTICE'""",
                    recipient);
            assertThat(notice).containsEntry("email_cu", oldEmail).containsEntry("email_moi", newEmail)
                    .containsEntry("thoi_diem", "10:00 12/10/2026");
            assertPayloadMatchesTemplate("EMAIL_CHANGED_NOTICE", recipient);
        }

        List<Map<String, Object>> audits = audits(superManager, "STAFF_EMAIL_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts").containsEntry("entity_id", vet)
                .containsEntry("actor_email", email(superManager));
        assertThat(audits.get(0).get("before_data")).isEqualTo("{\"email\": \"" + oldEmail + "\"}");
        assertThat(audits.get(0).get("after_data"))
                .isEqualTo("{\"email\": \"" + newEmail + "\", \"verificationMethod\": \"EMAIL_CODE\"}");

        assertThat(map(ok(get("/api/me", vetToken))).get("account")).as("Q2: phiên cũ vẫn sống, đọc email mới")
                .satisfies(account -> assertThat(map(account)).containsEntry("email", newEmail));
        assertNotLocked(vet);
    }

    /** Email là định danh (BR-TK-01): sau khi đổi, mọi luồng tra theo email đi theo email mới. */
    @Test
    void afterChangeLoginForgotSearchAndOldEmailFollowTheNewEmail() {
        long vet = staffWithPassword("VET", branchA);
        String oldEmail = email(vet);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        ok(confirm(token, vet, code(newEmail)));

        assertError(login(oldEmail, PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(login(newEmail, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(forgot(oldEmail).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxTo(oldEmail, "OTP_PASSWORD_RESET")).as("BR-TK-10: 202 nhưng không gửi mã").isZero();
        assertThat(forgot(newEmail).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxTo(newEmail, "OTP_PASSWORD_RESET"))
                .as("BR-TK-07 tính theo email nhận, mọi mục đích: email mới vừa nhận mã đổi email < 60 giây").isZero();
        clock.advance(Duration.ofSeconds(61));
        assertThat(forgot(newEmail).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxTo(newEmail, "OTP_PASSWORD_RESET")).isEqualTo(1);

        assertThat(searchIds(oldEmail)).doesNotContain(vet);
        assertThat(searchIds(newEmail)).containsExactly(vet);

        ResponseEntity<Map<String, Object>> reuse = post("/api/staff", token, Map.of("email", oldEmail,
                "phone", "0901234567", "fullName", "Dùng lại " + tag, "role", "VET", "branchId", branchA));
        assertThat(reuse.getStatusCode()).as("email cũ được giải phóng").isEqualTo(HttpStatus.CREATED);
    }

    /** R12: mã đặt lại mật khẩu đã gửi tới email cũ không còn tác dụng sau khi đổi email. */
    @Test
    void resetCodeSentToOldEmailIsUselessAfterChange() {
        long vet = staffWithPassword("VET", branchA);
        String oldEmail = email(vet);
        assertThat(forgot(oldEmail).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String resetCode = latestPayload(oldEmail, "OTP_PASSWORD_RESET");
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        ok(confirm(token, vet, code(newEmail)));

        assertRule(reset(oldEmail, resetCode, "Petcare456"), "BR-TK-05");
        assertRule(reset(newEmail, resetCode, "Petcare456"), "BR-TK-05");
        assertThat(login(newEmail, PASSWORD).getStatusCode()).as("mật khẩu không đổi").isEqualTo(HttpStatus.OK);
    }

    /** Q1: không kiểm trạng thái — DISABLED, đang bị khóa vẫn đổi được; trạng thái, khóa giữ nguyên. */
    @ParameterizedTest(name = "status={0} locked={1}")
    @CsvSource({"DISABLED, false", "ACTIVE, true", "DISABLED, true"})
    void anyStatusCanChangeEmailAndKeepsStatusAndLock(String status, boolean locked) {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET status = ?, is_locked = ?, locked_reason = ? WHERE id = ?", status, locked,
                locked ? "IT" : null, vet);
        String newEmail = newEmail();
        String token = token(superManager);

        ok(start(token, vet, newEmail));
        Map<String, Object> data = map(ok(confirm(token, vet, code(newEmail))));

        assertThat(data).containsEntry("email", newEmail).containsEntry("status", status)
                .containsEntry("isLocked", locked);
    }

    @Test
    void adminChangesSuperManagerEmail() {
        long admin = staff("ADMIN", null);
        long target = staff("SUPER_MANAGER", null);
        String newEmail = newEmail();

        ok(start(token(admin), target, newEmail));
        assertThat(map(ok(confirm(token(admin), target, code(newEmail))))).containsEntry("email", newEmail)
                .containsEntry("branchId", null);
    }

    // ================================================================ #24 xác nhận — mã sai / hết hạn

    @Test
    void wrongCodeIsCountedAndCommittedFifthCancelsTheCode() {
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        String right = code(newEmail);
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertRule(confirm(token, vet, wrong), "BR-TK-05");
            assertThat(otps(vet).get(0).get("failed_attempts")).as("commit sau lần sai %d", attempt)
                    .isEqualTo(attempt);
        }
        assertRule(confirm(token, vet, wrong), "BR-TK-06");
        assertThat(otps(vet).get(0).get("invalidated_at")).isNotNull();
        assertRule(confirm(token, vet, right), "BR-TK-05");
        assertThat(email(vet)).isNotEqualTo(newEmail);
        assertThat(audits(superManager, "STAFF_EMAIL_CHANGED")).isEmpty();
    }

    @Test
    void expiredCodeIsRejectedWithoutCounting() {
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));

        clock.advance(Duration.ofMinutes(5));
        assertRule(confirm(token, vet, code(newEmail)), "BR-TK-05");

        assertThat(otps(vet).get(0)).containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
        assertThat(email(vet)).isNotEqualTo(newEmail);
    }

    @Test
    void confirmWithoutCodeAndSecondConfirmAreBrTk05() {
        long vet = staff("VET", branchA);
        String token = token(superManager);
        assertRule(confirm(token, vet, "123456"), "BR-TK-05");

        String newEmail = newEmail();
        ok(start(token, vet, newEmail));
        String code = code(newEmail);
        ok(confirm(token, vet, code));
        assertRule(confirm(token, vet, code), "BR-TK-05");

        assertThat(audits(superManager, "STAFF_EMAIL_CHANGED")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = 'EMAIL_CHANGED_NOTICE'
                  AND payload->>'email_moi' = ?""", Long.class, newEmail)).isEqualTo(2);
    }

    // ================================================================ phân quyền, 404, hình thức

    @ParameterizedTest(name = "{0}@{1} → {2}@{3}: {4}")
    @CsvSource({
            "ADMIN,          -, VET,            A, 403",
            "SUPER_MANAGER,  -, SUPER_MANAGER,  -, 403",
            "BRANCH_MANAGER, A, VET,            B, 403",
            "BRANCH_MANAGER, A, BRANCH_MANAGER, A, 403",
            "BRANCH_MANAGER, A, RECEPTIONIST,   A, 200",
            "SUPER_MANAGER,  -, BRANCH_MANAGER, B, 200"})
    void hierarchyMatrixForStart(String actorRole, String actorBranch, String targetRole, String targetBranch,
            int status) {
        long actor = staff(actorRole, branchOf(actorBranch));
        long target = staff(targetRole, branchOf(targetBranch));
        String newEmail = newEmail();

        ResponseEntity<Map<String, Object>> response = start(token(actor), target, newEmail);

        if (status == 200) {
            ok(response);
            assertThat(audits(actor, "STAFF_ACTION_DENIED")).isEmpty();
            return;
        }
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        List<Map<String, Object>> denied = audits(actor, "STAFF_ACTION_DENIED");
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0)).containsEntry("entity_id", target);
        assertThat((String) denied.get(0).get("after_data")).contains("\"action\": \"CHANGE_EMAIL\"");
        assertThat(otps(target)).isEmpty();
        assertThat(outboxTo(newEmail)).isZero();
        assertNotLocked(target);
    }

    @Test
    void selfIsDeniedEvenForSuperManagerAndAdmin() {
        long admin = staff("ADMIN", null);
        assertError(start(token(superManager), superManager, newEmail()), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(start(token(admin), admin, newEmail()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(confirm(token(admin), admin, "123456"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    /** Thứ tự 403 trước BR-TK-01 (ADR-0029 mục 3): người ngoài phân cấp không dò được email nào đã có tài khoản. */
    @Test
    void outsiderWithTakenEmailGets403NotBrTk01() {
        long managerA = staff("BRANCH_MANAGER", branchA);
        long vetB = staff("VET", branchB);

        assertError(start(token(managerA), vetB, email(superManager)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    /** Bước đọc của #24: phân cấp kiểm trước khi tra mã — người ngoài không biết nhân viên có mã đang mở hay không. */
    @Test
    void confirmByOutsiderIs403AuditedAfterRollbackAndNeverTouchesCode() {
        long managerA = staff("BRANCH_MANAGER", branchA);
        long vetB = staff("VET", branchB);
        ok(start(token(superManager), vetB, newEmail()));
        String actorEmail = email(managerA);

        withNoOpenTransactionDuringAudit(actorEmail, () -> assertError(confirm(token(managerA), vetB, "123456"),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH"));

        assertThat(audits(managerA, "STAFF_ACTION_DENIED")).hasSize(1);
        assertThat(otps(vetB).get(0)).containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
    }

    @Test
    void missingAndCustomerAre404OnBothEndpoints() {
        long customer = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id""", Long.class, newEmail());
        String token = token(superManager);

        for (long id : new long[] {customer, 987654321L}) {
            assertError(start(token, id, newEmail()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            assertError(confirm(token, id, "123456"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void wrongRoleIs403WithoutAuditNoTokenIs401AndPendingFirstPasswordIsBrTk17() {
        long vet = staff("VET", branchA);
        long other = staff("VET", branchA);
        assertError(start(token(vet), other, newEmail()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(confirm(token(vet), other, "123456"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(audits(vet, "STAFF_ACTION_DENIED")).isEmpty();

        assertError(start(null, other, newEmail()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(confirm(null, other, "123456"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long manager = staff("SUPER_MANAGER", null);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", manager);
        assertRule(start(token(manager), other, newEmail()), "BR-TK-17");
        assertThat(otps(other)).isEmpty();
    }

    @Test
    void malformedInputIs400BeforeAnythingAndDoesNotCountAnAttempt() {
        long vet = staff("VET", branchA);
        String token = token(superManager);
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60) + ".vn";

        for (String body : List.of("{}", "{\"newEmail\":\"\"}", "{\"newEmail\":\"khong-hop-le\"}",
                "{\"newEmail\":\"" + tooLong + "\"}", "{\"newEmail\":123}")) {
            ResponseEntity<Map<String, Object>> response = raw("/api/staff/" + vet + "/email-change", token, body);
            assertThat(response.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertError(raw("/api/staff/" + vet + "/email-change", token, "{\"newEmail\":"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw("/api/staff/abc/email-change", token, "{\"newEmail\":\"a@b.vn\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(otps(vet)).isEmpty();

        String newEmail = newEmail();
        ok(start(token, vet, newEmail));
        for (String body : List.of("{}", "{\"code\":\"\"}", "{\"code\":\"12a456\"}", "{\"code\":\"123456789\"}")) {
            assertError(raw("/api/staff/" + vet + "/email-change/confirm", token, body), HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED");
        }
        assertThat(otps(vet).get(0).get("failed_attempts")).as("lỗi hình thức không tiêu lượt nhập").isEqualTo(0);
    }

    // ================================================================ commit / rollback

    @Test
    void outboxFailureOnStartRollsBackCodeAndKeepsPreviousOne() {
        long vet = staff("VET", branchA);
        String first = newEmail();
        String second = newEmail();
        String token = token(superManager);
        ok(start(token, vet, first));

        withTrigger("notification_outbox", "INSERT", "NEW.recipient_email = '" + second + "'", () ->
                assertError(start(token, vet, second), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(otps(vet)).hasSize(1);
        assertThat(otps(vet).get(0).get("invalidated_at")).as("mã cũ còn sống").isNull();
        ok(start(token, vet, second));
        assertThat(otps(vet)).as("lần lỗi không tiêu quota").hasSize(2);
    }

    @ParameterizedTest(name = "lỗi INSERT {0}")
    @CsvSource({"audit_logs, NEW.action = 'STAFF_EMAIL_CHANGED'",
            "notification_outbox, NEW.template_code = 'EMAIL_CHANGED_NOTICE'"})
    void failureAfterSettleRollsBackEmailCodeNoticesAndAudit(String table, String condition) {
        long vet = staff("VET", branchA);
        String oldEmail = email(vet);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        String code = code(newEmail);

        withTrigger(table, "INSERT", condition, () ->
                assertError(confirm(token, vet, code), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(email(vet)).isEqualTo(oldEmail);
        assertThat(otps(vet).get(0)).containsEntry("consumed_at", null).containsEntry("failed_attempts", 0);
        assertThat(outboxTo(oldEmail, "EMAIL_CHANGED_NOTICE")).isZero();
        assertThat(audits(superManager, "STAFF_EMAIL_CHANGED")).isEmpty();
        assertNotLocked(vet);
        assertThat(map(ok(confirm(token, vet, code)))).as("mã vẫn dùng lại được").containsEntry("email", newEmail);
    }

    @Test
    void bcryptRunsWithoutHoldingAConnection() {
        long vet = staff("VET", branchA);
        List<Integer> active = new ArrayList<>();
        doAnswer(invocation -> {
            Object raw = invocation.getArgument(0);
            if (raw.toString().matches("\\d{6}")) {
                active.add(hikari().getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
        doAnswer(invocation -> {
            active.add(hikari().getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
        String token = token(superManager);
        String newEmail = newEmail();

        ok(start(token, vet, newEmail));
        ok(confirm(token, vet, code(newEmail)));

        assertThat(active).hasSize(2).containsOnly(0);
    }

    // ================================================================ đồng thời

    /** R3: mã bị thay giữa lúc đọc và lúc khóa → BR-TK-05 không đếm; mã mới nguyên vẹn. */
    @Test
    void codeReplacedBetweenReadAndLockIsNotCounted() {
        long vet = staff("VET", branchA);
        String first = newEmail();
        String second = newEmail();
        String token = token(superManager);
        ok(start(token, vet, first));
        String firstCode = code(first);
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object matched = invocation.callRealMethod();
            if (once.getAndSet(false)) {
                ok(start(token, vet, second));
            }
            return matched;
        }).when(passwordEncoder).matches(any(), any());

        assertRule(confirm(token, vet, firstCode), "BR-TK-05");

        List<Map<String, Object>> codes = otps(vet);
        assertThat(codes.get(0)).containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
        assertThat(codes.get(1)).containsEntry("failed_attempts", 0).containsEntry("invalidated_at", null);
        assertThat(email(vet)).isNotEqualTo(first);
    }

    /** R9: nhân viên vừa bị điều chuyển khỏi chi nhánh của người xác nhận → 403 dưới khóa, bộ đếm không đổi. */
    @Test
    void hierarchyIsRecheckedUnderLock() {
        long managerA = staff("BRANCH_MANAGER", branchA);
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(managerA);
        ok(start(token, vet, newEmail));
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object matched = invocation.callRealMethod();
            if (once.getAndSet(false)) {
                jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, vet);
            }
            return matched;
        }).when(passwordEncoder).matches(any(), any());

        assertError(confirm(token, vet, code(newEmail)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        assertThat(audits(managerA, "STAFF_ACTION_DENIED")).hasSize(1);
        assertThat(otps(vet).get(0)).containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
        assertThat(email(vet)).isNotEqualTo(newEmail);
    }

    /** R6: hai nhân viên cùng xác nhận một email mới song song → một 200, một 409; bên thua không ghi gì. */
    @Test
    void concurrentConfirmsToSameEmailGiveOneSuccessOneConflict() throws Exception {
        long x = staff("VET", branchA);
        long y = staff("VET", branchB);
        String target = newEmail();
        String token = token(superManager);
        ok(start(token, x, target));
        String codeX = code(target);
        clock.advance(Duration.ofSeconds(61));
        ok(start(token, y, target));
        String codeY = code(target);
        barrierOnExistsByEmail(target);

        List<ResponseEntity<Map<String, Object>>> results = parallel(() -> confirm(token, x, codeX),
                () -> confirm(token, y, codeY));

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertError(r, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Long.class, target))
                .isEqualTo(1);
        long winner = email(x).equals(target) ? x : y;
        long loser = winner == x ? y : x;
        assertThat(otps(loser).get(0)).containsEntry("consumed_at", null);
        assertThat(audits(superManager, "STAFF_EMAIL_CHANGED")).hasSize(1)
                .allSatisfy(row -> assertThat(row).containsEntry("entity_id", winner));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = 'EMAIL_CHANGED_NOTICE'
                  AND payload->>'email_moi' = ?""", Long.class, target)).isEqualTo(2);
        assertNotLocked(x);
        assertNotLocked(y);
    }

    /** R7: tạo nhân viên bằng email E song song với xác nhận X → E: không bao giờ có hai tài khoản cùng E. */
    @Test
    void createStaffRacingConfirmNeverDuplicatesEmail() throws Exception {
        long x = staff("VET", branchA);
        String target = newEmail();
        String token = token(superManager);
        ok(start(token, x, target));
        String code = code(target);
        barrierOnExistsByEmail(target);

        List<ResponseEntity<Map<String, Object>>> results = parallel(() -> confirm(token, x, code),
                () -> post("/api/staff", token, Map.of("email", target, "phone", "0901234567",
                        "fullName", "Song song " + tag, "role", "VET", "branchId", branchA)));

        assertThat(results).extracting(ResponseEntity::getStatusCode).contains(HttpStatus.CONFLICT)
                .containsAnyOf(HttpStatus.OK, HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Long.class, target))
                .isEqualTo(1);
    }

    /** R5 + R4: các xác nhận của cùng nhân viên xếp hàng trên khóa {@code accounts}. */
    @Test
    void concurrentWrongCodesAreBothCountedAndDoubleConfirmChangesOnce() throws Exception {
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        String right = code(newEmail);
        String wrong = right.equals("000000") ? "111111" : "000000";

        List<ResponseEntity<Map<String, Object>>> wrongs = whileAccountLocked(vet,
                List.of(() -> confirm(token, vet, wrong), () -> confirm(token, vet, wrong)));
        wrongs.forEach(response -> assertRule(response, "BR-TK-05"));
        assertThat(otps(vet).get(0).get("failed_attempts")).isEqualTo(2);

        List<ResponseEntity<Map<String, Object>>> rights = whileAccountLocked(vet,
                List.of(() -> confirm(token, vet, right), () -> confirm(token, vet, right)));
        ok(rights.get(0));
        assertRule(rights.get(1), "BR-TK-05");
        assertThat(audits(superManager, "STAFF_EMAIL_CHANGED")).hasSize(1);
        assertThat(otps(vet).get(0).get("failed_attempts")).as("lần sau không đếm").isEqualTo(2);
    }

    /** R1: hai lần gửi mã tới cùng email cho cùng nhân viên xếp hàng → lần sau BR-TK-07. */
    @Test
    void concurrentStartsForSameEmailSendOneCode() throws Exception {
        long vet = staff("VET", branchA);
        String newEmail = newEmail();
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLocked(vet,
                List.of(() -> start(token, vet, newEmail), () -> start(token, vet, newEmail)));

        ok(results.get(0));
        assertRule(results.get(1), "BR-TK-07");
        assertThat(otps(vet)).hasSize(1);
        assertThat(outboxTo(newEmail)).isEqualTo(1);
    }

    /**
     * R11, R12, R13, R10: xác nhận đổi email nhận khóa trước, các luồng khác của cùng tài khoản chờ rồi chạy trên dữ
     * liệu mới — quên mật khẩu bằng email cũ không tạo mã, đặt lại bằng mã email cũ bị từ chối, SĐT tự sửa không bị đè.
     */
    @Test
    void flowsQueuedBehindConfirmSeeTheNewEmail() throws Exception {
        long vet = staffWithPassword("VET", branchA);
        String oldEmail = email(vet);
        assertThat(forgot(oldEmail).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String resetCode = latestPayload(oldEmail, "OTP_PASSWORD_RESET");
        clock.advance(Duration.ofSeconds(61));
        String newEmail = newEmail();
        String token = token(superManager);
        String vetToken = token(vet);
        ok(start(token, vet, newEmail));
        String code = code(newEmail);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLocked(vet, List.of(
                () -> confirm(token, vet, code),
                () -> forgot(oldEmail),
                () -> reset(oldEmail, resetCode, "Petcare456"),
                () -> patch("/api/me/staff-profile", vetToken, Map.of("phone", "0911111111"))));

        ok(results.get(0));
        assertThat(results.get(1).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxTo(oldEmail, "OTP_PASSWORD_RESET")).as("chỉ mã gửi trước khi đổi").isEqualTo(1);
        assertRule(results.get(2), "BR-TK-05");
        assertThat(results.get(3).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForMap("SELECT email, phone FROM accounts WHERE id = ?", vet))
                .containsEntry("email", newEmail).containsEntry("phone", "0911111111");
        assertThat(login(newEmail, PASSWORD).getStatusCode()).as("mật khẩu không bị đặt lại").isEqualTo(HttpStatus.OK);
    }

    @Test
    void reactivationQueuedBehindConfirmMailsTheNewEmail() throws Exception {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", vet);
        String oldEmail = email(vet);
        String newEmail = newEmail();
        String token = token(superManager);
        ok(start(token, vet, newEmail));
        String code = code(newEmail);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLocked(vet, List.of(
                () -> confirm(token, vet, code),
                () -> post("/api/staff/" + vet + "/reactivate", token, Map.of())));

        ok(results.get(0));
        ok(results.get(1));
        assertThat(outboxTo(newEmail, "STAFF_TEMP_PASSWORD")).isEqualTo(1);
        assertThat(outboxTo(oldEmail, "STAFF_TEMP_PASSWORD")).isZero();
    }

    // ================================================================ helpers — dữ liệu

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "StaffEmailChangeIT " + UUID.randomUUID());
    }

    private Long branchOf(String label) {
        return switch (label) {
            case "A" -> branchA;
            case "B" -> branchB;
            default -> null;
        };
    }

    private long staff(String role, Long branchId) {
        return staffWithHash(role, branchId, "hash");
    }

    private long staffWithPassword(String role, Long branchId) {
        return staffWithHash(role, branchId, passwordEncoder.encode(PASSWORD));
    }

    private long staffWithHash(String role, Long branchId, String hash) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), hash, role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag + " " + id, branchId);
        return id;
    }

    private static String newEmail() {
        return "email-change-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "StaffEmailChangeIT")).accessToken();
    }

    /** Mã trong email OTP mới nhất tới {@code email} (payload outbox trước khi ST20 gửi — job tắt ở profile test). */
    private String code(String email) {
        return latestPayload(email, "OTP_EMAIL_CHANGE");
    }

    private String latestPayload(String email, String template) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = ? ORDER BY id DESC LIMIT 1""", String.class, email,
                template);
    }

    private List<Map<String, Object>> otps(long accountId) {
        return jdbc.queryForList("""
                SELECT target_email, customer_id, failed_attempts, consumed_at, invalidated_at FROM otp_tokens
                WHERE account_id = ? AND purpose = 'CHANGE_EMAIL' ORDER BY id""", accountId);
    }

    private long outboxTo(String email) {
        return outboxTo(email, "OTP_EMAIL_CHANGE");
    }

    private long outboxTo(String email, String template) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE recipient_email = ? AND template_code = ?""",
                Long.class, email, template);
    }

    /** BR-QT-14: khóa payload ⊆ {@code allowed_vars} và ⊇ {@code required_vars} của chính dòng mẫu. */
    private void assertPayloadMatchesTemplate(String template, String recipient) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM jsonb_object_keys(o.payload) k
                         WHERE NOT jsonb_exists(t.allowed_vars, k)) AS not_allowed,
                       (SELECT count(*) FROM jsonb_array_elements_text(t.required_vars) r
                         WHERE NOT jsonb_exists(o.payload, r)) AS missing
                FROM notification_outbox o JOIN notification_templates t ON t.code = o.template_code
                WHERE o.recipient_email = ? AND o.template_code = ? ORDER BY o.id DESC LIMIT 1""", recipient,
                template);
        assertThat(row).as("%s", row).containsEntry("not_allowed", 0L).containsEntry("missing", 0L);
    }

    private List<Map<String, Object>> audits(long actorId, String action) {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, actor_email, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE actor_account_id = ? AND action = ? ORDER BY id""", actorId, action);
    }

    private long auditsOn(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?",
                Long.class, accountId);
    }

    @SuppressWarnings("unchecked")
    private List<Long> searchIds(String query) {
        Map<String, Object> page = map(ok(http.exchange("/api/staff?q=" + query, HttpMethod.GET,
                new HttpEntity<>(bearer(token(superManager))), MAP)));
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(row -> ((Number) row.get("accountId")).longValue()).toList();
    }

    private void assertNotLocked(long accountId) {
        tx.executeWithoutResult(status -> jdbc.queryForObject(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, accountId));
    }

    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_email_change_fail_" + suffix;
        String trigger = "trg_it_email_change_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, operation, table, function));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    /** Audit 403 của {@code actorEmail} chỉ được ghi khi không còn phiên nào "idle in transaction" (khuôn StaffAdminIT). */
    private void withNoOpenTransactionDuringAudit(String actorEmail, Runnable body) {
        withTrigger("audit_logs", "INSERT", """
                NEW.actor_email = '%s' AND EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND state LIKE 'idle in transaction%%')""".formatted(actorEmail), body);
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ================================================================ helpers — đồng thời

    /** Hai luồng cùng đi qua {@code existsByEmail(email)} rồi mới đi tiếp (khuôn {@code StaffAdminIT}). */
    private void barrierOnExistsByEmail(String email) {
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        Answer<?> real = mockingDetails(accountRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object exists = real.answer(invocation);
            if (email.equals(invocation.getArgument(0))) {
                bothChecked.await(30, TimeUnit.SECONDS);
            }
            return exists;
        }).when(accountRepository).existsByEmail(anyString());
    }

    @SafeVarargs
    private List<ResponseEntity<Map<String, Object>>> parallel(
            Callable<ResponseEntity<Map<String, Object>>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> call : calls) {
                futures.add(pool.submit(call));
            }
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Một transaction giữ khóa {@code accounts} của nhân viên; các request được bắn lần lượt, mỗi request phải đứng chờ
     * khóa trước khi bắn request kế tiếp, nên thứ tự nhận khóa sau khi thả = thứ tự trong danh sách (khuôn
     * {@code StaffDisableReactivateIT.whileLocked}).
     */
    private List<ResponseEntity<Map<String, Object>>> whileAccountLocked(long accountId,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(calls.size());
            }
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                assertThat(call.isDone()).as("request phải chờ khóa").isFalse();
            }
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_ANY_LOCK, Long.class) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("cần " + expected + " request chờ khóa; pg_stat_activity = "
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

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> start(String token, long id, String newEmail) {
        return post("/api/staff/" + id + "/email-change", token, Map.of("newEmail", newEmail));
    }

    private ResponseEntity<Map<String, Object>> confirm(String token, long id, String code) {
        return post("/api/staff/" + id + "/email-change/confirm", token, Map.of("code", code));
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/api/auth/login", null, Map.of("email", email, "password", password));
    }

    private ResponseEntity<Map<String, Object>> forgot(String email) {
        return post("/api/auth/password/forgot", null, Map.of("email", email));
    }

    private ResponseEntity<Map<String, Object>> reset(String email, String code, String newPassword) {
        return post("/api/auth/password/reset", null, Map.of("email", email, "code", code,
                "newPassword", newPassword));
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> patch(String path, String token, Object body) {
        return http.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String path, String token, String body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static Object ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp",
                        "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        assertThat((String) response.getBody().get("message")).isNotBlank();
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
    }
}
