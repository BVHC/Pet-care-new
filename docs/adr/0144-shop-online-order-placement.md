# ADR-0144: Đặt hàng online và giữ hàng (task 117, BE-2)

- **Trạng thái:** Accepted (autopilot BE-2, 17/11/2026)
- **Ngày:** 2026-11-17
- **Liên quan:** `docs/08-tang3-be2-spec.md` §B (BR-BH-08, BR-BH-09, BR-BH-12, FSM #14 dòng 1, #14b Order#11, "Thứ tự khóa khi đặt hàng"), `docs/api/shop-v1.md` (`listFulfillmentBranches`, `quoteOnlineOrder`, `placeOnlineOrder`), `V42__orders_online_source.sql`, `V44__online_orders_and_carts.sql`, ADR-0006, ADR-0043, ADR-0064, ADR-0137 (D5, D6), ADR-0140 (ST09), ADR-0143, `docs/06-module-contracts.md` §6b hàng 3, 23.
- **Triển khai:** `com.petcare.module.shop` (`OnlineOrderController`, `OnlineOrderService`, `OnlineOrder`, `OnlineOrderRepository`, `OnlineOrderMapper`, DTO); `inventory`: `StockApi.lockAvailableQuantity`, `StockApi.reserve`, `StockQueryApi.findAvailableQuantity` và mọi nơi tính tồn trừ `HELD` (commit `a01b879`); `sales`: `OnlineSalesOrderApi.createPendingOrder`, `OrderSource.ONLINE`, chặn quầy (commit `92dbf31`, `488bac1`); `customer`: `CustomerQueryApi.findAddress`. Không migration (V42, V44 đã có schema).

## 1. Quyết định

**P1. Thứ tự khóa của đặt đơn** (tinh chỉnh ADR-0137 D5: mã chứng từ phải có trước các INSERT). Một transaction: `carts` của khách `FOR NO KEY UPDATE` → đọc chi nhánh (`BranchQueryApi.findBranch`, phải `ACTIVE`, không `lockForBooking`) → đọc địa chỉ → `products FOR SHARE` của các dòng giỏ (id tăng dần: chốt giá, kiểm lại điều kiện bán online) → mọi `stock_lots` của các sản phẩm đó tại chi nhánh trong **một** câu `ORDER BY id FOR NO KEY UPDATE` (`StockApi.lockAvailableQuantity`) → mọi dòng đủ hàng hoặc 400 `BR-BH-08` nêu dòng thiếu (chưa ghi gì) → `document_counters` (mã Order rồi mã đơn online) → INSERT `orders` + `order_lines` (`OnlineSalesOrderApi.createPendingOrder`) → INSERT `online_orders` → INSERT `stock_reservations` `HELD` (`StockApi.reserve`) → xóa đúng các dòng giỏ đã đặt → outbox. Khớp "products FOR SHARE → lô" của bán lẻ và "lô → document_counters" của thu tiền; INSERT hàng mới không tranh khóa nào. `reserve` khóa lại lô (khóa đã giữ nên là no-op) và kiểm lại; thiếu ở đó là lỗi lập trình (`IllegalStateException`, rollback) vì lô đã khóa và đã kiểm trước.
- Giỏ chưa có dòng `carts` coi như giỏ rỗng (400 `BR-BH-08`). Thứ tự lỗi: 401 → 403 → 404 chi nhánh không có → 400 chi nhánh chưa `ACTIVE` → 404 địa chỉ → 400 giỏ rỗng → 400 thiếu hàng.
- Hợp đồng liệt kê 409 `CONCURRENCY_CONFLICT` ("hàng vừa bị người khác giữ"). Vì lô được khóa và kiểm dưới khóa, hai khách đua nhau luôn ra 201 + 400 `BR-BH-08`; 409 chỉ còn khi DB báo deadlock / lỗi khóa (đường chung của `GlobalExceptionHandler`).

- Giới hạn đã biết của `reserve`: nó khóa lại lô sau khi sinh mã, nên có thể khóa thêm một lô không có ở bước kiểm (phiếu nhập commit giữa chừng, hoặc `today` sang ngày mới làm đổi tập lô chưa hết hạn). Không deadlock (lô mới có id lớn hơn; không ai giữ lô rồi chờ hàng đợi `document_counters` của Order); trường hợp nửa đêm chỉ cho 500 từ `IllegalStateException` rồi rollback. Chấp nhận, không truyền id lô từ bước 4 vào `reserve`.

**P2. Kho** (task 1): `lockAvailableQuantity` (khóa lô một câu rồi trả tồn khả dụng trừ `HELD`), `reserve` (một dòng `HELD` mỗi sản phẩm, thiếu một dòng thì không giữ gì và trả `Shortage`), `findAvailableQuantity` (không khóa). `release`, `consumeReservation`, `restock` vẫn là placeholder (task 125 / 127 / 138).

**P3. `HELD` được trừ ở mọi nơi tính tồn khả dụng** (08 rủi ro 2, T54 "bắt buộc"): `availableQuantity(ies)`, `branchIdsWithAvailableStock`, `productIdsWithAvailableStock`, cột `available` của danh sách / chi tiết tồn, `issueForPayment` và `lockFefoLot` (quầy và tiêm không xuất được phần đã giữ cho đơn online). **Cố ý không trừ:** `hasStockAnywhere` (BR-SP-05: "còn hàng ở đâu đó", kể cả lô hết hạn) và bản tóm tắt ST08 `lowStock` (báo cáo tồn thực, không phải khả dụng). Ngược lại cờ `available` / `lowStock` / bộ lọc `belowMin` của **danh sách tồn dành cho nhân viên** trừ `HELD` (hàng đang giữ đã cam kết cho khách, nhân viên cần thấy thiếu); `totalQuantity` vẫn là tồn thực.

- Phần `HELD` có thể lớn hơn tồn thực khi hàng giảm bằng đường không phải bán (điều chỉnh giảm `StockAdjustmentService`, bỏ dòng phiếu nhập `StockReceiptService` không xét `HELD`): tồn khả dụng bị chặn ở 0, đúng thực tế vật lý; cách xử lý đơn đang giữ mà không còn hàng thuộc task 126 (xử lý và giao đơn).

**P4. Sales:** `OrderSource.ONLINE`, `OrderCancelType.ONLINE_CUSTOMER` / `ONLINE_EXPIRED`, `OnlineSalesOrderApi.createPendingOrder(code, branchId, customerId, accountId, lines)` (chỉ INSERT: Order `PENDING` với `pending_at`, dòng `GOODS` giá chụp, tổng tính lại). Quầy không chạm Order ONLINE, dùng đường lỗi có sẵn: thu tiền (`collectPayment`) và hủy chưa thu (BR-BH-06) và sửa dòng → **409 `INVALID_STATE_TRANSITION`**; xem trước phiếu thu #13 → **400 `BR-TG-01`**; danh sách chờ thu #12 và ST13 / `pendingOverdue` loại ONLINE. Danh sách Order của nhân viên vẫn hiện Order ONLINE (chỉ đọc, `?source=ONLINE`). **Chấp nhận:** Order ONLINE `PENDING` có hiện trong danh sách đơn chưa thanh toán của khách (`findUnpaidOrdersByCustomer`) vì đó là đơn đang mở của chính khách.

**P5. Mã đơn online** `DO-yyMM-00001` (docType `ONLINE_ORDER`, tiền tố `DO`; `DocumentCodeGenerator`): 08 ghi `DH-yyMMdd-xxxx`, vừa trùng tiền tố mã Order (`DH`) vừa không đúng định dạng của bộ sinh mã (chỉ `yyMM`, 5 chữ số). Đã sửa 08. Mã Order vẫn `("ORDER", "DH")`, do shop sinh rồi truyền cho sales.

**P6. Phí vận chuyển** nằm ở `online_orders.shipping_fee` (chốt từ `ShippingFeeApi.quote(subtotal)` lúc đặt, BR-QT-13), **không** là dòng Order. `total` của đơn online = tổng Order + phí; số tiền cổng thanh toán (task 127) lấy theo `total` đó. **Giả định:** báo cáo doanh thu (lấy từ Order) không gồm phí vận chuyển; Order ONLINE sẽ hiện thành nhóm `ONLINE` trong báo cáo doanh thu khi được `PAID` (task 127).

**P7. `payment_due_at`** = bây giờ (`Clock`, cắt về micro giây) + [CFG] `shop.payment_window_hours` (`SystemConfigApi.getInt`), chụp vào dòng (BR-QT-13): đổi cấu hình sau đó không đổi hạn của đơn đã đặt.

**P8. Địa chỉ:** `CustomerQueryApi.findAddress(customerId, addressId)` thật; địa chỉ không có hoặc của khách khác → **404** (ADR-0006: dữ liệu của người khác không lộ sự tồn tại; hợp đồng ghi cả 404 lẫn 400 "không phải của khách", chọn 404 cho cả hai). `receiver_name`, `receiver_phone` chụp nguyên; `ship_address` = `addressLine, ward, province` (bỏ phường nếu rỗng); dài hơn 300 ký tự → 400 `BR-BH-08` (không cắt ngầm).

**P9. Dòng được đặt** = các dòng giỏ còn bán được theo luật của task 116 (`CartService.eligible`), kiểm lại dưới `products FOR SHARE` và lấy **giá lúc đặt**. Rỗng sau lọc → 400 `BR-BH-08`. Sau khi đặt xóa đúng các dòng đã đặt; dòng không bán được ở lại giỏ (vẫn ẩn).

**P10. `listFulfillmentBranches`:** chi nhánh `ACTIVE` mà `findAvailableQuantity` ≥ số lượng mọi dòng (không khóa); giỏ rỗng → rỗng. `quoteOnlineOrder`: cùng kiểm tra như đặt (chi nhánh, địa chỉ, giỏ, tồn), không khóa, không ghi gì; `freeShippingApplied` = `freeThreshold > 0 && subtotal >= freeThreshold` (phí cơ bản bằng 0 không phải "miễn phí vì đạt ngưỡng").

**P11. Thông báo:** `ONLINE_ORDER_PLACED_APP` (IN_APP, tới tài khoản khách) + `ONLINE_ORDER_PLACED` (EMAIL, tới email của tài khoản) trong cùng transaction, đủ biến `ma_don`, `tong_tien` (`400.000 đ`), `han_thanh_toan` (`HH:mm dd/MM/yyyy`, giờ Việt Nam), `ten_khach`; tài khoản `PENDING` không nhận (BR-TB-02, như thông báo lịch hẹn).

**P12. Không audit** việc khách tự đặt hàng (như giỏ hàng, ADR-0143, và đặt lịch của khách). `OnlineOrderResponse.refundStatus` luôn null cho đơn mới (kiểu `shop.api.RefundStatus`, giá trị REQUESTED / SUCCEEDED / FAILED như hợp đồng).

## 2. Hệ quả

- `OpenApiContractParityIT` pin 206 → 209 (3 handler mới). Không đổi hợp đồng sinh từ `m_shop.py`.
- Hành vi: mọi số tồn khả dụng đã trừ `HELD` (hiện bằng 0 cho tới khi có đơn online); xuất kho quầy không lấy được phần đã giữ.
- Rollback: revert, không có migration dữ liệu. Hiệu năng: một câu khóa lô mỗi lần đặt; truy vấn tồn thêm `LEFT JOIN` / truy vấn con trên `stock_reservations` (index riêng một phần `ix_stock_reservations_held`).

## 3. Kiểm thử và đột biến

- Unit `OnlineOrderServiceTest` (10): `InOrder` giỏ → chi nhánh → địa chỉ → sản phẩm → lô → mã → Order → `online_orders` → giữ hàng → dọn giỏ → thông báo; lỗi không ghi gì và không tiêu mã; hạn thanh toán; ranh giới miễn phí.
- IT `OnlineOrderPlaceIT` (14, đồng hồ era 2005 dùng chung `CashierClockConfig`, không lấy era mới): đường chính, giá / phí / hạn chụp, dòng không bán được ở lại giỏ, giỏ rỗng, chi nhánh 404 / nháp 400, địa chỉ lạ 404, thiếu hàng không ghi gì, lô hết hạn bị bỏ, `HELD` của đơn khác bị trừ, báo giá không ghi gì, chi nhánh xử lý, nhân viên 403 / chưa đăng nhập 401; **đua hàng cuối** (hai khách cùng đặt: đúng một 201, một 400 `BR-BH-08`, Σ `HELD` ≤ tồn thực) và **đặt đơn so với thu tiền quầy** theo cả hai thứ tự xếp hàng chờ khóa lô.
- Đột biến (đều hoàn lại):
  1. Bỏ khóa lô ở đặt đơn (đọc tồn bằng `findAvailableQuantity` không khóa) → hai IT đua đỏ (một cái 500 từ `reserve`, một cái không thấy ai chờ khóa).
  2. `issueForPayment` bỏ trừ `HELD` → IT "đặt đơn xếp trước thu tiền" đỏ (quầy xuất được hàng đã giữ).
  3. Tiêu mã chứng từ trước khi kiểm tồn → unit "không tiêu mã khi thiếu hàng" đỏ. IT không bắt được vì mã tiêu trong transaction bị rollback cùng lỗi; giá trị của thứ tự này là tránh giữ `document_counters` trong lúc chờ khóa lô, nên chỉ unit `InOrder` bảo vệ.
  4. (task 1) `lockIssuableLots` đổi `FOR NO KEY UPDATE` → `FOR KEY SHARE` → ba IT đỏ.
