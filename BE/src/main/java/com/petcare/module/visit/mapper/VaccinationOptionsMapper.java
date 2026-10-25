package com.petcare.module.visit.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.visit.dto.ProtocolDoseOptionResponse;
import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.dto.VaccineProductOptionResponse;

/**
 * visit-v1 #18 (docs/adr/0073). Tham số vô hướng hoặc DTO của visit, không nhận record {@code api/} của module khác
 * (khuôn {@code MedicalRecordMapper.toWeightRef}); mọi target khai tường minh để MapStruct không lấy nhầm
 * {@code vaccineTypeId} từ {@code lastDose}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VaccinationOptionsMapper {

    @Mapping(target = "protocolId", source = "protocolId")
    @Mapping(target = "doseNumber", source = "doseNumber")
    @Mapping(target = "intervalDays", source = "intervalDays")
    @Mapping(target = "minAgeWeeks", source = "minAgeWeeks")
    @Mapping(target = "requiredForBoarding", source = "requiredForBoarding")
    ProtocolDoseOptionResponse toDoseOption(Long protocolId, int doseNumber, int intervalDays, int minAgeWeeks,
            boolean requiredForBoarding);

    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "availableQuantity", source = "availableQuantity")
    VaccineProductOptionResponse toProductOption(Long productId, String name, int availableQuantity);

    @Mapping(target = "vaccineTypeId", source = "vaccineTypeId")
    @Mapping(target = "protocol", source = "protocol")
    @Mapping(target = "suggestedProtocolId", source = "suggestedProtocolId")
    @Mapping(target = "lastDose", source = "lastDose")
    @Mapping(target = "products", source = "products")
    @Mapping(target = "underMinAge", source = "underMinAge")
    VaccinationOptionsResponse toOptions(Long vaccineTypeId, List<ProtocolDoseOptionResponse> protocol,
            Long suggestedProtocolId, VaccinationRecordResponse lastDose, List<VaccineProductOptionResponse> products,
            boolean underMinAge);
}
