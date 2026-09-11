package com.petcare.platform.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Access-token blacklist (Redis, TTL = thời gian còn lại của token).
 * <p>
 * Chính sách khi Redis không phản hồi (kết nối lỗi hoặc timeout): FAIL-OPEN —
 * coi token là KHÔNG bị blacklist, log WARN, không ném lỗi 500. Quyết định này
 * (xem plan C:\Users\Admin\.claude\plans\c-tr-c-docs-silly-curry.md) đánh đổi:
 * cửa sổ rủi ro (access token đã bị thu hồi nhưng còn dùng được) bị giới hạn bởi
 * access-token TTL (15 phút, xem jwt.access-token-ttl-min), đổi lại Redis sập
 * không kéo sập toàn bộ API. Refresh token (RefreshTokenService, lưu Postgres)
 * không phụ thuộc Redis nên revoke-all vẫn hoạt động đúng dù Redis sập.
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final boolean failOpen;

    public TokenBlacklistService(StringRedisTemplate redisTemplate,
                                  @Value("${app.security.blacklist-fail-open:true}") boolean failOpen) {
        this.redisTemplate = redisTemplate;
        this.failOpen = failOpen;
    }

    public void blacklist(UUID jti, Instant expiresAt, String reason) {
        long ttlSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
        try {
            redisTemplate.opsForValue().set(key(jti), reason, Duration.ofSeconds(ttlSeconds));
        } catch (RedisConnectionFailureException | QueryTimeoutException ex) {
            log.warn("Failed to blacklist jti={} due to Redis unavailability; token remains valid until natural expiry", jti, ex);
        }
    }

    public boolean isBlacklisted(UUID jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (RedisConnectionFailureException | QueryTimeoutException ex) {
            if (failOpen) {
                log.warn("Blacklist check failed for jti={} due to Redis unavailability; failing open (allowing request)", jti, ex);
                return false;
            }
            log.error("Blacklist check failed for jti={} due to Redis unavailability; failing closed (app.security.blacklist-fail-open=false)", jti, ex);
            throw ex;
        }
    }

    private String key(UUID jti) {
        return KEY_PREFIX + jti;
    }
}
