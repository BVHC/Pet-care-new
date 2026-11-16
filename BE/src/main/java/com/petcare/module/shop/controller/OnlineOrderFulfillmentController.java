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
import com.petcare.module.shop.dto.ShipRequest;
import com.petcare.module.shop.service.OnlineOrderFulfillmentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Xử lý và giao đơn online, shop-v1 (tag Online order fulfillment; UC65, BR-BH-11; docs/adr/0146). Lễ tân của chi nhánh
 * xử lý làm mọi chuyển trạng thái; quản lý chi nhánh chỉ xem. {@code cancelFailedOnlineOrder} (#10) chờ task 138.
 */
@RestController
@RequestMapping("/api/online-orders")
public class OnlineOrderFulfillmentController {

    private static final String VIEW = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST')";
    private static final String ACT = "hasRole('RECEPTIONIST')";
    private static final String TRANSITION = "`INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14)";
    private static final String ID = "onlineOrderId";

    private final OnlineOrderFulfillmentService orders;

    public OnlineOrderFulfillmentController(OnlineOrderFulfillmentService orders) {
        this.orders = orders;
    }

    @Operation(operationId = "listOnlineOrders", tags = "Online order fulfillment", summary = "Danh sách đơn online của chi nhánh",
            description = "UC65 · BR-BH-11. Actor: A05, A06. Quyền: A06 của chi nhánh xử lý; A05 chỉ xem. Chuyển trạng thái: —. Tự lấy chi nhánh của người gọi. `status` vắng = `PAID` + `PROCESSING` + `DELIVERY_FAILED` (việc cần làm). Cũ nhất trước.",
            parameters = {@Parameter(name = "status", in = ParameterIn.QUERY, example = "PAID"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(VIEW)
    public ApiResponse<PageResponse<OnlineOrderSummaryResponse>> listOnlineOrders(
            @RequestParam(required = false) OnlineOrderStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orders.list(status, page, size), "Danh sách đơn online của chi nhánh");
    }

    @Operation(operationId = "getOnlineOrder", tags = "Online order fulfillment", summary = "Chi tiết đơn online (nhân viên)",
            description = "UC65 · BR-BH-11. Actor: A05, A06. Quyền: A06 của chi nhánh xử lý; A05 chỉ xem. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{onlineOrderId}")
    @PreAuthorize(VIEW)
    public ApiResponse<OnlineOrderResponse> getOnlineOrder(@PathVariable Long onlineOrderId) {
        return ApiResponse.ok(orders.get(onlineOrderId));
    }

    @Operation(operationId = "startProcessingOnlineOrder", tags = "Online order fulfillment", summary = "Bắt đầu xử lý",
            description = "UC65 · BR-BH-11. Actor: A06. Quyền: Lễ tân chi nhánh xử lý. Chuyển trạng thái: PAID → PROCESSING (Đơn hàng online#5).",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/start-processing")
    @PreAuthorize(ACT)
    public ApiResponse<OnlineOrderResponse> startProcessingOnlineOrder(@PathVariable Long onlineOrderId) {
        return ApiResponse.ok(orders.startProcessing(onlineOrderId), "Đã bắt đầu xử lý đơn online");
    }

    @Operation(operationId = "shipOnlineOrder", tags = "Online order fulfillment", summary = "Bàn giao cho đơn vị vận chuyển",
            description = "UC65 · BR-BH-11. Actor: A06. Quyền: Lễ tân chi nhánh xử lý. Chuyển trạng thái: PROCESSING → SHIPPED (Đơn hàng online#6).",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-11` thiếu đơn vị vận chuyển hoặc mã vận đơn · `VALIDATION_FAILED` quá 100 ký tự")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/ship")
    @PreAuthorize(ACT)
    public ApiResponse<OnlineOrderResponse> shipOnlineOrder(@PathVariable Long onlineOrderId,
            @Valid @RequestBody ShipRequest request) {
        return ApiResponse.ok(orders.ship(onlineOrderId, request), "Đã bàn giao đơn online cho vận chuyển");
    }

    @Operation(operationId = "markOnlineOrderDelivered", tags = "Online order fulfillment", summary = "Cập nhật đã giao",
            description = "UC65 · BR-BH-11, 12. Actor: A06. Quyền: Lễ tân chi nhánh xử lý. Chuyển trạng thái: SHIPPED → DELIVERED (Đơn hàng online#7). Bắt đầu đếm hạn trả hàng và hạn tự xác nhận (BR-BH-12).",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/mark-delivered")
    @PreAuthorize(ACT)
    public ApiResponse<OnlineOrderResponse> markOnlineOrderDelivered(@PathVariable Long onlineOrderId) {
        return ApiResponse.ok(orders.markDelivered(onlineOrderId), "Đã cập nhật đơn online đã giao");
    }

    @Operation(operationId = "failOnlineOrderDelivery", tags = "Online order fulfillment", summary = "Ghi nhận giao thất bại",
            description = "UC65 · BR-BH-11. Actor: A06. Quyền: Lễ tân chi nhánh xử lý. Chuyển trạng thái: SHIPPED → DELIVERY_FAILED (Đơn hàng online#8).",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`VALIDATION_FAILED` thiếu lý do hoặc quá 300 ký tự (BR-BH-11)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/fail-delivery")
    @PreAuthorize(ACT)
    public ApiResponse<OnlineOrderResponse> failOnlineOrderDelivery(@PathVariable Long onlineOrderId,
            @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(orders.failDelivery(onlineOrderId, request.reason()), "Đã ghi nhận giao thất bại");
    }

    @Operation(operationId = "redeliverOnlineOrder", tags = "Online order fulfillment", summary = "Giao lại",
            description = "UC65 · BR-BH-11. Actor: A06. Quyền: Lễ tân chi nhánh xử lý. Chuyển trạng thái: DELIVERY_FAILED → SHIPPED (Đơn hàng online#9).",
            parameters = {@Parameter(name = ID, in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-11` quá số lần giao lại tối đa [CFG] · thiếu đơn vị vận chuyển hoặc mã vận đơn · `VALIDATION_FAILED` quá 100 ký tự")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = TRANSITION)
    @PostMapping("/{onlineOrderId}/redeliver")
    @PreAuthorize(ACT)
    public ApiResponse<OnlineOrderResponse> redeliverOnlineOrder(@PathVariable Long onlineOrderId,
            @Valid @RequestBody ShipRequest request) {
        return ApiResponse.ok(orders.redeliver(onlineOrderId, request), "Đã giao lại đơn online");
    }
}
