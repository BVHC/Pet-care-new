-- =============================================================================
-- V27 — Access token ngắn + refresh token xoay vòng; trạng thái online theo phiên (D024, docs/adr/0091).
--
--   * refresh_tokens: PART của sessions. Mỗi lần refresh đánh dấu token cũ đã dùng (used_at) và tạo token mới;
--     dùng lại token đã dùng bị coi là lộ token → hủy phiên. Chỉ lưu SHA-256 của chuỗi ngẫu nhiên, không lưu token gốc.
--     expires_at = sessions.expires_at (chốt lúc tạo, BR-QT-13): hạn phiên là trần tuyệt đối.
--   * sessions.access_expires_at: hạn của access token hiện hành. Nhân viên online ⇔ có phiên chưa hủy với
--     access_expires_at > now (BR-TN-06). NULL = phiên mở trước migration (token cũ không có refresh token, coi như offline).
--   * accounts.last_seen_at bỏ: không còn ghi mỗi request nên không còn nguồn dữ liệu.
--   * [CFG] session.access_ttl_minutes thay staff.online_window_minutes (cùng ý nghĩa, một tham số duy nhất).
--   * Số V dùng chung với BE-2 (V27): thống nhất với BE-2 trước khi merge (CLAUDE.md, 06 §6).
-- =============================================================================

ALTER TABLE sessions ADD COLUMN access_expires_at TIMESTAMPTZ;

CREATE TABLE refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES sessions (id) ON DELETE RESTRICT,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);
-- Kiểm FK khi xóa sessions (job dọn phiên, ADR-0008) và xóa con theo phiên.
CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);
-- Đúng một refresh token còn dùng được cho mỗi phiên.
CREATE UNIQUE INDEX ux_refresh_tokens_active_session ON refresh_tokens (session_id) WHERE used_at IS NULL;

ALTER TABLE accounts DROP COLUMN last_seen_at;

DELETE FROM system_configs WHERE key = 'staff.online_window_minutes';
INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('session.access_ttl_minutes', '10', 'INT', '5', '720', 'phút',
 'Hạn access token; nhân viên có phiên với access token còn hạn được coi là online (BR-TN-06, docs/adr/0091)');
