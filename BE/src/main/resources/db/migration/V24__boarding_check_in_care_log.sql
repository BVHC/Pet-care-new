-- =============================================================================
-- V24 — Lưu trú (T42, boarding-v1 #13–15, #19–22): 3 mẫu thông báo + 2 index — docs/adr/0085.
-- Số V24: V23 là của BE-1; BE-2 lấy V24, cần BE-1 xác nhận trước khi gộp.
--
--   * CARE_LOG_ABNORMAL (EMAIL) / CARE_LOG_ABNORMAL_APP (IN_APP): mục nhật ký đánh dấu bất thường (BR-LT-12, Đặt chỗ#8).
--     Khách theo BR-TB-02; lễ tân chi nhánh nhận bản _APP. Biến bắt buộc: {ten_thu_cung}, {chi_nhanh}, {ma_dat_cho},
--     {ngay_ghi} (dd/MM/yyyy); {ten_khach} không bắt buộc. Câu _APP trung tính vì cả khách lẫn lễ tân đọc.
--   * BOARDING_NO_KENNEL_APP (IN_APP): không còn chuồng đúng loại lúc nhận thú (BR-LT-08, Đặt chỗ#5), gửi
--     BRANCH_MANAGER. Biến bắt buộc: {ten_thu_cung}, {ma_dat_cho}, {ngay_nhan} (dd/MM/yyyy), {loai_chuong}.
--   * Theo khung V20: body = default_body, subject = default_subject; required_vars ⊆ allowed_vars; mọi biến bắt buộc
--     có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--   * ix_care_log_addenda_care_log_id: nạp bản bổ sung theo lô khi xem nhật ký. ix_weight_records_boarding_booking_id:
--     tra cân nặng lúc nhận theo đặt chỗ. Chỉ INSERT / CREATE INDEX.
-- =============================================================================

CREATE INDEX ix_care_log_addenda_care_log_id ON care_log_addenda (care_log_id);
CREATE INDEX ix_weight_records_boarding_booking_id ON weight_records (boarding_booking_id)
    WHERE boarding_booking_id IS NOT NULL;

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('CARE_LOG_ABNORMAL', 'EMAIL',
        'Nhật ký chăm sóc của {ten_thu_cung} có dấu hiệu bất thường',
        E'Xin chào {ten_khach},\n\nNhân viên chăm sóc vừa ghi nhận dấu hiệu bất thường của {ten_thu_cung} trong thời gian lưu trú {ma_dat_cho} tại {chi_nhanh} (nhật ký ngày {ngay_ghi}).\nChi nhánh sẽ liên hệ với Quý khách. Quý khách cũng có thể xem nhật ký trên ứng dụng hoặc gọi trực tiếp cho chi nhánh.\n\nTrân trọng,\nPet Care',
        'Nhật ký chăm sóc của {ten_thu_cung} có dấu hiệu bất thường',
        E'Xin chào {ten_khach},\n\nNhân viên chăm sóc vừa ghi nhận dấu hiệu bất thường của {ten_thu_cung} trong thời gian lưu trú {ma_dat_cho} tại {chi_nhanh} (nhật ký ngày {ngay_ghi}).\nChi nhánh sẽ liên hệ với Quý khách. Quý khách cũng có thể xem nhật ký trên ứng dụng hoặc gọi trực tiếp cho chi nhánh.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ma_dat_cho", "ngay_ghi"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ma_dat_cho", "ngay_ghi"]'::jsonb),

       ('CARE_LOG_ABNORMAL_APP', 'IN_APP',
        '{ten_thu_cung} có dấu hiệu bất thường khi lưu trú',
        E'Nhật ký ngày {ngay_ghi} của {ten_thu_cung} (đặt chỗ {ma_dat_cho} tại {chi_nhanh}) được đánh dấu bất thường. Vui lòng xem nhật ký chăm sóc và liên hệ chi nhánh.',
        '{ten_thu_cung} có dấu hiệu bất thường khi lưu trú',
        E'Nhật ký ngày {ngay_ghi} của {ten_thu_cung} (đặt chỗ {ma_dat_cho} tại {chi_nhanh}) được đánh dấu bất thường. Vui lòng xem nhật ký chăm sóc và liên hệ chi nhánh.',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ma_dat_cho", "ngay_ghi"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ma_dat_cho", "ngay_ghi"]'::jsonb),

       ('BOARDING_NO_KENNEL_APP', 'IN_APP',
        'Hết chuồng {loai_chuong} khi nhận thú {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} (ngày nhận {ngay_nhan}) bị hủy vì không còn chuồng {loai_chuong} trống lúc nhận thú. Vui lòng kiểm tra chuồng và thú quá hạn đón.',
        'Hết chuồng {loai_chuong} khi nhận thú {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} (ngày nhận {ngay_nhan}) bị hủy vì không còn chuồng {loai_chuong} trống lúc nhận thú. Vui lòng kiểm tra chuồng và thú quá hạn đón.',
        '["ten_thu_cung", "ma_dat_cho", "ngay_nhan", "loai_chuong"]'::jsonb,
        '["ten_thu_cung", "ma_dat_cho", "ngay_nhan", "loai_chuong"]'::jsonb);
