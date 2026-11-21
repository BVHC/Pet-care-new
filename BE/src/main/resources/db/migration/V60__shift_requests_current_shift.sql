-- =============================================================================
-- V60 — Nhân sự (T59 N3-S5b, docs/adr/0139 mục 17–19, trả D074-b; docs/05-erd.md §14.1, docs/02 BR-NS-04, BR-NS-06):
--   shift_requests.current_shift_definition_id = ca của phân ca LÚC GỬI yêu cầu (ảnh chụp, không đổi sau đó).
--   Duyệt SWAP (Phân ca#5) đổi shift_assignments.shift_definition_id sang ca đích, nên yêu cầu phải tự giữ ca gốc
--   để currentShiftName của yêu cầu đã duyệt vẫn là ca trước khi đổi.
--
--   * V36 đã áp dụng, không sửa. Số V60 vì origin/dev đã dùng V55–V59 (nợ D069 / D059: V55, V56 của nhánh manh trùng
--     số với dev, đổi số khi rebase).
--   * Backfill từ phân ca đúng vì tới V60 chưa có SWAP nào được duyệt (duyệt SWAP là placeholder 500, D074-d), nên ca
--     của phân ca chính là ca lúc gửi. Điều kiện này tự kiểm: nếu có SWAP đã duyệt, backfill ghi ca đích vào cột mới và
--     CHECK ck_shift_requests_swap_differs làm cả migration hỏng (rollback), không âm thầm ghi sai (bài học D068).
--   * Không thêm index: shift_definitions không bao giờ bị xóa (BR-NS-01), khóa ngoại RESTRICT không phải quét.
-- =============================================================================

ALTER TABLE shift_requests
    ADD COLUMN current_shift_definition_id BIGINT REFERENCES shift_definitions (id) ON DELETE RESTRICT;

UPDATE shift_requests r
SET current_shift_definition_id = a.shift_definition_id
FROM shift_assignments a
WHERE a.id = r.assignment_id;

ALTER TABLE shift_requests ALTER COLUMN current_shift_definition_id SET NOT NULL;

ALTER TABLE shift_requests
    ADD CONSTRAINT ck_shift_requests_swap_differs
        CHECK (target_shift_definition_id IS NULL OR target_shift_definition_id <> current_shift_definition_id);
