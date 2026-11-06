-- =============================================================================
-- V42 — Order nguồn ONLINE (T55b làm thay BE-2, docs/adr/0116; đặc tả docs/08 §B #14b, docs/05 §13 mục 26).
--
--   * Đơn online là một Order bình thường (hóa đơn: dòng hàng đã chốt giá + tổng tiền hàng) sinh ở trạng thái PENDING
--     (Order#11), lên PAID khi cổng báo thành công (Order#12), về CANCELLED khi khách hủy hoặc quá hạn (Order#13). Tiền
--     KHÔNG đi vào payments: payments là biên lai quầy gắn ca thu ngân (cashier_shift_id, received_by NOT NULL; method
--     CASH / TRANSFER), còn tiền online nằm ở payment_transactions (08 §C). Phí vận chuyển nằm ở online_orders.shipping_fee,
--     không phải dòng Order (08 L461: báo cáo doanh thu báo riêng).
--   * Bốn CHECK của orders đổi, payments không đổi:
--       - ck_orders_source: thêm ONLINE.
--       - ck_orders_single_source: ONLINE như RETAIL — không Visit, không đặt chỗ lưu trú.
--       - ck_orders_paid_payment: ONLINE luôn payment_id NULL (kể cả khi PAID); nguồn khác giữ PAID ⇔ payment_id. Nhờ vậy
--         DB tự chặn việc thu tiền quầy cho đơn online (orders.payment_id chỉ trỏ tới biên lai quầy).
--       - ck_orders_cancel_type: thêm ONLINE_CUSTOMER (khách hủy, Đơn hàng online#3) và ONLINE_EXPIRED (ST10 quá hạn
--         thanh toán, #4). KHÔNG dùng UNPAID cho đơn online: SalesReportRepository.lostRevenue đang đếm UNPAID là doanh thu
--         mất của quầy (BR-BH-05). Hủy sau PAID không đổi Order (08 L97): hoàn tiền ghi ở refunds.
--   * "Order ONLINE PAID ⇒ có giao dịch SUCCEEDED" KHÔNG thành CHECK: thêm FK orders → payment_transactions sẽ tạo vòng FK với
--     online_orders. Service giữ bất biến trong một transaction chuỗi Giao dịch#2 → Đơn online#2 → Order#12 (T65).
--   * Migration chỉ nới DB. Java (OrderSource, contract sales-v1/report-v1) CHƯA có ONLINE và không cần cho tới khi T63 ghi Order
--     ONLINE đầu tiên: bản đó phải thêm OrderSource.ONLINE, enum trong contract, và loại ONLINE khỏi ST13 digest + danh sách
--     đơn chờ thu của quầy + endpoint thu tiền quầy (ADR-0116 mục 3).
--   * DROP ... IF EXISTS rồi ADD: lặp lại an toàn nếu T55c viết cùng thay đổi. Mọi dòng sẵn có (nguồn VISIT / RETAIL / BOARDING)
--     thỏa các CHECK mới.
-- =============================================================================

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_source;
ALTER TABLE orders ADD CONSTRAINT ck_orders_source CHECK (source IN ('VISIT', 'RETAIL', 'BOARDING', 'ONLINE'));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_single_source;
ALTER TABLE orders ADD CONSTRAINT ck_orders_single_source CHECK (
    (source = 'VISIT' AND visit_id IS NOT NULL AND boarding_booking_id IS NULL)
    OR (source = 'BOARDING' AND boarding_booking_id IS NOT NULL AND visit_id IS NULL)
    OR (source IN ('RETAIL', 'ONLINE') AND visit_id IS NULL AND boarding_booking_id IS NULL));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_paid_payment;
ALTER TABLE orders ADD CONSTRAINT ck_orders_paid_payment CHECK (
    (source = 'ONLINE' AND payment_id IS NULL)
    OR (source <> 'ONLINE' AND (status = 'PAID') = (payment_id IS NOT NULL)));

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_cancel_type;
ALTER TABLE orders ADD CONSTRAINT ck_orders_cancel_type CHECK (cancel_type IN
    ('VISIT_CANCELLED', 'RETAIL_UNCLOSED', 'CHECKOUT_ABORTED', 'UNPAID', 'ONLINE_CUSTOMER', 'ONLINE_EXPIRED'));
