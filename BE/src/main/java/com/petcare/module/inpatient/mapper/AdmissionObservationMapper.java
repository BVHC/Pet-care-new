package com.petcare.module.inpatient.mapper;

import java.time.Instant;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.AdmissionObservation;

/**
 * Mục theo dõi nội trú (inpatient-v1 #9–#10; docs/adr/0149 mục 31, 36): chỉ chiều Entity → DTO, không chạm DB. Tên người
 * ghi do service tra theo lô và truyền vào. {@code createdAt} cũng truyền vào (không lấy từ entity) vì entity giữ nano
 * giây còn cột chỉ giữ micro giây: service đọc lại giá trị đã lưu. Mọi trường đích khai báo tường minh,
 * {@code unmappedTargetPolicy = ERROR}. {@code Short} → {@code Integer} do MapStruct tự đổi.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AdmissionObservationMapper {

    @Mapping(target = "observationId", source = "observation.id")
    @Mapping(target = "admissionId", source = "observation.admissionId")
    @Mapping(target = "recordedByName", source = "recordedByName")
    @Mapping(target = "temperatureC", source = "observation.temperatureC")
    @Mapping(target = "heartRate", source = "observation.heartRate")
    @Mapping(target = "respiratoryRate", source = "observation.respiratoryRate")
    @Mapping(target = "weightKg", source = "observation.weightKg")
    @Mapping(target = "appetite", source = "observation.appetite")
    @Mapping(target = "note", source = "observation.note")
    @Mapping(target = "abnormal", source = "observation.abnormal")
    @Mapping(target = "createdAt", source = "createdAt")
    ObservationResponse toResponse(AdmissionObservation observation, String recordedByName, Instant createdAt);
}
