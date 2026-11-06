# ADR-0112: Job dọn `notifications` hai ngưỡng (trả D023)

- **Trạng thái:** Accepted (hai thời hạn là giả định của nhóm, chưa có PO duyệt)
- **Ngày:** 2026-11-05
- **Liên quan:** UC88, care-v1 #5–#8; 04 L138, erd §11; ADR-0007 (job), 0014 (worker IN_APP), 0016 (dọn outbox, mẫu), 0039 (đọc / đánh dấu đã đọc — **mục 3 và dòng "Hệ quả" về D023 được sửa ở đây**), 0099 (index V32), 0111 (dọn OTP, mẫu). Nợ D023 (trả); mở D057 (FE).
- **Triển khai:**
  - `care/job/{NotificationCleanupJob, NotificationCleanupProperties}`, `care/service/NotificationCleanupService`, `NotificationRepository.{deleteReadBefore, deleteUnreadBefore}`; chú thích `MyNotificationService.markNotificationRead`.
  - `application.yml` (`app.jobs.notification-cleanup`), `application-test.yml` (`cron: "-"`), `docker-compose.yml` / `.env.example` (`NOTIFICATION_CLEANUP_CRON`). Không migration, không endpoint, không [CFG].
  - Test: `NotificationCleanupJobTest`, `NotificationCleanupPropertiesTest`, `NotificationCleanupIT` (era 1984), `NotificationCleanupScheduleIT`, `MyNotificationIT.markReadOfNotificationRemovedByCleanupIs404`.

## Bối cảnh

Worker ST20 IN_APP ghi một dòng `notifications` cho mỗi thông báo (≥ 12 nơi sinh: khóa tài khoản, ST03, ST04, lưu trú, hủy do phòng khám, tự chốt ca, tổng hợp Order `PENDING`, cảnh báo tồn kho); không job nào xóa. Người quản lý nhận tổng hợp hằng ngày dù đọc hay không; khách ít dùng app không bao giờ đọc nên chỉ dọn "đã đọc" bỏ sót nguồn tăng chính. Bảng và 2 index tăng mãi; `findMine` đếm `count(*)` toàn bộ dòng của tài khoản ở mỗi request. Đặc tả (01 UC88, 02, 04, 05) không quy định thời hạn giữ thông báo.

## Quyết định

1. Job `NotificationCleanupJob` hằng ngày, hai lượt tuần tự theo lô 1000, mỗi lô một transaction (`DELETE … WHERE id IN (SELECT id … ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED)`):
   - **đã đọc** (`read_at IS NOT NULL`) có `created_at < now − 30 ngày`;
   - **chưa đọc** (`read_at IS NULL`) có `created_at < now − 90 ngày`.
2. Cả hai mốc tính từ `created_at` (một đồng hồ, câu chọn đi theo id tăng dần). Hệ quả chấp nhận: thông báo tạo 31 ngày trước mà người dùng vừa đọc hôm nay vẫn bị xóa đêm đó.
3. `time-budget` 60 s dùng chung cho cả hai lượt; hết thì dừng, lượt sau làm tiếp. Lỗi log `NOTIFICATION_CLEANUP_FAILED`, không ném. Không audit (BR-QT-15 không liệt kê thông báo).
4. Tham số `app.jobs.notification-cleanup.{cron, read-retention-days, unread-retention-days, batch-size, time-budget}` là property, không phải [CFG] (như ADR-0016 mục 5). `@AssertTrue`: `unread > read`, nếu không dòng chưa đọc bị xóa sớm hơn dòng đã đọc; app không khởi động khi sai.
5. Cron `23 50 3 * * *` (03:50:23 VN): sau dọn outbox 03:40, giây 23 lệch ST20 (giây chia hết cho 5) và các job `*/5`.
6. Không thêm index (ADR-0016 mục 7).

## Hệ quả cho người dùng và code khác

- **#7 có thêm đường 404:** bấm vào thông báo vừa bị xóa trong đêm → `markRead` trả 0, `existsByIdAndAccountId` false → 404 "Không tìm thấy thông báo #id" (contract đã khai 404). **Sửa ADR-0039 mục 3:** không còn đúng "không ai xóa `notifications`"; kết quả vẫn không sai vì dòng vừa UPDATE thành công đang bị khóa tới commit, còn dòng đã đọc bị xóa giữa hai câu cho 404 thay vì 204.
- **Số chưa đọc trên chuông (#6) có thể giảm sau một đêm** khi dòng chưa đọc quá 90 ngày bị xóa.
- **FE:** `useNotificationWrite.onError` chỉ toast, không làm mới danh sách → dòng đã mất còn nằm tới lần tải kế tiếp. Nợ D057 cho FE-1 (làm mới `LIST` / `UNREAD` khi 404).
- Hai mốc là giả định; PO đổi thì chỉ sửa property, không đổi code.

## Đồng thời

Job chỉ khóa `notifications` trong một câu `SKIP LOCKED`, không xin `accounts`, không chờ khóa của ai: không có chu trình với `markRead` (UPDATE một dòng), `markAllRead` (UPDATE nhiều dòng của một tài khoản) hay ST20 (INSERT dòng mới, `created_at = now` nên không bao giờ khớp). Bên giữ khóa thắng; dòng bị bỏ qua được xóa đêm sau. Câu chọn đọc lại `read_at` dưới khóa (READ COMMITTED): dòng vừa được đọc không bị lượt "chưa đọc" xóa, mà đi theo mốc 30 ngày của lượt "đã đọc". Không bảng nào trỏ tới `notifications`. Chạy lại hoặc song song vô hại.

## Phương án bị loại

| Phương án | Vì sao không |
|---|---|
| A. Chỉ xóa "đã đọc" | Dòng chưa đọc (nguồn tăng chính) vẫn tăng mãi |
| B. Xóa mọi dòng cũ bất kể đã đọc | Có thể xóa cảnh báo chưa ai thấy, một số không có bản email đi kèm |
| D. Giữ K dòng mới nhất mỗi tài khoản | `ROW_NUMBER` theo tài khoản nặng; thông báo biến mất theo số lượng chứ không theo thời gian |
| E. Phân vùng theo tháng | Đổi khóa chính + migration lớn, quá nặng |
| F. Chấp nhận không dọn | Bảng vẫn tăng; đã chọn trả nợ |
| [CFG] cho hai mốc | Cần `ConfigKey` + seed migration (số V chung với BE-2) + ràng buộc cặp khóa; ADMIN không có nhu cầu |
| Mốc "đã đọc" theo `read_at` | `read_at` không tăng theo id, câu quét kém tối ưu, thêm ca kiểm thử |

## Kiểm

2026-11-05: unit `NotificationCleanup*Test` 17/17; `NotificationCleanupIT` 15 + `NotificationCleanupScheduleIT` 1 xanh; hồi quy không sửa 258/258 xanh (cả nhóm `module/care`: `MyNotificationIT` 26 gồm test 404 mới, `InAppNotificationIT`, `NotificationOutboxCleanupIT`, `NotificationOutboxIT`, `CareTask*IT`; `AppointmentReminderIT`, `VaccineReminderIT`, `FollowUpReminderIT`, `OpenApiContractParityIT`, `SchemaMigrationIT`). Chưa đo `EXPLAIN` trên ≥ 20 000 dòng.
