package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;

/**
 * {@code GET /api/me} (UC06, identity-v1 #8) qua HTTP trên Postgres 17 thật. Nhân viên đọc hồ sơ thật trong
 * {@code staff_profiles} + SĐT của tài khoản; khách đọc qua {@link CustomerQueryApi} (mock, vì bản thật của BE-2 chưa
 * có — nợ D010). Mỗi cột chèn một giá trị khác nhau để bắt trường lấy nhầm nguồn. Kiểm: đúng tập khóa của contract,
 * miễn chặn BR-TK-17, 401 khi phiên hủy / tài khoản bị khóa hoặc vô hiệu hóa (BR-TK-11), luôn trả chủ token
 * (ADR-0006), 405 sai method, 500 khi dữ liệu sai (nhân viên thiếu {@code staff_profiles}) hoặc customer chưa cài.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MeIT {

    private static final long CUSTOMER_ID = 800L;
    private static final String INTERNAL_MESSAGE = "Lỗi hệ thống, vui lòng thử lại sau";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @MockitoBean
    private CustomerQueryApi customerQueryApi;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long branchId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "MeIT chi nhánh " + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- nhân viên

    @Test
    void vetGetsFullStaffProfileMatchingContract() {
        String email = newEmail();
        long id = staffAccount(email, "VET", "0901234567");
        jdbc.update("""
                INSERT INTO staff_profiles (account_id, full_name, avatar_url, branch_id, specialty, bio)
                VALUES (?, 'Bác sĩ An', 'https://img.test/an.png', ?, 'Nội khoa', 'Mười năm kinh nghiệm')
                """, id, branchId);

        ResponseEntity<Map<String, Object>> response = me(open(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code")
                .containsEntry("message", "success").containsEntry("code", 200);
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("account", "staffProfile", "customerId", "linkDecisionPending")
                .containsEntry("customerId", null).containsEntry("linkDecisionPending", false);
        assertAccount(data, id, email, "VET", false);
        Map<String, Object> profile = map(data.get("staffProfile"));
        assertThat(profile).containsOnlyKeys("accountId", "fullName", "avatarUrl", "phone", "branchId", "specialty",
                        "bio")
                .containsEntry("fullName", "Bác sĩ An").containsEntry("avatarUrl", "https://img.test/an.png")
                .containsEntry("phone", "0901234567").containsEntry("specialty", "Nội khoa")
                .containsEntry("bio", "Mười năm kinh nghiệm");
        assertThat(number(profile.get("accountId"))).isEqualTo(id);
        assertThat(number(profile.get("branchId"))).isEqualTo(branchId).isNotEqualTo(id);
        verifyNoInteractions(customerQueryApi);
    }

    @Test
    void adminWithoutBranchGetsNullBranchId() {
        long id = staffAccount(newEmail(), "ADMIN", "0907654321");
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name) VALUES (?, 'Quản trị hệ thống')", id);

        Map<String, Object> data = data(me(open(id)));

        Map<String, Object> profile = map(data.get("staffProfile"));
        assertThat(profile).containsEntry("branchId", null).containsEntry("avatarUrl", null)
                .containsEntry("specialty", null).containsEntry("bio", null)
                .containsEntry("fullName", "Quản trị hệ thống").containsEntry("phone", "0907654321");
        assertThat(map(data.get("account"))).containsEntry("role", "ADMIN");
        verifyNoInteractions(customerQueryApi);
    }

    @Test
    void superManagerWithoutBranchGetsNullBranchId() {
        long id = staffWithProfile("SUPER_MANAGER", null);

        Map<String, Object> data = data(me(open(id)));

        assertThat(map(data.get("staffProfile"))).containsEntry("branchId", null);
        assertThat(map(data.get("account"))).containsEntry("role", "SUPER_MANAGER");
    }

    @Test
    void staffWithoutStaffProfileIsServerError() {
        long id = staffAccount(newEmail(), "ADMIN", "0907654321");

        assertError(me(open(id)), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(me(open(id)).getBody()).containsEntry("message", INTERNAL_MESSAGE);
        verifyNoInteractions(customerQueryApi);
    }

    // ---------------------------------------------------------------- khách (BR-KH-01, BR-TK-19, ADR-0006)

    @Test
    void customerGetsCustomerIdAndPendingLinkFlagWithoutContactData() {
        String email = newEmail();
        long id = customerAccount(email);
        stubCustomer(id, true);

        ResponseEntity<Map<String, Object>> response = me(open(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("account", "staffProfile", "customerId", "linkDecisionPending")
                .containsEntry("staffProfile", null).containsEntry("linkDecisionPending", true);
        assertThat(number(data.get("customerId"))).isEqualTo(CUSTOMER_ID).isNotEqualTo(id);
        assertAccount(data, id, email, "CUSTOMER", false);
    }

    @Test
    void customerWithoutPendingLink() {
        long id = customerAccount(newEmail());
        stubCustomer(id, false);

        assertThat(data(me(open(id)))).containsEntry("linkDecisionPending", false);
    }

    @Test
    void customerQueryPlaceholderGivesServerError() {
        long id = customerAccount(newEmail());
        doThrow(new UnsupportedOperationException("CustomerQueryApi chưa cài (D010)"))
                .when(customerQueryApi).findCustomerIdByAccountId(id);

        assertError(me(open(id)), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    @Test
    void alwaysReturnsTokenOwnerIgnoringRequestedAccountId() {
        long owner = staffWithProfile("VET", branchId);
        long other = staffWithProfile("RECEPTIONIST", branchId);

        ResponseEntity<Map<String, Object>> response = http.exchange("/api/me?accountId=" + other, HttpMethod.GET,
                new HttpEntity<>(bearer(open(owner))), MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(number(map(data(response).get("account")).get("id"))).isEqualTo(owner);
    }

    // ---------------------------------------------------------------- BR-TK-17

    @Test
    void mustChangePasswordStillGetsMeButOtherApisAreBlocked() {
        long id = staffWithProfile("VET", branchId);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);
        String token = open(id);

        ResponseEntity<Map<String, Object>> response = me(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(map(data(response).get("account"))).containsEntry("mustChangePassword", true);
        assertError(http.exchange("/api/product-categories", HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP),
                HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
    }

    // ---------------------------------------------------------------- xác thực (ADR-0003, BR-TK-11)

    @Test
    void withoutTokenIsUnauthenticated() {
        assertError(me(null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(me("not-a-jwt"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void revokedSessionIsUnauthenticated() {
        long id = staffWithProfile("VET", branchId);
        OpenedSession session = tx.execute(status -> sessions.open(id, "203.0.113.9", "MeIT"));
        tx.executeWithoutResult(status -> sessions.revoke(session.sessionId()));

        assertError(me(session.accessToken()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void lockedOrDisabledAccountIsUnauthenticatedOnNextRequest() {
        long locked = staffWithProfile("VET", branchId);
        String lockedToken = open(locked);
        long disabled = staffWithProfile("CARETAKER", branchId);
        String disabledToken = open(disabled);
        assertThat(me(lockedToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'MeIT' WHERE id = ?", locked);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);

        assertError(me(lockedToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(me(disabledToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void otherMethodsAreNotAllowed() {
        String token = open(staffWithProfile("VET", branchId));

        assertError(http.exchange("/api/me", HttpMethod.POST, new HttpEntity<>(bearer(token)), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(http.exchange("/api/me", HttpMethod.DELETE, new HttpEntity<>(bearer(token)), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    // ---------------------------------------------------------------- dữ liệu

    private long staffAccount(String email, String role, String phone) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, ?, 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, email, phone, role);
    }

    private long staffWithProfile(String role, Long branch) {
        long id = staffAccount(newEmail(), role, "0900000000");
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branch);
        return id;
    }

    private long customerAccount(String email) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    private void stubCustomer(long accountId, boolean linkDecisionPending) {
        doAnswer(invocation -> Optional.of(CUSTOMER_ID)).when(customerQueryApi).findCustomerIdByAccountId(accountId);
        doAnswer(invocation -> Optional.of(new CustomerContact(CUSTOMER_ID, "Nguyễn Văn Khách", "0912345678",
                "khach-ho-so@petcare.test", accountId, linkDecisionPending)))
                .when(customerQueryApi).findContact(CUSTOMER_ID);
    }

    private static String newEmail() {
        return "me-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "MeIT")).accessToken();
    }

    // ---------------------------------------------------------------- HTTP

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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private static void assertAccount(Map<String, Object> data, long id, String email, String role,
            boolean mustChangePassword) {
        Map<String, Object> account = map(data.get("account"));
        assertThat(account).containsOnlyKeys("id", "email", "role", "status", "isLocked", "mustChangePassword")
                .containsEntry("email", email).containsEntry("role", role).containsEntry("status", "ACTIVE")
                .containsEntry("isLocked", false).containsEntry("mustChangePassword", mustChangePassword);
        assertThat(number(account.get("id"))).isEqualTo(id);
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }
}
