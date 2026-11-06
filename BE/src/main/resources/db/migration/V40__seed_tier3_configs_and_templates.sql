-- =============================================================================
-- V40 — Seed tầng 3 phần BE-1 (T55b, docs/adr/0113): 8 tham số [CFG] và 40 mẫu thông báo.
--
-- 1. Tham số [CFG] (docs/09 §G, docs/10 §1.7, docs/02 BR-NS-04, BR-NS-07): 6 khóa complaint.*, 2 khóa workforce.*.
--   * Mỗi dòng khớp một hằng số com.petcare.module.identity.api.ConfigKey (cùng key, cùng value_type); lúc khởi động
--     SystemConfigService kiểm tra đủ key, đúng kiểu, value nằm trong min–max, sai thì app dừng.
--   * value = số mặc định ghi trong đặc tả. min_value / max_value là GIẢ ĐỊNH của T55b (đặc tả không nêu khoảng): đổi
--     bằng migration mới, không sửa file này.
--   * Không có ràng buộc chéo giữa các khóa này (ConfigConstraints không đổi): complaint.sla_open_hours và
--     complaint.sla_resolve_hours đo hai đồng hồ khác nhau (BR-DG-08), không cần thứ tự.
--
-- 2. Mẫu thông báo (docs/06 §6b hàng 17, §8 Q4; Hiến chọn "seed đủ cả BE-1 và BE-2" ngày 6/11/2026). 06 §8 Q4 từng định
--    seed dần cùng PR với lần enqueue đầu tiên và để module gửi đề xuất biến; đặc tả KHÔNG định nghĩa tên mã, kênh hay biến
--    của các họ dưới đây, nên toàn bộ tên mã / biến / nội dung là GIẢ ĐỊNH của T55b suy từ rule và FSM (ADR-0113 liệt kê
--    từng họ và nguồn). Module gửi được phép chỉnh biến bằng migration UPDATE mới (không sửa file này); nội dung chữ
--    ADMIN sửa được ở UC10 và khôi phục được về default_*.
--   * Họ NS (02 BR-NS-08): SHIFT_REQUEST_SUBMITTED_APP (BRANCH_MANAGER); SHIFT_REQUEST_APPROVED / _REJECTED,
--     SHIFT_ASSIGNMENT_CANCELLED (gồm hủy tự động BR-NS-09), SHIFT_ABSENCE_RECORDED (nhân viên, email + trong app).
--   * Họ khiếu nại (09 §D, FSM #26): COMPLAINT_CREATED_APP (BRANCH_MANAGER, hoặc SUPER_MANAGER khi không gắn chi nhánh);
--     COMPLAINT_TAKEN, COMPLAINT_RESOLVED (khách, email + trong app); COMPLAINT_REOPENED_APP (người xử lý);
--     COMPLAINT_ESCALATED_APP (SUPER_MANAGER, ST16). Tin nhắn trao đổi và tự đóng ST17 không có thông báo trong đặc tả.
--   * MATERIAL_SHORTAGE_APP (09 §E, BR-KB-13): kênh IN_APP buộc đuôi _APP (SchemaMigrationIT), nên mã có _APP dù 09 viết
--     "MATERIAL_SHORTAGE".
--   * Họ của BE-2 (08 L119, L178, §D, §F.3): ONLINE_ORDER_{PLACED,PAID,SHIPPED,DELIVERY_FAILED,CANCELLED} và
--     PAYMENT_{FAILED,EXPIRED} (khách, email + trong app); RETURN_REQUESTED_APP (BRANCH_MANAGER),
--     RETURN_APPROVED_APP / _REJECTED_APP (lễ tân tạo yêu cầu: 08 chỉ ghi "thông báo"); REFUND_REQUESTED (khách, email +
--     trong app), REFUND_FAILED_APP (BRANCH_MANAGER); ARTICLE_REVIEW_SUBMITTED_APP (SUPER_MANAGER),
--     ARTICLE_REVIEW_APPROVED_APP / _REJECTED_APP (bác sĩ tác giả).
--   * Theo khung V22 / V24 / V25: body = default_body, subject = default_subject; required_vars ⊆ allowed_vars; mọi biến bắt
--     buộc có mặt trong body (SchemaMigrationIT); ngoặc nhọn chỉ dùng cho biến (docs/adr/0037); {ten_khach} /
--     {ten_nhan_vien} chỉ là lời chào của email, không bắt buộc. Số tiền, ngày, giờ do bên gửi định dạng sẵn thành chữ.
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('complaint.max_open',                '10', 'INT', '1', '50',  'khiếu nại', 'Số khiếu nại OPEN / IN_PROGRESS tối đa của một khách cùng lúc (BR-DG-05)'),
('complaint.reopen_days',             '7',  'INT', '1', '30',  'ngày',      'Số ngày khách được mở lại khiếu nại kể từ lúc RESOLVED (BR-DG-07)'),
('complaint.max_reopen',              '2',  'INT', '0', '5',   'lần',       'Số lần mở lại tối đa của một khiếu nại (BR-DG-07)'),
('complaint.sla_open_hours',          '24', 'INT', '1', '168', 'giờ',       'Khiếu nại OPEN chưa được nhận quá số giờ này thì ST16 leo thang (BR-DG-08)'),
('complaint.sla_resolve_hours',       '72', 'INT', '1', '720', 'giờ',       'Khiếu nại IN_PROGRESS quá số giờ này kể từ hoạt động cuối thì ST16 leo thang (BR-DG-08)'),
('complaint.auto_close_days',         '7',  'INT', '1', '30',  'ngày',      'Khiếu nại RESOLVED quá số ngày này mà khách không phản hồi thì ST17 tự đóng (BR-DG-09)'),
('workforce.request_lead_hours',      '24', 'INT', '0', '168', 'giờ',       'Nhân viên gửi yêu cầu nghỉ / đổi ca trước giờ bắt đầu ca ít nhất số giờ này (BR-NS-04)'),
('workforce.absence_report_max_days', '7',  'INT', '1', '30',  'ngày',      'Ghi nhận vắng đột xuất cho phân ca không quá số ngày này trước hôm nay (BR-NS-07)');

INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                    allowed_vars, required_vars)
VALUES ('SHIFT_REQUEST_SUBMITTED_APP', 'IN_APP',
        'Yêu cầu {loai_yeu_cau} ca làm việc mới',
        '{ten_nhan_vien} gửi yêu cầu {loai_yeu_cau} ca {ten_ca} ngày {ngay_lam} ({chi_nhanh}). Lý do: {ly_do}. Vui lòng duyệt hoặc từ chối.',
        'Yêu cầu {loai_yeu_cau} ca làm việc mới',
        '{ten_nhan_vien} gửi yêu cầu {loai_yeu_cau} ca {ten_ca} ngày {ngay_lam} ({chi_nhanh}). Lý do: {ly_do}. Vui lòng duyệt hoặc từ chối.',
        '["ten_nhan_vien", "loai_yeu_cau", "ten_ca", "ngay_lam", "chi_nhanh", "ly_do"]'::jsonb,
        '["ten_nhan_vien", "loai_yeu_cau", "ten_ca", "ngay_lam", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_REQUEST_APPROVED', 'EMAIL',
        'Yêu cầu ca làm việc của bạn đã được duyệt',
        E'Xin chào {ten_nhan_vien},\n\nYêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn đã được quản lý duyệt.\n\nTrân trọng,\nPet Care',
        'Yêu cầu ca làm việc của bạn đã được duyệt',
        E'Xin chào {ten_nhan_vien},\n\nYêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn đã được quản lý duyệt.\n\nTrân trọng,\nPet Care',
        '["mo_ta_yeu_cau", "chi_nhanh", "ten_nhan_vien"]'::jsonb,
        '["mo_ta_yeu_cau", "chi_nhanh"]'::jsonb),

('SHIFT_REQUEST_APPROVED_APP', 'IN_APP',
        'Yêu cầu ca làm việc đã được duyệt',
        'Yêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn đã được quản lý duyệt.',
        'Yêu cầu ca làm việc đã được duyệt',
        'Yêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn đã được quản lý duyệt.',
        '["mo_ta_yeu_cau", "chi_nhanh"]'::jsonb,
        '["mo_ta_yeu_cau", "chi_nhanh"]'::jsonb),

('SHIFT_REQUEST_REJECTED', 'EMAIL',
        'Yêu cầu ca làm việc của bạn bị từ chối',
        E'Xin chào {ten_nhan_vien},\n\nYêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn không được duyệt. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        'Yêu cầu ca làm việc của bạn bị từ chối',
        E'Xin chào {ten_nhan_vien},\n\nYêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn không được duyệt. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        '["mo_ta_yeu_cau", "chi_nhanh", "ly_do", "ten_nhan_vien"]'::jsonb,
        '["mo_ta_yeu_cau", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_REQUEST_REJECTED_APP', 'IN_APP',
        'Yêu cầu ca làm việc bị từ chối',
        'Yêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn không được duyệt. Lý do: {ly_do}.',
        'Yêu cầu ca làm việc bị từ chối',
        'Yêu cầu {mo_ta_yeu_cau} tại {chi_nhanh} của bạn không được duyệt. Lý do: {ly_do}.',
        '["mo_ta_yeu_cau", "chi_nhanh", "ly_do"]'::jsonb,
        '["mo_ta_yeu_cau", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_ASSIGNMENT_CANCELLED', 'EMAIL',
        'Ca làm việc ngày {ngay_lam} của bạn đã bị hủy',
        E'Xin chào {ten_nhan_vien},\n\nCa {ten_ca} ngày {ngay_lam} ({gio_bat_dau}–{gio_ket_thuc}) tại {chi_nhanh} của bạn đã bị hủy. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        'Ca làm việc ngày {ngay_lam} của bạn đã bị hủy',
        E'Xin chào {ten_nhan_vien},\n\nCa {ten_ca} ngày {ngay_lam} ({gio_bat_dau}–{gio_ket_thuc}) tại {chi_nhanh} của bạn đã bị hủy. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        '["ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc", "chi_nhanh", "ly_do", "ten_nhan_vien"]'::jsonb,
        '["ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_ASSIGNMENT_CANCELLED_APP', 'IN_APP',
        'Ca làm việc ngày {ngay_lam} đã bị hủy',
        'Ca {ten_ca} ngày {ngay_lam} ({gio_bat_dau}–{gio_ket_thuc}) tại {chi_nhanh} của bạn đã bị hủy. Lý do: {ly_do}.',
        'Ca làm việc ngày {ngay_lam} đã bị hủy',
        'Ca {ten_ca} ngày {ngay_lam} ({gio_bat_dau}–{gio_ket_thuc}) tại {chi_nhanh} của bạn đã bị hủy. Lý do: {ly_do}.',
        '["ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc", "chi_nhanh", "ly_do"]'::jsonb,
        '["ten_ca", "ngay_lam", "gio_bat_dau", "gio_ket_thuc", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_ABSENCE_RECORDED', 'EMAIL',
        'Quản lý đã ghi nhận bạn vắng mặt ngày {ngay_lam}',
        E'Xin chào {ten_nhan_vien},\n\nQuản lý đã ghi nhận bạn vắng mặt ca {ten_ca} ngày {ngay_lam} tại {chi_nhanh}. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        'Quản lý đã ghi nhận bạn vắng mặt ngày {ngay_lam}',
        E'Xin chào {ten_nhan_vien},\n\nQuản lý đã ghi nhận bạn vắng mặt ca {ten_ca} ngày {ngay_lam} tại {chi_nhanh}. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        '["ten_ca", "ngay_lam", "chi_nhanh", "ly_do", "ten_nhan_vien"]'::jsonb,
        '["ten_ca", "ngay_lam", "chi_nhanh", "ly_do"]'::jsonb),

('SHIFT_ABSENCE_RECORDED_APP', 'IN_APP',
        'Bạn được ghi nhận vắng mặt ngày {ngay_lam}',
        'Quản lý đã ghi nhận bạn vắng mặt ca {ten_ca} ngày {ngay_lam} tại {chi_nhanh}. Lý do: {ly_do}.',
        'Bạn được ghi nhận vắng mặt ngày {ngay_lam}',
        'Quản lý đã ghi nhận bạn vắng mặt ca {ten_ca} ngày {ngay_lam} tại {chi_nhanh}. Lý do: {ly_do}.',
        '["ten_ca", "ngay_lam", "chi_nhanh", "ly_do"]'::jsonb,
        '["ten_ca", "ngay_lam", "chi_nhanh", "ly_do"]'::jsonb),

('COMPLAINT_CREATED_APP', 'IN_APP',
        'Khiếu nại mới {ma_khieu_nai}',
        'Khách {ten_khach} vừa gửi khiếu nại {ma_khieu_nai} về {chu_de}. Vui lòng nhận xử lý.',
        'Khiếu nại mới {ma_khieu_nai}',
        'Khách {ten_khach} vừa gửi khiếu nại {ma_khieu_nai} về {chu_de}. Vui lòng nhận xử lý.',
        '["ten_khach", "ma_khieu_nai", "chu_de"]'::jsonb,
        '["ten_khach", "ma_khieu_nai", "chu_de"]'::jsonb),

('COMPLAINT_TAKEN', 'EMAIL',
        'Khiếu nại {ma_khieu_nai} đã được tiếp nhận',
        E'Xin chào {ten_khach},\n\nKhiếu nại {ma_khieu_nai} của Quý khách đã được tiếp nhận và đang xử lý. Quý khách có thể theo dõi và trao đổi trên ứng dụng Pet Care.\n\nTrân trọng,\nPet Care',
        'Khiếu nại {ma_khieu_nai} đã được tiếp nhận',
        E'Xin chào {ten_khach},\n\nKhiếu nại {ma_khieu_nai} của Quý khách đã được tiếp nhận và đang xử lý. Quý khách có thể theo dõi và trao đổi trên ứng dụng Pet Care.\n\nTrân trọng,\nPet Care',
        '["ma_khieu_nai", "ten_khach"]'::jsonb,
        '["ma_khieu_nai"]'::jsonb),

('COMPLAINT_TAKEN_APP', 'IN_APP',
        'Khiếu nại {ma_khieu_nai} đã được tiếp nhận',
        'Khiếu nại {ma_khieu_nai} của bạn đã được tiếp nhận và đang xử lý.',
        'Khiếu nại {ma_khieu_nai} đã được tiếp nhận',
        'Khiếu nại {ma_khieu_nai} của bạn đã được tiếp nhận và đang xử lý.',
        '["ma_khieu_nai"]'::jsonb,
        '["ma_khieu_nai"]'::jsonb),

('COMPLAINT_RESOLVED', 'EMAIL',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết',
        E'Xin chào {ten_khach},\n\nKhiếu nại {ma_khieu_nai} của Quý khách đã được giải quyết. Kết luận: {ket_luan}\n\nNếu chưa hài lòng, Quý khách có thể mở lại trong {so_ngay_mo_lai} ngày. Nếu không có phản hồi, khiếu nại sẽ tự đóng sau {so_ngay_tu_dong_dong} ngày.\n\nTrân trọng,\nPet Care',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết',
        E'Xin chào {ten_khach},\n\nKhiếu nại {ma_khieu_nai} của Quý khách đã được giải quyết. Kết luận: {ket_luan}\n\nNếu chưa hài lòng, Quý khách có thể mở lại trong {so_ngay_mo_lai} ngày. Nếu không có phản hồi, khiếu nại sẽ tự đóng sau {so_ngay_tu_dong_dong} ngày.\n\nTrân trọng,\nPet Care',
        '["ma_khieu_nai", "ket_luan", "so_ngay_mo_lai", "so_ngay_tu_dong_dong", "ten_khach"]'::jsonb,
        '["ma_khieu_nai", "ket_luan", "so_ngay_mo_lai", "so_ngay_tu_dong_dong"]'::jsonb),

('COMPLAINT_RESOLVED_APP', 'IN_APP',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết: {ket_luan} Bạn có thể mở lại trong {so_ngay_mo_lai} ngày.',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết',
        'Khiếu nại {ma_khieu_nai} đã được giải quyết: {ket_luan} Bạn có thể mở lại trong {so_ngay_mo_lai} ngày.',
        '["ma_khieu_nai", "ket_luan", "so_ngay_mo_lai"]'::jsonb,
        '["ma_khieu_nai", "ket_luan", "so_ngay_mo_lai"]'::jsonb),

('COMPLAINT_REOPENED_APP', 'IN_APP',
        'Khiếu nại {ma_khieu_nai} được mở lại',
        'Khách {ten_khach} đã mở lại khiếu nại {ma_khieu_nai} (lần {so_lan_mo_lai}). Vui lòng nhận xử lý.',
        'Khiếu nại {ma_khieu_nai} được mở lại',
        'Khách {ten_khach} đã mở lại khiếu nại {ma_khieu_nai} (lần {so_lan_mo_lai}). Vui lòng nhận xử lý.',
        '["ten_khach", "ma_khieu_nai", "so_lan_mo_lai"]'::jsonb,
        '["ten_khach", "ma_khieu_nai", "so_lan_mo_lai"]'::jsonb),

('COMPLAINT_ESCALATED_APP', 'IN_APP',
        'Khiếu nại {ma_khieu_nai} quá hạn xử lý',
        'Khiếu nại {ma_khieu_nai} ({chu_de}) quá hạn xử lý và đã được chuyển lên cấp quản lý cao nhất.',
        'Khiếu nại {ma_khieu_nai} quá hạn xử lý',
        'Khiếu nại {ma_khieu_nai} ({chu_de}) quá hạn xử lý và đã được chuyển lên cấp quản lý cao nhất.',
        '["ma_khieu_nai", "chu_de"]'::jsonb,
        '["ma_khieu_nai", "chu_de"]'::jsonb),

('MATERIAL_SHORTAGE_APP', 'IN_APP',
        'Thiếu vật tư tiêu hao tại {chi_nhanh}',
        'Lượt khám {ma_luot_kham} tại {chi_nhanh} thiếu vật tư tiêu hao ở {so_mat_hang_thieu} mặt hàng. Vui lòng xem danh sách thiếu hụt và xử lý (điều chỉnh tồn hoặc nhập thêm).',
        'Thiếu vật tư tiêu hao tại {chi_nhanh}',
        'Lượt khám {ma_luot_kham} tại {chi_nhanh} thiếu vật tư tiêu hao ở {so_mat_hang_thieu} mặt hàng. Vui lòng xem danh sách thiếu hụt và xử lý (điều chỉnh tồn hoặc nhập thêm).',
        '["ma_luot_kham", "chi_nhanh", "so_mat_hang_thieu"]'::jsonb,
        '["ma_luot_kham", "chi_nhanh", "so_mat_hang_thieu"]'::jsonb),

('ONLINE_ORDER_PLACED', 'EMAIL',
        'Đơn hàng {ma_don} đã được đặt',
        E'Xin chào {ten_khach},\n\nPet Care đã nhận đơn hàng {ma_don} với tổng tiền {tong_tien}. Quý khách vui lòng thanh toán trước {han_thanh_toan}, quá hạn đơn sẽ tự hủy.\n\nTrân trọng,\nPet Care',
        'Đơn hàng {ma_don} đã được đặt',
        E'Xin chào {ten_khach},\n\nPet Care đã nhận đơn hàng {ma_don} với tổng tiền {tong_tien}. Quý khách vui lòng thanh toán trước {han_thanh_toan}, quá hạn đơn sẽ tự hủy.\n\nTrân trọng,\nPet Care',
        '["ma_don", "tong_tien", "han_thanh_toan", "ten_khach"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('ONLINE_ORDER_PLACED_APP', 'IN_APP',
        'Đơn hàng {ma_don} đã được đặt',
        'Đơn {ma_don} ({tong_tien}) đã được đặt. Vui lòng thanh toán trước {han_thanh_toan}, quá hạn đơn sẽ tự hủy.',
        'Đơn hàng {ma_don} đã được đặt',
        'Đơn {ma_don} ({tong_tien}) đã được đặt. Vui lòng thanh toán trước {han_thanh_toan}, quá hạn đơn sẽ tự hủy.',
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('ONLINE_ORDER_PAID', 'EMAIL',
        'Đơn hàng {ma_don} đã thanh toán thành công',
        E'Xin chào {ten_khach},\n\nPet Care đã nhận thanh toán {tong_tien} cho đơn hàng {ma_don}. Chi nhánh {chi_nhanh} sẽ chuẩn bị và bàn giao đơn cho đơn vị vận chuyển.\n\nTrân trọng,\nPet Care',
        'Đơn hàng {ma_don} đã thanh toán thành công',
        E'Xin chào {ten_khach},\n\nPet Care đã nhận thanh toán {tong_tien} cho đơn hàng {ma_don}. Chi nhánh {chi_nhanh} sẽ chuẩn bị và bàn giao đơn cho đơn vị vận chuyển.\n\nTrân trọng,\nPet Care',
        '["ma_don", "tong_tien", "chi_nhanh", "ten_khach"]'::jsonb,
        '["ma_don", "tong_tien", "chi_nhanh"]'::jsonb),

('ONLINE_ORDER_PAID_APP', 'IN_APP',
        'Đơn hàng {ma_don} đã thanh toán',
        'Đã nhận thanh toán {tong_tien} cho đơn {ma_don}. Chi nhánh {chi_nhanh} sẽ chuẩn bị và bàn giao đơn cho đơn vị vận chuyển.',
        'Đơn hàng {ma_don} đã thanh toán',
        'Đã nhận thanh toán {tong_tien} cho đơn {ma_don}. Chi nhánh {chi_nhanh} sẽ chuẩn bị và bàn giao đơn cho đơn vị vận chuyển.',
        '["ma_don", "tong_tien", "chi_nhanh"]'::jsonb,
        '["ma_don", "tong_tien", "chi_nhanh"]'::jsonb),

('ONLINE_ORDER_SHIPPED', 'EMAIL',
        'Đơn hàng {ma_don} đã bàn giao vận chuyển',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} đã được bàn giao cho {don_vi_van_chuyen}, mã vận đơn {ma_van_don}.\n\nTrân trọng,\nPet Care',
        'Đơn hàng {ma_don} đã bàn giao vận chuyển',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} đã được bàn giao cho {don_vi_van_chuyen}, mã vận đơn {ma_van_don}.\n\nTrân trọng,\nPet Care',
        '["ma_don", "don_vi_van_chuyen", "ma_van_don", "ten_khach"]'::jsonb,
        '["ma_don", "don_vi_van_chuyen", "ma_van_don"]'::jsonb),

('ONLINE_ORDER_SHIPPED_APP', 'IN_APP',
        'Đơn hàng {ma_don} đang được giao',
        'Đơn {ma_don} đã bàn giao cho {don_vi_van_chuyen}, mã vận đơn {ma_van_don}.',
        'Đơn hàng {ma_don} đang được giao',
        'Đơn {ma_don} đã bàn giao cho {don_vi_van_chuyen}, mã vận đơn {ma_van_don}.',
        '["ma_don", "don_vi_van_chuyen", "ma_van_don"]'::jsonb,
        '["ma_don", "don_vi_van_chuyen", "ma_van_don"]'::jsonb),

('ONLINE_ORDER_DELIVERY_FAILED', 'EMAIL',
        'Giao đơn hàng {ma_don} chưa thành công',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} giao chưa thành công. Lý do: {ly_do}. Quý khách vui lòng liên hệ {chi_nhanh} theo số {so_dien_thoai_chi_nhanh} để được hỗ trợ.\n\nTrân trọng,\nPet Care',
        'Giao đơn hàng {ma_don} chưa thành công',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} giao chưa thành công. Lý do: {ly_do}. Quý khách vui lòng liên hệ {chi_nhanh} theo số {so_dien_thoai_chi_nhanh} để được hỗ trợ.\n\nTrân trọng,\nPet Care',
        '["ma_don", "ly_do", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ten_khach"]'::jsonb,
        '["ma_don", "ly_do", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb),

('ONLINE_ORDER_DELIVERY_FAILED_APP', 'IN_APP',
        'Giao đơn hàng {ma_don} chưa thành công',
        'Đơn {ma_don} giao chưa thành công. Lý do: {ly_do}. Liên hệ {chi_nhanh} theo số {so_dien_thoai_chi_nhanh} để được hỗ trợ.',
        'Giao đơn hàng {ma_don} chưa thành công',
        'Đơn {ma_don} giao chưa thành công. Lý do: {ly_do}. Liên hệ {chi_nhanh} theo số {so_dien_thoai_chi_nhanh} để được hỗ trợ.',
        '["ma_don", "ly_do", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb,
        '["ma_don", "ly_do", "chi_nhanh", "so_dien_thoai_chi_nhanh"]'::jsonb),

('ONLINE_ORDER_CANCELLED', 'EMAIL',
        'Đơn hàng {ma_don} đã bị hủy',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} đã được hủy. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        'Đơn hàng {ma_don} đã bị hủy',
        E'Xin chào {ten_khach},\n\nĐơn hàng {ma_don} đã được hủy. Lý do: {ly_do}.\n\nTrân trọng,\nPet Care',
        '["ma_don", "ly_do", "ten_khach"]'::jsonb,
        '["ma_don", "ly_do"]'::jsonb),

('ONLINE_ORDER_CANCELLED_APP', 'IN_APP',
        'Đơn hàng {ma_don} đã bị hủy',
        'Đơn {ma_don} đã được hủy. Lý do: {ly_do}.',
        'Đơn hàng {ma_don} đã bị hủy',
        'Đơn {ma_don} đã được hủy. Lý do: {ly_do}.',
        '["ma_don", "ly_do"]'::jsonb,
        '["ma_don", "ly_do"]'::jsonb),

('PAYMENT_FAILED', 'EMAIL',
        'Thanh toán đơn hàng {ma_don} chưa thành công',
        E'Xin chào {ten_khach},\n\nThanh toán {tong_tien} cho đơn hàng {ma_don} chưa thành công. Quý khách có thể thanh toán lại trước {han_thanh_toan}.\n\nTrân trọng,\nPet Care',
        'Thanh toán đơn hàng {ma_don} chưa thành công',
        E'Xin chào {ten_khach},\n\nThanh toán {tong_tien} cho đơn hàng {ma_don} chưa thành công. Quý khách có thể thanh toán lại trước {han_thanh_toan}.\n\nTrân trọng,\nPet Care',
        '["ma_don", "tong_tien", "han_thanh_toan", "ten_khach"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('PAYMENT_FAILED_APP', 'IN_APP',
        'Thanh toán đơn hàng {ma_don} chưa thành công',
        'Thanh toán {tong_tien} cho đơn {ma_don} chưa thành công. Bạn có thể thanh toán lại trước {han_thanh_toan}.',
        'Thanh toán đơn hàng {ma_don} chưa thành công',
        'Thanh toán {tong_tien} cho đơn {ma_don} chưa thành công. Bạn có thể thanh toán lại trước {han_thanh_toan}.',
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('PAYMENT_EXPIRED', 'EMAIL',
        'Phiên thanh toán đơn hàng {ma_don} đã hết hạn',
        E'Xin chào {ten_khach},\n\nPhiên thanh toán {tong_tien} cho đơn hàng {ma_don} đã hết hạn. Nếu chưa thanh toán, Quý khách có thể tạo phiên mới trước {han_thanh_toan}.\n\nTrân trọng,\nPet Care',
        'Phiên thanh toán đơn hàng {ma_don} đã hết hạn',
        E'Xin chào {ten_khach},\n\nPhiên thanh toán {tong_tien} cho đơn hàng {ma_don} đã hết hạn. Nếu chưa thanh toán, Quý khách có thể tạo phiên mới trước {han_thanh_toan}.\n\nTrân trọng,\nPet Care',
        '["ma_don", "tong_tien", "han_thanh_toan", "ten_khach"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('PAYMENT_EXPIRED_APP', 'IN_APP',
        'Phiên thanh toán đơn hàng {ma_don} đã hết hạn',
        'Phiên thanh toán {tong_tien} cho đơn {ma_don} đã hết hạn. Bạn có thể tạo phiên mới trước {han_thanh_toan}.',
        'Phiên thanh toán đơn hàng {ma_don} đã hết hạn',
        'Phiên thanh toán {tong_tien} cho đơn {ma_don} đã hết hạn. Bạn có thể tạo phiên mới trước {han_thanh_toan}.',
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb,
        '["ma_don", "tong_tien", "han_thanh_toan"]'::jsonb),

('RETURN_REQUESTED_APP', 'IN_APP',
        'Yêu cầu trả hàng {ma_tra_hang} chờ duyệt',
        'Lễ tân đã tạo yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} tại {chi_nhanh}. Vui lòng duyệt hoặc từ chối.',
        'Yêu cầu trả hàng {ma_tra_hang} chờ duyệt',
        'Lễ tân đã tạo yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} tại {chi_nhanh}. Vui lòng duyệt hoặc từ chối.',
        '["ma_tra_hang", "ma_don", "chi_nhanh"]'::jsonb,
        '["ma_tra_hang", "ma_don", "chi_nhanh"]'::jsonb),

('RETURN_APPROVED_APP', 'IN_APP',
        'Yêu cầu trả hàng {ma_tra_hang} đã được duyệt',
        'Yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} đã được duyệt, số tiền hoàn {so_tien_hoan}.',
        'Yêu cầu trả hàng {ma_tra_hang} đã được duyệt',
        'Yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} đã được duyệt, số tiền hoàn {so_tien_hoan}.',
        '["ma_tra_hang", "ma_don", "so_tien_hoan"]'::jsonb,
        '["ma_tra_hang", "ma_don", "so_tien_hoan"]'::jsonb),

('RETURN_REJECTED_APP', 'IN_APP',
        'Yêu cầu trả hàng {ma_tra_hang} bị từ chối',
        'Yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} không được duyệt. Lý do: {ly_do}.',
        'Yêu cầu trả hàng {ma_tra_hang} bị từ chối',
        'Yêu cầu trả hàng {ma_tra_hang} cho đơn {ma_don} không được duyệt. Lý do: {ly_do}.',
        '["ma_tra_hang", "ma_don", "ly_do"]'::jsonb,
        '["ma_tra_hang", "ma_don", "ly_do"]'::jsonb),

('REFUND_REQUESTED', 'EMAIL',
        'Pet Care sẽ hoàn {so_tien_hoan} cho đơn hàng {ma_don}',
        E'Xin chào {ten_khach},\n\nPet Care đã ghi nhận hoàn {so_tien_hoan} cho đơn hàng {ma_don} (mã hoàn tiền {ma_hoan_tien}, hình thức {hinh_thuc_hoan}). Quý khách sẽ nhận được tiền sau khi hoàn tất xử lý.\n\nTrân trọng,\nPet Care',
        'Pet Care sẽ hoàn {so_tien_hoan} cho đơn hàng {ma_don}',
        E'Xin chào {ten_khach},\n\nPet Care đã ghi nhận hoàn {so_tien_hoan} cho đơn hàng {ma_don} (mã hoàn tiền {ma_hoan_tien}, hình thức {hinh_thuc_hoan}). Quý khách sẽ nhận được tiền sau khi hoàn tất xử lý.\n\nTrân trọng,\nPet Care',
        '["ma_hoan_tien", "ma_don", "so_tien_hoan", "hinh_thuc_hoan", "ten_khach"]'::jsonb,
        '["ma_hoan_tien", "ma_don", "so_tien_hoan", "hinh_thuc_hoan"]'::jsonb),

('REFUND_REQUESTED_APP', 'IN_APP',
        'Đã ghi nhận hoàn tiền cho đơn hàng {ma_don}',
        'Đã ghi nhận hoàn {so_tien_hoan} cho đơn {ma_don} (mã {ma_hoan_tien}, hình thức {hinh_thuc_hoan}).',
        'Đã ghi nhận hoàn tiền cho đơn hàng {ma_don}',
        'Đã ghi nhận hoàn {so_tien_hoan} cho đơn {ma_don} (mã {ma_hoan_tien}, hình thức {hinh_thuc_hoan}).',
        '["ma_hoan_tien", "ma_don", "so_tien_hoan", "hinh_thuc_hoan"]'::jsonb,
        '["ma_hoan_tien", "ma_don", "so_tien_hoan", "hinh_thuc_hoan"]'::jsonb),

('REFUND_FAILED_APP', 'IN_APP',
        'Hoàn tiền {ma_hoan_tien} không thành công',
        'Hoàn tiền {so_tien_hoan} (mã {ma_hoan_tien}) cho đơn {ma_don} tại {chi_nhanh} đã hết số lần thử. Vui lòng thử lại hoặc xử lý ngoài hệ thống.',
        'Hoàn tiền {ma_hoan_tien} không thành công',
        'Hoàn tiền {so_tien_hoan} (mã {ma_hoan_tien}) cho đơn {ma_don} tại {chi_nhanh} đã hết số lần thử. Vui lòng thử lại hoặc xử lý ngoài hệ thống.',
        '["ma_hoan_tien", "so_tien_hoan", "ma_don", "chi_nhanh"]'::jsonb,
        '["ma_hoan_tien", "so_tien_hoan", "ma_don", "chi_nhanh"]'::jsonb),

('ARTICLE_REVIEW_SUBMITTED_APP', 'IN_APP',
        'Bài viết chờ duyệt',
        'Bác sĩ {ten_tac_gia} gửi bài viết "{tieu_de_bai}" chờ duyệt.',
        'Bài viết chờ duyệt',
        'Bác sĩ {ten_tac_gia} gửi bài viết "{tieu_de_bai}" chờ duyệt.',
        '["ten_tac_gia", "tieu_de_bai"]'::jsonb,
        '["ten_tac_gia", "tieu_de_bai"]'::jsonb),

('ARTICLE_REVIEW_APPROVED_APP', 'IN_APP',
        'Bài viết của bạn đã được duyệt',
        'Bài viết "{tieu_de_bai}" của bạn đã được duyệt.',
        'Bài viết của bạn đã được duyệt',
        'Bài viết "{tieu_de_bai}" của bạn đã được duyệt.',
        '["tieu_de_bai"]'::jsonb,
        '["tieu_de_bai"]'::jsonb),

('ARTICLE_REVIEW_REJECTED_APP', 'IN_APP',
        'Bài viết của bạn chưa được duyệt',
        'Bài viết "{tieu_de_bai}" của bạn chưa được duyệt. Ghi chú của người duyệt: {ghi_chu_duyet}.',
        'Bài viết của bạn chưa được duyệt',
        'Bài viết "{tieu_de_bai}" của bạn chưa được duyệt. Ghi chú của người duyệt: {ghi_chu_duyet}.',
        '["tieu_de_bai", "ghi_chu_duyet"]'::jsonb,
        '["tieu_de_bai", "ghi_chu_duyet"]'::jsonb);
