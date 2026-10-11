package com.petcare.module.identity.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.PublicVetResponse;
import com.petcare.module.identity.service.PublicVetService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.constraints.Positive;

/**
 * {@code GET /api/public/vets} (identity-v1 #37, UC15). Path public: luôn xử lý như chưa đăng nhập, token gửi kèm bị
 * bỏ qua (docs/adr/0005), nên không bị BR-TK-17 chặn. Ràng buộc trên tham số do Spring MVC tự validate
 * ({@code HandlerMethodValidationException} → 400 {@code VALIDATION_FAILED}); không đặt {@code @Validated} ở lớp.
 */
@RestController
@RequestMapping("/api/public/vets")
public class PublicVetController {

    private final PublicVetService publicVets;

    public PublicVetController(PublicVetService publicVets) {
        this.publicVets = publicVets;
    }

    @GetMapping
    public ApiResponse<List<PublicVetResponse>> listPublicVets(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId) {
        return ApiResponse.ok(publicVets.listPublicVets(branchId));
    }
}
