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
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Chuyên mục bài viết — content-v1 #3–6, #15 (UC81, UC17; BR-BV-04) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class ArticleCategoryIT extends ContentItSupport {

    private static final String PATH = "/api/article-categories";

    private long superManagerId;
    private String superManager;
    private String tag;

    @BeforeEach
    void setUp() {
        superManagerId = staff("SUPER_MANAGER", null);
        superManager = token(superManagerId);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> create(String name, String slug) {
        return data(call(HttpMethod.POST, PATH, superManager, Map.of("name", name, "slug", slug)), HttpStatus.CREATED);
    }

    private long article(long categoryId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO articles (category_id, title, slug, content, author_display_name, status, created_by)
                VALUES (?, 'T', ?, '<p>x</p>', 'Tác giả', ?, ?) RETURNING id
                """, Long.class, categoryId, "a-" + UUID.randomUUID(), status, superManagerId);
    }

    @Test
    void createStoresNameAndSlugAndShowsZeroArticles() {
        Map<String, Object> created = create("ContentIT Tin tức " + tag, "tin-tuc-" + tag);

        assertThat(created).containsOnlyKeys("categoryId", "name", "slug", "isHidden", "articleCount")
                .containsEntry("slug", "tin-tuc-" + tag).containsEntry("isHidden", false)
                .containsEntry("articleCount", 0);
    }

    @Test
    void duplicateNameOrSlugIs400AndNothingIsWritten() {
        long existing = newCategory("dup " + tag, false);
        String name = jdbc.queryForObject("SELECT name FROM article_categories WHERE id = ?", String.class, existing);
        String slug = jdbc.queryForObject("SELECT slug FROM article_categories WHERE id = ?", String.class, existing);
        long before = jdbc.queryForObject("SELECT count(*) FROM article_categories", Long.class);

        assertError(call(HttpMethod.POST, PATH, superManager, Map.of("name", name, "slug", "khac-" + tag)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, PATH, superManager, Map.of("name", "ContentIT khac " + tag, "slug", slug)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM article_categories", Long.class)).isEqualTo(before);
    }

    @Test
    void slugMustMatchThePattern() {
        for (String slug : List.of("Tin-Tuc", "tin tuc", "tin--tuc", "-tin", "tín")) {
            assertError(call(HttpMethod.POST, PATH, superManager,
                    Map.of("name", "ContentIT s " + tag + slug.length(), "slug", slug)),
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
    }

    @Test
    void blankOrTooLongNameIs400() {
        assertError(call(HttpMethod.POST, PATH, superManager, Map.of("name", "  ", "slug", "ok-" + tag)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, PATH, superManager, Map.of("name", "n".repeat(101), "slug", "ok-" + tag)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void slugInPatchIsIgnoredAndHideAndUnhideWork() {
        long id = newCategory("patch " + tag, false);
        String slug = jdbc.queryForObject("SELECT slug FROM article_categories WHERE id = ?", String.class, id);

        Map<String, Object> hidden = data(call(HttpMethod.PATCH, PATH + "/" + id, superManager,
                Map.of("isHidden", true, "slug", "doi-slug")), HttpStatus.OK);
        assertThat(hidden).containsEntry("isHidden", true).containsEntry("slug", slug);

        Map<String, Object> renamed = data(call(HttpMethod.PATCH, PATH + "/" + id, superManager,
                Map.of("name", "ContentIT mới " + tag, "isHidden", false)), HttpStatus.OK);
        assertThat(renamed).containsEntry("isHidden", false).containsEntry("name", "ContentIT mới " + tag);
    }

    @Test
    void renameToAnExistingNameIs400AndUnknownIdIs404() {
        long a = newCategory("a " + tag, false);
        long b = newCategory("b " + tag, false);
        String nameOfB = jdbc.queryForObject("SELECT name FROM article_categories WHERE id = ?", String.class, b);

        assertError(call(HttpMethod.PATCH, PATH + "/" + a, superManager, Map.of("name", nameOfB)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/999999999", superManager, Map.of("isHidden", true)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void deleteWithArticlesIsBrBv04WithTheCount() {
        long id = newCategory("full " + tag, false);
        article(id, "DRAFT");
        article(id, "HIDDEN");

        org.springframework.http.ResponseEntity<Map<String, Object>> refused = call(HttpMethod.DELETE, PATH + "/" + id,
                superManager, null);
        assertRule(refused, "BR-BV-04");
        assertThat((String) refused.getBody().get("message")).contains("2 bài");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM article_categories WHERE id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    void deleteEmptyCategoryIs204AndUnknownIs404() {
        long id = newCategory("empty " + tag, false);

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, superManager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM article_categories WHERE id = ?", Long.class, id))
                .isZero();
        assertError(call(HttpMethod.DELETE, PATH + "/" + id, superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void articleCountCountsEveryStatusAndListIsSortedByName() {
        long first = newCategory("aaa " + tag, false);
        long second = newCategory("bbb " + tag, true);
        article(first, "DRAFT");
        article(first, "PUBLISHED");
        article(first, "HIDDEN");

        List<Map<String, Object>> all = list(call(HttpMethod.GET, PATH, superManager, null), HttpStatus.OK);

        Map<String, Object> a = all.stream().filter(c -> number(c.get("categoryId")) == first).findFirst().orElseThrow();
        Map<String, Object> b = all.stream().filter(c -> number(c.get("categoryId")) == second).findFirst().orElseThrow();
        assertThat(a).containsEntry("articleCount", 3);
        assertThat(b).containsEntry("articleCount", 0).containsEntry("isHidden", true);
        assertThat(all.indexOf(a)).isLessThan(all.indexOf(b));
    }

    @Test
    void publicListExcludesHiddenCategoriesAndShowsOnlyThreeFields() {
        long visible = newCategory("visible " + tag, false);
        long hidden = newCategory("hidden " + tag, true);

        List<Map<String, Object>> categories = list(call(HttpMethod.GET, "/api/public/article-categories", null, null),
                HttpStatus.OK);

        assertThat(categories.stream().map(c -> number(c.get("categoryId"))).toList()).contains(visible)
                .doesNotContain(hidden);
        assertThat(categories.get(0)).containsOnlyKeys("categoryId", "name", "slug");
    }

    @Test
    void onlySuperManagerManagesCategories() {
        for (String role : List.of("ADMIN", "BRANCH_MANAGER", "RECEPTIONIST")) {
            String token = token(staff(role, newBranch("ACTIVE")));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, PATH, token, Map.of("name", "x", "slug", "x")), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, token(onlineCustomer(false)[0]), null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}
