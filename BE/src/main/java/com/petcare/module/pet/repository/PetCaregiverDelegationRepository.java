package com.petcare.module.pet.repository;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.platform.enums.CaregiverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PetCaregiverDelegationRepository extends JpaRepository<PetCaregiverDelegation, UUID> {

    Optional<PetCaregiverDelegation> findByInvitationTokenHash(String invitationTokenHash);

    Optional<PetCaregiverDelegation> findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
            UUID petId, String caregiverEmail, Collection<CaregiverStatus> statuses);

    /** RULE-04-10 — toàn bộ delegation còn sống (INVITED/ACTIVE) của 1 pet, dùng khi ManagePetOwnership. */
    List<PetCaregiverDelegation> findByPetIdAndStatusIn(UUID petId, Collection<CaregiverStatus> statuses);

    /**
     * "Ủy quyền còn hiệu lực" (spec D-08) — điều kiện này phải giống hệt điều kiện
     * trong PetRepository.findAccessibleBy. Sửa một chỗ thì sửa cả hai.
     */
    @Query("""
            select count(d) > 0 from PetCaregiverDelegation d
            where d.petId = :petId
              and d.caregiverUserId = :caregiverUserId
              and d.status = :#{T(com.petcare.platform.enums.CaregiverStatus).ACTIVE}
              and (d.validUntil is null or d.validUntil > :now)
            """)
    boolean existsActiveDelegation(@Param("petId") UUID petId,
                                   @Param("caregiverUserId") UUID caregiverUserId,
                                   @Param("now") LocalDateTime now);

    @Query("""
            select d from PetCaregiverDelegation d
            where d.status = :#{T(com.petcare.platform.enums.CaregiverStatus).INVITED}
              and d.expiresAt <= :now
            """)
    List<PetCaregiverDelegation> findExpiredInvitations(@Param("now") LocalDateTime now);

    @Query("""
            select d from PetCaregiverDelegation d
            where d.status = :#{T(com.petcare.platform.enums.CaregiverStatus).ACTIVE}
              and d.validUntil is not null
              and d.validUntil <= :now
            """)
    List<PetCaregiverDelegation> findExpiredDelegations(@Param("now") LocalDateTime now);
}
