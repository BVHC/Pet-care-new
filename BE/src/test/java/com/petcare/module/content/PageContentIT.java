package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Nội dung trang tĩnh — content-v1 #1, #2, #14 (UC21, UC15–17) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class PageContentIT extends ContentItSupport {

    private static final String PATH = "/api/page-contents";

    private long superManagerId;
    private String superManager;

    @BeforeEach
    void setUp() {
        superManagerId = staff("SUPER_MANAGER", null);
        superManager = token(superManagerId);
    }

    @Test
    void listReturnsFourKeysInEnumOrder() {
        List<Map<String, Object>> pages = list(call(HttpMethod.GET, PATH, superManager, null), HttpStatus.OK);

        assertThat(pages.stream().map(p -> p.get("key")).toList())
                .containsExactly("HOME_BANNER", "ABOUT", "POLICY", "FAQ");
        assertThat(pages.get(0)).containsOnlyKeys("key", "title", "content", "updatedAt");
    }

    @Test
    void updateStoresTitleContentAndUpdatedBy() {
        Map<String, Object> body = Map.of("title", "Về chúng tôi", "content",
                Map.of("sections", List.of(Map.of("heading", "Sứ mệnh", "body", "Chăm sóc thú cưng"))));

        Map<String, Object> updated = data(call(HttpMethod.PUT, PATH + "/ABOUT", superManager, body), HttpStatus.OK);

        assertThat(updated).containsEntry("key", "ABOUT").containsEntry("title", "Về chúng tôi");
        assertThat(map(updated.get("content"))).containsKey("sections");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM page_contents WHERE key = 'ABOUT'", Long.class))
                .isEqualTo(superManagerId);
        Map<String, Object> publicPage = data(call(HttpMethod.GET, "/api/public/pages/ABOUT", null, null),
                HttpStatus.OK);
        assertThat(publicPage).containsEntry("title", "Về chúng tôi");
    }

    @Test
    void unknownOrLowercaseKeyIs404OnPutAndPublicGet() {
        Map<String, Object> body = Map.of("content", Map.of());

        assertError(call(HttpMethod.PUT, PATH + "/NOPE", superManager, body), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.PUT, PATH + "/about", superManager, body), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.GET, "/api/public/pages/abc", null, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.GET, "/api/public/pages/about", null, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void contentThatIsNotAJsonObjectIs400() {
        for (String raw : List.of("{\"content\": []}", "{\"content\": \"x\"}", "{\"content\": null}", "{}",
                "{\"content\": 5}")) {
            assertError(callRaw(HttpMethod.PUT, PATH + "/FAQ", superManager, raw), HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED");
        }
        assertThat(jdbc.queryForObject("SELECT content::text FROM page_contents WHERE key = 'FAQ'", String.class))
                .isEqualTo("{}");
    }

    @Test
    void contentOver64KbOrTitleOver250IsRejected() {
        String big = "x".repeat(70_000);

        assertError(call(HttpMethod.PUT, PATH + "/FAQ", superManager, Map.of("content", Map.of("a", big))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PUT, PATH + "/FAQ", superManager,
                Map.of("title", "t".repeat(251), "content", Map.of())), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void onlySuperManagerManagesPagesAndPublicNeedsNoToken() {
        for (String role : List.of("ADMIN", "BRANCH_MANAGER", "RECEPTIONIST", "VET")) {
            String token = token(staff(role, newBranch("ACTIVE")));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.PUT, PATH + "/FAQ", token, Map.of("content", Map.of())),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        String customer = token(onlineCustomer(false)[0]);
        assertError(call(HttpMethod.GET, PATH, customer, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(call(HttpMethod.GET, "/api/public/pages/FAQ", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
