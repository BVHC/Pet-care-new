-- =============================================================================
-- V9 — Chuẩn bị liên kết hồ sơ khách (task 08 phần 0, BR-TK-19, UC07) — docs/adr/0025. Số V9 đã chốt với BE-2 (2026-10-09).
--
--   * Mẫu OTP_PROFILE_LINK (BR-TK-04: gửi tới email ghi trong hồ sơ tại quầy, không phải email tài khoản). Theo khung
--     V8: body = default_body, subject = default_subject để UC10 khôi phục được; câu chào không dùng họ tên.
--     {email_tai_khoan} = email tài khoản đang xin liên kết, để chủ hồ sơ nhận ra người khác khai trùng SĐT (hạn chế
--     (1) của BR-TK-19); bắt buộc cùng {ma_otp} (BR-QT-14). Mẫu thuộc luồng HIGH (DeliveryLane.HIGH_TEMPLATES).
--   * Index FK otp_tokens.customer_id → customers: xóa hồ sơ online (liên kết BR-TK-19, ST02) kiểm FK RESTRICT ở cột
--     này; thiếu index thì quét toàn otp_tokens (cùng lý do docs/adr/0018). Các FK khác trỏ tới customers: nợ D013.
--   * Chỉ thêm; thay đổi tiếp theo dùng migration mới.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('OTP_PROFILE_LINK', 'EMAIL',
        'Mã xác thực liên kết hồ sơ Pet Care',
        E'Xin chào,\n\nTài khoản Pet Care {email_tai_khoan} vừa yêu cầu liên kết với hồ sơ khách hàng của bạn tại phòng khám Pet Care. Mã xác thực là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không yêu cầu liên kết, hãy bỏ qua email này; hồ sơ của bạn không thay đổi.\n\nPet Care',
        'Mã xác thực liên kết hồ sơ Pet Care',
        E'Xin chào,\n\nTài khoản Pet Care {email_tai_khoan} vừa yêu cầu liên kết với hồ sơ khách hàng của bạn tại phòng khám Pet Care. Mã xác thực là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không yêu cầu liên kết, hãy bỏ qua email này; hồ sơ của bạn không thay đổi.\n\nPet Care',
        '["ma_otp", "thoi_han_phut", "email_tai_khoan"]'::jsonb,
        '["ma_otp", "email_tai_khoan"]'::jsonb);

CREATE INDEX ix_otp_tokens_customer_id ON otp_tokens (customer_id) WHERE customer_id IS NOT NULL;
