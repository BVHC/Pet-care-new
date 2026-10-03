package com.petcare.module.identity.api;

import java.util.List;
import java.util.Optional;

/** Owner: identity (TK, QT) · BE-1. Chỉ đọc thông tin nhân viên. */
public interface StaffDirectoryApi {

    /** {@code active} = status ACTIVE và không bị khóa; {@code online} theo last_seen_at (BR-TN-06). */
    record StaffSummary(Long accountId, String fullName, Role role, Long branchId,
                        boolean active, boolean online) {}

    record PublicVetProfile(Long accountId, String fullName, String avatarUrl, String specialty,
                            String bio, Long branchId) {}

    Optional<StaffSummary> findStaff(Long accountId);

    /** Nhân viên active của chi nhánh theo chức vụ, người online xếp trước (BR-TN-05, 06). */
    List<StaffSummary> findAssignableStaff(Long branchId, Role role);

    /** Số BRANCH_MANAGER đang gắn với chi nhánh (BR-QT-04, điều kiện Chi nhánh#2). */
    int countBranchManagers(Long branchId);

    /** Người nhận thông báo theo chức vụ tại chi nhánh (BRANCH_MANAGER, RECEPTIONIST…). */
    List<Long> findActiveStaffIds(Long branchId, Role role);

    List<Long> findActiveSuperManagerIds();

    /** Hồ sơ VET hiển thị công khai, chỉ tài khoản ACTIVE (BR-TK-20, UC15). */
    List<PublicVetProfile> listPublicVets();

    Optional<String> findEmail(Long accountId);
}
