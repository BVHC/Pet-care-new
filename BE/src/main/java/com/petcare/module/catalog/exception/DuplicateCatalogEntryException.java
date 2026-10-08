package com.petcare.module.catalog.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Tên / SKU / bộ khóa danh mục đã tồn tại. Hợp đồng catalog-v1 trả 400 cho các trường hợp này và không có mã
 * {@code BR-*} tương ứng, nên không dùng {@code BusinessRuleViolationException} (bắt buộc có rule ID) mà dùng
 * {@link ErrorCode#VALIDATION_FAILED}. Lớp con riêng vì cần status khác 5 lớp dùng chung.
 */
public class DuplicateCatalogEntryException extends PlatformException {

    public DuplicateCatalogEntryException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
