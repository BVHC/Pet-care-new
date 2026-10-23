package com.petcare.module.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArticleHtmlTest {

    @Test
    void scriptAndEventHandlersAreRemoved() {
        String clean = ArticleHtml.clean("<p onclick=\"x()\">a</p><script>alert(1)</script>");

        assertThat(clean).isEqualTo("<p>a</p>");
    }

    @Test
    void javascriptAndPlainHttpUrlsAreDropped() {
        String clean = ArticleHtml.clean("<a href=\"javascript:alert(1)\">x</a><a href=\"http://e.com\">y</a>"
                + "<img src=\"http://e.com/a.png\">");

        assertThat(clean).doesNotContain("javascript:").doesNotContain("http://");
    }

    @Test
    void httpsLinksAndImagesAreKept() {
        String clean = ArticleHtml.clean("<p><a href=\"https://e.com/x\">x</a><img src=\"https://e.com/a.png\"></p>");

        assertThat(clean).contains("href=\"https://e.com/x\"").contains("src=\"https://e.com/a.png\"");
    }

    @Test
    void formattingTagsSurvive() {
        String clean = ArticleHtml.clean("<h2>T</h2><p><strong>b</strong> <em>i</em></p><ul><li>1</li></ul>");

        assertThat(clean).contains("<h2>T</h2>").contains("<strong>b</strong>").contains("<li>1</li>");
    }

    @Test
    void onlyMarkupLeavesNoText() {
        assertThat(ArticleHtml.hasText(ArticleHtml.clean("<script>x</script><p>  </p>"))).isFalse();
        assertThat(ArticleHtml.hasText(ArticleHtml.clean("<p>Xin chào</p>"))).isTrue();
        assertThat(ArticleHtml.hasText(ArticleHtml.clean("<p><img src=\"https://e.com/a.png\"></p>"))).isTrue();
    }
}
