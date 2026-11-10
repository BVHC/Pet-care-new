package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Khớp cụm từ có ranh giới từ, ưu tiên dạng có dấu; dạng bỏ dấu chỉ cho cụm từ từ 2 âm tiết (BR-AI-03, BR-AI-26,
 * 07 §14.3): "liệt kê" không là cấp cứu, "giá" không trùng "già", "tiêm" không trùng "tiệm".
 */
class PhraseMatcherTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" ~ \"{1}\" = {2}")
    @CsvSource(delimiter = '|', value = {
            "Chó bị liệt chân sau|liệt chân|true",
            "Liệt kê giúp tôi các mũi tiêm|liệt chân|false",
            "Giá vàng hôm nay|già|false",
            "Bé già nhà tôi|giá|false",
            "Tiệm thú y ở đâu|tiêm|false",
            "Mũi tiêm tiếp theo|tiêm|true",
            "Chó ngất xỉu|ngất|true",
            "Chó ngấtxỉu|ngất|false",
            "CHÓ KHÓ THỞ|khó thở|true",
            "chó  khó   thở|khó thở|true",
    })
    void wordBoundariesAreRespected(String text, String phrase, boolean expected) {
        assertThat(PhraseMatcher.hasPhrase(text, phrase)).isEqualTo(expected);
    }

    @Test
    void unaccentedFormOnlyMatchesPhrasesOfTwoOrMoreSyllables() {
        assertThat(PhraseMatcher.hasPhrase("cho kho tho", "khó thở")).isTrue();
        assertThat(PhraseMatcher.hasPhrase("gia dinh toi", "gia đình")).isTrue();
        // một âm tiết: "ngat" có thể là chữ khác, nên chỉ khớp dạng có dấu
        assertThat(PhraseMatcher.hasPhrase("cho ngat xiu", "ngất")).isFalse();
        assertThat(PhraseMatcher.hasPhrase("gia vang hom nay", "giá")).isFalse();
    }

    @Test
    void textAndPhraseAreNormalisedToTheSameUnicodeForm() {
        String decomposed = Normalizer.normalize("chó khó thở", Normalizer.Form.NFD);
        assertThat(PhraseMatcher.hasPhrase(decomposed, "khó thở")).isTrue();
        assertThat(PhraseMatcher.hasPhrase("chó khó thở", Normalizer.normalize("khó thở", Normalizer.Form.NFD)))
                .isTrue();
    }

    @Test
    void anyPhraseIsTrueWhenOneOfThemMatches() {
        assertThat(PhraseMatcher.anyPhrase("Mèo co giật liên tục", List.of("khó thở", "co giật"))).isTrue();
        assertThat(PhraseMatcher.anyPhrase("Mèo ăn ngon", List.of("khó thở", "co giật"))).isFalse();
        assertThat(PhraseMatcher.anyPhrase("bất kỳ", List.of())).isFalse();
    }
}
