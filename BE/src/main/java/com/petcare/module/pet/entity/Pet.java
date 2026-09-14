package com.petcare.module.pet.entity;

import com.petcare.platform.enums.PetStatus;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate Root Pets v1 — docs/06-erd.md §3.2/§3.3 bảng pets,
 * RULE-04-01→04-03. {@code ownerId} giữ dạng UUID thô (không
 * {@code @ManyToOne} — giữ Bounded Context, như {@code User.accountId}).
 * {@code species/gender} là VARCHAR → String thường; chỉ {@code status}
 * dùng NAMED_ENUM (copy {@code Account.status}).
 */
@Entity
@Table(name = "pets")
@Getter
@Setter
@NoArgsConstructor
public class Pet extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "species", nullable = false, length = 50)
    private String species = "DOG";

    @Column(name = "breed", length = 100)
    private String breed;

    @Column(name = "gender", nullable = false, length = 10)
    private String gender = "UNKNOWN";

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "microchip_number", length = 50)
    private String microchipNumber;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private PetStatus status = PetStatus.ACTIVE;

    public Pet(UUID ownerId, String name, String species) {
        this.ownerId = ownerId;
        this.name = name;
        this.species = species;
        this.status = PetStatus.ACTIVE;
    }
}
