-- =============================================================================
-- V37 — Khám tại nhà, phẫu thuật, vật tư tiêu hao (T55b, docs/adr/0113; đặc tả docs/09 §A, §B, §E):
--   cột mới của appointments và visits, bảng surgeries (ROOT · SM #23), bảng visit_material_usages (LOG). Chưa có logic.
--
--   * Cột "đúng bản chất" trước, ràng buộc sau: ALTER ... ADD COLUMN ... DEFAULT false NOT NULL là thay đổi metadata
--     (Postgres 11+), các INSERT hiện có không phải đổi. Hai CHECK mới của appointments đúng với mọi dòng cũ
--     (is_home_visit = false, home_address và home_fee NULL).
--   * CHECK (is_home_visit = (home_address IS NOT NULL AND home_fee IS NOT NULL)) chép nguyên văn 09 §A. Hệ quả: dòng
--     is_home_visit = false mà chỉ điền MỘT trong hai cột home_* vẫn lọt (vế phải = false); service giữ phần còn lại.
--   * "Dịch vụ khám tại nhà thuộc loại EXAM" (BR-LH-13) và "dịch vụ phẫu thuật thuộc loại SURGERY" (BR-KB-08) cần đọc
--     services.medical_type của hàng khác: CHECK không làm được, thuộc về service (T60, T61).
--   * surgeries: CHECK cột ↔ trạng thái theo khuôn care_tasks (ADR-0081), chỉ những gì FSM #23 buộc: CONSENTED trở đi có
--     đủ cam kết, PLANNED chưa có người/giờ cam kết, IN_PROGRESS / COMPLETED có started_at, COMPLETED có completed_at +
--     result_note + order_line_id, CANCELLED ⇔ có cancel_reason (cancelled_by NULL = hệ thống, FSM #23 dòng 6).
--     result_note không buộc ngược vào COMPLETED: BR-KB-11 cho dừng phẫu thuật kèm "ghi chú", đặc tả không nói ghi vào
--     cột nào, nên V37 để trống ràng buộc đó (ADR-0113 mục câu hỏi mở).
--   * visit_material_usages là LOG (chỉ created_at) nhưng resolved_by / resolved_at được cập nhật một lần khi
--     BRANCH_MANAGER đánh dấu đã xử lý (BR-KB-13) — cùng tiền lệ notification_outbox (erd §13 mục 11).
-- =============================================================================

-- ---------------------------------------------------------------- khám tại nhà (09 §A)

ALTER TABLE appointments
    ADD COLUMN is_home_visit       BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN home_address        VARCHAR(300),
    ADD COLUMN home_contact_phone  VARCHAR(15),
    ADD COLUMN home_fee            BIGINT,
    ADD CONSTRAINT ck_appointments_home_visit_fields
        CHECK (is_home_visit = (home_address IS NOT NULL AND home_fee IS NOT NULL)),
    ADD CONSTRAINT ck_appointments_home_visit_medical CHECK (NOT is_home_visit OR service_group = 'MEDICAL'),
    ADD CONSTRAINT ck_appointments_home_fee CHECK (home_fee >= 0);

-- Để hàng đợi bỏ qua lượt tại nhà và báo cáo phân biệt (09 §A)
ALTER TABLE visits ADD COLUMN is_home_visit BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------- phẫu thuật (09 §B)

CREATE TABLE surgeries (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id                BIGINT       NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    pet_id                  BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    branch_id               BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    service_id              BIGINT       NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    status                  VARCHAR(30)  NOT NULL,
    planned_by              BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    plan_note               VARCHAR(500),
    consent_text            TEXT,
    consent_signer_name     VARCHAR(100),
    consent_signer_relation VARCHAR(50),
    consented_by            BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    consented_at            TIMESTAMPTZ,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    result_note             TEXT,
    cancel_reason           VARCHAR(300),
    cancelled_by            BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    order_line_id           BIGINT       REFERENCES order_lines (id) ON DELETE RESTRICT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_surgeries_order_line_id UNIQUE (order_line_id),
    CONSTRAINT ck_surgeries_status CHECK (status IN ('PLANNED', 'CONSENTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_surgeries_consent_fields CHECK (
        status NOT IN ('CONSENTED', 'IN_PROGRESS', 'COMPLETED')
        OR (consent_text IS NOT NULL AND consent_signer_name IS NOT NULL AND consent_signer_relation IS NOT NULL
            AND consented_by IS NOT NULL AND consented_at IS NOT NULL)),
    CONSTRAINT ck_surgeries_planned_unconsented CHECK (
        status <> 'PLANNED' OR (consented_by IS NULL AND consented_at IS NULL)),
    CONSTRAINT ck_surgeries_started_at CHECK (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL),
    CONSTRAINT ck_surgeries_completed_fields CHECK (
        status <> 'COMPLETED'
        OR (completed_at IS NOT NULL AND result_note IS NOT NULL AND order_line_id IS NOT NULL)),
    CONSTRAINT ck_surgeries_cancelled_reason CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);
CREATE INDEX ix_surgeries_visit ON surgeries (visit_id);
CREATE INDEX ix_surgeries_branch_status ON surgeries (branch_id, status);
-- Kiểm FK khi xóa accounts (ST02), mẫu ADR-0018
CREATE INDEX ix_surgeries_planned_by ON surgeries (planned_by);
CREATE INDEX ix_surgeries_consented_by ON surgeries (consented_by) WHERE consented_by IS NOT NULL;
CREATE INDEX ix_surgeries_cancelled_by ON surgeries (cancelled_by) WHERE cancelled_by IS NOT NULL;

-- ---------------------------------------------------------------- vật tư tiêu hao (09 §E)

CREATE TABLE visit_material_usages (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id     BIGINT      NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    service_id   BIGINT      NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    product_id   BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    branch_id    BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    norm_qty     INT         NOT NULL,
    issued_qty   INT         NOT NULL,
    shortage_qty INT         NOT NULL,
    resolved_by  BIGINT      REFERENCES accounts (id) ON DELETE RESTRICT,
    resolved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Mỗi Visit chỉ trừ vật tư một lần theo (dịch vụ, sản phẩm) (BR-KB-14)
    CONSTRAINT uq_visit_material_usages_visit_service_product UNIQUE (visit_id, service_id, product_id),
    CONSTRAINT ck_visit_material_usages_norm_qty CHECK (norm_qty > 0),
    CONSTRAINT ck_visit_material_usages_issued_qty CHECK (issued_qty >= 0),
    CONSTRAINT ck_visit_material_usages_shortage_qty CHECK (shortage_qty >= 0),
    CONSTRAINT ck_visit_material_usages_balance CHECK (norm_qty = issued_qty + shortage_qty)
);
-- Danh sách thiếu hụt chưa xử lý của chi nhánh (BR-KB-13)
CREATE INDEX ix_visit_material_usages_open_shortage ON visit_material_usages (branch_id)
    WHERE shortage_qty > 0 AND resolved_at IS NULL;
CREATE INDEX ix_visit_material_usages_resolved_by ON visit_material_usages (resolved_by)
    WHERE resolved_by IS NOT NULL;
