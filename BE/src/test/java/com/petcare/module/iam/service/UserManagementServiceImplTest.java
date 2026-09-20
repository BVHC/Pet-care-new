package com.petcare.module.iam.service;

import com.petcare.module.auth.service.AccountLifecycleService;
import com.petcare.module.auth.service.AccountSummary;
import com.petcare.module.iam.dto.RoleAssignmentRequest;
import com.petcare.module.iam.dto.UpdateOwnProfileRequest;
import com.petcare.module.iam.dto.UpdateUserRequest;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.mapper.IamMapper;
import com.petcare.module.iam.mapper.IamMapperImpl;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-02-01→07. */
@ExtendWith(MockitoExtension.class)
class UserManagementServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccountLifecycleService accountLifecycleService;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final IamMapper iamMapper = new IamMapperImpl();

    private UserManagementServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return principal(role, organizationId, null);
    }

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role)
                .organizationId(organizationId).storeId(storeId).build();
    }

    private static User targetUser(UUID organizationId) {
        return targetUser(organizationId, null, UserRole.CUSTOMER);
    }

    private static User targetUser(UUID organizationId, UUID storeId, UserRole role) {
        User user = new User(UUID.randomUUID(), "Nguyen Van C");
        user.setId(UUID.randomUUID());
        user.setOrganizationId(organizationId);
        user.setStoreId(storeId);
        user.setRole(role);
        return user;
    }

    @BeforeEach
    void setUp() {
        service = new UserManagementServiceImpl(userRepository, iamMapper, accountLifecycleService, outboxEventRepository);
    }

    @Test
    void getUser_notFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(principal(UserRole.SUPER_ADMIN, null), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getUser_organizationAdmin_differentOrg_deniedByScope_RULE_02_05() {
        User target = targetUser(UUID.randomUUID(), UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.getUser(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getUser_organizationAdmin_sameOrg_succeeds_RULE_02_05() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        var response = service.getUser(actor, target.getId());

        assertThat(response.userId()).isEqualTo(target.getId());
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void getUser_superAdmin_anyOrg_succeeds() {
        User target = targetUser(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));

        var response = service.getUser(principal(UserRole.SUPER_ADMIN, null), target.getId());

        assertThat(response.userId()).isEqualTo(target.getId());
    }

    @Test
    void getUser_receptionist_customerTarget_allowed_RULE_02_06() {
        User target = targetUser(null, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, null);

        var response = service.getUser(actor, target.getId());

        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void getUser_receptionist_staffTarget_denied() {
        User target = targetUser(UUID.randomUUID(), UUID.randomUUID(), UserRole.VETERINARIAN);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, null);

        assertThatThrownBy(() -> service.getUser(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getUser_organizationAdmin_customerTarget_denied() {
        // Customer không thuộc Organization nào (organizationId luôn null) — ORGANIZATION_ADMIN
        // không quản trị được Customer qua endpoint này, chỉ SUPER_ADMIN/RECEPTIONIST.
        User target = targetUser(null, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.getUser(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateUser_outOfScope_deniedBeforeSaving() {
        User target = targetUser(UUID.randomUUID(), UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(null, UUID.randomUUID(), false, null, null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_receptionist_customerTarget_updatesProfileFields_RULE_02_06() {
        User target = targetUser(null, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, null);

        var response = service.updateUser(actor, target.getId(),
                new UpdateUserRequest(null, null, false, "Ten Moi", "FEMALE", null, null));

        assertThat(response.fullName()).isEqualTo("Ten Moi");
        assertThat(response.gender()).isEqualTo("FEMALE");
        assertThat(target.getGender()).isEqualTo("FEMALE");
        verify(userRepository).save(target);
    }

    @Test
    void updateUser_customerTarget_bindingFieldsRejected_RULE_02_02() {
        User target = targetUser(null, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.RECEPTIONIST, null);

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(UUID.randomUUID(), null, false, null, null, null, null)))
                .isInstanceOf(com.petcare.platform.exception.BusinessRuleViolationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_staffTarget_profileFieldsRejected_RULE_02_06() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(null, null, false, "Ten Moi", null, null, null)))
                .isInstanceOf(com.petcare.platform.exception.BusinessRuleViolationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_organizationAdmin_cannotMoveUserToDifferentOrganization() {
        // Lỗ hổng đã fix: assertInManagementScope trước đây chỉ kiểm tra org HIỆN TẠI của
        // target (khớp) rồi cho phép ghi thẳng organizationId MỚI từ request mà không kiểm
        // tra giá trị mới có nằm trong quyền quản trị của actor hay không.
        UUID actorOrgId = UUID.randomUUID();
        UUID otherOrgId = UUID.randomUUID();
        User target = targetUser(actorOrgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, actorOrgId);

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(otherOrgId, null, false, null, null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_invalidRoleScopeBinding_rejected_RULE_02_02() {
        // ORGANIZATION_ADMIN không được gắn storeId (RULE-02-02) — updateUser phải chặn dù
        // actor có quyền quản trị đúng Organization của target.
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, null, UserRole.ORGANIZATION_ADMIN);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(orgId, UUID.randomUUID(), false, null, null, null, null)))
                .isInstanceOf(com.petcare.platform.exception.BusinessRuleViolationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_organizationAdmin_withinOwnOrg_movesToOwnStore() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));
        UUID newStoreId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        service.updateUser(actor, target.getId(), new UpdateUserRequest(null, newStoreId, false, null, null, null, null));

        assertThat(target.getStoreId()).isEqualTo(newStoreId);
        verify(userRepository).save(target);
    }

    @Test
    void updateUser_clearStoreId_unbindsStoreKeepingOrganization() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, UUID.randomUUID(), UserRole.FINANCE_STAFF);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.getSummary(target.getAccountId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.ACTIVE));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        service.updateUser(actor, target.getId(), new UpdateUserRequest(null, null, true, null, null, null, null));

        assertThat(target.getStoreId()).isNull();
        assertThat(target.getOrganizationId()).isEqualTo(orgId);
        verify(userRepository).save(target);
    }

    @Test
    void updateUser_clearStoreIdWithStoreId_rejected_RULE_02_02() {
        User target = targetUser(UUID.randomUUID(), UUID.randomUUID(), UserRole.FINANCE_STAFF);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        assertThatThrownBy(() -> service.updateUser(actor, target.getId(),
                new UpdateUserRequest(null, UUID.randomUUID(), true, null, null, null, null)))
                .isInstanceOf(com.petcare.platform.exception.BusinessRuleViolationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignRole_organizationAdmin_escalatesToOrgAdmin_denied_RULE_02_03() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        assertThatThrownBy(() -> service.assignRole(actor, target.getId(),
                new RoleAssignmentRequest(UserRole.ORGANIZATION_ADMIN, orgId, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignRole_organizationAdmin_targetCurrentlyInDifferentOrg_deniedBeforeReassigning() {
        // Lỗ hổng đã fix: trước đây assignRole chỉ kiểm tra scope của role/org/store MỚI được
        // yêu cầu (đều trùng org của actor) mà không kiểm tra target hiện đang thuộc org nào —
        // cho phép 1 ORGANIZATION_ADMIN "chiếm" user thuộc Organization bất kỳ.
        UUID actorOrgId = UUID.randomUUID();
        UUID targetCurrentOrgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        User target = targetUser(targetCurrentOrgId, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, actorOrgId);

        assertThatThrownBy(() -> service.assignRole(actor, target.getId(),
                new RoleAssignmentRequest(UserRole.RECEPTIONIST, actorOrgId, storeId)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignRole_storeManager_withinOwnStore_updatesUser() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        User target = targetUser(orgId, storeId, UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, orgId, storeId);

        var response = service.assignRole(actor, target.getId(),
                new RoleAssignmentRequest(UserRole.VETERINARIAN, orgId, storeId));

        assertThat(response.role()).isEqualTo(UserRole.VETERINARIAN);
        verify(userRepository).save(target);
    }

    @Test
    void assignRole_storeManager_targetInDifferentStore_denied() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, orgId, UUID.randomUUID());

        assertThatThrownBy(() -> service.assignRole(actor, target.getId(),
                new RoleAssignmentRequest(UserRole.VETERINARIAN, orgId, actor.getStoreId())))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignRole_organizationAdmin_withinOwnOrg_updatesUserAndRecordsEvent() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        User target = targetUser(orgId);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        var response = service.assignRole(actor, target.getId(),
                new RoleAssignmentRequest(UserRole.RECEPTIONIST, orgId, storeId));

        assertThat(response.role()).isEqualTo(UserRole.RECEPTIONIST);
        assertThat(target.getRole()).isEqualTo(UserRole.RECEPTIONIST);
        assertThat(target.getStoreId()).isEqualTo(storeId);
        verify(userRepository).save(target);
        verify(outboxEventRepository).save(any());
    }

    @Test
    void listUsers_batchFetchesAccountStatuses_notPerRow() {
        UUID orgId = UUID.randomUUID();
        User u1 = targetUser(orgId, UUID.randomUUID(), UserRole.RECEPTIONIST);
        User u2 = targetUser(orgId, UUID.randomUUID(), UserRole.VETERINARIAN);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(userRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(u1, u2)));
        when(accountLifecycleService.getStatuses(any())).thenReturn(java.util.Map.of(
                u1.getAccountId(), AccountStatus.ACTIVE,
                u2.getAccountId(), AccountStatus.LOCKED));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        var page = service.listUsers(actor, null, null, null, pageable);

        assertThat(page.content()).hasSize(2);
        verify(accountLifecycleService).getStatuses(any());
        verify(accountLifecycleService, never()).getSummary(any());
    }

    @Test
    void listUsers_superAdmin_organizationIdFilter_passedToSpecification() {
        UUID orgId = UUID.randomUUID();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(userRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(),
                eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        service.listUsers(principal(UserRole.SUPER_ADMIN, null), orgId, null, null, pageable);

        verify(userRepository).findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<User>>any(), eq(pageable));
    }

    @Test
    void listUsers_organizationAdmin_requestsDifferentOrganizationId_denied() {
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.listUsers(actor, UUID.randomUUID(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getOwnProfile_returnsCurrentProfile() {
        User user = targetUser(null, null, UserRole.CUSTOMER);
        user.setGender("MALE");
        user.setDateOfBirth(java.time.LocalDate.of(1995, 5, 1));
        user.setAvatarUrl("https://example.com/avatar.png");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(accountLifecycleService.getSummary(user.getAccountId()))
                .thenReturn(new AccountSummary(user.getAccountId(), AccountStatus.ACTIVE));

        var response = service.getOwnProfile(user.getId());

        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        // RULE-02-06 "Customer toàn quyền xem hồ sơ của chính mình" — gender/dateOfBirth/
        // avatarUrl từng bị thiếu khỏi UserResponse (write-only qua PATCH /users/me).
        assertThat(response.gender()).isEqualTo("MALE");
        assertThat(response.dateOfBirth()).isEqualTo(java.time.LocalDate.of(1995, 5, 1));
        assertThat(response.avatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void updateOwnProfile_patchesOnlyNonNullFields() {
        User user = targetUser(null, null, UserRole.CUSTOMER);
        user.setGender("MALE");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(accountLifecycleService.getSummary(user.getAccountId()))
                .thenReturn(new AccountSummary(user.getAccountId(), AccountStatus.ACTIVE));

        var response = service.updateOwnProfile(user.getId(), new UpdateOwnProfileRequest("Ten Moi", null, null, null));

        assertThat(user.getFullName()).isEqualTo("Ten Moi");
        assertThat(user.getGender()).isEqualTo("MALE");
        assertThat(response.fullName()).isEqualTo("Ten Moi");
        assertThat(response.gender()).isEqualTo("MALE");
        verify(userRepository).save(user);
    }

    @Test
    void lockAccount_outOfScope_deniedBeforeDelegating() {
        User target = targetUser(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.lockAccount(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(accountLifecycleService, never()).lockAccount(any(), any(), any());
    }

    @Test
    void lockAccount_inScope_delegatesToAccountLifecycleService() {
        UUID orgId = UUID.randomUUID();
        // Target là staff (không phải CUSTOMER) — Customer thật luôn có organizationId = null
        // (RULE-02-02) nên không thể "trong scope" của 1 ORGANIZATION_ADMIN qua organizationId.
        User target = targetUser(orgId, null, UserRole.RECEPTIONIST);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        // organizationId/storeId truyền xuống phải khớp scope của TARGET (không phải actor) —
        // xem AuditOrganizationId javadoc.
        when(accountLifecycleService.lockAccount(target.getAccountId(), target.getOrganizationId(), target.getStoreId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.LOCKED));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        var response = service.lockAccount(actor, target.getId());

        assertThat(response.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(response.userId()).isEqualTo(target.getId());
    }

    @Test
    void lockAccount_selfAction_deniedByBusinessRule_RULE_02_05() {
        UUID orgId = UUID.randomUUID();
        User target = targetUser(orgId, null, UserRole.ORGANIZATION_ADMIN);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        // Actor chính là target — mô phỏng 1 ORGANIZATION_ADMIN cố tự khóa tài khoản của mình.
        UserPrincipal actor = UserPrincipal.builder().userId(target.getId())
                .role(UserRole.ORGANIZATION_ADMIN).organizationId(orgId).build();

        assertThatThrownBy(() -> service.lockAccount(actor, target.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(accountLifecycleService, never()).lockAccount(any(), any(), any());
    }

    @Test
    void lockAccount_customerTarget_organizationAdmin_deniedEvenWithMatchingOrg() {
        UUID orgId = UUID.randomUUID();
        // Dữ liệu không thực tế (Customer thật không có organizationId) nhưng vẫn phải bị chặn:
        // assertCanManageAccountLifecycle từ chối theo targetRole=CUSTOMER trước khi so organizationId.
        User target = targetUser(orgId, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, orgId);

        assertThatThrownBy(() -> service.lockAccount(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(accountLifecycleService, never()).lockAccount(any(), any(), any());
    }

    @Test
    void lockAccount_customerTarget_superAdminAllowed() {
        User target = targetUser(null, null, UserRole.CUSTOMER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.lockAccount(target.getAccountId(), target.getOrganizationId(), target.getStoreId()))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.LOCKED));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        var response = service.lockAccount(actor, target.getId());

        assertThat(response.status()).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void lockAccount_superAdmin_passesTargetOrganizationId_notActorOrganizationId() {
        // Bug đã sửa: SUPER_ADMIN luôn có organizationId=null (RoleScopeGuard.validateRoleScopeBinding)
        // — nếu accountLifecycleService nhận nhầm org của actor thay vì target, audit_logs.organization_id
        // sẽ ghi NULL dù target thuộc hẳn 1 Organization cụ thể (RULE-25-01/07).
        UUID targetOrgId = UUID.randomUUID();
        UUID targetStoreId = UUID.randomUUID();
        User target = targetUser(targetOrgId, targetStoreId, UserRole.STORE_MANAGER);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountLifecycleService.lockAccount(target.getAccountId(), targetOrgId, targetStoreId))
                .thenReturn(new AccountSummary(target.getAccountId(), AccountStatus.LOCKED));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null);

        var response = service.lockAccount(actor, target.getId());

        assertThat(response.status()).isEqualTo(AccountStatus.LOCKED);
        verify(accountLifecycleService).lockAccount(target.getAccountId(), targetOrgId, targetStoreId);
    }

    @Test
    void unlockAccount_outOfScope_deniedBeforeDelegating() {
        User target = targetUser(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.unlockAccount(actor, target.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(accountLifecycleService, never()).unlockAccount(any(), any(), any());
    }

    @Test
    void deactivateAccount_outOfScope_deniedBeforeDelegating() {
        User target = targetUser(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.deactivateAccount(actor, target.getId(), "Nghi viec"))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(accountLifecycleService, never()).deactivateAccount(any(), any(), any(), any());
    }

    @Test
    void reactivateAccount_outOfScope_deniedBeforeDelegating() {
        User target = targetUser(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.reactivateAccount(actor, target.getId(), "Quay lai lam viec"))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(accountLifecycleService, never()).reactivateAccount(any(), any(), any(), any());
    }
}
