# ADR-0005: Path public luôn xử lý như chưa đăng nhập

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-05
- **Supersedes:** ADR-0003 mục 4 (phạm vi của filter xác thực) và mục 9 (ngoại lệ `/api/public/**` của BR-TK-17).
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-12, 13, 17; BR-QT-15.
  - `docs/api/00-method.md` §3.1; `docs/api/identity-v1.md` A4.
  - ADR-0001 (actor của audit), ADR-0003.
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/security/SecurityConfig.java` (`PUBLIC_REQUESTS`), `JwtAuthenticationFilter.java` (`shouldNotFilter`).
  - `BE/src/main/java/com/petcare/module/identity/controller/MustChangePasswordInterceptor.java`.
  - Test: `SecurityConfigTest`, `JwtAuthenticationFilterTest`, `MustChangePasswordInterceptorTest`, `AuthenticationIT`.

## Bối cảnh

Theo ADR-0003, `JwtAuthenticationFilter` đọc token ở **mọi** request; `permitAll` chỉ cho path public đi qua khi chưa đăng nhập. Request public mang token còn hạn vì vậy vẫn có principal. FE gắn token vào mọi request (`FE/src/shared/api/axios.ts`), nên khi tài khoản còn `must_change_password` (BR-TK-17), interceptor chặn cả `POST /api/auth/login`, `/api/auth/password/forgot|reset` và `/api/auth/register…` bằng 400. Lý do: ADR-0003 mục 9 chỉ miễn `/api/public/**`.

Hệ quả: nhân viên vừa nhận mật khẩu tạm, đã đăng nhập một lần rồi quên mật khẩu tạm, sẽ không dùng được "Quên mật khẩu" (BR-TK-12 cho phép tài khoản `ACTIVE`), và cũng không đăng nhập lại được. Có thêm hai lỗi phụ:
- Audit đăng nhập lấy actor từ principal (`AuditRecorder`, ADR-0001). Token cũ của tài khoản X gửi kèm lần đăng nhập của Y sẽ làm audit ghi actor là X (BR-QT-15).
- Request public mang token cũ trả 500 khi DB lỗi lúc tra phiên.

## Quyết định

1. **Path public luôn ẩn danh.** `SecurityConfig.PUBLIC_REQUESTS` là một `OrRequestMatcher` gồm các `PathPatternRequestMatcher`, dựng từ `PUBLIC_PATHS`. Cùng một instance dùng cho `permitAll` và cho `JwtAuthenticationFilter.shouldNotFilter`: ở path public, filter không đọc token, không tra phiên, không ghi `last_seen_at`.
2. **BR-TK-17 chỉ miễn 3 endpoint của A4:** `GET /api/me`, `POST /api/me/password`, `POST /api/auth/logout`. Ngoại lệ `/api/public/**` của ADR-0003 mục 9 bị bỏ, vì path public không bao giờ có principal.
3. Danh sách path public giữ nguyên như ADR-0003 mục 6.

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| Interceptor `excludePathPatterns(PUBLIC_PATHS)` | Hai bộ so khớp khác nhau (Spring Security và MVC) có thể lệch nhau; vẫn tốn một query mỗi request; vẫn 500 khi DB lỗi; audit đăng nhập vẫn có thể lấy nhầm actor |
| Giữ nguyên, FE không gửi token tới path public | Dựa vào kỷ luật của client; mọi client khác (Postman, app sau này) vẫn gặp lỗi |

## Hệ quả

- **Tích cực:**
  - Một nguồn sự thật cho "path public".
  - Request public không tốn query tra phiên.
  - Đăng nhập không còn phụ thuộc trạng thái DB của token cũ.
- **Đánh đổi đã chấp nhận:**
  - Endpoint public không bao giờ biết ai đang gọi. Contract hiện không có endpoint public nào cần điều này; nếu cần "public nhưng cá nhân hóa" thì phải có ADR mới.
  - `/actuator/health` là path public, nên `show-details: when-authorized` không bao giờ hiện details, kể cả khi gửi token. Healthcheck Docker chỉ đọc HTTP status nên không bị ảnh hưởng.
  - Gọi path public với token không cập nhật trạng thái online (BR-TN-06 coi online chỉ là gợi ý).
- **Ràng buộc cho các task sau:**
  - **Đăng nhập (TK):** audit `LOGIN` (thành công) và `LOGIN_FAILED` **phải** gọi `AuditEntry.actor(id, email)`. Request đăng nhập không có principal, nên nếu không ghi đè thì actor là "hệ thống".
  - **Đặt lại mật khẩu, xác thực OTP:** xác định tài khoản theo email/OTP trong request, không theo principal.
  - Thêm path public mới: thêm vào `SecurityConfig.PUBLIC_PATHS`, đồng thời thêm một dòng vào `SecurityConfigTest.publicRequestMatcherCoversContractPublicPaths`.
