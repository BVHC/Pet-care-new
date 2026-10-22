package com.petcare.module.boarding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.entity.Kennel;

import jakarta.persistence.LockModeType;

/** Chuồng (docs/adr/0065). Khóa dòng {@code FOR NO KEY UPDATE} trước khi đọc entity, sau khóa {@code branches}. */
public interface KennelRepository extends JpaRepository<Kennel, Long> {

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM Kennel k WHERE k.id = :id")
    Optional<Kennel> findByIdForUpdate(@Param("id") Long id);

    /** Chi nhánh của chuồng, đọc không khóa để phân quyền trước khi khóa. */
    @Query(value = "SELECT branch_id FROM kennels WHERE id = :id", nativeQuery = true)
    Optional<Long> findBranchIdById(@Param("id") Long id);

    List<Kennel> findByBranchIdOrderByCodeAsc(Long branchId);

    boolean existsByBranchIdAndCode(Long branchId, String code);

    boolean existsByBranchIdAndCodeAndIdNot(Long branchId, String code, Long id);

    /** Sức chứa của một loại chuồng tại chi nhánh: số chuồng không {@code MAINTENANCE} (BR-LT-01). */
    @Query(value = """
            SELECT CAST(count(*) AS integer) FROM kennels
            WHERE branch_id = :branchId AND kennel_type_id = :kennelTypeId AND status <> 'MAINTENANCE'
            """, nativeQuery = true)
    int countCapacity(@Param("branchId") Long branchId, @Param("kennelTypeId") Long kennelTypeId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE kennel_id = :id)", nativeQuery = true)
    boolean hasBookingHistory(@Param("id") Long kennelId);
}
