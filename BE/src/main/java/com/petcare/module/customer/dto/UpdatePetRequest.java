package com.petcare.module.customer.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.entity.PetSex;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/pets/{petId}} (customer-v1 {@code UpdatePetRequest}, UC24; BR-KH-02, 03, 05). Quy ước PATCH
 * của module (docs/adr/0028): {@code null} = giữ, chuỗi rỗng sau {@code strip} = xóa (giống, màu lông, ảnh).
 * {@code name}, {@code species}, {@code sex} không xóa được. Đổi {@code species} khi thú đã có bệnh án / mũi tiêm → 400
 * BR-KH-03; thú đã mất → 400 BR-KH-05 (kiểm ở service).
 */
public record UpdatePetRequest(
        @Schema(description = "Tên mới; null = giữ nguyên", example = "Mực con")
        @Size(max = 100, message = "Tên thú cưng tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên thú cưng không được để trống")
        String name,

        @Schema(description = "Loài mới; chỉ đổi được khi chưa có bệnh án / mũi tiêm (BR-KH-03)", example = "DOG")
        Species species,

        @Schema(description = "Giới tính mới", example = "MALE")
        PetSex sex,

        @Schema(description = "Giống; null = giữ, rỗng = xóa", example = "Poodle")
        @Size(max = 100, message = "Giống tối đa 100 ký tự")
        String breed,

        @Schema(description = "Ngày sinh mới, không ở tương lai; null = giữ nguyên", example = "2024-03-15")
        LocalDate birthDate,

        @Schema(description = "Ngày sinh là ước lượng; null = giữ nguyên", example = "false")
        Boolean birthDateEstimated,

        @Schema(description = "Màu lông; null = giữ, rỗng = xóa", example = "Nâu")
        @Size(max = 50, message = "Màu lông tối đa 50 ký tự")
        String color,

        @Schema(description = "Đã triệt sản; null = giữ nguyên", example = "true")
        @JsonProperty("isNeutered") Boolean isNeutered,

        @Schema(description = "Ảnh (https); null = giữ, rỗng = xóa", example = "https://cdn.petcare.vn/pets/88.jpg")
        @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
        @Pattern(regexp = "^(\\s*|\\s*https://\\S+\\s*)$", message = "Ảnh phải là đường dẫn https://")
        String photoUrl) {
}
