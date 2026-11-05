package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.petcare.support.MutableClock;

/**
 * {@link RedisRateLimiter} trên Redis 7 thật, không cần Spring context (docs/adr/0100): đếm atomic dưới tải đồng
 * thời (G4), khóa luôn có TTL và tự sửa khóa mất TTL (G3), Retry-After lấy từ TTL còn lại, và kiểu dữ liệu script trả
 * về đúng như code đọc.
 */
class RedisRateLimiterIT {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T03:00:00Z"));
    private RedisRateLimiter limiter;

    @BeforeAll
    static void startRedis() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        container.start();
        factory = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        container.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) (RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
        RateLimitProperties properties = new RateLimitProperties(true, new RateLimitProperties.Rule(30, 60),
                new RateLimitProperties.Rule(10, 60), new RateLimitProperties.Rule(10, 60),
                new RateLimitProperties.Rule(10, 60), new RateLimitProperties.Rule(60, 3600), 10);
        limiter = new RedisRateLimiter(redis, clock, properties);
    }

    @Test
    void exactlyTheLimitIsAllowedWhenFiftyThreadsRaceForOneKey() throws Exception {
        int threads = 50;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RateLimiter.Decision>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return limiter.tryAcquire("rl:login:race", 10, Duration.ofSeconds(60));
                }));
            }
            int allowed = 0;
            for (Future<RateLimiter.Decision> result : results) {
                if (result.get(20, TimeUnit.SECONDS).allowed()) {
                    allowed++;
                }
            }
            assertThat(allowed).as("INCR atomic: đúng 10 lần được qua, không hơn").isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void firstCallSetsTheTtlAndTheRetryAfterComesFromIt() {
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.tryAcquire("rl:login:ttl", 2, Duration.ofSeconds(60)).allowed()).isTrue();
        }

        RateLimiter.Decision denied = limiter.tryAcquire("rl:login:ttl", 2, Duration.ofSeconds(60));

        assertThat(redis.getExpire("rl:login:ttl")).as("khóa đếm có TTL, không bất tử").isBetween(1L, 60L);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void aKeyThatLostItsTtlGetsOneAgainInsteadOfBlockingForever() {
        redis.opsForValue().set("rl:login:immortal", "5");
        assertThat(redis.getExpire("rl:login:immortal")).as("điều kiện dựng: khóa không hết hạn").isEqualTo(-1L);

        RateLimiter.Decision decision = limiter.tryAcquire("rl:login:immortal", 10, Duration.ofSeconds(60));

        assertThat(decision.allowed()).isTrue();
        assertThat(redis.getExpire("rl:login:immortal")).isBetween(1L, 60L);
    }

    @Test
    void theWindowResetsAfterItExpires() {
        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("rl:login:reset", 2, Duration.ofSeconds(1));
        }
        assertThat(limiter.tryAcquire("rl:login:reset", 2, Duration.ofSeconds(1)).allowed()).isFalse();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(limiter.tryAcquire("rl:login:reset", 2, Duration.ofSeconds(1)).allowed()).isTrue());
    }

    @Test
    void differentKeysAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("rl:login:a", 2, Duration.ofSeconds(60));
        }

        assertThat(limiter.tryAcquire("rl:login:a", 2, Duration.ofSeconds(60)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("rl:login:b", 2, Duration.ofSeconds(60)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("rl:register:a", 2, Duration.ofSeconds(60)).allowed()).isTrue();
    }
}
