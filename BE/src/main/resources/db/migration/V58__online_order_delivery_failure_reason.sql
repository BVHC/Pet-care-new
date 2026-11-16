-- =============================================================================
-- V58 — Task 126, xử lý và giao đơn online (docs/adr/0137 D2, docs/08-tang3-be2-spec.md §B Đơn hàng online#8):
--
--   * online_orders.delivery_failure_reason VARCHAR(500) NULL: lý do giao thất bại gần nhất, bắt buộc khi ghi #8
--     (SHIPPED -> DELIVERY_FAILED; request tối đa 300 ký tự, cột 500 theo ADR-0137). Giữ nguyên sau khi giao lại (#9)
--     nên KHÔNG có CHECK gắn với status; đơn chưa từng giao thất bại thì NULL. Không có trong schema OnlineOrder của
--     hợp đồng shop-v1: chỉ lưu, và là biến ly_do của thông báo ONLINE_ORDER_DELIVERY_FAILED.
--   * Đánh số: V58 là số trống kế tiếp sau V57 ở origin/dev và origin/manh lúc viết (17/11/2026).
-- Cột nullable, không backfill, không index (không ai lọc theo lý do).
-- =============================================================================

ALTER TABLE online_orders ADD COLUMN delivery_failure_reason VARCHAR(500);
