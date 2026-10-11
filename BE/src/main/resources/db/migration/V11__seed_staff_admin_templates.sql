-- =============================================================================
-- V11 — Seed 3 mẫu email của quản trị nhân viên (T14 phần 0, UC08; BR-QT-02, 10, BR-TK-04, 16) — docs/adr/0029.
-- Số V11: CHỜ BE-2 XÁC NHẬN (đổi tên file nếu BE-2 đã dùng V11).
--
--   * STAFF_TEMP_PASSWORD (BR-QT-02, 10: mật khẩu ban đầu khi tạo, gửi lại, kích hoạt lại). Mỗi lần gửi là một mật
--     khẩu mới, nên mẫu nói rõ chỉ mật khẩu trong email mới nhất còn dùng được. {mat_khau_tam} bị xóa khỏi payload
--     outbox sau khi gửi (NotificationOutbox.SENSITIVE_KEY_WORDS có "mat_khau"). Luồng HIGH.
--   * OTP_EMAIL_CHANGE (BR-TK-04, 16: OTP gửi tới email MỚI khi người có thẩm quyền sửa hộ email). Câu chữ chung cho
--     cả nhân viên (UC08) lẫn khách tại quầy (UC22) vì cùng một mã. Luồng HIGH.
--   * EMAIL_CHANGED_NOTICE (BR-TK-16: thông báo tới cả email cũ lẫn email mới). {thoi_diem} do identity định dạng
--     "HH:mm dd/MM/yyyy" theo giờ Việt Nam (như V8). Luồng NORMAL.
--   * Theo khung V3/V8: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mẫu OTP bắt buộc có {ma_otp} (BR-QT-14). Hai mẫu IN_APP của thao tác khóa
--     (ACCOUNT_LOCKED_FOLLOW_UP_APP, LAST_BRANCH_MANAGER_LOCKED_APP) seed ở T14 phần 5 (người dùng chốt 2026-10-11).
--   * Seed trước lời gọi enqueue đầu tiên (T14 phần 1, 3, 4) theo tiền lệ V9; cả T14 nằm trong một PR.
--   * Chỉ INSERT; thay đổi tiếp theo dùng migration mới.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('STAFF_TEMP_PASSWORD', 'EMAIL',
        'Thông tin đăng nhập tài khoản nhân viên Pet Care',
        E'Xin chào {ho_ten},\n\nTài khoản nhân viên Pet Care của bạn đã sẵn sàng.\n\nEmail đăng nhập: {email_dang_nhap}\nMật khẩu tạm: {mat_khau_tam}\n\nBạn phải đổi mật khẩu ngay ở lần đăng nhập đầu tiên. Nếu bạn nhận được nhiều email như thế này, chỉ mật khẩu trong email mới nhất còn dùng được. Không chia sẻ mật khẩu này với bất kỳ ai.\n\nPet Care',
        'Thông tin đăng nhập tài khoản nhân viên Pet Care',
        E'Xin chào {ho_ten},\n\nTài khoản nhân viên Pet Care của bạn đã sẵn sàng.\n\nEmail đăng nhập: {email_dang_nhap}\nMật khẩu tạm: {mat_khau_tam}\n\nBạn phải đổi mật khẩu ngay ở lần đăng nhập đầu tiên. Nếu bạn nhận được nhiều email như thế này, chỉ mật khẩu trong email mới nhất còn dùng được. Không chia sẻ mật khẩu này với bất kỳ ai.\n\nPet Care',
        '["ho_ten", "email_dang_nhap", "mat_khau_tam"]'::jsonb,
        '["ho_ten", "email_dang_nhap", "mat_khau_tam"]'::jsonb),

       ('OTP_EMAIL_CHANGE', 'EMAIL',
        'Mã xác thực email mới cho tài khoản Pet Care',
        E'Xin chào,\n\nEmail này vừa được đề nghị làm email đăng nhập mới cho một tài khoản Pet Care. Mã xác thực là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Hãy đọc mã này cho nhân viên Pet Care đang hỗ trợ bạn. Nếu bạn không yêu cầu đổi email, hãy bỏ qua email này; không có gì thay đổi.\n\nPet Care',
        'Mã xác thực email mới cho tài khoản Pet Care',
        E'Xin chào,\n\nEmail này vừa được đề nghị làm email đăng nhập mới cho một tài khoản Pet Care. Mã xác thực là: {ma_otp}\n\nMã có hiệu lực trong {thoi_han_phut} phút và chỉ dùng được một lần. Hãy đọc mã này cho nhân viên Pet Care đang hỗ trợ bạn. Nếu bạn không yêu cầu đổi email, hãy bỏ qua email này; không có gì thay đổi.\n\nPet Care',
        '["ma_otp", "thoi_han_phut"]'::jsonb,
        '["ma_otp"]'::jsonb),

       ('EMAIL_CHANGED_NOTICE', 'EMAIL',
        'Email đăng nhập Pet Care đã được thay đổi',
        E'Xin chào,\n\nEmail đăng nhập của tài khoản Pet Care đã được đổi từ {email_cu} sang {email_moi} lúc {thoi_diem}. Từ nay chỉ email mới dùng để đăng nhập và nhận mã xác thực.\n\nNếu bạn không yêu cầu thay đổi này, hãy liên hệ ngay chi nhánh Pet Care gần nhất.\n\nPet Care',
        'Email đăng nhập Pet Care đã được thay đổi',
        E'Xin chào,\n\nEmail đăng nhập của tài khoản Pet Care đã được đổi từ {email_cu} sang {email_moi} lúc {thoi_diem}. Từ nay chỉ email mới dùng để đăng nhập và nhận mã xác thực.\n\nNếu bạn không yêu cầu thay đổi này, hãy liên hệ ngay chi nhánh Pet Care gần nhất.\n\nPet Care',
        '["email_cu", "email_moi", "thoi_diem"]'::jsonb,
        '["email_cu", "email_moi", "thoi_diem"]'::jsonb);
