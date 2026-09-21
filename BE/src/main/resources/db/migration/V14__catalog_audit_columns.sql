-- docs/06-erd.md §3.2 Bảng: products/services/store_products/store_services — bổ sung cột
-- audit chuẩn BaseEntity (created_by/updated_by/deleted_at/version), theo quy ước bắt buộc ở
-- docs/architecture/system-overview.md §3 khi module Catalog được triển khai (cùng khuôn
-- V5__organization_audit_columns.sql / V8__store_audit_columns.sql). products/services thiếu cả
-- updated_at (V1 chỉ có created_at). store_products/store_services đã có created_at/updated_at
-- từ V1, chỉ thiếu created_by/updated_by/version — không thêm deleted_at cho 2 bảng override này,
-- cùng lý do store_policies/organization_policies: bản ghi override không có khái niệm soft-delete
-- riêng, chỉ bị ghi đè qua PUT.
--
-- Đồng thời thêm UNIQUE(organization_id, code) cho services — ERD gốc không có UNIQUE tường minh
-- (chỉ products.sku có uq_products_org_sku), nhưng docs/api/catalog-v1.md ASSUMPTION A5 (mục E,
-- Q3/Q8) đã nghiêng về hướng coi service code là unique trong Org, cùng cách products xử lý sku.

ALTER TABLE products
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE services
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE services
    ADD CONSTRAINT uq_services_org_code UNIQUE (organization_id, code);

ALTER TABLE store_products
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

ALTER TABLE store_services
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_products_org ON products (organization_id);
CREATE INDEX idx_services_org ON services (organization_id);
CREATE INDEX idx_store_products_store ON store_products (store_id);
CREATE INDEX idx_store_services_store ON store_services (store_id);
