package com.petcare.module.visit.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.visit.dto.AddendumResponse;
import com.petcare.module.visit.dto.MedicalRecordResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.dto.WeightRecordRefResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;
import com.petcare.module.visit.entity.Vaccination;

/**
 * {@link MedicalRecord} + bản bổ sung → {@link MedicalRecordResponse} cho nhân viên (visit-v1 schema
 * {@code MedicalRecord}; docs/adr/0066). {@code unmappedTargetPolicy = ERROR}, mọi target khai tường minh (khuôn
 * {@code VisitMapper}). {@code locked} do service truyền theo trạng thái lượt (§1), không đọc {@code locked_at}.
 * {@code prescriptions} rỗng cho tới T38. Tên người ghi bản bổ sung và tên loại vaccine / sản phẩm do service tra theo
 * lô rồi truyền vào, không nhận record {@code api/} của module khác.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MedicalRecordMapper {

    @Mapping(target = "visitId", source = "record.visitId")
    @Mapping(target = "examination", source = "record.examination")
    @Mapping(target = "diagnosis", source = "record.diagnosis")
    @Mapping(target = "treatmentPlan", source = "record.treatmentPlan")
    @Mapping(target = "internalNote", source = "record.internalNote")
    @Mapping(target = "followUpDate", source = "record.followUpDate")
    @Mapping(target = "locked", source = "locked")
    @Mapping(target = "lastEditedBy", source = "record.lastEditedBy")
    @Mapping(target = "prescriptions", expression = "java(java.util.List.of())")
    @Mapping(target = "vaccinations", source = "vaccinations")
    @Mapping(target = "addenda", source = "addenda")
    MedicalRecordResponse toResponse(MedicalRecord record, boolean locked, List<AddendumResponse> addenda,
            List<VaccinationRecordResponse> vaccinations);

    /** Lượt {@code MEDICAL} chưa ghi bệnh án (§1): khung rỗng, danh sách rỗng chứ không {@code null}. */
    @Mapping(target = "visitId", source = "visitId")
    @Mapping(target = "examination", ignore = true)
    @Mapping(target = "diagnosis", ignore = true)
    @Mapping(target = "treatmentPlan", ignore = true)
    @Mapping(target = "internalNote", ignore = true)
    @Mapping(target = "followUpDate", ignore = true)
    @Mapping(target = "locked", source = "locked")
    @Mapping(target = "lastEditedBy", ignore = true)
    @Mapping(target = "prescriptions", expression = "java(java.util.List.of())")
    @Mapping(target = "vaccinations", source = "vaccinations")
    @Mapping(target = "addenda", expression = "java(java.util.List.of())")
    MedicalRecordResponse toEmptyResponse(Long visitId, boolean locked, List<VaccinationRecordResponse> vaccinations);

    /** Mọi giá trị là cột đã chụp lúc tiêm (BR-SP-03); lô chưa điền (D046). Tên do service tra theo lô. */
    @Mapping(target = "vaccinationId", source = "vaccination.id")
    @Mapping(target = "visitId", source = "vaccination.visitId")
    @Mapping(target = "branchId", source = "vaccination.branchId")
    @Mapping(target = "vaccineTypeId", source = "vaccination.vaccineTypeId")
    @Mapping(target = "vaccineTypeName", source = "vaccineTypeName")
    @Mapping(target = "protocolId", source = "vaccination.protocolId")
    @Mapping(target = "doseNumber", source = "vaccination.doseNumber")
    @Mapping(target = "productId", source = "vaccination.productId")
    @Mapping(target = "productName", source = "productName")
    @Mapping(target = "lotNumber", ignore = true)
    @Mapping(target = "expiryDate", ignore = true)
    @Mapping(target = "administeredOn", source = "vaccination.administeredOn")
    @Mapping(target = "nextDueDate", source = "vaccination.nextDueDate")
    @Mapping(target = "superseded", expression = "java(vaccination.isSuperseded())")
    @Mapping(target = "recordedBy", source = "vaccination.recordedBy")
    @Mapping(target = "orderLineId", source = "vaccination.orderLineId")
    VaccinationRecordResponse toVaccinationRecord(Vaccination vaccination, String vaccineTypeName, String productName);

    @Mapping(target = "addendumId", source = "addendum.id")
    @Mapping(target = "content", source = "addendum.content")
    @Mapping(target = "isInternal", source = "addendum.internal")
    @Mapping(target = "createdBy", source = "addendum.createdBy")
    @Mapping(target = "createdByName", source = "createdByName")
    @Mapping(target = "createdAt", expression = "java(addendum.getCreatedAt() == null ? null"
            + " : addendum.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))")
    AddendumResponse toAddendum(MedicalRecordAddendum addendum, String createdByName);

    /** visit-v1 #14: tham số vô hướng, không nhận record {@code api/} của customer (docs/adr/0068). */
    @Mapping(target = "weightRecordId", source = "weightRecordId")
    @Mapping(target = "weightKg", source = "weightKg")
    @Mapping(target = "measuredAt", source = "measuredAt")
    WeightRecordRefResponse toWeightRef(Long weightRecordId, BigDecimal weightKg, Instant measuredAt);
}
