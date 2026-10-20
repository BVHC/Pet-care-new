package com.petcare.module.visit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.CreateVisitRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.service.VisitCheckInService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Tiếp nhận và hàng đợi — visit-v1 #1 tiếp nhận (UC44, UC47, A06; docs/adr/0059). Body sai là 400 trước
 * {@code @PreAuthorize}; chi nhánh lấy từ người đăng nhập, mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/visits")
public class VisitController {

    /** Hợp đồng chỉ cho A06 tiếp nhận (visit-v1 mục B; 01 UC44; 03 #1). */
    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";

    private final VisitCheckInService checkIns;

    public VisitController(VisitCheckInService checkIns) {
        this.checkIns = checkIns;
    }

    /** visit-v1 #1 (Visit#1 + Lịch hẹn#3 + Order#1 trong một transaction). */
    @Operation(operationId = "checkInVisit", tags = "Queue",
            summary = "Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu)",
            description = "UC44, UC47 · BR-TN-01…04, BR-CN-05, BR-LH-01, BR-KH-05. Actor: A06. Quyền: Lễ tân; chi nhánh "
                + "của lễ tân. Chuyển trạng thái: — → WAITING (Visit#1) · Lịch hẹn#3 · Order#1 (OPEN + dòng dịch vụ tự "
                + "sinh) · có dịch vụ Khám → Care Task#6. Trễ quá `visit.late_priority_minutes` (15 phút) [CFG] xếp như "
                + "walk-in. A9, A10, A11, A12. Thứ tự lỗi: 404 → 400 → 409 (docs/adr/0059). Có dịch vụ Khám: Care Task#6 "
                + "chưa nối, nối khi visit cài FollowUpApi. Khách mang nhiều thú thì tạo nhiều Visit.")
    @ApiErrorDoc(status = 400, description = "`BR-TN-01` walk-in thiếu dịch vụ / thú không khớp lịch hẹn / thú đã mất · "
            + "`BR-TN-02` lịch hẹn của chi nhánh khác hoặc ngoài cửa sổ check-in · `BR-CN-01` chi nhánh chưa ACTIVE · "
            + "`BR-CN-05` ngoài giờ mà chi nhánh không có cờ hoặc chưa đánh dấu cấp cứu · `BR-LH-01` walk-in: dịch vụ đã "
            + "ngừng, đang tắt tại chi nhánh hoặc không thuộc MEDICAL / GROOMING; có lịch: dịch vụ đã ngừng kinh doanh")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lịch hẹn không còn BOOKED (đã check-in / NO_SHOW: tiếp nhận như walk-in, "
            + "BR-TN-03)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<VisitResponse> checkInVisit(@Valid @RequestBody CreateVisitRequest request) {
        return ApiResponse.created(checkIns.checkInVisit(request), "Đã tiếp nhận");
    }
}
