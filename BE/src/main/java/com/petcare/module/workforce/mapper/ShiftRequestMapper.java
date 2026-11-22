package com.petcare.module.workforce.mapper;

import java.time.LocalDate;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.workforce.dto.ShiftRequestAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftRequest;

/**
 * Yêu cầu nghỉ / đổi ca (workforce-v1 #9–11; docs/adr/0138 bước 6): chỉ chiều Entity → DTO và <b>không chạm DB</b>. Tên
 * người gửi, ngày làm và tên ca do service ghép theo lô rồi truyền vào (khuôn {@code ShiftAssignmentMapper}). Mọi trường
 * đích khai báo tường minh, kể cả {@code id} / {@code createdAt} (nhiều tham số nên MapStruct không tự suy ra nguồn), và
 * {@code unmappedTargetPolicy = ERROR}. {@code toSnapshot} chỉ cho audit của từ chối (#14) và duyệt (#13); #9, #11 không audit (BR-NS-10).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShiftRequestMapper {

    @Mapping(target = "shiftRequestId", source = "request.id")
    @Mapping(target = "shiftAssignmentId", source = "request.assignmentId")
    @Mapping(target = "requesterId", source = "request.requesterId")
    @Mapping(target = "requesterName", source = "requesterName")
    @Mapping(target = "workDate", source = "workDate")
    @Mapping(target = "currentShiftName", source = "currentShiftName")
    @Mapping(target = "type", source = "request.requestType")
    @Mapping(target = "targetShiftDefinitionId", source = "request.targetShiftDefinitionId")
    @Mapping(target = "targetShiftName", source = "targetShiftName")
    @Mapping(target = "reason", source = "request.reason")
    @Mapping(target = "status", source = "request.status")
    @Mapping(target = "decidedBy", source = "request.decidedBy")
    @Mapping(target = "decisionNote", source = "request.decisionNote")
    @Mapping(target = "closedAt", source = "request.closedAt")
    @Mapping(target = "createdAt", source = "request.createdAt")
    ShiftRequestResponse toResponse(ShiftRequest request, String requesterName, LocalDate workDate,
            String currentShiftName, String targetShiftName);

    /** Ảnh chụp cho audit của từ chối (#14) và duyệt (#13) (BR-NS-10); enum thành chuỗi bằng {@code name()}. */
    @Mapping(target = "shiftAssignmentId", source = "assignmentId")
    @Mapping(target = "requesterId", source = "requesterId")
    @Mapping(target = "type", source = "requestType")
    @Mapping(target = "targetShiftDefinitionId", source = "targetShiftDefinitionId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "decidedBy", source = "decidedBy")
    @Mapping(target = "decisionNote", source = "decisionNote")
    ShiftRequestAuditSnapshot toSnapshot(ShiftRequest request);
}
