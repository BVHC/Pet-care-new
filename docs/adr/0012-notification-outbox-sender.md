# ADR-0012: Worker ST20 gửi email từ `notification_outbox`

- **Trạng thái:** Accepted (mục 1, 9 → ADR-0014; mục 3 cho luồng NORMAL → ADR-0017)
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/01-business-operations.md` ST20 (gửi thông báo & thử lại khi thất bại); `docs/02-business-rules.md` BR-TK-04, 05 (OTP qua email, hiệu lực 5 phút [CFG]), BR-TK-16 (thông báo tới email cũ và mới), BR-QT-14 (mẫu, biến bắt buộc), BR-LH-12, BR-TB-01, BR-TB-06 ("gửi thất bại thì thử lại theo ST20").
  - `docs/05-erd.md` §11 `notification_outbox`, §1 `notification_templates`; §13 mục 11 (lệch ghi ở đây).
  - `docs/06-module-contracts.md` §1 (`NotificationApi` chỉ ghi outbox), §7 G1 (NotificationTemplate thuộc identity), §8 Q4 (danh sách mẫu).
  - Convention 07 §7.2 (cấm `@TransactionalEventListener`, gửi qua outbox), §7.4 (job tách transaction theo bản ghi); 08 §8.1 (không log OTP).
  - ADR-0007 (cơ chế `@Scheduled`), ADR-0008 (`SKIP LOCKED` cho job), ADR-0009 (OTP dạng rõ chỉ nằm trong payload outbox). Nợ D003 (trả bởi ADR này), mở D006, D007, D008.
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/care/service/NotificationDispatchService.java`, `NotificationTemplateRenderer.java`, `DeliveryLane.java`.
  - `BE/src/main/java/com/petcare/module/care/job/PriorityNotificationJob.java`, `NotificationOutboxJob.java`, `NotificationOutboxRunner.java`, `NotificationOutboxProperties.java`.
  - `BE/src/main/java/com/petcare/module/care/entity/NotificationOutbox.java` (`markSent`, `scheduleRetry`, `markFailed`, xóa khóa nhạy cảm), `care/repository/NotificationOutboxRepository.java`, `LaneBacklog.java`.
  - `BE/src/main/java/com/petcare/module/identity/api/NotificationTemplateQueryApi.java` + `identity/entity/NotificationTemplate`, `identity/repository/NotificationTemplateRepository`, `identity/service/NotificationTemplateService`.
  - `BE/src/main/java/com/petcare/module/care/service/NotificationService.java` (người nhận theo kênh), `care/api/NotificationApi.java` (javadoc).
  - `BE/src/main/resources/db/migration/V4__st20_notification_outbox.sql`; `application.yml` (`spring.mail.*` timeout, `spring.task.scheduling.pool.size: 3`, `app.jobs.notification-outbox.*`, bật lại mail health); `docker-compose.yml` (Mailpit), `.env.example`.
  - Test (tiêu chí → test):
    - Gửi được, `Message-ID` cố định, mã OTP bị xóa khỏi DB: `NotificationOutboxIT.otpEmailIsSentWithFixedMessageIdAndCodeIsRemovedFromDb` (fail khi bỏ xóa khóa nhạy cảm — mutation 2026-10-07).
    - OTP không xếp sau tồn đọng: `NotificationOutboxIT.priorityLaneSendsOtpAheadOfNormalBacklog` (200 thư NORMAL đến hạn trước; fail khi luồng HIGH chọn nhầm dòng NORMAL — mutation 2026-10-07); `normalLaneLeavesHighTemplatesToPriorityLane`.
    - Thử lại 1/2/4/8 phút rồi `FAILED`: `NotificationDispatchServiceTest.transientErrorBacksOffExponentially`, `fifthTransientFailureMarksFailedAndScrubs`; `NotificationOutboxIT.smtpDownRetriesWithBackoffThenFails`.
    - Lỗi vĩnh viễn → `FAILED` ngay, không gửi: `NotificationDispatchServiceTest` (`missingRecipientEmail…`, `unknownTemplate…`, `templateOfOtherChannel…`, `missingRequiredVariable…`, `malformedAddress…`, `recipientRejectedByServerFails`); `NotificationOutboxIT.missingRequiredVariableFailsWithoutSending`.
    - SMTP treo không giữ transaction: `NotificationOutboxIT.hungSmtpIsCutByTimeoutAndReleasesTransaction`; `NotificationDispatchServiceTest.limitsIdleInTransactionBeforeLockingRow`.
    - Lỗi kết nối dừng lượt: `NotificationDispatchServiceTest.connectionRefusedSchedulesRetryAndStopsRun`, `readTimeoutInsidePerMessageFailureStopsRun`, `authenticationFailureStopsRun`; `NotificationOutboxRunnerTest.stopsOnServerError`.
    - Không gửi trùng: `NotificationOutboxIT.secondRunDoesNotResend`, `concurrentDispatchSendsOnce`, `lockedRowIsSkipped`.
    - Ngân sách thời gian, metrics, cảnh báo trễ: `NotificationOutboxRunnerTest`.
    - Người nhận theo kênh: `NotificationServiceTest.emailChannelRequiresRecipientEmailEvenWithAccount`, `inAppChannelRequiresRecipientAccount`.

## Bối cảnh

`NotificationApi.enqueue` chỉ INSERT `notification_outbox` trong transaction nghiệp vụ (transactional outbox, 06 §1); chưa có gì gửi đi (nợ D003), nên OTP đăng ký không tới người dùng và `payload` giữ mã OTP dạng rõ vĩnh viễn. ST20 là job đầu tiên gọi hệ thống ngoài (SMTP) giữa lúc giữ transaction DB, nên phải chốt: gửi theo giao dịch nào, thử lại ra sao, chống gửi trùng, không làm quá tải DB, và OTP (người dùng đang chờ, hiệu lực 5 phút [CFG]) không bị chậm khi có hàng trăm thư nhắc ghi cùng lúc.

Số đo khi thiết kế (DB dev, 2026-10-07): Postgres `max_connections = 100`; Hikari 10; `idle_in_transaction_session_timeout`, `lock_timeout`, `statement_timeout` = 0. JavaMail mặc định không có timeout. Một thư qua Gmail tốn ~0,5–1,5 s (TLS + AUTH mỗi thư) → một luồng ~0,7–1 thư/s: 600 thư nhắc ghi trước một OTP làm OTP chờ ~10 phút, quá hạn 5 phút.

## Quyết định

1. **Chỉ kênh EMAIL.** Dòng `IN_APP` không bị chọn, giữ `PENDING` (nợ D006).
2. **Người nhận theo kênh, chốt lúc ghi.** `EMAIL` bắt buộc `recipient_email` — địa chỉ tại thời điểm sự kiện; worker không tra email theo tài khoản. `IN_APP` bắt buộc `recipient_account_id`. Kiểm ở `NotificationService.enqueue` (`IllegalArgumentException`), không thêm CHECK DB.
3. **Mỗi dòng một transaction**, ở `NotificationDispatchService.dispatchNext`: `set_config('idle_in_transaction_session_timeout', '60s', true)` → `SELECT … FOR UPDATE SKIP LOCKED LIMIT 1` dòng đến hạn của luồng → dựng thư → SMTP → cập nhật trạng thái → commit.
4. **At-least-once + `Message-ID` cố định** `<notification-{id}@petcare.local>`: SMTP đã nhận mà commit lỗi thì lượt sau gửi lại cùng `Message-ID` (client gộp thư trùng). Trùng OTP vô hại.
5. **Phân loại lỗi.**
   - Vĩnh viễn → `FAILED` ngay: thiếu `recipient_email`; mẫu không có hoặc khác kênh `EMAIL`; thiếu biến bắt buộc (BR-QT-14); địa chỉ không hợp lệ; máy chủ từ chối người nhận (`SendFailedException` có địa chỉ không hợp lệ).
   - Tạm thời → `attempts + 1`, `next_attempt_at = now + initialBackoff × 2^attempts` (1, 2, 4, 8 phút); lần thứ `maxAttempts` (5) lỗi → `FAILED`.
   - Mức máy chủ (`ConnectException`, `SocketTimeoutException`, `UnknownHostException`, `MailAuthenticationException`) → như tạm thời và **dừng lượt** (`STOP_RUN`).
   - Lỗi DB → thoát ra, rollback dòng, lượt sau làm lại.
6. **Xóa dữ liệu nhạy cảm.** Khi dòng vào `SENT`/`FAILED`, mọi khóa payload có tên chứa `otp`, `mat_khau`, `password`, `token`, `secret` bị xóa. `payload` vì vậy không còn chỉ ghi một lần (erd §13 mục 11).
7. **Hai luồng.** HIGH = mẫu có người đang chờ (`OTP_REGISTER`, `OTP_PASSWORD_RESET`, `OTP_EMAIL_CHANGE`, `OTP_PROFILE_LINK`, `STAFF_TEMP_PASSWORD` — `DeliveryLane.HIGH_TEMPLATES`), job `PriorityNotificationJob` cron `*/5 * * * * *`; NORMAL = phần còn lại, job `NotificationOutboxJob` cron `*/10 * * * * *`. Hai luồng lọc `template_code IN / NOT IN` cùng một tập nên không chọn trùng dòng. Index mới `(status, template_code, next_attempt_at)` (V4).
8. **Ngân sách thời gian mỗi lượt** thay cho số dòng: HIGH 4 s, NORMAL 8 s (`NotificationOutboxRunner`). Lượt dừng khi hết dòng đến hạn, gặp `STOP_RUN` hoặc hết ngân sách.
9. **Scheduler 3 thread** (`spring.task.scheduling.pool.size: 3`): hai luồng ST20 và job khác chạy song song; một job vẫn không chồng lên chính nó. Job dùng tối đa 3 connection.
10. **Timeout SMTP bắt buộc**: kết nối 5 s, đọc 10 s, ghi 10 s (`spring.mail.properties`). `idle_in_transaction_session_timeout` 60 s lớn hơn tổng 25 s để không hủy nhầm lần gửi đã thành công.
11. **Metrics và cảnh báo.** Micrometer: gauge `notification.outbox.pending{lane}`, `notification.outbox.oldest.overdue.seconds{lane}` (giá trị lấy lúc cuối lượt, không truy vấn khi scrape), counter `notification.outbox.result{lane,result}`, timer `notification.outbox.queue.latency{lane}` (lần gửi đầu). Log `WARN NOTIFICATION_OUTBOX_LAGGING` khi dòng quá hạn lâu hơn `lag-warn-priority` (60 s) / `lag-warn` (15 phút). Endpoint `/actuator/metrics` vẫn đóng.
12. **Log.** Lượt có việc → một dòng INFO `<JOB> sent= retried= failed= …`; lượt rảnh → DEBUG (lệch ADR-0007 mục 6 — lượt 5 s/10 s sẽ sinh ~26.000 dòng INFO/ngày). Không log payload; email chỉ log dạng che.
13. **Biến tùy chọn thiếu → chuỗi rỗng.** V4 đổi câu chào `OTP_REGISTER` thành `Xin chào,` (cả bản mặc định) vì email gửi lại OTP không có `{ten_khach}`.
14. **Mẫu đọc qua `identity.api.NotificationTemplateQueryApi`** (NotificationTemplate thuộc identity — 06 §7 G1).
15. **Dev:** Mailpit trong docker-compose là SMTP mặc định (`localhost:1025`, UI `:8025`); mail health indicator bật lại.

**Chống gửi trùng phía ghi outbox** (quy tắc cho mọi module gọi `enqueue`): outbox chỉ bảo đảm "có dòng ⇔ transaction nghiệp vụ commit", không chống chính use case chạy hai lần. Use case do người dùng gọi chống lặp bằng rule/FSM của nó (OTP: BR-TK-07, mã mới vô hiệu mã cũ). Job sinh thông báo (ST03, ST04…) chọn bản ghi theo cờ "đã nhắc" và đặt cờ trong cùng transaction với `enqueue` (`appointments.reminded_at`, `medical_records.follow_up_reminded_at`, `vaccinations.due_reminded_at`).

## Lý do và phương án bị loại

| Phương án | Lý do loại |
|---|---|
| Gửi ngay sau commit (`@TransactionalEventListener(AFTER_COMMIT)`, `@Async`) | Convention 07 §7.2 cấm; mất thư khi app chết giữa chừng; vẫn cần worker để thử lại |
| Claim theo lô + lease rồi gửi ngoài transaction | Không giữ connection khi gửi, nhưng thêm khái niệm lease và gấp đôi transaction; với 1 thread/luồng và timeout SMTP, giữ 1 connection vài giây là chấp nhận được |
| At-most-once (đánh dấu trước rồi mới gửi) | Không trùng nhưng mất thư khi sập giữa chừng; mất OTP tệ hơn nhận trùng OTP |
| Worker tra email theo `recipient_account_id` lúc gửi | Sai với `EMAIL_CHANGED_NOTICE` (BR-TK-16 gửi email cũ); FK `RESTRICT` chặn ST02 xóa tài khoản `PENDING` nếu outbox OTP mang `account_id` |
| Một hàng đợi FIFO, ưu tiên bằng `ORDER BY CASE template_code …` | Phá thứ tự index, phải sắp xếp toàn bộ tồn đọng mỗi lần chọn; một lượt NORMAL dài vẫn chặn OTP |
| Cột `priority` mới trên `notification_outbox` | Đổi schema chung với BE-2 cho thông tin suy ra được từ `template_code` |
| Nhiều thread mỗi luồng / tái dùng kết nối SMTP theo lô | Tăng thông lượng nhưng thêm connection DB và độ phức tạp; chưa có số liệu cho thấy cần (D008) |
| Rate limit gửi trong worker | Chưa cần với Mailpit/Gmail ở quy mô đồ án; khi dùng nhà cung cấp thật thì cấu hình theo hạn mức của họ |

## Hệ quả

- **Tích cực:**
  - OTP tới trong ~5 s + thời gian gửi, không phụ thuộc tồn đọng thư hàng loạt.
  - Mã OTP và mật khẩu tạm không nằm lại trong DB sau khi gửi.
  - SMTP sập hoặc treo không giữ connection, khóa hay thread scheduler lâu hơn ~25 s / 60 s; mỗi lượt khi SMTP sập tốn ≤ 1 transaction.
  - Tải DB lúc rảnh: ~26.000 transaction ngắn/ngày (~0,3 tx/s), mỗi transaction một index scan; job dùng tối đa 3 connection.
- **Đánh đổi đã chấp nhận:**
  - At-least-once: hiếm khi nhận thư trùng (app/DB sập đúng lúc giữa SMTP và commit).
  - Gmail giới hạn ~500 thư/ngày (tài khoản cá nhân), ~2.000 (Workspace); vượt mức → lỗi tạm thời → thử lại → `FAILED`. Môi trường thật phải dùng nhà cung cấp SMTP transactional (SES, SendGrid, Brevo…) qua `MAIL_*`, không đổi code.
  - Nhiều instance: `SKIP LOCKED` vẫn không gửi trùng đồng thời, nhưng ADR-0007 mục 8 vẫn yêu cầu ADR mới trước khi scale ngang.
  - Chạy `mvn spring-boot:run` ngoài Docker cần Mailpit (`docker compose up -d mailpit`), nếu không `/actuator/health` báo mail DOWN.
- **Ràng buộc cho task sau:**
  - Mẫu mới mà người dùng đang chờ trên màn hình → thêm vào `DeliveryLane.HIGH_TEMPLATES`.
  - Biến payload chứa bí mật phải có tên chứa một trong các từ ở quyết định 6.
  - Module ghi `IN_APP` đầu tiên phải có worker IN_APP trước (D006) — dòng `IN_APP` dồn trong dải `PENDING` làm chậm truy vấn.
  - Bảng outbox chỉ tăng; dọn dòng đã xử lý ở D007.
