package com.petcare.platform.model;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một trang dữ liệu, luôn được bọc trong {@code ApiResponse<PageResponse<T>>}.
 * {@code page} đánh số từ 0 như Spring Data.
 */
public record PageResponse<T>(
        @Schema(description = "Các phần tử của trang")
        List<T> content,

        @Schema(description = "Số trang, bắt đầu từ 0", example = "0")
        int page,

        @Schema(description = "Số phần tử mỗi trang", example = "20")
        int size,

        @Schema(description = "Tổng số phần tử khớp bộ lọc", example = "57")
        long totalElements,

        @Schema(description = "Tổng số trang", example = "3")
        int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
