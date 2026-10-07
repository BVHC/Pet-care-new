-- =============================================================================
-- V5 — ST02 dọn tài khoản PENDING quá hạn (BR-TK-08, 03 Tài khoản#3, docs/adr/0013).
--
--   * accounts.pending_expires_at: hạn xác thực chốt lúc đăng ký = created_at + account.pending_ttl_hours [CFG]
--     (BR-QT-13: đổi [CFG] không ảnh hưởng tài khoản đang chờ, như sessions.expires_at, otp_tokens.expires_at).
--     Có giá trị ⇔ status = 'PENDING' (verify xóa giá trị). Lệch erd — erd §13 mục 12.
--   * Backfill tài khoản PENDING đã có theo [CFG] hiện tại, trước khi thêm CHECK.
--   * Index partial cho query chọn tài khoản quá hạn của ST02: chỉ chứa dòng PENDING.
--   * Index otp_tokens(account_id): DELETE otp_tokens của ST02 và kiểm FK RESTRICT khi xóa accounts không phải
--     quét toàn bảng (các cột FK khác trỏ tới accounts chưa có index — nợ D009).
-- =============================================================================

ALTER TABLE accounts ADD COLUMN pending_expires_at TIMESTAMPTZ;

UPDATE accounts
SET pending_expires_at = created_at
        + (SELECT value::int FROM system_configs WHERE key = 'account.pending_ttl_hours') * INTERVAL '1 hour'
WHERE status = 'PENDING';

ALTER TABLE accounts ADD CONSTRAINT ck_accounts_pending_expiry
    CHECK ((status = 'PENDING') = (pending_expires_at IS NOT NULL));

CREATE INDEX ix_accounts_pending_expires_at ON accounts (pending_expires_at) WHERE status = 'PENDING';

CREATE INDEX ix_otp_tokens_account_id ON otp_tokens (account_id);
