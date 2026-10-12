# ADR-0035: Tìm, khóa và mở khóa tài khoản (T14 phần 5)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-12
- **Liên quan:**
  - identity-v1 #28 `GET /accounts`, #29 `POST /accounts/{id}/lock`, #30 `POST /accounts/{id}/unlock` (UC09); 03 Tài khoản#7 (L50), #8 (L51).
  - BR-QT-04 (02 L47), 11 (L54), 12 (L55), 15 (L58); BR-TK-11 (L27), 12 (L28); BR-TN-06 (L146), 08 (L148).
  - 06-module-contracts §3 (`AccountLockedEvent`), §4, §8 Q2 (người nhận khi khóa khách), Q4 (mã mẫu).
  - ADR-0014 (IN_APP), 0021 (`accounts → sessions`), 0024 (đếm BRANCH_MANAGER tính cả người bị khóa), 0029 (thứ tự khóa, khóa không qua policy phân cấp), 0031 (nghĩa vụ `FOR SHARE` khi tạo việc dở), 0033. Nợ D001, D010, D016, D017.
- **Triển khai:**
  - `identity/controller/AccountAdminController`; `identity/service/{AccountLockService, AccountAdminQueryService, PendingWorkCollector, AccountLockNotices}`.
  - `identity/dto/{ReasonRequest, LockResult, PendingWorkItem}`; `identity/mapper/AccountAdminMapper`; `identity/repository/AccountSummaryRow`.
  - `AccountRepository.{findRoleById, findAccountPage}`; `Account.{lock, unlock}`; `QtAuditActions.{ACCOUNT_LOCKED, ACCOUNT_UNLOCKED}`.
  - Đổi chữ ký `VisitQueryApi.findUnfinishedVisitsAssignedTo` (trả `AssignedVisit`) và `CashierShiftQueryApi.findOpenShift` (trả `Optional<OpenShift>`); `StaffAccountService.checkNoPendingWork` dùng hai method mới, message BR-QT-08 không đổi.
  - `sales/service/OrderQueryApiPlaceholder` (mới); `V12__seed_account_lock_templates.sql`.
  - Test:
    - Unit: `AccountLockServiceTest`, `AccountLockNoticesTest`, `AccountAdminMapperTest`, `PendingWorkTypeContractTest`, `ReasonRequestValidationTest`, `AccountTest` (lock / unlock), `OrderQueryApiPlaceholderTest`.
    - IT: `module/identity/AccountLockIT`, `StaffTransferPlaceholderIT` (ca khóa trên bean thật), `PendingAccountCleanupIT.lockedPendingAccountIsStillPurged`, `SchemaMigrationIT` (V12, mẫu IN_APP có subject).

## Bối cảnh

BR-QT-11 nói ai khóa (chỉ ADMIN, mọi tài khoản trừ chính mình), hiệu lực ngay, phải có lý do và audit, liệt kê việc dở dang và báo người phụ trách. BR-QT-12 nói khóa độc lập với `status`. BR-QT-04 cho phép khóa quản lý cuối cùng và yêu cầu báo SUPER_MANAGER. 06 §8 Q2 chốt người nhận khi khóa khách.

Những điểm spec chưa nói, người dùng chốt ngày 2026-10-12:
- **U1:** `PendingWorkItem` cần id, chi nhánh và loại của từng việc, nhưng hai interface cũ chỉ trả id / boolean.
- **U2:** có báo khi nhân viên không có việc dở không.
- **U3:** quản lý cuối cùng thì SUPER_MANAGER nhận gì.
- **U4:** xác định "cuối cùng" khi hai lần khóa chạy song song.
- **U5:** thông báo có kèm lý do không.
- **U6:** listener của visit chưa tồn tại thì có phát sự kiện không.
- **U7:** số migration.

## Quyết định

1. **U1 — thay method cũ, không thêm song song.**
   - `VisitQueryApi.findUnfinishedVisitIdsAssignedTo` → `findUnfinishedVisitsAssignedTo(accountId)`: `List<AssignedVisit(visitId, branchId, status)>`, chỉ WAITING / IN_PROGRESS.
   - `CashierShiftQueryApi.hasOpenShift` → `findOpenShift(accountId)`: `Optional<OpenShift(shiftId, branchId)>`.
   - Cả hai đọc thuần, trong transaction của bên gọi. Chưa có bản thật nào nên đổi không tốn gì.
   - BR-QT-08 (vô hiệu hóa, điều chuyển) dùng chung, message không đổi.
2. **Ai bị khóa được:**
   - Mọi role, mọi `status` (kể cả `PENDING`, `DISABLED`, ADMIN khác), trừ chính mình → 400 `BR-QT-11`, kiểm trước khi đọc DB.
   - Đã khóa → 409 (`InvalidStateTransitionException`, cạnh `is_locked: false → true` không có trong whitelist); mở khóa tài khoản chưa khóa → 409.
   - Không có tài khoản → 404, kể cả khi ST02 xóa tài khoản `PENDING` đúng lúc giữa bước đọc và bước khóa dòng.
   - ST02 vẫn xóa tài khoản `PENDING` quá hạn dù đang khóa (BR-TK-08 không có ngoại lệ).
3. **Lý do bắt buộc ở cả khóa lẫn mở khóa** (03 #7, #8 "Có lý do"):
   - `ReasonRequest.reason` `@NotBlank`, `@Size(max = 500)`. `@Size` đếm UTF-16 nên emoji tính 2, chặt hơn contract một chút, không bao giờ lỏng hơn.
   - Service strip trước khi lưu.
   - Khóa ghi `locked_reason`; mở khóa **giữ** `locked_reason` (erd: "lý do khóa gần nhất").
4. **Khóa (một transaction, không `noRollbackFor`, không facade):**
   1. Đọc role bằng projection (không nạp entity).
   2. Nếu là BRANCH_MANAGER: `BranchApi.lockForStaffChange({chi nhánh đọc được})` (U4).
   3. `findByIdForUpdate(accounts)`, rồi đọc hồ sơ.
   4. Là BRANCH_MANAGER dưới khóa mà chi nhánh hiện tại chưa bị khóa (vừa điều chuyển, vừa đổi chức vụ lên BRANCH_MANAGER) → 409.
   5. Đã khóa → 409.
   6. **Đọc** việc dở và người nhận (`PendingWorkCollector`): placeholder ném ở đây → 500, chưa có lệnh ghi nào.
   7. **Ghi:** `Account.lock` (`is_locked`, `locked_reason`, `last_seen_at = NULL`) → `revokeAll` → `publishEvent(AccountLockedEvent)` → `enqueue` IN_APP → audit `ACCOUNT_LOCKED` → `flush`.
   - Thứ tự khóa `branches → accounts → sessions` (ADR-0029 mục 4, ADR-0021).
   - Không có gì phải giữ lại khi rollback, nên lỗi ở bất kỳ bước nào rollback tất cả.
5. **Việc dở (`pendingWork`)**, sắp theo thứ tự enum của contract (lượt cần gán lại lên đầu) rồi `entityId`:
   - **A05–A08, mọi status:** lượt được gán chưa xong (`needsReassign` chỉ với IN_PROGRESS — BR-TN-08) và ca thu ngân mở. Hỏi cả hai với mọi chức vụ, như BR-QT-08.
   - **Khách:** lịch `BOOKED`, đặt chỗ `BOOKED` / `CHECKED_IN` / `OVERDUE`, Order `OPEN` / `PENDING` (Q2). Thiếu hồ sơ khách → 500 như `GET /me`.
   - **ADMIN, SUPER_MANAGER:** rỗng, không hỏi interface nào.
6. **Người nhận** (U2, U3, Q2):
   - **RECEPTIONIST / VET / CARETAKER:** BRANCH_MANAGER đang hoạt động của chi nhánh; không còn ai thì mọi SUPER_MANAGER (như BR-TN-08). **Luôn gửi**, kể cả không có việc dở.
   - **BRANCH_MANAGER:** mọi SUPER_MANAGER, mẫu `ACCOUNT_LOCKED_FOLLOW_UP_APP`. Nếu là **quản lý cuối cùng** (đang `ACTIVE`, chi nhánh đọc dưới khóa là `ACTIVE`, không còn BRANCH_MANAGER nào khác đang hoạt động) thì gửi **một** mẫu `LAST_BRANCH_MANAGER_LOCKED_APP` kèm việc dở và tên chi nhánh, thay cho mẫu thường.
   - **Khách:** mỗi chi nhánh có việc dở → mọi lễ tân đang hoạt động của chi nhánh đó, chỉ với việc của chi nhánh đó. Không việc dở → không ai. Chi nhánh có việc mà không còn lễ tân → log `WARN ACCOUNT_LOCK_NO_RECIPIENT`, không gửi.
   - **ADMIN, SUPER_MANAGER:** không ai.
   - Thông báo kèm lý do (U5).
7. **U6 — phát `AccountLockedEvent(accountId, role, branchId, reason, actorId)`** ngay, đồng bộ trong transaction khóa. `branchId` null với ADMIN / SUPER_MANAGER / khách. Chưa có listener → không làm gì. Listener của visit (đặt `visits.needs_reassign`) là nợ **D017**, trả ở T26.
8. **Mở khóa:**
   - Một transaction: `findByIdForUpdate` → chưa khóa 409 → `Account.unlock()` (chỉ `is_locked`) → audit `ACCOUNT_UNLOCKED` → `flush`.
   - Không khóa `branches`, vì mở khóa chỉ làm tăng số quản lý (ADR-0029 mục 5).
   - Không hủy hay mở phiên, không thông báo, không sự kiện. `status` giữ nguyên (BR-QT-12).
9. **#28:** ADMIN, mọi role, mọi trạng thái.
   - `q` tìm trong email (không phân biệt hoa thường, LIKE escape như `StaffQueryService`, `@Size(max = 255)`); `role`, `isLocked`; `page` / `size` kẹp 1–100; sắp theo `id`.
   - Projection `AccountSummaryRow` không SELECT cột bí mật.
10. **Audit:** `before` / `after` = `{isLocked, status}`, `reason` = lý do. 403 vai trò không audit (`@PreAuthorize`; ADR-0029: khóa không qua ma trận phân cấp).
11. **U7 — V12** seed hai mẫu IN_APP theo khung V11 (khôi phục được, có `subject`, biến bắt buộc nằm trong body). **Số V12 đã xác nhận với BE-2 (2026-10-12).**
12. **`OrderQueryApiPlaceholder`** (module sales, BE-1 tạo thay như tiền lệ `CashierShiftQueryApiPlaceholder`): trước đây không có bean `OrderQueryApi` nào, inject vào là app không khởi động. BE-2 xóa lớp này khi cài thật.

## Lý do và phương án bị loại

- **Thêm method mới song song với method cũ:** hai method trùng nghĩa, BE-2 phải cài cả hai. Loại (U1).
- **Đọc "quản lý cuối" không khóa `branches`:** hai lần khóa song song mỗi bên thấy người kia còn hoạt động → không ai gửi LAST. Loại (U4); `AccountLockIT.concurrentLockOfLastTwoManagersSendsExactlyOneLast` đỏ khi bỏ khóa (mutation đã chạy).
- **Facade như `StaffAdminService`:** không có BCrypt và không có 403 phân cấp cần audit sau rollback, nên facade chỉ là một tầng thừa.
- **Chờ T26 mới phát sự kiện:** phần 5 sẽ không trọn hợp đồng 06 §3, và T26 phải sửa cả identity. Loại (U6).
- **Trả `pendingWork` rỗng khi interface còn placeholder:** sai 06 §1 (không có giá trị "an toàn"). Giữ 500.

## Hệ quả

- **Chạy được trên app thật hôm nay:** khóa / mở khóa ADMIN và SUPER_MANAGER; mở khóa mọi tài khoản; #28.
- **Còn 500, không ghi gì:**
  - Khóa A05–A08 (`VisitQueryApi`, `CashierShiftQueryApi` placeholder — D016, khoảng 21–22/10).
  - Khóa khách (`CustomerQueryApi` — D010; `AppointmentQueryApi`, `BoardingQueryApi`, `OrderQueryApi` cũng còn placeholder).
- **Hệ quả có chủ đích** (đã có code và test từ trước, phần 5 không sửa):
  - Tài khoản đang khóa: đăng nhập 400 BR-TK-11, quên mật khẩu không gửi OTP, vô hiệu hóa 400 BR-QT-12, kích hoạt lại 400 BR-QT-10.
  - VET đang khóa rời danh sách VET công khai; người đang khóa bị loại khỏi danh sách gán lượt và danh sách người nhận thông báo.
  - Kích hoạt chi nhánh vẫn đếm BRANCH_MANAGER đang khóa (ADR-0024).
- **Rủi ro chấp nhận:**
  - **R15 — khách đặt lịch đúng lúc bị khóa:** khách đặt lịch online cùng giây bị khóa thì lịch đó có thể thiếu trong thông báo. Luồng đặt lịch (T16) không khóa `accounts`; lịch vẫn hiện trong danh sách của chi nhánh. T16 xem lại nếu muốn `FOR SHARE accounts`.
  - **Thông báo LAST có thể thừa:** mở khóa / tạo / kích hoạt lại quản lý commit đúng lúc khóa đang đọc thì LAST vẫn gửi. Các luồng làm tăng số quản lý không khóa `branches`; SUPER_MANAGER kiểm lại sẽ thấy đã có quản lý.
  - **Không báo LAST trong một trường hợp:** SUPER_MANAGER vô hiệu hóa / điều chuyển / đổi chức vụ quản lý chưa khóa cuối cùng, trong khi người còn lại đang bị khóa. Spec chỉ yêu cầu báo khi quản lý cuối *bị khóa*, và người làm thao tác chính là SUPER_MANAGER.
  - **Request đang chạy dở của người bị khóa** hoàn tất với quyền cũ; request kế tiếp → 401 (ADR-0029 mục 10).
- **Nghĩa vụ cho T26 / BE-2** (D016, D017):
  - Gán lượt và mở ca phải `SELECT … FROM accounts … FOR SHARE` rồi kiểm `is_locked` dưới khóa đó (ADR-0031 mục 9). Thiếu bước này thì việc dở có thể chen vào sau lần đọc của luồng khóa.
  - Visit cài listener `AccountLockedEvent` (`@Transactional(MANDATORY)`, đặt `needs_reassign` cho lượt IN_PROGRESS của người bị khóa).
  - Cài thật 2 interface đã đổi chữ ký.

## TBD

- Retention của `locked_reason` và câu chữ hai mẫu: ADMIN sửa được qua UC10 (T15).
