# ADR-0033: Vô hiệu hóa và kích hoạt lại nhân viên (T14 phần 3)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-12
- **Liên quan:**
  - identity-v1 #20 `POST /staff/{id}/disable`, #21 `POST /staff/{id}/reactivate` (UC08); 03 Tài khoản#5 (L48), #6 (L49).
  - BR-QT-02 (02 L45), 03 (L46), 04 (L47), 07 (L50), 08 (L51), 09 (L52), 10 (L53), 12 (L55), 15 (L58); BR-TK-11 (L27), 17 (L33); BR-TN-06 (L146).
  - ADR-0021 (`accounts → sessions`), 0024 (đếm BRANCH_MANAGER), 0029 (policy, 403 audit sau rollback, thứ tự khóa, `BranchApi`, mật khẩu tạm), 0030 (`StaffResponse`, nghĩa vụ `last_seen_at = NULL`), 0031 (khuôn khóa chi nhánh, nghĩa vụ `FOR SHARE`), 0032. Nợ D014 (trả nốt), D016.
- **Triển khai:** `identity/controller/StaffController` (#20, #21, `@PreAuthorize` ADMIN / SUPER_MANAGER / BRANCH_MANAGER); `identity/dto/ReactivateStaffRequest`; `StaffAdminService.{disableStaff, reactivateStaff}`; `StaffAccountService.{disableStaff, reactivateStaff, reactivationBranch}` (+ `checkNoPendingWork(id, action)`); `Account.{disable, reactivate}`; `AccountTransitionHandler.fsmName()`; `QtAuditActions.{STAFF_DISABLED, STAFF_REACTIVATED}`. Không migration, không mẫu thông báo mới (dùng `STAFF_TEMP_PASSWORD` của V11). Test: `StaffDisableReactivateServiceTest`, `StaffAdminServiceTest`, `StaffAccountEntityTest`, `AccountTransitionHandlerTest`, `ReactivateStaffRequestValidationTest`, `module/identity/StaffDisableReactivateIT`, `module/identity/StaffTransferPlaceholderIT`.

## Bối cảnh

Spec nói ai được làm (BR-QT-07, 10), khi nào bị chặn (BR-QT-04, 08, 12, BR-QT-10 "đang LOCKED phải mở khóa trước") và hệ quả (BR-QT-09 hủy phiên, BR-QT-10 mật khẩu tạm + bắt đổi). Contract #20, #21 không nói: gọi khi đã ở trạng thái đích trả gì; BR-QT-08 có áp cho SUPER_MANAGER không (hai interface còn là placeholder, D016); vô hiệu hóa có báo cho nhân viên không. Người dùng chốt ba điểm này ngày 2026-10-12 (Q1–Q3).

## Quyết định

1. **Q1 — sai trạng thái → 409:** vô hiệu hóa người đã `DISABLED`, kích hoạt lại người đang `ACTIVE` → `InvalidStateTransitionException` do `AccountTransitionHandler.validateTransition` ném (03 L8 whitelist, convention 04 L73), sau mọi guard 400 (convention 05 L80). Bấm đúp lần hai nhận 409, không ghi, không gửi thêm mật khẩu.
2. **Q2 — SUPER_MANAGER bỏ qua BR-QT-08:** SUPER_MANAGER không thuộc chi nhánh, không bao giờ được gán lượt (VET / CARETAKER) hay mở ca (RECEPTIONIST), và không đổi chức vụ vào / ra A05–A08 được (BR-QT-05). Không gọi `VisitQueryApi` / `CashierShiftQueryApi` → ADMIN vô hiệu hóa SUPER_MANAGER chạy được trên app thật trước 21/10.
3. **Q3 — không gửi email khi vô hiệu hóa:** BR-QT-09 và Tài khoản#5 chỉ yêu cầu hủy phiên.
4. **BR-QT-04, BR-QT-08 chỉ khi `status = ACTIVE`:** hai guard thuộc cạnh `ACTIVE → DISABLED`; người đã `DISABLED` rơi thẳng xuống 409 mà không hỏi placeholder (nếu không, bấm đúp sẽ 500 thay vì 409 tới 21/10). BR-QT-12 vẫn kiểm cho mọi trạng thái (người `DISABLED` đang bị khóa → 400 BR-QT-12).
5. **Thứ tự #20 (một transaction):**
   1. `findStaffRow` (projection, không khóa) → 404.
   2. `BranchApi.lockForStaffChange({chi nhánh hiện tại})` (SUPER_MANAGER: tập rỗng).
   3. `findByIdForUpdate(accounts)` → đọc `staff_profiles`.
   4. Policy `DISABLE` → 403 có audit (facade ghi sau rollback).
   5. A05–A08 không có chi nhánh → 500 (dữ liệu hỏng).
   6. `is_locked` → 400 BR-QT-12.
   7. Nếu `ACTIVE` và A05–A08:
      - chi nhánh vừa đổi trong lúc chờ khóa → 409;
      - BR-QT-04 (đếm dưới khóa chi nhánh) → 400;
      - BR-QT-08 cuối cùng → 400 liệt kê, placeholder → 500.
   8. `validateTransition(status, DISABLED)` → 409.
   9. Ghi: `Account.disable()` (`status = DISABLED`, `last_seen_at = NULL`) → `revokeAll` → audit `STAFF_DISABLED` (`before` / `after` = `{status}`) → `flush()` → đọc lại → 200 "Đã vô hiệu hóa nhân viên".
6. **Thứ tự #21 (facade sinh mật khẩu tạm + BCrypt trước, rồi một transaction):**
   1. `findByIdForUpdate(accounts)` → 404, đọc `staff_profiles`.
   2. Policy `REACTIVATE` → 403 có audit (BRANCH_MANAGER gửi chi nhánh khác chi nhánh mình cũng 403).
   3. `is_locked` → 400 BR-QT-10.
   4. BR-QT-03 → 400:
      - SUPER_MANAGER kèm `branchId`;
      - chi nhánh mới không tồn tại (`BranchQueryApi.findBranch`).
   5. `validateTransition(status, ACTIVE)` → 409.
   6. Ghi: `Account.reactivate(hash)` (`ACTIVE`, hash mới, `must_change_password = true`, xóa bộ đếm sai + `locked_until`, `last_seen_at = NULL`) → đổi chi nhánh nếu có → `revokeAll` → outbox `STAFF_TEMP_PASSWORD` → audit `STAFF_REACTIVATED` (`before` / `after` = `{status, branchId}`) → `flush()` → đọc lại → 200 "Đã kích hoạt lại nhân viên".
7. **Kích hoạt lại không khóa `branches`:** chỉ làm tăng số BRANCH_MANAGER `ACTIVE` (ADR-0029 mục 5). Chạy chéo với luồng làm giảm thì bên giảm có thể chưa thấy người vừa kích hoạt → từ chối thừa, hướng an toàn. Tầng 1 chi nhánh không bị xóa nên đọc tồn tại không khóa là đủ.
8. **Body #21 không bắt buộc** (`@RequestBody(required = false)`, contract `required=[]`): thiếu body, `{}`, `branchId` null = giữ chi nhánh hiện tại. Trường lạ bị bỏ qua.
9. **Kích hoạt lại cũng `revokeAll`:** mật khẩu cũ hết hiệu lực thì phiên mở bằng nó cũng hủy (thực tế không còn phiên nào vì người `DISABLED` không đăng nhập được). Thứ tự `accounts → sessions` (ADR-0021).
10. **Message 409 không lộ tên lớp:** `AccountTransitionHandler` override `fsmName()` → `"tài khoản"` ("Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED"). Message 409 của xác thực OTP / tạo nhân viên đổi theo; thực tế hai luồng đó guard trước nên không ném.
11. **Commit / rollback:** mỗi use case một `@Transactional` mặc định, không `noRollbackFor`; mọi lỗi ném trước lệnh ghi đầu tiên; `revokeAllByAccount` có `flushAutomatically` nên `UPDATE accounts` chạy trước `UPDATE sessions`; lỗi ở bất kỳ câu ghi nào rollback trọn (IT tiêm lỗi audit / sessions / outbox / staff_profiles).

## Lý do và phương án bị loại

- **200 no-op khi đã ở trạng thái đích (như ADR-0031 Q2):** ADR-0031 áp cho thuộc tính (chức vụ, chi nhánh); ở đây là cạnh FSM không có trong bảng 03 → whitelist buộc từ chối. Không chọn.
- **Luôn hỏi BR-QT-08 cả với SUPER_MANAGER:** đồng nhất với điều chuyển nhưng mọi lần vô hiệu hóa 500 tới 21/10, trong khi SUPER_MANAGER không thể có việc dở. Không chọn.
- **Email báo vô hiệu hóa:** ngoài spec, cần mẫu mới + migration chờ BE-2 xác nhận số V. Không chọn.
- **Kiểm BR-QT-08 cả khi đã `DISABLED`:** người `DISABLED` không nhận việc mới (nghĩa vụ ADR-0031 §9), và bấm đúp sẽ 500 tới 21/10. Không chọn.
- **Khóa `branches` khi kích hoạt lại:** không cần cho BR-QT-04 (luồng tăng), thêm một cạnh khóa không cần thiết. Không chọn.

## Hệ quả

- **Nợ D014 trả xong:** mọi luồng làm giảm số BRANCH_MANAGER `ACTIVE` (đổi chức vụ, điều chuyển, vô hiệu hóa) khóa chi nhánh trước khi đếm. IT chạy đồng thời với `activateBranch` theo cả hai thứ tự, và với một vô hiệu hóa / đổi chức vụ / điều chuyển khác. Mutation bỏ khóa chi nhánh trong #20 → 5/5 IT đó đỏ (2026-10-12).
- **Vô hiệu hóa A05–A08 trả 500 trên app thật** tới khi `VisitQueryApi`, `CashierShiftQueryApi` có bản thật (D016). SUPER_MANAGER, các nhánh lỗi trước BR-QT-08, 409 và kích hoạt lại chạy được (`StaffTransferPlaceholderIT`).
- **Nghĩa vụ ADR-0031 §9 nay áp cho cả vô hiệu hóa:** gán lượt (T26) / mở ca (BE-2) phải `SELECT … FROM accounts WHERE id = <nhân viên> FOR SHARE` rồi kiểm lại `status` / chi nhánh / chức vụ dưới khóa đó, vì INSERT FK chỉ lấy `FOR KEY SHARE`, không xung đột với khóa của #20.
- **Hệ quả đúng rule ở luồng khác:**
  - BRANCH_MANAGER bị vô hiệu hóa không còn được đếm khi kích hoạt chi nhánh; kích hoạt lại thì được đếm lại.
  - VET bị vô hiệu hóa biến mất khỏi `/api/public/vets` (BR-TK-20); kích hoạt lại thì hiện lại nếu chi nhánh `ACTIVE`.
  - Người bị vô hiệu hóa: đăng nhập 400 BR-TK-11, token cũ 401, quên mật khẩu không gửi mã (BR-TK-12), gửi lại mật khẩu ban đầu 409.
  - Sau khi kích hoạt lại thì gửi lại mật khẩu ban đầu được (vẫn `must_change_password`).
- **Request đang chạy của chính người bị vô hiệu hóa** vẫn chạy xong với quyền cũ; request kế tiếp 401 (khoảng hở ADR-0029 §10).

## TBD

- Khi `VisitQueryApi` / `CashierShiftQueryApi` có bản thật: đổi ca 500 trong `StaffTransferPlaceholderIT` thành ca thành công (D016 *Cách trả*), và xác nhận hai query là đọc thuần, không khóa.
