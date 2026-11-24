package com.petcare.module.workforce.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.PutShiftRegistrationRequest;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.service.ShiftRegistrationService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Đăng ký ca theo tuần (UC35, workforce-v1 #15–17; docs/adr/0149). Không chứa logic và không biết mã rule: BR-NS-11 do
 * {@link ShiftRegistrationService} kiểm. Chỉ nhân viên A06–A08 và chỉ dữ liệu của chính họ; dùng chung hằng quyền
 * {@code STAFF} của {@link ShiftAssignmentController} để hai nơi không lệch nhau. Hiện có #15 (PUT) và #17; #16 (thống
 * kê tuần của quản lý) ở {@link ShiftWeeklySummaryController}.
 */
@RestController
@RequestMapping("/api")
public class ShiftRegistrationController {

    private final ShiftRegistrationService registrations;

    public ShiftRegistrationController(ShiftRegistrationService registrations) {
        this.registrations = registrations;
    }

    /** workforce-v1 #15. */
    @Operation(operationId = "putMyShiftRegistration", tags = "Shift registrations",
            summary = "Đăng ký ca theo tuần (thay cả tuần)",
            description = "UC35 · BR-NS-11, BR-NS-12. Actor: A06–A08. Quyền: Nhân viên A06–A08, chỉ dữ liệu của mình. "
                + "Chuyển trạng thái: — → SCHEDULED (Phân ca#1) · SCHEDULED → CANCELLED (Phân ca#7). Idempotent: gửi lại "
                + "cùng nội dung không đổi kết quả. Mục thiếu so với phân ca `SELF` hiện có bị hủy (lý do hệ thống ghi "
                + "'Nhân viên sửa đăng ký tuần'), mục mới được tạo, mục giữ nguyên không đổi; phân ca `MANAGER` không bị "
                + "đụng nhưng được tính vào định mức. Không thông báo, không audit (BR-NS-08, 10). Yêu cầu `PENDING` của "
                + "phân ca bị hủy tự `REJECTED` (Yêu cầu#5). Mọi mục tạo / hủy và Yêu cầu#5 nằm trong **một** "
                + "transaction. Response chỉ gồm phân ca `SCHEDULED` của tuần (A12). Đọc đăng ký hiện có và định mức "
                + "trước khi sửa: `GET /me/shift-registrations/{weekStart}`; xem lịch đầy đủ (cả nghỉ / vắng / hủy): "
                + "`GET /me/shift-assignments`. `items` tối đa 100 phần tử, vượt thì 400 `VALIDATION_FAILED` (Q4, "
                + "ADR-0148 mục 12).",
            parameters = {@Parameter(name = "weekStart", in = ParameterIn.PATH, example = "2026-11-23",
                    description = "Thứ Hai đầu tuần, yyyy-MM-dd, năm 2000–2100")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-11` `weekStart` không phải thứ Hai · quá hạn (sau 00:00 thứ Hai của "
            + "tuần) · ngày ngoài tuần · ca ngừng dùng hoặc không thuộc chi nhánh · trùng cặp (ngày, ca) trong lô · "
            + "chồng giờ với phân ca khác (kể cả `MANAGER`); `BR-NS-12` tập kết quả của tuần chưa đủ định mức (kể cả "
            + "`items` rỗng). Một lỗi thì không lưu gì, `message` liệt kê mọi mục lỗi; `VALIDATION_FAILED` thiếu "
            + "`items`, phần tử hoặc trường `workDate` / `shiftDefinitionId` null, hoặc quá 100 phần tử · "
            + "`MALFORMED_REQUEST` body hoặc `weekStart` sai kiểu, sai định dạng")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED` sai vai trò · `ACCESS_DENIED_SCOPE_MISMATCH` tài khoản "
            + "không còn là nhân viên A06–A08 đang hoạt động của một chi nhánh (đọc lại dưới khóa)")
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` trùng cặp ca – ngày do thao tác song song")
    @PutMapping("/me/shift-registrations/{weekStart}")
    @PreAuthorize(ShiftAssignmentController.STAFF)
    public ApiResponse<ShiftRegistrationResponse> putMine(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @Valid @RequestBody PutShiftRegistrationRequest request) {
        return ApiResponse.ok(registrations.putMyShiftRegistration(weekStart, request), "Đã lưu đăng ký ca của tuần");
    }

    /** workforce-v1 #17. */
    @Operation(operationId = "getMyShiftRegistration", tags = "Shift registrations",
            summary = "Đăng ký ca của tôi trong tuần",
            description = "UC35 · BR-NS-11, BR-NS-12. Actor: A06–A08. Quyền: Nhân viên A06–A08, chỉ dữ liệu của mình. "
                + "Chuyển trạng thái: —. Chỉ đọc, dùng để nạp phân ca hiện có và `requiredShifts` (định mức [CFG]) "
                + "trước khi gọi `PUT` thay cả tuần. Không kiểm hạn đăng ký: xem được cả tuần đã qua hạn. Cùng dạng "
                + "response với `PUT`: chỉ phân ca `SCHEDULED` của tuần (A12), cả `SELF` và `MANAGER`; tuần chưa có "
                + "phân ca trả `assignments` rỗng, `shiftCount` = 0. `editableUntil` là 00:00 thứ Hai của `weekStart` "
                + "theo giờ Việt Nam, trả dạng UTC.",
            parameters = {@Parameter(name = "weekStart", in = ParameterIn.PATH, example = "2026-11-23",
                    description = "Thứ Hai đầu tuần, yyyy-MM-dd, năm 2000–2100")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-11` `weekStart` không phải thứ Hai hoặc năm ngoài 2000–2100 · "
            + "`weekStart` sai định dạng (`MALFORMED_REQUEST`)")
    @ApiErrorDoc(status = 403)
    @GetMapping("/me/shift-registrations/{weekStart}")
    @PreAuthorize(ShiftAssignmentController.STAFF)
    public ApiResponse<ShiftRegistrationResponse> getMine(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ApiResponse.ok(registrations.getMyShiftRegistration(weekStart), "Đăng ký ca của tôi trong tuần");
    }
}
