-- =============================================================================
-- V52 — Feedback không trạng thái (T70 PR2, docs/adr/0122, docs/adr/0131; trả một phần D061).
--
--   * ADR-0122 bỏ BR-DG-04: feedback không còn NEW / SEEN / RESOLVED, không còn ghi chú xử lý; quản lý chỉ đọc.
--     Bỏ 5 cột status, seen_at, resolved_by, resolved_at, resolution_note. PostgreSQL xóa theo cột các ràng buộc và
--     index dính cột: ck_feedbacks_status, ck_feedbacks_resolution_note, FK resolved_by, ix_feedbacks_branch_status (V1),
--     ix_feedbacks_resolved_by (V7). Hai index V51 (created_at) và (branch_id, created_at) giữ nguyên.
--   * Mất các ghi chú xử lý nội bộ đã có (nếu có): chấp nhận, đề tài chưa có dữ liệu thật; feedback vốn chỉ giữ 30 ngày.
--   * Migration này đi cùng PR bỏ trạng thái trong code (entity Feedback, FeedbackReviewService, endpoint /resolve):
--     tách ra thì ddl-auto=validate làm entity gãy.
--   * Số V dùng chung với BE-2: V52 chờ BE-2 xác nhận trước khi merge (CLAUDE.md, 06 §6).
-- =============================================================================

ALTER TABLE feedbacks
    DROP COLUMN status,
    DROP COLUMN seen_at,
    DROP COLUMN resolved_by,
    DROP COLUMN resolved_at,
    DROP COLUMN resolution_note;
