package com.petcare.module.ai.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Thuộc tính đối tượng của một dịch vụ thẩm mỹ hoặc sản phẩm (07 §7.2, §8 {@code ai_item_profiles}). {@code null} / rỗng =
 * không giới hạn; mọi trường rỗng nghĩa là "không có hồ sơ" (BR-AI-20, UC94). Giá trị thuần, dùng chung giữa ghi hồ sơ (UC94)
 * và tính điểm (UC92) để hai bên không lệch định nghĩa "rỗng".
 */
public record ProfileCriteria(ProfileSpecies species, Integer minAgeWeeks, Integer maxAgeWeeks, BigDecimal minWeightKg,
        BigDecimal maxWeightKg, List<String> breedKeywords, Integer repeatIntervalDays) {

    public ProfileCriteria {
        breedKeywords = breedKeywords == null ? List.of() : List.copyOf(breedKeywords);
    }

    public boolean isEmpty() {
        return species == null && minAgeWeeks == null && maxAgeWeeks == null && minWeightKg == null
                && maxWeightKg == null && breedKeywords.isEmpty() && repeatIntervalDays == null;
    }

    /** Bằng nhau theo giá trị: {@code 1} và {@code 1.00} là cùng một cân nặng (cột {@code NUMERIC(6,2)} trả scale 2). */
    public boolean sameAs(ProfileCriteria other) {
        return species == other.species && Objects.equals(minAgeWeeks, other.minAgeWeeks)
                && Objects.equals(maxAgeWeeks, other.maxAgeWeeks) && sameNumber(minWeightKg, other.minWeightKg)
                && sameNumber(maxWeightKg, other.maxWeightKg) && breedKeywords.equals(other.breedKeywords)
                && Objects.equals(repeatIntervalDays, other.repeatIntervalDays);
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
