# Online Payment API v1 (tầng 3) — Pet Care v16

> **Module:** TG (thanh toán online) · **Owner:** BE-2. Tạo giao dịch thanh toán online cho đơn, nhận kết quả từ cổng (callback), đối soát. Trung lập với cổng: chọn VNPay / ZaloPay / MoMo là việc của nhóm. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC63, ST11, ST14; 08-tang3-be2-spec.md §C (BR-TG-06…10; 03 #15 Giao dịch thanh toán online); 05 bổ sung payment_transactions, payment_reconciliations, reconciliation_items.
> **Contract máy đọc:** [`./openapi/payment-v1.yaml`](./openapi/payment-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Chọn cổng, đăng ký sandbox và ánh xạ tham số riêng của cổng vào `GatewayCallbackRequest` là việc của lớp `PaymentGatewayClient` (08 §C), không nằm trong hợp đồng này.
- ST11 (hết hạn giao dịch) và ST14 (đối soát) là tác vụ hệ thống, không có endpoint ngoài các endpoint xem và ghi chú đối soát.
- Hoàn tiền (ST21) ở returns-v1.

---

## A. Danh sách endpoint (6)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /me/online-orders/{onlineOrderId}/pay` | Bắt đầu thanh toán đơn online | UC63 | BR-TG-06, 09, 11 |
| 2 | `GET /me/payment-transactions/{transactionId}` | Trạng thái giao dịch của tôi | UC63 | BR-TG-06 |
| 3 | `POST /public/payment-gateway/callback` | Nhận kết quả thanh toán từ cổng | UC63 | BR-TG-07, 08, 09 |
| 4 | `GET /payment-reconciliations` | Danh sách phiên đối soát | ST14 | BR-TG-10 |
| 5 | `GET /payment-reconciliations/{reconciliationId}/items` | Các mục lệch của một phiên đối soát | ST14 | BR-TG-10 |
| 6 | `POST /payment-reconciliation-items/{itemId}/resolve` | Ghi chú đã xử lý mục lệch | ST14 | BR-TG-10 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Bắt đầu thanh toán đơn online | A02 | CUSTOMER, chỉ đơn của mình | — → PENDING (Giao dịch#1) | — | Giao diện chuyển khách tới `redirectUrl`. Kết quả chỉ tin từ callback đã ký (A3). |
| Trạng thái giao dịch của tôi | A02 | CUSTOMER, chỉ đơn của mình | — | — | Trang kết quả sau khi quay lại từ cổng hỏi endpoint này. |
| Nhận kết quả thanh toán từ cổng | S02 | Public, kiểm chữ ký | PENDING → SUCCEEDED / FAILED (Giao dịch#2, #3) | Có | Idempotent theo mã tham chiếu: gọi lại cùng kết quả vẫn 200 và không đổi gì. Số tiền lệch → `FAILED` + mục đối soát (BR-TG-08). |
| Danh sách phiên đối soát | A04 | Chỉ SUPER_MANAGER | — | — | Mới nhất trước (`reconDate` giảm dần). |
| Các mục lệch của một phiên đối soát | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Ghi chú đã xử lý mục lệch | A04 | Chỉ SUPER_MANAGER | — | Có | Chỉ ghi chú; không sửa giao dịch hay đơn (BR-TG-10). |

---

## C. Chi tiết endpoint

### Online payment

- **`POST /me/online-orders/{onlineOrderId}/pay`** — Bắt đầu thanh toán đơn online. Response `201` `PaymentSession`. Lỗi: `400` `BR-TG-06` đơn đã quá hạn thanh toán (`BR-BH-12`) · `401` · `403` · `404` · `409` Đơn không ở `PENDING_PAYMENT` · đã có giao dịch `PENDING` (`BR-TG-06`).
- **`GET /me/payment-transactions/{transactionId}`** — Trạng thái giao dịch của tôi. Response `200` `PaymentTransaction`. Lỗi: `400` · `401` · `403` · `404` Không tìm thấy giao dịch — cả khi thuộc người khác.
### Gateway callback

- **`POST /public/payment-gateway/callback`** — Nhận kết quả thanh toán từ cổng. Public. Request `GatewayCallbackRequest`. Response `200` `CallbackAck`. Lỗi: `400` `BR-TG-07` chữ ký không hợp lệ · mã tham chiếu không tồn tại (không ghi gì ngoài log).
### Reconciliation

- **`GET /payment-reconciliations`** — Danh sách phiên đối soát. Query: `status?`, `page?`, `size?`. Response `200` trang `ReconciliationSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /payment-reconciliations/{reconciliationId}/items`** — Các mục lệch của một phiên đối soát. Query: `resolved?`, `page?`, `size?`. Response `200` trang `ReconciliationItem`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /payment-reconciliation-items/{itemId}/resolve`** — Ghi chú đã xử lý mục lệch. Request `ResolveItemRequest`. Response `200` `ReconciliationItem`. Lỗi: `400` · `401` · `403` · `404` · `409` Mục đã được xử lý.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`PaymentTransactionStatus`** — enum: `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`, `CANCELLED`. 03 #15
- **`PaymentSession`** — {`transactionId`: int64, `code`: string, `amount`: int64, `redirectUrl`: string, `expiresAt`: date-time}
- **`PaymentTransaction`** — {`transactionId`: int64, `onlineOrderId`: int64, `status`: PaymentTransactionStatus, `amount`: int64, `expiresAt`: date-time, `paidAt?`: date-time, `failureReason?`: string}
- **`GatewayCallbackRequest`** — {`transactionCode`: string, `gatewayTxnId?`: string, `status`: string, `amount`: int64, `signature`: string, `payload?`: object}
- **`CallbackAck`** — {`received`: boolean}
- **`ReconciliationStatus`** — enum: `RUNNING`, `MATCHED`, `MISMATCHED`, `FAILED`.
- **`ReconciliationSummary`** — {`reconciliationId`: int64, `reconDate`: date, `status`: ReconciliationStatus, `txnCount`: int32, `mismatchCount`: int32, `finishedAt?`: date-time}
- **`ReconciliationItemKind`** — enum: `MISSING_LOCAL`, `MISSING_GATEWAY`, `AMOUNT_DIFF`, `STATUS_DIFF`.
- **`ReconciliationItem`** — {`itemId`: int64, `kind`: ReconciliationItemKind, `transactionId?`: int64, `gatewayRef?`: string, `ourAmount?`: int64, `gatewayAmount?`: int64, `resolved`: boolean, `resolvedNote?`: string, `resolvedAt?`: date-time}
- **`ResolveItemRequest`** — {`note`: string}

---

## D. Bảo mật & độ tin cậy

1. `POST /me/online-orders/{onlineOrderId}/pay` chỉ CUSTOMER và chỉ đơn của mình; đơn người khác trả 404 (ADR-0006).
2. Callback là **công khai** (ADR-0005) nhưng chỉ xử lý khi chữ ký hợp lệ và idempotent theo mã tham chiếu; chữ ký sai trả 400 và không ghi gì ngoài log (BR-TG-07). Khóa bí mật của cổng chỉ ở biến môi trường.
3. Số tiền cổng báo phải khớp số tiền giao dịch; lệch thì `FAILED` và đi vào đối soát, không tự đánh dấu đơn đã trả (BR-TG-08).
4. Đối soát chỉ SUPER_MANAGER; không có chức năng tự sửa (BR-TG-10).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Callback của mọi cổng được ánh xạ về một dạng chung `GatewayCallbackRequest` ở lớp tích hợp | Giữ hợp đồng trung lập với cổng |
| A2 | Chỉ một cổng được tích hợp ở GĐ2 | YAGNI; 08 §C |
| A3 | Khách quay lại từ cổng thì giao diện hỏi `GET /me/payment-transactions/{id}` (không tin tham số trên URL quay về) | Kết quả chỉ tin từ callback đã ký |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Chọn VNPay, ZaloPay hay MoMo và đăng ký tài khoản sandbox | TBD (nhóm) | time.md T65 |
| Q2 | Có giới hạn IP của cổng được gọi callback không | TBD (BE-1, BE-2) | BR-TG-07 |
| Q3 | Cổng có hỗ trợ hủy phiên chưa thanh toán không | TBD (BE-2) | 03 #15 Giao dịch#5 |

---

## F. OpenAPI 3.1

[`./openapi/payment-v1.yaml`](./openapi/payment-v1.yaml)
