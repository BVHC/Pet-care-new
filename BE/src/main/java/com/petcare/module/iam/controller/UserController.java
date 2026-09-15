package com.petcare.module.iam.controller;

import com.petcare.module.iam.dto.AccountLifecycleResponse;
import com.petcare.module.iam.dto.DeactivateRequest;
import com.petcare.module.iam.dto.ReactivateRequest;
import com.petcare.module.iam.dto.RoleAssignmentRequest;
import com.petcare.module.iam.dto.RoleAssignmentResponse;
import com.petcare.module.iam.dto.UpdateOwnProfileRequest;
import com.petcare.module.iam.dto.UpdateUserRequest;
import com.petcare.module.iam.dto.UserResponse;
import com.petcare.module.iam.service.UserManagementService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 02 (IAM) — docs/api/iam-v1.md. Mọi path rơi vào
 * SecurityConfig#anyRequest().authenticated() (không nằm trong permitAll) —
 * @PreAuthorize xử lý phần role theo docs/02-business-rules.md RULE-02-05.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class UserController {

    private final UserManagementService userManagementService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getOwnProfile(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getOwnProfile(actor.getUserId())));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateOwnProfile(
            @AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody UpdateOwnProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.updateOwnProfile(actor.getUserId(), request)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> listUsers(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UUID storeId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                userManagementService.listUsers(actor, organizationId, role, storeId, pageable)));
    }

    // RECEPTIONIST chỉ được xem/sửa hồ sơ CUSTOMER (RULE-02-06) — kiểm tra chi tiết theo
    // role của target nằm ở RoleScopeGuard#assertCanAccessUserRecord (Service).
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('RECEPTIONIST')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@AuthenticationPrincipal UserPrincipal actor,
                                                               @PathVariable("id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getUser(actor, userId)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('RECEPTIONIST')")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(@AuthenticationPrincipal UserPrincipal actor,
                                                                  @PathVariable("id") UUID userId,
                                                                  @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.updateUser(actor, userId, request)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PostMapping("/{id}/role-assignment")
    public ResponseEntity<ApiResponse<RoleAssignmentResponse>> assignRole(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.assignRole(actor, userId, request)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/{id}/lock")
    public ResponseEntity<ApiResponse<AccountLifecycleResponse>> lockAccount(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.lockAccount(actor, userId)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/{id}/unlock")
    public ResponseEntity<ApiResponse<AccountLifecycleResponse>> unlockAccount(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.unlockAccount(actor, userId)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<AccountLifecycleResponse>> deactivateAccount(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID userId,
            @RequestBody(required = false) DeactivateRequest request) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.deactivateAccount(actor, userId, reason)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<AccountLifecycleResponse>> reactivateAccount(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID userId,
            @Valid @RequestBody ReactivateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.reactivateAccount(actor, userId, request.reason())));
    }
}
