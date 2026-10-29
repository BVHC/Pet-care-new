package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.job.PendingAccountCleanupJob;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * T40.3 — đủ 8 chuyển của bảng "1. Tài khoản" (docs/03-state-machines.md) qua endpoint / job thật, **không stand-in
 * nào**: {@code CustomerApi}, {@code VisitQueryApi}, {@code CashierShiftQueryApi} và các query API của khóa tài khoản
 * đều là bean thật (các IT cũ của từng luồng còn mock chúng, docs/testcases/T40-be.md §2.1). Trạng thái nguồn luôn đi
 * bằng API; mỗi chuyển kiểm {@code status} / {@code is_locked} trong DB và hệ quả chính của cột "Hệ quả". Bốn cặp sai
 * trả 409 {@code INVALID_STATE_TRANSITION} và không ghi gì ({@code xmin} của dòng {@code accounts}, phiên, audit,
 * outbox).
 * <p>
 * Era năm 1998 (nhỏ hơn era 2000 của {@code PendingAccountCleanupIT}): ST02 chạy ở 1998 chỉ xóa tài khoản
 * {@code PENDING} của lớp này. {@code audit_logs} không dọn được (trigger), test lọc theo {@code entity_id}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AccountFsmIT.TestBeans.class})
class AccountFsmIT {

    private static final Instant T0 = Instant.parse("1998-03-02T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

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
    private MutableClock clock;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PendingAccountCleanupJob pendingCleanup;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String tag;
    private long branch;
    private long admin;
    private long superManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        tag = "f" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                DELETE FROM otp_tokens WHERE account_id IN
                    (SELECT id FROM accounts WHERE pending_expires_at < '1999-01-01')""");
        jdbc.update("""
                DELETE FROM customers WHERE account_id IN
                    (SELECT id FROM accounts WHERE pending_expires_at < '1999-01-01')""");
        jdbc.update("DELETE FROM accounts WHERE pending_expires_at < '1999-01-01'");
        branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "AccountFsmIT " + tag);
        admin = seededStaff("ADMIN");
        superManager = seededStaff("SUPER_MANAGER");
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — đăng ký: {@code PENDING}, hồ sơ khách online gắn tài khoản, OTP vào outbox. */
    @Test
    void registerCreatesPendingAccountWithOnlineProfile() {
        String email = register();
        long id = accountId(email);

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("""
                SELECT created_channel, link_decision_pending FROM customers WHERE account_id = ?""", id))
                .containsEntry("created_channel", "ONLINE").containsEntry("link_decision_pending", false);
        assertThat(otpCode(email)).hasSize(6);
    }

    /** #2 — xác thực OTP: {@code ACTIVE}, mã đã dùng. */
    @Test
    void verifyingTheOtpActivatesTheAccount() {
        String email = register();
        long id = accountId(email);

        ok(post("/api/auth/register/verify", null, Map.of("email", email, "code", otpCode(email))));

        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM otp_tokens WHERE account_id = ?", Timestamp.class,
                id)).isNotNull();
    }

    /** #3 — ST02 xóa tài khoản {@code PENDING} quá hạn cùng hồ sơ online và OTP; tài khoản đã xác thực giữ nguyên. */
    @Test
    void st02DeletesAnExpiredPendingAccountWithItsProfile() {
        String expired = register();
        long expiredId = accountId(expired);
        String verified = register();
        long verifiedId = accountId(verified);
        ok(post("/api/auth/register/verify", null, Map.of("email", verified, "code", otpCode(verified))));
        clock.set(jdbc.queryForObject("SELECT pending_expires_at FROM accounts WHERE id = ?", Timestamp.class,
                expiredId).toInstant());

        pendingCleanup.run();

        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", expiredId)).isZero();
        assertThat(count("SELECT count(*) FROM customers WHERE account_id = ?", expiredId)).isZero();
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", expiredId)).isZero();
        assertThat(status(verifiedId)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM customers WHERE account_id = ?", verifiedId)).isOne();
    }

    /** #4 → #5 → #6 — tạo nhân viên, vô hiệu hóa (hủy mọi phiên), kích hoạt lại (bắt đổi mật khẩu). */
    @Test
    void staffIsCreatedDisabledAndReactivated() {
        long vet = createVet();
        assertThat(account(vet)).containsEntry("status", "ACTIVE").containsEntry("is_locked", false)
                .containsEntry("must_change_password", true);
        openSession(vet);

        ok(post("/api/staff/" + vet + "/disable", token(superManager), null));
        assertThat(account(vet)).containsEntry("status", "DISABLED").containsEntry("is_locked", false);
        assertThat(activeSessions(vet)).isZero();

        ok(post("/api/staff/" + vet + "/reactivate", token(superManager), reactivateBody()));
        assertThat(account(vet)).containsEntry("status", "ACTIVE").containsEntry("is_locked", false)
                .containsEntry("must_change_password", true);
    }

    /** #7, #8 — khóa / mở khóa chỉ đổi cờ; {@code status} giữ nguyên, kể cả {@code DISABLED} (BR-QT-12). */
    @Test
    void lockAndUnlockOnlyFlipTheFlag() {
        long active = createVet();
        openSession(active);

        ok(post("/api/accounts/" + active + "/lock", token(admin), reason("Khóa " + tag)));
        assertThat(account(active)).containsEntry("status", "ACTIVE").containsEntry("is_locked", true);
        assertThat(activeSessions(active)).isZero();
        ok(post("/api/accounts/" + active + "/unlock", token(admin), reason("Mở " + tag)));
        assertThat(account(active)).containsEntry("status", "ACTIVE").containsEntry("is_locked", false);

        long disabled = createVet();
        ok(post("/api/staff/" + disabled + "/disable", token(superManager), null));
        ok(post("/api/accounts/" + disabled + "/lock", token(admin), reason("Khóa " + tag)));
        assertThat(account(disabled)).containsEntry("status", "DISABLED").containsEntry("is_locked", true);
        ok(post("/api/accounts/" + disabled + "/unlock", token(admin), reason("Mở " + tag)));
        assertThat(account(disabled)).containsEntry("status", "DISABLED").containsEntry("is_locked", false);
    }

    // ================================================================ cặp sai → 409, không ghi gì

    @Test
    void disablingADisabledAccountIs409AndWritesNothing() {
        long vet = createVet();
        ok(post("/api/staff/" + vet + "/disable", token(superManager), null));
        Snapshot before = snapshot(vet, "STAFF_DISABLED");

        assertConflict(post("/api/staff/" + vet + "/disable", token(superManager), null));

        assertThat(snapshot(vet, "STAFF_DISABLED")).isEqualTo(before);
        assertThat(status(vet)).isEqualTo("DISABLED");
    }

    @Test
    void reactivatingAnActiveAccountIs409AndWritesNothing() {
        long vet = createVet();
        openSession(vet);
        Snapshot before = snapshot(vet, "STAFF_REACTIVATED");

        assertConflict(post("/api/staff/" + vet + "/reactivate", token(superManager), reactivateBody()));

        assertThat(snapshot(vet, "STAFF_REACTIVATED")).isEqualTo(before);
        assertThat(status(vet)).isEqualTo("ACTIVE");
    }

    @Test
    void lockingALockedAccountIs409AndWritesNothing() {
        long vet = createVet();
        ok(post("/api/accounts/" + vet + "/lock", token(admin), reason("Lần đầu " + tag)));
        Snapshot before = snapshot(vet, "ACCOUNT_LOCKED");

        assertConflict(post("/api/accounts/" + vet + "/lock", token(admin), reason("Lần hai " + tag)));

        assertThat(snapshot(vet, "ACCOUNT_LOCKED")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT locked_reason FROM accounts WHERE id = ?", String.class, vet))
                .isEqualTo("Lần đầu " + tag);
    }

    @Test
    void unlockingAnUnlockedAccountIs409AndWritesNothing() {
        long vet = createVet();
        openSession(vet);
        Snapshot before = snapshot(vet, "ACCOUNT_UNLOCKED");

        assertConflict(post("/api/accounts/" + vet + "/unlock", token(admin), reason("Thử " + tag)));

        assertThat(snapshot(vet, "ACCOUNT_UNLOCKED")).isEqualTo(before);
        assertThat(account(vet)).containsEntry("is_locked", false);
    }

    // ================================================================ helpers — dữ liệu

    /** Dấu "không ghi gì": phiên bản dòng {@code accounts}, phiên chưa hủy, audit của action, outbox về tài khoản. */
    private record Snapshot(String xmin, long activeSessions, long audits, long outbox) {
    }

    private Snapshot snapshot(long accountId, String action) {
        String email = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
        return new Snapshot(
                jdbc.queryForObject("SELECT xmin::text FROM accounts WHERE id = ?", String.class, accountId),
                activeSessions(accountId),
                count("""
                        SELECT count(*) FROM audit_logs
                        WHERE entity_type = 'accounts' AND entity_id = ? AND action = ?""", accountId, action),
                jdbc.queryForObject("""
                        SELECT count(*) FROM notification_outbox
                        WHERE recipient_email = ? OR payload->>'email_tai_khoan' = ?""", Long.class, email, email));
    }

    private long seededStaff(String role) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, NULL)", id,
                role + " " + tag);
        return id;
    }

    /** #4 qua {@code POST /api/staff} (SUPER_MANAGER), VET của chi nhánh của test. */
    private long createVet() {
        Map<String, Object> body = new HashMap<>();
        body.put("email", newEmail());
        body.put("phone", "0901234567");
        body.put("fullName", "Bác sĩ " + tag);
        body.put("role", "VET");
        body.put("branchId", branch);
        ResponseEntity<Map<String, Object>> created = post("/api/staff", token(superManager), body);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return ((Number) map(created.getBody().get("data")).get("accountId")).longValue();
    }

    /** #1 qua {@code POST /api/auth/register}, không SĐT nên cờ chờ liên kết không bật (BR-TK-19). */
    private String register() {
        String email = newEmail();
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "abc12345");
        body.put("fullName", "Khách " + tag);
        body.put("isAdult", true);
        body.put("termsAccepted", true);
        ResponseEntity<Map<String, Object>> response = post("/api/auth/register", null, body);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    /** Mã gốc nằm trong payload outbox (ST20 tắt ở profile test). */
    private String otpCode(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox WHERE recipient_email = ?
                ORDER BY id DESC LIMIT 1""", String.class, email);
    }

    private String newEmail() {
        return "fsm-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", Long.class, email);
    }

    private String status(long accountId) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, accountId);
    }

    private Map<String, Object> account(long accountId) {
        return jdbc.queryForMap("SELECT status, is_locked, must_change_password FROM accounts WHERE id = ?",
                accountId);
    }

    private long activeSessions(long accountId) {
        return count("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL", accountId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void openSession(long accountId) {
        token(accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AccountFsmIT")).accessToken();
    }

    private static Map<String, Object> reason(String reason) {
        return Map.of("reason", reason);
    }

    /** {@code branchId} null: giữ chi nhánh cũ. */
    private static Map<String, Object> reactivateBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", null);
        return body;
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private static void ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private static void assertConflict(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("errorCode", "INVALID_STATE_TRANSITION").containsEntry("statusCode", 409);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
