package com.petcare.module.visit.entity;

import com.petcare.platform.model.CreatedAtEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code visit_assignments} (erd §6, LOG): lịch sử gán / gán lại nhân viên cho lượt (Visit#2, #4; BR-TN-05, 06,
 * 08). Chỉ thêm mới: mọi cột {@code updatable = false}. {@code fromAccountId} null ở lần gán đầu; {@code reason} bắt
 * buộc khi gán lại lượt đã gọi; {@code assigneeWasOffline} ghi lại cảnh báo không chặn của BR-TN-06. Bản ghi do gán
 * lượt chưa gọi tạo từ T26 phần 3 (docs/adr/0061); gán lại lượt đã gọi tạo ở phần sau.
 */
@Getter
@Entity
@Table(name = "visit_assignments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitAssignment extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private Long visitId;

    @Column(name = "from_account_id", updatable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private Long toAccountId;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private Long assignedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_status_at_assign", nullable = false, updatable = false)
    private VisitStatus visitStatusAtAssign;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "assignee_was_offline", nullable = false, updatable = false)
    private boolean assigneeWasOffline;

    /**
     * Một lần gán (Visit#2, docs/adr/0061) hoặc gán lại lượt đã gọi (Visit#4, phần sau). {@code fromAccountId} null ở
     * lần gán đầu; {@code reason} chỉ bắt buộc ở Visit#4 (BR-TN-08); {@code created_at} theo {@code Clock}.
     */
    public static VisitAssignment of(Long visitId, Long fromAccountId, Long toAccountId, Long assignedBy,
            VisitStatus visitStatusAtAssign, String reason, boolean assigneeWasOffline) {
        VisitAssignment assignment = new VisitAssignment();
        assignment.visitId = visitId;
        assignment.fromAccountId = fromAccountId;
        assignment.toAccountId = toAccountId;
        assignment.assignedBy = assignedBy;
        assignment.visitStatusAtAssign = visitStatusAtAssign;
        assignment.reason = reason;
        assignment.assigneeWasOffline = assigneeWasOffline;
        return assignment;
    }
}
