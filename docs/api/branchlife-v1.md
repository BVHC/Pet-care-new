# Branch Lifecycle API v1 (tầng 3) — Pet Care v16

> **Module:** CN (tạm ngừng / đóng cửa, UC13) · **Owner:** BE-2. Xem trước tồn đọng, tạm ngừng, mở lại và đóng cửa chi nhánh. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC13; 08-tang3-be2-spec.md §F.1 (BR-CN-06…10; 03 #20 Chi nhánh mở rộng); 05 bổ sung cột suspended_at, expected_resume_on, closed_at… của branches.
> **Contract máy đọc:** [`./openapi/branchlife-v1.yaml`](./openapi/branchlife-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Kích hoạt chi nhánh `DRAFT → ACTIVE` ở branch-v1 (`POST /branches/{branchId}/activate`), không lặp ở đây.
- Hủy hàng loạt lịch hẹn và đặt chỗ chỉ phát `BranchClinicCancellationEvent` (06 §3); module appointment / boarding tự hủy và thông báo khách.
- Chuyển tồn kho đi dùng stockops-v1 (UC77) hoặc điều chỉnh giảm inventory-v1 (UC76), không có endpoint chuyển giao trong module này.

---

## A. Danh sách endpoint (4)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /branches/{branchId}/closure-impact` | Xem trước tồn đọng khi tạm ngừng / đóng cửa | UC13 | BR-CN-07, 08 |
| 2 | `POST /branches/{branchId}/suspend` | Tạm ngừng chi nhánh | UC13 | BR-CN-06…10 |
| 3 | `POST /branches/{branchId}/resume` | Mở lại chi nhánh đang tạm ngừng | UC13 | BR-CN-10, BR-QT-04 |
| 4 | `POST /branches/{branchId}/close` | Đóng cửa chi nhánh vĩnh viễn | UC13 | BR-CN-06…10 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem trước tồn đọng khi tạm ngừng / đóng cửa | A04 | Chỉ SUPER_MANAGER | — | — | Không ghi gì, giống `…/impact` của branch-v1 (BR-CN-07). |
| Tạm ngừng chi nhánh | A04 | Chỉ SUPER_MANAGER | ACTIVE → SUSPENDED (Chi nhánh mở rộng#1) | — | — |
| Mở lại chi nhánh đang tạm ngừng | A04 | Chỉ SUPER_MANAGER | SUSPENDED → ACTIVE (Chi nhánh mở rộng#2) | — | — |
| Đóng cửa chi nhánh vĩnh viễn | A04 | Chỉ SUPER_MANAGER | ACTIVE / SUSPENDED → CLOSED (Chi nhánh mở rộng#3, #4) | — | Không mở lại được. Dữ liệu lịch sử giữ nguyên. |

---

## C. Chi tiết endpoint

### Branch lifecycle

- **`GET /branches/{branchId}/closure-impact`** — Xem trước tồn đọng khi tạm ngừng / đóng cửa. Response `200` `ClosureImpact`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /branches/{branchId}/suspend`** — Tạm ngừng chi nhánh. Request `SuspendBranchRequest`. Response `200` `BranchLifecycle`. Lỗi: `400` `BR-CN-08` còn điều kiện chặn (message liệt kê) · `BR-CN-09` còn lịch `BOOKED` mà chưa chọn hủy hàng loạt · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái chi nhánh không cho phép (03 #20).
- **`POST /branches/{branchId}/resume`** — Mở lại chi nhánh đang tạm ngừng. Response `200` `BranchLifecycle`. Lỗi: `400` `BR-CN-01` chưa có giờ mở cửa hợp lệ · `BR-QT-04` chưa có BRANCH_MANAGER `ACTIVE` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái chi nhánh không cho phép (03 #20).
- **`POST /branches/{branchId}/close`** — Đóng cửa chi nhánh vĩnh viễn. Request `CloseBranchRequest`. Response `200` `BranchLifecycle`. Lỗi: `400` `BR-CN-08` còn điều kiện chặn (message liệt kê) · `BR-CN-09` còn lịch `BOOKED` mà chưa chọn hủy hàng loạt · còn tồn kho > 0 · còn nhân viên `ACTIVE` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái chi nhánh không cho phép (03 #20).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`BranchLifecycleStatus`** — enum: `DRAFT`, `ACTIVE`, `SUSPENDED`, `CLOSED`. 03 #2 và #20
- **`ClosureImpact`** — {`bookedAppointments`: int32, `activeBoardingStays`: int32, `openVisits`: int32, `openCashierShifts`: int32, `openOrders`: int32, `unfinishedOnlineOrders`: int32, `openCareTasks`: int32, `pendingRefunds`: int32, `inTransitTransfers`: int32, `openStockCounts`: int32, `stockItemCount`: int32, `stockTotalQuantity`: int32, `activeStaff`: int32, `suspendBlockers`: [string], `closeBlockers`: [string]}
- **`SuspendBranchRequest`** — {`reason`: string, `expectedResumeOn?`: date, `cancelBookings`: boolean, `careTaskTargetBranchId?`: int64}
- **`CloseBranchRequest`** — {`reason`: string, `cancelBookings`: boolean, `careTaskTargetBranchId?`: int64}
- **`BranchLifecycle`** — {`branchId`: int64, `status`: BranchLifecycleStatus, `suspendedAt?`: date-time, `expectedResumeOn?`: date, `closedAt?`: date-time, `reason?`: string}

---

## D. Bảo mật & độ tin cậy

1. Mọi endpoint chỉ SUPER_MANAGER (BR-CN-06); chi nhánh `DRAFT` không tạm ngừng / đóng.
2. Kiểm điều kiện chặn và thực hiện chạy trong cùng transaction, khóa chi nhánh trước khi đọc (ADR-0106; `BranchApi.lockForBooking`).
3. Mọi thao tác ghi audit kèm lý do (BR-QT-15).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Tạm ngừng chỉ chặn việc đang dở tại chỗ (Visit mở, thú trong chuồng, ca thu ngân mở); lịch đặt trước xử lý bằng lựa chọn hủy hàng loạt | BR-CN-08, BR-CN-09 |
| A2 | Đóng cửa phải dọn sạch tồn kho và nhân viên trước | Tránh dữ liệu mồ côi; BR-CN-08 |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Care Task `OPEN` khi đóng chi nhánh: chuyển sang chi nhánh khác hay luôn hủy | TBD (BE-1) | BR-CN-09 |
| Q2 | Tạm ngừng có cần ngày dự kiến mở lại bắt buộc không | TBD (PO) | BR-CN-10 |

---

## F. OpenAPI 3.1

[`./openapi/branchlife-v1.yaml`](./openapi/branchlife-v1.yaml)
