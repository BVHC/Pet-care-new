package com.petcare.module.iam.service;

import com.petcare.module.auth.service.AccountLifecycleService;
import com.petcare.module.auth.service.AccountSummary;
import com.petcare.module.iam.dto.AccountLifecycleResponse;
import com.petcare.module.iam.dto.RoleAssignmentRequest;
import com.petcare.module.iam.dto.RoleAssignmentResponse;
import com.petcare.module.iam.dto.UpdateOwnProfileRequest;
import com.petcare.module.iam.dto.UpdateUserRequest;
import com.petcare.module.iam.dto.UserResponse;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.mapper.IamMapper;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.module.iam.repository.UserSpecifications;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.outbox.OutboxJsonSupport;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module 02 (IAM) — ManageUser/AssignPermission/lifecycle (RULE-02-01→07).
 * lock/unlock/deactivate/reactivate ủy quyền transition FSM-1 thật cho
 * {@link AccountLifecycleService} (module Auth) — IAM chỉ enforce ranh giới
 * thẩm quyền theo scope (RULE-02-05) trước khi gọi.
 */
@Service
@RequiredArgsConstructor
public class UserManagementServiceImpl implements UserManagementService {

    private final UserRepository userRepository;
    private final IamMapper iamMapper;
    private final AccountLifecycleService accountLifecycleService;
    private final OutboxEventRepository outboxEventRepository;

    @Override
    @Transactional(readOnly = true)
    public UserResponse getOwnProfile(UUID userId) {
        User user = findUser(userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateOwnProfile(UUID userId, UpdateOwnProfileRequest request) {
        User user = findUser(userId);
        applyProfileFields(user, request.fullName(), request.gender(), request.dateOfBirth(), request.avatarUrl());
        userRepository.save(user);
        return toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(UserPrincipal actor, UUID organizationId, UserRole role,
                                                 UUID storeId, Pageable pageable) {
        UUID organizationScope;
        if (actor.getRole() == UserRole.ORGANIZATION_ADMIN) {
            // RULE-02-05 — ORGANIZATION_ADMIN chỉ được xem Organization của chính mình; nếu
            // request truyền organizationId khác, từ chối rõ ràng thay vì âm thầm ép về org
            // của actor (NFR-USE-001 — caller phải biết lý do bị chặn).
            if (organizationId != null && !organizationId.equals(actor.getOrganizationId())) {
                throw new AccessDeniedScopeException("ORGANIZATION:" + actor.getOrganizationId(),
                        "ORGANIZATION:" + organizationId);
            }
            organizationScope = actor.getOrganizationId();
        } else {
            // SUPER_ADMIN — organizationId là filter tuỳ chọn (null = toàn platform).
            organizationScope = organizationId;
        }
        Page<User> page = userRepository.findAll(UserSpecifications.withFilters(organizationScope, role, storeId), pageable);

        List<UUID> accountIds = page.getContent().stream().map(User::getAccountId).toList();
        Map<UUID, AccountStatus> statuses = accountLifecycleService.getStatuses(accountIds);
        return PageResponse.of(page.map(user -> iamMapper.toUserResponse(user, statuses.get(user.getAccountId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(UserPrincipal actor, UUID userId) {
        User user = findUser(userId);
        RoleScopeGuard.assertCanAccessUserRecord(actor, user.getRole(), user.getOrganizationId());
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateUser(UserPrincipal actor, UUID userId, UpdateUserRequest request) {
        User user = findUser(userId);
        RoleScopeGuard.assertCanAccessUserRecord(actor, user.getRole(), user.getOrganizationId());

        boolean hasProfileFields = request.fullName() != null || request.gender() != null
                || request.dateOfBirth() != null || request.avatarUrl() != null;
        boolean hasBindingFields = request.organizationId() != null || request.storeId() != null
                || request.clearStoreId();

        if (user.getRole() == UserRole.CUSTOMER) {
            // RULE-02-02 — Customer không được gắn organizationId/storeId.
            if (hasBindingFields) {
                throw new BusinessRuleViolationException("RULE-02-02",
                        "Customer không được gắn organizationId/storeId");
            }
            applyProfileFields(user, request.fullName(), request.gender(), request.dateOfBirth(), request.avatarUrl());
        } else {
            // RULE-02-06 — 4 field profile ở endpoint này chỉ dành cho Customer (Receptionist
            // sửa tại quầy); với target là staff, endpoint chỉ rebind organizationId/storeId.
            if (hasProfileFields) {
                throw new BusinessRuleViolationException("RULE-02-06",
                        "Chỉ được cập nhật fullName/gender/dateOfBirth/avatarUrl cho Customer");
            }
            if (request.clearStoreId() && request.storeId() != null) {
                throw new BusinessRuleViolationException("RULE-02-02",
                        "Không thể vừa gán storeId vừa yêu cầu clearStoreId");
            }
            UUID newOrganizationId = request.organizationId() != null ? request.organizationId() : user.getOrganizationId();
            // clearStoreId=true xoá storeId hiện tại về null (vd rút FINANCE_STAFF khỏi Store,
            // vẫn giữ Organization) — storeId=null trong request vốn chỉ có nghĩa "giữ nguyên",
            // không có cách nào khác để xoá field này một cách tường minh.
            UUID newStoreId = request.clearStoreId() ? null
                    : (request.storeId() != null ? request.storeId() : user.getStoreId());

            if (hasBindingFields) {
                // RULE-02-02 — binding org/store mới vẫn phải hợp lệ với role hiện tại của user.
                RoleScopeGuard.validateRoleScopeBinding(user.getRole(), newOrganizationId, newStoreId);
                // RULE-02-05 — actor không được "chuyển" user sang 1 Organization ngoài quyền quản trị
                // của mình (nếu chỉ check scope HIỆN TẠI ở trên thì ORGANIZATION_ADMIN vẫn có thể
                // tự ý kéo user từ Org khác về Org mình bằng cách set organizationId mới).
                RoleScopeGuard.assertCanManageOrganization(actor, newOrganizationId);
            }
            user.setOrganizationId(newOrganizationId);
            user.setStoreId(newStoreId);
        }

        userRepository.save(user);
        return toResponse(user);
    }

    private void applyProfileFields(User user, String fullName, String gender, LocalDate dateOfBirth,
                                     String avatarUrl) {
        if (fullName != null) {
            user.setFullName(fullName);
        }
        if (gender != null) {
            user.setGender(gender);
        }
        if (dateOfBirth != null) {
            user.setDateOfBirth(dateOfBirth);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }
    }

    @Override
    @Transactional
    @Auditable(action = "AssignPermission", resourceType = "User")
    public RoleAssignmentResponse assignRole(UserPrincipal actor, @AuditResourceId UUID userId, RoleAssignmentRequest request) {
        User user = findUser(userId);
        // RULE-02-06 — chặn tự đổi role bản thân TRƯỚC mọi guard theo scope khác (actor
        // luôn "quản trị được" chính mình nên assertCanManageUser/assertCanAssignRole bên
        // dưới không tự nhiên chặn được trường hợp này).
        RoleScopeGuard.assertNotSelfAssignment(actor, userId);
        // RULE-02-05 — actor phải đang quản trị ĐÚNG user này (scope HIỆN TẠI) trước khi được
        // đổi role/scope của họ; assertCanAssignRole bên dưới chỉ kiểm tra role/scope MỚI được
        // yêu cầu, không kiểm tra target hiện đang thuộc quyền actor hay không — thiếu bước này
        // cho phép 1 ORGANIZATION_ADMIN đổi role của user thuộc Organization bất kỳ. Dùng
        // assertCanManageUser (không phải assertInManagementScope) vì STORE_MANAGER cũng được
        // phép gọi endpoint này (@PreAuthorize).
        RoleScopeGuard.assertCanManageUser(actor, user.getOrganizationId(), user.getStoreId());
        RoleScopeGuard.assertCanAssignRole(actor, request.role(), request.organizationId(), request.storeId());

        user.setRole(request.role());
        user.setOrganizationId(request.organizationId());
        user.setStoreId(request.storeId());
        userRepository.save(user);

        recordPermissionAssigned(user);
        return iamMapper.toRoleAssignmentResponse(user);
    }

    @Override
    @Transactional
    public AccountLifecycleResponse lockAccount(UserPrincipal actor, UUID userId) {
        User user = findUser(userId);
        assertInManagementScope(actor, user);
        // organizationId/storeId truyền xuống CHỈ phục vụ audit_logs.organization_id/store_id
        // (RULE-25-01) ghi đúng scope của user bị khóa — xem AuditOrganizationId/AuditStoreId.
        AccountSummary summary = accountLifecycleService.lockAccount(user.getAccountId(),
                user.getOrganizationId(), user.getStoreId());
        return new AccountLifecycleResponse(user.getId(), summary.accountId(), summary.status());
    }

    @Override
    @Transactional
    public AccountLifecycleResponse unlockAccount(UserPrincipal actor, UUID userId) {
        User user = findUser(userId);
        assertInManagementScope(actor, user);
        AccountSummary summary = accountLifecycleService.unlockAccount(user.getAccountId(),
                user.getOrganizationId(), user.getStoreId());
        return new AccountLifecycleResponse(user.getId(), summary.accountId(), summary.status());
    }

    @Override
    @Transactional
    public AccountLifecycleResponse deactivateAccount(UserPrincipal actor, UUID userId, String reason) {
        User user = findUser(userId);
        assertInManagementScope(actor, user);
        AccountSummary summary = accountLifecycleService.deactivateAccount(user.getAccountId(), reason,
                user.getOrganizationId(), user.getStoreId());
        return new AccountLifecycleResponse(user.getId(), summary.accountId(), summary.status());
    }

    @Override
    @Transactional
    public AccountLifecycleResponse reactivateAccount(UserPrincipal actor, UUID userId, String reason) {
        User user = findUser(userId);
        assertInManagementScope(actor, user);
        AccountSummary summary = accountLifecycleService.reactivateAccount(user.getAccountId(), reason,
                user.getOrganizationId(), user.getStoreId());
        return new AccountLifecycleResponse(user.getId(), summary.accountId(), summary.status());
    }

    /**
     * Dùng cho các thao tác chỉ SUPER_ADMIN/ORGANIZATION_ADMIN được gọi
     * (@PreAuthorize đã chặn STORE_MANAGER ở Controller). Chặn self-action
     * TRƯỚC (RULE-02-05 — actor không được tự khóa/vô hiệu hóa chính mình,
     * xem {@link RoleScopeGuard#assertNotSelfLifecycleAction}), sau đó mới xét
     * scope theo role của target ({@link RoleScopeGuard#assertCanManageAccountLifecycle}).
     * AssignPermission dùng {@link RoleScopeGuard#assertCanManageUser} riêng
     * vì StoreManager cũng được phép gọi.
     */
    private void assertInManagementScope(UserPrincipal actor, User target) {
        RoleScopeGuard.assertNotSelfLifecycleAction(actor, target.getId());
        RoleScopeGuard.assertCanManageAccountLifecycle(actor, target.getRole(), target.getOrganizationId());
    }

    private void recordPermissionAssigned(User user) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("User");
        event.setAggregateId(user.getId().toString());
        event.setEventType("PermissionAssigned");
        // Dùng chung OutboxJsonSupport.escapeJson thay vì nối chuỗi thô — cùng lý do
        // AccountEventRecorder được tách ra để tránh JSON hỏng nếu sau này có field
        // free-text nào được thêm vào payload này.
        event.setPayload("{\"userId\":\"" + OutboxJsonSupport.escapeJson(user.getId().toString())
                + "\",\"role\":\"" + OutboxJsonSupport.escapeJson(user.getRole().name()) + "\"}");
        outboxEventRepository.save(event);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private UserResponse toResponse(User user) {
        AccountSummary summary = accountLifecycleService.getSummary(user.getAccountId());
        return iamMapper.toUserResponse(user, summary.status());
    }
}
