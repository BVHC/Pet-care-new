package com.petcare.module.appointment.controller;

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

import com.petcare.module.appointment.dto.AppointmentListFilter;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.BookAppointmentRequest;
import com.petcare.module.appointment.dto.CancelAppointmentRequest;
import com.petcare.module.appointment.dto.RescheduleAppointmentRequest;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.service.AppointmentBookingService;
import com.petcare.module.appointment.service.AppointmentChangeService;
import com.petcare.module.appointment.service.AppointmentQueryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Lịch hẹn — appointment-v1 #2 đặt lịch (UC39, A02 + A06; docs/adr/0043), #3 danh sách và #4 chi tiết (UC40, UC44;
 * docs/adr/0044), #5 đổi khung và #6 hủy (UC40, A02 + A06; docs/adr/0045). Body / tham số sai là 400 trước {@code @PreAuthorize}; phạm vi (lễ tân / nhân viên: chi nhánh của mình;
 * khách: dữ liệu của mình) và mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    /** Hợp đồng chỉ cho A02 (khách) và A06 (lễ tân) đặt lịch (appointment-v1 mục B). */
    static final String CUSTOMER_OR_RECEPTIONIST = "hasAnyRole('CUSTOMER','RECEPTIONIST')";

    /** Danh sách lịch hẹn: A02, A05, A06 (appointment-v1 mục B). */
    static final String LIST_ROLES = "hasAnyRole('CUSTOMER','BRANCH_MANAGER','RECEPTIONIST')";

    /** Chi tiết lịch hẹn: A02, A05, A06, A07, A08 (appointment-v1 mục B). */
    static final String VIEW_ROLES = "hasAnyRole('CUSTOMER','BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    private final AppointmentBookingService booking;
    private final AppointmentQueryService queries;
    private final AppointmentChangeService changes;

    public AppointmentController(AppointmentBookingService booking, AppointmentQueryService queries,
            AppointmentChangeService changes) {
        this.booking = booking;
        this.queries = queries;
        this.changes = changes;
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

    /** appointment-v1 #3. Tăng dần theo giờ hẹn; bộ lọc AND với phạm vi người xem. */
    @Operation(operationId = "listAppointments", tags = "Appointments",
            summary = "Danh sách lịch hẹn",
            description = "UC40, UC44 · BR-LH-11, BR-TN-02. Actor: A02, A05, A06. Quyền: Khách: lịch của mình · nhân "
                + "viên: chi nhánh mình. Chuyển trạng thái: —. Khách: chỉ lịch có `customerId` là hồ sơ của mình (chủ "
                + "lúc đặt); nhân viên: chi nhánh của mình, `branchId` khác → 403. Mọi bộ lọc là AND; `from > to` → "
                + "trang rỗng; `checkInReady` rỗng / vắng / false = không lọc. `checkInReady=true`: lịch BOOKED, ngày "
                + "hẹn hôm nay, giờ hẹn − `visit.check_in_early_minutes` ≤ now ≤ giờ hẹn + "
                + "`appointment.no_show_after_minutes` [CFG] (BR-TN-02, 03, BR-LH-08). Tăng dần theo `slotDate`, "
                + "`slotStart`, `appointmentId`. `page` < 0 → 0, `size` kẹp 1–100. `canReschedule` / `canCancel` theo "
                + "người xem; BRANCH_MANAGER luôn false (docs/adr/0044).",
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "3"),
                    @Parameter(name = "date", in = ParameterIn.QUERY, example = "2026-10-20"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "BOOKED"),
                    @Parameter(name = "petId", in = ParameterIn.QUERY, example = "12"),
                    @Parameter(name = "customerId", in = ParameterIn.QUERY, example = "88"),
                    @Parameter(name = "checkInReady", in = ParameterIn.QUERY, example = "true",
                            description = "Chỉ lịch BOOKED hôm nay trong cửa sổ check-in (A3)"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò hoặc nhân viên lọc chi nhánh khác")
    @GetMapping
    @PreAuthorize(LIST_ROLES)
    public ApiResponse<PageResponse<AppointmentResponse>> listAppointments(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) Long petId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Boolean checkInReady,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queries.listAppointments(new AppointmentListFilter(branchId, date, from, to, status, petId,
                customerId, checkInReady), page, size));
    }

    /** appointment-v1 #4. Khách: lịch của khách khác cùng 404 với lịch không có. */
    @Operation(operationId = "getAppointment", tags = "Appointments",
            summary = "Chi tiết lịch hẹn",
            description = "UC40 · —. Actor: A02, A05, A06, A07, A08. Quyền: Khách: lịch của mình · nhân viên chi "
                + "nhánh. Chuyển trạng thái: —. `canReschedule` / `canCancel` theo người xem; BRANCH_MANAGER, VET, "
                + "CARETAKER luôn false (docs/adr/0044).",
            parameters = @Parameter(name = "appointmentId", in = ParameterIn.PATH, example = "501"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Nhân viên xem lịch của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập")
    @GetMapping("/{appointmentId}")
    @PreAuthorize(VIEW_ROLES)
    public ApiResponse<AppointmentResponse> getAppointment(@PathVariable Long appointmentId) {
        return ApiResponse.ok(queries.getAppointment(appointmentId));
    }

    /** appointment-v1 #5. Cùng chi nhánh, thú, dịch vụ; khung mới trùng khung hiện tại → 200 không ghi. */
    @Operation(operationId = "rescheduleAppointment", tags = "Appointments",
            summary = "Đổi khung giờ",
            description = "UC40 · BR-LH-02, 03, 04, 05, 06. Actor: A02, A06. Quyền: Khách: lịch của mình · lễ tân chi "
                + "nhánh. Chuyển trạng thái: BOOKED → BOOKED (Lịch hẹn#2). Cùng chi nhánh, cùng thú, cùng dịch vụ; "
                + "muốn đổi khác thì hủy rồi đặt lại. Giữ mã lịch hẹn. Khung mới trùng khung hiện tại → 200, không "
                + "ghi, không tăng số lần đổi. BR-LH-05 trên mọi chi nhánh, không tính chính lịch này. Không kiểm lại "
                + "BR-LH-01, BR-LH-09, BR-KH-05; đổi khung xóa mốc đã nhắc (BR-LH-12) (docs/adr/0045).",
            parameters = @Parameter(name = "appointmentId", in = ParameterIn.PATH, example = "501"))
    @ApiErrorDoc(status = 400, description = "`BR-LH-06` hết lượt đổi (3 [CFG]), khách đổi khi còn < 12 giờ [CFG] "
            + "hoặc lễ tân đổi khi đã tới giờ hẹn · `BR-LH-04`, `BR-LH-02`, `BR-LH-05`, `BR-LH-03` với khung mới; tham "
            + "số / body sai kiểu (`MALFORMED_REQUEST`, `VALIDATION_FAILED`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò hoặc lễ tân đổi lịch của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập")
    @ApiErrorDoc(status = 409, description = "Lịch không còn BOOKED (kiểm ngay sau khi khóa, trước mọi rule)")
    @PostMapping("/{appointmentId}/reschedule")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<AppointmentResponse> rescheduleAppointment(@PathVariable Long appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        return ApiResponse.ok(changes.rescheduleAppointment(appointmentId, request), "Đã đổi khung giờ");
    }

    /** appointment-v1 #6. Body không bắt buộc; nguồn hủy theo vai trò người gọi. */
    @Operation(operationId = "cancelAppointment", tags = "Appointments",
            summary = "Hủy lịch hẹn",
            description = "UC40 · BR-LH-07, 09. Actor: A02, A06. Quyền: Khách: lịch của mình · lễ tân chi nhánh. "
                + "Chuyển trạng thái: BOOKED → CANCELLED (Lịch hẹn#4). Body không bắt buộc. Còn < 12 giờ [CFG] trước "
                + "giờ hẹn → lateCancel = true và đánh giá hạn chế đặt online (BR-LH-09: đếm NO_SHOW + hủy muộn của "
                + "lịch hẹn và đặt chỗ sau mốc max(now − 90 ngày, lần hạn chế gần nhất); đủ 3 [CFG] → hạn chế 30 ngày "
                + "[CFG]). cancelSource = CUSTOMER (khách) / STAFF (lễ tân) (docs/adr/0045).",
            parameters = @Parameter(name = "appointmentId", in = ParameterIn.PATH, example = "501"))
    @ApiErrorDoc(status = 400, description = "`BR-LH-07` đã tới giờ hẹn; tham số / body sai kiểu "
            + "(`MALFORMED_REQUEST`, `VALIDATION_FAILED`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò hoặc lễ tân hủy lịch của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập")
    @ApiErrorDoc(status = 409, description = "Lịch không còn BOOKED, kể cả CHECKED_IN (kiểm ngay sau khi khóa, trước "
            + "mọi rule)")
    @PostMapping("/{appointmentId}/cancel")
    @PreAuthorize(CUSTOMER_OR_RECEPTIONIST)
    public ApiResponse<AppointmentResponse> cancelAppointment(@PathVariable Long appointmentId,
            @Valid @RequestBody(required = false) CancelAppointmentRequest request) {
        return ApiResponse.ok(changes.cancelAppointment(appointmentId, request), "Đã hủy lịch hẹn");
    }
}
