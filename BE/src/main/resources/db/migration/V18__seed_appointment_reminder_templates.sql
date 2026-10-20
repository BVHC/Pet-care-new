-- =============================================================================
-- V18 — Seed 2 mẫu thông báo nhắc lịch hẹn (T25 phần 4, ST03; BR-LH-12; 06-module-contracts §8 Q4) — docs/adr/0051.
-- Số V18: V16 (sequence mã lịch hẹn), V17 (mẫu hủy do phòng khám) của BE-1; cần BE-2 xác nhận trước khi gộp.
--
--   * APPOINTMENT_REMINDER (EMAIL): khách có tài khoản đã xác thực (email của tài khoản) hoặc hồ sơ tại quầy có email
--     (BR-TB-02). Luồng NORMAL của ST20 (khách không chờ trên màn hình).
--   * APPOINTMENT_REMINDER_APP (IN_APP): khách có tài khoản đã xác thực. subject bắt buộc (thành notifications.title
--     ≤ 200; notifications.type = mã bỏ "_APP", 20 ký tự ≤ 40 — docs/adr/0014).
--   * Biến: {ten_khach} không bắt buộc; {ten_thu_cung}, {dich_vu}, {chi_nhanh}, {dia_chi_chi_nhanh},
--     {so_dien_thoai_chi_nhanh}, {gio_hen} (HH:mm dd/MM/yyyy giờ Việt Nam), {ma_lich_hen} (appointments.code, giữ
--     nguyên khi đổi khung — BR-LH-06) bắt buộc.
--   * Theo khung V17: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--   * Seed cùng PR với lời gọi enqueue đầu tiên (AppointmentReminderNotices, 06 §8 Q4). Chỉ INSERT.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('APPOINTMENT_REMINDER', 'EMAIL',
        'Nhắc lịch hẹn của {ten_thu_cung} lúc {gio_hen}',
        E'Xin chào {ten_khach},\n\nPet Care xin nhắc lịch hẹn {dich_vu} của {ten_thu_cung} lúc {gio_hen} tại {chi_nhanh} ({dia_chi_chi_nhanh}).\nMã lịch hẹn: {ma_lich_hen}\n\nQuý khách vui lòng đến đúng giờ. Nếu không thể đến, vui lòng hủy hoặc đổi lịch sớm trên ứng dụng, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        'Nhắc lịch hẹn của {ten_thu_cung} lúc {gio_hen}',
        E'Xin chào {ten_khach},\n\nPet Care xin nhắc lịch hẹn {dich_vu} của {ten_thu_cung} lúc {gio_hen} tại {chi_nhanh} ({dia_chi_chi_nhanh}).\nMã lịch hẹn: {ma_lich_hen}\n\nQuý khách vui lòng đến đúng giờ. Nếu không thể đến, vui lòng hủy hoặc đổi lịch sớm trên ứng dụng, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "dich_vu", "chi_nhanh", "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen"]'::jsonb,
        '["ten_thu_cung", "dich_vu", "chi_nhanh", "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen"]'::jsonb),

       ('APPOINTMENT_REMINDER_APP', 'IN_APP',
        'Nhắc lịch hẹn của {ten_thu_cung}',
        E'{ten_thu_cung} có lịch hẹn {dich_vu} lúc {gio_hen} tại {chi_nhanh} ({dia_chi_chi_nhanh}). Mã lịch hẹn: {ma_lich_hen}.\nNếu không thể đến, vui lòng hủy hoặc đổi lịch sớm, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.',
        'Nhắc lịch hẹn của {ten_thu_cung}',
        E'{ten_thu_cung} có lịch hẹn {dich_vu} lúc {gio_hen} tại {chi_nhanh} ({dia_chi_chi_nhanh}). Mã lịch hẹn: {ma_lich_hen}.\nNếu không thể đến, vui lòng hủy hoặc đổi lịch sớm, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.',
        '["ten_khach", "ten_thu_cung", "dich_vu", "chi_nhanh", "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen"]'::jsonb,
        '["ten_thu_cung", "dich_vu", "chi_nhanh", "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen"]'::jsonb);
