package com.petcare.module.catalog.entity;

import java.math.BigDecimal;

import com.petcare.module.customer.api.Species;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bảng {@code kennel_types} (erd §4, PART 1-1 của {@code services} nhóm BOARDING, BR-SP-04); PK là {@code service_id}. */
@Getter
@Entity
@Table(name = "kennel_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KennelType extends TimestampedEntity {

    @Id
    @Column(name = "service_id")
    private Long serviceId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false)
    private Species species;

    @Setter
    @Column(name = "max_weight_kg", nullable = false)
    private BigDecimal maxWeightKg;

    public KennelType(Long serviceId, Species species, BigDecimal maxWeightKg) {
        this.serviceId = serviceId;
        this.species = species;
        this.maxWeightKg = maxWeightKg;
    }
}
