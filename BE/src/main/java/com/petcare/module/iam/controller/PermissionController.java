package com.petcare.module.iam.controller;

import com.petcare.module.iam.dto.PermissionResponse;
import com.petcare.module.iam.service.RoleCatalogService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Module 02 (IAM) — docs/api/iam-v1.md A13 (`GET /permissions`). Bảng
 * `permissions` chưa được seed (chưa module nào định nghĩa permission code
 * thật) nên endpoint này trả mảng rỗng cho tới khi có module cần.
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class PermissionController {

    private final RoleCatalogService roleCatalogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> listPermissions() {
        return ResponseEntity.ok(ApiResponse.ok(roleCatalogService.listPermissions()));
    }
}
