package com.petcare.module.pet.mapper;

import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CaregiverDelegationMapper {

    CaregiverDelegationResponse toDelegationResponse(PetCaregiverDelegation entity);
}
