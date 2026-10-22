package com.petcare.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.entity.PetSex;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Thú cưng (customer-v1 {@code PetResponse}). Ba trường suy ra, không phải cột: {@code speciesLocked} (đã có bệnh án hoặc
 * mũi tiêm — BR-KH-03), {@code currentWeightKg} (bản ghi cân mới nhất — BR-KH-04), {@code deletable} (chưa phát sinh lịch
 * hẹn, đặt chỗ, Visit, Order và chưa mất — BR-KH-06). {@code isNeutered} cần {@code @JsonProperty} để JSON giữ đúng tên
 * của hợp đồng.
 */
public record PetResponse(
        @Schema(description = "Mã thú cưng", example = "88")
        Long petId,

        @Schema(description = "Chủ hiện tại (BR-KH-08)", example = "315")
        Long customerId,

        @Schema(description = "Tên thú cưng", example = "Mực")
        String name,

        @Schema(description = "Loài (BR-KH-02)", example = "DOG")
        Species species,

        @Schema(description = "Giới tính", example = "MALE")
        PetSex sex,

        @Schema(description = "Giống", example = "Poodle")
        String breed,

        @Schema(description = "Ngày sinh", example = "2024-03-15")
        LocalDate birthDate,

        @Schema(description = "Ngày sinh là ước lượng", example = "false")
        boolean birthDateEstimated,

        @Schema(description = "Màu lông", example = "Nâu")
        String color,

        @Schema(description = "Đã triệt sản; null nếu chưa rõ", example = "false")
        @JsonProperty("isNeutered") Boolean isNeutered,

        @Schema(description = "Ảnh (https)", example = "https://cdn.petcare.vn/pets/88.jpg")
        String photoUrl,

        @Schema(description = "Ngày mất; khác null = đã mất, chỉ đọc (BR-KH-05)", example = "2026-09-30")
        LocalDate deceasedOn,

        @Schema(description = "Đã có bệnh án hoặc mũi tiêm: không đổi được loài (BR-KH-03)", example = "false")
        boolean speciesLocked,

        @Schema(description = "Cân nặng hiện tại (kg): bản ghi cân mới nhất (BR-KH-04); null nếu chưa cân",
                example = "8.5")
        BigDecimal currentWeightKg,

        @Schema(description = "Chưa phát sinh lịch hẹn, đặt chỗ, Visit, Order và chưa mất: xóa được (BR-KH-06)",
                example = "true")
        boolean deletable) {
}
