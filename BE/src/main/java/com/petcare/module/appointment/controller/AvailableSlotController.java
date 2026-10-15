package com.petcare.module.appointment.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.appointment.dto.SlotResponse;
import com.petcare.module.appointment.service.AvailableSlotService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Khung giờ trống — appointment-v1 #1 (UC39, A02 + A06; docs/adr/0042). Tham số sai kiểu / thiếu là 400
 * {@code MALFORMED_REQUEST} (trước {@code @PreAuthorize}); {@code date} theo ISO {@code yyyy-MM-dd}. Phạm vi chi nhánh
 * của lễ tân và mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/branches")
public class AvailableSlotController {

    /** Hợp đồng chỉ cho A02 (khách) và A06 (lễ tân); A05 không có trong ma trận (appointment-v1 mục B). */
    static final String CUSTOMER_OR_RECEPTIONIST = "hasAnyRole('CUSTOMER','RECEPTIONIST')";

    private final AvailableSlotService slots;

    public AvailableSlotController(AvailableSlotService slots) {
        this.slots = slots;
    }

    /** appointment-v1 #1. Tăng dần theo giờ; ngày nghỉ / ngoài cửa sổ đặt lịch → mảng rỗng. */
    @Operation(operationId = "listAvailableSlots", tags = "Slots",
            summary = "Xem khung giờ trống",
            description = "UC39 · BR-LH-01, 02, 03, 04. Actor: A02, A06. Quyền: Khách; lễ tân chi nhánh. "
                + "Chuyển trạng thái: —. Ngày nghỉ hoặc ngày không có giờ mở cửa trả mảng rỗng (A1). Khách chỉ thấy "
                + "khung bắt đầu từ now + 24 giờ [CFG] và ngày hẹn ≤ hôm nay + 30 ngày [CFG]; lễ tân thấy mọi khung "
                + "chưa bắt đầu (BR-LH-04). Khung AVAILABLE chưa giữ chỗ: đặt lịch kiểm lại (docs/adr/0042).",
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.PATH, example = "3"),
                    @Parameter(name = "serviceId", in = ParameterIn.QUERY, example = "12"),
                    @Parameter(name = "date", in = ParameterIn.QUERY, example = "2026-10-20",
                            description = "Ngày hẹn, theo giờ Việt Nam")})
    @ApiErrorDoc(status = 400, description = "`BR-LH-01` dịch vụ không đặt được tại chi nhánh (ngừng kinh doanh, "
            + "nhóm Lưu trú, đang tắt) hoặc chi nhánh chưa nhận đặt lịch (lễ tân của chi nhánh DRAFT)")
    @ApiErrorDoc(status = 403, description = "Lễ tân xem chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Chi nhánh không tồn tại (khách: cả chi nhánh chưa hoạt động) hoặc dịch "
            + "vụ không tồn tại")
    @GetMapping("/{branchId}/available-slots")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<List<SlotResponse>> listAvailableSlots(
            @PathVariable Long branchId,
            @RequestParam Long serviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(slots.listAvailableSlots(branchId, serviceId, date));
    }
}
