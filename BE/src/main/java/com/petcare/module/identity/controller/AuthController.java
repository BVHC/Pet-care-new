package com.petcare.module.identity.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.ForgotPasswordRequest;
import com.petcare.module.identity.dto.LoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.RegisterAccountRequest;
import com.petcare.module.identity.dto.RegistrationResponse;
import com.petcare.module.identity.dto.ResendRegistrationOtpRequest;
import com.petcare.module.identity.dto.ResetPasswordRequest;
import com.petcare.module.identity.dto.VerificationResponse;
import com.petcare.module.identity.dto.VerifyAccountRequest;
import com.petcare.module.identity.service.LoginService;
import com.petcare.module.identity.service.LogoutService;
import com.petcare.module.identity.service.PasswordResetService;
import com.petcare.module.identity.service.RegistrationService;
import com.petcare.platform.model.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code /api/auth/**} của identity-v1. Mọi path ở đây là public, luôn xử lý như chưa đăng nhập (docs/adr/0005),
 * <b>trừ</b> {@code /logout}: path đó cần đăng nhập (docs/adr/0003 mục 6). Không {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrations;
    private final LoginService logins;
    private final LogoutService logouts;
    private final PasswordResetService passwordResets;

    public AuthController(RegistrationService registrations, LoginService logins, LogoutService logouts,
            PasswordResetService passwordResets) {
        this.registrations = registrations;
        this.logins = logins;
        this.logouts = logouts;
        this.passwordResets = passwordResets;
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

    /** IP client sau proxy đã là {@code getRemoteAddr()} (docs/adr/0002); lưu vào phiên (docs/adr/0003). */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(logins.login(request, httpRequest.getRemoteAddr(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)), "Đăng nhập thành công");
    }

    /**
     * Hủy phiên của token đang dùng (docs/adr/0021). Mọi role đã đăng nhập; miễn chặn BR-TK-17. Body (FE cũ gửi
     * {@code refreshToken}) không được đọc. 204 không có body (docs/api/00-method.md §3.4).
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        logouts.logout();
    }

    /**
     * UC04 — quên mật khẩu (docs/adr/0023). 202 cùng một body ở mọi trường hợp, kể cả email không có tài khoản
     * (BR-TK-10; docs/api/00-method.md §3.4). Gửi lại mã = gọi lại endpoint này.
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OtpSentResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ApiResponse.accepted(passwordResets.requestPasswordReset(request),
                "Nếu email đã đăng ký, mã OTP đã được gửi tới hộp thư");
    }

    /** UC04 — đặt lại mật khẩu bằng OTP (docs/adr/0023). 204 không có body; mọi phiên cũ bị hủy (BR-TK-13). */
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResets.resetPassword(request);
    }
}
