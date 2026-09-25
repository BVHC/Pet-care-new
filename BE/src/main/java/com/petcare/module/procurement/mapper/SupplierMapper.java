package com.petcare.module.procurement.mapper;

import com.petcare.module.procurement.dto.SupplierResponse;
import com.petcare.module.procurement.entity.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SupplierMapper {

    @Mapping(target = "supplierId", source = "id")
    SupplierResponse toResponse(Supplier supplier);
}
