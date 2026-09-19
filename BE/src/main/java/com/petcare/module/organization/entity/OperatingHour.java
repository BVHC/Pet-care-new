package com.petcare.module.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Child Entity Module 03 (Organization & Store Management) — docs/06-erd.md bảng
 * operating_hours. Không extend BaseEntity — bảng không có created_by/updated_by/deleted_at/
 * version (giống Otp/RefreshTokenEntity). RULE-03-02/07 — dayOfWeek 1=Chủ nhật...7=Thứ bảy
 * (CONFIRMED theo ERD, khác ISO). ConfigureOperatingHour dùng semantics replace-all (xóa hết
 * + ghi lại đúng mảng gửi lên) nên không cần @Version — mỗi lần cấu hình luôn ghi row mới.
 */
@Entity
@Table(name = "operating_hours")
@Getter
@Setter
@NoArgsConstructor
public class OperatingHour {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "is_closed", nullable = false)
    private boolean closed = false;

    public OperatingHour(UUID storeId, int dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean closed) {
        this.storeId = storeId;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.closed = closed;
    }
}
