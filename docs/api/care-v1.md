# Care & Notification API v1 — Pet Care v16

> **Module:** TB · **Owner:** BE-1. Danh sách việc gọi điện của lễ tân (Care Task), thông báo trong ứng dụng, cài đặt nhận thông báo.
> **Nguồn chân lý:** 01 UC87, UC88; 02 BR-TB-01…06, BR-LT-10; 03 #10 Care Task; 04 §11; 05 §11 (care_tasks, notifications, notification_outbox, accounts.notification_settings).
> **Contract máy đọc:** [`./openapi/care-v1.yaml`](./openapi/care-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- ST04 (nhắc tái chủng / tái khám), ST18 (sinh Care Task), ST19 (hủy Care Task khi thú mất), ST20 (gửi thông báo, thử lại) là tác vụ hệ thống; sinh / hủy Care Task tự động đi qua `CareTaskApi` (06-module-contracts).
- Không có endpoint tạo Care Task thủ công: Care Task#1 chỉ do ST18 kích hoạt.
- Mẫu thông báo do ADMIN sửa ở module identity (`/notification-templates`).

---

## A. Danh sách endpoint (10)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /care-tasks` | Danh sách việc gọi điện của chi nhánh | UC87 | BR-TB-05, BR-LT-10 |
| 2 | `GET /care-tasks/{careTaskId}` | Chi tiết Care Task | UC87 | BR-TB-05 |
| 3 | `POST /care-tasks/{careTaskId}/complete` | Ghi kết quả gọi điện | UC87 | BR-TB-05 |
| 4 | `POST /care-tasks/{careTaskId}/cancel` | Hủy Care Task | UC87 | BR-TB-05 |
| 5 | `GET /me/notifications` | Thông báo của tôi | UC88 | — |
| 6 | `GET /me/notifications/unread-count` | Số thông báo chưa đọc | UC88 | — |
| 7 | `POST /me/notifications/{notificationId}/read` | Đánh dấu đã đọc | UC88 | — |
| 8 | `POST /me/notifications/read-all` | Đánh dấu tất cả đã đọc | UC88 | — |
| 9 | `GET /me/notification-settings` | Cài đặt nhận thông báo | UC88 | BR-TB-02 |
| 10 | `PUT /me/notification-settings` | Sửa cài đặt nhận thông báo | UC88 | BR-TB-02 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách việc gọi điện của chi nhánh | A06 | Lễ tân chi nhánh được giao | — | — | Mặc định OPEN, sắp theo dueDate. |
| Chi tiết Care Task | A06 | Lễ tân chi nhánh được giao | — | — | — |
| Ghi kết quả gọi điện | A06 | Lễ tân chi nhánh được giao | OPEN → DONE (Care Task#2) | — | Không liên lạc được: không sinh task gọi lại. |
| Hủy Care Task | A06 | Lễ tân chi nhánh được giao | OPEN → CANCELLED (Care Task#3) | — | — |
| Thông báo của tôi | A02–A08 | Người đang đăng nhập | — | — | Mới nhất trước. |
| Số thông báo chưa đọc | A02–A08 | Người đang đăng nhập | — | — | A3. |
| Đánh dấu đã đọc | A02–A08 | Chủ thông báo | — | Có | — |
| Đánh dấu tất cả đã đọc | A02–A08 | Người đang đăng nhập | — | Có | — |
| Cài đặt nhận thông báo | A02 | Chỉ CUSTOMER | — | — | — |
| Sửa cài đặt nhận thông báo | A02 | Chỉ CUSTOMER | — | — | A1, A2; nội dung chờ Q1. |

---

## C. Chi tiết endpoint

### Care tasks

- **`GET /care-tasks`** — Danh sách việc gọi điện của chi nhánh. Query: `status?`, `type?`, `dueFrom?`, `dueTo?`, `page?`, `size?`. Response `200` trang `CareTask`. Lỗi: `400` · `401` · `403`.
- **`GET /care-tasks/{careTaskId}`** — Chi tiết Care Task. Response `200` `CareTask`. Lỗi: `401` · `403` · `404`.
- **`POST /care-tasks/{careTaskId}/complete`** — Ghi kết quả gọi điện. Request `CompleteCareTaskRequest`. Response `200` `CareTask`. Lỗi: `400` `BR-TB-05` chưa chọn kết quả / thiếu ghi chú · `401` · `403` · `404` · `409` Task không OPEN.
- **`POST /care-tasks/{careTaskId}/cancel`** — Hủy Care Task. Request `CancelCareTaskRequest`. Response `200` `CareTask`. Lỗi: `400` Thiếu lý do · `401` · `403` · `404` · `409` Task không OPEN.
### Notifications

- **`GET /me/notifications`** — Thông báo của tôi. Query: `unreadOnly?`, `page?`, `size?`. Response `200` trang `Notification`. Lỗi: `401`.
- **`GET /me/notifications/unread-count`** — Số thông báo chưa đọc. Response `200` `UnreadCount`. Lỗi: `401`.
- **`POST /me/notifications/{notificationId}/read`** — Đánh dấu đã đọc. Response `204` rỗng. Lỗi: `401` · `404`.
- **`POST /me/notifications/read-all`** — Đánh dấu tất cả đã đọc. Response `204` rỗng. Lỗi: `401`.
- **`GET /me/notification-settings`** — Cài đặt nhận thông báo. Response `200` `NotificationSettings`. Lỗi: `401` · `403`.
- **`PUT /me/notification-settings`** — Sửa cài đặt nhận thông báo. Request `NotificationSettings`. Response `200` `NotificationSettings`. Lỗi: `400` · `401` · `403`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`CareTaskType`** — enum: `VACCINE_DUE`, `VACCINE_OVERDUE`, `FOLLOW_UP_DUE`, `PICKUP_OVERDUE`. TÁI_CHỦNG, QUÁ_HẠN_TÁI_CHỦNG, TÁI_KHÁM, QUÁ_HẠN_ĐÓN (05 §0)
- **`CareTask`** — {`careTaskId`: int64, `type`: CareTaskType, `status`: OPEN|DONE|CANCELLED, `branchId`: int64, `customerId`: int64, `customerName`: string, `phoneToCall?`: string, `petId`: int64, `petName`: string, `vaccinationId?`: int64, `medicalRecordVisitId?`: int64, `boardingBookingId?`: int64, `dueDate`: date, `context`: string, `result?`: REACHED|UNREACHABLE, `note?`: string, `cancelReason?`: string, `handledBy?`: int64, `handledAt?`: date-time, `createdAt`: date-time}
- **`CompleteCareTaskRequest`** — {`result`: REACHED|UNREACHABLE, `note`: string}
- **`CancelCareTaskRequest`** — {`reason`: string}
- **`Notification`** — {`notificationId`: int64, `type`: string, `title`: string, `body`: string, `linkUrl?`: string, `readAt?`: date-time, `createdAt`: date-time}
- **`UnreadCount`** — {`count`: int32}
- **`ChannelToggle`** — {`email`: boolean, `inApp`: boolean}
- **`NotificationSettings`** — {`appointmentReminder`: ChannelToggle, `vaccineReminder`: ChannelToggle, `followUpReminder`: ChannelToggle} — Đề xuất, xem A1 / Q1

---

## D. Bảo mật & độ tin cậy

1. Care Task chỉ lễ tân của chi nhánh được giao xem và thực hiện (BR-TB-05); vai trò khác không thấy chức năng.
2. Mỗi người chỉ đọc / đánh dấu đã đọc thông báo của chính mình. Cài đặt nhận thông báo chỉ dành cho CUSTOMER (UC88).
3. SĐT cần gọi: Care Task PICKUP_OVERDUE dùng SĐT khẩn ghi lúc nhận thú, loại khác dùng SĐT hồ sơ khách (BR-TB-05).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Cài đặt nhận thông báo là bật / tắt từng loại nhắc (lịch hẹn, tái chủng, tái khám) theo từng kênh; email bảo mật (OTP, mật khẩu, đổi email) luôn gửi | 05 `accounts.notification_settings` JSONB [ERD], docs không quy định nội dung |
| A2 | Tắt nhắc qua cài đặt không làm sinh Care Task thay thế | BR-TB-02 chỉ sinh Care Task cho hồ sơ không có email |
| A3 | Có endpoint đếm thông báo chưa đọc cho huy hiệu trên giao diện | Nhu cầu hiển thị; chỉ đọc, không đổi nghiệp vụ |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Cài đặt nhận thông báo (UC88) gồm những gì? Contract đang đề xuất bật / tắt 3 loại nhắc theo 2 kênh (A1). | TBD (PO) | 05 `notification_settings` [ERD] |

---

## F. OpenAPI 3.1

[`./openapi/care-v1.yaml`](./openapi/care-v1.yaml)
