-- =============================================================================
-- V17 — Seed 2 mẫu thông báo khi phòng khám hủy lịch hẹn (T25 phần 1, Lịch hẹn#5; BR-LH-10, BR-CN-04, BR-KH-05,
-- BR-KH-08; 06-module-contracts §8 Q4) — docs/adr/0047.
-- Số V17: V13–V15 của BE-2 (nhánh dung), V16 là sequence mã lịch hẹn (đánh số lại khi gộp nhánh, 2026-10-18).
-- Cần BE-2 xác nhận trước khi gộp.
--
--   * APPOINTMENT_CANCELLED_BY_CLINIC (EMAIL): khách có tài khoản đã xác thực (email của tài khoản) hoặc hồ sơ tại
--     quầy có email (BR-TB-02).
--   * APPOINTMENT_CANCELLED_BY_CLINIC_APP (IN_APP): khách có tài khoản đã xác thực. subject bắt buộc (thành
--     notifications.title ≤ 200; notifications.type = mã bỏ "_APP", 31 ký tự ≤ 40 — docs/adr/0014).
--   * Biến: {ten_khach} không bắt buộc; {ten_thu_cung}, {dich_vu}, {chi_nhanh}, {gio_hen} (HH:mm dd/MM/yyyy giờ Việt
--     Nam), {ly_do} (câu của ClinicCancelCause, cũng là appointments.cancel_reason) bắt buộc.
--   * Theo khung V3/V8/V11/V12: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--   * Seed cùng PR với lời gọi enqueue đầu tiên (ClinicCancellationNotices, 06 §8 Q4). Chỉ INSERT.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('APPOINTMENT_CANCELLED_BY_CLINIC', 'EMAIL',
        'Lịch hẹn của {ten_thu_cung} lúc {gio_hen} đã bị hủy',
        E'Xin chào {ten_khach},\n\nPhòng khám rất tiếc phải hủy lịch hẹn {dich_vu} của {ten_thu_cung} tại {chi_nhanh} lúc {gio_hen}.\nLý do: {ly_do}\n\nLần hủy này không tính là hủy muộn. Quý khách có thể đặt lại lịch hẹn khác trên ứng dụng hoặc liên hệ chi nhánh.\n\nTrân trọng,\nPet Care',
        'Lịch hẹn của {ten_thu_cung} lúc {gio_hen} đã bị hủy',
        E'Xin chào {ten_khach},\n\nPhòng khám rất tiếc phải hủy lịch hẹn {dich_vu} của {ten_thu_cung} tại {chi_nhanh} lúc {gio_hen}.\nLý do: {ly_do}\n\nLần hủy này không tính là hủy muộn. Quý khách có thể đặt lại lịch hẹn khác trên ứng dụng hoặc liên hệ chi nhánh.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do"]'::jsonb,
        '["ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do"]'::jsonb),

       ('APPOINTMENT_CANCELLED_BY_CLINIC_APP', 'IN_APP',
        'Phòng khám đã hủy lịch hẹn của {ten_thu_cung}',
        E'Lịch hẹn {dich_vu} của {ten_thu_cung} tại {chi_nhanh} lúc {gio_hen} đã bị hủy.\nLý do: {ly_do}\nLần hủy này không tính là hủy muộn.',
        'Phòng khám đã hủy lịch hẹn của {ten_thu_cung}',
        E'Lịch hẹn {dich_vu} của {ten_thu_cung} tại {chi_nhanh} lúc {gio_hen} đã bị hủy.\nLý do: {ly_do}\nLần hủy này không tính là hủy muộn.',
        '["ten_khach", "ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do"]'::jsonb,
        '["ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do"]'::jsonb);
