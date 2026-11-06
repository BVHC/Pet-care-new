-- =============================================================================
-- V38 — Nội trú (T55b, docs/adr/0113; đặc tả docs/09 §C, FSM #24, #25): admissions (ROOT), admission_orders (ROOT),
--   admission_observations (LOG). Chưa có logic (T69).
--
--   * Cột kiểu "enum" ngắn của 09 (discharge_outcome, order_type, appetite: VARCHAR(10 hoặc 20)) làm VARCHAR(30) theo
--     erd §13 mục 7 và SchemaMigrationIT.enumCheckColumnsAreVarchar30 — cùng cách V34 làm với species.
--   * admissions: CHECK cột ↔ trạng thái theo khuôn care_tasks. DISCHARGED ⇔ có discharge_outcome; DISCHARGED còn buộc
--     tóm tắt, người xuất, thời điểm xuất và dòng Order (FSM #24 dòng 2: thêm dòng dịch vụ nội trú vào Order).
--   * admission_orders: mỗi cột done_* có giá trị ⇔ DONE (FSM #25 dòng 2); cancel_reason ⇔ CANCELLED; MEDICATION bắt
--     buộc có dòng đơn thuốc (BR-LT-15), loại khác để tùy ý.
--   * Thêm so với 09 §C, đều là index kiểm FK / tra cứu mà luồng đã có đòi hỏi: admissions (visit_id) cho guard "không
--     hoàn tất Visit khi còn ca ADMITTED" (BR-LT-14); admission_orders (prescription_item_id) vì visit-v1 #17 xóa cứng
--     dòng đơn thuốc (kiểm FK RESTRICT).
--   * Không có index cho admissions.boarding_booking_id / kennel_id: guard trả thú tra theo pet_id (ux_admissions_pet_
--     admitted); đặt chỗ lưu trú và chuồng không bị xóa cứng.
-- =============================================================================

CREATE TABLE admissions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                 VARCHAR(20)  NOT NULL,
    visit_id             BIGINT       NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    pet_id               BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    branch_id            BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    boarding_booking_id  BIGINT       REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    kennel_id            BIGINT       REFERENCES kennels (id) ON DELETE RESTRICT,
    service_id           BIGINT       NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    unit_price           BIGINT       NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    admitted_by          BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    admitted_at          TIMESTAMPTZ  NOT NULL,
    discharge_outcome    VARCHAR(30),
    discharge_summary    TEXT,
    discharged_by        BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    discharged_at        TIMESTAMPTZ,
    order_line_id        BIGINT       REFERENCES order_lines (id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_admissions_code UNIQUE (code),
    CONSTRAINT uq_admissions_order_line_id UNIQUE (order_line_id),
    CONSTRAINT ck_admissions_status CHECK (status IN ('ADMITTED', 'DISCHARGED')),
    CONSTRAINT ck_admissions_discharge_outcome CHECK (discharge_outcome IN ('HOME', 'DECEASED')),
    CONSTRAINT ck_admissions_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_admissions_discharge_outcome_tied CHECK ((status = 'DISCHARGED') = (discharge_outcome IS NOT NULL)),
    CONSTRAINT ck_admissions_discharge_fields CHECK (
        status <> 'DISCHARGED'
        OR (discharge_summary IS NOT NULL AND discharged_by IS NOT NULL AND discharged_at IS NOT NULL
            AND order_line_id IS NOT NULL))
);
-- Mỗi thú tối đa một ca đang nội trú (BR-LT-13, BR-TN-01); cũng là index của guard hasActiveAdmission(petId)
CREATE UNIQUE INDEX ux_admissions_pet_admitted ON admissions (pet_id) WHERE status = 'ADMITTED';
CREATE INDEX ix_admissions_branch_status ON admissions (branch_id, status);
CREATE INDEX ix_admissions_visit ON admissions (visit_id);
CREATE INDEX ix_admissions_admitted_by ON admissions (admitted_by);
CREATE INDEX ix_admissions_discharged_by ON admissions (discharged_by) WHERE discharged_by IS NOT NULL;

CREATE TABLE admission_orders (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admission_id         BIGINT       NOT NULL REFERENCES admissions (id) ON DELETE RESTRICT,
    order_type           VARCHAR(30)  NOT NULL,
    content              VARCHAR(500) NOT NULL,
    scheduled_at         TIMESTAMPTZ  NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    prescription_item_id BIGINT       REFERENCES prescription_items (id) ON DELETE RESTRICT,
    created_by           BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    done_by              BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    done_at              TIMESTAMPTZ,
    done_note            VARCHAR(300),
    cancel_reason        VARCHAR(300),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_admission_orders_type CHECK (order_type IN ('MEDICATION', 'FEEDING', 'MONITORING', 'PROCEDURE', 'OTHER')),
    CONSTRAINT ck_admission_orders_status CHECK (status IN ('ORDERED', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_admission_orders_medication_item CHECK (order_type <> 'MEDICATION' OR prescription_item_id IS NOT NULL),
    CONSTRAINT ck_admission_orders_done_fields CHECK (
        (done_by IS NOT NULL) = (status = 'DONE')
        AND (done_at IS NOT NULL) = (status = 'DONE')
        AND (done_note IS NOT NULL) = (status = 'DONE')),
    CONSTRAINT ck_admission_orders_cancelled_reason CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);
CREATE INDEX ix_admission_orders_admission_status ON admission_orders (admission_id, status, scheduled_at);
CREATE INDEX ix_admission_orders_prescription_item ON admission_orders (prescription_item_id)
    WHERE prescription_item_id IS NOT NULL;
CREATE INDEX ix_admission_orders_created_by ON admission_orders (created_by);
CREATE INDEX ix_admission_orders_done_by ON admission_orders (done_by) WHERE done_by IS NOT NULL;

-- LOG: chỉ thêm, không sửa, không xóa (BR-LT-16)
CREATE TABLE admission_observations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admission_id     BIGINT       NOT NULL REFERENCES admissions (id) ON DELETE RESTRICT,
    recorded_by      BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    temperature_c    NUMERIC(4,1),
    heart_rate       SMALLINT,
    respiratory_rate SMALLINT,
    weight_kg        NUMERIC(6,2),
    appetite         VARCHAR(30),
    note             VARCHAR(500),
    abnormal         BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_admission_observations_appetite CHECK (appetite IN ('NORMAL', 'REDUCED', 'NONE'))
);
CREATE INDEX ix_admission_observations_admission_created ON admission_observations (admission_id, created_at DESC);
CREATE INDEX ix_admission_observations_recorded_by ON admission_observations (recorded_by);
