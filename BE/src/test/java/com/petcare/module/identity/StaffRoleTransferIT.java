package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;

/**
 * T14 phần 2 — {@code POST /api/staff/{id}/change-role} (#18), {@code POST /api/staff/{id}/transfer} (#19) qua HTTP
 * trên Postgres 17 thật (identity-v1; BR-QT-03, 04, 05, 06, 08, 15; docs/adr/0029, 0031, 0032). {@code VisitQueryApi},
 * {@code CashierShiftQueryApi} là {@code @MockitoBean} (placeholder tới 21/10, nợ D016; bản placeholder thật được chạy
 * ở {@code StaffTransferPlaceholderIT}); mọi module khác thật, kể cả {@code BranchService.activateBranch} ở các ca
 * tranh chấp. Mỗi test tự tạo chi nhánh / tài khoản riêng; audit tra theo actor / entity riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StaffRoleTransferIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> STAFF_KEYS = Set.of("accountId", "email", "phone", "fullName", "role", "status",
            "isLocked", "branchId", "specialty", "bio", "mustChangePassword", "online");
    private static final String WAITING_ON_ACCOUNT_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
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
        tag = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch("ACTIVE");
        branchB = branch("ACTIVE");
        superManager = staff("SUPER_MANAGER", null);
        doReturn(List.of()).when(visitQueries).findUnfinishedVisitsAssignedTo(anyLong());
        doReturn(Optional.empty()).when(cashierShifts).findOpenShift(anyLong());
    }

    // ================================================================ #18 đổi chức vụ — thành công

    @Test
    void changeRoleReturnsContractShapeEqualToGetAuditsAndKeepsSessions() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);

        Map<String, Object> data = map(ok(changeRole(token(superManager), vet, "RECEPTIONIST")));

        assertThat(data).containsOnlyKeys(STAFF_KEYS).containsEntry("role", "RECEPTIONIST")
                .containsEntry("branchId", (int) branchA);
        assertThat(map(ok(get("/api/staff/" + vet, token(superManager))))).isEqualTo(data);
        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("RECEPTIONIST");
        List<Map<String, Object>> audits = audits(superManager, "STAFF_ROLE_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts").containsEntry("entity_id", vet);
        assertThat(json(audits.get(0), "before_data")).isEqualTo("{\"bio\": null, \"role\": \"VET\", \"specialty\": null}");
        assertThat(json(audits.get(0), "after_data"))
                .isEqualTo("{\"bio\": null, \"role\": \"RECEPTIONIST\", \"specialty\": null}");
        assertThat(get("/api/me", vetToken).getStatusCode()).as("không hủy phiên").isEqualTo(HttpStatus.OK);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void nextRequestOfTargetUsesNewRoleAndBranch() {
        long manager = staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchB);
        String managerToken = token(manager);
        assertThat(get("/api/branches/" + branchA + "/holidays", managerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ok(transfer(token(superManager), manager, branchB));

        assertThat(get("/api/branches/" + branchA + "/holidays", managerToken).getStatusCode())
                .as("chi nhánh cũ ngoài phạm vi (BranchScope)").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/branches/" + branchB + "/holidays", managerToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> me = map(ok(get("/api/me", managerToken)));
        assertThat(map(me.get("staffProfile"))).containsEntry("branchId", (int) branchB);

        ok(changeRole(token(superManager), manager, "VET"));
        Map<String, Object> meAfter = map(ok(get("/api/me", managerToken)));
        assertThat(map(meAfter.get("account"))).containsEntry("role", "VET");
        assertThat(post("/api/staff", managerToken, Map.of()).getStatusCode())
                .as("VET không còn quyền quản trị nhân viên").isIn(HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST);
    }

    @Test
    void eachFieldAfterChangeComesFromItsColumnAndOnlyAllowedFieldsChange() {
        long vet = staff("VET", branchA);
        jdbc.update("""
                UPDATE accounts SET status = 'DISABLED', is_locked = true, locked_reason = 'IT',
                       must_change_password = true, last_seen_at = now(), phone = '0911111111'
                WHERE id = ?""", vet);
        jdbc.update("UPDATE staff_profiles SET avatar_url = 'https://img.test/a.png' WHERE account_id = ?", vet);
        Map<String, Object> before = map(ok(get("/api/staff/" + vet, token(superManager))));

        Map<String, Object> afterTransfer = map(ok(transfer(token(superManager), vet, branchB)));
        Map<String, Object> expected = new HashMap<>(before);
        expected.put("branchId", (int) branchB);
        assertThat(afterTransfer).isEqualTo(expected);

        Map<String, Object> afterRole = map(ok(changeRole(token(superManager), vet, "CARETAKER")));
        expected.put("role", "CARETAKER");
        assertThat(afterRole).isEqualTo(expected);
        assertThat(afterRole).containsEntry("status", "DISABLED").containsEntry("isLocked", true)
                .containsEntry("online", true).containsEntry("mustChangePassword", true);
        assertThat(text("SELECT avatar_url FROM staff_profiles WHERE account_id = ?", vet))
                .isEqualTo("https://img.test/a.png");
    }

    @Test
    void vetLeavingClearsSpecialtyBioAndDisappearsFromPublicVets() {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE staff_profiles SET specialty = 'Nội khoa', bio = 'Mô tả' WHERE account_id = ?", vet);
        assertThat(publicVetIds()).contains(vet);

        Map<String, Object> data = map(ok(changeRole(token(superManager), vet, "RECEPTIONIST")));

        assertThat(data).containsEntry("specialty", null).containsEntry("bio", null);
        assertThat(jdbc.queryForMap("SELECT specialty, bio FROM staff_profiles WHERE account_id = ?", vet))
                .containsEntry("specialty", null).containsEntry("bio", null);
        assertThat(json(audits(superManager, "STAFF_ROLE_CHANGED").get(0), "before_data"))
                .isEqualTo("{\"bio\": \"Mô tả\", \"role\": \"VET\", \"specialty\": \"Nội khoa\"}");
        assertThat(publicVetIds()).doesNotContain(vet);
    }

    @Test
    void vetTransferKeepsProfileAndPublicVetsShowsNewBranchOnlyWhenActive() {
        long vet = staff("VET", branchA);
        long draft = branch("DRAFT");
        jdbc.update("UPDATE staff_profiles SET specialty = 'Nội khoa', bio = 'Mô tả' WHERE account_id = ?", vet);

        ok(transfer(token(superManager), vet, draft));
        assertThat(publicVetIds()).as("chi nhánh DRAFT không công khai (BR-CK-01)").doesNotContain(vet);

        Map<String, Object> data = map(ok(transfer(token(superManager), vet, branchB)));
        assertThat(data).containsEntry("specialty", "Nội khoa").containsEntry("bio", "Mô tả");
        assertThat(publicVet(vet)).containsEntry("branchId", (int) branchB);
    }

    @Test
    void managerTransferredIntoDraftBranchMakesItActivatable() {
        long draft = branch("DRAFT");
        hours(draft);
        staff("BRANCH_MANAGER", branchA);
        long manager = staff("BRANCH_MANAGER", branchA);
        assertRule(activate(draft), "BR-QT-04");

        ok(transfer(token(superManager), manager, draft));

        assertThat(activate(draft).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text("SELECT status FROM branches WHERE id = ?", draft)).isEqualTo("ACTIVE");
    }

    @Test
    void sameValueIsNoOpWithoutWriteOrAudit() {
        long vet = staff("VET", branchA);
        Timestamp accountUpdated = updatedAt("accounts", "id", vet);
        Timestamp profileUpdated = updatedAt("staff_profiles", "account_id", vet);
        Map<String, Object> current = map(ok(get("/api/staff/" + vet, token(superManager))));

        assertThat(map(ok(changeRole(token(superManager), vet, "VET")))).isEqualTo(current);
        assertThat(map(ok(transfer(token(superManager), vet, branchA)))).isEqualTo(current);

        assertThat(updatedAt("accounts", "id", vet)).isEqualTo(accountUpdated);
        assertThat(updatedAt("staff_profiles", "account_id", vet)).isEqualTo(profileUpdated);
        assertThat(audits(superManager, "STAFF_ROLE_CHANGED")).isEmpty();
        assertThat(audits(superManager, "STAFF_TRANSFERRED")).isEmpty();
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    // ================================================================ #19 điều chuyển — thành công

    @Test
    void transferReturnsContractShapeEqualToGetAuditsAndCallsGuards() {
        long receptionist = staff("RECEPTIONIST", branchA);

        Map<String, Object> data = map(ok(transfer(token(superManager), receptionist, branchB)));

        assertThat(data).containsOnlyKeys(STAFF_KEYS).containsEntry("branchId", (int) branchB)
                .containsEntry("role", "RECEPTIONIST");
        assertThat(map(ok(get("/api/staff/" + receptionist, token(superManager))))).isEqualTo(data);
        List<Map<String, Object>> audits = audits(superManager, "STAFF_TRANSFERRED");
        assertThat(audits).hasSize(1);
        assertThat(json(audits.get(0), "before_data")).isEqualTo("{\"branchId\": " + branchA + "}");
        assertThat(json(audits.get(0), "after_data")).isEqualTo("{\"branchId\": " + branchB + "}");
        Mockito.verify(visitQueries).findUnfinishedVisitsAssignedTo(receptionist);
        Mockito.verify(cashierShifts).findOpenShift(receptionist);
    }

    // ================================================================ Q1, BR-QT-04, 03, 08

    @Test
    void disabledOrLockedTargetCanBeChangedAndTransferred() {
        long disabled = staff("VET", branchA);
        long locked = staff("CARETAKER", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);

        assertThat(map(ok(changeRole(token(superManager), disabled, "RECEPTIONIST"))))
                .containsEntry("status", "DISABLED");
        assertThat(map(ok(transfer(token(superManager), locked, branchB)))).containsEntry("isLocked", true);
    }

    @Test
    void lastActiveManagerOfActiveBranchCannotLeaveButOthersCan() {
        long only = staff("BRANCH_MANAGER", branchA);

        assertRule(changeRole(token(superManager), only, "VET"), "BR-QT-04");
        assertRule(transfer(token(superManager), only, branchB), "BR-QT-04");
        assertThat(text("SELECT role FROM accounts WHERE id = ?", only)).isEqualTo("BRANCH_MANAGER");
        assertThat(number(value("SELECT branch_id FROM staff_profiles WHERE account_id = ?", only)))
                .isEqualTo(branchA);
        verifyNoInteractions(visitQueries, cashierShifts);

        long disabledManager = staff("BRANCH_MANAGER", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabledManager);
        assertRule(changeRole(token(superManager), only, "VET"), "BR-QT-04");
        ok(transfer(token(superManager), disabledManager, branchB));

        long lockedManager = staff("BRANCH_MANAGER", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", lockedManager);
        ok(changeRole(token(superManager), only, "VET"));
        assertThat(text("SELECT role FROM accounts WHERE id = ?", only)).isEqualTo("VET");
    }

    @Test
    void lastManagerOfDraftBranchCanLeave() {
        long draft = branch("DRAFT");
        long manager = staff("BRANCH_MANAGER", draft);

        ok(changeRole(token(superManager), manager, "VET"));
        ok(changeRole(token(superManager), manager, "BRANCH_MANAGER"));
        ok(transfer(token(superManager), manager, branchB));
    }

    @Test
    void unknownBranchIsBrQt03WithoutCallingGuards() {
        long vet = staff("VET", branchA);

        ResponseEntity<Map<String, Object>> response = transfer(token(superManager), vet, Long.MAX_VALUE);

        assertRule(response, "BR-QT-03");
        assertThat(response.getBody()).containsEntry("message", "Chi nhánh không tồn tại (BR-QT-03)");
        verifyNoInteractions(visitQueries, cashierShifts);
        assertThat(audits(superManager, "STAFF_TRANSFERRED")).isEmpty();
    }

    @ParameterizedTest(name = "visits={0} shift={1}")
    @CsvSource(delimiter = '|', value = {
            "12,15 | false | Không điều chuyển được: còn lượt tiếp nhận #12, #15 chưa hoàn tất (BR-QT-08)",
            "      | true  | Không điều chuyển được: còn ca thu ngân đang mở (BR-QT-08)",
            "12    | true  | Không điều chuyển được: còn lượt tiếp nhận #12 chưa hoàn tất; còn ca thu ngân đang mở (BR-QT-08)"})
    void pendingWorkIsBrQt08ListingEverythingAndWritesNothing(String visitIds, boolean openShift, String message) {
        long receptionist = staff("RECEPTIONIST", branchA);
        List<AssignedVisit> visits = new ArrayList<>();
        if (visitIds != null) {
            for (String id : visitIds.split(",")) {
                visits.add(waitingVisit(Long.parseLong(id.strip())));
            }
        }
        doReturn(visits).when(visitQueries).findUnfinishedVisitsAssignedTo(receptionist);
        doReturn(openShift(openShift)).when(cashierShifts).findOpenShift(receptionist);
        Timestamp profileUpdated = updatedAt("staff_profiles", "account_id", receptionist);

        ResponseEntity<Map<String, Object>> response = transfer(token(superManager), receptionist, branchB);

        assertRule(response, "BR-QT-08");
        assertThat(response.getBody()).containsEntry("message", message);
        assertThat(updatedAt("staff_profiles", "account_id", receptionist)).isEqualTo(profileUpdated);
        assertThat(audits(superManager, "STAFF_TRANSFERRED")).isEmpty();
    }

    // ================================================================ 401 / 403 / 404 / 400

    @Test
    void wrongRoleIs403AccessDeniedWithoutAuditButBadBodyIs400First() {
        long vet = staff("VET", branchA);
        long managerA = staff("BRANCH_MANAGER", branchA);
        long admin = staff("ADMIN", null);
        long customer = customer();

        for (long actor : new long[] {managerA, admin, vet, customer}) {
            assertError(changeRole(token(actor), vet, "CARETAKER"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(transfer(token(actor), vet, branchB), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertThat(audits(actor, "STAFF_ACTION_DENIED")).isEmpty();
        }
        assertError(changeRole(token(managerA), vet, "ADMIN"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("VET");
    }

    @Test
    void hierarchyDenialIs403AuditedAndWritesNothing() {
        long otherSuperManager = staff("SUPER_MANAGER", null);
        long admin = staff("ADMIN", null);

        for (long target : new long[] {otherSuperManager, admin, superManager}) {
            assertError(changeRole(token(superManager), target, "VET"), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED_SCOPE_MISMATCH");
            assertError(transfer(token(superManager), target, branchA), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED_SCOPE_MISMATCH");
        }
        ResponseEntity<Map<String, Object>> response = changeRole(token(superManager), admin, "VET");
        assertThat(response.getBody()).containsEntry("message", "Không có quyền truy cập dữ liệu này");

        List<Map<String, Object>> denied = audits(superManager, "STAFF_ACTION_DENIED");
        assertThat(denied).hasSize(7);
        assertThat(json(denied.get(0), "after_data")).isEqualTo("{\"action\": \"CHANGE_ROLE\", \"targetRole\": "
                + "\"SUPER_MANAGER\", \"requestedRole\": \"VET\", \"targetBranchId\": null, \"requestedBranchId\": null}");
        assertThat(json(denied.get(1), "after_data")).contains("\"action\": \"TRANSFER\"")
                .contains("\"requestedBranchId\": " + branchA);
        assertThat(text("SELECT role FROM accounts WHERE id = ?", admin)).isEqualTo("ADMIN");
        assertThat(text("SELECT branch_id FROM staff_profiles WHERE account_id = ?", admin)).isNull();
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    /** docs/adr/0029 mục 2: audit 403 ghi khi transaction nghiệp vụ đã rollback (không còn session mở dở). */
    @Test
    void hierarchyDenialAuditIsWrittenAfterRollback() {
        long admin = staff("ADMIN", null);
        String actorEmail = text("SELECT email FROM accounts WHERE id = ?", superManager);
        withTrigger("audit_logs", "INSERT", """
                NEW.actor_email = '%s' AND EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND state LIKE 'idle in transaction%%')""".formatted(actorEmail), () -> {
                    assertError(changeRole(token(superManager), admin, "VET"), HttpStatus.FORBIDDEN,
                            "ACCESS_DENIED_SCOPE_MISMATCH");
                    assertError(transfer(token(superManager), admin, branchA), HttpStatus.FORBIDDEN,
                            "ACCESS_DENIED_SCOPE_MISMATCH");
                });

        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).hasSize(2);
    }

    @Test
    void deniedAuditFailureStillReturns403() {
        long admin = staff("ADMIN", null);

        withTrigger("audit_logs", "INSERT", "NEW.action = 'STAFF_ACTION_DENIED' AND NEW.entity_id = " + admin,
                () -> assertError(changeRole(token(superManager), admin, "VET"), HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED_SCOPE_MISMATCH"));

        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).isEmpty();
        assertThat(text("SELECT role FROM accounts WHERE id = ?", admin)).isEqualTo("ADMIN");
    }

    @Test
    void missingOrCustomerIs404WithSameMessage() {
        long customer = customer();

        for (long id : new long[] {customer, Long.MAX_VALUE}) {
            ResponseEntity<Map<String, Object>> role = changeRole(token(superManager), id, "VET");
            assertError(role, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            assertThat(role.getBody()).containsEntry("message", "Không tìm thấy nhân viên #" + id);
            assertError(transfer(token(superManager), id, branchA), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
        assertThat(text("SELECT role FROM accounts WHERE id = ?", customer)).isEqualTo("CUSTOMER");
    }

    @Test
    void withoutTokenIs401AndActorMustChangePasswordIsBlocked() {
        long vet = staff("VET", branchA);
        assertError(changeRole(null, vet, "CARETAKER"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(transfer(null, vet, branchB), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", superManager);
        assertRule(changeRole(token(superManager), vet, "CARETAKER"), "BR-TK-17");
        assertRule(transfer(token(superManager), vet, branchB), "BR-TK-17");
        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("VET");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "change-role | {}                        | role",
            "change-role | {\"role\":null}           | role",
            "change-role | {\"role\":\"\"}           | role",
            "change-role | {\"role\":\"SUPER_MANAGER\"} | role",
            "change-role | {\"role\":\"ADMIN\"}      | role",
            "change-role | {\"role\":\"CUSTOMER\"}   | role",
            "change-role | {\"role\":\"vet\"}        | role",
            "change-role | {\"role\":123}            | role",
            "transfer    | {}                        | branchId",
            "transfer    | {\"branchId\":null}       | branchId",
            "transfer    | {\"branchId\":0}          | branchId",
            "transfer    | {\"branchId\":-3}         | branchId"})
    void invalidBodyIs400ValidationOnContractFieldWithoutSideEffects(String endpoint, String body, String field) {
        long vet = staff("VET", branchA);

        ResponseEntity<Map<String, Object>> response = raw("/api/staff/" + vet + "/" + endpoint, token(superManager),
                body, MediaType.APPLICATION_JSON);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) response.getBody().get("message")).startsWith(field + ": ");
        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("VET");
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    /** docs/adr/0032: số thực vào trường số nguyên không bị cắt (1.9 → 1) mà trả 400 — cả #19 lẫn #16 đã có. */
    @Test
    void malformedBodiesFloatsPathAndMediaType() {
        long vet = staff("VET", branchA);
        String token = token(superManager);
        String transferPath = "/api/staff/" + vet + "/transfer";

        for (String body : new String[] {"{\"branchId\":", "", "{\"branchId\":\"abc\"}", "{\"branchId\":true}",
                "{\"branchId\":" + branchB + ".9}", "{\"branchId\":" + branchB + ".0}"}) {
            assertError(raw(transferPath, token, body, MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                    "MALFORMED_REQUEST");
        }
        assertError(raw("/api/staff", token, "{\"email\":\"f-" + UUID.randomUUID() + "@petcare.test\","
                + "\"phone\":\"0901234567\",\"fullName\":\"X\",\"role\":\"VET\",\"branchId\":" + branchA + ".9}",
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw("/api/staff/abc/transfer", token, "{\"branchId\":1}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(transferPath, token, "x", MediaType.TEXT_PLAIN), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE");
        assertThat(number(value("SELECT branch_id FROM staff_profiles WHERE account_id = ?", vet))).isEqualTo(branchA);
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void unknownFieldsAreIgnored() {
        long vet = staff("VET", branchA);

        Map<String, Object> roleBody = new HashMap<>();
        roleBody.put("role", "CARETAKER");
        roleBody.put("branchId", branchB);
        roleBody.put("status", "DISABLED");
        assertThat(map(ok(post("/api/staff/" + vet + "/change-role", token(superManager), roleBody))))
                .containsEntry("role", "CARETAKER").containsEntry("branchId", (int) branchA)
                .containsEntry("status", "ACTIVE");

        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("branchId", branchB);
        transferBody.put("role", "VET");
        assertThat(map(ok(post("/api/staff/" + vet + "/transfer", token(superManager), transferBody))))
                .containsEntry("role", "CARETAKER").containsEntry("branchId", (int) branchB);
    }

    // ================================================================ commit / rollback

    @Test
    void auditFailureRollsBackRoleChangeAndTransfer() {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE staff_profiles SET specialty = 'Nội khoa' WHERE account_id = ?", vet);
        Timestamp accountUpdated = updatedAt("accounts", "id", vet);
        Timestamp profileUpdated = updatedAt("staff_profiles", "account_id", vet);

        withTrigger("audit_logs", "INSERT", "NEW.entity_id = " + vet, () -> {
            assertError(changeRole(token(superManager), vet, "CARETAKER"), HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR");
            assertError(transfer(token(superManager), vet, branchB), HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR");
        });

        assertThat(jdbc.queryForMap("""
                SELECT a.role, sp.branch_id, sp.specialty FROM accounts a
                JOIN staff_profiles sp ON sp.account_id = a.id WHERE a.id = ?""", vet))
                .containsEntry("role", "VET").containsEntry("branch_id", branchA)
                .containsEntry("specialty", "Nội khoa");
        assertThat(updatedAt("accounts", "id", vet)).isEqualTo(accountUpdated);
        assertThat(updatedAt("staff_profiles", "account_id", vet)).isEqualTo(profileUpdated);
    }

    @Test
    void profileUpdateFailureRollsBackRoleAndWritesNoAudit() {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE staff_profiles SET specialty = 'Nội khoa' WHERE account_id = ?", vet);

        withTrigger("staff_profiles", "UPDATE", "NEW.account_id = " + vet,
                () -> assertError(changeRole(token(superManager), vet, "CARETAKER"),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("VET");
        assertThat(audits(superManager, "STAFF_ROLE_CHANGED")).isEmpty();
    }

    @Test
    void rejectedRequestsWriteNothingAndReleaseLocks() {
        long only = staff("BRANCH_MANAGER", branchA);
        long admin = staff("ADMIN", null);

        assertRule(transfer(token(superManager), only, branchB), "BR-QT-04");
        assertNotLocked(only, branchA, branchB);
        assertRule(transfer(token(superManager), only, Long.MAX_VALUE), "BR-QT-03");
        assertNotLocked(only, branchA, branchB);
        assertError(changeRole(token(superManager), admin, "VET"), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertNotLocked(admin, branchA, branchB);
        long vet = staff("VET", branchA);
        doReturn(List.of(waitingVisit(9L))).when(visitQueries).findUnfinishedVisitsAssignedTo(vet);
        assertRule(transfer(token(superManager), vet, branchB), "BR-QT-08");
        assertNotLocked(vet, branchA, branchB);
        assertThat(audits(superManager, "STAFF_TRANSFERRED")).isEmpty();
    }

    @Test
    void successCommitsChangeAndAuditTogetherVisibleFromAnotherConnection() {
        long vet = staff("VET", branchA);

        ok(transfer(token(superManager), vet, branchB));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles sp
                WHERE sp.account_id = ? AND sp.branch_id = ? AND EXISTS (
                    SELECT 1 FROM audit_logs al WHERE al.action = 'STAFF_TRANSFERRED' AND al.entity_id = sp.account_id)
                """, Long.class, vet, branchB)).isEqualTo(1L);
        assertNotLocked(vet, branchA, branchB);
    }

    // ================================================================ lock + race (docs/adr/0031)

    /** R4 (nợ D014): kích hoạt chi nhánh DRAFT đi trước điều chuyển BM duy nhất đi → điều chuyển bị BR-QT-04. */
    @Test
    void activationFirstThenTransferOfOnlyManagerIsRejected() throws Exception {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(draft),
                List.of(() -> activate(draft), () -> transfer(token, manager, branchB)));

        assertThat(results.get(0).getStatusCode()).as("%s", results.get(0).getBody()).isEqualTo(HttpStatus.OK);
        assertRule(results.get(1), "BR-QT-04");
        assertNoActiveBranchWithoutManager(draft);
    }

    /** R4 chiều ngược lại: điều chuyển đi trước → kích hoạt đếm 0 quản lý → BR-QT-04. */
    @Test
    void transferOfOnlyManagerFirstThenActivationIsRejected() throws Exception {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(draft),
                List.of(() -> transfer(token, manager, branchB), () -> activate(draft)));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertThat(text("SELECT status FROM branches WHERE id = ?", draft)).isEqualTo("DRAFT");
        assertNoActiveBranchWithoutManager(draft);
    }

    /** R4 cho đổi chức vụ: kích hoạt trước → đổi chức vụ BM duy nhất bị BR-QT-04. */
    @Test
    void activationFirstThenChangeRoleOfOnlyManagerIsRejected() throws Exception {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(draft),
                List.of(() -> activate(draft), () -> changeRole(token, manager, "VET")));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertNoActiveBranchWithoutManager(draft);
    }

    /** R5: hai thao tác làm giảm số BM của cùng chi nhánh ACTIVE → đúng một thành công. */
    @Test
    void twoManagerReductionsOnTheSameBranchOnlyOneSucceeds() throws Exception {
        long manager1 = staff("BRANCH_MANAGER", branchA);
        long manager2 = staff("BRANCH_MANAGER", branchA);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA),
                List.of(() -> changeRole(token, manager1, "VET"), () -> transfer(token, manager2, branchB)));

        ok(results.get(0));
        assertRule(results.get(1), "BR-QT-04");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles sp JOIN accounts a ON a.id = sp.account_id
                WHERE sp.branch_id = ? AND a.role = 'BRANCH_MANAGER' AND a.status = 'ACTIVE'""", Long.class, branchA))
                .isEqualTo(1L);
        assertNoActiveBranchWithoutManager(branchA);
    }

    /** R6: điều chuyển A→B và B→A cùng lúc khóa {A, B} theo cùng thứ tự → không deadlock, cả hai xong. */
    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchB);
        long fromA = staff("BRANCH_MANAGER", branchA);
        long fromB = staff("BRANCH_MANAGER", branchB);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA, branchB),
                List.of(() -> transfer(token, fromA, branchB), () -> transfer(token, fromB, branchA)));

        ok(results.get(0));
        ok(results.get(1));
        assertThat(number(value("SELECT branch_id FROM staff_profiles WHERE account_id = ?", fromA)))
                .isEqualTo(branchB);
        assertThat(number(value("SELECT branch_id FROM staff_profiles WHERE account_id = ?", fromB)))
                .isEqualTo(branchA);
    }

    /** R2 / R3: chi nhánh đổi trong lúc chờ khóa {@code accounts} → 409; đã ở đúng chi nhánh đích → 200 không ghi. */
    @Test
    void branchChangedWhileWaitingIs409UnlessAlreadyAtDestination() throws Exception {
        long vet = staff("VET", branchA);
        long branchC = branch("ACTIVE");
        String token = token(superManager);

        ResponseEntity<Map<String, Object>> conflict = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, vet),
                () -> transfer(token, vet, branchC));
        assertError(conflict, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(number(value("SELECT branch_id FROM staff_profiles WHERE account_id = ?", vet))).isEqualTo(branchB);

        ResponseEntity<Map<String, Object>> conflictRole = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchA, vet),
                () -> changeRole(token, vet, "CARETAKER"));
        assertError(conflictRole, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(text("SELECT role FROM accounts WHERE id = ?", vet)).isEqualTo("VET");

        ResponseEntity<Map<String, Object>> doubleClick = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchC, vet),
                () -> transfer(token, vet, branchC));
        assertThat(map(ok(doubleClick))).containsEntry("branchId", (int) branchC);
        assertThat(audits(superManager, "STAFF_TRANSFERRED")).isEmpty();
        assertThat(audits(superManager, "STAFF_ROLE_CHANGED")).isEmpty();
    }

    /** R1: chức vụ vừa được đổi đúng giá trị yêu cầu trong lúc chờ → 200 không ghi. */
    @Test
    void roleChangedToRequestedWhileWaitingIsNoOp() throws Exception {
        long vet = staff("VET", branchA);
        String token = token(superManager);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", vet),
                () -> changeRole(token, vet, "CARETAKER"));

        assertThat(map(ok(response))).containsEntry("role", "CARETAKER");
        assertThat(audits(superManager, "STAFF_ROLE_CHANGED")).isEmpty();
    }

    /** R7: PATCH tự sửa hồ sơ và điều chuyển cùng chờ khóa {@code accounts} → không bên nào ghi đè bên kia. */
    @Test
    void concurrentSelfPatchDoesNotOverwriteBranch() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        String token = token(superManager);
        Map<String, Object> patch = Map.of("fullName", "Tên mới " + tag);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> http.exchange("/api/me/staff-profile", HttpMethod.PATCH, new HttpEntity<>(patch,
                        bearer(vetToken)), MAP),
                () -> transfer(token, vet, branchB)));

        ok(results.get(0));
        ok(results.get(1));
        assertThat(jdbc.queryForMap("SELECT full_name, branch_id FROM staff_profiles WHERE account_id = ?", vet))
                .containsEntry("full_name", "Tên mới " + tag).containsEntry("branch_id", branchB);
    }

    /** R7: VET đang bị đổi sang RECEPTIONIST thì PATCH {@code bio} chờ khóa, rồi đọc role mới → 403. */
    @Test
    void selfPatchBioAfterRoleChangeIs403() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> changeRole(token, vet, "RECEPTIONIST"),
                () -> http.exchange("/api/me/staff-profile", HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("bio", "Mô tả mới"), bearer(vetToken)), MAP)));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(text("SELECT bio FROM staff_profiles WHERE account_id = ?", vet)).isNull();
    }

    /** R9: request của chính người bị tác động không chờ khóa ({@code touchLastSeen} dùng {@code SKIP LOCKED}). */
    @Test
    void targetRequestsAreNotBlockedWhileTheirAccountRowIsLocked() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, vet);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(get("/api/me", vetToken).getStatusCode()).isEqualTo(HttpStatus.OK);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    // ================================================================ helpers — dữ liệu

    private long branch(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, "StaffRoleTransferIT " + UUID.randomUUID(), status);
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
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), role);
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
        return "role-transfer-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "StaffRoleTransferIT")).accessToken();
    }

    private String text(String sql, long id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private Object value(String sql, long id) {
        return jdbc.queryForObject(sql, Object.class, id);
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

    private static String json(Map<String, Object> row, String column) {
        return (String) row.get(column);
    }

    @SuppressWarnings("unchecked")
    private List<Long> publicVetIds() {
        return ((List<Map<String, Object>>) http.exchange("/api/public/vets", HttpMethod.GET, null, MAP).getBody()
                .get("data")).stream().map(row -> number(row.get("accountId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publicVet(long accountId) {
        return ((List<Map<String, Object>>) http.exchange("/api/public/vets", HttpMethod.GET, null, MAP).getBody()
                .get("data")).stream().filter(row -> number(row.get("accountId")) == accountId).findFirst()
                .orElseThrow();
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
        String function = "it_role_transfer_fail_" + suffix;
        String trigger = "trg_it_role_transfer_fail_" + suffix;
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

    // ================================================================ helpers — đồng thời

    /**
     * Một transaction giữ khóa {@code branches} của {@code branchIds} ({@code FOR UPDATE}); các request được bắn lần
     * lượt, mỗi request phải đứng chờ khóa (thấy trong {@code pg_stat_activity}) trước khi bắn request kế tiếp, nên thứ
     * tự nhận khóa sau khi thả = thứ tự trong danh sách. Trả kết quả theo đúng thứ tự đó.
     */
    private List<ResponseEntity<Map<String, Object>>> whileBranchesLocked(Collection<Long> branchIds,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM branches WHERE id = ? FOR UPDATE", branchIds, WAITING_ON_BRANCH_LOCK,
                requests);
    }

    private List<ResponseEntity<Map<String, Object>>> whileAccountLockedMany(long accountId,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM accounts WHERE id = ? FOR UPDATE", List.of(accountId),
                WAITING_ON_ACCOUNT_LOCK, requests);
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

    /** Khóa dòng {@code accounts}, chạy {@code whileHolding} trong cùng transaction, request phải chờ rồi mới thả. */
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
            awaitWaiting(WAITING_ON_ACCOUNT_LOCK, 1);
            assertThat(call.isDone()).as("request phải chờ khóa dòng accounts").isFalse();
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            return call.get(30, TimeUnit.SECONDS);
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

    private ResponseEntity<Map<String, Object>> changeRole(String token, long id, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("role", role);
        return post("/api/staff/" + id + "/change-role", token, body);
    }

    private ResponseEntity<Map<String, Object>> transfer(String token, long id, long branchId) {
        return post("/api/staff/" + id + "/transfer", token, Map.of("branchId", branchId));
    }

    private ResponseEntity<Map<String, Object>> activate(long branchId) {
        return post("/api/branches/" + branchId + "/activate", token(superManager), null);
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
