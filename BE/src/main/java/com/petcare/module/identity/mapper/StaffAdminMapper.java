package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.service.OtpService.IssuedOtp;

/**
 * {@code StaffResponse} của quản trị nhân viên (identity-v1 #15–#21, #24; docs/adr/0030), và {@code OtpSentResponse} của
 * sửa email (#23, docs/adr/0034). Một đường map duy nhất từ
 * projection {@link StaffDetailRow}; #16 đọc lại projection sau khi ghi, nên response tạo ≡ {@code GET /staff/{id}}.
 * Mọi trường đích khai báo tường minh và {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên
 * dịch. {@code online} do service tính (BR-TN-06).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StaffAdminMapper {

    @Mapping(target = "accountId", source = "row.accountId")
    @Mapping(target = "email", source = "row.email")
    @Mapping(target = "phone", source = "row.phone")
    @Mapping(target = "fullName", source = "row.fullName")
    @Mapping(target = "role", source = "row.role")
    @Mapping(target = "status", source = "row.status")
    @Mapping(target = "isLocked", source = "row.locked")
    @Mapping(target = "branchId", source = "row.branchId")
    @Mapping(target = "specialty", source = "row.specialty")
    @Mapping(target = "bio", source = "row.bio")
    @Mapping(target = "mustChangePassword", source = "row.mustChangePassword")
    @Mapping(target = "online", source = "online")
    StaffResponse toResponse(StaffDetailRow row, boolean online);

    /**
     * identity-v1 #23 (docs/adr/0034): mốc gửi lại BR-TK-07 do {@code OtpService} tính. {@code maskedEmail} chỉ có ở luồng
     * liên kết hồ sơ (contract) nên {@code null}. Mã gốc trong {@link IssuedOtp} không có trường đích nào.
     */
    @Mapping(target = "resendAvailableAt", source = "resendAvailableAt")
    @Mapping(target = "maskedEmail", ignore = true)
    OtpSentResponse toOtpSentResponse(IssuedOtp otp);
}
