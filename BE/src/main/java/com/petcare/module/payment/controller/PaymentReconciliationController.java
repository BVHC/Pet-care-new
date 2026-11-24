package com.petcare.module.payment.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.payment.dto.ReconciliationItem;
import com.petcare.module.payment.dto.ReconciliationSummary;
import com.petcare.module.payment.dto.ReconciliationSummary.ReconciliationStatus;
import com.petcare.module.payment.service.PaymentReconciliationService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** Các API đối soát cổng dành cho quản lý điều hành (payment-v1, ST14). */
@RestController
@RequestMapping("/api")
public class PaymentReconciliationController {
    private static final String SUPER_MANAGER = "hasRole('SUPER_MANAGER')";
    private final PaymentReconciliationService reconciliations;

    public PaymentReconciliationController(PaymentReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
    }

    @Operation(operationId = "listPaymentReconciliations", tags = "Reconciliation", summary = "Danh sách phiên đối soát",
            description = "ST14 · BR-TG-10. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. "
                    + "Mới nhất trước (`reconDate` giảm dần).")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/payment-reconciliations")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<PageResponse<ReconciliationSummary>> list(
            @RequestParam(required = false) ReconciliationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(reconciliations.list(status, page, size));
    }

    @Operation(operationId = "listReconciliationItems", tags = "Reconciliation",
            summary = "Các mục lệch của một phiên đối soát",
            description = "ST14 · BR-TG-10. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/payment-reconciliations/{reconciliationId}/items")
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<PageResponse<ReconciliationItem>> listItems(
            @PathVariable @Positive long reconciliationId,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(reconciliations.listItems(reconciliationId, resolved, page, size));
    }
}
