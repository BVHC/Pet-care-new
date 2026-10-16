package com.petcare.module.sales.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Yêu cầu thêm / sửa dòng sai hình dạng hoặc tham chiếu danh mục không dùng được (thiếu {@code serviceId} /
 * {@code productId}, tăng số lượng, dịch vụ ngừng kinh doanh…). Hợp đồng sales-v1 trả 400 và không có mã {@code BR-*}
 * cho các trường hợp này nên dùng {@link ErrorCode#VALIDATION_FAILED}, giống {@code DuplicateCatalogEntryException}.
 */
public class InvalidOrderLineRequestException extends PlatformException {

    public InvalidOrderLineRequestException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
