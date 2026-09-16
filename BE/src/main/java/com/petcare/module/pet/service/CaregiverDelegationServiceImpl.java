package com.petcare.module.pet.service;

import com.petcare.module.auth.service.AuthService;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.exception.CaregiverInvitationConflictException;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.mapper.CaregiverDelegationMapper;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class CaregiverDelegationServiceImpl implements CaregiverDelegationService {

    private static final int TOKEN_BYTES = 32;

    private final PetRepository pets;
    private final PetCaregiverDelegationRepository delegations;
    private final CaregiverDelegationTransitionHandler transitions;
    private final PetAccessGuard accessGuard;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final OutboxEventRepository outbox;
    private final CaregiverDelegationMapper mapper;
    private final int invitationTtlDays;
    private final SecureRandom secureRandom = new SecureRandom();

    public CaregiverDelegationServiceImpl(PetRepository pets,
                                          PetCaregiverDelegationRepository delegations,
                                          CaregiverDelegationTransitionHandler transitions,
                                          PetAccessGuard accessGuard,
                                          AuthService authService,
                                          NotificationService notificationService,
                                          OutboxEventRepository outbox,
                                          CaregiverDelegationMapper mapper,
                                          @Value("${app.caregiver.invitation-ttl-days:7}") int invitationTtlDays) {
        this.pets = pets;
        this.delegations = delegations;
        this.transitions = transitions;
        this.accessGuard = accessGuard;
        this.authService = authService;
        this.notificationService = notificationService;
        this.outbox = outbox;
        this.mapper = mapper;
        this.invitationTtlDays = invitationTtlDays;
    }

    @Override
    @Transactional
    public CaregiverInvitationOutcome inviteCaregiver(UUID ownerUserId, UUID petId,
                                                      InviteCaregiverRequest req) {
        Pet pet = pets.findById(petId).orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        accessGuard.requirePrimaryOwner(ownerUserId, pet); // RULE-04-04

        String rawToken = generateRawToken();
        Optional<UUID> caregiverUserId = authService.findUserIdByActiveAccountEmail(req.caregiverEmail());

        PetCaregiverDelegation delegation = new PetCaregiverDelegation();
        delegation.setPetId(petId);
        delegation.setPrimaryOwnerId(ownerUserId);
        delegation.setCaregiverUserId(caregiverUserId.orElse(null));
        delegation.setCaregiverEmail(req.caregiverEmail());
        delegation.setCaregiverPhone(req.caregiverPhone());
        delegation.setInvitationTokenHash(sha256Hex(rawToken));
        delegation.setStatus(CaregiverStatus.INVITED);
        delegation.setExpiresAt(LocalDateTime.now().plusDays(invitationTtlDays)); // RULE-04-05
        delegation.setValidUntil(req.validUntil());

        try {
            delegation = delegations.saveAndFlush(delegation);
        } catch (DataIntegrityViolationException ex) {
            throw new CaregiverInvitationConflictException(petId, req.caregiverEmail());
        }

        writeOutbox(petId, "CaregiverInvited", delegation.getId());

        UUID taskId = null;
        if (caregiverUserId.isPresent()) {
            taskId = notificationService.enqueue(caregiverUserId.get(), NotificationChannel.EMAIL,
                    "CaregiverInvited",
                    "Bạn được mời chăm sóc thú cưng. Mã lời mời: " + rawToken);
        }

        CaregiverInvitationResponse response = new CaregiverInvitationResponse(
                delegation.getId(), petId, delegation.getCaregiverEmail(),
                delegation.getStatus().name(), delegation.getExpiresAt(), delegation.getValidUntil(),
                caregiverUserId.isPresent() ? null : rawToken); // spec D-02

        return new CaregiverInvitationOutcome(response, taskId, req.caregiverEmail());
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse acceptCaregiverInvitation(UUID actorUserId, String rawToken) {
        throw new UnsupportedOperationException("Task 9 chưa cài đặt");
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse rejectCaregiverInvitation(UUID actorUserId, String rawToken) {
        throw new UnsupportedOperationException("Task 9 chưa cài đặt");
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse revokeCaregiver(UUID ownerUserId, UUID petId,
                                                       RevokeCaregiverRequest req) {
        throw new UnsupportedOperationException("Task 10 chưa cài đặt");
    }

    void writeOutbox(UUID petId, String eventType, UUID delegationId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Pet");
        event.setAggregateId(petId.toString());
        event.setEventType(eventType);
        event.setPayload("{\"petId\":\"" + petId + "\",\"delegationId\":\"" + delegationId + "\"}");
        outbox.save(event);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Chỉ lưu hash, không bao giờ lưu raw token — precedent RefreshTokenService. */
    String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
