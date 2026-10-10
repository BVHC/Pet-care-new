package com.petcare.module.customer.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.dto.AddressResponse;
import com.petcare.module.customer.entity.Address;

/**
 * Sổ địa chỉ (customer-v1 {@code Address}). {@code isDefault} lấy từ field {@code defaultAddress} (cột {@code is_default}),
 * cùng cách {@code LoginMapper} map {@code isLocked} từ {@code locked}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AddressMapper {

    @Mapping(target = "addressId", source = "id")
    @Mapping(target = "receiverName", source = "receiverName")
    @Mapping(target = "receiverPhone", source = "receiverPhone")
    @Mapping(target = "addressLine", source = "addressLine")
    @Mapping(target = "ward", source = "ward")
    @Mapping(target = "province", source = "province")
    @Mapping(target = "isDefault", source = "defaultAddress")
    AddressResponse toResponse(Address address);

    List<AddressResponse> toResponses(List<Address> addresses);
}
