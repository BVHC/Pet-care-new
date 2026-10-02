package com.petcare.platform.exception;

/**
 * Lớp cha của 5 exception dùng chung. {@code GlobalExceptionHandler} map mọi subclass theo {@link #errorCode()},
 * nên exception riêng của module (kế thừa một trong 5 lớp con) tự có envelope đúng mà không cần handler mới.
 */
public abstract class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;

    protected PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Message trả cho client; mặc định là message của exception. */
    public String clientMessage() {
        return getMessage();
    }
}
