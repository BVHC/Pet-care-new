-- =============================================================================
-- V59 — AI v2 / T89 còn lại (docs/adr/0134 D1, D2; docs/adr/0147; docs/07 BR-AI-29, BR-AI-30):
--
--   * Khóa [CFG] ai.ingest.max_file_kb (INT, mặc định 2048, khoảng 256–10240, đơn vị kB): trần cỡ file nạp tri thức
--     (POST /ai/documents/import). Đọc MỖI LẦN nạp nên đổi bằng PUT /api/system-configs/ai.ingest.max_file_kb có hiệu lực
--     ngay, không khởi động lại. Thay hằng 2 MB trong mã.
--   * ai_documents.risk_level: mức rủi ro của nội dung (LOW, MODERATE, HIGH), nullable: tài liệu cũ và tài liệu soạn tay
--     không nêu thì NULL, không backfill. VARCHAR(30) như mọi cột enum có CHECK (SchemaMigrationIT.enumCheckColumnsAreVarchar30),
--     khác VARCHAR(10) của ADR-0134 D2. Chưa dùng để lọc truy hồi (thuộc ADR-0136 D3).
--   * ai_documents.topic thêm SAFETY (cấp cứu, sơ cứu, ngộ độc, an toàn trẻ em) và LIFESTYLE (nhận nuôi, đi lại, nhiều thú,
--     thú già): DROP rồi ADD lại CHECK cùng tên với 8 giá trị. Mọi dòng cũ vẫn thuộc sáu giá trị cũ nên ADD luôn thành công;
--     không đổi topic của tài liệu đã nạp.
--   * external_id cho file nạp (FILE-<sha256 16 hex>-<n>) dùng cột và chỉ mục duy nhất một phần của V53: không đổi schema.
--   * Đánh số: V59 là số trống kế tiếp sau V58 ở mọi nhánh remote lúc viết (19/11/2026). BE-1 từng dự định V59 cho khóa
--     workforce.*: nếu V59 đã bị chiếm khi gộp thì đánh số lại file này (người gộp sau đổi, như V55 → V56).
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.ingest.max_file_kb', '2048', 'INT', '256', '10240', 'kB',
 'Cỡ tối đa của một file nạp tri thức (.md, .txt, .docx, .pdf); file lớn hơn bị từ chối (BR-AI-29)');

ALTER TABLE ai_documents ADD COLUMN risk_level VARCHAR(30);
ALTER TABLE ai_documents ADD CONSTRAINT ck_ai_documents_risk_level CHECK (risk_level IN ('LOW', 'MODERATE', 'HIGH'));

ALTER TABLE ai_documents DROP CONSTRAINT ck_ai_documents_topic;
ALTER TABLE ai_documents ADD CONSTRAINT ck_ai_documents_topic CHECK (topic IN ('CARE', 'NUTRITION', 'BEHAVIOR', 'HYGIENE',
    'VACCINATION', 'CLINIC_INFO', 'SAFETY', 'LIFESTYLE'));
