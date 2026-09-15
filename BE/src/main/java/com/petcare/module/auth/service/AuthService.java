package com.petcare.module.auth.service;

import com.petcare.module.auth.dto.CreateCustomerRequest;
import com.petcare.module.auth.dto.CreateCustomerResponse;
import com.petcare.module.auth.dto.CreateStaffRequest;
import com.petcare.module.auth.dto.CreateStaffResponse;
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
import com.petcare.platform.security.UserPrincipal;

public interface AuthService {

    RegistrationOutcome registerAccount(RegisterRequest request);

    Account verifyOtp(VerifyOtpRequest request);

    RegistrationOutcome resendOtp(ResendOtpRequest request);

    LoginResponse login(LoginRequest request, String userAgent, String ipAddress);

    LogoutResponse logout(String rawAccessToken, LogoutRequest request);

    RefreshTokenResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress);

    /** CreateStaff — D-04, RULE-01-03/02-01/02-02/02-03/02-05 (docs/api/auth-v1.md C7). */
    CreateStaffResponse createStaff(CreateStaffRequest request, UserPrincipal actor);

    /** Receptionist tạo customer tại quầy — RULE-02-06, ACTIVE ngay không OTP. */
    CreateCustomerResponse createCustomer(CreateCustomerRequest request);
}
