-- =============================================================================
-- V30 — trả nợ D041: index lượt tiếp nhận của chi nhánh theo ngày check-in — docs/adr/0095.
-- Số V30: BE-1 (visit); BE-2 từ V31 (06-module-contracts §6). Chỉ CREATE INDEX.
--
-- visit-v1 #2 (ADR-0060 mục 3) lọc branch_id và checked_in_at trong một ngày giờ Việt Nam, có hoặc không có status;
-- status COMPLETED / CANCELLED không gửi date cũng thành "hôm nay", cùng khoảng checked_in_at.
-- Trước V30 không index nào bắt đầu bằng (branch_id, checked_in_at):
--   * ix_visits_queue (branch_id, status, priority_class, queue_sort_key) phục vụ hàng đợi dở; với câu theo ngày nó chỉ
--     cho điều kiện branch_id, nên Postgres đọc mọi lượt của chi nhánh (kể cả lịch sử COMPLETED / CANCELLED) rồi lọc.
-- Index mới không partial (phủ mọi trạng thái, vì date không kèm status trả mọi trạng thái của ngày).
-- checked_in_at không bao giờ UPDATE (visits.checked_in_at updatable = false), nên index chỉ tốn thêm khi INSERT.
-- Giữ nguyên năm index cũ của visits. Không đổi hợp đồng, không phân trang: chấp nhận ở docs/adr/0095.
-- =============================================================================

CREATE INDEX ix_visits_branch_checked_in_at ON visits (branch_id, checked_in_at);
