package com.petcare.platform.security.token;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
    public RefreshTokenEntity issue(UUID accountId, String rawToken, UUID jti, UUID accessTokenJti, Instant expiresAt,
                                     String userAgent, String ipAddress) {
        RefreshTokenEntity entity = new RefreshTokenEntity(
                accountId, jti, accessTokenJti, hash(rawToken), Instant.now(), expiresAt, userAgent, ipAddress);
        return repository.save(entity);
    }

    @Transactional
    public RefreshTokenEntity rotate(String oldRawToken, String newRawToken, UUID newJti, UUID newAccessTokenJti,
                                      Instant newExpiresAt) {
        RefreshTokenEntity oldToken = repository.findByTokenHashAndRevokedAtIsNull(hash(oldRawToken))
                .orElseThrow(() -> new IllegalArgumentException("Refresh token not found or already revoked"));

        RefreshTokenEntity newToken = new RefreshTokenEntity(
                oldToken.getAccountId(), newJti, newAccessTokenJti, hash(newRawToken), Instant.now(), newExpiresAt,
                oldToken.getUserAgent(), oldToken.getIpAddress());
        repository.save(newToken);

        oldToken.setRevokedAt(Instant.now());
        oldToken.setRevokeReason(RefreshTokenRevokeReason.ROTATED);
        oldToken.setReplacedBy(newToken.getId());
        try {
            repository.save(oldToken);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Đụng độ với 1 revoke khác (vd logout) chạy song song, cùng đọc oldToken lúc còn
            // revokedAt=null trước khi thread nào commit — oldToken thực chất đã bị vô hiệu hóa,
            // coi như cùng lỗi "not found or already revoked" ở nhánh trên. newToken vừa insert
            // tự rollback theo @Transactional (không rò dữ liệu).
            throw new IllegalArgumentException("Refresh token not found or already revoked");
        }

        return newToken;
    }

    /** @return true nếu tìm thấy và thu hồi được refresh token, false nếu không có (đã revoke/không tồn tại). */
    @Transactional
    public boolean revoke(String rawToken, String reason) {
        try {
            return repository.findByTokenHashAndRevokedAtIsNull(hash(rawToken)).map(entity -> {
                entity.setRevokedAt(Instant.now());
                entity.setRevokeReason(reason);
                repository.save(entity);
                return true;
            }).orElse(false);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Đụng độ với rotate() chạy song song trên đúng raw token này — tại thời điểm race,
            // row đã thực sự bị revoke (bởi lý do khác), coi như tương đương not-found. Khác
            // doRevoke()/revokeByAccessTokenJti(): method này không đi theo chuỗi replacedBy nên
            // không có "generation kế tiếp" nào để tiếp tục revoke.
            return false;
        }
    }

    /** Số hop tối đa khi lần theo chuỗi replacedBy — chặn vòng lặp vô hạn nếu dữ liệu hỏng. */
    private static final int MAX_ROTATION_CHAIN_HOPS = 50;

    /**
     * RULE-01-06 — Logout tra ra đúng refresh token của phiên hiện tại chỉ từ JTI của access
     * token (không cần client gửi kèm raw refresh token trong body). Xem
     * RefreshTokenEntity#accessTokenJti.
     *
     * <p>Access token đưa lên có thể đã lỗi thời (client dùng access token cũ hơn lần
     * refresh/rotate gần nhất của cùng phiên — JWT chưa hết hạn nên server vẫn chấp nhận nó ở
     * mọi endpoint khác) — lúc đó row gắn với accessTokenJti này đã bị revoke do ROTATED, không
     * còn revokedAt=null nữa. Phải lần theo chuỗi {@code replacedBy} tới generation đang sống
     * của CÙNG phiên rồi thu hồi đúng row đó, nếu không refresh token thật sự đang hoạt động sẽ
     * không bao giờ bị đụng tới. Chỉ đi tiếp khi hop trước bị revoke vì ROTATED — nếu đã revoke
     * vì lý do khác (LOGOUT/LOCK_ACCOUNT/DEACTIVATE_ACCOUNT) thì phiên đã bị vô hiệu hoá từ
     * trước, không có gì để thu hồi thêm.
     *
     * @return true nếu tìm thấy và thu hồi được, false nếu không có (vd token issue trước khi
     * có cột này, hoặc phiên đã bị vô hiệu hoá từ trước).
     */
    @Transactional
    public boolean revokeByAccessTokenJti(UUID accessTokenJti, String reason) {
        Optional<RefreshTokenEntity> live = repository.findByAccessTokenJtiAndRevokedAtIsNull(accessTokenJti);
        if (live.isPresent()) {
            return doRevoke(live.get(), reason) != null;
        }

        Optional<RefreshTokenEntity> current = repository.findByAccessTokenJti(accessTokenJti);
        for (int hop = 0; hop < MAX_ROTATION_CHAIN_HOPS; hop++) {
            if (current.isEmpty() || !RefreshTokenRevokeReason.ROTATED.equals(current.get().getRevokeReason())
                    || current.get().getReplacedBy() == null) {
                return false;
            }
            current = repository.findById(current.get().getReplacedBy());
            if (current.isPresent() && current.get().getRevokedAt() == null) {
                return doRevoke(current.get(), reason) != null;
            }
        }
        return false;
    }

    /**
     * Retry theo {@code replacedBy} khi đụng optimistic-lock — cùng bản chất với case "access
     * token lỗi thời" mà {@link #revokeByAccessTokenJti} đã xử lý ở trên (dòng ~94-105), chỉ khác
     * là phát hiện ở đây muộn hơn (lúc save() thay vì lúc SELECT) vì 1 request rotate() khác vừa
     * commit ĐÚNG giữa lúc entity này được đọc và được ghi (race, không phải access token cũ theo
     * nghĩa tuần tự bình thường).
     *
     * @return entity thực sự bị revoke (có thể khác entity truyền vào, nếu phải hop theo
     * replacedBy), hoặc {@code null} nếu không revoke được gì (đã bị vô hiệu hóa bởi lý do khác).
     */
    private RefreshTokenEntity doRevoke(RefreshTokenEntity entity, String reason) {
        for (int hop = 0; hop < MAX_ROTATION_CHAIN_HOPS; hop++) {
            if (entity.getRevokedAt() != null) {
                // entity đã bị revoke từ trước khi tới lượt xử lý (đọc lại sau optimistic-lock
                // conflict, hoặc bản thân "next" trong chuỗi cũng đã bị rotate tiếp) — chỉ đi tiếp
                // nếu lý do là ROTATED (cùng logic chuỗi ở revokeByAccessTokenJti phía trên); lý do
                // khác nghĩa là phiên đã bị vô hiệu hóa từ 1 đường khác rồi, không còn gì để làm.
                if (!RefreshTokenRevokeReason.ROTATED.equals(entity.getRevokeReason()) || entity.getReplacedBy() == null) {
                    return null;
                }
                entity = repository.findById(entity.getReplacedBy()).orElse(null);
                if (entity == null) {
                    return null;
                }
                continue;
            }
            entity.setRevokedAt(Instant.now());
            entity.setRevokeReason(reason);
            try {
                return repository.save(entity);
            } catch (ObjectOptimisticLockingFailureException ex) {
                // Đụng độ với 1 rotate() khác vừa commit ĐÚNG giữa lúc entity này được đọc và
                // được ghi — đọc lại bản mới nhất, vòng lặp sẽ tự re-check revokedAt ở trên.
                entity = repository.findById(entity.getId()).orElse(null);
                if (entity == null) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * @return danh sách {@code accessTokenJti} (bỏ qua null — row issue trước khi có cột này)
     * của các refresh token vừa bị revoke, để caller (TokenIssuanceFacadeImpl) blacklist đúng
     * access token đang sống của từng phiên — RULE-02-04/07 yêu cầu thu hồi cả Access Token,
     * không chỉ Refresh Token.
     * <p>
     * Revoke từng entity qua {@link #doRevoke} (không {@code saveAll} 1 lượt) — kể từ khi entity
     * có {@code @Version}, 1 row trong batch đụng optimistic-lock (rotate() song song trên đúng
     * phiên đó) sẽ làm cả batch fail nếu dùng saveAll, chặn luôn việc revoke các phiên KHÁC không
     * hề liên quan — nghiêm trọng vì đây là đường LockAccount/DeactivateAccount (RULE-02-04/07,
     * phải revoke được MỌI phiên).
     */
    @Transactional
    public List<UUID> revokeAllForAccount(UUID accountId, String reason) {
        List<RefreshTokenEntity> active = repository.findAllByAccountIdAndRevokedAtIsNull(accountId);
        List<UUID> accessTokenJtis = new ArrayList<>();
        for (RefreshTokenEntity entity : active) {
            RefreshTokenEntity revoked = doRevoke(entity, reason);
            if (revoked != null && revoked.getAccessTokenJti() != null) {
                accessTokenJtis.add(revoked.getAccessTokenJti());
            }
        }
        return accessTokenJtis;
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
