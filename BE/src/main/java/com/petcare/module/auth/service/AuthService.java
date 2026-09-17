package com.petcare.module.auth.service;

import com.petcare.module.auth.dto.ForgotPasswordRequest;
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.LogoutResponse;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.ResetPasswordRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Account;

import java.util.Optional;
import java.util.UUID;

public interface AuthService {

    RegistrationOutcome registerAccount(RegisterRequest request);

    Account verifyOtp(VerifyOtpRequest request);

    RegistrationOutcome resendOtp(ResendOtpRequest request);

    LoginResponse login(LoginRequest request, String userAgent, String ipAddress);

    LogoutResponse logout(String rawAccessToken, LogoutRequest request);

    RefreshTokenResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress);

    /**
     * Gửi OTP đặt lại mật khẩu. Trả {@link Optional#empty()} khi email không
     * tồn tại / không đủ điều kiện — controller vẫn trả 200 để không lộ email
     * nào đã đăng ký (account enumeration).
     */
    Optional<RegistrationOutcome> forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    /**
     * Tra userId theo email của Account đang ACTIVE. Thuần đọc, mở ra cho module pet
     * dùng khi mời Caregiver (spec D-02) — email thuộc bảng accounts (auth module) nên
     * module khác không được đọc trực tiếp (01-package-structure.md).
     */
    Optional<UUID> findUserIdByActiveAccountEmail(String email);
}
