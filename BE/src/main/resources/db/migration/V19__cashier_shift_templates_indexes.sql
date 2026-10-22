-- =============================================================================
-- V19 — Thu ngân (T30, sales-v1 #12–21, ST13): 2 index + 2 mẫu thông báo trong app — docs/adr/0064.
-- Số V19: V18 là mẫu nhắc lịch hẹn của BE-1; BE-2 lấy V19, cần BE-1 xác nhận trước khi gộp.
--
--   * ix_orders_payment_id: xem phiếu thu → các Order của nó (sales-v1 #15); chỉ Order đã PAID có payment_id.
--   * ix_cashier_shifts_branch_date: danh sách ca của chi nhánh theo ngày mới nhất trước (sales-v1 #19).
--   * CASHIER_SHIFT_AUTO_CLOSED_APP (IN_APP): ST13 tự chốt ca chưa chốt → BRANCH_MANAGER (BR-TG-05).
--   * PENDING_ORDERS_DIGEST_APP (IN_APP): ST13 cuối ngày liệt kê Order PENDING quá hạn (BR-BH-05).
--   * Theo khung V17/V18: body = default_body, subject = default_subject để UC10 khôi phục được; required_vars ⊆
--     allowed_vars; mọi biến bắt buộc có mặt trong subject ∪ body; ngoặc nhọn chỉ dùng cho biến (docs/adr/0037).
--     subject bắt buộc với IN_APP (thành notifications.title ≤ 200; type = mã bỏ "_APP" ≤ 40 ký tự — docs/adr/0014).
--   * Seed cùng PR với lời gọi enqueue đầu tiên (06 §8 Q4). Chỉ INSERT / CREATE INDEX.
-- =============================================================================

CREATE INDEX ix_orders_payment_id ON orders (payment_id) WHERE payment_id IS NOT NULL;

CREATE INDEX ix_cashier_shifts_branch_date ON cashier_shifts (branch_id, business_date DESC, id DESC);

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('CASHIER_SHIFT_AUTO_CLOSED_APP', 'IN_APP',
        'Ca thu ngân {ma_ca} đã được tự chốt',
        E'Ca {loai_ca} {ma_ca} của {ten_thu_ngan} tại {chi_nhanh} (ngày {ngay_ca}) chưa được chốt nên hệ thống đã tự chốt lúc {gio_chot}. Vui lòng nhập số tiền thực đếm khi đối soát ca.',
        'Ca thu ngân {ma_ca} đã được tự chốt',
        E'Ca {loai_ca} {ma_ca} của {ten_thu_ngan} tại {chi_nhanh} (ngày {ngay_ca}) chưa được chốt nên hệ thống đã tự chốt lúc {gio_chot}. Vui lòng nhập số tiền thực đếm khi đối soát ca.',
        '["ma_ca", "loai_ca", "ten_thu_ngan", "chi_nhanh", "ngay_ca", "gio_chot"]'::jsonb,
        '["ma_ca", "loai_ca", "ten_thu_ngan", "chi_nhanh", "ngay_ca", "gio_chot"]'::jsonb),

       ('PENDING_ORDERS_DIGEST_APP', 'IN_APP',
        '{so_order} Order chờ thu tiền quá hạn tại {chi_nhanh}',
        E'Chi nhánh {chi_nhanh} có {so_order} Order đang chờ thu tiền từ {so_ngay} ngày trở lên, cũ nhất chốt ngày {ngay_cu_nhat}. Vui lòng xem danh sách Order quá hạn để xử lý.',
        '{so_order} Order chờ thu tiền quá hạn tại {chi_nhanh}',
        E'Chi nhánh {chi_nhanh} có {so_order} Order đang chờ thu tiền từ {so_ngay} ngày trở lên, cũ nhất chốt ngày {ngay_cu_nhat}. Vui lòng xem danh sách Order quá hạn để xử lý.',
        '["chi_nhanh", "so_order", "so_ngay", "ngay_cu_nhat"]'::jsonb,
        '["chi_nhanh", "so_order", "so_ngay", "ngay_cu_nhat"]'::jsonb);
