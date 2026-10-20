package com.petcare.module.visit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/visits} (visit-v1 {@code CreateVisitRequest}, UC44, UC47; docs/adr/0059). Không có
 * {@code branchId}: chi nhánh là của lễ tân; trường lạ bị bỏ qua. Walk-in thiếu dịch vụ (BR-TN-01), thú, chi nhánh, giờ
 * mở cửa, dịch vụ đều kiểm ở service, vì phụ thuộc ngữ cảnh và cần mã rule. Có {@code appointmentId} thì
 * {@code serviceId} bị bỏ qua (dịch vụ của lịch).
 */
public record CreateVisitRequest(
        @Schema(description = "Thú cưng được tiếp nhận", example = "12")
        @NotNull(message = "Thiếu thú cưng")
        @Positive(message = "Mã thú cưng không hợp lệ")
        Long petId,

        @Schema(description = "Có = check-in lịch hẹn; không = walk-in", example = "501")
        @Positive(message = "Mã lịch hẹn không hợp lệ")
        Long appointmentId,

        @Schema(description = "Bắt buộc với walk-in; có lịch hẹn thì lấy dịch vụ của lịch", example = "7")
        @Positive(message = "Mã dịch vụ không hợp lệ")
        Long serviceId,

        @Schema(description = "Bắt buộc true khi tiếp nhận ngoài giờ (BR-CN-05)", example = "false")
        @JsonProperty("isEmergency")
        Boolean isEmergency) {

    /** {@code null} = không cấp cứu. */
    public boolean emergency() {
        return Boolean.TRUE.equals(isEmergency);
    }
}
