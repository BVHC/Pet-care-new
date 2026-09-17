package com.petcare.module.pet.entity;

import com.petcare.platform.enums.CaregiverStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child entity của aggregate Pet (docs/05-domain-model.md §04) — vòng đời ủy quyền
 * chăm sóc Pet, FSM-3 (docs/03-state-machines.md §3).
 * Định danh lời mời là email, không phải phone — xem spec D-01 (RULE-01-10).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pet_caregiver_delegations")
public class PetCaregiverDelegation extends BaseEntity {

    @Column(name = "pet_id", nullable = false)
    private UUID petId;

    @Column(name = "primary_owner_id", nullable = false)
    private UUID primaryOwnerId;

    @Column(name = "caregiver_user_id")
    private UUID caregiverUserId;

    @Column(name = "caregiver_email", nullable = false, length = 100)
    private String caregiverEmail;

    @Column(name = "caregiver_phone", length = 20)
    private String caregiverPhone;

    @Column(name = "invitation_token_hash", nullable = false, length = 100)
    private String invitationTokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private CaregiverStatus status = CaregiverStatus.INVITED;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;
}
