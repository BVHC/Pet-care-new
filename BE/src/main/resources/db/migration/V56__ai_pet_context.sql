-- =============================================================================
-- V56 — AI v2 / T91 (docs/adr/0135 mục "Đã cài ở T91", docs/07 BR-AI-32, BR-AI-34):
--
--   * ai_chat_logs.used_pet_context: lượt hỏi đã gửi khối "THÔNG TIN THÚ" cho nhà cung cấp AI hay chưa. Chỉ là cờ; nội dung
--     khối không bao giờ vào nhật ký (BR-AI-24), nên CHECK ck_ai_chat_logs_no_content_for_personal không đổi. NOT NULL
--     DEFAULT false nên chạy được trên DB đã có dòng nhật ký (các dòng cũ là false, đúng sự thật).
--   * Công tắc [CFG] ai.chat.pet_context_enabled (BOOL, mặc định 'false', đọc mỗi lượt hỏi): chỉ ở POST /me/ai/chat. Mặc định
--     tắt và CHỈ được bật sau khi Hiến điền xong mục "Hiến xác minh" của ADR-0135 (điều khoản dữ liệu của gói Gemini trả phí).
--     Bật ('true') qua PUT /api/system-configs/ai.chat.pet_context_enabled. min / max của BOOL không được dùng (như V52, V53).
--   * Đánh số: ADR-0135 từng ghi "seed ở V53"; V53 đã chứa khóa business_intent (T90). T91 lần lượt định lấy V54, rồi V55,
--     nhưng V54 (orders.version, D045) và V55 (drop_feedback_status_columns, T70; đánh số lại từ V52) của BE-1 / BE-2 đã nằm
--     ở origin/dev trước khi T91 được đẩy lên; migration này chưa từng áp lên DB nào nên nhường số và lấy V56.
--   * DB đã áp V53 mà chưa có V51 (như DB demo 5433): V51 là migration bị bỏ lại phía sau số đã áp, nên Flyway báo "resolved
--     migration not applied" cho tới khi bật outOfOrder một lần để áp V51 (xóa bảng khiếu nại) cùng V54, V55, V56; sao lưu
--     trước (ghi ở ADR-0135 "Đã cài ở T91").
--   * Chưa làm (nợ ghi ở ADR-0134): ai_chat_logs.intent, risk_level, topic SAFETY / LIFESTYLE, ai.ingest.max_file_kb.
-- =============================================================================

ALTER TABLE ai_chat_logs ADD COLUMN used_pet_context BOOLEAN NOT NULL DEFAULT false;

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.pet_context_enabled', 'false', 'BOOL', 'false', 'true', 'bật/tắt',
 'Cho chatbot của khách đã đăng nhập gửi thêm thông tin tối thiểu của thú đang được hỏi (loài, giống, nhóm tuổi, cân nặng, tiêm phòng hoặc dịch vụ gần đây; không có tên) tới nhà cung cấp AI (BR-AI-32); chỉ bật sau khi xác minh điều khoản dữ liệu của gói Gemini (ADR-0135); tắt = chat không gửi gì về thú');
