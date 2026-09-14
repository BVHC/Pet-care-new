package com.petcare.module.auth.service;

import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.LogoutResponse;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Account;

public interface AuthService {

    RegistrationOutcome registerAccount(RegisterRequest request);

    Account verifyOtp(VerifyOtpRequest request);

    RegistrationOutcome resendOtp(ResendOtpRequest request);

    LoginResponse login(LoginRequest request, String userAgent, String ipAddress);

    LogoutResponse logout(String rawAccessToken, LogoutRequest request);

    RefreshTokenResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress);
}
