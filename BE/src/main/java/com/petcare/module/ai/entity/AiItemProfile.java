package com.petcare.module.ai.entity;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * Bảng {@code ai_item_profiles} (07 §8, REF): thuộc tính đối tượng và chu kỳ dùng lại của một dịch vụ thẩm mỹ hoặc sản
 * phẩm {@code GOODS} (BR-AI-20). Đúng một trong {@code service_id} / {@code product_id} (CHECK ở V34). Chưa có logic
 * (UC94 là việc của T78); entity có ở đây để schema được kiểm khi khởi động.
 */
@Getter
@Entity
@Table(name = "ai_item_profiles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiItemProfile extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", updatable = false)
    private Long serviceId;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", length = 30)
    private ProfileSpecies species;

    @Column(name = "min_age_weeks")
    private Short minAgeWeeks;

    @Column(name = "max_age_weeks")
    private Short maxAgeWeeks;

    @Column(name = "min_weight_kg", precision = 6, scale = 2)
    private BigDecimal minWeightKg;

    @Column(name = "max_weight_kg", precision = 6, scale = 2)
    private BigDecimal maxWeightKg;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "breed_keywords", columnDefinition = "varchar(100)[]")
    private String[] breedKeywords;

    @Column(name = "repeat_interval_days")
    private Short repeatIntervalDays;

    @Column(name = "updated_by")
    private Long updatedBy;
}
