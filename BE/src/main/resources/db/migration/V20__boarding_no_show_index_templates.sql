-- =============================================================================
-- V20 — Lưu trú (T31, boarding-v1 #1–12, ST05): 1 index + 2 mẫu thông báo — docs/adr/0065.
-- Số V20: V19 là thu ngân của BE-2; BE-2 lấy V20, cần BE-1 xác nhận trước khi gộp.
--
--   * ix_boarding_bookings_booked_check_in: ST05 quét đặt chỗ BOOKED tới hạn theo (check_in_date, id); partial theo
--     status = 'BOOKED' nên index nhỏ và khớp literal trong câu quét.
--   * BOARDING_CANCELLED_BY_CLINIC (EMAIL): khách có tài khoản đã xác thực (email của tài khoản) hoặc hồ sơ tại
--     quầy có email (BR-TB-02). Đặt chỗ#5: thú mất, chuyển chủ, ngày nghỉ / thu hẹp giờ mở cửa (BR-LT-08, BR-LH-10).
--   * BOARDING_CANCELLED_BY_CLINIC_APP (IN_APP): khách có tài khoản đã xác thực. subject bắt buộc (thành
--     notifications.title ≤ 200; type = mã bỏ "_APP", 28 ký tự ≤ 40 — docs/adr/0014).
--   * Biến: {ten_khach} không bắt buộc; {ten_thu_cung}, {chi_nhanh}, {ngay_nhan}, {ngay_tra} (dd/MM/yyyy),
--     {ma_dat_cho}, {ly_do} (câu của BoardingClinicCause, cũng là boarding_bookings.cancel_reason) bắt buộc.
--   * Theo khung V17/V19: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--   * Seed cùng PR với lời gọi enqueue đầu tiên (06 §8 Q4). Chỉ INSERT / CREATE INDEX.
-- =============================================================================

CREATE INDEX ix_boarding_bookings_booked_check_in ON boarding_bookings (check_in_date, id) WHERE status = 'BOOKED';

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('BOARDING_CANCELLED_BY_CLINIC', 'EMAIL',
        'Đặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} đã bị hủy',
        E'Xin chào {ten_khach},\n\nPhòng khám rất tiếc phải hủy đặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} (nhận {ngay_nhan}, trả {ngay_tra}).\nLý do: {ly_do}\n\nLần hủy này không tính là hủy muộn. Quý khách có thể đặt chỗ khác trên ứng dụng hoặc liên hệ chi nhánh.\n\nTrân trọng,\nPet Care',
        'Đặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} đã bị hủy',
        E'Xin chào {ten_khach},\n\nPhòng khám rất tiếc phải hủy đặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} (nhận {ngay_nhan}, trả {ngay_tra}).\nLý do: {ly_do}\n\nLần hủy này không tính là hủy muộn. Quý khách có thể đặt chỗ khác trên ứng dụng hoặc liên hệ chi nhánh.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ngay_nhan", "ngay_tra", "ma_dat_cho", "ly_do"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ngay_nhan", "ngay_tra", "ma_dat_cho", "ly_do"]'::jsonb),

       ('BOARDING_CANCELLED_BY_CLINIC_APP', 'IN_APP',
        'Phòng khám đã hủy đặt chỗ lưu trú của {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} (nhận {ngay_nhan}, trả {ngay_tra}) đã bị hủy.\nLý do: {ly_do}\nLần hủy này không tính là hủy muộn.',
        'Phòng khám đã hủy đặt chỗ lưu trú của {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} (nhận {ngay_nhan}, trả {ngay_tra}) đã bị hủy.\nLý do: {ly_do}\nLần hủy này không tính là hủy muộn.',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ngay_nhan", "ngay_tra", "ma_dat_cho", "ly_do"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ngay_nhan", "ngay_tra", "ma_dat_cho", "ly_do"]'::jsonb);
