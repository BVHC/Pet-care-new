package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Đăng ký ca của tôi trong tuần (workforce-v1 #17; docs/adr/0138 mục "Đăng ký tuần — đọc #17") trên Postgres thật:
 * nội dung (chỉ phân ca {@code SCHEDULED} của chính người gọi, cả SELF và MANAGER, mọi chi nhánh), biên tuần, định
 * mức [CFG], {@code editableUntil}, tuần trống / quá khứ / xa, ma trận đầu vào của {@code weekStart} (không bao giờ
 * 500), không ghi gì. Xác thực / phân quyền ở {@code ShiftRegistrationReadSecurityIT}, transaction ở
 * {@code ShiftRegistrationReadTxIT}. Era <b>năm 2083</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationReadIT.TestBeans.class})
class ShiftRegistrationReadIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2083-03-14T01:00:00Z");
    /** Thứ Hai của tuần được seed (kể từ ngày của {@code NOW}). */
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2083, 3, 14));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private SystemConfigService configService;

    private long branchA;
    private long branchB;
    private long manager;
    private long receptionist;
    private long vet;
    private long morning;
    private long afternoon;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:15", "17:45", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- nội dung

    @Test
    void returnsOnlyTheCallersScheduledRowsOfTheWeekFromBothSourcesWithEveryContractField() {
        long pm = data.assignment(branchA, receptionist, afternoon, WEEK, "SCHEDULED", "SELF", null);
        long am = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "MANAGER", null);
        long sunday = data.assignment(branchA, receptionist, morning, WEEK.plusDays(6), "SCHEDULED", "SELF", null);
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(1), "ON_LEAVE", "SELF", manager);
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(2), "CANCELLED", "SELF", manager);
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(3), "ABSENT", "MANAGER", manager);
        data.assignment(branchA, receptionist, morning, WEEK.minusDays(1), "SCHEDULED", "SELF", null); // Chủ nhật trước
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(7), "SCHEDULED", "SELF", null); // thứ Hai sau
        data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null); // người khác, cùng ca cùng ngày
        long request = pendingRequest(pm, receptionist);

        ResponseEntity<Map<String, Object>> response = registration(token(receptionist), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REGISTRATION_KEYS);
        assertThat(body).containsEntry("weekStart", WEEK.toString());
        assertThat(intOf(body, "requiredShifts")).isEqualTo(10);
        assertThat(intOf(body, "shiftCount")).isEqualTo(3);
        List<Map<String, Object>> rows = assignmentsOf(response);
        assertThat(rows).hasSize(intOf(body, "shiftCount"));
        assertThat(rows).extracting(row -> idOf(row, "shiftAssignmentId")).containsExactly(am, pm, sunday);
        assertThat(rows).extracting(row -> row.get("source")).containsExactly("MANAGER", "SELF", "SELF");
        assertThat(rows).extracting(row -> row.get("startTime")).containsExactly("08:00", "13:15", "08:00");
        assertThat(rows).extracting(row -> row.get("endTime")).containsExactly("12:00", "17:45", "12:00");
        assertThat(rows).extracting(row -> row.get("workDate"))
                .containsExactly(WEEK.toString(), WEEK.toString(), WEEK.plusDays(6).toString());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
            assertThat(row).containsEntry("status", "SCHEDULED").containsEntry("reason", null);
            assertThat(idOf(row, "staffId")).isEqualTo(receptionist);
            assertThat(idOf(row, "branchId")).isEqualTo(branchA);
            assertThat(row.get("staffName")).isNotNull();
        });
        assertThat(rows.get(0).get("shiftName").toString()).startsWith("Ca sáng");
        assertThat(rows.get(1).get("shiftName").toString()).startsWith("Ca chiều");
        assertThat(rows.get(0)).containsEntry("pendingRequestId", null);
        assertThat(rows.get(1)).containsEntry("pendingRequestId", (int) request);
    }

    @Test
    void editableUntilIsMidnightOfMondayInVietnamAsAnInstant() {
        ResponseEntity<Map<String, Object>> response = registration(token(receptionist), WEEK);

        Instant deadline = Instant.parse((String) dataOf(response).get("editableUntil"));

        // 00:00 giờ Việt Nam (UTC+7) = 17:00 UTC của ngày hôm trước, tính tay chứ không dùng lại công thức của code.
        assertThat(deadline).isEqualTo(Instant.parse(WEEK.minusDays(1) + "T17:00:00Z"));
        assertThat(OffsetDateTime.ofInstant(deadline, java.time.ZoneOffset.ofHours(7)).toLocalDateTime())
                .isEqualTo(WEEK.atStartOfDay());
    }

    @Test
    void aWeekWithoutAssignmentsIsAnEmptyArrayAndZeroShiftsButStillReportsTheQuota() {
        ResponseEntity<Map<String, Object>> response = registration(token(receptionist), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.get("assignments")).isNotNull().isEqualTo(List.of());
        assertThat(intOf(body, "shiftCount")).isZero();
        assertThat(intOf(body, "requiredShifts")).isEqualTo(10);
    }

    @Test
    void theQuotaFollowsTheCurrentConfigValue() {
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class,
                QUOTA_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = '13' WHERE key = ?", QUOTA_KEY);
            configService.reload();

            assertThat(intOf(dataOf(registration(token(receptionist), WEEK)), "requiredShifts")).isEqualTo(13);
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, QUOTA_KEY);
            configService.reload();
        }
        assertThat(intOf(dataOf(registration(token(receptionist), WEEK)), "requiredShifts")).isEqualTo(10);
    }

    @Test
    void aStaffMemberTransferredMidWeekStillSeesTheOldBranchRowsAndTheNewOnes() {
        long inB = definition(branchB, "Ca B", "08:00", "12:00", true);
        long old = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, receptionist);
        long fresh = data.assignment(branchB, receptionist, inB, WEEK.plusDays(1), "SCHEDULED", "MANAGER", null);

        ResponseEntity<Map<String, Object>> response = registration(token(receptionist), WEEK);

        assertThat(assignmentsOf(response)).extracting(row -> idOf(row, "shiftAssignmentId"))
                .containsExactly(old, fresh);
        assertThat(assignmentsOf(response)).extracting(row -> idOf(row, "branchId")).containsExactly(branchA, branchB);
        assertThat(intOf(dataOf(response), "shiftCount")).isEqualTo(2);
    }

    @Test
    void extraStaffIdAndBranchIdParametersCannotShowAnotherPersonsRegistration() {
        long mine = data.assignment(branchA, receptionist, afternoon, WEEK, "SCHEDULED", "SELF", null);
        data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
                REGISTRATIONS + WEEK + "?staffId=" + vet + "&branchId=" + branchB, token(receptionist));

        assertThat(assignmentsOf(response)).singleElement()
                .satisfies(row -> assertThat(idOf(row, "shiftAssignmentId")).isEqualTo(mine));
    }

    @Test
    void twoStaffInTheSameBranchAndWeekEachSeeOnlyTheirOwn() {
        long mine = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        long theirs = data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null);

        assertThat(assignmentsOf(registration(token(receptionist), WEEK))).extracting(row -> idOf(row, "shiftAssignmentId"))
                .containsExactly(mine);
        assertThat(assignmentsOf(registration(token(vet), WEEK))).extracting(row -> idOf(row, "shiftAssignmentId"))
                .containsExactly(theirs);
    }

    @Test
    void pastAndFarWeeksInsideTheSupportedYearsAreStill200() {
        String token = token(receptionist);
        for (LocalDate week : List.of(WEEK.minusWeeks(10), firstMondayOf(2000), lastMondayOf(2100))) {
            ResponseEntity<Map<String, Object>> response = registration(token, week);

            assertThat(response.getStatusCode().value()).as(week.toString()).isEqualTo(200);
            assertThat(assignmentsOf(response)).as(week.toString()).isEmpty();
        }
    }

    @Test
    void callingTwiceGivesTheSameBody() {
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        String token = token(receptionist);

        assertThat(registration(token, WEEK).getBody()).isEqualTo(registration(token, WEEK).getBody());
    }

    // ---------------------------------------------------------------- đầu vào của weekStart

    @Test
    void everyDayOfTheWeekButMondayIsBrNs11() {
        String token = token(receptionist);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day == DayOfWeek.MONDAY) {
                continue;
            }
            ResponseEntity<Map<String, Object>> response = registration(token, WEEK.with(day));

            assertBusinessRule(response, "BR-NS-11");
            assertThat(message(response)).as(day.toString()).startsWith("Ngày bắt đầu tuần phải là thứ Hai");
        }
    }

    @Test
    void aMondayOutsideYears2000To2100IsBrNs11NotAServerError() {
        String token = token(receptionist);

        for (LocalDate week : List.of(lastMondayOf(1999), firstMondayOf(2101))) {
            ResponseEntity<Map<String, Object>> response = registration(token, week);

            assertBusinessRule(response, "BR-NS-11");
            assertThat(message(response)).as(week.toString()).startsWith("Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100");
        }
        assertThat(registration(token, firstMondayOf(2000)).getStatusCode().value()).isEqualTo(200);
        assertThat(registration(token, lastMondayOf(2100)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aNonMondayInAnUnsupportedYearIsReportedAsNotMondayFirst() {
        assertThat(message(registration(token(receptionist), LocalDate.of(1999, 12, 29))))
                .startsWith("Ngày bắt đầu tuần phải là thứ Hai");
    }

    @Test
    void aMalformedDateIs400MalformedRequest() {
        String token = token(receptionist);

        for (String path : List.of("abc", "2083-13-01", "20830315", WEEK + "T00:00:00", "15-03-2083", "null")) {
            ResponseEntity<Map<String, Object>> response = registration(token, path);

            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(errorCode(response)).as(path).isEqualTo("MALFORMED_REQUEST");
        }
    }

    @Test
    void anImpossibleOrAbsurdDateIsAlways400AndNeverAServerError() {
        String token = token(receptionist);

        for (String path : List.of("2083-02-30", "10000-01-04", "+999999999-12-27", "-000001-01-03", "0000-01-03")) {
            ResponseEntity<Map<String, Object>> response = registration(token, path);

            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(errorCode(response)).as(path).isIn("MALFORMED_REQUEST", "BUSINESS_RULE_VIOLATION");
        }
    }

    @Test
    void aMissingOrExtraPathSegmentIs404NotFound() {
        String token = token(receptionist);

        for (String path : List.of("/api/me/shift-registrations/", "/api/me/shift-registrations",
                REGISTRATIONS + WEEK + "/x")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, path, token);

            assertThat(response.getStatusCode().value()).as(path).isEqualTo(404);
            assertThat(errorCode(response)).as(path).isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void otherMethodsOnTheSamePathAre405() {
        String token = token(receptionist);

        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.DELETE)) {
            ResponseEntity<Map<String, Object>> response = call(method, REGISTRATIONS + WEEK, token);

            assertThat(response.getStatusCode().value()).as(method.name()).isEqualTo(405);
            assertThat(errorCode(response)).isEqualTo("METHOD_NOT_ALLOWED");
        }
    }

    // ---------------------------------------------------------------- không ghi gì

    @Test
    void neitherASuccessNorABusinessRuleErrorLeavesAnyTrace() {
        long mine = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        pendingRequest(mine, receptionist);
        data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "MANAGER", null);
        String token = token(receptionist);
        String before = footprint(receptionist, vet, manager);

        assertThat(registration(token, WEEK).getStatusCode().value()).isEqualTo(200);
        assertThat(registration(token, WEEK.plusDays(1)).getStatusCode().value()).isEqualTo(400);
        assertThat(registration(token, firstMondayOf(2101)).getStatusCode().value()).isEqualTo(400);
        assertThat(registration(token, "abc").getStatusCode().value()).isEqualTo(400);

        assertThat(footprint(receptionist, vet, manager)).isEqualTo(before);
    }
}
