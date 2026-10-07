# ADR-0016: Dọn `notification_outbox` đã xử lý

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/01-business-operations.md` ST20; `docs/04-domain-model.md` §11 (`NotificationOutbox` — LOG), nguyên tắc 2 (không xóa cứng dữ liệu nghiệp vụ).
  - `docs/05-erd.md` bảng `notification_outbox`, §13 mục 11.
  - `docs/convention/backend/07-transaction-management.md` §7.4, `08-logging-and-audit.md` L15.
  - ADR-0007 (job định kỳ), ADR-0008 (mẫu job dọn), ADR-0012 (worker email), ADR-0014 (IN_APP). Nợ D007 (trả bởi ADR này).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/care/job/{NotificationOutboxCleanupJob, NotificationOutboxCleanupProperties}.java`.
  - `BE/src/main/java/com/petcare/module/care/service/NotificationOutboxCleanupService.java`.
  - `BE/src/main/java/com/petcare/module/care/repository/NotificationOutboxRepository.java` (`deleteProcessedBefore`).
  - `application.yml` (`app.jobs.notification-outbox-cleanup`), `application-test.yml` (`cron: "-"`), `docker-compose.yml` / `.env.example` (`NOTIFICATION_OUTBOX_CLEANUP_CRON`).
  - Test (tiêu chí → test):
    - Xóa `SENT` > 30 ngày, `FAILED` > 90 ngày, cả hai kênh; giữ `PENDING` dù cũ; không audit: `NotificationOutboxCleanupIT.deletesProcessedRowsBeyondRetentionAndKeepsTheRest`, `rowExactlyAtCutoffIsKept`, `statusFilterIsExact`, `pendingCannotBeCleanedEvenByMistake`.
    - Theo lô, mỗi lô một transaction: `NotificationOutboxCleanupIT.deletesInBatchesOfAtMostLimit`, `failedBatchKeepsEarlierBatchesDeleted`; `NotificationOutboxCleanupJobTest.jobItselfIsNotTransactional`.
    - Không chờ dòng đang bị khóa: `NotificationOutboxCleanupIT.skipsRowsLockedByConcurrentTransaction`.
    - Lịch, zone, cấu hình, log, MDC: `NotificationOutboxCleanupJobTest`, `NotificationOutboxCleanupPropertiesTest`, `NotificationOutboxCleanupScheduleIT`, `NotificationOutboxCleanupIT.notScheduledInTestProfile`.

## Bối cảnh

Worker ST20 chuyển dòng outbox sang `SENT`/`FAILED` nhưng không xóa (ADR-0012 *Hệ quả*); bảng và index chỉ tăng. Đặc tả không có thời hạn giữ outbox (khác `audit_logs` 2 năm — BR-QT-16). Kiểm tra trước khi quyết định (2026-10-07): không bảng nào có FK tới `notification_outbox`; không endpoint `docs/api` nào đọc outbox; nội dung người dùng thấy nằm ở email đã gửi hoặc bảng `notifications` (IN_APP); payload đã bị xóa khóa nhạy cảm khi `SENT`/`FAILED`.

## Quyết định

1. **Outbox là dữ liệu kỹ thuật của việc gửi**, không phải dữ liệu nghiệp vụ theo 04 nguyên tắc 2 (như `sessions` ở ADR-0008).
2. **Thời hạn giữ** (người dùng chọn 2026-10-07): `SENT` 30 ngày, `FAILED` 90 ngày (giữ lâu hơn để điều tra), tính từ `created_at`. Không có cột thời điểm kết thúc cho `FAILED`; thử lại tối đa ~15 phút nên lệch so với lúc kết thúc không đáng kể. Áp cho cả kênh EMAIL và IN_APP. **Không bao giờ** xóa `PENDING` (service từ chối cả khi bị gọi nhầm).
3. Chạy hằng ngày **03:40** giờ Việt Nam (lệch 03:00 của dọn phiên và các mốc 15 phút của ST02 để không tranh 4 thread scheduler); env `NOTIFICATION_OUTBOX_CLEANUP_CRON`.
4. Mỗi lô 1000 dòng một transaction: `DELETE … WHERE id IN (SELECT id … WHERE status = :status AND created_at < :cutoff ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)`; `SENT` trước rồi `FAILED`; `now` chốt một lần mỗi lượt.
5. Tham số `app.jobs.notification-outbox-cleanup.{cron, sent-retention-days, failed-retention-days, batch-size}` là property ứng dụng, không phải [CFG].
6. **Không audit** (ngoài BR-QT-15). Log INFO `NOTIFICATION_OUTBOX_CLEANUP sent=… failed=… sentCutoff=… failedCutoff=… durationMs=…`; lỗi → ERROR `NOTIFICATION_OUTBOX_CLEANUP_FAILED sentSoFar=… failedSoFar=…`, không ném.
7. **Không thêm index** theo `created_at` (như ADR-0008 mục 7).
8. **Không dọn `notifications`**: thời hạn giữ thông báo trong app là TBD của UC88 (ADR-0014 *Hệ quả*).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Một thời hạn chung | Dòng `FAILED` là thứ cần điều tra (sai địa chỉ, mẫu lỗi), nên giữ lâu hơn |
| Mốc theo `sent_at` | `FAILED` không có cột tương ứng; thêm cột phá quy tắc bảng LOG (`SchemaMigrationIT`) mà gần như không khác `created_at` |
| Thời hạn là [CFG] | Cần migration seed (chung số với BE-2); ADMIN không có nhu cầu đổi |
| Index `(created_at) WHERE status IN ('SENT','FAILED')` | Thêm chi phí cho mỗi lần worker đổi trạng thái; câu chọn đi theo PK tăng dần nên lô đầu tìm ngay dòng cũ nhất |
| Gộp vào worker ST20 | Worker chạy 5–10 s/lần với ngân sách thời gian ngắn; việc dọn hằng ngày tách riêng dễ quan sát hơn |

## Hệ quả

- Nợ D007 đã trả. Bảng outbox bị chặn ở khoảng 30–90 ngày dữ liệu.
- **Tải DB:** lượt hằng ngày; mỗi lô một DELETE ≤ 1000 dòng. Lô cuối (thiếu) có thể quét tới cuối bảng một lần mỗi trạng thái mỗi ngày — chấp nhận vì bảng bị chặn kích thước.
- **Không gây gửi trùng / không chặn worker:** worker chỉ khóa dòng `PENDING`, job chỉ chạm `SENT`/`FAILED` và `SKIP LOCKED`; id IDENTITY không tái dùng nên `Message-ID` (ADR-0012 mục 4) không trùng sau khi xóa.
- Điều tra sự cố gửi thư cũ hơn 30 ngày (`SENT`) / 90 ngày (`FAILED`) không còn dữ liệu outbox; tăng `*-retention-days` nếu cần.
- **Đã kiểm 2026-10-07:** `mvn clean verify` xanh; mutation bỏ `SKIP LOCKED` ở `deleteProcessedBefore` → `NotificationOutboxCleanupIT.skipsRowsLockedByConcurrentTransaction` đỏ (treo quá 5 s), các case khác xanh.
