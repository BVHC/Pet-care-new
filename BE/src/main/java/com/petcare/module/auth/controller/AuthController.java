package com.petcare.module.auth.controller;

import com.petcare.module.auth.dto.ForgotPasswordRequest;
import com.petcare.module.auth.dto.ForgotPasswordResponse;
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.LogoutResponse;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.RegisterResponse;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.ResendOtpResponse;
import com.petcare.module.auth.dto.ResetPasswordRequest;
import com.petcare.module.auth.dto.ResetPasswordResponse;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.dto.VerifyOtpResponse;
import com.petcare.module.auth.mapper.AuthMapper;
import com.petcare.module.auth.service.AuthService;
import com.petcare.module.auth.service.RegistrationOutcome;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api/auth-v1.md C1-C7 (sửa 2026-09-13 — email thay phone, RULE-01-10).
 * Register/VerifyOtp/ResendOtp/Login/Refresh permitAll; Logout bắt buộc
 * Bearer access token hợp lệ (SecurityConfig — RULE-01-06).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final NotificationService notificationService;
    private final AuthMapper authMapper;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegistrationOutcome outcome = authService.registerAccount(request);
        dispatchNotification(outcome, request.email());
        RegisterResponse response = authMapper.toRegisterResponse(outcome.account());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<VerifyOtpResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        var account = authService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.ok(authMapper.toVerifyOtpResponse(account)));
    }

    @PostMapping("/otp/resend")
    public ResponseEntity<ApiResponse<ResendOtpResponse>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        RegistrationOutcome outcome = authService.resendOtp(request);
        dispatchNotification(outcome, request.email());
        ResendOtpResponse response = authMapper.toResendOtpResponse(outcome.account());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                              HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody(required = false) LogoutRequest request) {
        String rawAccessToken = authorizationHeader.replaceFirst("(?i)^Bearer ", "");
        LogoutResponse response = authService.logout(rawAccessToken, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                                                       HttpServletRequest httpRequest) {
        RefreshTokenResponse response = authService.refresh(request, httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * C8 ForgotPassword — luôn 200 kể cả email không tồn tại, để không lộ
     * email nào đã đăng ký (xử lý ở AuthServiceImpl.forgotPassword).
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<ForgotPasswordResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request)
                .ifPresent(outcome -> dispatchNotification(outcome, request.email()));
        return ResponseEntity.ok(ApiResponse.ok(new ForgotPasswordResponse(true)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(new ResetPasswordResponse(true)));
    }

    /**
     * Gọi dispatch SAU KHI authService.registerAccount()/resendOtp() đã trả
     * về (transaction enqueue đã commit) — đúng lời gọi ngoài qua Spring
     * proxy như javadoc NotificationService yêu cầu.
     */
    private void dispatchNotification(RegistrationOutcome outcome, String email) {
        notificationService.dispatch(outcome.notificationTaskId(), email);
    }
}
