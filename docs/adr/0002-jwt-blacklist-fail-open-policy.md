# ADR-0002: Chính sách Fail-Open cho Access Token Blacklist khi Redis không khả dụng

- **Trạng thái:** Accepted
- **Ngày:** 2026-09-11
- **Liên quan:** ADR-0001, `docs/00-requirements.md` REQ-SEC-002, REQ-ACC-010
- **Triển khai:** `BE/src/main/java/com/petcare/platform/security/token/TokenBlacklistService.java`, `BE/src/main/resources/application*.yml` (`jwt.access-token-ttl-min`, `spring.data.redis.timeout`, `app.security.blacklist-fail-open`)

## Bối cảnh

ADR-0001 đặt access-token blacklist trên Redis. Redis hiện tại là hạ tầng **"khai báo nhưng chưa dùng"** — chưa qua kiểm chứng production, không có cấu hình HA/persistence tường minh trong `docker-compose.yml`. Cần quyết định: khi Redis không phản hồi (mất kết nối hoặc timeout), `JwtAuthenticationFilter` phải xử lý request đó thế nào?

Hai lựa chọn đối lập:
- **Fail-closed**: Redis không trả lời → từ chối toàn bộ request có auth. Bảo toàn tuyệt đối thuộc tính "token bị thu hồi thì không dùng được nữa" (REQ-SEC-002/REQ-ACC-010), nhưng biến Redis thành **single point of failure cho 100% traffic đã đăng nhập** của toàn hệ thống.
- **Fail-open**: Redis không trả lời → coi token là chưa bị blacklist, cho qua dựa trên chữ ký JWT hợp lệ. Hệ thống không bị gián đoạn khi Redis sập, nhưng trong đúng khoảng thời gian Redis sập, một access token *đã bị thu hồi trong lúc đó* vẫn dùng được cho tới khi tự hết hạn.

## Quyết định

**Fail-open**, đi kèm 3 biện pháp giảm rủi ro bắt buộc:

1. **Rút ngắn access-token TTL: 60 phút → 15 phút** (`jwt.access-token-ttl-min: 15`). Đây là biện pháp quan trọng nhất — nó giới hạn cứng cửa sổ phơi nhiễm của MỌI access token, không riêng lúc Redis sập, nên là "phòng thủ theo chiều sâu" áp dụng thường trực chứ không chỉ khi có sự cố.
2. **Redis command timeout ngắn: 300ms** (`spring.data.redis.timeout: 300ms`, Spring Boot tự áp dụng làm Lettuce command timeout) — để filter không bị treo request khi Redis chết hoặc mạng chậm, kích hoạt fail-open nhanh thay vì để client chờ timeout mặc định (Lettuce default ~60s).
3. **Alerting qua health check** — `spring-boot-starter-data-redis` tự đăng ký Redis health indicator vào `/actuator/health`, giúp ops phát hiện Redis down ngay, không im lặng.

`TokenBlacklistService.isBlacklisted()` bọc mọi lỗi kết nối/timeout Redis trong try/catch, log `WARN` và trả `false` (không throw, không làm sập request bằng lỗi 500).

Chính sách này **không hardcode** — đọc từ property `app.security.blacklist-fail-open` (mặc định `true`), cho phép chuyển sang fail-closed bằng cấu hình (không cần sửa code) nếu sau này đánh giá lại đánh đổi này (ví dụ: khi Redis đã production-hardened với Sentinel/Cluster và HA thực sự, hoặc yêu cầu bảo mật siết chặt hơn cho một môi trường cụ thể).

## Lý do

Refresh token (lưu Postgres, xem ADR-0001) **không bị ảnh hưởng** khi Redis sập — `revokeAllForAccount()` (LockAccount/DeactivateAccount) vẫn hoạt động đúng, và user không thể lấy access token mới (refresh bị chặn ngay). Vì vậy hậu quả thực tế của fail-open chỉ là: **access token đã phát hành trước đó, đúng lúc bị thu hồi trong cửa sổ Redis sập, còn dùng được tối đa 15 phút** — không phải "user bị khoá vẫn dùng được hệ thống vô thời hạn".

So với việc fail-closed biến Redis (hạ tầng chưa production-hardened) thành hard dependency của 100% API traffic có auth, cái giá về availability lớn hơn nhiều so với rủi ro bảo mật đã bị giới hạn sẵn bởi TTL ngắn.

## Hệ quả

- **Tích cực:** Redis sập không kéo sập toàn bộ API; rủi ro bảo mật có giới hạn cứng (≤ 15 phút) và bằng chứng log (`WARN`) mỗi lần fail-open kích hoạt.
- **Tiêu cực / đánh đổi đã chấp nhận:** Trong kịch bản hiếm (Redis sập ĐÚNG lúc user bị `Logout`/`LockAccount`), access token cũ của user đó có thể dùng được thêm tối đa 15 phút thay vì bị chặn ngay lập tức — không đạt 100% "instant revocation" tuyệt đối như RULE-01-06/RULE-02-04 mô tả trong khoảng thời gian Redis down.
- **Theo dõi thêm:** Nếu Redis được nâng cấp lên cấu hình HA thực sự (Sentinel/Cluster) trong tương lai, nên đánh giá lại chuyển `app.security.blacklist-fail-open` sang `false` cho môi trường production.
