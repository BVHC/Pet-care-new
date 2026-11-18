package com.petcare.module.workforce.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftReasonRequest;
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
 * Phân ca (UC34, UC35, workforce-v1 #4–6, #8; docs/adr/0138). Không chứa logic và không biết mã rule: BR-NS-02 /
 * BR-NS-03 do {@link ShiftAssignmentService} kiểm. #4–6 chỉ BRANCH_MANAGER (A06–A08 không có quyền ở đây, khác với
 * {@code GET /shift-definitions}); #8 chỉ nhân viên A06–A08 và chỉ lịch của chính họ.
 */
@RestController
@RequestMapping("/api")
public class ShiftAssignmentController {

    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";

    /** Nhân viên A06–A08 (không gồm BRANCH_MANAGER: quản lý không nằm trong lịch, workforce-v1 Q3). */
    static final String STAFF = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";

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

    /** workforce-v1 #6. */
    @Operation(operationId = "cancelShiftAssignment", tags = "Shift assignments", summary = "Hủy phân ca",
            description = "UC34 · BR-NS-02, BR-NS-08, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. "
                + "Chuyển trạng thái: SCHEDULED → CANCELLED (Phân ca#2). Cùng một transaction: phân ca → `CANCELLED` và "
                + "yêu cầu `PENDING` của nó → `REJECTED` (Yêu cầu#5; `decision_note` do hệ thống ghi, `decided_by` "
                + "rỗng); một bước lỗi thì rollback cả hai. Thông báo ghi outbox cùng transaction (BR-NS-08).",
            parameters = {@Parameter(name = "shiftAssignmentId", in = ParameterIn.PATH, example = "31")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-02` ca đã bắt đầu (dùng ghi vắng đột xuất); `VALIDATION_FAILED` "
            + "thiếu lý do")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED_SCOPE_MISMATCH` phân ca thuộc chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` phân ca không còn `SCHEDULED` (kiểm trước "
            + "guard 400, A11)")
    @PostMapping("/shift-assignments/{shiftAssignmentId}/cancel")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<ShiftAssignmentResponse> cancel(@PathVariable Long shiftAssignmentId,
            @Valid @RequestBody ShiftReasonRequest request) {
        return ApiResponse.ok(shiftAssignments.cancelShiftAssignment(shiftAssignmentId, request), "Đã hủy phân ca");
    }

    /** workforce-v1 #8. */
    @Operation(operationId = "listMyShiftAssignments", tags = "Shift assignments", summary = "Lịch làm việc của tôi",
            description = "UC35 · BR-NS-03. Actor: A06–A08. Quyền: Nhân viên A06–A08, chỉ dữ liệu của mình. Chuyển "
                + "trạng thái: —. Không phân trang (tối đa 62 ngày). Sắp `workDate`, `startTime` tăng dần. Gồm cả "
                + "phân ca đã nghỉ / vắng / hủy để hiển thị lịch sử.",
            parameters = {
                    @Parameter(name = "from", in = ParameterIn.QUERY, required = true, example = "2026-11-23"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, required = true, example = "2026-11-29")})
    @ApiErrorDoc(status = 400, description = "`from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày "
            + "(`BR-NS-03`)")
    @ApiErrorDoc(status = 403)
    @GetMapping("/me/shift-assignments")
    @PreAuthorize(STAFF)
    public ApiResponse<List<ShiftAssignmentResponse>> listMine(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(shiftAssignments.listMyShiftAssignments(from, to), "Lịch làm việc của tôi");
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
