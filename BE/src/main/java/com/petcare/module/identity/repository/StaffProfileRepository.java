package com.petcare.module.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;
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

    /** Như {@link #findStaffRow} cho nhiều tài khoản (docs/adr/0060); PK của hai bảng. Bên gọi không truyền tập rỗng. */
    @Query("""
            SELECT new com.petcare.module.identity.repository.StaffRow(
                sp.accountId, sp.fullName, a.role, sp.branchId, a.status, a.locked, a.lastSeenAt)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE sp.accountId IN :accountIds
            """)
    List<StaffRow> findStaffRows(@Param("accountIds") Collection<Long> accountIds);

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

    /**
     * identity-v1 #16, #17 (docs/adr/0030): một nhân viên theo {@code account_id}. Tài khoản khách không có dòng
     * {@code staff_profiles} nên không bao giờ khớp. Đọc không khóa.
     */
    @Query("""
            SELECT new com.petcare.module.identity.repository.StaffDetailRow(
                sp.accountId, a.email, a.phone, sp.fullName, a.role, a.status, a.locked, sp.branchId,
                sp.specialty, sp.bio, a.mustChangePassword, a.lastSeenAt)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE sp.accountId = :accountId
            """)
    Optional<StaffDetailRow> findStaffDetail(@Param("accountId") Long accountId);

    /**
     * identity-v1 #15 (docs/adr/0030): nhân viên trong phạm vi xem. {@code roles}, {@code statuses} luôn khác rỗng
     * (service tính giao với phạm vi); {@code branchId} null = mọi chi nhánh; {@code pattern} là chuỗi LIKE đã chữ thường
     * và escape bằng {@code !} ({@code %} khi không tìm). Không dùng dấu gạch ngược làm ký tự escape: lexer HQL của
     * Hibernate 6 coi gạch ngược + nháy đơn trong chuỗi là escape sequence. Sắp theo họ tên rồi {@code account_id};
     * trang và tổng là hai câu riêng (READ COMMITTED: {@code totalElements} có thể lệch nếu có ghi xen giữa).
     */
    @Query(value = """
            SELECT new com.petcare.module.identity.repository.StaffDetailRow(
                sp.accountId, a.email, a.phone, sp.fullName, a.role, a.status, a.locked, sp.branchId,
                sp.specialty, sp.bio, a.mustChangePassword, a.lastSeenAt)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE a.role IN :roles AND a.status IN :statuses
              AND (:branchId IS NULL OR sp.branchId = :branchId)
              AND (LOWER(sp.fullName) LIKE :pattern ESCAPE '!' OR LOWER(a.email) LIKE :pattern ESCAPE '!')
            ORDER BY sp.fullName, sp.accountId
            """,
            countQuery = """
            SELECT COUNT(sp)
            FROM StaffProfile sp
            JOIN Account a ON a.id = sp.accountId
            WHERE a.role IN :roles AND a.status IN :statuses
              AND (:branchId IS NULL OR sp.branchId = :branchId)
              AND (LOWER(sp.fullName) LIKE :pattern ESCAPE '!' OR LOWER(a.email) LIKE :pattern ESCAPE '!')
            """)
    Page<StaffDetailRow> findStaffPage(@Param("roles") Collection<Role> roles,
            @Param("statuses") Collection<AccountStatus> statuses, @Param("branchId") Long branchId,
            @Param("pattern") String pattern, Pageable pageable);

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
