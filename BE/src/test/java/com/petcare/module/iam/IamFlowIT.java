package com.petcare.module.iam;

import com.petcare.module.auth.dto.CreateCustomerRequest;
import com.petcare.module.auth.dto.CreateCustomerResponse;
import com.petcare.module.auth.dto.CreateStaffRequest;
import com.petcare.module.auth.dto.CreateStaffResponse;
import com.petcare.module.auth.service.AuthService;
import com.petcare.module.iam.dto.RoleAssignmentRequest;
import com.petcare.module.iam.dto.UpdateUserRequest;
import com.petcare.module.iam.service.RoleCatalogService;
import com.petcare.module.iam.service.UserManagementService;
import com.petcare.module.notification.gateway.EmailGateway;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test luồng CreateStaff (Module 01) -> ManageUser/lifecycle
 * (Module 02) trên Postgres thật (Testcontainers), pattern giống AuthFlowIT.
 * docs/api/auth-v1.md C7, docs/api/iam-v1.md.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class IamFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthService authService;

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private RoleCatalogService roleCatalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmailGateway emailGateway;

    private String uniqueEmail() {
        return "staff-" + System.nanoTime() + "@example.com";
    }

    private static UserPrincipal superAdmin() {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(UserRole.SUPER_ADMIN).build();
    }

    /**
     * Module 03 (Organization & Store) chưa triển khai — chưa có
     * OrganizationRepository/StoreRepository để insert qua JPA. `users.organization_id`
     * có FK CONFIRMED tới `organizations` (V1__init_schema.sql fk_users_org), nên mọi
     * test tạo staff với orgId ngẫu nhiên phải insert Organization thật trước, nếu
     * không FK vi phạm ở lúc lưu User (không phải lỗi giả định — Postgres thật enforce).
     */
    private UUID createOrganization() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, code, name) VALUES (?, ?, ?)",
                id, "ORG-" + id.toString().substring(0, 8), "Test Org " + id);
        return id;
    }

    /** Cùng lý do như {@link #createOrganization()} — `fk_users_store` tới `stores`. */
    private UUID createStore(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stores (id, organization_id, code, name, address, phone) VALUES (?, ?, ?, ?, ?, ?)",
                id, organizationId, "STR-" + id.toString().substring(0, 8), "Test Store " + id,
                "123 Test Street", "0900000000");
        return id;
    }

    @Test
    void createStaff_thenLifecycle_lockUnlockDeactivateReactivate() {
        UUID orgId = createOrganization();
        UUID storeId = createStore(orgId);
        CreateStaffResponse created = authService.createStaff(
                new CreateStaffRequest(uniqueEmail(), null, "password123", "Nguyen Van Receptionist",
                        UserRole.RECEPTIONIST, orgId, storeId),
                superAdmin());
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.mustChangePassword()).isTrue();

        var actor = superAdmin();

        var locked = userManagementService.lockAccount(actor, created.userId());
        assertThat(locked.status()).isEqualTo(AccountStatus.LOCKED);

        var unlocked = userManagementService.unlockAccount(actor, created.userId());
        assertThat(unlocked.status()).isEqualTo(AccountStatus.ACTIVE);

        var deactivated = userManagementService.deactivateAccount(actor, created.userId(), "Nghỉ việc");
        assertThat(deactivated.status()).isEqualTo(AccountStatus.DEACTIVATED);

        // Reactivate thiếu reason -> RULE-02-07
        assertThatThrownBy(() -> userManagementService.reactivateAccount(actor, created.userId(), " "))
                .isInstanceOf(BusinessRuleViolationException.class);

        var reactivated = userManagementService.reactivateAccount(actor, created.userId(), "Quay lại làm việc");
        assertThat(reactivated.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void assignRole_organizationAdmin_escalationDenied_RULE_02_03() {
        UUID orgId = createOrganization();
        UUID storeId = createStore(orgId);
        CreateStaffResponse created = authService.createStaff(
                new CreateStaffRequest(uniqueEmail(), null, "password123", "Nguyen Van Staff",
                        UserRole.RECEPTIONIST, orgId, storeId),
                superAdmin());

        var orgAdmin = UserPrincipal.builder().userId(UUID.randomUUID())
                .role(UserRole.ORGANIZATION_ADMIN).organizationId(orgId).build();

        assertThatThrownBy(() -> userManagementService.assignRole(orgAdmin, created.userId(),
                new RoleAssignmentRequest(UserRole.ORGANIZATION_ADMIN, orgId, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listUsers_organizationAdmin_scopedToOwnOrganization() {
        UUID orgId = createOrganization();
        UUID storeId = createStore(orgId);
        UUID otherOrgId = createOrganization();
        authService.createStaff(new CreateStaffRequest(uniqueEmail(), null, "password123", "Nguyen Van Org1",
                UserRole.RECEPTIONIST, orgId, storeId), superAdmin());
        authService.createStaff(new CreateStaffRequest(uniqueEmail(), null, "password123", "Nguyen Van Org2",
                UserRole.RECEPTIONIST, otherOrgId, createStore(otherOrgId)), superAdmin());

        var orgAdmin = UserPrincipal.builder().userId(UUID.randomUUID())
                .role(UserRole.ORGANIZATION_ADMIN).organizationId(orgId).build();

        var page = userManagementService.listUsers(orgAdmin, null, null, null, PageRequest.of(0, 20));
        assertThat(page.content()).allSatisfy(u -> assertThat(u.organizationId()).isEqualTo(orgId));
    }

    @Test
    void listUsers_superAdmin_filtersByOrganizationId() {
        UUID orgId = createOrganization();
        UUID otherOrgId = createOrganization();
        authService.createStaff(new CreateStaffRequest(uniqueEmail(), null, "password123", "Staff Org A",
                UserRole.RECEPTIONIST, orgId, createStore(orgId)), superAdmin());
        authService.createStaff(new CreateStaffRequest(uniqueEmail(), null, "password123", "Staff Org B",
                UserRole.RECEPTIONIST, otherOrgId, createStore(otherOrgId)), superAdmin());

        var page = userManagementService.listUsers(superAdmin(), orgId, null, null, PageRequest.of(0, 20));
        assertThat(page.content()).isNotEmpty();
        assertThat(page.content()).allSatisfy(u -> assertThat(u.organizationId()).isEqualTo(orgId));
    }

    @Test
    void listUsers_organizationAdmin_requestsDifferentOrganizationId_denied() {
        var orgAdmin = UserPrincipal.builder().userId(UUID.randomUUID())
                .role(UserRole.ORGANIZATION_ADMIN).organizationId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> userManagementService.listUsers(orgAdmin, UUID.randomUUID(), null, null,
                PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void createCustomer_receptionistCanAccessProfile_organizationAdminCannot() {
        CreateCustomerResponse created = authService.createCustomer(
                new CreateCustomerRequest(uniqueEmail(), null, "password123", "Nguyen Thi Khach"));
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.mustChangePassword()).isTrue();

        var receptionist = UserPrincipal.builder().userId(UUID.randomUUID()).role(UserRole.RECEPTIONIST).build();
        var profile = userManagementService.getUser(receptionist, created.userId());
        assertThat(profile.role()).isEqualTo(UserRole.CUSTOMER);

        var updated = userManagementService.updateUser(receptionist, created.userId(),
                new UpdateUserRequest(null, null, false, "Nguyen Thi Khach Moi", null, null, null));
        assertThat(updated.fullName()).isEqualTo("Nguyen Thi Khach Moi");

        var orgAdmin = UserPrincipal.builder().userId(UUID.randomUUID())
                .role(UserRole.ORGANIZATION_ADMIN).organizationId(UUID.randomUUID()).build();
        assertThatThrownBy(() -> userManagementService.getUser(orgAdmin, created.userId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void roleCatalog_listRoles_returnsNineCanonicalGlobalRoles() {
        var roles = roleCatalogService.listRoles();

        assertThat(roles).hasSize(9);
        assertThat(roles).extracting(r -> r.code())
                .contains("SUPER_ADMIN", "ORGANIZATION_ADMIN", "STORE_MANAGER", "RECEPTIONIST",
                        "VETERINARIAN", "GROOMER", "INVENTORY_STAFF", "FINANCE_STAFF", "CUSTOMER");
        assertThat(roles).allSatisfy(r -> assertThat(r.organizationId()).isNull());
    }
}
