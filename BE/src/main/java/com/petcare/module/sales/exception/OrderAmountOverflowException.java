package com.petcare.module.sales.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Thành tiền một dòng hoặc tổng Order vượt giới hạn {@code BIGINT}. Docs không có rule cho trường hợp này nên không
 * dùng {@code BusinessRuleViolationException} (bắt buộc rule ID): trả 400 {@link ErrorCode#VALIDATION_FAILED}, giống
 * {@code DuplicateCatalogEntryException}.
 */
public class OrderAmountOverflowException extends PlatformException {

    public OrderAmountOverflowException() {
        super(ErrorCode.VALIDATION_FAILED, "Thành tiền vượt giới hạn cho phép");
    }
}
