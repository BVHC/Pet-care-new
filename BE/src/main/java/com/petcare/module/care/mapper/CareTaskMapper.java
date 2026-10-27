package com.petcare.module.care.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.care.dto.CareTaskResponse;
import com.petcare.module.care.entity.CareTask;

/**
 * Việc gọi điện của lễ tân (care-v1 #1–4; docs/adr/0080). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. Bốn giá trị ghép từ module khác
 * ({@code customerName}, {@code phoneToCall}, {@code petName}, {@code context}) do service tính rồi truyền vào, vì care
 * không được đọc bảng của module khác. Không trả {@code updatedAt}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CareTaskMapper {

    @Mapping(target = "careTaskId", source = "task.id")
    @Mapping(target = "type", source = "task.taskType")
    @Mapping(target = "status", source = "task.status")
    @Mapping(target = "branchId", source = "task.branchId")
    @Mapping(target = "customerId", source = "task.customerId")
    @Mapping(target = "customerName", source = "customerName")
    @Mapping(target = "phoneToCall", source = "phoneToCall")
    @Mapping(target = "petId", source = "task.petId")
    @Mapping(target = "petName", source = "petName")
    @Mapping(target = "vaccinationId", source = "task.vaccinationId")
    @Mapping(target = "medicalRecordVisitId", source = "task.medicalRecordId")
    @Mapping(target = "boardingBookingId", source = "task.boardingBookingId")
    @Mapping(target = "dueDate", source = "task.dueDate")
    @Mapping(target = "context", source = "context")
    @Mapping(target = "result", source = "task.result")
    @Mapping(target = "note", source = "task.note")
    @Mapping(target = "cancelReason", source = "task.cancelReason")
    @Mapping(target = "handledBy", source = "task.handledBy")
    @Mapping(target = "handledAt", source = "task.handledAt")
    @Mapping(target = "createdAt", source = "task.createdAt")
    CareTaskResponse toResponse(CareTask task, String customerName, String phoneToCall, String petName, String context);
}
