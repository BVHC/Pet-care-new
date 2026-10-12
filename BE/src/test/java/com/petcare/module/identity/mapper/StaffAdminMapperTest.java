package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.service.OtpService.IssuedOtp;

/**
 * {@code StaffResponse} (docs/adr/0030): mỗi trường lấy đúng một nguồn — dòng có giá trị riêng cho từng trường, so cả
 * record nên lấy nhầm nguồn (ví dụ {@code specialty} ↔ {@code bio}, {@code email} ↔ {@code phone}) là đỏ.
 */
class StaffAdminMapperTest {

    private final StaffAdminMapper mapper = Mappers.getMapper(StaffAdminMapper.class);

    @Test
    void everyFieldComesFromItsOwnSource() {
        StaffDetailRow row = new StaffDetailRow(11L, "email@petcare.test", "0912345678", "Họ Tên", Role.VET,
                AccountStatus.DISABLED, true, 22L, "Chuyên môn", "Mô tả", false, Instant.parse("2026-10-11T00:00:00Z"));

        assertThat(mapper.toResponse(row, true)).isEqualTo(new StaffResponse(11L, "email@petcare.test", "0912345678",
                "Họ Tên", Role.VET, AccountStatus.DISABLED, true, 22L, "Chuyên môn", "Mô tả", false, true));
    }

    @Test
    void nullablesStayNullAndBooleansAreNotSwapped() {
        StaffDetailRow row = new StaffDetailRow(12L, "sm@petcare.test", "0900000000", "Quản lý", Role.SUPER_MANAGER,
                AccountStatus.ACTIVE, false, null, null, null, true, null);

        assertThat(mapper.toResponse(row, false)).isEqualTo(new StaffResponse(12L, "sm@petcare.test", "0900000000",
                "Quản lý", Role.SUPER_MANAGER, AccountStatus.ACTIVE, false, null, null, null, true, false));
    }

    /** #23 (docs/adr/0034): mốc gửi lại lấy từ {@code resendAvailableAt}, không phải {@code expiresAt}; không có mã. */
    @Test
    void otpSentResponseTakesResendMarkAndNeverTheCode() {
        Instant expiresAt = Instant.parse("2026-10-12T03:05:00Z");
        Instant resendAt = Instant.parse("2026-10-12T03:01:00Z");

        OtpSentResponse response = mapper.toOtpSentResponse(new IssuedOtp("246810", expiresAt, resendAt, 5));

        assertThat(response).isEqualTo(new OtpSentResponse(resendAt, null));
        assertThat(response.toString()).doesNotContain("246810");
    }
}
