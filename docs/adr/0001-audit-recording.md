# ADR-0001: Ghi audit nghiệp vụ bằng `AuditRecorder` tường minh

- **Trạng thái:** Accepted (mục 3 phần `LOGIN_FAILED` → 0019)
- **Ngày:** 2026-10-03
- **Liên quan:** BR-QT-15, BR-QT-16, BR-QT-01, BR-TK-16 (`docs/02-business-rules.md`); `docs/05-erd.md` bảng `audit_logs` (L148–164), L802 (thu tiền), §13 mục 6 và 9; ADR-0002 (IP)
- **Triển khai:** `BE/src/main/java/com/petcare/platform/audit/` (`AuditRecorder`, `AuditEntry`, `AuditPrincipal`, `AuditLogEntity`, `AuditLogRepository`); test `AuditRecorderTest`, `AuditRecorderIT`; quy ước dùng ở `docs/convention/backend/08-logging-and-audit.md` §8.2–8.3

## Bối cảnh

BR-QT-15 bắt ghi audit cho đăng nhập (thành công và thất bại), mọi thao tác quản trị, đổi giá, giao dịch tiền (thu tiền, hủy Order `PENDING`, hủy phiên trả thú), điều chỉnh kho và sửa bệnh án. Mỗi bản ghi gồm người thực hiện, thời điểm, hành động, đối tượng, giá trị trước và sau. BR-QT-16: chỉ thêm mới, không ai sửa hay xóa. Bảng `audit_logs` và trigger chặn UPDATE/DELETE/TRUNCATE đã có trong `V1__init_schema.sql`, nhưng chưa có code ghi.

Đặc tả không quy định cách ghi. Bốn yêu cầu kéo theo nhau:

1. **Giá trị trước/sau** chỉ service nghiệp vụ biết, vì nó đã nạp entity trước khi sửa.
2. **Cùng transaction** với nghiệp vụ: erd L802 liệt kê "ghi `audit_logs`" là một bước trong transaction thu tiền.
3. **Vẫn ghi khi nghiệp vụ thất bại**: BR-QT-15 có "đăng nhập thất bại"; BR-QT-01 "từ chối (403) và ghi audit". Ở đây nghiệp vụ rollback nhưng bản audit phải còn.
4. **Không ghi dữ liệu nhạy cảm**: bảng không sửa/xóa được, nên lỡ ghi `password_hash`, mã OTP, token hay CCCD (BR-TK-16 cấm lưu) là không gỡ được.

Convention 08 cũ quy định `@Auditable` + `AuditAspect` (`@AfterReturning`), viết trước bản đặc tả v16.

## Quyết định

1. **Phạm vi:** audit nghiệp vụ theo BR-QT-15, không phải HTTP access log. Log truy cập vẫn là application log (có `traceId`).
2. **Cơ chế:** service gọi `AuditRecorder` tường minh, truyền `AuditEntry`:
   ```java
   var before = snapshot(account);
   account.lock(reason);
   auditRecorder.record(AuditEntry.of("ACCOUNT_LOCKED")
           .entity("accounts", account.getId())
           .before(before).after(snapshot(account)).reason(reason));
   ```
3. **Transaction:**
   - `record(entry)` — mặc định, `Propagation.MANDATORY`: bắt buộc nằm trong transaction của use case. Nghiệp vụ rollback thì audit mất theo; ghi audit lỗi thì nghiệp vụ rollback. Gọi ngoài transaction là lỗi lập trình (`IllegalTransactionStateException`).
   - `recordIndependently(entry)` — **chỉ cho sự kiện thất bại** (`LOGIN_FAILED`, từ chối 403 do vượt phân cấp): transaction riêng `REQUIRES_NEW` qua `TransactionTemplate`.
4. **Snapshot:** caller truyền record hoặc `Map` chỉ gồm trường cần audit, không bao giờ truyền entity JPA. Recorder từ chối entity, `HibernateProxy`, và cả phần tử entity trong `Collection`/`Map`. Sau khi chuyển sang JSON bằng `ObjectMapper` của Spring, recorder duyệt cây và từ chối khóa có từ thuộc `{password, passwd, otp, token, secret, cccd, pin}` (tách theo `_` và camelCase, nên `spinach`, `tokenizer` không bị chặn).
5. **Actor:** platform khai báo `AuditPrincipal { accountId(); email(); }`; principal của module TK implement interface này. Không có phiên đăng nhập hoặc phiên anonymous → **hệ thống** (cả hai cột NULL, dùng cho job ST*). Principal khác kiểu → `IllegalStateException`. Entry ghi đè được actor qua `actor(id, email)` (ví dụ `LOGIN_FAILED` ghi email người dùng đã nhập). Có `accountId` thì bắt buộc có `email` (erd §13 mục 9: cột không có FK nên luôn ghi kèm email).
6. **Mã action:** chuỗi `^[A-Z][A-Z0-9_]{2,49}$`, là hằng số do từng module giữ. Platform chỉ kiểm tra định dạng. Catalog theo nhóm BR-QT-15 nằm ở convention 08 §8.3. `entityType` là tên bảng `^[a-z][a-z0-9_]{1,49}$`; `entityId` để trống được (bảng PK chuỗi như `system_configs`).
7. **Khi chính lệnh ghi độc lập thất bại** (DB chập chờn, hết connection): log `ERROR` `AUDIT_WRITE_FAILED` kèm toàn bộ entry và `traceId`, nuốt lỗi để client vẫn nhận lỗi gốc (401/403/400). Entry sai định dạng thì vẫn ném ra ở cả hai hàm, vì đó là lỗi lập trình, kiểm tra trước khi chạm DB.
8. **Bảo vệ chỉ-thêm-mới ở tầng ứng dụng:** `AuditLogEntity` là `@Immutable`, không setter; `AuditLogRepository` kế thừa `Repository` (không phải `JpaRepository`) và chỉ khai `save`.

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| `@Auditable` + AOP `@AfterReturning` (convention 08 cũ) | Aspect không biết giá trị trước khi sửa, trừ khi tự nạp lại entity (thêm query, dễ sai) hoặc dùng `ThreadLocal` để service đẩy dữ liệu vào (trạng thái ẩn, quên đẩy là sót dữ liệu mà không có lỗi). `@AfterReturning` không chạy khi method ném lỗi nên không ghi được `LOGIN_FAILED`/403. Thứ tự aspect so với proxy `@Transactional` quyết định audit có cùng transaction hay không, khó thấy khi đọc code |
| Luôn cùng transaction | Không ghi được sự kiện thất bại (vi phạm BR-QT-01, BR-QT-15) |
| Ghi bất đồng bộ sau commit | Crash ngay sau commit là mất audit; không đạt toàn vẹn của BR-QT-16 |
| Tự serialize entity, che theo blacklist | Trường mới thêm vào entity sau này tự động lọt vào audit; dính lazy proxy và vòng tham chiếu; JSON đổi hình mỗi khi entity đổi |
| Chỉ lưu diff | BR-QT-15 ghi "giá trị trước và sau" |
| Enum `AuditAction` tập trung trong platform | Mỗi module mới phải sửa `platform/`, đi ngược hướng phụ thuộc (convention 01) |
| Caller luôn tự truyền actor | Dễ truyền sai hoặc quên; SecurityContext đã có sẵn người đăng nhập |
| Ném lỗi (500) khi ghi độc lập thất bại | Che lỗi gốc: người nhập sai mật khẩu sẽ thấy "Lỗi hệ thống" |

## Hệ quả

- **Tích cực:** một điểm ghi duy nhất, đọc code thấy ngay thao tác nào có audit và ghi gì. Ngữ nghĩa transaction tường minh (`record` / `recordIndependently`). Dữ liệu nhạy cảm bị chặn trước khi chạm DB. Platform không phụ thuộc module.
- **Đánh đổi đã chấp nhận:**
  - Lệch convention 08 cũ; đã sửa §8.2 theo ADR này.
  - Service phải tự viết hàm snapshot cho từng aggregate cần audit.
  - `recordIndependently` cần connection thứ hai trong lúc transaction ngoài còn giữ connection. Khi pool cạn, nó chờ tới `connection-timeout` (30s ở profile docker) rồi lỗi, và lỗi bị nuốt + log ERROR. Vì vậy chỉ dùng cho sự kiện thất bại.
  - Khi ghi độc lập thất bại, bản audit chỉ còn trong application log, không có trong `audit_logs`.
  - `created_at` do `@CreationTimestamp` của `CreatedAtEntity` đặt theo giờ JVM, không qua bean `Clock`. Đây là nợ chung của base class, không riêng audit (system-overview §7).
- **Theo dõi thêm:**
  - Khi làm module TK: principal implement `AuditPrincipal`; kiểm IP end-to-end qua nginx (ADR-0002).
  - Khi làm UC11 (module QT): cần repository đọc riêng, không mở rộng `AuditLogRepository`.
  - BR-QT-16 "lưu tối thiểu 2 năm" hiện được bảo đảm vì không có đường xóa nào; nếu sau này cần lưu trữ hoặc dọn dữ liệu cũ thì phải có ADR mới.
