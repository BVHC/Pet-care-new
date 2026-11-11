package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.entity.ProfileSpecies;
import com.petcare.module.customer.api.Species;

/**
 * Điểm thuộc tính A và loại mục (07 §7.3, BR-AI-20): tiêu chí "đánh giá được" là tiêu chí có định nghĩa VÀ thú có dữ liệu;
 * loài, tuổi, cân nặng đánh giá được mà không khớp thì loại mục; giống không khớp chỉ không được tính điểm.
 */
class RecoAttributesTest {

    private static final BigDecimal NEUTRAL = new BigDecimal("0.3");

    /** Mochi (07 §7.3): chó poodle, 20 tuần, 3,2 kg. */
    private static final PetFacts MOCHI = new PetFacts(Species.DOG, "Poodle", 20L, new BigDecimal("3.2"));

    private static ProfileCriteria criteria(ProfileSpecies species, Integer minAge, Integer maxAge, String minKg,
            String maxKg, String... breeds) {
        return new ProfileCriteria(species, minAge, maxAge, minKg == null ? null : new BigDecimal(minKg),
                maxKg == null ? null : new BigDecimal(maxKg), List.of(breeds), null);
    }

    @Test
    void mochiMatchesAllFourCriteriaOfTheWorkedExample() {
        ProfileCriteria bath = criteria(ProfileSpecies.DOG, 12, null, "1", "8", "poodle");

        RecoAttributes.Match match = RecoAttributes.evaluate(bath, MOCHI).orElseThrow();

        assertThat(match.a()).isEqualByComparingTo("1");
        assertThat(match.reasons()).contains("Phù hợp với chó", "Phù hợp từ 12 tuần tuổi", "Phù hợp cân nặng 1–8 kg",
                "Phù hợp giống poodle");
    }

    @Test
    void weightOutsideTheRangeExcludesTheItem() {
        ProfileCriteria bigDogs = criteria(ProfileSpecies.DOG, null, null, "15", null);

        assertThat(RecoAttributes.evaluate(bigDogs, MOCHI)).isEmpty();
    }

    @Test
    void weightBoundsAreInclusive() {
        PetFacts pet = new PetFacts(Species.DOG, null, null, new BigDecimal("8.00"));

        assertThat(RecoAttributes.evaluate(criteria(ProfileSpecies.DOG, null, null, "1", "8"), pet)).isPresent();
        assertThat(RecoAttributes.evaluate(criteria(ProfileSpecies.DOG, null, null, "8.01", null), pet)).isEmpty();
    }

    @Test
    void speciesMismatchExcludesTheItem() {
        assertThat(RecoAttributes.evaluate(criteria(ProfileSpecies.CAT, null, null, null, null), MOCHI)).isEmpty();
    }

    @Test
    void ageOutsideTheRangeExcludesTheItemAndBoundsAreInclusive() {
        assertThat(RecoAttributes.evaluate(criteria(null, 21, null, null, null), MOCHI)).isEmpty();
        assertThat(RecoAttributes.evaluate(criteria(null, null, 19, null, null), MOCHI)).isEmpty();
        assertThat(RecoAttributes.evaluate(criteria(null, 20, 20, null, null), MOCHI)).isPresent();
    }

    @Test
    void breedMismatchDoesNotExcludeButCostsOneOfTheEvaluableCriteria() {
        ProfileCriteria bath = criteria(ProfileSpecies.DOG, 12, null, "1", "8", "corgi");

        RecoAttributes.Match match = RecoAttributes.evaluate(bath, MOCHI).orElseThrow();

        assertThat(match.a()).isEqualByComparingTo("0.75");
        assertThat(match.reasons()).noneMatch(reason -> reason.contains("giống"));
    }

    @Test
    void petWithoutBirthDateOrWeightLeavesThoseCriteriaUnevaluated() {
        PetFacts bare = new PetFacts(Species.DOG, "Poodle", null, null);
        ProfileCriteria bath = criteria(ProfileSpecies.DOG, 12, null, "1", "8", "poodle");

        RecoAttributes.Match match = RecoAttributes.evaluate(bath, bare).orElseThrow();

        // Chỉ loài và giống đánh giá được, cả hai khớp: A = 2/2, không bị phạt vì thiếu tuổi, cân nặng.
        assertThat(match.a()).isEqualByComparingTo("1");
    }

    @Test
    void noEvaluableCriterionGivesTheNeutralScoreAndTheGenericReason() {
        PetFacts bare = new PetFacts(Species.DOG, null, null, null);
        ProfileCriteria weightOnly = criteria(null, null, null, "1", "8");

        for (ProfileCriteria none : new ProfileCriteria[] {null, criteria(null, null, null, null, null), weightOnly}) {
            RecoAttributes.Match match = RecoAttributes.evaluate(none, bare).orElseThrow();

            assertThat(match.a()).isEqualByComparingTo(NEUTRAL);
            assertThat(match.reasons()).containsExactly("Phù hợp chung");
        }
    }

    @Test
    void blankBreedOfThePetLeavesTheBreedCriterionUnevaluated() {
        PetFacts noBreed = new PetFacts(Species.DOG, "  ", 20L, new BigDecimal("3.2"));
        ProfileCriteria bath = criteria(ProfileSpecies.DOG, 12, null, "1", "8", "poodle");

        assertThat(RecoAttributes.evaluate(bath, noBreed).orElseThrow().a()).isEqualByComparingTo("1");
    }

    @Test
    void breedMatchingIgnoresCaseAndAccentsIncludingTheLetterD() {
        PetFacts pet = new PetFacts(Species.DOG, "Chó Đốm Dalmatian", null, null);

        Optional<RecoAttributes.Match> match = RecoAttributes.evaluate(criteria(null, null, null, null, null, "dom"), pet);

        assertThat(match.orElseThrow().reasons()).contains("Phù hợp giống dom");
        assertThat(PlainText.normalize("Đốm  Sọc")).isEqualTo("dom  soc");
        assertThat(PlainText.normalize(null)).isEmpty();
    }

    @Test
    void speciesOtherMatchesOnlyOtherPets() {
        PetFacts rabbit = new PetFacts(Species.OTHER, null, null, null);

        assertThat(RecoAttributes.evaluate(criteria(ProfileSpecies.OTHER, null, null, null, null), rabbit)).isPresent();
        assertThat(RecoAttributes.evaluate(criteria(ProfileSpecies.DOG, null, null, null, null), rabbit)).isEmpty();
    }
}
