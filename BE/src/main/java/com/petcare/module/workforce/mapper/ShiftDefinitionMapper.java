package com.petcare.module.workforce.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.workforce.dto.ShiftDefinitionAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftDefinitionResponse;
import com.petcare.module.workforce.entity.ShiftDefinition;

/**
 * Ca làm việc (workforce-v1 #1–3; docs/adr/0138): chỉ chiều Entity → DTO. Chiều ngược lại cố ý không có: entity không có
 * setter, bất biến nằm ở factory {@code create} / {@code rename} / {@code changeTimes}. Mọi trường đích khai báo
 * tường minh và {@code unmappedTargetPolicy = ERROR}; không trả {@code createdAt} / {@code updatedAt}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShiftDefinitionMapper {

    @Mapping(target = "shiftDefinitionId", source = "id")
    @Mapping(target = "branchId", source = "branchId")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "startTime", source = "startTime")
    @Mapping(target = "endTime", source = "endTime")
    @Mapping(target = "active", source = "active")
    ShiftDefinitionResponse toResponse(ShiftDefinition definition);

    List<ShiftDefinitionResponse> toResponses(List<ShiftDefinition> definitions);

    @Mapping(target = "name", source = "name")
    @Mapping(target = "startTime", source = "startTime", dateFormat = "HH:mm")
    @Mapping(target = "endTime", source = "endTime", dateFormat = "HH:mm")
    @Mapping(target = "active", source = "active")
    ShiftDefinitionAuditSnapshot toSnapshot(ShiftDefinition definition);
}
