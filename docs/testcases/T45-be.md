# T45 (BE): Bảng truy vết 38 chuyển trạng thái — Chi nhánh, Order, Ca thu ngân, Phiếu nhập, Đặt chỗ lưu trú, Chuồng

> Phạm vi: mỗi FSM của BE-2 có một lớp `*FsmIT` chạy trên bean thật (khuôn T40, `T40-be.md`) + 2 E2E. Nguồn: `docs/03-state-machines.md` §2, §5–§9 và *Phụ lục*. Đường dẫn IT tính từ `BE/src/test/java/com/petcare/module/`. Unit `*TransitionHandlerTest` (kế thừa `platform/fsm/FsmTransitionTestBase`) đã duyệt **mọi** cặp `from × to` của 6 enum nên không nhắc lại.

## 1. Quy ước

- **Bean thật** — không `@MockitoBean`, không stand-in `@Primary` (trừ `MutableClock`). Trạng thái nguồn đi bằng endpoint / job thật; dữ liệu nền (nhân viên, loại chuồng, sản phẩm, giờ mở cửa, lô hết hạn) dựng bằng SQL qua `*ItSupport`.
- Cặp **sai** chỉ tính khi có đường HTTP: 409 `INVALID_STATE_TRANSITION` và "không ghi gì" (`xmin` các dòng liên quan, số dòng con, số movement / audit / thông báo không đổi). Cặp không có sự kiện nào kích hoạt (vd `CANCELLED → BOOKED`) chỉ unit phủ.
- Era (năm của `MutableClock`): job không cận dưới lấy era cũ hơn mọi era khác — `CashierShiftFsmIT` **1986** (ST13), `BoardingBookingFsmIT` **1987** (ST05 + ST15); các lớp còn lại dùng năm tương lai chưa ai quét (2033, 2035–2037) hoặc không cần đồng hồ.

## 2. Sáu bảng

### 2.1 Chi nhánh (2) — `branch/fsm/BranchTransitionHandler`; lớp `branch/BranchFsmIT` (5 test)

| # | Chuyển | Kích hoạt thật | `BranchFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `DRAFT` | `POST /api/branches` | `createdBranchIsDraftAndNotPublic` | `branch/BranchAdminIT` | `@MockitoBean` 7 bean (`StaffDirectoryApi`, …) |
| 2 | `DRAFT` → `ACTIVE` | `POST /api/branches/{id}/activate` sau khi có BRANCH_MANAGER (`POST /api/staff`) và giờ mở cửa | `activatingWithAManagerAndOpeningHoursMakesItPublic` | `branch/BranchAdminIT` | như #1 |

Điều kiện / cặp sai: thiếu BRANCH_MANAGER → 400 BR-QT-04; thiếu giờ mở cửa → 400 BR-CN-01 (không ghi gì); kích hoạt chi nhánh `ACTIVE` → 409. Tầng 3 chưa có tạm ngừng nên `ACTIVE` không có cạnh ra.

### 2.2 Order (10) — `sales/fsm/OrderTransitionHandler`; lớp `sales/OrderFsmIT` (nguồn VISIT + RETAIL, 14 test) và `boarding/BoardingBookingFsmIT` (nguồn BOARDING)

| # | Chuyển | Kích hoạt thật | Test | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `OPEN` (VISIT) | Visit#1 `POST /api/visits` | `OrderFsmIT#checkInOpensTheVisitOrderWithOneAutoLine` | `visit/VisitFsmIT` (T40.5), `VisitCheckInIT` | bean thật / stand-in `BoardingQueryApi` |
| 2 | — → `OPEN` (RETAIL) | `POST /api/orders` | `OrderFsmIT#retailOrderOpensEmpty` | `sales/RetailOrderIT` | bean thật |
| 3 | — → `PENDING` (BOARDING) | `POST …/start-checkout` (gọi lại dùng lại Order) | `BoardingBookingFsmIT#startCheckoutPendsOneBoardingOrderAndReusesItOnTheSecondCall` | `boarding/BoardingCheckoutIT` | Order mở SQL |
| 4 | `OPEN` → `PENDING` (VISIT) | Visit#5 `…/complete` | `OrderFsmIT#completingPendsTheVisitOrderAndCancellingCancelsIt` | `visit/VisitCompleteIT` | bean thật |
| 5 | `OPEN` → `PENDING` (RETAIL) | `POST /api/orders/{id}/close` | `OrderFsmIT#closingARetailOrderWithLinesPendsIt`, `closingAnEmptyRetailOrderIs400` | `RetailOrderIT` | bean thật |
| 6 | `OPEN` → `CANCELLED` (VISIT) | Visit#6 `…/cancel` | như #4 | `visit/VisitProgressIT` | stand-in `CashierShiftQueryApi` |
| 7 | `OPEN` → `CANCELLED` (RETAIL) | `POST /api/orders/{id}/cancel` | `OrderFsmIT#cancellingAnOpenRetailOrderKeepsItsLinesAndStock` | `RetailOrderIT` | bean thật |
| 8 | `PENDING` → `PAID` | `POST /api/payments` (gộp RETAIL + VISIT) | `OrderFsmIT#payingSeveralOrdersOfOneCustomerMarksThemPaidAndIssuesStock`; E2E `RetailSaleFlowIT`, `BoardingStayFlowIT` | `sales/PaymentIT` | Order SQL |
| 9 | `PENDING` → `CANCELLED` (BOARDING) | `POST /api/orders/{id}/abort-checkout` | `BoardingBookingFsmIT#abortingTheCheckoutCancelsTheBoardingOrderAndKeepsTheStay` | `BoardingCheckoutIT` | Order SQL |
| 10 | `PENDING` → `CANCELLED` | `POST /api/orders/{id}/cancel-unpaid` (BRANCH_MANAGER) | `OrderFsmIT#managerCancelsUnpaidVisitAndRetailOrders`, `cancellingUnpaidWithoutAReasonIs400AndWritesNothing`; BOARDING: `BoardingBookingFsmIT#managerCancelsTheUnpaidBoardingOrderOnlyAfterThePetLeftTheKennel` | `RetailOrderIT`, `BoardingCheckoutIT` | Order SQL |

Cặp sai trong `OrderFsmIT#disallowedOperationsAre409AndWriteNothing` (năm nguồn, nguồn đi bằng API): `RETAIL_OPEN` → cancel-unpaid, payment · `VISIT_OPEN` → close, cancel, cancel-unpaid, payment · `RETAIL_PENDING` → close, cancel · `PAID` và `CANCELLED` → close, cancel, cancel-unpaid, payment. 409 đứng trước mọi 400 (ADR-0051 §3, ADR-0064 §4). Thêm dòng vào Order `PAID` → 409 (`RetailSaleFlowIT`). Cặp sai của Order BOARDING (`abort-checkout`, `cancel-unpaid` nguồn khác) mang `INVALID_STATE_TRANSITION` ở `sales/OrderCancellationService`, IT cũ `BoardingCheckoutIT` phủ.

### 2.3 Ca thu ngân (6) — `sales/fsm/CashierShiftTransitionHandler`; lớp `sales/CashierShiftFsmIT` (10 test, era 1986)

| # | Chuyển | Kích hoạt thật | `CashierShiftFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `OPEN` | `POST /api/cashier-shifts` trong giờ | `openingDuringHoursStartsARegularShift` | `CashierShiftIT` | bean thật |
| 2 | — → `OPEN` | mở ca ngoài giờ (cờ cấp cứu) | `openingAfterHoursAtAnEmergencyBranchStartsAnAfterHoursShift` | `CashierShiftIT` | bean thật |
| 3 | `OPEN` → `CLOSED` | `…/close` | `closingWithCountedCashClosesTheShift` | `CashierShiftIT` | bean thật |
| 4 | `OPEN` → `CLOSED` | ST13 ca thường, +30 phút [CFG] | `st13AutoClosesARegularShiftThirtyMinutesAfterClosingTime` | `CashierShiftAutoCloseIT` | ca mở SQL |
| 5 | `OPEN` → `CLOSED` | ST13 ca ngoài giờ, giờ mở cửa kế tiếp | `st13AutoClosesAnAfterHoursShiftWhenTheBranchOpensAgain` | `CashierShiftAutoCloseIT` | ca mở SQL |
| 6 | `CLOSED` → `RECONCILED` | `…/reconcile` (ca chốt tay và ca tự chốt) | `reconcilingAManuallyAndAnAutoClosedShift` | `CashierShiftReadReconcileIT` | ca SQL |

Cặp sai: close × {`CLOSED`, `RECONCILED`}, reconcile × {`OPEN`, `RECONCILED`} → 409, ST13 chạy lại không chạm ca đã đóng. Mở ca thứ hai khi còn ca `OPEN` là **409** (ADR-0064 §1: `InvalidStateTransitionException`), không phải 400 BR-TG-01 như plan viết. `difference` chỉ tính khi đối soát (null lúc chốt).

### 2.4 Phiếu nhập (4) — `inventory/fsm/StockReceiptTransitionHandler`; lớp `inventory/StockReceiptFsmIT` (8 test)

| # | Chuyển | Kích hoạt thật | `StockReceiptFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `DRAFT` | `POST /api/stock-receipts` | `createdReceiptIsDraftAndTouchesNoStock` | `StockReceiptIT` | bean thật |
| 2 | `DRAFT` → `CONFIRMED` | `…/confirm` | `confirmingAddsOneLotPerLineWithItsMovement` | `StockReceiptIT` | bean thật |
| 3 | `DRAFT` → `CANCELLED` | `…/cancel` | `cancellingADraftChangesNoStock` | `StockReceiptIT` | bean thật |
| 4 | `CONFIRMED` → `CANCELLED` | `…/cancel`; lô đã bán bớt → 400 BR-KO-04 | `cancellingAConfirmedReceiptReversesEachLot`, `cancellingAConfirmedReceiptAfterStockLeftIs400AndWritesNothing` | `StockReceiptIT` | bean thật |

Cặp sai: confirm × {`CONFIRMED`, `CANCELLED`}, cancel × `CANCELLED` (cả hai loại), `PUT` sửa × {`CONFIRMED`, `CANCELLED`} → 409, `xmin` phiếu, dòng, lô, movement, audit không đổi.

### 2.5 Đặt chỗ lưu trú (11) — `boarding/fsm/BoardingBookingTransitionHandler`; lớp `boarding/BoardingBookingFsmIT` (22 test, era 1987)

| # | Chuyển | Kích hoạt thật | `BoardingBookingFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `BOOKED` | `POST /api/boarding-bookings` (khách, lễ tân) | `customerAndReceptionistBookWithASnapshotRate` | `BoardingBookingIT` | stand-in `VaccinationQueryApi` |
| 2 | giữ nguyên | `…/extend` (`BOOKED`, `CHECKED_IN`) | `extendingKeepsTheStatusWhenBookedAndWhenCheckedIn` | `BoardingChangeIT` | bean thật |
| 3 | `BOOKED` → `CHECKED_IN` | `…/check-in` | `checkInOccupiesTheKennelAndRecordsTheWeight` | `BoardingCheckInIT` | stand-in `VaccinationQueryApi` |
| 4 | `BOOKED` → `CANCELLED` | khách hủy, `late_cancel` ở mốc 24h | `customerCancelSetsLateCancelAtTheTwentyFourHourMark` | `BoardingChangeIT` | bean thật |
| 5 | `BOOKED` → `CANCELLED` | ngày nghỉ, thú mất, chuyển chủ, `cancel-no-kennel` | `clinicCancellationsFromHolidayDeceasedAndTransferAreNeverLate`, `cancelNoKennelCancelsWhenNoKennelOfTheTypeIsAvailable`, `clinicCancellationOfAClosedBookingIsANoOp` | `BoardingClinicCancellationIT`, `BoardingCheckInIT` | bean thật / stand-in |
| 6 | `BOOKED` → `NO_SHOW` | ST05 `BoardingNoShowJob` (19:00 ngày nhận) | `st05MarksAnUncollectedBookingNoShow` | `BoardingNoShowIT` | đặt chỗ SQL |
| 7 | `CHECKED_IN` → `OVERDUE` | ST15 `BoardingOverdueJob` (12:00 ngày trả; +1 ngày: Care Task `PICKUP_OVERDUE` — **trả G5 của T40**) | `st15MarksOverdueAndOpensOnePickupTaskAfterADay` | `BoardingOverdueIT` | đặt chỗ SQL |
| 8 | giữ nguyên | nhật ký bất thường | `abnormalCareLogKeepsTheStatusAndNotifies` | `CareLogIT` | đặt chỗ SQL |
| 9 | giữ nguyên | `…/start-checkout` | `startCheckoutPendsOneBoardingOrderAndReusesItOnTheSecondCall` | `BoardingCheckoutIT` | đặt chỗ SQL |
| 10 | → `CHECKED_OUT` | `…/hand-over` (cần Order `PAID`, BR-LT-09) | `handOverNeedsAPaidOrderThenFreesTheKennel`; E2E `BoardingStayFlowIT` | `BoardingCheckoutIT` | đặt chỗ SQL |
| 11 | → `CHECKED_OUT` | `…/end-deceased`; chặn `mark-deceased` tới lúc đó | `endDeceasedFreesTheKennelAndOnlyThenThePetCanBeMarkedDeceased`; từ `OVERDUE` (#10, #11): `anOverdueStayCanBeHandedOverAfterPaymentAndAnotherEndedAsDeceased` | `BoardingCheckoutIT` | đặt chỗ SQL |

Cặp sai trong `disallowedOperationsAre409AndWriteNothing` (6 nguồn đi bằng API / job): `BOOKED` → start-checkout, hand-over, end-deceased · `CHECKED_IN` → check-in, cancel, cancel-no-kennel · `OVERDUE` → thêm extend · `CHECKED_OUT`, `CANCELLED`, `NO_SHOW` → mọi thao tác trên. Nguồn `CANCELLED` phải hủy **trước ngày nhận** (BR-LT-06 chặn hủy đúng ngày nhận bằng 400).

### 2.6 Chuồng (5) — `boarding/fsm/KennelTransitionHandler`; lớp `boarding/KennelFsmIT` (10 test)

| # | Chuyển | Kích hoạt thật | `KennelFsmIT` | IT cũ | Mức IT cũ |
|---|---|---|---|---|---|
| 1 | — → `AVAILABLE` | `POST /api/kennels` | `createdKennelIsAvailableAndAddsCapacity` | `KennelIT` | bean thật |
| 2 | `AVAILABLE` → `OCCUPIED` | Đặt chỗ#3 `…/check-in` | `checkInOccupiesTheKennel` | `BoardingCheckInIT` | stand-in `VaccinationQueryApi` |
| 3 | `OCCUPIED` → `AVAILABLE` | Đặt chỗ#11 (`end-deceased`) ở đây; #10 (hand-over) ở `BoardingBookingFsmIT` | `endDeceasedFreesTheKennel` | `BoardingCheckoutIT` | đặt chỗ SQL |
| 4 | `AVAILABLE` → `MAINTENANCE` | `…/start-maintenance` | `maintenanceRemovesCapacityAndWarnsAboutShortage` | `KennelIT` | bean thật |
| 5 | `MAINTENANCE` → `AVAILABLE` | `…/end-maintenance` | `endingMaintenanceRestoresCapacity` | `KennelIT` | bean thật |

Cặp sai: start-maintenance × {`OCCUPIED`, `MAINTENANCE`}, end-maintenance × {`AVAILABLE`, `OCCUPIED`} → 409. `PATCH` không đổi được `status`; `DELETE` chuồng `OCCUPIED` → 409.

## 3. Lỗi tìm được và đã sửa

| # | Lỗi | Phát hiện bởi | Sửa |
|---|---|---|---|
| B1 | `POST /api/kennels/{id}/end-maintenance` trên chuồng `OCCUPIED` trả **200** và đặt chuồng về `AVAILABLE` khi thú còn bên trong (handler cho phép cạnh `OCCUPIED → AVAILABLE` của Chuồng#3, service gọi nhầm cho thao tác Chuồng#5): chuồng bị xếp cho thú khác đè lên thú đang ở | `KennelFsmIT#disallowedOperationsAre409AndWriteNothing[OCCUPIED]` (đỏ), `KennelServiceTest#endMaintenanceOnOccupiedKennelIsInvalidTransition` (đỏ → xanh) | `boarding/service/KennelService#endMaintenance` chỉ nhận `MAINTENANCE`, còn lại 409 `INVALID_STATE_TRANSITION` |

## 4. Đối chiếu với 02 / 03

Bảng không thêm hay bỏ cạnh nào so với 03: 2 + 10 + 6 + 4 + 11 + 5 = 38 dòng khớp từng dòng. Ba điểm cần biết:

1. **Mở ca thứ hai là 409**, không phải 400 BR-TG-01 (ADR-0064 §1); 03 Ca thu ngân#1, #2 chỉ nói "chưa có ca `OPEN` của mình" là điều kiện.
2. **Nhánh "thiếu mũi tiêm bắt buộc" khi lưu trú không có IT bean thật.** Các IT lưu trú mới dùng loài chó và `@BeforeEach` khẳng định chó không có loại vaccine bắt buộc (`vaccination_protocols … required_for_boarding AND is_active` = 0), vì phác đồ là dữ liệu dùng chung toàn chuỗi. Nhánh thiếu mũi vẫn do `BoardingCheckInIT` / `BoardingBookingIT` (stand-in) và unit của BE-1 phủ — ghi nợ `docs/dept/T45-fsm-it.md`.
3. **Cặp sai của Order BOARDING** (`abort-checkout` từ Order không BOARDING / không `PENDING`) chưa có test bean thật mới; IT cũ `BoardingCheckoutIT` dựng Order bằng SQL.
