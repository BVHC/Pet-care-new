package com.petcare.platform.security.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void blacklist_writesKeyWithTtlEqualToRemainingLifetime() {
        TokenBlacklistService service = new TokenBlacklistService(redisTemplate, true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        UUID jti = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);

        service.blacklist(jti, expiresAt, "LOGOUT");

        verify(valueOperations).set(eq("auth:blacklist:" + jti), eq("LOGOUT"), any(Duration.class));
    }

    @Test
    void isBlacklisted_true_whenKeyPresent() {
        TokenBlacklistService service = new TokenBlacklistService(redisTemplate, true);
        UUID jti = UUID.randomUUID();
        when(redisTemplate.hasKey("auth:blacklist:" + jti)).thenReturn(true);

        assertThat(service.isBlacklisted(jti)).isTrue();
    }

    @Test
    void isBlacklisted_false_whenKeyAbsent() {
        TokenBlacklistService service = new TokenBlacklistService(redisTemplate, true);
        UUID jti = UUID.randomUUID();
        when(redisTemplate.hasKey("auth:blacklist:" + jti)).thenReturn(false);

        assertThat(service.isBlacklisted(jti)).isFalse();
    }

    @Test
    void isBlacklisted_failOpen_returnsFalse_whenRedisUnavailable() {
        TokenBlacklistService service = new TokenBlacklistService(redisTemplate, true);
        UUID jti = UUID.randomUUID();
        when(redisTemplate.hasKey(any())).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(service.isBlacklisted(jti)).isFalse();
    }

    @Test
    void isBlacklisted_failClosed_throws_whenRedisUnavailable_andFailOpenDisabled() {
        TokenBlacklistService service = new TokenBlacklistService(redisTemplate, false);
        UUID jti = UUID.randomUUID();
        when(redisTemplate.hasKey(any())).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> service.isBlacklisted(jti)).isInstanceOf(RedisConnectionFailureException.class);
    }
}
