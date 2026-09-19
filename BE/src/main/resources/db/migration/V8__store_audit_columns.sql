-- docs/06-erd.md §Bảng: stores — bổ sung cột audit chuẩn BaseEntity
-- (created_by/updated_by/deleted_at/version), theo quy ước bắt buộc ở
-- docs/architecture/system-overview.md §3 khi module Store được triển khai
-- (cùng khuôn V5__organization_audit_columns.sql).

ALTER TABLE stores
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;
