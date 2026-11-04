# ADR-0102: Chấp nhận quota OTP theo email đích — người khác tiêu được quota của chủ email (D015)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-04
- **Liên quan:** BR-TK-04, BR-TK-07, BR-TK-10, BR-TK-19; ADR-0025 mục 4 (hủy mã chỉ trong cùng tài khoản), 0027 (UC07), 0034 Q7 (sửa hộ email nhân viên), 0097 mục (e) (UC22 #25), 0100 (rate limit IP không phủ endpoint cần token), 0101 (cùng tinh thần chấp nhận). Nợ: D015 (đóng ở dạng chấp nhận, không trả).
- **Triển khai:** không có. Không code, không migration, không contract, không [CFG].

Người dùng chốt ngày 2026-11-04: đồ án, không có người dùng thật; giữ UC07 nguyên vẹn, không sửa code. Khung chốt: đây là **thuộc tính vốn có của quota theo email đích**, không thể loại bỏ hoàn toàn mà chỉ giảm được; không gọi là "không thể tránh khỏi".

## Vấn đề

`OtpService.checkSendQuota` đếm theo `otp_tokens.target_email` (BR-TK-07: ≥ 60 giây giữa hai lần gửi, tối đa 5 mã / 60 phút). Email nhận không phải lúc nào cũng là email của người xin:
- `LINK_PROFILE` (UC07, `POST /api/me/link/otp`): email ghi trong **hồ sơ tại quầy** (BR-TK-04).
- `CHANGE_EMAIL`: email **mới** do người khác nhập (nhân viên #23, khách qua UC22 #25).

Câu đếm quota không khóa, nên hai tài khoản khác nhau cùng xin tới một email, chạy song song, có thể cùng vượt mốc 60 giây / 5 mã (cùng tài khoản thì khóa `accounts` tuần tự hóa).

## Kịch bản (timeline)

Y là tài khoản khách đã xác thực email, biết SĐT của hồ sơ quầy #315 có email `a@mail.com`:
1. 09:00 `GET /api/me/link-candidates` theo SĐT: thấy #315 (họ tên đã che, không lộ email).
2. 09:00:30 – 09:04:30 `POST /api/me/link/otp` `{customerId: 315}` năm lần, cách ≥ 60 giây: năm mã tới `a@mail.com`, quota 5/5.
3. Từ đó đến 10:00:30 chủ email không nhận được mã nào (kể cả quên mật khẩu). Y chỉ cần một lần mỗi giờ để giữ nguyên trạng.

Y không đọc được mã nên không liên kết được hồ sơ, không đọc được dữ liệu của chủ hồ sơ.

## Quyết định

1. **Chấp nhận quota theo email đích, không thêm quota phụ theo tài khoản.** Lý do:
   - Mọi quota OTP theo email đích đều cho phép bên kích hoạt việc gửi tiêu quota của người nhận: quota sinh ra để bảo vệ hộp thư khỏi spam, nên phải đếm theo người nhận.
   - BR-TK-07 đã có tính chất này ở đường công khai: ai gõ email của người khác vào đăng ký hoặc quên mật khẩu cũng tiêu quota của người đó. D015 chỉ thêm một đường vào, nên không xử lý riêng nó.
   - Quota phụ theo tài khoản chỉ giảm: kẻ tấn công tạo thêm tài khoản (email thật + OTP) để lặp lại. Chặn thật cần CAPTCHA hoặc giới hạn theo thiết bị, ngoài phạm vi đồ án.
2. **Không lấy advisory lock (namespace 1005, còn trống) quanh `checkSendQuota`.** Đổi cách khóa của mọi luồng OTP, phải chạy lại toàn bộ IT OTP; race chỉ cho vượt vài mã.
3. **Không đổi nghiệp vụ UC07 / BR-TK-19** (không bỏ liên kết tại nhà).

## Giảm thiểu hiện có

- Phải đăng nhập tài khoản khách đã xác thực email (`link-candidates`, `link/otp` đều `hasRole('CUSTOMER')`); biết đúng SĐT; hồ sơ quầy phải có email.
- Giữa hai lần gửi ≥ 60 giây: cần khoảng 4 phút để tiêu 5 mã.
- Không mất dữ liệu, không liên kết sai: mã gắn `(tài khoản, hồ sơ, email)` (ADR-0025).
- Chặn tự hết sau tối đa 1 giờ; khách ra quầy vẫn xử lý được (UC22 #27 kiểm CCCD, không OTP, không dùng quota email).
- #23 và UC22 #25 do người đã đăng nhập, đúng phân cấp, thực hiện: rủi ro lạm dụng thấp hơn khách bất kỳ.

## Chỗ yếu (ghi rõ để không bị coi là sót)

- **Email làm phiền:** mỗi email nêu tên tài khoản đang xin liên kết; không có cách chặn nào ngoài việc đổi thiết kế.
- **Quên mật khẩu bị nuốt lỗi:** `PasswordResetService.requestPasswordReset` bắt BR-TK-07, chỉ log `PASSWORD_RESET_OTP_THROTTLED` và trả `OtpSentResponse` như bình thường (BR-TK-10). Chủ email bị chặn thấy "đã gửi" nhưng không nhận email. Đây là hậu quả thực duy nhất.
- **Không audit lần xin mã liên kết:** `LinkProfileAttemptService.issueLinkOtp` không ghi audit (chỉ bước xác nhận ghi `CUSTOMER_PROFILE_LINKED`), nên không truy vết được ai xin mã tới email nào.
- **Rate limit IP (ADR-0100) không phủ** `/api/me/link/otp`, #23, UC22 #25 (chỉ `login`, `register*`, `password/*`).
- **Race không khóa:** hai tài khoản khác nhau có thể làm một email nhận thêm vài mã.

## Quy trình tay

- Chủ email bị chặn: chờ tối đa 1 giờ (mã cũ nhất ra khỏi cửa sổ), hoặc ra quầy để lễ tân xử lý (UC22).
- Không có công cụ xóa `otp_tokens`; không thêm.

## Điều kiện mở lại

Có người dùng thật báo bị chặn nhận mã, hoặc khi thêm CAPTCHA / giới hạn thiết bị ở một ADR khác.

## Cách trả nếu mở lại

- Quota phụ theo tài khoản người xin cho `LINK_PROFILE` (ví dụ số mã liên kết / giờ / tài khoản là [CFG] mới; đếm bằng `otp_tokens(account_id, purpose, created_at)`, kiểm trước quota theo email); cần ADR vì ngoài BR-TK-07. IT: tài khoản Y vượt quota phụ → 400, quota của email chủ hồ sơ không bị tiêu, chủ hồ sơ vẫn xin được mã quên mật khẩu.
- Hoặc bỏ liên kết tại nhà (chỉ UC22): sửa BR-TK-19, UC07, bỏ identity-v1 #11–#13, sửa FE và `LinkProfileIT`; khách cũ phải ra quầy để dùng lại thú và lịch sử.
- Cho race: `pg_advisory_xact_lock(1005, hashtext(target_email))` trong `OtpService` trước `checkSendQuota`, giữ thứ tự `accounts → otp_tokens`, chạy lại mọi IT OTP.
