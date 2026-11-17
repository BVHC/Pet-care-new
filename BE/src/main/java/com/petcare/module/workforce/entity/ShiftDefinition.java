package com.petcare.module.workforce.entity;

import static java.util.Objects.requireNonNull;

import java.time.LocalTime;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code shift_definitions} (erd §14.1, REF): ca làm việc do BRANCH_MANAGER định nghĩa cho chi nhánh mình
 * (BR-NS-01). {@code branch_id} bất biến. Entity giữ hai bất biến tự thân: tên không rỗng, ≤ 60 ký tự (lưu bản đã
 * {@code strip}) và giờ bắt đầu nhỏ hơn giờ kết thúc ({@code ck_shift_definitions_time_order}). Tên không trùng trong
 * chi nhánh (unique {@code lower(name)}), "ngừng dùng khi còn phân ca" và "sửa giờ khi ca đã có phân ca" cần dữ liệu
 * dòng khác nên do service kiểm dưới khóa dòng ca (docs/adr/0138). Các method ghi <b>không</b> tự khóa hay kiểm quyền.
 */
@Getter
@Entity
@Table(name = "shift_definitions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftDefinition extends TimestampedEntity {

    /** {@code name VARCHAR(60)} (erd §14.1). */
    public static final int NAME_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** Ca mới luôn đang dùng (BR-NS-01). */
    public static ShiftDefinition create(Long branchId, String name, LocalTime startTime, LocalTime endTime) {
        ShiftDefinition entity = new ShiftDefinition();
        entity.branchId = requireNonNull(branchId, "branchId");
        entity.name = EntityText.require(name, NAME_MAX_LENGTH, "name");
        requireOrdered(startTime, endTime);
        entity.startTime = startTime;
        entity.endTime = endTime;
        entity.active = true;
        return entity;
    }

    public void rename(String newName) {
        this.name = EntityText.require(newName, NAME_MAX_LENGTH, "name");
    }

    /** Nhận cả hai giờ để kiểm thứ tự trên giá trị kết quả, kể cả khi bên gọi chỉ đổi một trong hai. */
    public void changeTimes(LocalTime newStart, LocalTime newEnd) {
        requireOrdered(newStart, newEnd);
        this.startTime = newStart;
        this.endTime = newEnd;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    private static void requireOrdered(LocalTime start, LocalTime end) {
        requireNonNull(start, "startTime");
        requireNonNull(end, "endTime");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Giờ bắt đầu phải nhỏ hơn giờ kết thúc");
        }
    }
}
