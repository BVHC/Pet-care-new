package com.petcare.module.fees.mapper;

import org.mapstruct.Mapper;

import com.petcare.module.fees.dto.ShippingFeeConfigResponse;
import com.petcare.module.fees.entity.ShippingFeeConfig;

@Mapper(componentModel = "spring")
public interface ShippingFeeConfigMapper {

    ShippingFeeConfigResponse toResponse(ShippingFeeConfig config);
}
