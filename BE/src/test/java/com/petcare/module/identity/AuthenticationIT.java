package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.EntityManager;

/**
 * Xác thực JWT + phiên DB, RBAC, phạm vi chi nhánh, BR-TK-17, BR-TN-06 trên Postgres 17 thật qua HTTP
 * (docs/adr/0003). Dữ liệu dựng bằng SQL; phiên mở qua {@link SessionService} như task đăng nhập sẽ làm.
 * Thời gian điều khiển bằng một {@link Clock} thay được, để kiểm tra hết hạn phiên và nhịp ghi {@code last_seen_at}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AuthenticationIT.TestBeans.class, AuthenticationIT.ProbeController.class})
class AuthenticationIT {

    private static final Instant T0 = Instant.parse("2026-10-06T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Clock dịch được; {@code @Primary} nên mọi bean inject {@code Clock} dùng nó thay cho TimeConfig. */
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

    /** Endpoint thăm dò, chỉ có trong test. */
    @RestController
    static class ProbeController {
        private final BranchScope scope;
        private final AuditRecorder recorder;
        private final TransactionTemplate tx;

        ProbeController(BranchScope scope, AuditRecorder recorder, PlatformTransactionManager transactionManager) {
            this.scope = scope;
            this.recorder = recorder;
            this.tx = new TransactionTemplate(transactionManager);
        }

        @GetMapping("/api/test/whoami")
        Map<String, Object> whoami() {
            SecurityPrincipal principal = scope.current();
            Map<String, Object> body = new HashMap<>();
            body.put("accountId", principal.accountId());
            body.put("email", principal.email());
            body.put("sessionId", principal.sessionId());
            body.put("role", principal.role());
            body.put("branchId", principal.branchId());
            return body;
        }

        @GetMapping("/api/test/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok";
        }

        @GetMapping("/api/test/branch")
        Long branch(@RequestParam(required = false) Long branchId) {
            return scope.resolve(branchId);
        }

        @PostMapping("/api/test/audit")
        String audit(@RequestParam String marker) {
            tx.executeWithoutResult(status -> recorder.record(AuditEntry.of("IT_PROBE").reason(marker)));
            return "ok";
        }

        @GetMapping("/api/public/test/ping")
        String ping() {
            return "pong";
        }

        /** Path public: có principal thì trả accountId, không có thì "anonymous" (docs/adr/0005). */
        @GetMapping("/api/public/test/whoami")
        String publicWhoami() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null && authentication.getPrincipal() instanceof SecurityPrincipal principal
                    ? String.valueOf(principal.accountId())
                    : "anonymous";
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private EntityManager entityManager;

    private TransactionTemplate tx;
    private long branchA;
    private long branchB;
    private long vet;
    private long admin;
    private long customer;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        branchA = branch();
        branchB = branch();
        vet = staff("VET", branchA);
        admin = staff("ADMIN", null);
        customer = customerAccount();
    }

    // ---------------------------------------------------------------- phiên hợp lệ / không hợp lệ

    @Test
    void validSessionReturnsPrincipalLoadedFromDatabase() {
        OpenedSession session = open(vet);

        ResponseEntity<Map<String, Object>> response = get("/api/test/whoami", session.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("accountId", (int) vet).containsEntry("role", "VET")
                .containsEntry("branchId", (int) branchA).containsEntry("sessionId", session.sessionId().intValue());
    }

    @Test
    void sessionRowStoresOnlyHashOfJtiAndSnapshotOfTtl() {
        OpenedSession session = open(vet);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT token_hash, expires_at, revoked_at FROM sessions WHERE id = ?", session.sessionId());
        assertThat((String) row.get("token_hash")).matches("[0-9a-f]{64}");
        assertThat(session.accessToken()).doesNotContain((String) row.get("token_hash"));
        // session.ttl_hours mặc định 12 (V2)
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant()).isEqualTo(T0.plus(Duration.ofHours(12)));
        assertThat(row.get("revoked_at")).isNull();
    }

    @Test
    void requestWithoutTokenGets401Envelope() {
        ResponseEntity<Map<String, Object>> response = get("/api/test/whoami", null);

        assertError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void garbageTokenGets401() {
        assertError(get("/api/test/whoami", "garbage"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void revokedSessionRejectedOnNextRequest() {
        OpenedSession session = open(vet);
        assertThat(get("/api/test/whoami", session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        tx.executeWithoutResult(status -> sessions.revoke(session.sessionId()));

        assertError(get("/api/test/whoami", session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void revokeOthersKeepsCurrentSession() {
        OpenedSession current = open(vet);
        OpenedSession other = open(vet);

        tx.executeWithoutResult(status -> sessions.revokeOthers(vet, current.sessionId()));

        assertThat(get("/api/test/whoami", current.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(get("/api/test/whoami", other.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void revokeAllEndsEverySessionOfAccountOnly() {
        OpenedSession first = open(vet);
        OpenedSession second = open(vet);
        OpenedSession adminSession = open(admin);

        tx.executeWithoutResult(status -> sessions.revokeAll(vet));

        assertError(get("/api/test/whoami", first.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(get("/api/test/whoami", second.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(get("/api/test/whoami", adminSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Revoke chạy {@code MANDATORY} trong transaction của use case (khóa, đổi mật khẩu…): không được detach entity mà
     * use case đã nạp, nếu không thay đổi sau lời gọi revoke sẽ mất âm thầm. Kết quả revoke vẫn thấy ngay trong cùng
     * transaction qua {@code findAuthView} (đọc DB, không qua entity).
     */
    @Test
    void revokeKeepsEntitiesOfCallingTransactionManaged() {
        OpenedSession current = open(vet);
        OpenedSession other = open(vet);

        tx.executeWithoutResult(status -> {
            Account account = accounts.findById(vet).orElseThrow();

            sessions.revokeOthers(vet, current.sessionId());
            assertThat(entityManager.contains(account)).isTrue();
            sessions.revoke(current.sessionId());
            assertThat(entityManager.contains(account)).isTrue();
            sessions.revokeAll(vet);
            assertThat(entityManager.contains(account)).isTrue();

            assertThat(sessionRepository.findAuthView(other.sessionId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(sessionRepository.findAuthView(current.sessionId()).orElseThrow().revokedAt()).isNotNull();
        });
    }

    @Test
    void expiredSessionRejected() {
        OpenedSession session = open(vet);

        clock.set(T0.plus(Duration.ofHours(12)).minusSeconds(1));
        assertThat(get("/api/test/whoami", session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.set(T0.plus(Duration.ofHours(12)));
        assertError(get("/api/test/whoami", session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void lockedAccountRejectedImmediatelyEvenBeforeSessionsAreRevoked() {
        OpenedSession session = open(vet);

        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", vet);

        assertError(get("/api/test/whoami", session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void disabledAccountRejected() {
        OpenedSession session = open(vet);

        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", vet);

        assertError(get("/api/test/whoami", session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void temporaryLoginLockKeepsExistingSession() {
        OpenedSession session = open(vet);

        // BR-TK-09: khóa tạm chỉ chặn đăng nhập
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(T0.plus(Duration.ofMinutes(15))), vet);

        assertThat(get("/api/test/whoami", session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- RBAC, phạm vi chi nhánh

    @Test
    void wrongRoleGets403AccessDenied() {
        assertError(get("/api/test/admin-only", open(vet).accessToken()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(getText("/api/test/admin-only", open(admin).accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void branchStaffResolvesOwnBranchAndOtherBranchIs403() {
        String token = open(vet).accessToken();

        assertThat(getLong("/api/test/branch", token)).isEqualTo(branchA);
        assertThat(getLong("/api/test/branch?branchId=" + branchA, token)).isEqualTo(branchA);
        assertError(get("/api/test/branch?branchId=" + branchB, token), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void adminResolvesAnyBranchOrWholeChain() {
        String token = open(admin).accessToken();

        assertThat(getLong("/api/test/branch?branchId=" + branchB, token)).isEqualTo(branchB);
        assertThat(get("/api/test/branch", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** docs/adr/0006: khách không có phạm vi chi nhánh; service quên nhánh chủ sở hữu → 403, không ra toàn chuỗi. */
    @Test
    void customerCallingBranchQueryGets403() {
        String token = open(customer).accessToken();

        assertError(get("/api/test/branch", token), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(get("/api/test/branch?branchId=" + branchA, token), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void roleChangeTakesEffectOnNextRequestWithoutNewLogin() {
        String token = open(vet).accessToken();

        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", vet);

        assertThat(get("/api/test/whoami", token).getBody()).containsEntry("role", "RECEPTIONIST");
    }

    @Test
    void branchTransferTakesEffectOnNextRequestWithoutNewLogin() {
        String token = open(vet).accessToken();

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, vet);

        assertThat(getLong("/api/test/branch", token)).isEqualTo(branchB);
        assertError(get("/api/test/branch?branchId=" + branchA, token), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    // ---------------------------------------------------------------- BR-TK-17

    @Test
    void mustChangePasswordBlocksApiButNotPublicPages() {
        String token = open(vet).accessToken();
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);

        ResponseEntity<Map<String, Object>> blocked = get("/api/test/whoami", token);
        assertError(blocked, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) blocked.getBody().get("message")).endsWith("(BR-TK-17)");

        assertThat(getText("/api/public/test/ping", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * docs/adr/0005: FE gắn token vào mọi request; nhân viên còn phải đổi mật khẩu vẫn tới được path public (đăng nhập,
     * quên mật khẩu…) dưới dạng ẩn danh: token không được đọc, phiên không được tra, {@code last_seen_at} không đổi.
     */
    @Test
    void mustChangePasswordStillReachesPublicPathAsAnonymous() {
        String token = open(vet).accessToken();
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);

        ResponseEntity<String> response = getText("/api/public/test/whoami", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("anonymous");
        assertThat(lastSeen(vet)).isNull();
    }

    // ---------------------------------------------------------------- BR-TN-06

    @Test
    void staffLastSeenUpdatedAtMostOncePerMinute() {
        String token = open(vet).accessToken();

        get("/api/test/whoami", token);
        assertThat(lastSeen(vet)).isEqualTo(T0);

        clock.advance(Duration.ofSeconds(30));
        get("/api/test/whoami", token);
        assertThat(lastSeen(vet)).isEqualTo(T0);

        clock.advance(Duration.ofSeconds(31));
        get("/api/test/whoami", token);
        assertThat(lastSeen(vet)).isEqualTo(T0.plusSeconds(61));
    }

    @Test
    void customerLastSeenNotRecorded() {
        get("/api/test/whoami", open(customer).accessToken());

        assertThat(lastSeen(customer)).isNull();
    }

    // ---------------------------------------------------------------- audit, public paths

    @Test
    void auditActorComesFromAuthenticatedPrincipal() {
        String marker = UUID.randomUUID().toString();

        ResponseEntity<String> response = http.exchange("/api/test/audit?marker=" + marker, HttpMethod.POST,
                new HttpEntity<>(bearer(open(vet).accessToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_account_id, actor_email FROM audit_logs WHERE reason = ?", marker);
        assertThat(row).containsEntry("actor_account_id", vet).containsEntry("actor_email", email(vet));
    }

    @Test
    void publicPathOpenWithoutTokenAndWithGarbageToken() {
        assertThat(getText("/api/public/test/ping", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getText("/api/public/test/ping", "garbage").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutIsNotPublic() {
        ResponseEntity<Map<String, Object>> response = http.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), new ParameterizedTypeReference<>() {
                });

        assertError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- helpers

    private OpenedSession open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AuthenticationIT"));
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<String> getText(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
    }

    private Long getLong(String path, String token) {
        ResponseEntity<Long> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)),
                Long.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsKeys("message", "timestamp", "traceId");
    }

    private Instant lastSeen(long accountId) {
        java.sql.Timestamp value = jdbc.queryForObject("SELECT last_seen_at FROM accounts WHERE id = ?",
                java.sql.Timestamp.class, accountId);
        return value == null ? null : value.toInstant();
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "AuthIT chi nhánh " + UUID.randomUUID());
    }

    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "auth-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test",
                role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return id;
    }

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "auth-it-c-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test");
    }
}
