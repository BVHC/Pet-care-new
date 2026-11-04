# ADR-0100: Giới hạn tần suất theo IP cho endpoint public của identity — Redis + filter trong app (trả D011)

- **Trạng thái:** Proposed (chờ BE-1 xác nhận ngưỡng mặc định; IT đã chạy xanh 2026-11-04)
- **Ngày:** 2026-11-04
- **Liên quan:** ADR-0002 (IP client sau proxy), ADR-0005 (path public luôn ẩn danh), ADR-0009 (BCrypt cost 10), ADR-0019 (đăng nhập, khóa tạm), ADR-0023 (quên / đặt lại mật khẩu), ADR-0040 (Swagger = contract); BR-TK-07, BR-TK-09. Nợ: D011 (đóng); D056 (mở: Redis không mật khẩu).
- **Triển khai:** `platform/security/{RateLimiter, RedisRateLimiter, RateLimitFilter, RateLimitProperties}`, `platform/exception/TooManyRequestsException` + `ErrorCode.TOO_MANY_REQUESTS`, `GlobalExceptionHandler.handleTooManyRequests`, `SecurityConfig`, `CorsConfig` (expose `Retry-After`), `application*.yml`, `docker-compose.yml`, `.env.example`, `AuthController` (`@ApiErrorDoc` 429), generator `m_identity.py` + `lib.py`, `docs/api/00-method.md`. Test: `RateLimitFilterTest`, `RedisRateLimiterTest`, `RateLimitSecurityTest`, `RateLimitDisabledSecurityTest`, `GlobalExceptionHandlerTest` (xanh); `RedisRateLimiterIT` 5, `RateLimitIT` 7, `RateLimitRedisDownIT` 1 (xanh trên Redis 7 + Postgres 17 thật). Hồi quy xanh: `LoginIT` 41, `PasswordResetIT` 31, `AuthenticationIT` 38, `RegistrationResendIT` 21, `RegistrationVerificationIT` 13, `AuditRecorderIT` 7, `SchemaMigrationIT` 27, `PetcareApplicationTests`. `OpenApiContractParityIT` đỏ 2 test (`operationMetadataMatchesContract`, `responseCodesAndErrorDescriptionsMatchContract`) **từ trước** (đã chạy trên `HEAD` sạch): mô tả của `POST /api/visits` và `POST /api/accounts/{id}/unlock` ở controller (ADR-0088) chưa có trong contract; không có endpoint `/auth` hay mã 429 nào trong danh sách lệch.

Người dùng chốt ngày 2026-11-04: Redis + filter trong app; fail-open có circuit-breaker; phạm vi `login`, `register*`, `password/*` (không gồm `/auth/refresh`, không gồm `/api/public/**`).

## Vấn đề

Chốt chặn duy nhất của `POST /api/auth/login`, `/register*`, `/password/*` là khóa tạm theo **tài khoản** (BR-TK-09). Kẻ dò nhiều email, hoặc email không tồn tại, không bị chặn: mỗi request tốn một lần BCrypt (~80 ms CPU), mỗi lần sai ghi một dòng `audit_logs` vĩnh viễn (BR-QT-16) và OTP / cảnh báo khóa có thể gửi tới email người khác. Đặc tả không yêu cầu giới hạn theo IP (ADR-0019 *Hệ quả*), nên đây là quyết định kỹ thuật, không phải [CFG].

## Quyết định

1. **Chặn bằng 429 `TOO_MANY_REQUESTS` trước BCrypt và DB.** `RateLimitFilter` (không phải bean, như `JwtAuthenticationFilter`) đứng trong chain **sau `CorsFilter`**, trước mọi filter xác thực. Chỉ đếm `POST` của ba nhóm: `login`; `register` (`/register`, `/register/verify`, `/register/resend-otp`); `password` (`/password/forgot`, `/password/reset`). `OPTIONS`, `GET`, `/auth/refresh`, `/api/public/**` và mọi path khác không bị đếm.
2. **Khóa đếm** `rl:{nhóm}:{ip}`, ip = `request.getRemoteAddr()` (ADR-0002, không tự parse header); IPv6 gom theo 64 bit đầu; IPv4-mapped IPv6 coi như IPv4.
3. **Fixed window bằng một script Lua atomic** (`INCR`; lần đầu `EXPIRE`; trả `{count, TTL}`; TTL < 0 được gán lại để không có khóa bất tử). Chấp nhận burst gấp đôi ở ranh giới cửa sổ.
4. **Ngưỡng** (`app.rate-limit.*`, env `RATE_LIMIT_*`, **không** vào [CFG] vì ngoài đặc tả và không cần min–max / migration): login 30, register 10, password 10, đều trên cửa sổ 60 s. Đây là **giả định** chưa có số đo: lễ tân dùng chung một IP công cộng, đầu ca nhiều người đăng nhập cùng lúc nên rộng hơn con số 10/phút ví dụ của D011.
5. **Fail-open có circuit-breaker.** Redis lỗi hoặc quá timeout (`connect-timeout` và `timeout` cùng 300 ms) thì cho request qua, log `RATE_LIMIT_UNAVAILABLE` một lần và bỏ qua Redis `breaker-seconds` (10) giây theo `Clock`, rồi thử lại. Đăng nhập luôn dùng được; đổi lại tạm thời không có giới hạn.
6. **429 theo envelope chuẩn** qua `GlobalExceptionHandler` (không tự ghi body): `ErrorCode.TOO_MANY_REQUESTS`, header `Retry-After` = TTL còn lại làm tròn lên ≥ 1 giây, message tiếng Việt chung không nói ngưỡng. Exception riêng theo convention 04 §4.2 tiêu chí 2 (status và header khác mặc định), kế thừa thẳng `PlatformException`. Không ghi `audit_logs`, không đếm vào BR-TK-09 / BR-TK-07.
7. **`CorsConfig` expose `Retry-After`**; nếu không, JS cross-origin không đọc được số giây phải chờ.
8. **Bật / tắt:** `app.rate-limit.enabled` (mặc định `true`, profile `test` đặt `false`). Tắt thì không có bean `RedisRateLimiter`, `SecurityConfig` không gắn filter, không gọi Redis. IT hiện có đăng nhập hàng loạt từ `127.0.0.1` nên phải tắt; chỉ `RateLimitIT` bật lại với ngưỡng nhỏ.

## Phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| nginx `limit_req` | Rẻ nhất, chặn trước JVM, nhưng lỗi không theo envelope `ErrorResponse`, không có `traceId`, không test được bằng IT; có thể bổ sung sau như lớp thô. |
| Bộ đếm trong bộ nhớ (`ConcurrentHashMap`) | Đủ cho một instance, nhưng mất bộ đếm khi restart và không scale ngang; Redis đã dựng sẵn nên chi phí thêm gần bằng 0. |
| Bucket4j + Redis | Thêm dependency và phụ thuộc phiên bản Spring Boot cho thứ 12 dòng Lua làm được. |
| Fail-closed (503) | Redis hỏng thì cả hệ thống không đăng nhập được. |
| Giới hạn thêm theo email | Cho kẻ tấn công thêm một cách khóa nạn nhân (BR-TK-09 đã có rủi ro này); OTP đã có quota riêng (BR-TK-07). |

## Hệ quả và giới hạn

- **Phụ thuộc mới trên đường đăng nhập:** Redis. +~1 ms mỗi request khi khỏe; khi chết, breaker tránh trả 300 ms mỗi request (lần đầu vẫn chờ tối đa timeout).
- **Chặn nhầm:** nhiều người sau cùng một IP công cộng (NAT, wifi phòng khám) dùng chung một bucket. Trong Docker, `getRemoteAddr()` có thể là IP gateway thay vì IP client: chưa đo, cần kiểm khi chạy compose (xem kế hoạch kiểm chứng).
- **Không chống được botnet nhiều IP**; mục tiêu là giảm tải CPU và số dòng audit, không phải bảo vệ tuyệt đối.
- **Bộ đếm tăng cả khi request sau đó lỗi** và cả với request bị 429 (không chạm DB); chủ ý.
- **`/actuator/health`:** starter Redis thường kích hoạt `RedisHealthIndicator`, nên Redis chết có thể làm health DOWN dù đăng nhập vẫn chạy (fail-open). Chưa xác nhận bằng chạy thật; nếu đúng thì cân nhắc `management.health.redis.enabled=false`.
- **Redis của compose không mật khẩu và publish 6379:** ai với tới được cổng có thể xóa bộ đếm. BE đã nhận `REDIS_PASSWORD` tùy chọn; phần container ghi ở D056.
- Đổi contract: 429 cho 6 endpoint của identity-v1 và `TOO_MANY_REQUESTS` trong enum `Error` của cả 12 file openapi (sinh lại). FE chưa xử lý 429 (`FE/src/shared/api/api-error.ts` chỉ thêm mã vào kiểu; hiển thị "thử lại sau N giây" do FE-1).
