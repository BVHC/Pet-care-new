package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.OrganizationResponse;
import com.petcare.module.organization.entity.Organization;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrganizationMapper {

    @Mapping(target = "organizationId", source = "id")
    OrganizationResponse toResponse(Organization organization);
}
