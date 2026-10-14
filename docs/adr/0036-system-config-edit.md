# ADR-0036: Xem và sửa tham số [CFG] qua API (T15 phần 1)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-13
- **Liên quan:**
  - identity-v1 #31 `GET /system-configs`, #32 `PUT /system-configs/{key}` (UC10).
  - BR-QT-13 (02 L56), 15 (L58); BR-TN-03 (L143), BR-LH-08 (L129), BR-LT-02 (L171).
  - `docs/api/00-method.md` L70 (thứ tự kiểm); convention 04 §4.4, 06, 07 §7.1, §7.3, 08 §8.3.
  - Thay gạch đầu dòng "reload ở `afterCommit`" trong mục *TBD* của ADR-0004 và trả hai TBD đó. Liên quan ADR-0007 (một instance), 0015, 0020, 0029 mục 4 (entity cũ trong persistence context), 0032. Nợ mới D018.
- **Triển khai:**
  - `identity/controller/SystemConfigController`.
  - `identity/service/{SystemConfigAdminService, SystemConfigEditService, ConfigValues, ConfigConstraints}`; `SystemConfigService` (dùng `ConfigValues`, kiểm ràng buộc chéo khi nạp, `reload()` `synchronized`).
  - `identity/dto/{SystemConfigResponse, UpdateConfigRequest}`; `identity/mapper/SystemConfigAdminMapper`.
  - `SystemConfigRepository.findByKeyForUpdate`; `SystemConfig.changeValue`; `QtAuditActions.SYSTEM_CONFIG_UPDATED`.
  - Test:
    - Unit: `ConfigValuesTest`, `ConfigConstraintsTest`, `SystemConfigServiceTest` (thêm ca chéo, kích thước cache, reload chồng nhau), `SystemConfigEditServiceTest`, `SystemConfigAdminServiceTest`, `SystemConfigAdminMapperTest`, `UpdateConfigRequestValidationTest`.
    - IT: `module/identity/SystemConfigAdminIT`.
  - Không migration.

## Bối cảnh

ADR-0004 nạp 52 tham số vào cache bộ nhớ lúc khởi động và để lại cho UC10:
- ràng buộc chéo giữa các khóa;
- kiểm min–max, ghi audit, reload "sau khi commit (`TransactionSynchronization.afterCommit`)".

BR-QT-13 yêu cầu:
- từ chối giá trị ngoài khoảng và hiển thị khoảng cho phép;
- giá trị mới chỉ áp dụng cho giao dịch tạo sau.

BR-QT-15 bắt ghi audit mọi thao tác quản trị.

## Quyết định

1. **Ràng buộc chéo: đúng hai cặp**, kiểm cả khi sửa lẫn khi nạp (fail-fast như min–max):
   - `visit.late_priority_minutes` **<** `appointment.no_show_after_minutes`. BR-TN-03 xếp "trễ 15–30 phút" nằm giữa hai mốc; mốc NO_SHOW là BR-LH-08.
   - `boarding.min_nights` **≤** `boarding.max_nights` (BR-LT-02 "từ 1 đến 30 đêm").

   Rà các cặp khác của V2: đã được min–max từng khóa đảm bảo, hoặc đặc tả không buộc thứ tự:

   | Cặp | Vì sao không kiểm |
   |---|---|
   | `appointment.min_lead_hours` (≤ 72 giờ) vs `appointment.max_advance_days` (≥ 7 ngày) | 72 giờ < 7 ngày: min–max tự đảm bảo |
   | `boarding.min_lead_days` (≤ 7) vs `boarding.max_advance_days` (≥ 7) | Tự đảm bảo `≤` |
   | `otp.resend_interval_seconds` (≤ 300 giây) vs `otp.send_window_minutes` (≥ 15 phút) | Tự đảm bảo |
   | `appointment.reminder_hours_before` vs `appointment.late_cancel_hours` | BR-LH-12 còn 🆕; người dùng chọn không kiểm (2026-10-13) |
   | `boarding.overdue_care_task_days` vs `boarding.overdue_manager_alert_days` | BR-LT-10 chỉ là hai mốc độc lập |
2. **Reload bằng facade không transaction.**
   - `SystemConfigAdminService` (không `@Transactional`) gọi `SystemConfigEditService.updateConfig` (`@Transactional`). Khi method đó trả về thì đã commit; facade mới gọi `SystemConfigService.reload()`, rồi controller trả 200.
   - Rollback (method ném lỗi) thì không reload.
   - `reload()` là `synchronized` (gói cả đọc lẫn gán). Mỗi lần reload bắt đầu sau commit của chính nó, nên lần chạy sau cùng thấy mọi commit trước đó; luồng đọc giá trị không lấy monitor.
   - Không dùng `afterCommit`: theo javadoc của Spring, code đọc DB trong `afterCommit` vẫn tham gia transaction đã commit, phải `REQUIRES_NEW`. Cách đó cần connection thứ hai trong lúc connection cũ chưa trả, và trái convention 07. Lỗi ném ra trong đó cũng thành 500 dù DB đã ghi.
3. **Chuẩn hóa và chặt hơn lúc nạp.**
   - `ConfigValues` là bộ parse dùng chung cho nạp và API, nên giá trị API nhận luôn nạp lại được.
   - Dạng lưu: INT `Integer.toString` (`"05"` → `"5"`); DECIMAL `stripTrailingZeros().toPlainString()`; BOOL chỉ `true` / `false`; TIME `HH:mm`.
   - Ở API, TIME chỉ nhận `HH:mm` (D3); DECIMAL không nhận dạng mũ: `1e999999999` chuẩn hóa ra chuỗi hàng tỷ ký tự.
4. **Khóa và thứ tự.**
   - `findByKeyForUpdate` (`FOR NO KEY UPDATE`) cho key và khóa chung cặp, theo key tăng dần — **trước mọi lần đọc dòng đó trong transaction**. Nếu đọc trước, query khóa trả lại instance cũ (ADR-0029 mục 4) và phép kiểm chéo dùng giá trị cũ.
   - Không luồng nào khác khóa `system_configs`, nên không có chu trình chờ.
   - Không đặt `lock_timeout`: transaction rất ngắn, không I/O ngoài, chỉ ADMIN.
5. **Thứ tự kiểm của #32:**
   - Bean Validation (`@NotBlank`, `@Size(max = 255)`).
   - `@PreAuthorize` ADMIN (403 không audit).
   - 404 nếu key không thuộc `ConfigKey` (chưa chạm DB).
   - Khóa → kiểu / min–max / ràng buộc chéo → 400 BR-QT-13.
   - Trùng giá trị → 200, không ghi, không audit.
   - `changeValue` → `flush()` → audit `SYSTEM_CONFIG_UPDATED` (`entity = system_configs`, `entityId` null vì PK là chuỗi; `before` / `after` = `{key, value}`).
6. **Message BR-QT-13** luôn ghi **khoảng cho phép thực tế** (min–max ∩ ràng buộc chéo). Ví dụ: "Giá trị của visit.late_priority_minutes phải là số nguyên từ 0 đến 29 (phút): phải nhỏ hơn appointment.no_show_after_minutes đang là 30 (BR-QT-13)".
   - **D1:** sai kiểu cũng trả BR-QT-13: Bean Validation không biết kiểu của key, và hợp đồng chỉ có một lỗi 400.
   - **D2:** vi phạm ràng buộc chéo cũng trả BR-QT-13.
   - `minValue` / `maxValue` trong response vẫn là khoảng tĩnh. Muốn FE hiện khoảng thu hẹp phải đổi hợp đồng: chưa làm, đã báo FE (T22).
7. **Trùng giá trị** (so sau khi chuẩn hóa): 200, không ghi, không audit, nhưng **vẫn reload**.
8. **Reload lỗi sau commit** (ví dụ DB tạm mất kết nối): log ERROR `SYSTEM_CONFIG_RELOAD_FAILED key=…`, trả 500. DB đã có giá trị mới, cache còn giá trị cũ. ADMIN bấm lại cùng giá trị → nhánh trùng giá trị → reload → cache khớp DB. Khởi động lại cũng nạp đúng.
9. **BR-QT-13 với khóa đọc lúc kiểm tra.** Các khóa `otp.max_failed_attempts`, `login.*`, `address.max_per_customer`, `staff.online_window_minutes`, `vet.bio_max_length`, `password.min_length` là chính sách hiện hành, không phải giao dịch: đổi thì có hiệu lực ngay cho mã OTP đang mở, cửa sổ đếm đăng nhập sai đang chạy… Các giá trị đã chốt vào bản ghi giữ nguyên: `otp_tokens.expires_at`, `sessions.expires_at`, `accounts.pending_expires_at`, `locked_until`. Không migration.
10. **Bộ nhớ.**
    - Cache có đúng một entry mỗi `ConfigKey` (52); dòng DB lạ bị bỏ qua; API không thêm hay xóa được key (404 / 405).
    - Mỗi lần reload thay map bất biến mới; map cũ được GC thu; tối đa một map đang dựng (`synchronized`).
    - Giới hạn kích thước body JSON của cả app là việc khác: nợ D018.
11. **Chỉ một instance** (ADR-0004, ADR-0007): instance khác không được báo reload; khi scale ngang cần ADR mới.

## Hệ quả

- **Tích cực:** ADMIN sửa được [CFG] không cần SQL. Không lưu được giá trị làm app không khởi động được. Đổi giá trị và audit đi cùng nhau; cache chỉ đổi sau commit.
- **Đánh đổi đã chấp nhận:**
  - Bean Validation chạy trước `@PreAuthorize` (D4): non-ADMIN gửi body rỗng nhận 400 thay vì 403. Đây là hành vi chung của repo.
  - Một lần reload lỗi để cache lệch DB tới lần bấm lại hoặc khởi động lại.
  - Đổi các khóa đọc lúc kiểm tra có hiệu lực cả với việc đang dở (mục 9).
- **Mutation đã thử** (mỗi cái làm test đỏ): ghi ở mục *Kiểm chứng* của plan T15 phần 1 và ở system-overview.
