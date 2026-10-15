-- T16 phần 0 (docs/adr/0041): số của mã lịch hẹn hiển thị appointments.code = 'LH-' || yyMMdd || '-' || số.
-- Sequence toàn cục: mã duy nhất theo cấu tạo, không cần khóa; không reset theo ngày, có thể nhảy cóc khi rollback
-- (lịch hẹn không yêu cầu số liên tục, khác phiếu nhập BR-KO-03).
-- MAXVALUE 9999999999 (10 chữ số) giữ mã <= 20 ký tự (VARCHAR(20)); NO CYCLE: hết số thì báo lỗi, không quay vòng sinh mã trùng.
CREATE SEQUENCE appointment_code_seq AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;
