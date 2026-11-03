# ADR-0091: Access token 10 phút + refresh token xoay vòng; trạng thái online theo phiên (trả D024)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-02
- **Liên quan:** BR-TN-06, BR-TK-11, 13, 14, BR-QT-13; ADR-0003 (**thay** mục 1 hạn `exp`, mục 3 và "không refresh token"), ADR-0021 (thứ tự khóa; bỏ `clearLastSeen`, `touchLastSeen`), ADR-0008 (dọn phiên), ADR-0024 / 0030 (online). Nợ: D024 (đóng), D054, D055 (mở).
- **Đánh số:** `origin/dev` đã có ADR 0088–0090 của BE-2 nên ADR này lấy 0091 (chưa kiểm số V: V27 chưa ai dùng trên `origin/dev` / `origin/dung` lúc viết, **thống nhất với BE-2 trước khi merge**).
- **Triển khai:** `V27__refresh_tokens_access_ttl.sql`; `identity/entity/{RefreshToken, Session}`, `repository/{RefreshTokenRepository, SessionRepository}`, `service/{SessionService, RefreshTokenService, SessionCleanupService, SessionAuthenticationService, StaffPresence}`, `AuthController` (`POST /auth/refresh`, identity-v1 #39), `exception/InvalidRefreshTokenException`; test `RefreshTokenIT`, `RefreshTokenServiceTest`.

## Bối cảnh

D024: mọi request có token của nhân viên đều ghi `accounts.last_seen_at`, kể cả poll tự động của FE (hàng đợi 10 s, đơn chờ thu 15 s, chuông 60 s), nên nhân viên để tab mở vẫn "online" mãi (BR-TN-06). Token sống bằng cả phiên (12 giờ) và không có cách gia hạn nên token không nói được người còn ngồi máy hay không. Danh sách path "không tính là thao tác" bị loại: path như `GET /api/visits`, `GET /api/orders` vừa do poll vừa do người gọi, và phải bảo trì danh sách.

## Quyết định

1. **Access token sống ngắn.** `session.access_ttl_minutes` [CFG], mặc định 10 (khoảng 5–720); thay `staff.online_window_minutes` (cùng ý nghĩa). `exp` của JWT = `min(now + TTL, sessions.expires_at)`. Hạn phiên `session.ttl_hours` (12 giờ, ADR-0003) vẫn là **trần tuyệt đối**.
2. **Bảng `refresh_tokens`** (PART của `sessions`): `token_hash` (SHA-256 của chuỗi 256 bit, UNIQUE), `used_at` (NULL = còn dùng), `expires_at` (= hạn phiên, chốt lúc tạo, BR-QT-13). Index `(session_id)` và UNIQUE partial `(session_id) WHERE used_at IS NULL` (đúng một token còn dùng mỗi phiên). `sessions.access_expires_at` mới; `accounts.last_seen_at` **bỏ**.
3. **Đăng nhập** (`SessionService.open`, nơi duy nhất mở phiên) cấp access token + refresh token đầu; `LoginResponse` thêm `refreshToken`, `accessTokenExpiresAt` (cộng thêm).
4. **`POST /api/auth/refresh`** (public, xác thực bằng refresh token; bearer gửi kèm bị bỏ qua — ADR-0005). Một transaction, `noRollbackFor = InvalidRefreshTokenException`: khóa dòng token theo băm (`FOR NO KEY UPDATE`) → `now` → đọc phiên + tài khoản (`findAuthView`) → guard → ghi: `UPDATE sessions` (jti mới + hạn access mới, phải trúng 1 dòng, **trước** mọi ghi khác) → token cũ `used_at` → INSERT token mới → ký. Mọi từ chối là 401 chung, **trước** mọi lệnh ghi. Token đã dùng bị đưa lại ⇒ hủy phiên + audit `REFRESH_TOKEN_REUSED` rồi 401 (hai ghi này commit nhờ `noRollbackFor`, cùng khuôn `InvalidCredentialsException`). Hai refresh song song cùng token: lượt sau thấy `used_at` nên bị coi là dùng lại (nghiêm ngặt, không có cửa sổ ân hạn).
5. **Mỗi lần refresh đổi `jti`/`token_hash`** (chỉ lưu băm nên không ký lại được `jti` cũ): access token cũ chết ngay; request đang bay với token cũ nhận 401, client gửi lại bằng token mới.
6. **Online mới** (BR-TN-06): có phiên chưa hủy với `access_expires_at > now`. `StaffProfileRepository` trả `MAX(access_expires_at)` của phiên chưa hủy (subquery dùng `ix_sessions_active_account`); `StaffPresence`, `StaffDirectoryService`: `onlineUntil > now` (đúng mốc hạn là hết hạn, như `JwtTokenService.parse`). Xác thực (`SessionAuthenticationService`) **chỉ đọc**; bỏ `touchLastSeen`, `clearLastSeen`, 5 chỗ `lastSeenAt = null`. Hủy phiên (đăng xuất, khóa, vô hiệu hóa, đặt lại mật khẩu) tự làm offline.
7. **Thứ tự khóa:** `refresh_tokens → sessions`, không bao giờ `accounts`, nên không vòng với `accounts → sessions` (ADR-0021). Đăng xuất chỉ chạm `sessions`. Khóa tài khoản hủy phiên cùng transaction nên một refresh chạy chéo chỉ cấp được token mà request kế tiếp bị `SessionAuthenticationService` từ chối.
8. **Dọn phiên** (ADR-0008): `refresh_tokens.session_id` là `RESTRICT` nên `SessionCleanupService` khóa lô phiên (`SKIP LOCKED`) → xóa refresh token của chúng → xóa phiên. Refresh không chạm `sessions` khi phiên đã hết hạn nên không có vòng chờ với cleanup. Dọn token đã dùng của phiên còn lưu: **D054**.
9. **Test:** `session.access_ttl_minutes` baseline **720** trong profile test (`db/test-migration/V9000`, thêm vào `spring.flyway.locations` của `application-test.yml`) để các IT dịch `MutableClock` (hơn 70 lớp mở phiên) không đổi hành vi; `RefreshTokenIT` đặt 10 rồi trả lại 720.

## Lý do và phương án bị loại

- Danh sách path thụ động (BE-only): không đoán được request nào do người gọi, phải bảo trì, và mất lần xem của người trên `GET /api/visits` / `/api/orders`.
- Header đánh dấu request tự động: cần FE gắn cho mọi poll.
- Refresh token trong cookie `httpOnly`: `CorsConfig` đặt `allowCredentials = false` và CSRF đang tắt; đổi kéo theo CORS + CSRF.
- Giữ `jti` khi refresh: không thể vì chỉ lưu băm.

## Hệ quả

- **FE bắt buộc nối refresh (D055)**: FE hiện coi mọi 401 là hết phiên nên khi chạy với BE thật sẽ bị đăng xuất sau 10 phút. Ghi ở `CLAUDE.md` *Frontend notes*. Refresh chỉ khi người dùng có thao tác (không timer, không từ poll — làm vậy tái tạo D024), gom thành một luồng giữa các tab, retry một lần khi 401 vì token vừa đổi; mất response của refresh (đã xoay nhưng client không nhận) thì lần gửi lại bị coi là dùng lại và phiên bị hủy → đăng nhập lại.
- Phiên mở trước V27 không có refresh token và `access_expires_at` NULL: token cũ (`exp` = hạn phiên) dùng tiếp tới hết hạn, nhân viên hiện offline, không refresh được.
- Cột `last_seen_at` bị xóa (không hoàn tác; dữ liệu online chỉ là tạm). `staff.online_window_minutes` bị xóa khỏi `system_configs` (tổng vẫn 52 khóa).
- Rủi ro đã biết: refresh token bị lộ dùng được tới hết hạn phiên (12 giờ) nếu kẻ trộm dùng trước người thật; người thật dùng token cũ lần sau ⇒ phiên bị hủy và có audit.
