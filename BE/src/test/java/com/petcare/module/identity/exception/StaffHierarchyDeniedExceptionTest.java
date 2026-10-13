package com.petcare.module.identity.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.service.QtAuditActions;
import com.petcare.module.identity.service.StaffAction;
import com.petcare.module.identity.service.StaffHierarchyPolicy;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Actor;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Request;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ErrorCode;

/** 403 vượt phân cấp (docs/adr/0029 mục 2; 00-method L65): mã lỗi, message chung, bản ghi audit đủ 5 trường. */
class StaffHierarchyDeniedExceptionTest {

    private final StaffHierarchyPolicy policy = new StaffHierarchyPolicy();

    private StaffHierarchyDeniedException deny(Actor actor, Request request) {
        try {
            policy.check(actor, request);
        } catch (StaffHierarchyDeniedException ex) {
            return ex;
        }
        throw new AssertionError("phải bị từ chối");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> after(StaffHierarchyDeniedException ex) {
        return (Map<String, Object>) ex.auditEntry().after();
    }

    @Test
    void isScopeMismatchWithGenericClientMessage() {
        StaffHierarchyDeniedException ex = deny(new Actor(1L, Role.BRANCH_MANAGER, 10L),
                Request.on(StaffAction.DISABLE, 2L, Role.VET, 20L));

        assertThat(ex).isInstanceOf(AccessDeniedScopeException.class);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED_SCOPE_MISMATCH);
        assertThat(ex.clientMessage()).isEqualTo("Không có quyền truy cập dữ liệu này")
                .doesNotContain("VET", "BRANCH_MANAGER", "DISABLE", "20");
    }

    @Test
    void auditEntryCarriesTargetAndRequest() {
        StaffHierarchyDeniedException ex = deny(new Actor(1L, Role.SUPER_MANAGER, null),
                Request.changeRole(2L, Role.SUPER_MANAGER, null, Role.VET));

        assertThat(ex.auditEntry().action()).isEqualTo(QtAuditActions.STAFF_ACTION_DENIED);
        assertThat(ex.auditEntry().entityType()).isEqualTo("accounts");
        assertThat(ex.auditEntry().entityId()).isEqualTo(2L);
        assertThat(ex.auditEntry().before()).isNull();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("action", "CHANGE_ROLE");
        expected.put("targetRole", "SUPER_MANAGER");
        expected.put("targetBranchId", null);
        expected.put("requestedRole", "VET");
        expected.put("requestedBranchId", null);
        assertThat(after(ex)).isEqualTo(expected);
        assertThat(after(ex).keySet()).containsExactlyElementsOf(expected.keySet());
    }

    @Test
    void createDenialHasNoEntity() {
        StaffHierarchyDeniedException ex = deny(new Actor(1L, Role.BRANCH_MANAGER, 10L),
                Request.create(Role.BRANCH_MANAGER, 10L));

        assertThat(ex.auditEntry().entityType()).isNull();
        assertThat(ex.auditEntry().entityId()).isNull();
        assertThat(after(ex))
                .containsEntry("action", "CREATE")
                .containsEntry("requestedRole", "BRANCH_MANAGER")
                .containsEntry("requestedBranchId", 10L);
    }
}
