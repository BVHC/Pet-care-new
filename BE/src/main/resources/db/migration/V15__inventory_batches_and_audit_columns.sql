-- Module 12 (Inventory) — docs/06-erd.md §3.5, docs/05-domain-model.md §4.12.
--
-- inventory_items: Aggregate Root, thiếu 4/5 cột audit chuẩn BaseEntity (chỉ có version từ V1)
-- — bổ sung theo đúng khuôn V5/V8/V14 (created_by/updated_by -> accounts(id)).
--
-- inventory_adjustments: KHÔNG áp BaseEntity đầy đủ — created_by/approved_by đã trỏ users(id)
-- từ V1, là cặp định danh maker-checker nghiệp vụ RULE-12-03, khác không gian với audit trail
-- chung (accounts(id)). Không thêm updated_by/deleted_at: phiếu chỉ có 2 hành động
-- approve/reject, không có thao tác sửa nội dung hay soft-delete nào khác (cùng lý do V14 bỏ
-- deleted_at cho store_products/store_services). Chỉ thêm decided_at (thời điểm approve/reject,
-- đặt tên theo kiểu stock_transfers.shipped_at/received_at) và version (chặn race double-approve).
--
-- inventory_batches (mới, RULE-12-11 FEFO): ERD hiện chỉ có vaccine_batches (M10, scoped
-- category=MEDICINE), không có cơ chế lô/hạn dùng chung cho mọi mặt hàng. Bảng con nhiều dòng
-- (không phải cột đơn trên inventory_items) vì FEFO cần chọn đúng lô hết hạn sớm nhất trong
-- nhiều lô đang tồn song song của cùng Store+Product. inventory_items vẫn là rollup tổng dùng
-- cho TrackInventory + optimistic-lock overselling guard; inventory_batches là chi tiết theo lô
-- dùng cho FEFO issue-selection + TrackBatch/TrackExpiry. manufacture_date/expiry_date nullable
-- vì hàng ACCESSORY hợp lệ không có hạn dùng — không tự bịa ngày.

ALTER TABLE inventory_items
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ;
-- created_at không có ở V1 cho inventory_items (khác products/stores) — bổ sung để khớp BaseEntity.
ALTER TABLE inventory_items
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
-- V1 không có UNIQUE(store_id, product_id) — 2 lệnh ReceiveInventory đầu tiên đồng thời cho cùng
-- Store+Product (chưa có dòng inventory_items nào) đều thấy "chưa tồn tại" và có thể insert 2
-- dòng trùng, làm hỏng rollup (RULE-12-01 mỗi Store/Product chỉ 1 dòng tồn kho duy nhất).
ALTER TABLE inventory_items
    ADD CONSTRAINT uq_inventory_items_store_product UNIQUE (store_id, product_id);

ALTER TABLE inventory_adjustments
    ADD COLUMN decided_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

CREATE TABLE inventory_batches (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id          UUID NOT NULL REFERENCES stores (id),
    product_id        UUID NOT NULL REFERENCES products (id),
    batch_number      VARCHAR(100) NOT NULL,
    manufacture_date  DATE,
    expiry_date       DATE,
    quantity          INT NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID REFERENCES accounts (id),
    updated_by        UUID REFERENCES accounts (id),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_batches_store_product_batch UNIQUE (store_id, product_id, batch_number)
);

-- Không cần index riêng cho inventory_items.store_id — uq_inventory_items_store_product ở trên
-- đã là index (store_id, product_id), store_id là cột dẫn đầu.
CREATE INDEX idx_inventory_adjustments_store_status ON inventory_adjustments (store_id, status);
CREATE INDEX idx_inventory_batches_store_product_expiry ON inventory_batches (store_id, product_id, expiry_date);
