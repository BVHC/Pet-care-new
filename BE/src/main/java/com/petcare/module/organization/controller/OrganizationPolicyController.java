package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.OrganizationPolicyResponse;
import com.petcare.module.organization.dto.UpdateOrganizationPolicyRequest;
import com.petcare.module.organization.service.OrganizationPolicyService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md §A/§B (nửa Organization
 * của Q7, ManageOrganizationPolicy, RULE-03-09). CHỈ SUPER_ADMIN/ORGANIZATION_ADMIN — không có
 * STORE_MANAGER, khác OperatingHour/StoreResource.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class OrganizationPolicyController {

    private final OrganizationPolicyService organizationPolicyService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @GetMapping("/api/organizations/{id}/policy")
    public ResponseEntity<ApiResponse<OrganizationPolicyResponse>> getPolicy(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID organizationId) {
        return ResponseEntity.ok(ApiResponse.ok(organizationPolicyService.getPolicy(organizationId, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PatchMapping("/api/organizations/{id}/policy")
    public ResponseEntity<ApiResponse<OrganizationPolicyResponse>> updatePolicy(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID organizationId,
            @Valid @RequestBody UpdateOrganizationPolicyRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(organizationPolicyService.updatePolicy(organizationId, request, actor)));
    }
}
