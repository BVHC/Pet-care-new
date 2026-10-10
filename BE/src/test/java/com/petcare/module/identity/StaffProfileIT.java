package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.module.identity.service.SystemConfigService;

/**
 * {@code PATCH /api/me/staff-profile} (UC06, identity-v1 #10; BR-TK-15, 20, 11, 17; docs/adr/0026) qua HTTP trên
 * Postgres 17 thật. Kiểm: đúng tập khóa của contract và trùng {@code GET /me}; null = giữ, rỗng = xóa; mọi lỗi hình
 * thức / rule đủ 6 trường envelope và <b>không đổi dữ liệu</b> ({@code updated_at} hai bảng giữ nguyên); chỉ VET sửa
 * specialty / bio; bio theo [CFG]; khách 403; field lạ bị bỏ qua; khóa {@code accounts} trước khi đọc hồ sơ (không ghi
 * đè thay đổi đồng thời, thấy khóa ADMIN vừa đặt, không deadlock với luồng {@code accounts → sessions}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StaffProfileIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    /** {@code findByIdForUpdate}: Hibernate 6 sinh {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE}. */
    private static final String WAITING_ON_ACCOUNT_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
            """;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private SystemConfigService configs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long branchId;
    private long otherBranchId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branchId = branch();
        otherBranchId = branch();
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void vetUpdatesEveryFieldAndResponseMatchesContractAndGetMe() {
        long id = staff("VET", "0901234567", "Tên cũ", "https://img.test/old.png", "Cũ", "Bio cũ");
        String token = open(id);

        ResponseEntity<Map<String, Object>> response = patch(token, Map.of(
                "fullName", "  Bác sĩ An  ", "avatarUrl", "https://img.test/an.png", "phone", "0987654321",
                "specialty", "Nội khoa", "bio", "Mười năm kinh nghiệm"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code")
                .containsEntry("message", "Đã cập nhật hồ sơ").containsEntry("code", 200);
        Map<String, Object> profile = data(response);
        assertThat(profile).containsOnlyKeys("accountId", "fullName", "avatarUrl", "phone", "branchId", "specialty",
                        "bio")
                .containsEntry("fullName", "Bác sĩ An").containsEntry("avatarUrl", "https://img.test/an.png")
                .containsEntry("phone", "0987654321").containsEntry("specialty", "Nội khoa")
                .containsEntry("bio", "Mười năm kinh nghiệm");
        assertThat(number(profile.get("accountId"))).isEqualTo(id);
        assertThat(number(profile.get("branchId"))).isEqualTo(branchId).isNotEqualTo(id);

        assertThat(jdbc.queryForObject("SELECT phone FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("0987654321");
        assertThat(row(id)).containsEntry("full_name", "Bác sĩ An")
                .containsEntry("avatar_url", "https://img.test/an.png").containsEntry("specialty", "Nội khoa")
                .containsEntry("bio", "Mười năm kinh nghiệm");
        assertThat(map(data(me(token)).get("staffProfile"))).isEqualTo(profile);
    }

    @Test
    void allNullIsNoOpReturnsCurrent() {
        long id = staff("VET", "0901234567", "Bác sĩ An", "https://img.test/an.png", "Nội khoa", "Bio");
        Map<String, Object> before = timestamps(id);

        Map<String, Object> profile = data(patch(open(id), Map.of()));

        assertThat(profile).containsEntry("fullName", "Bác sĩ An").containsEntry("phone", "0901234567")
                .containsEntry("avatarUrl", "https://img.test/an.png").containsEntry("specialty", "Nội khoa")
                .containsEntry("bio", "Bio");
        assertThat(timestamps(id)).as("không đổi gì thì không UPDATE").isEqualTo(before);
    }

    @Test
    void explicitNullsKeepValues() {
        long id = staff("VET", "0901234567", "Bác sĩ An", "https://img.test/an.png", "Nội khoa", "Bio");
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", null);
        body.put("phone", null);
        body.put("avatarUrl", null);
        body.put("specialty", null);
        body.put("bio", null);

        assertThat(data(patch(open(id), body))).containsEntry("phone", "0901234567")
                .containsEntry("avatarUrl", "https://img.test/an.png").containsEntry("bio", "Bio");
    }

    @Test
    void emptyStringClearsOptionalFields() {
        long id = staff("VET", "0901234567", "Bác sĩ An", "https://img.test/an.png", "Nội khoa", "Bio");

        Map<String, Object> profile = data(patch(open(id), Map.of("avatarUrl", "", "specialty", "  ", "bio", "")));

        assertThat(profile).containsEntry("avatarUrl", null).containsEntry("specialty", null)
                .containsEntry("bio", null).containsEntry("fullName", "Bác sĩ An");
        assertThat(row(id)).containsEntry("avatar_url", null).containsEntry("specialty", null)
                .containsEntry("bio", null);
    }

    @Test
    void receptionistUpdatesNameAvatarPhone() {
        long id = staff("RECEPTIONIST", "0901234567", "Lễ tân", null, null, null);

        assertThat(data(patch(open(id), Map.of("fullName", "Lễ tân Hoa", "avatarUrl", "https://img.test/h.png",
                "phone", "0911111111")))).containsEntry("fullName", "Lễ tân Hoa").containsEntry("phone", "0911111111");
    }

    @Test
    void adminWithoutBranchUpdates() {
        long id = staff("ADMIN", "0907654321", "Quản trị", null, null, null, null);

        Map<String, Object> profile = data(patch(open(id), Map.of("fullName", "Quản trị viên")));

        assertThat(profile).containsEntry("fullName", "Quản trị viên").containsEntry("branchId", null);
    }

    @Test
    void unknownFieldsIgnored() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);

        Map<String, Object> profile = data(patch(open(id), Map.of("fullName", "Bác sĩ Bình", "role", "ADMIN",
                "branchId", otherBranchId, "accountId", 1, "status", "DISABLED")));

        assertThat(number(profile.get("branchId"))).isEqualTo(branchId);
        assertThat(jdbc.queryForMap("SELECT role, status FROM accounts WHERE id = ?", id))
                .containsEntry("role", "VET").containsEntry("status", "ACTIVE");
    }

    // ---------------------------------------------------------------- lỗi hình thức (400 VALIDATION_FAILED)

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fullName\": \"\"}",
            "{\"fullName\": \"   \"}",
            "{\"phone\": \"\"}",
            "{\"phone\": \"09012345678\"}",
            "{\"phone\": \"090123456\"}",
            "{\"phone\": \"+84901234567\"}",
            "{\"phone\": \"0901 234 567\"}",
            "{\"avatarUrl\": \"http://img.test/a.png\"}",
            "{\"avatarUrl\": \"javascript:alert(1)\"}",
            "{\"avatarUrl\": \"data:image/png;base64,AAAA\"}",
            "{\"avatarUrl\": \"https://\"}",
            "{\"email\": \"x@petcare.test\", \"phone\": \"abc\"}"})
    void invalidShapeIs400AndNothingChanges(String body) {
        long id = staff("VET", "0901234567", "Bác sĩ An", "https://img.test/an.png", null, null);
        Map<String, Object> before = timestamps(id);

        assertError(patchRaw(open(id), body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(timestamps(id)).isEqualTo(before);
    }

    @Test
    void tooLongFieldsAre400() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        String token = open(id);

        assertError(patch(token, Map.of("fullName", "a".repeat(101))), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(patch(token, Map.of("specialty", "a".repeat(201))), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(patch(token, Map.of("avatarUrl", "https://" + "a".repeat(493))), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(data(patch(token, Map.of("fullName", "a".repeat(100))))).containsEntry("fullName", "a".repeat(100));
    }

    @Test
    void malformedJsonIs400() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);

        assertError(patchRaw(open(id), "{\"fullName\": "), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(patchRaw(open(id), ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    // ---------------------------------------------------------------- BR-TK-15, 20, chỉ VET

    @Test
    void emailInBodyIs400BrTk15AndNothingChanges() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        Map<String, Object> before = timestamps(id);

        ResponseEntity<Map<String, Object>> response = patch(open(id),
                Map.of("email", "moi@petcare.test", "fullName", "Tên mới"));

        assertRule(response, "BR-TK-15");
        assertThat(timestamps(id)).isEqualTo(before);
        assertThat(row(id)).containsEntry("full_name", "Bác sĩ An");
    }

    @Test
    void caretakerBioIs403AndNothingChanges() {
        long id = staff("CARETAKER", "0901234567", "Chăm sóc", null, null, null);
        Map<String, Object> before = timestamps(id);

        assertError(patch(open(id), Map.of("fullName", "Tên mới", "bio", "Giới thiệu")), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(patch(open(id), Map.of("specialty", "")), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(timestamps(id)).isEqualTo(before);
    }

    @Test
    void bioOverDefaultLimitIs400BrTk20() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, "Bio cũ");
        String token = open(id);

        assertRule(patch(token, Map.of("bio", "a".repeat(501))), "BR-TK-20");
        assertThat(row(id)).containsEntry("bio", "Bio cũ");
        assertThat(data(patch(token, Map.of("bio", "a".repeat(500))))).containsEntry("bio", "a".repeat(500));
    }

    @Test
    void bioLimitFollowsConfigAndOnlyAppliesWhenBioIsSent() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, "x".repeat(400));
        String token = open(id);
        try {
            jdbc.update("UPDATE system_configs SET value = '100' WHERE key = 'vet.bio_max_length'");
            configs.reload();

            assertRule(patch(token, Map.of("bio", "b".repeat(101))), "BR-TK-20");
            assertThat(data(patch(token, Map.of("bio", "b".repeat(100))))).containsEntry("bio", "b".repeat(100));
            jdbc.update("UPDATE staff_profiles SET bio = ? WHERE account_id = ?", "x".repeat(400), id);
            assertThat(data(patch(token, Map.of("fullName", "Tên mới")))).as("BR-QT-13: không gửi bio thì không kiểm")
                    .containsEntry("fullName", "Tên mới").containsEntry("bio", "x".repeat(400));
        } finally {
            jdbc.update("UPDATE system_configs SET value = '500' WHERE key = 'vet.bio_max_length'");
            configs.reload();
        }
    }

    // ---------------------------------------------------------------- quyền, phiên, BR-TK-17

    @Test
    void customerIs403() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());

        assertError(patch(open(id), Map.of("fullName", "Khách")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void withoutTokenIsUnauthenticated() {
        assertError(patch(null, Map.of("fullName", "X")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void mustChangePasswordIsBlockedByBrTk17() {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);

        assertRule(patch(open(id), Map.of("fullName", "Tên mới")), "BR-TK-17");
        assertThat(row(id)).containsEntry("full_name", "Bác sĩ An");
    }

    @Test
    void otherMethodsAreNotAllowed() {
        String token = open(staff("VET", "0901234567", "Bác sĩ An", null, null, null));

        assertError(http.exchange("/api/me/staff-profile", HttpMethod.PUT,
                        new HttpEntity<>(Map.of("fullName", "X"), bearer(token)), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    // ---------------------------------------------------------------- khóa dòng accounts (docs/adr/0026)

    /** Luồng khác (như T14 điều chuyển) khóa {@code accounts} rồi đổi chi nhánh: PATCH chờ và không ghi đè. */
    @Test
    void patchWaitsForAccountLockAndKeepsConcurrentBranchChange() throws Exception {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        String token = open(id);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(id,
                () -> jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranchId, id),
                () -> patch(token, Map.of("fullName", "Bác sĩ Bình")));

        assertThat(number(data(response).get("branchId"))).isEqualTo(otherBranchId);
        assertThat(row(id)).containsEntry("full_name", "Bác sĩ Bình");
        assertThat(number(row(id).get("branch_id"))).as("không bị ghi đè bằng giá trị cũ").isEqualTo(otherBranchId);
    }

    /** ADMIN khóa tài khoản sau khi filter đã cho request qua: dưới khóa dòng thấy {@code is_locked} → BR-TK-11. */
    @Test
    void patchRacingAdminLockIs400AndNothingChanges() throws Exception {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        String token = open(id);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(id,
                () -> jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'StaffProfileIT' WHERE id = ?",
                        id),
                () -> patch(token, Map.of("fullName", "Tên mới", "phone", "0911111111")));

        assertRule(response, "BR-TK-11");
        assertThat(row(id)).containsEntry("full_name", "Bác sĩ An");
        assertThat(jdbc.queryForObject("SELECT phone FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("0901234567");
    }

    /** Luồng hủy phiên (đăng xuất, đổi mật khẩu…) khóa {@code accounts → sessions}: không deadlock, cả hai xong. */
    @Test
    void patchAndAccountsThenSessionsFlowBothSucceed() throws Exception {
        long id = staff("VET", "0901234567", "Bác sĩ An", null, null, null);
        String token = open(id);
        OpenedSession other = tx.execute(status -> sessions.open(id, "203.0.113.9", "StaffProfileIT-other"));

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(id,
                () -> jdbc.update("UPDATE sessions SET revoked_at = ?, updated_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), other.sessionId()),
                () -> patch(token, Map.of("phone", "0922222222")));

        assertThat(data(response)).containsEntry("phone", "0922222222");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM sessions WHERE id = ?", Boolean.class,
                other.sessionId())).isTrue();
    }

    /**
     * Transaction A khóa dòng {@code accounts} ({@code FOR UPDATE}), chạy {@code whileHolding}; request chạy ở thread
     * khác và phải đứng chờ khóa (thấy trong {@code pg_stat_activity}) trước khi A commit.
     */
    private ResponseEntity<Map<String, Object>> whileAccountLocked(long accountId, Runnable whileHolding,
            Callable<ResponseEntity<Map<String, Object>>> request) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
                whileHolding.run();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> call = pool.submit(request);
            awaitWaitingOnAccountLock();
            assertThat(call.isDone()).as("PATCH phải chờ khóa dòng accounts").isFalse();
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            return call.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaitingOnAccountLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_ACCOUNT_LOCK, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("PATCH phải đứng chờ khóa; pg_stat_activity = " + jdbc.queryForList("""
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

    // ---------------------------------------------------------------- dữ liệu

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "StaffProfileIT chi nhánh " + UUID.randomUUID());
    }

    private long staff(String role, String phone, String fullName, String avatarUrl, String specialty, String bio) {
        return staff(role, phone, fullName, avatarUrl, specialty, bio, branchId);
    }

    private long staff(String role, String phone, String fullName, String avatarUrl, String specialty, String bio,
            Long branch) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, ?, 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), phone, role);
        jdbc.update("""
                INSERT INTO staff_profiles (account_id, full_name, avatar_url, branch_id, specialty, bio)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, fullName, avatarUrl, branch, specialty, bio);
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM staff_profiles WHERE account_id = ?", id);
    }

    private Map<String, Object> timestamps(long id) {
        return jdbc.queryForMap("""
                SELECT a.updated_at AS account_updated, sp.updated_at AS profile_updated, a.phone, sp.full_name,
                       sp.avatar_url, sp.specialty, sp.bio, sp.branch_id
                FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id WHERE a.id = ?
                """, id);
    }

    private static String newEmail() {
        return "staff-profile-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "StaffProfileIT")).accessToken();
    }

    // ---------------------------------------------------------------- HTTP

    private ResponseEntity<Map<String, Object>> patch(String token, Map<String, Object> body) {
        return http.exchange("/api/me/staff-profile", HttpMethod.PATCH, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> patchRaw(String token, String body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/me/staff-profile", HttpMethod.PATCH, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> me(String token) {
        return http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return map(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }
}
