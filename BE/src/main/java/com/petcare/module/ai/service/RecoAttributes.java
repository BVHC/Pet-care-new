package com.petcare.module.ai.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.customer.api.Species;

/**
 * Điểm thuộc tính A ∈ [0, 1] và loại mục (07 §7.3, BR-AI-20). Tiêu chí <i>đánh giá được</i> là tiêu chí có định nghĩa trong
 * hồ sơ VÀ thú có dữ liệu. Loài, tuổi, cân nặng đánh giá được mà không khớp thì loại mục ({@link Optional#empty()}); giống
 * không khớp chỉ không được tính. {@code A = số khớp / số đánh giá được}; không có tiêu chí nào đánh giá được (kể cả mục chưa
 * có hồ sơ) thì {@code A = 0,3} và lý do "Phù hợp chung".
 */
final class RecoAttributes {

    static final BigDecimal NEUTRAL = new BigDecimal("0.3");
    private static final String GENERIC_REASON = "Phù hợp chung";

    /** {@code reasons} sinh từ mẫu theo từng tiêu chí khớp (BR-AI-22), không dùng LLM. */
    record Match(BigDecimal a, List<String> reasons) {
    }

    private RecoAttributes() {
    }

    /** {@code criteria} null = mục chưa có hồ sơ. */
    static Optional<Match> evaluate(ProfileCriteria criteria, PetFacts pet) {
        List<String> reasons = new ArrayList<>();
        int evaluable = 0;
        int matched = 0;
        if (criteria != null) {
            if (criteria.species() != null && pet.species() != null) {
                evaluable++;
                if (!criteria.species().name().equals(pet.species().name())) {
                    return Optional.empty();
                }
                matched++;
                reasons.add("Phù hợp với " + speciesName(pet.species()));
            }
            if ((criteria.minAgeWeeks() != null || criteria.maxAgeWeeks() != null) && pet.ageWeeks() != null) {
                evaluable++;
                long age = pet.ageWeeks();
                if (criteria.minAgeWeeks() != null && age < criteria.minAgeWeeks()
                        || criteria.maxAgeWeeks() != null && age > criteria.maxAgeWeeks()) {
                    return Optional.empty();
                }
                matched++;
                reasons.add(ageReason(criteria.minAgeWeeks(), criteria.maxAgeWeeks()));
            }
            if ((criteria.minWeightKg() != null || criteria.maxWeightKg() != null) && pet.weightKg() != null) {
                evaluable++;
                if (criteria.minWeightKg() != null && pet.weightKg().compareTo(criteria.minWeightKg()) < 0
                        || criteria.maxWeightKg() != null && pet.weightKg().compareTo(criteria.maxWeightKg()) > 0) {
                    return Optional.empty();
                }
                matched++;
                reasons.add(weightReason(criteria.minWeightKg(), criteria.maxWeightKg()));
            }
            if (!criteria.breedKeywords().isEmpty() && pet.breed() != null && !pet.breed().isBlank()) {
                evaluable++;
                String breed = PlainText.normalize(pet.breed());
                String keyword = criteria.breedKeywords().stream()
                        .filter(k -> !k.isBlank() && breed.contains(PlainText.normalize(k.trim()))).findFirst()
                        .orElse(null);
                if (keyword != null) {
                    matched++;
                    reasons.add("Phù hợp giống " + keyword.trim());
                }
            }
        }
        if (evaluable == 0) {
            return Optional.of(new Match(NEUTRAL, List.of(GENERIC_REASON)));
        }
        BigDecimal a = BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(evaluable), MathContext.DECIMAL64);
        return Optional.of(new Match(a, reasons));
    }

    private static String speciesName(Species species) {
        return switch (species) {
            case DOG -> "chó";
            case CAT -> "mèo";
            case OTHER -> "thú cưng loài khác";
        };
    }

    private static String ageReason(Integer min, Integer max) {
        if (min != null && max != null) {
            return "Phù hợp độ tuổi " + min + "–" + max + " tuần";
        }
        return min != null ? "Phù hợp từ " + min + " tuần tuổi" : "Phù hợp tới " + max + " tuần tuổi";
    }

    private static String weightReason(BigDecimal min, BigDecimal max) {
        if (min != null && max != null) {
            return "Phù hợp cân nặng " + kg(min) + "–" + kg(max) + " kg";
        }
        return min != null ? "Phù hợp từ " + kg(min) + " kg" : "Phù hợp tới " + kg(max) + " kg";
    }

    private static String kg(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
