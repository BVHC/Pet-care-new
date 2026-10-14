package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.module.identity.entity.SystemConfig;

/**
 * Tham số [CFG] (identity-v1 #31, #32; UC10 — docs/adr/0036). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. Chỉ gọi trong transaction của
 * {@code SystemConfigEditService} (OSIV tắt, docs/adr/0020).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SystemConfigAdminMapper {

    @Mapping(target = "key", source = "configKey")
    @Mapping(target = "value", source = "value")
    @Mapping(target = "valueType", source = "valueType")
    @Mapping(target = "minValue", source = "minValue")
    @Mapping(target = "maxValue", source = "maxValue")
    @Mapping(target = "unit", source = "unit")
    @Mapping(target = "description", source = "description")
    SystemConfigResponse toResponse(SystemConfig config);
}
