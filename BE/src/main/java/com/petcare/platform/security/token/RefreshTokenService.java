package com.petcare.platform.security.token;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Persist/rotate/revoke refresh token trong Postgres (docs/05-domain-model.md
 * RULE-01-06, RULE-02-04/07 — revoke-all session khi Logout/LockAccount/
 * DeactivateAccount). Chỉ lưu SHA-256 hash của raw token, không bao giờ lưu
 * raw token — nếu DB bị lộ, không thể tự tạo lại refresh token hợp lệ từ hash.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;

    @Transactional
    public RefreshTokenEntity issue(UUID accountId, String rawToken, UUID jti, Instant expiresAt,
                                     String userAgent, String ipAddress) {
        RefreshTokenEntity entity = new RefreshTokenEntity(
                accountId, jti, hash(rawToken), Instant.now(), expiresAt, userAgent, ipAddress);
        return repository.save(entity);
    }

    @Transactional
    public RefreshTokenEntity rotate(String oldRawToken, String newRawToken, UUID newJti, Instant newExpiresAt) {
        RefreshTokenEntity oldToken = repository.findByTokenHashAndRevokedAtIsNull(hash(oldRawToken))
                .orElseThrow(() -> new IllegalArgumentException("Refresh token not found or already revoked"));

        RefreshTokenEntity newToken = new RefreshTokenEntity(
                oldToken.getAccountId(), newJti, hash(newRawToken), Instant.now(), newExpiresAt,
                oldToken.getUserAgent(), oldToken.getIpAddress());
        repository.save(newToken);

        oldToken.setRevokedAt(Instant.now());
        oldToken.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        oldToken.setReplacedBy(newToken.getId());
        repository.save(oldToken);

        return newToken;
    }

    @Transactional
    public void revoke(String rawToken, String reason) {
        repository.findByTokenHashAndRevokedAtIsNull(hash(rawToken)).ifPresent(entity -> {
            entity.setRevokedAt(Instant.now());
            entity.setRevokeReason(reason);
            repository.save(entity);
        });
    }

    @Transactional
    public void revokeAllForAccount(UUID accountId, String reason) {
        List<RefreshTokenEntity> active = repository.findAllByAccountIdAndRevokedAtIsNull(accountId);
        Instant now = Instant.now();
        active.forEach(entity -> {
            entity.setRevokedAt(now);
            entity.setRevokeReason(reason);
        });
        repository.saveAll(active);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
