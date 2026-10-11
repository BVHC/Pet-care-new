package com.petcare.module.branch.entity;

import java.time.LocalDate;

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

/** Bảng {@code holidays} (erd §2, PART của Branch): ngày nghỉ cả ngày của một chi nhánh (BR-CN-03). */
@Getter
@Entity
@Table(name = "holidays")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    public Holiday(Long branchId, LocalDate holidayDate, String reason, Long createdBy) {
        this.branchId = branchId;
        this.holidayDate = holidayDate;
        this.reason = reason;
        this.createdBy = createdBy;
    }
}
