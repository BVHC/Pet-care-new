package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Đăng ký ca theo tuần (workforce-v1 #15; docs/adr/0149 mục "Đăng ký tuần — ghi #15") trên Postgres thật: hiệu ứng đủ
 * cột (Phân ca#1 và #7, Yêu cầu#5), response bằng đúng {@code GET} #17, phần khác biệt (mục giữ nguyên không đổi
 * {@code xmin}), gửi lại cùng nội dung không ghi gì, phân ca {@code MANAGER} được đếm và kiểm chồng giờ nhưng không bao
 * giờ bị hủy, mọi lỗi mục gom vào một message {@code BR-NS-11} và không để lại dấu vết, hạn 00:00 thứ Hai, định mức
 * [CFG], ma trận lỗi hình thức (không bao giờ 5xx). Xác thực / phân quyền ở {@code ShiftRegistrationPutSecurityIT}, khóa
 * ở {@code …PutLockIT}, rollback ở {@code …PutRollbackIT}. Era <b>năm 2086</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationPutIT.TestBeans.class})
class ShiftRegistrationPutIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2086-03-14T01:00:00Z");
    /** Thứ Hai đầu tiên sau ngày của {@code NOW}: hạn 00:00 của tuần này còn ở tương lai. */
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2086, 3, 15));
    private static final String MESSAGE = "Đã lưu đăng ký ca của tuần";

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
    private long noon;
    private long evening;
    private long retired;
    private long foreign;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
        noon = definition(branchA, "Ca giữa", "11:00", "14:00", true);
        evening = definition(branchA, "Ca tối", "17:00", "20:00", true);
        retired = definition(branchA, "Ca cũ", "08:00", "12:00", false);
        foreign = definition(branchB, "Ca chi nhánh khác", "08:00", "12:00", true);
        token = token(receptionist);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- đường chính

    @Test
    void savesTheWholeWeekAsSelfRowsAndReturnsExactlyWhatTheReadReturnsWithoutAuditOrOutbox() {
        long audit = totalAuditCount();
        long outbox = totalOutboxCount();

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, ten());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(message(response)).isEqualTo(MESSAGE);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REGISTRATION_KEYS);
        assertThat(body).containsEntry("weekStart", WEEK.toString());
        assertThat(intOf(body, "requiredShifts")).isEqualTo(10);
        assertThat(intOf(body, "shiftCount")).isEqualTo(10);
        assertThat(assignmentsOf(response)).hasSize(10).allSatisfy(row -> {
            assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
            assertThat(row).containsEntry("source", "SELF").containsEntry("status", "SCHEDULED")
                    .containsEntry("reason", null).containsEntry("pendingRequestId", null);
            assertThat(idOf(row, "staffId")).isEqualTo(receptionist);
            assertThat(idOf(row, "branchId")).isEqualTo(branchA);
        });
        assertThat(rowsOf(receptionist)).hasSize(10).allSatisfy(row -> {
            assertThat(row).containsEntry("source", "SELF").containsEntry("status", "SCHEDULED")
                    .containsEntry("reason", null).containsEntry("status_changed_by", null)
                    .containsEntry("status_changed_at", null);
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(branchA);
        });
        assertThat(dataOf(registration(token, WEEK))).isEqualTo(body);
        assertThat(totalAuditCount()).as("BR-NS-10: đăng ký tuần không ghi audit").isEqualTo(audit);
        assertThat(totalOutboxCount()).as("BR-NS-08: không thông báo").isEqualTo(outbox);
    }

    @Test
    void theResponseOrderDoesNotDependOnTheOrderOfTheItemsSent() {
        List<String> items = tenItems();
        Collections.reverse(items);

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, itemsBody(items.toArray(String[]::new)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentsOf(response)).extracting(row -> row.get("workDate").toString() + row.get("startTime"))
                .isSorted();
    }

    @Test
    void replacingTheWeekCancelsDroppedSelfRowsCreatesNewOnesAndLeavesKeptRowsUntouched() {
        List<Long> seeded = seedTen(receptionist);
        Map<Long, String> before = xminsOf(receptionist);
        long dropped = seeded.get(9); // Thứ Sáu, ca chiều
        String body = bodyExcept(WEEK.plusDays(4), afternoon, item(WEEK.plusDays(5), morning));

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(intOf(dataOf(response), "shiftCount")).isEqualTo(10);
        assertThat(rowOf(dropped)).containsEntry("status", "CANCELLED").containsEntry("reason", SELF_EDIT_REASON)
                .containsEntry("status_changed_by", receptionist).containsKey("status_changed_at");
        assertThat(rowOf(dropped).get("status_changed_at")).isNotNull();
        for (Long kept : seeded.subList(0, 9)) {
            assertThat(xminsOf(receptionist).get(kept)).as("mục giữ nguyên %d không bị ghi", kept).isEqualTo(before.get(kept));
            assertThat(rowOf(kept)).containsEntry("status", "SCHEDULED");
        }
        List<Map<String, Object>> scheduled = scheduledRowsOf(receptionist);
        assertThat(scheduled).hasSize(10);
        assertThat(scheduled.get(9)).containsEntry("work_date", WEEK.plusDays(5).toString())
                .containsEntry("source", "SELF").containsEntry("shift_definition_id", morning);
    }

    @Test
    void aDroppedRowWithAPendingRequestClosesItBySystemAndAKeptRowKeepsItsPendingRequest() {
        List<Long> seeded = seedTen(receptionist);
        long droppedRequest = pendingRequest(seeded.get(9), receptionist);
        long keptRequest = pendingRequest(seeded.get(0), receptionist);

        ResponseEntity<Map<String, Object>> response = put(token, WEEK,
                bodyExcept(WEEK.plusDays(4), afternoon, item(WEEK.plusDays(5), morning)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(requestRow(droppedRequest)).containsEntry("status", "REJECTED")
                .containsEntry("decision_note", EDITED_NOTE).containsEntry("decided_by", null);
        assertThat(requestRow(droppedRequest).get("closed_at")).isNotNull();
        assertThat(requestRow(keptRequest)).containsEntry("status", "PENDING").containsEntry("closed_at", null);
        assertThat(assignmentsOf(response)).filteredOn(row -> idOf(row, "shiftAssignmentId") == seeded.get(0))
                .singleElement().satisfies(row -> assertThat(idOf(row, "pendingRequestId")).isEqualTo(keptRequest));
    }

    @Test
    void resendingTheSameContentWritesNothingAtAll() {
        assertThat(put(token, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        Map<Long, String> before = xminsOf(receptionist);
        String footprint = trace(receptionist, vet);

        ResponseEntity<Map<String, Object>> again = put(token, WEEK, ten());

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(xminsOf(receptionist)).isEqualTo(before);
        assertThat(trace(receptionist, vet)).isEqualTo(footprint);
        assertThat(intOf(dataOf(again), "shiftCount")).isEqualTo(10);
    }

    // ---------------------------------------------------------------- phân ca MANAGER

    @Test
    void managerRowsAreCountedTowardsTheQuotaAndNeverTouched() {
        long sat1 = data.assignment(branchA, receptionist, morning, WEEK.plusDays(5), "SCHEDULED", "MANAGER", null);
        long sat2 = data.assignment(branchA, receptionist, afternoon, WEEK.plusDays(5), "SCHEDULED", "MANAGER", null);
        long sun = data.assignment(branchA, receptionist, morning, WEEK.plusDays(6), "SCHEDULED", "MANAGER", null);
        Map<Long, String> before = xminsOf(receptionist);
        List<String> seven = tenItems().subList(0, 7);

        ResponseEntity<Map<String, Object>> ok = put(token, WEEK, itemsBody(seven.toArray(String[]::new)));

        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(intOf(dataOf(ok), "shiftCount")).as("7 SELF + 3 MANAGER").isEqualTo(10);
        assertThat(assignmentsOf(ok)).extracting(row -> row.get("source")).containsOnly("SELF", "MANAGER");
        for (long managed : new long[] {sat1, sat2, sun}) {
            assertThat(rowOf(managed)).containsEntry("status", "SCHEDULED").containsEntry("source", "MANAGER");
            assertThat(xminsOf(receptionist).get(managed)).isEqualTo(before.get(managed));
        }

        ResponseEntity<Map<String, Object>> short1 = put(token, WEEK, itemsBody(seven.subList(0, 6).toArray(String[]::new)));

        assertBusinessRule(short1, "BR-NS-12");
        assertThat(message(short1)).contains("có 9 ca (gồm 3 ca do quản lý xếp)").contains("còn thiếu 1");
        assertThat(scheduledRowsOf(receptionist)).as("lần lỗi không đổi gì").hasSize(10);
    }

    @Test
    void anItemOnTheSamePairAsAManagerRowIsAnOverlapAndNothingIsSaved() {
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "MANAGER", null);
        String before = trace(receptionist, vet);

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, ten());

        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).contains("Mục 1: chồng giờ với phân ca do quản lý xếp");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    @Test
    void anItemThatOverlapsAManagerRowOfADifferentShiftIsRejectedToo() {
        data.assignment(branchA, receptionist, afternoon, WEEK, "SCHEDULED", "MANAGER", null);
        List<String> items = new ArrayList<>(tenItems().subList(2, 10)); // bỏ Thứ Hai
        items.add(item(WEEK, noon));                                      // 11:00–14:00 chồng ca chiều 13:00–17:00
        items.add(item(WEEK.plusDays(5), morning));

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, itemsBody(items.toArray(String[]::new)));

        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).contains("chồng giờ với phân ca do quản lý xếp ngày")
                .contains("(13:00–17:00)");
    }

    // ---------------------------------------------------------------- dữ liệu khác không bị ảnh hưởng

    @Test
    void anExistingSelfRowNeverBlocksAReplacementThatOverlapsItBecauseItIsBeingReplaced() {
        long old = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        List<String> items = new ArrayList<>(tenItems().subList(2, 10));
        items.add(item(WEEK, noon));                // 11:00–14:00 chồng ca sáng cũ của chính mình
        items.add(item(WEEK.plusDays(5), morning));

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, itemsBody(items.toArray(String[]::new)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(rowOf(old)).containsEntry("status", "CANCELLED");
        assertThat(scheduledRowsOf(receptionist)).hasSize(10);
    }

    @Test
    void otherWeeksOtherStaffAndNonScheduledRowsAreNeitherCountedNorTouched() {
        long previousWeek = data.assignment(branchA, receptionist, morning, WEEK.minusDays(1), "SCHEDULED", "SELF", null);
        long nextWeek = data.assignment(branchA, receptionist, morning, WEEK.plusDays(7), "SCHEDULED", "SELF", null);
        long onLeave = data.assignment(branchA, receptionist, morning, WEEK, "ON_LEAVE", "SELF", manager);
        long cancelled = data.assignment(branchA, receptionist, afternoon, WEEK, "CANCELLED", "SELF", manager);
        long absent = data.assignment(branchA, receptionist, morning, WEEK.plusDays(1), "ABSENT", "MANAGER", manager);
        long colleague = data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null);
        Map<Long, String> mine = xminsOf(receptionist);
        Map<Long, String> theirs = xminsOf(vet);

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, ten());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(intOf(dataOf(response), "shiftCount")).isEqualTo(10);
        for (long untouched : new long[] {previousWeek, nextWeek, onLeave, cancelled, absent}) {
            assertThat(xminsOf(receptionist).get(untouched)).isEqualTo(mine.get(untouched));
        }
        assertThat(xminsOf(vet)).isEqualTo(theirs);
        assertThat(rowOf(colleague)).containsEntry("status", "SCHEDULED");
    }

    @Test
    void aPairThatWasCancelledOrOnLeaveBeforeCanBeRegisteredAgain() {
        data.assignment(branchA, receptionist, morning, WEEK, "CANCELLED", "SELF", manager);
        data.assignment(branchA, receptionist, afternoon, WEEK, "ON_LEAVE", "SELF", manager);

        assertThat(put(token, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        assertThat(scheduledRowsOf(receptionist)).hasSize(10);
    }

    @Test
    void adjacentShiftsOfTheSameDayAreNotAnOverlap() {
        List<String> items = new ArrayList<>(tenItems().subList(2, 10));
        items.add(item(WEEK, afternoon)); // 13:00–17:00
        items.add(item(WEEK, evening));   // 17:00–20:00 liền kề

        assertThat(put(token, WEEK, itemsBody(items.toArray(String[]::new))).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- lỗi mục gom một message BR-NS-11

    @Test
    void everyItemErrorIsListedInOneBrNs11MessageAndNothingIsLeftBehind() {
        seedTen(receptionist);
        String before = trace(receptionist, vet);

        ResponseEntity<Map<String, Object>> response = put(token, WEEK, itemsBody(
                item(WEEK, morning),
                item(WEEK.minusDays(1), morning),
                item(WEEK.plusDays(1), foreign),
                item(WEEK.plusDays(1), 987_654_321L),
                item(WEEK.plusDays(2), retired),
                item(WEEK, morning),
                item(WEEK.plusDays(3), morning),
                item(WEEK.plusDays(3), noon),
                item(WEEK.plusDays(7), morning)));

        assertBusinessRule(response, "BR-NS-11");
        String text = message(response);
        assertThat(text).contains("Đăng ký tuần không hợp lệ")
                .contains("Mục 2: ngày " + vn(WEEK.minusDays(1)) + " nằm ngoài tuần")
                .contains("Mục 3: ca #" + foreign + " không tồn tại hoặc không thuộc chi nhánh của bạn")
                .contains("Mục 4: ca #987654321 không tồn tại hoặc không thuộc chi nhánh của bạn")
                .contains("Mục 5: ca '" + definitionName(retired) + "' đã ngừng dùng")
                .contains("Mục 6: trùng với mục 1 (cùng ngày, cùng ca)")
                .contains("Mục 7 và mục 8 chồng giờ ngày " + vn(WEEK.plusDays(3)))
                .contains("Mục 9: ngày " + vn(WEEK.plusDays(7)) + " nằm ngoài tuần");
        assertThat(text).as("không lộ tên ca của chi nhánh khác").doesNotContain("Ca chi nhánh khác");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    @Test
    void anItemErrorWinsOverAMissingQuota() {
        ResponseEntity<Map<String, Object>> response = put(token, WEEK, itemsBody(item(WEEK, retired)));

        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).doesNotContain("BR-NS-12");
    }

    @Test
    void theWeekStartMustBeAMondayInTheSupportedYears() {
        String before = trace(receptionist, vet);

        for (LocalDate bad : new LocalDate[] {WEEK.plusDays(1), WEEK.plusDays(6), WEEK.minusDays(1)}) {
            assertBusinessRule(put(token, bad, ten()), "BR-NS-11");
        }
        assertBusinessRule(put(token, firstMondayOf(2000).minusDays(7), ten()), "BR-NS-11");
        assertBusinessRule(put(token, LocalDate.of(2101, 1, 3), ten()), "BR-NS-11");
        assertThat(errorCode(put(token, "abc", ten()))).isEqualTo("MALFORMED_REQUEST");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- hạn 00:00 thứ Hai

    @Test
    void theDeadlineIsMidnightMondayVietnamTimeAndTheExactInstantIsAlreadyLate() {
        Instant deadline = WEEK.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        clock.set(deadline.minusSeconds(1));
        String early = token(receptionist);
        assertThat(put(early, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        String before = trace(receptionist, vet);

        clock.set(deadline);
        ResponseEntity<Map<String, Object>> late = put(early, WEEK, itemsBody(item(WEEK, morning)));

        assertBusinessRule(late, "BR-NS-11");
        assertThat(message(late)).contains("quá hạn");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
        clock.set(deadline.plusSeconds(3_600));
        assertBusinessRule(put(early, WEEK, ten()), "BR-NS-11");
        assertThat(registration(early, WEEK).getStatusCode().value()).as("đọc vẫn xem được tuần đã qua hạn").isEqualTo(200);
    }

    @Test
    void aWeekThatAlreadyStartedOnlyReportsTheDeadlineEvenWithBrokenItems() {
        clock.set(WEEK.plusDays(2).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        String late = token(receptionist);

        ResponseEntity<Map<String, Object>> response = put(late, WEEK, itemsBody(item(WEEK.plusDays(30), foreign)));

        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).contains("quá hạn").doesNotContain("Mục 1");
    }

    // ---------------------------------------------------------------- định mức (BR-NS-12)

    @Test
    void oneBelowTheQuotaAndAnEmptyListAreBrNs12WithTheNumbersAndSaveNothing() {
        seedTen(receptionist);
        String before = trace(receptionist, vet);

        ResponseEntity<Map<String, Object>> nine = put(token, WEEK, itemsBody(tenItems().subList(0, 9).toArray(String[]::new)));
        ResponseEntity<Map<String, Object>> empty = put(token, WEEK, "{'items':[]}");

        assertBusinessRule(nine, "BR-NS-12");
        assertThat(message(nine)).contains("có 9 ca (gồm 0 ca do quản lý xếp)").contains("tối thiểu 10 ca")
                .contains("còn thiếu 1");
        assertBusinessRule(empty, "BR-NS-12");
        assertThat(message(empty)).contains("còn thiếu 10");
        assertThat(trace(receptionist, vet)).as("tuần cũ còn nguyên").isEqualTo(before);
    }

    @Test
    void theQuotaFollowsTheCurrentConfigValueOnEveryCall() {
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, QUOTA_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = '2' WHERE key = ?", QUOTA_KEY);
            configService.reload();

            ResponseEntity<Map<String, Object>> low = put(token, WEEK, itemsBody(item(WEEK, morning), item(WEEK.plusDays(1), morning)));

            assertThat(low.getStatusCode().value()).isEqualTo(200);
            assertThat(intOf(dataOf(low), "requiredShifts")).isEqualTo(2);

            jdbc.update("UPDATE system_configs SET value = '13' WHERE key = ?", QUOTA_KEY);
            configService.reload();

            ResponseEntity<Map<String, Object>> high = put(token, WEEK, ten());

            assertBusinessRule(high, "BR-NS-12");
            assertThat(message(high)).contains("tối thiểu 13 ca");
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, QUOTA_KEY);
            configService.reload();
        }
        assertThat(put(token, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- hình thức body (không bao giờ 5xx)

    @Test
    void shapeErrorsAre400WithTheRightCodeAndNeverLeaveATrace() {
        String before = trace(receptionist, vet);
        String hundredOne = itemsBody(java.util.Collections.nCopies(101, item(WEEK, morning)).toArray(String[]::new));

        Map<String, String> validation = new java.util.LinkedHashMap<>();
        validation.put("{}", "thiếu items");
        validation.put("{'items':null}", "items null");
        validation.put("{'items':[null]}", "phần tử null");
        validation.put("{'items':[{}]}", "mục rỗng");
        validation.put("{'items':[{'shiftDefinitionId':" + morning + "}]}", "thiếu workDate");
        validation.put("{'items':[{'workDate':'" + WEEK + "'}]}", "thiếu shiftDefinitionId");
        validation.put("{'items':[{'workDate':null,'shiftDefinitionId':null}]}", "hai trường null");
        validation.put(hundredOne, "101 phần tử");
        validation.forEach((json, why) -> {
            ResponseEntity<Map<String, Object>> response = put(token, WEEK, json);
            assertThat(response.getStatusCode().value()).as(why).isEqualTo(400);
            assertThat(errorCode(response)).as(why).isEqualTo("VALIDATION_FAILED");
        });

        Map<String, String> malformed = new java.util.LinkedHashMap<>();
        malformed.put("'x'", "chuỗi thay vì object");
        malformed.put("[]", "mảng thay vì object");
        malformed.put("{'items':'x'}", "items là chuỗi");
        malformed.put("{'items':{}}", "items là object");
        malformed.put("{'items':[{'workDate':'abc','shiftDefinitionId':1}]}", "ngày sai định dạng");
        malformed.put("{'items':[{'workDate':'" + WEEK + "','shiftDefinitionId':1.5}]}", "số thực vào Long");
        malformed.put("{'items':[{'workDate':'" + WEEK + "','shiftDefinitionId':'x'}]}", "chuỗi vào Long");
        malformed.put("{'items':[", "JSON cụt");
        malformed.forEach((json, why) -> {
            ResponseEntity<Map<String, Object>> response = put(token, WEEK, json);
            assertThat(response.getStatusCode().value()).as(why).isEqualTo(400);
            assertThat(errorCode(response)).as(why).isEqualTo("MALFORMED_REQUEST");
        });

        assertThat(errorCode(call(HttpMethod.PUT, REGISTRATIONS + WEEK, token))).as("không có body")
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    @Test
    void exactlyOneHundredWellFormedItemsPassValidationAndFailOnTheBusinessRuleInstead() {
        ResponseEntity<Map<String, Object>> response = put(token, WEEK,
                itemsBody(java.util.Collections.nCopies(100, item(WEEK.plusDays(40), morning)).toArray(String[]::new)));

        assertBusinessRule(response, "BR-NS-11");
    }

    @Test
    void anExtremeYearInADateOrAnIdIsAnItemErrorNotA5xxOrA409() {
        ResponseEntity<Map<String, Object>> far = put(token, WEEK, itemsBody(
                "{'workDate':'+999999999-12-31','shiftDefinitionId':" + morning + "}",
                "{'workDate':'-999999999-01-01','shiftDefinitionId':" + morning + "}",
                item(WEEK, Long.MAX_VALUE), item(WEEK, 0L), item(WEEK, -1L)));

        assertBusinessRule(far, "BR-NS-11");
        assertThat(message(far)).contains("nằm ngoài tuần").contains("không tồn tại hoặc không thuộc chi nhánh");
    }

    // ---------------------------------------------------------------- tiện ích

    /** Thứ Hai–Thứ Sáu × (ca sáng, ca chiều) = 10 mục, đúng định mức mặc định. */
    private List<String> tenItems() {
        List<String> items = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            items.add(item(WEEK.plusDays(day), morning));
            items.add(item(WEEK.plusDays(day), afternoon));
        }
        return items;
    }

    private String ten() {
        return itemsBody(tenItems().toArray(String[]::new));
    }

    /** {@link #tenItems} bỏ mục (day, definition) rồi thêm các mục {@code extra}: một lần sửa tuần có một mục bị bỏ. */
    private String bodyExcept(LocalDate day, long definitionId, String... extra) {
        String dropped = item(day, definitionId);
        List<String> items = new ArrayList<>(tenItems().stream().filter(item -> !item.equals(dropped)).toList());
        items.addAll(List.of(extra));
        return itemsBody(items.toArray(String[]::new));
    }

    /** Chèn 10 phân ca SELF như {@link #tenItems} (theo thứ tự đó) và trả id. */
    private List<Long> seedTen(long staffId) {
        List<Long> ids = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            ids.add(data.assignment(branchA, staffId, morning, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
            ids.add(data.assignment(branchA, staffId, afternoon, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
        }
        return ids;
    }

    private Map<String, Object> rowOf(long assignmentId) {
        return assignmentRow(assignmentId);
    }

    private String definitionName(long definitionId) {
        return jdbc.queryForObject("SELECT name FROM shift_definitions WHERE id = ?", String.class, definitionId);
    }

    private static String vn(LocalDate day) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(day);
    }
}
