package com.petcare.module.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;

class ContentTextTest {

    @Test
    void nfcNormalizesDecomposedVietnamese() {
        String decomposed = Normalizer.normalize("Chào bạn", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("Chào bạn");
        assertThat(ContentText.normalize(decomposed)).isEqualTo("Chào bạn");
    }

    @Test
    void normalizeStripsOuterWhitespaceAndNullStaysNull() {
        assertThat(ContentText.normalize("  xin chào \n")).isEqualTo("xin chào");
        assertThat(ContentText.normalize(null)).isNull();
    }

    @Test
    void emojiCountsAsOneCodePoint() {
        assertThat("😀".length()).isEqualTo(2);
        assertThat(ContentText.codePoints("😀a")).isEqualTo(2);
    }

    @Test
    void escapeLikeEscapesPercentUnderscoreAndBackslash() {
        assertThat(ContentText.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void slugPattern() {
        assertThat(ContentText.isSlug("tin-tuc-1")).isTrue();
        assertThat(ContentText.isSlug("tin")).isTrue();
        assertThat(ContentText.isSlug("Tin-Tuc")).isFalse();
        assertThat(ContentText.isSlug("tin--tuc")).isFalse();
        assertThat(ContentText.isSlug("-tin")).isFalse();
        assertThat(ContentText.isSlug("tin tuc")).isFalse();
        assertThat(ContentText.isSlug("tín")).isFalse();
        assertThat(ContentText.isSlug("")).isFalse();
    }
}
