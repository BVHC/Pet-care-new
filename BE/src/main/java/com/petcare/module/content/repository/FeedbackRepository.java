package com.petcare.module.content.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.entity.Feedback;

import jakarta.persistence.LockModeType;

public interface FeedbackRepository extends JpaRepository<Feedback, Long>, JpaSpecificationExecutor<Feedback> {

    /** Chỉ chi nhánh của feedback (có thể null): phân quyền trước khi ghi, không nạp entity vào persistence context. */
    interface BranchOnly {
        /** Cột thứ hai để dòng có chi nhánh NULL vẫn là kết quả (một cột NULL duy nhất thì Spring Data trả rỗng). */
        Long getId();

        Long getBranchId();
    }

    java.util.Optional<BranchOnly> findBranchOnlyById(Long id);

    /** NEW → SEEN có điều kiện (BR-DG-04): trả 1 nếu đây là lần mở đầu tiên, 0 nếu đã có người mở trước. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("UPDATE Feedback f SET f.status = com.petcare.module.content.entity.FeedbackStatus.SEEN, "
            + "f.seenAt = :now, f.updatedAt = :now WHERE f.id = :id "
            + "AND f.status = com.petcare.module.content.entity.FeedbackStatus.NEW")
    int markSeenIfNew(@Param("id") Long id, @Param("now") java.time.Instant now);

    /**
     * Advisory lock theo transaction ({@code pg_advisory_xact_lock}, namespace 1003 theo khách): tuần tự hóa giới hạn
     * feedback mỗi ngày. {@code MANDATORY}: ngoài transaction thì khóa được nhả ngay khi câu lệnh xong.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer acquireTransactionLock(@Param("namespace") int namespace, @Param("key") int key);

    long countByCustomerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long customerId, java.time.Instant from,
            java.time.Instant to);

    java.util.List<Feedback> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Feedback f WHERE f.id = :id")
    Optional<Feedback> findByIdForUpdate(@Param("id") Long id);
}
