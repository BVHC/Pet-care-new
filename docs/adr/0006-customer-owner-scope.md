# ADR-0006: Phạm vi dữ liệu theo role — CUSTOMER không đi qua phạm vi chi nhánh

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-05
- **Supersedes:** ADR-0003 mục 8, phần "ADMIN, SUPER_MANAGER, CUSTOMER trả nguyên giá trị truyền vào".
- **Liên quan:**
  - `docs/01-business-operations.md` A02: "chỉ truy cập được dữ liệu của chính mình".
  - `docs/04-domain-model.md` nguyên tắc 8.
  - `docs/api/00-method.md` §3.3; `docs/api/appointment-v1.md`, `boarding-v1.md` (endpoint dùng chung khách–nhân viên).
  - Convention 02, 04; ADR-0003.
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/security/AccessScope.java`, `SecurityPrincipal.java`, `BranchScope.java`.
  - `BE/src/main/java/com/petcare/module/identity/service/AccountPrincipal.java`.
  - Test: `BranchScopeTest`, `AccountPrincipalTest`, `AuthenticationIT`.

## Bối cảnh

ADR-0003 mục 8 cho `BranchScope.resolve/check` coi CUSTOMER như ADMIN: `resolve(null)` trả `null` (toàn chuỗi), `check(...)` luôn qua.

Contract có nhiều endpoint dùng chung khách và nhân viên, ví dụ "Danh sách lịch hẹn: Khách: lịch của mình · nhân viên: chi nhánh mình" (`appointment-v1.md`), đặt chỗ lưu trú, nhật ký chăm sóc. Nếu service gọi `branchScope.resolve(branchId)` cho mọi role mà quên nhánh lọc theo chủ sở hữu, khách sẽ nhận dữ liệu của mọi chi nhánh. Đây là lỗi fail-open và không có nghiệp vụ nào cần hành vi đó (A02 chỉ thấy dữ liệu của mình).

## Quyết định

1. Enum `platform/security/AccessScope`:
   - `CHAIN`: ADMIN, SUPER_MANAGER.
   - `BRANCH`: A05–A08.
   - `OWNER`: CUSTOMER.
2. `SecurityPrincipal.accessScope()`; `branchScoped()` thành method mặc định (`accessScope() == BRANCH`). `AccountPrincipal` ánh xạ bằng `switch` đầy đủ theo `Role`, không có `default`, nên thêm role mới thì phải chọn phạm vi mới compile được.
3. `BranchScope.resolve/check` với `OWNER` → `AccessDeniedScopeException` (403 `ACCESS_DENIED_SCOPE_MISMATCH`, message chung, `actualScope = "owner:<accountId>"`). `CHAIN` và `BRANCH` giữ nguyên ADR-0003.
4. Dữ liệu của khách lọc theo **chủ sở hữu** (`customer_id` của tài khoản) ở service, không qua `BranchScope`. Với endpoint mà khách chọn chi nhánh làm **đích** (xem khung giờ trống, xem còn chỗ, đặt lịch, đặt lưu trú, gửi feedback), service cũng không gọi `BranchScope` cho khách; chỉ gọi cho nhân viên.

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| Giữ nguyên, chỉ ghi chú "phải kiểm tra chủ sở hữu" | Fail-open: quên một lần là rò dữ liệu toàn chuỗi mà không có lỗi nào báo |
| `IllegalStateException` (500) khi khách gọi | Cũng fail-closed, nhưng nếu lọt lên môi trường thật thì khách gặp 500; 403 khớp ngữ nghĩa "ngoài phạm vi" của convention 04 |
| Thêm cờ boolean `customer()` vào principal | Hai cờ boolean tạo ra tổ hợp vô nghĩa; enum đầy đủ để compiler bắt được role mới |

## Hệ quả

- **Tích cực:** quên nhánh khách → 403 thay vì trả dữ liệu toàn chuỗi. A05–A08 và ADMIN/SUPER_MANAGER không đổi hành vi.
- **Đánh đổi đã chấp nhận:**
  - Service của endpoint dùng chung phải rẽ nhánh theo `accessScope()` trước khi gọi `BranchScope`.
  - Gọi nhầm `BranchScope` cho khách ở endpoint "chi nhánh là đích" sẽ trả 403, tức tính năng bị chặn chứ không bị rò dữ liệu.
- **Ràng buộc cho các task sau:**
  - Mỗi endpoint dùng chung khách–nhân viên phải có IT gọi bằng token khách, kiểm tra khách chỉ thấy dữ liệu của mình và vẫn chọn được chi nhánh đích (convention 02).
