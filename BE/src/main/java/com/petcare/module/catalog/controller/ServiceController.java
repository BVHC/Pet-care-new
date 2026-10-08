package com.petcare.module.catalog.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.service.ServiceCatalogService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** {@code /api/services} của catalog-v1 (UC30), gồm loại chuồng và loại Khám/Tiêm. */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceCatalogService services;

    public ServiceController(ServiceCatalogService services) {
        this.services = services;
    }

    @GetMapping
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<List<ServiceResponse>> list(@RequestParam(required = false) ServiceGroup group,
            @RequestParam(required = false) Boolean isActive) {
        return ApiResponse.ok(services.listServices(group, isActive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ServiceResponse> create(@Valid @RequestBody CreateServiceRequest request) {
        return ApiResponse.created(services.createService(request), "Đã tạo dịch vụ");
    }

    @GetMapping("/{serviceId}")
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<ServiceResponse> get(@PathVariable Long serviceId) {
        return ApiResponse.ok(services.getService(serviceId));
    }

    @PatchMapping("/{serviceId}")
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ServiceResponse> update(@PathVariable Long serviceId,
            @Valid @RequestBody UpdateServiceRequest request) {
        return ApiResponse.ok(services.updateService(serviceId, request), "Đã cập nhật dịch vụ");
    }
}
