-- docs/06-erd.md §3.2 Bảng: store_policies — RULE-03-10 (ConfigureStorePolicy).
-- Đúng 1 bản ghi hiện hành/Store (1:1); lịch sử thay đổi qua audit_logs chung của hệ
-- thống, không versioning riêng bảng này. Bảng dạng lai: có created_by/updated_by/version
-- (giống BaseEntity) nhưng không có deleted_at (không có khái niệm soft-delete cho 1 dòng
-- settings 1:1 — xóa chỉ xảy ra qua ON DELETE CASCADE khi Store bị xóa).

CREATE TABLE store_policies
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id           UUID         NOT NULL,
    surcharge_enabled  BOOLEAN      NOT NULL DEFAULT false,
    surcharge_type     VARCHAR(20),
    surcharge_value    NUMERIC(12, 2),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_store_policies_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT fk_store_policies_created_by FOREIGN KEY (created_by) REFERENCES accounts (id),
    CONSTRAINT fk_store_policies_updated_by FOREIGN KEY (updated_by) REFERENCES accounts (id),
    CONSTRAINT uq_store_policies_store UNIQUE (store_id)
);
