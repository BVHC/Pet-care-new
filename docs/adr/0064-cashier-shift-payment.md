# ADR-0064: Thu ngân — ca thường / ngoài giờ, thu tiền gộp trừ kho FEFO, đối soát, ST13 tự chốt ca và báo Order PENDING quá hạn (T30)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-21
- **Liên quan:**
  - sales-v1 #12–#21 (UC70, UC71, UC72, ST06, ST13); 02 BR-TG-01…05, BR-CN-02, 03, 05, BR-BH-04, 05, 06, BR-KO-05, BR-QT-15; 03 #5 Order#8, #8 Ca thu ngân#1–#6; 05 §8 `cashier_shifts`, `payments`, `orders.payment_id`, nhật ký quyết định mục 10 (phiếu thu 0đ).
  - ADR-0007 (job), 0029–0031 (khóa `accounts FOR SHARE` khi tạo việc dở, nghĩa vụ mục 9), 0035 (người nhận thông báo khi chi nhánh không còn quản lý), 0038 (truy vấn chỉ ghép điều kiện đã gửi), 0040 (Swagger = hợp đồng), 0042 (sổ kho, thứ tự khóa lô), 0043 (mã chứng từ), 0044 (Order lõi, khóa trước đọc sau), 0049 / 0057 (kiểm tại thời điểm chạy), 0051 (Order bán lẻ, bộ lọc `pendingOverdue`).
- **Triển khai:**
  - `sales/entity/{CashierShift, CashierShiftStatus, ShiftTotals, Payment, PaymentMethod}`, `sales/fsm/CashierShiftTransitionHandler` (03 §8 đúng bảng), `SalesOrder.markPaid`.
  - `sales/service/{CashierShiftService, CashierShiftCloseService, ShiftReconcileService, CashierShiftQueryService, CashierShiftAssembler, ShiftTotalsReader, CashierShiftLookupService, PaymentService, PaymentQueryService, PaymentLines, PaymentAssembler, ShiftDeadline, ShiftNoticeRecipients, CashierShiftAutoCloseService, PendingOrderDigestService, PendingOverdueCutoff}`, `sales/job/{CashierShiftAutoCloseJob, PendingOrderDigestJob}` + `*Properties`, `sales/controller/{CashierShiftController, PaymentController}`, `sales/repository/{CashierShiftRepository, CashierShiftListRepository, PaymentRepository, SalesOrderRepository}`.
  - `inventory`: `StockApi.issueForPayment` (thay `issueForSale`), `StockQueryApi.availableQuantities`.
  - `V19__cashier_shift_templates_indexes.sql`: `ix_orders_payment_id`, `ix_cashier_shifts_branch_date`, mẫu `CASHIER_SHIFT_AUTO_CLOSED_APP`, `PENDING_ORDERS_DIGEST_APP`.
  - `CashierShiftQueryApiPlaceholder` xóa (trả nợ D016).
  - Test: xem *Kiểm chứng*.

## Bối cảnh

T19 / T29 dựng Order lõi và Order bán lẻ; Order `PENDING` chưa thu được vì chưa có ca thu ngân và chưa có thu tiền. Đặc tả để ngỏ: khóa nào bảo vệ thu tiền khỏi chốt ca song song, thu gộp nhiều Order trừ kho thế nào để hai quầy không deadlock, cách đo "quá giờ đóng cửa" cho ca tự chốt, ai nhận thông báo, ghi đè số thực đếm khi đối soát.

Đối chiếu thị trường (Square cash drawer, Odoo POS session closing, Shopify POS cash tracking): tiền mặt kỳ vọng tính từ giao dịch của ca, người dùng nhập số thực đếm, chênh lệch hiện ở báo cáo; ca đã đóng không sửa; phiên bị kẹt (thiết bị hỏng) là sự cố có thật nên ST13 tự chốt; quản lý đối soát kèm ghi chú.

## Quyết định

### 1. Thứ tự khóa (thêm vào bảng lock order của CLAUDE.md)

| Luồng | Thứ tự |
|---|---|
| Mở ca | `accounts FOR SHARE` (chính lễ tân, `StaffDirectoryApi.findStaffForShare`) → INSERT `cashier_shifts` (`uq_cashier_shifts_one_open` chặn ca thứ hai) |
| Thu tiền | `cashier_shifts FOR SHARE` (ca `OPEN` của mình) → `orders` (**một** câu `ORDER BY id FOR NO KEY UPDATE`) → mọi lô của hợp sản phẩm (**một** câu, `StockApi.issueForPayment`) → `document_counters` (`payments`, sinh mã **cuối cùng**) |
| Chốt ca / đối soát | `cashier_shifts FOR NO KEY UPDATE` → SUM `payments` (READ COMMITTED nên thấy phiếu thu đã commit) |
| ST13 tự chốt | `cashier_shifts FOR NO KEY UPDATE SKIP LOCKED` → SUM `payments` → outbox |

`FOR SHARE` của thu tiền xung đột với `FOR NO KEY UPDATE` của chốt ca nhưng không xung đột với nhau: nhiều lần thu cùng ca chạy song song, chốt ca chờ chúng xong rồi mới tính tổng nên **mọi phiếu thu đã commit đều nằm trong `expected_*`**; phiếu thu đến sau khi ca đã chốt không còn thấy ca `OPEN` (điều kiện `status = 'OPEN'` được đánh giá lại sau khi chờ khóa, READ COMMITTED) → 400 BR-TG-01. Bắt buộc READ COMMITTED: dưới REPEATABLE READ cùng tình huống trả 409 "could not serialize" (mutation bên dưới).

### 2. Thu gộp trừ kho (Order#8, BR-BH-04)
1. `StockApi.issueForPayment(branchId, List<OrderSale>, actorId)` thay `issueForSale` (chưa có bên gọi): khóa lô của **hợp** sản phẩm mọi Order trong **một** câu id tăng dần, kiểm tổng yêu cầu từng sản phẩm, thiếu bất kỳ sản phẩm nào thì không trừ gì và trả `Shortage(productId, tổng yêu cầu, tồn khả dụng)`; đủ thì trừ FEFO lần lượt theo `orderId` tăng dần trên cùng tập lô đã khóa, mỗi (Order, lô) một movement `SALE` nguồn `orders/<orderId>`. Gọi từng Order riêng sẽ khóa lô nhiều câu theo thứ tự sản phẩm của từng Order → hai quầy thu gộp chéo sản phẩm deadlock (IT + mutation).
2. Chỉ dòng `GOODS` và `DRUG` trừ kho; `VACCINE` đã trừ khi tiêm (BR-KB-04), `SERVICE` / `BOARDING` không có hàng.
3. Thiếu tồn → 400 BR-BH-04, message nêu tên các sản phẩm thiếu (tên đã snapshot trên dòng) và đường xem chi tiết là `POST /payments/preview`; rollback toàn bộ.
4. Thứ tự lỗi: 400 BR-TG-01 (chưa có ca) → 404 → 403 chi nhánh → 409 có Order không `PENDING` → 400 BR-TG-03 → 400 BR-TG-02 → 400 BR-BH-04. Khóa ca đứng đầu vì nó đứng đầu thứ tự khóa; 409 trước rule như ADR-0049 Q6 / ADR-0051.
5. Số tiền phải bằng tổng các Order, cho phép 0 (Order 0đ vẫn thu, vẫn qua ca và audit — erd mục 10). Tổng cộng `addExact`, tràn → 400 `VALIDATION_FAILED`.
6. Một phiếu thu tối đa 20 Order, `orderIds` không trùng (Bean Validation). Không có khóa idempotency: gửi lại sau khi đã thu nhận 409 (Order không còn `PENDING`), không thu hai lần; hai request song song cùng Order → 1 × 201, 1 × 409 (IT).
7. Mã phiếu thu `PT-yyMM-nnnnn` từ `DocumentCodeGenerator("payments", "PT", ngày VN lúc thu)`, sinh sau khi trừ kho để `document_counters` bị giữ ngắn nhất (hàng nóng: mọi phiếu thu toàn chuỗi cùng một dòng mỗi tháng — chấp nhận như ADR-0043).
8. Audit `ORDER_PAID` **mỗi Order** (convention 08 L66; before `{status}`, after `{status, paymentId, paymentCode, method, amount}`). Không audit mở / chốt / tự chốt ca (BR-QT-15 không liệt kê; log INFO `CASHIER_SHIFT_CLOSED`, `CASHIER_SHIFT_AUTO_CLOSED`, `PAYMENT_COLLECTED`).
9. Xem trước (#13): `readOnly` + REPEATABLE READ, không khóa, không cần ca mở, không ghi gì; mỗi **dòng** của sản phẩm thiếu một `ShortageLine` (`requested` = số lượng dòng, `available` = tồn khả dụng của sản phẩm, một câu `availableQuantities`). Lúc thu thật mọi điều kiện kiểm lại dưới khóa nên xem trước chỉ là gợi ý.

### 3. Ca thu ngân (Ca thu ngân#1–#6)
1. **Mở ca (#16):** lễ tân; khóa `accounts FOR SHARE` của chính mình (nghĩa vụ ADR-0031 mục 9: điều chuyển / vô hiệu hóa / khóa song song phải thấy ca mới, hoặc ca mới thấy chi nhánh mới) → đã có ca `OPEN` 409 → ca ngoài giờ: chi nhánh không có cờ 400 BR-CN-05, đang trong giờ mở cửa 400 BR-TG-05; ca thường: ngoài giờ mở cửa / ngày nghỉ / nghỉ trưa (giữa hai khoảng) 400 BR-TG-05. `business_date` = ngày VN lúc mở; ca ngoài giờ qua nửa đêm vẫn thuộc ngày đó. Không khóa `branches`, không chặn chi nhánh `DRAFT` (không có rule; check-in cũng vậy — ADR-0058). Hai tab mở ca cùng lúc: unique index → 409.
2. **Chốt ca (#18):** chỉ chủ ca (ca người khác kể cả cùng chi nhánh 403, không có 404); khóa dòng ca → không `OPEN` 409 → tổng phiếu thu **sau khóa** → `expected_cash`, `expected_transfer`, `counted_cash`; `difference` để trống tới đối soát (erd).
3. **Đối soát (#21):** BRANCH_MANAGER của chi nhánh; ca `CLOSED` mới đối soát (409 khác); ca tự chốt chưa có số thực đếm mà request không gửi `countedCash` → 400 BR-TG-05; `countedCash` gửi lên **ghi đè** số lễ tân đã nhập, audit giữ số cũ; `note` luôn bắt buộc; `difference = thực đếm − tiền mặt hệ thống`; `RECONCILED` là cuối (như Square: ca đã đóng không sửa). Audit `CASHIER_SHIFT_RECONCILED` (convention 08 L70).
4. **Xem (#17, #19, #20):** `readOnly`; danh sách REPEATABLE READ, lọc `status`, `businessDate`, `cashierId`, sắp `business_date DESC, id DESC` (`ix_cashier_shifts_branch_date`), `paymentCount` một câu GROUP BY, tên lễ tân một lần `findStaffByIds`; chi tiết: BRANCH_MANAGER cùng chi nhánh, lễ tân chỉ ca của mình (403 khác).
5. `CashierShiftQueryApi` thật (`CashierShiftLookupService`, một câu SQL đọc thuần): điều chuyển, vô hiệu hóa và khóa A05–A08 của identity hết lỗi 500, lễ tân còn ca `OPEN` bị BR-QT-08 (trả D016).

### 4. ST13
1. **Tự chốt ca** (`CashierShiftAutoCloseJob`, mặc định `0 4/5 * * * *` — phút 4, 9, 14… lệch ST05 `0 */5`, ST03 `2/5`, ST02): quét keyset id các ca `OPEN`, mỗi ca một transaction `FOR NO KEY UPDATE SKIP LOCKED` (ca đang có phiếu thu giữ `FOR SHARE` bị bỏ qua, lượt sau làm), hạn **tính lúc chạy** từ giờ mở cửa hiện hành và [CFG] (ADR-0057, áp dụng từ lần kiểm kế tiếp):
   - Ca thường: giờ đóng của khoảng cuối trong ngày mở ca + `cashier_shift.auto_close_grace_minutes`. Ngày không còn khoảng nào (ngày nghỉ khai báo sau khi mở ca) → 00:00 ngày kế tiếp, không chốt giữa lúc đang làm việc.
   - Ca ngoài giờ: `BranchQueryApi.nextOpeningStart` tính từ **lúc mở ca**; không có giờ mở cửa kế tiếp nào → không tự chốt, log WARN `CASHIER_SHIFT_NO_NEXT_OPENING`.
   - `now ≥ hạn` (bao gồm đúng hạn) → `CLOSED`, `auto_closed`, `counted_cash` NULL → thông báo trong app `CASHIER_SHIFT_AUTO_CLOSED_APP` cho mọi BRANCH_MANAGER đang hoạt động; chi nhánh không còn ai → mọi SUPER_MANAGER (ADR-0035); không có ai nữa → vẫn chốt, WARN. Lỗi thông báo rollback cả việc chốt (IT dùng trigger tạm), job chỉ log `CASHIER_SHIFT_AUTO_CLOSE_FAILED` và thử lại lượt sau.
2. **Báo Order PENDING quá hạn** (`PendingOrderDigestJob`, mặc định `0 50 23 * * *`): một thông báo `PENDING_ORDERS_DIGEST_APP` mỗi chi nhánh có Order `PENDING` chốt trước mốc `PendingOverdueCutoff` — **cùng một bean** với bộ lọc `pendingOverdue` của danh sách Order (#2, ADR-0051), nên số trong thông báo bằng `totalElements` của danh sách (IT). Người nhận như trên. Không đánh dấu đã báo, không thêm bảng (lệch ERD): chạy lại tay thì báo lại (nợ). Câu GROUP BY toàn chi nhánh có thể quét tuần tự `orders` mỗi đêm; chấp nhận ở quy mô chuỗi phòng khám, theo dõi nếu `orders` vượt vài triệu dòng.

### 5. Hoãn
`POST /orders/{orderId}/lines/{lineId}/move-to-external-purchase` (#11) chưa cài: cần `PrescriptionApi.markExternalPurchaseAtPayment` của visit (BE-1 giao 26/10, chưa có bean) và chưa thể có dòng `DRUG` (kê đơn T27 / T28 chưa có). Thu tiền thiếu thuốc kê đơn vẫn từ chối 400 BR-BH-04. Nợ ghi ở `docs/dept/T30-thu-ngan.md`.

## Giả định đã chốt (người dùng giao cho người lập kế hoạch; đối chiếu thị trường)
D3 (người nhận: quản lý → SUPER_MANAGER), D4 (digest không thêm bảng), D5 (ngày nghỉ khai báo sau → 00:00 hôm sau), D6 (`ShortageLine` theo dòng), D7 (đối soát ghi đè), D9 (tối đa 20 Order), D10 (không idempotency key), D11 (audit), D15 (ca ngoài giờ không có giờ mở cửa kế tiếp → không tự chốt) — nội dung ở các mục trên. Tên command theo operationId: `openCashierShift`, `getMyCurrentShift`, `closeCashierShift`, `listCashierShifts`, `getCashierShift`, `reconcileCashierShift`, `collectPayment`, `previewPayment`, `getPayment`, `listPayableOrders`; job: `autoCloseCashierShift`, `notifyPendingOrders`.

## Kiểm chứng
- Unit: `CashierShiftTransitionHandlerTest` (mọi cặp), `CashierShiftTest`, `SalesOrderTest.markPaid`, `CashierShiftServiceTest`, `CashierShiftCloseServiceTest`, `ShiftReconcileServiceTest`, `CashierShiftQueryServiceTest`, `ShiftTotalsReaderTest`, `PaymentLinesTest`, `PaymentServiceTest` (`InOrder` khóa ca → khóa Order → kho → mã → ghi → audit; mọi nhánh lỗi không ghi), `PaymentQueryServiceTest`, `ShiftDeadlineTest`, `CashierShiftAutoCloseServiceTest`, `PendingOverdueCutoffTest`, `PendingOrderDigestServiceTest`, `*JobTest`, `SalesJobPropertiesTest`, `StockIssueServiceTest` (multi-Order), `*RequestValidationTest`, `*ControllerSecurityTest`.
- IT (Postgres 17 thật): `CashierShiftLookupIT`, `CashierShiftIT` (mở / chốt, race mở ca, mở ca chờ điều chuyển), `CashierShiftReadReconcileIT`, `PaymentIT` (18), `PaymentQueryIT`, `CashierShiftAutoCloseIT`, `CashierShiftAutoCloseScheduleIT`, `PendingOrderDigestIT`, `StockApiIT` (+4: `issueForPayment`, `availableQuantities`, deadlock chéo), `StaffTransferPlaceholderIT` (D016 trả), `SchemaMigrationIT` (V19), `OpenApiContractParityIT` (114 endpoint).
- **Mutation** (mỗi cái làm test đỏ, đã hoàn nguyên): (a) bỏ `FOR SHARE` ca → `closeWaitsForInFlightPaymentAndCountsIt`, `paymentAfterTheShiftWasClosedWhileWaitingIsRejected` đỏ; (b) gọi `issueForPayment` từng Order → `crossProductCombinedPaymentsDoNotDeadlock` đỏ (deadlock / timeout); (c) `REPEATABLE_READ` cho `collectPayment` → `paymentAfterTheShiftWasClosedWhileWaitingIsRejected` đỏ (409 thay 400); (d) `findStaff` thay `findStaffForShare` khi mở ca → `openWaitsForAConcurrentTransferAndOpensAtTheNewBranch` đỏ; (e) bỏ `SKIP LOCKED` ở ST13 → `autoCloseSkipsAShiftHeldByAPayment` treo (không bao giờ xanh). Không kiểm: `save` thay `saveAndFlush` cho `Payment` (lỗi DB hiện ra ở `orders.flush()` ngay trong cùng transaction); khóa Order từng câu (hai phiếu thu khác Order không tranh khóa Order, bảo vệ thật là khóa lô — mutation (b)).

## Hệ quả
- BE-1: điều chuyển, vô hiệu hóa, khóa A05–A08 chạy thật; lễ tân còn ca `OPEN` bị BR-QT-08 (06 §6).
- Visit (T26 / T27): khi có kê đơn, thiếu thuốc lúc thu cần `PrescriptionApi.markExternalPurchaseAtPayment` rồi #11 (nợ).
- Số V kế tiếp **V20**, ADR kế tiếp **0065**. V19 và ADR-0064 chưa xác nhận với BE-1 tại thời điểm viết (kiểm `origin/manh` trống ngày 2026-10-21).
