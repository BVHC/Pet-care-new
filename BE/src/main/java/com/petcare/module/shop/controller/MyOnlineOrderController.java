package com.petcare.module.shop.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.OnlineOrderSummaryResponse;
import com.petcare.module.shop.dto.ReasonRequest;
import com.petcare.module.shop.service.MyOnlineOrderService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Đơn online của tôi, shop-v1 (tag My online orders; UC64, BR-BH-09, BR-BH-10; docs/adr/0145). Chỉ CUSTOMER, chỉ đơn của mình. */
@RestController
@RequestMapping("/api/me/online-orders")
public class MyOnlineOrderController {

    private static final String CUSTOMER = "hasRole('CUSTOMER')";
    private static final String TRANSITION = "`INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14)";

    private final MyOnlineOrderService orders;

    public MyOnlineOrderController(MyOnlineOrderService orders) {
        this.orders = orders;
    }

    @Operation(operationId = "listMyOnlineOrders", tags = "My online orders", summary = "Danh sách đơn online của tôi",
            description = "UC64 · BR-BH-10. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —. Mới nhất trước (`createdAt` giảm dần, trùng thì `orderId` giảm dần).",
            parameters = {@Parameter(name = "status", in = ParameterIn.QUERY, example = "PENDING_PAYMENT"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(CUSTOMER)
    public ApiResponse<PageResponse<OnlineOrderSummaryResponse>> listMyOnlineOrders(
            @RequestParam(required = false) OnlineOrderStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orders.list(status, page, size), "Danh sách đơn online của tôi");
    }

    @Operation(operationId = "getMyOnlineOrder", tags = "My online orders", summary = "Chi tiết đơn online của tôi",
            description = "UC64 · BR-BH-10. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "onlineOrderId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy đơn — cả khi đơn thuộc người khác (ADR-0006)")
    @GetMapping("/{onlineOrderId}")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<OnlineOrderResponse> getMyOnlineOrder(@PathVariable Long onlineOrderId) {
        return ApiResponse.ok(orders.get(onlineOrderId));
    }

    @Operation(operationId = "cancelMyOnlineOrder", tags = "My online orders", summary = "Hủy đơn online",
            description = "UC64 · BR-BH-09, 10, BR-TG-12. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: PENDING_PAYMENT → CANCELLED (Đơn hàng online#3). Chỉ hủy được khi chưa thanh toán: nhả giữ hàng, hủy giao dịch `PENDING`, Order → `CANCELLED`. Đơn đã thanh toán (#13: hoàn hàng về lô gốc và sinh yêu cầu hoàn tiền, returns-v1, ST21) chưa làm: trả 409 tới task 138.",
            parameters = {@Parameter(name = "onlineOrderId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "Thiếu lý do hoặc quá 300 ký tự")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION
            + " · đơn đã thanh toán (`PAID` / `PROCESSING`, Đơn hàng online#13) chưa hủy được, chờ task hoàn tiền")
    @PostMapping("/{onlineOrderId}/cancel")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<OnlineOrderResponse> cancelMyOnlineOrder(@PathVariable Long onlineOrderId,
            @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(orders.cancel(onlineOrderId, request.reason()), "Đã hủy đơn online");
    }

    @Operation(operationId = "confirmOnlineOrderReceived", tags = "My online orders", summary = "Xác nhận đã nhận hàng",
            description = "UC64 · BR-BH-10. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: DELIVERED → COMPLETED (Đơn hàng online#11).",
            parameters = {@Parameter(name = "onlineOrderId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/confirm-received")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<OnlineOrderResponse> confirmOnlineOrderReceived(@PathVariable Long onlineOrderId) {
        return ApiResponse.ok(orders.confirmReceived(onlineOrderId), "Đã xác nhận nhận hàng");
    }
}
