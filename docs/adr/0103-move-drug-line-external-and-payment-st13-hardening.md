# ADR-0103: sales-v1 #11 chuyển dòng thuốc sang mua ngoài lúc thu; siết thu tiền và ST13 (T30-8…14)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-05
- **Liên quan:** BR-BH-04, BR-KB-03, BR-KB-02, BR-TG-01…05, BR-QT-15; sales-v1 #11, #13, #14, A10; ADR-0044 (khóa Order), 0064 (thu tiền, ca, ST13), 0076 (`PrescriptionApi`), 0088 (N5, mở khóa), 0057 (cửa sổ kiểm lúc chạy).
- **Triển khai:** `OrderExternalPurchaseService` + handler trong `OrderController`; `SalesOrderRepository.findBranchIdById`; hằng audit `ORDER_LINE_MOVED_EXTERNAL`; phần thu tiền / ST13: xem các mục dưới. Không migration. Thay ADR-0064 ở hai điểm (mục 8 và 10).
- **Nợ trả:** T19-D01 (còn #11), T30-1; T30-8, 9, 10, 11, 12, 13, 14 (phần N+1); T45-6.

## A. sales-v1 #11 — `POST /api/orders/{orderId}/lines/{lineId}/move-to-external-purchase`

`PrescriptionApi.markExternalPurchaseAtPayment` có từ 26/10 (ADR-0076) nhưng chưa có bên gọi; luồng thiếu thuốc lúc thu chỉ chạy bằng stand-in trong `PrescriptionIT`.

1. **Thứ tự kiểm:** vai trò (chỉ RECEPTIONIST, 403 `ACCESS_DENIED`) → chi nhánh của Order đọc **không khóa** (`findBranchIdById`, không nạp entity; 404 rồi 403 `ACCESS_DENIED_SCOPE_MISMATCH`) → khóa Order → dòng thuộc Order (404) → Order `PENDING` (409) → dòng `DRUG` của Order `VISIT` (400 `BR-BH-04`) → còn thiếu tồn (400 `BR-BH-04`) → ghi. 404 dòng đứng trước 409 như `OrderLineService`. `branch_id` bất biến nên đọc trước khóa vẫn đúng và không vướng D045 (không nạp entity cũ).
2. **Thứ tự ghi:** `markExternalPurchaseAtPayment` **trước** khi xóa dòng Order (FK `prescription_items.order_line_id` `RESTRICT`), rồi `orders.flush()`, rồi audit, một `@Transactional`, không `noRollbackFor`, không `catch`. Khóa: `orders` → `prescription_items` (chính câu UPDATE của visit). Không khóa `visits` và không khóa lô (ADR-0076 §4); thứ tự ngược với #17 (`prescription_items → orders`) không deadlock vì #17 chỉ chạy khi lượt chưa `COMPLETED`, #11 chỉ khi Order `PENDING`.
3. **"Tồn vẫn đủ" xét trong phạm vi Order này (H3):** dùng đúng `PaymentLines.shortageLines(List.of(order), available)` (cùng công thức `shortages` của #13 với một Order); chuyển được khi `lineId` nằm trong kết quả. Hai dòng cùng thuốc trong một Order thiếu chung thì dòng nào cũng chuyển được; sau khi chuyển một dòng, dòng còn lại tính lại. Đủ cho riêng Order mà chỉ thiếu khi **thu gộp** (A 6 + B 6, tồn 10) thì 400 kèm lời khuyên thu riêng từng Order: không tách một dòng thành hai phần (BR-KB-03). *Giả định chưa PO xác nhận;* sai thì sửa bằng ADR mới.
4. **Tồn đọc không khóa** (`StockQueryApi.availableQuantities`): #11 không đổi kho; sai lệch tệ nhất là chuyển nhầm một dòng vừa có hàng trở về, và lúc thu mọi điều kiện kiểm lại dưới khóa.
5. **Audit** `ORDER_LINE_MOVED_EXTERNAL`, `entity("orders", id)`; `before` = `{lineId, productId, quantity, lineTotal, totalAmount}`, `after` = `{totalAmount, externalReason: OUT_OF_STOCK_AT_PAYMENT}`. Audit lỗi → rollback cả việc chuyển (IT `anAuditFailureRollsBackTheMoveCompletely`).
6. **Không cần ca mở** (hợp đồng không có BR-TG-01 cho #11). Bấm đúp: lần hai 404 (dòng đã mất dưới khóa). Gửi thu với tổng cũ sau khi chuyển: 400 `BR-TG-02`. Order còn tổng 0 sau khi chuyển (dịch vụ khám giá 0) vẫn thu 0đ được (`ck_payments_amount >= 0`). Với giá khám > 0 Order VISIT luôn còn dòng khám (BR-KB-02) nên tổng 0 khó gặp.
7. **Chấp nhận (H6):** dòng `DRUG` của Order `VISIT` không có dòng đơn thuốc (dữ liệu hỏng) → `IllegalStateException` của `PrescriptionApi` → 500 và rollback, đúng hợp đồng `PrescriptionApi`.

### Bảng đảm bảo (#11)

| # | Đảm bảo | Test |
|---|---|---|
| G1 | Khóa → đánh dấu → xóa → flush → audit; tổng giảm đúng `lineTotal` | `OrderExternalPurchaseServiceTest.movesTheLineInLockThenMarkThenRemoveOrderWithAudit`; `PrescriptionIT.aShortageAtPaymentMovesTheDrugToAnExternalPurchaseAndTheSecondPaymentSkipsIt` |
| G2 | Chi nhánh khác 403 trước khi chờ khóa Order | `…otherBranchIs403BeforeAnyLock`; `PrescriptionIT.anotherBranchReceptionistIsRefusedWithoutWaitingForTheOrderLock` |
| G3 | Vai trò: quản lý và lễ tân chi nhánh khác bị 403 | `PrescriptionIT.managerAndAnotherBranchReceptionistAreForbidden`; `OrderControllerSecurityTest` |
| G4 | Order không `PENDING` 409; dòng khác / thiếu 404; không phải `DRUG` của VISIT 400; tồn đủ 400, không ghi gì (`xmin`) | các ca tương ứng của `OrderExternalPurchaseServiceTest`, `PrescriptionIT.moveRefuses…`, `movingTwice…`, `movingAfterPaymentIs409` |
| G5 | Audit lỗi rollback toàn bộ (kể cả `prescription_items`) | `PrescriptionIT.anAuditFailureRollsBackTheMoveCompletely` |
| G6 | Tổng cũ sau khi chuyển bị `BR-TG-02`; Order tổng 0 thu được; hai dòng cùng thuốc | `PrescriptionIT.aPaymentWithTheOldTotal…`, `aZeroPricedExamLeavesAZeroOrderThatCanBePaid`, `twoLinesOfTheSameDrugNeedOnlyTheFirstMove` |
| G7 | Hợp đồng khớp code (186 endpoint) | `OpenApiContractParityIT` |

### Mutation (#11)

Chạy cả `PrescriptionIT` (53 ca) với từng đột biến, hoàn nguyên sau mỗi lần:

| # | Đột biến | Kết quả |
|---|---|---|
| M1 | Xóa dòng Order (flush) **trước** khi đánh dấu đơn thuốc | 5 ca đỏ: `aZeroPricedExam…`, `twoLinesOfTheSameDrug…`, `aPaymentWithTheOldTotal…`, `movingTwice…`, `aPrescribedDrugIsChargedAtCompletion…` (FK `RESTRICT`) |
| M2 | `branchScope.check` đặt sau khi khóa Order | 1 ca đỏ đúng chỗ: `anotherBranchReceptionistIsRefusedWithoutWaitingForTheOrderLock` |
| M3 | Bỏ kiểm "còn thiếu tồn" | 2 ca đỏ: `moveRefusesALineWhoseStockIsEnough…`, `twoLinesOfTheSameDrug…` |

Không đột biến được `REQUIRES_NEW` hoặc bỏ `flush()` trước audit: service là transaction ngoài cùng nên `REQUIRES_NEW` không đổi hành vi, và audit lỗi vẫn rollback dù flush hay không. Hai điểm đó chỉ được giữ bằng khuôn `@Transactional` mặc định + `InOrder` ở test đơn vị.

## Ghi chú đồng bộ

- `OpenApiContractParityIT` đã **đỏ sẵn** ở `3163f22`: hợp đồng `POST /api/visits` (A12) và `POST /api/accounts/{id}/unlock` đã cập nhật theo ADR-0088 nhưng annotation `@Operation` / `@ApiErrorDoc` của `VisitController` và `AccountAdminController` chưa theo kịp. BE-2 đồng bộ **chỉ phần chữ** của annotation hai controller (file của BE-1) để ghim được số 186; báo BE-1 ở `docs/06-module-contracts.md` §6.
- `PrescriptionIT` (BE-1) bỏ stand-in và gọi HTTP #11; cùng ghi ở 06 §6.

## B. Thu tiền: kiểm chi nhánh trước khi khóa, bỏ N+1 dòng Order (T30-8, T30-14)

8. **Phạm vi chi nhánh kiểm trước khi khóa Order** (thay ADR-0064 ở điểm "khóa rồi mới 403"; khớp ADR-0047 / 0049). `PaymentService.collectPayment`: khóa ca `OPEN FOR SHARE` → `SalesOrderRepository.findBranchRows(ids)` (một câu, không khóa, không nạp entity; thiếu id → 404 id nhỏ nhất) → `branchScope.check` từng Order → `findAllByIdForUpdate` (một câu, id tăng dần) → tiếp như cũ. `orders.branch_id` bất biến nên đọc trước khóa vẫn đúng. Lễ tân chi nhánh khác hết phải chờ khóa Order của chi nhánh kia rồi mới nhận 403. Thứ tự lỗi công khai không đổi (BR-TG-01 → 404 → 403 → 409 → BR-TG-03 → BR-TG-02 → BR-BH-04).
9. **N+1 dòng Order:** `@BatchSize(size = 20)` trên `SalesOrder.lines` (20 = trần Order một phiếu thu, A7). Xem trước / thu / danh sách nạp dòng của tối đa 20 Order trong một câu. Chấp nhận `BatchSize` toàn cục cho mọi nơi nạp `SalesOrder` (đọc, không đổi hành vi). Không còn phòng thủ `shift.branchId == order.branchId` (T30-14): điều chuyển bị BR-QT-08 chặn khi còn ca mở (ADR-0031 mục 9) và đổi chức vụ không đổi chi nhánh, nên trạng thái đó không xảy ra.

### Bảng đảm bảo (B)

| # | Đảm bảo | Test |
|---|---|---|
| G8 | Thứ tự `ca → findBranchRows → check → khóa Order`; chi nhánh khác không bao giờ gọi `findAllByIdForUpdate`; thiếu Order 404 trước mọi khóa | `PaymentServiceTest.scopeIsCheckedOnTheUnlockedBranchBeforeAnyOrderIsLocked`, `missingOrderIs404` |
| G9 | Order chi nhánh khác đang bị giữ khóa vẫn nhận 403 ngay (không chờ) | `PaymentIT.anOrderOfAnotherBranchHeldByAnotherTransactionIs403WithoutWaiting` |
| G10 | Xem trước phiếu thu tốn cùng số câu SQL với 1 và 3 Order | `PaymentPreviewCountIT.previewCostsTheSameStatementsForOneAndForThreeOrders` |

Mutation (chạy `PaymentIT` + `PaymentPreviewCountIT`, hoàn nguyên sau mỗi lần): đặt kiểm chi nhánh **sau** khóa Order → `anOrderOfAnotherBranchHeld…` đỏ (chờ khóa quá 10 giây); bỏ `@BatchSize` → `previewCostsTheSameStatements…` đỏ.

## C. ST13 và mở ca (T30-9, 10, 11, 12, 13; T45-6)

10. **ST13 chỉ khóa ca đã đến hạn** (thay ADR-0064 ở điểm "khóa mọi ca `OPEN` mỗi lượt"). `CashierShiftAutoCloseService.autoCloseCashierShift(shiftId, now)`: `findOpenDeadlineRow` (JPQL, cột bất biến `branchId`, `afterHours`, `businessDate`, `openedAt`; không khóa; rỗng → `SKIPPED`) → hạn tính lúc chạy từ giờ mở cửa hiện hành và [CFG] grace (không đổi, ADR-0057) → chưa đến hạn / không có hạn → trả `NOT_DUE` / `NO_DEADLINE` **không khóa** → đến hạn mới `lockOpenSkipLocked` (rỗng → `SKIPPED`) → chốt. Phiếu thu của ca chưa đến hạn không còn chờ job (mỗi 5 phút), và ca đang bị phiếu thu giữ không bị báo "bỏ qua" khi chưa đến hạn.
11. **`closed_at` lấy từ `Clock` sau khi có khóa** (T30-10 = T45-6), không phải lúc job bắt đầu. Phiếu thu giữ `FOR SHARE` trên ca và lấy `paid_at` sau khóa đó; ST13 dùng `SKIP LOCKED` nên không bao giờ chốt xen vào giữa một phiếu thu đang chạy; phiếu đã commit có `paid_at` < thời điểm ST13 giành khóa < `closed_at`, và tiền của nó có trong tổng ca (`ShiftTotalsReader` đọc sau khóa). `now` của job vẫn dùng để so với hạn.
12. **`CASHIER_SHIFT_NO_NEXT_OPENING` hạ từ WARN xuống DEBUG** (T30-11): ca ngoài giờ không còn khoảng mở cửa nào sẽ không bao giờ tự chốt (nợ T30-4), và WARN mỗi 5 phút cho mỗi ca đó là nhiễu. Quản lý vẫn thấy ca `OPEN` ở sales-v1 #19; tổng kết của job vẫn đếm vào `skipped`. Không thêm bộ đếm log riêng (không kiểm thử được bằng test có giá trị).
13. **Mở ca (T30-12):** lễ tân bị khóa / đổi vai / điều chuyển giữa lúc xác thực và `findStaffForShare` nhận **403** `ACCESS_DENIED_SCOPE_MISMATCH` thay vì 500 (`IllegalStateException`); hợp đồng #16 đã có 403. Không ghi gì.
14. **[CFG] `order.pending_alert_days` đọc một lần mỗi lượt digest** (T30-13): `PendingOverdueCutoff.cutoffFor(int)`; job đọc `days()` một lần rồi dùng cho mốc và thông báo, nên hai giá trị không lệch nếu [CFG] đổi giữa lượt. `cutoff()` (danh sách `pendingOverdue`) = `cutoffFor(days())`.

### Bảng đảm bảo (C)

| # | Đảm bảo | Test |
|---|---|---|
| G11 | Chưa đến hạn / không có hạn / ca không còn `OPEN` → không bao giờ gọi `lockOpenSkipLocked` | `CashierShiftAutoCloseServiceTest.aShiftThatIsNotDueNeverTakesTheLock`, `aShiftWithoutADeadlineNeverTakesTheLock`, `shiftThatIsNoLongerOpenIsSkippedWithoutTakingTheLock` |
| G12 | Ca chưa đến hạn không bị `SKIPPED` khi có phiếu thu giữ `FOR SHARE` | `CashierShiftAutoCloseIT.aShiftThatIsNotDueIsNotSkippedByAHeldShareLock` |
| G13 | `closed_at` ≥ `paid_at` và tổng ca gồm phiếu | `CashierShiftAutoCloseServiceTest.closedAtComesFromTheClockTakenAfterTheLockNotFromTheRunStart`, `CashierShiftAutoCloseIT.aPaymentCommittedAfterTheRunStartedStaysBeforeClosedAtAndInTheTotal` |
| G14 | Ca bị phiếu thu giữ → `SKIPPED`, không ghi gì | `…shiftHeldByAPaymentIsSkippedAndNothingIsWritten`, `CashierShiftAutoCloseIT.autoCloseSkipsAShiftHeldByAPayment` |
| G15 | Lễ tân không còn hợp lệ lúc mở ca → 403, không ghi | `CashierShiftServiceTest.staffThatIsNoLongerAnActiveReceptionistWithABranchIsForbiddenNotA500` |
| G16 | [CFG] đọc một lần mỗi lượt digest | `PendingOrderDigestJobTest.readsTheAlertDaysOnceAndDerivesTheCutoffFromThem`, `PendingOverdueCutoffTest.cutoffForUsesTheGivenDays…` |

Mutation (chạy `CashierShiftAutoCloseIT`, hoàn nguyên sau mỗi lần): khóa ca ngay từ đầu (trước khi tính hạn) → `aShiftThatIsNotDueIsNotSkippedByAHeldShareLock` đỏ; `closedAt` lấy từ `now` của job → `aPaymentCommittedAfterTheRunStarted…` đỏ.

## Nợ còn lại sau ADR này

T30-2, 3, 4, 5, 6, 15 và T30-12 (đã trả) xem `docs/dept/T30-thu-ngan.md`; T30-4 (ca ngoài giờ không có khoảng mở cửa nào không tự chốt) vẫn mở.
