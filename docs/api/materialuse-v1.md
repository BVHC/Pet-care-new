# Material Usage API v1 (tầng 3) — Pet Care v16

> **Module:** KB (trừ vật tư tiêu hao, ST07) · **Owner:** BE-1. Xem vật tư tiêu hao đã trừ theo định mức khi hoàn tất lượt và xử lý thiếu hụt. ST07 là tác vụ hệ thống, không có endpoint kích hoạt. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 ST07; 09-tang3-be1-spec.md §E (BR-KB-12…14); 05 bổ sung visit_material_usages.
> **Contract máy đọc:** [`./openapi/materialuse-v1.yaml`](./openapi/materialuse-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- ST07 chạy trong cùng transaction với hoàn tất lượt khám / thẩm mỹ (Visit#5), không có endpoint kích hoạt (BR-KB-12).
- Định mức vật tư cấu hình ở fees-v1 (`/services/{serviceId}/material-norms`); điều chỉnh tồn xử lý thiếu hụt dùng inventory-v1 (UC76).
- Vật tư tiêu hao không thành dòng Order (BR-KB-12).

---

## A. Danh sách endpoint (3)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /visits/{visitId}/material-usages` | Vật tư tiêu hao của một lượt | ST07 | BR-KB-12, 14 |
| 2 | `GET /material-shortages` | Thiếu hụt vật tư chưa xử lý | ST07 | BR-KB-13 |
| 3 | `POST /material-shortages/{usageId}/resolve` | Đánh dấu đã xử lý thiếu hụt | ST07 | BR-KB-13 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Vật tư tiêu hao của một lượt | A05, A07 | A05 của chi nhánh; A07 phụ trách lượt | — | — | Không phân trang. Rỗng khi lượt chưa hoàn tất hoặc dịch vụ không có định mức. |
| Thiếu hụt vật tư chưa xử lý | A05 | BRANCH_MANAGER của chi nhánh | — | — | Chỉ dòng `shortageQty > 0`. Cũ nhất trước. |
| Đánh dấu đã xử lý thiếu hụt | A05 | BRANCH_MANAGER của chi nhánh | — | Có | Chỉ ghi nhận; không tự điều chỉnh tồn (dùng inventory-v1). |

---

## C. Chi tiết endpoint

### Material usage

- **`GET /visits/{visitId}/material-usages`** — Vật tư tiêu hao của một lượt. Response `200` mảng `MaterialUsage`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /material-shortages`** — Thiếu hụt vật tư chưa xử lý. Query: `resolved?`, `page?`, `size?`. Response `200` trang `MaterialUsage`. Lỗi: `400` · `401` · `403`.
- **`POST /material-shortages/{usageId}/resolve`** — Đánh dấu đã xử lý thiếu hụt. Request `ResolveShortageRequest`. Response `200` `MaterialUsage`. Lỗi: `400` Dòng không có thiếu hụt · `401` · `403` · `404` · `409` Đã được xử lý.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`MaterialUsage`** — {`usageId`: int64, `visitId`: int64, `serviceId`: int64, `serviceName`: string, `productId`: int64, `productName`: string, `unit`: string, `normQty`: int32, `issuedQty`: int32, `shortageQty`: int32, `resolvedAt?`: date-time, `resolvedNote?`: string}
- **`ResolveShortageRequest`** — {`note`: string}

---

## D. Bảo mật & độ tin cậy

1. BRANCH_MANAGER chỉ xem và xử lý thiếu hụt của chi nhánh mình; VET chỉ xem vật tư của lượt mình phụ trách (BR-QT-03).
2. Thiếu tồn không chặn hoàn tất lượt (BR-KB-13); xuất kho FEFO qua `StockApi` trong cùng transaction hoàn tất.

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Thiếu tồn vật tư không chặn hoàn tất lượt; ghi thiếu hụt và báo quản lý | Không để việc kho cản việc khám (BR-KB-13) |
| A2 | Mỗi Visit trừ vật tư đúng một lần | BR-KB-14; bệnh án khóa nên không hoàn tác |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Báo thiếu vật tư vào ST08 hiện có hay thông báo riêng `MATERIAL_SHORTAGE` | TBD (BE-1, BE-2) | BR-KB-13 |
| Q2 | Thiếu hụt chưa xử lý quá bao lâu thì nhắc lại | TBD (PO) | BR-KB-13 |

---

## F. OpenAPI 3.1

[`./openapi/materialuse-v1.yaml`](./openapi/materialuse-v1.yaml)
