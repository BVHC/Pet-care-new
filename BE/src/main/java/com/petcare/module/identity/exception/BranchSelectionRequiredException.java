package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Request thiếu chi nhánh mà người gọi phải chọn: SUPER_MANAGER không có chi nhánh riêng nên phải gửi {@code branchId}
 * (identity-v1 #38; docs/adr/0089). Không thuộc rule {@code BR-*} nào, nên 400 {@code VALIDATION_FAILED} không kèm mã
 * rule — khác {@code BusinessRuleViolationException} ở mã lỗi (khuôn {@code InvalidBoardingRequestException}).
 */
public class BranchSelectionRequiredException extends PlatformException {

    public BranchSelectionRequiredException() {
        super(ErrorCode.VALIDATION_FAILED, "Vui lòng chọn chi nhánh");
    }
}
