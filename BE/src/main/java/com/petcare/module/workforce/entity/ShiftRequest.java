package com.petcare.module.workforce.entity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

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
 * Bảng {@code shift_requests} (erd §14.1, ROOT, 03 #11): yêu cầu nghỉ ({@code LEAVE}) hoặc đổi ca cùng ngày
 * ({@code SWAP}) của nhân viên cho một phân ca {@code SCHEDULED} của chính mình (BR-NS-04). Năm cột nội dung yêu cầu
 * ({@code assignment_id}, {@code requester_id}, {@code request_type}, {@code target_shift_definition_id},
 * {@code reason}) là {@code updatable = false}: đó là điều nhân viên đã yêu cầu. Chỉ nhóm {@code status},
 * {@code decided_by}, {@code decision_note}, {@code closed_at} đổi khi xử lý.
 * <p>
 * Mỗi method đóng yêu cầu đặt {@code closed_at} và các cột theo CHECK V36 ({@code ck_shift_requests_closed_at},
 * {@code _rejected_note}, {@code _approved_by}). Cả bốn cạnh ra khỏi {@code PENDING} nên mỗi method gọi
 * {@code requirePending()} (lớp chặn cuối, không thay {@code ShiftRequestTransitionHandler}).
 * <p>
 * <b>Cho T59 (BR-NS-09, nhân viên rời chi nhánh):</b> hủy hàng loạt yêu cầu {@code PENDING} nên dùng UPDATE
 * {@code WHERE status = 'PENDING'} ở repository, điều kiện đúng bằng cạnh {@code PENDING → REJECTED} của
 * {@link #rejectBySystem}, và tự ghi đủ cột như method này (khuôn {@code CareTaskRepository.cancelOpen*},
 * docs/adr/0079). Các luồng một-phân-ca (hủy, vắng, sửa đăng ký tuần) dùng {@link #rejectBySystem} vì đã khóa phân ca
 * rồi yêu cầu (docs/adr/0138).
 */
@Getter
@Entity
@Table(name = "shift_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftRequest extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false, updatable = false)
    private Long assignmentId;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, updatable = false, length = 30)
    private ShiftRequestType requestType;

    @Column(name = "target_shift_definition_id", updatable = false)
    private Long targetShiftDefinitionId;

    @Column(name = "reason", nullable = false, updatable = false, length = EntityText.NOTE_MAX_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShiftRequestStatus status;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decision_note", length = EntityText.NOTE_MAX_LENGTH)
    private String decisionNote;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Yêu cầu#1: {@code — → PENDING}. {@code SWAP} bắt buộc có ca đích khác ca hiện tại của phân ca
     * ({@code currentShiftDefinitionId}); {@code LEAVE} không có ca đích ({@code ck_shift_requests_swap_target}).
     * Điều kiện cần Clock / [CFG] / dòng khác (còn ≥ 24 giờ, ca đích đang dùng, chồng giờ, chủ phân ca) do service kiểm.
     */
    public static ShiftRequest submit(Long assignmentId, Long requesterId, Long currentShiftDefinitionId,
            ShiftRequestType type, Long targetShiftDefinitionId, String reason) {
        requireNonNull(type, "type");
        requireNonNull(currentShiftDefinitionId, "currentShiftDefinitionId");
        if (type == ShiftRequestType.SWAP) {
            requireNonNull(targetShiftDefinitionId, "targetShiftDefinitionId");
            if (targetShiftDefinitionId.equals(currentShiftDefinitionId)) {
                throw new IllegalArgumentException("Ca đích trùng ca hiện tại");
            }
        } else if (targetShiftDefinitionId != null) {
            throw new IllegalArgumentException("Yêu cầu nghỉ không có ca đích");
        }
        ShiftRequest entity = new ShiftRequest();
        entity.assignmentId = requireNonNull(assignmentId, "assignmentId");
        entity.requesterId = requireNonNull(requesterId, "requesterId");
        entity.requestType = type;
        entity.targetShiftDefinitionId = targetShiftDefinitionId;
        entity.reason = EntityText.require(reason, EntityText.NOTE_MAX_LENGTH, "reason");
        entity.status = ShiftRequestStatus.PENDING;
        return entity;
    }

    /** Yêu cầu#2: {@code PENDING → WITHDRAWN}. Người gửi rút; {@code decided_by} NULL, phân ca giữ nguyên. */
    public void withdraw(Instant at) {
        requirePending();
        Instant closed = requireNonNull(at, "at");
        this.status = ShiftRequestStatus.WITHDRAWN;
        this.closedAt = closed;
    }

    /** Yêu cầu#3: {@code PENDING → APPROVED}. BRANCH_MANAGER duyệt ({@code decided_by} bắt buộc). */
    public void approve(Long by, Instant at) {
        requirePending();
        Long decider = requireNonNull(by, "by");
        Instant closed = requireNonNull(at, "at");
        this.status = ShiftRequestStatus.APPROVED;
        this.decidedBy = decider;
        this.closedAt = closed;
    }

    /** Yêu cầu#4: {@code PENDING → REJECTED}. BRANCH_MANAGER từ chối, lý do bắt buộc (BR-NS-06). */
    public void reject(Long by, String note, Instant at) {
        requirePending();
        String text = EntityText.require(note, EntityText.NOTE_MAX_LENGTH, "decisionNote");
        Long decider = requireNonNull(by, "by");
        Instant closed = requireNonNull(at, "at");
        this.status = ShiftRequestStatus.REJECTED;
        this.decidedBy = decider;
        this.decisionNote = text;
        this.closedAt = closed;
    }

    /**
     * Yêu cầu#5: {@code PENDING → REJECTED} do hệ thống khi phân ca không còn hiệu lực (hủy, ghi vắng, nhân viên rời
     * chi nhánh, sửa đăng ký tuần). {@code decided_by} NULL, {@code decision_note} do hệ thống ghi.
     */
    public void rejectBySystem(String note, Instant at) {
        requirePending();
        String text = EntityText.require(note, EntityText.NOTE_MAX_LENGTH, "decisionNote");
        Instant closed = requireNonNull(at, "at");
        this.status = ShiftRequestStatus.REJECTED;
        this.decisionNote = text;
        this.closedAt = closed;
    }

    private void requirePending() {
        if (status != ShiftRequestStatus.PENDING) {
            throw new IllegalStateException("Yêu cầu đã ở trạng thái cuối " + status);
        }
    }
}
