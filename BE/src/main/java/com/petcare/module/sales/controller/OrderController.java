package com.petcare.module.sales.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.AddOrderLineRequest;
import com.petcare.module.sales.dto.AddOrderLineResult;
import com.petcare.module.sales.dto.CreateRetailOrderRequest;
import com.petcare.module.sales.dto.OrderReasonRequest;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.dto.UpdateOrderLineRequest;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.service.OrderCancellationService;
import com.petcare.module.sales.service.OrderLineService;
import com.petcare.module.sales.service.OrderListService;
import com.petcare.module.sales.service.OrderViewService;
import com.petcare.module.sales.service.RetailOrderService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/orders} của sales-v1: Order lõi (UC66, UC67; #3–6; BR-BH-02, 03; docs/adr/0044) và bán lẻ tại quầy (#1,
 * #2, #7, #8, #10; BR-BH-01, 05; docs/adr/0051). Xem / danh sách: nhân viên chi nhánh (A05–A08); thêm / sửa / xóa dòng:
 * lễ tân, VET, CARETAKER (quyền chi tiết theo loại dòng và chủ dòng nằm ở service); tạo / chốt / hủy bán lẻ: lễ tân;
 * hủy Order khách không thanh toán: BRANCH_MANAGER. Bean Validation của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    static final String BRANCH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')";
    static final String LINE_EDITORS = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";
    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";
    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";

    private final OrderViewService view;
    private final OrderLineService lines;
    private final RetailOrderService retail;
    private final OrderListService list;
    private final OrderCancellationService cancellation;

    public OrderController(OrderViewService view, OrderLineService lines, RetailOrderService retail,
            OrderListService list, OrderCancellationService cancellation) {
        this.view = view;
        this.lines = lines;
        this.retail = retail;
        this.list = list;
        this.cancellation = cancellation;
    }

    /** sales-v1 #1 (Order#2). */
    @Operation(operationId = "createRetailOrder", tags = "Orders",
            summary = "Tạo Order bán lẻ",
            description = "UC66 · BR-BH-01. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: — → OPEN, RETAIL (Order#2).")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping
    @PreAuthorize(RECEPTIONIST)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateRetailOrderRequest request) {
        return ApiResponse.created(retail.createRetailOrder(request), "Đã tạo Order bán lẻ");
    }

    /** sales-v1 #2. */
    @Operation(operationId = "listOrders", tags = "Orders",
            summary = "Danh sách Order",
            description = "UC66, UC70, ST13 · BR-BH-05. Actor: A05, A06, A07, A08. Quyền: Nhân viên chi nhánh của "
                + "Order. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "PENDING"),
                    @Parameter(name = "source", in = ParameterIn.QUERY, example = "RETAIL"),
                    @Parameter(name = "customerId", in = ParameterIn.QUERY, example = "5"),
                    @Parameter(name = "visitId", in = ParameterIn.QUERY, example = "61"),
                    @Parameter(name = "boardingBookingId", in = ParameterIn.QUERY, example = "9"),
                    @Parameter(name = "date", in = ParameterIn.QUERY, example = "2026-10-19"),
                    @Parameter(name = "pendingOverdue", in = ParameterIn.QUERY, example = "true",
                            description = "PENDING quá 1 ngày [CFG] — danh sách ST13 cho quản lý (BR-BH-05)"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(BRANCH_STAFF)
    public ApiResponse<PageResponse<OrderSummaryResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long boardingBookingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Boolean pendingOverdue,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(list.listOrders(status, source, customerId, visitId, boardingBookingId, date,
                pendingOverdue, page, size));
    }

    /** sales-v1 #3. */
    @Operation(operationId = "getOrder", tags = "Orders",
            summary = "Chi tiết Order",
            description = "UC66, UC67, UC70 · BR-BH-02, 06. Actor: A05, A06, A07, A08. Quyền: Nhân viên chi nhánh của "
                + "Order. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{orderId}")
    @PreAuthorize(BRANCH_STAFF)
    public ApiResponse<OrderResponse> get(@PathVariable Long orderId) {
        return ApiResponse.ok(view.getOrder(orderId));
    }

    /** sales-v1 #4. */
    @Operation(operationId = "addOrderLine", tags = "Orders",
            summary = "Thêm dịch vụ / hàng vào Order",
            description = "UC66, UC67 · BR-BH-02, 03, 04, BR-SP-01. Actor: A06, A07, A08. Quyền: Lễ tân: GOODS; VET: "
                + "SERVICE nhóm MEDICAL của Visit mình; CARETAKER: SERVICE nhóm GROOMING của Visit mình. Chuyển trạng "
                + "thái: —. Giá snapshot lúc thêm. Thiếu tồn bán lẻ: cảnh báo, không chặn.",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 400, description = "`BR-SP-01` thuốc kê đơn (dùng kê đơn) · sản phẩm / dịch vụ ngừng kinh "
            + "doanh")
    @ApiErrorDoc(status = 403, description = "`BR-BH-02` vai trò không được thêm loại dòng này")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Order không OPEN (khi PENDING chỉ lễ tân thêm GOODS) / đã PAID, "
            + "CANCELLED")
    @PostMapping("/{orderId}/lines")
    @PreAuthorize(LINE_EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddOrderLineResult> addLine(@PathVariable Long orderId,
            @Valid @RequestBody AddOrderLineRequest request) {
        return ApiResponse.created(lines.addLine(orderId, request), "Đã thêm dòng vào Order");
    }

    /** sales-v1 #5. */
    @Operation(operationId = "updateOrderLine", tags = "Orders",
            summary = "Sửa dòng (giảm số lượng / đổi dịch vụ tự sinh)",
            description = "UC66, UC67 · BR-BH-02, 03, 04, BR-TN-01. Actor: A06, A07, A08. Quyền: Chủ dòng "
                + "(ownerAccountId). Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101"),
                    @Parameter(name = "lineId", in = ParameterIn.PATH, example = "31")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "`BR-BH-02` không phải chủ dòng, hoặc dòng dịch vụ / thuốc đã khóa khi "
            + "PENDING")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Order đã PAID / CANCELLED")
    @PatchMapping("/{orderId}/lines/{lineId}")
    @PreAuthorize(LINE_EDITORS)
    public ApiResponse<OrderResponse> updateLine(@PathVariable Long orderId, @PathVariable Long lineId,
            @Valid @RequestBody UpdateOrderLineRequest request) {
        return ApiResponse.ok(lines.updateLine(orderId, lineId, request), "Đã cập nhật dòng");
    }

    /** sales-v1 #6. */
    @Operation(operationId = "deleteOrderLine", tags = "Orders",
            summary = "Xóa dòng",
            description = "UC66, UC67 · BR-BH-02, BR-KB-04. Actor: A06, A07, A08. Quyền: Chủ dòng (ownerAccountId). "
                + "Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101"),
                    @Parameter(name = "lineId", in = ParameterIn.PATH, example = "31")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-02` dòng VACCINE không xóa trực tiếp (xóa mũi tiêm) · dòng DRUG "
            + "xóa qua đơn thuốc")
    @ApiErrorDoc(status = 403, description = "`BR-BH-02` không phải chủ dòng")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Order đã PAID / CANCELLED")
    @DeleteMapping("/{orderId}/lines/{lineId}")
    @PreAuthorize(LINE_EDITORS)
    public ApiResponse<OrderResponse> deleteLine(@PathVariable Long orderId, @PathVariable Long lineId) {
        return ApiResponse.ok(lines.deleteLine(orderId, lineId), "Đã xóa dòng");
    }

    /** sales-v1 #7 (Order#5). */
    @Operation(operationId = "closeRetailOrder", tags = "Orders",
            summary = "Chốt Order bán lẻ",
            description = "UC66 · BR-BH-01. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: OPEN → PENDING, RETAIL "
                + "(Order#5).",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-01` Order chưa có dòng nào")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Không phải Order RETAIL OPEN")
    @PostMapping("/{orderId}/close")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<OrderResponse> close(@PathVariable Long orderId) {
        return ApiResponse.ok(retail.closeRetailOrder(orderId), "Đã chốt Order, chờ thu tiền");
    }

    /** sales-v1 #8 (Order#7). */
    @Operation(operationId = "cancelRetailOrder", tags = "Orders",
            summary = "Hủy Order bán lẻ chưa chốt",
            description = "UC66 · BR-BH-01. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: OPEN → CANCELLED, RETAIL "
                + "(Order#7).",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Không phải Order RETAIL OPEN")
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<OrderResponse> cancel(@PathVariable Long orderId) {
        return ApiResponse.ok(retail.cancelRetailOrder(orderId), "Đã hủy Order");
    }

    /** sales-v1 #10 (Order#10). */
    @Operation(operationId = "cancelUnpaidOrder", tags = "Orders",
            summary = "Hủy Order khách không thanh toán",
            description = "UC66 · BR-BH-05, BR-BC-02, BR-QT-15. Actor: A05. Quyền: Chỉ BRANCH_MANAGER chi nhánh. "
                + "Chuyển trạng thái: PENDING → CANCELLED (Order#10). cancelType = UNPAID (thất thu). Không đổi kho, "
                + "bệnh án, lịch hẹn, lưu trú. Ghi audit.",
            parameters = {
                    @Parameter(name = "orderId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-05` thiếu lý do · Order BOARDING khi thú còn trong chuồng (xử "
            + "lý theo BR-LT-09)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Order không PENDING")
    @PostMapping("/{orderId}/cancel-unpaid")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<OrderResponse> cancelUnpaid(@PathVariable Long orderId,
            @Valid @RequestBody OrderReasonRequest request) {
        return ApiResponse.ok(cancellation.cancelUnpaidOrder(orderId, request), "Đã hủy Order khách không thanh toán");
    }
}
