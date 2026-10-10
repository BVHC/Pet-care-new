package com.petcare.module.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.StaffProfile;

/**
 * Đọc nhân viên cho {@code StaffDirectoryApi} (docs/adr/0024). Mọi câu trả projection, không trả entity. "Đang hoạt
 * động" = {@code status = ACTIVE} và không bị khóa ({@code is_locked}); khóa tạm do đăng nhập sai ({@code locked_until})
 * không tính. Không có index {@code staff_profiles(branch_id)}: bảng nhân viên nhỏ (ADR-0024).
 */
public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {

    @Query("""
            SELECT new com.petcare.module.identity.repository.StaffRow(
                sp.accountId, sp.fullName, a.role, sp.branchId, a.status, a.locked, a.lastSeenAt)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE sp.accountId = :accountId
            """)
    Optional<StaffRow> findStaffRow(@Param("accountId") Long accountId);

    /** Nhân viên đang hoạt động của chi nhánh theo chức vụ (BR-TN-05); thứ tự online do service sắp. */
    @Query("""
            SELECT new com.petcare.module.identity.repository.StaffRow(
                sp.accountId, sp.fullName, a.role, sp.branchId, a.status, a.locked, a.lastSeenAt)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE sp.branchId = :branchId AND a.role = :role
              AND a.status = com.petcare.module.identity.entity.AccountStatus.ACTIVE AND a.locked = false
            ORDER BY sp.fullName, sp.accountId
            """)
    List<StaffRow> findActiveStaffRows(@Param("branchId") Long branchId, @Param("role") Role role);

    /**
     * BR-QT-04, điều kiện Chi nhánh#2: BRANCH_MANAGER {@code ACTIVE} gắn với chi nhánh, <b>tính cả người đang bị khóa</b>
     * — rule cho phép quản lý cuối cùng bị khóa mà chi nhánh vẫn hoạt động, nên khóa độc lập với "ACTIVE" (docs/adr/0024).
     * Đọc không khóa; luồng làm giảm số quản lý (T14) phải khóa dòng {@code branches} trước (nợ D014).
     */
    @Query("""
            SELECT COUNT(sp)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE sp.branchId = :branchId
              AND a.role = com.petcare.module.identity.api.Role.BRANCH_MANAGER
              AND a.status = com.petcare.module.identity.entity.AccountStatus.ACTIVE
            """)
    long countActiveBranchManagers(@Param("branchId") Long branchId);

    /** BR-TK-20, identity-v1 #37: VET {@code ACTIVE}, không bị khóa. Không lọc chi nhánh {@code ACTIVE} (content lọc). */
    @Query("""
            SELECT new com.petcare.module.identity.repository.PublicVetRow(
                sp.accountId, sp.fullName, sp.avatarUrl, sp.specialty, sp.bio, sp.branchId)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE a.role = com.petcare.module.identity.api.Role.VET
              AND a.status = com.petcare.module.identity.entity.AccountStatus.ACTIVE AND a.locked = false
            ORDER BY sp.fullName, sp.accountId
            """)
    List<PublicVetRow> findPublicVets();
}
