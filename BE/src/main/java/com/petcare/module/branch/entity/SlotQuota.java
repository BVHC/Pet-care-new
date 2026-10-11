package com.petcare.module.branch.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.module.catalog.api.ServiceGroup;
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
import lombok.Setter;

/**
 * Bảng {@code slot_quotas} (erd §2, PART của Branch): quota riêng của một khung giờ cụ thể, ghi đè quota mặc định;
 * 0 là khóa khung (BR-LH-03). Mỗi (chi nhánh, nhóm, ngày, giờ bắt đầu) có tối đa một dòng.
 */
@Getter
@Entity
@Table(name = "slot_quotas")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlotQuota extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false)
    private ServiceGroup serviceGroup;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    @Setter
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "quota", nullable = false)
    private int quota;

    @Setter
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    public SlotQuota(Long branchId, ServiceGroup serviceGroup, LocalDate slotDate, LocalTime slotStart, int quota,
            Long updatedBy) {
        this.branchId = branchId;
        this.serviceGroup = serviceGroup;
        this.slotDate = slotDate;
        this.slotStart = slotStart;
        this.quota = quota;
        this.updatedBy = updatedBy;
    }
}
