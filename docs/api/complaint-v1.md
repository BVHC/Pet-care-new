# Complaint API v1 (tầng 3) — Pet Care v16

> **Module:** DG (khiếu nại) · **Owner:** BE-1. Khách gửi và theo dõi khiếu nại (lễ tân tạo thay), quản lý chi nhánh và quản lý chuỗi xử lý. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Package thuộc BE-1 (đề xuất, Q1), không phải `content` của BE-2.
> **Nguồn chân lý:** 01 UC85, UC86, ST16, ST17; 09-tang3-be1-spec.md §D (BR-DG-05…09; 03 #26 Khiếu nại); 05 bổ sung complaints, complaint_messages.
> **Contract máy đọc:** [`./openapi/complaint-v1.yaml`](./openapi/complaint-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Feedback (UC83, UC84) ở content-v1, không có vòng đời xử lý; khiếu nại là chức năng khác (09 §D).
- ST16 (leo thang quá hạn) và ST17 (tự đóng) là tác vụ hệ thống, không có endpoint; chuyển #8, #9 của 03 #26 do hệ thống.
- Chưa có đính kèm ảnh (09 Q5).

---

## A. Danh sách endpoint (12)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /me/complaints` | Gửi khiếu nại | UC85 | BR-DG-05 |
| 2 | `GET /me/complaints` | Khiếu nại của tôi | UC85 | BR-DG-06 |
| 3 | `GET /me/complaints/{complaintId}` | Chi tiết khiếu nại của tôi | UC85 | BR-DG-06 |
| 4 | `POST /me/complaints/{complaintId}/messages` | Trao đổi thêm | UC85 | BR-DG-07 |
| 5 | `POST /me/complaints/{complaintId}/close` | Đóng hoặc rút khiếu nại | UC85 | BR-DG-07 |
| 6 | `POST /me/complaints/{complaintId}/reopen` | Mở lại khiếu nại | UC85 | BR-DG-07 |
| 7 | `POST /complaints` | Lễ tân tạo khiếu nại thay khách | UC85 | BR-DG-05, 06 |
| 8 | `GET /complaints` | Danh sách khiếu nại cần xử lý | UC86 | BR-DG-06, 08 |
| 9 | `GET /complaints/{complaintId}` | Chi tiết khiếu nại | UC86 | BR-DG-06 |
| 10 | `POST /complaints/{complaintId}/take` | Nhận xử lý khiếu nại | UC86 | BR-DG-07 |
| 11 | `POST /complaints/{complaintId}/messages` | Trả lời khách | UC86 | BR-DG-07 |
| 12 | `POST /complaints/{complaintId}/resolve` | Kết luận khiếu nại | UC86 | BR-DG-07 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Gửi khiếu nại | A02 | CUSTOMER, chỉ khiếu nại của mình | — → OPEN (Khiếu nại#1) | — | — |
| Khiếu nại của tôi | A02 | CUSTOMER, chỉ khiếu nại của mình | — | — | Mới nhất trước (`createdAt` giảm dần, trùng thì `complaintId` giảm dần). |
| Chi tiết khiếu nại của tôi | A02 | CUSTOMER, chỉ khiếu nại của mình | — | — | — |
| Trao đổi thêm | A02 | CUSTOMER, chỉ khiếu nại của mình | — | — | — |
| Đóng hoặc rút khiếu nại | A02 | CUSTOMER, chỉ khiếu nại của mình | RESOLVED → CLOSED (Khiếu nại#5); OPEN / IN_PROGRESS → CLOSED (Khiếu nại#4) | — | Đang xử lý thì là **rút** (`WITHDRAWN`, không mở lại); đã kết luận thì là đóng (`CUSTOMER`). |
| Mở lại khiếu nại | A02 | CUSTOMER, chỉ khiếu nại của mình | RESOLVED / CLOSED → OPEN (Khiếu nại#6, #7) | — | Thân là lý do mở lại, ghi thành tin nhắn. |
| Lễ tân tạo khiếu nại thay khách | A06 | Lễ tân, khách ở chi nhánh của mình | — → OPEN (Khiếu nại#1) | — | — |
| Danh sách khiếu nại cần xử lý | A04, A05, A06 | BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại; A06 chỉ cái mình tạo | — | — | `status` vắng = `OPEN` + `IN_PROGRESS`. Cũ nhất trước (`lastActivityAt` tăng dần). Tự lọc theo phạm vi người gọi. |
| Chi tiết khiếu nại | A04, A05, A06 | BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại; A06 chỉ cái mình tạo | — | — | — |
| Nhận xử lý khiếu nại | A04, A05 | BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại | OPEN → IN_PROGRESS (Khiếu nại#2) | — | — |
| Trả lời khách | A04, A05, A06 | BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại; A06 chỉ cái mình tạo | — | — | — |
| Kết luận khiếu nại | A04, A05 | BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại (người đang xử lý) | IN_PROGRESS → RESOLVED (Khiếu nại#3) | — | — |

---

## C. Chi tiết endpoint

### My complaints

- **`POST /me/complaints`** — Gửi khiếu nại. Request `CreateComplaintRequest`. Response `201` `Complaint`. Lỗi: `400` `BR-DG-05` nội dung < 10 / > 2 000 ký tự · thiếu chi nhánh · đối tượng liên quan không thuộc khách · quá 10 khiếu nại đang mở [CFG] · `401` · `403` · `404` Không tìm thấy chi nhánh / đối tượng liên quan.
- **`GET /me/complaints`** — Khiếu nại của tôi. Query: `status?`, `page?`, `size?`. Response `200` trang `ComplaintSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /me/complaints/{complaintId}`** — Chi tiết khiếu nại của tôi. Response `200` `Complaint`. Lỗi: `400` · `401` · `403` · `404` Không tìm thấy khiếu nại — cả khi thuộc người khác (ADR-0006).
- **`POST /me/complaints/{complaintId}/messages`** — Trao đổi thêm. Request `PostMessageRequest`. Response `201` `Complaint`. Lỗi: `400` · `401` · `403` · `404` · `409` Khiếu nại đã `CLOSED`.
- **`POST /me/complaints/{complaintId}/close`** — Đóng hoặc rút khiếu nại. Response `200` `Complaint`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái khiếu nại không cho phép (03 #26).
- **`POST /me/complaints/{complaintId}/reopen`** — Mở lại khiếu nại. Request `PostMessageRequest`. Response `200` `Complaint`. Lỗi: `400` `BR-DG-07` quá 7 ngày [CFG] · quá số lần mở lại tối đa [CFG] · khiếu nại đã rút · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái khiếu nại không cho phép (03 #26).
### Complaints

- **`POST /complaints`** — Lễ tân tạo khiếu nại thay khách. Request `CreateComplaintForCustomerRequest`. Response `201` `Complaint`. Lỗi: `400` `BR-DG-05` như tạo của khách · `401` · `403` · `404` Không tìm thấy khách / chi nhánh / đối tượng liên quan.
- **`GET /complaints`** — Danh sách khiếu nại cần xử lý. Query: `status?`, `escalated?`, `page?`, `size?`. Response `200` trang `ComplaintSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /complaints/{complaintId}`** — Chi tiết khiếu nại. Response `200` `Complaint`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /complaints/{complaintId}/take`** — Nhận xử lý khiếu nại. Response `200` `Complaint`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái khiếu nại không cho phép (03 #26).
- **`POST /complaints/{complaintId}/messages`** — Trả lời khách. Request `PostMessageRequest`. Response `201` `Complaint`. Lỗi: `400` · `401` · `403` · `404` · `409` Khiếu nại đã `CLOSED`.
- **`POST /complaints/{complaintId}/resolve`** — Kết luận khiếu nại. Request `ResolveComplaintRequest`. Response `200` `Complaint`. Lỗi: `400` `BR-DG-07` thiếu kết luận · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái khiếu nại không cho phép (03 #26).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ComplaintStatus`** — enum: `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`. 03 #26
- **`ComplaintTopic`** — enum: `SERVICE_QUALITY`, `GROOMING`, `BOARDING`, `PURCHASE`, `STAFF`, `OTHER`.
- **`ComplaintRelatedType`** — enum: `ORDER`, `ONLINE_ORDER`, `VISIT`, `BOARDING`.
- **`ComplaintClosedReason`** — enum: `CUSTOMER`, `WITHDRAWN`, `AUTO`.
- **`CreateComplaintRequest`** — {`topic`: ComplaintTopic, `branchId?`: int64, `relatedType?`: ComplaintRelatedType, `relatedId?`: int64, `content`: string}
- **`CreateComplaintForCustomerRequest`** — {`customerId`: int64, `topic`: ComplaintTopic, `branchId?`: int64, `relatedType?`: ComplaintRelatedType, `relatedId?`: int64, `content`: string}
- **`ComplaintSummary`** — {`complaintId`: int64, `code`: string, `topic`: ComplaintTopic, `status`: ComplaintStatus, `branchId?`: int64, `escalated`: boolean, `lastActivityAt`: date-time, `createdAt`: date-time}
- **`ComplaintMessageAuthorType`** — enum: `CUSTOMER`, `STAFF`, `SYSTEM`.
- **`ComplaintMessageKind`** — enum: `MESSAGE`, `STATUS`.
- **`ComplaintMessage`** — {`messageId`: int64, `authorType`: ComplaintMessageAuthorType, `authorName?`: string, `kind`: ComplaintMessageKind, `body`: string, `createdAt`: date-time}
- **`Complaint`** — {`complaintId`: int64, `code`: string, `customerId`: int64, `customerName`: string, `branchId?`: int64, `topic`: ComplaintTopic, `relatedType?`: ComplaintRelatedType, `relatedId?`: int64, `content`: string, `status`: ComplaintStatus, `createdByStaff`: boolean, `handlerName?`: string, `resolution?`: string, `resolvedAt?`: date-time, `closedAt?`: date-time, `closedReason?`: ComplaintClosedReason, `reopenCount`: int32, `escalatedAt?`: date-time, `lastActivityAt`: date-time, `createdAt`: date-time, `messages`: [ComplaintMessage]}
- **`PostMessageRequest`** — {`body`: string}
- **`ResolveComplaintRequest`** — {`resolution`: string}

---

## D. Bảo mật & độ tin cậy

1. Khách chỉ thấy và trao đổi khiếu nại của mình; của người khác trả 404 (ADR-0006).
2. BRANCH_MANAGER chỉ khiếu nại gắn chi nhánh mình; SUPER_MANAGER mọi khiếu nại, kể cả khiếu nại không gắn chi nhánh và đã leo thang; lễ tân chỉ khiếu nại do mình tạo thay khách (BR-DG-06, BR-QT-03).
3. Đối tượng liên quan (Order, đơn online, Visit, đặt chỗ) phải thuộc chính khách khiếu nại (BR-DG-05).
4. Mọi chuyển trạng thái ghi một dòng `STATUS` vào `complaint_messages` (vết xử lý); khóa khiếu nại trước khi đổi trạng thái (ADR-0106).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Khiếu nại bị rút (`WITHDRAWN`) không mở lại | BR-DG-07 |
| A2 | Leo thang chỉ là cờ, không đổi trạng thái, và chỉ một lần | BR-DG-08 |
| A3 | Lễ tân chỉ thấy khiếu nại do mình tạo thay khách | BR-DG-06; hạn chế dữ liệu nhạy |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Package `complaint` thuộc BE-1 (đề xuất) hay `content` của BE-2 | TBD (BE-1, BE-2) | 06 §2 vs time.md T70 |
| Q2 | SLA 24 giờ nhận, 72 giờ giải quyết có phù hợp không | TBD (PO) | BR-DG-08 |
| Q3 | Có cho khách đánh giá hài lòng khi đóng không | TBD (PO) | UC85 |

---

## F. OpenAPI 3.1

[`./openapi/complaint-v1.yaml`](./openapi/complaint-v1.yaml)
