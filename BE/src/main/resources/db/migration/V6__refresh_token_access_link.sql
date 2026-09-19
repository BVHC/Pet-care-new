-- RULE-01-06 (Logout) — trước đây Logout chỉ thu hồi được Refresh Token khi client
-- CHỦ ĐỘNG gửi kèm raw refresh token trong body (field vốn optional theo
-- docs/api/auth-v1.md #LogoutRequest). Nếu client không gửi (rất dễ xảy ra vì field
-- optional, hoặc refresh token lưu ở nơi JS không đọc được như httpOnly cookie),
-- refresh token của phiên đó vẫn sống nguyên — vi phạm RULE-01-06 ("thu hồi phiên
-- làm việc... vô hiệu hóa Refresh Token"). access_token_jti liên kết 1-1 mỗi
-- refresh token với access token được phát hành CÙNG LÚC (issue/rotate), để Logout
-- luôn tự tra ra đúng refresh token cần thu hồi chỉ từ access token trong header
-- Authorization, không phụ thuộc client có gửi kèm refresh token hay không.
ALTER TABLE refresh_tokens
    ADD COLUMN access_token_jti UUID;

CREATE INDEX idx_refresh_tokens_access_jti ON refresh_tokens(access_token_jti) WHERE revoked_at IS NULL;
