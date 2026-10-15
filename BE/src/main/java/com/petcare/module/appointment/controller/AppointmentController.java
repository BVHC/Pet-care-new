package com.petcare.module.appointment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.BookAppointmentRequest;
import com.petcare.module.appointment.service.AppointmentBookingService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Lịch hẹn — appointment-v1 #2 (UC39, A02 + A06; docs/adr/0043). Body sai là 400 trước {@code @PreAuthorize}; phạm vi
 * (lễ tân: chi nhánh của mình; khách: thú của mình) và mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    /** Hợp đồng chỉ cho A02 (khách) và A06 (lễ tân) đặt lịch (appointment-v1 mục B). */
    static final String CUSTOMER_OR_RECEPTIONIST = "hasAnyRole('CUSTOMER','RECEPTIONIST')";

    private final AppointmentBookingService booking;

    public AppointmentController(AppointmentBookingService booking) {
        this.booking = booking;
    }

    /** appointment-v1 #2. Khách → kênh ONLINE, lễ tân → COUNTER; khách của lịch là chủ hiện tại của thú (A2). */
    @Operation(operationId = "bookAppointment", tags = "Appointments",
            summary = "Đặt lịch hẹn",
            description = "UC39 · BR-LH-01…05, 09, BR-KH-05, BR-TK-19. Actor: A02, A06. Quyền: Khách: thú của mình · "
                + "lễ tân: chi nhánh mình. Chuyển trạng thái: — → BOOKED (Lịch hẹn#1); nhóm MEDICAL → Care Task#6. "
                + "Khách → channel ONLINE, lễ tân → COUNTER (A2). Nhóm MEDICAL: hủy nhắc tái khám đang chờ của thú "
                + "(BR-TB-06); nối Care Task#6 khi visit cài FollowUpApi (docs/adr/0043).")
    @ApiErrorDoc(status = 400, description = "`BR-LH-01` dịch vụ không đặt được / đang tắt tại chi nhánh, chi nhánh "
            + "chưa ACTIVE · `BR-LH-02` khung không hợp lệ · `BR-LH-03` khung đã đủ quota · `BR-LH-04` ngoài khoảng 24 "
            + "giờ – 30 ngày [CFG] (khách) hoặc khung đã bắt đầu (lễ tân) · `BR-LH-05` thú đã có 2 lịch BOOKED, trùng "
            + "nhóm trong ngày hoặc trùng khung · `BR-LH-09` khách đang bị hạn chế đặt online · `BR-KH-05` thú đã mất · "
            + "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403, description = "Lễ tân đặt lịch cho chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Thú cưng (kể cả thú của khách khác), dịch vụ hoặc chi nhánh không tồn "
            + "tại (khách: cả chi nhánh chưa hoạt động)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<AppointmentResponse> bookAppointment(@Valid @RequestBody BookAppointmentRequest request) {
        return ApiResponse.created(booking.bookAppointment(request), "Đã đặt lịch hẹn");
    }
}
