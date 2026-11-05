package com.petcare.module.visit.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ sức khỏe của một thú (visit-v1 schema {@code HealthRecord}, UC25, UC53; docs/adr/0108). DTO riêng cho khách và
 * nhân viên đọc chung (convention 02 §DTO, BR-KH-07): khách không nhận {@code internalNote} và bản bổ sung nội bộ nên
 * {@link HealthVisitEntryResponse} bỏ hẳn trường khi {@code null}. Danh sách không bao giờ {@code null}. Chỉ dựng bởi
 * {@code HealthRecordService}.
 */
public record HealthRecordResponse(
        @Schema(description = "Thú cưng", example = "5001")
        Long petId,
        @Schema(description = "Các lượt đã hoàn tất của thú ở mọi chi nhánh, mới nhất trước (BR-KH-07)")
        List<HealthVisitEntryResponse> visits,
        @Schema(description = "Mũi tiêm tại hệ thống, mới nhất trước; mũi cũ hơn cùng loại vaccine có superseded = true "
                + "(BR-KB-04)")
        List<VaccinationRecordResponse> vaccinations,
        @Schema(description = "Mũi chưa bị thay thế: ngày tái chủng kế tiếp của từng loại vaccine, gần nhất trước")
        List<UpcomingVaccinationResponse> upcomingVaccinations,
        @Schema(description = "Ngày tái khám gần nhất từ hôm nay trở đi trong các bệnh án (BR-KB-06); không có thì null",
                example = "2026-11-20", nullable = true)
        LocalDate upcomingFollowUpDate) {
}
