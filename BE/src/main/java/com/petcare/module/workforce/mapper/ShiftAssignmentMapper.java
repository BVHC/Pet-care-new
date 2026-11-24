package com.petcare.module.workforce.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftDefinition;

/**
 * Phân ca (workforce-v1 #4–5; docs/adr/0149): chỉ chiều Entity → DTO và <b>không chạm DB</b>. Tên nhân viên, ca và yêu cầu
 * {@code PENDING} do service ghép theo lô rồi truyền vào (khuôn {@code CatalogMapper}, nhiều nguồn). Mọi trường đích khai
 * báo tường minh và {@code unmappedTargetPolicy = ERROR}; không trả {@code createdAt} / {@code updatedAt} /
 * {@code statusChanged*}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShiftAssignmentMapper {

    @Mapping(target = "shiftAssignmentId", source = "assignment.id")
    @Mapping(target = "branchId", source = "assignment.branchId")
    @Mapping(target = "staffId", source = "assignment.staffId")
    @Mapping(target = "staffName", source = "staffName")
    @Mapping(target = "shiftDefinitionId", source = "assignment.shiftDefinitionId")
    @Mapping(target = "shiftName", source = "definition.name")
    @Mapping(target = "startTime", source = "definition.startTime")
    @Mapping(target = "endTime", source = "definition.endTime")
    @Mapping(target = "workDate", source = "assignment.workDate")
    @Mapping(target = "status", source = "assignment.status")
    @Mapping(target = "source", source = "assignment.source")
    @Mapping(target = "reason", source = "assignment.reason")
    @Mapping(target = "pendingRequestId", source = "pendingRequestId")
    ShiftAssignmentResponse toResponse(ShiftAssignment assignment, String staffName, ShiftDefinition definition,
            Long pendingRequestId);

    @Mapping(target = "staffId", source = "staffId")
    @Mapping(target = "shiftDefinitionId", source = "shiftDefinitionId")
    @Mapping(target = "workDate", source = "workDate", dateFormat = "yyyy-MM-dd")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "reason", source = "reason")
    ShiftAssignmentAuditSnapshot toSnapshot(ShiftAssignment assignment);
}
