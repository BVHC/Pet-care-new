package com.petcare.module.inventory.mapper;

import com.petcare.module.inventory.dto.InventoryBatchResponse;
import com.petcare.module.inventory.entity.InventoryBatch;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDate;

@Mapper(componentModel = "spring")
public interface InventoryBatchMapper {

    @Mapping(target = "expired", expression = "java(batch.isExpired(today))")
    InventoryBatchResponse toResponse(InventoryBatch batch, LocalDate today);
}
