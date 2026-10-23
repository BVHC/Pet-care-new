-- =============================================================================
-- V21 — Nội dung trang tĩnh (T32, content-v1 #1, #2, #14; UC21) — docs/adr/0071.
-- Số V21: V20 là lưu trú của BE-2; BE-2 lấy V21, cần BE-1 xác nhận trước khi gộp.
--
--   * Bốn trang cố định của erd §10 (HOME_BANNER, ABOUT, POLICY, FAQ), nội dung rỗng `{}`. Khóa trang là tập đóng
--     (PageKey) nên PUT chỉ sửa, không tạo; thiếu dòng seed thì sửa trang là 404.
--   * updated_by NULL: chưa ai sửa. Chỉ INSERT.
-- =============================================================================
INSERT INTO page_contents (key, title, content) VALUES
    ('HOME_BANNER', NULL, '{}'::jsonb),
    ('ABOUT',       NULL, '{}'::jsonb),
    ('POLICY',      NULL, '{}'::jsonb),
    ('FAQ',         NULL, '{}'::jsonb);
