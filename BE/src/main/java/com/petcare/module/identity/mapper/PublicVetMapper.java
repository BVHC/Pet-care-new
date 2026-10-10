package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.api.StaffDirectoryApi.PublicVetProfile;
import com.petcare.module.identity.dto.PublicVetResponse;

/**
 * {@code GET /api/public/vets}. Mọi trường đích khai báo tường minh và {@code unmappedTargetPolicy = ERROR}: thiếu một
 * trường của contract là lỗi biên dịch.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PublicVetMapper {

    @Mapping(target = "accountId", source = "vet.accountId")
    @Mapping(target = "fullName", source = "vet.fullName")
    @Mapping(target = "avatarUrl", source = "vet.avatarUrl")
    @Mapping(target = "specialty", source = "vet.specialty")
    @Mapping(target = "bio", source = "vet.bio")
    @Mapping(target = "branchId", source = "vet.branchId")
    @Mapping(target = "branchName", source = "branchName")
    PublicVetResponse toPublicVetResponse(PublicVetProfile vet, String branchName);
}
