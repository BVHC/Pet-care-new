package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Chuỗi "nhân viên rời chi nhánh" (BR-NS-09; 03 Phân ca#6, Yêu cầu#5; docs/adr/0149 bước 5, trả D071) trên Postgres thật,
 * qua HTTP của identity: vô hiệu hóa và điều chuyển hủy đúng tập phân ca {@code SCHEDULED} chưa bắt đầu của đúng nhân
 * viên tại chi nhánh cũ, đóng yêu cầu {@code PENDING} của chúng, gửi mỗi ca một cặp thông báo (payload khớp mẫu V40, tên
 * chi nhánh là chi nhánh <b>cũ</b>), không đụng ca đã bắt đầu / đã qua / đã ở trạng thái cuối / của người khác / của chi
 * nhánh khác. Thao tác UPDATE hàng loạt ghi <b>đúng các cột</b> mà đường entity của #6 ghi. BR-NS-09 không chặn: nhân viên
 * không có ca nào vẫn 200. Era <b>năm 2058</b>; đồng hồ 10:00 giờ Việt Nam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffLeftBranchIT.TestBeans.class})
class StaffLeftBranchIT extends StaffLeftBranchItSupport {

    private static final Instant NOW = Instant.parse("2058-03-14T03:00:00Z"); // 10:00 giờ Việt Nam

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private String token;
    private String untouchedBefore;
    private String requestOnStartedBefore;

    @BeforeEach
    void setUp() {
        seedWorld(NOW);
        token = token(superManager);
        untouchedBefore = snapshotOfAssignments(untouched());
        requestOnStartedBefore = String.valueOf(requestRow(requestOnStarted));
    }

    @AfterEach
    void tearDown() {
        tearDownWorld();
    }

    // ---------------------------------------------------------------- vô hiệu hóa

    @Test
    void disablingCancelsExactlyTheUpcomingShiftsRejectsTheirRequestsAndKeepsEverythingElse() {
        token(staff); // một phiên đang mở: vô hiệu hóa phải hủy nó (identity), cùng transaction
        assertThat(activeSessions(staff)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> response = disable(token, staff);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(accountRow(staff)).containsEntry("status", "DISABLED").containsEntry("branch_id", branchA);
        assertThat(activeSessions(staff)).isZero();
        assertThat(staffAuditCount(staff, "STAFF_DISABLED")).isEqualTo(1);
        assertLeavingEffects(untouchedBefore, requestOnStartedBefore);
    }

    @Test
    void theNoticesMapEveryFieldOfEachCancelledShiftAndNameTheBranchTheStaffLeft() {
        assertThat(disable(token, staff).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> inApp = noticePayloads("SHIFT_ASSIGNMENT_CANCELLED_APP", staff);
        List<Map<String, Object>> email = noticePayloads("SHIFT_ASSIGNMENT_CANCELLED", staff);
        assertThat(inApp).hasSize(3);
        assertThat(email).hasSize(3);
        // Thứ tự theo id phân ca tăng dần: futureMorning, futureAfternoon, todayLater
        assertShift(inApp.get(0), "Ca sáng", "21/03/2058", "08:00", "12:00");
        assertShift(inApp.get(1), "Ca chiều", "22/03/2058", "13:00", "17:00");
        assertShift(inApp.get(2), "Ca chiều", "14/03/2058", "13:00", "17:00");
        for (int i = 0; i < 3; i++) {
            assertThat(email.get(i)).containsEntry("ten_ca", inApp.get(i).get("ten_ca"))
                    .containsEntry("ngay_lam", inApp.get(i).get("ngay_lam"));
            assertThat(email.get(i).get("ten_nhan_vien")).as("lời chào chỉ có ở bản EMAIL").isNotNull();
            assertThat(inApp.get(i).get("ten_nhan_vien")).isNull();
        }
        assertTemplatesFit(staff);
        assertThat(cancelOutbox(staff)).first().satisfies(row -> assertThat(row)
                .containsEntry("recipient_account_id", staff).containsEntry("channel", "IN_APP"));
        assertThat(cancelOutbox(staff).get(1)).containsEntry("recipient_email", emailOf(staff));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id = ?",
                Integer.class, superManager)).as("người gọi không nhận gì").isZero();
    }

    @Test
    void aStaffMemberWithoutAnyUpcomingShiftIsStillDisabledWith200AndNothingElseChanges() {
        long vet = data.staff("VET", branchB);
        String before = worldOf(otherStaff);

        ResponseEntity<Map<String, Object>> response = disable(token, vet);

        assertThat(response.getStatusCode().value()).as("BR-NS-09 không chặn vô hiệu hóa").isEqualTo(200);
        assertThat(accountRow(vet)).containsEntry("status", "DISABLED");
        assertThat(cancelOutbox(vet)).isEmpty();
        assertThat(worldOf(otherStaff)).as("nhân viên khác không bị đụng").isEqualTo(before);
        assertThat(snapshotOfAssignments(untouched())).isEqualTo(untouchedBefore);
    }

    @Test
    void disablingTwiceIs409AndDoesNotCancelOrNotifyAgain() {
        assertThat(disable(token, staff).getStatusCode().value()).isEqualTo(200);
        String afterFirst = worldOf(staff);

        ResponseEntity<Map<String, Object>> second = disable(token, staff);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(second)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(worldOf(staff)).as("lần hai không để lại gì: không hủy, không thông báo thêm").isEqualTo(afterFirst);
    }

    // ---------------------------------------------------------------- điều chuyển

    @Test
    void transferringCancelsTheSameSetAndTheNoticesNameTheOldBranchNotTheNewOne() {
        ResponseEntity<Map<String, Object>> response = transfer(token, staff, branchB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(accountRow(staff)).containsEntry("status", "ACTIVE").containsEntry("branch_id", branchB);
        assertThat(staffAuditCount(staff, "STAFF_TRANSFERRED")).isEqualTo(1);
        assertLeavingEffects(untouchedBefore, requestOnStartedBefore);

        // Bẫy: StaffSummary.branchId đã là chi nhánh MỚI trong cùng transaction; tên phải là của chi nhánh cũ.
        for (String template : List.of("SHIFT_ASSIGNMENT_CANCELLED_APP", "SHIFT_ASSIGNMENT_CANCELLED")) {
            assertThat(noticePayloads(template, staff)).as(template)
                    .allSatisfy(payload -> assertThat(payload).containsEntry("chi_nhanh", branchName(branchA)));
        }
        assertTemplatesFit(staff);
    }

    @Test
    void transferringToTheSameBranchIsANoOpThatDoesNotTouchShifts() {
        String before = worldOf(staff);

        ResponseEntity<Map<String, Object>> response = transfer(token, staff, branchA);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(worldOf(staff)).as("đã ở chi nhánh đích: không phát sự kiện").isEqualTo(before);
    }

    // ---------------------------------------------------------------- UPDATE hàng loạt ghi đúng cột của đường entity

    /**
     * Đường hàng loạt (listener) bỏ qua entity nên không tự thừa hưởng logic của {@code ShiftAssignment.cancel} /
     * {@code ShiftRequest.rejectBySystem}: so từng cột với đường entity của #6 trên cùng đồng hồ. Khác nhau chỉ ở những
     * cột nói về người làm (hệ thống: NULL) và lý do.
     */
    @Test
    void theBulkUpdateWritesTheSameColumnsAsTheEntityPathOfTheManagerCancel() {
        long managerA = data.staff("BRANCH_MANAGER", branchA);
        long viaEntity = otherStaffFuture;
        long entityRequest = pendingRequest(viaEntity, otherStaff);
        ResponseEntity<Map<String, Object>> cancelled = call(org.springframework.http.HttpMethod.POST,
                cancelPath(viaEntity), token(managerA), reasonBody("Nghỉ lễ"));
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);

        assertThat(disable(token, staff).getStatusCode().value()).isEqualTo(200);

        Map<String, Object> entityShape = shapeOfAssignment(viaEntity);
        Map<String, Object> bulkShape = shapeOfAssignment(futureMorning);
        assertThat(bulkShape).as("cột phân ca").isEqualTo(entityShape);
        assertThat(shapeOfRequest(requestOnFuture)).as("cột yêu cầu").isEqualTo(shapeOfRequest(entityRequest));
        assertThat(assignmentRow(futureMorning)).containsEntry("status_changed_by", null);
        assertThat(assignmentRow(viaEntity)).containsEntry("status_changed_by", managerA);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertShift(Map<String, Object> payload, String name, String date, String start, String end) {
        assertThat(String.valueOf(payload.get("ten_ca"))).startsWith(name);
        assertThat(payload).containsEntry("ngay_lam", date).containsEntry("gio_bat_dau", start)
                .containsEntry("gio_ket_thuc", end).containsEntry("ly_do", LEFT_REASON);
    }

    /** Mô tả "hình dạng" của một phân ca đã hủy: cột nào có giá trị và các cột thời gian có bằng mốc đồng hồ không. */
    private Map<String, Object> shapeOfAssignment(long id) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, reason IS NOT NULL AS has_reason, status_changed_at IS NOT NULL AS has_changed_at,
                       updated_at = status_changed_at AS updated_with_status, source
                FROM shift_assignments WHERE id = ?""", id);
        Map<String, Object> shape = new LinkedHashMap<>(row);
        shape.remove("source"); // khác nguồn là chủ ý của dữ liệu thử, không phải của đường ghi
        return shape;
    }

    private Map<String, Object> shapeOfRequest(long id) {
        return jdbc.queryForMap("""
                SELECT status, decided_by IS NULL AS no_decider, decision_note IS NOT NULL AS has_note,
                       closed_at IS NOT NULL AS has_closed_at, updated_at = closed_at AS updated_with_close
                FROM shift_requests WHERE id = ?""", id);
    }
}
