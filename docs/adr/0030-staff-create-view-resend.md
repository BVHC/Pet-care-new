# ADR-0030: Tạo, xem nhân viên và gửi lại mật khẩu ban đầu (T14 phần 1)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-11
- **Liên quan:**
  - identity-v1 #15 `GET /staff`, #16 `POST /staff`, #17 `GET /staff/{id}`, #22 `POST /staff/{id}/resend-initial-password` (UC08).
  - BR-QT-01, 02, 03, 07, 15; BR-TK-01, 13, 17; BR-TN-06; 03 Tài khoản#4.
  - ADR-0019 (BCrypt ngoài transaction, audit sau rollback), ADR-0021 (`accounts → sessions`), ADR-0024 (định nghĩa online), ADR-0026 (đọc hồ sơ sau khóa `accounts`), ADR-0029 (nền T14: policy, 403 có audit, thứ tự kiểm, thứ tự khóa, mật khẩu tạm, V11).
- **Triển khai:** `identity/controller/StaffController`; `identity/service/{StaffAdminService, StaffAccountService, StaffQueryService, StaffPresence}`; `identity/dto/{CreateStaffRequest, StaffResponse}`; `identity/repository/StaffDetailRow`, `StaffProfileRepository.{findStaffDetail, findStaffPage}`; `identity/mapper/StaffAdminMapper`; `Account.{createStaff, reissueInitialPassword}`, `StaffProfile.create`; `QtAuditActions.{STAFF_CREATED, STAFF_INITIAL_PASSWORD_RESENT}`. Không migration (V11 của ADR-0029 đã seed `STAFF_TEMP_PASSWORD`).

## Bối cảnh

Trước phần này chưa có cách tạo nhân viên ngoài SQL. Hợp đồng identity-v1 để ngỏ ba điểm: điều kiện và hệ quả của "gửi lại mật khẩu ban đầu" (chỉ ghi 409 khi đã đổi mật khẩu lần đầu, "mã cũ hết hiệu lực"); request tạo SUPER_MANAGER có `branchId`; filter danh sách nằm ngoài phạm vi xem. Người dùng chốt ba điểm này ngày 2026-10-11.

## Quyết định

1. **Khuôn transaction (theo ADR-0029 mục 2):** facade `StaffAdminService` không `@Transactional` sinh mật khẩu tạm và BCrypt (`TemporaryPasswordGenerator.prepare`), gọi `StaffAccountService` (một transaction trọn use case), bắt `StaffHierarchyDeniedException` sau khi transaction đã rollback, ghi `recordIndependently(STAFF_ACTION_DENIED)` rồi ném lại đúng instance đó. Không `try/catch` nào bên trong transaction; không `noRollbackFor` (không có bộ đếm nào phải commit khi lỗi).

2. **#16 tạo nhân viên**, thứ tự kiểm: Bean Validation → `@PreAuthorize` (ADMIN, SUPER_MANAGER, BRANCH_MANAGER) → policy CREATE (403 có audit) → BR-QT-03 → BR-TK-01 → `validateInitial(ACTIVE)` → INSERT `accounts` (`ACTIVE`, `must_change_password = true`, có SĐT) → INSERT `staff_profiles` → outbox `STAFF_TEMP_PASSWORD` `{ho_ten, email_dang_nhap, mat_khau_tam}` → audit `STAFF_CREATED` → `flush()` → đọc lại projection.
   - **BR-QT-03:** role `SUPER_MANAGER` có `branchId` → 400 (người dùng chốt: không âm thầm bỏ dữ liệu client gửi); A05–A08 không có chi nhánh hiệu lực → 400; chi nhánh không tồn tại → 400. BRANCH_MANAGER để trống `branchId` = chi nhánh của mình (00-method §3.3). Tầng 1 chi nhánh chỉ có DRAFT / ACTIVE và không bị xóa nên không khóa `branches`; tạo chỉ làm **tăng** số BRANCH_MANAGER (ADR-0029 mục 5).
   - **`role`** của request là chuỗi + `@Pattern` 5 chức vụ: `ADMIN`, `CUSTOMER`, giá trị lạ đều 400 `VALIDATION_FAILED` với tên trường `role` (enum + `@AssertTrue` sẽ báo tên trường sai; enum riêng sẽ trả `MALFORMED_REQUEST` không chỉ trường).
   - **Audit `STAFF_CREATED.after`** = `email, phone, fullName, role, branchId, status`. Không có khóa `mustChangePassword`: `AuditRecorder` tách camelCase và từ chối từ `password` → mọi lần tạo sẽ 500.
   - Hai request trùng email song song → `uq_accounts_email` → 409 chung.

3. **#22 gửi lại mật khẩu ban đầu** (người dùng chốt bản chặt): `findByIdForUpdate(accounts)` là lệnh đọc đầu tiên → không có / là khách → 404 → đọc `staff_profiles` sau khóa → policy RESEND_INITIAL_PASSWORD (403 có audit; chính mình luôn bị từ chối) → `status ≠ ACTIVE` → 409 → `must_change_password = false` → 409 → `Account.reissueInitialPassword` (hash mới; xóa bộ đếm sai, `locked_until`, `last_seen_at` như BR-TK-13) → `SessionService.revokeAll` → outbox `STAFF_TEMP_PASSWORD` → audit `STAFF_INITIAL_PASSWORD_RESENT` (chỉ `entity`, không snapshot). `is_locked` vẫn được gửi. 204.
   - Đăng nhập bằng mật khẩu cũ chạy chéo: nếu đăng nhập khóa trước, phiên nó mở bị `revokeAll` hủy; nếu sau, so lại hash dưới khóa → 401 (ADR-0019 mục 4).

4. **Phạm vi xem #15, #17** (không qua policy, 403 không audit — ADR-0029 mục 1): ADMIN → SUPER_MANAGER; SUPER_MANAGER → A05–A08; BRANCH_MANAGER → A06–A08 chi nhánh mình.
   - Filter `role` ngoài phạm vi → trang rỗng, không chạy query (người dùng chốt). BRANCH_MANAGER gửi `branchId` khác → 403 (`BranchScope.resolve`). ADMIN gửi `branchId` → rỗng (SUPER_MANAGER không có chi nhánh). `status` vắng = `ACTIVE` + `DISABLED`.
   - #17: không có hoặc là tài khoản khách → 404 cùng message "Không tìm thấy nhân viên #id"; có nhưng ngoài phạm vi → 403.
   - `page` ≥ 0, `size` 1–100, kẹp như catalog; `q` tìm họ tên hoặc email, không phân biệt hoa thường; `!` `%` `_` trong chuỗi tìm được escape bằng `!` (`ESCAPE '!'`). Không dùng gạch ngược làm ký tự escape: lexer HQL của Hibernate 6 coi gạch ngược + nháy đơn trong chuỗi là escape sequence.

5. **`StaffResponse`** đúng 12 trường của contract, một đường map duy nhất (projection `StaffDetailRow` → `StaffAdminMapper`, `unmappedTargetPolicy = ERROR`); #16 đọc lại projection nên response tạo ≡ `GET /staff/{id}`. `phone` từ `accounts`; `isLocked` = chỉ `is_locked` (không tính `locked_until`, như `AccountSummary`); `online` = `last_seen_at` trong `staff.online_window_minutes` [CFG], đúng mốc vẫn online. Projection không SELECT `password_hash`, `locked_reason`, `locked_until`, bộ đếm sai. Không có `avatarUrl` (contract không có).

6. **`StaffPresence`** cùng công thức online với `StaffDirectoryService` (ADR-0024) nhưng là lớp riêng: refactor `StaffDirectoryService` sẽ phải sửa `StaffDirectoryServiceTest` (gọi constructor trực tiếp). `StaffPresenceParityTest` giữ hai bên khớp nhau.

## Lý do và phương án bị loại

- **#22 tối thiểu (chỉ thay hash, mọi trạng thái):** mật khẩu tạm gửi cho tài khoản `DISABLED` vô dụng (kích hoạt lại đã tự cấp mật khẩu mới), và phiên mở bằng mật khẩu cũ (có thể bị lộ) vẫn sống. Không chọn.
- **#22 không hủy phiên:** phiên cũ vẫn bị BR-TK-17 chặn gần hết, nhưng "mã cũ hết hiệu lực" của contract nghĩa là thông tin đăng nhập cũ không còn tác dụng; hủy phiên tốn một câu UPDATE theo thứ tự khóa sẵn có. Không chọn.
- **Bỏ qua `branchId` khi tạo SUPER_MANAGER:** âm thầm bỏ dữ liệu client gửi. Không chọn.
- **Filter ngoài phạm vi → 403:** `role` là bộ lọc, giao với tập được thấy là đủ; 403 bắt FE ẩn từng lựa chọn. Không chọn (riêng `branchId` của A05–A08 vẫn 403 theo 00-method §3.3).
- **Kiểm phân cấp #16 ở facade trước khi BCrypt** (policy CREATE thuần, không cần DB): tiết kiệm một BCrypt cho request bị từ chối, nhưng tách khỏi khuôn bắt buộc của ADR-0029 mục 2 dùng chung cho phần 1–5. Không chọn.
- **Khóa `branches` khi tạo BRANCH_MANAGER:** tạo chỉ làm tăng số quản lý; INSERT `staff_profiles` chỉ lấy `FOR KEY SHARE` trên chi nhánh, không chặn `activateBranch` / `lockForStaffChange` (`FOR NO KEY UPDATE`). Không cần.

## Hệ quả

- Tạo nhân viên làm thay đổi kết quả của các luồng có sẵn **theo đúng rule**: chi nhánh DRAFT có BRANCH_MANAGER vừa tạo kích hoạt được (BR-QT-04, BR-CN-01); VET vừa tạo ở chi nhánh ACTIVE **hiện ngay** ở `GET /api/public/vets` dù chưa đăng nhập lần nào (BR-TK-20 chỉ xét `ACTIVE`); nhân viên mới có trong `StaffDirectoryApi.findAssignableStaff` / `findActiveSuperManagerIds`. Muốn ẩn VET chưa đăng nhập thì phải sửa rule trước.
- **Nghĩa vụ cho phần 3 (vô hiệu hóa) và phần 5 (khóa):** cùng hủy mọi phiên nên phải đặt `last_seen_at = NULL` như đăng xuất / đặt lại mật khẩu (BR-TN-06), nếu không `online` còn hiện tối đa `staff.online_window_minutes`.
- **Gửi lại × nhân viên đổi mật khẩu chạy chéo:** gửi lại commit trước → lần đổi mật khẩu so lại hash dưới khóa (ADR-0022) → 400 BR-TK-14 và tính một lần sai; nhân viên đăng nhập lại bằng mật khẩu mới. Đổi mật khẩu commit trước → gửi lại 409. Chấp nhận.
- Bấm gửi lại nhiều lần: mỗi lần một mật khẩu, một email, một dòng audit; chỉ mật khẩu cuối dùng được (mẫu V11 đã nói rõ). Không giới hạn tần suất (chỉ cấp quản lý gọi được, có audit).
- Lỗi mạng sau khi #16 commit: gửi lại cùng request nhận 400 BR-TK-01; FE đọc `GET /staff?q=<email>` để biết kết quả.
- #15 chạy hai câu (trang + đếm) dưới READ COMMITTED: `totalElements` có thể lệch nếu có ghi xen giữa.
- Nếu sau này có UC13 (đóng chi nhánh) thì #16 phải khóa `branches` khi kiểm BR-QT-03.

## TBD

- Hạn dùng của mật khẩu tạm (giữ từ ADR-0029).
