package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;

import java.util.List;
import java.util.UUID;

/** Tên method trùng chính xác Command trong docs/01-business-operations.md §4. */
public interface CaregiverDelegationService {

    CaregiverInvitationOutcome inviteCaregiver(UUID ownerUserId, UUID petId, InviteCaregiverRequest req);

    CaregiverDelegationResponse acceptCaregiverInvitation(UUID actorUserId, String rawToken);

    CaregiverDelegationResponse rejectCaregiverInvitation(UUID actorUserId, String rawToken);

    CaregiverDelegationResponse revokeCaregiver(UUID ownerUserId, UUID petId, RevokeCaregiverRequest req);

    /**
     * RULE-04-10 — thu hồi hàng loạt mọi delegation INVITED/ACTIVE của previousOwnerId khi Pet
     * đổi chủ sở hữu chính (ManagePetOwnership). Không tự kiểm quyền — caller (PetServiceImpl)
     * đã xác thực actor là Primary Owner trước khi gọi.
     */
    List<CaregiverDelegationResponse> revokeAllForOwnershipTransfer(UUID petId, UUID previousOwnerId);
}
