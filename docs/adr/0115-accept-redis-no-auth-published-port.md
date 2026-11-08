# ADR-0115: Chấp nhận Redis của compose không mật khẩu và publish cổng 6379 (D056)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-07
- **Liên quan:** ADR-0100 (giới hạn tần suất theo IP trên Redis, nơi nợ phát sinh), 0101 (cùng kiểu chấp nhận: đồ án, không có người dùng thật), 0102. Nợ: D056 (đóng ở dạng chấp nhận, không trả).
- **Triển khai:** không có. Không code, không đổi `docker-compose.yml` / `.env.example` / `application.yml`, không migration, không [CFG].

Người dùng chốt ngày 2026-11-07: đồ án, không có người dùng thật; chỉ ghi chú, không sửa.

## Vấn đề

`docker-compose.yml` (service `redis`, dòng 37-47) dựng `redis:7-alpine` không `requirepass` và publish `6379:6379` ra host. Từ ADR-0100, Redis giữ bộ đếm giới hạn tần suất của `login`, `register*`, `password/*`: tiến trình nào với tới cổng đều chạy được `FLUSHDB` hoặc `DEL rl:*` để xóa bộ đếm. Backend đã nhận `REDIS_PASSWORD` (`application.yml`, mặc định rỗng = không AUTH) nhưng container chưa đặt mật khẩu. Chi tiết: `docs/dept/D056-redis-no-auth-published-port.md`.

## Quyết định

1. **Chấp nhận, không đổi gì.** Không `requirepass`, không bỏ `ports`, không tách `docker-compose.prod.yml`, không bind `127.0.0.1`.
2. **Thiệt hại có trần:** Redis chỉ giữ bộ đếm giới hạn tần suất (chưa có code nào khác dùng Redis). Mất bộ đếm nghĩa là mất lớp chống dò mật khẩu / dội email OTP trước BCrypt; **không lộ và không mất dữ liệu nghiệp vụ**. Lớp còn lại: BCrypt, khóa tạm đăng nhập (ADR-0019), quota OTP (BR-TK-07).
3. **Redis lỗi hoặc bị xóa thì bộ giới hạn fail-open** (ADR-0100): hệ thống vẫn chạy, chỉ mất bảo vệ.

## Hệ quả

- Ai với tới cổng 6379 trên máy chạy compose (LAN không tin cậy, VPS không có firewall) tắt được giới hạn tần suất.
- Không có người dùng thật và không có tải thật, nên không đáng chi phí: `requirepass` còn kéo theo healthcheck `redis-cli ping` cần AUTH và mọi lệnh smoke / dev đang dùng Redis không mật khẩu (chưa kiểm hết).

## Quy trình tay

- Không chạy compose trên máy có cổng 6379 truy cập được từ mạng không tin cậy; nếu phải chạy, chặn cổng bằng firewall.
- Môi trường nhiều người dùng được dựng thì phải trả nợ này **trước khi mở ra ngoài** (mục dưới).
- Nghi bộ đếm bị xóa: `redis-cli -h localhost keys 'rl:*'` rỗng bất thường; khởi động lại backend không khôi phục bộ đếm, nó tự đầy lại theo request.

## Cách trả nếu mở lại

Môi trường thật: `command: redis-server --requirepass ${REDIS_PASSWORD}`, healthcheck có AUTH (`-a` hoặc `REDISCLI_AUTH`), truyền `REDIS_PASSWORD` cho `backend`, bỏ `ports` của `redis` (backend nối qua mạng compose). Có thể tách `docker-compose.prod.yml` để dev giữ cổng mở. Kiểm `RedisHealthIndicator` (ADR-0100 *Hệ quả*) cùng lúc. Kiểm khi trả: `docker compose up` với `REDIS_PASSWORD` đặt thì backend khỏe, `redis-cli -h localhost ping` không AUTH bị từ chối (hoặc không với tới cổng), giới hạn vẫn trả 429 sau khi vượt ngưỡng, `RateLimitIT` vẫn xanh.
