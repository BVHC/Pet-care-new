package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Che tên thú ở {@code /me} (BR-AI-12b, ADR-0122, ADR-0132): cùng một quy tắc khớp với bước nhận ý định (BR-AI-26), nên
 * thứ router coi là "đã nhắc tên thú" thì cũng bị che, không sót, không che thừa.
 */
class PetNameMaskingTest {

    private final PersonalIntentRouter router = new PersonalIntentRouter();

    @ParameterizedTest(name = "[{index}] {0} → {2}")
    @CsvSource(delimiter = '|', value = {
            "Mochi tiêm khi nào?               | Mochi          | [thú] tiêm khi nào?",
            "MOCHI tiêm khi nào?               | Mochi          | [thú] tiêm khi nào?",
            "Mochi và Mochi ăn gì              | Mochi          | [thú] và [thú] ăn gì",
            "Mochila ăn gì                     | Mochi          | Mochila ăn gì",
            "Con Vàng tiêm khi nào?            | Vàng           | Con [thú] tiêm khi nào?",
            "con   vàng tiêm khi nào?          | Vàng           | con   [thú] tiêm khi nào?",
            "Bé Vàng ăn gì                     | Vàng           | Bé [thú] ăn gì",
            "Giá vàng hôm nay là bao nhiêu?    | Vàng           | Giá vàng hôm nay là bao nhiêu?",
            "Vàng ăn gì                        | Vàng           | Vàng ăn gì",
            "bong gon ăn gì                    | Bông Gòn       | [thú] ăn gì",
            "Bông Gòn ăn gì                    | Bông Gòn       | [thú] ăn gì",
            "Mi Mi ăn gì                       | Mi,Mi Mi       | [thú] ăn gì",
            "Mochi và Kitty đều ốm             | Mochi,Kitty    | [thú] và [thú] đều ốm"})
    void petNamesAreMaskedByTheSameRuleAsIntentRouting(String question, String names, String expected) {
        assertThat(router.maskPetNames(question, List.of(names.split(",")))).isEqualTo(expected);
    }

    @Test
    void aQuestionWithoutPetNamesOrWithNoPetsIsUnchanged() {
        assertThat(router.maskPetNames("Chó con ăn gì?", List.of("Mochi"))).isEqualTo("Chó con ăn gì?");
        assertThat(router.maskPetNames("Mochi ăn gì?", List.of())).isEqualTo("Mochi ăn gì?");
    }

    @Test
    void aBlankPetNameIsIgnoredInsteadOfMaskingEveryGap() {
        assertThat(router.maskPetNames("Mochi ăn gì?", List.of(" ", "")).trim()).isEqualTo("Mochi ăn gì?");
    }

    @Test
    void thePiiPlaceholdersAreLeftAlone() {
        assertThat(router.maskPetNames("Gọi [sđt] hỏi Mochi", List.of("Mochi"))).isEqualTo("Gọi [sđt] hỏi [thú]");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "Mochi tiêm khi nào?               | Mochi",
            "Con Vàng tiêm khi nào?            | Vàng",
            "bong gon ăn gì                    | Bông Gòn",
            "Giá vàng hôm nay                  | Vàng"})
    void everyNameTheRouterSeesIsMaskedAndNothingElseIs(String question, String name) {
        List<String> petNames = List.of(name);
        boolean mentioned = !router.mentionedPetNames(question, petNames).isEmpty();

        assertThat(router.maskPetNames(question, petNames).contains("[thú]")).isEqualTo(mentioned);
    }
}
