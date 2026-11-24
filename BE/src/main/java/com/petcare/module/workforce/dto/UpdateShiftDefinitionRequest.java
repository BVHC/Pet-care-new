package com.petcare.module.workforce.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sửa / ngừng dùng / bật lại ca (workforce-v1 {@code UpdateShiftDefinitionRequest}, #3). Chỉ các trường có trong body
 * được đổi ({@code null} = không gửi); body không có trường nào là 400 (docs/adr/0149).
 */
public record UpdateShiftDefinitionRequest(
        @Schema(description = "Tên ca mới, duy nhất trong chi nhánh (BR-NS-01)", example = "Ca sáng")
        @Size(max = 60, message = "Tên ca tối đa 60 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên ca không được để trống")
        String name,

        @Schema(description = "Giờ bắt đầu mới, HH:mm; chỉ đổi được khi ca chưa có phân ca nào", example = "07:30",
                type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,

        @Schema(description = "Giờ kết thúc mới, HH:mm; chỉ đổi được khi ca chưa có phân ca nào", example = "12:00",
                type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,

        @Schema(description = "false = ngừng dùng (khi ca không còn phân ca từ hôm nay); true = bật lại ca đã ngừng",
                example = "false")
        Boolean active) {

    @Schema(hidden = true)
    @AssertTrue(message = "Cần gửi ít nhất một trường cần sửa (name, startTime, endTime, active)")
    public boolean isNotEmpty() {
        return name != null || startTime != null || endTime != null || active != null;
    }
}
