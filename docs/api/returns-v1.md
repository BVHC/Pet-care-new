# Returns & Refunds API v1 (tầng 3) — Pet Care v16

> **Module:** BH, TG (trả hàng, hoàn tiền) · **Owner:** BE-2. Lễ tân tạo yêu cầu trả hàng, quản lý chi nhánh duyệt, hoàn tiền (tiền mặt / chuyển khoản tại quầy, qua cổng cho đơn online). **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC68, UC69, ST21; 08-tang3-be2-spec.md §D (BR-BH-13…15, BR-TG-12…14; 03 #16 Yêu cầu trả hàng, #17 Hoàn tiền); 05 bổ sung return_requests, return_request_lines, refunds.
> **Contract máy đọc:** [`./openapi/returns-v1.yaml`](./openapi/returns-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Khách không tạo yêu cầu trả hàng: UC68 chỉ có lễ tân (A06).
- Sinh hoàn tiền (Hoàn tiền#1) và hoàn tiền qua cổng (ST21) là chuyển do hệ thống, không có endpoint; riêng hoàn tiền tại quầy do lễ tân xác nhận (`confirm`).
- Hủy đơn online đã thanh toán sinh hoàn tiền ở shop-v1 (`cancel`, `cancel-failed`), không đi qua yêu cầu trả hàng.

---

## A. Danh sách endpoint (9)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /return-requests` | Tạo yêu cầu trả hàng | UC68 | BR-BH-13 |
| 2 | `GET /return-requests` | Danh sách yêu cầu trả hàng của chi nhánh | UC68 | BR-BH-14 |
| 3 | `GET /return-requests/{requestId}` | Chi tiết yêu cầu trả hàng | UC68 | BR-BH-14 |
| 4 | `POST /return-requests/{requestId}/cancel` | Rút yêu cầu trả hàng | UC68 | BR-BH-14 |
| 5 | `POST /return-requests/{requestId}/approve` | Duyệt yêu cầu trả hàng | UC69 | BR-BH-14, BR-TG-12 |
| 6 | `POST /return-requests/{requestId}/reject` | Từ chối yêu cầu trả hàng | UC69 | BR-BH-14 |
| 7 | `GET /refunds` | Danh sách hoàn tiền của chi nhánh | UC69 | BR-TG-12 |
| 8 | `POST /refunds/{refundId}/confirm` | Xác nhận đã hoàn tiền tại quầy | UC69 | BR-TG-13 |
| 9 | `POST /refunds/{refundId}/retry` | Thử lại hoàn tiền qua cổng | UC69 | BR-TG-14 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Tạo yêu cầu trả hàng | A06 | Lễ tân chi nhánh của Order | — → PENDING (Yêu cầu trả hàng#1) | — | — |
| Danh sách yêu cầu trả hàng của chi nhánh | A05, A06 | A06, A05 của chi nhánh của Order | — | — | Tự lấy chi nhánh của người gọi. `status` vắng = `PENDING`. Cũ nhất trước. |
| Chi tiết yêu cầu trả hàng | A05, A06 | A06, A05 của chi nhánh của Order | — | — | — |
| Rút yêu cầu trả hàng | A06 | Người tạo | PENDING → CANCELLED (Yêu cầu trả hàng#2) | — | — |
| Duyệt yêu cầu trả hàng | A05 | BRANCH_MANAGER của chi nhánh của Order | PENDING → APPROVED (Yêu cầu trả hàng#3); Hoàn tiền#1 | — | Nhập lại lô gốc hoặc điều chỉnh giảm, sinh hoàn tiền theo hình thức đã thu (BR-TG-12), trong một transaction. |
| Từ chối yêu cầu trả hàng | A05 | BRANCH_MANAGER của chi nhánh của Order | PENDING → REJECTED (Yêu cầu trả hàng#4) | — | — |
| Danh sách hoàn tiền của chi nhánh | A05, A06 | A06, A05 của chi nhánh của Order | — | — | `status` vắng = `REQUESTED` + `FAILED`. Cũ nhất trước. |
| Xác nhận đã hoàn tiền tại quầy | A06 | Lễ tân có ca `OPEN` của chính mình | REQUESTED → SUCCEEDED (Hoàn tiền#2) | — | Số hoàn tính vào ca; yêu cầu trả hàng tương ứng → `REFUNDED` (Yêu cầu trả hàng#5). |
| Thử lại hoàn tiền qua cổng | A05 | BRANCH_MANAGER của chi nhánh | FAILED → REQUESTED (Hoàn tiền#4) | — | — |

---

## C. Chi tiết endpoint

### Return requests

- **`POST /return-requests`** — Tạo yêu cầu trả hàng. Request `CreateReturnRequestRequest`. Response `201` `ReturnRequest`. Lỗi: `400` `BR-BH-13` Order chưa `PAID` / đơn online chưa giao · quá hạn 7 ngày [CFG] · dòng không phải `GOODS` hoặc là thuốc / vaccine / dịch vụ · vượt số lượng được trả · thiếu lý do · `401` · `403` · `404` Không tìm thấy Order hoặc dòng.
- **`GET /return-requests`** — Danh sách yêu cầu trả hàng của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `ReturnRequestSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /return-requests/{requestId}`** — Chi tiết yêu cầu trả hàng. Response `200` `ReturnRequest`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /return-requests/{requestId}/cancel`** — Rút yêu cầu trả hàng. Response `200` `ReturnRequest`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17).
- **`POST /return-requests/{requestId}/approve`** — Duyệt yêu cầu trả hàng. Request `ApproveReturnRequestRequest`. Response `200` `ReturnRequest`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17).
- **`POST /return-requests/{requestId}/reject`** — Từ chối yêu cầu trả hàng. Request `RejectReturnRequestRequest`. Response `200` `ReturnRequest`. Lỗi: `400` `BR-BH-14` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17).
### Refunds

- **`GET /refunds`** — Danh sách hoàn tiền của chi nhánh. Query: `status?`, `method?`, `page?`, `size?`. Response `200` trang `Refund`. Lỗi: `400` · `401` · `403`.
- **`POST /refunds/{refundId}/confirm`** — Xác nhận đã hoàn tiền tại quầy. Request `ConfirmRefundRequest`. Response `200` `Refund`. Lỗi: `400` `BR-TG-01` không có ca `OPEN` · hoàn tiền `GATEWAY` không xác nhận tay (do ST21) · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17).
- **`POST /refunds/{refundId}/retry`** — Thử lại hoàn tiền qua cổng. Response `200` `Refund`. Lỗi: `400` Chỉ hoàn tiền `GATEWAY` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ReturnReason`** — enum: `DEFECTIVE`, `WRONG_ITEM`, `CHANGED_MIND`, `OTHER`.
- **`ReturnRequestStatus`** — enum: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `REFUNDED`. 03 #16
- **`ReturnLineInput`** — {`orderLineId`: int64, `quantity`: int32}
- **`CreateReturnRequestRequest`** — {`orderId`: int64, `lines`: [ReturnLineInput], `reasonCode`: ReturnReason, `reasonNote?`: string}
- **`ReturnLine`** — {`orderLineId`: int64, `productName`: string, `unitPrice`: int64, `quantity`: int32}
- **`ReturnRequestSummary`** — {`requestId`: int64, `code`: string, `orderId`: int64, `status`: ReturnRequestStatus, `reasonCode`: ReturnReason, `refundAmount?`: int64, `createdAt`: date-time}
- **`ReturnRequest`** — {`requestId`: int64, `code`: string, `orderId`: int64, `branchId`: int64, `status`: ReturnRequestStatus, `reasonCode`: ReturnReason, `reasonNote?`: string, `lines`: [ReturnLine], `restock?`: boolean, `refundAmount?`: int64, `decisionNote?`: string, `createdAt`: date-time, `decidedAt?`: date-time}
- **`ApproveReturnRequestRequest`** — {`restock`: boolean}
- **`RejectReturnRequestRequest`** — {`reason`: string}
- **`RefundMethod`** — enum: `CASH`, `TRANSFER`, `GATEWAY`.
- **`RefundStatus`** — enum: `REQUESTED`, `SUCCEEDED`, `FAILED`. 03 #17
- **`Refund`** — {`refundId`: int64, `code`: string, `returnRequestId?`: int64, `onlineOrderId?`: int64, `method`: RefundMethod, `amount`: int64, `status`: RefundStatus, `attempts`: int32, `failureReason?`: string, `completedAt?`: date-time, `createdAt`: date-time}
- **`ConfirmRefundRequest`** — {`note?`: string}

---

## D. Bảo mật & độ tin cậy

1. Mọi endpoint chỉ nhân viên của chi nhánh của Order: A06 tạo / rút / xác nhận hoàn tiền; A05 duyệt / từ chối / thử lại; chi nhánh khác trả 403 / 404 (BR-QT-03).
2. Hoàn tiền tại quầy chỉ trong ca `OPEN` của chính lễ tân (BR-TG-13); ghi audit (BR-QT-15).
3. Hoàn kho và điều chỉnh tồn chạy trong cùng transaction với duyệt (BR-BH-14, 04 nguyên tắc 6).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Phí vận chuyển không hoàn khi trả hàng | BR-BH-14; chỉ hoàn toàn bộ khi hủy đơn chưa bàn giao |
| A2 | Hình thức hoàn bằng hình thức đã thu | BR-TG-12 |
| A3 | Trả hàng không đổi trạng thái Order / đơn online | BR-BH-15 |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Khách có xem được trạng thái hoàn tiền của đơn không (thêm `refundStatus` vào shop-v1 `OnlineOrder`) | TBD (PO) | 08 §D Q4 |
| Q2 | Hoàn chuyển khoản tại quầy có cần ghi mã giao dịch ngân hàng không | TBD (BE-2) | 08 §D Q5 |
| Q3 | Hạn trả hàng 7 ngày có áp cho mọi sản phẩm không | TBD (PO) | BR-BH-13 |

---

## F. OpenAPI 3.1

[`./openapi/returns-v1.yaml`](./openapi/returns-v1.yaml)
