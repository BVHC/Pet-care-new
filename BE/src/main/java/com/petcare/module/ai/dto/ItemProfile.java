package com.petcare.module.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.entity.ProfileSpecies;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ai-v1 {@code ItemProfile}: thuộc tính đối tượng và chu kỳ dùng lại của một dịch vụ thẩm mỹ hoặc sản phẩm (UC94, 07 §7.2).
 * Vừa là thân PUT vừa là {@code profile} trong dòng trả về. Khoảng giá trị khớp cột ({@code SMALLINT}, {@code NUMERIC(6,2)});
 * {@code min ≤ max} là luật chéo trường nên do service kiểm. Mọi trường rỗng nghĩa là xóa hồ sơ (BR-AI-20).
 */
public record ItemProfile(
        @Schema(description = "Vắng = mọi loài", nullable = true) ProfileSpecies species,

        @Min(0) @Max(5200) @Schema(example = "12", nullable = true) Integer minAgeWeeks,

        @Min(0) @Max(5200) @Schema(example = "52", nullable = true) Integer maxAgeWeeks,

        @DecimalMin("0") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2)
        @Schema(example = "1", nullable = true) BigDecimal minWeightKg,

        @DecimalMin("0") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2)
        @Schema(example = "8", nullable = true) BigDecimal maxWeightKg,

        @NotNull @Size(max = 50)
        @Schema(description = "Từ khóa giống viết thường không dấu; rỗng = mọi giống", example = "[\"poodle\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<@Size(max = 100) String> breedKeywords,

        @Min(1) @Max(3650) @Schema(description = "Chu kỳ dùng lại (ngày); vắng = mặc định [CFG]", example = "30",
                nullable = true)
        Integer repeatIntervalDays) {

    public ProfileCriteria toCriteria() {
        return new ProfileCriteria(species, minAgeWeeks, maxAgeWeeks, minWeightKg, maxWeightKg, breedKeywords,
                repeatIntervalDays);
    }

    public static ItemProfile from(ProfileCriteria criteria) {
        return new ItemProfile(criteria.species(), criteria.minAgeWeeks(), criteria.maxAgeWeeks(),
                criteria.minWeightKg(), criteria.maxWeightKg(), criteria.breedKeywords(), criteria.repeatIntervalDays());
    }
}
