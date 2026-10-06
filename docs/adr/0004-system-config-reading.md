# ADR-0004: Đọc tham số [CFG] bằng `ConfigKey` + cache trong bộ nhớ, seed toàn bộ bằng Flyway

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-04
- **Liên quan:**
  - `docs/02-business-rules.md` quy ước [CFG] (L9), BR-QT-13.
  - `docs/05-erd.md` bảng `system_configs`.
  - Convention 03 (khóa `<nhóm>.<tên>`), 06 (mục [CFG]).
  - `docs/06-module-contracts.md` §1 (`SystemConfigApi`).
  - ADR-0003 (`session.ttl_hours`).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/identity/api/` (`ConfigKey`, `ConfigValueType`, `SystemConfigApi`).
  - `module/identity/entity/SystemConfig`, `repository/SystemConfigRepository`, `service/SystemConfigService`.
  - `BE/src/main/resources/db/migration/V2__seed_system_configs.sql`.
  - Test: `ConfigKeyTest`, `SystemConfigServiceTest`, `SystemConfigIT`, `SchemaMigrationIT`.

## Bối cảnh

Đặc tả đánh dấu các con số ADMIN đổi được bằng **[CFG]**; số ghi trong 02 là giá trị mặc định (L9). BR-QT-13 đặt thêm hai yêu cầu:
- Mỗi tham số có khoảng hợp lệ min–max.
- Giá trị mới chỉ áp dụng cho giao dịch tạo sau.

Bảng `system_configs` (key, value, value_type `INT|DECIMAL|BOOL|TIME`, min, max, unit, description) đã có trong V1 nhưng chưa có dữ liệu. Convention 06 và `06-module-contracts.md` §1 để TBD "đọc thế nào", trong khi mọi module đều cần `SystemConfigApi` từ 06/10.

Có hai điểm tài liệu không nói rõ:
- Khoảng min–max của hầu hết tham số (chỉ có ví dụ OTP 1–15 phút).
- Marker [CFG] đứng sau một mệnh đề có nhiều số, ví dụ BR-TK-07 "60 giây … 5 OTP trong 1 giờ [CFG]", BR-TK-09 "5 lần … 15 phút [CFG] … khóa 15 phút".

## Quyết định

1. **Danh mục trong code:** enum `identity.api.ConfigKey`, mỗi hằng số gồm khóa chuỗi (`<nhóm>.<tên>`, convention 03) và `ConfigValueType`. `SystemConfigApi` nhận `ConfigKey`:
   - `getInt`, `getDecimal`, `getBool`, `getTime`.
   - Gọi getter khác kiểu của khóa là lỗi lập trình → `IllegalArgumentException`.
2. **Seed toàn bộ bằng Flyway:** `V2__seed_system_configs.sql` có 52 dòng: 51 tham số của 02 cộng `session.ttl_hours` (ADR-0003).
   - Marker [CFG] sau một mệnh đề nhiều số thì **mọi số trong mệnh đề đều là tham số**.
   - Số trùng nghĩa với khóa đã có thì dùng lại khóa đó:
     - "dưới 12 giờ" của BR-LH-06 = `appointment.late_cancel_hours` (BR-LH-07).
     - "quá 30 phút" của BR-TN-03 = `appointment.no_show_after_minutes` (BR-LH-08).
   - Số nằm ngoài mệnh đề có marker **không** phải tham số: 18 tuổi (BR-TK-02), 10–2000 ký tự và mức 1–5 của feedback (BR-DG-01), khung 30 phút (BR-LH-02).
   - Khoảng min–max do nhóm đề xuất và duyệt ngày 2026-10-04. Bảng đầy đủ nằm trong file V2 và trong `SystemConfigIT`.
3. **Cache trong bộ nhớ:** `SystemConfigService` nạp toàn bộ ở `afterPropertiesSet` (sau Flyway). Mọi điều kiện sau đều phải đúng, nếu không app **dừng khởi động** và liệt kê mọi khóa lỗi:
   - DB có đủ mọi `ConfigKey`;
   - đúng `value_type`;
   - giá trị parse được (TIME dạng `HH:mm`, BOOL chỉ nhận `true`/`false`);
   - giá trị nằm trong min–max.

   Khóa có trong DB mà không có trong enum chỉ log WARN.

   Map bất biến lưu trong biến `volatile` và được thay nguyên khối. `reload()` không hợp lệ thì giữ nguyên bản cũ và ném exception.
4. **"Chỉ áp dụng cho giao dịch tạo sau" (BR-QT-13):** caller chốt giá trị vào bản ghi lúc tạo, ví dụ `sessions.expires_at`, `otp_tokens.expires_at`. `SystemConfigApi` luôn trả giá trị hiện hành.
5. **Thêm tham số:** thêm hằng số vào `ConfigKey` và một migration INSERT mới; không sửa V2. Không hard-code số [CFG] trong code nghiệp vụ (convention 06).

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| `String key` (chữ ký PROPOSED cũ) | Gõ sai khóa hoặc đọc sai kiểu chỉ lộ ra lúc chạy (500); không kiểm tra được lúc khởi động là DB đủ khóa |
| Query DB mỗi lần đọc | Thêm truy vấn ở đường nóng (sinh khung giờ, kiểm tra OTP…), trong khi giá trị hầu như không đổi |
| Cache Redis | Thêm phụ thuộc runtime cho bảng 52 dòng; app hiện chạy một instance |
| Seed theo từng module khi cài | Nhiều migration nhỏ, dễ trùng số V giữa BE-1/BE-2; danh mục [CFG] cần duyệt một lần (timeline T1) |
| Chỉ số đứng ngay trước marker là [CFG] | Bỏ sót số ADMIN có lý do để chỉnh (thời gian khóa tạm, khoảng cách gửi OTP) và buộc hard-code chúng |

## Hệ quả

- **Tích cực:**
  - Không có [CFG] nào hard-code. Sai seed thì phát hiện ngay lúc khởi động, không đợi tới lúc gọi.
  - Đọc giá trị không chạm DB.
  - Một danh mục duy nhất cho code (`ConfigKey`), seed (V2) và test (`SystemConfigIT`).
- **Đánh đổi đã chấp nhận:**
  - Nhiều instance sẽ cần cơ chế báo nhau reload. Hiện app chạy một instance; khi scale ngang phải có ADR mới.
  - Đổi chữ ký `SystemConfigApi` sang `ConfigKey` cần báo BE-2 (06 §1). Tại thời điểm này chưa có caller.
  - `audit.retention_years` chưa có code dùng: chưa có đường xóa audit nào (ADR-0001).
- **TBD:**
  - **Ràng buộc chéo giữa các khóa** chưa kiểm tra, ví dụ `visit.late_priority_minutes` < `appointment.no_show_after_minutes` (BR-TN-03 xếp "trễ 15–30 phút" giữa hai mốc này), `boarding.min_nights` ≤ `boarding.max_nights`. Task API sửa tham số (UC10, 14/10) phải quyết định trước khi cho ADMIN sửa.
  - **Task UC10** cũng phải kiểm tra min–max trước khi lưu (BR-QT-13), ghi audit, rồi gọi `SystemConfigService.reload()` **sau khi commit** (`TransactionSynchronization.afterCommit`).
