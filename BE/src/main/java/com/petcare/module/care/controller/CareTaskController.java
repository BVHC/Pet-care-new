package com.petcare.module.care.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.dto.CancelCareTaskRequest;
import com.petcare.module.care.dto.CareTaskResponse;
import com.petcare.module.care.dto.CompleteCareTaskRequest;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.service.CareTaskDeskService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Việc gọi điện chăm sóc khách của lễ tân — care-v1 #1–4 (UC87, BR-TB-05; docs/adr/0080). Chỉ RECEPTIONIST, chỉ chi
 * nhánh của mình (lọc trong service). Không chứa logic và không biết mã rule: BR-TB-05 do {@link CareTaskDeskService}
 * kiểm, nên request cố ý không có {@code @NotNull} / {@code @NotBlank}.
 */
@RestController
@RequestMapping("/api/care-tasks")
public class CareTaskController {

    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";

    private final CareTaskDeskService careTasks;

    public CareTaskController(CareTaskDeskService careTasks) {
        this.careTasks = careTasks;
    }

    /** care-v1 #1. */
    @Operation(operationId = "listCareTasks", tags = "Care tasks",
            summary = "Danh sách việc gọi điện của chi nhánh",
            description = "UC87 · BR-TB-05, BR-LT-10. Actor: A06. Quyền: Lễ tân chi nhánh được giao. Chuyển trạng thái: —. "
                + "Lọc theo chi nhánh của lễ tân (không có tham số chi nhánh). `status` vắng = OPEN. Sắp `dueDate` tăng "
                + "dần, trùng thì `careTaskId` tăng dần. `page` < 0 → 0, `size` kẹp 1–100. `dueFrom` > `dueTo` → trang "
                + "rỗng (docs/adr/0080).",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "OPEN"),
                    @Parameter(name = "type", in = ParameterIn.QUERY, example = "VACCINE_DUE"),
                    @Parameter(name = "dueFrom", in = ParameterIn.QUERY, example = "2026-11-01"),
                    @Parameter(name = "dueTo", in = ParameterIn.QUERY, example = "2026-11-30"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<PageResponse<CareTaskResponse>> listCareTasks(
            @RequestParam(required = false) CareTaskStatus status,
            @RequestParam(required = false) CareTaskType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(careTasks.list(status, type, dueFrom, dueTo, page, size));
    }

    /** care-v1 #2. */
    @Operation(operationId = "getCareTask", tags = "Care tasks", summary = "Chi tiết Care Task",
            description = "UC87 · BR-TB-05. Actor: A06. Quyền: Lễ tân chi nhánh được giao. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "careTaskId", in = ParameterIn.PATH, example = "311")})
    @ApiErrorDoc(status = 400, description = "`careTaskId` sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{careTaskId}")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<CareTaskResponse> getCareTask(@PathVariable Long careTaskId) {
        return ApiResponse.ok(careTasks.get(careTaskId));
    }

    /** care-v1 #3, Care Task#2. */
    @Operation(operationId = "completeCareTask", tags = "Care tasks", summary = "Ghi kết quả gọi điện",
            description = "UC87 · BR-TB-05. Actor: A06. Quyền: Lễ tân chi nhánh được giao. Chuyển trạng thái: "
                + "OPEN → DONE (Care Task#2). Không liên lạc được: không sinh task gọi lại.",
            parameters = {@Parameter(name = "careTaskId", in = ParameterIn.PATH, example = "311")})
    @ApiErrorDoc(status = 400, description = "`BR-TB-05` chưa chọn kết quả / thiếu ghi chú; `VALIDATION_FAILED` ghi chú "
            + "quá 2000 ký tự; `MALFORMED_REQUEST` body hoặc `careTaskId` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Task không OPEN")
    @PostMapping("/{careTaskId}/complete")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<CareTaskResponse> completeCareTask(@PathVariable Long careTaskId,
            @Valid @RequestBody CompleteCareTaskRequest request) {
        return ApiResponse.ok(careTasks.complete(careTaskId, request), "Đã ghi kết quả gọi điện");
    }

    /** care-v1 #4, Care Task#3. */
    @Operation(operationId = "cancelCareTask", tags = "Care tasks", summary = "Hủy Care Task",
            description = "UC87 · BR-TB-05. Actor: A06. Quyền: Lễ tân chi nhánh được giao. Chuyển trạng thái: "
                + "OPEN → CANCELLED (Care Task#3).",
            parameters = {@Parameter(name = "careTaskId", in = ParameterIn.PATH, example = "311")})
    @ApiErrorDoc(status = 400, description = "`BR-TB-05` thiếu lý do; `VALIDATION_FAILED` lý do quá 300 ký tự; "
            + "`MALFORMED_REQUEST` body hoặc `careTaskId` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Task không OPEN")
    @PostMapping("/{careTaskId}/cancel")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<CareTaskResponse> cancelCareTask(@PathVariable Long careTaskId,
            @Valid @RequestBody CancelCareTaskRequest request) {
        return ApiResponse.ok(careTasks.cancel(careTaskId, request), "Đã hủy việc chăm sóc khách");
    }
}
