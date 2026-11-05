package com.petcare.module.visit.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.petcare.module.catalog.api.ServiceGroup;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một lượt đã hoàn tất trong hồ sơ sức khỏe (visit-v1 schema {@code HealthVisitEntry}, UC25; docs/adr/0108). Lượt
 * {@code GROOMING} không có bệnh án: các trường khám để {@code null}, đơn thuốc và bản bổ sung rỗng. {@code internalNote}
 * bỏ hẳn khỏi JSON khi {@code null}: khách luôn nhận {@code null} (BR-KH-07, BR-KB-01), nhân viên nhận giá trị thật.
 */
public record HealthVisitEntryResponse(
        @Schema(description = "Lượt tiếp nhận", example = "9001")
        Long visitId,
        @Schema(description = "Ngày khám: ngày check-in theo giờ Việt Nam", example = "2026-10-22")
        LocalDate visitDate,
        @Schema(description = "Chi nhánh nơi khám", example = "PetCare Tây Hồ")
        String branchName,
        @Schema(description = "Nhóm dịch vụ của lượt", example = "MEDICAL")
        ServiceGroup serviceGroup,
        @Schema(description = "Triệu chứng, khám lâm sàng, xét nghiệm", example = "Sốt 39,5°C, bỏ ăn 2 ngày",
                nullable = true)
        String examination,
        @Schema(description = "Chẩn đoán", example = "Viêm dạ dày ruột cấp", nullable = true)
        String diagnosis,
        @Schema(description = "Hướng điều trị", example = "Truyền dịch, kháng sinh 5 ngày", nullable = true)
        String treatmentPlan,
        @Schema(description = "Chỉ nhân viên; khách không nhận trường này (BR-KH-07)",
                example = "Chủ khó tính, gọi trước khi đổi thuốc", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String internalNote,
        @Schema(description = "Ngày tái khám (BR-KB-06)", example = "2026-11-05", nullable = true)
        LocalDate followUpDate,
        @Schema(description = "Đơn thuốc (BR-KB-03), gồm dòng mua ngoài")
        List<PrescriptionItemResponse> prescriptions,
        @Schema(description = "Bản bổ sung theo thứ tự ghi; khách chỉ nhận bản không nội bộ (BR-KB-01)")
        List<AddendumResponse> addenda) {
}
