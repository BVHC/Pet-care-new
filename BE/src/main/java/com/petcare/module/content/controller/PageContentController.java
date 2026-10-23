package com.petcare.module.content.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.PageContentResponse;
import com.petcare.module.content.dto.UpdatePageContentRequest;
import com.petcare.module.content.service.PageContentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/** Nội dung trang tĩnh (UC21, content-v1 #1–2). Không chứa logic: mọi rule ở {@link PageContentService}. */
@RestController
@RequestMapping("/api/page-contents")
public class PageContentController {

    private final PageContentService pages;

    public PageContentController(PageContentService pages) {
        this.pages = pages;
    }

    @Operation(operationId = "listPageContents", tags = "Pages", summary = "Danh sách nội dung trang",
            description = "UC21 · —. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<List<PageContentResponse>> list() {
        return ApiResponse.ok(pages.list(), "Danh sách nội dung trang");
    }

    @Operation(operationId = "updatePageContent", tags = "Pages", summary = "Sửa nội dung trang",
            description = "UC21 · —. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —.",
            parameters = {@Parameter(name = "key", in = ParameterIn.PATH, example = "ABOUT")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PutMapping("/{key}")
    @PreAuthorize(ContentAccess.SUPER_MANAGER)
    public ApiResponse<PageContentResponse> update(@PathVariable String key,
            @Valid @RequestBody UpdatePageContentRequest request) {
        return ApiResponse.ok(pages.update(key, request), "Đã cập nhật nội dung trang");
    }
}
