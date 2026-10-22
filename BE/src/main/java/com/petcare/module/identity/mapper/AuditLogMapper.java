package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.dto.AuditLogEntryResponse;
import com.petcare.module.identity.repository.AuditLogRow;

/**
 * Nhật ký audit (identity-v1 #36; UC11 — docs/adr/0038 mục 7). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. Không join, không làm giàu dữ
 * liệu: đúng thứ đã ghi.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuditLogMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "actorAccountId", source = "actorAccountId")
    @Mapping(target = "actorEmail", source = "actorEmail")
    @Mapping(target = "action", source = "action")
    @Mapping(target = "entityType", source = "entityType")
    @Mapping(target = "entityId", source = "entityId")
    @Mapping(target = "beforeData", source = "beforeData")
    @Mapping(target = "afterData", source = "afterData")
    @Mapping(target = "reason", source = "reason")
    @Mapping(target = "ipAddress", source = "ipAddress")
    @Mapping(target = "createdAt", source = "createdAt")
    AuditLogEntryResponse toResponse(AuditLogRow row);
}
