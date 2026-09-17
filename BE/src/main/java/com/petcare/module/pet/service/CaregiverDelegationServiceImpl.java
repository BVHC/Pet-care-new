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
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.mapper.CaregiverDelegationMapper;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.exception.BusinessRuleViolationException;
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
import java.util.List;
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
        return respondToInvitation(actorUserId, rawToken, CaregiverStatus.ACTIVE,
                "CaregiverInvitationAccepted");
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse rejectCaregiverInvitation(UUID actorUserId, String rawToken) {
        return respondToInvitation(actorUserId, rawToken, CaregiverStatus.REJECTED,
                "CaregiverInvitationRejected");
    }

    private CaregiverDelegationResponse respondToInvitation(UUID actorUserId, String rawToken,
                                                            CaregiverStatus target, String eventType) {
        PetCaregiverDelegation delegation = delegations.findByInvitationTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("CaregiverInvitation", "token"));

        // RULE-04-05 — tự kiểm hạn, không tin job (spec D-08).
        if (delegation.getStatus() == CaregiverStatus.INVITED
                && !delegation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessRuleViolationException("RULE-04-05", "Lời mời đã hết hạn (RULE-04-05)");
        }

        // A-03: đã gán thì bắt buộc khớp; A-02: chưa gán thì người cầm token nhận ủy quyền.
        if (delegation.getCaregiverUserId() != null
                && !delegation.getCaregiverUserId().equals(actorUserId)) {
            throw new UnauthorizedDelegatedActionException("INVITED_CAREGIVER", "OTHER_USER");
        }

        // Spec D-10 — idempotent khi đã ở đúng đích và đúng chủ thể.
        if (delegation.getStatus() == target
                && actorUserId.equals(delegation.getCaregiverUserId())) {
            return mapper.toDelegationResponse(delegation);
        }

        transitions.validateTransition(delegation.getStatus(), target);
        if (delegation.getCaregiverUserId() == null) {
            delegation.setCaregiverUserId(actorUserId);
        }
        delegation.setStatus(target);
        writeOutbox(delegation.getPetId(), eventType, delegation.getId());
        return mapper.toDelegationResponse(delegation);
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse revokeCaregiver(UUID ownerUserId, UUID petId,
                                                        RevokeCaregiverRequest req) {
        Pet pet = pets.findById(petId).orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        accessGuard.requirePrimaryOwner(ownerUserId, pet); // RULE-04-04

        // INVITED + ACTIVE là bản ghi "còn sống"; REVOKED để phục vụ idempotency (D-10).
        PetCaregiverDelegation delegation = delegations
                .findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(petId,
                        req.caregiverEmail(),
                        List.of(CaregiverStatus.INVITED, CaregiverStatus.ACTIVE, CaregiverStatus.REVOKED))
                .orElseThrow(() -> new ResourceNotFoundException("CaregiverDelegation", req.caregiverEmail()));

        if (delegation.getStatus() == CaregiverStatus.REVOKED) { // spec D-10
            return mapper.toDelegationResponse(delegation);
        }

        transitions.validateTransition(delegation.getStatus(), CaregiverStatus.REVOKED);
        delegation.setStatus(CaregiverStatus.REVOKED); // RULE-04-08 — hiệu lực tức thì
        writeOutbox(petId, "CaregiverRevoked", delegation.getId());
        return mapper.toDelegationResponse(delegation);
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
