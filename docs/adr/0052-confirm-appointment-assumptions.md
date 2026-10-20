# ADR-0052: Xác nhận giữ các giả định nghiệp vụ của lịch hẹn ([CFG] đọc lúc chạy, thú mất hủy mọi `BOOKED`, mốc đếm BR-LH-09, người nhận ST03)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-20
- **Người chốt:** user (BE-1), quyết định thay PO trong buổi thảo luận 2026-10-20.
- **Liên quan:** ADR-0036 §9, ADR-0045 §5, ADR-0047 Q3, ADR-0048 §2, ADR-0050 mục 7, ADR-0051 (A1–A4, Q3); nợ D036, D038, D039 (đóng cùng ADR này). BR-QT-13 (02 L56), BR-KH-05 (L91), BR-LH-09 (L130), BR-LH-12 (L133), BR-TB-02, BR-TB-05.
- **Triển khai:** không đổi code chạy, không migration. Làm rõ câu chữ BR-QT-13, BR-KH-05, BR-LH-09, BR-LH-12 trong `docs/02-business-rules.md`. Hành vi được chốt bởi: `NoShowIT.configChangeAppliesFromNextRun`, `NoShowFlowIT.{deceasedParallelNoShowEndsInExactlyOneFinalState, noShowCommittedBeforeDeceasedStaysNoShow}`, `BookingRestrictionIT.{violationsBehindALiftedRestrictionAreNotCountedAgain, violationsDuringTheRestrictionCountAfterTheLift}`, `AppointmentReminderIT.{configChangeAppliesFromNextRun, lockedAccountStillReminded, linkDecisionPendingStillReminded, noChannelIsMarkedWithoutOutboxOrCareTask, reminderHoldingTheRowThenCancelKeepsTheMark}`.

## Bối cảnh

T25 (ST05, gỡ hạn chế đặt online, ST03) chạy theo một số giả định mà đặc tả không quy định. Các ADR 0045, 0047, 0048, 0050, 0051 ghi các giả định đó; sổ nợ D036, D038, D039 ghi "chờ PO xác nhận". ADR đã Accepted thì bất biến, nên việc xác nhận được ghi ở ADR riêng này. ADR này không đổi quyết định nào của các ADR trên.

## Quyết định

Giữ nguyên toàn bộ hành vi hiện tại:

1. **[CFG] đọc lúc chạy** (D036 mục 1, D039 A1). Ba khóa `appointment.no_show_after_minutes`, `visit.check_in_early_minutes`, `appointment.reminder_hours_before` được đọc tại lần kiểm tra, không snapshot vào lịch. "Giao dịch" theo BR-QT-13 là chính lần check-in, lượt ST05 hoặc lượt ST03, vốn xảy ra sau thời điểm đổi giá trị. Ba khóa này thuộc nhóm "chính sách hiện hành" của ADR-0036 §9.
2. **Thú mất / chuyển chủ hủy mọi lịch `BOOKED` của thú** (D036 mục 2, ADR-0047 Q3), kể cả lịch đã quá giờ mà ST05 chưa chuyển `NO_SHOW`. Khi luồng này đua với ST05 trong khoảng ≤ 5 phút, lịch kết thúc ở đúng một trạng thái cuối: `CANCELLED` / `CLINIC` (không tính vi phạm) hoặc `NO_SHOW` (tính vi phạm). Cả hai đều chấp nhận. Khách bị tính oan thì lễ tân / BRANCH_MANAGER gỡ hạn chế sớm (appointment-v1 #9).
3. **Mốc đếm vi phạm BR-LH-09** (D038): `since = max(at − booking_restriction.window_days, starts_at của lần hạn chế gần nhất, kể cả đã hết hạn hoặc đã gỡ)`.
   - Mỗi vi phạm góp vào tối đa một lần hạn chế.
   - Vi phạm xảy ra trong lúc đang bị hạn chế không tạo hạn chế mới, nhưng được tính cho lần đánh giá sau.
4. **Người nhận ST03** (D039):
   - **A2:** tài khoản đang bị khóa vẫn được nhắc.
   - **A3:** cờ `link_decision_pending` không chặn nhắc lịch đã đặt.
   - **Q3:** khách không có kênh nào (hồ sơ tại quầy không email) không được nhắc và không sinh Care Task.
   - **A4:** email nhắc đã ghi outbox vẫn có thể tới sau khi lịch vừa bị hủy (trễ ≤ chu kỳ ST20).

## Lý do

- **[CFG]:** các chỗ đọc là `AppointmentNoShowService.java:60`, `AppointmentVisitService.java:66-67`, `AppointmentQueryService.java:140-141`, `AppointmentReminderService.java:62`. Đây là cửa sổ vận hành tại quầy và mốc chạy job, không phải điều khoản đã hứa với khách lúc đặt. Snapshot cần thêm cột, migration V19 và sửa đặt lịch, đổi khung, check-in, `checkInReady`, ST05, ST03 cùng các index quét. Chi phí đó không tương xứng với lợi ích.
- **Thú mất ∥ ST05:** `AppointmentClinicCancellationService.cancelBookedOfPet` (L95-98) gọi `AppointmentRepository.findBookedIdsByPetId` (L156). Cách sửa từng được ghi trong D036 — ST05 bỏ qua thú đã mất — **không có tác dụng**:
  - Nếu luồng thú mất đã commit thì lịch đã `CANCELLED` trong cùng transaction đó.
  - Nếu luồng thú mất đang chạy (giữ dòng `pets`, chờ dòng lịch mà ST05 đang khóa), thì ST05 đọc thường sẽ không thấy `deceased_on` chưa commit.
  - Còn nếu ST05 đọc `FOR SHARE` thì sẽ chờ `pets` trong khi luồng kia chờ dòng lịch, tức là deadlock.

  Phụ thuộc thứ tự là bản chất của hai luồng chạy đồng thời, và cả hai kết quả đều đúng rule.
- **BR-LH-09:** code tính mốc ở `BookingRestrictionService.java:83-87`. Đếm nguyên văn 90 ngày thì 3 vi phạm đã gây một lần hạn chế sẽ cộng với vi phạm mới và gây hạn chế lại ngay, tức là một vi phạm bị phạt nhiều lần. Mốc `max(starts_at, lifted_at)` chỉ khác ở trường hợp gỡ sớm, và bỏ qua vi phạm thật xảy ra trong lúc bị hạn chế.
- **ST03:**
  - **A2, A3:** lịch đã đặt được giữ nguyên để xử lý tại quầy (BR-QT-11), và BR-TK-19 chỉ ẩn chức năng đặt mới. Xử lý giống hủy do phòng khám (ADR-0047), qua helper dùng chung `AppointmentNoticeTargets` (L108).
  - **Q3:** BR-TB-02 / BR-TB-05 chỉ có Care Task cho tái chủng, tái khám, quá hạn đón (`AppointmentReminderNotices.java:62`).
  - **A4:** chặn thì worker ST20 dùng chung phải phụ thuộc module appointment, sai hướng phụ thuộc. Việc trễ chỉ vài giây.

## Phương án đã loại

- Snapshot ba khóa [CFG] vào `appointments`.
- Chỉ hủy lịch `BOOKED` tương lai khi thú mất / chuyển chủ: đúng chữ BR-KH-05 và cho kết quả xác định, nhưng khách luôn bị tính vi phạm cho lịch của thú đã mất.
- Không đếm `NO_SHOW` của thú có `deceased_on ≤ slot_date`: câu đếm phải đọc dữ liệu module customer.
- Mốc đếm `max(starts_at, lifted_at)`; đếm nguyên văn 90 ngày.
- Không nhắc tài khoản bị khóa.
- Thêm loại Care Task nhắc lịch hẹn: phải sửa 02 BR-TB-05, 03 §10, 05 `care_tasks.type` và chờ T38/T39.
- ST20 kiểm trạng thái lịch trước khi gửi.

## Hệ quả

- D036, D038, D039 đóng ngày 2026-10-20. Các ADR 0045, 0047, 0048, 0050, 0051 giữ nguyên.
- `docs/02-business-rules.md` ghi rõ cách hiểu ở BR-QT-13, BR-KH-05, BR-LH-09, BR-LH-12 (đánh dấu "làm rõ 2026-10-20, ADR-0052"), để mọi người đọc đặc tả hiểu giống nhau.
- Muốn đổi bất kỳ điểm nào sau này: viết ADR supersede ADR-0052 (kèm ADR gốc của điểm đó), sửa code và sửa đúng các test liệt kê ở *Triển khai*.
