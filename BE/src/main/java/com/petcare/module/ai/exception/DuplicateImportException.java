package com.petcare.module.ai.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * File đã được nạp trước đó (BR-AI-30, docs/adr/0134 D1): {@code external_id = FILE-<hash>-<n>} đã có. Hợp đồng ai-v1 #14
 * trả 409; không dùng {@code BusinessRuleViolationException} (cố định 400) mà dùng {@link ErrorCode#CONCURRENCY_CONFLICT},
 * cùng mã với trường hợp hai request nạp đồng thời chạm chỉ mục duy nhất. Message nêu mã tài liệu đã có (kể cả khi nó
 * {@code ARCHIVED}) để người quản lý mở lại thay vì nạp lại.
 */
public class DuplicateImportException extends PlatformException {

    public DuplicateImportException(Long existingDocumentId) {
        super(ErrorCode.CONCURRENCY_CONFLICT,
                "File này đã được nạp trước đó thành tài liệu #" + existingDocumentId + " (BR-AI-30)");
    }
}
