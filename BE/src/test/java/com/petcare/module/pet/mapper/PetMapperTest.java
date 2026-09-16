package com.petcare.module.pet.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.entity.Pet;
import com.petcare.platform.enums.PetStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PetMapperTest {
    private final PetMapper mapper = Mappers.getMapper(PetMapper.class);

    @Test
    void toResponse_mapsIdAndOwner() {
        Pet pet = new Pet(UUID.randomUUID(), "Milo", "DOG");
        PetResponse res = mapper.toResponse(pet);
        assertThat(res.name()).isEqualTo("Milo");
        assertThat(res.status()).isEqualTo(PetStatus.ACTIVE);
    }
}
