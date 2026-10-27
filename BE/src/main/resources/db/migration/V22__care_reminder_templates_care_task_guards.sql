-- =============================================================================
-- V22 — T39 phần 2: mẫu nhắc tái chủng / tái khám (ST04) + index & CHECK care_tasks (trả nợ D049) — docs/adr/0081.
-- Số V22: BE-1 (care); V23 giữ cho CHECK prescription_items (D043). Cần BE-2 xác nhận trước khi gộp.
-- Seed cùng PR với lời gọi enqueue đầu tiên của ST04 (06-module-contracts §8 Q4). Chỉ INSERT / CREATE / ALTER ADD.
--
-- 1. Bốn mẫu (BR-QT-14, BR-TB-01, 02, 06). Mỗi mũi / mỗi bệnh án một thông báo (cờ vaccinations.due_reminded_at,
--    medical_records.follow_up_reminded_at).
--   * *_REMINDER (EMAIL): khách có tài khoản hoặc hồ sơ tại quầy có email (BR-TB-02). Không có link: hồ sơ tại quầy
--     không đăng nhập được; link đặt lịch điền sẵn chỉ đi qua NotificationRequest.link của bản _APP.
--   * *_REMINDER_APP (IN_APP): khách có tài khoản. subject bắt buộc (notifications.title ≤ 200; type = mã bỏ "_APP").
--   * {ten_khach} không bắt buộc; còn lại bắt buộc. {loai_vaccine} = tên VaccineType (không theo nhãn hàng, BR-SP-07);
--     {ngay_tai_chung}, {ngay_tai_kham} dạng dd/MM/yyyy; {chi_nhanh} = chi nhánh phụ trách (BR-TB-02): tái chủng là
--     chi nhánh của mũi gần nhất cùng loại, tái khám là chi nhánh của Visit có hẹn.
--   * Khung V17/V18: body = default_body, subject = default_subject; required_vars ⊆ allowed_vars; mọi biến bắt buộc có
--     trong body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--
-- 2. Index (D049 mục 1). Không lọc status / task_type: ngoài hai câu hủy hệ thống (CareTaskRepository
--    cancelOpenVaccineTasks, cancelOpenFollowUpTasks), index vaccination_id còn phục vụ kiểm FK RESTRICT khi xóa mũi tiêm
--    ghi nhầm (visit-v1 #20, BR-KB-04) — câu kiểm FK "WHERE vaccination_id = $1" không dùng được uq_care_tasks_vaccine_overdue.
--
-- 3. CHECK trạng thái ↔ cột xử lý (D049 mục 2), chép bất biến CareTaskItSupport.invariantViolations; result đã do
--    ck_care_tasks_done_result (V1) giữ. CANCELLED cho handled_by NULL = hệ thống hủy (docs/adr/0079).
--    ADD CONSTRAINT kiểm dòng sẵn có: DB dev có dòng ghi tay sai bất biến thì V22 lỗi — cách xử lý ở docs/adr/0081.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('VACCINE_REMINDER', 'EMAIL',
        'Nhắc tiêm {loai_vaccine} cho {ten_thu_cung} ngày {ngay_tai_chung}',
        E'Xin chào {ten_khach},\n\nĐã gần đến ngày tiêm nhắc lại {loai_vaccine} của {ten_thu_cung} ({ngay_tai_chung}). Mũi trước được tiêm tại {chi_nhanh}.\n\nQuý khách vui lòng đặt lịch tiêm trên ứng dụng Pet Care hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        'Nhắc tiêm {loai_vaccine} cho {ten_thu_cung} ngày {ngay_tai_chung}',
        E'Xin chào {ten_khach},\n\nĐã gần đến ngày tiêm nhắc lại {loai_vaccine} của {ten_thu_cung} ({ngay_tai_chung}). Mũi trước được tiêm tại {chi_nhanh}.\n\nQuý khách vui lòng đặt lịch tiêm trên ứng dụng Pet Care hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "loai_vaccine", "ngay_tai_chung", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb,
        '["ten_thu_cung", "loai_vaccine", "ngay_tai_chung", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb),

       ('VACCINE_REMINDER_APP', 'IN_APP',
        'Sắp đến ngày tiêm nhắc lại của {ten_thu_cung}',
        '{ten_thu_cung} cần tiêm nhắc lại {loai_vaccine} vào ngày {ngay_tai_chung}. Bấm để đặt lịch tiêm, hoặc gọi {chi_nhanh} theo số {so_dien_thoai_chi_nhanh}.',
        'Sắp đến ngày tiêm nhắc lại của {ten_thu_cung}',
        '{ten_thu_cung} cần tiêm nhắc lại {loai_vaccine} vào ngày {ngay_tai_chung}. Bấm để đặt lịch tiêm, hoặc gọi {chi_nhanh} theo số {so_dien_thoai_chi_nhanh}.',
        '["ten_khach", "ten_thu_cung", "loai_vaccine", "ngay_tai_chung", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb,
        '["ten_thu_cung", "loai_vaccine", "ngay_tai_chung", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb),

       ('FOLLOW_UP_REMINDER', 'EMAIL',
        'Nhắc tái khám của {ten_thu_cung} ngày {ngay_tai_kham}',
        E'Xin chào {ten_khach},\n\nBác sĩ đã hẹn {ten_thu_cung} tái khám vào ngày {ngay_tai_kham} tại {chi_nhanh}.\n\nQuý khách vui lòng đặt lịch khám trên ứng dụng Pet Care hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        'Nhắc tái khám của {ten_thu_cung} ngày {ngay_tai_kham}',
        E'Xin chào {ten_khach},\n\nBác sĩ đã hẹn {ten_thu_cung} tái khám vào ngày {ngay_tai_kham} tại {chi_nhanh}.\n\nQuý khách vui lòng đặt lịch khám trên ứng dụng Pet Care hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.\n\nTrân trọng,\nPet Care',
        '["ten_khach", "ten_thu_cung", "ngay_tai_kham", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb,
        '["ten_thu_cung", "ngay_tai_kham", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb),

       ('FOLLOW_UP_REMINDER_APP', 'IN_APP',
        'Sắp đến ngày tái khám của {ten_thu_cung}',
        '{ten_thu_cung} có hẹn tái khám ngày {ngay_tai_kham} tại {chi_nhanh}. Bấm để đặt lịch, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.',
        'Sắp đến ngày tái khám của {ten_thu_cung}',
        '{ten_thu_cung} có hẹn tái khám ngày {ngay_tai_kham} tại {chi_nhanh}. Bấm để đặt lịch, hoặc gọi chi nhánh theo số {so_dien_thoai_chi_nhanh}.',
        '["ten_khach", "ten_thu_cung", "ngay_tai_kham", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb,
        '["ten_thu_cung", "ngay_tai_kham", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb);

CREATE INDEX ix_care_tasks_vaccination_id ON care_tasks (vaccination_id) WHERE vaccination_id IS NOT NULL;
CREATE INDEX ix_care_tasks_medical_record_id ON care_tasks (medical_record_id) WHERE medical_record_id IS NOT NULL;

ALTER TABLE care_tasks
    ADD CONSTRAINT ck_care_tasks_open_unhandled CHECK (status <> 'OPEN' OR
        (note IS NULL AND cancel_reason IS NULL AND handled_by IS NULL AND handled_at IS NULL)),
    ADD CONSTRAINT ck_care_tasks_done_handled CHECK (status <> 'DONE' OR
        (note IS NOT NULL AND handled_by IS NOT NULL AND handled_at IS NOT NULL AND cancel_reason IS NULL)),
    ADD CONSTRAINT ck_care_tasks_cancelled_reason CHECK (status <> 'CANCELLED' OR
        (cancel_reason IS NOT NULL AND handled_at IS NOT NULL));
