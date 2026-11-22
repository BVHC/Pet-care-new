-- =============================================================================
-- V55 — Nhân sự (T58, docs/adr/0123, D062; docs/05-erd.md §14.1, docs/02 BR-NS-02, BR-NS-11, BR-NS-12):
--   1. shift_assignments.source: SELF (nhân viên tự đăng ký tuần, BR-NS-11) | MANAGER (BRANCH_MANAGER phân hộ, BR-NS-02).
--   2. Khóa [CFG] workforce.weekly_required_shifts: định mức ca mỗi tuần (BR-NS-12), khớp ConfigKey.
--
--   * V36 đã áp dụng, không sửa. DEFAULT 'MANAGER' chỉ để ADD COLUMN NOT NULL chạy được trên bảng đã có dòng, rồi bỏ ngay:
--     code buộc phải truyền source, tránh phân ca tự đăng ký bị ghi nhầm thành MANAGER.
--   * Không thêm index: "phân ca SELF của nhân viên trong tuần" dùng ix_shift_assignments_staff_date (V36);
--     ux_shift_assignments_scheduled (V36) không đổi và áp cho cả hai nguồn (BR-NS-02).
--   * Khoảng 1–30 và mặc định 10 theo ADR-0123 mục 4. Không có ràng buộc chéo với khóa khác (ConfigConstraints không đổi).
-- =============================================================================

ALTER TABLE shift_assignments
    ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'MANAGER',
    ADD CONSTRAINT ck_shift_assignments_source CHECK (source IN ('SELF', 'MANAGER'));
ALTER TABLE shift_assignments ALTER COLUMN source DROP DEFAULT;

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('workforce.weekly_required_shifts', '10', 'INT', '1', '30', 'ca/tuần',
 'Số ca tối thiểu mỗi nhân viên phải đăng ký trong một tuần (BR-NS-12)');
