package com.petcare.module.content.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Lọc HTML bài viết khi lưu (05 {@code articles.content} = "HTML đã lọc"): bài hiển thị cho người chưa đăng nhập nên
 * script, thuộc tính sự kiện và URL không phải https đều bị bỏ (chống stored XSS). Danh sách thẻ cho phép là
 * {@link Safelist#relaxed()}; liên kết và ảnh chỉ nhận {@code https}.
 */
final class ArticleHtml {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .removeProtocols("a", "href", "ftp", "http", "https", "mailto")
            .addProtocols("a", "href", "https")
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "https");

    private ArticleHtml() {
    }

    static String clean(String html) {
        return Jsoup.clean(html, SAFELIST);
    }

    /** Bản đã lọc còn chữ hoặc ảnh (để từ chối bài rỗng sau khi lọc). */
    static boolean hasText(String cleanHtml) {
        Document doc = Jsoup.parseBodyFragment(cleanHtml);
        return !doc.text().isBlank() || !doc.select("img").isEmpty();
    }
}
