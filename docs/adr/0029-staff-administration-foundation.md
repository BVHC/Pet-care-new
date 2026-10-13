# ADR-0029: Nền chung quản trị nhân viên — ma trận phân cấp, 403 có audit, khóa chi nhánh, mật khẩu tạm

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-11
- **Liên quan:**
  - BR-QT-01 (02 L44), 02 (L45), 03 (L46), 04 (L47), 05 (L48), 06 (L49), 07 (L50), 08 (L51), 10 (L53), 11 (L54), 12 (L55), 15 (L58); BR-TK-03 (L19), 15 (L31), 16 (L32), 17 (L33); 03 Tài khoản #4–#8.
  - identity-v1 #15–24, #28–30 (UC08, UC09); `docs/api/00-method.md` L40 (chi nhánh của người gọi), L65 (403 message chung), L70 (thứ tự kiểm).
  - ADR-0003 (phân quyền, `BranchScope`, *Hệ quả*: `@PreAuthorize` A03–A05 + service kiểm phân cấp), ADR-0019 mục 6 (audit sau rollback), ADR-0021 (`accounts → sessions`), ADR-0023 (BCrypt ngoài khóa), ADR-0024 (`countBranchManagers`), ADR-0026 (`accounts → staff_profiles`), ADR-0028 (BE-1 làm thay BE-2). Nợ D014, D016.
- **Triển khai (T14 phần 0, không endpoint):**
  - `identity/service/{StaffAction, StaffHierarchyPolicy, QtAuditActions, TemporaryPasswordGenerator}`, `identity/exception/StaffHierarchyDeniedException`.
  - `branch/api/BranchApi`, `branch/service/BranchStaffLockService`, `BranchRepository.lockStatusForStaffChange`.
  - `visit/service/VisitQueryApiPlaceholder`, `sales/service/CashierShiftQueryApiPlaceholder` (nợ D016).
  - `V11__seed_staff_admin_templates.sql`: `STAFF_TEMP_PASSWORD`, `OTP_EMAIL_CHANGE`, `EMAIL_CHANGED_NOTICE`.
  - Test: `StaffHierarchyPolicyTest` (1651 tổ hợp), `StaffHierarchyDeniedExceptionTest`, `TemporaryPasswordGeneratorTest`, `BranchStaffLockServiceTest`, `BranchStatusParityTest`, `VisitQueryApiPlaceholderTest`, `CashierShiftQueryApiPlaceholderTest`, `GlobalExceptionHandlerTest.scopeMismatchSubclassHidesAuditDetails`, `AuditRecorderTest` (khóa mới), `module/branch/BranchStaffLockIT`, `SchemaMigrationIT` (V11).

## Bối cảnh

T14 xây 13 endpoint quản trị nhân viên và khóa tài khoản, chia 6 phần (0–5). Cả 5 phần endpoint cùng cần: luật ai được thao tác với ai, cách ghi audit cho 403 vượt phân cấp (BR-QT-01 "từ chối (403) và ghi audit") mà không cần connection thứ hai khi đang giữ khóa dòng, khóa dòng `branches` để BR-QT-04 không bị chạy chéo (D014: `countBranchManagers` đọc không khóa), mật khẩu tạm (BR-QT-02) và mẫu email. Module branch (BE-2) chưa có interface ghi nào; `VisitQueryApi`, `CashierShiftQueryApi` (guard BR-QT-08) chưa cài tới 21/10.

## Quyết định

1. **Ma trận phân cấp** (`StaffHierarchyPolicy`, thuần logic, không DB, **không `@Transactional`**):

   | Thao tác | ADMIN | SUPER_MANAGER | BRANCH_MANAGER |
   |---|---|---|---|
   | `CREATE` (BR-QT-01) | `SUPER_MANAGER` | A05–A08 | A06–A08; `branchId` null = chi nhánh mình (00-method L40), chi nhánh khác → từ chối |
   | `CHANGE_ROLE` (BR-QT-05) | — | người bị tác động và role mới ∈ A05–A08 | — |
   | `TRANSFER` (BR-QT-06) | — | người bị tác động ∈ A05–A08 | — |
   | `DISABLE`, `REACTIVATE` (BR-QT-07, 10) | `SUPER_MANAGER` | A05–A08 | A06–A08 cùng chi nhánh; kích hoạt lại không gán sang chi nhánh khác |
   | `RESEND_INITIAL_PASSWORD`, `CHANGE_EMAIL` (BR-QT-02, BR-TK-16 "theo phân cấp tạo tài khoản") | `SUPER_MANAGER` | A05–A08 | A06–A08 cùng chi nhánh |

   - Người bị tác động là **chính mình** luôn bị từ chối, bất kể role đọc dưới khóa (BR-QT-07; BR-TK-15 cấm tự sửa email).
   - BR-QT-05, 06 ("chỉ SUPER_MANAGER") là điều kiện **theo vai trò**: controller chặn bằng `@PreAuthorize("hasRole('SUPER_MANAGER')")` (403 `ACCESS_DENIED`, không audit, như ADR-0003). Policy vẫn kiểm (không phụ thuộc controller), còn audit thì chỉ ghi cho lần từ chối do người bị tác động nằm ngoài A05–A08.
   - Khóa / mở khóa (UC09, chỉ ADMIN) không qua policy: `@PreAuthorize("hasRole('ADMIN')")` + service kiểm "không khóa chính mình" (400, identity-v1 #29).
   - **Phạm vi xem** (`GET /staff`, `GET /staff/{id}`) không qua policy: dùng `BranchScope.resolve` / `check` (ADMIN, SUPER_MANAGER toàn chuỗi; BRANCH_MANAGER chi nhánh mình; 403 không audit). BR-QT-07 "ẩn nút … ngoài phạm vi" ngụ ý thấy được mà không thao tác được.

2. **403 vượt phân cấp có audit** (`StaffHierarchyDeniedException extends AccessDeniedScopeException`, tiêu chí 3 của convention 04 §4.2):
   - Client nhận 403 `ACCESS_DENIED_SCOPE_MISMATCH`, message chung "Không có quyền truy cập dữ liệu này" (00-method L65). Role, chi nhánh, thao tác chỉ nằm trong bản ghi audit.
   - Audit `STAFF_ACTION_DENIED`: `entity = accounts/<người bị tác động>` (không có với `CREATE`), `after = {action, targetRole, targetBranchId, requestedRole, requestedBranchId}` (`LinkedHashMap`, nhận null). Không có `before`.
   - **Khuôn bắt buộc:** service ghi `@Transactional` gọi policy **dưới khóa, trước mọi lệnh ghi**; transaction rollback và trả connection; facade không `@Transactional` bắt lỗi, gọi `AuditRecorder.recordIndependently(ex.auditEntry())`, ném lại. Không bắt lỗi này bên trong transaction (đã rollback-only khi đi qua proxy).

3. **Thứ tự kiểm** (00-method L70): Bean Validation (400) → `@PreAuthorize` (403 không audit) → người bị tác động tồn tại và là nhân viên (404; tài khoản `CUSTOMER` → 404) → policy (403 có audit) → guard BR (400; BR-QT-03 sau policy để người không có quyền không dò được chi nhánh) → `validateTransition` (409) → tranh chấp (409 chung).

4. **Thứ tự khóa của mọi luồng T14:** `branches` (id tăng dần) → `accounts` → `staff_profiles` → `sessions`. Mọi bước đọc **trước** khi khóa phải là projection (`StaffProfileRepository.findStaffRow`, `BranchQueryApi.findBranch`), không nạp entity — nếu nạp, câu khóa sau đó trả lại instance cũ (cùng bẫy `AccountCredential`, ADR-0019). Chi nhánh của người bị tác động chỉ biết sau khi đọc: đọc `branch_id` không khóa → `BranchApi.lockForStaffChange` → `findByIdForUpdate(accounts)` → đọc lại; lệch (vừa bị điều chuyển) → `ConcurrencyConflictException` 409.

5. **BR-QT-04 dưới khóa** (trả nợ D014 ở phần 2, 3): mọi luồng có thể làm **giảm** số BRANCH_MANAGER `ACTIVE` của chi nhánh X (vô hiệu hóa, điều chuyển đi, đổi chức vụ khỏi BRANCH_MANAGER) khóa `branches(X)` **trước** khi đếm, **kể cả khi X đang DRAFT** (để `activateBranch`, vốn cũng khóa X trước khi đếm, không đếm số cũ). Luồng tăng số và luồng khóa tài khoản (BR-QT-04 cho khóa BRANCH_MANAGER cuối cùng) không cần guard.

6. **`BranchApi.lockForStaffChange(Collection<Long>)`** (module branch, BE-1 thêm thay BE-2 như ADR-0028): `@Transactional(MANDATORY)`; transaction `readOnly` → `IllegalStateException` trước mọi SQL (PostgreSQL không cho khóa dòng trong transaction chỉ đọc); khóa từng id theo **thứ tự tăng dần do branch giữ** bằng câu native vô hướng `SELECT status FROM branches WHERE id = :id FOR NO KEY UPDATE` (không nạp entity → luôn đọc giá trị mới, commit không sinh `UPDATE branches`); trả `SortedMap<Long, BranchQueryApi.BranchStatus>`, id lạ vắng mặt (không ném — exception đi qua proxy `MANDATORY` làm transaction bên gọi rollback-only). Lỗi khóa (`PessimisticLockingFailureException`, deadlock) bên gọi **không bắt**, để handler trả 409.

7. **Mật khẩu tạm** (`TemporaryPasswordGenerator.prepare()`): gọi **ngoài** transaction (khuôn `OtpService.prepare`, ADR-0023) — `SecureRandom` + BCrypt. Độ dài `max(12, password.min_length)` [CFG] (≤ 64 ký tự ASCII, dưới 72 byte của BCrypt), có ít nhất 1 chữ và 1 số, bỏ `0 O o 1 l I`, luôn qua `PasswordPolicy` (BR-TK-03). Record `TemporaryPassword(plainPassword, passwordHash)`: `toString` che cả hai; tên thành phần cố ý chứa `password` để `AuditRecorder` từ chối nếu record lọt vào snapshot (`SENSITIVE_WORDS` không có `mat_khau`). Mật khẩu gốc chỉ vào payload outbox khóa `mat_khau_tam` (bị xóa sau khi gửi), cùng transaction với hash, nên không có email nào mang mật khẩu chưa được lưu. Mỗi lần gửi là mật khẩu mới; mẫu nói rõ chỉ email mới nhất còn dùng được.

8. **V11** seed 3 mẫu email theo khung V8 (khôi phục được, biến bắt buộc nằm trong biến cho phép và có trong nội dung): `STAFF_TEMP_PASSWORD` (HIGH; `ho_ten`, `email_dang_nhap`, `mat_khau_tam`, cả 3 bắt buộc vì nhân viên luôn có họ tên), `OTP_EMAIL_CHANGE` (HIGH; `ma_otp`*, `thoi_han_phut`; câu chữ chung cho nhân viên và khách tại quầy), `EMAIL_CHANGED_NOTICE` (NORMAL; `email_cu`*, `email_moi`*, `thoi_diem`* dạng `HH:mm dd/MM/yyyy`). Seed trước lời gọi `enqueue` đầu tiên (phần 1, 3, 4) theo tiền lệ V9. `ACCOUNT_LOCKED_FOLLOW_UP_APP`, `LAST_BRANCH_MANAGER_LOCKED_APP` seed ở phần 5 khi thiết kế payload `pendingWork` (người dùng chốt).

9. **Placeholder** `VisitQueryApiPlaceholder` (owner BE-1) và `CashierShiftQueryApiPlaceholder` (owner BE-2, BE-1 commit thay): mọi method ném `UnsupportedOperationException` (06 §1). Điều chuyển và vô hiệu hóa (guard BR-QT-08) trả 500 trên app thật tới 21/10 — nợ D016.

10. **Khoảng hở chấp nhận:** principal của người thực hiện đọc một lần ở đầu request (ADR-0003). Bị vô hiệu hóa / đổi chức vụ khi request của chính họ đang chạy thì request đó vẫn hoàn tất với quyền cũ; request kế tiếp nhận 401 (phiên đã hủy) hoặc quyền mới. Cùng mức ADR-0003, ADR-0021. Không khóa dòng `accounts` của người thực hiện.

## Lý do và phương án bị loại

- **Audit 403 bằng `recordIndependently` ngay trong transaction đang giữ khóa:** cần connection thứ hai trong lúc giữ khóa dòng — bẫy kẹt pool đã gặp ở ADR-0019. Loại.
- **Kiểm phân cấp trước khi khóa (đọc không khóa) rồi kiểm lại dưới khóa:** hai chỗ kiểm dễ lệch nhau; vẫn phải kiểm dưới khóa mới đúng. Loại.
- **Chờ BE-2 thêm method khóa chi nhánh:** chặn phần 2, 3 của T14 (12–13/10). Người dùng chọn BE-1 làm, ghi ở 06 §6.
- **Dùng lại `BranchRepository.findByIdForUpdate`:** trả `Branch` cũ nếu persistence context đã có (`BranchQueryApi.findBranch` dùng `findById`) — `BranchStaffLockIT.lockReturnsFreshStatusAfterStaleRead` ghi lại bẫy. Loại.
- **Ném 404 khi id chi nhánh lạ:** đánh dấu rollback-only qua proxy `MANDATORY`; bên gọi không còn lựa chọn 400 BR-QT-03 / 404 mà không rủi ro `UnexpectedRollbackException`. Loại.
- **Audit cả 403 sai vai trò của BR-QT-05, 06** (theo BR-QT-15 "mọi thao tác thuộc module quản trị"): lệch quy ước ADR-0003 (sai role không audit); hợp đồng D.6 chỉ đòi audit cho BR-QT-01, 07. Không chọn.
- **Seed cả 5 mẫu ở V11:** biến của 2 mẫu IN_APP phụ thuộc thiết kế `pendingWork` (phần 5); đoán trước thì phải sửa bằng migration mới. Không chọn.
- **[CFG] mới cho độ dài / hạn dùng mật khẩu tạm:** đặc tả không có; không tự thêm tham số.

## Hệ quả

- Phần 1–5 dùng khuôn ở mục 2–5; mỗi lỗi trong hợp đồng #15–30 thuộc đúng một lớp kiểm ở mục 3 và có một IT qua HTTP.
- Thao tác ghi của module branch trên chi nhánh X chờ khi một luồng T14 đang khóa X (vài câu SQL; không BCrypt nào chạy khi giữ khóa). Không chu trình chờ: branch không khóa `accounts`; mọi luồng đi theo `branches → accounts → {otp_tokens, staff_profiles} → {sessions, customers}`.
- `FOR NO KEY UPDATE` không xung đột `FOR KEY SHARE` của kiểm FK: tạo `staff_profiles`, ghi outbox / mở phiên cho người đang bị khóa dòng không bị chặn. **Ngoại lệ phần 4:** `UPDATE accounts SET email` sửa cột có unique index (`uq_accounts_email`, V1 L50) nên PostgreSQL lấy khóa mạnh `FOR UPDATE`, chờ / chặn các INSERT có FK tới tài khoản đó — ADR phần 4 ghi và có IT.
- Repo không đặt `lock_timeout`: request chờ khóa giữ một connection tới khi bên kia commit (như mọi luồng hiện có).
- `recordIndependently` lỗi thì chỉ log `AUDIT_WRITE_FAILED` (hành vi platform, ADR-0001): 403 vẫn trả nhưng có thể thiếu dòng audit.

## TBD

- Hạn dùng của mật khẩu tạm (đặc tả không nói; BR-TK-17 bắt đổi ở lần đăng nhập đầu).
- Cấp trên sửa hộ **SĐT** nhân viên: mô tả T14 có, hợp đồng identity-v1 không có endpoint (nhân viên tự sửa qua `PATCH /me/staff-profile`). Chốt trước phần 4.
