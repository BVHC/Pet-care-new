# Boarding API v1 — Pet Care v16

> **Module:** LT (tầng 2) · **Owner:** BE-2. Chuồng, sức chứa theo đêm, đặt chỗ theo loại chuồng, gia hạn / hủy, nhận thú, trả thú, thú mất khi lưu trú, nhật ký chăm sóc.
> **Nguồn chân lý:** 01 UC54, UC57–UC60; 02 BR-LT-01…12, BR-LH-09, BR-KH-04, 05, BR-SP-04, BR-BH-06, BR-TK-19; 03 #6 Đặt chỗ lưu trú, #7 Chuồng (+ Order#3); 04 §7; 05 §7 (kennels, boarding_bookings, boarding_check_ins, care_logs, care_log_addenda).
> **Contract máy đọc:** [`./openapi/boarding-v1.yaml`](./openapi/boarding-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC55, UC56 nội trú là tầng 3: thú ốm khi lưu trú xử lý bằng Visit khám thường (BR-LT-12).
- Hủy phiên trả thú (Order#9) là thao tác trên Order: `POST /orders/{orderId}/abort-checkout` (sales). Thu tiền trả thú: `POST /payments`.
- Hủy do phòng khám vì ngày nghỉ, thú mất, chuyển chủ (Đặt chỗ#5) chạy theo sự kiện, không có endpoint. Riêng trường hợp hết chuồng lúc nhận có endpoint `…/cancel-no-kennel`.
- ST05 (NO_SHOW, Đặt chỗ#6) và ST15 (OVERDUE, Đặt chỗ#7) là tác vụ hệ thống.
- Loại chuồng (dịch vụ nhóm BOARDING) quản lý ở catalog; bật / tắt tại chi nhánh ở branch.

---

## A. Danh sách endpoint (22)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /kennels` | Danh sách chuồng của chi nhánh | UC54, UC59 | BR-LT-01 |
| 2 | `POST /kennels` | Tạo chuồng | UC54 | BR-LT-01, BR-SP-04 |
| 3 | `PATCH /kennels/{kennelId}` | Sửa mã / ghi chú chuồng | UC54 | BR-LT-01 |
| 4 | `DELETE /kennels/{kennelId}` | Xóa chuồng | UC54 | BR-LT-01 |
| 5 | `POST /kennels/{kennelId}/start-maintenance` | Chuyển chuồng sang bảo trì | UC54 | BR-LT-01, 03 |
| 6 | `POST /kennels/{kennelId}/end-maintenance` | Hết bảo trì | UC54 | BR-LT-01 |
| 7 | `GET /branches/{branchId}/boarding-availability` | Xem còn chỗ theo đêm | UC58 | BR-LT-01, 02, 03, 04 |
| 8 | `POST /boarding-bookings` | Đặt chỗ lưu trú | UC58 | BR-LT-02…05, BR-LH-09, BR-KH-05, BR-TK-19 |
| 9 | `GET /boarding-bookings` | Danh sách đặt chỗ | UC58, UC59, UC60 | BR-LT-07, 11 |
| 10 | `GET /boarding-bookings/{bookingId}` | Chi tiết đặt chỗ | UC58, UC60 | — |
| 11 | `POST /boarding-bookings/{bookingId}/extend` | Gia hạn ngày trả | UC58 | BR-LT-02, 03, 06 |
| 12 | `POST /boarding-bookings/{bookingId}/cancel` | Hủy đặt chỗ | UC58 | BR-LT-06, BR-LH-09 |
| 13 | `GET /boarding-bookings/{bookingId}/check-in-readiness` | Kiểm tra điều kiện nhận thú | UC59 | BR-LT-04, 05, 08 |
| 14 | `POST /boarding-bookings/{bookingId}/check-in` | Nhận thú | UC59 | BR-LT-04, 05, 08, BR-KH-04 |
| 15 | `POST /boarding-bookings/{bookingId}/cancel-no-kennel` | Hủy vì không còn chuồng đúng loại lúc nhận | UC59 | BR-LT-08, BR-LH-09 |
| 16 | `POST /boarding-bookings/{bookingId}/start-checkout` | Bắt đầu trả thú | UC59 | BR-LT-04, 09, BR-BH-01 |
| 17 | `POST /boarding-bookings/{bookingId}/hand-over` | Giao thú (trả thú) | UC59 | BR-LT-09, BR-BH-06 |
| 18 | `POST /boarding-bookings/{bookingId}/end-deceased` | Kết thúc lưu trú do thú mất | UC59 | BR-LT-12, BR-KH-05 |
| 19 | `GET /boarding-bookings/{bookingId}/care-logs` | Xem nhật ký chăm sóc | UC57, UC60 | BR-LT-11 |
| 20 | `POST /boarding-bookings/{bookingId}/care-logs` | Ghi nhật ký chăm sóc | UC57 | BR-LT-11, 12 |
| 21 | `PATCH /care-logs/{careLogId}` | Sửa nhật ký trong 1 giờ | UC57 | BR-LT-11 |
| 22 | `POST /care-logs/{careLogId}/addenda` | Thêm bản bổ sung nhật ký | UC57 | BR-LT-11 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách chuồng của chi nhánh | A05, A06, A08 | Nhân viên chi nhánh | — | — | — |
| Tạo chuồng | A05 | BRANCH_MANAGER chi nhánh | — → AVAILABLE (Chuồng#1) | — | — |
| Sửa mã / ghi chú chuồng | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Xóa chuồng | A05 | BRANCH_MANAGER chi nhánh | — | — | — |
| Chuyển chuồng sang bảo trì | A05 | BRANCH_MANAGER chi nhánh | AVAILABLE → MAINTENANCE (Chuồng#4) | — | — |
| Hết bảo trì | A05 | BRANCH_MANAGER chi nhánh | MAINTENANCE → AVAILABLE (Chuồng#5) | — | — |
| Xem còn chỗ theo đêm | A02, A06 | Khách đã đăng nhập; lễ tân | — | — | A1. |
| Đặt chỗ lưu trú | A02, A06 | Khách: thú của mình · lễ tân chi nhánh | — → BOOKED (Đặt chỗ#1) | — | Snapshot giá đêm. Thiếu mũi bắt buộc dự kiến: cảnh báo, không chặn. |
| Danh sách đặt chỗ | A02, A05, A06, A07, A08 | Khách: của mình · nhân viên chi nhánh | — | — | — |
| Chi tiết đặt chỗ | A02, A05–A08 | Khách: của mình · nhân viên chi nhánh | — | — | — |
| Gia hạn ngày trả | A02, A06 | Khách: thú của mình · lễ tân chi nhánh | BOOKED / CHECKED_IN giữ nguyên (Đặt chỗ#2) | — | — |
| Hủy đặt chỗ | A02, A06 | Khách: thú của mình · lễ tân chi nhánh | BOOKED → CANCELLED (Đặt chỗ#4) | — | Còn < 24 giờ [CFG] trước ngày nhận → hủy muộn, đánh giá hạn chế đặt online (BR-LH-09). |
| Kiểm tra điều kiện nhận thú | A06, A08 | Lễ tân, CARETAKER chi nhánh | — | — | — |
| Nhận thú | A06, A08 | Lễ tân, CARETAKER chi nhánh | BOOKED → CHECKED_IN (Đặt chỗ#3) · Chuồng#2 (→ OCCUPIED) | — | Cân nặng ghi vào lịch sử (source INTAKE); nhận sớm được nếu còn chỗ. |
| Hủy vì không còn chuồng đúng loại lúc nhận | A06 | Lễ tân chi nhánh | BOOKED → CANCELLED (Đặt chỗ#5, phòng khám hủy) | — | Không tính hủy muộn; thông báo khách và BRANCH_MANAGER (A3). |
| Bắt đầu trả thú | A06 | Chỉ lễ tân chi nhánh | CHECKED_IN / OVERDUE giữ nguyên (Đặt chỗ#9) · Order#3 (BOARDING → PENDING) | — | Đã có Order BOARDING PENDING thì dùng lại và tính lại số đêm. Tiếp theo: `POST /payments`. |
| Giao thú (trả thú) | A06 | Chỉ lễ tân chi nhánh | CHECKED_IN / OVERDUE → CHECKED_OUT, RETURNED (Đặt chỗ#10) · Chuồng#3 | — | — |
| Kết thúc lưu trú do thú mất | A06 | Chỉ lễ tân chi nhánh | CHECKED_IN / OVERDUE → CHECKED_OUT, PET_DECEASED (Đặt chỗ#11) · Chuồng#3 · Order#3 (tính đến ngày mất) | — | Sau đó mới đánh dấu thú đã mất (`POST /pets/{petId}/mark-deceased`). Order xử lý như PENDING thường. |
| Xem nhật ký chăm sóc | A02, A05–A08 | Khách: thú của mình · nhân viên chi nhánh | — | — | Khách xem ngay khi được ghi. |
| Ghi nhật ký chăm sóc | A07, A08 | CARETAKER, VET chi nhánh | CHECKED_IN / OVERDUE giữ nguyên (Đặt chỗ#8 nếu bất thường) | — | Bất thường: thông báo CARE_LOG_ABNORMAL cho khách và lễ tân. |
| Sửa nhật ký trong 1 giờ | A07, A08 | Người ghi, trong 1 giờ [CFG] | — | — | — |
| Thêm bản bổ sung nhật ký | A07, A08 | CARETAKER, VET chi nhánh (A4) | — | — | — |

---

## C. Chi tiết endpoint

### Kennels

- **`GET /kennels`** — Danh sách chuồng của chi nhánh. Query: `kennelTypeId?`, `status?`. Response `200` mảng `Kennel`. Lỗi: `401` · `403`.
- **`POST /kennels`** — Tạo chuồng. Request `CreateKennelRequest`. Response `201` `Kennel`. Lỗi: `400` `BR-LT-01` mã trùng trong chi nhánh · loại chuồng không phải dịch vụ BOARDING đang bật · `401` · `403` · `404`.
- **`PATCH /kennels/{kennelId}`** — Sửa mã / ghi chú chuồng. Request `UpdateKennelRequest`. Response `200` `Kennel`. Lỗi: `400` · `401` · `403` · `404`.
- **`DELETE /kennels/{kennelId}`** — Xóa chuồng. Response `204` rỗng. Lỗi: `401` · `403` · `404` · `409` Chuồng đang OCCUPIED.
- **`POST /kennels/{kennelId}/start-maintenance`** — Chuyển chuồng sang bảo trì. Response `200` `MaintenanceResult`. Lỗi: `401` · `403` · `404` · `409` Chuồng không AVAILABLE (OCCUPIED không chuyển được).
- **`POST /kennels/{kennelId}/end-maintenance`** — Hết bảo trì. Response `200` `Kennel`. Lỗi: `401` · `403` · `404` · `409` Chuồng không MAINTENANCE.
### Availability

- **`GET /branches/{branchId}/boarding-availability`** — Xem còn chỗ theo đêm. Query: `kennelTypeId`, `checkInDate`, `checkOutDate`. Response `200` `BoardingAvailability`. Lỗi: `400` `BR-LT-02` quá 30 đêm [CFG] hoặc ngày trả không sau ngày nhận · loại chuồng không bật tại chi nhánh · `401` · `403` · `404`.
### Bookings

- **`POST /boarding-bookings`** — Đặt chỗ lưu trú. Request `CreateBookingRequest`. Response `201` `BookingResult`. Lỗi: `400` `BR-LT-02` thú sai loài / quá cân nặng tối đa / ngoài 1–30 đêm [CFG] / chồng ngày với đặt chỗ khác · `BR-LT-03` hết chỗ (message ghi các đêm thiếu) · `BR-LT-04` khách đặt ngoài khoảng 1–60 ngày [CFG], ngày nhận / trả là ngày nghỉ · `BR-LH-09` đang bị hạn chế đặt online · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ chờ liên kết · loại chuồng không bật tại chi nhánh ACTIVE · `401` · `403` · `404`.
- **`GET /boarding-bookings`** — Danh sách đặt chỗ. Query: `status?`, `petId?`, `customerId?`, `from?`, `to?`, `inStay?`, `missingCareLogToday?`, `page?`, `size?`. Response `200` trang `Booking`. Lỗi: `400` · `401` · `403`.
- **`GET /boarding-bookings/{bookingId}`** — Chi tiết đặt chỗ. Response `200` `Booking`. Lỗi: `401` · `403` · `404`.
- **`POST /boarding-bookings/{bookingId}/extend`** — Gia hạn ngày trả. Request `ExtendBookingRequest`. Response `200` `Booking`. Lỗi: `400` `BR-LT-06` đã qua ngày trả dự kiến · `BR-LT-03` hết chỗ các đêm thêm · `BR-LT-02` vượt 30 đêm · `401` · `403` · `404` · `409` Đặt chỗ OVERDUE (không gia hạn) hoặc đã kết thúc.
- **`POST /boarding-bookings/{bookingId}/cancel`** — Hủy đặt chỗ. Request `CancelBookingRequest`. Response `200` `Booking`. Lỗi: `400` Đã tới ngày nhận · `401` · `403` · `404` · `409` Đặt chỗ không còn BOOKED.
### Stay

- **`GET /boarding-bookings/{bookingId}/check-in-readiness`** — Kiểm tra điều kiện nhận thú. Response `200` `CheckInReadiness`. Lỗi: `401` · `403` · `404` · `409` Đặt chỗ không còn BOOKED.
- **`POST /boarding-bookings/{bookingId}/check-in`** — Nhận thú. Request `CheckInRequest`. Response `200` `Booking`. Lỗi: `400` `BR-LT-05` thiếu / quá hạn mũi bắt buộc (lễ tân tiếp nhận walk-in để tiêm trước) · `BR-LT-08` chuồng không AVAILABLE hoặc sai loại, thiếu cân nặng · `BR-LT-04` ngoài giờ mở cửa · `BR-LT-03` nhận sớm khi hết chỗ · `401` · `403` · `404` · `409` Đặt chỗ không còn BOOKED.
- **`POST /boarding-bookings/{bookingId}/cancel-no-kennel`** — Hủy vì không còn chuồng đúng loại lúc nhận. Response `200` `Booking`. Lỗi: `400` Vẫn còn chuồng AVAILABLE đúng loại · `401` · `403` · `404` · `409` Đặt chỗ không còn BOOKED.
- **`POST /boarding-bookings/{bookingId}/start-checkout`** — Bắt đầu trả thú. Response `200` `CheckoutStarted`. Lỗi: `400` `BR-LT-04` ngoài giờ mở cửa · `401` · `403` · `404` · `409` Thú không còn trong chuồng.
- **`POST /boarding-bookings/{bookingId}/hand-over`** — Giao thú (trả thú). Response `200` `Booking`. Lỗi: `400` `BR-LT-09` Order lưu trú chưa PAID — không có ngoại lệ · `401` · `403` · `404` · `409` Thú không còn trong chuồng.
- **`POST /boarding-bookings/{bookingId}/end-deceased`** — Kết thúc lưu trú do thú mất. Request `DeceasedEndRequest`. Response `200` `CheckoutStarted`. Lỗi: `400` · `401` · `403` · `404` · `409` Thú không còn trong chuồng.
### Care logs

- **`GET /boarding-bookings/{bookingId}/care-logs`** — Xem nhật ký chăm sóc. Response `200` mảng `CareLog`. Lỗi: `401` · `403` · `404`.
- **`POST /boarding-bookings/{bookingId}/care-logs`** — Ghi nhật ký chăm sóc. Request `CareLogRequest`. Response `201` `CareLog`. Lỗi: `400` Quá 5 ảnh [CFG] · `401` · `403` · `404` · `409` Thú không đang lưu trú.
- **`PATCH /care-logs/{careLogId}`** — Sửa nhật ký trong 1 giờ. Request `UpdateCareLogRequest`. Response `200` `CareLog`. Lỗi: `400` · `401` · `403` · `404` · `409` `BR-LT-11` đã quá thời hạn sửa — dùng bản bổ sung.
- **`POST /care-logs/{careLogId}/addenda`** — Thêm bản bổ sung nhật ký. Request `AddendumRequest`. Response `201` `CareLogAddendum`. Lỗi: `400` · `401` · `403` · `404`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`KennelStatus`** — enum: `AVAILABLE`, `OCCUPIED`, `MAINTENANCE`. 03 #7
- **`BookingStatus`** — enum: `BOOKED`, `CHECKED_IN`, `OVERDUE`, `CHECKED_OUT`, `CANCELLED`, `NO_SHOW`. 03 #6
- **`Kennel`** — {`kennelId`: int64, `branchId`: int64, `kennelTypeId`: int64, `kennelTypeName`: string, `code`: string, `status`: KennelStatus, `note?`: string, `currentBookingId?`: int64}
- **`CreateKennelRequest`** — {`kennelTypeId`: int64, `code`: string, `note?`: string}
- **`UpdateKennelRequest`** — {`code?`: string, `note?`: string}
- **`AffectedBookingBrief`** — {`bookingId`: int64, `code`: string, `checkInDate`: date, `checkOutDate`: date, `petName`: string}
- **`MaintenanceResult`** — {`kennel`: Kennel, `shortageBookings`: [AffectedBookingBrief]}
- **`NightAvailability`** — {`date`: date, `capacity`: int32, `occupied`: int32, `remaining`: int32}
- **`BoardingAvailability`** — {`kennelTypeId`: int64, `nightlyPrice`: int64, `nights`: [NightAvailability], `available`: boolean}
- **`VaccineGap`** — {`vaccineTypeId`: int64, `vaccineTypeName`: string, `lastAdministeredOn?`: date, `nextDueDate?`: date}
- **`CreateBookingRequest`** — {`petId`: int64, `branchId`: int64, `kennelTypeId`: int64, `checkInDate`: date, `checkOutDate`: date}
- **`Booking`** — {`bookingId`: int64, `code`: string, `customerId`: int64, `customerName`: string, `petId`: int64, `petName`: string, `branchId`: int64, `kennelTypeId`: int64, `kennelTypeName`: string, `kennelId?`: int64, `kennelCode?`: string, `checkInDate`: date, `checkOutDate`: date, `actualCheckInAt?`: date-time, `actualCheckOutAt?`: date-time, `nightlyPrice`: int64, `status`: BookingStatus, `endReason?`: RETURNED|PET_DECEASED, `channel`: ONLINE|COUNTER, `lateCancel`: boolean, `cancelSource?`: CUSTOMER|STAFF|CLINIC, `cancelReason?`: string, `overdueSince?`: date-time, `activeOrderId?`: int64, `missingCareLogToday`: boolean}
- **`BookingResult`** — {`booking`: Booking, `vaccineWarnings`: [VaccineGap]}
- **`ExtendBookingRequest`** — {`checkOutDate`: date}
- **`CancelBookingRequest`** — {`reason?`: string}
- **`CheckInReadiness`** — {`vaccineGaps`: [VaccineGap], `availableKennels`: [Kennel], `withinOpeningHours`: boolean, `canCheckIn`: boolean}
- **`CheckInRequest`** — {`kennelId`: int64, `weightKg`: number, `healthCondition`: string, `belongings?`: string, `dietInstructions?`: string, `emergencyPhone`: string}
- **`CheckoutStarted`** — {`booking`: Booking, `orderId`: int64, `nights`: int32, `amount`: int64}
- **`DeceasedEndRequest`** — {`note`: string}
- **`CareLogAddendum`** — {`addendumId`: int64, `content`: string, `createdBy`: int64, `createdAt`: date-time}
- **`CareLog`** — {`careLogId`: int64, `bookingId`: int64, `logDate`: date, `eating?`: string, `drinking?`: string, `hygiene?`: string, `activity?`: string, `note?`: string, `photoUrls`: [string], `isAbnormal`: boolean, `recordedBy`: int64, `recordedByName`: string, `createdAt`: date-time, `editableUntil`: date-time, `addenda`: [CareLogAddendum]}
- **`CareLogRequest`** — {`logDate?`: date, `eating?`: string, `drinking?`: string, `hygiene?`: string, `activity?`: string, `note?`: string, `photoUrls`: [string], `isAbnormal?`: boolean}
- **`UpdateCareLogRequest`** — {`eating?`: string, `drinking?`: string, `hygiene?`: string, `activity?`: string, `note?`: string, `photoUrls?`: [string]}
- **`AddendumRequest`** — {`content`: string}

---

## D. Bảo mật & độ tin cậy

1. Khách chỉ đặt / xem / hủy / gia hạn đặt chỗ của thú mình; nhân viên chỉ thao tác đặt chỗ của chi nhánh mình.
2. Kiểm sức chứa từng đêm và INSERT / gia hạn / nhận sớm trong cùng transaction có khóa theo chi nhánh × loại chuồng (BR-LT-03).
3. Nhận thú: lễ tân hoặc CARETAKER. Trả thú, bắt đầu trả thú, kết thúc do thú mất: chỉ lễ tân (BR-LT-08, 09).
4. Giao thú chỉ khi Order lưu trú đã PAID, không ngoại lệ (BR-LT-09, BR-BH-06).
5. Nhật ký đánh dấu bất thường gửi thông báo ngay cho khách và lễ tân chi nhánh trong cùng transaction (outbox) (BR-LT-12).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Khách chọn khoảng ngày rồi xem còn chỗ từng đêm; đêm = ngày nhận … ngày trả − 1 | BR-LT-02, 03; `check_out_date > check_in_date` |
| A2 | Lý do hủy đặt chỗ không bắt buộc với khách / lễ tân | BR-LT-06 không yêu cầu |
| A3 | Hủy vì hết chuồng đúng loại lúc nhận là endpoint riêng do lễ tân bấm sau khi nhận thất bại | BR-LT-08: 'đặt chỗ được hủy như hủy do phòng khám' — cần người kích hoạt và thời điểm rõ ràng |
| A4 | Bản bổ sung nhật ký thêm được bất cứ lúc nào bởi CARETAKER / VET chi nhánh | BR-LT-11 'sau đó chỉ thêm bổ sung', không giới hạn thời gian |
| A5 | Ảnh nhật ký là URL; tối đa 5 [CFG] | 05 `care_logs.photo_urls` |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Cơ chế tải ảnh nhật ký chăm sóc (upload trực tiếp hay presigned URL)? | TBD (BE + FE) | 05 chỉ lưu URL |

---

## F. OpenAPI 3.1

[`./openapi/boarding-v1.yaml`](./openapi/boarding-v1.yaml)
