package com.petcare.module.pet.service;

import com.petcare.module.auth.service.AuthService;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.exception.CaregiverInvitationConflictException;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.mapper.CaregiverDelegationMapper;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaregiverDelegationServiceImplTest {

    @Mock PetRepository pets;
    @Mock PetCaregiverDelegationRepository delegations;
    @Mock PetAccessGuard accessGuard;
    @Mock AuthService authService;
    @Mock NotificationService notificationService;
    @Mock OutboxEventRepository outbox;
    @Mock CaregiverDelegationMapper mapper;

    private CaregiverDelegationServiceImpl svc;

    private final UUID owner = UUID.randomUUID();
    private Pet pet;

    @BeforeEach
    void setUp() {
        svc = new CaregiverDelegationServiceImpl(pets, delegations,
                new CaregiverDelegationTransitionHandler(), accessGuard, authService,
                notificationService, outbox, mapper, 7);
        pet = new Pet(owner, "Mun", "DOG");
        pet.setId(UUID.randomUUID());
    }

    @Test
    void inviteCaregiver_existingAccount_enqueuesEmailAndHidesToken() {
        UUID caregiverUserId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("cg@example.com"))
                .thenReturn(Optional.of(caregiverUserId));
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });
        when(notificationService.enqueue(any(), any(NotificationChannel.class), anyString(), anyString()))
                .thenReturn(taskId);

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null));

        assertThat(outcome.response().invitationToken()).isNull();
        assertThat(outcome.notificationTaskId()).isEqualTo(taskId);
        assertThat(outcome.toAddress()).isEqualTo("cg@example.com");
        verify(outbox).save(any());
    }

    @Test
    void inviteCaregiver_noAccount_returnsTokenOnceAndSkipsEmail() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("new@example.com")).thenReturn(Optional.empty());
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("new@example.com", null, null));

        assertThat(outcome.response().invitationToken()).isNotBlank();
        assertThat(outcome.notificationTaskId()).isNull();
        verify(notificationService, never()).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void inviteCaregiver_notPrimaryOwner_forbidden() {
        UUID stranger = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER"))
                .when(accessGuard).requirePrimaryOwner(stranger, pet);

        assertThatThrownBy(() -> svc.inviteCaregiver(stranger, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null)))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void inviteCaregiver_duplicateOutstanding_conflict409() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("cg@example.com")).thenReturn(Optional.empty());
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenThrow(new DataIntegrityViolationException("uq_pcd_outstanding"));

        assertThatThrownBy(() -> svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null)))
                .isInstanceOf(CaregiverInvitationConflictException.class);
    }

    @Test
    void inviteCaregiver_storesHashNotRawToken() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("new@example.com")).thenReturn(Optional.empty());
        ArgumentCaptor<PetCaregiverDelegation> captor =
                ArgumentCaptor.forClass(PetCaregiverDelegation.class);
        when(delegations.saveAndFlush(captor.capture()))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("new@example.com", null, null));

        assertThat(captor.getValue().getInvitationTokenHash())
                .isNotEqualTo(outcome.response().invitationToken())
                .hasSize(64); // SHA-256 hex
        assertThat(captor.getValue().getStatus()).isEqualTo(CaregiverStatus.INVITED);
    }

    private PetCaregiverDelegation invited(UUID caregiverUserId, LocalDateTime expiresAt) {
        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setId(UUID.randomUUID());
        d.setPetId(pet.getId());
        d.setPrimaryOwnerId(owner);
        d.setCaregiverUserId(caregiverUserId);
        d.setCaregiverEmail("cg@example.com");
        d.setInvitationTokenHash(svc.sha256Hex("raw-token"));
        d.setStatus(CaregiverStatus.INVITED);
        d.setExpiresAt(expiresAt);
        return d;
    }

    @Test
    void acceptCaregiverInvitation_boundUserAccepts_becomesActive() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
        verify(outbox).save(any());
    }

    @Test
    void acceptCaregiverInvitation_unboundInvitation_bindsAcceptingUser() {
        UUID whoever = UUID.randomUUID();
        PetCaregiverDelegation d = invited(null, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(whoever, "raw-token");

        assertThat(d.getCaregiverUserId()).isEqualTo(whoever);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
    }

    @Test
    void acceptCaregiverInvitation_wrongUser_forbidden() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(UUID.randomUUID(), "raw-token"))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void acceptCaregiverInvitation_unknownToken_notFound() {
        when(delegations.findByInvitationTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(UUID.randomUUID(), "nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Spec D-08: không tin cron, tự kiểm expires_at. */
    @Test
    void acceptCaregiverInvitation_pastExpiry_businessRuleViolation() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().minusMinutes(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(caregiver, "raw-token"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RULE-04-05");
    }

    /** Spec D-10: lặp lại accept bởi đúng người thì 200, không ghi event lần hai. */
    @Test
    void acceptCaregiverInvitation_alreadyActiveSameUser_isIdempotent() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.ACTIVE);
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
        verify(outbox, never()).save(any());
    }

    @Test
    void acceptCaregiverInvitation_alreadyRevoked_invalidTransition() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.REVOKED);
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(caregiver, "raw-token"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectCaregiverInvitation_becomesRejected() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.rejectCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REJECTED);
    }

    @Test
    void revokeCaregiver_activeDelegation_becomesRevoked() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.ACTIVE);
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
        verify(outbox).save(any());
    }

    /** Spec D-04 — chủ hủy lời mời đang treo. */
    @Test
    void revokeCaregiver_pendingInvitation_becomesRevoked() {
        PetCaregiverDelegation d = invited(null, LocalDateTime.now().plusDays(1));
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
    }

    /** Spec D-10 — revoke lần hai bởi owner trả 200, không ghi event lần hai. */
    @Test
    void revokeCaregiver_alreadyRevoked_isIdempotent() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.REVOKED);
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        verify(outbox, never()).save(any());
    }

    @Test
    void revokeCaregiver_notPrimaryOwner_forbidden() {
        UUID stranger = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER"))
                .when(accessGuard).requirePrimaryOwner(stranger, pet);

        assertThatThrownBy(() -> svc.revokeCaregiver(stranger, pet.getId(),
                new RevokeCaregiverRequest("cg@example.com")))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void revokeCaregiver_noDelegationForEmail_notFound() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.revokeCaregiver(owner, pet.getId(),
                new RevokeCaregiverRequest("cg@example.com")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** RULE-04-10 — thu hồi hàng loạt khi ManagePetOwnership. */
    @Test
    void revokeAllForOwnershipTransfer_revokesActiveAndInvited_forOldOwnerOnly() {
        PetCaregiverDelegation active = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        active.setStatus(CaregiverStatus.ACTIVE);
        PetCaregiverDelegation stillInvited = invited(null, LocalDateTime.now().plusDays(1));
        when(delegations.findByPetIdAndStatusIn(pet.getId(),
                List.of(CaregiverStatus.INVITED, CaregiverStatus.ACTIVE)))
                .thenReturn(List.of(active, stillInvited));

        List<CaregiverDelegationResponse> out = svc.revokeAllForOwnershipTransfer(pet.getId(), owner);

        assertThat(active.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
        assertThat(stillInvited.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
        assertThat(out).hasSize(2);
        verify(outbox, times(2)).save(any());
    }

    @Test
    void revokeAllForOwnershipTransfer_onlyTargetsGivenPreviousOwnerId() {
        PetCaregiverDelegation otherOwnersDelegation = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        otherOwnersDelegation.setPrimaryOwnerId(UUID.randomUUID()); // chủ khác, không phải `owner`
        when(delegations.findByPetIdAndStatusIn(pet.getId(),
                List.of(CaregiverStatus.INVITED, CaregiverStatus.ACTIVE)))
                .thenReturn(List.of(otherOwnersDelegation));

        List<CaregiverDelegationResponse> out = svc.revokeAllForOwnershipTransfer(pet.getId(), owner);

        assertThat(otherOwnersDelegation.getStatus()).isEqualTo(CaregiverStatus.INVITED);
        assertThat(out).isEmpty();
        verify(outbox, never()).save(any());
    }

    @Test
    void revokeAllForOwnershipTransfer_noLiveDelegations_returnsEmptyNoOutbox() {
        when(delegations.findByPetIdAndStatusIn(pet.getId(),
                List.of(CaregiverStatus.INVITED, CaregiverStatus.ACTIVE)))
                .thenReturn(List.of());

        List<CaregiverDelegationResponse> out = svc.revokeAllForOwnershipTransfer(pet.getId(), owner);

        assertThat(out).isEmpty();
        verify(outbox, never()).save(any());
    }
}
