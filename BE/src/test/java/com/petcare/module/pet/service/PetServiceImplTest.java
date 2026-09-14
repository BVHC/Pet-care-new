package com.petcare.module.pet.service;

import com.petcare.module.iam.entity.User;
import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.UpdatePetRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.mapper.PetMapper;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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

    @InjectMocks
    private PetServiceImpl svc;

    @Test
    void create_setsOwnerToSelf_andEmitsPetAdded() {
        UUID me = UUID.randomUUID();
        when(users.findById(me)).thenReturn(new User(UUID.randomUUID(), "A"));
        when(pets.save(any(Pet.class))).thenAnswer(i -> {
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
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> svc.detail(UUID.randomUUID(), pet.getId()))
                .isInstanceOf(AccessDeniedScopeException.class);
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
}
