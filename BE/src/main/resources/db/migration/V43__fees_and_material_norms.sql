-- =============================================================================
-- V43 — Phí cấu hình và định mức vật tư (T55c, docs/adr/0117; đặc tả docs/08-tang3-be2-spec.md §A, UC32, BR-SP-08…11).
--
--   * shipping_fee_configs: MỘT dòng duy nhất toàn chuỗi (CHECK id = 1). Dòng này được SEED ở đây (0 đ, ngưỡng miễn phí
--     0 = không miễn phí) thay vì tạo ở lần PUT đầu tiên: cột id là IDENTITY ALWAYS, một INSERT hỏng (vi phạm CHECK
--     phí âm) vẫn tiêu một giá trị của dãy, lần INSERT sau nhận id = 2 và bị CHECK (id = 1) chặn vĩnh viễn. Có sẵn dòng
--     thì T62 chỉ UPDATE. `updated_by` cho phép NULL vì dòng seed chưa ai chỉnh (giả định A-1 của ADR-0117).
--   * branch_home_visit_fees: khóa chính là branch_id (1–1 với chi nhánh), chưa có dòng = chưa bật (fees-v1 trả
--     enabled = false, fee = 0). Không có cột id nên nằm trong TABLES_WITHOUT_ID của SchemaMigrationIT.
--   * service_material_norms: loại sản phẩm (GOODS / DRUG, không vaccine, BR-SP-10) kiểm ở service, không ở CHECK.
--   * Mọi FK ON DELETE RESTRICT (erd §0); cột trỏ tới accounts có index riêng (V7, ADR-0018).
-- =============================================================================

CREATE TABLE shipping_fee_configs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_fee       BIGINT      NOT NULL,
    free_threshold BIGINT      NOT NULL,
    updated_by     BIGINT      REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipping_fee_configs_single_row CHECK (id = 1),
    CONSTRAINT ck_shipping_fee_configs_base_fee CHECK (base_fee >= 0),
    CONSTRAINT ck_shipping_fee_configs_free_threshold CHECK (free_threshold >= 0)
);
CREATE INDEX ix_shipping_fee_configs_updated_by ON shipping_fee_configs (updated_by) WHERE updated_by IS NOT NULL;

INSERT INTO shipping_fee_configs (id, base_fee, free_threshold) OVERRIDING SYSTEM VALUE VALUES (1, 0, 0);

CREATE TABLE branch_home_visit_fees (
    branch_id  BIGINT      PRIMARY KEY REFERENCES branches (id) ON DELETE RESTRICT,
    enabled    BOOLEAN     NOT NULL DEFAULT false,
    fee        BIGINT      NOT NULL,
    updated_by BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_branch_home_visit_fees_fee CHECK (fee >= 0)
);
CREATE INDEX ix_branch_home_visit_fees_updated_by ON branch_home_visit_fees (updated_by);

CREATE TABLE service_material_norms (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id BIGINT      NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    product_id BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity   INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_material_norms_service_product UNIQUE (service_id, product_id),
    CONSTRAINT ck_service_material_norms_quantity CHECK (quantity >= 1)
);
-- (service_id) đã được index dẫn đầu của UNIQUE ở trên; (product_id) để kiểm FK khi xóa sản phẩm và tra "sản phẩm này nằm trong định mức nào"
CREATE INDEX ix_service_material_norms_product ON service_material_norms (product_id);
