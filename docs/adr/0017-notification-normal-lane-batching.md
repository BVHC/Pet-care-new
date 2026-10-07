# ADR-0017: Luồng NORMAL của ST20 gửi theo lô trên một kết nối SMTP

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Supersedes:** ADR-0012 mục 3 ("mỗi dòng một transaction") **cho luồng NORMAL**. Luồng HIGH và mọi mục khác của ADR-0012 giữ nguyên.
- **Liên quan:**
  - `docs/01-business-operations.md` ST20; BR-LH-12, BR-TB-01, BR-TB-06 (nhắc gửi qua ST20).
  - `docs/convention/backend/07-transaction-management.md` §7.4.
  - ADR-0012 (worker email: mục 4 at-least-once, mục 5 phân loại lỗi, mục 8 ngân sách, mục 10 timeout), ADR-0014. Nợ D008 (trả bởi ADR này).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/care/service/SmtpBatchSender.java` (mới), `SmtpFailures.java` (phân loại lỗi dùng chung, tách từ `NotificationDispatchService`).
  - `NotificationDispatchService.dispatchNextBatch`, `NotificationOutboxRepository.lockNextDueEmailBatchNotIn`, `NotificationOutboxRunner` (lane NORMAL), `NotificationOutboxProperties.{normalBatchSize, normalBatchSendBudget}`; `application.yml` (`normal-batch-size: 20`, `normal-batch-send-budget: 10s`).
  - Test (tiêu chí → test):
    - Một kết nối cho cả lô, đúng `Message-ID`: `NotificationOutboxIT.normalLaneSendsBatchOverOneConnection`; `SmtpBatchSenderTest.sendsWholeBatchOverOneConnectionInOrder`, `savesChangesBeforeSending`.
    - SMTP câm: cắt bằng timeout, chỉ dòng đầu bị tính lần thử, không còn phiên idle in transaction: `NotificationOutboxIT.hungSmtpStopsNormalBatchAndLeavesRestUntouched`.
    - Ma trận lỗi: `SmtpBatchSenderTest` (kết nối lỗi, xác thực lỗi, người nhận bị từ chối, timeout giữa lô, mất kết nối, hết ngân sách, QUIT lỗi); `NotificationDispatchServiceTest` (`composesEachRowSendsOneBatchAndMarksRowsByOutcome`, `connectionFailureChargesOnlyFirstRowAndStopsRun`, `rejectedRecipientFailsOnlyThatRow`, `transientErrorOfOneMessageSchedulesRetry`, `serverErrorOnLastAttemptFailsThatRowAndKeepsTheRest`, `databaseErrorInBatchPropagatesBeforeSending`, `batchLimitsIdleInTransactionBeforeLockingUpToBatchSize`).
    - Vòng lặp lượt: `NotificationOutboxRunnerTest` (`normalLaneSendsInBatchesWithConfiguredSizeAndBudgetNeverOneByOne`, `normalBatchStopRunEndsTheRun`, `normalLaneStopsWhenTimeBudgetIsSpent`); cấu hình: `NotificationOutboxPropertiesTest`.

## Bối cảnh

ADR-0012 mục 3 gửi mỗi dòng một transaction và (do `JavaMailSender.send` mở kết nối mỗi lần) một kết nối SMTP: TLS + AUTH ~0,5–1,5 s/thư với Gmail → luồng NORMAL ~1 thư/s. Đợt nhắc lịch / hủy hàng loạt vài nghìn thư mất cả giờ. Người dùng chọn trả nợ D008 trước khi có số đo (2026-10-07).

`JavaMailSender.send(MimeMessage...)` của Spring dùng một kết nối cho nhiều thư nhưng **gửi tiếp sau lỗi timeout** của một thư: một lô N thư trên máy chủ treo có thể chờ N × (ghi 10 s + đọc 10 s) trong khi transaction đang giữ khóa dòng outbox, vượt `idle_in_transaction_session_timeout` 60 s (ADR-0012 mục 10) — Postgres hủy phiên, mọi thư đã gửi bị gửi lại.

## Quyết định

1. **Chỉ luồng NORMAL theo lô.** HIGH (OTP, mật khẩu tạm) giữ một dòng / transaction để độ trễ thấp nhất; IN_APP không qua SMTP.
2. **Một lô = một transaction:** `set_config('idle_in_transaction_session_timeout', '60s', true)` → khóa tối đa `normal-batch-size` (20) dòng đến hạn (`ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED`) → dựng thư từng dòng → gửi trên một kết nối → cập nhật từng dòng → commit.
3. **Tự quản `Transport`** (`SmtpBatchSender`) thay vì `JavaMailSender.send(MimeMessage...)`, để dừng được giữa lô. Kết nối theo đúng cách của `JavaMailSenderImpl` (đọc host/port/user mỗi lần); mỗi thư `saveChanges()` trước khi gửi để giữ `Message-ID` cố định.
4. **Ngân sách gửi của lô** `normal-batch-send-budget` (mặc định 10 s, **tối đa 15 s**), tính từ trước khi kết nối; kiểm trước mỗi thư sau thư đầu. Tệ nhất trong transaction: 15 s + một thư (ghi 10 + đọc 10) + NOOP kiểm kết nối 10 + QUIT 10 = 55 s < 60 s. (Plan ban đầu ghi tối đa 20 s; hạ xuống 15 s sau khi tính thêm NOOP.)
5. **Ma trận lỗi** (phân loại như ADR-0012 mục 5, dùng chung `SmtpFailures`):

   | Tình huống | Dòng | Lượt |
   |---|---|---|
   | Dựng thư lỗi vĩnh viễn | dòng đó `FAILED`, không gửi | tiếp |
   | Dựng thư lỗi khác | dòng đó thử lại / `FAILED` | tiếp |
   | Không kết nối được / sai xác thực | **chỉ dòng đầu** bị tính một lần thử; các dòng khác không đổi | dừng (`STOP_RUN`) |
   | Thư i: máy chủ từ chối người nhận | dòng i `FAILED` | tiếp |
   | Thư i: lỗi cấp máy chủ (timeout, kết nối) | dòng i thử lại / `FAILED`; dòng sau i không đổi | dừng |
   | Thư i: lỗi riêng thư rồi mất kết nối | như trên | dừng |
   | Thư i: lỗi tạm riêng thư (4xx), còn kết nối | dòng i thử lại | tiếp |
   | Hết ngân sách lô trước thư i | dòng từ i không đổi | kết thúc lô |
   | Lỗi DB | rollback cả lô | ném → `NOTIFICATION_OUTBOX_FAILED` |

   "Chỉ dòng đầu bị tính" giữ đúng tốc độ tiêu lần thử như gửi từng thư khi SMTP sập (mỗi lượt một dòng), thay vì cả lô `FAILED` sau 5 lượt.
6. **Metrics:** thêm counter `notification.outbox.smtp.connections{lane}`. Với NORMAL, `notification.outbox.result` đếm dòng theo kết quả thật (`SENT`/`RETRY`/`FAILED`) và `STOP_RUN` đếm số lượt dừng vì lỗi máy chủ (luồng một dòng giữ nghĩa cũ: dòng gặp lỗi máy chủ đếm là `STOP_RUN`).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| `JavaMailSender.send(MimeMessage...)` | Gửi tiếp sau timeout → lô có thể giữ transaction N × 20 s, vượt idle timeout 60 s |
| Khóa lô rồi gửi ngoài transaction (claim + lease) | Cần cột lease / trạng thái mới và job thu hồi; ADR-0012 đã loại vì phức tạp |
| Nhiều thread cho NORMAL | Mỗi thread vẫn một kết nối mỗi thư; tốn connection DB và SMTP |
| Lô lớn (100) | Giữ khóa và transaction lâu hơn, một lỗi DB gửi lại nhiều thư hơn; 20 thư × ~0,2 s nằm gọn trong ngân sách |

## Hệ quả

- Nợ D008 đã trả. Thông lượng NORMAL ước ~3–10 thư/s trên kết nối giữ lại (thay ~1 thư/s); OTP không bị ảnh hưởng (luồng HIGH riêng).
- **At-least-once rộng hơn:** commit lỗi sau khi SMTP đã nhận làm lượt sau gửi lại cả lô (≤ 20 thư) thay vì 1 — vẫn cùng `Message-ID`, client gộp thư trùng (ADR-0012 mục 4).
- **Khóa:** tối đa 20 dòng NORMAL bị giữ ≤ 55 s; HIGH khóa dòng khác (`template_code IN`), lượt NORMAL song song (nếu có) dùng `SKIP LOCKED`.
- Đổi timeout SMTP (`MAIL_*_TIMEOUT_MS`) lớn hơn 5/10/10 s phải tính lại giới hạn ở mục 4 (như ADR-0012 mục 10).
- `dispatchNext` vẫn nhận luồng NORMAL (một dòng) nhưng runner không còn gọi; giữ làm thao tác nguyên tử cho test và công cụ vận hành.
- **Đã kiểm 2026-10-07:** `mvn clean verify` xanh; mutation cho `NotificationOutboxRunner` gọi lại `dispatchNext` từng dòng cho NORMAL → `NotificationOutboxIT.normalLaneSendsBatchOverOneConnection` đỏ (counter kết nối không tăng đúng 1), 12 case khác xanh.
