[← Backend Convention Index](INDEX.md)

# 6. Validation

| Layer | Trách nhiệm | Cơ chế | Lỗi trả client |
|---|---|---|---|
| Controller | Hình thức input: null, độ dài, định dạng, khoảng số | Bean Validation (`@Valid`, `@NotNull`, `@Size`, `@Email`, `@Positive`…) trên record request | 400 `VALIDATION_FAILED`, message `"field: lỗi; field: lỗi"` |
| Service / TransitionHandler | Toàn bộ business rule `BR-…` của `02-business-rules.md` và cột *Điều kiện* của `03-state-machines.md` | Kiểm tra thủ công ngay trước thao tác ghi, trong cùng transaction | 400 `BUSINESS_RULE_VIOLATION`, 409 `INVALID_STATE_TRANSITION`, 403, 404 ([04](04-exception-handling.md)) |
| Database | Lưới an toàn: `CHECK`, `UNIQUE`, partial unique, `EXCLUDE` trong `V1__init_schema.sql` | Constraint | 409 `CONCURRENCY_CONFLICT`, message chung |

- Controller **không bao giờ** biết mã `BR-…`.
- Ràng buộc DB không thay cho kiểm tra ở service: client chỉ nhận message chung khi DB chặn. Service phải kiểm tra trước để trả đúng mã rule; constraint chỉ bắt trường hợp hai request chạy song song.
- Kiểm tra hình thức có trong rule (mật khẩu ≥ 8 ký tự [CFG] — BR-TK-03, mô tả bác sĩ ≤ 500 ký tự [CFG] — BR-TK-20…) mà phụ thuộc tham số [CFG] thì kiểm ở service, vì annotation không đọc được giá trị cấu hình.

```java
@PostMapping("/appointments")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest req) {
    return ApiResponse.created(appointmentService.bookAppointment(req), "Đặt lịch thành công");
}

// Service — Lịch hẹn#1 (BR-LH-01…05, 09)
@Transactional
public AppointmentResponse bookAppointment(BookAppointmentRequest req) {
    checkServiceBookable(req);        // BR-LH-01
    checkBookingWindow(req);          // BR-LH-04
    checkPetLimits(req);              // BR-LH-05
    checkSlotQuota(req);              // BR-LH-03 — cần khóa đồng thời, xem 07
    handler.validateInitial(AppointmentStatus.BOOKED);
    ...
}
```

## Tham số [CFG]

- Con số ghi **[CFG]** trong đặc tả là giá trị mặc định; ADMIN đổi được (BR-QT-13). Không hard-code trong code nghiệp vụ: inject `identity.api.SystemConfigApi` và đọc bằng `getInt/getDecimal/getBool/getTime(ConfigKey.X)` ([ADR-0004](../../adr/0004-system-config-reading.md)). Danh mục đầy đủ là enum `ConfigKey`, seed ở `V2__seed_system_configs.sql`; tham số mới = hằng số mới + migration seed mới.
- Giá trị phải "chỉ áp dụng cho giao dịch tạo sau" thì chốt vào bản ghi lúc tạo, như `otp_tokens.expires_at` (erd L139, L179), `sessions.expires_at` (ADR-0003).

## Cảnh báo, không chặn

Nhiều rule ghi ở cột *Xử lý khi vi phạm* là "cảnh báo, không chặn". Các rule này **không ném exception**; thao tác vẫn thành công và client phải nhận được cảnh báo.

| Rule | Cảnh báo |
|---|---|
| BR-KH-02 | Chủ đã có thú cùng tên, cùng loài (nghi trùng) |
| BR-KH-10 | SĐT đã có ở hồ sơ khác (nghi trùng hồ sơ) |
| BR-TN-06 | Gán lượt cho nhân viên đang offline |
| BR-KB-04 | Thú chưa đủ tuổi tối thiểu của mũi tiêm |
| BR-LT-05 | Lúc đặt chỗ: dự kiến chưa đủ mũi bắt buộc vào ngày nhận |
| BR-LT-01 | Chuyển chuồng sang bảo trì làm thiếu chỗ cho đặt chỗ đã có |
| BR-BH-04 | Thêm dòng bán lẻ khi thiếu tồn khả dụng |

Hình thức trả cảnh báo về client (trường trong `ApiResponse`, bước xác nhận hai lần…) **chưa chốt**, cần ADR trước khi cài module đầu tiên có cảnh báo.

---

[← 5. FSM Implementation Pattern](05-fsm-pattern.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 7. Transaction Management →](07-transaction-management.md)
