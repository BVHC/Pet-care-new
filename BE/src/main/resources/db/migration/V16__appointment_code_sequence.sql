<<<<<<< HEAD
-- T16 phần 0 (docs/adr/0045; vốn là V13, đổi thành V16 vì trùng số với V13__pet_customer_fk_indexes): số của mã lịch hẹn hiển thị appointments.code = 'LH-' || yyMMdd || '-' || số.
=======
-- Đánh số lại V13 → V16 khi gộp nhánh manh với dung (2026-10-18): V13–V15 là của BE-2 (đã push lên origin/dung).
-- T16 phần 0 (docs/adr/0041): số của mã lịch hẹn hiển thị appointments.code = 'LH-' || yyMMdd || '-' || số.
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
-- Sequence toàn cục: mã duy nhất theo cấu tạo, không cần khóa; không reset theo ngày, có thể nhảy cóc khi rollback
-- (lịch hẹn không yêu cầu số liên tục, khác phiếu nhập BR-KO-03).
-- MAXVALUE 9999999999 (10 chữ số) giữ mã <= 20 ký tự (VARCHAR(20)); NO CYCLE: hết số thì báo lỗi, không quay vòng sinh mã trùng.
CREATE SEQUENCE IF NOT EXISTS appointment_code_seq AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;
