# Home Visit API v1 (tầng 3) — Pet Care v16

> **Module:** LH, KB (khám tại nhà) · **Owner:** BE-1. Đặt lịch khám tại nhà (khách hoặc lễ tân đặt hộ) và bác sĩ thực hiện lịch khám tại nhà. Lịch khám tại nhà là một lịch hẹn nhóm Khám có cờ tại nhà. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC43, UC51; 09-tang3-be1-spec.md §A (BR-LH-13, 14, BR-KB-07, BR-BH-16; 03 #22 Lịch khám tại nhà); 05 bổ sung appointments.is_home_visit…, visits.is_home_visit, order_lines HOME_FEE.
> **Contract máy đọc:** [`./openapi/homevisit-v1.yaml`](./openapi/homevisit-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Hủy và đổi giờ dùng lại `POST /appointments/{appointmentId}/cancel` và `…/reschedule` của appointment-v1 (BR-LH-14); khung giờ trống dùng `GET /branches/{branchId}/available-slots`.
- Phí khám tại nhà xem ở `GET /branches/{branchId}/home-visit-fee` (fees-v1, mở thêm cho khách A02).
- Hoàn tất khám tại nhà, bệnh án, kê đơn, tiêm đi theo visit-v1 (UC48, UC49) trên Visit do `start` tạo ra.
- ST05 (NO_SHOW tự động) bỏ qua lịch tại nhà (BR-LH-14).

---

## A. Danh sách endpoint (6)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /home-visit-appointments` | Đặt lịch khám tại nhà | UC43 | BR-LH-13, BR-SP-09, 11 |
| 2 | `GET /me/home-visit-appointments` | Lịch khám tại nhà của tôi | UC43 | BR-LH-13 |
| 3 | `GET /home-visit-appointments` | Lịch khám tại nhà của chi nhánh | UC51 | BR-KB-07 |
| 4 | `GET /home-visit-appointments/{appointmentId}` | Chi tiết lịch khám tại nhà | UC51 | BR-KB-07 |
| 5 | `POST /home-visit-appointments/{appointmentId}/start` | Bắt đầu khám tại nhà | UC51 | BR-KB-07, BR-BH-01, 16 |
| 6 | `POST /home-visit-appointments/{appointmentId}/mark-unreachable` | Ghi nhận không gặp được khách | UC51 | BR-LH-14, BR-LH-09 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Đặt lịch khám tại nhà | A02, A06 | CUSTOMER cho thú của mình; A06 đặt hộ ở chi nhánh của mình | — → BOOKED (Lịch khám tại nhà#1) | — | Chốt `homeFee` và địa chỉ lúc đặt. Dùng chung khóa quota với appointment-v1 (ADR-0045). |
| Lịch khám tại nhà của tôi | A02 | CUSTOMER, chỉ thú của mình | — | — | Mới nhất trước (`slotDate` giảm dần, trùng thì `appointmentId` giảm dần). |
| Lịch khám tại nhà của chi nhánh | A06, A07 | A06, A07 của chi nhánh | — | — | Tự lấy chi nhánh của người gọi. Sắp `slotStart` tăng dần. |
| Chi tiết lịch khám tại nhà | A02, A06, A07 | Khách chủ lịch; A06, A07 của chi nhánh | — | — | — |
| Bắt đầu khám tại nhà | A07 | VET của chi nhánh | BOOKED → CHECKED_IN (Lịch khám tại nhà#2); Visit#1 biến thể | — | Tạo Visit `IN_PROGRESS` gán VET gọi, mở Order `VISIT` với dòng khám và dòng phí khám tại nhà (snapshot). |
| Ghi nhận không gặp được khách | A07 | VET của chi nhánh | BOOKED → NO_SHOW (Lịch khám tại nhà#3) | — | Tính vào hạn chế đặt online của khách (BR-LH-09); thông báo khách. |

---

## C. Chi tiết endpoint

### Home visit booking

- **`POST /home-visit-appointments`** — Đặt lịch khám tại nhà. Request `CreateHomeVisitAppointmentRequest`. Response `201` `HomeVisitAppointment`. Lỗi: `400` `BR-LH-13` chi nhánh chưa bật khám tại nhà · dịch vụ không phải `EXAM` / không bật · địa chỉ không phải của khách · thú đã mất; `BR-LH-04` / `05` / `09` như lịch thường · `401` · `403` · `404` Không tìm thấy thú / chi nhánh / dịch vụ / địa chỉ · `409` `CONCURRENCY_CONFLICT` khung vừa hết quota (BR-LH-03).
- **`GET /me/home-visit-appointments`** — Lịch khám tại nhà của tôi. Query: `status?`, `page?`, `size?`. Response `200` trang `HomeVisitAppointmentSummary`. Lỗi: `400` · `401` · `403`.
### Home visit

- **`GET /home-visit-appointments`** — Lịch khám tại nhà của chi nhánh. Query: `date?`, `status?`, `page?`, `size?`. Response `200` trang `HomeVisitAppointmentSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /home-visit-appointments/{appointmentId}`** — Chi tiết lịch khám tại nhà. Response `200` `HomeVisitAppointment`. Lỗi: `400` · `401` · `403` · `404` Không tìm thấy lịch tại nhà — cả khi lịch thuộc người khác (ADR-0006).
- **`POST /home-visit-appointments/{appointmentId}/start`** — Bắt đầu khám tại nhà. Response `200` `HomeVisitStarted`. Lỗi: `400` `BR-KB-07` ngoài ngày hẹn · không phải lịch tại nhà · thú đã mất · `BR-TN-01` thú đã có Visit mở · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái lịch không cho phép (03 #22).
- **`POST /home-visit-appointments/{appointmentId}/mark-unreachable`** — Ghi nhận không gặp được khách. Request `MarkUnreachableRequest`. Response `200` `HomeVisitAppointment`. Lỗi: `400` `BR-LH-14` ngoài ngày hẹn · thiếu ghi chú · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái lịch không cho phép (03 #22).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`HomeVisitStatus`** — enum: `BOOKED`, `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW`. 03 #3 và #22
- **`CreateHomeVisitAppointmentRequest`** — {`petId`: int64, `serviceId`: int64, `branchId`: int64, `slotDate`: date, `slotStart`: string, `addressId?`: int64, `address?`: string, `contactPhone`: string, `note?`: string, `customerId?`: int64}
- **`HomeVisitAppointment`** — {`appointmentId`: int64, `code`: string, `petId`: int64, `petName`: string, `customerId`: int64, `serviceId`: int64, `serviceName`: string, `branchId`: int64, `slotDate`: date, `slotStart`: string, `status`: HomeVisitStatus, `homeAddress`: string, `homeContactPhone`: string, `homeFee`: int64, `note?`: string, `visitId?`: int64, `createdAt`: date-time}
- **`HomeVisitAppointmentSummary`** — {`appointmentId`: int64, `code`: string, `petName`: string, `serviceName`: string, `slotDate`: date, `slotStart`: string, `status`: HomeVisitStatus, `homeAddress`: string, `homeFee`: int64}
- **`HomeVisitStarted`** — {`appointmentId`: int64, `visitId`: int64, `orderId`: int64}
- **`MarkUnreachableRequest`** — {`note`: string}

---

## D. Bảo mật & độ tin cậy

1. Khách (A02) chỉ đặt cho thú của mình và dùng địa chỉ trong sổ địa chỉ của chính mình (BR-TK-18); lịch của người khác trả 404 (ADR-0006).
2. Lễ tân (A06) đặt hộ khách ở chi nhánh của mình; VET (A07) chỉ thấy và bắt đầu lịch tại nhà của chi nhánh mình (BR-QT-03).
3. Chỉ chi nhánh đã bật khám tại nhà (BR-SP-09, fees-v1) nhận đặt lịch; phí và địa chỉ chốt lúc đặt (BR-SP-11).
4. Bắt đầu khám: khóa lịch hẹn rồi tạo Visit và Order trong một transaction (BR-KB-07; `PetQueryApi.findPetForShare`, ADR-0041).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Lịch khám tại nhà tính vào quota nhóm Khám / Tiêm của chi nhánh | Tái dùng BR-LH-03; không thêm quota mới |
| A2 | Không giới hạn vùng phục vụ; nhân viên xác nhận qua điện thoại | UC43 không nêu vùng |
| A3 | ST05 bỏ qua lịch tại nhà; bác sĩ ghi 'không gặp được' → NO_SHOW | Bác sĩ có thể đến muộn |
| A4 | Thu tiền khám tại nhà tại chi nhánh qua lễ tân | BR-BH-16 |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Giới hạn bán kính phục vụ hoặc phí theo khoảng cách | TBD (PO) | fees-v1 Q1 |
| Q2 | Có thu tiền tại nhà (bác sĩ nhận) không | TBD (PO) | BR-BH-16 |
| Q3 | Có cho phân công trước một bác sĩ cụ thể cho lịch tại nhà không (hiện bác sĩ tự bắt đầu) | TBD (BE-1) | BR-KB-07 |

---

## F. OpenAPI 3.1

[`./openapi/homevisit-v1.yaml`](./openapi/homevisit-v1.yaml)
