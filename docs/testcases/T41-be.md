# T41 (BE): Kịch bản E2E luồng chính — đặt lịch → tiếp nhận → tiêm → Order → thu tiền → nhắc tái chủng

> Phạm vi: T41.1 lập kịch bản (không code / migration / ADR); T41.2 viết IT theo bảng này — **xong 29/10, xanh, không lộ lỗi nào** (§6); T41.3 nhánh khám (`MainFlowExamIT`, §9) — **xong 29/10, xanh, không lộ lỗi nào**. Nguồn: `docs/03-state-machines.md` §3, §4, §5, §8 và *Phụ lục* (L237–253); rule ở `docs/02-business-rules.md`. Đối chiếu code đọc 29/10/2026.
> Đã chốt khi thảo luận (29/10): dữ liệu nền dựng bằng SQL helper · chỉ khách có tài khoản · có 1 dòng `GOODS` để chứng minh FEFO lúc thu · dịch vụ của lịch hẹn chỉ loại `VACCINE` · bổ sung 29/10 để phủ đủ Phụ lục 03: thú có sẵn mũi 1 cũ kèm Care Task `OPEN` (mũi mới hủy task) và VET ghi bệnh án (hoàn tất khóa bệnh án).

## 1. Quy ước

- **E2E**: mọi bước ở §3 đi qua endpoint / job thật, không `@MockitoBean`, không stand-in, không `UPDATE` trạng thái. Chỉ dữ liệu nền ở §2 dùng SQL.
- Era **2029**, `MutableClock` theo giờ VN. Ngày tiêm `D` = 2029-03-14 (thứ Tư). Era này ở tương lai so với mọi era khác, nên ST05 / ST18 / ST03 của IT khác không quét tới (`VisitFsmIT` giữ 2028).
- Mở lại token sau mỗi lần dời đồng hồ qua ngày, vì phiên hết hạn theo cùng `Clock` (CLAUDE.md *Testing*).
- Ký hiệu giá: `S` = dịch vụ tiêm, `V` = vaccine, `G` = hàng. Cột "—" nghĩa là bước đó không đụng tới đối tượng.

## 2. Dữ liệu dựng sẵn (SQL)

| Dữ liệu | Cách dựng |
|---|---|
| Chi nhánh `ACTIVE`, mở 08:00–12:00 và 14:00–19:00 mọi ngày; BRANCH_MANAGER, RECEPTIONIST, VET kèm `staff_profiles` | như `visit/VisitFsmIT` |
| Dịch vụ nhóm `MEDICAL`, loại `VACCINE` "Tiêm phòng dại", giá S, bật tại chi nhánh | như `VisitFsmIT` |
| Loại vaccine Dại (DOG); phác đồ mũi 1 và mũi 2, mỗi mũi khoảng cách **21 ngày**; sản phẩm vaccine giá V | `visit/VaccinationItSupport.vaccineType / protocol / product` |
| Lô vaccine: **L0** hết hạn D−1 (5), **L1** hạn D+300 (5), **L2** hạn D+60 (5) | `VaccinationItSupport.lot(branch, product, expiry, qty)` |
| Sản phẩm hàng (`tracks_expiry = true`), giá G; lô **G0** hết hạn D−1 (5), **G1** hạn D+300 (10), **G2** hạn D+60 (2) | SQL theo mẫu `lot(...)` |
| Khách: `accounts` CUSTOMER `ACTIVE` (email UUID) + `customers` gắn `account_id`, không cờ chờ liên kết; 1 chó còn sống, đủ tuổi tối thiểu | SQL như `appointment/AppointmentItSupport` |
| **Mũi cũ** (điểm 1): lượt cũ `MEDICAL` `COMPLETED` cùng chi nhánh, ngày D−30; mũi Dại **mũi 1** `administered_on` D−30, `next_due_date` D−9, lô L0 (còn hạn lúc tiêm), chưa bị thay thế, `overdue_handled_at` đặt sẵn; **Care Task `VACCINE_OVERDUE` `OPEN`** của mũi đó (giả lập ST18 đã chạy — khách có email nên ST04 không sinh `VACCINE_DUE`) | `VaccinationItSupport.dose(...)` + `careTask(mũi, thú, "VACCINE_OVERDUE", "OPEN")`, như `VaccinationRecordIT.olderDoseWithOpenTask`. `dose` tự tạo Order `OPEN` + 1 dòng `VACCINE` cho lượt cũ: đây là dữ liệu nền, không thu tiền, không tính ở §4 |

## 3. Bảng bước

| # | Giờ | Vai trò | Endpoint / job | Lịch hẹn | Visit | Order | Kho (lô · `stock_movements`) | Outbox / task / audit | 03 · rule |
|---|---|---|---|---|---|---|---|---|---|
| 1 | D−3 10:00 | A02 | `POST /api/appointments` {petId, branchId, serviceId, `slotDate` D, `slotStart` 09:00} | `BOOKED`, mã `LH-…`, `reschedule_count = 0`, `late_cancel = false` | — | — | không đổi | không | Lịch hẹn#1 · BR-LH-01…05 |
| 2 | D 08:40 | A06 | `POST /api/visits` {petId, appointmentId} | `CHECKED_IN` | `WAITING`, mã `TN-…`, `priorityClass = APPOINTMENT`, chưa gán | `VISIT` `OPEN`, 1 dòng `SERVICE` `is_auto_generated`, đơn giá S; tổng S | không đổi | không | Visit#1 → Lịch hẹn#3, Order#1 · BR-TN-01, 02 |
| 3 | 08:45 | A06 | `POST /api/visits/{id}/assign` {assigneeId: VET} | — | `WAITING`, assignee = VET | không đổi | — | — | Visit#2 · BR-TN-05 |
| 4 | 09:00 | A07 | `POST /api/visits/{id}/call` | — | `IN_PROGRESS` | `OPEN` | — | — | Visit#3 |
| 5 | 09:05 | A07 | `GET /api/visits/{id}/vaccination-options` | — | — | — | chỉ đọc | — | `lastDose` = mũi cũ (mũi 1, D−30); gợi ý **mũi 2**; sản phẩm vaccine `availableQuantity = 10` (L1 + L2, không tính L0) · BR-KB-04, BR-KO-01 |
| 6 | 09:10 | A07 | `POST /api/visits/{id}/vaccinations` {vaccineTypeId, protocolId = mũi 2, productId} | — | `IN_PROGRESS` | `OPEN`, thêm dòng `VACCINE` đơn giá V; tổng S+V | **L2 5→4**; L0, L1 không đổi · +1 `VACCINATION` trỏ mũi mới | mũi mới `dose_number = 2`, `next_due_date = D+21`; **mũi cũ `superseded_at` = giờ bước 6; Care Task `VACCINE_OVERDUE` → `CANCELLED`, `cancel_reason` "Đã tiêm lại (BR-TB-03)", `handled_by` NULL** | "ghi nhận mũi tiêm" (Phụ lục 03) · Care Task#5 · BR-KB-04, BR-KO-05, BR-TB-03 |
| 7 | 09:15 | A07 | `PUT /api/visits/{id}/medical-record` {examination: "Thân nhiệt 38,5°C, khỏe, đủ điều kiện tiêm"} | — | `IN_PROGRESS` | — | — | `medical_records` của lượt được tạo, `locked_at` NULL, `diagnosis` NULL (lượt chỉ tiêm không bắt buộc chẩn đoán) | visit-v1 #13 · BR-KB-01, BR-KB-02 |
| 8 | 09:20 | A07 | `POST /api/visits/{id}/complete` | `COMPLETED` | `COMPLETED` | `PENDING` | không đổi | **`medical_records.locked_at` = `visits.completed_at`** | Visit#5 → Lịch hẹn#7, Order#4, bệnh án khóa (Phụ lục 03) · BR-KB-01, BR-KB-02 (lượt chỉ tiêm, có ≥ 1 mũi) |
| 9 | 09:21 | A07 | `PUT /api/visits/{id}/medical-record` lần nữa; rồi `GET` cùng đường dẫn | — | `COMPLETED` | — | — | PUT → **409 `INVALID_STATE_TRANSITION`**, bệnh án không đổi (`xmin` như sau bước 8); GET → `locked = true`, `examination` của bước 7 | BR-KB-01 (sửa sau khi khóa chỉ qua bản bổ sung, ADR-0070) |
| 10 | 09:25 | A06 | `POST /api/orders/{orderId}/lines` {`lineType` GOODS, productId, quantity 3} | — | — | `PENDING`, thêm dòng `GOODS` 3 × G; tổng S+V+3G; `stockWarning` null (khả dụng 12 ≥ 3) | không đổi (chưa trừ) | — | Order `PENDING`: lễ tân thêm hàng bán lẻ · BR-BH-02, 04 |
| 11 | 09:28 | A06 | `POST /api/cashier-shifts` {afterHours: false} | — | — | — | — | — | Ca thu ngân#1 · BR-TG-01, 05 |
| 12 | 09:30 | A06 | `POST /api/payments/preview` {orderIds} | — | — | không đổi; preview trả tổng S+V+3G | không đổi | — | — |
| 13 | 09:31 | A06 | `POST /api/payments` {orderIds: [order], method CASH, amount S+V+3G} | — | — | **`PAID`**, có `payment_id` | **G2 2→0, G1 10→9** (FEFO qua 2 lô), G0 không đổi · +2 `SALE`; **vaccine không bị trừ lần 2** (L2 vẫn 4) | audit `ORDER_PAID`; phiếu thu thuộc ca của A06 | Order#8 · BR-TG-01…04, BR-BH-04, BR-KO-05 |
| 14 | D+13 07:10 | job | `VaccineReminderJob.run()` | — | — | — | — | không ghi gì (D+21 nằm ngoài cửa sổ D+13…D+20) | cận của `DueDateWindow` |
| 15 | D+14 07:10 | job | `VaccineReminderJob.run()` | — | — | — | — | mũi mới được đặt `due_reminded_at`; outbox `VACCINE_REMINDER` (EMAIL, email tài khoản) + `VACCINE_REMINDER_APP` (IN_APP, account id, link `/booking?petId=&branchId=&serviceType=VACCINE`, `VaccineReminderService.java:57`); 0 Care Task; mũi cũ `due_reminded_at` vẫn NULL — chỉ là kiểm phụ: hạn D−9 của nó vốn ngoài cửa sổ, bộ lọc `superseded_at IS NULL` do `VaccineReminderIT` chứng minh | ST04 · BR-TB-01 (7 ngày [CFG]), BR-TB-02, BR-TB-03 |
| 16 | D+14 07:40 | job | `VaccineReminderJob.run()` lần nữa | — | — | — | — | không thêm dòng nào | ST04 không gửi trùng (ADR-0082) |

## 4. Bất biến kiểm ở cuối mạch

- Tổng lô vaccine 15 → 14; tổng lô hàng 17 → 14; `stock_movements` mới = 1 `VACCINATION` + 2 `SALE`; không lô nào âm; L0, G0 không đổi.
- Σ dòng Order = `orders.total_amount` = `payments.amount` = S+V+3G; đơn giá mỗi dòng bằng giá lúc thêm dòng (snapshot).
- Trong era 2029, không kể dữ liệu nền (lượt cũ, Order và mũi của nó, Care Task): đúng 1 `appointments`, 1 `visits`, 1 `orders`, 1 `payments`, 1 `vaccinations` mới, 1 `medical_records` (đã khóa); 0 `care_tasks` mới; outbox đúng 2 dòng.
- Dữ liệu nền sau mạch: mũi cũ đã bị thay thế, Care Task của nó `CANCELLED` (không còn task `OPEN` nào của thú); Order của lượt cũ không đổi.

## 5. Ngoài phạm vi (đã có test riêng hoặc thuộc task khác)

ST03 nhắc lịch (`AppointmentReminderIT`; lỗi cận dưới của câu quét đã sửa ở T41.5, §11), Care Task cho khách không email, xóa mũi, hủy / `NO_SHOW`, lưu trú (T42, T43 — BE-2), ST18 quá hạn tái chủng. Tái khám và kê đơn: nhánh khám ở §9 (T41.3); mua ngoài lúc thu (sales #11) chưa có — BE-2, T43.

## 6. IT (T41.2)

`BE/src/test/java/com/petcare/module/visit/MainFlowE2EIT.java`: một `@Test` chạy §3 tuần tự, assert từng cột sau mỗi bước rồi kiểm §4; tái dùng `VaccinationItSupport`, `AppointmentItSupport`; dọn era 2029 theo thứ tự con trước cha (FK `RESTRICT`): Care Task → mũi tiêm (`deleteDosesOfPet`) → bệnh án (trước `AppointmentItSupport.cleanUp`, CLAUDE.md *Testing*) → dòng Order / Order → lượt.

Kết quả 29/10/2026: `MainFlowE2EIT` 1/1 xanh hai lần liên tiếp (~62 s, dọn đủ); hồi quy nhóm `visit` 475 xanh (474 + lớp này), `care` 134 xanh. Không lỗi nào để sửa (BE-1 hay BE-2), nên không có nợ / ghi chú mới cho BE-2.

Chạy: `mvn verify "-Dit.test=MainFlowE2EIT" -Dtest=none "-Dsurefire.failIfNoSpecifiedTests=false"` (trong `BE/`, Docker Desktop đang chạy).

## 7. Nguồn đối chiếu trong code

| Phần | Vị trí |
|---|---|
| Endpoint, vai trò | `appointment/controller/AppointmentController.java:79` · `visit/controller/VisitController.java:108, 169, 222, 269` · `visit/controller/VaccinationController.java:61, 82` · `sales/controller/OrderController.java:148` · `sales/controller/CashierShiftController.java:71` · `sales/controller/PaymentController.java:74, 91` |
| Body | `BookAppointmentRequest`, `CreateVisitRequest`, `AssignRequest`, `RecordVaccinationRequest`, `AddOrderLineRequest` (`SERVICE` / `GOODS`), `OpenShiftRequest(afterHours)`, `CreatePaymentRequest(orderIds, method, amount)` |
| Kho | `inventory/service/StockIssueService.java:100` (`SALE`), `:143` (`VACCINATION`); thu tiền chỉ trừ `GOODS` / `DRUG` (`sales/service/PaymentLines.java:26`) |
| Order | `sales/service/OrderLinePolicy.java:65` (`PENDING` chỉ thêm `GOODS`); `OrderLineService.stockWarning` (null khi đủ tồn); `sales/service/PaymentService.java:89` (số tiền phải bằng tổng, BR-TG-02), `:152` (audit `ORDER_PAID`) |
| Mũi cũ, Care Task | `visit/service/VaccinationRecordService.java:137–140` (`findUnsupersededIds` → `markSuperseded` → `CareTaskApi.cancelForRevaccination`); `care/service/CareTaskService.java:27` (lý do "Đã tiêm lại (BR-TB-03)"), `:69`; gợi ý mũi `visit/service/VaccinationOptionService.java:120` (mũi kế tiếp sau mũi gần nhất) |
| Bệnh án | `visit/controller/MedicalRecordController.java:77` (`PUT`, VET), `visit/service/MedicalRecordService.java:130` → `:228` (không `IN_PROGRESS` → 409); khóa ở `visit/service/VisitCompletionService.java:111–118` (chỉ khi bệnh án đã có: lượt chưa ghi bệnh án thì không có gì để khóa) |
| ST04 | `visit/service/DueDateWindow.java` (hôm nay … hôm nay + N), `visit/service/VaccineReminderService.java:94` (`REMINDER_VACCINE_DAYS_BEFORE`), `visit/service/ReminderChannels.java:33–34` (IN_APP + EMAIL); mẫu V22 `VACCINE_REMINDER`, `VACCINE_REMINDER_APP` |

## 8. Rollback từng chuỗi (không lặp lại trong E2E)

E2E chỉ chạy đường thành công; rollback khi một bước giữa chừng lỗi (convention 09 §FSM, dòng cuối) đã có IT riêng:

| Chuỗi Phụ lục 03 | IT chứng minh rollback |
|---|---|
| Visit#1 check-in | `VisitCheckInIT.orderLineFailureRollsBackEverything`, `visitInsertFailureRollsBackTheAppointment` |
| Ghi nhận mũi tiêm | `VaccinationRecordIT.aFailureInTheStockMovementRollsBackTheOrderLineTheDoseAndTheLot`, `aFailureMarkingTheOlderDoseRollsBackEverythingIncludingTheStock`, `aFailureCancellingTheCareTaskRollsBackEverythingIncludingTheSupersededMark` |
| Visit#5 hoàn tất | `VisitCompleteIT.aFailureWhileWritingTheOrderOfAVaccineOnlyVisitRollsEverythingBack`, `aFailureWhileWritingTheAppointmentRollsTheVisitBack`, `aFailureWhileWritingTheOrderRollsTheVisitAndTheAppointmentBack`, `aFailureWhileLockingTheMedicalRecordRollsEveryEarlierWriteBack` |
| Order#8 thu tiền | `PaymentIT.failureWhileAuditingRollsBackEverything` |
| ST04 | `VaccineReminderIT.outboxFailureRollsBackTheFlagAndTheNextRunSucceeds`, `taskFailureRollsBackTheFlagAndTheNextRunSucceeds` |

## 9. T41.3 — nhánh khám (`MainFlowExamIT`)

> Đã chốt khi thảo luận (29/10): dòng mua ngoài tạo **lúc kê** (#16 `EXTERNAL_PURCHASE`; sales #11 chưa có) · đầu vào là đặt lịch online rồi check-in · có bệnh án cũ đã khóa với hẹn tái khám chưa nhắc, đặt lịch MEDICAL phải hủy nhắc đó (Care Task#6, ADR-0084) · khách có tài khoản (ST04 → IN_APP + EMAIL). Quy ước như §1; era **2030**, ngày khám `D` = 2030-03-13 (thứ Tư). Giá: `S` = dịch vụ khám 150 000, `A` = thuốc 30 000.

### 9.1 Dữ liệu dựng sẵn (SQL)

| Dữ liệu | Cách dựng |
|---|---|
| Chi nhánh `ACTIVE` mở 08:00–12:00, 14:00–19:00; RECEPTIONIST, VET | như §2 |
| Dịch vụ `MEDICAL` loại `EXAM`, giá S, bật tại chi nhánh | `AppointmentItSupport.service("MEDICAL")` (mặc định `EXAM`) |
| Thuốc kê đơn A: lô **A0** hết hạn D−1 (5), **A1** hạn D+200 (3), **A2** hạn D+30 (2) | `VaccinationItSupport.prescriptionDrug / lot` |
| Thuốc kê đơn B: chỉ lô **B0** hết hạn D−1 (5) — tồn khả dụng 0 | như trên |
| Khách có tài khoản `ACTIVE` + 1 chó | như §2 |
| **Bệnh án cũ R0**: lượt `COMPLETED` D−20, đã khóa, `follow_up_date` D+2, chưa nhắc, chưa hủy | `FollowUpItSupport.record(…, locked = true, reminded = false)` |

### 9.2 Bảng bước

| # | Giờ | Vai trò | Endpoint / job | Kỳ vọng | 03 · rule |
|---|---|---|---|---|---|
| 1 | D−3 10:00 | A02 | `POST /api/appointments` (D 09:00) | `BOOKED`; **R0 `follow_up_cancelled_at` = giờ bước 1**, chưa nhắc; 0 Care Task, 0 outbox | Lịch hẹn#1 · Care Task#6 · BR-TB-06 |
| 2 | D 08:40 | A06 | `POST /api/visits` | Lịch hẹn `CHECKED_IN`; Visit `WAITING`; Order `OPEN` + dòng `SERVICE` S; R0 không đổi (`xmin`) dù check-in EXAM gọi lại `FollowUpApi` | Visit#1 |
| 3–4 | 08:45, 09:00 | A06, A07 | `…/assign`, `…/call` | `IN_PROGRESS` | Visit#2, #3 |
| 5 | 09:02 | A07 | `…/complete` chưa có bệnh án | **400 BR-KB-02**; Visit, Order, lịch hẹn không đổi (`xmin`), không có bệnh án | BR-KB-02 |
| 6 | 09:05 | A07 | `PUT …/medical-record` {examination, diagnosis, treatmentPlan, followUpDate D+14} | bệnh án chưa khóa, chưa nhắc; GET trả đủ trường | BR-KB-01, 06 |
| 7 | 09:10 | A07 | `POST …/prescriptions` {A, 4} | dòng đơn thường; Order thêm `DRUG` 4 × A (tổng S+4A); kho chưa trừ (khả dụng 5 = A1 + A2) | BR-KB-03, BR-KO-01 |
| 8a | 09:12 | A07 | `POST …/prescriptions` {B, 2} không `shortageResolution` | **400 BR-KB-03**, không dòng đơn mới, Order không đổi | BR-KB-03 |
| 8b | 09:13 | A07 | như 8a + `EXTERNAL_PURCHASE` | dòng đơn mua ngoài `OUT_OF_STOCK_AT_PRESCRIBE`, `orderLineId` null; Order không đổi (`xmin`) | BR-KB-03 |
| 9 | 09:20 | A07 | `…/complete` | lịch hẹn, Visit `COMPLETED`; Order `PENDING`; `locked_at` = `completed_at`; GET có 2 dòng đơn | Visit#5 · BR-KB-02 |
| 10–11 | 09:25, 09:26 | A06 | mở ca; `POST /api/payments/preview` | tổng S+4A, không thiếu hàng, Order không đổi | — |
| 12 | 09:27 | A06 | `POST /api/payments` {CASH, S+4A} | `PAID`; **A2 2→0, A1 3→1** (2 `SALE`), A0, B0 không đổi; audit `ORDER_PAID` | Order#8 · BR-BH-04, BR-KO-05 |
| 13 | D+10 07:40 | job | `FollowUpReminderJob.run()` | không ghi gì (D+14 ngoài D+10…D+13) | `DueDateWindow` |
| 14 | D+11 07:40 | job | chạy lại | `follow_up_reminded_at` đặt; outbox `FOLLOW_UP_REMINDER_APP` (IN_APP, link `/booking?petId=&branchId=`) + `FOLLOW_UP_REMINDER` (EMAIL), `ngay_tai_kham` 27/03/2030; 0 Care Task; R0 không được nhắc | ST04 · BR-TB-02, 06 |
| 15 | D+11 08:10 | job | chạy lại | không thêm dòng | ADR-0082 |

### 9.3 Bất biến cuối mạch

- Tồn thuốc A 10 → 6, B0 vẫn 5, không lô âm; Σ dòng Order = `total_amount` = `payments.amount` = S+4A.
- Đúng 1 lịch hẹn, 1 Visit mới, 1 Order, 1 phiếu thu, 1 bệnh án mới (đã khóa), 2 dòng đơn (A × 4 có dòng Order; B × 2 mua ngoài, không dòng Order), 2 audit `PRESCRIPTION_ITEM_ADDED`, 0 Care Task, outbox đúng 2 dòng.

### 9.4 Kết quả

`BE/src/test/java/com/petcare/module/visit/MainFlowExamIT.java` (1 `@Test`, khung của `MainFlowE2EIT`; dọn: `deletePrescriptionsOfPet` → `FollowUpItSupport.cleanUp` → Order / phiếu thu / ca → danh mục → `AppointmentItSupport.cleanUp`). 29/10/2026: xanh hai lần liên tiếp (~61 s / ~39 s, dọn đủ); hồi quy nhóm `visit` 476 xanh (475 + lớp này), `care` 134 xanh. Không lỗi nào để sửa, không nợ / ghi chú mới cho BE-2. Nguồn code: `visit/service/PrescriptionService.java:164` (`decideQuantity`), `MedicalRecordService.java:213` (khoảng ngày tái khám), `VisitCompletionService.java:122` (BR-KB-02), `FollowUpReminderService` (kênh, payload), `AppointmentBookingService.java:175` / `VisitCheckInService.java:159` (`FollowUpApi`), `sales/service/PaymentLines.java` (chỉ `GOODS` / `DRUG` trừ kho).

## 10. T41.4 — smoke Docker (container thật, giờ thật)

> Đã chốt khi thảo luận (29/10): ADMIN hạ [CFG] `appointment.min_lead_hours` = 0 qua `PUT /api/system-configs/{key}` (UC10, volume tạm nên không khôi phục) để khách đặt ONLINE khung 30 phút kế tiếp và check-in ngay · chạy cả nhánh tiêm (thú **V**, như T41.2) và nhánh khám (thú **E**, như T41.3) của cùng một khách — BR-LH-05 (c) cấm 2 lịch MEDICAL cùng ngày cho một thú · script giữ trong repo để chạy lại ở T50.

**Chạy** (gốc repo; Docker Desktop đang chạy, có `.env`, cổng 8080 / 8025 / 1025 trống; **không chạy 23:00–24:00 giờ VN** — chi nhánh smoke mở 00:00–23:59, hết khung trong ngày):

```bash
python BE/scripts/smoke/t41_main_flow.py          # giữ stack để xem Mailpit http://localhost:8025
python BE/scripts/smoke/t41_main_flow.py --down   # dọn stack + volume petcare-smoke sau khi chạy
```

- `BE/scripts/smoke/docker-compose.smoke.yml`: project `petcare-smoke`, container `petcare-smoke-*`, network / volume riêng (volume `pet-care-new_*` của dev không bị đụng); postgres, redis không publish cổng (máy dev có Postgres cài sẵn giữ 5432) — script truy vấn qua `docker exec … psql`; cron ST04 tái chủng `30 * * * * *`, tái khám `40 * * * * *`, ST18 `50 * * * * *`.
- `BE/scripts/smoke/t41_main_flow.py` (Python stdlib): `down -v` project smoke → `up -d --build postgres redis mailpit backend` → chờ `/actuator/health` → dữ liệu nền SQL (như §2, §9.1; tài khoản ADMIN / RECEPTIONIST / VET / khách mật khẩu `Petcare123`) → 22 bước qua API thật → in bảng; mã thoát ≠ 0 khi có bước sai.
- Khác IT: giờ thật (không `MutableClock`); phác đồ khoảng cách **7 ngày** và hẹn tái khám **hôm nay + 3** để hạn rơi ngay vào cửa sổ ST04 (7 / 3 ngày), nên không có bước "ngoài cửa sổ"; R0 hẹn hôm nay + 10 để ST04 mỗi phút không kịp nhắc trước khi bước đặt lịch hủy; email lọc theo tiêu đề nhắc (ST03 nhắc lịch hẹn có thể gửi thêm thư khác).

**Kết quả 30/10/2026 00:01** (lần chạy 2; lần 1 dừng ở dữ liệu nền vì script chèn SĐT cho tài khoản khách — `ck_accounts_phone_by_role`, lỗi của script, đã sửa): **PASS 22/22**, không lộ lỗi BE.

| # | Nhánh | Vai trò | Request | HTTP | Kết quả chính |
|---|---|---|---|---|---|
| 1 | — | ADMIN | login; `PUT /api/system-configs/appointment.min_lead_hours` | 200/200 | value=0 |
| 2 | V | A02 | login; `POST /api/appointments` (tiêm) | 200/201 | LH-261030-0001 BOOKED ONLINE |
| 3 | E | A02 | `POST /api/appointments` (khám) | 201 | R0 hủy nhắc = true |
| 4 | V | A06/A07 | `POST /api/visits` → assign → call | 201/200/200 | IN_PROGRESS, lịch CHECKED_IN, Order OPEN 150 000 |
| 5 | V | A07 | `GET …/vaccination-options` | 200 | gợi ý mũi 2; khả dụng 10 |
| 6 | V | A07 | `POST …/vaccinations` | 201 | lô L0/L1/L2 = 5/5/4; next_due = hôm nay + 7; mũi cũ thay thế, task CANCELLED |
| 7 | V | A07 | `PUT …/medical-record`; `POST …/complete` | 200/200 | COMPLETED / COMPLETED / PENDING, bệnh án khóa |
| 8 | V | A07 | `PUT …/medical-record` (đã khóa) | 409 | INVALID_STATE_TRANSITION |
| 9 | V | A06 | `POST /api/orders/{id}/lines` GOODS × 3 | 201 | tổng 420 000 |
| 10 | E | A06/A07 | `POST /api/visits` → assign → call | 201/200/200 | IN_PROGRESS, lịch CHECKED_IN, Order OPEN 150 000 |
| 11 | E | A07 | `POST …/complete` (chưa chẩn đoán) | 400 | BR-KB-02 |
| 12 | E | A07 | `PUT …/medical-record` (chẩn đoán, tái khám) | 200 | followUpDate = hôm nay + 3 |
| 13 | E | A07 | `POST …/prescriptions` A × 4 | 201 | dòng DRUG; tổng 270 000 |
| 14 | E | A07 | `POST …/prescriptions` B × 2 → + `EXTERNAL_PURCHASE` | 400/201 | BR-KB-03 → `OUT_OF_STOCK_AT_PRESCRIBE`, tổng giữ 270 000 |
| 15 | E | A07 | `POST …/complete` | 200 | COMPLETED / COMPLETED / PENDING, bệnh án khóa |
| 16 | — | A06 | mở ca; preview 2 Order | 201/200 | tổng 690 000, không thiếu hàng |
| 17 | V | A06 | `POST /api/payments` Order tiêm | 201 | PAID; G0/G1/G2 = 5/9/0; vaccine không trừ lần 2 |
| 18 | E | A06 | `POST /api/payments` Order khám | 201 | PAID; A0/A1/A2 = 5/1/0; B0 = 5 |
| 19 | V, E | job | ST04 (cron mỗi phút) | — | sau 43 s: outbox `FOLLOW_UP_REMINDER` (EMAIL), `_APP` (IN_APP), `VACCINE_REMINDER` (EMAIL), `_APP` (IN_APP); 0 Care Task OPEN |
| 20 | V, E | Mailpit | `GET /api/v1/search?query=to:<email>` | 200 | "Nhắc tái khám của Bông ngày …", "Nhắc tiêm Dại … cho Milu ngày …" |
| 21 | V, E | A02 | `GET /api/me/notifications` | 200 | "Sắp đến ngày tái khám của Bông", "Sắp đến ngày tiêm nhắc lại của Milu" |
| 22 | V, E | job | chạy lại sau một phút | — | outbox vẫn 4 dòng, Mailpit vẫn 2 thư nhắc |

## 11. T41.5 — sửa ST03 (D053) · T41.6 — chốt T41

- **T41.5 (31/10):** `AppointmentRepository.findDueForReminderIds` (lô đầu của ST03) thêm cận dưới `(slot_date, slot_start) > (CAST(:fromDate AS date), CAST(:fromTime AS time))`, chép câu lô sau; cửa sổ `(now, now + H]` đúng ADR-0056 §1. Trước khi sửa `AppointmentReminderIT` 34 ca, 2 đỏ (`startedOrPastBookedIsNotReminded`: câu quét trả lịch đã bắt đầu; `reminderQueriesUseTheirIndexesOnRealisticData`: `EXPLAIN` bind thừa tham số); sau khi sửa 34/34 xanh, unit reminder 35/35, nhóm `appointment` 384/384 xanh (24 lớp). Không ADR / migration; D053 đã trả.
- **T41.6:** T41.2–T41.4 không lộ lỗi BE nào (lỗi duy nhất là script smoke chèn SĐT cho tài khoản khách, sửa ngay ở T41.4), nên không có cặp "test đỏ → sửa → xanh" nào; chỉ chốt tài liệu (`time.md`, `system-overview.md`, `CLAUDE.md`, `docs/dept`).
