package com.petcare.module.iam.controller;

import com.petcare.module.iam.dto.RoleResponse;
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
 * Module 02 (IAM) — docs/api/iam-v1.md A12 (`GET /roles`). Chỉ cần
 * {@code authenticated()} (SecurityConfig), không @PreAuthorize riêng — mọi
 * actor đã đăng nhập được đọc danh mục role.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class RoleController {

    private final RoleCatalogService roleCatalogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoleResponse>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.ok(roleCatalogService.listRoles()));
    }
}
