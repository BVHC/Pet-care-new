-- =============================================================================
-- V35 — Seed 18 tham số [CFG] của phân hệ AI (T55a, docs/adr/0110, đặc tả docs/07-ai-subsystem.md §4.6).
--
--   * Mỗi dòng khớp một hằng số com.petcare.module.identity.api.ConfigKey (cùng key, cùng value_type); lúc khởi động
--     SystemConfigService kiểm tra đủ key, đúng kiểu, value nằm trong min–max, sai thì app dừng.
--   * Đây là các tham số DECIMAL đầu tiên của hệ thống (ai.retrieval.min_similarity và ba trọng số ai.reco.weight_*).
--     Giá trị viết dạng chuẩn mà ConfigValues ghi ra (không số 0 thừa ở cuối: '0.6', không '0.60') để một lần sửa qua
--     API không đổi chữ của dòng mặc định.
--   * Tổng ba trọng số = 1 (BR-AI-21) KHÔNG kiểm ở đây: ConfigConstraints chỉ hỗ trợ cặp INT. Mỗi trọng số chỉ có khoảng
--     0–1; việc kiểm tổng thuộc về module ai khi dùng (T78), ghi ở ADR-0110.
--   * ai.reco.senior_age_years_* là giá trị tạm (07 Q3, chưa có nguồn thú y).
--   * Khóa có ba đoạn (ai.chat.max_question_chars): ConfigKeyTest cho phép tối đa ba đoạn kể từ V35 (ADR-0110).
--   * Danh sách từ khóa cấp cứu, luật ý định cá nhân, danh sách hoạt chất và giới hạn tần suất theo IP (BR-AI-09,
--     app.rate-limit.*) không phải [CFG] (07 §4.6).
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('ai.chat.max_question_chars',            '500', 'INT',     '100', '2000', 'ký tự',   'Độ dài tối đa của một câu hỏi chatbot (BR-AI-08)'),
('ai.chat.max_previous_questions',        '3',   'INT',     '0',   '5',    'câu',     'Số câu hỏi trước của chính người dùng được đưa vào ngữ cảnh (BR-AI-08)'),
('ai.retrieval.top_k',                    '5',   'INT',     '1',   '10',   'đoạn',    'Số đoạn tri thức truy hồi cho mỗi câu hỏi (BR-AI-04)'),
('ai.retrieval.min_similarity',           '0.6', 'DECIMAL', '0.3', '0.9',  'cosine',  'Ngưỡng độ tương đồng cosine tối thiểu để một đoạn được dùng; dưới ngưỡng thì ngoài phạm vi (BR-AI-04)'),
('ai.llm.timeout_seconds',                '15',  'INT',     '5',   '60',   'giây',    'Thời gian chờ tối đa phản hồi của mô hình ngôn ngữ; quá hạn thì UNAVAILABLE (BR-AI-10)'),
('ai.personal.appointments_limit',        '5',   'INT',     '1',   '10',   'lịch',    'Số lịch hẹn sắp tới tối đa chatbot trả khi khách hỏi lịch của mình (BR-AI-27)'),
('ai.index.catalog_sync_minutes',         '60',  'INT',     '5',   '1440', 'phút',    'Chu kỳ đồng bộ tài liệu phác đồ từ danh mục vaccine (ST22)'),
('ai.reco.weight_attr',                   '0.5', 'DECIMAL', '0',   '1',    'trọng số', 'Trọng số điểm khớp thuộc tính (loài, tuổi, cân nặng, giống) trong gợi ý; ba trọng số cộng bằng 1 (BR-AI-21)'),
('ai.reco.weight_history',                '0.3', 'DECIMAL', '0',   '1',    'trọng số', 'Trọng số điểm theo lịch sử dùng dịch vụ / mua hàng của thú trong gợi ý (BR-AI-21)'),
('ai.reco.weight_popularity',             '0.2', 'DECIMAL', '0',   '1',    'trọng số', 'Trọng số điểm độ phổ biến toàn chuỗi trong gợi ý (BR-AI-21)'),
('ai.reco.max_per_group',                 '6',   'INT',     '1',   '12',   'mục',     'Số gợi ý tối đa mỗi nhóm (dịch vụ, lưu trú, sản phẩm) (BR-AI-21)'),
('ai.reco.cache_minutes',                 '10',  'INT',     '0',   '60',   'phút',    'Thời gian giữ kết quả gợi ý trong bộ nhớ đệm; 0 là tắt (BR-AI-23)'),
('ai.reco.default_groom_interval_days',   '30',  'INT',     '7',   '365',  'ngày',    'Chu kỳ dùng lại mặc định của dịch vụ thẩm mỹ khi hồ sơ không ghi (07 §7.3)'),
('ai.reco.default_product_interval_days', '45',  'INT',     '7',   '365',  'ngày',    'Chu kỳ dùng lại mặc định của sản phẩm khi hồ sơ không ghi (07 §7.3)'),
('ai.reco.senior_age_years_dog',          '7',   'INT',     '5',   '15',   'năm',     'Tuổi coi là già của chó, dùng cho nhóm tuổi trong gợi ý; giá trị tạm (07 §7.3, Q3)'),
('ai.reco.senior_age_years_cat',          '10',  'INT',     '5',   '15',   'năm',     'Tuổi coi là già của mèo, dùng cho nhóm tuổi trong gợi ý; giá trị tạm (07 §7.3, Q3)'),
('ai.reco.senior_age_years_other',        '7',   'INT',     '5',   '15',   'năm',     'Tuổi coi là già của loài khác, dùng cho nhóm tuổi trong gợi ý; giá trị tạm (07 §7.3, Q3)'),
('ai.log.retention_days',                 '30',  'INT',     '1',   '180',  'ngày',    'Số ngày giữ nhật ký hỏi đáp trước khi ST23 xóa (BR-AI-24)');
