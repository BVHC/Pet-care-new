package com.petcare.module.identity.mapper;

import java.time.Instant;

import org.mapstruct.Mapper;

import com.petcare.module.identity.dto.OtpSentResponse;

/**
 * UC04 — quên mật khẩu (docs/adr/0023). Map từ mốc gửi lại chứ không từ {@code IssuedOtp}: nhánh không gửi mã không có
 * {@code IssuedOtp}, mà body phải giống hệt nhau ở mọi nhánh (BR-TK-10).
 */
@Mapper(componentModel = "spring")
public interface PasswordResetMapper {

    OtpSentResponse toOtpSentResponse(Instant resendAvailableAt, String maskedEmail);
}
