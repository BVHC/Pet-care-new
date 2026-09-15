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
        UUID storeId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);
        assertThatCode(() -> RoleScopeGuard.assertCanManageUser(actor, UUID.randomUUID(), storeId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void assertCanManageUser_customer_alwaysDenied() {
        UserPrincipal actor = principal(UserRole.CUSTOMER, null, null);
        assertThatThrownBy(() -> RoleScopeGuard.assertCanManageUser(actor, null, null))
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
}
