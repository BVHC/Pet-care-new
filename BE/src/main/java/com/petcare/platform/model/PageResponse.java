package com.petcare.platform.model;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Một trang dữ liệu, luôn được bọc trong {@code ApiResponse<PageResponse<T>>}.
 * {@code page} đánh số từ 0 như Spring Data.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
