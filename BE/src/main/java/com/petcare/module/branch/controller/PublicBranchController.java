package com.petcare.module.branch.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.branch.dto.PublicBranchResponse;
import com.petcare.module.branch.service.PublicBranchService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.constraints.Positive;

/**
 * {@code GET /api/public/branches[/{branchId}]} (branch-v1 #20–21, UC15). Path public: luôn xử lý như chưa đăng nhập,
 * token gửi kèm bị bỏ qua (docs/adr/0005). Ràng buộc trên tham số do Spring MVC tự validate, không đặt
 * {@code @Validated} ở lớp.
 */
@RestController
@RequestMapping("/api/public/branches")
public class PublicBranchController {

    private final PublicBranchService publicBranches;

    public PublicBranchController(PublicBranchService publicBranches) {
        this.publicBranches = publicBranches;
    }

    @Operation(operationId = "listPublicBranches", tags = "Public", summary = "Chi nhánh công khai",
            description = "UC15 · BR-CK-01, BR-CN-05. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @GetMapping
    public ApiResponse<List<PublicBranchResponse>> listPublicBranches(
            @RequestParam(required = false) Boolean acceptsAfterHoursEmergency) {
        return ApiResponse.ok(publicBranches.list(acceptsAfterHoursEmergency));
    }

    @Operation(operationId = "getPublicBranch", tags = "Public", summary = "Chi tiết chi nhánh công khai",
            description = "UC15 · BR-CK-01. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —. "
                + "Chi nhánh DRAFT trả 404.")
    @ApiErrorDoc(status = 404)
    @GetMapping("/{branchId}")
    public ApiResponse<PublicBranchResponse> getPublicBranch(
            @PathVariable @Positive(message = "branchId phải là số dương") Long branchId) {
        return ApiResponse.ok(publicBranches.get(branchId));
    }
}
