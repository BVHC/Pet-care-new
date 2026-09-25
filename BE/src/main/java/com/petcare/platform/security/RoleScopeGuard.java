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
                // Khớp với nhánh STORE_MANAGER ở assertCanAssignRole (kiểm tra cả storeId lẫn
                // organizationId) — trước đây chỉ check storeId, cho phép actor "vượt" sang quản
                // lý user gắn storeId trùng nhưng organizationId khác (dữ liệu không nhất quán vì
                // Module 03/Store chưa triển khai nên storeId không tự đối chiếu ra đúng Org chủ).
                if (!Objects.equals(actor.getStoreId(), targetStoreId)
                        || !Objects.equals(actor.getOrganizationId(), targetOrganizationId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + targetStoreId);
                }
            }
            default -> throw new AccessDeniedScopeException("SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER",
                    actor.getRole().name());
        }
    }

    /**
     * RULE-02-05 — actor có được sửa thông tin {@code Store} này không (dùng cho
     * {@code PATCH /stores/{id}} — {@code UpdateStore}, docs/api/org-store-v1.md B): SUPER_ADMIN
     * toàn quyền; ORGANIZATION_ADMIN mọi Store trong Organization mình; STORE_MANAGER chỉ đúng
     * Store mình đang quản lý. Cùng cấu trúc {@link #assertCanManageUser} (3 role được phép gọi,
     * STORE_MANAGER phải khớp cả storeId lẫn organizationId) nhưng tách method riêng vì đây là
     * hành động trên aggregate {@code Store} (Module 03), không phải {@code User} (Module 02) —
     * tránh đặt tên gây hiểu nhầm dù logic giống hệt.
     */
    public static void assertCanManageStore(UserPrincipal actor, java.util.UUID organizationId,
                                             java.util.UUID storeId) {
        switch (actor.getRole()) {
            case SUPER_ADMIN -> {
                // Toàn quyền.
            }
            case ORGANIZATION_ADMIN -> {
                if (!Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + organizationId);
                }
            }
            case STORE_MANAGER -> {
                if (!Objects.equals(actor.getStoreId(), storeId)
                        || !Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + storeId);
                }
            }
            default -> throw new AccessDeniedScopeException("SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER",
                    actor.getRole().name());
        }
    }

    /**
     * RULE-05-01/06 — actor có được đọc dữ liệu Catalog (Product/Service master) của
     * Organization này không: SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN/STORE_MANAGER chỉ
     * đúng Organization của mình. Rộng hơn {@link #assertCanManageOrganization} (chỉ 2 role
     * Admin) vì StoreManager cũng cần đọc danh mục gốc Organization để biết còn gì để cấu hình
     * giá/khả dụng tại Store (RULE-05-05). Không dùng cho POST/PATCH — ManageProduct/
     * ManageService chỉ ORGANIZATION_ADMIN (RULE-05-01), chặn ở {@code @PreAuthorize} Controller.
     */
    public static void assertCanViewOrganizationCatalog(UserPrincipal actor, java.util.UUID organizationId) {
        if (actor.getRole() == UserRole.SUPER_ADMIN) {
            return;
        }
        if ((actor.getRole() == UserRole.ORGANIZATION_ADMIN || actor.getRole() == UserRole.STORE_MANAGER)
                && Objects.equals(actor.getOrganizationId(), organizationId)) {
            return;
        }
        throw new AccessDeniedScopeException("ORGANIZATION:" + organizationId,
                actor.getOrganizationId() == null ? actor.getRole().name() : "ORGANIZATION:" + actor.getOrganizationId());
    }

    /**
     * RULE-12-01 — actor có được vận hành tồn kho (Receive/Issue/Track/Adjust/Count) của Store
     * này không: SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN mọi Store trong Organization mình;
     * STORE_MANAGER/INVENTORY_STAFF chỉ đúng Store mình gắn. Rộng hơn {@link #assertCanManageStore}
     * (không có INVENTORY_STAFF — role vận hành kho chính theo docs/01-business-operations.md
     * §12) nhưng cùng cấu trúc org/store binding. Approve/RejectInventoryAdjustment KHÔNG dùng
     * method này — tái dùng {@link #assertCanManageStore} (RULE-12-03 chỉ StoreManager/OrgAdmin
     * được duyệt, không cho InventoryStaff tự duyệt phiếu chính mình tạo).
     */
    public static void assertCanOperateStoreInventory(UserPrincipal actor, java.util.UUID organizationId,
                                                        java.util.UUID storeId) {
        switch (actor.getRole()) {
            case SUPER_ADMIN -> {
                // Toàn quyền.
            }
            case ORGANIZATION_ADMIN -> {
                if (!Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + organizationId);
                }
            }
            case STORE_MANAGER, INVENTORY_STAFF -> {
                if (!Objects.equals(actor.getStoreId(), storeId)
                        || !Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + storeId);
                }
            }
            default -> throw new AccessDeniedScopeException(
                    "SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER|INVENTORY_STAFF", actor.getRole().name());
        }
    }

    /**
     * RULE-14-01 — actor có được vận hành Order (CreateOrder POS/CancelOrder phía nhân viên) của
     * Store này không: SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN mọi Store trong Organization
     * mình; STORE_MANAGER/RECEPTIONIST chỉ đúng Store mình gắn. Cùng cấu trúc
     * {@link #assertCanOperateStoreInventory} nhưng đổi INVENTORY_STAFF -> RECEPTIONIST — actor
     * vận hành Order tại quầy theo docs/01-business-operations.md §14 là Receptionist, không phải
     * InventoryStaff, nên không tái dùng được method Inventory (set role khác nhau).
     */
    public static void assertCanOperateStoreOrder(UserPrincipal actor, java.util.UUID organizationId,
                                                    java.util.UUID storeId) {
        switch (actor.getRole()) {
            case SUPER_ADMIN -> {
                // Toàn quyền.
            }
            case ORGANIZATION_ADMIN -> {
                if (!Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + organizationId);
                }
            }
            case STORE_MANAGER, RECEPTIONIST -> {
                if (!Objects.equals(actor.getStoreId(), storeId)
                        || !Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + storeId);
                }
            }
            default -> throw new AccessDeniedScopeException(
                    "SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER|RECEPTIONIST", actor.getRole().name());
        }
    }

    /**
     * RULE-14-05 — actor có được ProcessOrder (CONFIRMED->PROCESSING) của Store này không:
     * SUPER_ADMIN toàn quyền; ORGANIZATION_ADMIN mọi Store trong Organization mình;
     * STORE_MANAGER/RECEPTIONIST/INVENTORY_STAFF chỉ đúng Store mình gắn. Không tái dùng được
     * {@link #assertCanOperateStoreOrder} (thiếu INVENTORY_STAFF) lẫn
     * {@link #assertCanOperateStoreInventory} (thiếu RECEPTIONIST) vì RULE-14-05 nêu đích danh cả
     * 2 actor riêng cho ProcessOrder ("Receptionist/InventoryStaff").
     */
    public static void assertCanProcessStoreOrder(UserPrincipal actor, java.util.UUID organizationId,
                                                    java.util.UUID storeId) {
        switch (actor.getRole()) {
            case SUPER_ADMIN -> {
                // Toàn quyền.
            }
            case ORGANIZATION_ADMIN -> {
                if (!Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                            "ORGANIZATION:" + organizationId);
                }
            }
            case STORE_MANAGER, RECEPTIONIST, INVENTORY_STAFF -> {
                if (!Objects.equals(actor.getStoreId(), storeId)
                        || !Objects.equals(actor.getOrganizationId(), organizationId)) {
                    throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + storeId);
                }
            }
            default -> throw new AccessDeniedScopeException(
                    "SUPER_ADMIN|ORGANIZATION_ADMIN|STORE_MANAGER|RECEPTIONIST|INVENTORY_STAFF", actor.getRole().name());
        }
    }

    /**
     * RULE-14-01 — actor CUSTOMER có phải chủ sở hữu Order này không (dùng cho CreateOrder Online/
     * CheckoutOrder/ViewOrder/CancelOrder phía khách hàng): SUPER_ADMIN bypass (đồng nhất mọi guard
     * khác trong class này); còn lại bắt buộc {@code actor.getUserId() == customerId}. Method đơn
     * giản kiểu {@link #assertNotSelfAssignment} — không cần org/store scoping vì CUSTOMER không
     * gắn Organization/Store (RULE-02-02).
     */
    public static void assertIsOrderOwner(UserPrincipal actor, java.util.UUID customerId) {
        if (actor.getRole() == UserRole.SUPER_ADMIN) {
            return;
        }
        if (!Objects.equals(actor.getUserId(), customerId)) {
            throw new AccessDeniedScopeException("CUSTOMER:" + customerId, "CUSTOMER:" + actor.getUserId());
        }
    }

    /**
     * RULE-02-05 — dành riêng cho {@code ConfigureOperatingHour} ({@code PUT
     * /stores/{id}/operating-hours}, docs/api/org-store-v1.md — quyết định 2026-09-17): CHỈ
     * {@code STORE_MANAGER} đúng Store mình quản lý được gọi, khác hẳn {@link #assertCanManageStore}
     * — {@code SUPER_ADMIN}/{@code ORGANIZATION_ADMIN} KHÔNG có ngoại lệ ở đây dù toàn quyền trên
     * mọi endpoint Store khác (bám sát literal `docs/01-business-operations.md` `01#3`, chỉ gán
     * đúng 1 dòng StoreManager cho command này, không có OrgAdmin). {@code @PreAuthorize} ở
     * Controller đã chặn role khác STORE_MANAGER; guard này chỉ còn cần khớp đúng storeId
     * (defense-in-depth, không dựa hoàn toàn vào {@code @PreAuthorize}).
     */
    public static void assertIsOwnStoreManager(UserPrincipal actor, java.util.UUID storeId) {
        if (actor.getRole() != UserRole.STORE_MANAGER) {
            throw new AccessDeniedScopeException("STORE_MANAGER", actor.getRole().name());
        }
        if (!Objects.equals(actor.getStoreId(), storeId)) {
            throw new AccessDeniedScopeException("STORE:" + actor.getStoreId(), "STORE:" + storeId);
        }
    }

    /**
     * RULE-02-05 — actor không được tự khóa/mở khóa/vô hiệu hóa/tái kích hoạt
     * chính tài khoản của mình qua các thao tác quản trị phân cấp
     * (LockAccount/UnlockAccount/DeactivateAccount/ReactivateAccount). "Phân
     * cấp quản trị" ngụ ý tác động lên người khác, không phải bản thân; nếu
     * không, actor cuối cùng còn quyền Admin của 1 Organization có thể tự khóa
     * mình và không ai còn quyền để mở lại (tương tự tinh thần chống tự thay
     * đổi bản thân đã áp dụng cho AssignPermission — RULE-02-06,
     * {@link #assertNotSelfAssignment}).
     */
    public static void assertNotSelfLifecycleAction(UserPrincipal actor, java.util.UUID targetUserId) {
        if (Objects.equals(actor.getUserId(), targetUserId)) {
            throw new BusinessRuleViolationException("RULE-02-05",
                    "Không được tự khóa/vô hiệu hóa tài khoản của chính mình");
        }
    }

    /**
     * RULE-02-05 — actor có được khóa/mở khóa/vô hiệu hóa/tái kích hoạt tài
     * khoản của {@code targetRole} này không (dùng cho
     * LockAccount/UnlockAccount/DeactivateAccount/ReactivateAccount):
     * <ul>
     *   <li>Target là CUSTOMER: chỉ SUPER_ADMIN — Customer không gắn
     *   Organization trong schema hiện tại (RULE-02-02), nên ORGANIZATION_ADMIN
     *   không có cách xác định "khách hàng thuộc Organization mình" theo đúng
     *   tinh thần RULE-02-05 (giống lý do {@link #assertCanAccessUserRecord}
     *   loại ORGANIZATION_ADMIN khỏi customer target). RECEPTIONIST không nằm
     *   trong danh sách Actor của FSM-1 Lock/Unlock/Deactivate/Reactivate
     *   (docs/03-state-machines.md) nên cũng không được gọi các thao tác này.</li>
     *   <li>Target không phải CUSTOMER (staff): delegate
     *   {@link #assertCanManageOrganization} — SUPER_ADMIN toàn quyền,
     *   ORGANIZATION_ADMIN chỉ đúng Organization của target.</li>
     * </ul>
     */
    public static void assertCanManageAccountLifecycle(UserPrincipal actor, UserRole targetRole,
                                                         java.util.UUID targetOrganizationId) {
        if (targetRole == UserRole.CUSTOMER) {
            if (actor.getRole() == UserRole.SUPER_ADMIN) {
                return;
            }
            throw new AccessDeniedScopeException("SUPER_ADMIN", actor.getRole().name());
        }
        assertCanManageOrganization(actor, targetOrganizationId);
    }
}
