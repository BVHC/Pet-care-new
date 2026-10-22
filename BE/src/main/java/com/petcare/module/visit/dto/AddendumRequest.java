package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body của {@code POST /api/visits/{visitId}/medical-record/addenda} (visit-v1 {@code AddendumRequest}, UC53;
 * docs/adr/0064). Nội dung bắt buộc: thiếu / chỉ khoảng trắng là 400 {@code VALIDATION_FAILED}. Compact constructor bỏ
 * khoảng trắng hai đầu trước Bean Validation (khuôn {@code ReassignRequest}). Hợp đồng và cột {@code content TEXT} không
 * có độ dài tối đa nên không thêm {@code @Size}. {@code isInternal} vắng = {@code false}.
 */
public record AddendumRequest(
        @Schema(description = "Nội dung bổ sung (BR-KB-01)", example = "Bổ sung: kết quả xét nghiệm máu về sau, bạch cầu tăng")
        @NotBlank(message = "Bắt buộc nhập nội dung bổ sung")
        String content,

        @Schema(description = "Bổ sung cho ghi chú nội bộ, khách không thấy", example = "false")
        Boolean isInternal) {

    public AddendumRequest {
        content = content == null ? null : content.strip();
    }

    /** {@code isInternal} vắng hoặc {@code null} là bản bổ sung khách xem được. */
    public boolean internal() {
        return Boolean.TRUE.equals(isInternal);
    }
}
