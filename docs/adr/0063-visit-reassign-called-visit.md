# ADR-0063: Gán lại lượt đã gọi (`POST /api/visits/{visitId}/reassign`, Visit#4) và chi tiết lượt cho SUPER_MANAGER

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-21
- **Liên quan:**
  - BR-TN-05, 08, 09 (02 L145, L148–149), BR-QT-04, 11, 12, 15 (L47, L54–55, L58), BR-BH-02 (L190), BR-TK-17.
  - 01 UC45 (L115); 03 §4 Visit#4 (L105), Tài khoản#7, #8 (L50–51); erd §6 `visits.needs_reassign`, `visit_assignments`.
  - visit-v1 #4 (actor A04–A08), #9 (lỗi / ghi chú viết lại), `ReassignRequest` (mô tả trường), A14, A15 mới, §E Q2 đã chốt; `docs/api/00-method.md` L60–70.
  - ADR-0024 (`active`, `findActiveStaffIds`), 0031 Q4 + mục 9 (đổi chức vụ khi còn việc dở; khóa `accounts FOR SHARE`), 0035 (khóa tài khoản, thông báo SUPER_MANAGER khi chi nhánh không còn quản lý hoạt động), 0044 (`VisitOrderApi.transferLineOwnership`), 0055 (lý do bắt buộc, `strip` trong compact constructor), 0058 (thứ tự khóa, tên command `reassignCalledVisit`), 0060 (`VisitResponder`, `requireRole`), 0061 (`requireAssignable`), 0062 (listener cần gán lại, D042).
  - Nợ: không trả, không mở; **D042** sửa mục *Hệ quả*.
- **Triển khai (T26 phần 5):**
  - visit: `service/VisitReassignService` (mới), `service/VisitAuditActions` (mới), `dto/ReassignRequest` (mới), `entity/Visit.reassignTo`, `service/VisitDispatchService.isAssignable` (tách từ `requireAssignable`, hành vi không đổi), `service/VisitQueueService.DETAIL_ROLES`, `controller/VisitController` (+1 handler, `DETAIL_ROLES`, `REASSIGN_ROLES`).
  - identity, sales: không sửa; gọi `StaffDirectoryApi.findStaffForShare`, `findActiveStaffIds`, `VisitOrderApi.transferLineOwnership` có sẵn.
  - Hợp đồng: `docs/api/generator/m_visit.py`.
  - Test:
    - Unit: `VisitReassignServiceTest` (28), `ReassignRequestValidationTest` (8), `VisitTest` (+1), `VisitQueueServiceTest` (+1, sửa ca SUPER_MANAGER), `VisitControllerSecurityTest` (sửa map).
    - IT: `VisitReassignIT` (13: đường đúng, D1–D4, BR-TN-05, ma trận body, vai trò / chéo chi nhánh, rollback G5, race G1–G4), `VisitQueueIT.queueRolesFollowTheContract` (#4 thêm SUPER_MANAGER), `OpenApiContractParityIT` (103 → 104).
  - Không migration, không event mới, không job, không thông báo.

## Bối cảnh

Phần 4 đánh dấu `needs_reassign` cho lượt `IN_PROGRESS` của người bị khóa (ADR-0062), nhưng chưa ai chuyển được lượt đó cho người khác. Các điểm docs để ngỏ:

- **"Không còn `ACTIVE`" (BR-TN-08, 03 #4):** rule giải thích là "bị khóa theo BR-QT-11". Nhưng ADR-0031 Q4 cho đổi chức vụ khi còn lượt: VET đổi thành CARETAKER vẫn `ACTIVE`, không gọi / hoàn tất được lượt Khám (BR-TN-05). Nếu đọc đúng chữ, lượt đó kẹt vĩnh viễn. Vô hiệu hóa và điều chuyển đã bị BR-QT-08 chặn khi còn lượt dở.
- **Ai được làm:** BRANCH_MANAGER của chi nhánh, hoặc SUPER_MANAGER "nếu chi nhánh không còn BRANCH_MANAGER `ACTIVE`". Đây là điều kiện động, không phải role tĩnh.
- **visit-v1 Q2:** SUPER_MANAGER được gán lại, nhưng #2 / #4 chỉ cho A05–A08 xem lượt.
- **Thứ tự lỗi:** visit đặt 400 trước 409 (#5, #8, khuôn `00-method` L70). Áp cho #9 thì lượt `COMPLETED` có người phụ trách còn hoạt động trả "400 người phụ trách vẫn hoạt động" — sai nghĩa.
- **Lý do:** hợp đồng ghi "`BR-TN-08` … thiếu lý do", nhưng thiếu trường là lỗi hình thức (`VALIDATION_FAILED`, khuôn ADR-0055).

## Quyết định

Người dùng chốt D1–D4 ngày 2026-10-21.

1. **D1 — điều kiện gán lại (A15).** Được gán lại khi người phụ trách hiện tại **không còn đủ điều kiện phụ trách lượt**: không `active` (bị khóa hoặc vô hiệu hóa), khác chi nhánh, hoặc sai chức vụ với nhóm dịch vụ. Dùng đúng định nghĩa của BR-TN-05 (`VisitDispatchService.isAssignable`), đọc dưới khóa `accounts FOR SHARE` của người cũ. **Không xét cờ `needs_reassign`**; gán lại thì gỡ cờ.
2. **D2 — phạm vi người thực hiện.**
   - BRANCH_MANAGER: `BranchScope.check(branch của lượt)` → 403 nếu chi nhánh khác.
   - SUPER_MANAGER: `StaffDirectoryApi.findActiveStaffIds(branch, BRANCH_MANAGER)` (`ACTIVE` và không khóa — cùng định nghĩa `AccountLockService` dùng để quyết định báo SUPER_MANAGER) không rỗng → **403 `ACCESS_DENIED_SCOPE_MISMATCH` message chung**. Đọc không khóa.
3. **D3 — Q2 (A14).** #4 `GET /visits/{id}` thêm `SUPER_MANAGER` (`DETAIL_ROLES`); `BranchScope.check` với CHAIN cho qua. SUPER_MANAGER lấy `visitId` từ thông báo khóa tài khoản (đã kèm việc dở). #2 không đổi.
4. **D4 — thứ tự lỗi #9:** role → body (400) → 404 → 403 → khóa → **409 không `IN_PROGRESS`** → 409 vừa bị gán lại song song → **400 BR-TN-08** → 400 BR-TN-05. Lệch có chủ đích so với #5 / #8: guard BR-TN-08 chỉ có nghĩa trên lượt đã gọi.
5. **Luồng `reassignCalledVisit`** (một `@Transactional`, READ COMMITTED):
   - `findSummary` (projection) → phạm vi (D2);
   - `findStaffForShare` cho người cũ (từ projection) và người mới **theo id tăng dần** (một lần nếu trùng hoặc lượt chưa gán) → `lockById` → `findById`;
   - kiểm `IN_PROGRESS` **tường minh** rồi `validateTransition(IN_PROGRESS, IN_PROGRESS)` (map có `WAITING → IN_PROGRESS` của #3, chỉ dùng FSM thì lượt `WAITING` lọt qua);
   - `assignee_id` dưới khóa khác projection → `ConcurrencyConflictException` 409 (tài khoản vừa khóa không phải của người cũ thật);
   - `isAssignable(người cũ)` → 400 BR-TN-08; `requireAssignable(người mới)` → 400 BR-TN-05;
   - ghi: `visit.reassignTo` (người mới, `needs_reassign = false`) → `saveAndFlush` → `visit_assignments` (`IN_PROGRESS`, lý do, cờ offline) → `VisitOrderApi.transferLineOwnership(cũ → mới)` → audit `VISIT_REASSIGNED` (`visits`, before / after `{assigneeId, needsReassign}`, lý do) → response `Visit`.
   - Thứ tự khóa: `accounts` (id ↑, `FOR SHARE`) → `visits` → `orders` (ADR-0058 §5).
6. **Body:** `assigneeId` `@NotNull @Positive`; `reason` `@NotBlank @Size(max = 300)`, compact constructor `strip()` trước Bean Validation (khoảng trắng Unicode → 400). Thiếu lý do → 400 `VALIDATION_FAILED`, không phải BR-TN-08.
7. **Không làm:** không thông báo (BR-TN-08 không yêu cầu); không trả cảnh báo offline (schema `Visit`), cờ offline vẫn lưu ở `visit_assignments`; không gọi `setAutoLineOwner` (dòng tự sinh thuộc người cũ từ #2, `transferLineOwnership` chuyển cùng).
8. **Nghĩa vụ cho T27 / T28:**
   - Bệnh án lưu người ghi theo từng phần, hiện tên người cũ sau khi gán lại (BR-TN-08 "kèm tên người ghi").
   - Ghi bệnh án, kê đơn, hoàn tất (#10) chỉ do người phụ trách **hiện tại** (`visits.assignee_id`), kiểm dưới khóa lượt.
   - Ghi mũi tiêm chỉ ở `IN_PROGRESS` (ADR-0062 §8).

## Đảm bảo

| Đảm bảo | Điều kiện | Test |
|---|---|---|
| G1. Người mới vừa bị khóa thì không nhận lượt | `findStaffForShare(mới)` chờ khóa tài khoản commit rồi đọc `is_locked` → 400 BR-TN-05 | `VisitReassignIT.reassignWaitsForAConcurrentLockOfTheNewAssignee` |
| G2. #9 đi trước, khóa người mới đi sau → lượt vẫn được đánh dấu | `FOR SHARE` làm lần khóa chờ; listener (READ COMMITTED) thấy `assignee_id` mới | `lockOfTheNewAssigneeWaitsForTheReassignmentAndFlagsTheVisitAgain` |
| G3. Người cũ vừa được mở khóa thì #9 từ chối | `findStaffForShare(cũ)` xung đột với `findByIdForUpdate` của mở khóa → 400 BR-TN-08 | `reassignWaitsForAConcurrentUnlockOfTheOldAssignee` |
| G4. Hai lần gán lại song song ghi một lần | khóa `visits` xếp hàng; lần sau thấy `assignee_id` khác projection → 409 `CONCURRENCY_CONFLICT` | `concurrentReassignmentsWriteOnceAndTheSecondIs409` |
| G5. Một transaction | lỗi ở `visit_assignments`, `order_lines` hoặc `audit_logs` → `visits`, Order, lịch sử không đổi | `failureOfAnyWriteRollsBackTheWholeReassignment` (trigger tạm) |
| G6. Thứ tự khóa | `accounts` id ↑ → `visits` → `orders` | unit `InOrder` (`reassignLocksAccountsByAscendingIdThenTheVisitThenWritesInOrder`, `newAssigneeWithTheLowerIdIsLockedFirst`) |

**Không đảm bảo:**
- SUPER_MANAGER đọc số quản lý không khóa: một BRANCH_MANAGER được mở khóa **trong lúc** SUPER_MANAGER gán lại thì lần gán đó vẫn hoàn tất (cửa sổ một request, như ADR-0061 A4, ADR-0062 #11). Khóa mọi tài khoản quản lý của chi nhánh `FOR SHARE` thì chặn được, nhưng thêm một nhóm khóa cho một cửa sổ không gây sai dữ liệu.
- Tài khoản của chính người thực hiện không bị khóa trong lúc request chạy.

## Mutation (mỗi thay đổi phải làm một test đỏ)

Đã chạy từng mutation trên `VisitReassignIT` ngày 2026-10-21; cả 8 đều đỏ.

| Mutation | Test đỏ |
|---|---|
| `findStaffForShare` → `findStaff` cho người mới | `reassignWaitsForAConcurrentLockOfTheNewAssignee` (G1) |
| `findStaffForShare` → `findStaff` cho người cũ | `reassignWaitsForAConcurrentUnlockOfTheOldAssignee` (G3) |
| Bỏ kiểm người phụ trách lệch projection | `concurrentReassignmentsWriteOnceAndTheSecondIs409` (G4) |
| Bỏ `transferLineOwnership` | `managerReassignsTheCalledVisitOfALockedVetWithHistoryOwnershipAndAudit` |
| `reassignTo` không gỡ cờ | `managerReassignsTheCalledVisitOfALockedVetWithHistoryOwnershipAndAudit` |
| `noRollbackFor = RuntimeException.class` | `failureOfAnyWriteRollsBackTheWholeReassignment` (G5) |
| Bỏ kiểm quản lý chi nhánh với SUPER_MANAGER | `superManagerReassignsOnlyWhenTheBranchHasNoActiveManagerAndSeesTheDetail` (D2) |
| `isolation = REPEATABLE_READ` | G1 + G3 (đọc lại sau khi chờ khóa thấy snapshot cũ) |

Chặn bằng test nhưng chưa chạy như mutation riêng:
- **Đảo 409 với BR-TN-08:** unit `visitThatIsNotInProgressIs409EvenWhenTheAssigneeIsStillEligible`, IT `visitThatIsNotInProgressIs409EvenWhenTheAssigneeIsStillActive` (lượt `WAITING` / `COMPLETED` với người phụ trách còn hoạt động).
- **`save` thay `saveAndFlush`, khóa `visits` trước `accounts`:** không quan sát được bằng hành vi → unit `InOrder`.

## Đã cân nhắc

- **Đúng chữ: chỉ xét `active`:** lượt của người đổi chức vụ kẹt, phải mở nợ chờ PO. Không chọn.
- **Theo cờ `needs_reassign`:** lệch chữ rule; cờ cũ sau mở khóa (D042) sẽ cho gán lại người đang hoạt động. Không chọn.
- **400 BR-TN-08 cho SUPER_MANAGER khi chi nhánh còn quản lý:** FE phân biệt được, nhưng đây là "ai được làm", thuộc hàng 403 của `00-method` L65. Không chọn.
- **Mở cả #2 cho SUPER_MANAGER:** cần `branchId` bắt buộc với SM, đổi hợp đồng và service nhiều hơn; thông báo đã có `visitId`. Không chọn.
- **400 trước 409 như #5 / #8:** message sai nghĩa cho lượt `COMPLETED` / `CANCELLED`. Không chọn.
- **Đọc lại người cũ sau khi khóa lượt (thay cho 409 xung đột):** phải khóa `accounts` sau `visits`, ngược thứ tự ADR-0058 (listener khóa tài khoản đi `accounts → visits`) → có thể deadlock. Không chọn.
- **Gộp vào `VisitDispatchService`:** actor khác (A05 / A04 so với A06), có audit; tách service giữ mỗi lớp một nhóm actor. Không chọn.
