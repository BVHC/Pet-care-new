-- =============================================================================
-- V7 — Index cho các cột FK trỏ tới accounts (trả nợ D009, docs/adr/0018).
--
--   * Xóa một dòng accounts (ST02, BR-TK-08) bắt Postgres kiểm FK RESTRICT ở mọi cột trỏ tới accounts bằng câu
--     "SELECT 1 FROM <bảng> WHERE <cột> = $1". 43 cột ở 34 bảng; trước V7 có 38 cột không có index dùng được
--     → mỗi tài khoản bị xóa là ~38 lần quét toàn bảng.
--   * Cột cho phép NULL: index partial WHERE <cột> IS NOT NULL (toán tử = là strict nên câu kiểm FK suy ra được
--     vị từ này; dòng NULL không bao giờ cần tìm). Cột NOT NULL: index thường.
--   * sessions.account_id và cashier_shifts.cashier_id đã có index partial (WHERE revoked_at IS NULL,
--     WHERE status = 'OPEN') nhưng câu kiểm FK không suy ra được vị từ đó nên không dùng được — thêm index đủ.
--   * Không CONCURRENTLY: Flyway chạy migration trong transaction; bảng còn gần rỗng.
--   * SchemaMigrationIT.everyForeignKeyToAccountsHasUsableIndex chặn cột FK mới trỏ tới accounts mà thiếu index.
-- =============================================================================

CREATE INDEX ix_sessions_account_id ON sessions (account_id);
CREATE INDEX ix_system_configs_updated_by ON system_configs (updated_by) WHERE updated_by IS NOT NULL;
CREATE INDEX ix_notification_templates_updated_by ON notification_templates (updated_by) WHERE updated_by IS NOT NULL;
CREATE INDEX ix_holidays_created_by ON holidays (created_by);
CREATE INDEX ix_branch_quota_defaults_updated_by ON branch_quota_defaults (updated_by);
CREATE INDEX ix_slot_quotas_updated_by ON slot_quotas (updated_by);
CREATE INDEX ix_customers_created_by ON customers (created_by) WHERE created_by IS NOT NULL;
CREATE INDEX ix_appointments_booked_by ON appointments (booked_by);
CREATE INDEX ix_booking_restrictions_lifted_by ON booking_restrictions (lifted_by) WHERE lifted_by IS NOT NULL;
CREATE INDEX ix_visits_checked_in_by ON visits (checked_in_by);
CREATE INDEX ix_visit_assignments_from_account_id ON visit_assignments (from_account_id) WHERE from_account_id IS NOT NULL;
CREATE INDEX ix_visit_assignments_to_account_id ON visit_assignments (to_account_id);
CREATE INDEX ix_visit_assignments_assigned_by ON visit_assignments (assigned_by);
CREATE INDEX ix_medical_records_last_edited_by ON medical_records (last_edited_by) WHERE last_edited_by IS NOT NULL;
CREATE INDEX ix_medical_record_addenda_created_by ON medical_record_addenda (created_by);
CREATE INDEX ix_boarding_bookings_booked_by ON boarding_bookings (booked_by);
CREATE INDEX ix_weight_records_recorded_by ON weight_records (recorded_by) WHERE recorded_by IS NOT NULL;
CREATE INDEX ix_boarding_check_ins_received_by ON boarding_check_ins (received_by);
CREATE INDEX ix_care_logs_recorded_by ON care_logs (recorded_by);
CREATE INDEX ix_care_log_addenda_created_by ON care_log_addenda (created_by);
CREATE INDEX ix_cashier_shifts_cashier_id ON cashier_shifts (cashier_id);
CREATE INDEX ix_cashier_shifts_reconciled_by ON cashier_shifts (reconciled_by) WHERE reconciled_by IS NOT NULL;
CREATE INDEX ix_payments_received_by ON payments (received_by);
CREATE INDEX ix_orders_created_by ON orders (created_by);
CREATE INDEX ix_orders_cancelled_by ON orders (cancelled_by) WHERE cancelled_by IS NOT NULL;
CREATE INDEX ix_order_lines_added_by ON order_lines (added_by);
CREATE INDEX ix_order_lines_owner_account_id ON order_lines (owner_account_id);
CREATE INDEX ix_stock_receipts_created_by ON stock_receipts (created_by);
CREATE INDEX ix_stock_receipts_confirmed_by ON stock_receipts (confirmed_by) WHERE confirmed_by IS NOT NULL;
CREATE INDEX ix_stock_receipts_cancelled_by ON stock_receipts (cancelled_by) WHERE cancelled_by IS NOT NULL;
CREATE INDEX ix_stock_adjustments_created_by ON stock_adjustments (created_by);
CREATE INDEX ix_stock_movements_created_by ON stock_movements (created_by);
CREATE INDEX ix_vaccinations_recorded_by ON vaccinations (recorded_by);
CREATE INDEX ix_articles_created_by ON articles (created_by);
CREATE INDEX ix_page_contents_updated_by ON page_contents (updated_by) WHERE updated_by IS NOT NULL;
CREATE INDEX ix_feedbacks_resolved_by ON feedbacks (resolved_by) WHERE resolved_by IS NOT NULL;
CREATE INDEX ix_care_tasks_handled_by ON care_tasks (handled_by) WHERE handled_by IS NOT NULL;
CREATE INDEX ix_notification_outbox_recipient_account_id ON notification_outbox (recipient_account_id) WHERE recipient_account_id IS NOT NULL;
