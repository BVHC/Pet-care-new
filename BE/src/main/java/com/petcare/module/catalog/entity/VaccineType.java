package com.petcare.module.catalog.entity;

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

/** Bảng {@code vaccine_types} (erd §4, REF). Loài không đổi sau khi tạo (BR-SP-07). */
@Getter
@Entity
@Table(name = "vaccine_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VaccineType extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false)
    private Species species;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public VaccineType(String name, Species species) {
        this.name = name;
        this.species = species;
    }
}
