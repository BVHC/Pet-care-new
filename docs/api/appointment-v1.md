# Appointment API v1 — Pet Care v16

> **Module:** LH · **Owner:** BE-1. Khung giờ trống, đặt lịch, xem, đổi khung, hủy lịch hẹn; hạn chế đặt online.
> **Nguồn chân lý:** 01 UC39, UC40; 02 BR-LH-01…12, BR-TK-19, BR-KH-05, BR-TB-01, 06; 03 #3 Lịch hẹn; 04 §5; 05 §5 (appointments, booking_restrictions).
> **Contract máy đọc:** [`./openapi/appointment-v1.yaml`](./openapi/appointment-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC43 khám tại nhà là tầng 3, không có endpoint.
- Check-in lịch hẹn (Lịch hẹn#3) là một phần của tiếp nhận Visit: `POST /visits` ở module visit. Hoàn tất (#7) và hủy lượt (#8) cũng đi từ Visit.
- Hủy do phòng khám (#5) không có endpoint: chạy theo sự kiện ngày nghỉ / thu hẹp giờ, thú mất, chuyển chủ (06-module-contracts §3).
- ST03 nhắc lịch hẹn, ST05 chuyển NO_SHOW (#6) là tác vụ hệ thống.
- Cấu hình quota (UC42) thuộc module branch.

---

## A. Danh sách endpoint (9)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /branches/{branchId}/available-slots` | Xem khung giờ trống | UC39 | BR-LH-01, 02, 03, 04 |
| 2 | `POST /appointments` | Đặt lịch hẹn | UC39 | BR-LH-01…05, 09, BR-KH-05, BR-TK-19 |
| 3 | `GET /appointments` | Danh sách lịch hẹn | UC40, UC44 | BR-LH-11, BR-TN-02 |
| 4 | `GET /appointments/{appointmentId}` | Chi tiết lịch hẹn | UC40 | — |
| 5 | `POST /appointments/{appointmentId}/reschedule` | Đổi khung giờ | UC40 | BR-LH-03, 04, 05, 06 |
| 6 | `POST /appointments/{appointmentId}/cancel` | Hủy lịch hẹn | UC40 | BR-LH-07, 09 |
| 7 | `GET /me/booking-restriction` | Tình trạng hạn chế đặt online của tôi | UC39, UC58 | BR-LH-09 |
| 8 | `GET /customers/{customerId}/booking-restriction` | Tình trạng hạn chế đặt online của khách | UC39 | BR-LH-09 |
| 9 | `POST /customers/{customerId}/booking-restriction/lift` | Gỡ hạn chế sớm | UC39 | BR-LH-09, BR-QT-15 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem khung giờ trống | A02, A06 | Khách; lễ tân chi nhánh | — | — | Ngày nghỉ hoặc ngày không có giờ mở cửa trả mảng rỗng (A1). |
| Đặt lịch hẹn | A02, A06 | Khách: thú của mình · lễ tân: chi nhánh mình | — → BOOKED (Lịch hẹn#1); nhóm MEDICAL → Care Task#6 | — | Khách → channel ONLINE, lễ tân → COUNTER (A2). Nhóm MEDICAL: hủy nhắc tái khám đang chờ của thú (BR-TB-06). |
| Danh sách lịch hẹn | A02, A05, A06 | Khách: lịch của mình · nhân viên: chi nhánh mình | — | — | — |
| Chi tiết lịch hẹn | A02, A05, A06, A07, A08 | Khách: lịch của mình · nhân viên chi nhánh | — | — | — |
| Đổi khung giờ | A02, A06 | Khách: lịch của mình · lễ tân chi nhánh | BOOKED → BOOKED (Lịch hẹn#2) | — | Cùng chi nhánh, cùng thú, cùng dịch vụ; muốn đổi khác thì hủy rồi đặt lại. Giữ mã lịch hẹn. |
| Hủy lịch hẹn | A02, A06 | Khách: lịch của mình · lễ tân chi nhánh | BOOKED → CANCELLED (Lịch hẹn#4) | — | Còn < 12 giờ [CFG] → lateCancel = true và đánh giá hạn chế đặt online (BR-LH-09). |
| Tình trạng hạn chế đặt online của tôi | A02 | Khách đang đăng nhập | — | — | FE hiển thị lý do và ngày hết hạn chế. |
| Tình trạng hạn chế đặt online của khách | A05, A06 | Nhân viên | — | — | — |
| Gỡ hạn chế sớm | A05, A06 | Lễ tân, BRANCH_MANAGER | — | — | Ghi audit. |

---

## C. Chi tiết endpoint

### Slots

- **`GET /branches/{branchId}/available-slots`** — Xem khung giờ trống. Query: `serviceId`, `date`. Response `200` mảng `Slot`. Lỗi: `400` `BR-LH-01` dịch vụ không đặt được tại chi nhánh · `401` · `403` · `404`.
### Appointments

- **`POST /appointments`** — Đặt lịch hẹn. Request `BookAppointmentRequest`. Response `201` `Appointment`. Lỗi: `400` `BR-LH-01` dịch vụ không đặt được / đang tắt tại chi nhánh, chi nhánh chưa ACTIVE · `BR-LH-02` khung không hợp lệ · `BR-LH-03` khung đã đủ quota · `BR-LH-04` ngoài khoảng 24 giờ – 30 ngày [CFG] (khách) hoặc khung đã bắt đầu (lễ tân) · `BR-LH-05` thú đã có 2 lịch BOOKED, trùng nhóm trong ngày hoặc trùng khung · `BR-LH-09` khách đang bị hạn chế đặt online · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403` Thú không thuộc khách / chi nhánh ngoài phạm vi · `404`.
- **`GET /appointments`** — Danh sách lịch hẹn. Query: `branchId?`, `date?`, `from?`, `to?`, `status?`, `petId?`, `customerId?`, `checkInReady?`, `page?`, `size?`. Response `200` trang `Appointment`. Lỗi: `400` · `401` · `403`.
- **`GET /appointments/{appointmentId}`** — Chi tiết lịch hẹn. Response `200` `Appointment`. Lỗi: `401` · `403` · `404`.
- **`POST /appointments/{appointmentId}/reschedule`** — Đổi khung giờ. Request `RescheduleRequest`. Response `200` `Appointment`. Lỗi: `400` `BR-LH-06` hết lượt đổi (3 [CFG]) hoặc khách đổi khi còn < 12 giờ · `BR-LH-04`, `BR-LH-03`, `BR-LH-05` với khung mới · `401` · `403` · `404` · `409` Lịch không còn BOOKED.
- **`POST /appointments/{appointmentId}/cancel`** — Hủy lịch hẹn. Request `CancelAppointmentRequest`. Response `200` `Appointment`. Lỗi: `400` Đã qua giờ hẹn · `401` · `403` · `404` · `409` Lịch không còn BOOKED.
### Booking restrictions

- **`GET /me/booking-restriction`** — Tình trạng hạn chế đặt online của tôi. Response `200` `RestrictionStatus`. Lỗi: `401` · `403`.
- **`GET /customers/{customerId}/booking-restriction`** — Tình trạng hạn chế đặt online của khách. Response `200` `RestrictionStatus`. Lỗi: `401` · `403` · `404`.
- **`POST /customers/{customerId}/booking-restriction/lift`** — Gỡ hạn chế sớm. Request `LiftRestrictionRequest`. Response `200` `RestrictionStatus`. Lỗi: `400` · `401` · `403` · `404` · `409` Khách không đang bị hạn chế.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`AppointmentStatus`** — enum: `BOOKED`, `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW`. 03 #3
- **`Slot`** — {`slotStart`: string, `slotEnd`: string, `quota`: int32, `booked`: int32, `status`: AVAILABLE|FULL}
- **`BookAppointmentRequest`** — {`petId`: int64, `branchId`: int64, `serviceId`: int64, `slotDate`: date, `slotStart`: string, `note?`: string}
- **`Appointment`** — {`appointmentId`: int64, `code`: string, `customerId`: int64, `customerName`: string, `petId`: int64, `petName`: string, `branchId`: int64, `branchName`: string, `serviceId`: int64, `serviceName`: string, `serviceGroup`: MEDICAL|GROOMING, `slotDate`: date, `slotStart`: string, `status`: AppointmentStatus, `channel`: ONLINE|COUNTER, `rescheduleCount`: int32, `lateCancel`: boolean, `cancelSource?`: CUSTOMER|STAFF|CLINIC, `cancelReason?`: string, `cancelledAt?`: date-time, `note?`: string, `visitId?`: int64, `canReschedule`: boolean, `canCancel`: boolean}
- **`RescheduleRequest`** — {`slotDate`: date, `slotStart`: string}
- **`CancelAppointmentRequest`** — {`reason?`: string}
- **`BookingRestriction`** — {`restrictionId`: int64, `customerId`: int64, `startsAt`: date-time, `endsAt`: date-time, `violationCount`: int32}
- **`RestrictionStatus`** — {`restricted`: boolean, `restriction?`: BookingRestriction}
- **`LiftRestrictionRequest`** — {`reason`: string}

---

## D. Bảo mật & độ tin cậy

1. Khách chỉ đặt / xem / đổi / hủy lịch của thú mình là chủ hiện tại; nhân viên chỉ thao tác lịch của chi nhánh mình.
2. Đếm quota và INSERT trong cùng transaction có khóa theo chi nhánh × nhóm × khung (05 `appointments`), nên hai người không cùng lấy được chỗ cuối.
3. Khách: kiểm thời hạn đặt 24 giờ / 30 ngày [CFG], hạn chế online, cờ chờ liên kết. Lễ tân: đặt mọi khung chưa bắt đầu và còn quota; giới hạn số lịch của thú vẫn áp dụng (BR-LH-04, 05).
4. Gỡ hạn chế đặt online ghi audit (BR-LH-09).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Khung giờ trống trả theo từng ngày; khách chỉ thấy khung trong khoảng đặt được, khung hết quota hiện FULL | BR-LH-02, 03, 04: không hiển thị khung ngoài khoảng, khung đủ quota hiện 'Hết chỗ' |
| A2 | Lễ tân đặt hộ không truyền customerId; khách của lịch là chủ hiện tại của thú | BR-LH-01: lịch gồm 1 thú; 05 `appointments.customer_id` = chủ |
| A3 | Danh sách chờ check-in là bộ lọc `checkInReady=true` của danh sách lịch hẹn | BR-TN-02: chỉ hiển thị lịch trong cửa sổ check-in |
| A4 | Lý do hủy không bắt buộc khi khách / lễ tân hủy lịch BOOKED | BR-LH-07 không yêu cầu lý do |

Không có câu hỏi mở.

---

## F. OpenAPI 3.1

[`./openapi/appointment-v1.yaml`](./openapi/appointment-v1.yaml)
