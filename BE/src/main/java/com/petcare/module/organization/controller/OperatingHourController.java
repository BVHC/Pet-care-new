package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.ConfigureOperatingHoursRequest;
import com.petcare.module.organization.dto.OperatingHoursResponse;
import com.petcare.module.organization.service.OperatingHourService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md §C3
 * (ConfigureOperatingHour, RULE-03-02/07). CHỈ STORE_MANAGER đúng Store mình quản lý được gọi
 * (quyết định 2026-09-17, bám literal docs/01-business-operations.md `01#3`) — khác các endpoint
 * Store khác vốn cho cả SUPER_ADMIN/ORGANIZATION_ADMIN.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class OperatingHourController {

    private final OperatingHourService operatingHourService;

    @PreAuthorize("hasRole('STORE_MANAGER')")
    @PutMapping("/api/stores/{id}/operating-hours")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>> configureOperatingHours(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody ConfigureOperatingHoursRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(operatingHourService.configureOperatingHours(storeId, request, actor)));
    }
}
