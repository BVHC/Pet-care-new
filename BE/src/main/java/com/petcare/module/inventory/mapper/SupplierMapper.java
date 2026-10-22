package com.petcare.module.inventory.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inventory.dto.SupplierResponse;
import com.petcare.module.inventory.entity.Supplier;

/** Nhà cung cấp (inventory-v1 {@code Supplier}); mọi trường đích khai báo tường minh. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SupplierMapper {

    @Mapping(target = "supplierId", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "address", source = "address")
    @Mapping(target = "isActive", source = "active")
    SupplierResponse toResponse(Supplier supplier);

    List<SupplierResponse> toResponses(List<Supplier> suppliers);
}
