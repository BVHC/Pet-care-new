package com.petcare.module.branch.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bảng {@code branch_services} (erd §2, PART của Branch; model {@code BranchService} ở docs/04, đặt tên khác để
 * không trùng class service của UC12): bật / tắt một dịch vụ, kể cả loại chuồng, tại một chi nhánh (UC33, BR-LH-01,
 * BR-SP-04). Chưa có dòng nghĩa là chưa bật.
 */
@Getter
@Entity
@Table(name = "branch_services")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchServiceSetting extends TimestampedEntity {

    @EmbeddedId
    private BranchServiceSettingId id;

    @Setter
    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    public BranchServiceSetting(Long branchId, Long serviceId, boolean enabled) {
        this.id = new BranchServiceSettingId(branchId, serviceId);
        this.enabled = enabled;
    }
}
