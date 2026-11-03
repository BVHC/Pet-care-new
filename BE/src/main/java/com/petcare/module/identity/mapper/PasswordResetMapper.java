package com.petcare.module.identity.mapper;

import java.time.Instant;

import org.mapstruct.Mapper;

import com.petcare.module.identity.dto.OtpSentResponse;

/**
 * UC04 — quên mật khẩu (docs/adr/0023). Map từ mốc gửi lại chứ không từ {@code IssuedOtp}: nhánh không gửi mã không có
 * {@code IssuedOtp}, mà body phải giống hệt nhau ở mọi nhánh (BR-TK-10). {@code codeLength}: độ dài mã {@code prepare()}
 * vừa sinh, nên luôn có và không phụ thuộc tài khoản; MapStruct ghép theo tên tham số (đổi tên là ra 0 âm thầm, đã có
 * test chặn).
 */
@Mapper(componentModel = "spring")
public interface PasswordResetMapper {

    OtpSentResponse toOtpSentResponse(Instant resendAvailableAt, String maskedEmail, int codeLength);
}
