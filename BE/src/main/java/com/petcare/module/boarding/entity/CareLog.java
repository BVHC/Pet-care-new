package com.petcare.module.boarding.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

/** Bảng {@code care_logs} (erd §7, PART của đặt chỗ): một mục nhật ký chăm sóc (BR-LT-11). */
@Getter
@Entity
@Table(name = "care_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareLog extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "boarding_booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "log_date", nullable = false, updatable = false)
    private LocalDate logDate;

    @Column(name = "eating")
    private String eating;

    @Column(name = "drinking")
    private String drinking;

    @Column(name = "hygiene")
    private String hygiene;

    @Column(name = "activity")
    private String activity;

    @Column(name = "note")
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "photo_urls", nullable = false)
    private List<String> photoUrls = new ArrayList<>();

    @Column(name = "is_abnormal", nullable = false, updatable = false)
    private boolean abnormal;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    public static CareLog record(Long bookingId, LocalDate logDate, String eating, String drinking, String hygiene,
            String activity, String note, List<String> photoUrls, boolean abnormal, Long recordedBy) {
        CareLog log = new CareLog();
        log.bookingId = bookingId;
        log.logDate = logDate;
        log.edit(eating, drinking, hygiene, activity, note, photoUrls);
        log.abnormal = abnormal;
        log.recordedBy = recordedBy;
        return log;
    }

    /** Ghi đè các trường nội dung; service đã quyết trường nào giữ nguyên (E9) và truyền giá trị cuối. */
    public void edit(String eating, String drinking, String hygiene, String activity, String note,
            List<String> photoUrls) {
        this.eating = eating;
        this.drinking = drinking;
        this.hygiene = hygiene;
        this.activity = activity;
        this.note = note;
        this.photoUrls = new ArrayList<>(photoUrls);
    }
}
