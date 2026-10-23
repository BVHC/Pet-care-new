package com.petcare.module.content.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.ArticleCategoryRequest;
import com.petcare.module.content.dto.ArticleCategoryResponse;
import com.petcare.module.content.dto.UpdateArticleCategoryRequest;
import com.petcare.module.content.service.ArticleCategoryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Chuyên mục bài viết (UC81, content-v1 #3–6). Không chứa logic: mọi rule ở {@link ArticleCategoryService}. */
@RestController
@RequestMapping("/api/article-categories")
public class ArticleCategoryController {

    private final ArticleCategoryService categories;

    public ArticleCategoryController(ArticleCategoryService categories) {
        this.categories = categories;
    }

    @Operation(operationId = "listArticleCategories", tags = "Article categories", summary = "Danh sách chuyên mục",
            description = "UC81 · BR-BV-04. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<List<ArticleCategoryResponse>> list() {
        return ApiResponse.ok(categories.list(), "Danh sách chuyên mục");
    }

    @Operation(operationId = "createArticleCategory", tags = "Article categories", summary = "Tạo chuyên mục",
            description = "UC81 · BR-BV-04. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400, description = "Tên hoặc slug trùng")
    @ApiErrorDoc(status = 403)
    @PostMapping
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ArticleCategoryResponse> create(@Valid @RequestBody ArticleCategoryRequest request) {
        return ApiResponse.created(categories.create(request), "Đã tạo chuyên mục");
    }

    @Operation(operationId = "updateArticleCategory", tags = "Article categories", summary = "Sửa / ẩn chuyên mục",
            description = "UC81 · BR-BV-04. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. "
                + "Chuyên mục ẩn không hiện ở bộ lọc công khai; bài trong đó vẫn truy cập được theo đường dẫn.",
            parameters = {@Parameter(name = "categoryId", in = ParameterIn.PATH, example = "3")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/{categoryId}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<ArticleCategoryResponse> update(@PathVariable Long categoryId,
            @Valid @RequestBody UpdateArticleCategoryRequest request) {
        return ApiResponse.ok(categories.update(categoryId, request), "Đã cập nhật chuyên mục");
    }

    @Operation(operationId = "deleteArticleCategory", tags = "Article categories", summary = "Xóa chuyên mục rỗng",
            description = "UC81 · BR-BV-04. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "categoryId", in = ParameterIn.PATH, example = "3")})
    @ApiErrorDoc(status = 400, description = "`BR-BV-04` còn bài viết (message ghi số bài) — chỉ ẩn")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @DeleteMapping("/{categoryId}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long categoryId) {
        categories.delete(categoryId);
    }
}
