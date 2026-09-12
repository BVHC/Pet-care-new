-- ============================================================================
-- V3: Index hỗ trợ RefreshTokenCleanupJob (ADR-0003)
-- Query 2 nhánh: (1) revoked_at IS NULL + expires_at < cutoff (bucket default);
--                (2) revoked_at IS NOT NULL, lọc theo revoke_reason, + revoked_at
--                    < cutoff (bucket non-ROTATED / extended-retention).
-- ============================================================================

CREATE INDEX idx_refresh_tokens_cleanup_expired
    ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;

CREATE INDEX idx_refresh_tokens_cleanup_revoked
    ON refresh_tokens (revoked_at, revoke_reason) WHERE revoked_at IS NOT NULL;
