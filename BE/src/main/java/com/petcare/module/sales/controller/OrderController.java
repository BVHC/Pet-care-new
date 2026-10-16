package com.petcare.module.sales.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.sales.dto.AddOrderLineRequest;
import com.petcare.module.sales.dto.AddOrderLineResult;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.dto.UpdateOrderLineRequest;
import com.petcare.module.sales.service.OrderLineService;
import com.petcare.module.sales.service.OrderViewService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/orders} của sales-v1, phần Order lõi (UC66, UC67; #3–6; BR-BH-02, 03; docs/adr/0044). Xem: nhân viên
 * chi nhánh của Order (A05–A08); thêm / sửa / xóa dòng: lễ tân, VET, CARETAKER (quyền chi tiết theo loại dòng và chủ
 * dòng nằm ở service). Bean Validation của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    static final String BRANCH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')";
    static final String LINE_EDITORS = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";

    private final OrderViewService view;
    private final OrderLineService lines;

    public OrderController(OrderViewService view, OrderLineService lines) {
        this.view = view;
        this.lines = lines;
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
}
