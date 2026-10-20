package com.petcare.module.appointment.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.appointment.dto.LiftRestrictionRequest;
import com.petcare.module.appointment.dto.RestrictionStatusResponse;
import com.petcare.module.appointment.service.BookingRestrictionStatusService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Hạn chế đặt online — appointment-v1 #7 xem của tôi (A02), #8 xem của một khách và #9 gỡ sớm (A05, A06; BR-LH-09,
 * docs/adr/0050). Body sai là 400 trước {@code @PreAuthorize}; nhân viên xem / gỡ toàn chuỗi; mọi kiểm tra còn lại ở
 * service.
 */
@RestController
public class BookingRestrictionController {

    /** #7: chỉ khách đang đăng nhập (appointment-v1 mục B). */
    static final String CUSTOMER_ONLY = "hasRole('CUSTOMER')";

    /** #8, #9: lễ tân hoặc BRANCH_MANAGER (BR-LH-09 "Lễ tân hoặc BRANCH_MANAGER được gỡ hạn chế sớm"). */
    static final String RECEPTIONIST_OR_BRANCH_MANAGER = "hasAnyRole('RECEPTIONIST','BRANCH_MANAGER')";

    private final BookingRestrictionStatusService service;

    public BookingRestrictionController(BookingRestrictionStatusService service) {
        this.service = service;
    }

    /** appointment-v1 #7. */
    @Operation(operationId = "getMyBookingRestriction", tags = "Booking restrictions",
            summary = "Tình trạng hạn chế đặt online của tôi",
            description = "UC39, UC58 · BR-LH-09. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng thái: —. FE "
                + "hiển thị lý do và ngày hết hạn chế. Không bị hạn chế (chưa từng, đã hết hạn, đã gỡ) → restricted = "
                + "false, restriction = null. Không chặn khi hồ sơ còn chờ quyết định liên kết (docs/adr/0050).")
    @ApiErrorDoc(status = 400, description = "BR-TK-17")
    @ApiErrorDoc(status = 403)
    @GetMapping("/api/me/booking-restriction")
    @PreAuthorize(CUSTOMER_ONLY)
    public ApiResponse<RestrictionStatusResponse> getMyBookingRestriction() {
        return ApiResponse.ok(service.getMyStatus());
    }

    /** appointment-v1 #8. */
    @Operation(operationId = "getCustomerBookingRestriction", tags = "Booking restrictions",
            summary = "Tình trạng hạn chế đặt online của khách",
            description = "UC39 · BR-LH-09. Actor: A05, A06. Quyền: Nhân viên. Chuyển trạng thái: —. Toàn chuỗi: hạn "
                + "chế gắn với khách, nhân viên chi nhánh nào cũng xem được. Không bị hạn chế → restricted = false, "
                + "restriction = null (docs/adr/0050).",
            parameters = @Parameter(name = "customerId", in = ParameterIn.PATH, example = "88"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò")
    @ApiErrorDoc(status = 404, description = "Khách hàng không tồn tại")
    @GetMapping("/api/customers/{customerId}/booking-restriction")
    @PreAuthorize(RECEPTIONIST_OR_BRANCH_MANAGER)
    public ApiResponse<RestrictionStatusResponse> getCustomerBookingRestriction(@PathVariable Long customerId) {
        return ApiResponse.ok(service.getCustomerStatus(customerId));
    }

    /** appointment-v1 #9. Thành công → restricted = false. */
    @Operation(operationId = "liftBookingRestriction", tags = "Booking restrictions",
            summary = "Gỡ hạn chế sớm",
            description = "UC39 · BR-LH-09, BR-QT-15. Actor: A05, A06. Quyền: Lễ tân, BRANCH_MANAGER. Chuyển trạng "
                + "thái: —. Toàn chuỗi. Ghi audit BOOKING_RESTRICTION_LIFTED; không gửi thông báo cho khách. Thành "
                + "công → restricted = false, restriction = null. Lần đánh giá vi phạm sau vẫn đếm từ starts_at của "
                + "lần hạn chế vừa gỡ, nên vi phạm cũ không bị tính lại (docs/adr/0045, 0050).",
            parameters = @Parameter(name = "customerId", in = ParameterIn.PATH, example = "88"))
    @ApiErrorDoc(status = 400, description = "Thiếu lý do / lý do quá 300 ký tự (`VALIDATION_FAILED`); body sai "
            + "(`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò")
    @ApiErrorDoc(status = 404, description = "Khách hàng không tồn tại")
    @ApiErrorDoc(status = 409, description = "Khách không đang bị hạn chế (chưa từng, đã hết hạn hoặc đã gỡ)")
    @PostMapping("/api/customers/{customerId}/booking-restriction/lift")
    @PreAuthorize(RECEPTIONIST_OR_BRANCH_MANAGER)
    public ApiResponse<RestrictionStatusResponse> liftBookingRestriction(@PathVariable Long customerId,
            @Valid @RequestBody LiftRestrictionRequest request) {
        return ApiResponse.ok(service.liftRestriction(customerId, request.reason()), "Đã gỡ hạn chế đặt online");
    }
}
