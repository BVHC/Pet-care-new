package com.petcare.module.workforce.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.service.ShiftRegistrationService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Đăng ký ca theo tuần (UC35, workforce-v1 #15–17; docs/adr/0138). Không chứa logic và không biết mã rule: BR-NS-11 do
 * {@link ShiftRegistrationService} kiểm. Chỉ nhân viên A06–A08 và chỉ dữ liệu của chính họ; dùng chung hằng quyền
 * {@code STAFF} của {@link ShiftAssignmentController} để hai nơi không lệch nhau. Hiện có #17; #15 (PUT) và #16 (thống
 * kê tuần của quản lý) làm ở các lát sau.
 */
@RestController
@RequestMapping("/api")
public class ShiftRegistrationController {

    private final ShiftRegistrationService registrations;

    public ShiftRegistrationController(ShiftRegistrationService registrations) {
        this.registrations = registrations;
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
