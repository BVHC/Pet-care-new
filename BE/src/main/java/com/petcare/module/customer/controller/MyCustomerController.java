package com.petcare.module.customer.controller;

import java.util.List;

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

import com.petcare.module.customer.dto.AddressRequest;
import com.petcare.module.customer.dto.AddressResponse;
import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.dto.UpdateAddressRequest;
import com.petcare.module.customer.dto.UpdateMyCustomerProfileRequest;
import com.petcare.module.customer.service.AddressBookService;
import com.petcare.module.customer.service.MyCustomerProfileService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/**
 * {@code /api/me/**} của customer-v1 #1–7 (UC06, docs/adr/0028) — hồ sơ khách và sổ địa chỉ của chính khách đang đăng
 * nhập; chỉ CUSTOMER. Không chặn khi hồ sơ còn cờ chờ liên kết (BR-TK-19 không liệt kê hai chức năng này).
 * Bean Validation của body chạy trước {@code @PreAuthorize} (như {@code PATCH /me/staff-profile}): nhân viên gửi body sai
 * hình thức nhận 400, body hợp lệ nhận 403 (docs/adr/0028).
 */
@RestController
@RequestMapping("/api/me")
public class MyCustomerController {

    private final MyCustomerProfileService profiles;
    private final AddressBookService addressBook;

    public MyCustomerController(MyCustomerProfileService profiles, AddressBookService addressBook) {
        this.profiles = profiles;
        this.addressBook = addressBook;
    }

    /** customer-v1 #1. */
    @GetMapping("/customer-profile")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<CustomerProfileResponse> getMyProfile() {
        return ApiResponse.ok(profiles.getMyProfile());
    }

    /** customer-v1 #2 (BR-TK-15, BR-KH-01). */
    @PatchMapping("/customer-profile")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<CustomerProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateMyCustomerProfileRequest request) {
        return ApiResponse.ok(profiles.updateMyProfile(request), "Đã cập nhật hồ sơ");
    }

    /** customer-v1 #3. Mảng thẳng trong {@code data}, mặc định trước. */
    @GetMapping("/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<AddressResponse>> listMyAddresses() {
        return ApiResponse.ok(addressBook.listMyAddresses());
    }

    /** customer-v1 #4 (BR-TK-18). */
    @PostMapping("/addresses")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddressResponse> addAddress(@Valid @RequestBody AddressRequest request) {
        return ApiResponse.created(addressBook.addAddress(request), "Đã thêm địa chỉ");
    }

    /** customer-v1 #5. */
    @PatchMapping("/addresses/{addressId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<AddressResponse> updateAddress(@PathVariable Long addressId,
            @Valid @RequestBody UpdateAddressRequest request) {
        return ApiResponse.ok(addressBook.updateAddress(addressId, request), "Đã cập nhật địa chỉ");
    }

    /** customer-v1 #6 (BR-TK-18). 204 không body. */
    @DeleteMapping("/addresses/{addressId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@PathVariable Long addressId) {
        addressBook.deleteAddress(addressId);
    }

    /** customer-v1 #7. Đã là mặc định thì trả về như cũ, không ghi. */
    @PostMapping("/addresses/{addressId}/set-default")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<AddressResponse> setDefaultAddress(@PathVariable Long addressId) {
        return ApiResponse.ok(addressBook.setDefaultAddress(addressId), "Đã đặt địa chỉ mặc định");
    }
}
