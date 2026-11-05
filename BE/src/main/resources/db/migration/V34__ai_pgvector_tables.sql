-- =============================================================================
-- V34 — Phân hệ AI (T55a, docs/adr/0110, đặc tả docs/07-ai-subsystem.md §8 + docs/05-erd.md §14.2):
--   extension pgvector + bốn bảng ai_documents, ai_chunks, ai_chat_logs, ai_item_profiles. Chưa có logic RAG (T56 / T77).
--
--   * CREATE EXTENSION vector cần superuser (pgvector KHÔNG "trusted", khác btree_gist ở V1 và unaccent / pg_trgm ở V33):
--     user của docker-compose và của Testcontainers là superuser nên chạy được; DB do DBA quản lý phải tạo extension
--     trước, khi đó IF NOT EXISTS bỏ qua. Thiếu extension thì migration lỗi ngay lúc khởi động (07 §12).
--     Cần ảnh có pgvector (pgvector/pgvector:pg17), không phải postgres:17.
--   * species ở hai bảng là VARCHAR(30), không phải VARCHAR(10) như bảng ở 07 §8: cùng đầu mục §8 quy định "enum
--     VARCHAR(30) + CHECK" và mọi cột species của V1 là VARCHAR(30) (SchemaMigrationIT.enumCheckColumnsAreVarchar30).
--   * Chưa tạo chỉ mục vector (HNSW / IVFFlat): 07 §8 chốt quét tuần tự có lọc ở quy mô vài trăm đoạn. Khi vượt khoảng
--     10 000 đoạn: CREATE INDEX ... USING hnsw (embedding vector_cosine_ops) bằng migration mới.
--   * ai_chat_logs là LOG: chỉ created_at, không có cột tài khoản / IP (BR-AI-24).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ai_documents — ROOT · SM #13 (DRAFT → PUBLISHED → ARCHIVED). Tài liệu tri thức MANUAL hoặc CATALOG_PROTOCOL.
CREATE TABLE ai_documents (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type     VARCHAR(30)  NOT NULL,
    vaccine_type_id BIGINT       REFERENCES vaccine_types (id) ON DELETE RESTRICT,
    title           VARCHAR(200) NOT NULL,
    topic           VARCHAR(30)  NOT NULL,
    species         VARCHAR(30)  NOT NULL,
    content         TEXT         NOT NULL,
    source_note     VARCHAR(500),
    reviewer_name   VARCHAR(100),
    reviewed_on     DATE,
    content_hash    VARCHAR(64),
    status          VARCHAR(30)  NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    indexed_version INT          NOT NULL DEFAULT 0,
    index_status    VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    index_error     VARCHAR(300),
    published_at    TIMESTAMPTZ,
    updated_by      BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_documents_source_type CHECK (source_type IN ('MANUAL', 'CATALOG_PROTOCOL')),
    CONSTRAINT ck_ai_documents_topic CHECK (topic IN ('CARE', 'NUTRITION', 'BEHAVIOR', 'HYGIENE', 'VACCINATION',
                                                      'CLINIC_INFO')),
    CONSTRAINT ck_ai_documents_species CHECK (species IN ('DOG', 'CAT', 'OTHER', 'ALL')),
    CONSTRAINT ck_ai_documents_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_ai_documents_index_status CHECK (index_status IN ('PENDING', 'INDEXED', 'FAILED')),
    CONSTRAINT ck_ai_documents_content_length CHECK (char_length(content) <= 20000),
    -- Phác đồ lấy từ danh mục thì có vaccine_type_id, tài liệu soạn tay thì không (BR-AI-13).
    CONSTRAINT ck_ai_documents_vaccine_type_tied CHECK ((source_type = 'CATALOG_PROTOCOL') = (vaccine_type_id IS NOT NULL)),
    -- Lớp kiểm soát 1: tài liệu soạn tay luôn có nguồn (BR-AI-14).
    CONSTRAINT ck_ai_documents_manual_has_source CHECK (source_type <> 'MANUAL' OR source_note IS NOT NULL),
    -- Lớp kiểm soát 2: tài liệu soạn tay chưa có người rà soát thì không được PUBLISHED (BR-AI-14).
    CONSTRAINT ck_ai_documents_manual_published_reviewed CHECK (
        source_type <> 'MANUAL' OR status <> 'PUBLISHED' OR (reviewer_name IS NOT NULL AND reviewed_on IS NOT NULL))
);
-- Mỗi loại vaccine có tối đa một tài liệu phác đồ; đồng thời là index cho phép kiểm FK khi xóa vaccine_types.
CREATE UNIQUE INDEX ux_ai_documents_vaccine_type ON ai_documents (vaccine_type_id) WHERE vaccine_type_id IS NOT NULL;
-- Danh sách và truy hồi: lọc theo trạng thái và chủ đề.
CREATE INDEX ix_ai_documents_status_topic ON ai_documents (status, topic);
-- Kiểm FK khi xóa accounts (ST02), mẫu ADR-0018.
CREATE INDEX ix_ai_documents_updated_by ON ai_documents (updated_by) WHERE updated_by IS NOT NULL;

-- ai_chunks — PART của ai_documents. Đoạn đã chia kèm vector 768 chiều (gemini-embedding-001, ADR-0109).
CREATE TABLE ai_chunks (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES ai_documents (id) ON DELETE RESTRICT,
    doc_version     INT          NOT NULL,
    ordinal         INT          NOT NULL,
    heading         VARCHAR(200),
    text            TEXT         NOT NULL,
    embedding       vector(768)  NOT NULL,
    embedding_model VARCHAR(60)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Cũng là index cho phép kiểm FK document_id (cột đầu).
    CONSTRAINT uq_ai_chunks_document_version_ordinal UNIQUE (document_id, doc_version, ordinal)
);

-- ai_chat_logs — LOG, chỉ thêm. Một dòng cho mỗi lượt hỏi đáp UC90; không có tài khoản, IP, kênh (BR-AI-24).
CREATE TABLE ai_chat_logs (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    result              VARCHAR(30)   NOT NULL,
    error_kind          VARCHAR(30),
    question            VARCHAR(500),
    answer              TEXT,
    top_similarity      NUMERIC(4, 3),
    source_document_ids BIGINT[],
    llm_model           VARCHAR(60),
    input_tokens        INT,
    output_tokens       INT,
    thought_tokens      INT,
    latency_ms          INT           NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_chat_logs_result CHECK (result IN ('ANSWERED', 'OUT_OF_SCOPE', 'MEDICAL_REFUSAL', 'EMERGENCY',
                                                        'UNAVAILABLE', 'PERSONAL', 'NEED_PET_CHOICE', 'NEED_LOGIN')),
    CONSTRAINT ck_ai_chat_logs_error_kind CHECK (error_kind IN ('TIMEOUT', 'PROVIDER_RATE_LIMIT', 'PROVIDER_ERROR',
                                                                'NO_KEY')),
    -- error_kind có đúng với kết quả UNAVAILABLE (07 §8).
    CONSTRAINT ck_ai_chat_logs_error_kind_tied CHECK ((result = 'UNAVAILABLE') = (error_kind IS NOT NULL)),
    -- Ba kết quả liên quan dữ liệu cá nhân / đăng nhập chỉ ghi kết quả và độ trễ, không ghi nội dung (BR-AI-24).
    CONSTRAINT ck_ai_chat_logs_no_content_for_personal CHECK (
        result NOT IN ('PERSONAL', 'NEED_PET_CHOICE', 'NEED_LOGIN') OR (question IS NULL AND answer IS NULL))
);
-- ST23 xóa theo hạn giữ và hiệu chỉnh ngưỡng theo thời gian.
CREATE INDEX ix_ai_chat_logs_created_at ON ai_chat_logs (created_at);

-- ai_item_profiles — REF. Thuộc tính đối tượng và chu kỳ dùng lại của một dịch vụ thẩm mỹ hoặc sản phẩm GOODS (BR-AI-20).
-- Dịch vụ nhóm MEDICAL và sản phẩm DRUG / VACCINE / kê đơn không được có hồ sơ: kiểm trong service (BR-AI-18), không ở đây.
CREATE TABLE ai_item_profiles (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id           BIGINT        REFERENCES services (id) ON DELETE RESTRICT,
    product_id           BIGINT        REFERENCES products (id) ON DELETE RESTRICT,
    species              VARCHAR(30),
    min_age_weeks        SMALLINT,
    max_age_weeks        SMALLINT,
    min_weight_kg        NUMERIC(6, 2),
    max_weight_kg        NUMERIC(6, 2),
    breed_keywords       VARCHAR(100)[],
    repeat_interval_days SMALLINT,
    updated_by           BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_item_profiles_species CHECK (species IN ('DOG', 'CAT', 'OTHER')),
    CONSTRAINT ck_ai_item_profiles_repeat_interval CHECK (repeat_interval_days > 0),
    -- Đúng một trong hai: dịch vụ hoặc sản phẩm.
    CONSTRAINT ck_ai_item_profiles_one_target CHECK ((service_id IS NULL) <> (product_id IS NULL)),
    CONSTRAINT ck_ai_item_profiles_age_range CHECK (min_age_weeks <= max_age_weeks),
    CONSTRAINT ck_ai_item_profiles_weight_range CHECK (min_weight_kg <= max_weight_kg)
);
-- Mỗi dịch vụ / sản phẩm một hồ sơ; đồng thời là index cho phép kiểm FK khi xóa services / products.
CREATE UNIQUE INDEX ux_ai_item_profiles_service ON ai_item_profiles (service_id) WHERE service_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ai_item_profiles_product ON ai_item_profiles (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX ix_ai_item_profiles_updated_by ON ai_item_profiles (updated_by) WHERE updated_by IS NOT NULL;
