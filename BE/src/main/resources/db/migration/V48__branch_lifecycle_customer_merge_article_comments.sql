-- =============================================================================
-- V48 — Chi nhánh tạm ngừng / đóng cửa, gộp hồ sơ khách, bài viết của bác sĩ và bình luận (T55c, docs/adr/0117;
-- docs/08-tang3-be2-spec.md §F, UC13, UC27, UC80, UC82, BR-CN-06…10, BR-KH-11…14, BR-BV-05…08, FSM #20, #21)
-- + 1 tham số [CFG] content.comment_per_hour.
--
--   * branches: thêm SUSPENDED, CLOSED và 5 cột. CHECK cột ↔ trạng thái chỉ ràng buộc chiều "đã ở trạng thái thì phải có
--     đủ" (SUSPENDED có suspended_at + lý do, CLOSED có closed_at + lý do, DRAFT chưa từng tạm ngừng) và closed_at chỉ
--     có ở CLOSED. Mở lại SUSPENDED → ACTIVE có xóa suspended_at hay giữ làm lịch sử thì đặc tả không nói: CHECK cho phép
--     cả hai, T75 chọn (ADR-0117, TBD-4).
--   * customers.merged_into_id: đặc tả (BR-KH-12) nói "hồ sơ nguồn thành MERGED" nhưng chỉ thêm cột này, không thêm cột
--     trạng thái: "đã gộp" = merged_into_id IS NOT NULL. Index một phần để kiểm FK (everyForeignKeyToCustomersHasUsableIndex).
--   * customer_merges là LOG (chỉ created_at); `moved` là JSONB đếm số bản ghi đã chuyển theo loại.
--   * articles: thêm PENDING_REVIEW, REJECTED và 5 cột. REJECTED bắt buộc có review_note (FSM #5), PENDING_REVIEW bắt buộc
--     có submitted_at (FSM #2). Bài do SUPER_MANAGER soạn giữ luồng cũ, các cột mới để NULL (BR-BV-07).
--   * article_comments.status là VARCHAR(30) (đặc tả ghi 20), lý do như stock_counts.scope ở V46. Index
--     (customer_id, created_at DESC) phục vụ giới hạn bình luận mỗi giờ mỗi khách (BR-BV-08) đồng thời là index FK.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- UC13 — branches
-- ---------------------------------------------------------------------------
ALTER TABLE branches
    ADD COLUMN suspended_at       TIMESTAMPTZ,
    ADD COLUMN suspend_reason     VARCHAR(300),
    ADD COLUMN expected_resume_on DATE,
    ADD COLUMN closed_at          TIMESTAMPTZ,
    ADD COLUMN close_reason       VARCHAR(300);

ALTER TABLE branches DROP CONSTRAINT ck_branches_status;
ALTER TABLE branches ADD CONSTRAINT ck_branches_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'CLOSED'));
ALTER TABLE branches ADD CONSTRAINT ck_branches_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));
ALTER TABLE branches ADD CONSTRAINT ck_branches_close_reason CHECK (status <> 'CLOSED' OR close_reason IS NOT NULL);
ALTER TABLE branches ADD CONSTRAINT ck_branches_suspended CHECK (
    status <> 'SUSPENDED' OR (suspended_at IS NOT NULL AND suspend_reason IS NOT NULL));
ALTER TABLE branches ADD CONSTRAINT ck_branches_draft_not_suspended CHECK (status <> 'DRAFT' OR suspended_at IS NULL);

-- ---------------------------------------------------------------------------
-- UC27 — customers, customer_merges
-- ---------------------------------------------------------------------------
ALTER TABLE customers ADD COLUMN merged_into_id BIGINT REFERENCES customers (id) ON DELETE RESTRICT;
ALTER TABLE customers ADD CONSTRAINT ck_customers_merged_into_other CHECK (merged_into_id IS NULL OR merged_into_id <> id);
CREATE INDEX ix_customers_merged_into ON customers (merged_into_id) WHERE merged_into_id IS NOT NULL;

CREATE TABLE customer_merges (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_customer_id BIGINT      NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    target_customer_id BIGINT      NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    executed_by        BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    moved              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_merges_distinct CHECK (source_customer_id <> target_customer_id)
);
CREATE INDEX ix_customer_merges_source ON customer_merges (source_customer_id);
CREATE INDEX ix_customer_merges_target ON customer_merges (target_customer_id);
CREATE INDEX ix_customer_merges_executed_by ON customer_merges (executed_by);

-- ---------------------------------------------------------------------------
-- UC80, UC82 — articles, article_comments
-- ---------------------------------------------------------------------------
ALTER TABLE articles
    ADD COLUMN author_account_id BIGINT REFERENCES accounts (id) ON DELETE RESTRICT,
    ADD COLUMN review_note       VARCHAR(300),
    ADD COLUMN submitted_at      TIMESTAMPTZ,
    ADD COLUMN reviewed_by       BIGINT REFERENCES accounts (id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_at       TIMESTAMPTZ;

ALTER TABLE articles DROP CONSTRAINT ck_articles_status;
ALTER TABLE articles ADD CONSTRAINT ck_articles_status CHECK (status IN (
    'DRAFT', 'PUBLISHED', 'HIDDEN', 'PENDING_REVIEW', 'REJECTED'));
ALTER TABLE articles ADD CONSTRAINT ck_articles_rejected_note CHECK (status <> 'REJECTED' OR review_note IS NOT NULL);
ALTER TABLE articles ADD CONSTRAINT ck_articles_pending_submitted CHECK (
    status <> 'PENDING_REVIEW' OR submitted_at IS NOT NULL);
CREATE INDEX ix_articles_author_status ON articles (author_account_id, status);
CREATE INDEX ix_articles_reviewed_by ON articles (reviewed_by) WHERE reviewed_by IS NOT NULL;

CREATE TABLE article_comments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id  BIGINT        NOT NULL REFERENCES articles (id) ON DELETE RESTRICT,
    customer_id BIGINT        NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    content     VARCHAR(1000) NOT NULL,
    status      VARCHAR(30)   NOT NULL,
    edited_at   TIMESTAMPTZ,
    hidden_by   BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    hide_reason VARCHAR(300),
    hidden_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_article_comments_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'DELETED')),
    CONSTRAINT ck_article_comments_content CHECK (char_length(content) >= 1),
    -- Ẩn kèm lý do (BR-BV-08); bỏ ẩn rồi thì các cột này có xóa hay không là việc của T75
    CONSTRAINT ck_article_comments_hidden CHECK (
        status <> 'HIDDEN' OR (hidden_by IS NOT NULL AND hide_reason IS NOT NULL AND hidden_at IS NOT NULL))
);
CREATE INDEX ix_article_comments_article_status_created ON article_comments (article_id, status, created_at);
CREATE INDEX ix_article_comments_customer_created ON article_comments (customer_id, created_at DESC);
CREATE INDEX ix_article_comments_hidden_by ON article_comments (hidden_by) WHERE hidden_by IS NOT NULL;

-- docs/08 §G. Khoảng min–max và đơn vị do T55c chọn (đặc tả chỉ cho giá trị mặc định): ADR-0117 mục 4.
INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('content.comment_per_hour', '10', 'INT', '1', '100', 'bình luận', 'Số bình luận tối đa mỗi khách mỗi giờ; vượt thì 429 (BR-BV-08)');
