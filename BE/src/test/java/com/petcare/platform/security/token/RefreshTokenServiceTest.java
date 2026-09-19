package com.petcare.platform.security.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phát hiện qua code review — rotate() (refresh) và doRevoke()/revokeByAccessTokenJti() (logout)
 * đều read-modify-save cùng 1 row refresh_tokens mà trước đây không có @Version, cho phép lost
 * update khi 2 luồng nghiệp vụ độc lập chạm cùng 1 row gần như đồng thời. Test này giả lập race
 * bằng cách mock repository.save() ném ObjectOptimisticLockingFailureException ở lần gọi đầu
 * (đúng pattern OrganizationServiceImplTest/StoreServiceImplTest/AuthServiceImplTest).
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository);
    }

    private static RefreshTokenEntity token(UUID id, UUID accountId) {
        RefreshTokenEntity entity = new RefreshTokenEntity(accountId, UUID.randomUUID(), UUID.randomUUID(),
                "hash", Instant.now(), Instant.now().plusSeconds(600), "UA", "127.0.0.1");
        entity.setId(id);
        return entity;
    }

    private static RefreshTokenEntity revokedToken(UUID id, UUID accountId, String reason, UUID replacedBy) {
        RefreshTokenEntity entity = token(id, accountId);
        entity.setRevokedAt(Instant.now());
        entity.setRevokeReason(reason);
        entity.setReplacedBy(replacedBy);
        return entity;
    }

    // --- rotate() ---

    @Test
    void rotate_concurrentRevokeRacesOldToken_throwsAndDoesNotSwallowConflict() {
        UUID oldId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        RefreshTokenEntity oldToken = token(oldId, accountId);
        when(repository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(oldToken));
        // Lần save() đầu (insert newToken, id=null lúc gọi) thành công; lần save(oldToken) thứ 2
        // (id=oldId) đụng optimistic-lock vì 1 revoke khác (vd logout) vừa commit trước.
        when(repository.save(any(RefreshTokenEntity.class))).thenAnswer(invocation -> {
            RefreshTokenEntity arg = invocation.getArgument(0);
            if (oldId.equals(arg.getId())) {
                throw new ObjectOptimisticLockingFailureException(RefreshTokenEntity.class, oldId);
            }
            return arg;
        });

        assertThatThrownBy(() -> service.rotate("old-raw", "new-raw", UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- revoke(rawToken, reason) — logout bằng raw token trực tiếp ---

    @Test
    void revoke_concurrentRotateRacesSameToken_returnsFalse_notException() {
        RefreshTokenEntity entity = token(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(entity));
        when(repository.save(any(RefreshTokenEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(RefreshTokenEntity.class, entity.getId()));

        boolean revoked = service.revoke("raw", RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isFalse();
    }

    // --- revokeByAccessTokenJti() — logout bằng JTI, phải lần theo replacedBy khi đụng race ---

    @Test
    void revokeByAccessTokenJti_concurrentRotateRace_followsReplacedByAndRevokesLiveSuccessor() {
        UUID accountId = UUID.randomUUID();
        UUID accessJti = UUID.randomUUID();
        UUID liveId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        // Bản trong bộ nhớ lúc revokeByAccessTokenJti đọc — vẫn revokedAt=null vì đọc TRƯỚC khi
        // rotate() song song commit.
        RefreshTokenEntity staleReadCopy = token(liveId, accountId);
        // Bản thật trong DB SAU KHI rotate() song song đã commit.
        RefreshTokenEntity actualAfterRotate = revokedToken(liveId, accountId, RefreshTokenRevokeReason.ROTATED, successorId);
        RefreshTokenEntity successor = token(successorId, accountId);

        when(repository.findByAccessTokenJtiAndRevokedAtIsNull(accessJti)).thenReturn(Optional.of(staleReadCopy));
        when(repository.save(argThat(e -> e != null && liveId.equals(e.getId()))))
                .thenThrow(new ObjectOptimisticLockingFailureException(RefreshTokenEntity.class, liveId));
        when(repository.findById(liveId)).thenReturn(Optional.of(actualAfterRotate));
        when(repository.findById(successorId)).thenReturn(Optional.of(successor));
        when(repository.save(argThat(e -> e != null && successorId.equals(e.getId()))))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean revoked = service.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isTrue();
        verify(repository).save(argThat(e -> successorId.equals(e.getId())
                && RefreshTokenRevokeReason.LOGOUT.equals(e.getRevokeReason())));
    }

    @Test
    void revokeByAccessTokenJti_concurrentRevokeForOtherReason_stopsWithoutFollowingChain() {
        // Đụng race, nhưng bản mới nhất bị revoke vì LOCK_ACCOUNT (không phải ROTATED) — phiên đã
        // bị vô hiệu hóa từ đường khác, không có chuỗi replacedBy nào để đi tiếp.
        UUID accountId = UUID.randomUUID();
        UUID accessJti = UUID.randomUUID();
        UUID liveId = UUID.randomUUID();
        RefreshTokenEntity staleReadCopy = token(liveId, accountId);
        RefreshTokenEntity actualAfterLock = revokedToken(liveId, accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT, null);

        when(repository.findByAccessTokenJtiAndRevokedAtIsNull(accessJti)).thenReturn(Optional.of(staleReadCopy));
        when(repository.save(any(RefreshTokenEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(RefreshTokenEntity.class, liveId));
        when(repository.findById(liveId)).thenReturn(Optional.of(actualAfterLock));

        boolean revoked = service.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isFalse();
    }

    // --- revokeAllForAccount() — LockAccount/DeactivateAccount phải revoke MỌI phiên ---

    @Test
    void revokeAllForAccount_oneSessionRaces_othersStillRevoked() {
        UUID accountId = UUID.randomUUID();
        UUID racingId = UUID.randomUUID();
        UUID normalId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        RefreshTokenEntity racingSession = token(racingId, accountId);
        RefreshTokenEntity normalSession = token(normalId, accountId);
        RefreshTokenEntity racingAfterRotate = revokedToken(racingId, accountId, RefreshTokenRevokeReason.ROTATED, successorId);
        RefreshTokenEntity successor = token(successorId, accountId);

        when(repository.findAllByAccountIdAndRevokedAtIsNull(accountId))
                .thenReturn(List.of(racingSession, normalSession));
        when(repository.save(argThat(e -> e != null && racingId.equals(e.getId()))))
                .thenThrow(new ObjectOptimisticLockingFailureException(RefreshTokenEntity.class, racingId));
        when(repository.findById(racingId)).thenReturn(Optional.of(racingAfterRotate));
        when(repository.findById(successorId)).thenReturn(Optional.of(successor));
        when(repository.save(argThat(e -> e != null && successorId.equals(e.getId()))))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(argThat(e -> e != null && normalId.equals(e.getId()))))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.revokeAllForAccount(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);

        // Phiên "normal" không liên quan gì tới race vẫn phải bị revoke — không được để 1 phiên
        // đụng optimistic-lock làm cả batch fail (đây là bug nếu dùng saveAll không bọc try/catch).
        verify(repository).save(argThat(e -> normalId.equals(e.getId())
                && RefreshTokenRevokeReason.LOCK_ACCOUNT.equals(e.getRevokeReason())));
        // Phiên đụng race: lần save đầu trên chính nó đụng conflict (ném exception, không tính là
        // revoke thành công); retry đi theo replacedBy và revoke ĐÚNG successor đang sống.
        verify(repository).save(argThat(e -> successorId.equals(e.getId())
                && RefreshTokenRevokeReason.LOCK_ACCOUNT.equals(e.getRevokeReason())));
    }
}
