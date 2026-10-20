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
| 5 | `POST /appointments/{appointmentId}/reschedule` | Đổi khung giờ | UC40 | BR-LH-02, 03, 04, 05, 06 |
| 6 | `POST /appointments/{appointmentId}/cancel` | Hủy lịch hẹn | UC40 | BR-LH-07, 09 |
| 7 | `GET /me/booking-restriction` | Tình trạng hạn chế đặt online của tôi | UC39, UC58 | BR-LH-09 |
| 8 | `GET /customers/{customerId}/booking-restriction` | Tình trạng hạn chế đặt online của khách | UC39 | BR-LH-09 |
| 9 | `POST /customers/{customerId}/booking-restriction/lift` | Gỡ hạn chế sớm | UC39 | BR-LH-09, BR-QT-15 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem khung giờ trống | A02, A06 | Khách; lễ tân chi nhánh | — | — | Ngày nghỉ hoặc ngày không có giờ mở cửa trả mảng rỗng (A1). Khách chỉ thấy khung bắt đầu từ now + 24 giờ [CFG] và ngày hẹn ≤ hôm nay + 30 ngày [CFG]; lễ tân thấy mọi khung chưa bắt đầu (BR-LH-04). Khung AVAILABLE chưa giữ chỗ: đặt lịch kiểm lại (docs/adr/0046). |
| Đặt lịch hẹn | A02, A06 | Khách: thú của mình · lễ tân: chi nhánh mình | — → BOOKED (Lịch hẹn#1); nhóm MEDICAL → Care Task#6 | — | Khách → channel ONLINE, lễ tân → COUNTER (A2). Nhóm MEDICAL: hủy nhắc tái khám đang chờ của thú (BR-TB-06); nối Care Task#6 khi visit cài FollowUpApi (docs/adr/0047). |
| Danh sách lịch hẹn | A02, A05, A06 | Khách: lịch của mình · nhân viên: chi nhánh mình | — | — | Khách: chỉ lịch có `customerId` là hồ sơ của mình (chủ lúc đặt); nhân viên: chi nhánh của mình, `branchId` khác → 403. Mọi bộ lọc là AND; `from > to` → trang rỗng; `checkInReady` rỗng / vắng / false = không lọc. `checkInReady=true`: lịch BOOKED, ngày hẹn hôm nay, giờ hẹn − `visit.check_in_early_minutes` ≤ now ≤ giờ hẹn + `appointment.no_show_after_minutes` [CFG] (BR-TN-02, 03, BR-LH-08). Tăng dần theo `slotDate`, `slotStart`, `appointmentId`. `page` < 0 → 0, `size` kẹp 1–100. `canReschedule` / `canCancel` theo người xem; BRANCH_MANAGER luôn false (docs/adr/0048). |
| Chi tiết lịch hẹn | A02, A05, A06, A07, A08 | Khách: lịch của mình · nhân viên chi nhánh | — | — | `canReschedule` / `canCancel` theo người xem; BRANCH_MANAGER, VET, CARETAKER luôn false (docs/adr/0048). |
| Đổi khung giờ | A02, A06 | Khách: lịch của mình · lễ tân chi nhánh | BOOKED → BOOKED (Lịch hẹn#2) | — | Cùng chi nhánh, cùng thú, cùng dịch vụ; muốn đổi khác thì hủy rồi đặt lại. Giữ mã lịch hẹn. Khung mới trùng khung hiện tại → 200, không ghi, không tăng số lần đổi. BR-LH-05 trên mọi chi nhánh, không tính chính lịch này. Không kiểm lại BR-LH-01, BR-LH-09, BR-KH-05; đổi khung xóa mốc đã nhắc (BR-LH-12) (docs/adr/0049). |
| Hủy lịch hẹn | A02, A06 | Khách: lịch của mình · lễ tân chi nhánh | BOOKED → CANCELLED (Lịch hẹn#4) | — | Body không bắt buộc. Còn < 12 giờ [CFG] trước giờ hẹn → lateCancel = true và đánh giá hạn chế đặt online (BR-LH-09: đếm NO_SHOW + hủy muộn của lịch hẹn và đặt chỗ sau mốc max(now − 90 ngày, lần hạn chế gần nhất); đủ 3 [CFG] → hạn chế 30 ngày [CFG]). cancelSource = CUSTOMER (khách) / STAFF (lễ tân) (docs/adr/0049). |
| Tình trạng hạn chế đặt online của tôi | A02 | Khách đang đăng nhập | — | — | FE hiển thị lý do và ngày hết hạn chế. Không bị hạn chế (chưa từng, đã hết hạn, đã gỡ) → restricted = false, restriction = null. Không chặn khi hồ sơ còn chờ quyết định liên kết (docs/adr/0055). |
| Tình trạng hạn chế đặt online của khách | A05, A06 | Nhân viên | — | — | Toàn chuỗi: hạn chế gắn với khách, nhân viên chi nhánh nào cũng xem được. Không bị hạn chế → restricted = false, restriction = null (docs/adr/0055). |
| Gỡ hạn chế sớm | A05, A06 | Lễ tân, BRANCH_MANAGER | — | — | Toàn chuỗi. Ghi audit BOOKING_RESTRICTION_LIFTED; không gửi thông báo cho khách. Thành công → restricted = false, restriction = null. Lần đánh giá vi phạm sau vẫn đếm từ starts_at của lần hạn chế vừa gỡ, nên vi phạm cũ không bị tính lại (docs/adr/0049, 0055). |

---

## C. Chi tiết endpoint

### Slots

- **`GET /branches/{branchId}/available-slots`** — Xem khung giờ trống. Query: `serviceId`, `date`. Response `200` mảng `Slot`. Lỗi: `400` `BR-LH-01` dịch vụ không đặt được tại chi nhánh (ngừng kinh doanh, nhóm Lưu trú, đang tắt) hoặc chi nhánh chưa nhận đặt lịch (lễ tân của chi nhánh DRAFT) · `401` · `403` Lễ tân xem chi nhánh khác · `404` Chi nhánh không tồn tại (khách: cả chi nhánh chưa hoạt động) hoặc dịch vụ không tồn tại.
### Appointments

- **`POST /appointments`** — Đặt lịch hẹn. Request `BookAppointmentRequest`. Response `201` `Appointment`. Lỗi: `400` `BR-LH-01` dịch vụ không đặt được / đang tắt tại chi nhánh, chi nhánh chưa ACTIVE · `BR-LH-02` khung không hợp lệ · `BR-LH-03` khung đã đủ quota · `BR-LH-04` ngoài khoảng 24 giờ – 30 ngày [CFG] (khách) hoặc khung đã bắt đầu (lễ tân) · `BR-LH-05` thú đã có 2 lịch BOOKED, trùng nhóm trong ngày hoặc trùng khung · `BR-LH-09` khách đang bị hạn chế đặt online (message kèm thời điểm hết hạn chế, giờ Việt Nam) · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403` Lễ tân đặt lịch cho chi nhánh khác · `404` Thú cưng (kể cả thú của khách khác), dịch vụ hoặc chi nhánh không tồn tại (khách: cả chi nhánh chưa hoạt động).
- **`GET /appointments`** — Danh sách lịch hẹn. Query: `branchId?`, `date?`, `from?`, `to?`, `status?`, `petId?`, `customerId?`, `checkInReady?`, `page?`, `size?`. Response `200` trang `Appointment`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò hoặc nhân viên lọc chi nhánh khác.
- **`GET /appointments/{appointmentId}`** — Chi tiết lịch hẹn. Response `200` `Appointment`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Nhân viên xem lịch của chi nhánh khác · `404` Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập.
- **`POST /appointments/{appointmentId}/reschedule`** — Đổi khung giờ. Request `RescheduleRequest`. Response `200` `Appointment`. Lỗi: `400` `BR-LH-06` hết lượt đổi (3 [CFG]), khách đổi khi còn < 12 giờ [CFG] hoặc lễ tân đổi khi đã tới giờ hẹn · `BR-LH-04`, `BR-LH-02`, `BR-LH-05`, `BR-LH-03` với khung mới; tham số / body sai kiểu (`MALFORMED_REQUEST`, `VALIDATION_FAILED`); BR-TK-17 · `401` · `403` Sai vai trò hoặc lễ tân đổi lịch của chi nhánh khác · `404` Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập · `409` Lịch không còn BOOKED (kiểm ngay sau khi khóa, trước mọi rule).
- **`POST /appointments/{appointmentId}/cancel`** — Hủy lịch hẹn. Request `CancelAppointmentRequest`. Response `200` `Appointment`. Lỗi: `400` `BR-LH-07` đã tới giờ hẹn; tham số / body sai kiểu (`MALFORMED_REQUEST`, `VALIDATION_FAILED`); BR-TK-17 · `401` · `403` Sai vai trò hoặc lễ tân hủy lịch của chi nhánh khác · `404` Lịch hẹn không tồn tại hoặc không phải của khách đang đăng nhập · `409` Lịch không còn BOOKED, kể cả CHECKED_IN (kiểm ngay sau khi khóa, trước mọi rule).
### Booking restrictions

- **`GET /me/booking-restriction`** — Tình trạng hạn chế đặt online của tôi. Response `200` `RestrictionStatus`. Lỗi: `400` BR-TK-17 · `401` · `403`.
- **`GET /customers/{customerId}/booking-restriction`** — Tình trạng hạn chế đặt online của khách. Response `200` `RestrictionStatus`. Lỗi: `400` Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · `404` Khách hàng không tồn tại.
- **`POST /customers/{customerId}/booking-restriction/lift`** — Gỡ hạn chế sớm. Request `LiftRestrictionRequest`. Response `200` `RestrictionStatus`. Lỗi: `400` Thiếu lý do / lý do quá 300 ký tự (`VALIDATION_FAILED`); body sai (`MALFORMED_REQUEST`); BR-TK-17 · `401` · `403` Sai vai trò · `404` Khách hàng không tồn tại · `409` Khách không đang bị hạn chế (chưa từng, đã hết hạn hoặc đã gỡ).

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

1. Khách đặt lịch cho thú mình là chủ hiện tại; xem / đổi / hủy lịch có `customerId` là hồ sơ của mình (chủ lúc đặt). Nhân viên chỉ thao tác lịch của chi nhánh mình.
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
