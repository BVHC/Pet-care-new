package com.petcare.module.inpatient.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;

import jakarta.persistence.LockModeType;

/**
 * Bảng {@code admissions} (docs/adr/0149). Hàm khóa và hàm đọc cho {@code AdmissionQueryApi} là {@code MANDATORY} (cần
 * transaction của bên gọi). N1: khóa theo id; N2: hai câu "còn {@code ADMITTED}" (theo thú, theo Visit); N3: trang theo
 * chi nhánh (inpatient-v1 #2). Đọc chi tiết #3 dùng {@code findById} sẵn có.
 */
public interface AdmissionRepository extends JpaRepository<Admission, Long> {

    /**
     * inpatient-v1 #2: ca của một chi nhánh theo trạng thái, nhập viện sớm nhất trước, trùng thì id tăng dần (index
     * {@code ix_admissions_branch_status} V38). {@code pageable} không mang sort (thứ tự cố định ở đây).
     */
    @Query("""
            SELECT a FROM Admission a WHERE a.branchId = :branchId AND a.status = :status
            ORDER BY a.admittedAt ASC, a.id ASC""")
    Page<Admission> findPageByBranchAndStatus(@Param("branchId") Long branchId,
            @Param("status") AdmissionStatus status, Pageable pageable);

    /** Literal {@code ADMITTED} để planner dùng partial index {@code ux_admissions_pet_admitted} (V38). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            SELECT COUNT(a) > 0 FROM Admission a
            WHERE a.petId = :petId AND a.status = com.petcare.module.inpatient.entity.AdmissionStatus.ADMITTED""")
    boolean existsActiveByPetId(@Param("petId") Long petId);

    /** Index {@code ix_admissions_visit} (V38). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            SELECT COUNT(a) > 0 FROM Admission a
            WHERE a.visitId = :visitId AND a.status = com.petcare.module.inpatient.entity.AdmissionStatus.ADMITTED""")
    boolean existsActiveByVisitId(@Param("visitId") Long visitId);

    /** {@code FOR NO KEY UPDATE}: xuất viện, ra y lệnh, ghi theo dõi (khóa ca trước y lệnh). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Admission a WHERE a.id = :id")
    Optional<Admission> findByIdForUpdate(@Param("id") Long id);
}
