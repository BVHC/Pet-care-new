-- =============================================================================
-- V62 — Nội trú, nền module (T69 N1, docs/adr/0149; đặc tả docs/09 §C, docs/adr/0148).
--
--   * ck_admissions_source: mỗi ca nhập viện có đúng MỘT nguồn (BR-LT-13): nhập từ lượt khám thì có chuồng điều trị
--     (kennel_id), nhập từ lưu trú thì có đặt chỗ (boarding_booking_id) và thú ở nguyên chuồng của đặt chỗ. V38 chưa
--     có CHECK này. Bảng admissions chưa có dòng nào ở mọi DB (chưa có Java ghi vào), nên thêm CHECK không cần dọn dữ liệu.
--   * INPATIENT_OBSERVATION_ABNORMAL (EMAIL) / INPATIENT_OBSERVATION_ABNORMAL_APP (IN_APP): mục theo dõi nội trú đánh dấu
--     bất thường (BR-LT-16, ADR-0148 mục 4). Khách nhận theo kênh của BR-TB-02; VET phụ trách nhận bản _APP. Không dùng
--     lại CARE_LOG_ABNORMAL vì mẫu đó bắt buộc {ma_dat_cho} mà ca nhập từ khám không có đặt chỗ. Biến bắt buộc:
--     {ten_thu_cung}, {chi_nhanh}, {ma_noi_tru}, {ngay_ghi} (dd/MM/yyyy); {ten_khach} không bắt buộc (khuôn
--     CARE_LOG_ABNORMAL, giả định của BE-1, ADR-0149). Câu _APP trung tính vì cả khách lẫn VET đều đọc.
--   * Theo khung V20: body = default_body, subject = default_subject; required_vars ⊆ allowed_vars; mọi biến bắt buộc có
--     mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--   * Số V dùng chung với BE-2: đã kiểm origin/dev, origin/dung, origin/Hien chưa dùng V62 (22/11/2026); V49 vẫn để dành.
--     Chỉ ALTER ADD CONSTRAINT và INSERT; không sửa V38.
-- =============================================================================

ALTER TABLE admissions ADD CONSTRAINT ck_admissions_source
    CHECK ((boarding_booking_id IS NULL) <> (kennel_id IS NULL));

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('INPATIENT_OBSERVATION_ABNORMAL', 'EMAIL',
        'Theo dõi nội trú của {ten_thu_cung} có dấu hiệu bất thường',
        E'Xin chào {ten_khach},\n\nBác sĩ vừa ghi nhận dấu hiệu bất thường của {ten_thu_cung} trong thời gian điều trị nội trú {ma_noi_tru} tại {chi_nhanh} (theo dõi ngày {ngay_ghi}).\nChi nhánh sẽ liên hệ với Quý khách. Quý khách cũng có thể gọi trực tiếp cho chi nhánh để biết thêm tình hình.\n\nTrân trọng,\nPet Care',
        'Theo dõi nội trú của {ten_thu_cung} có dấu hiệu bất thường',
        E'Xin chào {ten_khach},\n\nBác sĩ vừa ghi nhận dấu hiệu bất thường của {ten_thu_cung} trong thời gian điều trị nội trú {ma_noi_tru} tại {chi_nhanh} (theo dõi ngày {ngay_ghi}).\nChi nhánh sẽ liên hệ với Quý khách. Quý khách cũng có thể gọi trực tiếp cho chi nhánh để biết thêm tình hình.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi"]'::jsonb),

       ('INPATIENT_OBSERVATION_ABNORMAL_APP', 'IN_APP',
        '{ten_thu_cung} có dấu hiệu bất thường khi nội trú',
        E'Theo dõi ngày {ngay_ghi} của {ten_thu_cung} (ca nội trú {ma_noi_tru} tại {chi_nhanh}) được đánh dấu bất thường. Vui lòng xem chỉ số theo dõi và liên hệ chi nhánh.',
        '{ten_thu_cung} có dấu hiệu bất thường khi nội trú',
        E'Theo dõi ngày {ngay_ghi} của {ten_thu_cung} (ca nội trú {ma_noi_tru} tại {chi_nhanh}) được đánh dấu bất thường. Vui lòng xem chỉ số theo dõi và liên hệ chi nhánh.',
        '["ten_khach", "ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi"]'::jsonb,
        '["ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi"]'::jsonb);
