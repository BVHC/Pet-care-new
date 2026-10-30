-- =============================================================================
-- V25 — Lưu trú (T43, boarding-v1 #16–18, sales-v1 #9, ST15): 3 index + 4 mẫu thông báo — docs/adr/0086.
-- Số V25: V24 là của BE-2 (T42); cần BE-1 xác nhận trước khi gộp.
--
--   * ix_boarding_bookings_checked_in_due: ST15 quét đặt chỗ CHECKED_IN tới hạn theo (check_out_date, id); partial theo
--     status = 'CHECKED_IN' để khớp literal trong câu quét. ix_boarding_bookings_overdue: lượt theo dõi quét OVERDUE
--     theo id (thông báo ngày, Care Task, báo quản lý).
--   * ix_orders_boarding_booking_id: tra Order lưu trú theo đặt chỗ ở mọi trạng thái (hasPaidOrder, findPaidOrder);
--     uq_orders_boarding_unfinished chỉ phủ OPEN / PENDING.
--   * BOARDING_OVERDUE (EMAIL) / BOARDING_OVERDUE_APP (IN_APP): thú quá hạn đón, gửi khách mỗi ngày (BR-LT-10, BR-TB-02).
--     Biến bắt buộc {ten_thu_cung}, {chi_nhanh}, {so_dien_thoai_chi_nhanh}, {ma_dat_cho}, {ngay_tra} (dd/MM/yyyy),
--     {so_ngay_qua_han}; {ten_khach} không bắt buộc.
--   * BOARDING_OVERDUE_MANAGER_APP (IN_APP): quá hạn từ 7 ngày [CFG], gửi BRANCH_MANAGER. Biến bắt buộc
--     {ten_thu_cung}, {ma_dat_cho}, {ngay_tra}, {so_ngay_qua_han}, {sdt_khan} (SĐT liên hệ khẩn ghi lúc nhận thú).
--   * BOARDING_CAPACITY_WARNING_APP (IN_APP): đặt chỗ quá hạn làm thiếu chỗ cho đặt chỗ sắp nhận, gửi lễ tân. Biến bắt
--     buộc {loai_chuong}, {ma_dat_cho_qua_han}, {danh_sach_dat_cho} (mã cách nhau bằng dấu phẩy).
--   * Theo khung V20 / V24: body = default_body, subject = default_subject; required_vars ⊆ allowed_vars; mọi biến bắt
--     buộc có mặt trong body (SchemaMigrationIT); ngoặc nhọn chỉ dùng cho biến (docs/adr/0037). Chỉ INSERT / CREATE INDEX.
-- =============================================================================

CREATE INDEX ix_boarding_bookings_checked_in_due ON boarding_bookings (check_out_date, id) WHERE status = 'CHECKED_IN';
CREATE INDEX ix_boarding_bookings_overdue ON boarding_bookings (id) WHERE status = 'OVERDUE';
CREATE INDEX ix_orders_boarding_booking_id ON orders (boarding_booking_id) WHERE boarding_booking_id IS NOT NULL;

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('BOARDING_OVERDUE', 'EMAIL',
        'Bé {ten_thu_cung} đã quá hạn đón về ({ma_dat_cho})',
        E'Xin chào {ten_khach},\n\nĐặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} đã quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Phí lưu trú vẫn được tính theo đêm cho tới khi bé được đón về.\nQuý khách vui lòng đón bé hoặc liên hệ chi nhánh qua số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        'Bé {ten_thu_cung} đã quá hạn đón về ({ma_dat_cho})',
        E'Xin chào {ten_khach},\n\nĐặt chỗ lưu trú {ma_dat_cho} của {ten_thu_cung} tại {chi_nhanh} đã quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Phí lưu trú vẫn được tính theo đêm cho tới khi bé được đón về.\nQuý khách vui lòng đón bé hoặc liên hệ chi nhánh qua số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han"]'::jsonb),

       ('BOARDING_OVERDUE_APP', 'IN_APP',
        'Bé {ten_thu_cung} đã quá hạn đón về',
        E'Bé {ten_thu_cung} (đặt chỗ {ma_dat_cho}) tại {chi_nhanh} quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Phí lưu trú vẫn tính theo đêm. Vui lòng đón bé hoặc gọi chi nhánh: {so_dien_thoai_chi_nhanh}.',
        'Bé {ten_thu_cung} đã quá hạn đón về',
        E'Bé {ten_thu_cung} (đặt chỗ {ma_dat_cho}) tại {chi_nhanh} quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Phí lưu trú vẫn tính theo đêm. Vui lòng đón bé hoặc gọi chi nhánh: {so_dien_thoai_chi_nhanh}.',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han"]'::jsonb),

       ('BOARDING_OVERDUE_MANAGER_APP', 'IN_APP',
        'Quá hạn đón {so_ngay_qua_han} ngày: {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Số liên hệ khẩn: {sdt_khan}.',
        'Quá hạn đón {so_ngay_qua_han} ngày: {ten_thu_cung}',
        E'Đặt chỗ {ma_dat_cho} của {ten_thu_cung} quá hạn đón {so_ngay_qua_han} ngày (ngày trả dự kiến {ngay_tra}). Số liên hệ khẩn: {sdt_khan}.',
        '["ten_thu_cung", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han", "sdt_khan"]'::jsonb,
        '["ten_thu_cung", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han", "sdt_khan"]'::jsonb),

       ('BOARDING_CAPACITY_WARNING_APP', 'IN_APP',
        'Thiếu chuồng {loai_chuong} vì {ma_dat_cho_qua_han} quá hạn đón',
        E'Đặt chỗ {ma_dat_cho_qua_han} quá hạn đón đang chiếm chuồng {loai_chuong}. Các đặt chỗ sắp nhận có thể thiếu chỗ: {danh_sach_dat_cho}.',
        'Thiếu chuồng {loai_chuong} vì {ma_dat_cho_qua_han} quá hạn đón',
        E'Đặt chỗ {ma_dat_cho_qua_han} quá hạn đón đang chiếm chuồng {loai_chuong}. Các đặt chỗ sắp nhận có thể thiếu chỗ: {danh_sach_dat_cho}.',
        '["loai_chuong", "ma_dat_cho_qua_han", "danh_sach_dat_cho"]'::jsonb,
        '["loai_chuong", "ma_dat_cho_qua_han", "danh_sach_dat_cho"]'::jsonb);
