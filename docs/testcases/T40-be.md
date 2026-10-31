# T40 (BE): Bảng truy vết 28 chuyển trạng thái — Tài khoản, Lịch hẹn, Visit, Care Task

> Phạm vi: T40.1 lập bảng (không code / migration); các task con sau cập nhật cột IT. Mỗi FSM có một lớp `*FsmIT` chạy trên bean thật (§3). Nguồn: `docs/03-state-machines.md` §1, §3, §4, §10 (bảng chuyển) và *Phụ lục*. Đối chiếu code đọc 28/10/2026, **chưa chạy lại test**.
> Đường dẫn IT tính từ `BE/src/test/java/com/petcare/module/`; số sau dấu `:` là dòng của `void` của test. Unit `*TransitionHandlerTest` (kế thừa `platform/fsm/FsmTransitionTestBase`) đã duyệt **mọi** cặp `from × to` của 4 enum nên không nhắc lại ở từng dòng.

## 1. Quy ước

- **E2E** — test đi qua endpoint / job / listener thật. **stand-in** — API chéo là `@MockitoBean` hoặc event bắn tay. **SQL-setup** — trạng thái nguồn đặt bằng `UPDATE`.
- Chuyển **hợp lệ** chỉ tính khi có test E2E. Cặp **sai** được SQL-setup, miễn assert mã lỗi và không ghi gì (`xmin`).
- Cặp không có sự kiện nào kích hoạt (vd `COMPLETED → BOOKED`) không có đường HTTP: chỉ unit phủ.
- Mã lỗi cặp sai theo ADR của luồng: guard nghiệp vụ có thể trả 400 trước 409 (ADR-0029), hoặc 403 trước 409 (ADR-0069 §2).
- SQL-setup có thể tạo trạng thái trái *Phụ lục* 03 (vd Visit `COMPLETED` mà Order còn `OPEN`). Các test cũ chỉ `UPDATE visits.status` được ghi **(chỉ visits.status)**; test mới (G2, G3) phải đi tới trạng thái nguồn bằng API thật.

## 2. Bốn bảng

### 2.1 Tài khoản (8) — `identity/fsm/AccountTransitionHandler`; #3, #7, #8 ngoài map (xóa cứng và cờ `is_locked`)

**`identity/AccountFsmIT` (T40.3) phủ cả 8 dòng E2E trên bean thật** (không `@MockitoBean` nào, era 1998). Cột "IT cũ" là test chi tiết của từng luồng; *Mức* ghi mức của IT cũ.

| # | Chuyển | Kích hoạt thật | `AccountFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `PENDING` | `POST /api/auth/register` | `registerCreatesPendingAccountWithOnlineProfile` | `identity/RegistrationIT:100` | stand-in `CustomerApi` |
| 2 | `PENDING` → `ACTIVE` | `POST /api/auth/register/verify` | `verifyingTheOtpActivatesTheAccount` | `identity/RegistrationVerificationIT:142` | stand-in `CustomerApi` |
| 3 | `PENDING` → xóa | ST02 `PendingAccountCleanupJob` | `st02DeletesAnExpiredPendingAccountWithItsProfile` | `identity/PendingAccountCleanupIT:141` | stand-in `CustomerApi` |
| 4 | — → `ACTIVE` | `POST /api/staff` | `staffIsCreatedDisabledAndReactivated` | `identity/StaffAdminIT:123`, `:226` | E2E |
| 5 | `ACTIVE` → `DISABLED` | `POST /api/staff/{id}/disable` | như #4 | `visit/VisitCompleteIT#unfinishedVisitsBlockDisablingTheAssigneeUntilEachIsCompleted` (BR-QT-08 với Visit thật — G1); `StaffTransferPlaceholderIT:123`; `StaffDisableReactivateIT:143` (stand-in `VisitQueryApi`) | E2E |
| 6 | `DISABLED` → `ACTIVE` | `POST /api/staff/{id}/reactivate` | như #4 | `StaffDisableReactivateIT:396` (lớp mock visit / cashier, luồng này không gọi) | E2E |
| 7 | `is_locked` false → true | `POST /api/accounts/{id}/lock` | `lockAndUnlockOnlyFlipTheFlag` (cả `ACTIVE` và `DISABLED`) | `StaffTransferPlaceholderIT:182`, `visit/VisitReassignIT:180` (kiểm `needs_reassign`); `AccountLockIT:193` (stand-in) | E2E |
| 8 | `is_locked` true → false | `POST /api/accounts/{id}/unlock` | như #7, `status` trả về đúng giá trị trước khóa | `AccountLockIT:294`, `:324` | stand-in 6 query API |

Cặp sai trong `AccountFsmIT` (nguồn đi bằng API; 409 `INVALID_STATE_TRANSITION`; `xmin` dòng `accounts`, số phiên chưa hủy, audit của action, outbox về tài khoản đều không đổi): disable `DISABLED` · reactivate `ACTIVE` · lock đã khóa · unlock chưa khóa. IT cũ còn phủ: disable khi `is_locked` → 400 BR-QT-12 (`StaffDisableReactivateIT:220`) · reactivate đang khóa → 400 BR-QT-10 (`:529`) · verify tài khoản đã `ACTIVE` → **404** (`RegistrationVerificationIT:283`), hành vi OTP chung (ADR-0010 / 0011), không phải cặp FSM.

### 2.2 Lịch hẹn (8) — `appointment/fsm/AppointmentTransitionHandler`

**`appointment/AppointmentFsmIT` (T40.4) phủ cả 8 dòng E2E trên bean thật** (không mock, không `JdbcPetCustomerModule`; mọi lịch đặt qua `POST /api/appointments`; era 1997 vì ST05 không có cận dưới). *Mức* là của IT cũ.

| # | Chuyển | Kích hoạt thật | `AppointmentFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `BOOKED` | `POST /api/appointments` | `customerBooksAndReschedules` | `appointment/BookAppointmentIT:164`, `:197` | stand-in `JdbcPetCustomerModule` |
| 2 | `BOOKED` → `BOOKED` | `POST …/{id}/reschedule` | như #1 | `RescheduleCancelIT:175` | stand-in `JdbcPetCustomerModule` + `BoardingQueryApi` |
| 3 | `BOOKED` → `CHECKED_IN` | Visit#1 `POST /api/visits` | `checkInMovesTheBookingToCheckedIn` | `visit/VisitCheckInIT:155` | stand-in `BoardingQueryApi` |
| 4 | `BOOKED` → `CANCELLED` | `POST …/{id}/cancel`; `late_cancel` đúng ở mốc 12h | `customerCancelSetsLateCancelAtTheTwelveHourMark` | `RescheduleCancelIT:344`, `:419` | stand-in như #2 |
| 5 | `BOOKED` → `CANCELLED` | ngày nghỉ / giờ mở cửa / thú mất / chuyển chủ (listener) | `petDeceasedCancelsItsBookedAppointment` (thú mất) | `ClinicCancellationIT:172`, `:234`, `:254`, `:288`; `NO_SHOW` giữ nguyên: `NoShowFlowIT:293` | stand-in `BoardingQueryApi` |
| 6 | `BOOKED` → `NO_SHOW` | ST05 `NoShowJob` | `st05MarksAnOverdueBookingNoShow` | `NoShowIT:159`, `NoShowFlowIT:144` | stand-in `BoardingQueryApi` |
| 7 | `CHECKED_IN` → `COMPLETED` | Visit#5 `…/complete` | `completingTheVisitCompletesTheAppointment` | `visit/VisitCompleteIT:185` | E2E |
| 8 | `CHECKED_IN` → `CANCELLED` | Visit#6 `…/cancel` | `cancellingTheWaitingVisitCancelsTheAppointment` | `visit/VisitProgressIT:421` (`late_cancel = false`, trả quota) | stand-in `CashierShiftQueryApi` (luồng không gọi) |

Cặp sai trong `AppointmentFsmIT#leavingBookedRejectsRescheduleCancelAndCheckIn` (12 cặp, **trả G3**): reschedule / cancel / check-in × nguồn `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW` (nguồn đi bằng API) → 409, `xmin` dòng `appointments` và số `visits` / `orders` không đổi. 409 đứng trước mọi 400 (ADR-0049 / 0050); riêng check-in, guard 400 của visit (chi nhánh, thú khớp, thú còn sống, giờ mở cửa) đứng trước nên lần thử luôn trong giờ, thú còn sống — nguồn `CANCELLED` vì vậy đi bằng lễ tân hủy, không bằng thú mất. Cancel lịch `CHECKED_IN` cũng 409: cạnh #8 chỉ dành cho Visit#6. IT cũ còn phủ: race check-in × hủy / đổi giờ (`appointment/AppointmentVisitApiIT:145–180`).

### 2.3 Visit (6) — `visit/fsm/VisitTransitionHandler`

**`visit/VisitFsmIT` (T40.5) phủ cả 6 dòng E2E trên bean thật**, mỗi chuyển kiểm cả Lịch hẹn và Order (lượt luôn có lịch đặt qua `POST /api/appointments`; era 2028). *Mức* là của IT cũ.

| # | Chuyển | Kích hoạt thật | `VisitFsmIT` (Visit · Lịch hẹn · Order) | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `WAITING` | `POST /api/visits` | `checkInOpensTheVisitWithItsOrder` (· `CHECKED_IN` · `OPEN` + 1 dòng tự sinh) | `VisitCheckInIT:155`, `:233` | stand-in `BoardingQueryApi` |
| 2 | `WAITING` → `WAITING` | `…/assign` | `assigningAWaitingVisitKeepsItWaiting` (gán rồi gán lại; chủ dòng tự sinh đổi theo) | `visit/VisitDispatchIT:176`, `:211` | stand-in `OrderQueryService` |
| 3 | `WAITING` → `IN_PROGRESS` | `…/call` | `callingStartsTheVisit` | `visit/VisitProgressIT:185` | stand-in `CashierShiftQueryApi`, `OrderQueryService` |
| 4 | `IN_PROGRESS` → `IN_PROGRESS` | `…/reassign` | `reassigningACalledVisitAfterTheAssigneeIsLocked` (ADMIN khóa → `needs_reassign` → manager gán lại; 1 audit) | `visit/VisitReassignIT:180` | stand-in `CashierShiftQueryApi` |
| 5 | `IN_PROGRESS` → `COMPLETED` | `…/complete` | `completingClosesTheVisitTheAppointmentAndTheOrder` (· `COMPLETED` · `PENDING`; bệnh án khóa) | `visit/VisitCompleteIT:185`, `:218`, `:271` | E2E |
| 6 | `WAITING` → `CANCELLED` | `…/cancel` | `cancellingAWaitingVisitCancelsTheAppointmentAndTheOrder` (· `CANCELLED`, không hủy muộn · `CANCELLED`) | `VisitProgressIT:382`, `:421` | stand-in như #3 |

Cặp sai trong `VisitFsmIT#disallowedOperationsAre409AndWriteNothing` (15 cặp, **trả G2**). Nguồn đi bằng API và luôn gán `vetA` trước, vì 403 "không phải người phụ trách" đứng trước 409 (§4.3):

| Nguồn | Thao tác bị từ chối |
|---|---|
| `WAITING` | complete · reassign |
| `IN_PROGRESS` | call lần hai · assign · cancel |
| `COMPLETED` | call · assign · cancel · complete · reassign |
| `CANCELLED` | call · assign · cancel · complete · reassign |

Tất cả → 409; `xmin` của Visit / lịch / Order, số `visit_assignments`, `order_lines` và audit `VISIT_REASSIGNED` đều không đổi. Cặp sai trong IT cũ phần lớn đặt nguồn bằng `UPDATE visits.status` (`VisitDispatchIT:306`, `VisitProgressIT:256`, `:527`, `VisitReassignIT:328`); giữ nguyên.

### 2.4 Care Task (6) — `care/fsm/CareTaskTransitionHandler`

**`care/CareTaskFsmIT` (T40.6) phủ cả 6 dòng E2E trên bean thật** (không mock; mũi tiêm ghi qua `POST /api/visits/{id}/vaccinations`, hẹn tái khám qua bệnh án, task sinh bởi job thật cho hồ sơ tại quầy không email; era 1989 vì `VaccineOverdueJob` không có cận dưới). IT cũ dựng task / mũi bằng SQL; *Mức* là của IT cũ.

| # | Chuyển | Kích hoạt thật | `CareTaskFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `OPEN` | **ST18**, gọi từ ST04 (`VaccineReminderJob`, `FollowUpReminderJob`) và `VaccineOverdueJob` | `jobsOpenOneTaskPerTypeForAProfileWithoutEmail` (3/4 loại, chạy lại không sinh thêm; `PICKUP_OVERDUE` chờ G5) | `visit/VaccineReminderIT:218`, `FollowUpReminderIT:219`, `VaccineOverdueIT:116`; `care/CareTaskApiIT:146` | mũi / bệnh án chèn SQL |
| 2 | `OPEN` → `DONE` | `POST /api/care-tasks/{id}/complete` | `receptionistCompletesTheCall` | `care/CareTaskDeskIT:312`, `:334` | task chèn SQL |
| 3 | `OPEN` → `CANCELLED` | `POST /api/care-tasks/{id}/cancel` | `receptionistCancelsWithAReason` | `CareTaskDeskIT:387` | task chèn SQL |
| 4 | `OPEN` → `CANCELLED` | **ST19**, thú mất (listener visit `@Order` 100 → care 200) | `markingThePetDeceasedCancelsItsOpenTasksOnly` qua `POST /api/pets/{petId}/mark-deceased` (task `DONE` giữ `xmin`) — **trả G4** | `care/CarePetLifecycleIT:113`, `visit/FollowUpCancellationIT:202` | bắn event tay |
| 5 | `OPEN` → `CANCELLED` | Ghi mũi tiêm `POST /api/visits/{id}/vaccinations` (mũi mới cùng loại) | `revaccinationCancelsTheOpenTasksOfTheOlderDose` (task còn `OPEN` sau đặt lịch + check-in, hủy đúng lúc ghi mũi) | `visit/VaccinationRecordIT:164` | task chèn SQL |
| 6 | `OPEN` → `CANCELLED` | Đặt lịch `MEDICAL`; check-in có dịch vụ tự sinh `EXAM` (`FollowUpApi`) | `bookingAMedicalAppointmentCancelsTheFollowUpTask`, `walkInExamCheckInCancelsTheFollowUpTask` (task tái chủng giữ `OPEN`) | `BookAppointmentIT:214`, `VisitCheckInIT:211` | stand-in `JdbcPetCustomerModule` / `BoardingQueryApi`; phạm vi hẹp hơn 02 (§4) |

Cặp sai trong `CareTaskFsmIT#closedTasksRejectCompleteAndCancel` (6 cặp): complete / cancel × nguồn `DONE` (lễ tân), `CANCELLED` (lễ tân), `CANCELLED` (hệ thống, thú mất) — nguồn đi bằng API, body hợp lệ, lễ tân cùng chi nhánh → 409 `INVALID_STATE_TRANSITION`; `xmin`, cột xử lý và số task không đổi. IT cũ còn phủ: complete sau khi hệ thống đã hủy (`CareTaskDeskIT:609`), race với hủy hệ thống (G7). Hệ thống hủy task đã đóng (#4–#6) là **no-op, không phải 409** (`UPDATE … WHERE status = 'OPEN'`, ADR-0079): `CareTaskFsmIT` #4, #5 kiểm `xmin` của task `DONE`.

## 3. Khoảng trống → task con

Chốt 28/10: **mỗi FSM một lớp `*FsmIT`** trên bean thật (không `@MockitoBean`, era riêng, trạng thái nguồn đi bằng API), gom đủ chuyển hợp lệ + cặp sai có đường HTTP; IT cũ giữ nguyên. Lý do: đối chiếu code, nhiều chuyển tưởng E2E thật ra chạy với stand-in (Tài khoản 4/8 — §2.1, Lịch hẹn 7/8 — §2.2, Visit 5/6 — §2.3). Khoảng trống G2–G4 viết vào lớp FSM tương ứng.

| Task | Nội dung | Trạng thái |
|---|---|---|
| T40.1 | Bảng truy vết này | Xong 28/10 |
| T40.2 | **G1**: disable VET còn Visit thật được gán → 400 BR-QT-08 (liệt kê id lượt, không ghi gì), hoàn tất xong thì disable được — `visit/VisitCompleteIT` | Xong 28/10 |
| T40.3 | `identity/AccountFsmIT`: 8 chuyển + 4 cặp sai (§2.1) | Xong 28/10, 9 test |
| T40.4 | `appointment/AppointmentFsmIT`: 8 chuyển + 12 cặp sai (§2.2), gồm **G3** | Xong 28/10, 11 test |
| T40.5 | `visit/VisitFsmIT`: 6 chuyển (kèm Lịch hẹn + Order) + 15 cặp sai (§2.3), gồm **G2** | Xong 29/10, 10 test |
| T40.6 | `care/CareTaskFsmIT`: 6 chuyển + 6 cặp sai (§2.4), gồm **G4** | Xong 29/10, 10 test |
| T40.7 | Chạy lại IT theo nhóm (identity, appointment, visit, care) + cập nhật tài liệu cuối T40 | Xong 29/10: identity 739 xanh; appointment 384, chỉ 2 đỏ đã biết của `AppointmentReminderIT` (`startedOrPastBookedIsNotReminded`, `reminderQueriesUseTheirIndexesOnRealisticData` — thiếu cận dưới, từ `af117e0`); visit 474 xanh; care 134 xanh (14 lớp; số 135 ghi trước đó là đếm nhầm) |

G5 — `PICKUP_OVERDUE` sinh bởi ST15 thuộc BE-2 (T43): chờ, không phải nợ mới.

**Stand-in còn sót trong IT cũ** (nợ của test, không phải của `src/main`; bỏ khi đụng tới lớp đó): `@MockitoBean CustomerApi` ở `RegistrationIT`, `RegistrationVerificationIT`, `PendingAccountCleanupIT` (từ D001, trả 16/10); 6 query API mock ở `AccountLockIT`; `JdbcPetCustomerModule` (`@Primary` thay `CustomerQueryApi`, từ trước T17) ở `BookAppointmentIT`, `RescheduleCancelIT`; `@MockitoBean BoardingQueryApi` (từ D033, đã có bản thật ở T31) ở `RescheduleCancelIT`, `NoShowIT`, `NoShowFlowIT`, `ClinicCancellationIT`, `BookingRestrictionIT`, `visit/VisitCheckInIT`; `@MockitoBean CashierShiftQueryApi` ở `visit/VisitProgressIT`, `VisitReassignIT`, `MedicalRecordIT` (từ D016, trả ở T30); `@MockitoBean OrderQueryService` ở `visit/VisitDispatchIT`, `VisitProgressIT`.

## 4. Đối chiếu với 02 / 03

Bảng không thêm hay bỏ cạnh nào so với 03: 8 + 8 + 6 + 6 dòng khớp từng dòng, tác nhân và điều kiện khớp. Ba điểm cần biết:

1. **Care#6: code hẹp hơn BR-TB-06 / Care Task#6** — đều là quyết định đã ghi, không phải lỗi mới. (a) Check-in chỉ hủy khi dịch vụ **tự sinh** là `EXAM`; đổi dòng sang `EXAM` sau đó (BR-KB-02) không hủy, dù BR-TB-06 viết "Visit mới có dịch vụ loại Khám" (ADR-0084 §4, 06 G5). (b) Lịch `MEDICAL` bị hủy / `NO_SHOW` không khôi phục nhắc (D052 mục 1, chờ PO). (c) Lịch đặt trước lúc bệnh án khóa không hủy nhắc (D052 mục 2, chờ PO).
2. **Tác nhân theo 03:** Care#1 là ST18 (01 §B: ST18 được ST04 / ST15 gọi), Care#4 là ST19.
3. **Thứ tự lỗi ảnh hưởng cách viết G2:** ADR-0069 §2 đặt 403 "không phải người phụ trách" (kể cả lượt chưa gán) trước 409, nên lượt phải được gán cho VET trước khi hủy; nếu không, call / complete trả 403, không phải 409.
