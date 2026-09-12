-- ============================================================================
-- V2: Refresh Token store cho JWT session infrastructure
-- Nguồn chân lý nghiệp vụ: docs/05-domain-model.md (RULE-02-04/07 — revoke-all
--   session khi LockAccount/DeactivateAccount), docs/03-state-machines.md FSM 1
--   (Account), docs/02-business-rules.md RULE-01-06 (Logout thu hồi Session).
-- Quyết định kỹ thuật (xem plan C:\Users\Admin\.claude\plans\c-tr-c-docs-silly-curry.md):
--   - Không có bảng Session/RefreshToken nào được đặc tả trong docs/06-erd.md —
--     đây là thiết kế hạ tầng bảo mật (platform/security), không phải bảng nghiệp vụ.
--   - Refresh token lưu Postgres (nguồn audit/revoke-all đáng tin cậy); access-token
--     blacklist lưu Redis (không cần bảng DB, có TTL tự dọn dẹp).
--   - Không extend BaseEntity: đây là artifact hệ thống, không cần created_by/
--     deleted_at/version như các bảng nghiệp vụ.
--   - Chỉ lưu SHA-256 hash của raw token, không bao giờ lưu raw refresh token.
-- ============================================================================

CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    jti            UUID NOT NULL,
    token_hash     VARCHAR(255) NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  VARCHAR(50),
    replaced_by    UUID REFERENCES refresh_tokens(id),
    user_agent     VARCHAR(255),
    ip_address     VARCHAR(45),
    CONSTRAINT uq_refresh_tokens_jti UNIQUE (jti),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_account ON refresh_tokens(account_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);
