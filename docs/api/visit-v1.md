# Visit & Clinical API v1 — Pet Care v16

> **Module:** TN, KB · **Owner:** BE-1. Tiếp nhận, hàng đợi, gán / gọi / gán lại lượt, hoàn tất / hủy lượt; bệnh án, bản bổ sung, kê đơn, tiêm chủng; hồ sơ sức khỏe thú cưng.
> **Nguồn chân lý:** 01 UC25, UC44–UC49, UC52, UC53; 02 BR-TN-01…09, BR-KB-01…06, BR-KH-04, 05, 07, BR-CN-05, BR-BH-02; 03 #4 Visit (+ Lịch hẹn#3, #7, #8, Order#1, #4, #6, Care Task#5, #6); 04 §6; 05 §6 (visits, visit_assignments, medical_records, medical_record_addenda, prescription_items, vaccinations).
> **Contract máy đọc:** [`./openapi/visit-v1.yaml`](./openapi/visit-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC50 phẫu thuật, UC51 khám tại nhà, UC55–UC56 nội trú là tầng 3.
- Thêm / đổi / xóa dòng dịch vụ, hàng của Order nguồn VISIT (UC67) thuộc module sales: `/orders/{orderId}/lines`.
- ST04 nhắc tái chủng / tái khám, ST06 trừ kho khi tiêm (chạy trong `POST …/vaccinations`) và ST07 (tầng 3) không có endpoint riêng.
- Lịch sử cân nặng đọc ở module customer: `GET /pets/{petId}/weights`.

---

## A. Danh sách endpoint (21)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /visits` | Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu) | UC44, UC47 | BR-TN-01…04, BR-CN-05, BR-LH-01, BR-KH-05 |
| 2 | `GET /visits` | Hàng đợi của chi nhánh | UC45 | BR-TN-04, 09 |
| 3 | `GET /me/queue` | Hàng đợi của tôi | UC46 | BR-TN-05 |
| 4 | `GET /visits/{visitId}` | Chi tiết lượt | UC45, UC46 | — |
| 5 | `POST /visits/{visitId}/assign` | Gán / gán lại nhân viên (lượt chưa gọi) | UC45 | BR-TN-05, 06, 08 |
| 6 | `POST /visits/{visitId}/move` | Sắp lại thứ tự hàng đợi | UC45 | BR-TN-04 |
| 7 | `POST /visits/{visitId}/mark-emergency` | Xác định ca cấp cứu | UC47 | BR-TN-04 |
| 8 | `POST /visits/{visitId}/call` | Gọi lượt | UC46 | BR-TN-05, 09 |
| 9 | `POST /visits/{visitId}/reassign` | Gán lại lượt đã gọi | UC45 | BR-TN-05, 08, BR-QT-15 |
| 10 | `POST /visits/{visitId}/complete` | Hoàn tất lượt | UC48, UC49, UC52 | BR-KB-01, 02, BR-SP-06, BR-LH-11 |
| 11 | `POST /visits/{visitId}/cancel` | Hủy lượt khách bỏ về | UC45 | BR-TN-07 |
| 12 | `GET /visits/{visitId}/medical-record` | Xem bệnh án của lượt | UC48, UC53 | BR-KB-01 |
| 13 | `PUT /visits/{visitId}/medical-record` | Ghi bệnh án / hẹn tái khám | UC48 | BR-KB-01, 06 |
| 14 | `POST /visits/{visitId}/weight` | Ghi cân nặng khi khám | UC48 | BR-KH-04 |
| 15 | `POST /visits/{visitId}/medical-record/addenda` | Thêm bản bổ sung bệnh án | UC53 | BR-KB-01, BR-QT-15 |
| 16 | `POST /visits/{visitId}/prescriptions` | Kê đơn một dòng thuốc | UC48, UC67 | BR-KB-03, BR-SP-01, BR-BH-02 |
| 17 | `DELETE /visits/{visitId}/prescriptions/{itemId}` | Xóa dòng đơn thuốc | UC48 | BR-KB-03, BR-BH-02 |
| 18 | `GET /visits/{visitId}/vaccination-options` | Gợi ý mũi và sản phẩm vaccine | UC49 | BR-KB-04, BR-SP-02, 07, BR-KO-01 |
| 19 | `POST /visits/{visitId}/vaccinations` | Ghi nhận mũi tiêm | UC49, ST06 | BR-KB-04, BR-KO-05, BR-TB-03 |
| 20 | `DELETE /visits/{visitId}/vaccinations/{vaccinationId}` | Xóa mũi tiêm ghi nhầm | UC49 | BR-KB-04 |
| 21 | `GET /pets/{petId}/health-record` | Hồ sơ sức khỏe thú cưng | UC25, UC53 | BR-KH-07, BR-KB-01, 04, 06 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu) | A06 | Lễ tân; chi nhánh của lễ tân | — → WAITING (Visit#1) · Lịch hẹn#3 · Order#1 (OPEN + dòng dịch vụ tự sinh) · có dịch vụ Khám → Care Task#6 | — | Trễ 15–30 phút [CFG] xếp như walk-in. Khách mang nhiều thú thì tạo nhiều Visit. |
| Hàng đợi của chi nhánh | A05, A06, A07, A08 | Nhân viên chi nhánh | — | — | A1. |
| Hàng đợi của tôi | A07, A08 | VET, CARETAKER | — | — | Visit WAITING / IN_PROGRESS gán cho tôi. |
| Chi tiết lượt | A05–A08 | Nhân viên chi nhánh | — | — | — |
| Gán / gán lại nhân viên (lượt chưa gọi) | A06 | Lễ tân chi nhánh | WAITING → WAITING (Visit#2) | — | Dòng dịch vụ tự sinh chuyển quyền cho người được gán. |
| Sắp lại thứ tự hàng đợi | A06 | Lễ tân chi nhánh | — | — | A3. Trả hàng đợi sau khi sắp. |
| Xác định ca cấp cứu | A06, A07 | Nhân viên chi nhánh | — | — | Lượt lên đầu hàng đợi (A2). |
| Gọi lượt | A07, A08 | Người được gán lượt | WAITING → IN_PROGRESS (Visit#3) | — | — |
| Gán lại lượt đã gọi | A05 (A04 khi chi nhánh không còn BRANCH_MANAGER ACTIVE) | BRANCH_MANAGER chi nhánh; SUPER_MANAGER theo điều kiện | IN_PROGRESS → IN_PROGRESS (Visit#4) | — | Bệnh án giữ nguyên kèm tên người ghi; quyền xóa dòng Order chuyển cho người mới; ghi audit. |
| Hoàn tất lượt | A07, A08 | Người được gán lượt | IN_PROGRESS → COMPLETED (Visit#5) · Lịch hẹn#7 · Order#4 (→ PENDING) · bệnh án khóa | — | — |
| Hủy lượt khách bỏ về | A06 | Lễ tân chi nhánh | WAITING → CANCELLED (Visit#6) · Lịch hẹn#8 · Order#6 | — | Lịch hẹn → CANCELLED, không tính hủy muộn. |
| Xem bệnh án của lượt | A07, A08 | VET, CARETAKER chi nhánh (CARETAKER chỉ xem) | — | — | — |
| Ghi bệnh án / hẹn tái khám | A07 | VET được gán lượt | — | — | — |
| Ghi cân nặng khi khám | A07 | VET được gán lượt | — | — | source = VET (A8). |
| Thêm bản bổ sung bệnh án | A07 | VET chi nhánh nơi khám (A7) | — | — | Không sửa / xóa nội dung cũ; ghi audit. |
| Kê đơn một dòng thuốc | A07 | VET được gán lượt | — | — | Đủ tồn → sinh 1 dòng Order DRUG; mua ngoài → không sinh dòng; không tách dòng. |
| Xóa dòng đơn thuốc | A07 | VET được gán lượt | — | — | Xóa kèm dòng Order DRUG tương ứng. |
| Gợi ý mũi và sản phẩm vaccine | A07 | VET được gán lượt | — | — | — |
| Ghi nhận mũi tiêm | A07 | VET được gán lượt | IN_PROGRESS giữ nguyên · dòng Order VACCINE · trừ kho FEFO · Care Task#5 | — | Ngày tiêm = hôm nay (A6). Chưa đủ tuổi: cảnh báo trong warnings, không chặn. |
| Xóa mũi tiêm ghi nhầm | A07 | VET đã ghi mũi đó | — | — | Hoàn kho đúng lô, xóa dòng Order vaccine trong cùng transaction. |
| Hồ sơ sức khỏe thú cưng | A02, A06, A07, A08 | Khách là chủ hiện tại; nhân viên | — | — | Khách không nhận internalNote và bản bổ sung nội bộ. Chỉ mũi tiêm tại hệ thống. |

---

## C. Chi tiết endpoint

### Queue

- **`POST /visits`** — Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu). Request `CreateVisitRequest`. Response `201` `Visit`. Lỗi: `400` `BR-TN-01` thú đã mất / walk-in thiếu dịch vụ · `BR-TN-02` ngoài cửa sổ check-in · `BR-CN-05` ngoài giờ mà chi nhánh không có cờ hoặc chưa đánh dấu cấp cứu · `BR-CN-01` chi nhánh chưa ACTIVE · `BR-LH-01` dịch vụ đang tắt · `401` · `403` · `404` · `409` Lịch hẹn không còn BOOKED (đã check-in / NO_SHOW: tiếp nhận như walk-in, BR-TN-03).
- **`GET /visits`** — Hàng đợi của chi nhánh. Query: `date?`, `status?`, `serviceGroup?`, `assigneeId?`, `needsReassign?`. Response `200` mảng `Visit`. Lỗi: `400` · `401` · `403`.
- **`GET /me/queue`** — Hàng đợi của tôi. Response `200` mảng `Visit`. Lỗi: `401` · `403`.
- **`GET /visits/{visitId}`** — Chi tiết lượt. Response `200` `Visit`. Lỗi: `401` · `403` · `404`.
- **`POST /visits/{visitId}/assign`** — Gán / gán lại nhân viên (lượt chưa gọi). Request `AssignRequest`. Response `200` `AssignResult`. Lỗi: `400` `BR-TN-05` nhân viên không ACTIVE, khác chi nhánh hoặc sai chức vụ · `401` · `403` · `404` · `409` Lượt đã được gọi.
- **`POST /visits/{visitId}/move`** — Sắp lại thứ tự hàng đợi. Request `MoveVisitRequest`. Response `200` mảng `Visit`. Lỗi: `400` · `401` · `403` · `404` · `409` Lượt đã được gọi.
- **`POST /visits/{visitId}/mark-emergency`** — Xác định ca cấp cứu. Response `200` `Visit`. Lỗi: `401` · `403` · `404` · `409` Lượt đã được gọi hoặc đã là cấp cứu.
- **`POST /visits/{visitId}/call`** — Gọi lượt. Response `200` `Visit`. Lỗi: `401` · `403` `BR-TN-05` lượt không gán cho người gọi · `404` · `409` Lượt không còn WAITING.
- **`POST /visits/{visitId}/reassign`** — Gán lại lượt đã gọi. Request `ReassignRequest`. Response `200` `Visit`. Lỗi: `400` `BR-TN-08` người phụ trách vẫn ACTIVE · thiếu lý do · `BR-TN-05` người mới sai chức vụ · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
### Completion

- **`POST /visits/{visitId}/complete`** — Hoàn tất lượt. Response `200` `Visit`. Lỗi: `400` `BR-KB-02` có dịch vụ loại Khám mà chưa có chẩn đoán · chỉ có dịch vụ loại Tiêm mà chưa ghi mũi nào · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
- **`POST /visits/{visitId}/cancel`** — Hủy lượt khách bỏ về. Request `CancelVisitRequest`. Response `200` `Visit`. Lỗi: `401` · `403` · `404` · `409` `BR-TN-07` lượt đã được gọi — nhân viên hoàn tất theo BR-KB-02.
### Medical record

- **`GET /visits/{visitId}/medical-record`** — Xem bệnh án của lượt. Response `200` `MedicalRecord`. Lỗi: `401` · `403` · `404` Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án).
- **`PUT /visits/{visitId}/medical-record`** — Ghi bệnh án / hẹn tái khám. Request `UpdateMedicalRecordRequest`. Response `200` `MedicalRecord`. Lỗi: `400` `BR-KB-06` ngày tái khám không sau ngày khám hoặc quá 180 ngày [CFG] · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS — bệnh án đã khóa, dùng bản bổ sung.
- **`POST /visits/{visitId}/weight`** — Ghi cân nặng khi khám. Request `RecordWeightRequest`. Response `201` `WeightRecordRef`. Lỗi: `400` · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
- **`POST /visits/{visitId}/medical-record/addenda`** — Thêm bản bổ sung bệnh án. Request `AddendumRequest`. Response `201` `Addendum`. Lỗi: `400` · `401` · `403` · `404` · `409` Visit chưa COMPLETED — sửa trực tiếp bệnh án.
### Prescriptions

- **`POST /visits/{visitId}/prescriptions`** — Kê đơn một dòng thuốc. Request `AddPrescriptionRequest`. Response `201` `PrescriptionItem`. Lỗi: `400` `BR-KB-03` thiếu tồn khả dụng (message ghi số tồn hiện có) — gửi lại kèm shortageResolution · sản phẩm không phải thuốc kê đơn · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
- **`DELETE /visits/{visitId}/prescriptions/{itemId}`** — Xóa dòng đơn thuốc. Response `204` rỗng. Lỗi: `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
### Vaccinations

- **`GET /visits/{visitId}/vaccination-options`** — Gợi ý mũi và sản phẩm vaccine. Query: `vaccineTypeId`. Response `200` `VaccinationOptions`. Lỗi: `400` Loại vaccine không áp dụng cho loài của thú · `401` · `403` · `404`.
- **`POST /visits/{visitId}/vaccinations`** — Ghi nhận mũi tiêm. Request `RecordVaccinationRequest`. Response `201` `RecordVaccinationResult`. Lỗi: `400` `BR-KB-04` sản phẩm không thuộc loại vaccine, không còn tồn khả dụng, hoặc mũi không thuộc phác đồ đúng loài · `401` · `403` · `404` · `409` Lượt không IN_PROGRESS.
- **`DELETE /visits/{visitId}/vaccinations/{vaccinationId}`** — Xóa mũi tiêm ghi nhầm. Response `204` rỗng. Lỗi: `401` · `403` · `404` · `409` Visit đã COMPLETED — sai sót ghi bằng bản bổ sung.
### Health record

- **`GET /pets/{petId}/health-record`** — Hồ sơ sức khỏe thú cưng. Response `200` `HealthRecord`. Lỗi: `400` `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403` Không phải chủ hiện tại · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`VisitStatus`** — enum: `WAITING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`. 03 #4
- **`PriorityClass`** — enum: `EMERGENCY`, `APPOINTMENT`, `WALK_IN`. BR-TN-03, 04
- **`CreateVisitRequest`** — {`petId`: int64, `appointmentId?`: int64, `serviceId?`: int64, `isEmergency?`: boolean}
- **`Visit`** — {`visitId`: int64, `code`: string, `branchId`: int64, `customerId`: int64, `customerName`: string, `petId`: int64, `petName`: string, `species`: DOG|CAT|OTHER, `appointmentId?`: int64, `serviceGroup`: MEDICAL|GROOMING, `status`: VisitStatus, `priorityClass`: PriorityClass, `queueSortKey`: date-time, `assigneeId?`: int64, `assigneeName?`: string, `needsReassign`: boolean, `orderId`: int64, `checkedInAt`: date-time, `calledAt?`: date-time, `completedAt?`: date-time, `cancelledAt?`: date-time, `cancelReason?`: string}
- **`AssignRequest`** — {`assigneeId`: int64}
- **`AssignResult`** — {`visit`: Visit, `assigneeOffline`: boolean}
- **`MoveVisitRequest`** — {`placeBeforeVisitId`: int64}
- **`ReassignRequest`** — {`assigneeId`: int64, `reason`: string}
- **`CancelVisitRequest`** — {`reason?`: string}
- **`PrescriptionItem`** — {`itemId`: int64, `productId`: int64, `productName`: string, `quantity`: int32, `dosageInstructions`: string, `isExternalPurchase`: boolean, `externalReason?`: OUT_OF_STOCK_AT_PRESCRIBE|OUT_OF_STOCK_AT_PAYMENT, `orderLineId?`: int64}
- **`Addendum`** — {`addendumId`: int64, `content`: string, `isInternal`: boolean, `createdBy`: int64, `createdByName`: string, `createdAt`: date-time}
- **`VaccinationRecord`** — {`vaccinationId`: int64, `visitId`: int64, `branchId`: int64, `vaccineTypeId`: int64, `vaccineTypeName`: string, `protocolId`: int64, `doseNumber`: int32, `productId`: int64, `productName`: string, `lotNumber?`: string, `expiryDate?`: date, `administeredOn`: date, `nextDueDate`: date, `superseded`: boolean, `recordedBy`: int64, `orderLineId`: int64}
- **`MedicalRecord`** — {`visitId`: int64, `examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date, `locked`: boolean, `lastEditedBy?`: int64, `prescriptions`: [PrescriptionItem], `vaccinations`: [VaccinationRecord], `addenda`: [Addendum]}
- **`UpdateMedicalRecordRequest`** — {`examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date}
- **`RecordWeightRequest`** — {`weightKg`: number}
- **`WeightRecordRef`** — {`weightRecordId`: int64, `weightKg`: number, `measuredAt`: date-time}
- **`AddendumRequest`** — {`content`: string, `isInternal?`: boolean}
- **`AddPrescriptionRequest`** — {`productId`: int64, `quantity`: int32, `dosageInstructions`: string, `shortageResolution?`: REDUCE_TO_AVAILABLE|EXTERNAL_PURCHASE}
- **`VaccineProductOption`** — {`productId`: int64, `name`: string, `availableQuantity`: int32}
- **`VaccinationOptions`** — {`vaccineTypeId`: int64, `protocol`: [ProtocolDoseOption], `suggestedProtocolId?`: int64, `lastDose?`: VaccinationRecord, `products`: [VaccineProductOption], `underMinAge`: boolean}
- **`ProtocolDoseOption`** — {`protocolId`: int64, `doseNumber`: int32, `intervalDays`: int32, `minAgeWeeks`: int32, `requiredForBoarding`: boolean}
- **`RecordVaccinationRequest`** — {`vaccineTypeId`: int64, `protocolId`: int64, `productId`: int64}
- **`RecordVaccinationResult`** — {`vaccination`: VaccinationRecord, `warnings`: [string]}
- **`HealthVisitEntry`** — {`visitId`: int64, `visitDate`: date, `branchName`: string, `serviceGroup`: MEDICAL|GROOMING, `examination?`: string, `diagnosis?`: string, `treatmentPlan?`: string, `internalNote?`: string, `followUpDate?`: date, `prescriptions`: [PrescriptionItem], `addenda`: [Addendum]}
- **`UpcomingVaccination`** — {`vaccineTypeId`: int64, `vaccineTypeName`: string, `nextDueDate`: date}
- **`HealthRecord`** — {`petId`: int64, `visits`: [HealthVisitEntry], `vaccinations`: [VaccinationRecord], `upcomingVaccinations`: [UpcomingVaccination], `upcomingFollowUpDate?`: date}

---

## D. Bảo mật & độ tin cậy

1. Nhân viên chỉ thấy và thao tác Visit của chi nhánh mình. Chỉ người được gán gọi lượt, ghi bệnh án, kê đơn, tiêm, hoàn tất (BR-TN-05, BR-KB-01).
2. Tiếp nhận tạo Visit, chuyển lịch hẹn và mở Order trong một transaction (03 Phụ lục, 06 §4.1). Hoàn tất / hủy lượt tương tự.
3. Ghi mũi tiêm: khóa lô FEFO, sinh dòng Order, ghi mũi, trừ kho trong một transaction theo thứ tự ở 06-module-contracts §5. Xóa mũi ghi nhầm đi ngược lại.
4. `internalNote` và bản bổ sung `isInternal` không bao giờ trả cho khách (BR-KB-01, BR-KH-07).
5. Gán lại lượt đã gọi, bản bổ sung bệnh án ghi audit (BR-TN-08, BR-KB-01, BR-QT-15).
6. Thú đã mất không được tiếp nhận (BR-TN-01, BR-KH-05).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Danh sách hàng đợi trả theo thứ tự ưu tiên của BR-TN-04 (`priorityClass`, rồi `queueSortKey`), mặc định Visit hôm nay chưa kết thúc | BR-TN-04 |
| A2 | UC47 'Xác định ca cấp cứu' sau khi đã tiếp nhận: `POST …/mark-emergency` khi Visit còn WAITING | UC47 có actor A07, A06; tiếp nhận chỉ do A06 |
| A3 | Sắp lại thứ tự hàng đợi bằng cách đặt Visit trước một Visit khác; server tính lại `queue_sort_key` | 05 `visits.queue_sort_key`: lễ tân sắp lại thì sửa cột này |
| A4 | Lý do hủy lượt không bắt buộc | BR-TN-07 không yêu cầu; `visits.cancel_reason` cho phép null |
| A5 | Thiếu tồn khi kê đơn: request đầu trả 400 `BR-KB-03` kèm số tồn trong message; FE gửi lại với `shortageResolution` | BR-KB-03: báo số tồn rồi VET chọn giảm số lượng hoặc mua ngoài |
| A6 | Ngày tiêm = ngày hiện tại theo giờ Việt Nam, không cho chọn | BR-KB-04 trừ kho ngay khi ghi nhận; 05 `administered_on` không ở tương lai |
| A7 | Bản bổ sung bệnh án do VET của chi nhánh nơi khám ghi, sau khi Visit COMPLETED | BR-KB-01: chỉ VET; sau khi khóa mới có bản bổ sung |
| A8 | Cân nặng khi khám ghi bằng endpoint riêng, mỗi lần là một bản ghi | 05 `weight_records` là LOG |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Dòng dịch vụ tự sinh khi tiếp nhận có thể bị VET đổi (BR-TN-01). Nếu đổi từ dịch vụ thường sang dịch vụ loại Khám thì có hủy nhắc tái khám như Visit#1 có dịch vụ Khám không? | TBD (PO), tạm chỉ xét tại Visit#1 (06 G5) | 03 Care Task#6 |

---

## F. OpenAPI 3.1

[`./openapi/visit-v1.yaml`](./openapi/visit-v1.yaml)
