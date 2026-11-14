-- =============================================================================
-- V51 — Gỡ khiếu nại khỏi DB, thêm khóa lưu giữ feedback (T70 PR1, docs/adr/0122, docs/adr/0131; trả một phần D061).
--
--   * ADR-0122 bỏ khiếu nại (UC85, UC86, ST16, ST17, FSM #26). V39 và V40 đã áp dụng nên không sửa, chỉ gỡ ở đây:
--     bỏ complaint_messages rồi complaints (FK duy nhất trỏ vào complaints là của complaint_messages), 6 khóa [CFG]
--     complaint.* (mỗi khóa một câu DELETE, như V27) và 7 mẫu thông báo COMPLAINT_*. Không có mã Java nào ghi vào hai
--     bảng hay gọi enqueue với 7 mã này; nếu còn dòng notification_outbox trỏ tới một mẫu thì FK RESTRICT làm migration
--     lỗi chứ không xóa lặng lẽ.
--   * feedback.retention_days (30, 7–365, BR-DG-05): số ngày giữ feedback trước khi ST24 xóa cứng. Chưa có job đọc
--     khóa này cho tới PR kế tiếp (FeedbackCleanupJob).
--   * Hai index feedbacks chỉ THÊM, không đụng cột: (created_at) cho ST24 quét theo lô; (branch_id, created_at) cho danh
--     sách feedback theo chi nhánh sau khi cột status bị bỏ (migration kế tiếp, ix_feedbacks_branch_status đi theo cột).
--   * Bảng feedbacks giữ nguyên cột và CHECK: entity Feedback vẫn ánh xạ status / seen_at / resolved_*.
--   * Số V dùng chung với BE-2: V51 = phần này, V52 = bỏ cột trạng thái feedbacks, V53 = Nhân sự (D062). Thống nhất với
--     BE-2 trước khi merge (CLAUDE.md, 06 §6).
-- =============================================================================

DROP TABLE complaint_messages;
DROP TABLE complaints;

DELETE FROM system_configs WHERE key = 'complaint.max_open';
DELETE FROM system_configs WHERE key = 'complaint.reopen_days';
DELETE FROM system_configs WHERE key = 'complaint.max_reopen';
DELETE FROM system_configs WHERE key = 'complaint.sla_open_hours';
DELETE FROM system_configs WHERE key = 'complaint.sla_resolve_hours';
DELETE FROM system_configs WHERE key = 'complaint.auto_close_days';

DELETE FROM notification_templates WHERE code IN (
    'COMPLAINT_CREATED_APP', 'COMPLAINT_TAKEN', 'COMPLAINT_TAKEN_APP', 'COMPLAINT_RESOLVED',
    'COMPLAINT_RESOLVED_APP', 'COMPLAINT_REOPENED_APP', 'COMPLAINT_ESCALATED_APP');

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('feedback.retention_days', '30', 'INT', '7', '365', 'ngày', 'Số ngày giữ feedback kể từ lúc gửi; quá hạn thì ST24 xóa cứng (BR-DG-05)');

CREATE INDEX ix_feedbacks_created_at ON feedbacks (created_at);
CREATE INDEX ix_feedbacks_branch_created ON feedbacks (branch_id, created_at);
