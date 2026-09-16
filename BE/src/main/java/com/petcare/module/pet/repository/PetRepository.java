package com.petcare.module.pet.repository;

import com.petcare.module.pet.entity.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PetRepository extends JpaRepository<Pet, UUID> {

    List<Pet> findByOwnerId(UUID ownerId);

    Page<Pet> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Pet mình sở hữu HOẶC pet có ủy quyền còn hiệu lực trỏ về mình (RULE-04-09).
     * Điều kiện hiệu lực phải giống hệt PetCaregiverDelegationRepository.existsActiveDelegation
     * (spec D-08) — sửa một chỗ thì sửa cả hai.
     *
     * Enum literal dùng SpEL binding (:#{T(...).ACTIVE}), KHÔNG dùng literal Java
     * inline (d.status = com.petcare.platform.enums.CaregiverStatus.ACTIVE) — Task 2
     * phát hiện literal inline khiến Hibernate NAMED_ENUM sinh cast theo simple
     * class name (::caregiverstatus) thay vì tên type Postgres thật
     * (caregiver_status_enum từ V1), gây lỗi runtime "type does not exist". Xem
     * PetCaregiverDelegationRepository.existsActiveDelegation (Task 2) để đối chiếu
     * cú pháp đã dùng.
     */
    @Query("""
            select p from Pet p
            where p.ownerId = :userId
               or exists (select 1 from PetCaregiverDelegation d
                          where d.petId = p.id
                            and d.caregiverUserId = :userId
                            and d.status = :#{T(com.petcare.platform.enums.CaregiverStatus).ACTIVE}
                            and (d.validUntil is null or d.validUntil > :now))
            """)
    Page<Pet> findAccessibleBy(@Param("userId") UUID userId,
                               @Param("now") LocalDateTime now,
                               Pageable pageable);
}
