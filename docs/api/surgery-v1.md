# Surgery API v1 (tầng 3) — Pet Care v16

> **Module:** KB (phẫu thuật) · **Owner:** BE-1. VET lập kế hoạch phẫu thuật từ lượt khám, lễ tân lập cam kết với khách, VET thực hiện và hoàn tất (dịch vụ phẫu thuật vào Order của Visit). **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC50; 09-tang3-be1-spec.md §B (BR-KB-08…11; 03 #23 Phẫu thuật); 05 bổ sung surgeries.
> **Contract máy đọc:** [`./openapi/surgery-v1.yaml`](./openapi/surgery-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Phẫu thuật không phải lịch hẹn riêng (BR-LH-01): chỉ phát sinh từ lượt khám `IN_PROGRESS`.
- Dịch vụ phẫu thuật vào Order do hệ thống thêm khi hoàn tất (`VisitOrderApi`, UC67), không có endpoint thêm dòng ở đây.
- Hủy phẫu thuật do hủy Visit (Phẫu thuật#6) là chuyển do hệ thống, không có endpoint.
- Nhập viện sau phẫu thuật dùng inpatient-v1 (UC55).

---

## A. Danh sách endpoint (8)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /visits/{visitId}/surgeries` | Lập kế hoạch phẫu thuật từ lượt khám | UC50 | BR-KB-08 |
| 2 | `GET /visits/{visitId}/surgeries` | Phẫu thuật của một lượt khám | UC50 | BR-KB-08 |
| 3 | `GET /surgeries` | Phẫu thuật của chi nhánh | UC50 | BR-KB-09 |
| 4 | `GET /surgeries/{surgeryId}` | Chi tiết phẫu thuật | UC50 | BR-KB-08 |
| 5 | `POST /surgeries/{surgeryId}/consent` | Lập cam kết phẫu thuật | UC50 | BR-KB-09 |
| 6 | `POST /surgeries/{surgeryId}/start` | Bắt đầu phẫu thuật | UC50 | BR-KB-09, 10 |
| 7 | `POST /surgeries/{surgeryId}/complete` | Hoàn tất phẫu thuật | UC50 | BR-KB-10 |
| 8 | `POST /surgeries/{surgeryId}/cancel` | Hủy hoặc dừng phẫu thuật | UC50 | BR-KB-11 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Lập kế hoạch phẫu thuật từ lượt khám | A07 | VET phụ trách Visit | — → PLANNED (Phẫu thuật#1) | — | Thông báo lễ tân chi nhánh để lập cam kết. |
| Phẫu thuật của một lượt khám | A06, A07, A08 | A07 / A06 của chi nhánh; A08 chỉ xem | — | — | Không phân trang (ít dòng). Cũ nhất trước. |
| Phẫu thuật của chi nhánh | A06, A07 | A06, A07 của chi nhánh | — | — | `status` vắng = `PLANNED` + `CONSENTED` + `IN_PROGRESS` (việc cần làm). Cũ nhất trước. |
| Chi tiết phẫu thuật | A06, A07, A08 | A06, A07 của chi nhánh; A08 chỉ xem | — | — | — |
| Lập cam kết phẫu thuật | A06 | Lễ tân chi nhánh của Visit | PLANNED → CONSENTED (Phẫu thuật#2) | — | Bản chụp, không sửa sau khi lập. |
| Bắt đầu phẫu thuật | A07 | VET phụ trách Visit | CONSENTED → IN_PROGRESS (Phẫu thuật#3) | — | — |
| Hoàn tất phẫu thuật | A07 | VET phụ trách Visit | IN_PROGRESS → COMPLETED (Phẫu thuật#4) | — | Thêm dịch vụ phẫu thuật vào Order của Visit với giá chốt lúc này (BR-BH-03). |
| Hủy hoặc dừng phẫu thuật | A07, A06 | A07 phụ trách; A06 chỉ khi `PLANNED` / `CONSENTED` | PLANNED / CONSENTED / IN_PROGRESS → CANCELLED (Phẫu thuật#5) | — | — |

---

## C. Chi tiết endpoint

### Surgeries

- **`POST /visits/{visitId}/surgeries`** — Lập kế hoạch phẫu thuật từ lượt khám. Request `PlanSurgeryRequest`. Response `201` `Surgery`. Lỗi: `400` `BR-KB-08` Visit không `IN_PROGRESS` · dịch vụ không phải `SURGERY` hoặc không bật ở chi nhánh · `401` · `403` · `404`.
- **`GET /visits/{visitId}/surgeries`** — Phẫu thuật của một lượt khám. Response `200` mảng `Surgery`. Lỗi: `400` · `401` · `403` · `404`.
- **`GET /surgeries`** — Phẫu thuật của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `SurgerySummary`. Lỗi: `400` · `401` · `403`.
- **`GET /surgeries/{surgeryId}`** — Chi tiết phẫu thuật. Response `200` `Surgery`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /surgeries/{surgeryId}/consent`** — Lập cam kết phẫu thuật. Request `ConsentSurgeryRequest`. Response `200` `Surgery`. Lỗi: `400` `BR-KB-09` thiếu nội dung hoặc người ký · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái phẫu thuật không cho phép (03 #23).
- **`POST /surgeries/{surgeryId}/start`** — Bắt đầu phẫu thuật. Response `200` `Surgery`. Lỗi: `400` `BR-KB-09` chưa có cam kết · Visit không còn `IN_PROGRESS` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái phẫu thuật không cho phép (03 #23).
- **`POST /surgeries/{surgeryId}/complete`** — Hoàn tất phẫu thuật. Request `CompleteSurgeryRequest`. Response `200` `Surgery`. Lỗi: `400` `BR-KB-10` thiếu ghi chú kết quả · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái phẫu thuật không cho phép (03 #23).
- **`POST /surgeries/{surgeryId}/cancel`** — Hủy hoặc dừng phẫu thuật. Request `CancelSurgeryRequest`. Response `200` `Surgery`. Lỗi: `400` `BR-KB-11` thiếu lý do · `401` · `403` Lễ tân không hủy được phẫu thuật `IN_PROGRESS` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái phẫu thuật không cho phép (03 #23).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`SurgeryStatus`** — enum: `PLANNED`, `CONSENTED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`. 03 #23
- **`PlanSurgeryRequest`** — {`serviceId`: int64, `planNote?`: string}
- **`ConsentSurgeryRequest`** — {`consentText`: string, `signerName`: string, `signerRelation`: string}
- **`CompleteSurgeryRequest`** — {`resultNote`: string}
- **`CancelSurgeryRequest`** — {`reason`: string}
- **`SurgerySummary`** — {`surgeryId`: int64, `visitId`: int64, `petName`: string, `serviceName`: string, `status`: SurgeryStatus, `createdAt`: date-time}
- **`Surgery`** — {`surgeryId`: int64, `visitId`: int64, `petId`: int64, `petName`: string, `branchId`: int64, `serviceId`: int64, `serviceName`: string, `status`: SurgeryStatus, `planNote?`: string, `consentText?`: string, `consentSignerName?`: string, `consentSignerRelation?`: string, `consentedAt?`: date-time, `startedAt?`: date-time, `completedAt?`: date-time, `resultNote?`: string, `cancelReason?`: string, `orderLineId?`: int64, `createdAt`: date-time}

---

## D. Bảo mật & độ tin cậy

1. Chỉ VET đang phụ trách Visit lập kế hoạch, bắt đầu, hoàn tất (BR-KB-08, 10); lễ tân của chi nhánh của Visit lập cam kết và hủy trước khi bắt đầu (BR-KB-09, 11).
2. CARETAKER (A08) chỉ xem, như bệnh án (BR-KB-01); chi nhánh khác trả 403 / 404 (BR-QT-03).
3. Cam kết là bản chụp không sửa sau khi lập; ghi audit lập cam kết, hoàn tất, hủy (BR-QT-15 mở rộng).
4. Khóa Visit rồi mới đổi trạng thái phẫu thuật và thêm dòng Order, cùng transaction (04 nguyên tắc 6).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Thêm loại `SURGERY` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm (BR-SP-06) | Cần để chọn dịch vụ phẫu thuật; BE-2 xác nhận |
| A2 | Cam kết chỉ ghi nhận đã ký trên giấy, không ký điện tử | YAGNI; UC50 'lập cam kết' |
| A3 | Dừng phẫu thuật giữa chừng không tính dịch vụ | BR-KB-11; BE-1 / PO xác nhận |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | BE-2 xác nhận thêm `SURGERY`, `INPATIENT` vào `services.medical_type` | TBD (BE-2) | BR-SP-06 |
| Q2 | Có cần nhiều bác sĩ (phụ phẫu), gây mê riêng không | TBD (PO) | BR-KB-08 |
| Q3 | Dừng phẫu thuật có tính phí một phần không | TBD (PO) | BR-KB-11 |

---

## F. OpenAPI 3.1

[`./openapi/surgery-v1.yaml`](./openapi/surgery-v1.yaml)
