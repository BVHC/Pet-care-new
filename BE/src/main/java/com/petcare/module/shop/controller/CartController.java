package com.petcare.module.shop.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.shop.dto.CartResponse;
import com.petcare.module.shop.dto.SetCartItemRequest;
import com.petcare.module.shop.service.CartService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Giỏ hàng của tôi, shop-v1 (tag Cart; UC61, BR-BH-07; docs/adr/0141). Chỉ CUSTOMER, chỉ giỏ của chính mình. */
@RestController
@RequestMapping("/api/me/cart")
public class CartController {

    private static final String CUSTOMER = "hasRole('CUSTOMER')";

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @Operation(operationId = "getMyCart", tags = "Cart", summary = "Xem giỏ hàng",
            description = "UC61 · BR-BH-07. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —. Sản phẩm ngừng kinh doanh tự bị loại khỏi kết quả.")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(CUSTOMER)
    public ApiResponse<CartResponse> getMyCart() {
        return ApiResponse.ok(cart.get());
    }

    @Operation(operationId = "clearMyCart", tags = "Cart", summary = "Xóa toàn bộ giỏ",
            description = "UC61 · BR-BH-07. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 403)
    @DeleteMapping
    @PreAuthorize(CUSTOMER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMyCart() {
        cart.clear();
    }

    @Operation(operationId = "setCartItem", tags = "Cart", summary = "Thêm hoặc đặt số lượng sản phẩm trong giỏ",
            description = "UC61 · BR-BH-07. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "productId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400, description = "`BR-BH-07` sản phẩm không phải `GOODS` / là thuốc kê đơn / quá 30 dòng · số lượng ngoài 1–99")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy sản phẩm đang kinh doanh")
    @PutMapping("/items/{productId}")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<CartResponse> setCartItem(@PathVariable Long productId,
            @Valid @RequestBody SetCartItemRequest request) {
        return ApiResponse.ok(cart.setItem(productId, request));
    }

    @Operation(operationId = "removeCartItem", tags = "Cart", summary = "Xóa sản phẩm khỏi giỏ",
            description = "UC61 · BR-BH-07. Actor: A02. Quyền: CUSTOMER, chỉ dữ liệu của mình. Chuyển trạng thái: —. Sản phẩm không có trong giỏ vẫn trả giỏ hiện tại.",
            parameters = {@Parameter(name = "productId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @DeleteMapping("/items/{productId}")
    @PreAuthorize(CUSTOMER)
    public ApiResponse<CartResponse> removeCartItem(@PathVariable Long productId) {
        return ApiResponse.ok(cart.removeItem(productId));
    }
}
