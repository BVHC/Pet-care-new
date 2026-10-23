package com.petcare.module.content.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.PageContentResponse;
import com.petcare.module.content.dto.PublicArticleCardResponse;
import com.petcare.module.content.dto.PublicArticleCategoryResponse;
import com.petcare.module.content.dto.PublicArticleResponse;
import com.petcare.module.content.service.ArticleCategoryService;
import com.petcare.module.content.service.PublicArticleService;
import com.petcare.module.content.service.PageContentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Nội dung công khai (content-v1 #14–17; UC15–UC17). Path public: luôn xử lý như chưa đăng nhập, token gửi kèm bị bỏ
 * qua (docs/adr/0005). Không chứa logic.
 */
@RestController
@RequestMapping("/api/public")
public class PublicContentController {

    private final PageContentService pages;
    private final ArticleCategoryService categories;
    private final PublicArticleService articles;

    public PublicContentController(PageContentService pages, ArticleCategoryService categories,
            PublicArticleService articles) {
        this.pages = pages;
        this.categories = categories;
        this.articles = articles;
    }

    @Operation(operationId = "getPublicPage", tags = "Public", summary = "Nội dung trang công khai",
            description = "UC15–UC17 · —. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "key", in = ParameterIn.PATH, example = "ABOUT")})
    @ApiErrorDoc(status = 404)
    @GetMapping("/pages/{key}")
    public ApiResponse<PageContentResponse> getPublicPage(@PathVariable String key) {
        return ApiResponse.ok(pages.getPublic(key), "Nội dung trang");
    }

    @Operation(operationId = "listPublicArticleCategories", tags = "Public", summary = "Chuyên mục công khai",
            description = "UC17 · BR-BV-03, 04. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —. "
                + "Bỏ chuyên mục ẩn.")
    @ApiErrorDoc(status = 400)
    @GetMapping("/article-categories")
    public ApiResponse<List<PublicArticleCategoryResponse>> listPublicArticleCategories() {
        return ApiResponse.ok(categories.listPublic(), "Danh sách chuyên mục");
    }

    @Operation(operationId = "listPublicArticles", tags = "Public", summary = "Danh sách bài viết",
            description = "UC17 · BR-BV-03, BR-CK-01. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —. "
                + "Chỉ PUBLISHED, mới nhất trước.",
            parameters = {@Parameter(name = "categorySlug", in = ParameterIn.QUERY, example = "cham-soc-thu-cung")})
    @ApiErrorDoc(status = 400)
    @GetMapping("/articles")
    public ApiResponse<PageResponse<PublicArticleCardResponse>> listPublicArticles(
            @RequestParam(required = false) String categorySlug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(articles.list(categorySlug, page, size), "Danh sách bài viết");
    }

    @Operation(operationId = "getPublicArticle", tags = "Public", summary = "Đọc bài viết",
            description = "UC17 · BR-BV-03. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "slug", in = ParameterIn.PATH, example = "tiem-phong-cho-cho")})
    @ApiErrorDoc(status = 404, description = "Không có, hoặc bài DRAFT / HIDDEN (BR-BV-03)")
    @GetMapping("/articles/{slug}")
    public ApiResponse<PublicArticleResponse> getPublicArticle(@PathVariable String slug) {
        return ApiResponse.ok(articles.get(slug), "Bài viết");
    }
}
