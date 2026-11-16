package com.petcare.module.shop.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.shop.dto.FulfillmentBranchResponse;
import com.petcare.module.shop.dto.OnlineOrderQuoteResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.PlaceOnlineOrderRequest;
import com.petcare.module.shop.service.OnlineOrderService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Đặt hàng online của tôi, shop-v1 (tags Checkout, My online orders; UC62, BR-BH-08; docs/adr/0144). Chỉ CUSTOMER. */
@RestController
@RequestMapping("/api/me/online-orders")
public class OnlineOrderController {

    private static final String CUSTOMER = "hasRole('CUSTOMER')";

    private final OnlineOrderService orders;

    public OnlineOrderController(OnlineOrderService orders) {
        this.orders = orders;
    }

    @Operation(operationId = "listFulfillmentBranches", tags = "Checkout", summary = "Chi nhánh xử lý được cho giỏ hiện tại",
            description = "UC62 · BR-BH-08. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —. Chỉ chi nhánh `ACTIVE` còn đủ hàng cho mọi dòng của giỏ (đã trừ phần giữ, BR-BH-09).")
    @ApiErrorDoc(status = 403)
    @GetMapping("/fulfillment-branches")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<List<FulfillmentBranchResponse>> listFulfillmentBranches() {
        return ApiResponse.ok(orders.listFulfillmentBranches());
    }

    @Operation(operationId = "quoteOnlineOrder", tags = "Checkout", summary = "Báo giá đơn online (ST09)",
            description = "UC62 · BR-BH-08, BR-SP-08, ST09. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —. Không giữ hàng, không ghi gì; phí chốt thật lúc đặt.",
            parameters = {@Parameter(name = "branchId", in = ParameterIn.QUERY, required = true, example = "3"),
                    @Parameter(name = "addressId", in = ParameterIn.QUERY, required = true, example = "7")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-08` giỏ rỗng · chi nhánh không đủ hàng")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy địa chỉ (hoặc địa chỉ không phải của khách) / chi nhánh")
    @GetMapping("/quote")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<OnlineOrderQuoteResponse> quoteOnlineOrder(@RequestParam Long branchId,
            @RequestParam Long addressId) {
        return ApiResponse.ok(orders.quote(branchId, addressId));
    }

    @Operation(operationId = "placeOnlineOrder", tags = "My online orders", summary = "Đặt hàng online",
            description = "UC62 · BR-BH-08, 09, BR-SP-08, 11. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: — → PENDING_PAYMENT (Đơn hàng online#1); Order#11. Giữ hàng, chốt phí, tạo Order `ONLINE`, dọn giỏ trong một transaction. Bước tiếp: `POST /me/online-orders/{onlineOrderId}/pay` (payment-v1).")
    @ApiErrorDoc(status = 400, description = "`BR-BH-08` giỏ rỗng · chi nhánh không `ACTIVE` / thiếu hàng")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy chi nhánh / địa chỉ (hoặc địa chỉ không phải của khách)")
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hàng vừa bị người khác giữ")
    @PostMapping
    @PreAuthorize(CUSTOMER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OnlineOrderResponse> placeOnlineOrder(@Valid @RequestBody PlaceOnlineOrderRequest request) {
        return ApiResponse.created(orders.place(request), "Đã đặt hàng online");
    }
}
