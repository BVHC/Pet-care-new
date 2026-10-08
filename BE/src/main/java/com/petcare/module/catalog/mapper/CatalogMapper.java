package com.petcare.module.catalog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.petcare.module.catalog.dto.KennelTypeSpec;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.VaccinationProtocolResponse;
import com.petcare.module.catalog.dto.VaccineTypeResponse;
import com.petcare.module.catalog.entity.KennelType;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.ProductCategory;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.catalog.entity.VaccineType;

/** Entity → response của catalog-v1; chỉ đổi tên các trường khác nhau (id, và cờ {@code is*}). */
@Mapper(componentModel = "spring")
public interface CatalogMapper {

    @Mapping(target = "categoryId", source = "id")
    @Mapping(target = "isActive", source = "active")
    ProductCategoryResponse toResponse(ProductCategory category);

    @Mapping(target = "productId", source = "id")
    @Mapping(target = "isPrescription", source = "prescription")
    @Mapping(target = "isActive", source = "active")
    ProductResponse toResponse(Product product);

    @Mapping(target = "vaccineTypeId", source = "id")
    @Mapping(target = "isActive", source = "active")
    VaccineTypeResponse toResponse(VaccineType vaccineType);

    @Mapping(target = "protocolId", source = "id")
    @Mapping(target = "isActive", source = "active")
    VaccinationProtocolResponse toResponse(VaccinationProtocol protocol);

    KennelTypeSpec toSpec(KennelType kennelType);

    @Mapping(target = "serviceId", source = "service.id")
    @Mapping(target = "name", source = "service.name")
    @Mapping(target = "group", source = "service.group")
    @Mapping(target = "medicalType", source = "service.medicalType")
    @Mapping(target = "price", source = "service.price")
    @Mapping(target = "priceIsFrom", source = "service.priceIsFrom")
    @Mapping(target = "description", source = "service.description")
    @Mapping(target = "isActive", source = "service.active")
    @Mapping(target = "kennelType", source = "kennelType")
    ServiceResponse toResponse(Service service, KennelType kennelType);
}
