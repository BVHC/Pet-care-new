[← Backend Convention Index](INDEX.md)

# 8. Logging & Audit

## 8.1. Application Log

- SLF4J + Logback (`resources/logback-spring.xml`), dùng `@Slf4j` của Lombok. Profile `docker` log JSON (`logstash-logback-encoder`); profile khác log dạng chữ kèm `[traceId]`.
- `traceId` do `TraceIdFilter` (filter đầu tiên) đặt vào MDC và header `X-Trace-Id`, xuyên suốt mọi layer; đọc trong code bằng `TraceContext.current()`. Cũng là `traceId` của `ErrorResponse`.
- Không log dữ liệu nhạy cảm: mật khẩu, mã OTP, token, CCCD (cùng danh sách bị chặn ở audit, §8.2).

| Level | Khi dùng |
|---|---|
| `ERROR` | Lỗi hệ thống không mong muốn (DB down, NPE…); `GlobalExceptionHandler` đã log khi trả 500, `AuditRecorder` log `AUDIT_WRITE_FAILED` |
| `WARN` | Rule violation, sai trạng thái, lỗi validation đã được xử lý — `GlobalExceptionHandler` đã log, service **không** log lặp lại trước khi ném |
| `INFO` | Use case nghiệp vụ quan trọng hoàn tất (đặt lịch, thu tiền, chốt ca…) và mỗi lượt chạy job ST* (số bản ghi xử lý) |
| `DEBUG` | Chi tiết nội bộ phục vụ debug |

## 8.2. Audit Log (`audit_logs`, BR-QT-15, 16)

Tách biệt hoàn toàn khỏi application log. Service nghiệp vụ gọi `AuditRecorder` (`platform/audit`) **tường minh**; không dùng `@Auditable`/AOP. Lý do và các phương án bị loại: [ADR-0001](../../adr/0001-audit-recording.md).

```java
@Transactional
public AccountResponse lockAccount(Long accountId, String reason) {
    Account account = ...;
    AccountAuditSnapshot before = AccountAuditSnapshot.of(account);   // record chỉ gồm trường cần audit
    account.lock(reason);
    auditRecorder.record(AuditEntry.of(QtAuditActions.ACCOUNT_LOCKED)
            .entity("accounts", account.getId())
            .before(before)
            .after(AccountAuditSnapshot.of(account))
            .reason(reason));
    ...
}
```

| Hàm | Khi nào | Transaction |
|---|---|---|
| `record(entry)` | Mặc định, cho mọi thao tác thành công | `MANDATORY`: phải gọi trong `@Transactional` của use case. Nghiệp vụ rollback thì audit mất theo; ghi audit lỗi thì nghiệp vụ rollback. Gọi ngoài transaction → `IllegalTransactionStateException` |
| `recordIndependently(entry)` | **Chỉ** sự kiện thất bại mà audit phải còn dù nghiệp vụ rollback: `LOGIN_FAILED`, từ chối 403 theo BR-QT-01 | `REQUIRES_NEW` (cần connection thứ hai). Ghi lỗi → log `ERROR AUDIT_WRITE_FAILED` kèm entry, không ném, client nhận lỗi gốc |

Quy tắc của `AuditEntry` (vi phạm là lỗi lập trình → `IllegalArgumentException`/`IllegalStateException` → 500, ở cả hai hàm):
- `action`: hằng số `UPPER_SNAKE` do module giữ, khớp `^[A-Z][A-Z0-9_]{2,49}$`, dạng `<ĐỐI_TƯỢNG>_<QUÁ_KHỨ>` (`ACCOUNT_LOCKED`, `ORDER_PAID`).
- `entity(type, id)`: `type` là **tên bảng** (`accounts`, `orders`); `id` để `null` với bảng PK chuỗi (`system_configs`, `notification_templates`) — khi đó khóa nằm trong snapshot.
- `before`/`after`: **record hoặc `Map` do service tự chọn trường**, không bao giờ là entity JPA (bị từ chối, kể cả khi nằm trong `List`/`Map`). Khóa có từ `password`, `passwd`, `otp`, `token`, `secret`, `cccd`, `pin` bị từ chối (BR-TK-16 không lưu CCCD; chỉ ghi *phương thức* xác minh). Thời điểm serialize dạng ISO-8601.
- `reason`: bắt buộc ở những thao tác rule đòi lý do (khóa, hủy Order `PENDING`, gán lại lượt…). Việc kiểm tra "có lý do" là business rule nên nằm ở service và ném `BusinessRuleViolationException`; recorder không kiểm tra.
- Actor: tự lấy từ `SecurityContext`; principal của TK phải implement `AuditPrincipal`. Không có phiên đăng nhập → hệ thống (2 cột actor NULL), dùng cho job ST*. Khi chưa có phiên (đăng nhập thất bại, đăng ký) dùng `.actor(accountIdOrNull, emailNhapVao)`.
- IP: tự lấy từ request hiện tại (sau `RemoteIpValve`, [ADR-0002](../../adr/0002-client-ip-behind-proxy.md)); ngoài request là NULL.

`audit_logs` chỉ thêm mới: `AuditLogEntity` là `@Immutable`, `AuditLogRepository` chỉ có `save`, DB có trigger chặn UPDATE/DELETE/TRUNCATE. Màn hình xem audit (UC11, chỉ ADMIN) dùng repository đọc riêng trong module QT.

## 8.3. Danh mục thao tác phải audit

Nguồn: BR-QT-15 và mọi chỗ đặc tả ghi "ghi audit". Mã action do module đặt khi cài; đặc tả chỉ có sẵn 3 mã ví dụ (erd L155): `LOGIN_FAILED`, `PRICE_CHANGED`, `ORDER_PAID`. Thêm mã mới thì bổ sung vào cột cuối.

| Nhóm BR-QT-15 | Thao tác (nguồn) | Module | Hàm | Mã |
|---|---|---|---|---|
| Đăng nhập | Đăng nhập thành công (UC03) | TK | `record` | *(TK đặt)* |
| | Đăng nhập thất bại (UC03, BR-TK-09) | TK | `recordIndependently` | `LOGIN_FAILED` |
| Thao tác quản trị | Tạo, đổi chức vụ, điều chuyển, vô hiệu hóa, kích hoạt lại nhân viên (UC08) | QT | `record` | *(QT đặt)* |
| | Từ chối do vượt phân cấp, 403 (BR-QT-01) | QT | `recordIndependently` | *(QT đặt)* |
| | Khóa / mở khóa (BR-QT-11; Tài khoản #7, #8) | QT | `record` | *(QT đặt)* |
| | Cấu hình tham số, mẫu thông báo (UC10, BR-QT-13, 14) | QT | `record` | *(QT đặt)* |
| Đổi giá | Đổi giá sản phẩm, dịch vụ (UC29, UC30) | SP | `record` | `PRICE_CHANGED` |
| Giao dịch tiền | Thu tiền (BR-TG-04; Order #8) | TG | `record` | `ORDER_PAID` |
| | Hủy Order `PENDING` khách không thanh toán (BR-BH-05; Order #10) | BH | `record` | *(BH đặt)* |
| | Hủy phiên trả thú (BR-LT-09; Order #9) | LT | `record` | *(LT đặt)* |
| | Chuyển dòng thuốc kê đơn sang mua ngoài lúc thu (BR-BH-04) | BH | `record` | *(BH đặt)* |
| | Đối soát ca thu ngân (Ca thu ngân #6) | TG | `record` | *(TG đặt)* |
| Điều chỉnh kho | Phiếu điều chỉnh tồn (BR-KO-06) | KO | `record` | *(KO đặt)* |
| | Hủy phiếu nhập đã xác nhận (BR-KO-04; Phiếu nhập #4) | KO | `record` | *(KO đặt)* |
| Sửa bệnh án | Bản bổ sung bệnh án sau khi khóa (BR-KB-01) | KB | `record` | *(KB đặt)* |
| Ngoài BR-QT-15 (rule riêng bắt ghi audit) | Sửa hộ email / khôi phục tài khoản (BR-TK-16) | TK | `record` | *(TK đặt)* |
| | Liên kết hồ sơ khách (BR-TK-19) | TK | `record` | *(TK đặt)* |
| | Gỡ hạn chế đặt online sớm (BR-LH-09) | LH | `record` | *(LH đặt)* |
| | Chuyển chủ thú cưng (BR-KH-08) | KH | `record` | *(KH đặt)* |
| | Gán lại lượt đã gọi (BR-TN-08; Visit #4) | TN | `record` | *(TN đặt)* |

---

[← 7. Transaction Management](07-transaction-management.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 9. Testing →](09-testing.md)
