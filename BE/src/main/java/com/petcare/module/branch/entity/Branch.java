package com.petcare.module.branch.entity;

import java.math.BigDecimal;
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
import lombok.Setter;

/**
 * Bảng {@code branches} (erd §2, ROOT · SM #2). Mới tạo là {@code DRAFT}; kích hoạt khi đã có BRANCH_MANAGER và giờ
 * mở cửa (BR-CN-01, BR-QT-04). Cờ nhận cấp cứu ngoài giờ là thuộc tính, không phải trạng thái (BR-CN-05).
 */
@Getter
@Entity
@Table(name = "branches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Branch extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Setter
    @Column(name = "address", nullable = false)
    private String address;

    @Setter
    @Column(name = "phone", nullable = false)
    private String phone;

    @Setter
    @Column(name = "latitude", nullable = false)
    private BigDecimal latitude;

    @Setter
    @Column(name = "longitude", nullable = false)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BranchStatus status;

    @Setter
    @Column(name = "accepts_after_hours_emergency", nullable = false)
    private boolean acceptsAfterHoursEmergency;

    @Column(name = "activated_at")
    private Instant activatedAt;

    public Branch(String name, String address, String phone, BigDecimal latitude, BigDecimal longitude,
            boolean acceptsAfterHoursEmergency) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.acceptsAfterHoursEmergency = acceptsAfterHoursEmergency;
        this.status = BranchStatus.DRAFT;
    }

    /** Chi nhánh#2. Việc kiểm điều kiện và {@code validateTransition} do {@code BranchService} làm trước. */
    public void activate(Instant at) {
        this.status = BranchStatus.ACTIVE;
        this.activatedAt = at;
    }
}
