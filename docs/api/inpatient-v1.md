# Inpatient API v1 (tầng 3) — Pet Care v16

> **Module:** LT (nội trú) · **Owner:** BE-1. Nhập / xuất viện (gồm chuyển thú đang lưu trú sang điều trị), y lệnh và theo dõi nội trú. **Đặc tả bổ sung GĐ2, duyệt ở T54 (Hiến, 5/11/2026), sửa ở ADR-0124 (9/11/2026), ADR-0148; #1–#10 đã cài (T69 N3–N6, ADR-0149).** Package thuộc BE-1 (T54), không phải `boarding` của BE-2.
> **Nguồn chân lý:** 01 UC55, UC56; 09-tang3-be1-spec.md §C (BR-LT-13…17; 03 #24 Nhập viện, #25 Y lệnh nội trú); 05 bổ sung admissions, admission_orders, admission_observations; ADR-0124.
> **Contract máy đọc:** [`./openapi/inpatient-v1.yaml`](./openapi/inpatient-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Dịch vụ nội trú vào Order do hệ thống thêm **một dòng** khi xuất viện (`VisitOrderApi.addServiceLine` với đơn giá đã chốt, UC67), nhập viện không thêm dòng; không có endpoint thêm dòng ở đây.
- Kê đơn cho y lệnh `MEDICATION` dùng `POST /visits/{visitId}/prescriptions` của visit-v1 (#16); y lệnh chỉ liên kết dòng đơn.
- Khách chưa có endpoint xem nội trú (09 Q4).
- Kết cục `DECEASED`: xuất viện không tự đánh dấu mất hay kết thúc lưu trú; lễ tân làm sau khi Visit hoàn tất (kết thúc lưu trú `PET_DECEASED`, rồi `POST /pets/{petId}/mark-deceased` của customer-v1, BR-LT-14).

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
| Nhập viện | A07 | VET của chi nhánh | — → ADMITTED (Nhập viện#1) | — | Từ lưu trú: tạo Visit điều trị gán VET gọi (Order không có dòng tự sinh), đặt chỗ giữ nguyên trạng thái (`CHECKED_IN` hoặc `OVERDUE`), thú ở nguyên chuồng (BR-LT-13, BR-LT-17). Visit điều trị là Visit#7, Order#14; chuồng điều trị là Chuồng#6, #7 (03). Thứ tự lỗi: 400 hình thức → 403 vai trò → 400 `BR-LT-13` nguồn → 404 Visit / đặt chỗ → 403 chi nhánh → 409 → 400 `BR-LT-13` điều kiện → 404 / 400 dịch vụ → 404 / 400 chuồng (ADR-0149). |
| Ca nội trú của chi nhánh | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | `status` vắng = `ADMITTED`. Chỉ ca của chi nhánh người gọi. Nhập viện sớm nhất trước (trùng thì `admissionId` tăng dần). `kennelCode`: chuồng điều trị (nhập từ khám) hoặc chuồng của đặt chỗ (nhập từ lưu trú). |
| Chi tiết ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | — |
| Xuất viện | A07 | VET của chi nhánh | ADMITTED → DISCHARGED (Nhập viện#2) | — | Thêm **một** dòng dịch vụ nội trú (số lượng = ngày xuất − ngày nhập + 1 theo ngày lịch giờ Việt Nam, đơn giá = `unitPrice` đã chốt, không phải giá danh mục hiện hành) vào Order của Visit trong cùng transaction; giải phóng chuồng nếu được cấp riêng. `DECEASED` không tự đánh dấu thú mất (BR-LT-14). Dòng này sau đó không sửa / xóa tay được (BR-LT-18). Thứ tự lỗi: 400 hình thức → 403 vai trò → 400 `BR-LT-14` tóm tắt rỗng → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-14` còn y lệnh. |
| Ra y lệnh | A07 | VET của chi nhánh | — → ORDERED (Y lệnh nội trú#1) | — | Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-15`. |
| Y lệnh của ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | Không phân trang. Sắp `scheduledAt` tăng dần, trùng thì `orderId` tăng dần. |
| Đánh dấu đã thực hiện y lệnh | A07, A08 | A07; A08 chỉ việc không phải thuốc | ORDERED → DONE (Y lệnh nội trú#2) | — | Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 A08 với y lệnh thuốc → 409 → 400 `BR-LT-15`. |
| Hủy y lệnh | A07 | VET của chi nhánh | ORDERED → CANCELLED (Y lệnh nội trú#3) | — | Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-15`. |
| Ghi chỉ số theo dõi | A07, A08 | A07, A08 của chi nhánh | — | — | Chỉ thêm, không sửa / xóa. Mọi VET / CARETAKER của chi nhánh ghi được, không cần là người phụ trách Visit. Có `weightKg` thì ghi thêm vào hồ sơ cân của thú (BR-KH-04) với nguồn `VET` gắn Visit của ca. `abnormal = true` báo khách (kênh theo BR-TB-02) và VET phụ trách (IN_APP; không gửi cho chính VET đó nếu họ là người ghi) bằng cặp mẫu `INPATIENT_OBSERVATION_ABNORMAL` / `_APP` (BR-LT-16, ADR-0148). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 409 → 400 `BR-LT-16` (ADR-0149). |
| Chỉ số theo dõi của ca nội trú | A06, A07, A08 | A06 chỉ xem; A07; A08 theo BR-LT-15 | — | — | Mới nhất trước (trùng thì `observationId` giảm dần). Đọc được cả ca đã xuất viện. |

---

## C. Chi tiết endpoint

### Admissions

- **`POST /admissions`** — Nhập viện. Request `AdmitRequest`. Response `201` `Admission`. Lỗi: `400` `BR-LT-13` không đúng một trong `visitId` / `boardingBookingId` · thiếu `kennelId` khi nhập từ khám / có `kennelId` khi nhập từ lưu trú · Visit không `IN_PROGRESS` / không của mình / không thuộc nhóm Khám/Tiêm · đặt chỗ không `CHECKED_IN` / `OVERDUE` · thú đang lưu trú mà nhập từ khám · dịch vụ không `INPATIENT` / ngừng kinh doanh / chưa bật ở chi nhánh (A5) · chuồng không `AVAILABLE` / khác chi nhánh · thú đã mất; `VALIDATION_FAILED` thiếu `serviceId` hoặc id ≤ 0; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Không phải VET · Visit / đặt chỗ của chi nhánh khác · `404` Không tìm thấy Visit / đặt chỗ / dịch vụ / chuồng · `409` Thú đã có ca nội trú `ADMITTED` (`INVALID_STATE_TRANSITION`; hai lần nhập viện đồng thời: bên thua `CONCURRENCY_CONFLICT`).
- **`GET /admissions`** — Ca nội trú của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `AdmissionSummary`. Lỗi: `400` · `401` · `403` Sai vai trò.
- **`GET /admissions/{admissionId}`** — Chi tiết ca nội trú. Response `200` `Admission`. Lỗi: `400` · `401` · `403` Sai vai trò · ca của chi nhánh khác · `404` Không tìm thấy ca nội trú.
- **`POST /admissions/{admissionId}/discharge`** — Xuất viện. Request `DischargeRequest`. Response `200` `Admission`. Lỗi: `400` `BR-LT-14` còn y lệnh `ORDERED` · thiếu tóm tắt (rỗng sau `strip()`); `VALIDATION_FAILED` thiếu kết cục / tóm tắt hoặc tóm tắt quá 2000 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit · `404` Không tìm thấy ca nội trú · `409` `INVALID_STATE_TRANSITION` ca không còn `ADMITTED` (03 #24); hai xuất viện đồng thời: bên sau 409.
### Admission orders

- **`POST /admissions/{admissionId}/orders`** — Ra y lệnh. Request `CreateAdmissionOrderRequest`. Response `201` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` `MEDICATION` thiếu dòng đơn thuốc · dòng đơn không thuộc Visit của ca · dòng đơn mua ngoài · loại khác `MEDICATION` mà có dòng đơn · nội dung rỗng sau `strip()`; `VALIDATION_FAILED` thiếu `orderType` / `content` / `scheduledAt`, `content` quá 500 ký tự hoặc `prescriptionItemId` ≤ 0; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit · `404` Không tìm thấy ca nội trú · `409` `INVALID_STATE_TRANSITION` ca không còn `ADMITTED`.
- **`GET /admissions/{admissionId}/orders`** — Y lệnh của ca nội trú. Query: `status?`. Response `200` mảng `AdmissionOrder`. Lỗi: `400` · `401` · `403` Sai vai trò · ca của chi nhánh khác · `404` Không tìm thấy ca nội trú.
- **`POST /admission-orders/{admissionOrderId}/complete`** — Đánh dấu đã thực hiện y lệnh. Request `CompleteAdmissionOrderRequest`. Response `200` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` ghi chú rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Sai vai trò · y lệnh của chi nhánh khác · A08 không thực hiện y lệnh thuốc · `404` Không tìm thấy y lệnh · `409` `INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25).
- **`POST /admission-orders/{admissionOrderId}/cancel`** — Hủy y lệnh. Request `AdmissionOrderReasonRequest`. Response `200` `AdmissionOrder`. Lỗi: `400` `BR-LT-15` lý do rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Không phải VET · y lệnh của chi nhánh khác · không phải VET phụ trách Visit · `404` Không tìm thấy y lệnh · `409` `INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25).
### Observations

- **`POST /admissions/{admissionId}/observations`** — Ghi chỉ số theo dõi. Request `CreateObservationRequest`. Response `201` `Observation`. Lỗi: `400` `BR-LT-16` không có chỉ số hay ghi chú nào (rỗng sau `strip()`) · giá trị ngoài giới hạn lưu trữ (nhiệt độ, nhịp tim / nhịp thở 1…32767, cân nặng 0.01…9999.99 kg); `VALIDATION_FAILED` thiếu `abnormal` hoặc ghi chú quá 500 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17 · `401` · `403` Không phải VET / CARETAKER · ca của chi nhánh khác · `404` Không tìm thấy ca nội trú · `409` `INVALID_STATE_TRANSITION` ca không còn `ADMITTED`.
- **`GET /admissions/{admissionId}/observations`** — Chỉ số theo dõi của ca nội trú. Query: `page?`, `size?`. Response `200` trang `Observation`. Lỗi: `400` · `401` · `403` Sai vai trò · ca của chi nhánh khác · `404` Không tìm thấy ca nội trú.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`AdmissionStatus`** — enum: `ADMITTED`, `DISCHARGED`. 03 #24
- **`DischargeOutcome`** — enum: `HOME`, `DECEASED`.
- **`AdmissionOrderType`** — enum: `MEDICATION`, `FEEDING`, `MONITORING`, `PROCEDURE`, `OTHER`.
- **`AdmissionOrderStatus`** — enum: `ORDERED`, `DONE`, `CANCELLED`. 03 #25
- **`Appetite`** — enum: `NORMAL`, `REDUCED`, `NONE`.
- **`AdmitRequest`** — {`visitId?`: int64, `boardingBookingId?`: int64, `serviceId`: int64, `kennelId?`: int64}
- **`Admission`** — {`admissionId`: int64, `code`: string, `visitId`: int64, `petId`: int64, `petName`: string, `branchId`: int64, `boardingBookingId?`: int64, `kennelId?`: int64, `serviceId`: int64, `serviceName`: string, `unitPrice`: int64, `status`: AdmissionStatus, `admittedAt`: date-time, `billedDays`: int32, `estimatedAmount`: int64, `dischargeOutcome?`: DischargeOutcome, `dischargeSummary?`: string, `dischargedAt?`: date-time, `orderLineId?`: int64}
- **`AdmissionSummary`** — {`admissionId`: int64, `code`: string, `petName`: string, `status`: AdmissionStatus, `admittedAt`: date-time, `kennelCode?`: string, `billedDays`: int32, `estimatedAmount`: int64}
- **`DischargeRequest`** — {`outcome`: DischargeOutcome, `summary`: string}
- **`AdmissionOrder`** — {`admissionOrderId`: int64, `admissionId`: int64, `orderType`: AdmissionOrderType, `content`: string, `scheduledAt`: date-time, `status`: AdmissionOrderStatus, `prescriptionItemId?`: int64, `doneAt?`: date-time, `doneNote?`: string, `cancelReason?`: string}
- **`CreateAdmissionOrderRequest`** — {`orderType`: AdmissionOrderType, `content`: string, `scheduledAt`: date-time, `prescriptionItemId?`: int64}
- **`CompleteAdmissionOrderRequest`** — {`note`: string}
- **`AdmissionOrderReasonRequest`** — {`reason`: string}
- **`Observation`** — {`observationId`: int64, `admissionId`: int64, `recordedByName`: string, `temperatureC?`: number, `heartRate?`: int32, `respiratoryRate?`: int32, `weightKg?`: number, `appetite?`: Appetite, `note?`: string, `abnormal`: boolean, `createdAt`: date-time}
- **`CreateObservationRequest`** — {`temperatureC?`: number, `heartRate?`: int32, `respiratoryRate?`: int32, `weightKg?`: number, `appetite?`: Appetite, `note?`: string, `abnormal`: boolean}

---

## D. Bảo mật & độ tin cậy

1. Chỉ nhân viên của chi nhánh của ca nội trú (BR-QT-03): VET được gán Visit nhập / xuất viện, ra và hủy y lệnh (gán lại Visit chuyển quyền, BR-TN-08); A08 chỉ làm việc không phải thuốc và ghi theo dõi; A06 chỉ xem để lập phiếu thu.
2. Y lệnh thuốc chỉ VET thực hiện (BR-LT-15); theo dõi chỉ thêm, không sửa, không xóa (BR-LT-16).
3. Nhập viện khóa thú `FOR SHARE` (`PetQueryApi.findPetForShare`, ADR-0041), rồi khóa lượt khám (từ khám) hoặc đặt chỗ `FOR SHARE` (từ lưu trú), rồi mới tạo Visit điều trị / chiếm chuồng và ca trong một transaction (ADR-0149).
4. Chi nhánh khác trả 403 / 404; mỗi thú tối đa một ca `ADMITTED` (BR-LT-13).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Thêm loại `INPATIENT` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm, giá theo ngày | BR-LT-13; BE-2 xác nhận |
| A2 | Nội trú dùng chung chuồng với lưu trú: nhập từ lưu trú giữ chuồng cũ, nhập từ khám lấy chuồng `AVAILABLE` | UC55 không nêu chuồng riêng; ADR-0124 |
| A3 | Phí lưu trú theo đêm và phí nội trú theo ngày tính riêng (thú bị tính cả hai cho những ngày điều trị) | BR-LT-17; ADR-0124 |
| A5 | Dịch vụ nội trú khi nhập viện phải đang kinh doanh và đang bật tại chi nhánh của ca (cùng nghĩa bật / tắt dịch vụ của walk-in); lượt khám phải thuộc nhóm Khám/Tiêm | BR-LT-13 chỉ nêu loại `INPATIENT`; BR-LH-01 (ghi chú ADR-0088); ADR-0149 |
| A6 | `serviceName`, `petName`, `kennelCode` là tên / mã hiện tại; chỉ `unitPrice` là giá đã chốt | BR-BH-03; ADR-0149 |
| A7 | Y lệnh thuốc (`MEDICATION`) chỉ liên kết dòng đơn thuốc **của phòng khám** (có dòng Order): dòng mua ngoài, dòng của lượt khác hoặc không tồn tại bị 400; loại y lệnh khác `MEDICATION` mà gửi `prescriptionItemId` cũng 400. `scheduledAt` không giới hạn (cho ghi bù y lệnh đã làm) | BR-LT-15; Mạnh chốt 2026-11-24; ADR-0149 mục 27 |
| A4 | Chưa có khoảng lâm sàng cho nhiệt độ, nhịp tim, nhịp thở: 400 "giá trị ngoài khoảng" của #9 hiện chỉ là ngoài giới hạn lưu trữ của cột (nhiệt độ `NUMERIC(4,1)`, nhịp 1…32 767) và cân nặng ≤ 0 (BR-KH-04); PO chốt khoảng thì mới chặn theo lâm sàng | BR-LT-16; ADR-0149 mục 8 |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | BE-2 xác nhận `INPATIENT` trong `services.medical_type`, dùng chung chuồng, `VisitOrderApi.addServiceLine(visitId, serviceId, quantity, unitPrice, actorId)` và guard kết thúc lưu trú | Đã xác nhận bằng lời qua Mạnh 2026-11-09, chưa có văn bản của BE-2 | 09 §C; ADR-0124 |

---

## F. OpenAPI 3.1

[`./openapi/inpatient-v1.yaml`](./openapi/inpatient-v1.yaml)
