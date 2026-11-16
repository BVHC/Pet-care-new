-- =============================================================================
-- V57 — AI v2 / T92 phần 2 (docs/adr/0136 "Đã cài ở T92 (phần 2)", docs/07 BR-AI-33, BR-AI-35):
--
--   * ai_chat_logs.intent: ý định của lượt hỏi (docs/adr/0136 D4): EMERGENCY, MEDICAL_RECORD, APPOINTMENT_UPCOMING,
--     VACCINATION_SCHEDULE, BUSINESS_PRICE, BUSINESS_BRANCH, PET_CARE, MEDICAL_RISK, OUT_OF_DOMAIN, UNKNOWN. Chỉ là nhãn thống kê,
--     không chứa nội dung câu hỏi (BR-AI-24), nên CHECK ck_ai_chat_logs_no_content_for_personal không đổi. Cột nullable: dòng
--     cũ (trước V57) không có ý định, không backfill.
--   * Công tắc [CFG] ai.chat.open_domain_enabled (BOOL, mặc định 'false', đọc mỗi lượt hỏi): chế độ mở, chỉ có tác dụng khi
--     ai.chat.general_knowledge_enabled cũng bật VÀ khách đã đăng nhập (POST /me/ai/chat). Bật: câu vô hại ngoài lĩnh vực thú cưng
--     được trả lời kèm tiền tố riêng; y khoa, liều, nội dung gây hại, chèn chỉ thị, lộ lời nhắc vẫn bị chặn. Bật ('true') qua
--     PUT /api/system-configs/ai.chat.open_domain_enabled; hoàn tác bằng 'false'. min / max của BOOL không được dùng (như V52).
--   * Đánh số: V57 là số trống kế tiếp sau V56 (T91) ở mọi nhánh remote lúc viết (16/11/2026). BE-1 cần một số cho khóa
--     workforce.weekly_required_shifts (BR-NS-12): dùng V58 nếu V57 đã đẩy lên trước.
-- =============================================================================

ALTER TABLE ai_chat_logs ADD COLUMN intent VARCHAR(30);
ALTER TABLE ai_chat_logs ADD CONSTRAINT ck_ai_chat_logs_intent CHECK (intent IN
    ('EMERGENCY', 'MEDICAL_RECORD', 'APPOINTMENT_UPCOMING', 'VACCINATION_SCHEDULE', 'BUSINESS_PRICE', 'BUSINESS_BRANCH',
     'PET_CARE', 'MEDICAL_RISK', 'OUT_OF_DOMAIN', 'UNKNOWN'));

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.open_domain_enabled', 'false', 'BOOL', 'false', 'true', 'bật/tắt',
 'Cho chatbot của khách đã đăng nhập trả lời cả câu vô hại ngoài lĩnh vực thú cưng bằng kiến thức chung, kèm lưu ý tham khảo (BR-AI-33); chỉ có tác dụng khi kiến thức chung cũng bật; y khoa, liều thuốc và nội dung gây hại vẫn bị từ chối');
