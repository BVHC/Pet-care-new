# Sales & Cashier API v1 — Pet Care v16

> **Module:** BH, TG · **Owner:** BE-2. Order tại quầy (VISIT / RETAIL / BOARDING), dòng Order, chốt / hủy, thu tiền (thu gộp), ca thu ngân, đối soát.
> **Nguồn chân lý:** 01 UC66, UC67, UC70–UC72; 02 BR-BH-01…06, BR-TG-01…05, BR-TN-01, 08, BR-KB-03, 04, BR-LT-09, BR-SP-01, BR-QT-15; 03 #5 Order, #8 Ca thu ngân; 04 §8; 05 §8 (orders, order_lines, payments, cashier_shifts).
> **Contract máy đọc:** [`./openapi/sales-v1.yaml`](./openapi/sales-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC61–UC65 (giỏ hàng, đơn online, thanh toán cổng) và UC68–UC69 (trả hàng) là tầng 3.
- Order VISIT được mở / chốt / hủy theo Visit (Order#1, #4, #6) qua `VisitOrderApi`; Order BOARDING sinh khi bắt đầu trả thú / thú mất (Order#3) qua `BoardingOrderApi`. Không có endpoint tạo Order nguồn VISIT hay BOARDING.
- Dòng DRUG sinh từ kê đơn (`/visits/{visitId}/prescriptions`), dòng VACCINE từ ghi mũi tiêm, dòng BOARDING từ trả thú. Ở đây chỉ thêm dòng SERVICE và GOODS.
- ST06 trừ kho chạy trong `POST /payments`; ST13 (tự chốt ca, cảnh báo Order PENDING quá hạn) là tác vụ hệ thống.

---

## A. Danh sách endpoint (21)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /orders` | Tạo Order bán lẻ | UC66 | BR-BH-01 |
| 2 | `GET /orders` | Danh sách Order | UC66, UC70, ST13 | BR-BH-05 |
| 3 | `GET /orders/{orderId}` | Chi tiết Order | UC66, UC67, UC70 | BR-BH-02, 06 |
| 4 | `POST /orders/{orderId}/lines` | Thêm dịch vụ / hàng vào Order | UC66, UC67 | BR-BH-02, 03, 04, BR-SP-01 |
| 5 | `PATCH /orders/{orderId}/lines/{lineId}` | Sửa dòng (giảm số lượng / đổi dịch vụ tự sinh) | UC66, UC67 | BR-BH-02, 03, 04, BR-TN-01 |
| 6 | `DELETE /orders/{orderId}/lines/{lineId}` | Xóa dòng | UC66, UC67 | BR-BH-02, BR-KB-04 |
| 7 | `POST /orders/{orderId}/close` | Chốt Order bán lẻ | UC66 | BR-BH-01 |
| 8 | `POST /orders/{orderId}/cancel` | Hủy Order bán lẻ chưa chốt | UC66 | BR-BH-01 |
| 9 | `POST /orders/{orderId}/abort-checkout` | Hủy phiên trả thú (khách không thanh toán) | UC59 | BR-LT-09, BR-QT-15 |
| 10 | `POST /orders/{orderId}/cancel-unpaid` | Hủy Order khách không thanh toán | UC66 | BR-BH-05, BR-BC-02, BR-QT-15 |
| 11 | `POST /orders/{orderId}/lines/{lineId}/move-to-external-purchase` | Chuyển dòng thuốc kê đơn sang mua ngoài khi thiếu tồn lúc thu | UC70 | BR-BH-04, BR-KB-03, BR-QT-15 |
| 12 | `GET /customers/{customerId}/payable-orders` | Order PENDING của khách tại chi nhánh | UC70 | BR-TG-01, 03 |
| 13 | `POST /payments/preview` | Xem trước khi thu (tổng tiền, dòng thiếu tồn) | UC70 | BR-TG-02, 03, BR-BH-04 |
| 14 | `POST /payments` | Thu tiền | UC70, ST06 | BR-TG-01…04, BR-BH-04, 06, BR-KO-05 |
| 15 | `GET /payments/{paymentId}` | Chi tiết phiếu thu | UC70 | — |
| 16 | `POST /cashier-shifts` | Mở ca thu ngân | UC71 | BR-TG-01, 05, BR-CN-05 |
| 17 | `GET /me/cashier-shift` | Ca đang mở của tôi | UC70, UC71 | BR-TG-01 |
| 18 | `POST /cashier-shifts/{shiftId}/close` | Chốt ca | UC71 | BR-TG-05 |
| 19 | `GET /cashier-shifts` | Danh sách ca | UC72 | BR-TG-05 |
| 20 | `GET /cashier-shifts/{shiftId}` | Chi tiết ca | UC71, UC72 | BR-TG-05 |
| 21 | `POST /cashier-shifts/{shiftId}/reconcile` | Đối soát ca | UC72 | BR-TG-04, 05, BR-QT-15 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Tạo Order bán lẻ | A06 | Lễ tân | — → OPEN, RETAIL (Order#2) | — | — |
| Danh sách Order | A05, A06, A07, A08 | Nhân viên chi nhánh của Order | — | — | — |
| Chi tiết Order | A05, A06, A07, A08 | Nhân viên chi nhánh của Order | — | — | — |
| Thêm dịch vụ / hàng vào Order | A06, A07, A08 | Lễ tân: GOODS; VET: SERVICE nhóm MEDICAL của Visit mình; CARETAKER: SERVICE nhóm GROOMING của Visit mình | — | — | Giá snapshot lúc thêm. Thiếu tồn bán lẻ: cảnh báo, không chặn. |
| Sửa dòng (giảm số lượng / đổi dịch vụ tự sinh) | A06, A07, A08 | Chủ dòng (ownerAccountId) | — | — | — |
| Xóa dòng | A06, A07, A08 | Chủ dòng (ownerAccountId) | — | — | — |
| Chốt Order bán lẻ | A06 | Lễ tân | OPEN → PENDING, RETAIL (Order#5) | — | — |
| Hủy Order bán lẻ chưa chốt | A06 | Lễ tân | OPEN → CANCELLED, RETAIL (Order#7) | — | — |
| Hủy phiên trả thú (khách không thanh toán) | A06 | Lễ tân | PENDING → CANCELLED, BOARDING (Order#9) | — | cancelType = CHECKOUT_ABORTED, không tính thất thu. Đặt chỗ giữ trạng thái, tiếp tục tính đêm. Ghi audit. |
| Hủy Order khách không thanh toán | A05 | Chỉ BRANCH_MANAGER chi nhánh | PENDING → CANCELLED (Order#10) | — | cancelType = UNPAID (thất thu). Không đổi kho, bệnh án, lịch hẹn, lưu trú. Ghi audit. |
| Chuyển dòng thuốc kê đơn sang mua ngoài khi thiếu tồn lúc thu | A06 | Lễ tân | — | — | Dòng bị xóa khỏi Order, vẫn in trên đơn thuốc (external_reason = OUT_OF_STOCK_AT_PAYMENT). Ghi audit. |
| Order PENDING của khách tại chi nhánh | A06 | Lễ tân chi nhánh | — | — | Dùng để chọn Order thu gộp. |
| Xem trước khi thu (tổng tiền, dòng thiếu tồn) | A06 | Lễ tân chi nhánh | — | — | Không ghi gì (A2). |
| Thu tiền | A06 | Lễ tân có ca OPEN của chính mình | PENDING → PAID (Order#8) cho mọi Order trong phiếu | — | Trừ kho FEFO cùng transaction; ghi vào ca; ghi audit. |
| Chi tiết phiếu thu | A05, A06 | Nhân viên chi nhánh | — | — | — |
| Mở ca thu ngân | A06 | Lễ tân | — → OPEN (Ca thu ngân#1 ca thường / #2 ca ngoài giờ) | — | — |
| Ca đang mở của tôi | A06 | Lễ tân | — | — | — |
| Chốt ca | A06 | Lễ tân chủ ca | OPEN → CLOSED (Ca thu ngân#3) | — | Tổng hợp giao dịch theo phương thức. |
| Danh sách ca | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Chi tiết ca | A05, A06 | BRANCH_MANAGER chi nhánh; lễ tân chủ ca | — | — | — |
| Đối soát ca | A05 | BRANCH_MANAGER chi nhánh | CLOSED → RECONCILED (Ca thu ngân#6) | — | Ghi chênh lệch và ghi chú (cả sai sót sau khi thu, BR-TG-04); ghi audit. |

---

## C. Chi tiết endpoint

### Orders

- **`POST /orders`** — Tạo Order bán lẻ. Request `CreateRetailOrderRequest`. Response `201` `Order`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /orders`** — Danh sách Order. Query: `status?`, `source?`, `customerId?`, `visitId?`, `boardingBookingId?`, `date?`, `pendingOverdue?`, `page?`, `size?`. Response `200` trang `OrderSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /orders/{orderId}`** — Chi tiết Order. Response `200` `Order`. Lỗi: `401` · `403` · `404`.
- **`POST /orders/{orderId}/lines`** — Thêm dịch vụ / hàng vào Order. Request `AddOrderLineRequest`. Response `201` `AddOrderLineResult`. Lỗi: `400` `BR-SP-01` thuốc kê đơn (dùng kê đơn) · sản phẩm / dịch vụ ngừng kinh doanh · `401` · `403` `BR-BH-02` vai trò không được thêm loại dòng này · `404` · `409` Order không OPEN (khi PENDING chỉ lễ tân thêm GOODS) / đã PAID, CANCELLED.
- **`PATCH /orders/{orderId}/lines/{lineId}`** — Sửa dòng (giảm số lượng / đổi dịch vụ tự sinh). Request `UpdateOrderLineRequest`. Response `200` `Order`. Lỗi: `400` · `401` · `403` `BR-BH-02` không phải chủ dòng, hoặc dòng dịch vụ / thuốc đã khóa khi PENDING · `404` · `409` Order đã PAID / CANCELLED.
- **`DELETE /orders/{orderId}/lines/{lineId}`** — Xóa dòng. Response `200` `Order`. Lỗi: `400` `BR-BH-02` dòng VACCINE không xóa trực tiếp (xóa mũi tiêm) · dòng DRUG xóa qua đơn thuốc · `401` · `403` `BR-BH-02` không phải chủ dòng · `404` · `409` Order đã PAID / CANCELLED.
- **`POST /orders/{orderId}/close`** — Chốt Order bán lẻ. Response `200` `Order`. Lỗi: `400` `BR-BH-01` Order chưa có dòng nào · `401` · `403` · `404` · `409` Không phải Order RETAIL OPEN.
- **`POST /orders/{orderId}/cancel`** — Hủy Order bán lẻ chưa chốt. Response `200` `Order`. Lỗi: `401` · `403` · `404` · `409` Không phải Order RETAIL OPEN.
- **`POST /orders/{orderId}/abort-checkout`** — Hủy phiên trả thú (khách không thanh toán). Request `ReasonRequest`. Response `200` `Order`. Lỗi: `400` `BR-LT-09` thiếu lý do · thú không còn trong chuồng (dùng cancel-unpaid) · `401` · `403` · `404` · `409` Không phải Order BOARDING PENDING.
- **`POST /orders/{orderId}/cancel-unpaid`** — Hủy Order khách không thanh toán. Request `ReasonRequest`. Response `200` `Order`. Lỗi: `400` `BR-BH-05` thiếu lý do · Order BOARDING khi thú còn trong chuồng (xử lý theo BR-LT-09) · `401` · `403` · `404` · `409` Order không PENDING.
- **`POST /orders/{orderId}/lines/{lineId}/move-to-external-purchase`** — Chuyển dòng thuốc kê đơn sang mua ngoài khi thiếu tồn lúc thu. Response `200` `Order`. Lỗi: `400` Dòng không phải DRUG hoặc tồn vẫn đủ · `401` · `403` · `404` · `409` Order không PENDING.
### Payments

- **`GET /customers/{customerId}/payable-orders`** — Order PENDING của khách tại chi nhánh. Response `200` mảng `OrderSummary`. Lỗi: `401` · `403` · `404`.
- **`POST /payments/preview`** — Xem trước khi thu (tổng tiền, dòng thiếu tồn). Request `PaymentPreviewRequest`. Response `200` `PaymentPreview`. Lỗi: `400` `BR-TG-03` Order khác khách / khác chi nhánh · `BR-TG-01` có Order không PENDING · `401` · `403` · `404`.
- **`POST /payments`** — Thu tiền. Request `CreatePaymentRequest`. Response `201` `Payment`. Lỗi: `400` `BR-TG-01` chưa mở ca · `BR-TG-02` số tiền khác tổng · `BR-TG-03` Order khác khách / chi nhánh · `BR-BH-04` thiếu tồn (gọi preview để xem dòng thiếu) · `401` · `403` · `404` · `409` Có Order không còn PENDING · xung đột đồng thời.
- **`GET /payments/{paymentId}`** — Chi tiết phiếu thu. Response `200` `Payment`. Lỗi: `401` · `403` · `404`.
### Cashier shifts

- **`POST /cashier-shifts`** — Mở ca thu ngân. Request `OpenShiftRequest`. Response `201` `CashierShift`. Lỗi: `400` `BR-TG-05` ca thường ngoài giờ mở cửa / ngày nghỉ; ca ngoài giờ trong giờ mở cửa · `BR-CN-05` chi nhánh không có cờ cấp cứu ngoài giờ · `401` · `403` · `409` Đã có ca OPEN của mình.
- **`GET /me/cashier-shift`** — Ca đang mở của tôi. Response `200` `CurrentShift`. Lỗi: `401` · `403`.
- **`POST /cashier-shifts/{shiftId}/close`** — Chốt ca. Request `CloseShiftRequest`. Response `200` `CashierShift`. Lỗi: `400` · `401` · `403` · `404` · `409` Ca không OPEN.
- **`GET /cashier-shifts`** — Danh sách ca. Query: `status?`, `businessDate?`, `cashierId?`, `page?`, `size?`. Response `200` trang `CashierShift`. Lỗi: `400` · `401` · `403`.
- **`GET /cashier-shifts/{shiftId}`** — Chi tiết ca. Response `200` `CashierShift`. Lỗi: `401` · `403` · `404`.
- **`POST /cashier-shifts/{shiftId}/reconcile`** — Đối soát ca. Request `ReconcileShiftRequest`. Response `200` `CashierShift`. Lỗi: `400` `BR-TG-05` ca tự chốt chưa nhập số tiền thực đếm · `401` · `403` · `404` · `409` Ca không CLOSED.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`OrderSource`** — enum: `VISIT`, `RETAIL`, `BOARDING`.
- **`OrderStatus`** — enum: `OPEN`, `PENDING`, `PAID`, `CANCELLED`. 03 #5
- **`OrderLine`** — {`lineId`: int64, `lineType`: SERVICE|GOODS|DRUG|VACCINE|BOARDING, `serviceId?`: int64, `productId?`: int64, `description`: string, `quantity`: int32, `unitPrice`: int64, `lineTotal`: int64, `isAutoGenerated`: boolean, `addedBy`: int64, `ownerAccountId`: int64, `canEdit`: boolean, `canDelete`: boolean}
- **`Order`** — {`orderId`: int64, `code`: string, `source`: OrderSource, `status`: OrderStatus, `customerId`: int64, `customerName`: string, `branchId`: int64, `visitId?`: int64, `boardingBookingId?`: int64, `totalAmount`: int64, `lines`: [OrderLine], `createdAt`: date-time, `pendingAt?`: date-time, `paidAt?`: date-time, `paymentId?`: int64, `cancelType?`: VISIT_CANCELLED|RETAIL_UNCLOSED|CHECKOUT_ABORTED|UNPAID, `cancelReason?`: string, `cancelledAt?`: date-time, `deliveryAllowed`: boolean}
- **`OrderSummary`** — {`orderId`: int64, `code`: string, `source`: OrderSource, `status`: OrderStatus, `customerId`: int64, `customerName`: string, `branchId`: int64, `totalAmount`: int64, `createdAt`: date-time, `pendingAt?`: date-time, `paidAt?`: date-time}
- **`CreateRetailOrderRequest`** — {`customerId`: int64}
- **`AddOrderLineRequest`** — {`lineType`: SERVICE|GOODS, `serviceId?`: int64, `productId?`: int64, `quantity`: int32}
- **`StockWarning`** — {`productId`: int64, `requested`: int32, `available`: int32}
- **`AddOrderLineResult`** — {`order`: Order, `lineId`: int64, `stockWarning?`: StockWarning} — stockWarning có khi hàng bán lẻ thiếu tồn khả dụng — cảnh báo, không chặn (BR-BH-04)
- **`UpdateOrderLineRequest`** — {`quantity?`: int32, `serviceId?`: int64}
- **`ReasonRequest`** — {`reason`: string}
- **`ShortageLine`** — {`orderId`: int64, `lineId`: int64, `lineType`: GOODS|DRUG, `productId`: int64, `productName`: string, `requested`: int32, `available`: int32}
- **`PaymentPreviewRequest`** — {`orderIds`: [integer]}
- **`PaymentPreview`** — {`customerId`: int64, `branchId`: int64, `totalAmount`: int64, `shortages`: [ShortageLine]}
- **`CreatePaymentRequest`** — {`orderIds`: [integer], `method`: CASH|TRANSFER, `amount`: int64}
- **`Payment`** — {`paymentId`: int64, `code`: string, `branchId`: int64, `customerId`: int64, `cashierShiftId`: int64, `method`: CASH|TRANSFER, `amount`: int64, `receivedBy`: int64, `paidAt`: date-time, `orders`: [OrderSummary]}
- **`CashierShift`** — {`shiftId`: int64, `branchId`: int64, `cashierId`: int64, `cashierName`: string, `isAfterHours`: boolean, `businessDate`: date, `status`: OPEN|CLOSED|RECONCILED, `openedAt`: date-time, `closedAt?`: date-time, `autoClosed`: boolean, `expectedCash?`: int64, `expectedTransfer?`: int64, `countedCash?`: int64, `difference?`: int64, `reconcileNote?`: string, `reconciledBy?`: int64, `reconciledAt?`: date-time, `paymentCount`: int32}
- **`OpenShiftRequest`** — {`afterHours`: boolean}
- **`CurrentShift`** — {`open`: boolean, `shift?`: CashierShift}
- **`CloseShiftRequest`** — {`countedCash`: int64}
- **`ReconcileShiftRequest`** — {`countedCash?`: int64, `note`: string}

---

## D. Bảo mật & độ tin cậy

1. Nhân viên chỉ thao tác Order, phiếu thu, ca của chi nhánh mình.
2. Quyền thêm / xóa dòng theo BR-BH-02: VET thêm dịch vụ cho Visit mình phụ trách, CARETAKER thêm dịch vụ thẩm mỹ, lễ tân thêm hàng bán lẻ; dòng chỉ xóa bởi `ownerAccountId`; khi PENDING chỉ lễ tân thêm / xóa dòng bán lẻ. Request ngoài quyền trả 403.
3. Thu tiền là một transaction: khóa các Order, kiểm PENDING và cùng khách / chi nhánh, khóa lô, kiểm tồn, ghi phiếu thu, chuyển PAID, trừ kho FEFO, ghi audit (05 `payments`, BR-BH-04, BR-TG-04). Hai quầy thu cùng lúc không làm tồn âm.
4. Hủy Order từ PENDING, hủy phiên trả thú, chuyển thuốc sang mua ngoài lúc thu, đối soát ca đều bắt buộc lý do / ghi chú và ghi audit (BR-QT-15).
5. Hàng và thú chỉ giao khi Order PAID; response có `deliveryAllowed` để FE hiển thị 'Chưa thanh toán – chưa giao hàng' (BR-BH-06).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Order bán lẻ bắt buộc gắn hồ sơ khách | 05 `orders.customer_id` NOT NULL; khách lạ thì lễ tân tạo hồ sơ tại quầy trước |
| A2 | Có endpoint xem trước khi thu (`/payments/preview`) trả tổng tiền và danh sách dòng thiếu tồn | BR-BH-04: từ chối thu và chỉ rõ dòng thiếu; envelope lỗi không chứa danh sách |
| A3 | Ba thao tác hủy là ba endpoint riêng theo người kích hoạt và trạng thái (Order#7, #9, #10) | Mỗi chuyển có người kích hoạt và điều kiện khác nhau trong 03 #5 |
| A4 | `countedCash` chỉ đếm tiền mặt; chuyển khoản lễ tân xác nhận từng phiếu thu nên không đếm lại | BR-TG-02; 05 `cashier_shifts` chỉ có `counted_cash` |
| A5 | VET / CARETAKER đổi dịch vụ của dòng tự sinh bằng PATCH `serviceId`, giá snapshot lại tại thời điểm đổi | BR-TN-01 cho phép đổi; BR-BH-03 snapshot khi thêm dòng |

Không có câu hỏi mở.

---

## F. OpenAPI 3.1

[`./openapi/sales-v1.yaml`](./openapi/sales-v1.yaml)
