-- =============================================================================
-- V53 — AI v2 / T90 (docs/adr/0134 mục "Đã cài ở T90", docs/07 BR-AI-30, BR-AI-31, BR-AI-34):
--
--   * ai_documents.external_id: khóa nguồn ngoài của tài liệu, hiện chỉ có 'ARTICLE-<id>' (bài viết đồng bộ bởi ST22).
--     UNIQUE một phần: tài liệu soạn tay, phác đồ và file nạp để NULL (nhiều dòng NULL vẫn hợp lệ). Cột mới nullable, nên
--     chạy được trên DB đã có tài liệu.
--   * ai_chat_logs.source_type: nguồn của câu trả lời (BR-AI-34). Cột nullable: dòng cũ và kết quả OUT_OF_SCOPE /
--     UNAVAILABLE không có nguồn. Năm giá trị của BR-AI-34 (KNOWLEDGE_BASE_AND_GENERAL dài 26 ký tự nên cột là 30).
--   * Công tắc [CFG] ai.chat.business_intent_enabled (BOOL, mặc định 'false', đọc mỗi lượt hỏi): bật thì câu hỏi giá dịch vụ
--     và thông tin chi nhánh được trả từ CSDL, không qua mô hình (BR-AI-31). Bật ('true') qua
--     PUT /api/system-configs/ai.chat.business_intent_enabled. min / max của BOOL không được dùng (như V52).
--   * Số V53 chiếm trước V51 (dành cho gỡ bảng khiếu nại, T70): nếu V51 được thêm sau khi V53 đã chạy ở một DB thì Flyway
--     báo "resolved migration not applied"; khi đó đổi số hoặc bật outOfOrder (ghi ở ADR-0134).
--   * Chưa làm (nợ ghi ở ADR-0134): risk_level, topic SAFETY / LIFESTYLE, ai.ingest.max_file_kb, ai.chat.pet_context_enabled,
--     ai_chat_logs.intent / used_pet_context, external_id cho file nạp.
-- =============================================================================

ALTER TABLE ai_documents ADD COLUMN external_id VARCHAR(100);
CREATE UNIQUE INDEX uq_ai_documents_external_id ON ai_documents (external_id) WHERE external_id IS NOT NULL;

ALTER TABLE ai_chat_logs ADD COLUMN source_type VARCHAR(30);
ALTER TABLE ai_chat_logs ADD CONSTRAINT ck_ai_chat_logs_source_type CHECK (source_type IN
    ('DATABASE', 'KNOWLEDGE_BASE', 'GENERAL_KNOWLEDGE', 'KNOWLEDGE_BASE_AND_GENERAL', 'SAFETY'));

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.business_intent_enabled', 'false', 'BOOL', 'false', 'true', 'bật/tắt',
 'Cho chatbot trả lời giá dịch vụ và thông tin chi nhánh trực tiếp từ cơ sở dữ liệu, không qua mô hình (BR-AI-31); tắt = các câu này đi theo đường tài liệu như trước');
