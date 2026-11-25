# ADR-0154: ST20 gửi email ngoài transaction (nhận dòng → gửi → ghi kết quả) và gửi OTP ngay sau commit

- **Trạng thái:** Proposed
- **Ngày:** 2026-11-25
- **Thay thế:** ADR-0012 mục 3 (gửi SMTP trong transaction đang khóa dòng) và mục 10 phần "idle timeout 60 s lớn hơn tổng timeout SMTP"; ADR-0017 mục 3 (tự quản `Transport`) và mục 4 (ngân sách gửi của lô). Các mục khác của hai ADR giữ nguyên.
- **Liên quan:** ADR-0007 (job), ADR-0012 (outbox, at-least-once, phân loại lỗi, hai luồng), ADR-0017 (lô NORMAL), ADR-0130 (tiền lệ `AFTER_COMMIT` của `ai`); convention 07 §7.2, §7.4; nợ D087 (mới), D008 (giữ trạng thái đã trả).
- **Triển khai:** `care/service/NotificationDispatchService` (`claimNextHigh`, `claimNextNormalBatch`, `claimHighById`, `settle`), `care/service/OutboxMailSender` (mới, thay `SmtpBatchSender` đã xóa), `care/job/NotificationOutboxRunner` (`deliver`, `sendNow`), `care/entity/NotificationOutbox` (`claim`, `release`, `isClaimedBy`), `care/repository/NotificationOutboxRepository` (`lockDueEmailById`, `findByIdForUpdate`; bỏ `lockNextDueEmailNotIn`), `care/service/NotificationService` + `PriorityEmailQueued`, `care/listener/PriorityEmailKickListener`, `care/job/MailKickExecutorConfig`, `platform/config/AsyncConfig`, `NotificationOutboxProperties` (`priority-claim-lease`, `normal-claim-lease`; bỏ `normal-batch-send-budget`), `application.yml` / `application-test.yml` (`kick-enabled`).

## Bối cảnh

ADR-0012 gửi SMTP **giữa** transaction đang khóa dòng outbox. Vì vậy phải có `idle_in_transaction_session_timeout` 60 s, và ADR-0017 phải tự quản `jakarta.mail.Transport` (`SmtpBatchSender`, ~150 dòng + test 205 dòng) chỉ để dừng được giữa lô, vì `JavaMailSender.send(MimeMessage...)` gửi tiếp sau timeout. Người dùng muốn bớt code tự viết, dùng `JavaMailSender` chuẩn, gửi OTP ngay (hiện chờ tới 5 s), thư nhắc lịch vẫn theo lô (2026-11-25).

## Quyết định

1. **Giữ outbox làm nguồn sự thật.** Service nghiệp vụ vẫn chỉ `NotificationApi.enqueue`; không gửi mail thẳng.
2. **Ba bước, SMTP ở giữa và ngoài transaction.**
   - *Nhận dòng* (một transaction ngắn): khóa dòng đến hạn `FOR UPDATE SKIP LOCKED` (HIGH một dòng, NORMAL tối đa 20) → dựng thư → đặt `next_attempt_at = now + hạn giữ` (cắt micro giây). Giá trị đó là **vé**. Lỗi dựng thư được xử lý ngay như trước.
   - *Gửi*: HIGH và NORMAL đều gọi **một lần** `JavaMailSender.send(MimeMessage...)`; kết quả từng thư lấy từ `MailSendException.getFailedMessages()`.
   - *Ghi kết quả* (một transaction ngắn): khóa từng dòng `FOR NO KEY UPDATE` theo `id` tăng dần → **kiểm vé dưới khóa** (`PENDING` và `next_attempt_at` = vé) → `SENT` / thử lại / `FAILED` / trả về hạn cũ. Vé lệch thì bỏ qua (`NOTIFICATION_SETTLE_STALE`).
   - Không migration: `status` vẫn `PENDING` trong lúc giữ, không thêm cột.
3. **Ma trận lỗi ADR-0017 mục 5 giữ nguyên ý nghĩa.**
   - Người nhận bị từ chối → `FAILED`.
   - Lỗi cấp máy chủ (kết nối, timeout, xác thực) → chỉ thư lỗi đầu tiên tốn một lần thử; các thư lỗi cấp máy chủ sau nó được trả về hạn cũ, không tốn lần thử; lượt dừng.
   - Lỗi tạm riêng thư → thử lại theo backoff.
   - `attempts` chỉ tăng lúc ghi kết quả.
   - Lỗi không kèm danh sách thư (sai xác thực lúc kết nối lại giữa lô) tính cho cả lô; thư đã tới máy chủ trước đó sẽ được gửi lại cùng `Message-ID`.
4. **Hạn giữ** (config, không phải [CFG]):
   - `priority-claim-lease` 60 s (tối thiểu 60 s; một thư tệ nhất 35 s).
   - `normal-claim-lease` 10 phút, có validator `≥ normal-batch-size × 30 s`, vì `JavaMailSender` gửi tiếp sau timeout từng thư.
   - Đổi `MAIL_*_TIMEOUT_MS` thì phải tính lại hai giá trị này.
5. **OTP / mật khẩu tạm gửi ngay sau commit.**
   - `NotificationService.enqueue` phát `PriorityEmailQueued(id)` cho email thuộc `HIGH_TEMPLATES`.
   - `PriorityEmailKickListener` (`@Async("mailKickExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)`) gọi `NotificationOutboxRunner.sendNow`, chỉ nhận đúng dòng đó (`lockDueEmailById`).
   - Job HIGH 5 s vẫn chạy làm đường dự phòng. Kích và job cùng lúc: `SKIP LOCKED` + vé → một lần gửi.
   - `@EnableAsync` ở `platform/config/AsyncConfig`.
   - Executor 2 thread, hàng đợi 200. Hàng đợi đầy thì **bỏ lần kích và log** (`NOTIFICATION_KICK_DROPPED`), không ném, vì ném trong `afterCommit` thành 500 cho request đã commit.
   - `kick-enabled` mặc định `true`, profile test `false`; chỉ `PriorityEmailKickIT` bật.
6. **Ngoại lệ có chủ đích của convention 07 §7.2** (cấm `@TransactionalEventListener` / `@Async`), giống `ai` ST22: đây không phải hệ quả nghiệp vụ mà là tối ưu độ trễ của một tác vụ mạng. Độ bền nằm ở dòng outbox và job quét bù. Thư NORMAL không được kích.
7. **Metric:** bỏ `notification.outbox.smtp.connections`, thêm `notification.outbox.smtp.batches{lane}` (số lời gọi `JavaMailSender`, mỗi lời gọi một kết nối nếu không lỗi). `STOP_RUN` đếm số lượt dừng ở cả hai luồng email.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Service nghiệp vụ gọi `@Async` gửi thẳng | Mất thư khi restart / SMTP sập, không thử lại, gửi cả khi rollback nếu không dùng `AFTER_COMMIT` |
| `send(MimeMessage...)` cả lô **trong** transaction | Lô treo giữ khóa N × 25 s, vượt idle timeout 60 s (lý do của ADR-0017) |
| Trạng thái `SENDING` + cột lease | Cần migration, sửa CHECK và các câu backlog / dọn; vé bằng `next_attempt_at` đủ cho một instance |
| Lớp con `JavaMailSenderImpl` ghi đè `doSend` | Phụ thuộc hàm `protected` của Spring, ngân sách phải qua ThreadLocal |

## Hệ quả

- Không còn transaction nào mở trong lúc chờ SMTP; xóa `SmtpBatchSender` và test của nó. `idle_in_transaction_session_timeout` 60 s giữ làm lưới an toàn cho bước nhận.
- At-least-once giữ nguyên, cùng `Message-ID`. Chết sau khi nhận dòng thì dòng hiện lại sau hạn giữ (HIGH 60 s, NORMAL 10 phút), không tốn lần thử. Gửi chậm quá hạn giữ có thể gây gửi trùng, đã chặn bằng validator.
- **SMTP treo:** thread NORMAL kẹt tới ~10 phút (scheduler 4 thread còn 3) nhưng không giữ khóa hay connection DB; OTP không bị ảnh hưởng. Chấp nhận, ghi ở D087.
- Mỗi lô NORMAL tốn 2 transaction ngắn thay cho 1 dài; mỗi OTP được kích tốn thêm 2 transaction.
- Test: `NotificationDispatchServiceTest`, `OutboxMailSenderTest` (ma trận lỗi), `NotificationOutboxRunnerTest` (thứ tự nhận → gửi → ghi, `sendNow`), `NotificationOutboxPropertiesTest`, `NotificationServiceTest`, `MailKickExecutorConfigTest`; `NotificationOutboxIT` (trong lúc SMTP treo: `FOR UPDATE NOWAIT` thành công và không có phiên idle in transaction; dòng bỏ dở hiện lại sau hạn giữ; vé cũ không đè); `PriorityEmailKickIT` (gửi khi không job nào chạy, rollback không gửi, NORMAL không kích, kích ∥ job một thư, SMTP sập để lại cho job).
