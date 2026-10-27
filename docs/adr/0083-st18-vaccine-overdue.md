# ADR-0083: ST18 — Care Task quá hạn tái chủng

- **Trạng thái:** Proposed (chờ BE-2 xác nhận số V23; nội dung đã chốt và có test xanh)
- **Ngày:** 2026-10-27
- **Liên quan:** BR-TB-02, 04, 05, BR-KH-05, 08, BR-QT-13; 03 §10 (Care Task#1, #5); ADR-0007 (job), 0057 (cửa sổ vận hành), 0074 / 0075 (ghi / xóa mũi), 0079 (nghĩa vụ `create`), 0082 (khuôn ST04), 0084 (luồng hủy). Nợ: D043 (cập nhật số V).
- **Triển khai (T39 phần 5):** module `visit`. `job/{VaccineOverdueJob, VaccineOverdueProperties}`, `service/VaccineOverdueService`; truy vấn `findOverdueIds`, `findOverdueIdsAfter`, `lockOverdue`, `markOverdueHandled` ở `VaccinationRepository`; migration `V23__vaccination_overdue_flag.sql`. Không endpoint, không `api` mới.

## Quyết định

1. **Một job ở `visit`** (dữ liệu nằm ở `vaccinations`, Care Task chỉ là đầu ra, 06 §7 G2): `app.jobs.vaccine-overdue.cron` = `30 25 7-20 * * *`, mỗi giờ 07:00–20:00, phút 25 để lệch ST04 (phút 10 / 40) và các job 5 phút. `batch-size` 100, `time-budget` 60 s, env `VACCINE_OVERDUE_CRON`, test profile `"-"`.
2. **Ngưỡng** `cutoff = hôm nay − N` theo giờ VN, N = [CFG] `reminder.vaccine_overdue_days` (V2, mặc định 7), đọc một lần mỗi lượt (ADR-0057). Mũi có `next_due_date <= cutoff` là quá hạn, tính cả biên. **Không có cận dưới:** tồn đọng (job tắt lâu, lần chạy đầu sau khi triển khai) vẫn được xét. Đây là điểm khác ST04, vốn bỏ qua ngày đã qua.
3. **V23:** cột `vaccinations.overdue_handled_at` (NULL = chưa xét, không backfill) là cờ "đã xét", đặt cả khi không sinh task. Partial index `ix_vaccinations_overdue_scan (next_due_date, id) WHERE superseded_at IS NULL AND overdue_handled_at IS NULL` chỉ chứa mũi còn chờ xét, nên không lớn theo lịch sử. Entity chỉ đọc cột này, chỉ bulk UPDATE ghi.
4. **Chọn mũi:** chưa bị thay thế, chưa xét, lượt `COMPLETED` (nghĩa vụ ADR-0075). Mũi đã được tiêm lại (`superseded_at`) thì không bao giờ được xét; task quá hạn đã có của mũi cũ bị hủy ở #19 (`cancelForRevaccination`, Care Task#5).
5. **Một mũi một transaction**, READ COMMITTED (bắt buộc cho `ON CONFLICT` của `CareTaskApi.create`), không `catch`, `noRollbackFor` hay `REQUIRES_NEW`. Thứ tự giống ST04: đọc `pet_id` không khóa → `pets FOR SHARE` → mũi `FOR NO KEY UPDATE SKIP LOCKED` (không bao giờ chờ) → đọc projection dưới khóa → UPDATE cờ có điều kiện, **phải đúng 1 dòng** → `CareTaskApi.create`. Task lỗi thì cờ rollback và lượt sau làm lại.
6. **Loại task vô dụng trước khi tạo**, vì cờ đã commit thì không xét lại: thú đã mất → chỉ đặt cờ (log `VACCINE_OVERDUE_PET_DECEASED`); hồ sơ không SĐT (null hoặc trắng) → chỉ đặt cờ (BR-TB-04 v16, log `VACCINE_OVERDUE_NO_PHONE`). Task `VACCINE_OVERDUE`: chi nhánh của chính mũi (= "chi nhánh tiêm gần nhất", bất biến ADR-0074 G3), `customerId` = chủ **hiện tại** của thú (BR-KH-08), `due_date` = `next_due_date` gốc. Tối đa 1 task / mũi nhờ cờ và `UNIQUE (vaccination_id)` + `ON CONFLICT DO NOTHING` (ADR-0079).
7. **Keyset lô sau** `(next_due_date, id) > (mũi afterId)` kèm **cận dưới thừa** `next_due_date >= (next_due_date của afterId)`. Thiếu cận này, Postgres chỉ rút được khoảng hai đầu cho index một cột `ix_vaccinations_next_due` và chọn nó (đọc cả mũi đã xét rồi lọc). Có cận này, hai index được ước lượng như nhau và `ix_vaccinations_overdue_scan` thắng, cả custom plan lẫn generic plan (pgjdbc server-prepare sau 5 lần chạy). Lô đầu và lô sau là hai câu hằng, như ADR-0054.
8. Không audit (BR-QT-15 không liệt kê), không FSM (không đổi trạng thái nào). Tên command: `raiseVaccineOverdueTask`.

## Giả định (chưa PO xác nhận)

- Hồ sơ không SĐT lúc xét thì mũi không được xét lại, kể cả khi sau đó hồ sơ được bổ sung SĐT.
- Mũi quá hạn rất lâu (ví dụ 400 ngày) vẫn sinh task, ở lần chạy đầu cũng vậy.
- Mũi đã xét rồi được khôi phục (xóa mũi mới, ADR-0075) không sinh task lần nữa; mũi khôi phục chưa xét thì sinh đúng 1 task.

## Bảo đảm ↔ test

| Bảo đảm | Test |
|---|---|
| Đúng mũi, biên ngưỡng tính cả biên, chi nhánh / chủ hiện tại / ngày gốc, chỉ cột cờ đổi; thú mất, không SĐT | `VaccineOverdueIT` (`overdueDoseGetsOneTask…`, `cutoffBoundaryIsInclusive`, `onlyUnsupersededUnhandled…`, `profileWithoutPhone…`, `deceasedPet…`, `taskGoesToTheCurrentOwner…`); unit `VaccineOverdueServiceTest` |
| Keyset lô sau đủ, đúng thứ tự, không trùng (hai mũi cùng ngày ở hai trang) | `keysetPagesCoverEveryDoseOnceInDueDateThenIdOrder` |
| Chạy trùng: 2 lượt song song, chạy lại, [CFG] đổi giữa 2 lượt, cùng mũi với ST04, mũi khôi phục, task đã có sẵn, tồn đọng | `twoConcurrentRuns…`, `rerunCreatesNothing…`, `configChange…`, `sameDoseRemindedThenRaised…`, `aRestored…`, `existingOverdueTask…`, `backlogAfterDowntime…` |
| Lỗi trước / lúc commit rollback cả cờ; mũi lỗi vĩnh viễn không chặn mũi sau | `taskFailureRollsBack…`, `flagFailureWritesNothing`, `commitFailure…`, `aPermanentlyFailingDose…`; unit `VaccineOverdueJobTest` |
| Tranh chấp: dòng bị giữ → `SKIPPED` không chờ, lượt sau xử lý; mũi đang bị thay thế không bao giờ được xét | `VaccineOverdueRaceIT` |
| Câu quét / khóa dùng index (20 000 dòng), cả generic plan | `ReminderScanIndexIT` |
| Cron đăng ký đúng, giá trị mặc định | `ReminderScheduleIT`, `VaccineOverduePropertiesTest` |

## Hệ quả, chưa làm

- Lần triển khai đầu sinh task cho mọi mũi đã quá hạn trong lịch sử (không backfill). Dữ liệu dev / demo hiện ít nên chấp nhận được.
- `PICKUP_OVERDUE` (nhánh ST15 của ST18) là việc của BE-2 (T42 / T43), gọi cùng `CareTaskApi.create`.
- Chạy nhiều instance cần ADR mới (ADR-0007 §8); IT chỉ chứng minh lỡ chạy chồng thì dữ liệu vẫn đúng.

## Phương án đã loại

- **Cửa sổ có cận dưới như ST04:** bỏ sót mũi tồn đọng khi job tắt quá N ngày, trái với "tối đa 1 task / mũi" theo nghĩa phải có.
- **Dùng lại `ix_vaccinations_next_due` hoặc cờ chung `due_reminded_at`:** index đọc cả mũi đã xét; cờ chung lẫn ST04 với ST18 (một mũi có cả nhắc lẫn task quá hạn).
- **Backfill cờ cho mũi cũ:** giấu mũi quá hạn thật mà chưa ai gọi.
- **Truyền `afterDate` làm tham số keyset:** phải sửa service / job, và generic plan vẫn không biết giá trị.
