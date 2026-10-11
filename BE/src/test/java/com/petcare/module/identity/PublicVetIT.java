package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.service.SessionService;

/**
 * {@code GET /api/public/vets} (UC15, identity-v1 #37; BR-TK-20, BR-CK-01; docs/adr/0026) qua HTTP trên Postgres 17
 * thật, <b>không mock</b> branch: {@code BranchQueryService} thật lọc chi nhánh {@code ACTIVE} và trả tên. Chỉ VET
 * {@code ACTIVE}, không bị khóa (khóa tạm vẫn hiện — ADR-0024 mục 1), thuộc chi nhánh {@code ACTIVE}. Mỗi test dùng
 * chi nhánh mới; danh sách toàn chuỗi chỉ kiểm id do test tạo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicVetIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @MockitoSpyBean
    private StaffProfileRepository staffProfiles;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String activeName;
    private long active;
    private long draft;

    @BeforeEach
    void setUp() {
        activeName = "PublicVetIT chi nhánh " + UUID.randomUUID();
        active = branch(activeName, "ACTIVE");
        draft = branch("PublicVetIT nháp " + UUID.randomUUID(), "DRAFT");
    }

    @Test
    void onlyActiveUnlockedVetsOfActiveBranchesMatchingContract() {
        long shown = staff("VET", "ACTIVE", false, active, "Bác sĩ An", "https://img.test/an.png", "Nội khoa",
                "Mười năm kinh nghiệm");
        long temporarilyLocked = staff("VET", "ACTIVE", false, active, "Bác sĩ Bình", null, null, null);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(900)), temporarilyLocked);
        long locked = staff("VET", "ACTIVE", true, active, "Bị khóa", null, null, null);
        long disabled = staff("VET", "DISABLED", false, active, "Vô hiệu", null, null, null);
        long caretaker = staff("CARETAKER", "ACTIVE", false, active, "Chăm sóc", null, "Có specialty", "Có bio");
        long inDraft = staff("VET", "ACTIVE", false, draft, "Ở chi nhánh nháp", null, null, null);
        long noBranch = staff("VET", "ACTIVE", false, null, "Không chi nhánh", null, null, null);

        ResponseEntity<Map<String, Object>> response = list(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code").containsEntry("code", 200);
        List<Map<String, Object>> vets = vets(response);
        List<Long> ids = vets.stream().map(vet -> number(vet.get("accountId"))).toList();
        assertThat(ids).contains(shown, temporarilyLocked)
                .doesNotContain(locked, disabled, caretaker, inDraft, noBranch);

        Map<String, Object> an = vets.stream().filter(vet -> number(vet.get("accountId")) == shown).findFirst()
                .orElseThrow();
        assertThat(an).containsOnlyKeys("accountId", "fullName", "avatarUrl", "specialty", "bio", "branchId",
                        "branchName")
                .containsEntry("fullName", "Bác sĩ An").containsEntry("avatarUrl", "https://img.test/an.png")
                .containsEntry("specialty", "Nội khoa").containsEntry("bio", "Mười năm kinh nghiệm")
                .containsEntry("branchName", activeName);
        assertThat(number(an.get("branchId"))).isEqualTo(active).isNotEqualTo(shown);
    }

    @Test
    void filterByBranchKeepsQueryOrder() {
        long other = branch("PublicVetIT khác " + UUID.randomUUID(), "ACTIVE");
        long b = staff("VET", "ACTIVE", false, active, "Bình", null, null, null);
        long a = staff("VET", "ACTIVE", false, active, "An", null, null, null);
        long elsewhere = staff("VET", "ACTIVE", false, other, "Ở chi nhánh khác", null, null, null);

        List<Long> ids = vets(list(active, null)).stream().map(vet -> number(vet.get("accountId"))).toList();

        assertThat(ids).containsExactly(a, b).doesNotContain(elsewhere);
    }

    @Test
    void draftOrUnknownBranchIsEmptyWithoutReadingStaff() {
        staff("VET", "ACTIVE", false, draft, "Ở chi nhánh nháp", null, null, null);
        clearInvocations(staffProfiles);

        assertThat(vets(list(draft, null))).isEmpty();
        assertThat(vets(list(999_999_999L, null))).isEmpty();
        verify(staffProfiles, never()).findPublicVets();
    }

    @Test
    void invalidBranchIdIs400() {
        assertError(http.exchange("/api/public/vets?branchId=0", HttpMethod.GET, HttpEntity.EMPTY, MAP),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(http.exchange("/api/public/vets?branchId=-1", HttpMethod.GET, HttpEntity.EMPTY, MAP),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(http.exchange("/api/public/vets?branchId=abc", HttpMethod.GET, HttpEntity.EMPTY, MAP),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    /** Path public luôn ẩn danh (ADR-0005): token của tài khoản phải đổi mật khẩu bị bỏ qua, không BR-TK-17. */
    @Test
    void publicPathIgnoresTokenAndBrTk17() {
        long shown = staff("VET", "ACTIVE", false, active, "Bác sĩ An", null, null, null);
        long viewer = staff("RECEPTIONIST", "ACTIVE", false, active, "Lễ tân", null, null, null);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", viewer);
        String token = new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(viewer, "203.0.113.9", "PublicVetIT")).accessToken();

        assertThat(vets(list(active, token))).extracting(vet -> number(vet.get("accountId"))).containsExactly(shown);
        assertThat(vets(list(active, "not-a-jwt"))).hasSize(1);
    }

    @Test
    void otherMethodsAreNotAllowed() {
        assertError(http.exchange("/api/public/vets", HttpMethod.POST, HttpEntity.EMPTY, MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    // ---------------------------------------------------------------- dữ liệu

    private long branch(String name, String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, name, status);
    }

    private long staff(String role, String status, boolean locked, Long branch, String fullName, String avatarUrl,
            String specialty, String bio) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status, is_locked)
                VALUES (?, '0900000000', 'hash', ?, ?, ?) RETURNING id
                """, Long.class, "public-vet-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test",
                role, status, locked);
        jdbc.update("""
                INSERT INTO staff_profiles (account_id, full_name, avatar_url, branch_id, specialty, bio)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, fullName, avatarUrl, branch, specialty, bio);
        return id;
    }

    // ---------------------------------------------------------------- HTTP

    private ResponseEntity<Map<String, Object>> list(Long branchId, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String path = branchId == null ? "/api/public/vets" : "/api/public/vets?branchId=" + branchId;
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vets(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data")).isInstanceOf(List.class);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
    }
}
