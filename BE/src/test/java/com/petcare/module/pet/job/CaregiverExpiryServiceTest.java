package com.petcare.module.pet.job;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.platform.enums.CaregiverStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaregiverExpiryServiceTest {

    @Mock PetCaregiverDelegationRepository delegations;
    @Mock OutboxEventRepository outbox;

    private CaregiverExpiryService svc;

    @BeforeEach
    void setUp() {
        svc = new CaregiverExpiryService(delegations, new CaregiverDelegationTransitionHandler(), outbox);
    }

    private PetCaregiverDelegation delegation(CaregiverStatus status) {
        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setId(UUID.randomUUID());
        d.setPetId(UUID.randomUUID());
        d.setStatus(status);
        return d;
    }

    @Test
    void processInvitationExpiry_marksInvitedAsExpiredAndEmitsEvent() {
        PetCaregiverDelegation d = delegation(CaregiverStatus.INVITED);
        when(delegations.findExpiredInvitations(any(LocalDateTime.class))).thenReturn(List.of(d));

        int updated = svc.processInvitationExpiry();

        assertThat(updated).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.EXPIRED);
        verify(outbox, times(1)).save(any());
    }

    @Test
    void processDelegationExpiry_marksActiveAsExpired() {
        PetCaregiverDelegation d = delegation(CaregiverStatus.ACTIVE);
        when(delegations.findExpiredDelegations(any(LocalDateTime.class))).thenReturn(List.of(d));

        int updated = svc.processDelegationExpiry();

        assertThat(updated).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.EXPIRED);
    }

    /** Spec D-03: validUntil NULL = vô thời hạn, repository không trả về, job không đụng. */
    @Test
    void processDelegationExpiry_noRows_doesNothing() {
        when(delegations.findExpiredDelegations(any(LocalDateTime.class))).thenReturn(List.of());

        assertThat(svc.processDelegationExpiry()).isZero();
        verify(outbox, times(0)).save(any());
    }
}
