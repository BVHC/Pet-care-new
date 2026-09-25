-- Module 13 (Procurement) — docs/06-erd.md §3.5, docs/05-domain-model.md §4.13.
--
-- suppliers: Aggregate Root CRUD thuần (ManageSupplier, RULE-13-04), KHÔNG có cặp Maker-Checker
-- -> áp BaseEntity đầy đủ (created_by/updated_by -> accounts(id)), cùng khuôn V15 nâng
-- inventory_items lên BaseEntity.
--
-- purchase_requests: created_by/approved_by đã trỏ users(id) từ V1 — cặp định danh Maker-Checker
-- nghiệp vụ RULE-13-02, khác không gian với audit trail chung (accounts(id)) -> KHÔNG áp BaseEntity
-- đầy đủ (cùng lý do V15 giữ inventory_adjustments ở dạng plain entity). Thêm submitted_at/
-- decided_at/cancelled_at — mốc thời gian từng transition; decided_at dùng chung cho cả APPROVED
-- lẫn REJECTED, đối xứng với approved_by cũng dùng chung 2 nhánh (RULE-13-02) — và version (chặn
-- race double-approve/double-cancel, cùng lý do V15 thêm version cho inventory_adjustments).
--
-- purchase_orders: created_by trỏ users(id) từ V1 — cùng loài định danh nghiệp vụ (PO không có
-- approved_by, chỉ 1 actor tạo) -> cũng KHÔNG áp BaseEntity đầy đủ. Chỉ thêm version (khóa lạc
-- quan, dọn đường cho ReceiveGoods/CancelPurchaseOrder/CancelRemainingPurchaseOrder ở task sau —
-- RULE-13-05..08 ngoài phạm vi lần này); KHÔNG thêm updated_at vì trong phạm vi task này PO chỉ
-- dừng ở ISSUED, chưa có hành động sửa nội dung nào.
--
-- purchase_request_lines: thêm recommended_supplier_name (nullable, text tự do) — RULE-13-01 yêu
-- cầu rõ "...và nhà cung cấp khuyến nghị" nhưng V1 chưa có cột nào lưu — đây là advisory tự do,
-- khác với Supplier thật gắn ở purchase_orders.supplier_id (FK có ràng buộc ACTIVE, RULE-13-04).
--
-- purchase_order_lines: không đổi — dòng thuần túy, tạo 1 lần lúc PO khởi tạo, chưa có lệnh sửa
-- nào trong phạm vi task này.

ALTER TABLE suppliers
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE purchase_requests
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN decided_at   TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN version      BIGINT NOT NULL DEFAULT 0;

ALTER TABLE purchase_orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE purchase_request_lines
    ADD COLUMN recommended_supplier_name VARCHAR(255);

CREATE INDEX idx_suppliers_organization_status ON suppliers (organization_id, status);
CREATE INDEX idx_purchase_requests_store_status ON purchase_requests (store_id, status);
CREATE INDEX idx_purchase_request_lines_request ON purchase_request_lines (purchase_request_id);
CREATE INDEX idx_purchase_orders_store_status ON purchase_orders (store_id, status);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_order_lines_order ON purchase_order_lines (purchase_order_id);
