# Notification API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Notification Management (Module 23): đọc/đánh dấu/xóa hiển thị
> của user. Mọi `Send*` đều là effects hệ thống theo domain events (không endpoint
> gửi tay — đúng bản chất dispatcher).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#23`), `docs/02-business-rules.md`
> (RULE-23-01→06), `docs/04-glossary.md` (`23`), `docs/05-domain-model.md` (`4.23`),
> `docs/06-erd.md` (`notification_tasks`/`notification_delivery_logs`).
> **Contract máy đọc:** [`./openapi/notification-v1.yaml`](./openapi/notification-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Không có endpoint gửi tay: mọi notification sinh từ domain events hợp lệ
  (RULE-23-02 CONFIRMED) — dispatcher không nhận lệnh compose từ API.
- `RetryNotification` (backoff ×3 → FAILED) là job hệ thống — không endpoint.
- Opt-in/out marketing đọc từ consent M22 (`MARKETING`); tin giao dịch/cấp cứu bắt
  buộc bất chấp opt-out (RULE-23-05 CONFIRMED) — không endpoint consent ở đây.
- Kênh ERD (`IN_APP`/`SMS`/`EMAIL`/`PUSH`) thiếu `WEBHOOK` (RULE-23-01 có) và Zalo
  chỉ xuất hiện ở delivery logs → TBD Q6, v1 giữ 4 kênh ERD.
- `is_read` không có cột ERD (ViewNotification có "đánh dấu đã đọc") → read-state
  TBD Q7; PROPOSED đọc từ log mở rộng, không phát minh cột ở contract này.

---

## A. Confirmed Notification API (3 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /notifications/me` | `ViewNotification` — `01#23`, RULE-23-04 |
| 2 | `POST /notifications/{id}/read` | `ViewNotification` (đánh dấu đã đọc) — `01#23`, RULE-23-04 |
| 3 | `DELETE /notifications/{id}` | `ViewNotification` (xóa hiển thị của mình) — `01#23`, RULE-23-04 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ViewNotification | Customer / Staff | Của mình | RULE-23-04 (list của mình) | GET | `/notifications/me` | Bearer | Self | none | Idempotent | Query PROPOSED: `unreadOnly?`, `page`, `pageSize`; sắp mới nhất trước (A1) |
| (mark read) | Customer / Staff | Của mình | RULE-23-04 | POST | `…/{id}/read` | Bearer | Self | (read-state TBD Q7) | Idempotent | — |
| (dismiss) | Customer / Staff | Của mình | RULE-23-04 (xóa hiển thị) | DELETE | `…/{id}` | Bearer | Self | (ẩn với mình — A2, không xóa task gốc) | Idempotent | Xóa hiển thị ≠ xóa task hệ thống |

**ASSUMPTIONS dùng chung:** A1 sort `scheduled_at` desc (docs không nêu thứ tự —
hiển nhiên cho inbox) · A2 DELETE chỉ ẩn với user, task/logs hệ thống giữ nguyên
(RULE-23-04 "xóa hiển thị trên ứng dụng của mình").

---

## C. Detailed endpoint contract

### C1. Inbox (proposed)

- **`GET /notifications/me`** — Query PROPOSED: `unreadOnly?`, `page`, `pageSize`.
  Response `200 {items: [{notificationId, channel, eventType, title?, content,
  status, scheduledAt, read? (TBD Q7)}], page, pageSize, total, unreadCount?}`.
  `content` đã lọc nhạy cảm theo RULE-23-06 (server-side, không trả OTP/mật khẩu/
  CVV/bệnh án thô — mừng là không endpoint nào sinh content tay).
- **`POST …/{id}/read`** — Của mình. Response `200 {notificationId, read: true}`.
- **`DELETE …/{id}`** — Của mình. Ẩn hiển thị (A2). Response `204` (không body —
  theo convention method DELETE chuẩn; các file khác chưa dùng 204 — note V4:
  dùng 204 đúng semantics HTTP, envelope lỗi vẫn chuẩn khi lỗi).
- **Status:** `200`/`204` · `401` · `403` (không phải của mình) · `404`.

---

## D. Security & reliability (dispatcher design notes — effects, không endpoint)

1. Mọi send gắn `recipient + channel` xác định (RULE-23-01) — event nào thiếu người
   nhận/kênh thì không gửi (fail-closed), không đoán.
2. Retry backoff ×3 → FAILED (RULE-23-03 CONFIRMED) — job hệ thống.
3. Nội dung cấm thô nhạy cảm (RULE-23-06); link dữ liệu có hạn + token an toàn.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `unreadCount` có trả trong list không (A: có)? | TBD (BE/FE) | docs không nêu |
| Q4 | Xóa hiển thị có khôi phục được không? | TBD (PO) | RULE-23-04 |
| Q5 | Template quản trị (ai sửa title/content mẫu)? | TBD (PO) | docs không có op quản trị mẫu |
| Q6 | Kênh `WEBHOOK`/Zalo ténum chính thức? | TBD (PO) | RULE-23-01 vs ERD |
| Q7 | `read` lưu ở đâu (cột/log mới)? | TBD (BE) | ERD thiếu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/notification-v1.yaml`](./openapi/notification-v1.yaml).
