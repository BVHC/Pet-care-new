# ADR-0056: ST03 nhắc lịch hẹn (`remindAppointment`) — cửa sổ `(now, now + H]`, một lịch một transaction, `SKIP LOCKED`, UPDATE có điều kiện hai cột, kênh BR-TB-02, ràng buộc chéo nhắc trước mốc hủy muộn

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-19
- **Đánh số lại:** ban đầu là ADR-0051; đổi thành số hiện tại ngày 2026-10-20 khi gộp T25 lên `dev`, vì ADR-0045…0050 (lịch hẹn T16, +4) và ADR-0051 (BE-2) đã chiếm số. Mọi tham chiếu ADR-0047…0052 của T25 trong code / tài liệu đã đổi theo (+5). Ngoại lệ: comment trong `V17__seed_appointment_clinic_cancel_templates.sql` (0047 = ADR-0052) và `V18__seed_appointment_reminder_templates.sql` (0051 = ADR-0056) giữ số cũ, vì sửa migration đã chạy làm đổi checksum Flyway.
- **Liên quan:**
  - BR-LH-12 (02 L133), BR-LH-07 (L128), BR-LH-06 (L127), BR-LH-03 (L124), BR-QT-11 (L54), BR-QT-13 (L56), BR-TB-02 (L250), BR-TB-05 (L253), BR-KH-05, BR-TK-19.
  - 01 ST03 (L216); 03 §3 (ST03 không đổi trạng thái nên không có dòng chuyển); 05 `appointments.reminded_at` (L487).
  - `docs/06-module-contracts.md` §8 Q4 L243 (khách không email: không tự động gửi), L256 (mẫu `APPOINTMENT_REMINDER`); convention 07 §7.4.
  - ADR-0007 (job), 0012 / 0014 / 0017 (ST20), 0015 (`updated_at` của bulk update), 0036 §1, §4 (ràng buộc chéo [CFG]), 0037 (ngữ pháp biến mẫu), 0049 Q9 (đổi khung xóa `reminded_at`), 0052 (người nhận thông báo lịch hẹn), 0053 / 0054 (khuôn ST05, keyset, bẫy `LocalTime`).
  - Nợ: mở D039 (giả định chờ PO); cập nhật D025 (ST03 là job tiêu thụ đầu tiên), D023 (bảng `notifications` lớn nhanh hơn).
- **Bổ sung:** ADR-0036 §1 — thêm cặp ràng buộc chéo thứ ba (mục 8). Không sửa ADR-0036.
- **Triển khai (T25 phần 4):**
  - `appointment/job/{AppointmentReminderJob, AppointmentReminderProperties}`.
  - `appointment/service/{AppointmentReminderService, ReminderWindow, AppointmentReminderNotices, AppointmentNoticeTargets}`; `ClinicCancellationNotices` dùng `AppointmentNoticeTargets` (không đổi hành vi).
  - `AppointmentRepository.{findDueForReminderIds, findDueForReminderIdsAfter, lockDueForReminder, findReminderRow, markRemindedIfDue}`.
  - `identity/service/ConfigConstraints` thêm cặp `late_cancel_hours < reminder_hours_before`.
  - `V18__seed_appointment_reminder_templates.sql`.
  - `application.yml` `app.jobs.appointment-reminder` (cron `0 2/5 * * * *`, `batch-size` 100, `time-budget` 60s); `application-test.yml` cron `"-"`; `APPOINTMENT_REMINDER_CRON` trong `docker-compose.yml` / `.env.example`.
  - Không endpoint, không DDL.

## Bối cảnh

BR-LH-12: hệ thống nhắc khách trước giờ hẹn 24 giờ [CFG] `appointment.reminder_hours_before`, "tức là trước mốc hủy muộn" (BR-LH-07, 12 giờ [CFG]), để khách kịp hủy / đổi mà không bị tính hủy muộn; gửi lỗi thì ST20 thử lại. Cột `appointments.reminded_at` (V1) và việc đổi khung xóa cột này (ADR-0049 Q9) đã có; chưa có job, mẫu thông báo, quy tắc người nhận cho thông báo này.

## Quyết định

1. **Cửa sổ `(now, now + H]`** theo giờ Việt Nam (không DST), `ReminderWindow`: cận trên tính là được (đúng H giờ trước là đến hạn), cận dưới **loại trừ** — lịch đã bắt đầu không nhắc, kể cả khi ST05 chưa chuyển nó sang `NO_SHOW` (trên app thật ST05 còn rollback ở D033 nên lịch quá giờ có thể nằm `BOOKED` mãi) — Q4. Hai mốc cắt về µs như cột. Dưới khóa lấy `now` mới và kiểm lại "chưa bắt đầu": một lượt dài tối đa 60 s, lịch bắt đầu giữa lượt thì bỏ qua.
2. **Lịch đặt / đổi khung khi đã ở trong cửa sổ vẫn được nhắc** ở lượt kế tiếp (lễ tân đặt cho chiều nay, khách đặt đúng mốc 24 giờ) — Q2, khớp ADR-0049 Q9. Không lọc theo `created_at`.
3. **[CFG] đọc lúc chạy**, một lần mỗi lượt (giả định A1, như ADR-0053 §2 — chờ PO cùng D036; ghi D039).
4. **Một lịch một transaction** (convention 07 §7.4); job không `@Transactional`; READ COMMITTED; không `noRollbackFor`, không `try/catch` trong service. Một lịch lỗi được log `APPOINTMENT_REMINDER_FAILED`, lượt sau thử lại (điều kiện chỉ dựa trên `status`, `reminded_at`, khung).
5. **Thứ tự trong `remind`:** chặn transaction readOnly → `lockDueForReminder` (`BOOKED` + `reminded_at IS NULL` + trong cửa sổ, `FOR NO KEY UPDATE SKIP LOCKED`, `MANDATORY`) → projection `ReminderRow` đọc sau khóa → kiểm lại cửa sổ (sai → `IllegalStateException`) → `now` µs → chưa bắt đầu? → `markRemindedIfDue` → ghi outbox cùng transaction. Đặt `reminded_at` cùng transaction với `enqueue` là điều kiện để không nhắc trùng; outbox lỗi rollback cả mốc, lượt sau nhắc lại.
6. **Ghi bằng UPDATE có điều kiện hai cột, không qua entity** (Q7): `UPDATE appointments SET reminded_at = :at, updated_at = :at WHERE id = :id AND status = 'BOOKED' AND reminded_at IS NULL`, phải trả đúng 1 dòng. `Appointment` không có `@Version` / `@DynamicUpdate`, Hibernate ghi mọi cột: entity đọc trước khóa rồi `save` sẽ ghi lại khung cũ đè lên lần đổi khung vừa commit. Câu có điều kiện chặn điều đó bằng cấu trúc, không dựa vào thứ tự gọi. Bulk update tự đặt `updated_at` (ADR-0015). Không thêm method ghi vào entity.
7. **Người nhận theo BR-TB-02** qua helper dùng chung `AppointmentNoticeTargets` (tách từ `ClinicCancellationNotices`, Q5): tài khoản đã xác thực → `IN_APP` + `EMAIL` tới email tài khoản; tài khoản `PENDING` → như không có tài khoản; hồ sơ tại quầy có email → `EMAIL`; **không kênh nào → không gửi, không sinh Care Task** (BR-TB-02 chỉ áp cho tái chủng / tái khám; BR-TB-05 không có loại cho lịch hẹn; 06 §8 Q4 L243), vẫn đặt `reminded_at`, log INFO `APPOINTMENT_REMINDER_NO_CHANNEL` — Q3. Tài khoản đang `is_locked` vẫn nhận (A2, như ADR-0052; BR-QT-11 giữ lịch để xử lý tại quầy); cờ `link_decision_pending` không chặn (A3).
8. **Ràng buộc chéo `appointment.late_cancel_hours < appointment.reminder_hours_before`** (strict) thêm vào `ConfigConstraints` (Q9): BR-LH-12 chỉ có nghĩa khi nhắc đến trước mốc hủy muộn; trước đây ADMIN đặt được nhắc 6 giờ / hủy muộn 12 giờ. UC10 trả BR-QT-13 kèm khoảng hiệu lực, khóa cả cặp theo key tăng dần (ADR-0036 §4); app từ chối khởi động nếu DB vi phạm. Seed V2 (12 < 24) hợp lệ. Lịch được tạo khi đã qua mốc hủy muộn (lễ tân đặt sát giờ) vẫn được nhắc sau mốc — đúng, vì lịch sinh ra lúc đó.
9. **Dữ liệu lệch — lịch `BOOKED` mà thú đã mất** (Q10): không gửi, vẫn đặt `reminded_at`, log WARN `APPOINTMENT_REMINDER_PET_DECEASED`; không tự hủy (việc của listener ADR-0052, đã hủy mọi `BOOKED` khi đánh dấu thú mất).
10. **Mẫu `APPOINTMENT_REMINDER` (EMAIL, luồng NORMAL) + `_APP` (IN_APP, link `/appointments`)**, seed V18. Biến: `ten_khach` không bắt buộc; `ten_thu_cung`, `dich_vu`, `chi_nhanh`, `dia_chi_chi_nhanh`, `so_dien_thoai_chi_nhanh`, `gio_hen` (`HH:mm dd/MM/yyyy`), `ma_lich_hen` (`appointments.code`) bắt buộc, kiểm khác `null` trong transaction vì `enqueue` không kiểm. Tên thú / dịch vụ / chi nhánh là giá trị hiện tại (dịch vụ đã ngừng vẫn có tên — lịch đã đặt vẫn hiệu lực, BR-LH-03).
11. **Quét theo keyset `(slot_date, slot_start, id)`** đúng thứ tự `ix_appointments_status_slot` (khuôn ADR-0054), hai câu hằng; lô sau lấy khung của id cuối bằng subquery. Giờ truyền dạng `LocalTime.toString()` + `CAST(:t AS time)`. Không index mới.
12. **Cron mặc định `0 2/5 * * * *`** (Q11): lệch phút với ST05 (`0 */5`) và ST02 (`0 */15`). Scheduler có 4 thread; ba job dài (mỗi job ≤ 60 s khi tồn đọng) cùng phút sẽ chỉ còn 1 thread cho ST20 HIGH (OTP) / NORMAL / IN_APP. Không đổi pool (ADR-0014). Job dài mới phải chọn phút lệch.
13. **Không khóa thú / chi nhánh, không audit, không FSM, không sự kiện, không endpoint.** Tên command `remindAppointment`.

## Đồng thời trên cùng dòng (đã chứng minh)

Mọi luồng khác ghi `appointments` khóa dòng bằng câu chờ (`findByIdForUpdate`, `findAllByIdInForUpdate`) hoặc `SKIP LOCKED` (ST05) và đọc entity sau khóa. ST03 chỉ giữ một khóa dòng, lấy bằng `SKIP LOCKED`, nên không bao giờ chờ khóa dòng → không thể nằm trong vòng deadlock; đọc khách / thú / dịch vụ / chi nhánh / tài khoản là SELECT thường; INSERT outbox chỉ lấy `FOR KEY SHARE` trên mẫu (không bao giờ bị xóa) và tài khoản (không bao giờ là `PENDING`). Không có I/O ngoài trong transaction. Kết quả từng cặp, cả hai thứ tự (`AppointmentReminderIT`):

| Cặp | Luồng kia giữ dòng trước | ST03 giữ dòng trước |
|---|---|---|
| Hủy (khách / lễ tân) | ST03 bỏ qua ngay; không bao giờ nhắc | Hủy chờ ms rồi chạy trên dòng mới: `CANCELLED`, hủy muộn đúng, giữ `reminded_at`; 1 nhắc |
| Đổi khung | ST03 bỏ qua; lượt sau nhắc khung mới | Đổi khung chờ, xóa mốc, khung mới không bị ghi đè; lượt sau nhắc lại (2 nhắc) |
| Check-in | ST03 bỏ qua; không nhắc | Check-in chờ rồi thành công; nhắc đã gửi |
| Hủy do phòng khám (thú mất, ngày nghỉ) | ST03 bỏ qua; chỉ có thông báo hủy | Listener chờ rồi hủy; 1 nhắc + 1 thông báo hủy; bên phát vẫn 200/201 |
| ST05 | Không bao giờ cùng đủ điều kiện (`slot > now` vs `slot + phút < now`) — `ReminderWindowNoShowParityTest` | idem |
| Hai lượt ST03 | Lượt sau bỏ qua ở câu khóa | idem |

## Phương án đã loại

- **Một lô một transaction:** ít câu SQL hơn nhưng một dòng dữ liệu hỏng rollback cả lô và kẹt ở mọi lượt sau.
- **Ghi `reminded_at` qua entity (`markReminded` + `saveAndFlush`, khuôn ST05):** an toàn chỉ nhờ "khóa rồi mới đọc"; một lần sửa sau này đọc entity trước khóa là mất cập nhật của đổi khung.
- **Sinh Care Task gọi điện cho khách không có email:** không có trong BR-TB-05; 06 §8 Q4 nói "không tự động gửi". Chờ PO nếu cần (D039).
- **Không chặn dưới (chỉ `slot − H ≤ now`):** nhắc cả lịch đã qua khi ST05 lỗi / ngừng.
- **Bỏ qua lịch đặt / đổi khung khi đã trong cửa sổ:** khách vừa thao tác nên biết giờ, nhưng đổi khung không có cột thời điểm riêng; user chọn nhắc (Q2).
- **Tăng pool scheduler lên 5:** ảnh hưởng mọi job, thêm một connection có thể bị chiếm; lệch phút là đủ.
- **Đọc cài đặt nhận thông báo (D025):** PO chưa chốt nội dung; trả muộn nhất cùng ST04 (Q1).

## Hệ quả

- Khách nhận email + chuông nhắc lịch; chưa tắt được (D025).
- ADMIN không đặt được `late_cancel_hours ≥ reminder_hours_before` nữa (UC10 400 BR-QT-13).
- Nhắc commit rồi lịch bị hủy ngay sau: email nhắc có thể tới **sau** khi hủy, trễ ≤ chu kỳ ST20 (10 s); outbox không biết lịch đã hủy, thông báo hủy luôn tới sau (A4, chấp nhận — D039).
- **Lần triển khai đầu** có một đợt nhắc dồn: mọi lịch `BOOKED` trong 24 giờ tới cùng lúc (vài trăm); các email luồng NORMAL khác (`PASSWORD_CHANGED`, `LOGIN_LOCKED_WARNING`, hủy do phòng khám…) có thể trễ vài phút trong đợt này. OTP đi luồng HIGH, không bị ảnh hưởng. Ổn định: mỗi 5 phút ≈ số lịch bắt đầu trong 5 phút đó.
- Backlog sau khi job ngừng bị chặn bởi cửa sổ: tối đa = số lịch `BOOKED` trong H giờ tới; mỗi lịch khoảng 7 câu SQL ngắn; ngân sách 60 s mỗi lượt.
- Bảng `notifications` lớn nhanh hơn (D023 vẫn mở).
- ST03 không gọi `BoardingQueryApi` nên chạy được trên app thật ngay (không dính D033).

## Kiểm chứng

- Unit: `ReminderWindowTest` (7), `ReminderWindowNoShowParityTest` (1, vét cạn biên), `AppointmentReminderServiceTest` (11: thứ tự khóa → đọc → UPDATE → outbox, không bao giờ `save` / `saveAndFlush` / `findById`, lịch bắt đầu giữa lượt bỏ qua, ranh giới transaction), `AppointmentReminderNoticesTest` (6), `AppointmentNoticeTargetsTest` (6), `AppointmentReminderJobTest` (5), `AppointmentReminderPropertiesTest` (5, gồm phút chạy không trùng ST05 / ST02), `ConfigConstraintsTest` (+2), `SystemConfigServiceTest` (fixture + fail-fast cặp mới). `ClinicCancellationNoticesTest`, `ClinicCancellationListenersTest`, `AppointmentClinicCancellationServiceTest` xanh không sửa.
- IT: `module/appointment/AppointmentReminderIT` (34: R1–R12, người nhận, payload ⊆ / ⊇ biến dòng V18 + ST20 gửi email và giao IN_APP, [CFG] lượt sau, outbox lỗi rollback mốc, `SKIP LOCKED`, UPDATE có điều kiện không đụng dòng đã hủy / đã nhắc, ma trận đồng thời hai thứ tự, ST03 ∥ ST05, hai lượt song song, keyset, EXPLAIN 20 000 dòng), `AppointmentReminderScheduleIT` (1); `SystemConfigAdminIT` (+2: cặp mới hai chiều, đua cặp; ma trận biên mỗi khóa thêm hai khóa bị thu hẹp); `SchemaMigrationIT` (V18 + 2 mã mẫu).
- Mutation (mỗi cái làm đỏ test): bỏ `SKIP LOCKED`; bỏ `reminded_at IS NULL` ở câu khóa; bỏ `status = 'BOOKED'` ở UPDATE; cận trên `≤ → <`; bỏ cận dưới ở câu quét; bỏ kiểm lại "chưa bắt đầu" sau khóa; helper chọn kênh bỏ điều kiện `pending`. Kết quả ghi ở mục "Kết quả mutation".

## Kết quả mutation

(điền sau khi chạy — xem mục cập nhật cuối file)
