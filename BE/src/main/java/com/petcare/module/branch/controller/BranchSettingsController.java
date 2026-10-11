package com.petcare.module.branch.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.branch.dto.BranchServiceItem;
import com.petcare.module.branch.dto.QuotaDefaultResponse;
import com.petcare.module.branch.dto.SetBranchServiceRequest;
import com.petcare.module.branch.dto.SetQuotaDefaultRequest;
import com.petcare.module.branch.dto.SetSlotQuotaRequest;
import com.petcare.module.branch.dto.SlotQuotaResponse;
import com.petcare.module.branch.service.BranchServiceAvailabilityService;
import com.petcare.module.branch.service.QuotaService;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** Dịch vụ tại chi nhánh và quota của branch-v1 (UC33, UC42, endpoint #13–19). */
@RestController
@RequestMapping("/api/branches/{branchId}")
public class BranchSettingsController {

    private final BranchServiceAvailabilityService serviceAvailability;
    private final QuotaService quotas;

    public BranchSettingsController(BranchServiceAvailabilityService serviceAvailability, QuotaService quotas) {
        this.serviceAvailability = serviceAvailability;
        this.quotas = quotas;
    }

    @GetMapping("/services")
    @PreAuthorize(BranchAccess.STAFF_READ)
    public ApiResponse<List<BranchServiceItem>> listServices(@PathVariable Long branchId) {
        return ApiResponse.ok(serviceAvailability.listServices(branchId));
    }

    @PutMapping("/services/{serviceId}")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<BranchServiceItem> setService(@PathVariable Long branchId, @PathVariable Long serviceId,
            @Valid @RequestBody SetBranchServiceRequest request) {
        return ApiResponse.ok(serviceAvailability.setService(branchId, serviceId, request.enabled()),
                "Đã cập nhật dịch vụ tại chi nhánh");
    }

    @GetMapping("/quota-defaults")
    @PreAuthorize(BranchAccess.QUOTA_READ)
    public ApiResponse<List<QuotaDefaultResponse>> listQuotaDefaults(@PathVariable Long branchId) {
        return ApiResponse.ok(quotas.listDefaults(branchId));
    }

    @PutMapping("/quota-defaults/{serviceGroup}")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<QuotaDefaultResponse> setQuotaDefault(@PathVariable Long branchId,
            @PathVariable ServiceGroup serviceGroup, @Valid @RequestBody SetQuotaDefaultRequest request) {
        return ApiResponse.ok(quotas.setDefault(branchId, serviceGroup, request.defaultQuota()),
                "Đã cập nhật quota mặc định");
    }

    @GetMapping("/slot-quotas")
    @PreAuthorize(BranchAccess.QUOTA_READ)
    public ApiResponse<List<SlotQuotaResponse>> listSlotQuotas(@PathVariable Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ServiceGroup serviceGroup) {
        return ApiResponse.ok(quotas.listSlotQuotas(branchId, from, to, serviceGroup));
    }

    @PutMapping("/slot-quotas")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<SlotQuotaResponse> setSlotQuota(@PathVariable Long branchId,
            @Valid @RequestBody SetSlotQuotaRequest request) {
        return ApiResponse.ok(quotas.setSlotQuota(branchId, request), "Đã cập nhật quota của khung giờ");
    }

    @DeleteMapping("/slot-quotas/{slotQuotaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public void deleteSlotQuota(@PathVariable Long branchId, @PathVariable Long slotQuotaId) {
        quotas.deleteSlotQuota(branchId, slotQuotaId);
    }
}
