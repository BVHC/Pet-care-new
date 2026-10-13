-- =============================================================================
-- V12 — Seed 2 mẫu thông báo trong app của thao tác khóa tài khoản (T14 phần 5, UC09; BR-QT-11, BR-QT-04;
-- 06-module-contracts §8 Q2, Q4) — docs/adr/0035.
-- Số V12: đã xác nhận với BE-2 (2026-10-12, cùng V11).
--
--   * ACCOUNT_LOCKED_FOLLOW_UP_APP (BR-QT-11): người phụ trách xử lý tiếp việc dở dang của tài khoản vừa bị khóa —
--     BRANCH_MANAGER của chi nhánh (nhân viên A06–A08; không còn ai thì SUPER_MANAGER), SUPER_MANAGER (người bị khóa là
--     BRANCH_MANAGER), lễ tân của từng chi nhánh có việc dở (khách, Q2 — mỗi lễ tân chỉ thấy việc ở chi nhánh mình).
--   * LAST_BRANCH_MANAGER_LOCKED_APP (BR-QT-04): BRANCH_MANAGER cuối cùng đang hoạt động của chi nhánh ACTIVE bị khóa →
--     mọi SUPER_MANAGER, thay cho mẫu trên (người dùng chốt 2026-10-12).
--   * Kênh IN_APP: subject bắt buộc (thành notifications.title ≤ 200; notifications.type = mã bỏ "_APP", ≤ 40 —
--     docs/adr/0014). Biến: {ho_ten}, {vai_tro} không bắt buộc; {email_tai_khoan}, {ly_do}, {thoi_diem} (HH:mm
--     dd/MM/yyyy giờ Việt Nam), {viec_do_dang} (mỗi việc một dòng, "Không có" khi rỗng) bắt buộc; mẫu LAST thêm
--     {ten_chi_nhanh} bắt buộc. Lý do khóa nằm trong thông báo (người dùng chốt 2026-10-12).
--   * Theo khung V3/V8/V11: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong body.
--   * Seed cùng PR với lời gọi enqueue đầu tiên (AccountLockService, 06 §8 Q4). Chỉ INSERT.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('ACCOUNT_LOCKED_FOLLOW_UP_APP', 'IN_APP',
        'Tài khoản đã bị khóa — cần xử lý việc dở dang',
        E'Tài khoản {email_tai_khoan} ({ho_ten}, {vai_tro}) đã bị quản trị viên khóa lúc {thoi_diem}.\nLý do: {ly_do}\n\nViệc dở dang cần xử lý tiếp:\n{viec_do_dang}',
        'Tài khoản đã bị khóa — cần xử lý việc dở dang',
        E'Tài khoản {email_tai_khoan} ({ho_ten}, {vai_tro}) đã bị quản trị viên khóa lúc {thoi_diem}.\nLý do: {ly_do}\n\nViệc dở dang cần xử lý tiếp:\n{viec_do_dang}',
        '["ho_ten", "email_tai_khoan", "vai_tro", "ly_do", "thoi_diem", "viec_do_dang"]'::jsonb,
        '["email_tai_khoan", "ly_do", "thoi_diem", "viec_do_dang"]'::jsonb),

       ('LAST_BRANCH_MANAGER_LOCKED_APP', 'IN_APP',
        'Chi nhánh không còn quản lý đang hoạt động',
        E'Quản lý cuối cùng của chi nhánh {ten_chi_nhanh} là {ho_ten} ({email_tai_khoan}) đã bị quản trị viên khóa lúc {thoi_diem}.\nLý do: {ly_do}\n\nChi nhánh vẫn hoạt động bình thường. Hãy bổ nhiệm quản lý thay thế hoặc chờ tài khoản được mở khóa.\n\nViệc dở dang cần xử lý tiếp:\n{viec_do_dang}',
        'Chi nhánh không còn quản lý đang hoạt động',
        E'Quản lý cuối cùng của chi nhánh {ten_chi_nhanh} là {ho_ten} ({email_tai_khoan}) đã bị quản trị viên khóa lúc {thoi_diem}.\nLý do: {ly_do}\n\nChi nhánh vẫn hoạt động bình thường. Hãy bổ nhiệm quản lý thay thế hoặc chờ tài khoản được mở khóa.\n\nViệc dở dang cần xử lý tiếp:\n{viec_do_dang}',
        '["ho_ten", "email_tai_khoan", "vai_tro", "ly_do", "thoi_diem", "viec_do_dang", "ten_chi_nhanh"]'::jsonb,
        '["email_tai_khoan", "ly_do", "thoi_diem", "viec_do_dang", "ten_chi_nhanh"]'::jsonb);
