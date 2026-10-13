package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.zaxxer.hikari.HikariDataSource;

/**
 * T14 phần 3 — {@code POST /api/staff/{id}/disable} (#20, Tài khoản#5), {@code POST /api/staff/{id}/reactivate} (#21,
 * Tài khoản#6) qua HTTP trên Postgres 17 thật (identity-v1; BR-QT-02, 03, 04, 07, 08, 09, 10, 12, 15, BR-TK-11, 17,
 * BR-TN-06; docs/adr/0029, 0033). {@code VisitQueryApi}, {@code CashierShiftQueryApi} là {@code @MockitoBean}
 * (placeholder tới 21/10, nợ D016; bản placeholder thật ở {@code StaffTransferPlaceholderIT}); mọi module khác thật,
 * kể cả đăng nhập, đăng xuất, {@code /api/public/vets}, {@code BranchService.activateBranch} ở các ca tranh chấp. Mỗi
 * test tự tạo chi nhánh / tài khoản riêng; audit tra theo actor / entity riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StaffDisableReactivateIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Petcare123";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> STAFF_KEYS = Set.of("accountId", "email", "phone", "fullName", "role", "status",
            "isLocked", "branchId", "specialty", "bio", "mustChangePassword", "online");
    /**
     * Mọi câu đang chờ khóa dòng: request sau có thể chờ ở {@code accounts} (đăng nhập, đăng xuất, kích hoạt lại) hoặc ở
     * {@code branches} (vô hiệu hóa thứ hai chờ chi nhánh mà vô hiệu hóa thứ nhất đang giữ trong lúc chờ
     * {@code accounts}). Các test trong lớp chạy tuần tự nên không có ai khác chờ khóa.
     */
    private static final String WAITING_ON_ANY_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND datname = current_database()
            """;
    private static final String WAITING_ON_BRANCH_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from branches%for no key update%'
            """;

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

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private VisitQueryApi visitQueries;

    @MockitoBean
    private CashierShiftQueryApi cashierShifts;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long branchB;
    private long superManager;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch("ACTIVE");
        branchB = branch("ACTIVE");
        superManager = staff("SUPER_MANAGER", null);
        doReturn(List.of()).when(visitQueries).findUnfinishedVisitsAssignedTo(anyLong());
        doReturn(Optional.empty()).when(cashierShifts).findOpenShift(anyLong());
    }

    // ================================================================ #20 vô hiệu hóa — thành công

    @Test
    void disableReturnsContractShapeEqualToGetRevokesEverySessionGoesOfflineAndAudits() {
        long vet = staff("VET", branchA);
        String vetToken1 = token(vet);
        String vetToken2 = token(vet);
        jdbc.update("UPDATE accounts SET last_seen_at = now() WHERE id = ?", vet);
        long auditsBefore = auditsOn(vet);

        Map<String, Object> data = map(ok(disable(token(superManager), vet)));

        assertThat(data).containsOnlyKeys(STAFF_KEYS).containsEntry("accountId", (int) vet)
                .containsEntry("status", "DISABLED").containsEntry("online", false).containsEntry("isLocked", false)
                .containsEntry("role", "VET").containsEntry("branchId", (int) branchA);
        assertThat(map(ok(get("/api/staff/" + vet, token(superManager))))).isEqualTo(data);
        assertError(get("/api/me", vetToken1), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(get("/api/me", vetToken2), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertDisabledInvariant(vet);
        List<Map<String, Object>> audits = audits(superManager, "STAFF_DISABLED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts").containsEntry("entity_id", vet);
        assertThat(json(audits.get(0), "before_data")).isEqualTo("{\"status\": \"ACTIVE\"}");
        assertThat(json(audits.get(0), "after_data")).isEqualTo("{\"status\": \"DISABLED\"}");
        assertThat(auditsOn(vet)).as("BR-QT-09: lịch sử giữ nguyên, chỉ thêm 1 dòng").isEqualTo(auditsBefore + 1);
        assertThat(outboxCount(email(vet))).as("Q3: không gửi email").isZero();
        assertNotLocked(vet, branchA);
    }

    @Test
    void disabledStaffCannotLoginWithCorrectPassword() {
        long vet = staffWithPassword("VET", branchA);

        ok(disable(token(superManager), vet));

        assertRule(login(email(vet), PASSWORD), "BR-TK-11");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, vet)).isZero();
    }

    @Test
    void disabledVetLeavesPublicVetsAndDisabledManagerNoLongerCountsForActivation() {
        long vet = staff("VET", branchA);
        assertThat(publicVetIds()).contains(vet);
        ok(disable(token(superManager), vet));
        assertThat(publicVetIds()).doesNotContain(vet);

        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        ok(disable(token(superManager), manager));
        assertRule(activate(draft), "BR-QT-04");
        assertThat(text("SELECT status FROM branches WHERE id = ?", draft)).isEqualTo("DRAFT");
    }

    /** docs/adr/0033 Q2: SUPER_MANAGER không có chi nhánh, không bao giờ có lượt / ca → không hỏi BR-QT-08. */
    @Test
    void adminDisablesSuperManagerWithoutPendingWorkCheck() {
        long admin = staff("ADMIN", null);
        long target = staff("SUPER_MANAGER", null);

        assertThat(map(ok(disable(token(admin), target)))).containsEntry("status", "DISABLED")
                .containsEntry("branchId", null);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void branchManagerDisablesOwnSubordinate() {
        long manager = staff("BRANCH_MANAGER", branchA);
        long caretaker = staff("CARETAKER", branchA);

        assertThat(map(ok(disable(token(manager), caretaker)))).containsEntry("status", "DISABLED");
        assertThat(audits(manager, "STAFF_DISABLED")).hasSize(1);
    }

    // ================================================================ #20 — lỗi

    /** BR-QT-12: đang bị khóa (kể cả đã DISABLED) → 400, phiên còn nguyên. */
    @ParameterizedTest(name = "status={0}")
    @CsvSource({"ACTIVE", "DISABLED"})
    void lockedTargetIsBrQt12AndWritesNothing(String status) {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT', status = ? WHERE id = ?", status,
                vet);
        Timestamp updated = updatedAt("accounts", "id", vet);

        ResponseEntity<Map<String, Object>> response = disable(token(superManager), vet);

        assertRule(response, "BR-QT-12");
        assertThat(response.getBody()).containsEntry("message",
                "Tài khoản đang bị khóa, cần mở khóa trước khi vô hiệu hóa (BR-QT-12)");
        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo(status);
        assertThat(updatedAt("accounts", "id", vet)).isEqualTo(updated);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void lastActiveManagerOfActiveBranchCannotBeDisabledButSecondCan() {
        long manager1 = staff("BRANCH_MANAGER", branchA);
        String manager1Token = token(manager1);

        assertThat(disable(token(superManager), manager1).getBody()).containsEntry("message",
                "Không vô hiệu hóa được: đây là quản lý cuối cùng của chi nhánh đang hoạt động (BR-QT-04)");
        assertThat(get("/api/me", manager1Token).getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(visitQueries, cashierShifts);

        staff("BRANCH_MANAGER", branchA);
        ok(disable(token(superManager), manager1));
        assertNoActiveBranchWithoutManager(branchA);
    }

    @ParameterizedTest(name = "visits={0} shift={1}")
    @CsvSource(delimiter = '|', value = {
            "12,15 | false | Không vô hiệu hóa được: còn lượt tiếp nhận #12, #15 chưa hoàn tất (BR-QT-08)",
            "      | true  | Không vô hiệu hóa được: còn ca thu ngân đang mở (BR-QT-08)",
            "12    | true  | Không vô hiệu hóa được: còn lượt tiếp nhận #12 chưa hoàn tất; còn ca thu ngân đang mở (BR-QT-08)"})
    void pendingWorkIsBrQt08ListingEverythingAndWritesNothing(String visitIds, boolean openShift, String message) {
        long receptionist = staff("RECEPTIONIST", branchA);
        String receptionistToken = token(receptionist);
        List<AssignedVisit> visits = visitIds == null ? List.of()
                : java.util.Arrays.stream(visitIds.split(",")).map(Long::valueOf).map(id -> waitingVisit(id)).toList();
        doReturn(visits).when(visitQueries).findUnfinishedVisitsAssignedTo(receptionist);
        doReturn(openShift(openShift)).when(cashierShifts).findOpenShift(receptionist);
        Timestamp updated = updatedAt("accounts", "id", receptionist);

        ResponseEntity<Map<String, Object>> response = disable(token(superManager), receptionist);

        assertRule(response, "BR-QT-08");
        assertThat(response.getBody()).containsEntry("message", message);
        assertThat(text("SELECT status FROM accounts WHERE id = ?", receptionist)).isEqualTo("ACTIVE");
        assertThat(updatedAt("accounts", "id", receptionist)).isEqualTo(updated);
        assertThat(get("/api/me", receptionistToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits(superManager, "STAFF_DISABLED")).isEmpty();
        assertNotLocked(receptionist, branchA);
    }

    /** Q1: bấm lần hai → 409 từ FSM, message tên nghiệp vụ; không hỏi BR-QT-08 lần nữa. */
    @Test
    void disablingTwiceIs409WithBusinessNameAndOneAudit() {
        long vet = staff("VET", branchA);
        ok(disable(token(superManager), vet));
        org.mockito.Mockito.clearInvocations(visitQueries, cashierShifts);

        ResponseEntity<Map<String, Object>> again = disable(token(superManager), vet);

        assertError(again, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(again.getBody()).containsEntry("message",
                "Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED");
        assertThat(audits(superManager, "STAFF_DISABLED")).hasSize(1);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void wrongRoleIs403AccessDeniedWithoutAudit() {
        long vet = staff("VET", branchA);
        long receptionist = staff("RECEPTIONIST", branchA);
        long caretaker = staff("CARETAKER", branchA);
        long customer = customer();

        for (long actor : new long[] {receptionist, caretaker, vet, customer}) {
            assertError(disable(token(actor), vet), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(reactivate(token(actor), vet, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertThat(audits(actor, "STAFF_ACTION_DENIED")).isEmpty();
        }
        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo("ACTIVE");
    }

    /** BR-QT-07, 10: phạm vi theo phân cấp; 403 có audit, message chung (00-method L65), không ghi gì. */
    @Test
    void hierarchyDenialIs403AuditedAndWritesNothing() {
        long managerA = staff("BRANCH_MANAGER", branchA);
        long otherManagerA = staff("BRANCH_MANAGER", branchA);
        long vetB = staff("VET", branchB);
        long vetA = staff("VET", branchA);
        long admin = staff("ADMIN", null);

        for (long target : new long[] {otherManagerA, vetB, managerA}) {
            ResponseEntity<Map<String, Object>> response = disable(token(managerA), target);
            assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
            assertThat(response.getBody()).containsEntry("message", "Không có quyền truy cập dữ liệu này");
        }
        assertError(disable(token(admin), vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(disable(token(superManager), admin), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(disable(token(superManager), superManager), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");

        List<Map<String, Object>> denied = audits(managerA, "STAFF_ACTION_DENIED");
        assertThat(denied).hasSize(3);
        assertThat(json(denied.get(0), "after_data")).contains("\"action\": \"DISABLE\"")
                .contains("\"targetRole\": \"BRANCH_MANAGER\"");
        assertThat(audits(admin, "STAFF_ACTION_DENIED")).hasSize(1);
        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id IN (?, ?, ?, ?, ?) AND status = 'ACTIVE'",
                Long.class, otherManagerA, vetB, managerA, vetA, admin)).isEqualTo(5L);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    /** docs/adr/0029 mục 2: audit 403 ghi khi transaction nghiệp vụ đã rollback. */
    @Test
    void hierarchyDenialAuditIsWrittenAfterRollback() {
        long admin = staff("ADMIN", null);
        String actorEmail = email(superManager);
        withTrigger("audit_logs", "INSERT", """
                NEW.actor_email = '%s' AND EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND state LIKE 'idle in transaction%%')""".formatted(actorEmail), () -> {
                    assertError(disable(token(superManager), admin), HttpStatus.FORBIDDEN,
                            "ACCESS_DENIED_SCOPE_MISMATCH");
                    assertError(reactivate(token(superManager), admin, null), HttpStatus.FORBIDDEN,
                            "ACCESS_DENIED_SCOPE_MISMATCH");
                });

        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).hasSize(2);
    }

    @Test
    void missingOrCustomerIs404WithSameMessage() {
        long customer = customer();

        for (long id : new long[] {customer, Long.MAX_VALUE}) {
            ResponseEntity<Map<String, Object>> disabled = disable(token(superManager), id);
            assertError(disabled, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            assertThat(disabled.getBody()).containsEntry("message", "Không tìm thấy nhân viên #" + id);
            ResponseEntity<Map<String, Object>> reactivated = reactivate(token(superManager), id, null);
            assertError(reactivated, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            assertThat(reactivated.getBody()).containsEntry("message", "Không tìm thấy nhân viên #" + id);
        }
        assertThat(text("SELECT status FROM accounts WHERE id = ?", customer)).isEqualTo("ACTIVE");
    }

    @Test
    void withoutTokenIs401AndActorMustChangePasswordIsBlocked() {
        long vet = staff("VET", branchA);
        assertError(disable(null, vet), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(reactivate(null, vet, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", superManager);
        assertRule(disable(token(superManager), vet), "BR-TK-17");
        assertRule(reactivate(token(superManager), vet, null), "BR-TK-17");
        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo("ACTIVE");
    }

    @Test
    void disableIgnoresAnyBodyAndBadPathIs400() {
        long vet = staff("VET", branchA);

        assertThat(map(ok(raw("/api/staff/" + vet + "/disable", token(superManager), "{\"status\":\"ACTIVE\"}",
                MediaType.APPLICATION_JSON)))).containsEntry("status", "DISABLED");
        assertError(raw("/api/staff/abc/disable", token(superManager), null, MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    // ================================================================ #21 kích hoạt lại — thành công

    @Test
    void reactivateIssuesNewTemporaryPasswordForcesChangeRevokesSessionsAndAudits(CapturedOutput output) {
        long vet = staffWithPassword("VET", branchA);
        jdbc.update("""
                UPDATE accounts SET status = 'DISABLED', must_change_password = false, failed_login_count = 3,
                       first_failed_login_at = now(), locked_until = now() + interval '10 minutes',
                       last_seen_at = now() WHERE id = ?""", vet);
        String staleToken = token(vet);

        ResponseEntity<Map<String, Object>> response = reactivate(token(superManager), vet, null);
        Map<String, Object> data = map(ok(response));

        assertThat(response.getBody()).containsEntry("message", "Đã kích hoạt lại nhân viên");
        assertThat(data).containsOnlyKeys(STAFF_KEYS).containsEntry("status", "ACTIVE")
                .containsEntry("mustChangePassword", true).containsEntry("online", false)
                .containsEntry("branchId", (int) branchA);
        assertThat(map(ok(get("/api/staff/" + vet, token(superManager))))).isEqualTo(data);
        assertThat(jdbc.queryForMap("""
                SELECT failed_login_count, first_failed_login_at, locked_until, last_seen_at FROM accounts WHERE id = ?""",
                vet)).containsEntry("failed_login_count", 0).containsEntry("first_failed_login_at", null)
                .containsEntry("locked_until", null).containsEntry("last_seen_at", null);
        assertError(get("/api/me", staleToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        List<Map<String, Object>> mails = jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id, payload::text AS payload FROM notification_outbox
                WHERE recipient_email = ?""", email(vet));
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)).containsEntry("template_code", "STAFF_TEMP_PASSWORD").containsEntry("channel", "EMAIL")
                .containsEntry("recipient_account_id", null);
        String temp = tempPassword(email(vet));
        assertThat(temp).isNotBlank();
        assertThat(passwordEncoder.matches(temp, text("SELECT password_hash FROM accounts WHERE id = ?", vet)))
                .isTrue();

        List<Map<String, Object>> audits = audits(superManager, "STAFF_REACTIVATED");
        assertThat(audits).hasSize(1);
        assertThat(json(audits.get(0), "before_data"))
                .isEqualTo("{\"status\": \"DISABLED\", \"branchId\": " + branchA + "}");
        assertThat(json(audits.get(0), "after_data")).isEqualTo("{\"status\": \"ACTIVE\", \"branchId\": " + branchA + "}");

        assertThat(response.getBody().toString()).doesNotContain(temp);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND "
                + "(before_data::text LIKE ? OR after_data::text LIKE ?)", Long.class, vet, "%" + temp + "%",
                "%" + temp + "%")).isZero();
        assertThat(output.getAll()).doesNotContain(temp);

        assertError(login(email(vet), PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        Map<String, Object> login = map(ok(login(email(vet), temp)));
        assertThat(map(login.get("account"))).containsEntry("mustChangePassword", true)
                .containsEntry("status", "ACTIVE");
        assertRule(get("/api/staff/" + vet, (String) login.get("accessToken")), "BR-TK-17");
        assertNotLocked(vet, branchA);
    }

    /** docs/adr/0029 mục 7: BCrypt của mật khẩu tạm chạy khi request không giữ connection nào. */
    @Test
    void temporaryPasswordIsHashedWithoutHoldingConnection() {
        long vet = disabledStaff("VET", branchA);
        HikariDataSource hikari = hikari();
        List<Integer> active = new ArrayList<>();
        doAnswer(invocation -> {
            synchronized (active) {
                active.add(hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        ok(reactivate(token(superManager), vet, null));

        assertThat(active).hasSize(1).containsOnly(0);
    }

    @Test
    void reactivateWithBranchMovesStaffAndPublicVetsFollows() {
        long vet = disabledStaff("VET", branchA);
        long draft = branch("DRAFT");

        assertThat(map(ok(reactivate(token(superManager), vet, draft)))).containsEntry("branchId", (int) draft)
                .containsEntry("status", "ACTIVE");
        assertThat(publicVetIds()).as("chi nhánh DRAFT chưa công khai").doesNotContain(vet);
        assertThat(json(audits(superManager, "STAFF_REACTIVATED").get(0), "after_data"))
                .isEqualTo("{\"status\": \"ACTIVE\", \"branchId\": " + draft + "}");

        long vet2 = disabledStaff("VET", branchA);
        ok(reactivate(token(superManager), vet2, branchB));
        assertThat(publicVetIds()).contains(vet2);
    }

    @Test
    void reactivatedManagerCountsAgainForActivation() {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = disabledStaff("BRANCH_MANAGER", draft);
        assertRule(activate(draft), "BR-QT-04");

        ok(reactivate(token(superManager), manager, null));

        ok(activate(draft));
    }

    @Test
    void branchManagerReactivatesOwnSubordinateButNotIntoAnotherBranch() {
        long manager = staff("BRANCH_MANAGER", branchA);
        long caretaker = disabledStaff("CARETAKER", branchA);

        assertError(reactivate(token(manager), caretaker, branchB), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(json(audits(manager, "STAFF_ACTION_DENIED").get(0), "after_data"))
                .contains("\"action\": \"REACTIVATE\"").contains("\"requestedBranchId\": " + branchB);
        assertThat(text("SELECT status FROM accounts WHERE id = ?", caretaker)).isEqualTo("DISABLED");

        assertThat(map(ok(reactivate(token(manager), caretaker, branchA)))).containsEntry("status", "ACTIVE")
                .containsEntry("branchId", (int) branchA);
    }

    @Test
    void emptyOrMissingBodyKeepsBranchAndUnknownFieldsAreIgnored() {
        long vet1 = disabledStaff("VET", branchA);
        long vet2 = disabledStaff("VET", branchA);
        long vet3 = disabledStaff("VET", branchA);
        String token = token(superManager);

        assertThat(map(ok(raw("/api/staff/" + vet1 + "/reactivate", token, null, MediaType.APPLICATION_JSON))))
                .containsEntry("branchId", (int) branchA);
        assertThat(map(ok(raw("/api/staff/" + vet2 + "/reactivate", token, "{}", MediaType.APPLICATION_JSON))))
                .containsEntry("branchId", (int) branchA);
        assertThat(map(ok(raw("/api/staff/" + vet3 + "/reactivate", token,
                "{\"role\":\"BRANCH_MANAGER\",\"status\":\"DISABLED\"}", MediaType.APPLICATION_JSON))))
                .containsEntry("role", "VET").containsEntry("status", "ACTIVE");
    }

    // ================================================================ #21 — lỗi

    @Test
    void reactivateActiveIs409AndLockedIsBrQt10() {
        long vet = staff("VET", branchA);
        ResponseEntity<Map<String, Object>> active = reactivate(token(superManager), vet, null);
        assertError(active, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(active.getBody()).containsEntry("message",
                "Không thể chuyển trạng thái tài khoản từ ACTIVE sang ACTIVE");
        assertThat(outboxCount(email(vet))).isZero();

        long locked = disabledStaff("VET", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        ResponseEntity<Map<String, Object>> response = reactivate(token(superManager), locked, null);
        assertRule(response, "BR-QT-10");
        assertThat(response.getBody()).containsEntry("message", "Tài khoản đang bị khóa, cần mở khóa trước (BR-QT-10)");
        assertThat(text("SELECT status FROM accounts WHERE id = ?", locked)).isEqualTo("DISABLED");
        assertThat(outboxCount(email(locked))).isZero();
        assertThat(audits(superManager, "STAFF_REACTIVATED")).isEmpty();
    }

    @Test
    void reactivateBranchRulesAndBodyShape() {
        long vet = disabledStaff("VET", branchA);
        long admin = staff("ADMIN", null);
        long chainManager = staff("SUPER_MANAGER", null);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", chainManager);
        String token = token(superManager);
        String path = "/api/staff/" + vet + "/reactivate";

        assertThat(reactivate(token, vet, Long.MAX_VALUE).getBody()).containsEntry("message",
                "Chi nhánh không tồn tại (BR-QT-03)");
        assertThat(reactivate(token(admin), chainManager, branchA).getBody()).containsEntry("message",
                "Quản lý cấp chuỗi không thuộc chi nhánh nào (BR-QT-03)");
        for (String body : new String[] {"{\"branchId\":0}", "{\"branchId\":-1}"}) {
            ResponseEntity<Map<String, Object>> response = raw(path, token, body, MediaType.APPLICATION_JSON);
            assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertThat((String) response.getBody().get("message")).startsWith("branchId: ");
        }
        for (String body : new String[] {"{\"branchId\":" + branchB + ".9}", "{\"branchId\":" + branchB + ".0}",
                "{\"branchId\":\"abc\"}", "{\"branchId\":"}) {
            assertError(raw(path, token, body, MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                    "MALFORMED_REQUEST");
        }
        assertError(raw(path, token, "x", MediaType.TEXT_PLAIN), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE");
        assertThat(jdbc.queryForMap("""
                SELECT a.status, sp.branch_id FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id
                WHERE a.id = ?""", vet)).containsEntry("status", "DISABLED").containsEntry("branch_id", branchA);
        assertThat(outboxCount(email(vet))).isZero();
    }

    @Test
    void resendInitialPasswordFollowsStatus() {
        long vet = disabledStaff("VET", branchA);
        assertError(post("/api/staff/" + vet + "/resend-initial-password", token(superManager), null),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        ok(reactivate(token(superManager), vet, null));

        assertThat(post("/api/staff/" + vet + "/resend-initial-password", token(superManager), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ================================================================ commit / rollback

    @Test
    void auditFailureRollsBackDisableIncludingSessionRevoke() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        Timestamp updated = updatedAt("accounts", "id", vet);

        withTrigger("audit_logs", "INSERT", "NEW.action = 'STAFF_DISABLED' AND NEW.entity_id = " + vet,
                () -> assertError(disable(token(superManager), vet), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo("ACTIVE");
        assertThat(updatedAt("accounts", "id", vet)).isEqualTo(updated);
        assertThat(get("/api/me", vetToken).getStatusCode()).as("UPDATE sessions đã chạy cũng rollback")
                .isEqualTo(HttpStatus.OK);
        assertNotLocked(vet, branchA);
    }

    @Test
    void sessionRevokeFailureRollsBackStatusAlreadyFlushed() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);

        withTrigger("sessions", "UPDATE", "NEW.account_id = " + vet,
                () -> assertError(disable(token(superManager), vet), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo("ACTIVE");
        assertThat(get("/api/me", vetToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits(superManager, "STAFF_DISABLED")).isEmpty();
    }

    @Test
    void outboxFailureRollsBackReactivation() {
        long vet = disabledStaff("VET", branchA);
        String hash = text("SELECT password_hash FROM accounts WHERE id = ?", vet);

        withTrigger("notification_outbox", "INSERT", "NEW.recipient_email = '" + email(vet) + "'",
                () -> assertError(reactivate(token(superManager), vet, branchB), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(jdbc.queryForMap("""
                SELECT a.status, a.password_hash, a.must_change_password, sp.branch_id
                FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id WHERE a.id = ?""", vet))
                .containsEntry("status", "DISABLED").containsEntry("password_hash", hash)
                .containsEntry("must_change_password", false).containsEntry("branch_id", branchA);
        assertThat(audits(superManager, "STAFF_REACTIVATED")).isEmpty();
    }

    @Test
    void profileUpdateFailureRollsBackReactivationWithoutMail() {
        long vet = disabledStaff("VET", branchA);

        withTrigger("staff_profiles", "UPDATE", "NEW.account_id = " + vet,
                () -> assertError(reactivate(token(superManager), vet, branchB), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(text("SELECT status FROM accounts WHERE id = ?", vet)).isEqualTo("DISABLED");
        assertThat(outboxCount(email(vet))).isZero();
    }

    // ================================================================ lock + race (docs/adr/0033 L5)

    /** R1: kích hoạt chi nhánh DRAFT trước → vô hiệu hóa BM duy nhất bị BR-QT-04 (trả nợ D014). */
    @Test
    void activationFirstThenDisableOfOnlyManagerIsRejected() throws Exception {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(draft),
                List.of(() -> activate(draft), () -> disable(token, manager)));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertNoActiveBranchWithoutManager(draft);
    }

    /** R1 chiều ngược lại: vô hiệu hóa trước → kích hoạt đếm 0 quản lý → BR-QT-04. */
    @Test
    void disableOfOnlyManagerFirstThenActivationIsRejected() throws Exception {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(draft),
                List.of(() -> disable(token, manager), () -> activate(draft)));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertThat(text("SELECT status FROM branches WHERE id = ?", draft)).isEqualTo("DRAFT");
        assertNoActiveBranchWithoutManager(draft);
    }

    /** R2–R4: hai thao tác làm giảm số BM của cùng chi nhánh ACTIVE (2 BM) → đúng một thành công. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"disable", "change-role", "transfer"})
    void disableRacingAnotherManagerReductionOnlyOneSucceeds(String other) throws Exception {
        long manager1 = staff("BRANCH_MANAGER", branchA);
        long manager2 = staff("BRANCH_MANAGER", branchA);
        String token = token(superManager);
        Callable<ResponseEntity<Map<String, Object>>> second = switch (other) {
            case "disable" -> () -> disable(token, manager2);
            case "change-role" -> () -> post("/api/staff/" + manager2 + "/change-role", token, Map.of("role", "VET"));
            default -> () -> post("/api/staff/" + manager2 + "/transfer", token, Map.of("branchId", branchB));
        };

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA),
                List.of(() -> disable(token, manager1), second));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertThat(activeManagers(branchA)).isEqualTo(1L);
        assertNoActiveBranchWithoutManager(branchA);
    }

    /** R5: hai lần vô hiệu hóa cùng người chạy song song → 200 + 409, một audit. */
    @Test
    void concurrentDoubleDisableIsOneSuccessAndOneConflict() throws Exception {
        long vet = staff("VET", branchA);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet,
                List.of(() -> disable(token, vet), () -> disable(token, vet)));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(audits(superManager, "STAFF_DISABLED")).hasSize(1);
        assertDisabledInvariant(vet);
    }

    /** R6: đăng nhập đi trước vô hiệu hóa → phiên vừa mở bị hủy; vô hiệu hóa đi trước → đăng nhập 400 BR-TK-11. */
    @Test
    void loginRacingDisableNeverLeavesALiveSession() throws Exception {
        long first = staffWithPassword("VET", branchA);
        String token = token(superManager);
        List<ResponseEntity<Map<String, Object>>> loginFirst = whileAccountLockedMany(first,
                List.of(() -> login(email(first), PASSWORD), () -> disable(token, first)));
        String issued = (String) map(ok(loginFirst.get(0))).get("accessToken");
        ok(loginFirst.get(1));
        assertError(get("/api/me", issued), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertDisabledInvariant(first);

        long second = staffWithPassword("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> disableFirst = whileAccountLockedMany(second,
                List.of(() -> disable(token, second), () -> login(email(second), PASSWORD)));
        ok(disableFirst.get(0));
        assertRule(disableFirst.get(1), "BR-TK-11");
        assertDisabledInvariant(second);
    }

    /** R7: điều chuyển xong trong lúc vô hiệu hóa chờ khóa → vô hiệu hóa thấy chi nhánh đổi → 409, không đếm nhầm. */
    @Test
    void transferWhileDisableWaitsIs409() throws Exception {
        long manager = staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchA);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA), List.of(
                () -> post("/api/staff/" + manager + "/transfer", token, Map.of("branchId", branchB)),
                () -> disable(token, manager)));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(text("SELECT status FROM accounts WHERE id = ?", manager)).isEqualTo("ACTIVE");
        assertNotLocked(manager, branchA, branchB);
    }

    /**
     * R8: đăng xuất và vô hiệu hóa cùng khóa {@code accounts → sessions} → không deadlock. {@code last_seen_at} có giá trị
     * để {@code clearLastSeen} ({@code WHERE last_seen_at IS NOT NULL}) thực sự phải chờ dòng đang bị khóa.
     */
    @Test
    void logoutAndDisableDoNotDeadlock() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        String token = token(superManager);
        jdbc.update("UPDATE accounts SET last_seen_at = now() WHERE id = ?", vet);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> post("/api/auth/logout", vetToken, null), () -> disable(token, vet)));

        assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ok(results.get(1));
        assertDisabledInvariant(vet);
    }

    /** R9: hai lần kích hoạt lại song song → 200 + 409, đúng một email mật khẩu. */
    @Test
    void concurrentDoubleReactivateSendsExactlyOnePassword() throws Exception {
        long vet = disabledStaff("VET", branchA);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet,
                List.of(() -> reactivate(token, vet, null), () -> reactivate(token, vet, null)));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(outboxCount(email(vet))).isEqualTo(1L);
        assertThat(passwordEncoder.matches(tempPassword(email(vet)),
                text("SELECT password_hash FROM accounts WHERE id = ?", vet))).isTrue();
    }

    /** R10: kích hoạt lại (không khóa chi nhánh) và điều chuyển người DISABLED, cả hai thứ tự → không deadlock. */
    @Test
    void reactivateAndTransferOfDisabledStaffDoNotDeadlock() throws Exception {
        String token = token(superManager);
        long vet1 = disabledStaff("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> reactivateFirst = whileAccountLockedMany(vet1, List.of(
                () -> reactivate(token, vet1, null),
                () -> post("/api/staff/" + vet1 + "/transfer", token, Map.of("branchId", branchB))));
        ok(reactivateFirst.get(0));
        ok(reactivateFirst.get(1));
        assertThat(jdbc.queryForMap("""
                SELECT a.status, sp.branch_id FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id
                WHERE a.id = ?""", vet1)).containsEntry("status", "ACTIVE").containsEntry("branch_id", branchB);

        long vet2 = disabledStaff("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> transferFirst = whileAccountLockedMany(vet2, List.of(
                () -> post("/api/staff/" + vet2 + "/transfer", token, Map.of("branchId", branchB)),
                () -> reactivate(token, vet2, null)));
        ok(transferFirst.get(0));
        assertThat(map(ok(transferFirst.get(1)))).as("đọc chi nhánh mới dưới khóa").containsEntry("branchId",
                (int) branchB);
        assertNotLocked(vet2, branchA, branchB);
    }

    // ================================================================ helpers — dữ liệu

    private long branch(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, "StaffDisableReactivateIT " + UUID.randomUUID(), status);
    }

    private void hours(long branchId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2026-10-01', ?, ?, ?)
                    """, branchId, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        }
    }

    private long staff(String role, Long branchId) {
        return staffWithHash(role, branchId, "hash");
    }

    /** Nhân viên có mật khẩu {@link #PASSWORD} thật (BCrypt) để đăng nhập qua HTTP. */
    private long staffWithPassword(String role, Long branchId) {
        return staffWithHash(role, branchId, passwordEncoder.encode(PASSWORD));
    }

    private long disabledStaff(String role, Long branchId) {
        long id = staffWithPassword(role, branchId);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", id);
        return id;
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

    private long customer() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
    }

    private static String newEmail() {
        return "disable-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String email(long accountId) {
        return text("SELECT email FROM accounts WHERE id = ?", accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "StaffDisableReactivateIT"))
                .accessToken();
    }

    private String text(String sql, long id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private Timestamp updatedAt(String table, String idColumn, long id) {
        return jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE " + idColumn + " = ?",
                Timestamp.class, id);
    }

    private List<Map<String, Object>> audits(long actorId, String action) {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE actor_account_id = ? AND action = ? ORDER BY id""", actorId, action);
    }

    private long auditsOn(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?",
                Long.class, accountId);
    }

    private long outboxCount(String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'STAFF_TEMP_PASSWORD'""", Long.class, email);
    }

    /** Mật khẩu tạm trong email mới nhất (payload outbox trước khi ST20 gửi — job tắt ở profile test). */
    private String tempPassword(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'mat_khau_tam' FROM notification_outbox
                WHERE recipient_email = ? ORDER BY id DESC LIMIT 1""", String.class, email);
    }

    private static String json(Map<String, Object> row, String column) {
        return (String) row.get(column);
    }

    @SuppressWarnings("unchecked")
    private List<Long> publicVetIds() {
        return ((List<Map<String, Object>>) http.exchange("/api/public/vets", HttpMethod.GET, null, MAP).getBody()
                .get("data")).stream().map(row -> number(row.get("accountId"))).toList();
    }

    private long activeManagers(long branchId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles sp JOIN accounts a ON a.id = sp.account_id
                WHERE sp.branch_id = ? AND a.role = 'BRANCH_MANAGER' AND a.status = 'ACTIVE'""", Long.class, branchId);
    }

    /** I1 (docs/adr/0033 C4): DISABLED ⇒ không còn phiên chưa hủy và offline. */
    private void assertDisabledInvariant(long accountId) {
        assertThat(jdbc.queryForMap("SELECT status, last_seen_at FROM accounts WHERE id = ?", accountId))
                .containsEntry("status", "DISABLED").containsEntry("last_seen_at", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId)).isZero();
    }

    /** Invariant BR-QT-04 / D014: chi nhánh ACTIVE không bao giờ có 0 BRANCH_MANAGER ACTIVE. */
    private void assertNoActiveBranchWithoutManager(long branchId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM branches b
                WHERE b.id = ? AND b.status = 'ACTIVE' AND NOT EXISTS (
                    SELECT 1 FROM staff_profiles sp JOIN accounts a ON a.id = sp.account_id
                    WHERE sp.branch_id = b.id AND a.role = 'BRANCH_MANAGER' AND a.status = 'ACTIVE')
                """, Long.class, branchId)).isZero();
    }

    /** Không còn khóa sót: một transaction khác lấy được khóa ngay ({@code NOWAIT}) trên mọi dòng liên quan. */
    private void assertNotLocked(long accountId, long... branchIds) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, accountId);
            for (long branchId : branchIds) {
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR UPDATE NOWAIT", Long.class, branchId);
            }
        });
    }

    /** Trigger tạm làm {@code operation} trên {@code table} lỗi khi {@code condition} đúng (khuôn {@code StaffAdminIT}). */
    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_disable_fail_" + suffix;
        String trigger = "trg_it_disable_fail_" + suffix;
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

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ================================================================ helpers — đồng thời

    /**
     * Một transaction giữ khóa {@code branches} của {@code branchIds} ({@code FOR UPDATE}); các request được bắn lần
     * lượt, mỗi request phải đứng chờ khóa (thấy trong {@code pg_stat_activity}) trước khi bắn request kế tiếp, nên thứ
     * tự nhận khóa sau khi thả = thứ tự trong danh sách.
     */
    private List<ResponseEntity<Map<String, Object>>> whileBranchesLocked(Collection<Long> branchIds,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM branches WHERE id = ? FOR UPDATE", branchIds, WAITING_ON_BRANCH_LOCK,
                requests);
    }

    /** Như trên với dòng {@code accounts}: mọi request (đăng nhập, đăng xuất, quản trị) xếp hàng trên cùng dòng. */
    private List<ResponseEntity<Map<String, Object>>> whileAccountLockedMany(long accountId,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM accounts WHERE id = ? FOR UPDATE", List.of(accountId), WAITING_ON_ANY_LOCK,
                requests);
    }

    private List<ResponseEntity<Map<String, Object>>> whileLocked(String lockSql, Collection<Long> ids,
            String waitingSql, List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                for (Long id : ids) {
                    jdbc.queryForObject(lockSql, Long.class, id);
                }
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(waitingSql, calls.size());
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

    private void awaitWaiting(String sql, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(sql, Long.class) < expected) {
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

    private ResponseEntity<Map<String, Object>> disable(String token, long id) {
        return post("/api/staff/" + id + "/disable", token, null);
    }

    private ResponseEntity<Map<String, Object>> reactivate(String token, long id, Long branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", branchId);
        return post("/api/staff/" + id + "/reactivate", token, body);
    }

    private ResponseEntity<Map<String, Object>> activate(long branchId) {
        return post("/api/branches/" + branchId + "/activate", token(superManager), null);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/api/auth/login", null, Map.of("email", email, "password", password));
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String path, String token, String body, MediaType type) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(type);
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

    private static long number(Object value) {
        return ((Number) value).longValue();
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

    /** Stub của {@code VisitQueryApi.findUnfinishedVisitsAssignedTo}: BR-QT-08 chỉ cần id của lượt. */
    private static AssignedVisit waitingVisit(long visitId) {
        return new AssignedVisit(visitId, 1L, VisitStatus.WAITING);
    }

    /** Stub của {@code CashierShiftQueryApi.findOpenShift}: BR-QT-08 chỉ cần có hay không có ca. */
    private static Optional<OpenShift> openShift(boolean open) {
        return open ? Optional.of(new OpenShift(77L, 1L)) : Optional.empty();
    }
}
