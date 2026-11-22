-- =============================================================================
<<<<<<<< HEAD:BE/src/main/resources/db/migration/V55__drop_feedback_status_columns.sql
-- V55 — Feedback không trạng thái (T70 PR2, docs/adr/0122, docs/adr/0131; trả một phần D061).
========
-- V56 — Feedback không trạng thái (T70 PR2, docs/adr/0122, docs/adr/0131; trả một phần D061).
>>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069)):BE/src/main/resources/db/migration/V56__drop_feedback_status_columns.sql
--
--   * ADR-0122 bỏ BR-DG-04: feedback không còn NEW / SEEN / RESOLVED, không còn ghi chú xử lý; quản lý chỉ đọc.
--     Bỏ 5 cột status, seen_at, resolved_by, resolved_at, resolution_note. PostgreSQL xóa theo cột các ràng buộc và
--     index dính cột: ck_feedbacks_status, ck_feedbacks_resolution_note, FK resolved_by, ix_feedbacks_branch_status (V1),
--     ix_feedbacks_resolved_by (V7). Hai index V51 (created_at) và (branch_id, created_at) giữ nguyên.
--   * Mất các ghi chú xử lý nội bộ đã có (nếu có): chấp nhận, đề tài chưa có dữ liệu thật; feedback vốn chỉ giữ 30 ngày.
--   * Migration này đi cùng PR bỏ trạng thái trong code (entity Feedback, FeedbackReviewService, endpoint /resolve):
--     tách ra thì ddl-auto=validate làm entity gãy.
<<<<<<<< HEAD:BE/src/main/resources/db/migration/V55__drop_feedback_status_columns.sql
--   * Số V dùng chung với BE-2: V55 chờ BE-2 xác nhận trước khi merge (CLAUDE.md, 06 §6).
--   * Đánh số lại V52 → V55 (autopilot BE-2, 15/11/2026): V52 trùng với V52__ai_general_knowledge_switch (đã chạy ở DB demo). DB nào đã chạy bản V52 feedback cần flyway repair.
========
--   * Số V dùng chung với BE-2: V56 chờ BE-2 xác nhận trước khi merge (CLAUDE.md, 06 §6). Trước đây là V52, trùng V52 của AI
--     (ai_general_knowledge_switch) nên đổi số (D069); file chưa chạy ở DB nào.
>>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069)):BE/src/main/resources/db/migration/V56__drop_feedback_status_columns.sql
-- =============================================================================

ALTER TABLE feedbacks
    DROP COLUMN status,
    DROP COLUMN seen_at,
    DROP COLUMN resolved_by,
    DROP COLUMN resolved_at,
    DROP COLUMN resolution_note;
