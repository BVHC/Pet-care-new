package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.petcare.support.MutableClock;

/**
 * Quyết định cho qua / chặn và circuit-breaker của {@link RedisRateLimiter} với Redis giả (docs/adr/0100). Đếm atomic
 * và TTL trên Redis thật được chứng minh ở {@code RedisRateLimiterIT}.
 */
class RedisRateLimiterTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T03:00:00Z"));
    private final RateLimitProperties properties = new RateLimitProperties(true,
            new RateLimitProperties.Rule(30, 60), new RateLimitProperties.Rule(10, 60),
            new RateLimitProperties.Rule(10, 60), 10);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, clock, properties);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void redisReturns(long count, long ttl) {
        doReturn(List.of(count, ttl)).when(redis).execute(any(RedisScript.class), eq(List.of("rl:login:1.2.3.4")),
                eq("60"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void redisFails() {
        doThrow(new RedisConnectionFailureException("Redis down")).when(redis)
                .execute(any(RedisScript.class), eq(List.of("rl:login:1.2.3.4")), eq("60"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyRedisCalls(int times) {
        verify(redis, times(times)).execute(any(RedisScript.class), eq(List.of("rl:login:1.2.3.4")), eq("60"));
    }

    @Test
    void allowsUpToAndIncludingTheLimit() {
        redisReturns(10, 55);

        assertThat(limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW)).isEqualTo(RateLimiter.Decision.ALLOWED);
    }

    @Test
    void deniesOverTheLimitWithTheRemainingTtlAsRetryAfter() {
        redisReturns(11, 37);

        RateLimiter.Decision decision = limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(37);
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() {
        redisReturns(11, 0);

        assertThat(limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void redisFailureFailsOpenAndSkipsRedisWhileTheBreakerIsOpen() {
        redisFails();

        assertThat(limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW)).isEqualTo(RateLimiter.Decision.ALLOWED);
        clock.advance(Duration.ofSeconds(9));
        assertThat(limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW)).isEqualTo(RateLimiter.Decision.ALLOWED);

        verifyRedisCalls(1);
    }

    @Test
    void retriesRedisOnceTheBreakerWindowHasPassed() {
        redisFails();
        limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW);
        clock.advance(Duration.ofSeconds(10));
        redisReturns(11, 20);

        RateLimiter.Decision decision = limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW);

        assertThat(decision.allowed()).as("Redis khỏe lại nên giới hạn có hiệu lực").isFalse();
        verifyRedisCalls(2);
    }

    @Test
    void malformedScriptResultIsTreatedAsUnavailableNotAsADenial() {
        doReturn(null).when(redis).execute(any(RedisScript.class), eq(List.of("rl:login:1.2.3.4")), eq("60"));

        assertThat(limiter.tryAcquire("rl:login:1.2.3.4", 10, WINDOW)).isEqualTo(RateLimiter.Decision.ALLOWED);
    }
}
