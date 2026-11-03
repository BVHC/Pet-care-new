-- =============================================================================
-- V29 — trả nợ D032: index danh sách lịch hẹn của chi nhánh theo ngày — docs/adr/0094.
-- Số V29: BE-1 (appointment); BE-2 từ V30 (06-module-contracts §6). Chỉ CREATE INDEX.
--
-- appointment-v1 #3 của nhân viên luôn lọc branch_id, thường kèm date hoặc from / to, có hoặc không có status.
-- Trước V29 không index nào bắt đầu bằng (branch_id, slot_date) cho mọi trạng thái:
--   * ix_appointments_quota là partial (BOOKED, CHECKED_IN, COMPLETED) và cột thứ hai là service_group;
--   * ix_appointments_status_slot bắt đầu bằng status, branch_id chỉ là Filter sau khi đọc phần index của mọi chi nhánh.
-- Index mới không partial, thứ tự cột khớp ORDER BY slot_date, slot_start, id (id còn lại do incremental sort).
-- Giữ nguyên hai index cũ: ix_appointments_quota đếm quota (BR-LH-03); ix_appointments_status_slot phục vụ ST03, ST05.
-- Không đổi OFFSET / count(*) chính xác của #3: chấp nhận ở docs/adr/0094.
-- =============================================================================

CREATE INDEX ix_appointments_branch_slot ON appointments (branch_id, slot_date, slot_start);
