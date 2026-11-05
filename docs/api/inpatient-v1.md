# Inpatient API v1 (tầng 3) — Pet Care v16

> **Module:** LT (nội trú) · **Owner:** BE-1. Nhập / xuất viện (gồm chuyển thú đang lưu trú sang điều trị), y lệnh và theo dõi hậu phẫu. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Package thuộc BE-1 (đề xuất, Q2), không phải `boarding` của BE-2.
> **Nguồn chân lý:** 01 UC55, UC56; 09-tang3-be1-spec.md §C (BR-LT-13…17; 03 #24 Nhập viện, #25 Y lệnh nội trú); 05 bổ sung admissions, admission_orders, admission_observations.
> **Contract máy đọc:** [`./openapi/inpatient-v1.yaml`](./openapi/inpatient-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Dịch vụ nội trú vào Order do hệ thống thêm khi xuất viện (`VisitOrderApi`, UC67), không có endpoint thêm dòng ở đây.
- Kê đơn cho y lệnh `MEDICATION` dùng `POST /visits/{visitId}/prescriptions` của visit-v1 (#16); y lệnh chỉ liên kết dòng đơn.
- Khách chưa có endpoint xem nội trú (09 Q4).
- Đánh dấu thú đã mất khi kết cục `DECEASED` dùng `POST /pets/{petId}/mark-deceased` của customer-v1 (BR-KH-05).

---

## A. Danh sách endpoint (10)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /admissions` | Nhập viện | UC55 | BR-LT-13 |
| 2 | `GET /admissions` | Ca nội trú của chi nhánh | UC55 | BR-LT-17 |
| 3 | `GET /admissions/{admissionId}` | Chi tiết ca nội trú | UC55 | BR-LT-17 |
| 4 | `POST /admissions/{admissionId}/discharge` | Xuất viện | UC55 | BR-LT-14 |
| 5 | `POST /admissions/{admissionId}/orders` | Ra y lệnh | UC56 | BR-LT-15 |
| 6 | `GET /admissions/{admissionId}/orders` | Y lệnh của ca nội trú | UC56 | BR-LT-15 |
| 7 | `POST /admission-orders/{admissionOrderId}/complete` | Đánh dấu đã thực hiện y lệnh | UC56 | BR-LT-15 |
| 8 | `POST /admission-orders/{admissionOrderId}/cancel` | Hủy y lệnh | UC56 | BR-LT-15 |
| 9 | `POST /admissions/{admissionId}/observations` | Ghi chỉ số theo dõi | UC56 | BR-LT-16 |
| 10 | `GET /admissions/{admissionId}/observations` | Chỉ số theo dõi của ca nội trú | UC56 | BR-LT-16 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Nhập viện | A07 | VET của chi nhánh | — → ADMITTED (Nhập viện#1) | — | Từ lưu trú: tạo Visit điều trị gán VET gọi, đặt chỗ giữ nguyên `CHECKED_IN` (BR-LT-17). |
| Ca nội trú của chi nhánh | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | `status` vắng = `ADMITTED`. Nhập viện sớm nhất trước. |
| Chi tiết ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | — |
| Xuất viện | A07 | VET của chi nhánh | ADMITTED → DISCHARGED (Nhập viện#2) | — | Thêm dòng dịch vụ nội trú (số ngày × giá đã chốt) vào Order của Visit; giải phóng chuồng nếu được cấp riêng. |
| Ra y lệnh | A07 | VET của chi nhánh | — → ORDERED (Y lệnh nội trú#1) | — | — |
| Y lệnh của ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | Không phân trang. Sắp `scheduledAt` tăng dần, trùng thì `orderId` tăng dần. |
| Đánh dấu đã thực hiện y lệnh | A07, A08 | A07; A08 chỉ việc không phải thuốc | ORDERED → DONE (Y lệnh nội trú#2) | — | — |
| Hủy y lệnh | A07 | VET của chi nhánh | ORDERED → CANCELLED (Y lệnh nội trú#3) | — | — |
| Ghi chỉ số theo dõi | A07, A08 | A07, A08 của chi nhánh | — | — | Chỉ thêm, không sửa / xóa. `abnormal = true` báo VET và khách (BR-LT-12); cân nặng ghi vào hồ sơ cân (BR-KH-04). |
| Chỉ số theo dõi của ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | Mới nhất trước. |

---

## C. Chi tiết endpoint

### Admissions

- **`POST /admissions`** — Nhập viện. Request `AdmitRequest`. Response `201` `Admission`. Lỗi: `400` `BR-LT-13` không đúng một trong `visitId` / `boardingBookingId` · Visit không `IN_PROGRESS` / không của mình · đặt chỗ không `CHECKED_IN` · dịch vụ không `INPATIENT` · chuồng không `AVAILABLE` · thú đã mất · `401` · `403` · `404` Không tìm thấy Visit / đặt chỗ / dịch vụ / chuồng · `409` Thú đã có ca nội trú `ADMITTED`.
- **`GET /admissions`** — Ca nội trú của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `AdmissionSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /admissions/{admissionId}`** — Chi tiết ca nội trú. Response `200` `Admission`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /admissions/{admissionId}/discharge`** — Xuất viện. Request `DischargeRequest`. Response `200` `Admission`. Lỗi: `400` `BR-LT-14` còn y lệnh `ORDERED` · thiếu tóm tắt · `DECEASED` mà thú chưa đánh dấu đã mất · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25).
### Admission orders

- **`POST /admissions/{admissionId}/orders`** — Ra y lệnh. Request `CreateAdmissionOrderRequest`. Response `201` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` `MEDICATION` thiếu dòng đơn thuốc · dòng đơn không thuộc Visit · `401` · `403` · `404` · `409` Ca không `ADMITTED`.
- **`GET /admissions/{admissionId}/orders`** — Y lệnh của ca nội trú. Query: `status?`. Response `200` mảng `AdmissionOrder`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /admission-orders/{admissionOrderId}/complete`** — Đánh dấu đã thực hiện y lệnh. Request `CompleteAdmissionOrderRequest`. Response `200` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` thiếu ghi chú · `401` · `403` A08 không thực hiện y lệnh thuốc · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25).
- **`POST /admission-orders/{admissionOrderId}/cancel`** — Hủy y lệnh. Request `ReasonRequest`. Response `200` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25).
### Observations

- **`POST /admissions/{admissionId}/observations`** — Ghi chỉ số theo dõi. Request `CreateObservationRequest`. Response `201` `Observation`. Lỗi: `400` `BR-LT-16` không có chỉ số hay ghi chú nào · giá trị ngoài khoảng · `401` · `403` · `404` · `409` Ca không `ADMITTED`.
- **`GET /admissions/{admissionId}/observations`** — Chỉ số theo dõi của ca nội trú. Query: `page?`, `size?`. Response `200` trang `Observation`. Lỗi: `400` · `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`AdmissionStatus`** — enum: `ADMITTED`, `DISCHARGED`. 03 #24
- **`DischargeOutcome`** — enum: `HOME`, `DECEASED`.
- **`AdmissionOrderType`** — enum: `MEDICATION`, `FEEDING`, `MONITORING`, `PROCEDURE`, `OTHER`.
- **`AdmissionOrderStatus`** — enum: `ORDERED`, `DONE`, `CANCELLED`. 03 #25
- **`Appetite`** — enum: `NORMAL`, `REDUCED`, `NONE`.
- **`AdmitRequest`** — {`visitId?`: int64, `boardingBookingId?`: int64, `serviceId`: int64, `kennelId?`: int64}
- **`Admission`** — {`admissionId`: int64, `code`: string, `visitId`: int64, `petId`: int64, `petName`: string, `branchId`: int64, `boardingBookingId?`: int64, `kennelId?`: int64, `serviceId`: int64, `serviceName`: string, `unitPrice`: int64, `status`: AdmissionStatus, `admittedAt`: date-time, `dischargeOutcome?`: DischargeOutcome, `dischargeSummary?`: string, `dischargedAt?`: date-time, `orderLineId?`: int64}
- **`AdmissionSummary`** — {`admissionId`: int64, `code`: string, `petName`: string, `status`: AdmissionStatus, `admittedAt`: date-time, `kennelCode?`: string}
- **`DischargeRequest`** — {`outcome`: DischargeOutcome, `summary`: string}
- **`AdmissionOrder`** — {`admissionOrderId`: int64, `admissionId`: int64, `orderType`: AdmissionOrderType, `content`: string, `scheduledAt`: date-time, `status`: AdmissionOrderStatus, `prescriptionItemId?`: int64, `doneAt?`: date-time, `doneNote?`: string, `cancelReason?`: string}
- **`CreateAdmissionOrderRequest`** — {`orderType`: AdmissionOrderType, `content`: string, `scheduledAt`: date-time, `prescriptionItemId?`: int64}
- **`CompleteAdmissionOrderRequest`** — {`note`: string}
- **`ReasonRequest`** — {`reason`: string}
- **`Observation`** — {`observationId`: int64, `admissionId`: int64, `recordedByName`: string, `temperatureC?`: number, `heartRate?`: int32, `respiratoryRate?`: int32, `weightKg?`: number, `appetite?`: Appetite, `note?`: string, `abnormal`: boolean, `createdAt`: date-time}
- **`CreateObservationRequest`** — {`temperatureC?`: number, `heartRate?`: int32, `respiratoryRate?`: int32, `weightKg?`: number, `appetite?`: Appetite, `note?`: string, `abnormal`: boolean}

---

## D. Bảo mật & độ tin cậy

1. Chỉ nhân viên của chi nhánh của ca nội trú (BR-QT-03): VET nhập / xuất viện, ra và hủy y lệnh; A08 chỉ làm việc không phải thuốc và ghi theo dõi; A06 chỉ xem để lập phiếu thu.
2. Y lệnh thuốc chỉ VET thực hiện (BR-LT-15); theo dõi chỉ thêm, không sửa, không xóa (BR-LT-16).
3. Nhập viện từ lưu trú khóa đặt chỗ và thú (`PetQueryApi.findPetForShare`, ADR-0041) rồi tạo Visit điều trị trong một transaction.
4. Chi nhánh khác trả 403 / 404; mỗi thú tối đa một ca `ADMITTED` (BR-LT-13).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Thêm loại `INPATIENT` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm, giá theo ngày | BR-LT-13; BE-2 xác nhận |
| A2 | Nội trú dùng chung chuồng với lưu trú | UC55 không nêu chuồng riêng |
| A3 | Phí lưu trú theo đêm và phí nội trú theo ngày tính riêng | BR-LT-17 |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | BE-2 xác nhận `INPATIENT` trong `services.medical_type` và dùng chung chuồng | TBD (BE-2) | 09 §C |
| Q2 | Package `inpatient` thuộc BE-1 (đề xuất) hay `boarding` của BE-2 | TBD (BE-1, BE-2) | 06 §2, đề cương 2.4.4 |
| Q3 | Y lệnh có chu kỳ lặp (mỗi N giờ) hay mỗi y lệnh một lần | TBD (PO) | BR-LT-15 |
| Q4 | Khách có xem được tình trạng nội trú không | TBD (PO) | BR-LT-17 |

---

## F. OpenAPI 3.1

[`./openapi/inpatient-v1.yaml`](./openapi/inpatient-v1.yaml)
