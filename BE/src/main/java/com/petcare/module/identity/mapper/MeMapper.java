package com.petcare.module.identity.mapper;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.dto.MeResponse;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.StaffProfile;

/**
 * {@code GET /api/me}. {@code AccountSummary} dùng lại {@link LoginMapper#toAccountSummary}. Mọi trường đích khai báo
 * tường minh và {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch.
 */
@Mapper(componentModel = "spring", uses = LoginMapper.class, injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MeMapper {

    /** {@code phone} lấy từ {@code accounts.phone} vì {@code staff_profiles} không có cột SĐT. */
    @Mapping(target = "accountId", source = "profile.accountId")
    @Mapping(target = "fullName", source = "profile.fullName")
    @Mapping(target = "avatarUrl", source = "profile.avatarUrl")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "branchId", source = "profile.branchId")
    @Mapping(target = "specialty", source = "profile.specialty")
    @Mapping(target = "bio", source = "profile.bio")
    StaffProfileResponse toStaffProfileResponse(StaffProfile profile, String phone);

    @Mapping(target = "account", source = "account")
    @Mapping(target = "staffProfile", source = "staffProfile")
    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "linkDecisionPending", source = "linkDecisionPending")
    MeResponse toMeResponse(Account account, StaffProfileResponse staffProfile, Long customerId,
                            boolean linkDecisionPending);
}
