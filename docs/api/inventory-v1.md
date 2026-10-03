# Inventory API v1 — Pet Care v16

> **Module:** KO (tầng 2) · **Owner:** BE-2. Nhà cung cấp, phiếu nhập kho, xem tồn theo lô / hạn dùng, tồn tối thiểu, điều chỉnh tồn.
> **Nguồn chân lý:** 01 UC73–UC76; 02 BR-KO-01…07, BR-SP-05, BR-QT-15; 03 #9 Phiếu nhập kho; 04 §9; 05 §9 (suppliers, inventory_items, stock_lots, stock_receipts, stock_receipt_lines, stock_adjustments, stock_adjustment_lines, stock_movements).
> **Contract máy đọc:** [`./openapi/inventory-v1.yaml`](./openapi/inventory-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC77 chuyển kho, UC78 kiểm kê, UC79 truy vết lô là tầng 3.
- Trừ kho khi thu tiền và khi tiêm (ST06) chạy bên trong `POST /payments` (sales) và `POST /visits/{visitId}/vaccinations` (visit) qua `StockApi`, không có endpoint riêng.
- ST08 cảnh báo tồn là tác vụ hệ thống; số liệu tương ứng xem ở báo cáo `GET /reports/stock-alerts`.

---

## A. Danh sách endpoint (17)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /suppliers` | Danh sách nhà cung cấp | UC73, UC74 | BR-KO-02 |
| 2 | `POST /suppliers` | Thêm nhà cung cấp | UC73 | BR-KO-02 |
| 3 | `PATCH /suppliers/{supplierId}` | Sửa / ngừng hợp tác nhà cung cấp | UC73 | BR-KO-02 |
| 4 | `DELETE /suppliers/{supplierId}` | Xóa nhà cung cấp chưa có phiếu nhập | UC73 | BR-KO-02 |
| 5 | `GET /stock-receipts` | Danh sách phiếu nhập | UC74 | BR-KO-03 |
| 6 | `POST /stock-receipts` | Tạo phiếu nhập nháp | UC74 | BR-KO-03 |
| 7 | `GET /stock-receipts/{receiptId}` | Chi tiết phiếu nhập | UC74 | BR-KO-03 |
| 8 | `PUT /stock-receipts/{receiptId}` | Sửa phiếu nháp | UC74 | BR-KO-03 |
| 9 | `POST /stock-receipts/{receiptId}/confirm` | Xác nhận phiếu nhập | UC74 | BR-KO-02, 03, BR-SP-05 |
| 10 | `POST /stock-receipts/{receiptId}/cancel` | Hủy phiếu nhập | UC74 | BR-KO-03, 04, 06, BR-QT-15 |
| 11 | `GET /inventory` | Xem tồn kho chi nhánh | UC75 | BR-KO-01, 07 |
| 12 | `GET /inventory/{productId}` | Tồn của một sản phẩm | UC75, UC66, UC48 | BR-KO-01 |
| 13 | `GET /inventory/{productId}/lots` | Tồn theo lô, hạn dùng | UC75, UC76 | BR-KO-01, 05 |
| 14 | `PUT /inventory/{productId}/min-quantity` | Đặt tồn tối thiểu | UC75 | BR-KO-07 |
| 15 | `POST /stock-adjustments` | Lập phiếu điều chỉnh tồn | UC76 | BR-KO-01, 06, BR-QT-15 |
| 16 | `GET /stock-adjustments` | Danh sách phiếu điều chỉnh | UC76 | BR-KO-06 |
| 17 | `GET /stock-adjustments/{adjustmentId}` | Chi tiết phiếu điều chỉnh | UC76 | BR-KO-06 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách nhà cung cấp | A05 | BRANCH_MANAGER | — | — | — |
| Thêm nhà cung cấp | A05 | BRANCH_MANAGER (A1) | — | — | — |
| Sửa / ngừng hợp tác nhà cung cấp | A05 | BRANCH_MANAGER (A1) | — | — | Ngừng hợp tác thì không chọn được ở phiếu nhập mới. |
| Xóa nhà cung cấp chưa có phiếu nhập | A05 | BRANCH_MANAGER (A1) | — | — | — |
| Danh sách phiếu nhập | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Tạo phiếu nhập nháp | A05 | BRANCH_MANAGER chi nhánh | — → DRAFT (Phiếu nhập#1) | — | Chưa ảnh hưởng tồn. |
| Chi tiết phiếu nhập | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Sửa phiếu nháp | A05 | BRANCH_MANAGER chi nhánh | — | — | A2. |
| Xác nhận phiếu nhập | A05 | BRANCH_MANAGER chi nhánh | DRAFT → CONFIRMED (Phiếu nhập#2) | — | Cộng tồn theo lô; cùng số lô và hạn thì cộng vào lô cũ. |
| Hủy phiếu nhập | A05 | BRANCH_MANAGER chi nhánh | DRAFT → CANCELLED (Phiếu nhập#3) · CONFIRMED → CANCELLED (Phiếu nhập#4) | — | Phiếu CONFIRMED: trừ lại tồn, ghi audit. Không xóa cứng phiếu. |
| Xem tồn kho chi nhánh | A05, A06, A07, A08 | Nhân viên chi nhánh (chỉ BRANCH_MANAGER sửa) | — | — | — |
| Tồn của một sản phẩm | A05, A06, A07, A08 | Nhân viên chi nhánh | — | — | Dùng ở POS / kê đơn / tiêm để xem tồn khả dụng. |
| Tồn theo lô, hạn dùng | A05, A06 | Nhân viên chi nhánh | — | — | Sắp theo hạn dùng tăng dần (thứ tự FEFO). |
| Đặt tồn tối thiểu | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Lập phiếu điều chỉnh tồn | A05 | BRANCH_MANAGER chi nhánh | — | — | Hiệu lực ngay, ghi movement ADJUSTMENT và audit. A4. |
| Danh sách phiếu điều chỉnh | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Chi tiết phiếu điều chỉnh | A05 | BRANCH_MANAGER chi nhánh | — | — | — |

---

## C. Chi tiết endpoint

### Suppliers

- **`GET /suppliers`** — Danh sách nhà cung cấp. Query: `isActive?`, `q?`. Response `200` mảng `Supplier`. Lỗi: `401` · `403`.
- **`POST /suppliers`** — Thêm nhà cung cấp. Request `SupplierRequest`. Response `201` `Supplier`. Lỗi: `400` · `401` · `403`.
- **`PATCH /suppliers/{supplierId}`** — Sửa / ngừng hợp tác nhà cung cấp. Request `UpdateSupplierRequest`. Response `200` `Supplier`. Lỗi: `400` · `401` · `403` · `404`.
- **`DELETE /suppliers/{supplierId}`** — Xóa nhà cung cấp chưa có phiếu nhập. Response `204` rỗng. Lỗi: `400` `BR-KO-02` đã có phiếu nhập — chỉ ngừng hợp tác · `401` · `403` · `404`.
### Stock receipts

- **`GET /stock-receipts`** — Danh sách phiếu nhập. Query: `status?`, `from?`, `to?`, `supplierId?`, `page?`, `size?`. Response `200` trang `StockReceiptSummary`. Lỗi: `400` · `401` · `403`.
- **`POST /stock-receipts`** — Tạo phiếu nhập nháp. Request `StockReceiptRequest`. Response `201` `StockReceipt`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /stock-receipts/{receiptId}`** — Chi tiết phiếu nhập. Response `200` `StockReceipt`. Lỗi: `401` · `403` · `404`.
- **`PUT /stock-receipts/{receiptId}`** — Sửa phiếu nháp. Request `StockReceiptRequest`. Response `200` `StockReceipt`. Lỗi: `400` · `401` · `403` · `404` · `409` Phiếu không còn DRAFT.
- **`POST /stock-receipts/{receiptId}/confirm`** — Xác nhận phiếu nhập. Response `200` `StockReceipt`. Lỗi: `400` `BR-KO-02` nhà cung cấp ngừng hợp tác · `BR-KO-03` dòng số lượng ≤ 0 / thiếu giá nhập / thiếu số lô, hạn dùng hoặc hạn không sau ngày nhập · `401` · `403` · `404` · `409` Phiếu không còn DRAFT.
- **`POST /stock-receipts/{receiptId}/cancel`** — Hủy phiếu nhập. Request `CancelReceiptRequest`. Response `200` `StockReceipt`. Lỗi: `400` `BR-KO-04` tồn hiện tại của lô không đủ để trừ lại — dùng phiếu điều chỉnh (BR-KO-06) · `401` · `403` · `404` · `409` Phiếu đã CANCELLED.
### Inventory

- **`GET /inventory`** — Xem tồn kho chi nhánh. Query: `q?`, `productType?`, `belowMin?`, `expiringWithinDays?`, `page?`, `size?`. Response `200` trang `InventoryItem`. Lỗi: `400` · `401` · `403`.
- **`GET /inventory/{productId}`** — Tồn của một sản phẩm. Response `200` `InventoryItem`. Lỗi: `401` · `403` · `404`.
- **`GET /inventory/{productId}/lots`** — Tồn theo lô, hạn dùng. Response `200` mảng `StockLot`. Lỗi: `401` · `403` · `404`.
- **`PUT /inventory/{productId}/min-quantity`** — Đặt tồn tối thiểu. Request `SetMinQuantityRequest`. Response `200` `InventoryItem`. Lỗi: `400` · `401` · `403` · `404`.
### Adjustments

- **`POST /stock-adjustments`** — Lập phiếu điều chỉnh tồn. Request `StockAdjustmentRequest`. Response `201` `StockAdjustment`. Lỗi: `400` `BR-KO-06` thiếu lý do / ghi chú khi OTHER · giảm quá tồn của lô · `BR-KO-01` tồn âm · `401` · `403` · `404`.
- **`GET /stock-adjustments`** — Danh sách phiếu điều chỉnh. Query: `from?`, `to?`, `page?`, `size?`. Response `200` trang `StockAdjustment`. Lỗi: `400` · `401` · `403`.
- **`GET /stock-adjustments/{adjustmentId}`** — Chi tiết phiếu điều chỉnh. Response `200` `StockAdjustment`. Lỗi: `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`Supplier`** — {`supplierId`: int64, `name`: string, `phone`: string, `address?`: string, `isActive`: boolean}
- **`SupplierRequest`** — {`name`: string, `phone`: string, `address?`: string}
- **`UpdateSupplierRequest`** — {`name?`: string, `phone?`: string, `address?`: string, `isActive?`: boolean}
- **`ReceiptLineRequest`** — {`productId`: int64, `quantity`: int32, `unitCost`: int64, `lotNumber?`: string, `expiryDate?`: date}
- **`StockReceiptRequest`** — {`supplierId`: int64, `receiptDate`: date, `note?`: string, `lines`: [ReceiptLineRequest]}
- **`ReceiptLine`** — {`lineId`: int64, `productId`: int64, `productName`: string, `quantity`: int32, `unitCost`: int64, `lotNumber?`: string, `expiryDate?`: date, `stockLotId?`: int64}
- **`StockReceipt`** — {`receiptId`: int64, `code`: string, `branchId`: int64, `supplierId`: int64, `supplierName`: string, `receiptDate`: date, `status`: DRAFT|CONFIRMED|CANCELLED, `note?`: string, `lines`: [ReceiptLine], `createdBy`: int64, `confirmedBy?`: int64, `confirmedAt?`: date-time, `cancelledBy?`: int64, `cancelledAt?`: date-time, `cancelReason?`: string}
- **`StockReceiptSummary`** — {`receiptId`: int64, `code`: string, `supplierName`: string, `receiptDate`: date, `status`: DRAFT|CONFIRMED|CANCELLED, `lineCount`: int32, `totalCost`: int64}
- **`CancelReceiptRequest`** — {`reason?`: string}
- **`InventoryItem`** — {`productId`: int64, `productName`: string, `sku`: string, `unit`: string, `productType`: GOODS|DRUG|VACCINE, `tracksExpiry`: boolean, `totalQuantity`: int32, `availableQuantity`: int32, `minQuantity`: int32, `belowMin`: boolean, `nearestExpiryDate?`: date, `expiredQuantity`: int32}
- **`StockLot`** — {`stockLotId`: int64, `lotNumber?`: string, `expiryDate?`: date, `quantity`: int32, `isDefault`: boolean, `expired`: boolean}
- **`SetMinQuantityRequest`** — {`minQuantity`: int32}
- **`AdjustmentLineRequest`** — {`stockLotId`: int64, `quantityDelta`: int32}
- **`StockAdjustmentRequest`** — {`reason`: DAMAGED|EXPIRED|COUNT_MISMATCH|OTHER, `note?`: string, `lines`: [AdjustmentLineRequest]}
- **`AdjustmentLine`** — {`stockLotId`: int64, `productId`: int64, `productName`: string, `lotNumber?`: string, `quantityDelta`: int32, `balanceAfter`: int32}
- **`StockAdjustment`** — {`adjustmentId`: int64, `code`: string, `branchId`: int64, `reason`: DAMAGED|EXPIRED|COUNT_MISMATCH|OTHER, `note?`: string, `lines`: [AdjustmentLine], `createdBy`: int64, `createdAt`: date-time}

---

## D. Bảo mật & độ tin cậy

1. Tồn kho theo chi nhánh: mọi endpoint (trừ nhà cung cấp, dùng chung toàn chuỗi) làm việc trên chi nhánh của người gọi.
2. Ghi (nhập kho, điều chỉnh, tồn tối thiểu, nhà cung cấp) chỉ BRANCH_MANAGER; lễ tân, VET, CARETAKER chỉ xem (UC75).
3. Mọi thay đổi số dư lô ghi `stock_movements` cùng transaction; tồn không bao giờ âm (BR-KO-01, 04 nguyên tắc 5).
4. Hủy phiếu đã xác nhận và điều chỉnh tồn ghi audit (BR-KO-04, 06, BR-QT-15).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Nhà cung cấp do BRANCH_MANAGER bất kỳ quản lý, dùng chung toàn chuỗi | UC73 actor A05; BR-KO-02 dùng chung toàn chuỗi |
| A2 | Phiếu nháp sửa bằng PUT thay toàn bộ phiếu (kể cả các dòng) | BR-KO-03 'sửa tự do' khi DRAFT |
| A3 | Lý do hủy phiếu nhập không bắt buộc | BR-KO-03, 04 không yêu cầu; cột `cancel_reason` cho phép null |
| A4 | Điều chỉnh sản phẩm không quản lý hạn dùng dùng lô mặc định (lấy id từ danh sách lô) | 04 nguyên tắc 4: mọi sản phẩm đều tồn theo lô |
| A5 | Xóa nhà cung cấp chưa có phiếu nhập; đã có thì chỉ ngừng hợp tác | BR-KO-02 |

Không có câu hỏi mở.

---

## F. OpenAPI 3.1

[`./openapi/inventory-v1.yaml`](./openapi/inventory-v1.yaml)
