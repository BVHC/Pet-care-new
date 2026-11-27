-- =============================================================================
-- V66 — Câu cá nhân mơ hồ hỏi mô hình một nhãn (docs/adr/0157, docs/07 BR-AI-26):
--
--   * Công tắc [CFG] ai.chat.intent_llm_enabled (BOOL, mặc định 'true', đọc mỗi lượt hỏi): chỉ ở POST /me/ai/chat. Câu có từ
--     khóa lịch hẹn / tiêm nhưng thiếu dấu hiệu sở hữu (BR-AI-26 không nhận) được hỏi mô hình một nhãn APPOINTMENT_UPCOMING /
--     VACCINATION_SCHEDULE / NONE; lỗi mô hình coi là NONE. Tắt ('false') qua PUT /api/system-configs/ai.chat.intent_llm_enabled
--     thì chatbot quay về đúng BR-AI-26 cũ. min / max của BOOL không được dùng (như V52).
--   * Không đổi ai_chat_logs (CHECK ck_ai_chat_logs_intent của V57 giữ nguyên): lượt cá nhân vẫn ghi APPOINTMENT_UPCOMING /
--     VACCINATION_SCHEDULE, không có nội dung (BR-AI-24).
--   * Đánh số: V65 (trigger khóa bệnh án, docs/adr/0153) chưa vào repo (overlay), Flyway outOfOrder = false nên khi đưa vào
--     phải đổi số thành V67 (Hiến chấp nhận 27/11/2026).
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.intent_llm_enabled', 'true', 'BOOL', 'false', 'true', 'bật/tắt',
 'Khách đã đăng nhập hỏi lịch hẹn / lịch tiêm theo cách gõ tự nhiên (thiếu "của tôi", tên thú…): chatbot hỏi mô hình AI một nhãn để nhận ra câu hỏi cá nhân (BR-AI-26, docs/adr/0157); tắt thì chỉ dùng luật từ khóa');
