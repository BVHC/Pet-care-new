# Workforce API v1 (tầng 3) — Pet Care v16

> **Module:** NS · **Owner:** BE-1. Ca làm việc, phân ca, lịch làm việc của nhân viên, yêu cầu nghỉ / đổi ca và ghi nhận vắng đột xuất. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC34–UC38; 02 BR-NS-01…10; 03 #11 Yêu cầu nghỉ / đổi ca, #12 Phân ca; 04 §12.1; 05 §14.1 (shift_definitions, shift_assignments, shift_requests).
> **Contract máy đọc:** [`./openapi/workforce-v1.yaml`](./openapi/workforce-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Danh sách nhân viên của chi nhánh (UC34 'xem nhân viên chi nhánh') dùng `GET /staff` của identity (UC08), không lặp ở đây.
- Hủy phân ca, từ chối yêu cầu khi nhân viên bị vô hiệu hóa / điều chuyển (BR-NS-09; Yêu cầu#5, Phân ca#6) là hệ quả hệ thống, không có endpoint.
- Lịch làm việc không chặn đặt lịch, hàng đợi, thu ngân (02 §7 ghi chú đầu mục).

---

## A. Danh sách endpoint (14)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /shift-definitions` | Danh sách ca làm việc của chi nhánh | UC34 | BR-NS-01 |
| 2 | `POST /shift-definitions` | Tạo ca làm việc | UC34 | BR-NS-01, BR-NS-10 |
| 3 | `PATCH /shift-definitions/{shiftDefinitionId}` | Sửa / ngừng dùng ca làm việc | UC34 | BR-NS-01, BR-NS-10 |
| 4 | `GET /shift-assignments` | Lịch phân ca của chi nhánh | UC34 | BR-NS-03 |
| 5 | `POST /shift-assignments` | Phân ca cho nhân viên | UC34 | BR-NS-02, BR-NS-08, BR-NS-10 |
| 6 | `POST /shift-assignments/{shiftAssignmentId}/cancel` | Hủy phân ca | UC34 | BR-NS-02, BR-NS-08, BR-NS-10 |
| 7 | `POST /shift-assignments/{shiftAssignmentId}/absent` | Ghi nhận vắng đột xuất | UC38 | BR-NS-07, BR-NS-08, BR-NS-10 |
| 8 | `GET /me/shift-assignments` | Lịch làm việc của tôi | UC35 | BR-NS-03 |
| 9 | `POST /me/shift-requests` | Gửi yêu cầu nghỉ / đổi ca | UC36 | BR-NS-04, BR-NS-08 |
| 10 | `GET /me/shift-requests` | Yêu cầu nghỉ / đổi ca của tôi | UC36 | BR-NS-03 |
| 11 | `POST /me/shift-requests/{shiftRequestId}/withdraw` | Rút yêu cầu | UC36 | BR-NS-05 |
| 12 | `GET /shift-requests` | Yêu cầu nghỉ / đổi ca của chi nhánh | UC37 | BR-NS-06 |
| 13 | `POST /shift-requests/{shiftRequestId}/approve` | Duyệt yêu cầu | UC37 | BR-NS-06, BR-NS-08, BR-NS-10 |
| 14 | `POST /shift-requests/{shiftRequestId}/reject` | Từ chối yêu cầu | UC37 | BR-NS-06, BR-NS-08, BR-NS-10 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách ca làm việc của chi nhánh | A05 | BRANCH_MANAGER của chi nhánh | — | — | Không phân trang. `activeOnly` vắng = false (cả ca ngừng dùng). Sắp theo `startTime` tăng dần. |
| Tạo ca làm việc | A05 | BRANCH_MANAGER của chi nhánh | — | — | — |
| Sửa / ngừng dùng ca làm việc | A05 | BRANCH_MANAGER của chi nhánh | — | — | Chỉ các trường có trong body được đổi. |
| Lịch phân ca của chi nhánh | A05 | BRANCH_MANAGER của chi nhánh | — | — | Sắp `workDate`, `startTime` tăng dần, trùng thì `shiftAssignmentId` tăng dần. |
| Phân ca cho nhân viên | A05 | BRANCH_MANAGER của chi nhánh | — → SCHEDULED (Phân ca#1) | — | — |
| Hủy phân ca | A05 | BRANCH_MANAGER của chi nhánh | SCHEDULED → CANCELLED (Phân ca#2) | — | Yêu cầu `PENDING` của phân ca tự `REJECTED` (Yêu cầu#5). |
| Ghi nhận vắng đột xuất | A05 | BRANCH_MANAGER của chi nhánh | SCHEDULED → ABSENT (Phân ca#4) | — | Yêu cầu `PENDING` của phân ca tự `REJECTED` (Yêu cầu#5). |
| Lịch làm việc của tôi | A06–A08 | Nhân viên A06–A08, chỉ dữ liệu của mình | — | — | Không phân trang (tối đa 62 ngày). Sắp `workDate`, `startTime` tăng dần. Gồm cả phân ca đã nghỉ / vắng / hủy để hiển thị lịch sử. |
| Gửi yêu cầu nghỉ / đổi ca | A06–A08 | Nhân viên A06–A08, chỉ dữ liệu của mình | — → PENDING (Yêu cầu#1) | — | — |
| Yêu cầu nghỉ / đổi ca của tôi | A06–A08 | Nhân viên A06–A08, chỉ dữ liệu của mình | — | — | Mới nhất trước (`createdAt` giảm dần, trùng thì `shiftRequestId` giảm dần). |
| Rút yêu cầu | A06–A08 | Người gửi | PENDING → WITHDRAWN (Yêu cầu#2) | — | — |
| Yêu cầu nghỉ / đổi ca của chi nhánh | A05 | BRANCH_MANAGER của chi nhánh | — | — | `status` vắng = `PENDING`. Cũ nhất trước (`createdAt` tăng dần, trùng thì `shiftRequestId` tăng dần) để xử lý theo thứ tự gửi. |
| Duyệt yêu cầu | A05 | BRANCH_MANAGER của chi nhánh | PENDING → APPROVED (Yêu cầu#3); Phân ca#3 hoặc #5 | — | — |
| Từ chối yêu cầu | A05 | BRANCH_MANAGER của chi nhánh | PENDING → REJECTED (Yêu cầu#4) | — | — |

---

## C. Chi tiết endpoint

### Shift definitions

- **`GET /shift-definitions`** — Danh sách ca làm việc của chi nhánh. Query: `activeOnly?`. Response `200` mảng `ShiftDefinition`. Lỗi: `400` · `401` · `403`.
- **`POST /shift-definitions`** — Tạo ca làm việc. Request `CreateShiftDefinitionRequest`. Response `201` `ShiftDefinition`. Lỗi: `400` `BR-NS-01` tên trùng trong chi nhánh · giờ bắt đầu không nhỏ hơn giờ kết thúc; `VALIDATION_FAILED` sai kiểu / quá dài · `401` · `403`.
- **`PATCH /shift-definitions/{shiftDefinitionId}`** — Sửa / ngừng dùng ca làm việc. Request `UpdateShiftDefinitionRequest`. Response `200` `ShiftDefinition`. Lỗi: `400` `BR-NS-01` tên trùng · sửa giờ khi ca đã có phân ca · ngừng dùng khi ca còn phân ca `SCHEDULED` từ hôm nay · `401` · `403` · `404`.
### Shift assignments

- **`GET /shift-assignments`** — Lịch phân ca của chi nhánh. Query: `from`, `to`, `staffId?`, `status?`, `page?`, `size?`. Response `200` trang `ShiftAssignment`. Lỗi: `400` `from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày (`BR-NS-03`) · `401` · `403`.
- **`POST /shift-assignments`** — Phân ca cho nhân viên. Request `CreateShiftAssignmentRequest`. Response `201` `ShiftAssignment`. Lỗi: `400` `BR-NS-02` ngày trong quá khứ · ca ngừng dùng · nhân viên không phải A06–A08 `ACTIVE` của chi nhánh · trùng hoặc chồng giờ phân ca khác · `401` · `403` · `404` Không tìm thấy nhân viên hoặc ca trong chi nhánh.
- **`POST /shift-assignments/{shiftAssignmentId}/cancel`** — Hủy phân ca. Request `ReasonRequest`. Response `200` `ShiftAssignment`. Lỗi: `400` `BR-NS-02` ca đã bắt đầu (dùng ghi vắng đột xuất); `VALIDATION_FAILED` thiếu lý do · `401` · `403` · `404` · `409` Phân ca không còn `SCHEDULED`.
- **`POST /shift-assignments/{shiftAssignmentId}/absent`** — Ghi nhận vắng đột xuất. Request `ReasonRequest`. Response `200` `ShiftAssignment`. Lỗi: `400` `BR-NS-07` ca của ngày tương lai · quá 7 ngày **[CFG]**; `VALIDATION_FAILED` thiếu lý do · `401` · `403` · `404` · `409` Phân ca không còn `SCHEDULED`.
- **`GET /me/shift-assignments`** — Lịch làm việc của tôi. Query: `from`, `to`. Response `200` mảng `ShiftAssignment`. Lỗi: `400` `from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày (`BR-NS-03`) · `401` · `403`.
### Shift requests

- **`POST /me/shift-requests`** — Gửi yêu cầu nghỉ / đổi ca. Request `CreateShiftRequestRequest`. Response `201` `ShiftRequest`. Lỗi: `400` `BR-NS-04` phân ca không `SCHEDULED` · còn dưới 24 giờ **[CFG]** · đã có yêu cầu chờ · `SWAP` thiếu / thừa ca đích, ca đích khác ngày hoặc chồng giờ · `401` · `403` · `404` Không tìm thấy phân ca của mình.
- **`GET /me/shift-requests`** — Yêu cầu nghỉ / đổi ca của tôi. Query: `status?`, `page?`, `size?`. Response `200` trang `ShiftRequest`. Lỗi: `400` · `401` · `403`.
- **`POST /me/shift-requests/{shiftRequestId}/withdraw`** — Rút yêu cầu. Response `200` `ShiftRequest`. Lỗi: `400` · `401` · `403` · `404` · `409` Yêu cầu không còn `PENDING`.
- **`GET /shift-requests`** — Yêu cầu nghỉ / đổi ca của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `ShiftRequest`. Lỗi: `400` · `401` · `403`.
- **`POST /shift-requests/{shiftRequestId}/approve`** — Duyệt yêu cầu. Response `200` `ShiftRequest`. Lỗi: `400` `BR-NS-06` phân ca đã bắt đầu · `SWAP`: ca đích ngừng dùng hoặc chồng giờ · `401` · `403` · `404` · `409` Yêu cầu không còn `PENDING`.
- **`POST /shift-requests/{shiftRequestId}/reject`** — Từ chối yêu cầu. Request `RejectShiftRequestRequest`. Response `200` `ShiftRequest`. Lỗi: `400` `VALIDATION_FAILED` thiếu lý do từ chối (`BR-NS-06`) · `401` · `403` · `404` · `409` Yêu cầu không còn `PENDING`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ShiftDefinition`** — {`shiftDefinitionId`: int64, `branchId`: int64, `name`: string, `startTime`: string, `endTime`: string, `active`: boolean}
- **`CreateShiftDefinitionRequest`** — {`name`: string, `startTime`: string, `endTime`: string}
- **`UpdateShiftDefinitionRequest`** — {`name?`: string, `startTime?`: string, `endTime?`: string, `active?`: boolean}
- **`ShiftAssignmentStatus`** — enum: `SCHEDULED`, `ON_LEAVE`, `ABSENT`, `CANCELLED`. 03 #12
- **`ShiftAssignment`** — {`shiftAssignmentId`: int64, `branchId`: int64, `staffId`: int64, `staffName`: string, `shiftDefinitionId`: int64, `shiftName`: string, `startTime`: string, `endTime`: string, `workDate`: date, `status`: ShiftAssignmentStatus, `reason?`: string, `pendingRequestId?`: int64}
- **`CreateShiftAssignmentRequest`** — {`staffId`: int64, `shiftDefinitionId`: int64, `workDate`: date}
- **`ReasonRequest`** — {`reason`: string}
- **`ShiftRequestType`** — enum: `LEAVE`, `SWAP`.
- **`ShiftRequestStatus`** — enum: `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`. 03 #11
- **`ShiftRequest`** — {`shiftRequestId`: int64, `shiftAssignmentId`: int64, `requesterId`: int64, `requesterName`: string, `workDate`: date, `currentShiftName`: string, `type`: ShiftRequestType, `targetShiftDefinitionId?`: int64, `targetShiftName?`: string, `reason`: string, `status`: ShiftRequestStatus, `decidedBy?`: int64, `decisionNote?`: string, `closedAt?`: date-time, `createdAt`: date-time}
- **`CreateShiftRequestRequest`** — {`shiftAssignmentId`: int64, `type`: ShiftRequestType, `targetShiftDefinitionId?`: int64, `reason`: string}
- **`RejectShiftRequestRequest`** — {`decisionNote`: string}

---

## D. Bảo mật & độ tin cậy

1. Ca, phân ca, duyệt yêu cầu, vắng đột xuất: chỉ BRANCH_MANAGER của chính chi nhánh (BR-NS-01, 02, 06, 07). SUPER_MANAGER không có chức năng nhân sự.
2. Nhân viên A06–A08 chỉ xem lịch và yêu cầu của chính mình, chỉ gửi / rút yêu cầu cho phân ca của mình (BR-NS-03, 04, 05). Phân ca người khác trả 404, không lộ sự tồn tại.
3. Thao tác của BRANCH_MANAGER ghi audit (BR-NS-10). Chồng giờ phân ca kiểm dưới khóa dòng tài khoản nhân viên để hai thao tác song song không cùng lọt (05 §13 mục 24).
4. Thông báo ghi `notification_outbox` trong cùng transaction nghiệp vụ (BR-NS-08).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Đổi ca (`SWAP`) là đổi sang một ca khác của chi nhánh trong cùng ngày, không phải đổi với đồng nghiệp | UC36 không nói rõ; phương án ít phát minh nhất vì không cần xác nhận hai chiều |
| A2 | Lịch làm việc chỉ để xem, không chặn hay đổi hành vi của hàng đợi / đặt lịch / thu ngân | 02 §7 ghi chú: mệnh đề về ca làm trong QT đã gỡ ở v10; BR-TN-05 chỉ xét nhân viên ACTIVE |
| A3 | Không có định biên ca (số người tối thiểu) nên duyệt nghỉ không kiểm số người còn lại | UC37 chỉ nói duyệt / từ chối; YAGNI |
| A4 | Gửi yêu cầu trước giờ bắt đầu ca ít nhất 24 giờ và ghi vắng đột xuất trong 7 ngày là [CFG] mới (`workforce.request_lead_hours`, `workforce.absence_report_max_days`) | Cần ConfigKey mới ở T55 (06 §1, BR-QT-13) |
| A5 | Hủy phân ca chỉ khi ca chưa bắt đầu; ca đã bắt đầu thì chỉ có ghi vắng đột xuất | Tách rõ hai thao tác của BRANCH_MANAGER (UC34 và UC38) |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Khi nhân viên bị vô hiệu hóa / điều chuyển, identity phát sự kiện nào cho workforce? Đề xuất `StaffLeftBranchEvent(staffId, branchId)` ở `identity.api` (06 §3). | TBD (BE-1) | BR-NS-09, BR-QT-06, BR-QT-09 |
| Q2 | Chặn chồng giờ ở DB bằng cột sao chép + `EXCLUDE USING gist` hay chỉ kiểm trong service? | TBD (BE-1) | 05 §13 mục 24 |
| Q3 | BRANCH_MANAGER có được tự phân ca / xin nghỉ không (UC35–36 chỉ nêu A06–A08)? | TBD (PO) | 01 UC35, UC36 |

---

## F. OpenAPI 3.1

[`./openapi/workforce-v1.yaml`](./openapi/workforce-v1.yaml)
