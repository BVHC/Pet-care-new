package com.petcare.module.inpatient.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.entity.Admission;

import jakarta.persistence.LockModeType;

/**
 * Bảng {@code admissions} (docs/adr/0149). Hàm khóa là {@code MANDATORY} (cần transaction của bên gọi). N1 chỉ có hàm
 * khóa theo id; N2 thêm các câu đọc (theo thú, theo Visit, danh sách) cùng test của chúng.
 */
public interface AdmissionRepository extends JpaRepository<Admission, Long> {

    /** {@code FOR NO KEY UPDATE}: xuất viện, ra y lệnh, ghi theo dõi (khóa ca trước y lệnh). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Admission a WHERE a.id = :id")
    Optional<Admission> findByIdForUpdate(@Param("id") Long id);
}
