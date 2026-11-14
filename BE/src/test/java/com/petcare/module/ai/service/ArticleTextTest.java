package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * HTML của bài viết (đã lọc ở module content) thành văn bản Markdown cho kho tri thức (docs/adr/0134 "Đã cài ở T90"):
 * tiêu đề {@code h1}–{@code h6} thành {@code #}–{@code ###} để {@code DocumentChunker} tách mục, danh sách thành gạch đầu
 * dòng, thẻ khác bị lược, ký tự HTML được giải mã. Hàm thuần: cùng bài thì cùng chữ, nên mã băm ổn định.
 */
class ArticleTextTest {

    @Test
    void headingsBecomeMarkdownHeadingsAndParagraphsAreSeparatedByOneBlankLine() {
        assertThat(ArticleText.plain("<h2>Mở đầu</h2><p>Nội dung <b>A</b></p><p>Đoạn hai</p>"))
                .isEqualTo("## Mở đầu\n\nNội dung A\n\nĐoạn hai");
    }

    @Test
    void headingLevelsAreCappedAtThreeLikeTheChunker() {
        assertThat(ArticleText.plain("<h1>Một</h1><h3>Ba</h3><h5>Năm</h5>")).isEqualTo("# Một\n\n### Ba\n\n### Năm");
    }

    @Test
    void listItemsBecomeDashLines() {
        assertThat(ArticleText.plain("<p>Chuẩn bị:</p><ul><li>Khăn tắm</li><li>Sữa tắm</li></ul><p>Xong.</p>"))
                .isEqualTo("Chuẩn bị:\n\n- Khăn tắm\n- Sữa tắm\n\nXong.");
    }

    /** HTML đã lọc của content có xuống dòng giữa các {@code li}: danh sách vẫn là một khối liền, đoạn văn trước nó vẫn cách một dòng trống. */
    @Test
    void aListWrittenOverSeveralSourceLinesStaysOneBlock() {
        assertThat(ArticleText.plain("<p>Đưa đi khám nếu:</p>\n<ul>\n  <li>Nôn nhiều</li>\n\n  <li>Lờ đờ</li>\n  <li>Thở nhanh</li>\n</ul>\n<p>Gọi ngay.</p>"))
                .isEqualTo("Đưa đi khám nếu:\n\n- Nôn nhiều\n- Lờ đờ\n- Thở nhanh\n\nGọi ngay.");
    }

    @Test
    void lineBreaksAreKeptAndHtmlEntitiesAreDecoded() {
        assertThat(ArticleText.plain("<p>Tắm &amp; sấy<br>giá &lt; 200&nbsp;000</p>")).isEqualTo("Tắm & sấy\ngiá < 200 000");
    }

    @Test
    void scriptAndStyleNeverReachTheKnowledgeBase() {
        assertThat(ArticleText.plain("<p>Chữ</p><script>alert(1)</script><style>p{color:red}</style>")).isEqualTo("Chữ");
    }

    @Test
    void sourceIndentationAndRunsOfBlankLinesAreCollapsed() {
        assertThat(ArticleText.plain("<div>\n    <p>Một</p>\n\n\n\n    <p>Hai</p>\n</div>")).isEqualTo("Một\n\nHai");
    }

    @Test
    void emptyOrTagOnlyInputGivesAnEmptyString() {
        assertThat(ArticleText.plain(null)).isEmpty();
        assertThat(ArticleText.plain("   ")).isEmpty();
        assertThat(ArticleText.plain("<p> </p><br>")).isEmpty();
    }

    @Test
    void theSameArticleAlwaysGivesTheSameTextSoTheHashIsStable() {
        String html = "<h2>Tắm</h2><p>Bao lâu một lần?</p>";

        assertThat(ArticleText.plain(html)).isEqualTo(ArticleText.plain(html));
    }
}
