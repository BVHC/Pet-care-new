package com.petcare.module.boarding.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Yêu cầu lưu trú sai hình dạng nhưng không thuộc rule {@code BR-*} nào (ngày trả mới không sau ngày trả cũ, khoảng lọc
 * ngược). Hợp đồng boarding-v1 trả 400 {@code VALIDATION_FAILED} cho các trường hợp này, giống
 * {@code InvalidOrderLineRequestException} của sales.
 */
public class InvalidBoardingRequestException extends PlatformException {

    public InvalidBoardingRequestException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
