package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.RegistrationResponse;
import com.petcare.module.identity.dto.VerificationResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.service.OtpService.IssuedOtp;

@Mapper(componentModel = "spring")
public interface RegistrationMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "email", source = "account.email")
    @Mapping(target = "status", source = "account.status")
    @Mapping(target = "otpResendAvailableAt", source = "otp.resendAvailableAt")
    @Mapping(target = "otpCodeLength", expression = "java(otp.code().length())")
    RegistrationResponse toResponse(Account account, IssuedOtp otp);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "status", source = "account.status")
    VerificationResponse toVerificationResponse(Account account, boolean linkDecisionPending);

    @Mapping(target = "maskedEmail", ignore = true)
    @Mapping(target = "codeLength", expression = "java(otp.code().length())")
    OtpSentResponse toOtpSentResponse(IssuedOtp otp);
}
