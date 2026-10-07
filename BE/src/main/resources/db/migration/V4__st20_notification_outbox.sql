-- =============================================================================
-- V4 — Worker ST20 gửi email từ notification_outbox (docs/adr/0012).
--
--   * Index cho luồng HIGH: worker lấy dòng theo status + template_code IN (OTP_*, STAFF_TEMP_PASSWORD) +
--     next_attempt_at, để OTP không phải quét qua tồn đọng thư hàng loạt. Index V1 (status, next_attempt_at) vẫn
--     phục vụ luồng NORMAL.
--   * Mẫu OTP_REGISTER: câu chào không còn phụ thuộc {ten_khach} (email gửi lại OTP không có họ tên — biến tùy
--     chọn thiếu được thay bằng chuỗi rỗng). ten_khach vẫn trong allowed_vars để UC10 dùng lại được. Sửa cả bản
--     mặc định để "khôi phục mặc định" (BR-QT-14) cho cùng nội dung.
-- =============================================================================

CREATE INDEX ix_notification_outbox_status_template_next_attempt
    ON notification_outbox (status, template_code, next_attempt_at);

UPDATE notification_templates
SET body         = E'Xin chào,\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.\n\nPet Care',
    default_body = E'Xin chào,\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.\n\nPet Care',
    updated_at   = now()
WHERE code = 'OTP_REGISTER';
