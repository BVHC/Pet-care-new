-- =============================================================================
-- V66 — Gỡ trả hàng và hoàn tiền khỏi DB (docs/adr/0156; bỏ UC68, UC69, ST21, FSM #16, #17, Đơn hàng online #10, #13).
--
--   * V40, V45, V46, V47 đã áp dụng nên không sửa, chỉ gỡ ở đây (cùng khuôn V51 bỏ khiếu nại).
--   * Bỏ refunds, return_request_lines rồi return_requests: FK duy nhất trỏ vào ba bảng là FK giữa chính chúng. Không còn
--     mã Java nào đọc / ghi ba bảng này.
--   * 2 khóa [CFG] payment.refund_max_attempts (V45), returns.window_days (V46): mỗi khóa một câu DELETE, như V27 / V51.
--   * 6 mẫu thông báo RETURN_* / REFUND_* (V40). Nếu còn dòng notification_outbox trỏ tới một mẫu thì FK RESTRICT làm
--     migration lỗi chứ không xóa lặng lẽ.
--   * Lý do điều chỉnh tồn RETURN_DISCARD (V47) bỏ khỏi ck_stock_adjustments_reason; chưa có mã nào ghi lý do này, còn
--     dòng nào thì ADD CONSTRAINT lỗi chứ không xóa lặng lẽ.
--   * V65 giữ cho ADR-0153 (trigger khóa bệnh án, chưa commit lúc viết file này).
-- =============================================================================

DROP TABLE refunds;
DROP TABLE return_request_lines;
DROP TABLE return_requests;

DELETE FROM system_configs WHERE key = 'payment.refund_max_attempts';
DELETE FROM system_configs WHERE key = 'returns.window_days';

DELETE FROM notification_templates WHERE code IN (
    'RETURN_REQUESTED_APP', 'RETURN_APPROVED_APP', 'RETURN_REJECTED_APP',
    'REFUND_REQUESTED', 'REFUND_REQUESTED_APP', 'REFUND_FAILED_APP');

ALTER TABLE stock_adjustments DROP CONSTRAINT ck_stock_adjustments_reason;
ALTER TABLE stock_adjustments ADD CONSTRAINT ck_stock_adjustments_reason CHECK (reason IN (
    'DAMAGED', 'EXPIRED', 'COUNT_MISMATCH', 'OTHER', 'COUNT_DIFF'));
