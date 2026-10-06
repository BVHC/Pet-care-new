-- =============================================================================
-- V2 — Seed tham số [CFG] (BR-QT-13) vào system_configs. docs/adr/0004.
--
--   * Mỗi dòng khớp một hằng số com.petcare.module.identity.api.ConfigKey (cùng key, cùng value_type);
--     lúc khởi động SystemConfigService kiểm tra đủ key, đúng kiểu, value nằm trong min–max, sai thì app dừng.
--   * value = con số mặc định ghi trong docs/02-business-rules.md. Marker [CFG] đứng sau một mệnh đề nhiều số
--     thì mọi số trong mệnh đề là tham số; số trùng nghĩa với key đã có thì dùng lại key đó
--     (BR-LH-06 "dưới 12 giờ" = appointment.late_cancel_hours; BR-TN-03 "quá 30 phút" = appointment.no_show_after_minutes).
--   * min_value / max_value: khoảng đề xuất (docs chỉ có ví dụ OTP 1–15 phút), đã duyệt cùng plan 2026-10-04.
--   * session.ttl_hours không có trong 02: hạn phiên đăng nhập (identity-v1 Q1), quyết định ở docs/adr/0003.
--   * Chỉ INSERT; thêm tham số sau này = hằng số ConfigKey mới + migration mới.
-- =============================================================================

INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
-- TK, QT
('password.min_length',             '8',     'INT', '8',    '64',   'ký tự',   'Độ dài tối thiểu của mật khẩu (BR-TK-03)'),
('otp.code_length',                 '6',     'INT', '4',    '8',    'chữ số',  'Số chữ số của mã OTP (BR-TK-05)'),
('otp.ttl_minutes',                 '5',     'INT', '1',    '15',   'phút',    'Thời gian hiệu lực của mã OTP (BR-TK-05)'),
('otp.max_failed_attempts',         '5',     'INT', '1',    '10',   'lần',     'Số lần nhập sai tối đa của một mã OTP (BR-TK-06)'),
('otp.resend_interval_seconds',     '60',    'INT', '30',   '300',  'giây',    'Khoảng cách tối thiểu giữa hai lần gửi OTP (BR-TK-07)'),
('otp.max_sends_per_window',        '5',     'INT', '1',    '20',   'mã',      'Số mã OTP tối đa gửi tới một email trong một cửa sổ (BR-TK-07)'),
('otp.send_window_minutes',         '60',    'INT', '15',   '1440', 'phút',    'Độ dài cửa sổ đếm số OTP gửi tới một email (BR-TK-07)'),
('account.pending_ttl_hours',       '24',    'INT', '1',    '168',  'giờ',     'Tài khoản chưa xác thực quá thời gian này thì bị xóa (BR-TK-08, ST02)'),
('login.max_failed_attempts',       '5',     'INT', '3',    '10',   'lần',     'Số lần đăng nhập sai liên tiếp thì khóa tạm (BR-TK-09)'),
('login.failed_window_minutes',     '15',    'INT', '5',    '60',   'phút',    'Cửa sổ đếm số lần đăng nhập sai (BR-TK-09)'),
('login.lock_minutes',              '15',    'INT', '5',    '1440', 'phút',    'Thời gian khóa tạm do đăng nhập sai (BR-TK-09, ST01)'),
('address.max_per_customer',        '5',     'INT', '1',    '20',   'địa chỉ', 'Số địa chỉ tối đa trong sổ địa chỉ của khách (BR-TK-18)'),
('vet.bio_max_length',              '500',   'INT', '100',  '500',  'ký tự',   'Độ dài tối đa mô tả ngắn của bác sĩ; cột bio là VARCHAR(500) (BR-TK-20)'),
('audit.retention_years',           '2',     'INT', '2',    '10',   'năm',     'Thời gian lưu audit tối thiểu (BR-QT-16)'),
('session.ttl_hours',               '12',    'INT', '1',    '72',   'giờ',     'Hạn phiên đăng nhập tính từ lúc đăng nhập, không gia hạn (docs/adr/0003)'),
-- LH
('appointment.min_lead_hours',          '24', 'INT', '0',   '72',   'giờ',     'Khách đặt lịch trước giờ hẹn tối thiểu (BR-LH-04)'),
('appointment.max_advance_days',        '30', 'INT', '7',   '90',   'ngày',    'Khách đặt lịch xa nhất (BR-LH-04)'),
('appointment.max_booked_per_pet',      '2',  'INT', '1',   '5',    'lịch',    'Số lịch BOOKED tối đa của một thú cưng (BR-LH-05)'),
('appointment.reschedule_min_lead_hours','12','INT', '0',   '48',   'giờ',     'Khách đổi lịch trước giờ hẹn tối thiểu (BR-LH-06)'),
('appointment.max_reschedules',         '3',  'INT', '0',   '10',   'lần',     'Số lần đổi lịch tối đa của một lịch hẹn (BR-LH-06)'),
('appointment.late_cancel_hours',       '12', 'INT', '0',   '48',   'giờ',     'Hủy khi còn dưới mốc này trước giờ hẹn là hủy muộn (BR-LH-07, BR-LH-06)'),
('appointment.no_show_after_minutes',   '30', 'INT', '15',  '120',  'phút',    'Quá giờ hẹn chừng này mà chưa check-in thì NO_SHOW (BR-LH-08, BR-TN-03)'),
('appointment.reminder_hours_before',   '24', 'INT', '1',   '72',   'giờ',     'Nhắc lịch hẹn trước giờ hẹn (BR-LH-12, ST03)'),
('booking_restriction.violation_threshold','3','INT','1',   '10',   'lần',     'Số lần NO_SHOW hoặc hủy muộn thì bị hạn chế đặt online (BR-LH-09)'),
('booking_restriction.window_days',     '90', 'INT', '30',  '365',  'ngày',    'Cửa sổ đếm số lần vi phạm (BR-LH-09)'),
('booking_restriction.duration_days',   '30', 'INT', '1',   '180',  'ngày',    'Thời gian bị hạn chế đặt online (BR-LH-09)'),
-- TN, KB
('visit.check_in_early_minutes',        '30', 'INT', '0',   '120',  'phút',    'Check-in sớm nhất trước giờ hẹn (BR-TN-02)'),
('visit.late_priority_minutes',         '15', 'INT', '0',   '30',   'phút',    'Trễ tối đa mà vẫn giữ ưu tiên lịch hẹn (BR-TN-03)'),
('staff.online_window_minutes',         '10', 'INT', '1',   '60',   'phút',    'Nhân viên có thao tác trong khoảng này được coi là online (BR-TN-06)'),
('medical_record.follow_up_max_days',   '180','INT', '30',  '365',  'ngày',    'Ngày tái khám xa nhất sau ngày khám (BR-KB-06)'),
-- LT
('boarding.min_nights',                 '1',  'INT', '1',   '7',    'đêm',     'Số đêm lưu trú tối thiểu (BR-LT-02)'),
('boarding.max_nights',                 '30', 'INT', '1',   '90',   'đêm',     'Số đêm lưu trú tối đa (BR-LT-02)'),
('boarding.overdue_hold_nights',        '2',  'INT', '0',   '7',    'đêm',     'Đặt chỗ OVERDUE chiếm thêm số đêm này sau đêm hiện tại (BR-LT-03)'),
('boarding.min_lead_days',              '1',  'INT', '0',   '7',    'ngày',    'Khách đặt lưu trú online trước ngày nhận tối thiểu (BR-LT-04)'),
('boarding.max_advance_days',           '60', 'INT', '7',   '180',  'ngày',    'Khách đặt lưu trú online xa nhất (BR-LT-04)'),
('boarding.checkout_time',           '12:00', 'TIME','06:00','22:00','giờ',    'Giờ trả thú quy định (BR-LT-04)'),
('boarding.late_cancel_hours',          '24', 'INT', '0',   '72',   'giờ',     'Hủy khi còn dưới mốc này trước ngày nhận là hủy muộn (BR-LT-06)'),
('boarding.overdue_care_task_days',     '1',  'INT', '1',   '7',    'ngày',    'Quá hạn đón từ số ngày này thì sinh Care Task gọi điện (BR-LT-10)'),
('boarding.overdue_manager_alert_days', '7',  'INT', '1',   '30',   'ngày',    'Quá hạn đón từ số ngày này thì báo BRANCH_MANAGER (BR-LT-10)'),
('boarding.capacity_warning_days',      '2',  'INT', '1',   '7',    'ngày',    'Cảnh báo thiếu chỗ cho đặt chỗ nhận trong số ngày tới (BR-LT-10)'),
('care_log.min_per_day',                '1',  'INT', '1',   '5',    'mục',     'Số mục nhật ký chăm sóc tối thiểu mỗi ngày (BR-LT-11)'),
('care_log.max_photos',                 '5',  'INT', '0',   '10',   'ảnh',     'Số ảnh tối đa của một mục nhật ký (BR-LT-11)'),
('care_log.edit_window_minutes',        '60', 'INT', '0',   '1440', 'phút',    'Người ghi được sửa mục nhật ký trong khoảng này (BR-LT-11)'),
-- BH, TG
('order.pending_alert_days',            '1',  'INT', '1',   '30',   'ngày',    'Order PENDING quá số ngày này được liệt kê cuối ngày (BR-BH-05, ST13)'),
('cashier_shift.auto_close_grace_minutes','30','INT','0',   '180',  'phút',    'Quá giờ đóng cửa cuối ngày chừng này thì tự chốt ca thường (BR-TG-05, ST13)'),
-- KO
('stock.expiry_warning_days',           '30', 'INT', '7',   '180',  'ngày',    'Cảnh báo lô sẽ hết hạn trong số ngày tới (BR-KO-07, ST08)'),
-- DG
('feedback.max_per_day',                '5',  'INT', '1',   '20',   'feedback','Số feedback tối đa một khách gửi mỗi ngày (BR-DG-01)'),
-- TB
('reminder.vaccine_days_before',        '7',  'INT', '1',   '30',   'ngày',    'Nhắc tái chủng trước ngày tái chủng (BR-TB-01, ST04)'),
('reminder.vaccine_overdue_days',       '7',  'INT', '1',   '60',   'ngày',    'Quá ngày tái chủng chừng này thì sinh Care Task quá hạn (BR-TB-04)'),
('reminder.follow_up_days_before',      '3',  'INT', '1',   '14',   'ngày',    'Nhắc tái khám trước ngày tái khám (BR-TB-06, ST04)'),
-- BC
('report.max_period_months',            '12', 'INT', '1',   '24',   'tháng',   'Kỳ báo cáo dài tối đa (BR-BC-01)'),
('report.revaccination_grace_days',     '7',  'INT', '0',   '30',   'ngày',    'Tiêm lại không muộn quá số ngày này sau ngày tái chủng thì tính là quay lại (BR-BC-04)');
