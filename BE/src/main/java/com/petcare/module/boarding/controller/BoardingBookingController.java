package com.petcare.module.boarding.controller;

import java.time.LocalDate;

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

import com.petcare.module.boarding.dto.BoardingAvailabilityResponse;
import com.petcare.module.boarding.dto.BookingFilter;
import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.dto.BookingResultResponse;
import com.petcare.module.boarding.dto.CancelBookingRequest;
import com.petcare.module.boarding.dto.CheckInReadinessResponse;
import com.petcare.module.boarding.dto.CheckInRequest;
import com.petcare.module.boarding.dto.CreateBookingRequest;
import com.petcare.module.boarding.dto.ExtendBookingRequest;
import com.petcare.module.boarding.service.BoardingAvailabilityService;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.service.BoardingBookingService;
import com.petcare.module.boarding.service.BoardingChangeService;
import com.petcare.module.boarding.service.BoardingCheckInService;
import com.petcare.module.boarding.service.BoardingViewService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Còn chỗ, đặt chỗ, xem, gia hạn, hủy (UC58, boarding-v1 #7–12). Không chứa logic: mọi rule ở service. */
@RestController
@RequestMapping("/api")
public class BoardingBookingController {

    static final String CUSTOMER_OR_RECEPTIONIST = "hasAnyRole('CUSTOMER', 'RECEPTIONIST')";
    static final String RECEPTIONIST_OR_CARETAKER = "hasAnyRole('RECEPTIONIST', 'CARETAKER')";
    static final String ANY_BOARDING_VIEWER =
            "hasAnyRole('CUSTOMER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')";

    private final BoardingAvailabilityService availability;
    private final BoardingBookingService booking;
    private final BoardingViewService views;
    private final BoardingChangeService changes;
    private final BoardingCheckInService stays;

    public BoardingBookingController(BoardingAvailabilityService availability, BoardingBookingService booking,
            BoardingViewService views, BoardingChangeService changes, BoardingCheckInService stays) {
        this.availability = availability;
        this.booking = booking;
        this.views = views;
        this.changes = changes;
        this.stays = stays;
    }

    /** boarding-v1 #7. */
    @Operation(operationId = "getBoardingAvailability", tags = "Availability",
            summary = "Xem còn chỗ theo đêm",
            description = "UC58 · BR-LT-01, 02, 03, 04. Actor: A02, A06. Quyền: Khách đã đăng nhập; lễ tân. "
                + "Chuyển trạng thái: —. A1.",
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.PATH, example = "1"),
                    @Parameter(name = "kennelTypeId", in = ParameterIn.QUERY, example = "5"),
                    @Parameter(name = "checkInDate", in = ParameterIn.QUERY, example = "2026-11-02"),
                    @Parameter(name = "checkOutDate", in = ParameterIn.QUERY, example = "2026-11-05")})
    @ApiErrorDoc(status = 400, description = "`BR-LT-02` quá 30 đêm [CFG] hoặc ngày trả không sau ngày nhận · "
            + "loại chuồng không bật tại chi nhánh")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/branches/{branchId}/boarding-availability")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<BoardingAvailabilityResponse> availability(@PathVariable Long branchId,
            @RequestParam Long kennelTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        return ApiResponse.ok(availability.availability(branchId, kennelTypeId, checkInDate, checkOutDate),
                "Số chỗ còn lại theo đêm");
    }

    /** boarding-v1 #8 (Đặt chỗ#1). */
    @Operation(operationId = "createBoardingBooking", tags = "Bookings",
            summary = "Đặt chỗ lưu trú",
            description = "UC58 · BR-LT-02…05, BR-LH-09, BR-KH-05, BR-TK-19. Actor: A02, A06. "
                + "Quyền: Khách: thú của mình · lễ tân chi nhánh. Chuyển trạng thái: — → BOOKED (Đặt chỗ#1). "
                + "Snapshot giá đêm. Thiếu mũi bắt buộc dự kiến: cảnh báo, không chặn.")
    @ApiErrorDoc(status = 400, description = "`BR-LT-02` thú sai loài / quá cân nặng tối đa / ngoài 1–30 đêm [CFG] / "
            + "chồng ngày với đặt chỗ khác · `BR-LT-03` hết chỗ (message ghi các đêm thiếu) · `BR-LT-04` khách đặt "
            + "ngoài khoảng 1–60 ngày [CFG], ngày nhận / trả là ngày nghỉ · `BR-LH-09` đang bị hạn chế đặt online · "
            + "`BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ chờ liên kết · loại chuồng không bật tại chi nhánh ACTIVE")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/boarding-bookings")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BookingResultResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.created(booking.createBooking(request), "Đã đặt chỗ lưu trú");
    }

    /** boarding-v1 #9. */
    @Operation(operationId = "listBoardingBookings", tags = "Bookings",
            summary = "Danh sách đặt chỗ",
            description = "UC58, UC59, UC60 · BR-LT-07, 11. Actor: A02, A05, A06, A07, A08. "
                + "Quyền: Khách: của mình · nhân viên chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "BOOKED"),
                    @Parameter(name = "petId", in = ParameterIn.QUERY, example = "21"),
                    @Parameter(name = "customerId", in = ParameterIn.QUERY, example = "14"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-11-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-11-30"),
                    @Parameter(name = "inStay", in = ParameterIn.QUERY, example = "true"),
                    @Parameter(name = "missingCareLogToday", in = ParameterIn.QUERY, example = "true"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/boarding-bookings")
    @PreAuthorize(ANY_BOARDING_VIEWER)
    public ApiResponse<PageResponse<BookingResponse>> list(
            @RequestParam(required = false) BoardingBookingStatus status,
            @RequestParam(required = false) Long petId, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean inStay,
            @RequestParam(required = false) Boolean missingCareLogToday,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(views.listBookings(
                new BookingFilter(status, petId, customerId, from, to, inStay, missingCareLogToday), page, size),
                "Danh sách đặt chỗ lưu trú");
    }

    /** boarding-v1 #10. */
    @Operation(operationId = "getBoardingBooking", tags = "Bookings",
            summary = "Chi tiết đặt chỗ",
            description = "UC58, UC60 · —. Actor: A02, A05–A08. Quyền: Khách: của mình · nhân viên chi nhánh. "
                + "Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/boarding-bookings/{bookingId}")
    @PreAuthorize(ANY_BOARDING_VIEWER)
    public ApiResponse<BookingResponse> get(@PathVariable Long bookingId) {
        return ApiResponse.ok(views.getBooking(bookingId), "Chi tiết đặt chỗ lưu trú");
    }

    /** boarding-v1 #11 (Đặt chỗ#2). */
    @Operation(operationId = "extendBoardingBooking", tags = "Bookings",
            summary = "Gia hạn ngày trả",
            description = "UC58 · BR-LT-02, 03, 06. Actor: A02, A06. Quyền: Khách: thú của mình · lễ tân chi nhánh. "
                + "Chuyển trạng thái: BOOKED / CHECKED_IN giữ nguyên (Đặt chỗ#2).",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-LT-06` đã qua ngày trả dự kiến · `BR-LT-03` hết chỗ các đêm thêm · "
            + "`BR-LT-02` vượt 30 đêm · ngày trả mới không sau ngày trả hiện tại (`VALIDATION_FAILED`)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đặt chỗ OVERDUE (không gia hạn) hoặc đã kết thúc")
    @PostMapping("/boarding-bookings/{bookingId}/extend")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<BookingResponse> extend(@PathVariable Long bookingId,
            @Valid @RequestBody ExtendBookingRequest request) {
        return ApiResponse.ok(changes.extendBooking(bookingId, request), "Đã gia hạn đặt chỗ lưu trú");
    }

    /** boarding-v1 #12 (Đặt chỗ#4). */
    @Operation(operationId = "cancelBoardingBooking", tags = "Bookings",
            summary = "Hủy đặt chỗ",
            description = "UC58 · BR-LT-06, BR-LH-09. Actor: A02, A06. Quyền: Khách: thú của mình · lễ tân chi nhánh. "
                + "Chuyển trạng thái: BOOKED → CANCELLED (Đặt chỗ#4). Còn < 24 giờ [CFG] trước ngày nhận → hủy muộn, "
                + "đánh giá hạn chế đặt online (BR-LH-09).",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "Đã tới ngày nhận")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đặt chỗ không còn BOOKED")
    @PostMapping("/boarding-bookings/{bookingId}/cancel")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<BookingResponse> cancel(@PathVariable Long bookingId,
            @Valid @RequestBody(required = false) CancelBookingRequest request) {
        return ApiResponse.ok(changes.cancelBooking(bookingId, request), "Đã hủy đặt chỗ lưu trú");
    }

    /** boarding-v1 #13. */
    @Operation(operationId = "getCheckInReadiness", tags = "Stay",
            summary = "Kiểm tra điều kiện nhận thú",
            description = "UC59 · BR-LT-04, 05, 08. Actor: A06, A08. Quyền: Lễ tân, CARETAKER chi nhánh. "
                + "Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đặt chỗ không còn BOOKED")
    @GetMapping("/boarding-bookings/{bookingId}/check-in-readiness")
    @PreAuthorize(RECEPTIONIST_OR_CARETAKER)
    public ApiResponse<CheckInReadinessResponse> checkInReadiness(@PathVariable Long bookingId) {
        return ApiResponse.ok(stays.readiness(bookingId));
    }

    /** boarding-v1 #14 (Đặt chỗ#3, Chuồng#2). */
    @Operation(operationId = "checkInBoarding", tags = "Stay",
            summary = "Nhận thú",
            description = "UC59 · BR-LT-04, 05, 08, BR-KH-04. Actor: A06, A08. Quyền: Lễ tân, CARETAKER chi nhánh. "
                + "Chuyển trạng thái: BOOKED → CHECKED_IN (Đặt chỗ#3) · Chuồng#2 (→ OCCUPIED). "
                + "Cân nặng ghi vào lịch sử (source INTAKE); nhận sớm được nếu còn chỗ.",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-LT-05` thiếu / quá hạn mũi bắt buộc (lễ tân tiếp nhận walk-in để "
            + "tiêm trước) · `BR-LT-08` chuồng không AVAILABLE hoặc sai loại, thiếu cân nặng · `BR-LT-04` ngoài giờ "
            + "mở cửa · `BR-LT-03` nhận sớm khi hết chỗ")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đặt chỗ không còn BOOKED")
    @PostMapping("/boarding-bookings/{bookingId}/check-in")
    @PreAuthorize(RECEPTIONIST_OR_CARETAKER)
    public ApiResponse<BookingResponse> checkIn(@PathVariable Long bookingId,
            @Valid @RequestBody CheckInRequest request) {
        return ApiResponse.ok(stays.checkIn(bookingId, request), "Đã nhận thú lưu trú");
    }

    /** boarding-v1 #15 (Đặt chỗ#5). */
    @Operation(operationId = "cancelBoardingNoKennel", tags = "Stay",
            summary = "Hủy vì không còn chuồng đúng loại lúc nhận",
            description = "UC59 · BR-LT-08, BR-LH-09. Actor: A06. Quyền: Lễ tân chi nhánh. "
                + "Chuyển trạng thái: BOOKED → CANCELLED (Đặt chỗ#5, phòng khám hủy). Không tính hủy muộn; "
                + "thông báo khách và BRANCH_MANAGER (A3).",
            parameters = {@Parameter(name = "bookingId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "Vẫn còn chuồng AVAILABLE đúng loại")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đặt chỗ không còn BOOKED")
    @PostMapping("/boarding-bookings/{bookingId}/cancel-no-kennel")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ApiResponse<BookingResponse> cancelNoKennel(@PathVariable Long bookingId) {
        return ApiResponse.ok(stays.cancelNoKennel(bookingId), "Đã hủy đặt chỗ vì không còn chuồng");
    }
}
