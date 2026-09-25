-- Module 14 (Order) — docs/06-erd.md §3.6, docs/05-domain-model.md §4.14, RULE-14-04.
--
-- orders: KHÔNG áp BaseEntity (plain, giống purchase_requests V16 — customer_id là định danh
-- nghiệp vụ trỏ users(id), không phải audit trail chung accounts(id)). Thêm reserved_until
-- (nullable — chỉ set cho đơn Online lúc CreateOrder/CheckoutOrder, POS luôn NULL vì không giữ
-- chỗ ảo theo RULE-14-04) và cancelled_at (nullable, mốc CancelOrder/ProcessOrderTimeout — cùng
-- tinh thần cancelled_at của purchase_requests). version đã có từ V1, không đổi.
--
-- inventory_reservations: thêm version — khóa lạc quan chặn race double-release khi CancelOrder
-- (customer/receptionist) và ProcessOrderTimeout (job nền) cùng nhắm vào 1 reservation gần như
-- đồng thời lúc TTL sắp hết.

ALTER TABLE orders
    ADD COLUMN reserved_until TIMESTAMPTZ,
    ADD COLUMN cancelled_at   TIMESTAMPTZ;

ALTER TABLE inventory_reservations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- idx_orders_customer: phục vụ ViewOrder list (đơn của actor Customer).
-- idx_orders_status_reserved_until: phục vụ ProcessOrderTimeoutJob quét PENDING_PAYMENT quá hạn.
-- idx_inventory_reservations_order: phục vụ releaseReservation(orderId).
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_status_reserved_until ON orders (status, reserved_until);
CREATE INDEX idx_inventory_reservations_order ON inventory_reservations (order_id);
