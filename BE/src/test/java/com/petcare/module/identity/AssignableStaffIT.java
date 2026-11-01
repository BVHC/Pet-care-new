package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * {@code GET /api/staff/assignable} (identity-v1 #38, UC45; BR-TN-05, 06; docs/adr/0089) qua HTTP trên Postgres 17 thật,
 * không mock module nào. Ghim: lễ tân đọc được danh sách gán lượt nhưng <b>vẫn bị 403 ở {@code GET /api/staff}</b>; chỉ
 * VET / CARETAKER ACTIVE, không bị khóa, đúng chi nhánh và chức vụ; online trước, rồi họ tên, rồi id; đúng 4 khóa JSON;
 * phạm vi chi nhánh; SUPER_MANAGER phải chọn chi nhánh; thứ tự lỗi 401 → 400 BR-TK-17 → 400 tham số → 403 vai trò → 403
 * phạm vi → 400 thiếu chi nhánh; route {@code /assignable} thắng {@code /{id}}.
 * <p>
 * Era riêng 2029 (không IT nào quét tới) cho {@link MutableClock}; mỗi test tự tạo chi nhánh và tài khoản (email UUID,
 * họ tên mang {@code tag}) rồi xóa đúng những dòng đó. Endpoint chỉ đọc nên không có audit để dọn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AssignableStaffIT.TestBeans.class})
class AssignableStaffIT {

    private static final Instant T0 = Instant.parse("2029-05-14T03:00:00Z");
    private static final String PATH = "/api/staff/assignable";
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
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> branchIds = new ArrayList<>();
    private String tag;
    private long branchA;
    private long branchB;
    private long receptionistA;
    private long managerA;
    private long superManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tag = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch();
        branchB = branch();
        receptionistA = staff(Role.RECEPTIONIST, branchA, "Le tan " + tag);
        managerA = staff(Role.BRANCH_MANAGER, branchA, "Quan ly " + tag);
        superManager = staff(Role.SUPER_MANAGER, null, "Tong quan ly " + tag);
    }

    /** Xóa đúng dòng của test, theo thứ tự FK {@code RESTRICT}: phiên → hồ sơ nhân viên → tài khoản → chi nhánh. */
    @AfterEach
    void cleanUp() {
        for (long id : accountIds) {
            jdbc.update("DELETE FROM sessions WHERE account_id = ?", id);
            jdbc.update("DELETE FROM staff_profiles WHERE account_id = ?", id);
            jdbc.update("DELETE FROM accounts WHERE id = ?", id);
        }
        for (long id : branchIds) {
            jdbc.update("DELETE FROM branches WHERE id = ?", id);
        }
    }

    // ================================================================ lễ tân: mở endpoint hẹp, không mở GET /api/staff

    @Test
    void receptionistIsDeniedTheStaffDirectoryButReadsTheAssignableList() {
        long vet = staff(Role.VET, branchA, "Bac si " + tag);
        String receptionist = token(receptionistA);

        assertError(get("/api/staff", receptionist), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(get("/api/staff?role=VET&branchId=" + branchA, receptionist), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(get("/api/staff/" + vet, receptionist), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(ids(get(PATH + "?role=VET", receptionist))).containsExactly(vet);
    }

    // ================================================================ nội dung và thứ tự

    @Test
    void listsOnlyActiveUnlockedStaffOfTheCallersBranchAndRoleOnlineFirstWithFourKeys() {
        long onlineAtTheEdge = staff(Role.VET, branchA, "Bb " + tag, "ACTIVE", false, T0.minus(Duration.ofMinutes(10)));
        long onlineNow = staff(Role.VET, branchA, "Dd " + tag, "ACTIVE", false, T0);
        long neverSeen = staff(Role.VET, branchA, "Aa " + tag);
        long sameName1 = staff(Role.VET, branchA, "Cc " + tag);
        long sameName2 = staff(Role.VET, branchA, "Cc " + tag);
        long stale = staff(Role.VET, branchA, "Ee " + tag, "ACTIVE", false, T0.minus(Duration.ofMinutes(11)));
        long temporarilyLocked = staff(Role.VET, branchA, "Ff " + tag);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(T0.plus(Duration.ofHours(1))),
                temporarilyLocked);
        staff(Role.VET, branchA, "Gg " + tag, "ACTIVE", true, T0);
        staff(Role.VET, branchA, "Hh " + tag, "DISABLED", false, T0);
        staff(Role.VET, branchB, "Ii " + tag, "ACTIVE", false, T0);
        staff(Role.CARETAKER, branchA, "Jj " + tag, "ACTIVE", false, T0);

        ResponseEntity<Map<String, Object>> response = get(PATH + "?role=VET", token(receptionistA));

        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code").containsEntry("code", 200);
        assertThat(response.getBody().get("data")).isInstanceOf(List.class);
        assertThat(ids(response)).containsExactly(onlineAtTheEdge, onlineNow, neverSeen, sameName1, sameName2, stale,
                temporarilyLocked);
        assertThat(rows(response)).extracting(row -> row.get("online"))
                .containsExactly(true, true, false, false, false, false, false);
        assertThat(rows(response)).extracting(row -> row.get("fullName")).containsExactly("Bb " + tag, "Dd " + tag,
                "Aa " + tag, "Cc " + tag, "Cc " + tag, "Ee " + tag, "Ff " + tag);
        for (Map<String, Object> row : rows(response)) {
            assertThat(row).containsOnlyKeys("accountId", "fullName", "role", "online").containsEntry("role", "VET");
        }
    }

    @Test
    void theCaretakerListHoldsOnlyCaretakers() {
        long caretaker = staff(Role.CARETAKER, branchA, "Cham soc " + tag);
        staff(Role.VET, branchA, "Bac si " + tag);

        ResponseEntity<Map<String, Object>> response = get(PATH + "?role=CARETAKER", token(managerA));

        assertThat(ids(response)).containsExactly(caretaker);
        assertThat(rows(response).get(0)).containsEntry("role", "CARETAKER");
    }

    @Test
    void aStaffMemberCreatedJustNowIsListedOnTheNextCall() {
        String receptionist = token(receptionistA);
        assertThat(ids(get(PATH + "?role=VET", receptionist))).isEmpty();

        long vet = staff(Role.VET, branchA, "Moi " + tag);

        assertThat(ids(get(PATH + "?role=VET", receptionist))).containsExactly(vet);
    }

    // ================================================================ phạm vi chi nhánh

    @Test
    void scopeMatrixForEachCaller() {
        long vetA = staff(Role.VET, branchA, "Bac si A " + tag);
        long vetB = staff(Role.VET, branchB, "Bac si B " + tag);
        long receptionistB = staff(Role.RECEPTIONIST, branchB, "Le tan B " + tag);

        for (long caller : List.of(receptionistA, managerA)) {
            String token = token(caller);
            assertThat(ids(get(PATH + "?role=VET", token))).as("không branchId = chi nhánh mình").containsExactly(vetA);
            assertThat(ids(get(PATH + "?role=VET&branchId=" + branchA, token))).containsExactly(vetA);
            assertError(get(PATH + "?role=VET&branchId=" + branchB, token), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED_SCOPE_MISMATCH");
        }
        assertThat(ids(get(PATH + "?role=VET", token(receptionistB)))).containsExactly(vetB);

        String superManagerToken = token(superManager);
        assertThat(ids(get(PATH + "?role=VET&branchId=" + branchA, superManagerToken))).containsExactly(vetA);
        assertThat(ids(get(PATH + "?role=VET&branchId=" + branchB, superManagerToken))).containsExactly(vetB);
    }

    @Test
    void superManagerMustChooseABranchAndAnUnknownBranchIsAnEmptyList() {
        String token = token(superManager);

        ResponseEntity<Map<String, Object>> missing = get(PATH + "?role=VET", token);

        assertError(missing, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(missing.getBody()).containsEntry("message", "Vui lòng chọn chi nhánh");
        assertThat(ids(get(PATH + "?role=VET&branchId=" + Long.MAX_VALUE, token))).isEmpty();
    }

    // ================================================================ vai trò và đăng nhập

    @Test
    void adminVetCaretakerAndCustomerAreDeniedAndAnonymousIs401() {
        for (long caller : List.of(staff(Role.ADMIN, null, "Admin " + tag), staff(Role.VET, branchA, "Vet " + tag),
                staff(Role.CARETAKER, branchA, "Care " + tag), customer())) {
            String token = token(caller);

            assertError(get(PATH + "?role=VET&branchId=" + branchA, token), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(get(PATH + "?role=VET", token), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(get(PATH + "?role=VET&branchId=" + branchA, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(get(PATH, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void aCallerWhoMustChangeThePasswordIsBlockedByBrTk17() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionistA);

        ResponseEntity<Map<String, Object>> response = get(PATH + "?role=VET", token(receptionistA));

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    // ================================================================ tham số: 400 trước 403

    @Test
    void badParametersAre400BeforeTheRoleCheckAndValidOnesAre403() {
        String vet = token(staff(Role.VET, branchA, "Vet " + tag));
        String customer = token(customer());

        ResponseEntity<Map<String, Object>> noRole = get(PATH, vet);
        assertError(noRole, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(noRole.getBody()).containsEntry("message", "Thiếu tham số 'role'");
        for (String role : List.of("BRANCH_MANAGER", "RECEPTIONIST", "ADMIN", "vet", "XYZ")) {
            assertError(get(PATH + "?role=" + role, customer), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        for (String branchId : List.of("0", "-5")) {
            assertError(get(PATH + "?role=VET&branchId=" + branchId, vet), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertError(get(PATH + "?role=VET&branchId=" + branchId, customer), HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED");
        }
        assertError(get(PATH + "?role=VET&branchId=abc", vet), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get(PATH + "?role=VET", vet), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(get(PATH + "?role=CARETAKER&branchId=" + branchA, customer), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void anAuthorizedCallerWithBadParametersGets400() {
        String receptionist = token(receptionistA);

        assertError(get(PATH, receptionist), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get(PATH + "?role=BRANCH_MANAGER", receptionist), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get(PATH + "?role=VET&branchId=0", receptionist), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    // ================================================================ route

    @Test
    void assignableIsNotSwallowedByTheIdRouteAndTheIdRouteIsUnchanged() {
        long vet = staff(Role.VET, branchA, "Bac si " + tag);
        String token = token(superManager);

        assertThat(ids(get(PATH + "?role=VET&branchId=" + branchA, token))).as("literal thắng {id}")
                .containsExactly(vet);
        ResponseEntity<Map<String, Object>> noParams = get(PATH, token);
        assertError(noParams, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(noParams.getBody()).as("lỗi của handler mới, không phải chuyển 'assignable' sang Long")
                .containsEntry("message", "Thiếu tham số 'role'");
        ResponseEntity<Map<String, Object>> notANumber = get("/api/staff/abc", token);
        assertError(notANumber, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(notANumber.getBody()).containsEntry("message", "Tham số 'id' sai kiểu dữ liệu");
        Map<String, Object> detail = map(ok(get("/api/staff/" + vet, token)));
        assertThat(((Number) detail.get("accountId")).longValue()).isEqualTo(vet);
        assertThat(detail).as("GET /staff/{id} vẫn trả StaffResponse đầy đủ").containsKey("email");
    }

    // ================================================================ helpers — dữ liệu

    private long branch() {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "AssignableStaffIT " + UUID.randomUUID());
        branchIds.add(id);
        return id;
    }

    private long staff(Role role, Long branchId, String name) {
        return staff(role, branchId, name, "ACTIVE", false, null);
    }

    private long staff(Role role, Long branchId, String name, String status, boolean locked, Instant lastSeenAt) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status, is_locked)
                VALUES (?, '0900000000', 'hash', ?, ?, ?) RETURNING id
                """, Long.class, "assignable-it-" + UUID.randomUUID() + "@petcare.test", role.name(), status, locked);
        accountIds.add(id);
        if (lastSeenAt != null) {
            jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(lastSeenAt), id);
        }
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id, name,
                branchId);
        return id;
    }

    private long customer() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "assignable-it-c-" + UUID.randomUUID() + "@petcare.test");
        accountIds.add(id);
        return id;
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "AssignableStaffIT")).accessToken();
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    private static Object ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ResponseEntity<Map<String, Object>> response) {
        Object data = ok(response);
        assertThat(data).isInstanceOf(List.class);
        return (List<Map<String, Object>>) data;
    }

    private static List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return rows(response).stream().map(row -> ((Number) row.get("accountId")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
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
