-- =============================================================================
-- V26 — ST08 cảnh báo tồn kho (T44, BR-KO-07, docs/adr/0087): 1 mẫu thông báo trong app.
-- Số V26: V25 là mẫu / index trả thú của BE-2 (T43); BE-2 lấy V26, cần BE-1 xác nhận trước khi gộp.
--
--   * STOCK_ALERT_DIGEST_APP (IN_APP): mỗi ngày gửi BRANCH_MANAGER (không còn ai: SUPER_MANAGER) số sản phẩm dưới tồn tối
--     thiểu, số lô sẽ hết hạn trong N ngày [CFG stock.expiry_warning_days] và số lô đã hết hạn còn tồn.
--   * Theo khung V19 / V25: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--     subject bắt buộc với IN_APP (thành notifications.title ≤ 200; type = mã bỏ "_APP" ≤ 40 ký tự — docs/adr/0014).
--   * Seed cùng PR với lời gọi enqueue đầu tiên (06 §8 Q4). Chỉ INSERT.
-- =============================================================================

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('STOCK_ALERT_DIGEST_APP', 'IN_APP',
        'Cảnh báo tồn kho tại {chi_nhanh}',
        E'Chi nhánh {chi_nhanh} có {so_duoi_nguong} sản phẩm dưới tồn tối thiểu, {so_sap_het_han} lô sẽ hết hạn trong {so_ngay} ngày tới và {so_da_het_han} lô đã hết hạn còn tồn. Vui lòng xem báo cáo tồn kho; lô đã hết hạn cần lập phiếu điều chỉnh lý do hết hạn.',
        'Cảnh báo tồn kho tại {chi_nhanh}',
        E'Chi nhánh {chi_nhanh} có {so_duoi_nguong} sản phẩm dưới tồn tối thiểu, {so_sap_het_han} lô sẽ hết hạn trong {so_ngay} ngày tới và {so_da_het_han} lô đã hết hạn còn tồn. Vui lòng xem báo cáo tồn kho; lô đã hết hạn cần lập phiếu điều chỉnh lý do hết hạn.',
        '["chi_nhanh", "so_duoi_nguong", "so_sap_het_han", "so_ngay", "so_da_het_han"]'::jsonb,
        '["chi_nhanh", "so_duoi_nguong", "so_sap_het_han", "so_ngay", "so_da_het_han"]'::jsonb);
