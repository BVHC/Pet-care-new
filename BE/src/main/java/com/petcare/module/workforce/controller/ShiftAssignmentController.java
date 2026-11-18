package com.petcare.module.workforce.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.service.ShiftAssignmentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Phân ca của chi nhánh (UC34, workforce-v1 #4–5; docs/adr/0138). Không chứa logic và không biết mã rule: BR-NS-02 /
 * BR-NS-03 do {@link ShiftAssignmentService} kiểm. Chỉ BRANCH_MANAGER (A06–A08 không có quyền đọc ở đây, khác với
 * {@code GET /shift-definitions}).
 */
@RestController
@RequestMapping("/api")
public class ShiftAssignmentController {

    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";

    private final ShiftAssignmentService shiftAssignments;

    public ShiftAssignmentController(ShiftAssignmentService shiftAssignments) {
        this.shiftAssignments = shiftAssignments;
    }

    /** workforce-v1 #4. */
    @Operation(operationId = "listShiftAssignments", tags = "Shift assignments",
            summary = "Lịch phân ca của chi nhánh",
            description = "UC34 · BR-NS-03. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. Chuyển trạng thái: —. "
                + "Sắp `workDate`, `startTime` tăng dần, trùng thì `shiftAssignmentId` tăng dần. Chi nhánh đông nhân "
                + "viên có thể vượt một trang (`size` tối đa 100): màn lịch tuần gọi thêm trang. Luôn là chi nhánh của "
                + "người gọi; `staffId` của chi nhánh khác cho trang rỗng.",
            parameters = {
                    @Parameter(name = "from", in = ParameterIn.QUERY, required = true, example = "2026-11-23"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, required = true, example = "2026-11-29"),
                    @Parameter(name = "staffId", in = ParameterIn.QUERY, example = "12"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "SCHEDULED"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "`from` / `to` thiếu hoặc sai kiểu (`MALFORMED_REQUEST`) · `to` < `from` "
            + "hoặc khoảng quá 62 ngày tính cả hai đầu (`BR-NS-03`, A9)")
    @ApiErrorDoc(status = 403)
    @GetMapping("/shift-assignments")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<PageResponse<ShiftAssignmentResponse>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) ShiftAssignmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shiftAssignments.listShiftAssignments(from, to, staffId, status, page, size),
                "Lịch phân ca của chi nhánh");
    }

    /** workforce-v1 #5. */
    @Operation(operationId = "createShiftAssignment", tags = "Shift assignments",
            summary = "Phân ca cho nhân viên",
            description = "UC34 · BR-NS-02, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. Chuyển trạng "
                + "thái: — → SCHEDULED (Phân ca#1). Phân hộ (đường dự phòng, ví dụ nhân viên mới vào giữa tuần): tạo "
                + "phân ca `source = MANAGER`. Không thông báo cho nhân viên (BR-NS-08 không liệt kê phân hộ). Không "
                + "kiểm định mức tuần (BR-NS-12). Chồng giờ là hai khoảng giờ giao nhau thật sự; hai ca liền kề (kết "
                + "thúc = bắt đầu) không chồng. Nhân viên tự đăng ký dùng `PUT /me/shift-registrations/{weekStart}`.")
    @ApiErrorDoc(status = 400, description = "`BR-NS-02` ngày trong quá khứ · ca ngừng dùng · nhân viên không phải "
            + "A06–A08 `ACTIVE` của chi nhánh · trùng hoặc chồng giờ phân ca khác")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy nhân viên hoặc ca trong chi nhánh (id của chi nhánh khác "
            + "và id không tồn tại trả cùng một dạng lỗi)")
    @PostMapping("/shift-assignments")
    @PreAuthorize(BRANCH_MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftAssignmentResponse> create(@Valid @RequestBody CreateShiftAssignmentRequest request) {
        return ApiResponse.created(shiftAssignments.createShiftAssignment(request), "Đã phân ca cho nhân viên");
    }
}
