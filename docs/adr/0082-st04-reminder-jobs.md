# ADR-0082: ST04 — nhắc tái chủng và nhắc tái khám

- **Trạng thái:** Accepted (cùng PR với V22 / ADR-0081; số V22 còn chờ BE-2 xác nhận)
- **Ngày:** 2026-10-27
- **Liên quan:** BR-TB-01, 02, 03, 06, BR-KH-05, 08, BR-QT-13; 03 §10 (Care Task#1); 06 §7 G2, G3, §8 Q1, Q4; ADR-0007 (job), 0012 (outbox, chống gửi trùng), 0056 (khuôn ST03), 0057 (cửa sổ vận hành), 0074 / 0075 (mũi tiêm), 0079 (nghĩa vụ `create`), 0081 (V22). Nợ: D049 (đóng), D029 (cập nhật), D051 (mới).
- **Triển khai (T39 phần 3 + 4):** module `visit`. `job/{VaccineReminderJob, FollowUpReminderJob}` + `*Properties`; `service/{VaccineReminderService, FollowUpReminderService, DueDateWindow, ReminderChannels, ReminderResult}`; truy vấn mới ở `VaccinationRepository`, `MedicalRecordRepository`. Không endpoint, không `api` mới, không migration (V22 đã có mẫu + index).

## Quyết định

1. **Hai job độc lập** (một job một nguồn), `app.jobs.vaccine-reminder.cron` = `30 10 7-20 * * *`, `app.jobs.follow-up-reminder.cron` = `30 40 7-20 * * *`: mỗi giờ từ 07:00 đến 20:00 (không gửi email ban đêm, lượt sau tự thử lại phần lỗi), giây 30 để lệch mọi job 5 phút. `batch-size` 100, `time-budget` 60 s. Env `VACCINE_REMINDER_CRON`, `FOLLOW_UP_REMINDER_CRON`; test profile `"-"`.
2. **Cửa sổ** `[hôm nay, hôm nay + N]` theo giờ VN, cả hai cận tính; N = [CFG] `reminder.vaccine_days_before` (7) / `reminder.follow_up_days_before` (3), đọc mỗi lượt (ADR-0057). **Ngày đã qua không nhắc** (người dùng chốt): quá hạn tái chủng đã có BR-TB-04 (ST18); câu quét có cận dưới nên khớp index và không tốn transaction cho tồn đọng.
3. **Chọn bản ghi:** mũi tiêm `superseded_at IS NULL AND due_reminded_at IS NULL`, lượt `COMPLETED` (nghĩa vụ ADR-0075); bệnh án `follow_up_date NOT NULL`, chưa nhắc, chưa hủy nhắc, `locked_at NOT NULL` (= lượt `COMPLETED`). Index: `ix_vaccinations_next_due`, `ix_medical_records_follow_up_due` (V1).
4. **Một bản ghi một transaction**, READ COMMITTED (bắt buộc cho `ON CONFLICT` của `CareTaskApi.create`), không `catch` / `noRollbackFor` / `REQUIRES_NEW`. Thứ tự: đọc `pet_id` không khóa → `pets FOR SHARE` (`PetQueryApi.findPetForShare`, nghĩa vụ ADR-0079 mục 2: trước dòng nguồn) → dòng nguồn `FOR NO KEY UPDATE SKIP LOCKED` (không bao giờ chờ nên không vào vòng deadlock; `OF v` không khóa `visits`) → đọc projection dưới khóa → **UPDATE cờ có điều kiện, phải đúng 1 dòng** → đầu ra (outbox hoặc Care Task). Cờ ghi trước đầu ra, cùng transaction: đầu ra lỗi thì cờ rollback, lượt sau làm lại.
5. **Kênh theo BR-TB-02** (`ReminderChannels`, bản sao hàm thuần của `AppointmentNoticeTargets.requests`; visit không import được lớp package-private của appointment): tài khoản đã xác thực → `IN_APP` (kèm link) + `EMAIL` tới email tài khoản; tài khoản `PENDING` coi như không có; hồ sơ có email → `EMAIL`; **không kênh nào → Care Task** `VACCINE_DUE` / `FOLLOW_UP_DUE` (chi nhánh: chi nhánh của chính mũi = "tiêm gần nhất" nhờ bất biến ADR-0074 G3; chi nhánh của lượt có hẹn), `customerId` = **chủ hiện tại** của thú (BR-KH-08). Không kênh và không SĐT → không gửi, chỉ đặt cờ (log `*_NO_CHANNEL`).
6. **Thú đã mất (G3):** tái chủng đặt `due_reminded_at` (không có cột hủy riêng); tái khám đặt `follow_up_cancelled_at` (đúng nghĩa "nhắc bị hủy", BR-TB-06). Không gửi, không task.
7. **Link bản `_APP`** (BR-TB-01, 06): tái chủng `/booking?petId=&branchId=&serviceType=VACCINE`, tái khám `/booking?petId=&branchId=`. Bản `EMAIL` không link (hồ sơ tại quầy không đăng nhập được, ADR-0081). FE chưa đọc query: nợ D051.
8. **Biến mẫu** kiểm khác null trước khi đặt cờ (`enqueue` không kiểm; thiếu thì ST20 `FAILED` âm thầm sau commit). Ngày `dd/MM/yyyy`; `loai_vaccine` = tên `VaccineType` (BR-SP-07, loại đã ngừng vẫn nhắc).
9. Không audit (BR-QT-15 không liệt kê nhắc), không FSM. Tên command: `remindRevaccination`, `remindFollowUp`.

## Giả định (chưa PO xác nhận)

- Khách bị khóa tài khoản hoặc còn cờ chờ liên kết vẫn được nhắc (như ST03, ADR-0057).
- Mũi đã nhắc rồi được khôi phục (xóa mũi mới, `restoreLatest`) không nhắc lại (ADR-0075 đã loại việc đặt lại `due_reminded_at`).

## Bảo đảm ↔ test

| Bảo đảm | Test |
|---|---|
| Đúng dòng, cửa sổ, kênh, chủ hiện tại, thú mất, chỉ cột cờ đổi | `VaccineReminderIT`, `FollowUpReminderIT`; unit `*ReminderServiceTest`, `DueDateWindowTest`, `ReminderChannelsTest` |
| Chạy trùng: 2 lượt song song, chạy lại (`xmin` không đổi), [CFG] đổi giữa 2 lượt, 2 loại cùng ngày, mũi khôi phục | `twoConcurrentRuns…`, `rerunSendsNothing…`, `configChange…`, `twoVaccineTypes…`, `aRestoredDose…` |
| Lỗi sau khi đặt cờ → rollback cả cờ, lượt sau đúng 1 bộ đầu ra; outbox `FAILED` không nhắc lại | `outboxFailureRollsBack…`, `taskFailureRollsBack…`, `failedDeliveryIsNotRemindedAgain`; unit `failureAfterTheFlagPropagates…`, `remindIsDefaultTransaction…` |
| Tranh chấp: dòng bị giữ → `SKIPPED` không chờ; #19 song song hủy task vừa sinh; thú mất ↔ ST04 xếp hàng đúng chiều | `VaccineReminderRaceIT` (xác nhận chờ qua `pg_stat_activity`) |
| Mẫu V22 gửi được từ đầu tới cuối qua ST20 | `outboxRowsRenderWithTheV22TemplatesThroughSt20` (GreenMail) |
| Câu quét / khóa dùng index (20 000 dòng) | `ReminderScanIndexIT` |
| Cron đăng ký đúng, mặc định lệch job khác | `ReminderScheduleIT`, `*ReminderPropertiesTest` |

## Hệ quả, chưa làm

- **D029:** từ nay có nhắc tái khám thật, nên thú đã đặt lịch Khám/Tiêm (hoặc có Visit Khám mới) vẫn nhận nhắc cho tới khi nối `FollowUpApi`.
- **ST19 / Q1 (T39 phần sau):** Care Task `OPEN` do ST04 sinh không tự hủy khi thú mất sau đó và không chuyển sang chủ mới. `VaccineReminderRaceIT` ghi nhận thứ tự khóa: ST04 không bao giờ sinh task SAU khi thú đã đánh dấu mất.
- **Tại-nhiều-nhất-một-lần ở ST04:** outbox `FAILED` sau 5 lần thử của ST20 thì khách không được nhắc và không có Care Task thay thế; đặc tả không quy định dự phòng. Dấu vết: outbox `FAILED` (giữ 90 ngày, ADR-0016) và log ST20.
- Ngày đến hạn đã qua mà chưa nhắc (job tắt lâu, phác đồ khoảng cách < N, hoàn tất muộn) không được nhắc nữa; tái chủng còn ST18, tái khám không có gì.
- Chạy nhiều instance cần ADR mới (ADR-0007 §8); IT chỉ chứng minh lỡ chạy chồng thì dữ liệu vẫn đúng.

## Phương án đã loại

- **Một job cho cả hai nguồn:** lỗi hoặc `time-budget` của nguồn này chặn nguồn kia; hai bảng không chung điều kiện quét.
- **Import `AppointmentNoticeTargets` / tách `care.api` dùng chung:** lớp package-private của module khác; mở `api` chung đổi file đang chạy ổn của BE-2 / appointment chỉ để dùng lại ~10 dòng.
- **Nhắc cả ngày đã qua:** quét mọi mũi cũ chưa thay thế, nội dung "sắp đến ngày" sai nghĩa, không có cận dưới để khớp index.
- **Kiểm "đã đặt lịch Khám/Tiêm" lúc gửi:** lặp phần việc của D029 và thêm method vào `AppointmentQueryApi`.
