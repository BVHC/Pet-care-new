package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.CreateOrganizationRequest;
import com.petcare.module.organization.dto.OrganizationResponse;
import com.petcare.module.organization.dto.UpdateOrganizationRequest;
import com.petcare.module.organization.service.OrganizationService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md
 * §A/C1 (CreateOrganization, UpdateOrganization + list/detail derived).
 * RULE-03-01: cách ly tenant enforce ở Service qua RoleScopeGuard.
 */
@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class OrganizationController {

    private final OrganizationService organizationService;

    // A1 (docs/api/org-store-v1.md): tenant lifecycle là PLATFORM scope — Org chưa
    // tồn tại thì chưa có ORGANIZATION_ADMIN để tạo nó, nên chỉ SUPER_ADMIN được gọi.
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<OrganizationResponse>> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationResponse response = organizationService.createOrganization(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrganizationResponse>>> listOrganizations(
            @AuthenticationPrincipal UserPrincipal actor, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.listOrganizations(actor, pageable)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrganization(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID organizationId) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.getOrganization(organizationId, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<OrganizationResponse>> updateOrganization(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID organizationId,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.updateOrganization(organizationId, request, actor)));
    }
}
