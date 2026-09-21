package com.petcare.module.catalog.controller;

import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.service.ServiceCatalogService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module 05 — docs/api/catalog-v1.md §A/C1 (ManageService/ViewService), cùng phân quyền {@link ProductController}. */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class ServiceCatalogController {

    private final ServiceCatalogService serviceCatalogService;

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/api/services")
    public ResponseEntity<ApiResponse<ServiceResponse>> createService(
            @AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody CreateServiceRequest request) {
        ServiceResponse response = serviceCatalogService.createService(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/services")
    public ResponseEntity<ApiResponse<PageResponse<ServiceResponse>>> listServices(
            @AuthenticationPrincipal UserPrincipal actor, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(serviceCatalogService.listServices(actor, pageable)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/services/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> getService(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID serviceId) {
        return ResponseEntity.ok(ApiResponse.ok(serviceCatalogService.getService(serviceId, actor)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PatchMapping("/api/services/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> updateService(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID serviceId,
            @Valid @RequestBody UpdateServiceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(serviceCatalogService.updateService(serviceId, request, actor)));
    }
}
