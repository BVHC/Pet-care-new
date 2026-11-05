# Stock Operations API v1 (tầng 3) — Pet Care v16

> **Module:** KO (chuyển kho, kiểm kê, truy vết lô) · **Owner:** BE-2. Chuyển kho giữa chi nhánh, kiểm kê kho của chi nhánh, truy vết lô hàng. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC77, UC78, UC79; 08-tang3-be2-spec.md §E (BR-KO-08…13; 03 #18 Phiếu chuyển kho, #19 Phiên kiểm kê); 05 bổ sung stock_transfers, stock_transfer_lines, stock_counts, stock_count_lines.
> **Contract máy đọc:** [`./openapi/stockops-v1.yaml`](./openapi/stockops-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Điều chỉnh tồn (UC76) tạo từ kiểm kê dùng chung module inventory-v1; không có endpoint điều chỉnh mới ở đây.
- Không nhận lệch khi nhận hàng: lệch xử lý bằng điều chỉnh tồn UC76 (08 §E A1).

---

## A. Danh sách endpoint (13)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /stock-transfers` | Tạo phiếu chuyển kho | UC77 | BR-KO-08 |
| 2 | `GET /stock-transfers` | Danh sách phiếu chuyển kho | UC77 | BR-KO-11 |
| 3 | `GET /stock-transfers/{transferId}` | Chi tiết phiếu chuyển kho | UC77 | BR-KO-11 |
| 4 | `POST /stock-transfers/{transferId}/receive` | Xác nhận nhận hàng | UC77 | BR-KO-09 |
| 5 | `POST /stock-transfers/{transferId}/cancel` | Hủy phiếu chuyển kho | UC77 | BR-KO-10 |
| 6 | `POST /stock-counts` | Mở phiên kiểm kê | UC78 | BR-KO-12 |
| 7 | `GET /stock-counts` | Danh sách phiên kiểm kê | UC78 | BR-KO-12 |
| 8 | `GET /stock-counts/{countId}` | Chi tiết phiên kiểm kê | UC78 | BR-KO-12 |
| 9 | `PUT /stock-counts/{countId}/lines` | Lưu số đếm theo lô | UC78 | BR-KO-12 |
| 10 | `POST /stock-counts/{countId}/confirm` | Xác nhận kiểm kê | UC78 | BR-KO-12 |
| 11 | `POST /stock-counts/{countId}/cancel` | Hủy phiên kiểm kê | UC78 | BR-KO-12 |
| 12 | `GET /stock-lots` | Tìm lô hàng | UC79 | BR-KO-13 |
| 13 | `GET /stock-lots/{lotId}/trace` | Truy vết đường đi của lô | UC79 | BR-KO-13 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Tạo phiếu chuyển kho | A05 | BRANCH_MANAGER của chi nhánh nguồn | — → IN_TRANSIT (Phiếu chuyển kho#1) | — | Trừ FEFO lô nguồn và ghi lô vào dòng ngay khi tạo. |
| Danh sách phiếu chuyển kho | A05 | BRANCH_MANAGER của chi nhánh nguồn / đích | — | — | Mới nhất trước. `direction` vắng = cả hai chiều. |
| Chi tiết phiếu chuyển kho | A05 | BRANCH_MANAGER của chi nhánh nguồn / đích | — | — | — |
| Xác nhận nhận hàng | A05 | BRANCH_MANAGER của chi nhánh đích | IN_TRANSIT → RECEIVED (Phiếu chuyển kho#2) | — | — |
| Hủy phiếu chuyển kho | A05 | BRANCH_MANAGER của chi nhánh nguồn / đích | IN_TRANSIT → CANCELLED (Phiếu chuyển kho#3) | — | Hoàn đúng lô nguồn. |
| Mở phiên kiểm kê | A05 | BRANCH_MANAGER của chi nhánh | — → OPEN (Phiên kiểm kê#1) | — | — |
| Danh sách phiên kiểm kê | A05 | BRANCH_MANAGER của chi nhánh | — | — | Mới nhất trước. |
| Chi tiết phiên kiểm kê | A05 | BRANCH_MANAGER của chi nhánh | — | — | — |
| Lưu số đếm theo lô | A05 | BRANCH_MANAGER của chi nhánh | — | Có | Ghi đè số đếm của các lô gửi lên; lô không gửi giữ nguyên. |
| Xác nhận kiểm kê | A05 | BRANCH_MANAGER của chi nhánh | OPEN → CONFIRMED (Phiên kiểm kê#2) | — | Tạo điều chỉnh `COUNT_DIFF` cho lô lệch (inventory-v1, UC76); ghi audit. |
| Hủy phiên kiểm kê | A05 | BRANCH_MANAGER của chi nhánh | OPEN → CANCELLED (Phiên kiểm kê#3) | — | — |
| Tìm lô hàng | A05, A07 | A05 lô của chi nhánh mình; A07 lô thuốc / vaccine | — | — | Phải có `lotNumber` hoặc `productId`. Mới nhập trước. |
| Truy vết đường đi của lô | A05, A07 | A05 lô của chi nhánh mình; A07 lô thuốc / vaccine | — | — | Tên thú / khách chỉ có với lô thuốc, vaccine. Mỗi lượt xem ghi audit (BR-KO-13). |

---

## C. Chi tiết endpoint

### Stock transfers

- **`POST /stock-transfers`** — Tạo phiếu chuyển kho. Request `CreateStockTransferRequest`. Response `201` `StockTransfer`. Lỗi: `400` `BR-KO-08` đích trùng nguồn / không `ACTIVE` · dòng vượt tồn khả dụng · trùng sản phẩm · `401` · `403` · `404` Không tìm thấy chi nhánh đích hoặc sản phẩm.
- **`GET /stock-transfers`** — Danh sách phiếu chuyển kho. Query: `direction?`, `status?`, `page?`, `size?`. Response `200` trang `StockTransferSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /stock-transfers/{transferId}`** — Chi tiết phiếu chuyển kho. Response `200` `StockTransfer`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /stock-transfers/{transferId}/receive`** — Xác nhận nhận hàng. Response `200` `StockTransfer`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19).
- **`POST /stock-transfers/{transferId}/cancel`** — Hủy phiếu chuyển kho. Request `ReasonRequest`. Response `200` `StockTransfer`. Lỗi: `400` `BR-KO-10` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19).
### Stock counts

- **`POST /stock-counts`** — Mở phiên kiểm kê. Request `OpenStockCountRequest`. Response `201` `StockCount`. Lỗi: `400` `BR-KO-12` thiếu `categoryId` khi scope = CATEGORY · `401` · `403` · `409` Chi nhánh đã có phiên `OPEN`.
- **`GET /stock-counts`** — Danh sách phiên kiểm kê. Query: `status?`, `page?`, `size?`. Response `200` trang `StockCountSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /stock-counts/{countId}`** — Chi tiết phiên kiểm kê. Response `200` `StockCount`. Lỗi: `400` · `401` · `403` · `404`.
- **`PUT /stock-counts/{countId}/lines`** — Lưu số đếm theo lô. Request `SaveStockCountLinesRequest`. Response `200` `StockCount`. Lỗi: `400` Lô không thuộc phạm vi phiên · số đếm âm · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19).
- **`POST /stock-counts/{countId}/confirm`** — Xác nhận kiểm kê. Response `200` `StockCount`. Lỗi: `400` `BR-KO-12` chưa nhập số đếm cho lô nào · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19).
- **`POST /stock-counts/{countId}/cancel`** — Hủy phiên kiểm kê. Request `ReasonRequest`. Response `200` `StockCount`. Lỗi: `400` `BR-KO-12` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19).
### Lot trace

- **`GET /stock-lots`** — Tìm lô hàng. Query: `lotNumber?`, `productId?`, `page?`, `size?`. Response `200` trang `LotSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /stock-lots/{lotId}/trace`** — Truy vết đường đi của lô. Response `200` `LotTrace`. Lỗi: `400` · `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`StockTransferStatus`** — enum: `IN_TRANSIT`, `RECEIVED`, `CANCELLED`. 03 #18
- **`StockTransferLineInput`** — {`productId`: int64, `quantity`: int32}
- **`CreateStockTransferRequest`** — {`destBranchId`: int64, `lines`: [StockTransferLineInput]}
- **`StockTransferLine`** — {`productId`: int64, `productName`: string, `quantity`: int32, `lotNumber?`: string, `expiryDate?`: date}
- **`StockTransferSummary`** — {`transferId`: int64, `code`: string, `sourceBranchId`: int64, `destBranchId`: int64, `status`: StockTransferStatus, `lineCount`: int32, `createdAt`: date-time}
- **`StockTransfer`** — {`transferId`: int64, `code`: string, `sourceBranchId`: int64, `sourceBranchName`: string, `destBranchId`: int64, `destBranchName`: string, `status`: StockTransferStatus, `lines`: [StockTransferLine], `createdAt`: date-time, `receivedAt?`: date-time, `cancelledAt?`: date-time, `cancelReason?`: string}
- **`ReasonRequest`** — {`reason`: string}
- **`StockCountStatus`** — enum: `OPEN`, `CONFIRMED`, `CANCELLED`. 03 #19
- **`StockCountScope`** — enum: `ALL`, `CATEGORY`.
- **`OpenStockCountRequest`** — {`scope`: StockCountScope, `categoryId?`: int64}
- **`StockCountLine`** — {`stockLotId`: int64, `productId`: int64, `productName`: string, `lotNumber?`: string, `expiryDate?`: date, `countedQty?`: int32, `systemQty`: int32}
- **`StockCountSummary`** — {`countId`: int64, `code`: string, `status`: StockCountStatus, `scope`: StockCountScope, `createdAt`: date-time, `confirmedAt?`: date-time}
- **`StockCount`** — {`countId`: int64, `code`: string, `branchId`: int64, `status`: StockCountStatus, `scope`: StockCountScope, `categoryId?`: int64, `lines`: [StockCountLine], `createdAt`: date-time, `confirmedAt?`: date-time, `cancelReason?`: string}
- **`CountedQtyInput`** — {`stockLotId`: int64, `countedQty`: int32}
- **`SaveStockCountLinesRequest`** — {`lines`: [CountedQtyInput]}
- **`LotSummary`** — {`lotId`: int64, `productId`: int64, `productName`: string, `lotNumber?`: string, `expiryDate?`: date, `branchId`: int64, `quantity`: int32}
- **`LotTraceEvent`** — {`occurredAt`: date-time, `kind`: RECEIPT|TRANSFER_IN|TRANSFER_OUT|TRANSFER_CANCEL|ORDER_ISSUE|VACCINATION|ADJUSTMENT, `quantity`: int32, `documentCode?`: string, `branchId`: int64, `petName?`: string, `customerName?`: string}
- **`LotTrace`** — {`lot`: LotSummary, `events`: [LotTraceEvent], `remaining`: int32}

---

## D. Bảo mật & độ tin cậy

1. Chuyển kho: A05 thấy phiếu mà chi nhánh mình là nguồn hoặc đích; chỉ nguồn tạo, chỉ đích nhận, cả hai hủy được (BR-KO-11).
2. Kiểm kê: A05 chỉ kho chi nhánh mình, mỗi chi nhánh tối đa một phiên `OPEN` (BR-KO-12).
3. Truy vết: A05 chỉ lô của chi nhánh mình, A07 xem lô thuốc / vaccine; thông tin thú / khách chỉ hiện với lô thuốc, vaccine; mọi lượt xem ghi audit (BR-KO-13).
4. Trừ kho FEFO và hoàn kho chạy trong cùng transaction với chuyển trạng thái; khóa lô trước khi đọc (ADR-0042, 0106).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Nhận hàng chuyển kho phải đủ, không nhận lệch | Lệch xử lý bằng điều chỉnh UC76 |
| A2 | Kiểm kê không khóa nhập / xuất; lệch tính theo tồn hệ thống lúc xác nhận | YAGNI; BR-KO-12 |
| A3 | Truy vết chỉ lộ thú / khách cho lô thuốc, vaccine | Phục vụ truy hồi lô lỗi, giảm lộ dữ liệu |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Phiếu `IN_TRANSIT` quá bao nhiêu ngày thì cảnh báo | TBD (BE-2) | 08 §E |
| Q2 | VET xem tên khách trong truy vết hay chỉ mã thú | TBD (PO) | BR-KO-13 |

---

## F. OpenAPI 3.1

[`./openapi/stockops-v1.yaml`](./openapi/stockops-v1.yaml)
