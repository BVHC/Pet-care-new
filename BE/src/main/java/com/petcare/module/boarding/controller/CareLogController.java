package com.petcare.module.boarding.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.boarding.dto.CareLogAddendumRequest;
import com.petcare.module.boarding.dto.CareLogAddendumResponse;
import com.petcare.module.boarding.dto.CareLogRequest;
import com.petcare.module.boarding.dto.CareLogResponse;
import com.petcare.module.boarding.dto.UpdateCareLogRequest;
import com.petcare.module.boarding.service.CareLogService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Nhật ký chăm sóc lưu trú (UC57, UC60, boarding-v1 #19–22). Không chứa logic: mọi rule ở service. */
@RestController
@RequestMapping("/api")
public class CareLogController {

    static final String CARE_STAFF = "hasAnyRole('VET', 'CARETAKER')";

    private final CareLogService careLogs;

    public CareLogController(CareLogService careLogs) {
        this.careLogs = careLogs;
    }

    /** boarding-v1 #19. */
    @Operation(operationId = "listCareLogs", tags = "Care logs",
            summary = "Xem nhật ký chăm sóc",
            description = "UC57, UC60 · BR-LT-11. Actor: A02, A05–A08. Quyền: Khách: thú của mình · nhân viên chi "
                + "nhánh. Chuyển trạng thái: —. Khách xem ngay khi được ghi.",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/boarding-bookings/{bookingId}/care-logs")
    @PreAuthorize(BoardingBookingController.ANY_BOARDING_VIEWER)
    public ApiResponse<List<CareLogResponse>> list(@PathVariable Long bookingId) {
        return ApiResponse.ok(careLogs.list(bookingId));
    }

    /** boarding-v1 #20 (Đặt chỗ#8 nếu bất thường). */
    @Operation(operationId = "createCareLog", tags = "Care logs",
            summary = "Ghi nhật ký chăm sóc",
            description = "UC57 · BR-LT-11, 12. Actor: A07, A08. Quyền: CARETAKER, VET chi nhánh. "
                + "Chuyển trạng thái: CHECKED_IN / OVERDUE giữ nguyên (Đặt chỗ#8 nếu bất thường). Bất thường: "
                + "thông báo CARE_LOG_ABNORMAL cho khách và lễ tân.",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "Quá 5 ảnh [CFG]")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Thú không đang lưu trú")
    @PostMapping("/boarding-bookings/{bookingId}/care-logs")
    @PreAuthorize(CARE_STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CareLogResponse> create(@PathVariable Long bookingId,
            @Valid @RequestBody CareLogRequest request) {
        return ApiResponse.created(careLogs.create(bookingId, request), "Đã ghi nhật ký chăm sóc");
    }

    /** boarding-v1 #21. */
    @Operation(operationId = "updateCareLog", tags = "Care logs",
            summary = "Sửa nhật ký trong 1 giờ",
            description = "UC57 · BR-LT-11. Actor: A07, A08. Quyền: Người ghi, trong 1 giờ [CFG]. "
                + "Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "careLogId", in = ParameterIn.PATH, example = "31")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`BR-LT-11` đã quá thời hạn sửa — dùng bản bổ sung")
    @PatchMapping("/care-logs/{careLogId}")
    @PreAuthorize(CARE_STAFF)
    public ApiResponse<CareLogResponse> update(@PathVariable Long careLogId,
            @Valid @RequestBody UpdateCareLogRequest request) {
        return ApiResponse.ok(careLogs.update(careLogId, request), "Đã sửa nhật ký chăm sóc");
    }

    /** boarding-v1 #22. */
    @Operation(operationId = "addCareLogAddendum", tags = "Care logs",
            summary = "Thêm bản bổ sung nhật ký",
            description = "UC57 · BR-LT-11. Actor: A07, A08. Quyền: CARETAKER, VET chi nhánh (A4). "
                + "Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "careLogId", in = ParameterIn.PATH, example = "31")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/care-logs/{careLogId}/addenda")
    @PreAuthorize(CARE_STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CareLogAddendumResponse> addAddendum(@PathVariable Long careLogId,
            @Valid @RequestBody CareLogAddendumRequest request) {
        return ApiResponse.created(careLogs.addAddendum(careLogId, request), "Đã thêm bản bổ sung nhật ký");
    }
}
