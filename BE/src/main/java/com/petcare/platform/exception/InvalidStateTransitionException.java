package com.petcare.platform.exception;

/**
 * Chuyển trạng thái không có trong bảng của docs/03-state-machines.md.
 * {@code from = null} nghĩa là tạo mới với trạng thái khởi tạo không hợp lệ.
 */
public class InvalidStateTransitionException extends PlatformException {

    private final String fsmName;
    private final String from;
    private final String to;

    public InvalidStateTransitionException(String fsmName, String from, String to) {
        super(ErrorCode.INVALID_STATE_TRANSITION,
                "Không thể chuyển trạng thái " + fsmName + " từ " + (from == null ? "—" : from) + " sang " + to);
        this.fsmName = fsmName;
        this.from = from;
        this.to = to;
    }

    public String getFsmName() {
        return fsmName;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
