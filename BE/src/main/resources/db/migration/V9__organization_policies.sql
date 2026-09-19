-- docs/06-erd.md §3.2 Bảng: organization_policies — RULE-03-09 (ManageOrganizationPolicy).
-- Đúng 1 bản ghi hiện hành/Organization (1:1); lịch sử thay đổi qua audit_logs chung của hệ
-- thống, không versioning riêng bảng này. Bảng dạng lai: có created_by/updated_by/version
-- (giống BaseEntity) nhưng không có deleted_at (không có khái niệm soft-delete cho 1 dòng
-- settings 1:1 — xóa chỉ xảy ra qua ON DELETE CASCADE khi Organization bị xóa).

CREATE TABLE organization_policies
(
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID         NOT NULL,
    refund_window_days        INT          NOT NULL DEFAULT 7,
    refund_requires_approval  BOOLEAN      NOT NULL DEFAULT true,
    data_retention_days       INT          NOT NULL DEFAULT 730,
    security_framework_level  VARCHAR(30)  NOT NULL DEFAULT 'STANDARD',
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                UUID,
    updated_by                UUID,
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_org_policies_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_org_policies_created_by FOREIGN KEY (created_by) REFERENCES accounts (id),
    CONSTRAINT fk_org_policies_updated_by FOREIGN KEY (updated_by) REFERENCES accounts (id),
    CONSTRAINT uq_org_policies_org UNIQUE (organization_id)
);
