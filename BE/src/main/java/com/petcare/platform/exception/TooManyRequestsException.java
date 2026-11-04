package com.petcare.platform.exception;

/**
 * Một IP gọi endpoint public quá nhanh (docs/adr/0100, nợ D011). 429 kèm header {@code Retry-After}.
 * Exception riêng theo docs/convention/backend/04-exception-handling.md §4.2 tiêu chí 2: HTTP status và header khác
 * mặc định của {@code GlobalExceptionHandler}. Kế thừa thẳng {@link PlatformException} vì không base nào mang 429
 * (cùng tiền lệ {@code InvalidCredentialsException}). Message chung, không nói ngưỡng hay khóa đếm.
 */
public class TooManyRequestsException extends PlatformException {

    static final String MESSAGE_TEMPLATE = "Bạn thao tác quá nhanh, vui lòng thử lại sau %d giây";

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS, MESSAGE_TEMPLATE.formatted(Math.max(1, retryAfterSeconds)));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Số giây nguyên ≥ 1 client nên đợi; chính là giá trị header {@code Retry-After}. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
