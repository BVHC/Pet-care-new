# ADR-0062: Gọi lượt, hủy lượt khách bỏ về và đánh dấu cần gán lại khi khóa tài khoản (`POST /api/visits/{visitId}/call`, `/cancel`; listener `AccountLockedEvent`)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-21
- **Liên quan:**
  - BR-TN-01, 05, 07, 08, 09 (02 L141, L145–149), BR-QT-08, 11, 12, 15 (L51, L54–55, L58), BR-LH-03, 09, 11, BR-KB-02, BR-TK-17.
  - 01 UC45, UC46 (L115–116); 03 §4 Visit #3, #6 (L104, L107), Lịch hẹn#8 (L88), Order#6 (L128), Tài khoản#7, #8 (L50–51), Phụ lục (L243); erd §6 `visits.needs_reassign` (L537).
  - visit-v1 #8, #11 (lỗi / ghi chú viết lại), §E Q3 mới; 06 §3 `AccountLockedEvent`; `docs/api/00-method.md` L65–70.
  - ADR-0024 (`active`), 0031 Q4 + mục 9 (đổi chức vụ khi còn việc dở; khóa `accounts FOR SHARE`), 0035 (khóa tài khoản, phát sự kiện), 0049 (lý do hủy không bắt buộc), 0050 (`AppointmentApi.cancelByVisit`), 0044 (`VisitOrderApi.cancelByVisit`), 0058 (thứ tự khóa, tên command), 0060 (`VisitResponder`, `requireRole`), 0061 (khuôn điều phối, `requireAssignable`, `findStaffForShare`).
  - Nợ: **trả D017**; **mở D042** (cờ không gỡ khi mở khóa).
- **Triển khai (T26 phần 4):**
  - visit: `controller/VisitController` (+2 handler, hằng `CALL_ROLES`), `service/VisitProgressService` (`callVisit`, `cancelVisit`), `service/VisitReassignFlagService`, `listener/AccountLockedListener` (package mới), `dto/CancelVisitRequest`, `entity/Visit` (`call`, `cancel`), `repository/VisitRepository.flagNeedsReassign`, `fsm/VisitTransitionHandler.FSM_NAME`.
  - identity, appointment, sales: không sửa; gọi `StaffDirectoryApi.findStaffForShare`, `AppointmentApi.cancelByVisit`, `VisitOrderApi.cancelByVisit` có sẵn.
  - Hợp đồng: `docs/api/generator/m_visit.py` (#8 thêm 400, 403 / 409 viết lại, ghi chú; #11 thêm 400, 403, 409 viết lại; mô tả `CancelVisitRequest.reason`; Q3).
  - Test:
    - Unit: `VisitProgressServiceTest` (34), `VisitReassignFlagServiceTest` (3), `AccountLockedListenerTest` (2), `CancelVisitRequestValidationTest` (2), `VisitTest` (3), `VisitControllerSecurityTest` (sửa map).
    - IT: `VisitProgressIT` (37: đường đúng, rule, thứ tự lỗi, ma trận đầu vào, vai trò / phiên / chéo chi nhánh, rollback xuyên module, listener L1–L7, race R1–R7), `VisitDispatchCountIT` (+1: hủy lượt không tăng câu SQL theo số dòng Order), `OpenApiContractParityIT` (101 → 103).
  - Không migration, không event mới, không job, không audit, không thông báo.

## Bối cảnh

Phần 3 điều phối được lượt chưa gọi; chưa ai chuyển lượt sang `IN_PROGRESS` hay hủy lượt. Các điểm docs để ngỏ hoặc mâu thuẫn:

- **#8 403 "`BR-TN-05`":** hợp đồng viết mã rule vào 403. Nhưng `00-method.md` L65 nói 403 chỉ có message chung (danh sách rule trả 403 không có BR-TN-05), và L70 nói rule mà FE cần phân biệt thì trả 400.
- **#11 409 "`BR-TN-07`":** `InvalidStateTransitionException` không mang mã rule.
- **Người gọi thay đổi trong lúc request chạy:** ADR-0031 Q4 cho đổi chức vụ khi còn lượt được gán. Ví dụ VET đổi thành CARETAKER vẫn còn lượt Khám, và `@PreAuthorize` của #8 vẫn cho CARETAKER qua. Ngoài ra, lần khóa tài khoản chạy song song có thể không thấy lượt vừa được gọi.
- **D017:** từ phần này mới có lượt `IN_PROGRESS` thật. Nếu chưa có listener `AccountLockedEvent` thì khóa một VET đang khám không đánh dấu lượt cần gán lại (03 Tài khoản#7).
- **Mở khóa:** 03 Tài khoản#8 / BR-QT-12 chỉ nói `status` giữ nguyên, không nói gì về `needs_reassign`.
- **FSM:** có cạnh `IN_PROGRESS → IN_PROGRESS` (#4 gán lại lượt đã gọi). Chỉ dùng `validateTransition(status, IN_PROGRESS)` thì gọi lượt lần hai **lọt qua** và ghi đè `called_at`. Unit test bắt được lỗi này khi code.

## Quyết định

1. **#8 `callVisit` (Visit#3).** Thứ tự chạy:
   - role (`VET`, `CARETAKER`; lớp hai `requireRole`) → `findSummary` 404 → `BranchScope.check` 403;
   - **`StaffDirectoryApi.findStaffForShare(người gọi)`** (khóa `accounts FOR SHARE`) → khóa `visits` (`lockById`, rồi `findById`);
   - người gọi ≠ `assignee_id` (kể cả lượt chưa gán) → **403 `ACCESS_DENIED_SCOPE_MISMATCH` message chung** (Q1);
   - `requireAssignable` dưới khóa tài khoản → 400 BR-TN-05 nếu người gọi không còn `active`, khác chi nhánh, hoặc sai chức vụ với nhóm (Q4);
   - **trạng thái phải là `WAITING`, kiểm tường minh** rồi mới `validateTransition(WAITING, IN_PROGRESS)` (tránh cạnh #4) → 409;
   - `called_at` = clock cắt µs → `saveAndFlush`.
   - Không ghi `visit_assignments`, không đụng Order / lịch hẹn.
2. **#11 `cancelVisit` (Visit#6).** Thứ tự chạy:
   - role `RECEPTIONIST` → 404 → 403 → khóa `visits` → `validateTransition(status, CANCELLED)` → 409 (`IN_PROGRESS` / `COMPLETED` / `CANCELLED`, Q2);
   - `at` = clock µs, lý do đã chuẩn hóa → `saveAndFlush` lượt;
   - **`AppointmentApi.cancelByVisit(appointmentId, at)`** nếu có lịch (Lịch hẹn#8: `STAFF`, `late_cancel = false`, cùng mốc `at`);
   - **`VisitOrderApi.cancelByVisit(visitId, lễ tân)`** (Order#6: `VISIT_CANCELLED`, dòng giữ nguyên);
   - dựng response.
   - Thứ tự khóa `visits → appointments → orders` (ADR-0058 §5). Không khóa `branches` / advisory, vì `AppointmentApi` chỉ cấm khóa `appointments` trước `branches`.
3. **Body #11 không bắt buộc** (A4, khuôn ADR-0049):
   - `@RequestBody(required = false)`, `@Size(max = 300)` có message tiếng Việt.
   - Service `strip()` (khoảng trắng Unicode); chuỗi rỗng thì lưu NULL.
   - Jackson mặc định đổi số / boolean thành chuỗi (`{"reason": 123}` → `"123"`, đã ghim bằng IT); không đổi cấu hình.
   - Hợp đồng sinh `requestBody.required: true` vì generator chưa có cờ body tùy chọn, giống appointment-v1 #6. Ghi chú #11 nói rõ "Không gửi body: hủy không lý do".
4. **Bấm lại:** gọi lần hai / hủy lần hai → 409, không ghi. Tiền lệ: xác định cấp cứu lần hai (ADR-0061); hợp đồng ghi 409.
5. **Listener D017.**
   - `visit/listener/AccountLockedListener.on(AccountLockedEvent)` chỉ gọi `VisitReassignFlagService.flagCalledVisitsOf(accountId)` (`MANDATORY`).
   - Một câu native `UPDATE visits SET needs_reassign = true, updated_at = :now WHERE assignee_id = :id AND status = 'IN_PROGRESS' AND needs_reassign = false`. Repository có `@Transactional(MANDATORY)` và `@Modifying(flushAutomatically = true)`, **không** `clearAutomatically`.
   - Chạy trong transaction khóa của identity (`AccountLockService.lockAccount`, sau `sessions.revokeAll`); lỗi → cả lần khóa rollback.
   - Không lọc theo role: đổi chức vụ được phép khi còn lượt (ADR-0031 Q4).
   - Lượt `WAITING` không đánh dấu: lễ tân gán lại tự do (Visit#2).
6. **Mở khóa không gỡ cờ (Q5).** Docs không quy định. Cờ còn lại chỉ là gợi ý cũ trên hàng đợi; người phụ trách vẫn khám / hoàn tất được, vì hoàn tất không xét cờ này. Câu hỏi PO ghi ở visit-v1 Q3; nợ **D042**.
7. **Không audit, không thông báo** cho #8 / #11 (BR-QT-15 không liệt kê; 08 §8.3). Thông báo khi khóa tài khoản do identity tự gửi như cũ.
8. **Nghĩa vụ cho T28** (ghi mũi tiêm): chỉ cho ghi khi Visit `IN_PROGRESS`. Nếu ghi được ở `WAITING`, #11 sẽ hủy Order mà kho vaccine đã trừ không được hoàn (Order#6 không hoàn kho).

## Lý do

- **403 chung cho "không phải chủ lượt"** (Q1): đúng hàng "không phải chủ" của `00-method` L65. Không tạo lớp con chỉ để đổi message (convention 04 §4.2). FE chỉ hiện nút Gọi ở hàng đợi của chính người dùng (#3), nên không cần phân biệt. Hợp đồng ghi mã rule dạng mô tả `(BR-TN-05, 09)`, không phải đuôi message.
- **409 chung** (Q2): cùng khuôn #5 "Lượt đã được gọi". Hợp đồng ghi `(BR-TN-07 …)` trong mô tả.
- **Khóa `FOR SHARE` tài khoản người gọi** (Q4): mở rộng nghĩa vụ ADR-0031 mục 9 từ Visit#2 / #4 sang Visit#3. Hai lý do:
  - *Khóa tài khoản đi trước:* #8 chờ, rồi đọc thấy `is_locked` → 400. Lượt không thành `IN_PROGRESS` cho người đã bị khóa.
  - *#8 đi trước:* lần khóa tài khoản chờ `FOR NO KEY UPDATE`; sau khi #8 commit, `pendingWork` và câu UPDATE của listener (READ COMMITTED) đều thấy lượt vừa gọi.

  Không có vòng chờ: không bên nào giữ `visits` rồi chờ `accounts`.
- **Kiểm `WAITING` tường minh:** chỉ dùng FSM thì cạnh #4 cho `IN_PROGRESS → IN_PROGRESS` qua. Đây là cùng khuôn `AppointmentVisitService.cancelByVisit` (cạnh của #4 / #5 không được dùng cho #8).
- **Ghi lượt trước rồi mới gọi appointment → sales:** mọi guard và lỗi DB của visit lộ trước; thứ tự khóa vẫn đúng vì lượt đã bị khóa từ trước đó. Callee lỗi → rollback cả ba (đã chứng minh bằng trigger và dữ liệu hỏng).
- **Không `clearAutomatically`:** lúc listener chạy, entity `Account` của identity vừa bị `lock()` mà chưa flush (`AccountLockService` L136–144). `flushAutomatically` ghi nó trước câu native; clear sẽ tách entity mà identity còn dùng (audit, mapper). Transaction khóa chỉ đọc visit qua projection, nên không có entity `Visit` cũ cần xóa.

## Hệ quả

- #2 / #3 / #4 hiện `IN_PROGRESS`, `calledAt`; lượt `CANCELLED` rời hàng đợi mặc định (A1); bộ lọc `needsReassign=true` có dữ liệu thật.
- **identity:**
  - Lượt đã hủy không còn chặn vô hiệu hóa / điều chuyển (BR-QT-08).
  - Khóa A05–A08 trên app thật **vẫn 500** tới khi BE-2 cài `CashierShiftQueryApi` (D016), nên listener chỉ chạy thật sau T30. IT thay bean đó bằng mock.
- **appointment:**
  - Lịch `CHECKED_IN → CANCELLED` (Lịch hẹn#8), trả quota (BR-LH-03, đã kiểm bằng `available-slots`).
  - Không tính vi phạm BR-LH-09. ST03 / ST05 không chạm (chỉ `BOOKED`).
- **sales:** Order `CANCELLED` không thêm / sửa dòng được (409). `orders.cancelled_at` lấy `Instant.now(clock)` của sales nên có thể lệch vài µs so với `visits.cancelled_at`; giá trị này không trả qua visit.
- **customer:** sau khi hủy, thú không còn Visit mở → được đánh dấu mất / chuyển chủ.
- **Không đảm bảo:**
  - Cờ không tự gỡ khi mở khóa (D042).
  - #11 không khóa tài khoản lễ tân: lễ tân bị khóa **trong lúc** request chạy thì request đó vẫn hoàn tất (cửa sổ một request, như ADR-0061 A4).

## Mutation (mỗi thay đổi phải làm một test đỏ)

| Mutation | Test đỏ |
|---|---|
| `findStaffForShare` → `findStaff` ở #8 (bỏ khóa) | `VisitProgressIT.callWaitsForAConcurrentLockOfTheCaller` (R1), `lockWaitsForACallHoldingTheShareLockAndFlagsTheNewlyCalledVisit` (R2) |
| Bỏ kiểm `WAITING` tường minh ở #8 | `VisitProgressServiceTest.callOfAVisitThatIsNoLongerWaitingIs409[IN_PROGRESS]`, `VisitProgressIT.callingTwiceOrACompletedVisitIs409` |
| Bỏ gọi `AppointmentApi.cancelByVisit` | `cancelOfAnAppointmentVisitCancelsTheAppointmentNotLateAndReturnsTheSlot` |
| Bỏ gọi `VisitOrderApi.cancelByVisit` | `cancelOfAWalkInCancelsTheVisitAndItsOrderTogether` |
| `noRollbackFor = PlatformException.class` trên `cancelVisit` | `cancelRollsBackWhenTheAppointmentIsNotCheckedIn` |
| Bỏ `status = 'IN_PROGRESS'` trong câu UPDATE | `lockFlagsOnlyTheCalledVisitsOfTheLockedAccount` |
| Bỏ `@EventListener` | `lockFlagsOnlyTheCalledVisitsOfTheLockedAccount` |
| Đảo "không phải chủ" với kiểm trạng thái | `VisitProgressServiceTest.notTheAssigneeComesBeforeTheStateCheck`, `VisitProgressIT.callErrorPrecedence` |
| Đảo `requireAssignable` với kiểm trạng thái | `callerWhoseRoleChangedAfterAssignmentIs400` (unit + IT) |
| Không cắt µs | `callMovesTheVisitToInProgressAndTouchesNothingElse` (POST = GET, DB = µs) |
| `@PreAuthorize` #8 nới thành `QUEUE_ROLES` | `VisitControllerSecurityTest`, `VisitProgressIT.callRolesMatrix` |

Không phát hiện được bằng hành vi (chặn bằng review và unit):
- **`save` thay `saveAndFlush`:** Hibernate tự flush trước câu kế tiếp → unit `InOrder ... saveAndFlush`.
- **Khóa `visits` trước `accounts` ở #8:** không bên nào giữ `visits` rồi chờ `accounts`, nên không có deadlock để quan sát → unit `InOrder findStaffForShare → lockById`.
- **`clearAutomatically = true`:** AUTO flush thường ghi kịp `Account` nên kết quả có thể vẫn đúng → unit reflection `VisitReassignFlagServiceTest.serviceAndRepositoryJoinTheLockTransaction`.
- **try / catch quanh lời gọi callee rồi đi tiếp:** dữ liệu vẫn rollback (rollback-only), chỉ HTTP đổi 409 → 500 → unit `calleeFailureIsNotSwallowed`.

## Đã cân nhắc

- **Lớp con 403 / 409 kèm `(BR-…)` ở message** (khuôn `OrderLineAccessDeniedException`): FE đọc được `ruleId`, nhưng lệch `00-method` L65 (BR-TN-05 không thuộc danh sách rule 403) và convention 04 §4.2. Không chọn.
- **400 BR-TN-05 cho "không phải chủ lượt":** khớp L70 nhưng trái hàng "không phải chủ" = 403 và hợp đồng. Không chọn.
- **Bấm lại #8 / #11 = 200 không ghi** (khuôn ADR-0061 Q4): hợp đồng ghi 409; trạng thái đã đổi nên khác "gán đúng người đang được gán". Không chọn.
- **Không khóa tài khoản người gọi:** đơn giản hơn, nhưng để lọt lượt `IN_PROGRESS` của người vừa bị khóa (không được đánh dấu), và VET đổi thành CARETAKER vẫn gọi được lượt Khám. Không chọn.
- **Lọc listener theo role VET / CARETAKER:** bỏ sót người đã đổi chức vụ mà còn giữ lượt. Không chọn.
- **Mở khóa gỡ cờ** (`AccountUnlockedEvent`): hợp lý theo nghĩa của cờ, nhưng là hành vi docs chưa ghi → chờ PO (D042).
- **Tính `needsReassign` động** (IN_PROGRESS + người phụ trách không ACTIVE): luôn đúng, nhưng lệch erd L537 / 06 §3 (cột đã có), và bộ lọc phải ghép chéo module. Không chọn.
- **Để D017 sang phần 5 cùng #9:** phần 4 là nơi sinh lượt `IN_PROGRESS` đầu tiên, để lâu hơn thì cờ sai trên dữ liệu thật. Không chọn.
