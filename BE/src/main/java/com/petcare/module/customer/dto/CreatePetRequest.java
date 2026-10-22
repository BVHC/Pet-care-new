package com.petcare.module.customer.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.entity.PetSex;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/me/pets} và {@code POST /api/customers/{customerId}/pets} (customer-v1
 * {@code CreatePetRequest}, UC24; BR-KH-02). Bắt buộc tên, loài, giới tính. Ngày sinh không ở tương lai: kiểm ở service
 * bằng bean {@code Clock} (BR-KH-02, 400), không dùng {@code @PastOrPresent} vì nó đọc đồng hồ hệ thống. {@code photoUrl}
 * chỉ {@code https://} (06 §7 G6).
 */
public record CreatePetRequest(
        @Schema(description = "Tên thú cưng", example = "Mực")
        @NotBlank(message = "Vui lòng nhập tên thú cưng")
        @Size(max = 100, message = "Tên thú cưng tối đa 100 ký tự")
        String name,

        @Schema(description = "Loài: DOG, CAT, OTHER (BR-KH-02)", example = "DOG")
        @NotNull(message = "Vui lòng chọn loài")
        Species species,

        @Schema(description = "Giới tính: MALE, FEMALE, UNKNOWN", example = "MALE")
        @NotNull(message = "Vui lòng chọn giới tính")
        PetSex sex,

        @Schema(description = "Giống", example = "Poodle")
        @Size(max = 100, message = "Giống tối đa 100 ký tự")
        String breed,

        @Schema(description = "Ngày sinh, không ở tương lai", example = "2024-03-15")
        LocalDate birthDate,

        @Schema(description = "Ngày sinh là ước lượng; null = không", example = "false")
        Boolean birthDateEstimated,

        @Schema(description = "Màu lông", example = "Nâu")
        @Size(max = 50, message = "Màu lông tối đa 50 ký tự")
        String color,

        @Schema(description = "Đã triệt sản; null = chưa rõ", example = "false")
        @JsonProperty("isNeutered") Boolean isNeutered,

        @Schema(description = "Ảnh (https)", example = "https://cdn.petcare.vn/pets/88.jpg")
        @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
        @Pattern(regexp = "^(\\s*|\\s*https://\\S+\\s*)$", message = "Ảnh phải là đường dẫn https://")
        String photoUrl) {
}
