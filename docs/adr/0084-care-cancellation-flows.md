# ADR-0084: Các luồng hủy của Care — `FollowUpApi`, thú mất (ST19), chuyển chủ (Q1)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-27
- **Liên quan:** BR-TB-03, 05, 06, BR-KH-05, 08; 03 Care Task#4, #6 và *Phụ lục*; 06 §3, §4.1, §8 Q1; ADR-0045 (khóa đặt lịch), 0059 (check-in), 0079 (nghĩa vụ bên gọi `CareTaskApi`), 0081, 0082 (ST04). Nợ: D029 (đóng), D027 (ghi nhận), D052 (mới).
- **Triển khai (T39 phần 6):** `visit/service/FollowUpCancellationService` (cài `FollowUpApi`), `visit/listener/VisitPetDeceasedListener`, `care/listener/{CarePetDeceasedListener, CarePetOwnerTransferredListener}`; truy vấn mới ở `MedicalRecordRepository`, `CareTaskRepository`; hai method `CareTaskService` không nằm trong `CareTaskApi`. Bên gọi: `AppointmentBookingService`, `VisitCheckInService`. Không endpoint, không migration.

## Quyết định

1. **Chữ ký:** `FollowUpApi.cancelPendingFollowUps(long petId, Instant at)` thay `(Long petId, LocalDate bookedOn)`. Chỉ xét bệnh án đã khóa nên `locked_at` luôn trước `at`, ngày không lọc thêm gì; `at` là mốc của use case (bên gọi lấy sau các khóa) và là giá trị `follow_up_cancelled_at`. Không có bên cài hay bên gọi nào khác nên đổi được.
2. **Phạm vi (người dùng chốt 27/10):** chỉ bệnh án **đã khóa** (`locked_at IS NOT NULL`, lượt `COMPLETED`), có `follow_up_date`, chưa nhắc, chưa hủy nhắc. Ngày tái khám của lượt đang khám còn sửa được nên chưa phải "hẹn tái khám".
3. **Ba bước theo thứ tự cố định** (READ COMMITTED, bên gọi không `catch`): (1) một UPDATE đặt `follow_up_cancelled_at` cho nhắc **chưa gửi** của mọi bệnh án đã khóa của thú → (2) đọc id các bệnh án đã **nhắc** (chỉ những bệnh án này mới có task) → (3) `CareTaskApi.cancelFollowUpTasks(ids)`. Nếu ST04 đang giữ dòng, bước 1 chờ, rồi kiểm lại điều kiện và bỏ qua dòng ST04 vừa đánh dấu; bước 2 và 3 chạy trên snapshot mới nên thấy và hủy chính task ST04 vừa commit. Nếu bước 1 giữ dòng trước thì ST04 gặp `SKIP LOCKED` và bỏ qua, không chờ. Đọc id **trước** UPDATE sẽ để sót task đó.
4. **Bên gọi:** đặt lịch nhóm `MEDICAL` gọi sau INSERT (mốc `now` của use case); check-in gọi sau `openForVisit` khi **dịch vụ tự sinh** có `medicalType == EXAM` (06 G5: đổi dòng sang EXAM sau đó không hủy). Đổi khung giờ không gọi. `VACCINE` và Thẩm mỹ không hủy.
5. **Không khôi phục** nhắc / task khi lịch hẹn đã kích hoạt việc hủy sau đó bị hủy hoặc `NO_SHOW` (người dùng chốt). Đặc tả không nói; xem Giả định và D052.
6. **Thú mất (ST19, Care Task#4):** hai listener `@EventListener` trên `PetDeceasedEvent`, mỗi cái một UPDATE. Visit: hủy nhắc chưa gửi của bệnh án đã khóa. Care: mọi task `OPEN` của thú (mọi loại) → `CANCELLED`, lý do `Thú cưng đã mất (BR-KH-05)`, `handled_by` NULL = hệ thống. Care hủy theo `pet_id` nên không cần bước 2.
7. **Chuyển chủ (Q1):** `CarePetOwnerTransferredListener` đổi `customer_id` của mọi task `OPEN` của thú sang chủ mới (điều kiện `customer_id <> :new`), giữ loại, chi nhánh, hạn, nguồn. Task đã đóng giữ chủ lúc đóng. Nhắc tái chủng / tái khám đi theo thú (BR-KH-08): visit không làm gì.
8. **Thứ tự khóa:** mọi luồng chạm cả hai bảng khóa `medical_records` **trước** `care_tasks`. Đặt lịch MEDICAL: `advisory 1001 → branches → appointments → medical_records → care_tasks`; check-in EXAM: `pets FOR SHARE → appointments → document_counters → orders → medical_records → care_tasks`; thú mất: `pets → medical_records → care_tasks → appointments / bookings` (listener có `@Order` chạy trước listener không `@Order` của appointment và boarding, vốn ở `LOWEST_PRECEDENCE`; hai nhóm không chung khóa nên thứ tự giữa chúng không tạo vòng); chuyển chủ: `customers ↑ → pets → care_tasks`; ST04: `pets FOR SHARE → medical_records SKIP LOCKED → care_tasks`; lễ tân hoàn tất / hủy task chỉ khóa `care_tasks`. Listener của visit phải chạy **trước** listener của care: `@Order(100)` / `@Order(200)`. Đảo thì thú mất giữ `care_tasks` rồi chờ `medical_records` trong khi đặt lịch làm ngược lại (D027 còn mở nên hai luồng này có thể chạy song song).
9. **Ranh giới module:** `care` và `visit` không import nhau ở `main`, nên mỗi bên có hằng `ORDER` riêng; `PetLifecycleListenersTest` ghim quan hệ 100 < 200. Tên bean có tiền tố module (`VisitPetDeceasedListener`, `CarePet…`) để không trùng bean của appointment / boarding.
10. Không audit, không thông báo, không FSM từng dòng (cập nhật hàng loạt có `status = 'OPEN'` trong WHERE, khuôn ADR-0079 / 0081). Lỗi ở bất kỳ bước nào lan lên và rollback cả use case phát sinh (đặt lịch, check-in, đánh dấu mất, chuyển chủ).

## Giả định (chưa PO xác nhận)

- Hủy nhắc là một chiều: không khôi phục khi lịch đã kích hoạt bị hủy / `NO_SHOW` (BR-TB-06 nói "thú đã có lịch BOOKED", đọc chặt thì nhắc phải tiếp tục). Ghi ở D052.
- Lịch đặt **trước** lúc VET hoàn tất lượt có hẹn tái khám thì không hủy nhắc đó (bệnh án lúc ấy chưa khóa). Ghi ở D052.
- Hủy hàng loạt theo thú (không phân biệt "hẹn tái khám nào": lịch khám của thú dập mọi nhắc tái khám chưa gửi của thú đó). BR-TB-06 viết theo thú, không theo bệnh án.

## Bảo đảm ↔ test

| Bảo đảm | Test |
|---|---|
| Đúng dòng: đã khóa, chưa nhắc, có ngày, đúng thú; task chỉ `FOLLOW_UP_DUE` `OPEN` của bệnh án đã nhắc; gọi hai lần vô hại | `FollowUpCancellationIT`; unit `FollowUpCancellationServiceTest` (`InOrder` UPDATE → SELECT → hủy task) |
| ST04 bỏ qua bệnh án đã hủy nhắc | `st04SkipsARecordWhoseReminderWasCancelled` |
| Race: hủy chờ ST04 rồi hủy task ST04 vừa sinh; ST04 bỏ qua không chờ khi hủy giữ dòng | `FollowUpCancellationRaceIT` |
| Đặt lịch MEDICAL / check-in EXAM hủy, Thẩm mỹ / VACCINE không; lỗi `FollowUpApi` rollback cả bên gọi; nhánh lỗi không gọi | `BookAppointmentIT`, `VisitCheckInIT`; unit `AppointmentBookingServiceTest`, `VisitCheckInServiceTest` |
| Thú mất hủy cả nhắc lẫn task mọi loại (kể cả `PICKUP_OVERDUE`), thú khác nguyên vẹn | `FollowUpCancellationIT`, `CarePetLifecycleIT` |
| Chuyển chủ: task `OPEN` sang chủ mới, task đã đóng giữ chủ cũ, nhắc đi theo thú | `ownerTransferMoves…`, `CarePetLifecycleIT` |
| Listener lỗi rollback cả đổi thú, kể cả phần listener visit đã làm trước; trigger chứng minh visit chạy trước care (`visit_done=1`) | `aFailureInTheCareListenerRollsBack…`, `aFailingTaskUpdateRollsBack…` |
| Visit chạy trước care; chỉ ủy quyền; mọi method `MANDATORY` | `PetLifecycleListenersTest`, `FollowUpCancellationServiceTest`, `CareTaskServiceTest` |

**Đột biến đã thử (đều làm test đỏ, đã hoàn nguyên):** đọc id bệnh án đã nhắc *trước* UPDATE → `cancellationWaitingForSt04CancelsTheTaskSt04JustCreated` đỏ (task ST04 vừa sinh còn `OPEN`); `@Order` của care nhỏ hơn visit → `visitListenerRunsBeforeCareListenerOnPetDeceased` và `aFailureInTheCareListenerRollsBack…` đỏ (`visit_done=0`).

## Hệ quả, chưa làm

- **D029 đóng.** D027 giữ nguyên (luồng thú mất / chuyển chủ vẫn chưa lấy advisory 1001); `@Order` ở mục 8 là điều kiện để các listener mới không thêm vòng chờ.
- Bên mới chạm `care_tasks` và `medical_records` phải theo thứ tự ở mục 8.
- `PICKUP_OVERDUE` bị hủy khi thú mất chỉ về lý thuyết (thú đang lưu trú không đánh dấu mất được); câu UPDATE theo `pet_id` không phân biệt loại cho đơn giản.

## Phương án đã loại

- **Truyền `LocalDate bookedOn` như chữ ký cũ:** không lọc thêm gì với bệnh án đã khóa; `Instant` cần cho `follow_up_cancelled_at`.
- **Hủy cả bệnh án chưa khóa:** thêm nhánh ở luồng ghi bệnh án (đặt lại / xóa ngày tái khám có gỡ cờ hủy không?) và test riêng, để lấy một trường hợp hiếm (đặt lịch giữa lúc đang khám).
- **Khôi phục khi lịch bị hủy / `NO_SHOW`:** thêm lời gọi từ hủy lịch, ST05 và hủy hàng loạt, thêm method `api`, thêm race với ST04.
- **Một listener ở care gọi sang visit (hoặc ngược lại):** vi phạm ranh giới module; hai listener độc lập có `@Order` tách được và mỗi bên một UPDATE.
- **Care tự đọc `medical_records` để biết task nào cần hủy:** care không đọc bảng của module khác; visit đã biết id bệnh án.
