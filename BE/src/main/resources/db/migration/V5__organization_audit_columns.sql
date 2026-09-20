-- docs/06-erd.md §Bảng: organizations — bổ sung address + cột audit chuẩn BaseEntity
-- (created_by/updated_by/deleted_at/version), theo quy ước bắt buộc ở
-- docs/architecture/system-overview.md §3 khi module Organization được triển khai.

ALTER TABLE organizations
    ADD COLUMN address    TEXT,
    ADD COLUMN created_by UUID REFERENCES accounts (id),
    ADD COLUMN updated_by UUID REFERENCES accounts (id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0;
