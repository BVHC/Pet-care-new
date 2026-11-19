package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * Xác thực và phân quyền của hai thao tác gốc phát {@code StaffLeftBranchEvent} (BR-NS-09; docs/adr/0138 bước 5):
 * 401 và BR-TK-17 trước mọi việc, vai trò, phân cấp (ADMIN không vô hiệu hóa được lễ tân; quản lý chi nhánh khác không
 * đụng được nhân viên chi nhánh này) — <b>mỗi lệnh bị từ chối không để lại dấu vết</b> ở workforce. Hai đường thành công
 * chứng minh listener <b>không dính {@code BranchScope}</b>: SUPER_MANAGER (không có chi nhánh) điều chuyển vẫn hủy được
 * phân ca, BRANCH_MANAGER chỉ hủy phân ca của chi nhánh của mình, thông báo đi tới nhân viên chứ không tới người gọi, và
 * ADMIN vô hiệu hóa SUPER_MANAGER (không có chi nhánh cũ) không phát gì. Era <b>năm 2061</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffLeftBranchSecurityIT.TestBeans.class})
class StaffLeftBranchSecurityIT extends StaffLeftBranchItSupport {

    private static final Instant NOW = Instant.parse("2061-03-14T03:00:00Z"); // 10:00 giờ Việt Nam

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long managerA;
    private long managerC;
    private long admin;
    private long otherSuperManager;
    private String before;
    private String untouchedBefore;
    private String requestOnStartedBefore;

    @BeforeEach
    void setUp() {
        seedWorld(NOW);
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerC = data.staff("BRANCH_MANAGER", branchC);
        admin = data.staff("ADMIN", null);
        otherSuperManager = data.staff("SUPER_MANAGER", null);
        before = worldOf(staff);
        untouchedBefore = snapshotOfAssignments(untouched());
        requestOnStartedBefore = String.valueOf(requestRow(requestOnStarted));
    }

    @AfterEach
    void tearDown() {
        tearDownWorld();
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenBothEndpointsAre401AndLeaveNoTrace() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            ResponseEntity<Map<String, Object>> disabled = disable(token, staff);
            ResponseEntity<Map<String, Object>> transferred = transfer(token, staff, branchB);

            assertThat(disabled.getStatusCode().value()).as("disable token=%s", token).isEqualTo(401);
            assertThat(transferred.getStatusCode().value()).as("transfer token=%s", token).isEqualTo(401);
            assertThat(errorCode(disabled)).isEqualTo("UNAUTHENTICATED");
        }
        assertUntouched();
    }

    @Test
    void aRevokedSessionIs401ForBothEndpoints() {
        String token = token(superManager);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", superManager);

        assertThat(disable(token, staff).getStatusCode().value()).isEqualTo(401);
        assertThat(transfer(token, staff, branchB).getStatusCode().value()).isEqualTo(401);
        assertUntouched();
    }

    @Test
    void aSuperManagerWhoMustChangeThePasswordGetsBrTk17BeforeAnythingElse() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", superManager);
        String token = token(superManager);

        assertBusinessRule(disable(token, staff), "BR-TK-17");
        assertBusinessRule(transfer(token, staff, branchB), "BR-TK-17");
        assertUntouched();
    }

    // ---------------------------------------------------------------- vai trò và phân cấp

    @Test
    void frontDeskAndCareRolesCannotDisableOrTransfer() {
        for (String role : List.of("RECEPTIONIST", "VET", "CARETAKER")) {
            long caller = data.staff(role, branchA);
            String token = token(caller);

            assertThat(disable(token, staff).getStatusCode().value()).as("%s disable", role).isEqualTo(403);
            assertThat(transfer(token, staff, branchB).getStatusCode().value()).as("%s transfer", role).isEqualTo(403);
        }
        assertUntouched();
    }

    @Test
    void onlyASuperManagerMayTransferSoABranchManagerAndAnAdminAreForbidden() {
        assertThat(transfer(token(managerA), staff, branchB).getStatusCode().value()).isEqualTo(403);
        assertThat(transfer(token(admin), staff, branchB).getStatusCode().value()).isEqualTo(403);
        assertUntouched();
    }

    @Test
    void theHierarchyForbidsAnAdminDisablingBranchStaffAndABranchManagerOfAnotherBranchAndAuditsTheDenial() {
        ResponseEntity<Map<String, Object>> byAdmin = disable(token(admin), staff);
        ResponseEntity<Map<String, Object>> byOtherBranch = disable(token(managerC), staff);

        assertThat(byAdmin.getStatusCode().value()).as("ADMIN chỉ vô hiệu hóa được SUPER_MANAGER").isEqualTo(403);
        assertThat(byOtherBranch.getStatusCode().value()).as("quản lý chi nhánh khác").isEqualTo(403);
        assertThat(denialAuditCount(admin)).as("từ chối được ghi audit độc lập sau rollback").isEqualTo(1);
        assertThat(denialAuditCount(managerC)).isEqualTo(1);
        assertUntouched();
    }

    // ---------------------------------------------------------------- được phép: phạm vi dữ liệu là của sự kiện

    /** Chứng minh listener không dùng {@code BranchScope}: người điều chuyển là SUPER_MANAGER, không có chi nhánh. */
    @Test
    void aSuperManagerWithoutABranchTransfersAndTheShiftsAreStillCancelled() {
        ResponseEntity<Map<String, Object>> response = transfer(token(superManager), staff, branchB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertLeavingEffects(untouchedBefore, requestOnStartedBefore);
    }

    @Test
    void aBranchManagerDisablingOwnBranchStaffCancelsOnlyThatBranchShiftsAndNoticesGoToTheStaffMember() {
        ResponseEntity<Map<String, Object>> response = disable(token(managerA), staff);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertLeavingEffects(untouchedBefore, requestOnStartedBefore); // gồm ca của chi nhánh C và của người khác: nguyên vẹn
        assertThat(cancelOutbox(staff)).extracting(row -> row.get("recipient_account_id"))
                .filteredOn(id -> id != null).containsOnly(staff);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE recipient_account_id = ? AND template_code LIKE 'SHIFT_ASSIGNMENT_CANCELLED%'""",
                Integer.class, managerA)).as("người gọi không nhận thông báo").isZero();
    }

    @Test
    void anAdminDisablingASuperManagerHasNoBranchToLeaveSoWorkforceIsNotTouched() {
        ResponseEntity<Map<String, Object>> response = disable(token(admin), otherSuperManager);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(accountRow(otherSuperManager)).containsEntry("status", "DISABLED").containsEntry("branch_id", null);
        assertUntouched();
    }

    // ---------------------------------------------------------------- helpers

    /** Lệnh bị từ chối (hoặc không liên quan tới {@code staff}) không để lại dấu vết nào ở nhân viên và workforce. */
    private void assertUntouched() {
        assertThat(worldOf(staff)).as("nhân viên, phiên, audit, phân ca, yêu cầu, outbox phải y như trước").isEqualTo(before);
        assertThat(snapshotOfAssignments(untouched())).isEqualTo(untouchedBefore);
        assertThat(cancelOutbox(staff)).isEmpty();
    }
}
