package com.petcare.module.inpatient.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.entity.AdmissionOrder;

/**
 * Y lệnh nội trú (inpatient-v1 #5–#8; docs/adr/0149 mục 26): chỉ chiều Entity → DTO và không chạm DB. Mọi trường đích khai
 * báo tường minh, {@code unmappedTargetPolicy = ERROR}. Cố ý không lộ {@code createdBy}, {@code doneBy}, mốc tạo / sửa.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AdmissionOrderMapper {

    @Mapping(target = "admissionOrderId", source = "id")
    @Mapping(target = "admissionId", source = "admissionId")
    @Mapping(target = "orderType", source = "orderType")
    @Mapping(target = "content", source = "content")
    @Mapping(target = "scheduledAt", source = "scheduledAt")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "prescriptionItemId", source = "prescriptionItemId")
    @Mapping(target = "doneAt", source = "doneAt")
    @Mapping(target = "doneNote", source = "doneNote")
    @Mapping(target = "cancelReason", source = "cancelReason")
    AdmissionOrderResponse toResponse(AdmissionOrder order);
}
