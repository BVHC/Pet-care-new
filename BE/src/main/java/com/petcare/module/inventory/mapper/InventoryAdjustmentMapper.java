package com.petcare.module.inventory.mapper;

import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.entity.InventoryAdjustment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InventoryAdjustmentMapper {

    @Mapping(target = "adjustmentId", source = "id")
    InventoryAdjustmentResponse toResponse(InventoryAdjustment adjustment);
}
