-- =============================================================================
-- V3 — Seed mẫu thông báo OTP_REGISTER (BR-QT-14, 06-module-contracts §8 Q4).
--
--   * Mẫu được seed dần: mỗi mẫu một migration, cùng PR với lời gọi NotificationApi.enqueue đầu tiên dùng nó
--     (notification_outbox.template_code có FK). Lời gọi đầu tiên: RegistrationService.registerAccount (UC01).
--   * body = default_body, subject = default_subject để UC10 khôi phục được mẫu mặc định.
--   * Biến viết dạng {ten_bien}; required_vars ⊆ allowed_vars. BR-QT-14: mẫu OTP bắt buộc có {ma_otp}.
--   * Chỉ INSERT; mẫu tiếp theo dùng migration mới.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('OTP_REGISTER', 'EMAIL',
        'Mã xác thực đăng ký tài khoản Pet Care',
        E'Chào {ten_khach},\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.\n\nPet Care',
        'Mã xác thực đăng ký tài khoản Pet Care',
        E'Chào {ten_khach},\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.\n\nPet Care',
        '["ten_khach", "ma_otp", "thoi_han_phut"]'::jsonb,
        '["ma_otp"]'::jsonb);
