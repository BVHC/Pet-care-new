-- =============================================================================
-- V55 — AI v2 / T91 (docs/adr/0135 mục "Đã cài ở T91", docs/07 BR-AI-32, BR-AI-34):
--
--   * ai_chat_logs.used_pet_context: lượt hỏi đã gửi khối "THÔNG TIN THÚ" cho nhà cung cấp AI hay chưa. Chỉ là cờ; nội dung
--     khối không bao giờ vào nhật ký (BR-AI-24), nên CHECK ck_ai_chat_logs_no_content_for_personal không đổi. NOT NULL
--     DEFAULT false nên chạy được trên DB đã có dòng nhật ký (các dòng cũ là false, đúng sự thật).
--   * Công tắc [CFG] ai.chat.pet_context_enabled (BOOL, mặc định 'false', đọc mỗi lượt hỏi): chỉ ở POST /me/ai/chat. Mặc định
--     tắt và CHỈ được bật sau khi Hiến điền xong mục "Hiến xác minh" của ADR-0135 (điều khoản dữ liệu của gói Gemini trả phí).
--     Bật ('true') qua PUT /api/system-configs/ai.chat.pet_context_enabled. min / max của BOOL không được dùng (như V52, V53).
--   * ADR-0135 từng ghi "seed ở V53"; V53 chỉ mang khóa business_intent (T90), nên việc này đáng ra là V54, nhưng V54
--     (orders.version, D045) của BE-1 đã nằm ở origin/dev (PR #32), nên T91 lấy V55.
--   * Ở origin/dev còn V51 và V52 (T70: gỡ khiếu nại, bỏ cột trạng thái của feedbacks) và V54 mà nhánh dev chưa có: nếu chúng
--     được gộp sau khi V55 đã chạy ở một DB thì Flyway báo "resolved migration not applied"; khi đó bật outOfOrder hoặc đổi số
--     (ghi ở ADR-0135). Riêng origin/dev đang có hai file V52 (ai_general_knowledge_switch của T92 và
--     drop_feedback_status_columns của T70): trùng số, Flyway sẽ không khởi động; không phải việc của T91.
--   * Chưa làm (nợ ghi ở ADR-0134): ai_chat_logs.intent, risk_level, topic SAFETY / LIFESTYLE, ai.ingest.max_file_kb.
-- =============================================================================

ALTER TABLE ai_chat_logs ADD COLUMN used_pet_context BOOLEAN NOT NULL DEFAULT false;

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.pet_context_enabled', 'false', 'BOOL', 'false', 'true', 'bật/tắt',
 'Cho chatbot của khách đã đăng nhập gửi thêm thông tin tối thiểu của thú đang được hỏi (loài, giống, nhóm tuổi, cân nặng, tiêm phòng hoặc dịch vụ gần đây; không có tên) tới nhà cung cấp AI (BR-AI-32); chỉ bật sau khi xác minh điều khoản dữ liệu của gói Gemini (ADR-0135); tắt = chat không gửi gì về thú');
