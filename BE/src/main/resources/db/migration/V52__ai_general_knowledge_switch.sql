-- =============================================================================
-- V52 — Công tắc [CFG] ai.chat.general_knowledge_enabled (AI v2 / T92, docs/adr/0136, docs/07 §4.6, BR-AI-33).
--
--   * Công tắc BOOL đầu tiên của hệ thống (ConfigValueType.BOOL đã có từ V1, ConfigValues / getBool đã có test; min / max của
--     BOOL không được dùng nên đặt 'false' / 'true' chỉ để khớp bảng kỳ vọng của SystemConfigIT).
--   * Mặc định 'false': đường ống chat giữ đúng như T77 (không có lớp từ khóa MEDICAL_RISK, không kiến thức chung).
--     Bật ('true') qua PUT /api/system-configs/ai.chat.general_knowledge_enabled; có hiệu lực ở lượt hỏi sau (đọc mỗi lượt).
--   * Số V52 chiếm trước V51 (dành cho gỡ bảng khiếu nại, T70): nếu V51 được thêm sau khi V52 đã chạy ở một DB thì Flyway
--     báo "resolved migration not applied"; khi đó đổi số hoặc bật outOfOrder (ghi ở ADR-0136).
--   * Các khóa còn lại của docs/07 §4.6 v2 (business_intent, pet_context, ingest.max_file_kb) và các cột nhật ký mới chưa cài.
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.general_knowledge_enabled', 'false', 'BOOL', 'false', 'true', 'bật/tắt',
 'Cho chatbot trả lời bằng kiến thức chăm sóc thú cưng phổ thông khi kho tri thức không có đáp án (BR-AI-33); tắt = chỉ trả lời từ tài liệu');
