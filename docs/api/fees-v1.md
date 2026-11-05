# Fee & Material-norm Config API v1 (tầng 3) — Pet Care v16

> **Module:** SP (mở rộng UC30) · **Owner:** BE-2. Cấu hình phí vận chuyển (toàn chuỗi), phí khám tại nhà (theo chi nhánh) và định mức vật tư tiêu hao của dịch vụ. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC32; 08-tang3-be2-spec.md §A (BR-SP-08…11); 05 bổ sung shipping_fee_configs, branch_home_visit_fees, service_material_norms.
> **Contract máy đọc:** [`./openapi/fees-v1.yaml`](./openapi/fees-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Phí vận chuyển của một giỏ hàng cụ thể lấy qua `GET /me/online-orders/quote` (shop-v1), không có endpoint tính phí ở đây (ST09).
- ST07 (tự trừ vật tư theo định mức) là tác vụ hệ thống của BE-1, đọc định mức qua interface (08 §A, 09).

---

## A. Danh sách endpoint (6)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /shipping-fee-config` | Xem cấu hình phí vận chuyển | UC32 | BR-SP-08 |
| 2 | `PUT /shipping-fee-config` | Lưu cấu hình phí vận chuyển | UC32 | BR-SP-08, 11 |
| 3 | `GET /branches/{branchId}/home-visit-fee` | Xem phí khám tại nhà của chi nhánh | UC32 | BR-SP-09 |
| 4 | `PUT /branches/{branchId}/home-visit-fee` | Lưu phí khám tại nhà của chi nhánh | UC32 | BR-SP-09, 11 |
| 5 | `GET /services/{serviceId}/material-norms` | Xem định mức vật tư của dịch vụ | UC32 | BR-SP-10 |
| 6 | `PUT /services/{serviceId}/material-norms` | Lưu định mức vật tư của dịch vụ | UC32 | BR-SP-10, 11 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem cấu hình phí vận chuyển | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Lưu cấu hình phí vận chuyển | A04 | Chỉ SUPER_MANAGER | — | Có | — |
| Xem phí khám tại nhà của chi nhánh | A02, A04, A06, A07 | SUPER_MANAGER; CUSTOMER (xem phí khi đặt); A06, A07 của chính chi nhánh | — | — | Chi nhánh chưa cấu hình trả `enabled = false`, `fee = 0`. |
| Lưu phí khám tại nhà của chi nhánh | A04 | Chỉ SUPER_MANAGER | — | Có | — |
| Xem định mức vật tư của dịch vụ | A04 | Chỉ SUPER_MANAGER | — | — | Không phân trang (ít dòng). |
| Lưu định mức vật tư của dịch vụ | A04 | Chỉ SUPER_MANAGER | — | Có | Ghi đè toàn bộ; chỉ ảnh hưởng các lần thực hiện sau (BR-SP-10). |

---

## C. Chi tiết endpoint

### Shipping fee

- **`GET /shipping-fee-config`** — Xem cấu hình phí vận chuyển. Response `200` `ShippingFeeConfig`. Lỗi: `401` · `403`.
- **`PUT /shipping-fee-config`** — Lưu cấu hình phí vận chuyển. Request `SaveShippingFeeConfigRequest`. Response `200` `ShippingFeeConfig`. Lỗi: `400` `BR-SP-08` số âm hoặc sai kiểu · `401` · `403`.
### Home visit fee

- **`GET /branches/{branchId}/home-visit-fee`** — Xem phí khám tại nhà của chi nhánh. Response `200` `HomeVisitFee`. Lỗi: `400` · `401` · `403` · `404`.
- **`PUT /branches/{branchId}/home-visit-fee`** — Lưu phí khám tại nhà của chi nhánh. Request `SaveHomeVisitFeeRequest`. Response `200` `HomeVisitFee`. Lỗi: `400` `BR-SP-09` phí âm · `401` · `403` · `404`.
### Material norms

- **`GET /services/{serviceId}/material-norms`** — Xem định mức vật tư của dịch vụ. Response `200` mảng `MaterialNorm`. Lỗi: `400` · `401` · `403` · `404`.
- **`PUT /services/{serviceId}/material-norms`** — Lưu định mức vật tư của dịch vụ. Request `SaveMaterialNormsRequest`. Response `200` mảng `MaterialNorm`. Lỗi: `400` `BR-SP-10` sản phẩm là vaccine / không tồn tại · số lượng < 1 · trùng sản phẩm · `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ShippingFeeConfig`** — {`baseFee`: int64, `freeThreshold`: int64, `updatedAt`: date-time}
- **`SaveShippingFeeConfigRequest`** — {`baseFee`: int64, `freeThreshold`: int64}
- **`HomeVisitFee`** — {`branchId`: int64, `enabled`: boolean, `fee`: int64}
- **`SaveHomeVisitFeeRequest`** — {`enabled`: boolean, `fee`: int64}
- **`MaterialNorm`** — {`productId`: int64, `productName`: string, `unit`: string, `quantity`: int32}
- **`MaterialNormInput`** — {`productId`: int64, `quantity`: int32}
- **`SaveMaterialNormsRequest`** — {`items`: [MaterialNormInput]}

---

## D. Bảo mật & độ tin cậy

1. Cấu hình và sửa: chỉ SUPER_MANAGER; đọc phí khám tại nhà thêm CUSTOMER (xem phí khi đặt lịch) và A06, A07 của chính chi nhánh.
2. Mọi thay đổi ghi audit, mở rộng BR-QT-15 (BR-SP-11).
3. Giá trị đã chốt vào đơn / lịch không đổi khi cấu hình đổi (BR-SP-11).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Phí vận chuyển là phí cố định kèm ngưỡng miễn phí, không theo vùng hay khoảng cách | YAGNI; BR-SP-08 |
| A2 | Định mức chỉ nhận sản phẩm `GOODS` / `DRUG`, không nhận vaccine | Vaccine đã trừ kho khi tiêm (BR-KO-05) |
| A3 | Mỗi chi nhánh một mức phí khám tại nhà cố định | UC32 không nêu cách tính; phương án ít phát minh nhất |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Phí khám tại nhà có cần theo khoảng cách hoặc theo vùng không | TBD (PO) | BR-SP-09 |
| Q2 | Định mức vật tư theo dịch vụ hay theo dịch vụ × loài / cân nặng | TBD (BE-2, BE-1) | BR-SP-10 |

---

## F. OpenAPI 3.1

[`./openapi/fees-v1.yaml`](./openapi/fees-v1.yaml)
