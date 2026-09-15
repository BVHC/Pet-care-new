package com.petcare.platform.security;

import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * RULE-02-01/02/03 (docs/02-business-rules.md) — chống leo thang quyền, dùng
 * chung bởi {@code AuthServiceImpl.createStaff} (Module 01) và
 * {@code UserManagementServiceImpl.assignRole} (Module 02), tránh trùng logic
 * no-escalation ở 2 module (docs/convention/backend/01-package-structure.md:
 * hạ tầng dùng chung nằm ở platform/).
 * <p>
 * Ma trận org/store bắt buộc theo role ở {@link #validateRoleScopeBinding} là
 * diễn giải hợp lý từ RULE-02-02 — chưa có ma trận chính thức từ PO
 * (docs/api/auth-v1.md Q9 đang TBD), tập trung ở đây để dễ chỉnh nếu PO chốt
 * khác.
 */
public final class RoleScopeGuard {

    private static final Set<UserRole> STORE_MANAGER_ASSIGNABLE_ROLES = EnumSet.of(
            UserRole.RECEPTIONIST, UserRole.VETERINARIAN, UserRole.GROOMER,
            UserRole.INVENTORY_STAFF, UserRole.FINANCE_STAFF);

    private RoleScopeGuard() {
    }

    /** RULE-02-02 — role chỉ hợp lệ khi org/store binding khớp đúng phạm vi của role đó. */
    public static void validateRoleScopeBinding(UserRole role, java.util.UUID organizationId, java.util.UUID storeId) {
        switch (role) {
            case SUPER_ADMIN -> {
                if (organizationId != null || storeId != null) {
                    throw new BusinessRuleViolationException("RULE-02-02",
                            "SUPER_ADMIN không được gắn organizationId/storeId");
                }
            }
            case ORGANIZATION_ADMIN -> {
                if (organizationId == null || storeId != null) {
                    throw new BusinessRuleViolationException("RULE-02-02",
                            "ORGANIZATION_ADMIN bắt buộc organizationId, không được gắn storeId");
                }
            }
            case STORE_MANAGER, RECEPTIONIST, VETERINARIAN, GROOMER, INVENTORY_STAFF -> {
                // organizationId bắt buộc (không chỉ storeId): Module 03 (Store) chưa triển
                // khai nên IAM không có cách tra "store thuộc org nào" — organizationId phải
                // được denormalize thẳng lên User để các guard/filter theo Organization
                // (assertCanManageOrganization, GET /users filter) hoạt động đúng cho cả
                // role cấp Store, không chỉ ORGANIZATION_ADMIN.
                if (organizationId == null || storeId == null) {
                    throw new BusinessRuleViolationException("RULE-02-02",
                            role + " bắt buộc cả organizationId và storeId");
                }
            }
            case FINANCE_STAFF -> {
                if (organizationId == null) {
                    throw new BusinessRuleViolationException("RULE-02-02",
                            "FINANCE_STAFF bắt buộc organizationId (storeId tùy chọn)");
                }
            }
            case CUSTOMER -> {
                if (organizationId != null || storeId != null) {
                    throw new BusinessRuleViolationException("RULE-02-02",
                            "CUSTOMER không được gắn organizationId/storeId");
                }
            }
        }
    }

    /**
     * RULE-02-06 — "Nhân viên không được phép tự ý thay đổi vai trò... của bản
     * thân": chặn tuyệt đối actor gọi role-assignment lên chính mình, bất kể
     * role đích có nằm trong quyền gán của actor hay không (kể cả downgrade,
     * vốn không phải leo thang nhưng vẫn là "tự ý thay đổi vai trò").
     */
    public static void assertNotSelfAssignment(UserPrincipal actor, java.util.UUID targetUserId) {
        if (Objects.equals(actor.getUserId(), targetUserId)) {
            throw new BusinessRuleViolationException("RULE-02-06",
                    "Không được tự thay đổi vai trò của chính mình");
        }
    }

    /**
     * RULE-02-03/05 — thẩm quyền gán role không được vượt phạm vi của actor
     * (chống tự nâng quyền / tạo ngang hàng / gán ngoài scope).
     */
    public static void assertCanAssignRole(UserPrincipal actor, UserRole targetRole,
                                            java.util.UUID targetOrgId, java.util.UUID targetStoreId) {
        validateRoleScopeBinding(targetRole, targetOrgId, targetStoreId);

        UserRole actorRole = actor.getRole();
        switch (actorRole) {
            case SUPER_ADMIN -> {
                // Toàn quyền — không giới hạn thêm.
            }
            case ORGANIZATION_ADMIN -> {
                if (targetRole == UserRole.SUPER_ADMIN || targetRole == UserRole.ORGANIZATION_ADMIN) {
                    throw new AccessDeniedScopeException("<=ORGANIZATION_ADMIN scope", targetRole.name());
                }
                if (!Objects.equals(actor.getOrganizationId(), targetOrgId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + targetOrgId);
                }
            }
            case STORE_MANAGER -> {
                if (!STORE_MANAGER_ASSIGNABLE_ROLES.contains(targetRole)) {
                    throw new AccessDeniedScopeException("STORE_MANAGER assignable roles", targetRole.name());
                }
                if (!Objects.equals(actor.getStoreId(), targetStoreId)
                        || !Objects.equals(actor.getOrganizationId(), targetOrgId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + targetStoreId);
                }
            }
            default -> throw new AccessDeniedScopeException("SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER",
                    actorRole.name());
        }
    }

    /**
     * RULE-02-05 — actor có được quản trị dữ liệu thuộc Organization này
     * không: SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN chỉ đúng Organization
     * của mình. Dùng cho các thao tác chỉ SUPER_ADMIN/ORGANIZATION_ADMIN được
     * gọi (ManageUser: getUser/updateUser/lock/unlock/deactivate/reactivate —
     * @PreAuthorize đã chặn STORE_MANAGER từ tầng Controller). KHÔNG dùng cho
     * AssignPermission vì StoreManager cũng được quyền gọi — xem
     * {@link #assertCanManageUser}.
     */
    public static void assertCanManageOrganization(UserPrincipal actor, java.util.UUID organizationId) {
        if (actor.getRole() == UserRole.SUPER_ADMIN) {
            return;
        }
        if (actor.getRole() == UserRole.ORGANIZATION_ADMIN && Objects.equals(actor.getOrganizationId(), organizationId)) {
            return;
        }
        throw new AccessDeniedScopeException("ORGANIZATION:" + organizationId,
                actor.getScope() == null ? "UNKNOWN" : actor.getScope().name());
    }

    /**
     * RULE-02-05/06 — actor có được xem/sửa hồ sơ {@code User} này không
     * (dùng cho {@code GET/PATCH /users/{id}}):
     * <ul>
     *   <li>Target là CUSTOMER: SUPER_ADMIN hoặc RECEPTIONIST (bất kỳ Store
     *   nào — Customer không gắn Organization/Store, RULE-02-02) được phép;
     *   ORGANIZATION_ADMIN KHÔNG được vì Customer không thuộc Organization
     *   nào trong schema hiện tại.</li>
     *   <li>Target không phải CUSTOMER (staff): chỉ SUPER_ADMIN/ORGANIZATION_ADMIN
     *   (delegate {@link #assertCanManageOrganization}) — RECEPTIONIST bị
     *   chặn, chỉ được đụng hồ sơ Customer.</li>
     * </ul>
     */
    public static void assertCanAccessUserRecord(UserPrincipal actor, UserRole targetRole,
                                                  java.util.UUID targetOrganizationId) {
        if (targetRole == UserRole.CUSTOMER) {
            if (actor.getRole() == UserRole.SUPER_ADMIN || actor.getRole() == UserRole.RECEPTIONIST) {
                return;
            }
            throw new AccessDeniedScopeException("SUPER_ADMIN|RECEPTIONIST", actor.getRole().name());
        }
        if (actor.getRole() == UserRole.RECEPTIONIST) {
            throw new AccessDeniedScopeException("SUPER_ADMIN|ORGANIZATION_ADMIN", actor.getRole().name());
        }
        assertCanManageOrganization(actor, targetOrganizationId);
    }

    /**
     * RULE-02-05 — actor có đang quản trị user với org/store HIỆN TẠI này
     * không: SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN đúng Organization;
     * STORE_MANAGER đúng Store. Dùng cho AssignPermission (
     * {@code POST /users/{id}/role-assignment}) — nơi cả 3 role đều được
     * phép gọi (khác {@link #assertCanManageOrganization} chỉ dành cho 2
     * role Admin) — bổ sung cho {@link #assertCanAssignRole}, vốn chỉ kiểm
     * tra role/scope MỚI được yêu cầu chứ không kiểm tra target hiện có
     * thuộc quyền actor hay không.
     */
    public static void assertCanManageUser(UserPrincipal actor, java.util.UUID targetOrganizationId,
                                            java.util.UUID targetStoreId) {
        switch (actor.getRole()) {
            case SUPER_ADMIN -> {
                // Toàn quyền.
            }
            case ORGANIZATION_ADMIN -> {
                if (!Objects.equals(actor.getOrganizationId(), targetOrganizationId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + targetOrganizationId);
                }
            }
            case STORE_MANAGER -> {
                if (!Objects.equals(actor.getStoreId(), targetStoreId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + targetStoreId);
                }
            }
            default -> throw new AccessDeniedScopeException("SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER",
                    actor.getRole().name());
        }
    }
}
