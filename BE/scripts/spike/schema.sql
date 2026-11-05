-- T56 spike AI: bốn bảng `ai_*` theo docs/07-ai-subsystem.md §8, chỉ để chạy trong container spike.
-- KHÔNG phải migration Flyway (V34–V35 thuộc T55a). Khác bản thật đúng hai chỗ, đều có chủ ý:
--   1. Bỏ khóa ngoại tới vaccine_types / accounts / services / products (không có trong container spike).
--   2. Bỏ CHECK "MANUAL + PUBLISHED phải có reviewer_name, reviewed_on" (BR-AI-14, lớp 2). Chưa có bác sĩ
--      rà soát (07 Q2) nên tài liệu thử phải PUBLISHED mà không có người rà soát; KHÔNG điền tên giả.
--      Tài liệu thử ghi rõ "CHƯA DUYỆT" trong source_note. Bản thật phải giữ CHECK này.
CREATE EXTENSION IF NOT EXISTS vector;

DROP TABLE IF EXISTS ai_chunks, ai_chat_logs, ai_item_profiles, ai_documents CASCADE;

CREATE TABLE ai_documents (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug            VARCHAR(60)  NOT NULL UNIQUE,   -- chỉ có ở spike: tên file / khóa để đối chiếu với bộ câu hỏi
    source_type     VARCHAR(30)  NOT NULL CHECK (source_type IN ('MANUAL', 'CATALOG_PROTOCOL')),
    vaccine_type_id BIGINT,
    title           VARCHAR(200) NOT NULL,
    topic           VARCHAR(30)  NOT NULL CHECK (topic IN ('CARE', 'NUTRITION', 'BEHAVIOR', 'HYGIENE', 'VACCINATION', 'CLINIC_INFO')),
    species         VARCHAR(10)  NOT NULL CHECK (species IN ('DOG', 'CAT', 'OTHER', 'ALL')),
    content         TEXT         NOT NULL CHECK (char_length(content) <= 20000),
    source_note     VARCHAR(500),
    reviewer_name   VARCHAR(100),
    reviewed_on     DATE,
    content_hash    VARCHAR(64),
    status          VARCHAR(30)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    version         INT          NOT NULL DEFAULT 1,
    indexed_version INT          NOT NULL DEFAULT 0,
    index_status    VARCHAR(30)  NOT NULL DEFAULT 'PENDING' CHECK (index_status IN ('PENDING', 'INDEXED', 'FAILED')),
    index_error     VARCHAR(300),
    published_at    TIMESTAMPTZ,
    updated_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((source_type = 'CATALOG_PROTOCOL') = (vaccine_type_id IS NOT NULL)),
    CHECK (source_type <> 'MANUAL' OR source_note IS NOT NULL)
);
CREATE UNIQUE INDEX uq_ai_documents_vaccine_type ON ai_documents (vaccine_type_id) WHERE vaccine_type_id IS NOT NULL;
CREATE INDEX ix_ai_documents_status_topic ON ai_documents (status, topic);

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
    UNIQUE (document_id, doc_version, ordinal)
);

CREATE TABLE ai_chat_logs (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    result              VARCHAR(30) NOT NULL CHECK (result IN ('ANSWERED', 'OUT_OF_SCOPE', 'MEDICAL_REFUSAL', 'EMERGENCY', 'UNAVAILABLE', 'PERSONAL', 'NEED_PET_CHOICE', 'NEED_LOGIN')),
    error_kind          VARCHAR(30) CHECK (error_kind IN ('TIMEOUT', 'PROVIDER_RATE_LIMIT', 'PROVIDER_ERROR', 'NO_KEY')),
    question            VARCHAR(500),
    answer              TEXT,
    top_similarity      NUMERIC(4, 3),
    source_document_ids BIGINT[],
    llm_model           VARCHAR(60),
    input_tokens        INT,
    output_tokens       INT,
    thought_tokens      INT,
    latency_ms          INT         NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((result = 'UNAVAILABLE') = (error_kind IS NOT NULL))
);
CREATE INDEX ix_ai_chat_logs_created_at ON ai_chat_logs (created_at);

CREATE TABLE ai_item_profiles (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id           BIGINT,
    product_id           BIGINT,
    species              VARCHAR(10) CHECK (species IN ('DOG', 'CAT', 'OTHER')),
    min_age_weeks        SMALLINT,
    max_age_weeks        SMALLINT,
    min_weight_kg        NUMERIC(6, 2),
    max_weight_kg        NUMERIC(6, 2),
    breed_keywords       VARCHAR(100)[],
    repeat_interval_days SMALLINT CHECK (repeat_interval_days > 0),
    updated_by           BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((service_id IS NULL) <> (product_id IS NULL)),
    CHECK (min_age_weeks <= max_age_weeks),
    CHECK (min_weight_kg <= max_weight_kg)
);
CREATE UNIQUE INDEX uq_ai_item_profiles_service ON ai_item_profiles (service_id) WHERE service_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ai_item_profiles_product ON ai_item_profiles (product_id) WHERE product_id IS NOT NULL;
