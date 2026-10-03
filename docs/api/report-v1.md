# Report API v1 — Pet Care v16

> **Module:** BC (tầng 2) · **Owner:** BE-2 (số liệu lịch hẹn, tiêm chủng do BE-1 cung cấp qua *ReportApi). Sáu báo cáo cố định của BR-BC-03, tính tại thời điểm xem.
> **Nguồn chân lý:** 01 UC89; 02 BR-BC-01…04, BR-BH-05, BR-KO-07, BR-DG-03; 04 *Ngoài phạm vi* (báo cáo không có model); 06-module-contracts §8 Q3.
> **Contract máy đọc:** [`./openapi/report-v1.yaml`](./openapi/report-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Không có báo cáo tùy biến, xuất file hay lưu báo cáo: BR-BC-03 là danh mục cố định.
- Báo cáo công suất chuồng đã bỏ ở v15 (BR-BC-03).

---

## A. Danh sách endpoint (7)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /reports/revenue` | Doanh thu theo nguồn | UC89 | BR-BC-01, 02, 03 |
| 2 | `GET /reports/lost-revenue` | Thất thu | UC89 | BR-BC-01, 02, BR-BH-05 |
| 3 | `GET /reports/completed-services` | Lượt dịch vụ hoàn tất | UC89 | BR-BC-01, 03 |
| 4 | `GET /reports/no-show` | Tỷ lệ NO_SHOW và hủy muộn | UC89 | BR-BC-01, 03 |
| 5 | `GET /reports/revaccination-return` | Tỷ lệ quay lại tái chủng | UC89 | BR-BC-01, 03, 04 |
| 6 | `GET /reports/stock-alerts` | Tồn dưới ngưỡng và lô sắp / đã hết hạn | UC89 | BR-BC-01, 03, BR-KO-07 |
| 7 | `GET /reports/feedback` | Feedback theo chi nhánh | UC89 | BR-BC-01, 03, BR-DG-03 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Doanh thu theo nguồn | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Thất thu | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Lượt dịch vụ hoàn tất | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Tỷ lệ NO_SHOW và hủy muộn | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Tỷ lệ quay lại tái chủng | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Tồn dưới ngưỡng và lô sắp / đã hết hạn | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Feedback theo chi nhánh | A04, A05 | SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình | — | — | — |

---

## C. Chi tiết endpoint

### Reports

- **`GET /reports/revenue`** — Doanh thu theo nguồn. Query: `branchId?`, `from`, `to`. Response `200` `RevenueReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/lost-revenue`** — Thất thu. Query: `branchId?`, `from`, `to`. Response `200` `LostRevenueReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/completed-services`** — Lượt dịch vụ hoàn tất. Query: `branchId?`, `from`, `to`. Response `200` `CompletedServicesReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/no-show`** — Tỷ lệ NO_SHOW và hủy muộn. Query: `branchId?`, `from`, `to`. Response `200` `NoShowReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/revaccination-return`** — Tỷ lệ quay lại tái chủng. Query: `branchId?`, `from`, `to`. Response `200` `RevaccinationReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/stock-alerts`** — Tồn dưới ngưỡng và lô sắp / đã hết hạn. Query: `branchId?`, `withinDays?`. Response `200` `StockAlertReport`. Lỗi: `400` · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.
- **`GET /reports/feedback`** — Feedback theo chi nhánh. Query: `branchId?`, `from`, `to`. Response `200` `FeedbackReport`. Lỗi: `400` `BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to · `401` · `403` `BR-BC-01` chi nhánh ngoài phạm vi.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`RevenueRow`** — {`branchId`: int64, `branchName`: string, `source`: VISIT|RETAIL|BOARDING, `orderCount`: int32, `amount`: int64}
- **`RevenueReport`** — {`from`: date, `to`: date, `rows`: [RevenueRow], `totalAmount`: int64} — BR-BC-02: Order PAID có thời điểm thanh toán trong kỳ, theo đơn giá snapshot
- **`LostRevenueRow`** — {`orderId`: int64, `orderCode`: string, `branchId`: int64, `source`: VISIT|RETAIL|BOARDING, `amount`: int64, `reason`: string, `cancelledAt`: date-time}
- **`LostRevenueReport`** — {`from`: date, `to`: date, `rows`: [LostRevenueRow], `count`: int32, `totalAmount`: int64} — BR-BC-02: chỉ Order hủy theo BR-BH-05 (UNPAID); hủy phiên trả thú không tính
- **`ServiceCountRow`** — {`branchId`: int64, `serviceGroup`: MEDICAL|GROOMING, `serviceId`: int64, `serviceName`: string, `count`: int32}
- **`CompletedServicesReport`** — {`from`: date, `to`: date, `rows`: [ServiceCountRow]}
- **`NoShowRow`** — {`branchId`: int64, `dueCount`: int32, `noShowCount`: int32, `lateCancelCount`: int32, `noShowRate`: number, `lateCancelRate`: number}
- **`NoShowReport`** — {`from`: date, `to`: date, `rows`: [NoShowRow]}
- **`ReturnRateRow`** — {`branchId`: int64, `vaccineTypeId`: int64, `vaccineTypeName`: string, `dueCount`: int32, `returnedCount`: int32, `rate`: number}
- **`RevaccinationReport`** — {`from`: date, `to`: date, `rows`: [ReturnRateRow]} — BR-BC-04: đo khách quay lại tiêm đúng hạn tại hệ thống
- **`LowStockRow`** — {`branchId`: int64, `productId`: int64, `productName`: string, `availableQuantity`: int32, `minQuantity`: int32}
- **`ExpiringLotRow`** — {`branchId`: int64, `productId`: int64, `productName`: string, `stockLotId`: int64, `lotNumber?`: string, `expiryDate`: date, `quantity`: int32, `expired`: boolean}
- **`StockAlertReport`** — {`lowStock`: [LowStockRow], `expiringLots`: [ExpiringLotRow]}
- **`FeedbackStatRow`** — {`branchId?`: int64, `branchName?`: string, `count`: int32, `averageRating?`: number}
- **`FeedbackReport`** — {`from`: date, `to`: date, `rows`: [FeedbackStatRow]}

---

## D. Bảo mật & độ tin cậy

1. SUPER_MANAGER xem toàn chuỗi và lọc theo chi nhánh; BRANCH_MANAGER chỉ chi nhánh mình — truyền chi nhánh khác trả 403 (BR-BC-01).
2. Kỳ báo cáo tối đa 12 tháng [CFG], ngày theo giờ Việt Nam; vượt trả 400 `BR-BC-01`.
3. Chỉ đọc; module report không có bảng riêng, gọi `*ReportApi` của từng module sở hữu dữ liệu (06 Q3).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Mỗi báo cáo là một endpoint riêng, cùng bộ tham số `branchId`, `from`, `to` | BR-BC-03 liệt kê 6 báo cáo độc lập |
| A2 | Báo cáo tồn kho (5) không theo kỳ, lấy tại thời điểm xem; `withinDays` mặc định 30 [CFG] | BR-KO-07: lô hết hạn trong 30 ngày [CFG] |
| A3 | Tỷ lệ trả về dạng số thập phân 0–1, kèm tử số và mẫu số | FE tự định dạng phần trăm |

Không có câu hỏi mở.

---

## F. OpenAPI 3.1

[`./openapi/report-v1.yaml`](./openapi/report-v1.yaml)
