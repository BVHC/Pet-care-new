package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #12 xem, #13 ghi bệnh án trên Postgres thật (docs/adr/0065). Mục theo plan:
 * <ul>
 * <li>Đường đúng: lần ghi đầu tạo dòng, PUT thay toàn bộ, chuẩn hóa, khung rỗng, hình dạng JSON, ngày tái khám.</li>
 * <li>Xác thực / phân quyền: 401, BR-TK-17, role, chi nhánh, người phụ trách (gồm sau gán lại), thứ tự lỗi.</li>
 * <li>Nghiệp vụ: GROOMING 404 (G2), biên tái khám + [CFG] (G4), 409 không {@code IN_PROGRESS} (G6), không đổi thì không
 * ghi (D4), audit ai ghi phần nào (G12), khóa loài (G14).</li>
 * <li>Transaction: audit lỗi thì rollback cả bệnh án; race với khóa lượt và khóa thú ({@code pg_stat_activity}).</li>
 * </ul>
 * {@code CashierShiftQueryApi} (placeholder, D016) được thay bằng mock "không có ca mở" để khóa tài khoản chạy được.
 * Đồng hồ {@link MutableClock} ở <b>năm 2011</b>; chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MedicalRecordIT.TestBeans.class})
class MedicalRecordIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2011, 4, 6);
    private static final Instant T0 = at(9, 0);
    private static final String CFG_KEY = "medical_record.follow_up_max_days";
    private static final String PATH = "/api/visits/%d/medical-record";
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

    @MockitoBean
    private CashierShiftQueryApi cashierShifts;

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
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long vetA;
    private long vetB;
    private long caretaker;
    private long manager;
    private long superManager;
    private long admin;
    private long customerAccount;
    private long customer;
    private long pet;
    private String receptionistToken;
    private String vetAToken;
    private String vetBToken;
    private String caretakerToken;
    private String managerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch)) {
            data.branchService(b, medical, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        superManager = data.staff("SUPER_MANAGER", null);
        admin = data.staff("ADMIN", null);
        customerAccount = data.customerAccount();
        customer = data.counterCustomer("Khách bệnh án " + UUID.randomUUID(), null);
        pet = data.pet(customer);
        receptionistToken = token(receptionist);
        vetAToken = token(vetA);
        vetBToken = token(vetB);
        caretakerToken = token(caretaker);
        managerToken = token(manager);
        adminToken = token(admin);
    }

    @AfterEach
    void tearDown() {
        for (long b : branches) {
            jdbc.update("""
                    DELETE FROM medical_record_addenda WHERE medical_record_id IN (
                        SELECT id FROM visits WHERE branch_id = ?)""", b);
            jdbc.update("""
                    DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)""", b);
            jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        data.cleanUp();
    }

    // ================================================================ đường đúng

    @Test
    void firstWriteCreatesTheRecordNormalisesAndAuditsWithoutBefore() {
        long visit = called(walkIn(medical), vetA);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        LocalDate followUp = TODAY.plusDays(14);

        ResponseEntity<Map<String, Object>> response = put(vetAToken, visit, body("  khám  ", "viêm dạ dày", "　",
                "nội bộ", followUp.toString()));

        Map<String, Object> record = data(response, HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200);
        assertThat(record.keySet()).containsExactlyInAnyOrder("visitId", "examination", "diagnosis", "treatmentPlan",
                "internalNote", "followUpDate", "locked", "lastEditedBy", "prescriptions", "vaccinations", "addenda");
        assertThat(record).containsEntry("visitId", (int) visit).containsEntry("examination", "khám")
                .containsEntry("diagnosis", "viêm dạ dày").containsEntry("internalNote", "nội bộ")
                .containsEntry("followUpDate", followUp.toString()).containsEntry("locked", false)
                .containsEntry("lastEditedBy", (int) vetA);
        assertThat(record.get("treatmentPlan")).as("chuỗi toàn khoảng trắng thành null").isNull();
        assertThat(record.get("prescriptions")).isEqualTo(List.of());
        assertThat(record.get("vaccinations")).isEqualTo(List.of());
        assertThat(record.get("addenda")).isEqualTo(List.of());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM medical_records WHERE visit_id = ?", visit);
        assertThat(row).containsEntry("examination", "khám").containsEntry("last_edited_by", vetA);
        assertThat(row.get("treatment_plan")).isNull();
        assertThat(row.get("locked_at")).isNull();

        List<Map<String, Object>> audits = audits(visit);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("actor_account_id", vetA).containsEntry("entity_type", "medical_records");
        assertThat(audits.get(0).get("b")).isNull();
        assertThat((String) audits.get(0).get("a")).contains("\"examination\": \"khám\"")
                .contains("\"followUpDate\": \"" + followUp + "\"");
    }

    @Test
    void anEmptyFirstWriteStillCreatesTheRecordAndPutReplacesEverything() {
        long visit = called(walkIn(medical), vetA);

        data(put(vetAToken, visit, body(null, null, null, null, null)), HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isEqualTo(1);

        data(put(vetAToken, visit, body("a", "b", "c", "d", TODAY.plusDays(3).toString())), HttpStatus.OK);
        Map<String, Object> cleared = data(put(vetAToken, visit, Map.of()), HttpStatus.OK);

        assertThat(cleared.get("examination")).isNull();
        assertThat(cleared.get("diagnosis")).isNull();
        assertThat(cleared.get("followUpDate")).isNull();
        assertThat(audits(visit)).as("lần rỗng đầu không đổi gì khi nội dung rỗng, hai lần còn lại có đổi").hasSize(3);
    }

    @Test
    void getReturnsAnEmptyFrameThenTheStoredRecordForVetAndCaretaker() {
        long visit = called(walkIn(medical), vetA);

        Map<String, Object> empty = data(get(vetAToken, visit), HttpStatus.OK);
        assertThat(empty).containsEntry("locked", false);
        assertThat(empty.get("diagnosis")).isNull();
        assertThat(empty.get("addenda")).isEqualTo(List.of());

        put(vetAToken, visit, body("khám", "dx", null, "bí mật", null));

        assertThat(data(get(vetBToken, visit), HttpStatus.OK)).containsEntry("diagnosis", "dx");
        assertThat(data(get(caretakerToken, visit), HttpStatus.OK)).containsEntry("internalNote", "bí mật");
    }

    @Test
    void unknownBodyFieldsAreIgnoredAndCannotLockTheRecord() {
        long visit = called(walkIn(medical), vetA);

        Map<String, Object> record = data(put(vetAToken, visit, Map.of("diagnosis", "dx", "locked", true)),
                HttpStatus.OK);

        assertThat(record).containsEntry("locked", false);
        assertThat(jdbc.queryForObject("SELECT locked_at FROM medical_records WHERE visit_id = ?", Timestamp.class,
                visit)).isNull();
    }

    @Test
    void getOnACompletedVisitIsLockedAndListsAddendaWithAuthorNames() {
        long visit = called(walkIn(medical), vetA);
        put(vetAToken, visit, body("khám", "dx", null, null, null));
        complete(visit);
        jdbc.update("""
                INSERT INTO medical_record_addenda (medical_record_id, content, is_internal, created_by)
                VALUES (?, 'bổ sung 1', false, ?), (?, 'bổ sung 2', true, ?)""", visit, vetA, visit, vetB);

        Map<String, Object> record = data(get(caretakerToken, visit), HttpStatus.OK);

        assertThat(record).containsEntry("locked", true);
        List<Map<String, Object>> addenda = listOf(record.get("addenda"));
        assertThat(addenda).extracting(a -> a.get("content")).containsExactly("bổ sung 1", "bổ sung 2");
        assertThat(addenda).extracting(a -> a.get("isInternal")).containsExactly(false, true);
        assertThat(addenda).extracting(a -> a.get("createdByName")).allMatch(n -> ((String) n).startsWith("VET "));
        assertThat(addenda.get(0).keySet()).containsExactlyInAnyOrder("addendumId", "content", "isInternal",
                "createdBy", "createdByName", "createdAt");
    }

    // ================================================================ xác thực / phân quyền

    @Test
    void withoutTokenOrAfterLogoutBothEndpointsAre401() {
        long visit = called(walkIn(medical), vetA);

        assertError(get(null, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(put(null, visit, Map.of()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vetA);
        assertError(get(vetAToken, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(put(vetAToken, visit, Map.of()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void aVetWhoMustChangeThePasswordGetsBrTk17OnBothEndpoints() {
        long visit = called(walkIn(medical), vetA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vetA);

        for (ResponseEntity<Map<String, Object>> response : List.of(get(vetAToken, visit),
                put(vetAToken, visit, Map.of()))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) response.getBody().get("message")).contains("BR-TK-17");
        }
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
    }

    @Test
    void rolesOutsideTheContractAre403OnGetAndOnlyVetMayPut() {
        long visit = called(walkIn(medical), vetA);
        String customerToken = token(customerAccount);

        for (String bearer : List.of(receptionistToken, managerToken, token(superManager), adminToken, customerToken)) {
            assertError(get(bearer, visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String bearer : List.of(caretakerToken, receptionistToken, managerToken, token(superManager), adminToken,
                customerToken)) {
            assertError(put(bearer, visit, Map.of("diagnosis", "x")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        assertThat(audits(visit)).isEmpty();
    }

    @Test
    void staffOfAnotherBranchGet403OnGetAndPut() {
        long visit = called(walkIn(medical), vetA);
        String otherVet = token(data.staff("VET", otherBranch));
        String otherCaretaker = token(data.staff("CARETAKER", otherBranch));

        assertScopeDenied(get(otherVet, visit));
        assertScopeDenied(get(otherCaretaker, visit));
        assertScopeDenied(put(otherVet, visit, Map.of("diagnosis", "x")));
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
    }

    @Test
    void onlyTheCurrentAssigneeMayWriteButAnyVetOfTheBranchMayRead() {
        long visit = called(walkIn(medical), vetA);

        assertScopeDenied(put(vetBToken, visit, Map.of("diagnosis", "x")));
        assertThat(get(vetBToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        assertThat(audits(visit)).isEmpty();
    }

    @Test
    void aWaitingVisitIsAlways403ForNonAssigneeBefore409() {
        long visit = assigned(walkIn(medical), vetA);

        assertScopeDenied(put(vetBToken, visit, Map.of("diagnosis", "x")));
        assertError(put(vetAToken, visit, Map.of("diagnosis", "x")), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void afterARealReassignmentTheOldVetIs403AndTheNewVetWritesWithItsOwnAudit() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, body("khám bởi A", "dx A", null, null, null)), HttpStatus.OK);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(put(vetAToken, visit, Map.of("diagnosis", "x")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        ResponseEntity<Map<String, Object>> reassigned = exchange(HttpMethod.POST, managerToken,
                "/api/visits/" + visit + "/reassign", Map.of("assigneeId", vetB, "reason", "IT " + UUID.randomUUID()));
        assertThat(reassigned.getStatusCode()).as(String.valueOf(reassigned.getBody())).isEqualTo(HttpStatus.OK);

        Map<String, Object> record = data(put(vetBToken, visit, body("khám bởi A", "dx B", null, null, null)),
                HttpStatus.OK);

        assertThat(record).containsEntry("diagnosis", "dx B").containsEntry("lastEditedBy", (int) vetB);
        List<Map<String, Object>> audits = audits(visit);
        assertThat(audits).extracting(a -> a.get("actor_account_id")).containsExactly(vetA, vetB);
        assertThat((String) audits.get(1).get("b")).contains("\"diagnosis\": \"dx A\"");
        assertThat((String) audits.get(1).get("a")).contains("\"diagnosis\": \"dx B\"");
    }

    @Test
    void theFormerAssigneeWithAValidTokenIs403AfterTheVisitMovedToAnotherVet() {
        long visit = called(walkIn(medical), vetA);
        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetB, visit);

        assertScopeDenied(put(vetAToken, visit, Map.of("diagnosis", "x")));
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
    }

    // ================================================================ thứ tự lỗi và validate

    @Test
    void malformedRequestsAre400BeforeTheRoleCheck() {
        long visit = called(walkIn(medical), vetA);
        String path = PATH.formatted(visit);

        assertError(raw(HttpMethod.PUT, vetAToken, path, "{"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.PUT, vetAToken, path, "{\"followUpDate\":\"2011-13-01\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.PUT, vetAToken, path, "{\"followUpDate\":\"05/11/2011\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.PUT, vetAToken, path, "{\"diagnosis\":[\"a\"]}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.PUT, receptionistToken, path, "{"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchange(HttpMethod.PUT, vetAToken, path, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.GET, vetAToken, "/api/visits/abc/medical-record", null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
    }

    @Test
    void missingVisitIs404AndAVisitOfAnotherBranchIs403EvenWhenCompleted() {
        assertError(get(vetAToken, 987654321L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(put(vetAToken, 987654321L, Map.of()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        long visit = called(walkIn(medical), vetA);
        complete(visit);
        String otherVet = token(data.staff("VET", otherBranch));

        assertScopeDenied(put(otherVet, visit, Map.of("diagnosis", "x")));
    }

    @Test
    void aGroomingVisitHasNoMedicalRecordOnGetAndPut() {
        long visit = called(walkIn(grooming), caretaker);

        assertError(get(caretakerToken, visit), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(get(vetAToken, visit), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(put(vetAToken, visit, Map.of("diagnosis", "x")), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        assertThat(audits(visit)).isEmpty();
    }

    @Test
    void aVisitThatIsNotInProgressIs409AndNothingChanges() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, body("khám", "dx", null, null, null)), HttpStatus.OK);
        long xmin = xmin("medical_records", visit);
        long audits = audits(visit).size();
        complete(visit);

        assertError(put(vetAToken, visit, body("sửa", "dx mới", null, null, null)), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        jdbc.update("UPDATE visits SET status = 'WAITING', completed_at = NULL WHERE id = ?", visit);
        assertError(put(vetAToken, visit, Map.of("diagnosis", "x")), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(xmin("medical_records", visit)).isEqualTo(xmin);
        assertThat(audits(visit)).hasSize((int) audits);
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE visit_id = ?", String.class, visit))
                .isEqualTo("dx");
    }

    // ---- BR-KB-06 và [CFG]

    @Test
    void followUpBoundariesAreAfterTheExaminationDayUpToTheConfiguredMaximum() {
        long visit = called(walkIn(medical), vetA);

        for (long offset : List.of(-1L, 0L, 181L)) {
            ResponseEntity<Map<String, Object>> response = put(vetAToken, visit,
                    Map.of("diagnosis", "dx", "followUpDate", TODAY.plusDays(offset).toString()));
            assertThat(response.getStatusCode()).as("offset " + offset).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
            assertThat((String) response.getBody().get("message")).endsWith("(BR-KB-06)").contains("180");
        }
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).as("lỗi không ghi").isZero();
        assertThat(audits(visit)).isEmpty();
        data(put(vetAToken, visit, Map.of("diagnosis", "dx", "followUpDate", TODAY.plusDays(1).toString())),
                HttpStatus.OK);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx", "followUpDate", TODAY.plusDays(180).toString())),
                HttpStatus.OK);
    }

    @Test
    void theExaminationDayIsTheVietnamDayOfTheCheckInEvenAcrossMidnight() {
        long visit = called(walkIn(medical), vetA);
        // 00:30 ngày kế tiếp giờ Việt Nam = 17:30Z của ngày TODAY: ngày UTC khác ngày Việt Nam.
        jdbc.update("UPDATE visits SET checked_in_at = ? WHERE id = ?",
                Timestamp.from(TODAY.plusDays(1).atTime(0, 30).atZone(VN).toInstant()), visit);

        assertError(put(vetAToken, visit, Map.of("followUpDate", TODAY.plusDays(1).toString())),
                HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        data(put(vetAToken, visit, Map.of("followUpDate", TODAY.plusDays(2).toString())), HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT follow_up_date FROM medical_records WHERE visit_id = ?", LocalDate.class,
                visit)).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    void theConfiguredMaximumAppliesToNewDatesOnlyAndASavedDateSurvivesAShrinkingConfig() {
        long visit = called(walkIn(medical), vetA);
        LocalDate saved = TODAY.plusDays(100);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx", "followUpDate", saved.toString())), HttpStatus.OK);
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, CFG_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = '30' WHERE key = ?", CFG_KEY);
            configs.reload();

            data(put(vetAToken, visit, Map.of("diagnosis", "dx mới", "followUpDate", saved.toString())), HttpStatus.OK);
            ResponseEntity<Map<String, Object>> changed = put(vetAToken, visit,
                    Map.of("diagnosis", "dx mới", "followUpDate", saved.plusDays(1).toString()));
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) changed.getBody().get("message")).contains("30").endsWith("(BR-KB-06)");
            data(put(vetAToken, visit, Map.of("diagnosis", "dx mới", "followUpDate", TODAY.plusDays(30).toString())),
                    HttpStatus.OK);
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, CFG_KEY);
            configs.reload();
        }
    }

    // ---- không đổi thì không ghi (D4)

    @Test
    void anIdenticalPutReturns200WithoutWritingAuditingOrChangingTheEditor() {
        long visit = called(walkIn(medical), vetA);
        Map<String, Object> request = body("khám", "dx", null, "nội bộ", TODAY.plusDays(7).toString());
        data(put(vetAToken, visit, request), HttpStatus.OK);
        long xmin = xmin("medical_records", visit);
        Object updatedAt = jdbc.queryForObject("SELECT updated_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
        jdbc.update("UPDATE medical_records SET last_edited_by = ? WHERE visit_id = ?", vetB, visit);
        long afterTouch = xmin("medical_records", visit);

        Map<String, Object> again = data(put(vetAToken, visit, request), HttpStatus.OK);

        assertThat(again).containsEntry("diagnosis", "dx").containsEntry("lastEditedBy", (int) vetB);
        assertThat(xmin("medical_records", visit)).isEqualTo(afterTouch).isNotEqualTo(xmin);
        assertThat(audits(visit)).hasSize(1);
        assertThat(updatedAt).isNotNull();
    }

    @Test
    void ClearingAFieldIsAChangeThatWritesAndAudits() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, body("khám", "dx", null, null, null)), HttpStatus.OK);

        data(put(vetAToken, visit, body("khám", null, null, null, null)), HttpStatus.OK);

        assertThat(audits(visit)).hasSize(2);
        assertThat((String) audits(visit).get(1).get("b")).contains("\"diagnosis\": \"dx\"");
        assertThat((String) audits(visit).get(1).get("a")).contains("\"diagnosis\": null");
    }

    // ================================================================ khóa loài (BR-KH-03)

    @Test
    void oncethePetHasAMedicalRecordItsSpeciesCannotChange() {
        long visit = called(walkIn(medical), vetA);
        ResponseEntity<Map<String, Object>> before = exchange(HttpMethod.PATCH, receptionistToken, "/api/pets/" + pet,
                Map.of("species", "CAT"));
        assertThat(before.getStatusCode()).as("chưa có bệnh án thì đổi được: " + before.getBody())
                .isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE pets SET species = 'DOG' WHERE id = ?", pet);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);

        ResponseEntity<Map<String, Object>> after = exchange(HttpMethod.PATCH, receptionistToken, "/api/pets/" + pet,
                Map.of("species", "CAT"));

        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) after.getBody().get("message")).contains("BR-KH-03");
    }

    // ================================================================ transaction

    @Test
    void auditFailureRollsBackTheWholeFirstWriteAndLaterWrites() {
        long visit = called(walkIn(medical), vetA);

        assertError(withRejectingTrigger("INSERT", "audit_logs", "NEW.action = 'MEDICAL_RECORD_UPDATED'",
                () -> put(vetAToken, visit, body("khám", "dx", null, null, null))), HttpStatus.CONFLICT,
                "CONCURRENCY_CONFLICT");
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).as("không bản ghi thiếu audit")
                .isZero();

        data(put(vetAToken, visit, body("khám", "dx", null, null, null)), HttpStatus.OK);
        long xmin = xmin("medical_records", visit);
        assertError(withRejectingTrigger("INSERT", "audit_logs", "NEW.action = 'MEDICAL_RECORD_UPDATED'",
                () -> put(vetAToken, visit, body("khám khác", "dx khác", null, null, null))), HttpStatus.CONFLICT,
                "CONCURRENCY_CONFLICT");

        assertThat(xmin("medical_records", visit)).isEqualTo(xmin);
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE visit_id = ?", String.class, visit))
                .isEqualTo("dx");
        assertThat(audits(visit)).hasSize(1);
    }

    @Test
    void dbFailureOnTheRecordWriteRollsBackWithoutAudit() {
        long visit = called(walkIn(medical), vetA);

        assertError(withRejectingTrigger("INSERT", "medical_records", "true",
                () -> put(vetAToken, visit, body("khám", "dx", null, null, null))), HttpStatus.CONFLICT,
                "CONCURRENCY_CONFLICT");

        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        assertThat(audits(visit)).isEmpty();
    }

    /** READ COMMITTED đọc lại sau khóa: lượt vừa được chuyển cho VET khác trong lúc chờ → 403, không ghi. */
    @Test
    void writeWaitsForTheVisitLockAndThenSeesTheNewAssignee() throws Exception {
        long visit = called(walkIn(medical), vetA);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from visits where id = % for no key update%",
                j -> j.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetB, visit),
                List.of(() -> put(vetAToken, visit, Map.of("diagnosis", "x"))), 1);

        assertScopeDenied(results.get(0));
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
        assertThat(audits(visit)).isEmpty();
    }

    /** Khóa thú: đang giữ {@code pets FOR NO KEY UPDATE} (như đổi loài) thì lần ghi đầu phải chờ rồi mới ghi. */
    @Test
    void firstWriteWaitsForAConcurrentPetLock() throws Exception {
        long visit = called(walkIn(medical), vetA);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", new Object[] {pet},
                "%from pets%for share%", j -> { },
                List.of(() -> put(vetAToken, visit, Map.of("diagnosis", "dx"))), 1);

        assertThat(results.get(0).getStatusCode()).as(String.valueOf(results.get(0).getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isEqualTo(1);
    }

    // ================================================================ helpers

    private List<ResponseEntity<Map<String, Object>>> whileHeld(String lockSql, Object[] lockArgs, String waitingLike,
            Consumer<JdbcTemplate> beforeRelease, List<Callable<ResponseEntity<Map<String, Object>>>> requests,
            int expectedWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList(lockSql, lockArgs);
                held.countDown();
                await(release);
                beforeRelease.accept(jdbc);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
            }
            awaitWaiting(waitingLike, expectedWaiting);
            assertThat(calls).as("request phải đứng chờ khóa").noneMatch(Future::isDone);
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

    private void awaitWaiting(String like, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, like);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy " + count + " request chờ khóa (" + like + ")");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Trigger tạm từ chối INSERT / UPDATE (23514 = check_violation → 409), giới hạn bằng {@code condition}. */
    private <T> T withRejectingTrigger(String event, String table, String condition, Supplier<T> action) {
        String name = "it_reject_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table + " FOR EACH ROW WHEN ("
                + condition + ") EXECUTE FUNCTION " + name + "()");
        try {
            return action.get();
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON " + table);
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    private long walkIn(long serviceId) {
        clock.set(T0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("petId", pet);
        request.put("serviceId", serviceId);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", request),
                HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    private long assigned(long visitId, long assigneeId) {
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, receptionistToken,
                "/api/visits/" + visitId + "/assign", Map.of("assigneeId", assigneeId));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long called(long visitId, long assigneeId) {
        assigned(visitId, assigneeId);
        String bearer = assigneeId == vetA ? vetAToken : assigneeId == vetB ? vetBToken : caretakerToken;
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, bearer,
                "/api/visits/" + visitId + "/call", null);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    /** Visit#5 chưa có (T27 phần 3): đặt trạng thái cuối bằng SQL, đúng cột mà FSM sẽ ghi. */
    private void complete(long visitId) {
        jdbc.update("UPDATE visits SET status = 'COMPLETED', completed_at = now() WHERE id = ?", visitId);
    }

    private List<Map<String, Object>> audits(long visitId) {
        return jdbc.queryForList("""
                SELECT actor_account_id, entity_type, before_data::text AS b, after_data::text AS a
                FROM audit_logs WHERE action = 'MEDICAL_RECORD_UPDATED' AND entity_id = ? ORDER BY id""", visitId);
    }

    private long xmin(String table, long id) {
        String column = "medical_records".equals(table) ? "visit_id" : "id";
        return jdbc.queryForObject("SELECT xmin::text::bigint FROM " + table + " WHERE " + column + " = ?", Long.class,
                id);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long activeBranch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
    }

    private ResponseEntity<Map<String, Object>> lock(long accountId) {
        return exchange(HttpMethod.POST, adminToken, "/api/accounts/" + accountId + "/lock",
                Map.of("reason", "IT khóa " + UUID.randomUUID()));
    }

    private static Map<String, Object> body(String examination, String diagnosis, String treatmentPlan,
            String internalNote, String followUpDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("examination", examination);
        body.put("diagnosis", diagnosis);
        body.put("treatmentPlan", treatmentPlan);
        body.put("internalNote", internalNote);
        body.put("followUpDate", followUpDate);
        return body;
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, long visitId) {
        return exchange(HttpMethod.GET, bearer, PATH.formatted(visitId), null);
    }

    private ResponseEntity<Map<String, Object>> put(String bearer, long visitId, Object body) {
        return exchange(HttpMethod.PUT, bearer, PATH.formatted(visitId), body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers(bearer)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(HttpMethod method, String bearer, String path, String body) {
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers(bearer)), MAP);
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static void assertScopeDenied(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(response.getBody()).containsEntry("message", "Không có quyền truy cập dữ liệu này");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "MedicalRecordIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
