# ADR-0001: Chiến lược lưu trữ JWT Refresh Token & Access Token Blacklist

- **Trạng thái:** Accepted
- **Ngày:** 2026-09-11
- **Liên quan:** `docs/05-domain-model.md` (RULE-02-04, RULE-02-07), `docs/02-business-rules.md` (RULE-01-06), `docs/03-state-machines.md` FSM 1 (Account)
- **Triển khai:** `BE/src/main/resources/db/migration/V2__auth_session_tokens.sql`, `BE/src/main/java/com/petcare/platform/security/token/`

## Bối cảnh

Theo RULE-01-06 (Logout), RULE-02-04 (LockAccount) và RULE-02-07 (DeactivateAccount), khi một trong ba sự kiện này xảy ra, **toàn bộ Access Token và Refresh Token đang có của account phải bị thu hồi ngay lập tức** — không được chấp nhận cho bất kỳ request nào sau đó. Tuy nhiên `docs/06-erd.md` (nguồn chân lý schema) **không đặc tả bất kỳ bảng Session/RefreshToken/TokenBlacklist nào** — đây là hạ tầng bảo mật thuần kỹ thuật (`platform/security`), không phải entity nghiệp vụ, nên không thuộc phạm vi ERD.

JWT bản chất là stateless (tự chứa toàn bộ thông tin xác thực trong chữ ký), nên thu hồi tức thời trước khi token tự hết hạn bắt buộc phải có một cơ chế lưu trữ trạng thái phía server để tra cứu "token này còn hiệu lực hay đã bị thu hồi" tại mỗi request.

Hai loại token có đặc tính khác nhau, cần cân nhắc riêng:
- **Refresh Token**: sống lâu (30 ngày), số lượng ít (1 record/phiên đăng nhập), cần audit trail (ai đăng nhập từ đâu, khi nào, token nào đã bị rotate/revoke).
- **Access Token**: sống ngắn (15 phút, xem ADR-0002), được gửi kèm **mọi** request có auth — việc tra cứu trạng thái thu hồi của nó nằm trên critical path của toàn bộ API, cần độ trễ thấp.

## Quyết định

Dùng chiến lược **hybrid**, tách theo đặc tính của từng loại token:

| Loại | Nơi lưu | Lý do |
|---|---|---|
| **Refresh Token** | PostgreSQL — bảng `refresh_tokens` mới (`V2__auth_session_tokens.sql`) | Cần audit trail bền vững (issued_at/expires_at/revoked_at/revoke_reason/user_agent/ip_address), hỗ trợ rotation có truy vết (`replaced_by`), và `revokeAllForAccount()` (dùng cho LockAccount/DeactivateAccount) phải đáng tin cậy — không chấp nhận mất dữ liệu nếu cache restart. |
| **Access Token Blacklist** | Redis — key `auth:blacklist:{jti}`, TTL = thời gian còn lại của token | Access token tự hết hạn sau tối đa 15 phút nên record blacklist không cần tồn tại lâu — Redis TTL tự dọn dẹp, không cần cron cleanup. Redis tra cứu nhanh (sub-ms), phù hợp vì đây là check chạy trên mọi request. |

Chỉ lưu **SHA-256 hash** của raw refresh token trong Postgres, không bao giờ lưu raw token — nếu DB bị lộ, kẻ tấn công không thể tự tạo lại refresh token hợp lệ từ hash.

Một `TokenIssuanceFacade` (interface, `platform/security/token/`) hợp nhất `JwtTokenProvider` + `RefreshTokenService` + `TokenBlacklistService` thành một contract duy nhất (`issueTokens`/`refreshTokens`/`logout`/`revokeAllSessions`) để module Auth (Module 01, chưa triển khai) gọi khi implement `Login`/`Logout`/`RefreshToken`/`LockAccount`/`DeactivateAccount`, thay vì thao tác trực tiếp 3 lớp bên dưới.

## Lựa chọn đã cân nhắc và bị loại

1. **Toàn bộ trong PostgreSQL** (cả refresh token lẫn blacklist) — loại bỏ vì mỗi request có auth phải query DB để check blacklist, tăng latency và tải DB không cần thiết cho một check chỉ cần đúng/sai; cần thêm cron job tự dọn token hết hạn.
2. **Toàn bộ trong Redis** (cả refresh token lẫn blacklist) — loại bỏ vì mất khả năng audit/truy vết lịch sử refresh token lâu dài nếu Redis restart/mất dữ liệu (không có persistence chắc chắn theo cấu hình `docker-compose.yml` hiện tại — không có AOF/RDB bền vững được cấu hình tường minh).

## Hệ quả

- **Tích cực:** Redis hiện đã khai báo (`spring-boot-starter-data-redis`) nhưng chưa được dùng ở đâu trong code — quyết định này là lần đầu tiên tận dụng nó, đúng mục đích cache/TTL. Refresh token có audit trail đầy đủ trong Postgres, hỗ trợ điều tra sự cố bảo mật sau này.
- **Tiêu cực / rủi ro:** Redis trở thành một phụ thuộc bổ sung cho access-token revocation — hành vi khi Redis không khả dụng được xử lý riêng ở ADR-0002 (fail-open).
- **Theo dõi thêm:** Chưa có cron/job dọn các row `refresh_tokens` đã hết hạn hoặc bị revoke lâu ngày trong Postgres (bảng sẽ tăng dần theo thời gian) — để lại cho lần triển khai module Auth thực tế cân nhắc (ví dụ: job dọn định kỳ hoặc partition theo thời gian nếu volume lớn).
