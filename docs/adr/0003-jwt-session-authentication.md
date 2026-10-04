# ADR-0003: Xác thực bằng JWT HS256 gắn phiên DB, RBAC 7 role và phạm vi chi nhánh

- **Trạng thái:** Accepted — mục 4, 9 superseded by ADR-0005; mục 8 superseded by ADR-0006
- **Ngày:** 2026-10-04
- **Liên quan:**
  - `docs/02-business-rules.md`: BR-TK-09, 11, 13, 14, 17; BR-QT-01, 03, 05, 06, 07, 11, 12, 13; BR-TN-06; BR-DG-03; BR-BC-01.
  - `docs/03-state-machines.md` #1 Tài khoản.
  - `docs/04-domain-model.md` nguyên tắc 2, 8.
  - `docs/05-erd.md` bảng `accounts`, `staff_profiles`, `sessions`.
  - `docs/api/00-method.md` §3.1, §3.3, §3.5; `docs/api/identity-v1.md` §D, A1, A4, Q1.
  - Convention 02 (phạm vi chi nhánh), 04.
  - ADR-0001 (actor của audit), ADR-0002 (IP client), ADR-0004 (đọc [CFG]).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/security/`: `SecurityConfig`, `JwtProperties`, `JwtTokenService`, `TokenClaims`, `JwtAuthenticationFilter`, `SecurityPrincipal`, `SessionAuthenticator`, `BranchScope`.
  - `BE/src/main/java/com/petcare/module/identity/`: entity `Account`, `StaffProfile`, `Session`; `SessionRepository`, `AccountRepository`; `AccountPrincipal`, `SessionAuthenticationService`, `SessionService`, `MustChangePasswordInterceptor`, `IdentityWebConfig`.
  - Test: `JwtTokenServiceTest`, `JwtAuthenticationFilterTest`, `BranchScopeTest`, `SecurityConfigTest`, `AccountPrincipalTest`, `SessionAuthenticationServiceTest`, `SessionServiceTest`, `MustChangePasswordInterceptorTest`, `AuthenticationIT`.

## Bối cảnh

Trước ADR này `SecurityConfig` chỉ là bản tạm, chặn mọi endpoint bằng 401. Mọi module nghiệp vụ cần biết ai đang gọi, người đó có role gì và thuộc chi nhánh nào. Đặc tả đặt ra các ràng buộc sau:

- **Hủy phiên có hiệu lực ngay ở request kế tiếp** (identity-v1 §D.2):
  - Khóa, vô hiệu hóa → hủy mọi phiên (BR-TK-11, BR-QT-09).
  - Đặt lại mật khẩu → hủy mọi phiên (BR-TK-13).
  - Đổi mật khẩu → hủy "mọi phiên **khác**" (BR-TK-14). Như vậy một tài khoản được có nhiều phiên cùng lúc.
- **Khóa "có hiệu lực ngay"** (BR-QT-11). Khóa là cờ `is_locked` độc lập với `status` (BR-QT-12, 03 #1).
- **Bảng `sessions`** (erd §1) chỉ lưu `token_hash`, không lưu token gốc; có `expires_at`, `revoked_at`. Contract A1: không có refresh token. Thời hạn phiên là TBD Q1.
- **Đổi chức vụ, điều chuyển** (BR-QT-05, 06) **không** nói hủy phiên, nhưng quyền mới phải đúng ngay.
- **Phạm vi chi nhánh:** A05–A08 chỉ làm việc trên chi nhánh của mình (04 nguyên tắc 8). Tham số `branchId` chỉ có tác dụng với SUPER_MANAGER/ADMIN; truyền chi nhánh khác thì 403 (`00-method` §3.3). Customer, Pet, danh mục và nhà cung cấp dùng chung toàn chuỗi.
- **BR-TK-17:** chặn mọi chức năng khác cho tới khi đổi mật khẩu lần đầu (contract A4).
- **BR-TN-06:** nhân viên được coi là online nếu có thao tác trong 10 phút [CFG].
- **Path public** theo contract §3.1: `/auth/register…`, `/auth/login`, `/auth/password/…`, `/public/…`.

## Quyết định

1. **Token:** JWT ký HS256 bằng `JWT_SECRET`. Secret ngắn hơn 32 byte UTF-8 thì app dừng lúc khởi động (`JwtProperties`).
   - Claims chỉ dùng để định danh: `iss = petcare-api`, `sub = accounts.id`, `sid = sessions.id`, `jti` (128 bit ngẫu nhiên, base64url), `iat`, `exp = sessions.expires_at`.
   - Không có role hay chi nhánh trong token; không có refresh token.
   - Khi parse: verify chữ ký, `iss`, `alg == HS256`, `exp > now(Clock)`. Thiếu `sub`, `sid` hoặc `jti` thì từ chối.
2. **Bảng `sessions` giữ nguyên V1:**
   - `token_hash = SHA-256 hex(jti)`, được INSERT trước, sau đó mới ký token với `sid` vừa có.
   - `ip_address = getRemoteAddr()` (ADR-0002); `user_agent` cắt còn 500 ký tự.
   - Hủy phiên chỉ đặt `revoked_at`, không bao giờ xóa (04 nguyên tắc 2).
3. **Hạn phiên:** tuyệt đối, bằng thời điểm đăng nhập cộng `session.ttl_hours` [CFG] (mặc định 12, khoảng 1–72; ADR-0004).
   - Giá trị chốt vào `expires_at` lúc tạo phiên (BR-QT-13) và làm tròn xuống giây cho khớp `exp`.
   - Không gia hạn. Hết hạn thì đăng nhập lại.
4. **Kiểm tra ở mỗi request:** `JwtAuthenticationFilter` (đặt trước `AnonymousAuthenticationFilter`) parse token, rồi gọi `SessionAuthenticator` (identity). Bước này chạy **một query** JOIN `sessions`, `accounts` và LEFT JOIN `staff_profiles` theo `sid`. Phiên hợp lệ khi đủ tất cả điều kiện:
   - phiên tồn tại;
   - `account_id = sub`;
   - `token_hash` khớp SHA-256(`jti`), so sánh hằng thời gian;
   - `revoked_at IS NULL`;
   - `expires_at > now`;
   - `status = ACTIVE`;
   - `is_locked = false`.

   Không xét `locked_until`, vì BR-TK-09 chỉ chặn **đăng nhập**.

   Kết quả:
   - **Hợp lệ:** `SecurityContext` nhận `AccountPrincipal` (implement `SecurityPrincipal extends AuditPrincipal`), authority `ROLE_<role>`.
   - **Không hợp lệ:** request ẩn danh, quy tắc phân quyền trả 401 với thông điệp chung.
   - **Lỗi hạ tầng (DB):** chuyển cho `GlobalExceptionHandler` qua `HandlerExceptionResolver`, trả 500 envelope.
5. **Role, `branchId`, `mustChangePassword` đọc từ DB ở mỗi request** (cùng query với bước 4). Đổi chức vụ hay điều chuyển có hiệu lực ở request kế tiếp mà không phải hủy phiên.
6. **RBAC:**
   - `@EnableMethodSecurity`; controller dùng `@PreAuthorize("hasRole('…')")`.
   - Public: `/api/auth/register`, `/api/auth/register/**`, `/api/auth/login`, `/api/auth/password/**`, `/api/public/**`, cùng health, info và API docs. Mọi path khác phải đăng nhập (`/api/auth/logout` cũng vậy).
   - 403 do sai role (`@PreAuthorize`) **không** ghi audit ở platform. Audit 403 của BR-QT-01/07 do service QT ghi bằng `recordIndependently` khi người gọi thuộc phân cấp A03–A05 nhưng vượt quyền (convention 08 §8.3).
7. **Tiền tố `/api`:** mỗi controller nghiệp vụ tự ghi trong `@RequestMapping("/api/…")`. Actuator và swagger giữ path cũ.
8. **Phạm vi chi nhánh:** `BranchScope` (platform) do service **tự gọi** cho dữ liệu có `branch_id`; không có filter hay AOP tự áp.
   - `resolve(requestedBranchId)`:
     - A05–A08 trả chi nhánh của mình; nếu truyền chi nhánh khác thì `AccessDeniedScopeException` (403).
     - ADMIN, SUPER_MANAGER, CUSTOMER trả nguyên giá trị truyền vào (`null` = toàn chuỗi).
   - `check(resourceBranchId)`: A05–A08 chỉ qua với bản ghi cùng chi nhánh. Bản ghi không gắn chi nhánh (`null`) cũng bị 403, ví dụ BR-DG-03.
   - A05–A08 thiếu `branch_id` là dữ liệu sai (BR-QT-03) → `IllegalStateException`, không bao giờ coi là toàn chuỗi.
   - `current()` trả principal để service lấy `accountId` làm actor.
9. **BR-TK-17:** `MustChangePasswordInterceptor` (identity) gắn cho `/api/**`.
   - Khi cờ bật, chỉ cho qua `GET /api/me`, `POST /api/me/password`, `POST /api/auth/logout` và `/api/public/**`.
   - Trang công khai được miễn vì người chưa đăng nhập cũng xem được; sửa A4 của contract cho khớp.
   - Path còn lại trả 400 `BUSINESS_RULE_VIOLATION … (BR-TK-17)`.
10. **BR-TN-06:** sau khi xác thực **nhân viên**, nếu `last_seen_at` cũ hơn 60 giây thì `UPDATE accounts SET last_seen_at … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)`.
    - Request không bao giờ phải chờ khóa dòng `accounts`.
    - Câu UPDATE không đổi `updated_at`.
    - Khách không được cập nhật.
11. **`SessionService`** (identity): `open`, `revoke`, `revokeAll`, `revokeOthers`. Mọi hàm `@Transactional(MANDATORY)` vì luôn nằm trong use case gọi tới (đăng nhập, đăng xuất, đổi/đặt lại mật khẩu, khóa, vô hiệu hóa).

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| Opaque token (chuỗi ngẫu nhiên, tra theo `token_hash`) | Cũng đúng nghiệp vụ, nhưng nhóm chọn JWT để client đọc được `exp` và giữ `jjwt` đã có. JWT không giảm số query vì vẫn phải tra phiên mỗi request |
| JWT không trạng thái + blacklist Redis | Không bảo đảm "request kế tiếp" khi Redis lỗi hoặc trễ; thêm phụ thuộc runtime cho một bảng đã có trong DB |
| Đưa role, chi nhánh vào claims | Claim cũ khi đổi chức vụ hoặc điều chuyển; muốn đúng thì phải hủy phiên ở BR-QT-05/06, là hệ quả đặc tả không có |
| Phiên trượt (gia hạn mỗi request) | Ghi DB mỗi request; đặc tả không yêu cầu |
| TTL trong `application.yml` | ADMIN không đổi được; nhóm chọn để là [CFG] |
| `addPathPrefix("/api")` tập trung; `server.servlet.context-path=/api` | Nhóm chọn để controller tự ghi `/api`. Context-path còn kéo actuator/swagger vào `/api` và phải sửa healthcheck |
| Ghi audit mọi 403 ở `RestAccessDeniedHandler` | BR-QT-15 không yêu cầu audit mọi 403; mỗi 403 tốn thêm một connection (`REQUIRES_NEW`). Phần BR-QT-01/07 bắt buộc thì QT ghi đúng chỗ |
| Kiểm tra `locked_until` ở mỗi request | BR-TK-09 chỉ chặn đăng nhập; chặn thêm phiên đang có là phát minh |
| Filter / AOP tự áp phạm vi chi nhánh | Dữ liệu toàn chuỗi (Customer, Pet, danh mục) sẽ bị chặn nhầm; mỗi truy vấn cần biết cột chi nhánh của nó |

## Hệ quả

- **Tích cực:**
  - Hủy phiên, khóa, vô hiệu hóa, đổi role và điều chuyển đều có hiệu lực ở request kế tiếp.
  - Lộ `JWT_SECRET` vẫn không giả được token cho một phiên đang có, vì còn phải biết `jti`.
  - Platform không import module; `AuditRecorder` lấy được actor thật.
- **Đánh đổi đã chấp nhận:**
  - Một query JOIN mỗi request có token; nhân viên có thêm tối đa một UPDATE mỗi 60 giây.
  - Request đang chạy song song với lúc hủy phiên có thể vẫn hoàn tất (READ COMMITTED). Đặc tả chỉ yêu cầu "request kế tiếp".
  - Trạng thái online trễ tối đa 60 giây, và có thể trễ hơn khi dòng `accounts` đang bị khóa (`SKIP LOCKED`). BR-TN-06 coi online chỉ là gợi ý. Nếu ADMIN đặt `staff.online_window_minutes = 1` thì sai số bằng đúng cửa sổ.
  - Phiên không bao giờ bị xóa nên bảng `sessions` chỉ tăng. Dọn dữ liệu cần ADR mới (04 nguyên tắc 2).
  - FE (`axios.ts`) còn luồng refresh tới `/api/auth/refresh`, endpoint không tồn tại (A1). Sửa khi nối FE.
- **Ràng buộc cho các task sau:**
  - **Đăng nhập, đăng xuất, mật khẩu (08/10):**
    - Đăng nhập dùng `SessionService.open`, và tự kiểm tra PENDING (BR-TK-08), `locked_until` (BR-TK-09), `is_locked`/DISABLED (BR-TK-11).
    - Đăng xuất gọi `revoke` và đặt `last_seen_at = NULL` (BR-TN-06 "offline ngay").
    - Đổi mật khẩu gọi `revokeOthers`; đặt lại mật khẩu gọi `revokeAll`.
  - **Quản trị (12/10):**
    - Khóa và vô hiệu hóa gọi `revokeAll` (BR-TK-11, BR-QT-09).
    - Endpoint `/staff`, `/accounts` để `@PreAuthorize` cho A03–A05; service tự kiểm tra phân cấp và `recordIndependently` khi từ chối (BR-QT-01, 07).
  - **Mọi module:** controller ghi `/api/…`; dữ liệu có `branch_id` thì gọi `BranchScope`; actor lấy từ `BranchScope.current().accountId()`.
  - **BR-TK-19:** chặn chức năng khi còn cờ `link_decision_pending` nằm ở module customer, chưa làm ở đây.
