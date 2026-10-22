package com.petcare.module.boarding.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.boarding.dto.CreateKennelRequest;
import com.petcare.module.boarding.dto.KennelResponse;
import com.petcare.module.boarding.dto.MaintenanceResultResponse;
import com.petcare.module.boarding.dto.UpdateKennelRequest;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.service.KennelService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Chuồng (UC54, boarding-v1 #1–6). Không chứa logic: mọi rule ở {@link KennelService}. */
@RestController
@RequestMapping("/api")
public class KennelController {

    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";
    static final String BRANCH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'CARETAKER')";

    private final KennelService kennels;

    public KennelController(KennelService kennels) {
        this.kennels = kennels;
    }

    /** boarding-v1 #1. */
    @Operation(operationId = "listKennels", tags = "Kennels",
            summary = "Danh sách chuồng của chi nhánh",
            description = "UC54, UC59 · BR-LT-01. Actor: A05, A06, A08. Quyền: Nhân viên chi nhánh. "
                + "Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "kennelTypeId", in = ParameterIn.QUERY, example = "5"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "AVAILABLE")})
    @ApiErrorDoc(status = 403)
    @GetMapping("/kennels")
    @PreAuthorize(BRANCH_STAFF)
    public ApiResponse<List<KennelResponse>> list(@RequestParam(required = false) Long kennelTypeId,
            @RequestParam(required = false) KennelStatus status) {
        return ApiResponse.ok(kennels.listKennels(kennelTypeId, status), "Danh sách chuồng");
    }

    /** boarding-v1 #2 (Chuồng#1). */
    @Operation(operationId = "createKennel", tags = "Kennels",
            summary = "Tạo chuồng",
            description = "UC54 · BR-LT-01, BR-SP-04. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. "
                + "Chuyển trạng thái: — → AVAILABLE (Chuồng#1).")
    @ApiErrorDoc(status = 400, description = "`BR-LT-01` mã trùng trong chi nhánh · loại chuồng không phải dịch vụ "
            + "BOARDING đang bật")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/kennels")
    @PreAuthorize(BRANCH_MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KennelResponse> create(@Valid @RequestBody CreateKennelRequest request) {
        return ApiResponse.created(kennels.createKennel(request), "Đã tạo chuồng");
    }

    /** boarding-v1 #3. */
    @Operation(operationId = "updateKennel", tags = "Kennels",
            summary = "Sửa mã / ghi chú chuồng",
            description = "UC54 · BR-LT-01. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "kennelId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/kennels/{kennelId}")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<KennelResponse> update(@PathVariable Long kennelId,
            @Valid @RequestBody UpdateKennelRequest request) {
        return ApiResponse.ok(kennels.updateKennel(kennelId, request), "Đã cập nhật chuồng");
    }

    /** boarding-v1 #4. */
    @Operation(operationId = "deleteKennel", tags = "Kennels",
            summary = "Xóa chuồng",
            description = "UC54 · BR-LT-01. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "kennelId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Chuồng đang OCCUPIED hoặc đã có lịch sử lưu trú "
            + "(chuyển bảo trì thay vì xóa)")
    @DeleteMapping("/kennels/{kennelId}")
    @PreAuthorize(BRANCH_MANAGER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long kennelId) {
        kennels.deleteKennel(kennelId);
    }

    /** boarding-v1 #5 (Chuồng#4). */
    @Operation(operationId = "startKennelMaintenance", tags = "Kennels",
            summary = "Chuyển chuồng sang bảo trì",
            description = "UC54 · BR-LT-01, 03. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. "
                + "Chuyển trạng thái: AVAILABLE → MAINTENANCE (Chuồng#4).",
            parameters = {@Parameter(name = "kennelId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Chuồng không AVAILABLE (OCCUPIED không chuyển được)")
    @PostMapping("/kennels/{kennelId}/start-maintenance")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<MaintenanceResultResponse> startMaintenance(@PathVariable Long kennelId) {
        return ApiResponse.ok(kennels.startMaintenance(kennelId), "Đã chuyển chuồng sang bảo trì");
    }

    /** boarding-v1 #6 (Chuồng#5). */
    @Operation(operationId = "endKennelMaintenance", tags = "Kennels",
            summary = "Hết bảo trì",
            description = "UC54 · BR-LT-01. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. "
                + "Chuyển trạng thái: MAINTENANCE → AVAILABLE (Chuồng#5).",
            parameters = {@Parameter(name = "kennelId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Chuồng không MAINTENANCE")
    @PostMapping("/kennels/{kennelId}/end-maintenance")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<KennelResponse> endMaintenance(@PathVariable Long kennelId) {
        return ApiResponse.ok(kennels.endMaintenance(kennelId), "Đã hết bảo trì");
    }
}
