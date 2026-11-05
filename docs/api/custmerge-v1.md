# Customer Merge API v1 (tầng 3) — Pet Care v16

> **Module:** KH (gộp hồ sơ khách, UC27) · **Owner:** BE-2. Xem trước và gộp hồ sơ khách trùng của cùng một khách. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC27; 08-tang3-be2-spec.md §F.2 (BR-KH-11…14); 05 bổ sung customers.merged_into_id, customer_merges.
> **Contract máy đọc:** [`./openapi/custmerge-v1.yaml`](./openapi/custmerge-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Gợi ý hồ sơ nghi trùng dùng chức năng tra cứu sẵn có của customer-v1 (UC22); không lặp ở đây.
- Hệ quả lên module khác chạy qua `CustomerMergedEvent` (06 §3), không có endpoint từng module.
- Gộp không hoàn tác; không có endpoint hủy gộp (BR-KH-13).

---

## A. Danh sách endpoint (2)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /customers/{customerId}/merge-preview` | Xem trước gộp hồ sơ khách | UC27 | BR-KH-11, 12, 14 |
| 2 | `POST /customer-merges` | Gộp hồ sơ khách trùng | UC27 | BR-KH-11…14 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem trước gộp hồ sơ khách | A05 | BRANCH_MANAGER (xem Q1) | — | — | Không ghi gì. `blockers` rỗng mới gộp được. |
| Gộp hồ sơ khách trùng | A05 | BRANCH_MANAGER (xem Q1) | — | — | Chuyển dữ liệu và phát `CustomerMergedEvent` trong một transaction (BR-KH-12). Không hoàn tác (BR-KH-13). |

---

## C. Chi tiết endpoint

### Customer merge

- **`GET /customers/{customerId}/merge-preview`** — Xem trước gộp hồ sơ khách. Query: `sourceCustomerId`. Response `200` `MergePreview`. Lỗi: `400` `sourceCustomerId` trùng đích hoặc thiếu · `401` · `403` · `404` Không tìm thấy một trong hai hồ sơ.
- **`POST /customer-merges`** — Gộp hồ sơ khách trùng. Request `CustomerMergeRequest`. Response `201` `CustomerMergeResult`. Lỗi: `400` `BR-KH-11` cả hai đã liên kết tài khoản · đích không phải bên có tài khoản · nguồn còn Visit mở / Order `OPEN` / `PENDING` · đã `MERGED` · `BR-KH-14` chưa `confirm` · `401` · `403` · `404` Không tìm thấy hồ sơ · `409` `CONCURRENCY_CONFLICT` hồ sơ vừa bị đổi.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`MergePreview`** — {`targetCustomerId`: int64, `sourceCustomerId`: int64, `petCount`: int32, `addressCount`: int32, `duplicateAddressCount`: int32, `appointmentCount`: int32, `boardingCount`: int32, `orderCount`: int32, `onlineOrderCount`: int32, `feedbackCount`: int32, `complaintCount`: int32, `careTaskCount`: int32, `warnings`: [string], `blockers`: [string]}
- **`CustomerMergeRequest`** — {`sourceCustomerId`: int64, `targetCustomerId`: int64, `confirm`: boolean}
- **`CustomerMergeResult`** — {`mergeId`: int64, `sourceCustomerId`: int64, `targetCustomerId`: int64, `moved`: object, `createdAt`: date-time}

---

## D. Bảo mật & độ tin cậy

1. Chỉ BRANCH_MANAGER (A05); gộp chạm dữ liệu dùng chung toàn chuỗi (hồ sơ khách dùng chung, 04 nguyên tắc 8) nên mọi lượt gộp ghi `customer_merges` và audit (BR-KH-13, BR-QT-15).
2. Không gộp khi cả hai hồ sơ đã liên kết tài khoản (BR-TK-19, BR-KH-11).
3. Toàn bộ chuyển dữ liệu chạy trong một transaction (04 nguyên tắc 6); khóa hai hồ sơ theo thứ tự `id` tăng dần để tránh deadlock.

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | BRANCH_MANAGER gộp được mọi hồ sơ khách dùng chung toàn chuỗi, không chỉ khách của chi nhánh mình | Hồ sơ khách không thuộc chi nhánh (04 nguyên tắc 8) |
| A2 | Không tự gộp thú trùng; lễ tân xử lý sau | BR-KH-12 |
| A3 | Gộp không hoàn tác | BR-KH-13; YAGNI |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Phạm vi quản lý của A05: chỉ khách đã có giao dịch tại chi nhánh mình hay toàn chuỗi | TBD (PO) | BR-KH-11, UC27 |
| Q2 | Có cần SUPER_MANAGER duyệt khi hồ sơ có đơn hàng lớn / khiếu nại đang mở không | TBD (PO) | BR-KH-11 |

---

## F. OpenAPI 3.1

[`./openapi/custmerge-v1.yaml`](./openapi/custmerge-v1.yaml)
