-- =============================================================================
-- V39 — Khiếu nại (T55b, docs/adr/0113; đặc tả docs/09 §D, FSM #26): complaints (ROOT), complaint_messages (LOG).
--   Chưa có logic (T70). Mã KN-yyMMdd-xxxx do DocumentCodeGenerator sinh khi làm T70 (erd §13 mục 26).
--
--   * Cột kiểu "enum" ngắn của 09 (topic, related_type, closed_reason: VARCHAR(20); author_type, kind: VARCHAR(10)) làm
--     VARCHAR(30) theo erd §13 mục 7. author_type và kind là NOT NULL (09 không ghi "?" nên là bắt buộc).
--   * complaints: CHECK cột ↔ trạng thái, chỉ những gì FSM #26 buộc và không phụ thuộc cách service xóa cột khi mở lại
--     (#6, #7 chỉ nói "xóa người xử lý"): OPEN chưa có người xử lý; IN_PROGRESS và RESOLVED có người xử lý; RESOLVED có
--     kết luận và resolved_at; CLOSED có closed_at và closed_reason. closed_* không buộc ngược về CLOSED để một khiếu nại
--     mở lại có thể giữ lịch sử (ADR-0113 mục câu hỏi mở).
--   * related_id không có FK: trỏ tới bốn bảng khác nhau theo related_type; "thuộc khách" kiểm trong service (09 §D).
-- =============================================================================

CREATE TABLE complaints (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  VARCHAR(20)   NOT NULL,
    customer_id           BIGINT        NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    branch_id             BIGINT        REFERENCES branches (id) ON DELETE RESTRICT,
    topic                 VARCHAR(30)   NOT NULL,
    related_type          VARCHAR(30),
    related_id            BIGINT,
    content               VARCHAR(2000) NOT NULL,
    status                VARCHAR(30)   NOT NULL,
    created_by_staff_id   BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    handler_id            BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    resolution            TEXT,
    resolved_at           TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    closed_reason         VARCHAR(30),
    reopen_count          SMALLINT      NOT NULL DEFAULT 0,
    last_activity_at      TIMESTAMPTZ   NOT NULL,
    escalated_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_complaints_code UNIQUE (code),
    CONSTRAINT ck_complaints_topic CHECK (topic IN ('SERVICE_QUALITY', 'GROOMING', 'BOARDING', 'PURCHASE', 'STAFF', 'OTHER')),
    CONSTRAINT ck_complaints_related_type CHECK (related_type IN ('ORDER', 'ONLINE_ORDER', 'VISIT', 'BOARDING')),
    CONSTRAINT ck_complaints_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_complaints_closed_reason CHECK (closed_reason IN ('CUSTOMER', 'WITHDRAWN', 'AUTO')),
    CONSTRAINT ck_complaints_reopen_count CHECK (reopen_count >= 0),
    CONSTRAINT ck_complaints_related_pair CHECK ((related_type IS NULL) = (related_id IS NULL)),
    -- Chi nhánh bắt buộc trừ chủ đề OTHER (BR-DG-05)
    CONSTRAINT ck_complaints_branch_unless_other CHECK (topic = 'OTHER' OR branch_id IS NOT NULL),
    CONSTRAINT ck_complaints_handler CHECK (
        (status <> 'OPEN' OR handler_id IS NULL)
        AND (status NOT IN ('IN_PROGRESS', 'RESOLVED') OR handler_id IS NOT NULL)),
    CONSTRAINT ck_complaints_resolved_fields CHECK (
        status <> 'RESOLVED' OR (resolution IS NOT NULL AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_complaints_closed_fields CHECK (
        status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_reason IS NOT NULL))
);
-- ST16 / ST17 quét theo trạng thái và hoạt động cuối (BR-DG-08, 09)
CREATE INDEX ix_complaints_status_activity ON complaints (status, last_activity_at);
CREATE INDEX ix_complaints_branch_status ON complaints (branch_id, status);
-- Khiếu nại của tôi (UC85) và kiểm FK khi xóa customers; CustomerMergedEvent đổi customer_id
CREATE INDEX ix_complaints_customer_created ON complaints (customer_id, created_at DESC);
CREATE INDEX ix_complaints_handler ON complaints (handler_id) WHERE handler_id IS NOT NULL;
CREATE INDEX ix_complaints_created_by_staff ON complaints (created_by_staff_id) WHERE created_by_staff_id IS NOT NULL;

-- LOG: mỗi chuyển trạng thái ghi một dòng STATUS (vết xử lý)
CREATE TABLE complaint_messages (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id BIGINT        NOT NULL REFERENCES complaints (id) ON DELETE RESTRICT,
    author_type  VARCHAR(30)   NOT NULL,
    author_id    BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    kind         VARCHAR(30)   NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_complaint_messages_author_type CHECK (author_type IN ('CUSTOMER', 'STAFF', 'SYSTEM')),
    CONSTRAINT ck_complaint_messages_kind CHECK (kind IN ('MESSAGE', 'STATUS'))
);
CREATE INDEX ix_complaint_messages_complaint_created ON complaint_messages (complaint_id, created_at);
CREATE INDEX ix_complaint_messages_author ON complaint_messages (author_id) WHERE author_id IS NOT NULL;
