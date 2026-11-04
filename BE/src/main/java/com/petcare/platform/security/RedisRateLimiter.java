package com.petcare.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fixed window theo khóa, đếm bằng một script Lua chạy atomic trên Redis (docs/adr/0100). Script tự sửa khóa mất TTL,
 * nên không có khóa "bất tử" dù tiến trình chết giữa {@code INCR} và {@code EXPIRE}.
 *
 * <p>Fail-open có circuit-breaker: Redis lỗi hoặc quá {@code spring.data.redis.timeout} thì cho request qua, log
 * {@code RATE_LIMIT_UNAVAILABLE} một lần và bỏ qua Redis trong {@code app.rate-limit.breaker-seconds} giây (đồng hồ
 * {@link Clock}) để mỗi request không phải chờ hết timeout. Đăng nhập luôn dùng được, đổi lại tạm thời không có giới hạn.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    /** Trả {count, ttlGiây}. TTL < 0 (khóa không hết hạn) được gán lại để không chặn vĩnh viễn. */
    static final String SCRIPT = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {c, ttl}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> REDIS_SCRIPT = new DefaultRedisScript<>(SCRIPT, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration breakerOpen;
    private volatile Instant breakerOpenUntil = Instant.MIN;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock, RateLimitProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.breakerOpen = Duration.ofSeconds(properties.breakerSeconds());
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now(clock);
        if (now.isBefore(breakerOpenUntil)) {
            return Decision.ALLOWED;
        }
        try {
            List<?> result = redis.execute(REDIS_SCRIPT, List.of(key), Long.toString(window.toSeconds()));
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("Redis trả kết quả không đúng dạng: " + result);
            }
            long count = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            return count <= limit ? Decision.ALLOWED : Decision.denied(ttl);
        } catch (RuntimeException ex) {
            breakerOpenUntil = now.plus(breakerOpen);
            log.warn("RATE_LIMIT_UNAVAILABLE: bỏ qua giới hạn tần suất {}s, cho request đi qua ({})",
                    breakerOpen.toSeconds(), ex.toString());
            return Decision.ALLOWED;
        }
    }
}
