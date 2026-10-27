package com.petcare.module.visit.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.entity.MedicalRecord;

/**
 * Bảng {@code medical_records}, PK = {@code visit_id}. Bên ghi bệnh án khóa dòng {@code visits} trước
 * ({@code VisitRepository.lockById}, docs/adr/0066 §7) rồi mới đọc dòng này. Ngoại lệ: ST04 nhắc tái khám
 * (docs/adr/0082) khóa trực tiếp dòng này {@code SKIP LOCKED} (không chờ nên không nằm trong vòng deadlock) sau
 * {@code pets FOR SHARE}; các truy vấn ST04 dùng chung điều kiện "đến hạn nhắc" để câu quét khớp
 * {@code ix_medical_records_follow_up_due}.
 */
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Long> {

    /**
     * ST04: id bệnh án (= id lượt) có hẹn tái khám trong {@code [from, to]}, chưa nhắc, chưa bị hủy nhắc, đã khóa (lượt
     * {@code COMPLETED}, erd {@code locked_at}). Theo ngày tái khám rồi id. Không khóa.
     */
    @Query(value = """
            SELECT visit_id FROM medical_records
            WHERE follow_up_date IS NOT NULL AND follow_up_reminded_at IS NULL AND follow_up_cancelled_at IS NULL
              AND locked_at IS NOT NULL AND follow_up_date BETWEEN :fromDate AND :toDate
            ORDER BY follow_up_date, visit_id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueForReminderIds(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
            @Param("limit") int limit);

    /** Lô kế tiếp của {@link #findDueForReminderIds}: keyset {@code (follow_up_date, visit_id)} lớn hơn của {@code afterId}. */
    @Query(value = """
            SELECT m.visit_id FROM medical_records m
            WHERE m.follow_up_date IS NOT NULL AND m.follow_up_reminded_at IS NULL AND m.follow_up_cancelled_at IS NULL
              AND m.locked_at IS NOT NULL AND m.follow_up_date BETWEEN :fromDate AND :toDate
              AND (m.follow_up_date, m.visit_id) > (SELECT p.follow_up_date, p.visit_id FROM medical_records p
                                                    WHERE p.visit_id = :afterId)
            ORDER BY m.follow_up_date, m.visit_id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueForReminderIdsAfter(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
            @Param("afterId") long afterId, @Param("limit") int limit);

    /** Thú của lượt có bệnh án, đọc không khóa để khóa {@code pets} trước dòng nguồn. Cột không đổi. */
    @Query(value = "SELECT pet_id FROM visits WHERE id = :visitId", nativeQuery = true)
    Optional<Long> findPetIdOfVisit(@Param("visitId") long visitId);

    /**
     * Khóa bệnh án ({@code FOR NO KEY UPDATE SKIP LOCKED}) nếu <b>vẫn</b> đến hạn nhắc: một lượt ST04 khác, hay hủy nhắc
     * tái khám (D029) có thể đã commit sau khi quét. {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT visit_id FROM medical_records
            WHERE visit_id = :visitId AND follow_up_date IS NOT NULL AND follow_up_reminded_at IS NULL
              AND follow_up_cancelled_at IS NULL AND locked_at IS NOT NULL
              AND follow_up_date BETWEEN :fromDate AND :toDate
            FOR NO KEY UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockDueForReminder(@Param("visitId") long visitId, @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Các cột ST04 cần để soạn nhắc; đọc <b>sau</b> {@link #lockDueForReminder}, không nạp entity. */
    @Query(value = """
            SELECT m.visit_id AS visitId, v.pet_id AS petId, v.branch_id AS branchId, m.follow_up_date AS followUpDate
            FROM medical_records m JOIN visits v ON v.id = m.visit_id WHERE m.visit_id = :visitId
            """, nativeQuery = true)
    Optional<DueFollowUp> findDueFollowUp(@Param("visitId") long visitId);

    /** Đánh dấu đã nhắc tái khám (cùng transaction với {@code enqueue} / {@code create}); bên gọi đã khóa nên phải là 1. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE medical_records SET follow_up_reminded_at = :at, updated_at = :at
            WHERE visit_id = :visitId AND follow_up_reminded_at IS NULL AND follow_up_cancelled_at IS NULL
            """, nativeQuery = true)
    int markFollowUpReminded(@Param("visitId") long visitId, @Param("at") Instant at);

    /**
     * Hủy nhắc tái khám của thú đã mất (BR-TB-06, G3): đặt {@code follow_up_cancelled_at}, không gửi gì. Cùng điều kiện
     * với {@link #markFollowUpReminded}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE medical_records SET follow_up_cancelled_at = :at, updated_at = :at
            WHERE visit_id = :visitId AND follow_up_reminded_at IS NULL AND follow_up_cancelled_at IS NULL
            """, nativeQuery = true)
    int markFollowUpCancelled(@Param("visitId") long visitId, @Param("at") Instant at);

    /** Projection của {@link #findDueFollowUp}. */
    interface DueFollowUp {

        Long getVisitId();

        Long getPetId();

        Long getBranchId();

        LocalDate getFollowUpDate();
    }
}
