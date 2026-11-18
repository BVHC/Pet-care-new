package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Ca làm việc qua HTTP thật (workforce-v1 #1–3, UC34, BR-NS-01, BR-NS-10; docs/adr/0138): thành công, từng dòng ma trận
 * validate / exception (docs plan D3), dữ liệu và audit đúng, mọi nhánh lỗi không đổi dữ liệu. Authn / authz ở
 * {@link ShiftDefinitionSecurityIT}, đua khóa ở {@link ShiftDefinitionLockIT}, rollback ở
 * {@link ShiftDefinitionRollbackIT}. Era <b>năm 2045</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftDefinitionIT.TestBeans.class})
class ShiftDefinitionIT extends ShiftDefinitionItSupport {

    private static final Instant NOW = Instant.parse("2045-03-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2045, 3, 14);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branchA;
    private long branchB;
    private long managerA;
    private long managerB;
    private long receptionistA;
    private String tokenA;
    private String tokenB;
    private String receptionistToken;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        receptionistA = data.staff("RECEPTIONIST", branchA);
        tokenA = token(managerA);
        tokenB = token(managerB);
        receptionistToken = token(receptionistA);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- create

    @Test
    void createPersistsTheShiftAndAuditsItAndReturnsExactlyTheContractFields() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA,
                "{'name':'  Ca sáng  ','startTime':'08:00','endTime':'12:00'}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        Map<String, Object> shift = dataOf(response);
        assertThat(shift).containsOnlyKeys("shiftDefinitionId", "branchId", "name", "startTime", "endTime", "active");
        assertThat(shift).containsEntry("name", "Ca sáng").containsEntry("startTime", "08:00")
                .containsEntry("endTime", "12:00").containsEntry("active", true)
                .containsEntry("branchId", (int) branchA);
        long id = ((Number) shift.get("shiftDefinitionId")).longValue();
        assertThat(row(id)).containsEntry("name", "Ca sáng").containsEntry("is_active", true)
                .containsEntry("branch_id", branchA);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT action, actor_account_id, before_data::text AS before_text, after_data->>'name' AS n,
                       after_data->>'startTime' AS s, after_data->>'endTime' AS e, after_data->>'active' AS a,
                       (SELECT COUNT(*) FROM jsonb_object_keys(after_data)) AS key_count
                FROM audit_logs WHERE entity_type = 'shift_definitions' AND entity_id = ?""", id);
        assertThat(audit).containsEntry("action", "SHIFT_DEFINITION_CREATED").containsEntry("actor_account_id", managerA)
                .containsEntry("n", "Ca sáng").containsEntry("s", "08:00").containsEntry("e", "12:00")
                .containsEntry("a", "true").containsEntry("key_count", 4L);
        assertThat(audit.get("before_text")).isNull();
    }

    @Test
    void createAcceptsExactlySixtyCharactersAndKeepsTheCharacterCase() {
        String name = "Ca " + "x".repeat(57);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA,
                "{'name':'" + name + "','startTime':'00:00','endTime':'23:59'}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(dataOf(response)).containsEntry("name", name);
    }

    @Test
    void createIgnoresAForgedBranchIdAndAlwaysUsesTheCallersBranch() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA,
                "{'name':'Ca lạ','startTime':'08:00','endTime':'12:00','branchId':" + branchB + ",'foo':1}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(dataOf(response)).containsEntry("branchId", (int) branchA);
        assertThat(definitionCount(branchA)).isEqualTo(1);
        assertThat(definitionCount(branchB)).isZero();
    }

    @Test
    void createRejectsInvalidBodiesWithoutWritingAnything() {
        List<String[]> cases = List.of(
                new String[] {"{}", "VALIDATION_FAILED"},
                new String[] {"{'name':'','startTime':'08:00','endTime':'12:00'}", "VALIDATION_FAILED"},
                new String[] {"{'name':'   ','startTime':'08:00','endTime':'12:00'}", "VALIDATION_FAILED"},
                new String[] {"{'name':null,'startTime':'08:00','endTime':'12:00'}", "VALIDATION_FAILED"},
                new String[] {"{'name':'" + "x".repeat(61) + "','startTime':'08:00','endTime':'12:00'}",
                        "VALIDATION_FAILED"},
                new String[] {"{'name':'Ca','endTime':'12:00'}", "VALIDATION_FAILED"},
                new String[] {"{'name':'Ca','startTime':'08:00'}", "VALIDATION_FAILED"},
                new String[] {"{'name':'Ca','startTime':null,'endTime':null}", "VALIDATION_FAILED"},
                new String[] {"{'name':'Ca','startTime':'8:00','endTime':'12:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':'Ca','startTime':'08:00:30','endTime':'12:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':'Ca','startTime':'25:00','endTime':'26:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':'Ca','startTime':'08h','endTime':'12:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':'Ca','startTime':800,'endTime':'12:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':['Ca'],'startTime':'08:00','endTime':'12:00'}", "MALFORMED_REQUEST"},
                new String[] {"{'name':'Ca','startTime':'08:00','endTime':'12:00'", "MALFORMED_REQUEST"},
                new String[] {"[]", "MALFORMED_REQUEST"},
                new String[] {"'chuỗi'", "MALFORMED_REQUEST"});

        for (String[] c : cases) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA, c[0]);
            assertThat(response.getStatusCode().value()).as(c[0]).isEqualTo(400);
            assertThat(errorCode(response)).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(call(HttpMethod.POST, PATH, tokenA).getStatusCode().value()).as("không có body").isEqualTo(400);
        assertThat(definitionCount(branchA)).isZero();
        assertThat(auditCountForBranch(branchA)).isZero();
    }

    @Test
    void createListsEveryInvalidFieldAtOnceAndNeverEchoesTheName() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA, "{'name':'  '}");

        assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(message(response)).contains("name: Vui lòng nhập tên ca").contains("startTime: ")
                .contains("endTime: ").contains("; ");
    }

    @Test
    void createRejectsAnEqualOrInvertedTimeRangeWithBrNs01() {
        for (String range : List.of("'startTime':'08:00','endTime':'08:00'", "'startTime':'12:00','endTime':'08:00'",
                "'startTime':'00:00','endTime':'00:00'")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA,
                    "{'name':'Ca sai giờ'," + range + "}");
            assertBusinessRule(response, "BR-NS-01");
            assertThat(message(response)).startsWith("Giờ bắt đầu phải nhỏ hơn giờ kết thúc");
        }
        assertThat(definitionCount(branchA)).isZero();
        assertThat(auditCountForBranch(branchA)).isZero();
    }

    @Test
    void createRejectsADuplicateNameIgnoringCaseSpacesAndEvenForAnInactiveShift() {
        definition(branchA, "Ca sáng", "07:00", "11:00", false);
        // data.definition thêm hậu tố UUID; tạo ca có tên chính xác bằng API để so trùng thật
        assertThat(call(HttpMethod.POST, PATH, tokenA, "{'name':'Ca Cố Định','startTime':'08:00','endTime':'12:00'}")
                .getStatusCode().value()).isEqualTo(201);

        for (String name : List.of("Ca Cố Định", "ca cố định", "  CA CỐ ĐỊNH  ")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, tokenA,
                    "{'name':'" + name + "','startTime':'13:00','endTime':'17:00'}");
            assertBusinessRule(response, "BR-NS-01");
            assertThat(message(response)).startsWith("Tên ca đã tồn tại trong chi nhánh").doesNotContain("cố định");
        }
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    @Test
    void theSameNameIsAllowedInAnotherBranch() {
        assertThat(call(HttpMethod.POST, PATH, tokenA, "{'name':'Ca chung','startTime':'08:00','endTime':'12:00'}")
                .getStatusCode().value()).isEqualTo(201);

        assertThat(call(HttpMethod.POST, PATH, tokenB, "{'name':'Ca chung','startTime':'08:00','endTime':'12:00'}")
                .getStatusCode().value()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- list

    @Test
    void managerSeesActiveAndInactiveShiftsOfOwnBranchSortedByStartThenId() {
        long late = definition(branchA, "Chiều", "14:00", "19:00", true);
        long inactive = definition(branchA, "Sớm", "06:00", "08:00", false);
        long morning = definition(branchA, "Sáng", "08:00", "12:00", true);
        long tie = definition(branchA, "Sáng 2", "08:00", "12:00", true);
        definition(branchB, "Của chi nhánh B", "08:00", "12:00", true);

        ResponseEntity<Map<String, Object>> all = call(HttpMethod.GET, PATH, tokenA);
        ResponseEntity<Map<String, Object>> onlyActive = call(HttpMethod.GET, PATH + "?activeOnly=true", tokenA);
        ResponseEntity<Map<String, Object>> explicitFalse = call(HttpMethod.GET, PATH + "?activeOnly=false", tokenA);

        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(ids(all)).containsExactly(inactive, morning, tie, late);
        assertThat(ids(onlyActive)).containsExactly(morning, tie, late);
        assertThat(ids(explicitFalse)).containsExactly(inactive, morning, tie, late);
        assertThat(listOf(all).get(0)).containsOnlyKeys("shiftDefinitionId", "branchId", "name", "startTime", "endTime",
                "active").containsEntry("startTime", "06:00").containsEntry("endTime", "08:00");
    }

    @Test
    void staffOnlyEverSeeActiveShiftsWhateverTheyAskFor() {
        long active = definition(branchA, "Sáng", "08:00", "12:00", true);
        definition(branchA, "Ngừng", "13:00", "17:00", false);

        for (String suffix : List.of("", "?activeOnly=false", "?activeOnly=true")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, PATH + suffix, receptionistToken);
            assertThat(response.getStatusCode().value()).as(suffix).isEqualTo(200);
            assertThat(ids(response)).as(suffix).containsExactly(active);
        }
    }

    @Test
    void listRejectsABadFlagAndTreatsAnEmptyFlagAsMissing() {
        long active = definition(branchA, "Sáng", "08:00", "12:00", true);
        long inactive = definition(branchA, "Ngừng", "13:00", "17:00", false);

        ResponseEntity<Map<String, Object>> bad = call(HttpMethod.GET, PATH + "?activeOnly=abc", tokenA);
        ResponseEntity<Map<String, Object>> empty = call(HttpMethod.GET, PATH + "?activeOnly=", tokenA);

        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(bad)).isEqualTo("MALFORMED_REQUEST");
        assertThat(empty.getStatusCode().value()).isEqualTo(200);
        assertThat(ids(empty)).containsExactly(active, inactive);
    }

    // ---------------------------------------------------------------- update

    @Test
    void renameChangesOnlyTheNameAndAuditsBeforeAndAfter() {
        long id = definition(branchA, "Ca cũ", "08:00", "12:00", true);
        String oldName = (String) row(id).get("name");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'name':'  Ca mới  '}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("name", "Ca mới").containsEntry("startTime", "08:00")
                .containsEntry("endTime", "12:00").containsEntry("active", true);
        assertThat(row(id)).containsEntry("name", "Ca mới");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT action, actor_account_id, before_data->>'name' AS before_name, after_data->>'name' AS after_name
                FROM audit_logs WHERE entity_type = 'shift_definitions' AND entity_id = ?""", id);
        assertThat(audit).containsEntry("action", "SHIFT_DEFINITION_UPDATED").containsEntry("actor_account_id", managerA)
                .containsEntry("before_name", oldName).containsEntry("after_name", "Ca mới");
    }

    @Test
    void anUpdateThatChangesNothingKeepsTheRowAndWritesNoAudit() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);
        String name = (String) row(id).get("name");
        String xmin = xmin(id);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'name':'" + name + "','startTime':'08:00','endTime':'12:00','active':true}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(xmin(id)).isEqualTo(xmin);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void changingOnlyTheCaseOfTheNameIsAllowedAndItselfIsNotADuplicate() {
        assertThat(call(HttpMethod.POST, PATH, tokenA, "{'name':'ca đêm','startTime':'18:00','endTime':'22:00'}")
                .getStatusCode().value()).isEqualTo(201);
        long id = jdbc.queryForObject("SELECT id FROM shift_definitions WHERE branch_id = ? AND name = 'ca đêm'",
                Long.class, branchA);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'name':'Ca Đêm'}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(row(id)).containsEntry("name", "Ca Đêm");
    }

    @Test
    void renameToAnotherShiftsNameIsBrNs01AndLeavesTheRowUntouched() {
        assertThat(call(HttpMethod.POST, PATH, tokenA, "{'name':'Ca X','startTime':'08:00','endTime':'12:00'}")
                .getStatusCode().value()).isEqualTo(201);
        long id = definition(branchA, "Ca Y", "13:00", "17:00", true);
        String xmin = xmin(id);

        assertBusinessRule(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'name':'ca x'}"), "BR-NS-01");

        assertThat(xmin(id)).isEqualTo(xmin);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void updateRejectsEmptyOrInvalidBodiesAndLeavesTheRowUntouched() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);
        String xmin = xmin(id);

        List<String[]> cases = List.of(
                new String[] {"{}", "VALIDATION_FAILED"},
                new String[] {"{'name':null}", "VALIDATION_FAILED"},
                new String[] {"{'foo':1}", "VALIDATION_FAILED"},
                new String[] {"{'name':'   '}", "VALIDATION_FAILED"},
                new String[] {"{'name':'" + "x".repeat(61) + "'}", "VALIDATION_FAILED"},
                new String[] {"{'active':'yes'}", "MALFORMED_REQUEST"},
                new String[] {"{'startTime':'8:00'}", "MALFORMED_REQUEST"},
                new String[] {"[]", "MALFORMED_REQUEST"});
        for (String[] c : cases) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + id, tokenA, c[0]);
            assertThat(response.getStatusCode().value()).as(c[0]).isEqualTo(400);
            assertThat(errorCode(response)).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(message(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{}"))).contains("Cần gửi ít nhất một trường");
        assertThat(call(HttpMethod.PATCH, PATH + "/" + id, tokenA).getStatusCode().value()).isEqualTo(400);
        assertThat(xmin(id)).isEqualTo(xmin);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void timesChangeWhenTheShiftHasNoAssignmentsAndTheResultingOrderIsChecked() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);

        ResponseEntity<Map<String, Object>> ok = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'startTime':'07:30','endTime':'11:30'}");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(ok)).containsEntry("startTime", "07:30").containsEntry("endTime", "11:30");

        // chỉ đổi một giờ nhưng cặp kết quả đảo ngược (bắt đầu 11:30 → 12:00 > kết thúc 11:30)
        assertBusinessRule(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'startTime':'12:00'}"), "BR-NS-01");
        assertBusinessRule(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'endTime':'07:30'}"), "BR-NS-01");
        assertThat(row(id)).containsEntry("start_time", "07:30:00").containsEntry("end_time", "11:30:00");
    }

    @Test
    void aShiftWithAnyAssignmentEvenAPastCancelledOneCannotChangeItsTimesButCanBeRenamed() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);
        long staff = data.staff("RECEPTIONIST", branchA);
        data.assignment(branchA, staff, id, TODAY.minusDays(30), "CANCELLED", "MANAGER", managerA);
        String xmin = xmin(id);

        ResponseEntity<Map<String, Object>> retime = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'endTime':'13:00'}");
        assertBusinessRule(retime, "BR-NS-01");
        assertThat(message(retime)).startsWith("Ca đã có phân ca, không đổi được giờ");
        assertThat(xmin(id)).isEqualTo(xmin);

        // gửi lại đúng giờ cũ không phải là đổi giờ
        assertThat(call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'name':'Ca đã đổi tên','startTime':'08:00','endTime':'12:00'}").getStatusCode().value())
                .isEqualTo(200);
        assertThat(row(id)).containsEntry("name", "Ca đã đổi tên").containsEntry("end_time", "12:00:00");
    }

    @Test
    void deactivatingIsBlockedByAScheduledAssignmentFromTodayOnButNotByOtherOnes() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);
        long staff = data.staff("VET", branchA);
        data.assignment(branchA, staff, id, TODAY.minusDays(1), "SCHEDULED", "SELF", null);
        data.assignment(branchA, staff, id, TODAY.plusDays(3), "ON_LEAVE", "MANAGER", managerA);
        data.assignment(branchA, staff, id, TODAY.plusDays(4), "CANCELLED", "MANAGER", managerA);
        long today = scheduled(branchA, staff, id, TODAY);

        ResponseEntity<Map<String, Object>> blocked = call(HttpMethod.PATCH, PATH + "/" + id, tokenA,
                "{'active':false}");
        assertBusinessRule(blocked, "BR-NS-01");
        assertThat(message(blocked)).startsWith("Ca còn phân ca từ hôm nay trở đi");
        assertThat(row(id)).containsEntry("is_active", true);

        jdbc.update("DELETE FROM shift_assignments WHERE id = ?", today);
        long tomorrow = scheduled(branchA, staff, id, TODAY.plusDays(1));
        assertBusinessRule(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'active':false}"), "BR-NS-01");

        jdbc.update("DELETE FROM shift_assignments WHERE id = ?", tomorrow);
        ResponseEntity<Map<String, Object>> ok = call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'active':false}");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(ok)).containsEntry("active", false);
        assertThat(row(id)).containsEntry("is_active", false);
    }

    @Test
    void aReactivatedShiftIsListedAgainAndItsNameStaysReserved() {
        assertThat(call(HttpMethod.POST, PATH, tokenA, "{'name':'Ca giữ tên','startTime':'08:00','endTime':'12:00'}")
                .getStatusCode().value()).isEqualTo(201);
        long id = jdbc.queryForObject("SELECT id FROM shift_definitions WHERE branch_id = ?", Long.class, branchA);

        assertThat(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'active':false}").getStatusCode().value())
                .isEqualTo(200);
        assertThat(ids(call(HttpMethod.GET, PATH, receptionistToken))).isEmpty();
        assertBusinessRule(call(HttpMethod.POST, PATH, tokenA,
                "{'name':'ca giữ tên','startTime':'13:00','endTime':'17:00'}"), "BR-NS-01");

        assertThat(call(HttpMethod.PATCH, PATH + "/" + id, tokenA, "{'active':true}").getStatusCode().value())
                .isEqualTo(200);
        assertThat(ids(call(HttpMethod.GET, PATH, receptionistToken))).containsExactly(id);
        assertThat(auditCount(id)).as("tạo + ngừng + bật lại").isEqualTo(3);
    }

    @Test
    void updateOfAMissingShiftIs404EvenWhenTheBodyBreaksABusinessRule() {
        ResponseEntity<Map<String, Object>> missing = call(HttpMethod.PATCH, PATH + "/999999999", tokenA,
                "{'startTime':'12:00','endTime':'08:00'}");

        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(errorCode(missing)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(call(HttpMethod.PATCH, PATH + "/abc", tokenA, "{'name':'x'}").getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void updateOfAnotherBranchesShiftIs403EvenWhenTheBodyBreaksABusinessRule() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);
        String xmin = xmin(id);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + id, tokenB,
                "{'startTime':'12:00','endTime':'08:00'}");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(xmin(id)).isEqualTo(xmin);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void thereIsNoDeleteAndNoPutAndOnlyJsonIsAccepted() {
        long id = definition(branchA, "Ca", "08:00", "12:00", true);

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, tokenA).getStatusCode().value()).isEqualTo(405);
        assertThat(call(HttpMethod.PUT, PATH + "/" + id, tokenA, "{'name':'x'}").getStatusCode().value())
                .isEqualTo(405);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setBearerAuth(tokenA);
        ResponseEntity<Map<String, Object>> text = http.exchange(PATH, HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("x", headers), MAP);
        assertThat(text.getStatusCode().value()).isEqualTo(415);
        assertThat(definitionCount(branchA)).isEqualTo(1);
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return listOf(response).stream().map(m -> ((Number) m.get("shiftDefinitionId")).longValue()).toList();
    }
}
