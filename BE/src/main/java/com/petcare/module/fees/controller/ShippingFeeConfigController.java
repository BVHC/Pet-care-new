package com.petcare.module.fees.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.fees.dto.SaveShippingFeeConfigRequest;
import com.petcare.module.fees.dto.ShippingFeeConfigResponse;
import com.petcare.module.fees.service.ShippingFeeService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/** Cấu hình phí vận chuyển toàn chuỗi, fees-v1 #1, #2 (UC32, BR-SP-08, 11; docs/adr/0140). Chỉ SUPER_MANAGER. */
@RestController
@RequestMapping("/api/shipping-fee-config")
public class ShippingFeeConfigController {

    private static final String SUPER_MANAGER = "hasRole('SUPER_MANAGER')";

    private final ShippingFeeService fees;

    public ShippingFeeConfigController(ShippingFeeService fees) {
        this.fees = fees;
    }

    /** fees-v1 #1. */
    @Operation(operationId = "getShippingFeeConfig", tags = "Shipping fee", summary = "Xem cấu hình phí vận chuyển",
            description = "UC32 · BR-SP-08. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<ShippingFeeConfigResponse> getShippingFeeConfig() {
        return ApiResponse.ok(fees.get());
    }

    /** fees-v1 #2. */
    @Operation(operationId = "saveShippingFeeConfig", tags = "Shipping fee", summary = "Lưu cấu hình phí vận chuyển",
            description = "UC32 · BR-SP-08, 11. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400, description = "`BR-SP-08` số âm hoặc sai kiểu")
    @ApiErrorDoc(status = 403)
    @PutMapping
    @PreAuthorize(SUPER_MANAGER)
    public ApiResponse<ShippingFeeConfigResponse> saveShippingFeeConfig(
            @Valid @RequestBody SaveShippingFeeConfigRequest request) {
        return ApiResponse.ok(fees.save(request));
    }
}
