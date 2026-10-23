package com.petcare.module.content.service;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.dto.PageContentResponse;
import com.petcare.module.content.dto.UpdatePageContentRequest;
import com.petcare.module.content.entity.PageContent;
import com.petcare.module.content.entity.PageKey;
import com.petcare.module.content.exception.ContentValidationException;
import com.petcare.module.content.repository.PageContentRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Nội dung trang tĩnh (content-v1 #1, #2, #14; UC21). Bốn khóa cố định, mỗi khóa một dòng seed ở V21 nên chỉ sửa,
 * không tạo. Khóa lạ (kể cả chữ thường) là 404. Không audit (BR-QT-15 không gồm nội dung trang).
 */
@Service
public class PageContentService {

    static final int MAX_CONTENT_BYTES = 64 * 1024;

    private final PageContentRepository pages;
    private final BranchScope scope;

    public PageContentService(PageContentRepository pages, BranchScope scope) {
        this.pages = pages;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public List<PageContentResponse> list() {
        return pages.findAll().stream()
                .sorted(Comparator.comparingInt(p -> PageKey.valueOf(p.getKey()).ordinal()))
                .map(PageContentService::toResponse).toList();
    }

    /** Khóa dòng → ghi → flush; chỉ người ghi {@code updated_by} đổi, nội dung giống cũ thì không có UPDATE. */
    @Transactional
    public PageContentResponse update(String key, UpdatePageContentRequest request) {
        Long actorId = scope.current().accountId();
        PageKey pageKey = parse(key);
        if (!request.content().isObject()) {
            throw new ContentValidationException("Nội dung trang phải là một đối tượng JSON");
        }
        if (request.content().toString().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new ContentValidationException("Nội dung trang tối đa 64 KB");
        }
        PageContent page = pages.findByKeyForUpdate(pageKey.name())
                .orElseThrow(() -> new ResourceNotFoundException("trang", key));
        page.setTitle(request.title());
        page.setContent(request.content());
        page.setUpdatedBy(actorId);
        return toResponse(pages.saveAndFlush(page));
    }

    @Transactional(readOnly = true)
    public PageContentResponse getPublic(String key) {
        return pages.findById(parse(key).name()).map(PageContentService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("trang", key));
    }

    private static PageKey parse(String key) {
        return Arrays.stream(PageKey.values()).filter(k -> k.name().equals(key)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("trang", key));
    }

    private static PageContentResponse toResponse(PageContent page) {
        return new PageContentResponse(PageKey.valueOf(page.getKey()), page.getTitle(), page.getContent(),
                page.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }
}
