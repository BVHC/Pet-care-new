package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.RegisterAccountRequest;
import com.petcare.module.identity.dto.RegistrationResponse;
import com.petcare.module.identity.dto.ResendRegistrationOtpRequest;
import com.petcare.module.identity.dto.VerificationResponse;
import com.petcare.module.identity.dto.VerifyAccountRequest;
import com.petcare.module.identity.service.RegistrationService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/**
 * {@code /api/auth/**} của identity-v1. Path public, luôn xử lý như chưa đăng nhập (docs/adr/0005): không đọc
 * người đang đăng nhập, không {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrations;

    public AuthController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegistrationResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        return ApiResponse.created(registrations.registerAccount(request),
                "Đăng ký thành công, vui lòng kiểm tra email để lấy mã OTP");
    }

    @PostMapping("/register/verify")
    public ApiResponse<VerificationResponse> verify(@Valid @RequestBody VerifyAccountRequest request) {
        return ApiResponse.ok(registrations.verifyAccount(request), "Xác thực tài khoản thành công");
    }

    @PostMapping("/register/resend-otp")
    public ApiResponse<OtpSentResponse> resendRegistrationOtp(
            @Valid @RequestBody ResendRegistrationOtpRequest request) {
        return ApiResponse.ok(registrations.resendRegistrationOtp(request),
                "Đã gửi lại mã OTP, vui lòng kiểm tra email");
    }
}
