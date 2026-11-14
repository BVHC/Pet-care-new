package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.content.api.ContentQueryApi;
import com.petcare.module.content.api.ContentQueryApi.ArticleInfo;
import com.petcare.module.content.api.ContentQueryApi.ArticleState;

/**
 * {@link ContentQueryApi} (07 I-12, T90): module {@code ai} đọc bài viết để đồng bộ vào kho tri thức (ST22, BR-AI-30). Bài
 * dựng bằng API của chính module content (không SQL), nên trạng thái và {@code updated_at} là thứ content thật ghi. Era
 * 2004 của {@link ContentItSupport}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class ContentQueryApiIT extends ContentItSupport {

    private static final String PATH = "/api/articles";

    @Autowired
    private ContentQueryApi content;

    private String superManager;
    private long category;
    private String tag;

    @BeforeEach
    void setUp() {
        superManager = token(staff("SUPER_MANAGER", null));
        category = newCategory("qapi", false);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private long draft(String name, String html) {
        Map<String, Object> body = Map.of("categoryId", category, "title", "Bài " + name + " " + tag,
                "slug", name + "-" + tag, "content", html, "authorDisplayName", "Bác sĩ An");
        return number(data(call(HttpMethod.POST, PATH, superManager, body), HttpStatus.CREATED).get("articleId"));
    }

    private void post(long id, String action) {
        data(call(HttpMethod.POST, PATH + "/" + id + "/" + action, superManager, null), HttpStatus.OK);
    }

    private List<Long> publishedIds(Instant since) {
        return content.listPublishedArticles(since).stream().map(ArticleInfo::articleId).toList();
    }

    @Test
    void listsOnlyPublishedArticlesInIdOrderWithTheRawHtmlAndTheTimestamps() {
        long first = draft("a", "<h2>Mở đầu</h2><p>Nội dung <b>A</b></p>");
        long never = draft("b", "<p>Chưa xuất bản</p>");
        long second = draft("c", "<p>Nội dung C</p>");
        post(second, "publish");
        post(first, "publish");

        List<ArticleInfo> listed = content.listPublishedArticles(Instant.EPOCH).stream()
                .filter(article -> List.of(first, never, second).contains(article.articleId())).toList();

        assertThat(listed).extracting(ArticleInfo::articleId).containsExactly(first, second);
        ArticleInfo a = listed.get(0);
        assertThat(a.title()).isEqualTo("Bài a " + tag);
        assertThat(a.content()).as("đúng HTML mà API của content trả cho bài (đã lọc), không lược thẻ").isEqualTo(
                data(call(HttpMethod.GET, PATH + "/" + first, superManager, null), HttpStatus.OK).get("content"))
                .contains("<h2>Mở đầu</h2>").contains("<b>A</b>");
        assertThat(a.authorDisplayName()).isEqualTo("Bác sĩ An");
        assertThat(a.status()).isEqualTo(ArticleState.PUBLISHED);
        assertThat(a.firstPublishedAt()).isNotNull();
        assertThat(a.updatedAt()).isNotNull();
    }

    @Test
    void sinceKeepsOnlyArticlesUpdatedAtOrAfterIt() {
        long early = draft("early", "<p>Sớm</p>");
        post(early, "publish");
        clock.advance(Duration.ofHours(1));
        long late = draft("late", "<p>Muộn</p>");
        post(late, "publish");
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(30));

        assertThat(publishedIds(cutoff)).contains(late).doesNotContain(early);
        assertThat(publishedIds(Instant.EPOCH)).contains(early, late);
    }

    @Test
    void aHiddenArticleIsNotListedButFindArticleStillReturnsItAsHidden() {
        long id = draft("hid", "<p>Sẽ ẩn</p>");
        post(id, "publish");
        post(id, "hide");

        assertThat(publishedIds(Instant.EPOCH)).doesNotContain(id);
        assertThat(content.findArticle(id)).get().extracting(ArticleInfo::status).isEqualTo(ArticleState.HIDDEN);
    }

    @Test
    void findArticleReturnsADraftWithItsContentAndNothingForAnUnknownId() {
        long id = draft("nh", "<p>Nháp</p>");

        ArticleInfo found = content.findArticle(id).orElseThrow();

        assertThat(found.status()).isEqualTo(ArticleState.DRAFT);
        assertThat(found.content()).isEqualTo("<p>Nháp</p>");
        assertThat(found.firstPublishedAt()).isNull();
        assertThat(content.findArticle(999_999_999L)).isEmpty();
    }
}
