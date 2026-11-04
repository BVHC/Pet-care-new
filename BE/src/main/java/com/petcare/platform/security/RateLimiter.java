package com.petcare.platform.security;

import java.time.Duration;

/**
 * Bộ đếm giới hạn tần suất theo khóa (docs/adr/0100, nợ D011). Chỉ có một cài đặt thật ({@link RedisRateLimiter});
 * giao diện này để {@link RateLimitFilter} test được mà không cần Redis.
 */
public interface RateLimiter {

    /**
     * Ghi nhận một lần gọi của {@code key} trong cửa sổ {@code window} và cho biết có được phép không. Lần gọi bị từ
     * chối vẫn được tính. Không ném exception: hạ tầng đếm lỗi thì cho qua (fail-open).
     */
    Decision tryAcquire(String key, int limit, Duration window);

    /** {@code retryAfterSeconds} chỉ có nghĩa khi {@code allowed == false}; luôn ≥ 1 trong trường hợp đó. */
    record Decision(boolean allowed, long retryAfterSeconds) {

        public static final Decision ALLOWED = new Decision(true, 0);

        public static Decision denied(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
