package com.petcare.module.pet.service;

import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Nơi DUY NHẤT định nghĩa "ai đọc được Pet nào" (RULE-04-09) và "ai là Primary Owner"
 * (RULE-04-04). Điều kiện "ủy quyền còn hiệu lực" được đánh giá tại thời điểm request,
 * KHÔNG dựa vào job hết hạn (spec D-08) — giữa hai tick cron vẫn tồn tại bản ghi
 * status=ACTIVE nhưng valid_until đã trôi qua.
 *
 * Bản sao thứ hai của cùng điều kiện nằm ở PetRepository.findAccessibleBy (dùng cho
 * GET /pets). Sửa một chỗ thì sửa cả hai.
 */
@Component
@RequiredArgsConstructor
public class PetAccessGuard {

    private final PetCaregiverDelegationRepository delegations;

    /** RULE-04-09 — owner hoặc caregiver đang ACTIVE và còn hạn. */
    public void requireCanViewPet(UUID userId, Pet pet) {
        if (pet.getOwnerId().equals(userId)) {
            return;
        }
        if (delegations.existsActiveDelegation(pet.getId(), userId, LocalDateTime.now())) {
            return;
        }
        throw new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED");
    }

    /** RULE-04-04 — chỉ Primary Owner; caregiver ACTIVE cũng bị chặn. */
    public void requirePrimaryOwner(UUID userId, Pet pet) {
        if (!pet.getOwnerId().equals(userId)) {
            throw new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER");
        }
    }
}
