package com.petcare.module.pet.service;

import com.petcare.module.iam.entity.User;
import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.UpdatePetRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.mapper.PetMapper;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PetServiceImplTest {

    @Mock
    private PetRepository pets;

    @Mock
    private UserProvisioningService users;

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private PetMapper mapper;

    @Mock
    private PetAccessGuard accessGuard;

    @InjectMocks
    private PetServiceImpl svc;

    @Test
    void create_setsOwnerToSelf_andEmitsPetAdded() {
        UUID me = UUID.randomUUID();
        when(users.findById(me)).thenReturn(new User(UUID.randomUUID(), "A"));
        when(pets.saveAndFlush(any(Pet.class))).thenAnswer(i -> {
            Pet p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
        when(mapper.toResponse(any(Pet.class))).thenAnswer(i -> {
            Pet p = i.getArgument(0);
            return new PetResponse(p.getId(), p.getOwnerId(), p.getName(), p.getSpecies(),
                    p.getBreed(), p.getGender(), p.getDateOfBirth(), p.getWeightKg(),
                    p.getMicrochipNumber(), p.getAvatarUrl(), p.getStatus());
        });

        PetResponse out = svc.create(me,
                new CreatePetRequest("Milo", "DOG", null, "MALE", null, null, null, null));

        assertThat(out.ownerId()).isEqualTo(me);
        assertThat(out.name()).isEqualTo("Milo");
        verify(outbox).save(argThat(e -> "PetAdded".equals(e.getEventType())));
    }

    @Test
    void detail_otherOwnersPet_forbidden() {
        Pet pet = new Pet(UUID.randomUUID(), "Milo", "DOG");
        pet.setId(UUID.randomUUID());
        UUID stranger = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        // detail() ủy quyền kiểm tra cho PetAccessGuard — guard ném subclass của
        // AccessDeniedScopeException nên assertion cũ giữ nguyên.
        doThrow(new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED"))
                .when(accessGuard).requireCanViewPet(stranger, pet);

        assertThatThrownBy(() -> svc.detail(stranger, pet.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void detail_activeCaregiver_allowed() {
        UUID caregiver = UUID.randomUUID();
        Pet pet = new Pet(UUID.randomUUID(), "Mun", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(mapper.toResponse(pet)).thenReturn(mock(PetResponse.class));

        svc.detail(caregiver, pet.getId());

        verify(accessGuard).requireCanViewPet(caregiver, pet);
    }

    @Test
    void detail_guardRejects_propagates403() {
        UUID stranger = UUID.randomUUID();
        Pet pet = new Pet(UUID.randomUUID(), "Mun", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED"))
                .when(accessGuard).requireCanViewPet(stranger, pet);

        assertThatThrownBy(() -> svc.detail(stranger, pet.getId()))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void list_usesAccessibleQuery_notOwnerOnly() {
        UUID me = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        when(pets.findAccessibleBy(eq(me), any(LocalDateTime.class), eq(pageable)))
                .thenReturn(Page.empty());

        svc.list(me, pageable);

        verify(pets).findAccessibleBy(eq(me), any(LocalDateTime.class), eq(pageable));
        verify(pets, never()).findByOwnerId(eq(me), any(Pageable.class));
    }

    @Test
    void update_nonOwner_forbidden() {
        Pet pet = new Pet(UUID.randomUUID(), "Milo", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> svc.update(UUID.randomUUID(), pet.getId(),
                new UpdatePetRequest("Max", null, null, null, null, null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void update_optimisticLockAtFlush_mapsTo409() {
        UUID me = UUID.randomUUID();
        Pet pet = new Pet(me, "Milo", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(pets.saveAndFlush(any(Pet.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Pet.class, pet.getId()));

        assertThatThrownBy(() -> svc.update(me, pet.getId(),
                new UpdatePetRequest("Max", null, null, null, null, null, null, null)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }
}
