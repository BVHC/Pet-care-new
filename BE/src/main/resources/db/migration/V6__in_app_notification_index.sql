-- =============================================================================
-- V6 — ST20 giao thông báo kênh IN_APP (docs/adr/0014, trả nợ D006).
--
--   * Index partial cho câu chọn / đếm dòng IN_APP đến hạn của InAppNotificationJob: chỉ chứa dòng IN_APP chưa
--     giao, nên không phải đọc qua tồn đọng email PENDING (index V1 (status, next_attempt_at) không có channel).
--     Dòng EMAIL không vào index này. Câu truy vấn phải viết status / channel dạng hằng số để planner dùng được
--     index partial (NotificationOutboxRepository).
--   * Lệch erd — erd §13 mục 13.
-- =============================================================================

CREATE INDEX ix_notification_outbox_in_app_due ON notification_outbox (next_attempt_at, id)
    WHERE status = 'PENDING' AND channel = 'IN_APP';
