package com.petcare.module.branch.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.module.catalog.api.ServiceGroup;
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
 * Bảng {@code branch_quota_defaults} (erd §2, PART của Branch): quota mặc định của một nhóm dịch vụ (Khám/Tiêm,
 * Thẩm mỹ) tại chi nhánh, áp dụng cho mọi khung giờ (BR-LH-03). Không có dòng thì quota là 1.
 */
@Getter
@Entity
@Table(name = "branch_quota_defaults")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchQuotaDefault extends TimestampedEntity {

    @EmbeddedId
    private BranchQuotaDefaultId id;

    @Setter
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "default_quota", nullable = false)
    private int defaultQuota;

    @Setter
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    public BranchQuotaDefault(Long branchId, ServiceGroup serviceGroup, int defaultQuota, Long updatedBy) {
        this.id = new BranchQuotaDefaultId(branchId, serviceGroup);
        this.defaultQuota = defaultQuota;
        this.updatedBy = updatedBy;
    }
}
