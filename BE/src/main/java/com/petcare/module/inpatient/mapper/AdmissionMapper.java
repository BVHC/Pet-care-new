package com.petcare.module.inpatient.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmissionSummaryResponse;
import com.petcare.module.inpatient.entity.Admission;

/**
 * Ca nội trú (inpatient-v1 #1–#3; docs/adr/0149 mục 17): chỉ chiều Entity → DTO và <b>không chạm DB</b>. Tên thú, tên
 * dịch vụ, mã chuồng (module khác) và số ngày / tiền tạm tính (tính bằng một {@code now} cho cả request) do service ghép
 * rồi truyền vào (khuôn {@code ShiftRequestMapper}). Mọi trường đích khai báo tường minh, {@code unmappedTargetPolicy =
 * ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AdmissionMapper {

    @Mapping(target = "admissionId", source = "admission.id")
    @Mapping(target = "code", source = "admission.code")
    @Mapping(target = "visitId", source = "admission.visitId")
    @Mapping(target = "petId", source = "admission.petId")
    @Mapping(target = "petName", source = "petName")
    @Mapping(target = "branchId", source = "admission.branchId")
    @Mapping(target = "boardingBookingId", source = "admission.boardingBookingId")
    @Mapping(target = "kennelId", source = "admission.kennelId")
    @Mapping(target = "serviceId", source = "admission.serviceId")
    @Mapping(target = "serviceName", source = "serviceName")
    @Mapping(target = "unitPrice", source = "admission.unitPrice")
    @Mapping(target = "status", source = "admission.status")
    @Mapping(target = "admittedAt", source = "admission.admittedAt")
    @Mapping(target = "billedDays", source = "billedDays")
    @Mapping(target = "estimatedAmount", source = "estimatedAmount")
    @Mapping(target = "dischargeOutcome", source = "admission.dischargeOutcome")
    @Mapping(target = "dischargeSummary", source = "admission.dischargeSummary")
    @Mapping(target = "dischargedAt", source = "admission.dischargedAt")
    @Mapping(target = "orderLineId", source = "admission.orderLineId")
    AdmissionResponse toResponse(Admission admission, String petName, String serviceName, int billedDays,
            long estimatedAmount);

    @Mapping(target = "admissionId", source = "admission.id")
    @Mapping(target = "code", source = "admission.code")
    @Mapping(target = "petName", source = "petName")
    @Mapping(target = "status", source = "admission.status")
    @Mapping(target = "admittedAt", source = "admission.admittedAt")
    @Mapping(target = "kennelCode", source = "kennelCode")
    @Mapping(target = "billedDays", source = "billedDays")
    @Mapping(target = "estimatedAmount", source = "estimatedAmount")
    AdmissionSummaryResponse toSummary(Admission admission, String petName, String kennelCode, int billedDays,
            long estimatedAmount);
}
