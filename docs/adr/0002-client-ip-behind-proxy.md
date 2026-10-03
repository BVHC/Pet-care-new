# ADR-0002: Xác định IP client sau reverse proxy

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-03
- **Liên quan:** ADR-0001 (`audit_logs.ip_address`); `docs/05-erd.md` bảng `audit_logs` (L161), `sessions.ip_address` (L122)
- **Triển khai:** `BE/src/main/resources/application.yml` (`server.forward-headers-strategy: native`); `AuditRecorder.currentIpAddress()`; test `AuditRecorderIT.tomcatTrustsForwardedHeadersOnlyFromInternalProxies`

## Bối cảnh

`audit_logs.ip_address` và `sessions.ip_address` cần IP của người dùng. Trong Docker, request đi qua nginx của FE (`FE/nginx.conf` L12–18: `proxy_pass http://backend:8080/api/`, đặt `X-Real-IP` và `X-Forwarded-For: $proxy_add_x_forwarded_for`). Nếu không cấu hình gì, `request.getRemoteAddr()` ở backend luôn là IP container nginx.

Thiết lập này ảnh hưởng **mọi request** chứ không chỉ audit (`getRemoteAddr`, `getScheme`, `isSecure`, URL tự sinh), nên tách thành ADR riêng.

## Quyết định

Đặt `server.forward-headers-strategy: native`. Spring Boot gắn `RemoteIpValve` của Tomcat vào engine. Valve chỉ tin `X-Forwarded-For`/`X-Forwarded-Proto` khi request đến từ proxy thuộc dải nội bộ mặc định (`10/8`, `192.168/16`, `172.16/12`, `169.254/16`, `127/8`, `100.64/10`, IPv6 nội bộ), và lấy IP **ngoài cùng không thuộc proxy tin cậy** trong chuỗi. Code chỉ cần đọc `getRemoteAddr()`, không tự parse header.

## Lý do

| Phương án | Đánh giá |
|---|---|
| **native** (chọn) | Valve duyệt chuỗi `X-Forwarded-For` từ phải sang trái và dừng ở địa chỉ đầu tiên không phải proxy tin cậy. Giá trị client tự chèn vào đầu chuỗi bị bỏ qua khi request đi qua nginx, vì nginx nối IP thật vào cuối chuỗi |
| `framework` (`ForwardedHeaderFilter`) | Lấy giá trị đầu tiên của `X-Forwarded-For`, mà giá trị này client tự đặt được, nên dễ giả mạo |
| Ghi `remoteAddr` thô | Trong Docker mọi bản ghi mang IP của nginx, gần như vô dụng |
| Để NULL tới khi có TK | Hoãn quyết định mà không giảm rủi ro; audit đầu tiên của TK (`LOGIN_FAILED`) cần IP ngay |

## Hệ quả

- **Tích cực:** `audit_logs.ip_address` và sau này `sessions.ip_address` có IP client thật khi đi qua nginx. Code không phải tự đọc header.
- **Đánh đổi đã chấp nhận:** valve tin mọi địa chỉ trong dải nội bộ, nên IP chỉ đáng tin khi client thật nằm **ngoài** dải đó. Hai trường hợp ở môi trường dev mà giả được `X-Forwarded-For`:
  1. `docker-compose.yml` publish `8080:8080` ra host. Request gọi thẳng `localhost:8080` đi vào qua gateway Docker (`172.x`), mà dải này thuộc nhóm tin cậy.
  2. Browser trên host gọi qua nginx (`localhost:3000`). nginx thấy client là gateway `172.x` và nối địa chỉ đó vào cuối chuỗi; valve coi địa chỉ đó là proxy nên tiếp tục đọc sang trái, tới giá trị client tự chèn.

  Ở môi trường thật (client có IP công khai, không publish 8080, chỉ nginx ra ngoài) thì không gặp hai trường hợp này. Nếu cần chặt hơn, thu hẹp `server.tomcat.remoteip.internal-proxies` về đúng IP của nginx.
- **Theo dõi thêm:** kiểm IP end-to-end qua nginx khi có endpoint đăng nhập (module TK). Hiện mọi path ngoài health/docs trả 401 (`SecurityConfig`) nên chưa có đường nào ghi audit qua HTTP.
