package com.petcare.module.iam.service;

import com.petcare.module.iam.dto.AccountLifecycleResponse;
import com.petcare.module.iam.dto.RoleAssignmentRequest;
import com.petcare.module.iam.dto.RoleAssignmentResponse;
import com.petcare.module.iam.dto.UpdateOwnProfileRequest;
import com.petcare.module.iam.dto.UpdateUserRequest;
import com.petcare.module.iam.dto.UserResponse;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Module 02 (IAM) — ManageUser/AssignPermission/LockAccount/UnlockAccount/
 * DeactivateAccount/ReactivateAccount (docs/01-business-operations.md §2,
 * docs/02-business-rules.md RULE-02-01→07, docs/api/iam-v1.md).
 */
public interface UserManagementService {

    UserResponse getOwnProfile(UUID userId);

    UserResponse updateOwnProfile(UUID userId, UpdateOwnProfileRequest request);

    PageResponse<UserResponse> listUsers(UserPrincipal actor, UUID organizationId, UserRole role, UUID storeId,
                                         Pageable pageable);

    UserResponse getUser(UserPrincipal actor, UUID userId);

    UserResponse updateUser(UserPrincipal actor, UUID userId, UpdateUserRequest request);

    RoleAssignmentResponse assignRole(UserPrincipal actor, UUID userId, RoleAssignmentRequest request);

    AccountLifecycleResponse lockAccount(UserPrincipal actor, UUID userId);

    AccountLifecycleResponse unlockAccount(UserPrincipal actor, UUID userId);

    AccountLifecycleResponse deactivateAccount(UserPrincipal actor, UUID userId, String reason);

    AccountLifecycleResponse reactivateAccount(UserPrincipal actor, UUID userId, String reason);
}
