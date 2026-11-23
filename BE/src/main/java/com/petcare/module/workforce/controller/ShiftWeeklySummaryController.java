package com.petcare.module.workforce.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.ShiftWeeklySummaryResponse;
import com.petcare.module.workforce.service.ShiftWeeklySummaryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Thống kê số ca mỗi nhân viên trong tuần (UC34, workforce-v1 #16; docs/adr/0149). Không chứa logic và không biết mã
 * rule: BR-NS-11 do {@link ShiftWeeklySummaryService} kiểm. Chỉ BRANCH_MANAGER, chỉ chi nhánh của người gọi; dùng chung
 * hằng quyền của {@link ShiftAssignmentController} để các nơi không lệch nhau.
 */
@RestController
@RequestMapping("/api")
public class ShiftWeeklySummaryController {

    private final ShiftWeeklySummaryService summaries;

    public ShiftWeeklySummaryController(ShiftWeeklySummaryService summaries) {
        this.summaries = summaries;
    }

    /** workforce-v1 #16. */
    @Operation(operationId = "listShiftWeeklySummaries", tags = "Shift registrations",
            summary = "Thống kê số ca mỗi nhân viên trong tuần",
            description = "UC34 · BR-NS-12. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. Chuyển trạng thái: —. "
                + "Không phân trang. Mọi nhân viên A06–A08 `ACTIVE` và không bị khóa của chi nhánh người gọi, kể cả "
                + "người chưa có ca nào. Sắp `staffName` tăng dần (trùng tên thì `staffId`). `shiftCount` đếm phân ca "
                + "`SCHEDULED` của tuần, cả `SELF` và `MANAGER`, ở mọi chi nhánh (khớp `GET /me/shift-registrations/"
                + "{weekStart}`); `ON_LEAVE`, `ABSENT`, `CANCELLED` không đếm. `requiredShifts` là [CFG] "
                + "`workforce.weekly_required_shifts` hiện hành; `meetsRequirement` = `shiftCount` ≥ `requiredShifts`. "
                + "Chỉ để quản lý theo dõi, không chặn gì (BR-NS-12).",
            parameters = {@Parameter(name = "weekStart", in = ParameterIn.QUERY, required = true, example = "2026-11-23",
                    description = "Thứ Hai đầu tuần, yyyy-MM-dd, năm 2000–2100")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-11` `weekStart` không phải thứ Hai hoặc năm ngoài 2000–2100 · "
            + "`weekStart` thiếu hoặc sai định dạng (`MALFORMED_REQUEST`)")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED` sai vai trò · `ACCESS_DENIED_SCOPE_MISMATCH` người gọi "
            + "không thuộc chi nhánh nào")
    @GetMapping("/shift-weekly-summaries")
    @PreAuthorize(ShiftAssignmentController.BRANCH_MANAGER)
    public ApiResponse<List<ShiftWeeklySummaryResponse>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ApiResponse.ok(summaries.listWeeklySummaries(weekStart), "Thống kê số ca trong tuần");
    }
}
