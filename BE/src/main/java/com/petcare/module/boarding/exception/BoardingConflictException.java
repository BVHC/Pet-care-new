package com.petcare.module.boarding.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Thao tác lưu trú không làm được ở trạng thái hiện tại nhưng không phải một cạnh sai của FSM (xóa chuồng đang có thú
 * hoặc đã có lịch sử, gia hạn đặt chỗ quá hạn đón). Hợp đồng trả 409 với message riêng, nên không dùng
 * {@code InvalidStateTransitionException} (message cố định "from → to").
 */
public class BoardingConflictException extends PlatformException {

    public BoardingConflictException(String message) {
        super(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
