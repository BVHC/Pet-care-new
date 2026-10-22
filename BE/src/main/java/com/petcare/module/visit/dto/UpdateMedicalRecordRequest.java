package com.petcare.module.visit.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body của {@code PUT /api/visits/{visitId}/medical-record} (visit-v1 {@code UpdateMedicalRecordRequest}, UC48;
 * docs/adr/0064). PUT <b>thay toàn bộ</b> 5 trường: trường vắng hoặc {@code null} là xóa giá trị (§4). Compact
 * constructor bỏ khoảng trắng hai đầu (khuôn {@code ReassignRequest}) và đổi chuỗi rỗng thành {@code null}, nên
 * "có chẩn đoán" của BR-KB-02 chỉ cần kiểm khác {@code null}. Ngày tái khám (BR-KB-06) kiểm ở service theo ngày
 * check-in và [CFG] đọc lúc ghi (§3), không ở Bean Validation.
 */
public record UpdateMedicalRecordRequest(
        @Schema(description = "Triệu chứng, khám lâm sàng, xét nghiệm; gồm tiền sử tiêm do chủ khai (04 nguyên tắc 9)",
                example = "Sốt 39,5°C, bỏ ăn 2 ngày. Chủ khai đã tiêm dại ở phòng khám khác năm ngoái")
        String examination,

        @Schema(description = "Chẩn đoán; bắt buộc trước khi hoàn tất nếu lượt có dịch vụ loại Khám (BR-KB-02)",
                example = "Viêm dạ dày ruột cấp")
        String diagnosis,

        @Schema(description = "Hướng điều trị", example = "Truyền dịch, kháng sinh 5 ngày")
        String treatmentPlan,

        @Schema(description = "Ghi chú nội bộ, khách không xem được (BR-KB-01, BR-KH-07)",
                example = "Chủ khó tính, gọi trước khi đổi thuốc")
        String internalNote,

        @Schema(description = "Ngày tái khám: sau ngày khám, xa nhất 180 ngày [CFG] (BR-KB-06)", example = "2026-11-05")
        LocalDate followUpDate) {

    public UpdateMedicalRecordRequest {
        examination = clean(examination);
        diagnosis = clean(diagnosis);
        treatmentPlan = clean(treatmentPlan);
        internalNote = clean(internalNote);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
