# ADR-0014: ST20 giao thông báo kênh IN_APP vào bảng `notifications`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/01-business-operations.md` ST20, UC88; `docs/02-business-rules.md` BR-TB-02 (khách có tài khoản nhận email + trong app), BR-QT-14 (mẫu, biến bắt buộc), BR-LT-12 ("thông báo ngay" cho khách và lễ tân).
  - `docs/05-erd.md` §11 `notifications`, `notification_outbox`; §13 mục 13 (lệch ghi ở đây).
  - `docs/06-module-contracts.md` §1 (`NotificationApi`), §8 Q4 (mã `_APP`, mỗi mã một kênh); `docs/api/care-v1.md` A1, Q1 (cài đặt nhận thông báo còn TBD).
  - Convention 07 §7.2 (outbox), §7.4 (job tách transaction theo bản ghi); 08 L9, L15.
  - ADR-0007 (job), ADR-0012 (worker email). **Supersedes ADR-0012 mục 1 (chỉ EMAIL) và mục 9 (scheduler 3 thread).** Trả nợ D006.
- **Triển khai:**
  - `BE/src/main/resources/db/migration/V6__in_app_notification_index.sql`.
  - `BE/src/main/java/com/petcare/module/care/entity/Notification.java`, `care/repository/NotificationRepository.java`, `care/service/InAppNotificationDeliveryService.java`, `care/job/InAppNotificationJob.java`.
  - Sửa: `care/repository/NotificationOutboxRepository.java` (`lockNextDueInApp`, `countDueInApp`, `findOldestDueInApp`), `care/service/DeliveryLane.java` (`IN_APP`, `isEmail()`), `care/service/NotificationDispatchService.java` (từ chối lane IN_APP), `care/job/NotificationOutboxRunner.java`, `care/job/NotificationOutboxProperties.java` (`in-app-cron`, `in-app-time-budget`, `lag-warn-in-app`), `care/api/NotificationApi.java` (javadoc).
  - Cấu hình: `application.yml` (`spring.task.scheduling.pool.size: 4`, `app.jobs.notification-outbox.in-app-*`), `application-test.yml`; env `NOTIFICATION_IN_APP_CRON` (docker-compose, `.env.example`).
  - Test (tiêu chí → test):
    - Một dòng outbox → đúng một thông báo, đúng giá trị cột, khóa nhạy cảm bị xóa, chạy lại không tạo thêm: `InAppNotificationIT.enqueuedRowBecomesExactlyOneNotification`; `InAppNotificationDeliveryServiceTest.deliversOneNotificationThenMarksSent`, `typeIsTemplateCodeWithoutAppSuffix`.
    - Use case rollback → không có thông báo: `InAppNotificationIT.rolledBackUseCaseProducesNoNotification`.
    - Không trùng khi chạy song song / dòng đang bị khóa: `InAppNotificationIT.concurrentDeliveryCreatesOneNotification`, `lockedRowIsSkippedThenDeliveredOnce`.
    - Lỗi sau INSERT → rollback cả thông báo, lượt sau giao đúng 1: `InAppNotificationIT.failureAfterInsertRollsBackNotificationToo` (trigger tạm làm UPDATE `SENT` lỗi lúc commit); `InAppNotificationDeliveryServiceTest.databaseErrorPropagatesWithoutChangingRow`.
    - Dòng không giao được → `FAILED`, không chặn dòng sau: `InAppNotificationIT.undeliverableRowsFailWithoutBlockingLaterRows`; `InAppNotificationDeliveryServiceTest` (`missingRecipientAccountFails`, `unknownTemplateFails`, `emailTemplateFails`, `missingRequiredVariableFails`, `templateWithoutSubjectFails`, `linkUrlOverLimitFails`, `typeAtLimitIsDeliveredOverLimitFails`).
    - Tiêu đề > 200 ký tự bị cắt theo code point: `InAppNotificationDeliveryServiceTest.titleOverLimitIsCutWithEllipsis`, `titleIsMeasuredAndCutByCodePoint`; `InAppNotificationIT.longTitleIsDeliveredCutTo200Characters`.
    - Tách khỏi email: `InAppNotificationIT.emailBacklogNeitherBlocksNorIsTouchedByInAppLane`; `NotificationOutboxIT.inAppRowsAreNeverPicked` (job email vẫn bỏ qua IN_APP); `NotificationDispatchServiceTest.inAppLaneIsRejected`.
    - Index V6 được dùng bởi đúng câu SQL của repository (2.000 dòng email `PENDING`): `InAppNotificationIT.inAppQueriesUsePartialIndex`; schema: `SchemaMigrationIT.notificationOutboxHasPartialIndexForInAppLane`.
    - Lượt chạy, ngân sách, bỏ câu đếm khi đã hết dòng, cảnh báo trễ, lỗi không ném: `NotificationOutboxRunnerTest` (`inAppLaneDeliversThroughInAppServiceNeverEmail`, `drainedInAppRunSkipsBacklogQuery`, `inAppBudgetAndLagWarningUseOwnProperties`, `inAppFailureIsLoggedNotThrown`); job và lịch: `NotificationOutboxJobsTest`, `NotificationOutboxScheduleIT`; cấu hình: `NotificationOutboxPropertiesTest`.

## Bối cảnh

ADR-0012 chỉ làm kênh EMAIL; dòng `notification_outbox.channel = 'IN_APP'` không ai chọn, mãi `PENDING` và dồn trong dải `PENDING` mà truy vấn email phải đọc qua (nợ D006). Module đầu tiên gửi `_APP` (ST03, ST04, BR-LT-12, BR-QT-11, ST08, ST13 — 06 §8 Q4) chưa được làm, nên phải có worker IN_APP trước. Khác email, giao trong app không gọi hệ thống ngoài: chỉ INSERT một dòng `notifications` trong cùng DB. Các điểm phải chốt: chạy ở job nào, có trùng không, tải DB, dòng lỗi có chặn luồng không, và lấy `type` / `title` từ đâu (erd §11 không nói).

## Quyết định

1. **Job riêng `InAppNotificationJob`**, cron `*/5 * * * * *` (`NOTIFICATION_IN_APP_CRON`), ngân sách 4 s mỗi lượt, cảnh báo trễ 60 s. Dùng chung `NotificationOutboxRunner` (lane `DeliveryLane.IN_APP`). Không gộp vào job email: SMTP lỗi làm lượt email dừng (`STOP_RUN`) hoặc treo tới ~25 s, và một đợt IN_APP lớn sẽ chiếm ngân sách của OTP.
2. **Scheduler 4 thread** (thay mục 9 của ADR-0012): 5 job (dọn phiên, ST02 tới 60 s, HIGH, NORMAL, IN_APP). Job dùng tối đa 4 connection (Hikari 10).
3. **Mỗi dòng một transaction** (`InAppNotificationDeliveryService.deliverNext`): `SELECT … FOR UPDATE SKIP LOCKED LIMIT 1` dòng IN_APP đến hạn → kiểm → render → INSERT `notifications` → `markSent` → commit. **Exactly-once:** INSERT và `SENT` cùng transaction, không có tác dụng ngoài DB trước commit → không cần `Message-ID`, không thử lại lũy thừa. Lỗi DB thoát ra, rollback, dòng giữ `PENDING`, lượt sau làm lại.
4. **Không có "dòng độc".** Lỗi ràng buộc DB sẽ rollback mà dòng vẫn đứng đầu `ORDER BY next_attempt_at, id`, chặn cả luồng ở mọi lượt. Vì vậy mọi giới hạn của `notifications` được kiểm trước INSERT, và dòng không giao được → `FAILED` ngay: thiếu `recipient_account_id`; mẫu không có hoặc khác kênh `IN_APP`; thiếu biến bắt buộc (BR-QT-14); mẫu không có `subject`; `type` > 40 ký tự; `link_url` > 500 ký tự. Độ dài đếm theo code point như `VARCHAR(n)` của Postgres. FK `account_id` không thể hỏng: `RESTRICT` không cho xóa tài khoản khi outbox còn trỏ tới.
5. **Giá trị cột.** `type` = mã mẫu bỏ hậu tố `_APP` (`APPOINTMENT_REMINDER_APP` → `APPOINTMENT_REMINDER`, khớp ví dụ erd §11; mã không có hậu tố giữ nguyên). `title` = `subject` của mẫu đã render — **mẫu IN_APP bắt buộc có subject**; dài quá 200 ký tự thì cắt còn 199 + `…`. `body` = `body` đã render. `link_url` = `payload.link_url` (`linkUrl` của request). `read_at` = NULL.
6. **Index partial V6** `ix_notification_outbox_in_app_due (next_attempt_at, id) WHERE status = 'PENDING' AND channel = 'IN_APP'`: câu chọn / đếm IN_APP không đọc qua tồn đọng email (index V1 `(status, next_attempt_at)` không có `channel`). Câu SQL viết `status`, `channel` dạng hằng số: với tham số bind, generic plan của prepared statement phía server không chứng minh được predicate của index partial.
7. **Câu đếm tồn đọng chỉ chạy khi lượt dừng vì hết ngân sách.** Lượt kết thúc vì hết dòng (`NONE`) thì tồn đọng là 0 — chỉ một luồng chọn dòng IN_APP. Lúc rảnh mỗi lượt là một transaction chỉ đọc.
8. **Không đọc `accounts.notification_settings`** (care-v1 Q1 còn TBD): module gửi quyết định có `enqueue` hay không. Tài khoản bị khóa / `DISABLED` vẫn nhận (thông báo nằm chờ); tài khoản `PENDING` không bao giờ là người nhận (`NotificationApi`).
9. **Metrics, log** như ADR-0012 mục 11–12 với `lane=IN_APP`; mã log lượt `NOTIFICATION_IN_APP` / `NOTIFICATION_IN_APP_FAILED`; từng dòng DEBUG `NOTIFICATION_DELIVERED id template accountId`, WARN `NOTIFICATION_FAILED`. Không log payload.

## Lý do và phương án bị loại

| Phương án | Lý do loại |
|---|---|
| Gộp IN_APP vào `PriorityNotificationJob` | Không thêm thread nhưng hai luồng ảnh hưởng nhau: đợt IN_APP lớn làm OTP trễ, SMTP treo làm IN_APP trễ tới ~25 s |
| Job riêng nhưng giữ 3 thread | Khi ST02 chạy (tới 60 s), ba luồng thông báo tranh hai thread → OTP / IN_APP trễ thêm vài giây |
| Gộp lô N dòng mỗi transaction | Ít commit hơn nhưng một dòng lỗi rollback cả lô; với vài ms/dòng chưa cần (xem *Hệ quả*) |
| Không thêm index (dùng index V1) | Khi SMTP sập lâu, email tồn N dòng: N = 100.000 → ~50 ms mỗi lần chọn, đợt 500 IN_APP vượt ngân sách |
| Chọn dòng IN_APP bằng `template_code IN (…mã _APP…)` trên index V4 | Phụ thuộc quy ước tên mã và danh sách phải cập nhật tay; quên một mã thì mẫu đó không bao giờ được giao |
| `type` = nguyên mã mẫu (có `_APP`) | Lệch ví dụ erd §11; FE phải biết quy ước đặt mã mẫu |
| Khóa idempotency trên outbox để chống producer `enqueue` hai lần | Không phải việc của worker (ADR-0012: producer chống lặp bằng cờ "đã nhắc" / FSM); cần thêm cột và migration |

## Hệ quả

- Nợ D006 đã trả. Truy vấn của luồng email NORMAL không còn đọc qua dòng IN_APP dồn lại.
- **Trùng lặp:** không phát sinh từ việc tách job — hai tập dòng rời nhau theo `channel` (cột không đổi sau khi ghi); cron không tự chồng; `SKIP LOCKED` + Postgres kiểm lại `WHERE` trên bản mới nhất trước khi khóa; INSERT và `SENT` cùng commit. Sự kiện sinh dòng EMAIL + dòng `_APP`, hoặc N người nhận, là N dòng theo thiết kế.
- **Tải DB:** lúc rảnh 1 transaction chỉ đọc mỗi 5 s (~17.000/ngày). Mỗi dòng giao: 1 SELECT khóa + 1 SELECT mẫu + 1 INSERT + 1 UPDATE, một lần ghi WAL. Đợt 500 dòng ≈ 1–3 s. Trần: một thread, ≤ 1 connection, ngân sách 4 s / 5 s. Nếu metrics cho thấy áp lực ghi, chuyển sang lô N dòng mỗi transaction (ADR mới).
- **Khóa:** INSERT `notifications` kiểm FK lấy `FOR KEY SHARE` trên `accounts`, chỉ chờ khi đúng tài khoản đó đang bị `SELECT … FOR UPDATE` (luồng OTP, vài ms tới ~100 ms). ST02 chỉ khóa tài khoản `PENDING`.
- **Tăng trưởng:** mỗi dòng giao để lại một dead tuple ở outbox (autovacuum; dọn dòng `SENT` là D007) và một dòng `notifications` vĩnh viễn. **TBD:** đặc tả không có thời hạn giữ `notifications`; chốt cùng task UC88.
- **Ràng buộc cho task sau:**
  - Mẫu `_APP` seed cùng PR với module gửi đầu tiên dùng nó (06 §8 Q4) và **phải có `subject`**.
  - UC88 (`/me/notifications…`) đọc `notifications` theo `account_id` (index `(account_id, read_at, created_at DESC)` có sẵn ở V1).
  - Cài đặt nhận thông báo (care-v1 Q1): khi PO chốt, module gửi kiểm trước `enqueue`, worker không đổi.
- **Mutation:** ghi kết quả khi chạy `InAppNotificationIT` (cần Docker) — bỏ `markSent`, bỏ `SKIP LOCKED`, bỏ kiểm `link_url` > 500.
