# ADR-0007: Chạy job định kỳ bằng Spring `@Scheduled` trên một instance

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-06
- **Liên quan:**
  - `docs/01-business-operations.md` mục B (ST01–ST21; tầng 3 không làm).
  - Convention 07 §7.4 (job định kỳ), 08 §8.1 (log mỗi lượt chạy), 01 (package), 03 (tên).
  - ADR-0001 (actor hệ thống khi ghi audit từ job), ADR-0008 (job đầu tiên: dọn phiên).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/config/SchedulingConfig.java`, `TimeConfig.java` (`BUSINESS_ZONE_ID`).
  - Job đầu tiên: `BE/src/main/java/com/petcare/module/identity/job/SessionCleanupJob.java`.
  - `BE/src/main/resources/application.yml`, `BE/src/test/resources/application-test.yml` (`app.jobs.*`).
  - Test: `SessionCleanupJobTest`, `SessionCleanupScheduleIT`, `SessionCleanupIT`.

## Bối cảnh

Đặc tả có khoảng 14 tác vụ định kỳ tầng 1–2: ST02 dọn tài khoản `PENDING`, ST03 nhắc lịch, ST04 nhắc tái chủng/tái khám, ST05 `NO_SHOW`, ST08 cảnh báo kho, ST13 tự chốt ca, ST15 `OVERDUE`, ST20 gửi outbox… Chúng chia cho cả BE-1 và BE-2. Convention 07 §7.4 yêu cầu chốt bằng ADR khi cài job đầu tiên: cách chia transaction, lịch chạy, chống chạy trùng khi có nhiều instance. Job đầu tiên là job dọn phiên (ADR-0008).

Hiện trạng: `docker-compose.yml` chạy đúng một container `backend`, không có `replicas`. Chưa có `@Scheduled`, `@Async` hay bean `Executor` nào.

## Quyết định

1. **Cơ chế:** Spring `@EnableScheduling` ở `platform/config/SchedulingConfig`. Dùng scheduler mặc định của Spring Boot (1 thread): các job chạy tuần tự, một job không chồng lên chính nó.
2. **Vị trí và tên:** job đặt ở `module/<m>/job/{Việc}Job`, thuộc module sở hữu dữ liệu. Job chỉ tính thời điểm bằng bean `Clock`, gọi service, rồi log. Không có logic nghiệp vụ, không `@Transactional`.
3. **Lịch:** `@Scheduled(cron = "${app.jobs.<job>.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)`.
   - Cron 6 trường theo giờ Việt Nam; `"-"` để tắt.
   - Tham số vận hành của job (cron, kích thước lô…) đặt ở `app.jobs.<job>.*`, là record `@ConfigurationProperties` + `@Validated`, đặt cạnh job. Sai giá trị thì app dừng lúc khởi động.
   - Con số nghiệp vụ có trong 02 (ví dụ 30 phút của ST05) vẫn đọc qua `SystemConfigApi` (ADR-0004), không đưa vào `app.jobs`.
4. **Transaction:** nằm ở service, theo từng bản ghi hoặc từng lô (convention 07 §7.4). Lỗi ở một bản ghi hoặc lô không rollback những gì đã commit.
5. **Lỗi:** job bắt `RuntimeException`, log `ERROR <JOB>_FAILED` kèm số đã xử lý, không ném ra scheduler. Lượt sau xử lý bù, nên điều kiện chọn bản ghi phải dựa trên trạng thái hoặc thời điểm, không dựa trên "kể từ lượt trước".
6. **Log:** mỗi lượt đặt MDC `traceId = "job-<uuid>"` và gỡ trong `finally`. Mỗi lượt có một dòng INFO `<JOB> …` kèm số bản ghi (convention 08 §8.1).
7. **Audit:** chỉ khi đặc tả bắt ghi; job không có principal nên actor là hệ thống (ADR-0001).
8. **Chống chạy trùng:** giả định **một instance**. Mọi job phải **idempotent**: chạy lại hoặc chạy chồng không sai dữ liệu, ví dụ UPDATE có điều kiện trạng thái, hoặc `FOR UPDATE SKIP LOCKED` khi chọn lô. Muốn chạy nhiều instance thì phải có ADR mới, thêm khóa phân tán (ShedLock).
9. **Test:** `application-test.yml` đặt `cron: "-"` cho mọi job, để không job nào tự chạy trong `@SpringBootTest`. IT gọi job hoặc service trực tiếp. Mỗi job có một kiểm tra rằng nó thật sự được đăng ký với scheduler khi có cron (mẫu: `SessionCleanupScheduleIT`).

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| ShedLock ngay từ đầu | Thêm dependency và bảng `shedlock` (migration chung số version với BE-2) cho một vấn đề chưa tồn tại (1 instance). Thêm sau không phải sửa job, chỉ thêm annotation |
| Advisory lock tự viết (`pg_try_advisory_lock`) | Khóa gắn với connection, trong khi job chia nhiều transaction (mỗi lô một connection), nên phải tự giữ connection riêng: dễ sai, khó test |
| Quartz | Nặng (bảng riêng, cấu hình), vượt nhu cầu: không cần lịch động, không cần lưu trạng thái trigger |
| Cron ngoài (crontab / k8s CronJob / pg_cron) gọi endpoint | Thêm bề mặt tấn công (endpoint nội bộ), tách logic khỏi app, môi trường dev và test khó tái hiện |

## Hệ quả

- **Tích cực:**
  - Không thêm dependency hay migration.
  - Mọi job chung một mẫu: vị trí, cách đặt lịch, cách tắt, transaction, log.
- **Đánh đổi đã chấp nhận:**
  - Chạy nhiều instance thì mỗi instance đều chạy job. Job idempotent nên dữ liệu vẫn đúng, nhưng có thể gửi trùng thông báo (ST20) → phải có ADR mới trước khi scale ngang.
  - Scheduler 1 thread: một job chạy lâu làm job khác trễ lịch. Khi có job nặng (ST20 gửi mail) cần xem lại `spring.task.scheduling.pool.size`.
  - App tắt đúng giờ chạy thì bỏ lượt đó. Chấp nhận được vì job xử lý bù ở lượt sau (quyết định 5).
- **Ràng buộc cho các task sau (ST của BE-1 và BE-2):**
  - Theo đúng quyết định 2–9; thêm `app.jobs.<job>.cron` vào `application.yml` và `cron: "-"` vào `application-test.yml`.
  - Job đổi trạng thái theo FSM vẫn đi qua service và TransitionHandler của aggregate, kèm hệ quả liên aggregate trong cùng transaction của bản ghi đó.
