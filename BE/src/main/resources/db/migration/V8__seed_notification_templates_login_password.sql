-- =============================================================================
-- V8 — Seed 3 mẫu thông báo của task đăng nhập / mật khẩu (BR-QT-14, 06-module-contracts §8 Q4).
--
--   * OTP_PASSWORD_RESET (BR-TK-04, 05: quên mật khẩu, UC04), LOGIN_LOCKED_WARNING (BR-TK-09: khóa tạm do đăng
--     nhập sai, ST01 — docs/adr/0019), PASSWORD_CHANGED (BR-TK-13: đặt lại mật khẩu thành công).
--   * Một migration cho cả 3 mẫu vì cùng một đợt task 07 (người dùng chốt 2026-10-08); lời gọi enqueue đầu tiên
--     nằm ở dịch vụ đăng nhập / đặt lại mật khẩu của identity.
--   * Theo khung V3: body = default_body, subject = default_subject để UC10 khôi phục được mẫu mặc định; biến
--     {ten_bien}; required_vars ⊆ allowed_vars; mẫu OTP bắt buộc có {ma_otp} (BR-QT-14). Câu chào không dùng họ
--     tên (như V4) vì tài khoản nhân viên và khách đều nhận.
--   * Biến thời điểm (thoi_diem_mo_khoa, thoi_diem) do identity định dạng sẵn "HH:mm dd/MM/yyyy" theo giờ Việt Nam.
--   * OTP_PASSWORD_RESET thuộc luồng gửi HIGH (DeliveryLane.HIGH_TEMPLATES); 2 mẫu còn lại luồng NORMAL.
--   * Chỉ INSERT; mẫu tiếp theo dùng migration mới.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('OTP_PASSWORD_RESET', 'EMAIL',
        'Mã xác thực đặt lại mật khẩu Pet Care',
        E'Xin chào,\n\nMã xác thực đặt lại mật khẩu tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này; mật khẩu hiện tại vẫn được giữ nguyên.\n\nPet Care',
        'Mã xác thực đặt lại mật khẩu Pet Care',
        E'Xin chào,\n\nMã xác thực đặt lại mật khẩu tài khoản Pet Care của bạn là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này; mật khẩu hiện tại vẫn được giữ nguyên.\n\nPet Care',
        '["ma_otp", "thoi_han_phut"]'::jsonb,
        '["ma_otp"]'::jsonb),

       ('LOGIN_LOCKED_WARNING', 'EMAIL',
        'Cảnh báo: tài khoản Pet Care tạm khóa đăng nhập',
        E'Xin chào,\n\nTài khoản Pet Care của bạn vừa bị nhập sai mật khẩu {so_lan_sai} lần liên tiếp nên tạm thời không đăng nhập được cho tới {thoi_diem_mo_khoa}.\n\nNếu đó không phải bạn, hãy dùng chức năng Quên mật khẩu để đặt mật khẩu mới.\n\nPet Care',
        'Cảnh báo: tài khoản Pet Care tạm khóa đăng nhập',
        E'Xin chào,\n\nTài khoản Pet Care của bạn vừa bị nhập sai mật khẩu {so_lan_sai} lần liên tiếp nên tạm thời không đăng nhập được cho tới {thoi_diem_mo_khoa}.\n\nNếu đó không phải bạn, hãy dùng chức năng Quên mật khẩu để đặt mật khẩu mới.\n\nPet Care',
        '["so_lan_sai", "thoi_diem_mo_khoa"]'::jsonb,
        '["thoi_diem_mo_khoa"]'::jsonb),

       ('PASSWORD_CHANGED', 'EMAIL',
        'Mật khẩu Pet Care của bạn đã được đặt lại',
        E'Xin chào,\n\nMật khẩu tài khoản Pet Care của bạn đã được đặt lại lúc {thoi_diem}. Mọi phiên đăng nhập trước đó đã bị đăng xuất.\n\nNếu đó không phải bạn, hãy liên hệ ngay chi nhánh Pet Care gần nhất.\n\nPet Care',
        'Mật khẩu Pet Care của bạn đã được đặt lại',
        E'Xin chào,\n\nMật khẩu tài khoản Pet Care của bạn đã được đặt lại lúc {thoi_diem}. Mọi phiên đăng nhập trước đó đã bị đăng xuất.\n\nNếu đó không phải bạn, hãy liên hệ ngay chi nhánh Pet Care gần nhất.\n\nPet Care',
        '["thoi_diem"]'::jsonb,
        '["thoi_diem"]'::jsonb);
