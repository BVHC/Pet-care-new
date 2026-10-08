package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.security.BranchScope;
import com.petcare.support.MutableClock;

/**
 * UC03 đăng xuất ({@code POST /api/auth/logout}, docs/adr/0021) trên Postgres 17 thật qua HTTP. Tài khoản chèn bằng
 * SQL, phiên mở qua {@link SessionService} (khuôn {@code AuthenticationIT}). Kiểm: hủy đúng phiên hiện tại, nhân viên
 * về offline ({@code last_seen_at = NULL}), không đổi {@code updated_at}, không audit, mọi đầu vào lỗi (401/405) không
 * đổi DB; đồng thời: request cùng token đang chạy không ghi lại online (race R3), hai lần đăng xuất song song, đăng xuất
 * chờ khóa dòng {@code accounts} (không {@code SKIP LOCKED}), không deadlock với luồng khóa {@code accounts → sessions}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LogoutIT.TestBeans.class, LogoutIT.ProbeController.class})
class LogoutIT {

    /** 09:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    /** Câu {@code AccountRepository.clearLastSeen} đang chờ khóa, nhận diện trong {@code pg_stat_activity}. */
    private static final String CLEAR_LAST_SEEN_WAITING = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE accounts SET last_seen_at = NULL%'
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Endpoint cần token, chỉ có trong test: một request "nghiệp vụ" bất kỳ của người đang đăng nhập. */
    @RestController
    static class ProbeController {
        private final BranchScope scope;

        ProbeController(BranchScope scope) {
            this.scope = scope;
        }

        @GetMapping("/api/test/logout-probe")
        Map<String, Object> probe() {
            return Map.of("accountId", scope.current().accountId());
        }
    }

    /** Spy để dừng filter của một request ngay sau bước đọc phiên (trước bước ghi {@code last_seen_at}). */
    @MockitoSpyBean
    private SessionRepository sessionRepository;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MutableClock clock;

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
                """, Long.class, "LogoutIT chi nhánh " + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void staffLogoutRevokesCurrentSessionGoesOfflineWithoutAuditOrUpdatedAt() {
        long vet = staff("VET");
        OpenedSession session = open(vet);
        assertThat(probe(session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastSeen(vet)).isEqualTo(T0);
        Instant accountUpdatedAt = accountTime(vet, "updated_at");
        clock.advance(Duration.ofMinutes(5));

        ResponseEntity<Map<String, Object>> response = logout(session.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(sessionTime(session, "updated_at")).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(lastSeen(vet)).isNull();
        assertThat(accountTime(vet, "updated_at")).isEqualTo(accountUpdatedAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class, vet))
                .isZero();
        assertError(probe(session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void secondLogoutWithSameTokenIs401AndKeepsFirstRevokedAt() {
        OpenedSession session = open(staff("RECEPTIONIST"));
        assertThat(logout(session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        clock.advance(Duration.ofMinutes(1));

        assertError(logout(session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
    }

    @Test
    void customerLogoutRevokesSessionAndLastSeenStaysNull() {
        long customer = customerAccount();
        OpenedSession session = open(customer);

        assertThat(logout(session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
        assertThat(lastSeen(customer)).isNull();
    }

    @Test
    void adminLogoutGoesOffline() {
        long admin = staffWithoutBranch("ADMIN");
        OpenedSession session = open(admin);
        probe(session.accessToken());
        assertThat(lastSeen(admin)).isEqualTo(T0);

        assertThat(logout(session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(lastSeen(admin)).isNull();
    }

    /** BR-TK-17 miễn đăng xuất (docs/adr/0005 mục 2): còn phải đổi mật khẩu vẫn đăng xuất được. */
    @Test
    void mustChangePasswordStillLogsOut() {
        long vet = staff("VET");
        OpenedSession session = open(vet);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        ResponseEntity<Map<String, Object>> blocked = probe(session.accessToken());
        assertError(blocked, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) blocked.getBody().get("message")).endsWith("(BR-TK-17)");

        assertThat(logout(session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
    }

    /** FE cũ gửi {@code {refreshToken}} (auth.api.ts); body không được đọc, kiểu nội dung nào cũng 204. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"refreshToken\":\"abc\"}| application/json",
            "{}                       | application/json",
            "khong-phai-json          | application/json",
            "khong-phai-json          | text/plain"})
    void bodyIsIgnored(String body, String contentType) {
        OpenedSession session = open(staff("CARETAKER"));
        HttpHeaders headers = bearer(session.accessToken());
        headers.setContentType(MediaType.parseMediaType(contentType));

        ResponseEntity<Map<String, Object>> response = http.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
    }

    /** R4: chỉ phiên của token đang dùng; máy khác vẫn dùng được và online lại ngay ở request kế tiếp. */
    @Test
    void otherSessionStaysValidAndComesBackOnline() {
        long vet = staff("VET");
        OpenedSession phone = open(vet);
        OpenedSession desk = open(vet);
        probe(desk.accessToken());

        assertThat(logout(desk.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(lastSeen(vet)).isNull();
        assertThat(sessionTime(phone, "revoked_at")).isNull();

        clock.advance(Duration.ofSeconds(10));
        assertThat(probe(phone.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastSeen(vet)).isEqualTo(T0.plusSeconds(10));
        assertError(probe(desk.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- đầu vào lỗi: không đổi DB

    @Test
    void missingOrGarbageTokenIs401() {
        assertError(logout(null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(logout("garbage"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void expiredSessionIs401AndUntouched() {
        OpenedSession session = open(staff("VET"));
        clock.advance(Duration.ofHours(12));   // session.ttl_hours [CFG] mặc định

        assertError(logout(session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(sessionTime(session, "revoked_at")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"is_locked = true", "status = 'DISABLED'"})
    void lockedOrDisabledAccountIs401AndUntouched(String change) {
        long vet = staff("VET");
        OpenedSession session = open(vet);
        jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(T0), vet);
        jdbc.update("UPDATE accounts SET " + change + " WHERE id = ?", vet);

        assertError(logout(session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(sessionTime(session, "revoked_at")).isNull();
        assertThat(lastSeen(vet)).isEqualTo(T0);
    }

    @Test
    void getIs405WithTokenAnd401Without() {
        OpenedSession session = open(staff("VET"));

        assertError(http.exchange("/api/auth/logout", HttpMethod.GET, new HttpEntity<>(bearer(session.accessToken())),
                MAP), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(http.exchange("/api/auth/logout", HttpMethod.GET, new HttpEntity<>(bearer(null)), MAP),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(sessionTime(session, "revoked_at")).isNull();
    }

    // ---------------------------------------------------------------- đồng thời (docs/adr/0021)

    /** Câu ghi online của filter không ghi cho phiên đã hủy (điều kiện EXISTS — docs/adr/0021 mục 3). */
    @Test
    void touchLastSeenSkipsRevokedSession() {
        long vet = staff("VET");
        OpenedSession live = open(vet);
        OpenedSession revoked = open(vet);
        tx.executeWithoutResult(status -> sessions.revoke(revoked.sessionId()));
        Instant threshold = T0.minusSeconds(60);

        Integer skipped = tx.execute(status -> accounts.touchLastSeen(vet, revoked.sessionId(), T0, threshold));
        assertThat(skipped).isZero();
        assertThat(lastSeen(vet)).isNull();

        Integer touched = tx.execute(status -> accounts.touchLastSeen(vet, live.sessionId(), T0, threshold));
        assertThat(touched).isEqualTo(1);
        assertThat(lastSeen(vet)).isEqualTo(T0);
    }

    /**
     * Race R3: request A cùng token đã đọc phiên (còn hiệu lực) nhưng chưa ghi {@code last_seen_at}; đăng xuất chạy
     * xong; A ghi tiếp. A vẫn hoàn tất (00-method §3.1: token hết hiệu lực ở request <b>kế tiếp</b>), nhưng không được
     * đưa nhân viên đã đăng xuất về online.
     */
    @Test
    void inFlightRequestCannotMarkLoggedOutStaffOnline() throws Exception {
        long vet = staff("VET");
        String token = open(vet).accessToken();
        AtomicBoolean armed = new AtomicBoolean(true);
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Answer<?> real = mockingDetails(sessionRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object view = real.answer(invocation);
            if (armed.compareAndSet(true, false)) {
                arrived.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return view;
        }).when(sessionRepository).findAuthView(anyLong());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<Map<String, Object>>> inFlight = pool.submit(() -> probe(token));
            assertThat(arrived.await(10, TimeUnit.SECONDS)).as("request A dừng sau khi đọc phiên").isTrue();

            assertThat(logout(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(lastSeen(vet)).isNull();

            release.countDown();
            assertThat(inFlight.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(lastSeen(vet)).as("nhân viên đã đăng xuất không được online lại").isNull();
        assertError(probe(token), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    /** R1: hai lần đăng xuất cùng token đều qua filter trước khi lần nào commit: cả hai 204, hủy đúng một lần. */
    @Test
    void concurrentLogoutWithSameTokenBothSucceed() throws Exception {
        long vet = staff("VET");
        OpenedSession session = open(vet);
        OpenedSession other = open(vet);
        AtomicInteger calls = new AtomicInteger();
        CyclicBarrier bothAuthenticated = new CyclicBarrier(2);
        Answer<?> real = mockingDetails(sessionRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object view = real.answer(invocation);
            if (calls.incrementAndGet() <= 2) {
                bothAuthenticated.await(30, TimeUnit.SECONDS);
            }
            return view;
        }).when(sessionRepository).findAuthView(anyLong());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> first = pool.submit(() -> logout(session.accessToken()));
            Future<ResponseEntity<Map<String, Object>>> second = pool.submit(() -> logout(session.accessToken()));

            assertThat(first.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(second.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
        assertThat(sessionTime(other, "revoked_at")).isNull();
        assertThat(lastSeen(vet)).isNull();
    }

    /**
     * R5: một transaction đang giữ khóa dòng {@code accounts} (như đăng nhập ở máy khác: khóa → mở phiên mới). Đăng
     * xuất phải <b>chờ</b> rồi mới xóa {@code last_seen_at} (không bỏ qua bằng {@code SKIP LOCKED}); phiên mới còn nguyên.
     */
    @Test
    void logoutWaitsForAccountLockAndKeepsNewSession() throws Exception {
        long vet = staff("VET");
        String token = open(vet).accessToken();
        jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(T0), vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OpenedSession> login = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, vet);
                OpenedSession opened = sessions.open(vet, "203.0.113.7", "LogoutIT-login");
                locked.countDown();
                await(release);
                return opened;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> logout = pool.submit(() -> logout(token));
            awaitClearLastSeenWaiting();
            release.countDown();

            OpenedSession newSession = login.get(30, TimeUnit.SECONDS);
            assertThat(logout.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(lastSeen(vet)).isNull();
            assertThat(sessionTime(newSession, "revoked_at")).isNull();
            assertThat(probe(newSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * R6: luồng hủy phiên khóa {@code accounts → sessions} (như đổi / đặt lại mật khẩu, khóa tài khoản sau này:
     * {@code FOR UPDATE} rồi {@code UPDATE sessions … WHERE account_id}). Đăng xuất cùng thứ tự nên chỉ chờ, không deadlock;
     * đảo thứ tự trong {@code LogoutService} thì Postgres hủy một bên.
     */
    @Test
    void logoutDoesNotDeadlockWithAccountsFirstRevoke() throws Exception {
        long vet = staff("VET");
        OpenedSession session = open(vet);
        jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(T0), vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch logoutWaiting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> revokeAll = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, vet);
                locked.countDown();
                await(logoutWaiting);
                return jdbc.update("""
                        UPDATE sessions SET revoked_at = ?, updated_at = ? WHERE account_id = ? AND revoked_at IS NULL
                        """, Timestamp.from(T0), Timestamp.from(T0), vet);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> logout = pool.submit(() -> logout(session.accessToken()));
            awaitClearLastSeenWaiting();
            logoutWaiting.countDown();

            assertThat(revokeAll.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(logout.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            logoutWaiting.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(sessionTime(session, "revoked_at")).isEqualTo(T0);
        assertThat(lastSeen(vet)).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private OpenedSession open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "LogoutIT"));
    }

    private ResponseEntity<Map<String, Object>> logout(String token) {
        return http.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> probe(String token) {
        return http.exchange("/api/test/logout-probe", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
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

    /** Chờ tới khi câu {@code clearLastSeen} của đăng xuất đang đứng chờ khóa dòng {@code accounts}. */
    private void awaitClearLastSeenWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(CLEAR_LAST_SEEN_WAITING, Long.class) == 0) {
            assertThat(System.nanoTime()).as("đăng xuất phải chờ khóa dòng accounts").isLessThan(deadline);
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

    private Instant lastSeen(long accountId) {
        return accountTime(accountId, "last_seen_at");
    }

    private Instant accountTime(long accountId, String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?", Timestamp.class,
                accountId);
        return value == null ? null : value.toInstant();
    }

    private Instant sessionTime(OpenedSession session, String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM sessions WHERE id = ?", Timestamp.class,
                session.sessionId());
        return value == null ? null : value.toInstant();
    }

    /** Nhân viên A05–A08 của chi nhánh dựng ở {@link #setUp}. */
    private long staff(String role) {
        return staff(role, branchId);
    }

    /** ADMIN, SUPER_MANAGER: không gắn chi nhánh (BR-QT-03). */
    private long staffWithoutBranch(String role) {
        return staff(role, null);
    }

    private long staff(String role, Long branch) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "logout-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test",
                role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branch);
        return id;
    }

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "logout-it-c-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test");
    }
}
