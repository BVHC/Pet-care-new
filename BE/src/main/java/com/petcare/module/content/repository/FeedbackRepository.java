package com.petcare.module.content.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.entity.Feedback;

public interface FeedbackRepository extends JpaRepository<Feedback, Long>, JpaSpecificationExecutor<Feedback> {

    /** Chỉ chi nhánh của feedback (có thể null): phân quyền trước khi đọc, không nạp entity vào persistence context. */
    interface BranchOnly {
        /** Cột thứ hai để dòng có chi nhánh NULL vẫn là kết quả (một cột NULL duy nhất thì Spring Data trả rỗng). */
        Long getId();

        Long getBranchId();
    }

    java.util.Optional<BranchOnly> findBranchOnlyById(Long id);

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

    /**
     * Một lô của ST24 (BR-DG-05, docs/adr/0122, 0131): xóa cứng tối đa {@code limit} feedback có
     * {@code created_at < cutoff} (so sánh chặt: đúng biên thì giữ), cũ nhất trước. {@code created_at} bất biến
     * ({@code updatable = false}, không có UPDATE nào khác trong mã) nên mọi dòng bị xóa đều là dòng subquery đã chọn và
     * đã khóa: không có khoảng hở giữa chọn và xóa, và không bao giờ xóa dòng còn hạn. {@code ORDER BY created_at, id}
     * để quét {@code ix_feedbacks_created_at} (V51); ADR-0122 ghi "id tăng dần", đổi vì thứ tự theo id không dùng được
     * index đó. {@code SKIP LOCKED}: không chờ dòng mà tx khác đang giữ (dòng bỏ qua được xóa ở lượt sau), nên job
     * không tạo chu trình khóa. Không bảng nào trỏ FK tới {@code feedbacks}; không ghi audit (không thuộc BR-QT-15).
     */
    @Modifying
    @Query(value = """
            DELETE FROM feedbacks WHERE id IN (
                SELECT id FROM feedbacks
                WHERE created_at < :cutoff
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);
}
