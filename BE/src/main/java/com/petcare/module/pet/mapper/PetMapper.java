package com.petcare.module.pet.mapper;

import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.entity.Pet;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PetMapper {
    PetResponse toResponse(Pet entity);
}
