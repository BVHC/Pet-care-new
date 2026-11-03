package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.service.SessionCleanupService;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.platform.security.BranchScope;
import com.petcare.support.MutableClock;

/**
 * Access token 10 phút + refresh token xoay vòng (docs/adr/0091, trả D024) trên Postgres 17 thật qua HTTP. Tài khoản
 * chèn bằng SQL, phiên mở qua {@link SessionService} (khuôn {@code LogoutIT}). Era đồng hồ 2038 (không job nào quét);
 * riêng test dọn phiên dùng năm 1984 và cutoff 1985 để chỉ chạm dòng của chính nó. Profile test nâng
 * {@code session.access_ttl_minutes} lên 720 (db/test-migration); lớp này đặt 10 rồi trả lại.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RefreshTokenIT.TestBeans.class, RefreshTokenIT.ProbeController.class})
class RefreshTokenIT {

    private static final Instant T0 = Instant.parse("2038-03-01T02:00:00Z");
    private static final String TEST_BASELINE_TTL = "720";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String ANY_LOCK_WAITING = """
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

    /** Endpoint cần token, chỉ có trong test: một request bất kỳ của người đang đăng nhập (kể cả poll tự động). */
    @RestController
    static class ProbeController {
        private final BranchScope scope;

        ProbeController(BranchScope scope) {
            this.scope = scope;
        }

        @GetMapping("/api/test/refresh-probe")
        Map<String, Object> probe() {
            return Map.of("accountId", scope.current().accountId());
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private SessionCleanupService cleanup;

    @Autowired
    private SystemConfigService configs;

    @Autowired
    private StaffDirectoryApi directory;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long branchId;
    private final List<Long> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        setAccessTtl("10");
        branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "RefreshTokenIT chi nhánh " + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        for (long id : accounts) {
            jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM sessions WHERE account_id = ?)",
                    id);
            jdbc.update("DELETE FROM sessions WHERE account_id = ?", id);
        }
        setAccessTtl(TEST_BASELINE_TTL);
    }

    // ---------------------------------------------------------------- đăng nhập cấp cặp token

    @Test
    void openingASessionIssuesAShortAccessTokenAndTheFirstRefreshToken() {
        long vet = staff("VET");

        OpenedSession opened = open(vet);

        assertThat(opened.accessExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(opened.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(12)));
        assertThat(sessionTime(opened.sessionId(), "access_expires_at")).isEqualTo(opened.accessExpiresAt());
        Map<String, Object> token = jdbc.queryForMap(
                "SELECT token_hash, used_at, expires_at FROM refresh_tokens WHERE session_id = ?", opened.sessionId());
        assertThat(token.get("token_hash")).isEqualTo(sha256(opened.refreshToken()));
        assertThat(token.get("used_at")).isNull();
        assertThat(((Timestamp) token.get("expires_at")).toInstant()).as("hạn refresh token = hạn phiên")
                .isEqualTo(opened.expiresAt());
        assertThat(opened.refreshToken()).doesNotContain(sha256(opened.refreshToken()));
        assertThat(probe(opened.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- refresh thành công

    @Test
    void refreshReturnsANewPairKillsTheOldAccessTokenAndRotatesTheRefreshToken() {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        clock.advance(Duration.ofMinutes(3));

        ResponseEntity<Map<String, Object>> response = refresh(opened.refreshToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Làm mới phiên thành công").containsEntry("code", 200);
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("accessToken", "accessTokenExpiresAt", "refreshToken", "expiresAt");
        assertThat(Instant.parse((String) data.get("accessTokenExpiresAt")))
                .isEqualTo(T0.plus(Duration.ofMinutes(13)));
        assertThat(Instant.parse((String) data.get("expiresAt"))).as("hạn tuyệt đối của phiên không đổi")
                .isEqualTo(opened.expiresAt());
        String newRefresh = (String) data.get("refreshToken");
        String newAccess = (String) data.get("accessToken");
        assertThat(newRefresh).isNotEqualTo(opened.refreshToken());

        assertThat(probe(newAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(probe(opened.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT token_hash <> ? FROM sessions WHERE id = ?", Boolean.class,
                sha256(opened.accessToken()), opened.sessionId())).isTrue();
        assertThat(sessionTime(opened.sessionId(), "access_expires_at")).isEqualTo(T0.plus(Duration.ofMinutes(13)));
        assertThat(sessionTime(opened.sessionId(), "expires_at")).isEqualTo(opened.expiresAt());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT token_hash, used_at, expires_at FROM refresh_tokens WHERE session_id = ? ORDER BY id",
                opened.sessionId());
        assertThat(rows).hasSize(2);
        assertThat(((Timestamp) rows.get(0).get("used_at")).toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
        assertThat(rows.get(1).get("used_at")).isNull();
        assertThat(rows.get(1).get("token_hash")).isEqualTo(sha256(newRefresh));
        assertThat(((Timestamp) rows.get(1).get("expires_at")).toInstant()).isEqualTo(opened.expiresAt());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class, vet))
                .as("refresh thành công không audit").isZero();
    }

    /** Bearer gửi kèm ở path public bị bỏ qua (ADR-0005): token hết hạn hay rác không cản refresh. */
    @Test
    void refreshIgnoresAnyBearerTokenSentAlong() {
        OpenedSession opened = open(staff("VET"));
        clock.advance(Duration.ofMinutes(11));
        assertError(probe(opened.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        ResponseEntity<Map<String, Object>> response = http.exchange("/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", opened.refreshToken()), bearer("garbage")), MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- online theo access token (D024)

    /**
     * D024: poll tự động không làm ai online. Request hợp lệ không đổi hạn access token; hết hạn thì nhân viên offline
     * dù tab còn mở (poll nhận 401); chỉ refresh (thao tác của người) mới đưa nhân viên online lại.
     */
    @Test
    void pollingNeverExtendsOnlineButRefreshDoes() {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        assertThat(online(vet)).isTrue();

        for (int minute = 1; minute < 10; minute++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(probe(opened.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(sessionTime(opened.sessionId(), "access_expires_at")).as("poll không đổi hạn")
                .isEqualTo(opened.accessExpiresAt());
        assertThat(online(vet)).as("còn 1 phút").isTrue();

        clock.advance(Duration.ofMinutes(1));   // đúng mốc hạn access token là đã hết hạn
        assertThat(online(vet)).isFalse();
        for (int i = 0; i < 3; i++) {
            assertError(probe(opened.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
            clock.advance(Duration.ofMinutes(5));
        }
        assertThat(online(vet)).as("poll bị 401 không giữ online").isFalse();

        ResponseEntity<Map<String, Object>> refreshed = refresh(opened.refreshToken());
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(online(vet)).isTrue();
        assertThat(probe((String) data(refreshed).get("accessToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bothSessionsKeepTheStaffOnlineUntilTheLastAccessTokenExpires() {
        long vet = staff("VET");
        open(vet);
        clock.advance(Duration.ofMinutes(5));
        OpenedSession desk = open(vet);
        clock.advance(Duration.ofMinutes(6));

        assertThat(online(vet)).as("phiên mới còn 9 phút").isTrue();

        tx.executeWithoutResult(status -> sessions.revoke(desk.sessionId()));
        assertThat(online(vet)).as("phiên còn lại đã hết hạn access token").isFalse();
    }

    // ---------------------------------------------------------------- từ chối: 401 chung, không ghi gì

    @Test
    void unknownRefreshTokenIs401() {
        OpenedSession opened = open(staff("VET"));
        String before = state(opened);

        assertError(refresh("khong-phai-refresh-token"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state(opened)).isEqualTo(before);
    }

    @Test
    void refreshAfterLogoutIs401() {
        OpenedSession opened = open(staff("VET"));
        assertThat(http.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(bearer(opened.accessToken())),
                MAP).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String before = state(opened);

        assertError(refresh(opened.refreshToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state(opened)).isEqualTo(before);
        assertThat(online(accountOf(opened))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"is_locked = true", "status = 'DISABLED'"})
    void lockedOrDisabledAccountCannotRefresh(String change) {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        jdbc.update("UPDATE accounts SET " + change + " WHERE id = ?", vet);
        String before = state(opened);

        assertError(refresh(opened.refreshToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state(opened)).isEqualTo(before);
    }

    @Test
    void refreshTokenDiesWithTheSession() {
        OpenedSession opened = open(staff("VET"));
        clock.advance(Duration.ofHours(12));
        String before = state(opened);

        assertError(refresh(opened.refreshToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state(opened)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "'   '"})
    void blankRefreshTokenIs400(String value) {
        assertError(refresh(value.replace("'", "")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void tooLongRefreshTokenIs400() {
        assertError(refresh("x".repeat(129)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------- hạn phiên là trần tuyệt đối

    @Test
    void accessTokenNeverOutlivesTheSession() {
        OpenedSession opened = open(staff("VET"));
        clock.advance(Duration.ofHours(11).plusMinutes(55));

        ResponseEntity<Map<String, Object>> response = refresh(opened.refreshToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse((String) data(response).get("accessTokenExpiresAt")))
                .isEqualTo(opened.expiresAt());
        assertThat(sessionTime(opened.sessionId(), "access_expires_at")).isEqualTo(opened.expiresAt());
        clock.advance(Duration.ofMinutes(5));
        assertError(refresh((String) data(response).get("refreshToken")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- dùng lại token đã dùng

    @Test
    void reusingASpentRefreshTokenRevokesTheSessionAndAudits() {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        Map<String, Object> first = data(refresh(opened.refreshToken()));
        clock.advance(Duration.ofSeconds(30));

        ResponseEntity<Map<String, Object>> reuse = refresh(opened.refreshToken());

        assertError(reuse, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(sessionTime(opened.sessionId(), "revoked_at")).isEqualTo(T0.plusSeconds(30));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_account_id, entity_type, entity_id FROM audit_logs WHERE action = 'REFRESH_TOKEN_REUSED' "
                        + "AND entity_id = ?", opened.sessionId());
        assertThat(audit).containsEntry("actor_account_id", vet).containsEntry("entity_type", "sessions");
        assertError(refresh((String) first.get("refreshToken")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(probe((String) first.get("accessToken")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(online(vet)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'REFRESH_TOKEN_REUSED' "
                + "AND entity_id = ?", Long.class, opened.sessionId())).as("lần sau không audit thêm").isEqualTo(1);
    }

    /**
     * Hai refresh cùng token chạy song song: khóa dòng xếp hàng, lượt đầu xoay token, lượt sau thấy token đã dùng nên bị
     * coi là dùng lại và hủy phiên (nghiêm ngặt, docs/adr/0091: client phải gom refresh một luồng).
     */
    @Test
    void concurrentRefreshWithTheSameTokenSucceedsOnceThenRevokesTheSession() throws Exception {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpStatus> statuses = new ArrayList<>();
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return refresh(opened.refreshToken());
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                statuses.add(HttpStatus.valueOf(call.get(30, TimeUnit.SECONDS).getStatusCode().value()));
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        assertThat(sessionTime(opened.sessionId(), "revoked_at")).isEqualTo(T0);
        assertThat(online(vet)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'REFRESH_TOKEN_REUSED' "
                + "AND entity_id = ?", Long.class, opened.sessionId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- đồng thời với luồng hủy phiên (docs/adr/0021)

    /**
     * Luồng khóa {@code accounts → sessions} (khóa tài khoản, đổi mật khẩu…) đang giữ dòng phiên đã hủy: refresh chỉ
     * khóa {@code refresh_tokens → sessions}, nên chờ rồi bị từ chối, không deadlock; token chưa bị đánh dấu đã dùng.
     */
    @Test
    void refreshWaitsForAnAccountsFirstRevokeAndIsRejectedWithoutSpendingTheToken() throws Exception {
        long vet = staff("VET");
        OpenedSession opened = open(vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch refreshWaiting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> revokeAll = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, vet);
                int revoked = jdbc.update(
                        "UPDATE sessions SET revoked_at = ?, updated_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                        Timestamp.from(T0), Timestamp.from(T0), vet);
                locked.countDown();
                await(refreshWaiting);
                return revoked;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> call = pool.submit(() -> refresh(opened.refreshToken()));
            awaitLockWaiting();
            refreshWaiting.countDown();

            assertThat(revokeAll.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertError(call.get(30, TimeUnit.SECONDS), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } finally {
            refreshWaiting.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT used_at FROM refresh_tokens WHERE session_id = ?", Timestamp.class,
                opened.sessionId())).as("token chưa bị đánh dấu đã dùng").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                opened.sessionId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- job dọn phiên (docs/adr/0008)

    /** {@code refresh_tokens.session_id} là RESTRICT: dọn phiên phải xóa refresh token của lô trước. */
    @Test
    void sessionCleanupDeletesTheRefreshTokensOfExpiredSessionsFirst() {
        long vet = staff("VET");
        clock.set(Instant.parse("1984-06-01T02:00:00Z"));
        OpenedSession old = open(vet);
        Map<String, Object> rotated = data(refresh(old.refreshToken()));
        assertThat(rotated).containsKey("refreshToken");
        clock.set(T0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                old.sessionId())).isEqualTo(2);

        cleanup.deleteExpiredBatch(Instant.parse("1985-01-01T00:00:00Z"), 100);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id = ?", Long.class, old.sessionId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                old.sessionId())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private void setAccessTtl(String minutes) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'session.access_ttl_minutes'", minutes);
        configs.reload();
    }

    private OpenedSession open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "RefreshTokenIT"));
    }

    private ResponseEntity<Map<String, Object>> refresh(String refreshToken) {
        return http.exchange("/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshToken)), MAP);
    }

    private ResponseEntity<Map<String, Object>> probe(String token) {
        return http.exchange("/api/test/refresh-probe", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody().get("data");
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsKeys("message", "timestamp", "traceId");
    }

    /** BR-TN-06, docs/adr/0091: qua {@code StaffDirectoryApi} thật (đồng hồ của test). */
    private boolean online(long accountId) {
        return tx.execute(status -> directory.findStaff(accountId).orElseThrow().online());
    }

    private long accountOf(OpenedSession session) {
        return jdbc.queryForObject("SELECT account_id FROM sessions WHERE id = ?", Long.class, session.sessionId());
    }

    /** Toàn bộ trạng thái ghi của một phiên: so sánh trước / sau một lần từ chối để chứng minh không ghi gì. */
    private String state(OpenedSession session) {
        return jdbc.queryForObject("""
                SELECT concat_ws('|', s.token_hash, s.access_expires_at, s.revoked_at,
                       (SELECT string_agg(t.id || ':' || coalesce(t.used_at::text, '-'), ',' ORDER BY t.id)
                        FROM refresh_tokens t WHERE t.session_id = s.id))
                FROM sessions s WHERE s.id = ?""", String.class, session.sessionId());
    }

    private Instant sessionTime(long sessionId, String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM sessions WHERE id = ?", Timestamp.class,
                sessionId);
        return value == null ? null : value.toInstant();
    }

    private void awaitLockWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(ANY_LOCK_WAITING, Long.class) == 0) {
            assertThat(System.nanoTime()).as("refresh phải chờ khóa dòng sessions").isLessThan(deadline);
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long staff(String role) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "refresh-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test",
                role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        accounts.add(id);
        return id;
    }
}
