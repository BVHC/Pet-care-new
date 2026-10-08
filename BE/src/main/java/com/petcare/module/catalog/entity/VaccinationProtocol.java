package com.petcare.module.catalog.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.module.customer.api.Species;
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
 * Bảng {@code vaccination_protocols} (erd §4, REF). Một dòng = một mũi của (loài, loại vaccine); bộ ba loài, loại,
 * mũi thứ không đổi sau khi tạo (catalog-v1 A3). Sửa dòng chỉ áp dụng cho mũi tiêm sau đó (BR-SP-03).
 */
@Getter
@Entity
@Table(name = "vaccination_protocols")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VaccinationProtocol extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false)
    private Species species;

    @Column(name = "vaccine_type_id", nullable = false)
    private Long vaccineTypeId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "dose_number", nullable = false)
    private int doseNumber;

    @Setter
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Setter
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_age_weeks", nullable = false)
    private int minAgeWeeks;

    @Setter
    @Column(name = "required_for_boarding", nullable = false)
    private boolean requiredForBoarding;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public VaccinationProtocol(Species species, Long vaccineTypeId, int doseNumber, int intervalDays,
            int minAgeWeeks, boolean requiredForBoarding) {
        this.species = species;
        this.vaccineTypeId = vaccineTypeId;
        this.doseNumber = doseNumber;
        this.intervalDays = intervalDays;
        this.minAgeWeeks = minAgeWeeks;
        this.requiredForBoarding = requiredForBoarding;
    }
}
