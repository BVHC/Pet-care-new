-- D021 + ADR-0098.
-- 1. Index cho lọc GET /api/audit-logs?action=… (identity-v1 #36): bỏ giới hạn 31 ngày của luật R3 (ADR-0038 mục 3).
-- 2. Gỡ hai trigger chặn UPDATE / DELETE / TRUNCATE trên audit_logs (V1, ERD §13 dòng 6) theo yêu cầu của chủ dự án.
--    Từ đây bảo đảm "chỉ thêm mới" (BR-QT-16) nằm ở tầng ứng dụng: AuditLogEntity @Immutable, AuditLogRepository chỉ có
--    save, AuditLogController chỉ có GET.

CREATE INDEX ix_audit_logs_action_created_at ON audit_logs (action, created_at);

DROP TRIGGER trg_audit_logs_no_update_delete ON audit_logs;
DROP TRIGGER trg_audit_logs_no_truncate ON audit_logs;
DROP FUNCTION forbid_audit_log_change();
