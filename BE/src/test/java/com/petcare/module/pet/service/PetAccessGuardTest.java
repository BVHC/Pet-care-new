package com.petcare.module.pet.service;

import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PetAccessGuardTest {

    @Mock PetCaregiverDelegationRepository delegations;
    @InjectMocks PetAccessGuard guard;

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private Pet pet;

    @BeforeEach
    void setUp() {
        pet = new Pet(owner, "Mun", "DOG");
        pet.setId(UUID.randomUUID());
    }

    @Test
    void requireCanViewPet_ownerPasses_withoutTouchingDelegations() {
        assertThatCode(() -> guard.requireCanViewPet(owner, pet)).doesNotThrowAnyException();
    }

    @Test
    void requireCanViewPet_activeCaregiverPasses() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThatCode(() -> guard.requireCanViewPet(other, pet)).doesNotThrowAnyException();
    }

    @Test
    void requireCanViewPet_strangerForbidden() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireCanViewPet(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    /**
     * Spec D-08: guard tự kiểm thời hạn. Repository trả false cho delegation ACTIVE
     * đã quá valid_until, nên guard phải chặn — kể cả khi job chưa kịp quét.
     */
    @Test
    void requireCanViewPet_activeButExpiredDelegationForbidden() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireCanViewPet(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void requirePrimaryOwner_ownerPasses() {
        assertThatCode(() -> guard.requirePrimaryOwner(owner, pet)).doesNotThrowAnyException();
    }

    @Test
    void requirePrimaryOwner_activeCaregiverStillForbidden() {
        assertThatThrownBy(() -> guard.requirePrimaryOwner(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }
}
