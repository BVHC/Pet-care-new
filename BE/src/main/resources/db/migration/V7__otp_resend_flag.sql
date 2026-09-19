-- RULE-01-05 (resend OTP tối đa 5 lần/giờ) — countByEmailAndPurposeAndCreatedAtAfter
-- đếm mọi row otps (kể cả OTP gốc phát sinh từ RegisterAccount) rồi cộng bù +1 vào
-- ngưỡng để trừ đúng 1 bản ghi đó ra. Cộng bù chỉ đúng khi OTP gốc còn nằm trong cửa
-- sổ rolling 1h đang đếm; nếu user chờ >1h rồi mới bắt đầu resend, OTP gốc tự văng
-- khỏi cửa sổ nhưng ngưỡng vẫn cộng dư +1 -> cho phép 6 lần resend/giờ thay vì 5.
-- is_resend đánh dấu trực tiếp OTP nào do resendOtp() tạo ra, để đếm đúng số lần
-- resend thật sự thay vì suy luận ngược từ tổng số row trừ hao.
ALTER TABLE otps
    ADD COLUMN is_resend BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_otps_email_purpose_resend ON otps(email, purpose, is_resend, created_at);
