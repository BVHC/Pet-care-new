package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Bài viết công khai — content-v1 #16, #17 (UC17; BR-BV-03, 04, BR-CK-01) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class PublicArticleIT extends ContentItSupport {

    private static final String LIST = "/api/public/articles";

    private long authorId;
    private String tag;

    @BeforeEach
    void setUp() {
        authorId = staff("SUPER_MANAGER", null);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private String slugOf(long categoryId) {
        return jdbc.queryForObject("SELECT slug FROM article_categories WHERE id = ?", String.class, categoryId);
    }

    private long article(long categoryId, String slug, String status, String publishedAt) {
        return jdbc.queryForObject("""
                INSERT INTO articles (category_id, title, slug, summary, content, author_display_name, status,
                                      first_published_at, created_by)
                VALUES (?, ?, ?, 'Tóm tắt', '<p>Nội dung <b>đậm</b></p>', 'BS. An', ?, CAST(? AS timestamptz), ?)
                RETURNING id
                """, Long.class, categoryId, "Tiêu đề " + slug, slug, status, publishedAt, authorId);
    }

    private List<Long> ids(Map<String, Object> page) {
        return pageContent(page).stream().map(x -> number(x.get("articleId"))).toList();
    }

    @Test
    void onlyPublishedNewestFirstWithCardFieldsAndNoContent() {
        long category = newCategory("pub", false);
        long old = article(category, "cu-" + tag, "PUBLISHED", "2004-03-01T00:00:00Z");
        long recent = article(category, "moi-" + tag, "PUBLISHED", "2004-03-09T00:00:00Z");
        long draft = article(category, "nhap-" + tag, "DRAFT", null);
        long hidden = article(category, "an-" + tag, "HIDDEN", "2004-03-05T00:00:00Z");

        Map<String, Object> page = data(call(HttpMethod.GET, LIST + "?categorySlug=" + slugOf(category), null, null),
                HttpStatus.OK);

        assertThat(ids(page)).containsExactly(recent, old).doesNotContain(draft, hidden);
        assertThat(pageContent(page).get(0)).containsOnlyKeys("articleId", "title", "slug", "categoryName",
                        "categorySlug", "coverImageUrl", "summary", "authorDisplayName", "publishedAt")
                .containsEntry("publishedAt", "2004-03-09T00:00:00Z").containsEntry("categorySlug", slugOf(category));
    }

    @Test
    void categoryFilterNarrowsAndAHiddenCategoryStillFilters() {
        long visible = newCategory("v", false);
        long hiddenCategory = newCategory("h", true);
        long inVisible = article(visible, "trong-v-" + tag, "PUBLISHED", "2004-03-02T00:00:00Z");
        long inHidden = article(hiddenCategory, "trong-h-" + tag, "PUBLISHED", "2004-03-03T00:00:00Z");

        assertThat(ids(data(call(HttpMethod.GET, LIST + "?categorySlug=" + slugOf(visible), null, null),
                HttpStatus.OK))).containsExactly(inVisible);
        assertThat(ids(data(call(HttpMethod.GET, LIST + "?categorySlug=" + slugOf(hiddenCategory), null, null),
                HttpStatus.OK))).containsExactly(inHidden);
        assertThat(ids(data(call(HttpMethod.GET, LIST + "?size=100", null, null), HttpStatus.OK)))
                .contains(inVisible, inHidden);
    }

    @Test
    void unknownCategorySlugGivesAnEmptyPage() {
        Map<String, Object> page = data(call(HttpMethod.GET, LIST + "?categorySlug=khong-co-" + tag, null, null),
                HttpStatus.OK);

        assertThat(pageContent(page)).isEmpty();
        assertThat(number(page.get("totalElements"))).isZero();
    }

    @Test
    void sizeIsClampedToOneHundred() {
        assertThat(number(data(call(HttpMethod.GET, LIST + "?size=500", null, null), HttpStatus.OK).get("size")))
                .isEqualTo(100);
    }

    @Test
    void readPublishedArticleByItsSlugEvenInAHiddenCategory() {
        long category = newCategory("doc", true);
        article(category, "doc-" + tag, "PUBLISHED", "2004-03-04T00:00:00Z");

        Map<String, Object> found = data(call(HttpMethod.GET, LIST + "/doc-" + tag, null, null), HttpStatus.OK);

        assertThat(found).containsOnlyKeys("articleId", "title", "slug", "categoryName", "categorySlug",
                        "coverImageUrl", "summary", "content", "authorDisplayName", "publishedAt")
                .containsEntry("content", "<p>Nội dung <b>đậm</b></p>")
                .containsEntry("publishedAt", "2004-03-04T00:00:00Z");
    }

    @Test
    void draftHiddenAndUnknownSlugAllReturnTheSame404() {
        long category = newCategory("404", false);
        article(category, "nhap-404-" + tag, "DRAFT", null);
        article(category, "an-404-" + tag, "HIDDEN", "2004-03-05T00:00:00Z");

        ResponseEntity<Map<String, Object>> draft = call(HttpMethod.GET, LIST + "/nhap-404-" + tag, null, null);
        ResponseEntity<Map<String, Object>> hidden = call(HttpMethod.GET, LIST + "/an-404-" + tag, null, null);
        ResponseEntity<Map<String, Object>> unknown = call(HttpMethod.GET, LIST + "/khong-co-404-" + tag, null, null);

        for (ResponseEntity<Map<String, Object>> response : List.of(draft, hidden, unknown)) {
            assertError(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
        assertThat(hidden.getBody().get("message").toString().replace("an-404-" + tag, "X"))
                .isEqualTo(draft.getBody().get("message").toString().replace("nhap-404-" + tag, "X"))
                .isEqualTo(unknown.getBody().get("message").toString().replace("khong-co-404-" + tag, "X"));
    }

    @Test
    void aGarbageTokenIsIgnoredOnPublicPaths() {
        long category = newCategory("tok", false);
        article(category, "tok-" + tag, "PUBLISHED", "2004-03-04T00:00:00Z");

        assertThat(call(HttpMethod.GET, LIST + "/tok-" + tag, "rac.khong.phai.token", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
