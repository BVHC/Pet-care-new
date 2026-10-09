package com.petcare.module.branch.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * Bảng {@code opening_hours} (erd §2, PART của Branch): một dòng cho mỗi thứ trong tuần (1 = thứ Hai … 7 = Chủ
 * nhật) của một phiên bản giờ mở cửa. Mỗi ngày có 0, 1 hoặc 2 khoảng giờ (BR-CN-02); không có khoảng nào là ngày nghỉ
 * cố định hằng tuần. Phiên bản có {@code effective_from} lớn nhất không quá ngày đang xét là bản áp dụng (BR-CN-04).
 */
@Getter
@Entity
@Table(name = "opening_hours")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpeningHours extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "open_1")
    private LocalTime open1;

    @Column(name = "close_1")
    private LocalTime close1;

    @Column(name = "open_2")
    private LocalTime open2;

    @Column(name = "close_2")
    private LocalTime close2;

    public OpeningHours(Long branchId, LocalDate effectiveFrom, int dayOfWeek, LocalTime open1, LocalTime close1,
            LocalTime open2, LocalTime close2) {
        this.branchId = branchId;
        this.effectiveFrom = effectiveFrom;
        this.dayOfWeek = dayOfWeek;
        this.open1 = open1;
        this.close1 = close1;
        this.open2 = open2;
        this.close2 = close2;
    }
}
