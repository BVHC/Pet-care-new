package com.petcare.platform.security.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    private static final List<String> KNOWN_SECURITY_REASONS =
            List.of(RefreshTokenRevokeReason.LOGOUT, RefreshTokenRevokeReason.LOCK_ACCOUNT,
                    RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT);

    @Mock
    private RefreshTokenCleanupService cleanupService;

    @Test
    void cleanupExpiredRefreshTokens_loopsUntilBatchReturnsLessThanBatchSize() {
        RefreshTokenCleanupJob job = new RefreshTokenCleanupJob(cleanupService, 7, 30, 2, 10, KNOWN_SECURITY_REASONS);

        when(cleanupService.cleanupExpiredOrRotatedBatch(any(Instant.class), eq(2))).thenReturn(2, 2, 1);
        when(cleanupService.cleanupExtendedRetentionBatch(any(Instant.class), eq(2))).thenReturn(0);
        when(cleanupService.findUnrecognizedRevokeReasons(KNOWN_SECURITY_REASONS)).thenReturn(List.of());

        job.cleanupExpiredRefreshTokens();

        verify(cleanupService, times(3)).cleanupExpiredOrRotatedBatch(any(Instant.class), eq(2));
        verify(cleanupService, times(1)).cleanupExtendedRetentionBatch(any(Instant.class), eq(2));
    }

    @Test
    void cleanupExpiredRefreshTokens_stopsAtMaxBatchesPerRun_whenBacklogNeverDrainsBelowBatchSize() {
        RefreshTokenCleanupJob job = new RefreshTokenCleanupJob(cleanupService, 7, 30, 2, 3, KNOWN_SECURITY_REASONS);

        when(cleanupService.cleanupExpiredOrRotatedBatch(any(Instant.class), eq(2))).thenReturn(2);
        when(cleanupService.cleanupExtendedRetentionBatch(any(Instant.class), eq(2))).thenReturn(0);
        when(cleanupService.findUnrecognizedRevokeReasons(KNOWN_SECURITY_REASONS)).thenReturn(List.of());

        job.cleanupExpiredRefreshTokens();

        verify(cleanupService, times(3)).cleanupExpiredOrRotatedBatch(any(Instant.class), eq(2));
    }

    @Test
    void cleanupExpiredRefreshTokens_doesNotThrow_whenUnrecognizedReasonsFound() {
        RefreshTokenCleanupJob job = new RefreshTokenCleanupJob(cleanupService, 7, 30, 500, 200, KNOWN_SECURITY_REASONS);

        when(cleanupService.cleanupExpiredOrRotatedBatch(any(Instant.class), anyInt())).thenReturn(0);
        when(cleanupService.cleanupExtendedRetentionBatch(any(Instant.class), anyInt())).thenReturn(0);
        when(cleanupService.findUnrecognizedRevokeReasons(KNOWN_SECURITY_REASONS)).thenReturn(List.of("SOME_FUTURE_REASON"));

        assertThatCode(job::cleanupExpiredRefreshTokens).doesNotThrowAnyException();

        verify(cleanupService, times(1)).findUnrecognizedRevokeReasons(KNOWN_SECURITY_REASONS);
    }
}
