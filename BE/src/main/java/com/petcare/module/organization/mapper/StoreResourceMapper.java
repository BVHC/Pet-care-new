package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.StoreResourceListResponse;
import com.petcare.module.organization.dto.StoreResourceResponse;
import com.petcare.module.organization.entity.StoreResource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface StoreResourceMapper {

    @Mapping(target = "resourceId", source = "id")
    @Mapping(target = "isActive", source = "active")
    StoreResourceResponse toResponse(StoreResource entity);

    default StoreResourceListResponse toListResponse(List<StoreResource> entities) {
        return new StoreResourceListResponse(entities.stream().map(this::toResponse).toList());
    }
}
