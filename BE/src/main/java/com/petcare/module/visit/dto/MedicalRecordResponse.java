package com.petcare.module.visit.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bệnh án của một lượt cho <b>nhân viên</b> (visit-v1 schema {@code MedicalRecord}, UC48, UC53; docs/adr/0064). Có
 * {@code internalNote}: hồ sơ sức khỏe cho khách (#21) dùng DTO riêng (convention 02 §DTO, BR-KH-07), không dùng lại
 * record này. {@code locked} = lượt đã {@code COMPLETED} (§1). Lượt chưa ghi bệnh án trả khung rỗng; danh sách không
 * bao giờ {@code null}. Chỉ dựng bởi {@code MedicalRecordMapper}.
 */
public record MedicalRecordResponse(
        @Schema(description = "Lượt của bệnh án", example = "9001")
        Long visitId,

        @Schema(description = "Triệu chứng, khám lâm sàng, xét nghiệm", example = "Sốt 39,5°C, bỏ ăn 2 ngày")
        String examination,

        @Schema(description = "Chẩn đoán", example = "Viêm dạ dày ruột cấp")
        String diagnosis,

        @Schema(description = "Hướng điều trị", example = "Truyền dịch, kháng sinh 5 ngày")
        String treatmentPlan,

        @Schema(description = "Chỉ nhân viên", example = "Chủ khó tính, gọi trước khi đổi thuốc")
        String internalNote,

        @Schema(description = "Ngày tái khám (BR-KB-06)", example = "2026-11-05")
        LocalDate followUpDate,

        @Schema(description = "Visit COMPLETED: chỉ thêm bản bổ sung (BR-KB-01)", example = "false")
        boolean locked,

        @Schema(description = "Người sửa gần nhất", example = "45")
        Long lastEditedBy,

        @Schema(description = "Đơn thuốc (BR-KB-03)")
        List<PrescriptionItemResponse> prescriptions,

        @Schema(description = "Mũi tiêm của lượt (BR-KB-04)")
        List<VaccinationRecordResponse> vaccinations,

        @Schema(description = "Bản bổ sung sau khi khóa (BR-KB-01)")
        List<AddendumResponse> addenda) {
}
