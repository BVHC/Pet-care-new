# ADR-0111: Job dọn `otp_tokens` hết hạn (trả D012)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-05
- **Liên quan:** BR-TK-05, BR-TK-07, BR-QT-13; 04 nguyên tắc 2; ADR-0007 (job), 0008 (dọn phiên, mẫu), 0011 (khóa `accounts → otp_tokens`), 0013 (ST02), 0016 (dọn outbox, mẫu), 0102 (quota theo email). Nợ D012 (trả).
- **Triển khai:**
  - `identity/job/{OtpTokenCleanupJob, OtpTokenCleanupProperties}`, `identity/service/OtpTokenCleanupService`, `OtpTokenRepository.deleteExpiredBefore`.
  - `application.yml` (`app.jobs.otp-token-cleanup`), `application-test.yml` (`cron: "-"`), `docker-compose.yml` / `.env.example` (`OTP_TOKEN_CLEANUP_CRON`). Không migration, không endpoint, không [CFG].
  - Test: `OtpTokenCleanupJobTest`, `OtpTokenCleanupPropertiesTest`, `OtpTokenCleanupIT` (era 1985), `OtpTokenCleanupScheduleIT`.

## Bối cảnh

`otp_tokens` chỉ bị xóa khi ST02 xóa tài khoản `PENDING`. Mã của tài khoản `ACTIVE` (đăng ký đã dùng, quên mật khẩu, đổi email, liên kết hồ sơ) nằm lại mãi cùng `code_hash`; bảng và 3 index lớn dần. Đặc tả không quy định thời hạn giữ OTP; BR-QT-15 không liệt kê OTP và BR-QT-16 chỉ nói `audit_logs`, nên `otp_tokens` là dữ liệu kỹ thuật như `sessions` (ADR-0008) và `notification_outbox` (ADR-0016), không thuộc nguyên tắc "không xóa cứng".

## Quyết định

1. Job `OtpTokenCleanupJob` hằng ngày xóa dòng có `expires_at < now − retention-days` (mặc định **1 ngày**), theo lô 1000, mỗi lô một transaction; `DELETE … WHERE id IN (SELECT id … ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED)`.
2. Điều kiện **chỉ theo `expires_at`**, mọi mục đích và trạng thái: `invalidateActive*` chỉ hủy mã khi có mã mới, nên mã hết hạn không ai dùng có `consumed_at` và `invalidated_at` đều NULL.
3. Mỗi lượt có `time-budget` 60 s (house style job); hết thì dừng, lượt sau làm tiếp. Lỗi được log `OTP_TOKEN_CLEANUP_FAILED`, không ném. Không audit.
4. Cron `17 20 3 * * *` (03:20:17 VN): giữa dọn phiên 03:00 và dọn outbox 03:40; giây 17 để không bắt đầu cùng ST20 (giây chia hết cho 5) và các job `*/5` (giây 0). Mọi phút đã có một job `*/5` nên không tránh được phút. Property `app.jobs.otp-token-cleanup.{cron, retention-days, batch-size, time-budget}`, không phải [CFG].
5. Không thêm index theo `expires_at` (như ADR-0016 mục 7): câu chọn đi theo PK, dòng cũ có id nhỏ.

## Bất biến (vì sao không đổi quota BR-TK-07)

Dòng bị xóa có `created_at < expires_at < now − retention`; quota chỉ đếm `created_at > now' − cửa sổ` với `now' ≥ now`. Cửa sổ `otp.send_window_minutes` tối đa **1440 phút** (V2), nên cần `retention-days × 1440 ≥ 1440`, thỏa với `retention-days ≥ 1` (`@Positive`). `OtpTokenCleanupIT.sendWindowMaxFitsRetention` ghim `max_value` của khóa này: migration nào nới cửa sổ quá thời hạn giữ sẽ làm test đỏ. Khoảng cách gửi lại (≤ 300 s) chỉ cần dòng mới nhất.

## Đồng thời

Job chỉ khóa `otp_tokens` trong một câu `SKIP LOCKED`, không xin `accounts` và không chờ khóa của ai, nên không có chu trình với luồng OTP (`accounts → otp_tokens`, ADR-0011), `invalidateActive*` (quét cả mã cũ) hay `deleteByAccountId` của ST02: bên giữ khóa thắng, dòng bị bỏ qua được xóa ở lượt sau, hoặc UPDATE/DELETE chờ job commit rồi thấy 0 dòng. Ghi theo entity (`consume`, `recordFailedAttempt`) chỉ chạy trên mã chưa hết hạn (`OtpService:123,238`), tập rời với tập job xóa, nên không có UPDATE vào dòng đã mất. Job chạy lại hoặc song song vô hại (xóa hai lần = không có gì).

## Phương án bị loại

| Phương án | Vì sao không |
|---|---|
| Giữ 7 hoặc 30 ngày | Bảng lớn hơn, `code_hash` tồn tại lâu hơn; quota chỉ cần tối đa 1 ngày. Người dùng chọn 1 ngày (2026-11-05) |
| Xóa sớm dòng đã dùng / đã hủy | Thêm tham số và ca kiểm thử, lợi ích nhỏ |
| Keyset `id > lô trước` | Nhanh hơn khi xóa rất nhiều nhưng lệch mẫu session / outbox; chưa cần ở quy mô này |
| Xóa trong đường phát hành OTP | Làm chậm request công khai, kéo dài khóa `accounts` |
| Xóa mềm / chỉ xóa `code_hash` | Bảng vẫn lớn, không giải quyết vấn đề chính |

## Hệ quả

- Không còn điều tra được OTP cũ hơn 1 ngày sau khi hết hạn; tăng `retention-days` nếu cần (giữ bất biến trên).
- Tải DB: một DELETE ≤ 1000 dòng mỗi lô, một lượt/ngày; lô cuối quét phần còn lại (khoảng 1 ngày OTP). Dòng chết chờ autovacuum.
- `ST02` giữ nguyên `deleteByAccountId`.
- **Kiểm 2026-11-05:** unit `OtpTokenCleanup*Test` 14/14; IT `OtpTokenCleanupIT` 17 + `OtpTokenCleanupScheduleIT` 1 xanh; hồi quy không sửa 350/350 xanh (`Registration*`, `PasswordResetIT`, `LinkProfileIT`, `OtpLinkProfileIT`, `StaffEmailChangeIT`, `CustomerAccountEmailChangeIT`, `PendingAccountCleanupIT`, `SessionCleanupIT`, `SystemConfigAdminIT`, `AccountFsmIT`, `SchemaMigrationIT`, `OpenApiContractParityIT`). Chưa đo `EXPLAIN` trên ≥ 20 000 dòng.
