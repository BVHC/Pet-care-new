package com.petcare.module.ai.service;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/**
 * HTML của bài viết (module content đã lọc, {@code ContentQueryApi} trả nguyên văn) thành văn bản Markdown cho kho tri
 * thức (BR-AI-30, docs/adr/0134 "Đã cài ở T90"): {@code h1}–{@code h6} thành {@code #}–{@code ###} (đúng ba cấp mà
 * {@code DocumentChunker} tách mục), {@code li} thành gạch đầu dòng, thẻ khác bị lược. Hàm thuần: cùng bài thì cùng chữ,
 * nên mã băm ổn định giữa các lượt đồng bộ.
 */
final class ArticleText {

    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00a0]+");
    private static final Pattern AROUND_NEWLINE = Pattern.compile(" ?\\n ?");
    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");
    /** HTML nguồn xuống dòng giữa các {@code li}: gộp lại để một danh sách là một khối, không phải nhiều đoạn một dòng. */
    private static final Pattern LIST_GAP = Pattern.compile("(?m)^(- .*)\\n\\n(?=- )");
    private static final int MAX_HEADING_LEVEL = 3;

    private ArticleText() {
    }

    static String plain(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("script, style").remove();
        for (Element heading : doc.select("h1, h2, h3, h4, h5, h6")) {
            int level = Math.min(heading.normalName().charAt(1) - '0', MAX_HEADING_LEVEL);
            heading.prependChild(new TextNode("\n\n" + "#".repeat(level) + " "));
            heading.appendChild(new TextNode("\n\n"));
        }
        doc.select("li").forEach(item -> item.prependChild(new TextNode("\n- ")));
        doc.select("p, div, blockquote, ul, ol, table").forEach(block -> block.appendChild(new TextNode("\n\n")));
        String text = SPACES.matcher(doc.body().wholeText()).replaceAll(" ");
        text = AROUND_NEWLINE.matcher(text).replaceAll("\n");
        return LIST_GAP.matcher(BLANK_RUNS.matcher(text).replaceAll("\n\n")).replaceAll("$1\n").strip();
    }
}
