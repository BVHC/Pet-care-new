package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/** Quản lý bài viết — content-v1 #7–13 (UC81; BR-BV-01, 02) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class ArticleIT extends ContentItSupport {

    private static final String PATH = "/api/articles";

    private long superManagerId;
    private String superManager;
    private long category;
    private String tag;

    @BeforeEach
    void setUp() {
        superManagerId = staff("SUPER_MANAGER", null);
        superManager = token(superManagerId);
        category = newCategory("art", false);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> body(String slug) {
        return Map.of("categoryId", category, "title", "Tiêu đề " + tag, "slug", slug, "content", "<p>Nội dung</p>",
                "authorDisplayName", "Bác sĩ An");
    }

    private ResponseEntity<Map<String, Object>> create(String slug) {
        return call(HttpMethod.POST, PATH, superManager, body(slug));
    }

    private long createdId(String slug) {
        return number(data(create(slug), HttpStatus.CREATED).get("articleId"));
    }

    // ---------------------------------------------------------------- soạn

    @Test
    void createIsDraftWithCreatedByAndCleanHtml() {
        Map<String, Object> request = new java.util.HashMap<>(body("bai-" + tag));
        request.put("content", "<p onclick=\"x()\">Chào</p><script>alert(1)</script>");

        Map<String, Object> created = data(call(HttpMethod.POST, PATH, superManager, request), HttpStatus.CREATED);

        assertThat(created).containsOnlyKeys("articleId", "categoryId", "title", "slug", "coverImageUrl", "summary",
                        "content", "authorDisplayName", "status", "firstPublishedAt", "createdAt", "updatedAt")
                .containsEntry("status", "DRAFT").containsEntry("content", "<p>Chào</p>")
                .containsEntry("firstPublishedAt", null);
        assertThat(jdbc.queryForObject("SELECT created_by FROM articles WHERE id = ?", Long.class,
                number(created.get("articleId")))).isEqualTo(superManagerId);
    }

    @Test
    void contentEmptyAfterCleaningIsBrBv01() {
        Map<String, Object> request = new java.util.HashMap<>(body("rong-" + tag));
        request.put("content", "<script>alert(1)</script>");

        assertRule(call(HttpMethod.POST, PATH, superManager, request), "BR-BV-01");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM articles WHERE slug = ?", Long.class, "rong-" + tag))
                .isZero();
    }

    @Test
    void duplicateSlugIsBrBv01() {
        createdId("trung-" + tag);

        assertRule(create("trung-" + tag), "BR-BV-01");
    }

    @Test
    void missingTitleOrContentOrBadFieldsAre400() {
        for (String field : List.of("title", "content", "authorDisplayName", "categoryId", "slug")) {
            Map<String, Object> request = new java.util.HashMap<>(body("thieu-" + field + "-" + tag));
            request.remove(field);
            assertError(call(HttpMethod.POST, PATH, superManager, request), HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED");
        }
        for (Map.Entry<String, Object> bad : Map.<String, Object>of("slug", "Sai Slug", "coverImageUrl",
                "http://e.com/a.png", "summary", "s".repeat(501)).entrySet()) {
            Map<String, Object> request = new java.util.HashMap<>(body("loi-" + tag));
            request.put(bad.getKey(), bad.getValue());
            assertError(call(HttpMethod.POST, PATH, superManager, request), HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED");
        }
    }

    @Test
    void unknownCategoryIs404() {
        Map<String, Object> request = new java.util.HashMap<>(body("khong-cm-" + tag));
        request.put("categoryId", 999_999_999L);

        assertError(call(HttpMethod.POST, PATH, superManager, request), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void slugChangeIsAllowedWhileDraftButLockedAfterFirstPublish() {
        long id = createdId("nhap-" + tag);

        Map<String, Object> renamed = data(call(HttpMethod.PATCH, PATH + "/" + id, superManager,
                Map.of("slug", "nhap-moi-" + tag)), HttpStatus.OK);
        assertThat(renamed).containsEntry("slug", "nhap-moi-" + tag);

        data(call(HttpMethod.POST, PATH + "/" + id + "/publish", superManager, null), HttpStatus.OK);

        assertRule(call(HttpMethod.PATCH, PATH + "/" + id, superManager, Map.of("slug", "doi-nua-" + tag)),
                "BR-BV-01");
        Map<String, Object> same = data(call(HttpMethod.PATCH, PATH + "/" + id, superManager,
                Map.of("slug", "nhap-moi-" + tag, "title", "Tiêu đề sửa")), HttpStatus.OK);
        assertThat(same).containsEntry("title", "Tiêu đề sửa").containsEntry("slug", "nhap-moi-" + tag);
    }

    @Test
    void renameToAnExistingSlugIsBrBv01AndMovingToAnotherCategoryWorks() {
        createdId("co-san-" + tag);
        long id = createdId("sua-" + tag);
        long other = newCategory("other", false);

        assertRule(call(HttpMethod.PATCH, PATH + "/" + id, superManager, Map.of("slug", "co-san-" + tag)),
                "BR-BV-01");
        Map<String, Object> moved = data(call(HttpMethod.PATCH, PATH + "/" + id, superManager,
                Map.of("categoryId", other)), HttpStatus.OK);

        assertThat(number(moved.get("categoryId"))).isEqualTo(other);
        assertError(call(HttpMethod.PATCH, PATH + "/" + id, superManager, Map.of("categoryId", 999_999_999L)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.PATCH, PATH + "/999999999", superManager, Map.of("title", "x")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- vòng đời

    @Test
    void publishSetsFirstPublishedAtOnceAndRepublishKeepsIt() {
        long id = createdId("vong-doi-" + tag);

        Map<String, Object> published = data(call(HttpMethod.POST, PATH + "/" + id + "/publish", superManager, null),
                HttpStatus.OK);
        assertThat(published).containsEntry("status", "PUBLISHED")
                .containsEntry("firstPublishedAt", "2004-03-10T03:00:00Z");

        clock.advance(Duration.ofMinutes(1));
        data(call(HttpMethod.POST, PATH + "/" + id + "/hide", superManager, null), HttpStatus.OK);
        Map<String, Object> again = data(call(HttpMethod.POST, PATH + "/" + id + "/publish", superManager, null),
                HttpStatus.OK);

        assertThat(again).containsEntry("status", "PUBLISHED").containsEntry("firstPublishedAt", "2004-03-10T03:00:00Z");
    }

    @Test
    void publishWhilePublishedAndHideWhenNotPublishedAre409() {
        long id = createdId("409-" + tag);

        assertError(call(HttpMethod.POST, PATH + "/" + id + "/hide", superManager, null), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        data(call(HttpMethod.POST, PATH + "/" + id + "/publish", superManager, null), HttpStatus.OK);
        assertError(call(HttpMethod.POST, PATH + "/" + id + "/publish", superManager, null), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        data(call(HttpMethod.POST, PATH + "/" + id + "/hide", superManager, null), HttpStatus.OK);
        assertError(call(HttpMethod.POST, PATH + "/" + id + "/hide", superManager, null), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertError(call(HttpMethod.POST, PATH + "/999999999/publish", superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void deleteDraftIs204ButEverPublishedIsBrBv02EvenWhenHidden() {
        long draft = createdId("xoa-" + tag);
        long published = createdId("da-dang-" + tag);
        data(call(HttpMethod.POST, PATH + "/" + published + "/publish", superManager, null), HttpStatus.OK);
        data(call(HttpMethod.POST, PATH + "/" + published + "/hide", superManager, null), HttpStatus.OK);

        assertThat(call(HttpMethod.DELETE, PATH + "/" + draft, superManager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertRule(call(HttpMethod.DELETE, PATH + "/" + published, superManager, null), "BR-BV-02");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM articles WHERE id IN (?, ?)", Long.class, draft,
                published)).isEqualTo(1L);
        assertError(call(HttpMethod.DELETE, PATH + "/" + draft, superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- danh sách / chi tiết

    @Test
    void listFiltersByStatusCategoryAndTitleAndEscapesLike() {
        long a = createdId("ds-a-" + tag);
        long b = createdId("ds-b-" + tag);
        data(call(HttpMethod.POST, PATH + "/" + b + "/publish", superManager, null), HttpStatus.OK);
        long other = newCategory("lst", false);
        jdbc.update("UPDATE articles SET title = 'Giảm 50% hôm nay " + tag + "' WHERE id = ?", a);

        Map<String, Object> published = data(call(HttpMethod.GET, PATH + "?status=PUBLISHED&categoryId=" + category,
                superManager, null), HttpStatus.OK);
        Map<String, Object> byTitle = data(call(HttpMethod.GET, PATH + "?q=50% hôm&size=100", superManager, null),
                HttpStatus.OK);
        Map<String, Object> emptyCategory = data(call(HttpMethod.GET, PATH + "?categoryId=" + other, superManager,
                null), HttpStatus.OK);

        assertThat(pageContent(published).stream().map(x -> number(x.get("articleId"))).toList()).containsExactly(b);
        assertThat(pageContent(byTitle).stream().map(x -> number(x.get("articleId"))).toList()).contains(a);
        assertThat(pageContent(emptyCategory)).isEmpty();
        assertThat(pageContent(published).get(0)).containsOnlyKeys("articleId", "categoryId", "title", "slug",
                "status", "firstPublishedAt", "updatedAt");
        assertError(call(HttpMethod.GET, PATH + "?status=NOPE", superManager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(call(HttpMethod.GET, PATH + "?q=" + "a".repeat(101), superManager, null), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
    }

    @Test
    void detailReturnsTheFullArticleAnd404ForUnknown() {
        long id = createdId("ct-" + tag);

        assertThat(data(call(HttpMethod.GET, PATH + "/" + id, superManager, null), HttpStatus.OK))
                .containsEntry("slug", "ct-" + tag);
        assertError(call(HttpMethod.GET, PATH + "/999999999", superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void onlySuperManagerManagesArticles() {
        long id = createdId("quyen-" + tag);
        for (String role : List.of("ADMIN", "BRANCH_MANAGER", "VET")) {
            String token = token(staff(role, newBranch("ACTIVE")));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, PATH + "/" + id + "/publish", token, null), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
            assertError(call(HttpMethod.DELETE, PATH + "/" + id, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, token(onlineCustomer(false)[0]), null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- đua

    /** Bài đang tạo (giữ FOR SHARE chuyên mục + đã INSERT) thì xóa chuyên mục phải chờ rồi thấy bài: 400 BR-BV-04. */
    @Test
    void deleteCategoryRacingArticleCreateNeverOrphans() throws Exception {
        long raced = newCategory("race", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> holding = new CompletableFuture<>();
        try {
            CompletableFuture<Void> creator = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM article_categories WHERE id = ? FOR SHARE", Long.class,
                                raced);
                        jdbc.update("""
                                INSERT INTO articles (category_id, title, slug, content, author_display_name, status,
                                                      created_by)
                                VALUES (?, 'Đua', ?, '<p>x</p>', 'Tác giả', 'DRAFT', ?)
                                """, raced, "dua-" + tag, superManagerId);
                        holding.complete(null);
                        release.join();
                    }), pool);
            holding.get(10, TimeUnit.SECONDS);

            CompletableFuture<ResponseEntity<Map<String, Object>>> delete = CompletableFuture.supplyAsync(() ->
                    call(HttpMethod.DELETE, "/api/article-categories/" + raced, superManager, null), pool);
            waitUntilSomeoneWaitsOnALock();
            release.complete(null);
            creator.get(10, TimeUnit.SECONDS);

            assertRule(delete.get(10, TimeUnit.SECONDS), "BR-BV-04");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM articles WHERE category_id = ?", Long.class, raced))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM article_categories WHERE id = ?", Long.class, raced))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM articles a LEFT JOIN article_categories c ON c.id = a.category_id
                    WHERE c.id IS NULL
                    """, Long.class)).isZero();
        } finally {
            release.complete(null);
            pool.shutdownNow();
        }
    }

    private void waitUntilSomeoneWaitsOnALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Không có phiên nào chờ khóa sau 10 giây");
    }
}
