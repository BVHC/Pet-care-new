# ADR-0031: Đổi chức vụ và điều chuyển nhân viên (T14 phần 2)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-12
- **Liên quan:**
  - identity-v1 #18 `POST /staff/{id}/change-role`, #19 `POST /staff/{id}/transfer` (UC08).
  - BR-QT-03 (02 L46), 04 (L47), 05 (L48), 06 (L49), 08 (L51), 15 (L58); BR-TK-15, 20; BR-TN-05, 06, 08; nguyên tắc 8 (04).
  - ADR-0003 (role / chi nhánh đọc lại mỗi request), ADR-0024 (đếm BRANCH_MANAGER tính cả người bị khóa), ADR-0026 (`accounts → staff_profiles`), ADR-0029 (policy, 403 có audit, thứ tự khóa, `BranchApi`), ADR-0030 (`StaffResponse`, khuôn facade), ADR-0032 (số thực → 400). Nợ D014 (trả bước 2, 4 cho hai luồng này), D016.
- **Triển khai:** `identity/controller/StaffController` (#18, #19, `@PreAuthorize("hasRole('SUPER_MANAGER')")`); `identity/dto/{ChangeRoleRequest, TransferStaffRequest}`; `StaffAdminService.{changeStaffRole, transferStaff}`; `StaffAccountService.{changeStaffRole, transferStaff, lockTarget, checkNotLastBranchManager, checkNoPendingWork}`; `Account.changeStaffRole`, `StaffProfile.{transferTo, clearVetProfile}`; `QtAuditActions.{STAFF_ROLE_CHANGED, STAFF_TRANSFERRED}`. Không migration, không mẫu thông báo. Test: `StaffRoleTransferServiceTest`, `StaffAdminServiceTest`, `StaffAccountEntityTest`, `StaffRoleTransferRequestValidationTest`, `module/identity/StaffRoleTransferIT`, `module/identity/StaffTransferPlaceholderIT`.

## Bối cảnh

Spec chỉ nói ai được làm (BR-QT-05, 06: chỉ SUPER_MANAGER, trong nhóm A05–A08) và khi nào bị chặn (BR-QT-04 quản lý cuối cùng; BR-QT-08 việc dở — chỉ với điều chuyển). Contract #18, #19 không nêu điều kiện trạng thái, kết quả khi gửi trùng giá trị hiện tại, số phận hồ sơ công khai của VET khi đổi chức vụ, hay việc dở khi đổi chức vụ. Người dùng chốt các điểm này ngày 2026-10-12 (Q1–Q4).

## Quyết định

1. **Q1 — mọi trạng thái:** nhân viên `DISABLED` hoặc `is_locked` vẫn được đổi chức vụ / điều chuyển (contract không có 409 trạng thái; BR-QT-12 chỉ cấm vô hiệu hóa người đang bị khóa). BR-QT-04 chỉ đếm BRANCH_MANAGER `ACTIVE`, nên BM `DISABLED` không bị chặn.
2. **Q2 — trùng giá trị hiện tại:** 200 trả `StaffResponse` hiện tại, không ghi, không audit, không gọi BR-QT-03 / 04 / 08. Vẫn kiểm 404 và 403. So sánh **trước** khi gọi method domain nào, nên Hibernate không UPDATE và `updated_at` không đổi.
3. **Q3 — VET rời chức vụ VET:** xóa `specialty`, `bio` (contract: "specialty, bio chỉ VET"; người đó không còn tự sửa được, `PATCH /me/staff-profile` → 403). Giá trị cũ nằm ở `before` của audit. Trở thành VET thì giữ giá trị hiện có. Điều chuyển VET giữ nguyên hồ sơ.
4. **Q4 — đổi chức vụ không chặn việc dở:** spec #18 chỉ có BR-QT-04, 05; không gọi `VisitQueryApi` / `CashierShiftQueryApi`, nên đổi chức vụ chạy được trên app thật trước 21/10. Khoảng hở ghi ở TBD.
5. **Không hủy phiên, không gửi thông báo:** role và chi nhánh đọc lại từ DB mỗi request (ADR-0003), request kế tiếp của người bị tác động dùng quyền mới; spec không yêu cầu thông báo. `last_seen_at` giữ nguyên.
6. **Thứ tự (một transaction, ADR-0029 mục 3, 4):**
   1. `findStaffRow` (projection, không khóa): không có / tài khoản khách → 404 "Không tìm thấy nhân viên #id".
   2. `BranchApi.lockForStaffChange({chi nhánh hiện tại} ∪ {chi nhánh đích nếu #19})` — một lần gọi, branch tự sắp id tăng dần.
   3. `findByIdForUpdate(accounts)`, rồi đọc `staff_profiles` (entity sau khóa).
   4. Policy (403 `STAFF_ACTION_DENIED` có audit, facade ghi sau rollback). A05–A08 không có chi nhánh → `IllegalStateException` (dữ liệu hỏng, 500).
   5. Trùng giá trị (Q2) → 200. Kiểm **trước** 409, nên request trùng / bấm đúp mà người kia đã làm xong trong lúc chờ khóa nhận 200, không phải 409.
   6. Chi nhánh đọc dưới khóa khác lần đọc không khóa → 409 `ConcurrencyConflictException` (đã khóa nhầm chi nhánh, không được đếm BR-QT-04).
   7. #19: chi nhánh đích vắng trong map khóa → 400 BR-QT-03 "Chi nhánh không tồn tại".
   8. BR-QT-04 (#18 trên chi nhánh hiện tại, #19 trên chi nhánh nguồn): người bị tác động là BRANCH_MANAGER `ACTIVE`, chi nhánh (đọc dưới khóa) `ACTIVE`, `countActiveBranchManagers ≤ 1` → 400.
   9. #19: BR-QT-08 **cuối cùng** — hỏi cả hai interface với mọi chức vụ (vì Q4 cho RECEPTIONIST còn ca mở đổi chức vụ), liệt kê đủ: "Không điều chuyển được: còn lượt tiếp nhận #12, #15 chưa hoàn tất; còn ca thu ngân đang mở (BR-QT-08)".
   10. Ghi (`Account.changeStaffRole` [+ `clearVetProfile`] / `StaffProfile.transferTo`) → audit `STAFF_ROLE_CHANGED` (`before`/`after` = `role, specialty, bio`) / `STAFF_TRANSFERRED` (`branchId`) → `flush()` → đọc lại `findStaffDetail` → 200 (`ApiResponse.ok`, message "Đã đổi chức vụ" / "Đã điều chuyển nhân viên").
7. **Request:** `role` là chuỗi + `@Pattern(BRANCH_MANAGER|RECEPTIONIST|VET|CARETAKER)` như `CreateStaffRequest` (mọi giá trị khác → 400 `VALIDATION_FAILED` trường `role`); `branchId` `@NotNull @Positive`. Trường lạ bị bỏ qua (change-role kèm `branchId` không điều chuyển).
8. **Commit / rollback:** mỗi use case một `@Transactional` mặc định, không `noRollbackFor` (không có bộ đếm nào phải sống sót); facade không transaction (ADR-0029 mục 2). Mọi lỗi được ném trước lệnh ghi đầu tiên; lỗi sau ghi (UPDATE, INSERT audit, đọc response) rollback trọn. Không `try/catch` trong transaction.
9. **Nghĩa vụ cho T26 (gán lượt) và BE-2 (mở ca thu ngân):** FK `visits.assignee_id` / `cashier_shifts.cashier_id` chỉ lấy `FOR KEY SHARE` trên `accounts`, không xung đột với `FOR NO KEY UPDATE` của điều chuyển, nên gán lượt / mở ca có thể chen vào sau bước BR-QT-08 mà điều chuyển vẫn commit. Luồng tạo việc dở phải `SELECT … FROM accounts WHERE id = <nhân viên> FOR SHARE` rồi kiểm chi nhánh / chức vụ / trạng thái của nhân viên dưới khóa đó (chờ điều chuyển commit → thấy chi nhánh mới → từ chối). Bên điều chuyển không thể khóa trước dòng chưa tồn tại. Ghi ở `06-module-contracts.md` §4 và D016.

## Lý do và phương án bị loại

- **409 cho DISABLED / bị khóa:** contract không có; kích hoạt lại đã gán được chi nhánh (BR-QT-10) nhưng đổi chức vụ người đang nghỉ là việc quản trị hợp lệ. Người dùng chọn cho mọi trạng thái.
- **400 khi trùng giá trị:** thêm lỗi ngoài contract; bấm đúp sẽ báo lỗi dù kết quả đúng. Không chọn.
- **Giữ specialty / bio khi rời VET:** để lại dữ liệu người đó không tự xóa được, vẫn hiện ở `GET /me` và `StaffResponse`. Không chọn.
- **Áp BR-QT-08 cho đổi chức vụ:** lệch spec #18 và làm đổi chức vụ 500 tới 21/10 (D016). Không chọn.
- **409 khi chi nhánh đổi kể cả lúc đã thành no-op:** đúng nhưng trả lỗi cho bấm đúp. Không chọn.
- **Chỉ hỏi `hasOpenShift` với RECEPTIONIST:** người vừa được đổi khỏi RECEPTIONIST (Q4) có thể còn ca mở. Không chọn.
- **Hủy phiên khi đổi quyền:** không cần (quyền đọc lại mỗi request), buộc người bị tác động đăng nhập lại vô cớ. Không chọn.

## Hệ quả

- Nợ D014: đổi chức vụ, điều chuyển đã khóa `branches` trước khi đếm (IT R4 hai chiều, R5, R6; mutation bỏ khóa → 3 IT đỏ). Còn vô hiệu hóa (phần 3).
- Điều chuyển trả 500 trên app thật tới khi `VisitQueryApi`, `CashierShiftQueryApi` có bản thật (D016); các nhánh 400 / 403 / 404 / no-op và đổi chức vụ chạy được (`StaffTransferPlaceholderIT`).
- Hệ quả đúng rule ở luồng khác: BM chuyển vào chi nhánh DRAFT làm chi nhánh kích hoạt được; VET rời VET biến mất khỏi `/api/public/vets`; VET chuyển sang chi nhánh DRAFT tạm ẩn tới khi chi nhánh kích hoạt; phạm vi xem / gửi lại mật khẩu của BRANCH_MANAGER đi theo chi nhánh mới; BM chuyển đi bị 403 ở API của chi nhánh cũ.
- Tạo BM mới vào chi nhánh X (không khóa X, ADR-0030) chạy chéo luồng làm giảm BM của X: phép đếm có thể chưa thấy BM đang tạo → từ chối nhầm (an toàn).
- Khóa `branches` giữ vài câu SQL (không BCrypt, không gọi mạng): thao tác ghi khác của module branch trên cùng chi nhánh chờ vài ms. Request của chính người bị tác động không chờ (`touchLastSeen` dùng `SKIP LOCKED`).
- FE: người bị đổi chức vụ thấy menu cũ tới khi gọi lại `GET /me`; server vẫn chặn đúng.

## TBD

- **Q4:** đổi chức vụ VET / CARETAKER đang có Visit được gán, hoặc RECEPTIONIST có ca `OPEN`: lượt có thể đang gán cho người không còn đúng chức vụ (BR-TN-05), và BR-TN-08 chỉ cho gán lại khi người phụ trách không còn `ACTIVE`. Cần chốt rule trước T26 (20–21/10).
