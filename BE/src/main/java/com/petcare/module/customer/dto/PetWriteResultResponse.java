package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả thêm / sửa thú (customer-v1 {@code PetWriteResult}). {@code duplicateNameWarning}: chủ đã có thú khác cùng
 * tên (không phân biệt hoa thường) và cùng loài — cảnh báo, không chặn (BR-KH-02).
 */
public record PetWriteResultResponse(
        @Schema(description = "Thú cưng sau khi ghi")
        PetResponse pet,

        @Schema(description = "Chủ đã có thú khác cùng tên, cùng loài: nghi trùng (BR-KH-02)", example = "false")
        boolean duplicateNameWarning) {
}
