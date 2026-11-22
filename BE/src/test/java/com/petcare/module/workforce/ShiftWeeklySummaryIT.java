package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Thống kê số ca mỗi nhân viên trong tuần (workforce-v1 #16; docs/adr/0138 mục "Thống kê tuần") trên Postgres thật:
 * ai có mặt (A06–A08 {@code ACTIVE} không khóa của chi nhánh người gọi, kể cả người 0 ca), cách đếm (chỉ
 * {@code SCHEDULED}, cả SELF và MANAGER, mọi chi nhánh, biên tuần Chủ nhật / thứ Hai), định mức [CFG], thứ tự tên tiếng
 * Việt, chi nhánh trống, ma trận đầu vào của {@code weekStart} (không bao giờ 500) và không ghi gì. Xác thực / phân
 * quyền ở {@code ShiftWeeklySummarySecurityIT}, transaction ở {@code ShiftWeeklySummaryTxIT}. Era <b>năm 2090</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftWeeklySummaryIT.TestBeans.class})
class ShiftWeeklySummaryIT extends ShiftWeeklySummaryItSupport {

    private static final Instant NOW = Instant.parse("2090-03-14T01:00:00Z");
    /** Thứ Hai của tuần được seed. */
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2090, 3, 14));

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
    private long caretaker;
    private long morning;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        caretaker = data.staff("CARETAKER", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- nội dung

    @Test
    void listsEveryActiveStaffMemberOfTheCallersBranchWithEveryContractFieldIncludingThoseWithoutShifts() {
        schedule(receptionist, 3);
        schedule(caretaker, 10);

        ResponseEntity<Map<String, Object>> response = summaries(token(manager), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("code", 200);
        Map<Long, Map<String, Object>> rows = byStaff(response);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(receptionist, vet, caretaker);
        assertThat(rows.values()).allSatisfy(row -> {
            assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
            assertThat(intOf(row, "requiredShifts")).isEqualTo(10);
        });
        assertThat(rows.get(receptionist)).containsEntry("role", "RECEPTIONIST").containsEntry("shiftCount", 3)
                .containsEntry("meetsRequirement", false);
        assertThat(rows.get(vet)).containsEntry("role", "VET").containsEntry("shiftCount", 0)
                .containsEntry("meetsRequirement", false);
        assertThat(rows.get(caretaker)).containsEntry("role", "CARETAKER").containsEntry("shiftCount", 10)
                .containsEntry("meetsRequirement", true);
        assertThat(rows.get(vet).get("staffName")).isEqualTo(jdbc.queryForObject(
                "SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class, vet));
    }

    @Test
    void theRequirementIsMetFromExactlyTheQuotaUpward() {
        schedule(receptionist, 9);
        schedule(vet, 10);
        schedule(caretaker, 11);

        Map<Long, Map<String, Object>> rows = byStaff(summaries(token(manager), WEEK));

        assertThat(rows.get(receptionist)).containsEntry("shiftCount", 9).containsEntry("meetsRequirement", false);
        assertThat(rows.get(vet)).containsEntry("shiftCount", 10).containsEntry("meetsRequirement", true);
        assertThat(rows.get(caretaker)).containsEntry("shiftCount", 11).containsEntry("meetsRequirement", true);
    }

    @Test
    void countsOnlyScheduledRowsOfTheWeekFromBothSourcesAndTheEdgesOfTheWeekAreExact() {
        long afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null); // thứ Hai: tính
        data.assignment(branchA, receptionist, afternoon, WEEK, "SCHEDULED", "MANAGER", null); // MANAGER: tính
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(6), "SCHEDULED", "SELF", null); // Chủ nhật: tính
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(1), "ON_LEAVE", "SELF", manager);
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(2), "CANCELLED", "SELF", manager);
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(3), "ABSENT", "MANAGER", manager);
        data.assignment(branchA, receptionist, morning, WEEK.minusDays(1), "SCHEDULED", "SELF", null); // Chủ nhật trước
        data.assignment(branchA, receptionist, morning, WEEK.plusDays(7), "SCHEDULED", "SELF", null); // thứ Hai sau
        data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null); // người khác

        Map<Long, Map<String, Object>> rows = byStaff(summaries(token(manager), WEEK));

        assertThat(rows.get(receptionist)).containsEntry("shiftCount", 3);
        assertThat(rows.get(vet)).containsEntry("shiftCount", 1);
        assertThat(rows.get(caretaker)).containsEntry("shiftCount", 0);
    }

    @Test
    void aStaffMemberTransferredMidWeekIsCountedWithTheShiftsLeftAtTheOldBranchLikeTheirOwnView() {
        long inB = definition(branchB, "Ca B", "08:00", "12:00", true);
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null); // đã bắt đầu, còn ở chi nhánh cũ
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, receptionist);
        data.assignment(branchB, receptionist, inB, WEEK.plusDays(1), "SCHEDULED", "MANAGER", null);

        Map<Long, Map<String, Object>> asNewManager = byStaff(summaries(token(data.staff("BRANCH_MANAGER", branchB)), WEEK));
        Map<Long, Map<String, Object>> asOldManager = byStaff(summaries(token(manager), WEEK));

        assertThat(asNewManager.get(receptionist)).containsEntry("shiftCount", 2);
        assertThat(asOldManager).doesNotContainKey(receptionist);
        assertThat(intOf(dataOf(registration(token(receptionist), WEEK)), "shiftCount"))
                .as("số nhân viên tự thấy ở #17").isEqualTo(2);
    }

    // ---------------------------------------------------------------- ai có mặt

    @Test
    void doesNotListOtherBranchesManagersLockedDisabledOrNonStaffAccounts() {
        long otherBranchVet = data.staff("VET", branchB);
        long locked = data.staff("VET", branchA);
        long disabled = data.staff("CARETAKER", branchA);
        long secondManager = data.staff("BRANCH_MANAGER", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", locked);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        data.assignment(branchB, otherBranchVet, definition(branchB, "Ca B", "08:00", "12:00", true), WEEK, "SCHEDULED",
                "SELF", null);

        Map<Long, Map<String, Object>> rows = byStaff(summaries(token(manager), WEEK));

        assertThat(rows.keySet()).containsExactlyInAnyOrder(receptionist, vet, caretaker);
        assertThat(rows.keySet()).doesNotContain(otherBranchVet, locked, disabled, manager, secondManager);
    }

    @Test
    void aBranchWithoutSchedulableStaffIsAnEmptyListNotNull() {
        long loneBranch = data.branch();
        long loneManager = data.staff("BRANCH_MANAGER", loneBranch);

        ResponseEntity<Map<String, Object>> response = summaries(token(loneManager), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("data")).isInstanceOf(List.class);
        assertThat(listOf(response)).isEmpty();
    }

    // ---------------------------------------------------------------- thứ tự, định mức

    @Test
    void rowsAreSortedByVietnameseNameThenStaffIdAcrossRoles() {
        long second = data.staff("VET", branchA);
        long third = data.staff("RECEPTIONIST", branchA);
        rename(receptionist, "Đào");
        rename(vet, "An");
        rename(caretaker, "Bình");
        rename(second, "An");
        rename(third, "Dũng");

        List<Map<String, Object>> rows = listOf(summaries(token(manager), WEEK));

        assertThat(rows).extracting(row -> row.get("staffName")).containsExactly("An", "An", "Bình", "Dũng", "Đào");
        assertThat(rows).extracting(row -> idOf(row, "staffId"))
                .containsExactly(Math.min(vet, second), Math.max(vet, second), caretaker, third, receptionist);
    }

    @Test
    void theQuotaFollowsTheCurrentConfigValueAndEveryRowCarriesIt() {
        schedule(receptionist, 12);
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, QUOTA_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = '13' WHERE key = ?", QUOTA_KEY);
            configService.reload();

            Map<Long, Map<String, Object>> rows = byStaff(summaries(token(manager), WEEK));

            assertThat(rows.values()).allSatisfy(row -> assertThat(intOf(row, "requiredShifts")).isEqualTo(13));
            assertThat(rows.get(receptionist)).containsEntry("shiftCount", 12).containsEntry("meetsRequirement", false);
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, QUOTA_KEY);
            configService.reload();
        }
        assertThat(byStaff(summaries(token(manager), WEEK)).get(receptionist)).containsEntry("meetsRequirement", true);
    }

    @Test
    void anyMondayInTheSupportedRangeIsAcceptedEvenInThePastOrFarAhead() {
        for (LocalDate monday : new LocalDate[] {firstMondayOf(2000), lastMondayOf(2100), WEEK.minusWeeks(52)}) {
            assertThat(summaries(token(manager), monday).getStatusCode().value()).as("%s", monday).isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- đầu vào

    @Test
    void weekStartMatrixNeverReaches500AndWritesNothing() {
        String token = token(manager);
        String before = footprint(manager, receptionist, vet, caretaker);

        for (String notMonday : new String[] {WEEK.plusDays(1).toString(), WEEK.plusDays(6).toString(),
                WEEK.minusDays(1).toString()}) {
            assertBusinessRule(summaries(token, notMonday), "BR-NS-11");
        }
        for (LocalDate outOfRange : new LocalDate[] {firstMondayOf(1999), firstMondayOf(2101), lastMondayOf(1999)}) {
            assertBusinessRule(summaries(token, outOfRange), "BR-NS-11");
        }
        assertBusinessRule(summariesRaw(token, "weekStart=%2B999999-01-05"), "BR-NS-11");
        assertBusinessRule(summariesRaw(token, "weekStart=-0001-01-04"), "BR-NS-11");
        for (String malformed : new String[] {"abc", "2090-13-40", "2090%2F03%2F20", "", "20900320"}) {
            ResponseEntity<Map<String, Object>> response = summariesRaw(token, "weekStart=" + malformed);

            assertThat(response.getStatusCode().value()).as("weekStart=%s", malformed).isEqualTo(400);
            assertThat(errorCode(response)).as("weekStart=%s", malformed).isEqualTo("MALFORMED_REQUEST");
        }
        ResponseEntity<Map<String, Object>> missing = summariesRaw(token, "");

        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(missing)).isEqualTo("MALFORMED_REQUEST");
        assertThat(footprint(manager, receptionist, vet, caretaker)).isEqualTo(before);
    }

    @Test
    void aReadWritesNothingAndTheSameCallTwiceGivesTheSameAnswer() {
        schedule(receptionist, 4);
        String token = token(manager);
        String before = footprint(manager, receptionist, vet, caretaker);

        ResponseEntity<Map<String, Object>> first = summaries(token, WEEK);
        ResponseEntity<Map<String, Object>> second = summaries(token, WEEK);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(listOf(second)).isEqualTo(listOf(first));
        assertThat(footprint(manager, receptionist, vet, caretaker)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- hạ tầng

    /** {@code count} phân ca {@code SCHEDULED} của nhân viên trong tuần, mỗi ngày một ca (tối đa 7 ngày × 2 ca). */
    private void schedule(long staffId, int count) {
        long afternoon = definition(branchA, "Ca chiều " + staffId, "13:00", "17:00", true);
        for (int i = 0; i < count; i++) {
            long definition = i < 7 ? morning : afternoon;
            data.assignment(branchA, staffId, definition, WEEK.plusDays(i % 7), "SCHEDULED", "SELF", null);
        }
    }
}
