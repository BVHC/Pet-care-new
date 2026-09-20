package com.petcare.platform.security;

import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** docs/02-business-rules.md RULE-02-01/02/03. */
class RoleScopeGuardTest {

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().role(role).organizationId(organizationId).storeId(storeId).build();
    }

    // ---- validateRoleScopeBinding (RULE-02-02) ----

    @Test
    void validateRoleScopeBinding_superAdmin_rejectsOrgOrStore() {
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.SUPER_ADMIN, UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessRuleViolationException) ex).getRuleId())
                        .isEqualTo("RULE-02-02"));
    }

    @Test
    void validateRoleScopeBinding_organizationAdmin_requiresOrgNoStore() {
        UUID orgId = UUID.randomUUID();
        assertThatCode(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.ORGANIZATION_ADMIN, orgId, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.ORGANIZATION_ADMIN, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.ORGANIZATION_ADMIN, orgId, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void validateRoleScopeBinding_storeScopedRoles_requireOrganizationIdAndStoreId() {
        for (UserRole role : new UserRole[]{UserRole.STORE_MANAGER, UserRole.RECEPTIONIST,
                UserRole.VETERINARIAN, UserRole.GROOMER, UserRole.INVENTORY_STAFF}) {
            assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(role, null, null))
                    .as("role=%s should require both organizationId and storeId", role)
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(role, UUID.randomUUID(), null))
                    .as("role=%s missing storeId should still fail", role)
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(role, null, UUID.randomUUID()))
                    .as("role=%s missing organizationId should still fail", role)
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatCode(() -> RoleScopeGuard.validateRoleScopeBinding(role, UUID.randomUUID(), UUID.randomUUID()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void validateRoleScopeBinding_financeStaff_requiresOrganizationId() {
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.FINANCE_STAFF, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.FINANCE_STAFF, null, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatCode(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.FINANCE_STAFF, UUID.randomUUID(), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.FINANCE_STAFF, UUID.randomUUID(), UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void validateRoleScopeBinding_customer_rejectsOrgOrStore() {
        assertThatThrownBy(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.CUSTOMER, UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatCode(() -> RoleScopeGuard.validateRoleScopeBinding(UserRole.CUSTOMER, null, null))
                .doesNotThrowAnyException();
    }

    // ---- assertCanAssignRole (RULE-02-03/05) ----

    @Test
    void assertCanAssignRole_superAdmin_canAssignAnyRole() {
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);
        assertThatCode(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanAssignRole_organizationAdmin_cannotEscalateToOrgAdminOrSuperAdmin() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);

        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.ORGANIZATION_ADMIN, orgId, null))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.SUPER_ADMIN, null, null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanAssignRole_organizationAdmin_cannotAssignOutsideOwnOrg() {
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);
        UUID otherStoreId = UUID.randomUUID();

        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.RECEPTIONIST, UUID.randomUUID(), otherStoreId))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanAssignRole_organizationAdmin_canAssignStoreRoleWithinOwnOrg() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);

        assertThatCode(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.RECEPTIONIST, orgId, storeId))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanAssignRole_storeManager_onlyAssignableRolesWithinOwnStore() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, orgId, storeId);

        assertThatCode(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.RECEPTIONIST, orgId, storeId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.STORE_MANAGER, orgId, storeId))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.RECEPTIONIST, orgId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.RECEPTIONIST, UUID.randomUUID(), storeId))
                .as("khác organizationId dù cùng storeId cũng phải bị chặn")
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanAssignRole_receptionist_hasNoAssignmentAuthority() {
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, null, UUID.randomUUID());
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAssignRole(actor, UserRole.CUSTOMER, null, null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertCanManageOrganization (RULE-02-05 — SUPER_ADMIN/ORGANIZATION_ADMIN only) ----

    @Test
    void assertCanManageOrganization_superAdmin_anyOrganization() {
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);
        assertThatCode(() -> RoleScopeGuard.assertCanManageOrganization(actor, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanManageOrganization_organizationAdmin_onlyOwnOrganization() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);
        assertThatCode(() -> RoleScopeGuard.assertCanManageOrganization(actor, orgId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageOrganization(actor, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageOrganization_storeManager_alwaysDenied() {
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageOrganization(actor, actor.getOrganizationId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertCanManageUser (RULE-02-05 — SUPER_ADMIN/ORGANIZATION_ADMIN/STORE_MANAGER) ----

    @Test
    void assertCanManageUser_organizationAdmin_deniedForDifferentOrganization() {
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, UUID.randomUUID(), null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageUser_storeManager_allowedOnlyForOwnStore() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, orgId, storeId);
        assertThatCode(() -> RoleScopeGuard.assertCanManageUser(actor, orgId, storeId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, orgId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, UUID.randomUUID(), storeId))
                .as("khác organizationId dù cùng storeId cũng phải bị chặn")
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageUser_customer_alwaysDenied() {
        UserPrincipal actor = principal(UserRole.CUSTOMER, null, null);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, null, null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertCanManageStore (RULE-02-05 — UpdateStore, docs/api/org-store-v1.md B) ----

    @Test
    void assertCanManageStore_superAdmin_alwaysAllowed() {
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);
        assertThatCode(() -> RoleScopeGuard.assertCanManageStore(actor, UUID.randomUUID(), UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanManageStore_organizationAdmin_allowedForAnyStoreInOwnOrg() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);
        assertThatCode(() -> RoleScopeGuard.assertCanManageStore(actor, orgId, UUID.randomUUID()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageStore(actor, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageStore_storeManager_allowedOnlyForOwnStore() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, orgId, storeId);
        assertThatCode(() -> RoleScopeGuard.assertCanManageStore(actor, orgId, storeId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageStore(actor, orgId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageStore(actor, UUID.randomUUID(), storeId))
                .as("khác organizationId dù cùng storeId cũng phải bị chặn")
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageStore_receptionist_alwaysDenied() {
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageStore(actor, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertIsOwnStoreManager (RULE-02-05 — ConfigureOperatingHour, chỉ STORE_MANAGER) ----

    @Test
    void assertIsOwnStoreManager_ownStore_allowed() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        assertThatCode(() -> RoleScopeGuard.assertIsOwnStoreManager(actor, storeId))
                .doesNotThrowAnyException();
    }

    @Test
    void assertIsOwnStoreManager_differentStore_denied() {
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> RoleScopeGuard.assertIsOwnStoreManager(actor, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertIsOwnStoreManager_superAdmin_stillDenied() {
        // Khác assertCanManageStore — SUPER_ADMIN KHÔNG có ngoại lệ ở đây (quyết định 2026-09-17,
        // bám literal docs/01-business-operations.md `01#3` chỉ gán StoreManager).
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);
        assertThatThrownBy(() -> RoleScopeGuard.assertIsOwnStoreManager(actor, storeId))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertIsOwnStoreManager_organizationAdmin_stillDenied() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);
        assertThatThrownBy(() -> RoleScopeGuard.assertIsOwnStoreManager(actor, storeId))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertCanAccessUserRecord (RULE-02-05/06 — GET/PATCH /users/{id}) ----

    @Test
    void assertCanAccessUserRecord_customerTarget_superAdminAndReceptionistAllowed() {
        assertThatCode(() -> RoleScopeGuard.assertCanAccessUserRecord(
                principal(UserRole.SUPER_ADMIN, null, null), UserRole.CUSTOMER, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> RoleScopeGuard.assertCanAccessUserRecord(
                principal(UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID()), UserRole.CUSTOMER, null))
                .doesNotThrowAnyException();
    }

    @Test
    void assertCanAccessUserRecord_customerTarget_organizationAdminDenied() {
        // Customer không thuộc Organization nào (organizationId luôn null trong schema) —
        // ORGANIZATION_ADMIN không quản trị được qua endpoint này, chỉ SUPER_ADMIN/RECEPTIONIST.
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAccessUserRecord(actor, UserRole.CUSTOMER, null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanAccessUserRecord_staffTarget_receptionistDenied() {
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAccessUserRecord(actor, UserRole.VETERINARIAN, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanAccessUserRecord_staffTarget_organizationAdminSameOrgAllowed_differentOrgDenied() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);

        assertThatCode(() -> RoleScopeGuard.assertCanAccessUserRecord(actor, UserRole.RECEPTIONIST, orgId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanAccessUserRecord(actor, UserRole.RECEPTIONIST, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---- assertNotSelfLifecycleAction (RULE-02-05) ----

    @Test
    void assertNotSelfLifecycleAction_sameUserId_throwsBusinessRuleViolation() {
        UUID userId = UUID.randomUUID();
        UserPrincipal actor = UserPrincipal.builder().userId(userId).role(UserRole.ORGANIZATION_ADMIN).build();
        assertThatThrownBy(() -> RoleScopeGuard.assertNotSelfLifecycleAction(actor, userId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessRuleViolationException) ex).getRuleId())
                        .isEqualTo("RULE-02-05"));
    }

    @Test
    void assertNotSelfLifecycleAction_differentUserId_doesNotThrow() {
        UserPrincipal actor = UserPrincipal.builder().userId(UUID.randomUUID()).role(UserRole.ORGANIZATION_ADMIN).build();
        assertThatCode(() -> RoleScopeGuard.assertNotSelfLifecycleAction(actor, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    // ---- assertCanManageAccountLifecycle (RULE-02-05 — Lock/Unlock/Deactivate/Reactivate) ----

    @Test
    void assertCanManageAccountLifecycle_customerTarget_onlySuperAdminAllowed() {
        assertThatCode(() -> RoleScopeGuard.assertCanManageAccountLifecycle(
                principal(UserRole.SUPER_ADMIN, null, null), UserRole.CUSTOMER, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageAccountLifecycle(
                principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null), UserRole.CUSTOMER, null))
                .as("Customer không gắn Organization trong schema hiện tại — ORGANIZATION_ADMIN không quản trị được")
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageAccountLifecycle(
                principal(UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID()), UserRole.CUSTOMER, null))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageAccountLifecycle_staffTarget_delegatesToAssertCanManageOrganization() {
        UUID orgId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId, null);
        assertThatCode(() -> RoleScopeGuard.assertCanManageAccountLifecycle(actor, UserRole.RECEPTIONIST, orgId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageAccountLifecycle(actor, UserRole.RECEPTIONIST, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }
}
