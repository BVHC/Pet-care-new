# ADR-0020: Tắt Open Session In View (`spring.jpa.open-in-view: false`)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-08
- **Liên quan:** ADR-0019 mục 4 (so BCrypt khi không giữ connection); convention 02 (entity không rời service, DTO qua MapStruct), 07 §7.1 (transaction ở service); `application-docker.yml` (`hikari.maximum-pool-size: 10`, `connection-timeout: 30000`).
- **Triển khai:** `BE/src/main/resources/application.yml` (`spring.jpa.open-in-view: false`); bằng chứng `LoginIT.bcryptRunsWithoutHoldingConnection`, toàn bộ `mvn clean verify` xanh.

## Bối cảnh

Spring Boot mặc định bật Open Session In View: `OpenEntityManagerInViewInterceptor` mở một `EntityManager` cho mỗi request web và chỉ đóng khi trả response. Spring ORM (`HibernateJpaVendorAdapter`) đặt `hibernate.connection.handling_mode = DELAYED_ACQUISITION_AND_HOLD` — connection mượn lần đầu được giữ tới khi `EntityManager` đóng. Hai thứ cộng lại: request nào chạm DB một lần thì giữ connection tới lúc trả response, kể cả phần chạy sau transaction.

Đăng nhập (ADR-0019 mục 4) tách BCrypt (~80 ms CPU) ra ngoài transaction để request dồn dập không cạn pool 10 connection. Khi viết `LoginIT.bcryptRunsWithoutHoldingConnection`, test đỏ: lúc so BCrypt vẫn còn 1 connection active, vì connection mượn ở bước đọc (transaction readOnly) bị OSIV giữ lại.

Kiểm codebase (2026-10-08): không entity nào có liên kết JPA (`@ManyToOne`, `@OneToMany`…) — mọi tham chiếu là cột id; không controller nào gọi repository; entity được map sang DTO trong service (convention 02). Filter xác thực (`SessionAuthenticator`) chạy trước interceptor OSIV nên không phụ thuộc OSIV.

## Quyết định

Tắt OSIV toàn hệ thống: `spring.jpa.open-in-view: false` trong `application.yml` (áp dụng mọi profile).

- Mỗi `@Transactional` mở và đóng `EntityManager` của riêng nó → connection trả về pool ngay khi transaction kết thúc.
- Code ngoài transaction (controller, facade không transaction như `LoginService`, serialize JSON) không giữ connection.
- Không đổi `hibernate.connection.handling_mode` (để Spring tự reset trạng thái connection khi kết thúc transaction).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Giữ OSIV, chỉ đổi đường đăng nhập sang `JdbcTemplate` cho bước đọc | Các endpoint khác vẫn giữ connection suốt request; lệch pattern `JpaRepository`; transaction thứ hai của đăng nhập vẫn giữ connection tới hết request |
| Giữ OSIV, đặt `handling_mode = DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` | Đi ngược cấu hình Spring chọn để dọn trạng thái connection (`readOnly`, isolation) sau transaction; rủi ro khó thấy |
| Chấp nhận, sửa ADR-0019 | Tách BCrypt ra ngoài transaction mất tác dụng; request đăng nhập dồn dập cạn pool |

## Hệ quả

- **Bắt buộc:** mọi truy cập dữ liệu lazy và mọi mapping entity → DTO phải nằm trong transaction của service (đã là quy tắc của convention 02). Code đọc entity ngoài transaction sẽ gặp `LazyInitializationException` thay vì âm thầm mở thêm query.
- Repository gọi ngoài `@Transactional` (không nên có ở service nghiệp vụ) chạy với `EntityManager` ngắn hạn riêng cho mỗi lời gọi; entity trả về ở trạng thái detached.
- Spring Boot không còn log cảnh báo `spring.jpa.open-in-view is enabled by default`.
- Một connection của pool được giữ đúng trong thời gian transaction, nên ước lượng tải theo số transaction đồng thời thay vì số request đồng thời.
