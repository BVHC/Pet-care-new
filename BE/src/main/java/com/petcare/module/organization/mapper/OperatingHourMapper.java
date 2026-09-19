package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.OperatingHourItem;
import com.petcare.module.organization.dto.OperatingHoursResponse;
import com.petcare.module.organization.entity.OperatingHour;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface OperatingHourMapper {

    @Mapping(target = "isClosed", source = "closed")
    OperatingHourItem toItem(OperatingHour entity);

    default OperatingHoursResponse toResponse(UUID storeId, UUID organizationId, List<OperatingHour> entities) {
        return new OperatingHoursResponse(storeId, organizationId, entities.stream().map(this::toItem).toList());
    }
}
