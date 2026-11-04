# ADR-0101: Chấp nhận không giới hạn kích thước body JSON của request (D018)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-04
- **Liên quan:** ADR-0002 (môi trường thật không publish 8080), 0036 (nợ D018 phát hiện ở đây), 0037 (nội dung mẫu thông báo mở rộng D018), 0100 (D011 đã trả, điều kiện "trả khi" của D018). Nợ: D018 (đóng ở dạng chấp nhận, không trả).
- **Triển khai:** không có. Không code, không migration, không contract, không [CFG].

Người dùng chốt ngày 2026-11-04: đồ án, không có người dùng thật; rủi ro này xa vời, không sửa gì.

## Vấn đề

Backend không giới hạn kích thước body JSON. Jackson đọc cả body khi resolve `@RequestBody`, trước Bean Validation và `@PreAuthorize`; Tomcat `maxPostSize` không áp dụng cho JSON. Mọi request đã qua xác thực (và request ẩn danh tới endpoint public) gửi được body rất lớn, buộc JVM cấp phát tương ứng. Chi tiết: `docs/dept/D018-request-body-size-limit.md`.

## Quyết định

1. **Chấp nhận, không thêm giới hạn nào.** Không filter đếm byte ở `platform/`, không cấu hình server, không đặt `client_max_body_size` trong `FE/nginx.conf`. Các ADR cũ giữ nguyên.
2. **Lớp bảo vệ còn lại là nginx mặc định 1 MB** (`client_max_body_size` chưa đặt nên nginx dùng 1 MB, trả 413) cho request đi qua `FE/nginx.conf`.
3. **Cổng 8080:** `docker-compose.yml` publish `8080:8080` cho dev, nên gọi thẳng cổng này không có giới hạn nào. Chấp nhận. Môi trường triển khai thật không được publish 8080 (ADR-0002); đây là quy ước vận hành, không có gì trong code ép buộc.
4. **Bean Validation vẫn từ chối trường quá dài** sau khi parse, nên tính đúng của nghiệp vụ không bị ảnh hưởng; rủi ro chỉ là bộ nhớ / khả dụng.

## Hệ quả

- Một tài khoản đã đăng nhập, hoặc người gọi thẳng 8080, có thể làm JVM cấp phát nhiều bộ nhớ; nhiều request song song có thể gây `OutOfMemoryError`. `PUT /api/notification-templates/{code}` (ADMIN) lớn tỉ lệ với body (message BR-QT-14 đủ mọi biến lỗi, audit chứa nội dung hai lần).
- Không có người dùng thật và không có tải thật, nên không đáng chi phí: filter + ADR số liệu + IT cho endpoint public / có token / chunked + khớp nginx.

## Quy trình tay

- Không publish 8080 ra ngoài máy dev. Nếu một môi trường nhiều người dùng được dựng, mọi request đi qua nginx.
- Gặp `OutOfMemoryError` sau request bất thường: khởi động lại backend.

## Cách trả nếu mở lại

Đúng như mục *Cách trả* của D018: filter trong `platform/` đặt cạnh `RateLimitFilter` (trước `JwtAuthenticationFilter`), đếm byte theo `Content-Length` và theo luồng đọc (chunked), mã 413 qua `GlobalExceptionHandler`, con số mặc định chọn sau khi liệt kê endpoint có body lớn nhất; đặt `client_max_body_size` khớp trong `FE/nginx.conf`; IT 413 cho endpoint public và endpoint có token, body chunked, body đúng giới hạn vẫn chạy.
