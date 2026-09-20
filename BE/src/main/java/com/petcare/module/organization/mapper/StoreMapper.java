package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.entity.Store;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StoreMapper {

    @Mapping(target = "storeId", source = "id")
    StoreResponse toResponse(Store store);
}
