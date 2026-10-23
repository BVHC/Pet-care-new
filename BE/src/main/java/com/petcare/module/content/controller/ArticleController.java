package com.petcare.module.content.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.ArticleResponse;
import com.petcare.module.content.dto.ArticleSummaryResponse;
import com.petcare.module.content.dto.CreateArticleRequest;
import com.petcare.module.content.dto.UpdateArticleRequest;
import com.petcare.module.content.entity.ArticleStatus;
import com.petcare.module.content.service.ArticleService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Bài viết (UC81, content-v1 #7–13). Không chứa logic: mọi rule ở {@link ArticleService}. */
@RestController
@RequestMapping("/api/articles")
public class ArticleController {

    private final ArticleService articles;

    public ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @Operation(operationId = "listArticles", tags = "Articles", summary = "Danh sách bài viết",
            description = "UC81 · BR-BV-02. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "PUBLISHED"),
                    @Parameter(name = "categoryId", in = ParameterIn.QUERY, example = "3"),
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "tiêm phòng")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<PageResponse<ArticleSummaryResponse>> list(
            @RequestParam(required = false) ArticleStatus status,
            @RequestParam(required = false) @Positive(message = "categoryId phải là số dương") Long categoryId,
            @RequestParam(required = false) @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(articles.list(status, categoryId, q, page, size), "Danh sách bài viết");
    }

    @Operation(operationId = "createArticle", tags = "Articles", summary = "Soạn bài viết",
            description = "UC81 · BR-BV-01. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: — → DRAFT.")
    @ApiErrorDoc(status = 400, description = "`BR-BV-01` slug trùng, thiếu tiêu đề / chuyên mục / nội dung")
    @ApiErrorDoc(status = 403)
    @PostMapping
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ArticleResponse> create(@Valid @RequestBody CreateArticleRequest request) {
        return ApiResponse.created(articles.create(request), "Đã tạo bài viết");
    }

    @Operation(operationId = "getArticle", tags = "Articles", summary = "Chi tiết bài viết",
            description = "UC81 · —. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "articleId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{articleId}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<ArticleResponse> get(@PathVariable Long articleId) {
        return ApiResponse.ok(articles.get(articleId), "Chi tiết bài viết");
    }

    @Operation(operationId = "updateArticle", tags = "Articles", summary = "Sửa bài viết",
            description = "UC81 · BR-BV-01. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "articleId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-BV-01` đổi slug sau lần xuất bản đầu · slug trùng")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/{articleId}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<ArticleResponse> update(@PathVariable Long articleId,
            @Valid @RequestBody UpdateArticleRequest request) {
        return ApiResponse.ok(articles.update(articleId, request), "Đã cập nhật bài viết");
    }

    @Operation(operationId = "publishArticle", tags = "Articles", summary = "Xuất bản / hiện lại bài",
            description = "UC81 · BR-BV-02. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: "
                + "DRAFT / HIDDEN → PUBLISHED. Lần xuất bản đầu ghi firstPublishedAt và khóa slug.",
            parameters = {@Parameter(name = "articleId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Bài đang PUBLISHED")
    @PostMapping("/{articleId}/publish")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<ArticleResponse> publish(@PathVariable Long articleId) {
        return ApiResponse.ok(articles.publish(articleId), "Đã xuất bản bài viết");
    }

    @Operation(operationId = "hideArticle", tags = "Articles", summary = "Gỡ (ẩn) bài",
            description = "UC81 · BR-BV-02. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: "
                + "PUBLISHED → HIDDEN.",
            parameters = {@Parameter(name = "articleId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Bài không PUBLISHED")
    @PostMapping("/{articleId}/hide")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<ArticleResponse> hide(@PathVariable Long articleId) {
        return ApiResponse.ok(articles.hide(articleId), "Đã gỡ bài viết");
    }

    @Operation(operationId = "deleteArticle", tags = "Articles", summary = "Xóa bài chưa từng xuất bản",
            description = "UC81 · BR-BV-02. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "articleId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-BV-02` bài đã từng xuất bản — chỉ ẩn")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @DeleteMapping("/{articleId}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long articleId) {
        articles.delete(articleId);
    }
}
