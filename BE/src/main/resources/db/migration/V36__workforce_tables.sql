-- =============================================================================
-- V36 — Nhân sự (T55b, docs/adr/0113; đặc tả docs/05-erd.md §14.1, docs/02 BR-NS-01…10, docs/03 #11–#12, docs/04 §12.1):
--   shift_definitions (REF), shift_assignments (ROOT · SM #12), shift_requests (ROOT · SM #11). Chưa có logic (T58–T59).
--
--   * Chồng giờ giữa hai ca KHÁC nhau của cùng một nhân viên không chặn được ở DB (giờ nằm ở shift_definitions): service
--     kiểm dưới khóa dòng accounts của nhân viên (BR-NS-02). Phương án EXCLUDE USING gist cần sao chép giờ vào
--     shift_assignments — 05 §13 mục 24 để BE-1 quyết khi làm T58; V36 chưa làm (ghi ở ADR-0113).
--   * Mọi FK ON DELETE RESTRICT. Mỗi FK → accounts có index mà câu kiểm FK dùng được (ADR-0018): plain, hoặc partial
--     WHERE <cột> IS NOT NULL cho cột nullable.
-- =============================================================================

CREATE TABLE shift_definitions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id  BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    name       VARCHAR(60) NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    is_active  BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Không có ca qua nửa đêm (BR-NS-01)
    CONSTRAINT ck_shift_definitions_time_order CHECK (start_time < end_time)
);
-- Tên không trùng trong chi nhánh, không phân biệt hoa thường (BR-NS-01); cột đầu branch_id cũng là index của FK
CREATE UNIQUE INDEX ux_shift_definitions_branch_name ON shift_definitions (branch_id, lower(name));
CREATE INDEX ix_shift_definitions_branch_active ON shift_definitions (branch_id, is_active);

CREATE TABLE shift_assignments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id           BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    staff_id            BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    shift_definition_id BIGINT       NOT NULL REFERENCES shift_definitions (id) ON DELETE RESTRICT,
    work_date           DATE         NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    reason              VARCHAR(300),
    status_changed_by   BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    status_changed_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_shift_assignments_status CHECK (status IN ('SCHEDULED', 'ON_LEAVE', 'ABSENT', 'CANCELLED')),
    CONSTRAINT ck_shift_assignments_status_changed CHECK ((status = 'SCHEDULED') = (status_changed_at IS NULL)),
    -- Vắng đột xuất và hủy phân ca bắt buộc có lý do (BR-NS-02, BR-NS-07)
    CONSTRAINT ck_shift_assignments_reason CHECK (status NOT IN ('ABSENT', 'CANCELLED') OR reason IS NOT NULL)
);
-- Chặn trùng đúng cặp ca – ngày khi còn SCHEDULED (BR-NS-02)
CREATE UNIQUE INDEX ux_shift_assignments_scheduled ON shift_assignments (staff_id, shift_definition_id, work_date)
    WHERE status = 'SCHEDULED';
CREATE INDEX ix_shift_assignments_branch_date ON shift_assignments (branch_id, work_date);
-- Lịch của tôi (UC35), kiểm chồng giờ, và kiểm FK khi xóa accounts
CREATE INDEX ix_shift_assignments_staff_date ON shift_assignments (staff_id, work_date);
-- Kiểm "ngừng dùng ca còn phân ca" (BR-NS-01)
CREATE INDEX ix_shift_assignments_definition_scheduled ON shift_assignments (shift_definition_id)
    WHERE status = 'SCHEDULED';
CREATE INDEX ix_shift_assignments_status_changed_by ON shift_assignments (status_changed_by)
    WHERE status_changed_by IS NOT NULL;

CREATE TABLE shift_requests (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id              BIGINT       NOT NULL REFERENCES shift_assignments (id) ON DELETE RESTRICT,
    requester_id               BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    request_type               VARCHAR(30)  NOT NULL,
    target_shift_definition_id BIGINT       REFERENCES shift_definitions (id) ON DELETE RESTRICT,
    reason                     VARCHAR(300) NOT NULL,
    status                     VARCHAR(30)  NOT NULL,
    decided_by                 BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    decision_note              VARCHAR(300),
    closed_at                  TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_shift_requests_type CHECK (request_type IN ('LEAVE', 'SWAP')),
    CONSTRAINT ck_shift_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_shift_requests_swap_target CHECK ((request_type = 'SWAP') = (target_shift_definition_id IS NOT NULL)),
    CONSTRAINT ck_shift_requests_closed_at CHECK ((status = 'PENDING') = (closed_at IS NULL)),
    CONSTRAINT ck_shift_requests_rejected_note CHECK (status <> 'REJECTED' OR decision_note IS NOT NULL),
    CONSTRAINT ck_shift_requests_approved_by CHECK (status <> 'APPROVED' OR decided_by IS NOT NULL)
);
-- Tối đa 1 yêu cầu PENDING mỗi phân ca (BR-NS-04)
CREATE UNIQUE INDEX ux_shift_requests_pending ON shift_requests (assignment_id) WHERE status = 'PENDING';
CREATE INDEX ix_shift_requests_requester_created ON shift_requests (requester_id, created_at DESC);
-- Danh sách chờ duyệt của chi nhánh (nối shift_assignments.branch_id)
CREATE INDEX ix_shift_requests_status_created ON shift_requests (status, created_at, id);
-- Kiểm FK khi xóa dòng cha
CREATE INDEX ix_shift_requests_assignment ON shift_requests (assignment_id);
CREATE INDEX ix_shift_requests_target_definition ON shift_requests (target_shift_definition_id)
    WHERE target_shift_definition_id IS NOT NULL;
CREATE INDEX ix_shift_requests_decided_by ON shift_requests (decided_by) WHERE decided_by IS NOT NULL;
