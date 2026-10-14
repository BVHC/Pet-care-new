# ADR-0037: Xem, sửa và khôi phục mẫu thông báo qua API (T15 phần 2)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-13
- **Liên quan:**
  - identity-v1 #33 `GET /notification-templates`, #34 `PUT /notification-templates/{code}`, #35 `POST /notification-templates/{code}/restore-default` (UC10).
  - BR-QT-14 (02 L57), 15 (L58), 16 (L59).
  - 06 §8 Q4 (mẫu `_APP` bắt buộc có `subject`); erd §1 `notification_templates`.
  - Convention 04, 06, 07 §7.1, 08 §8.3.
  - ADR-0012 (worker ST20 render lúc gửi), 0014 (IN_APP), 0020, 0029 mục 4, 0036 (khuôn sửa [CFG]). Nợ mới D019; mở rộng D018.
- **Triển khai:**
  - `identity/controller/NotificationTemplateController`.
  - `identity/service/{NotificationTemplateAdminService, TemplateVariables}`.
  - `identity/dto/{NotificationTemplateResponse, UpdateTemplateRequest}`; `identity/mapper/NotificationTemplateAdminMapper`.
  - `NotificationTemplateRepository.findByCodeForUpdate`; `NotificationTemplate.{changeContent, restoreDefault, isDefault, hasContent}`; `QtAuditActions.{NOTIFICATION_TEMPLATE_UPDATED, NOTIFICATION_TEMPLATE_RESTORED}`.
  - Test:
    - Unit: `TemplateVariablesTest`, `NotificationTemplateSyntaxParityTest`, `NotificationTemplateAdminServiceTest`, `NotificationTemplateTest`, `NotificationTemplateAdminMapperTest`, `UpdateTemplateRequestValidationTest`, `NotificationDispatchServiceTest.lineBreakInSubjectCannotInjectHeaders`.
    - IT: `module/identity/NotificationTemplateAdminIT`.
  - Không migration. Không sửa code `care/`.

## Bối cảnh

Mẫu thông báo được seed dần (V3–V12, 06 §8 Q4) và worker ST20 render **lúc gửi** bằng `care/service/NotificationTemplateRenderer`. Renderer thay đúng chuỗi `{ten}` cho từng `ten ∈ allowedVars` bằng `String.replace`; mọi ngoặc khác giữ nguyên chữ, vì nó giả định "UC10 chặn lúc lưu mẫu".

BR-QT-14 yêu cầu:
- mẫu không thêm, không xóa, chỉ sửa nội dung;
- chỉ dùng biến có sẵn, đủ biến bắt buộc;
- từ chối và "chỉ rõ biến bị lỗi";
- luôn khôi phục được mẫu mặc định.

BR-QT-15 bắt audit mọi thao tác quản trị.

## Quyết định

1. **Chỉ ADMIN** (UC10, actor A03): `@PreAuthorize`; role khác → 403 `ACCESS_DENIED`, không audit. Không có endpoint thêm / xóa (405).

2. **Không facade, mỗi thao tác ghi một `@Transactional`** (không `noRollbackFor`, không `catch`): không BCrypt, không cache, không có 403 phải audit sau rollback. Thứ tự #34:
   1. hình thức (Bean Validation, trước `@PreAuthorize` như tiền lệ);
   2. `findByCodeForUpdate` (`FOR NO KEY UPDATE`) **trước mọi lần đọc dòng đó** (ADR-0029 mục 4) → không có → 404 "Không tìm thấy mẫu thông báo #X";
   3. quét biến trên dòng đã khóa → 400 BR-QT-14;
   4. ghi → `flush()` → audit → map response.

   #35 giống hệt, bỏ bước 3.

3. **Ngữ pháp biến chặt.** Dấu ngoặc nhọn chỉ dùng để viết `{ten}` với `ten ∈ allowed_vars`, khớp chính xác và phân biệt hoa thường. Mọi `{` / `}` khác là lỗi BR-QT-14: `{abc}`, `{ Ma_OTP }`, `{}`, `{{ma_otp}}`, `{ma_otp`, `}` lẻ. Hệ quả: không viết được ngoặc nhọn dạng chữ; đổi lại, nội dung gửi đi không bao giờ lộ dấu ngoặc hay biến chưa thay. Biến bắt buộc tính trên **tiêu đề ∪ nội dung**.

   Message nêu **đủ** mọi biến lỗi, không trùng, theo thứ tự xuất hiện, không cắt bớt (BR-QT-14 "chỉ rõ biến bị lỗi"). Phần nào lỗi thì nêu phần đó, gom vào một message, ví dụ: "Tiêu đề dùng biến không có: {abc}; Nội dung có dấu { hoặc } lẻ — …; Biến dùng được: {…}; Mẫu thiếu biến bắt buộc: {ma_otp} (BR-QT-14)".

4. **Bộ quét ở identity, renderer giữ nguyên, khớp nhau bằng parity test.** `TemplateVariables` (package-private) không đặt ở `identity.api`, vì `api/` chỉ chứa interface, record, enum, event (06 §1). `NotificationTemplateSyntaxParityTest` (chỉ ở test) chứng minh, trên 20 000 chuỗi sinh với seed cố định cùng các ca biên, với payload gán mỗi biến một giá trị canh không chứa ngoặc:
   - bộ quét chấp nhận ⇔ nội dung render không còn `{` / `}`;
   - biến `a` được quét thấy ⇔ giá trị canh của `a` xuất hiện.

5. **Bản mặc định không quét lại khi khôi phục.** IT `everySeededDefaultPassesValidation` PUT lại `default_*` của mọi mẫu seed và đòi 200. Seed mới vi phạm ngữ pháp thì IT đỏ ngay trong PR thêm seed.

6. **Hình thức: tiêu đề bắt buộc với mọi kênh, ≤ 255** (cột `subject`); nội dung bắt buộc, không giới hạn độ dài (cột `TEXT`). 06 §8 Q4 chỉ bắt `subject` cho mẫu `_APP`; người dùng chốt mở rộng cho EMAIL, vì thư không tiêu đề dễ bị coi là spam và mọi seed đều có tiêu đề. Hợp đồng `UpdateTemplateRequest.subject` đổi `subject?` → `subject`. Thiếu hoặc rỗng → 400 `VALIDATION_FAILED`, không phải BR-QT-14.

   **Không** cấm xuống dòng trong tiêu đề. Đã kiểm bằng scratch test trên `org.eclipse.angus:jakarta.mail` 2.0.5: `setSubject("Mã\r\nBcc: x")` cho ra một dòng tiếp nối, `getHeader("Bcc") == null`, vì thư viện tự gấp dòng. `NotificationDispatchServiceTest.lineBreakInSubjectCannotInjectHeaders` khóa hành vi này nếu đổi thư viện mail. Tiêu đề IN_APP dài quá 200 ký tự sau khi render bị worker cắt (ADR-0014), nên chấp nhận tới 255.

7. **Lưu nguyên văn.** Không `strip`, không đổi `\r\n`. `isDefault` và việc so trùng đều theo từng ký tự. `@NotBlank` vẫn từ chối chuỗi toàn khoảng trắng; đây là kiểm hình thức, không phải chuẩn hóa.

8. **Nội dung trùng bản đang dùng (#34) / mẫu đã là mặc định (#35):** 200, **không UPDATE dòng** (`updated_at`, `updated_by` giữ nguyên) nhưng **vẫn audit** với `before` = `after`. Đây là cách hiểu thận trọng của BR-QT-15 "mọi thao tác thuộc module quản trị", **khác** ADR-0036 (trùng giá trị [CFG] không audit). Người dùng chốt 2026-10-13.

9. **Audit:** `NOTIFICATION_TEMPLATE_UPDATED` / `NOTIFICATION_TEMPLATE_RESTORED`, `entity = notification_templates`, `entityId` null (PK chuỗi, convention 08 L44). `before` / `after` là `{code, subject, body}` nguyên văn. Snapshot lớn bằng nội dung mẫu, mà nội dung chỉ bị giới hạn bởi D018.

10. **Hiệu lực:** worker đọc mẫu bằng transaction readOnly riêng (READ COMMITTED).
    - Bản sửa chưa commit hoặc đã rollback không bao giờ được gửi.
    - Mọi lần đọc sau commit dùng bản mới, kể cả dòng outbox đã xếp hàng trước khi sửa. Biến bắt buộc không đổi nên payload cũ vẫn render được.
    - Lượt gửi đã đọc mẫu trước commit vẫn gửi bản cũ.

11. **Đồng thời:** hai ADMIN sửa cùng mẫu thì tuần tự trên khóa dòng. Người sau ghi đè (không If-Match, cùng mức ADR-0036). `before` của lần sau = `after` của lần trước, vì dòng chỉ được nạp lần đầu bằng câu khóa. Dự án không cấu hình `lock_timeout`, nên người sau đứng chờ. Mỗi transaction chỉ khóa một dòng nên không có deadlock giữa các lần sửa mẫu.

## Hệ quả

- **Response:** `NotificationTemplateResponse` gồm 7 khóa của hợp đồng, `channel` là enum `NotificationApi.Channel`. Không trả `default_*`, `updated_by`, thời gian.
- **Request → entity:** đi qua lệnh `changeContent` của entity, nên không có đường nào ghi `channel`, `allowed_vars`, `required_vars`, `default_*`; trường lạ bị Jackson bỏ qua.
- **Commit / rollback:**
  - Mọi lỗi sau khi đã UPDATE (audit, map, commit) đều rollback cả UPDATE. Client chỉ nhận 200 khi đã commit; chứng minh bằng trigger hoãn tới COMMIT trong IT.
  - Mutation đã chạy:
    - đọc entity không khóa trước khi khóa → IT đồng thời đỏ;
    - bỏ `flush()` → unit `InOrder` đỏ;
    - bỏ `@Transactional` → IT đỏ;
    - thêm `noRollbackFor` → test reflection đỏ.
  - `try/catch` quanh `audit.record` **không** làm IT nào đỏ, và cũng không gây hại: proxy `MANDATORY` của `AuditRecorder` đã đánh dấu rollback-only, nên commit vẫn rollback và trả 500. `noRollbackFor` cũng không làm IT đỏ vì cùng lý do; chỉ test reflection giữ nó.
- **Nợ:**
  - **D019:** renderer thay tuần tự, nên giá trị payload chứa `{bien_sau}` bị thay ở lượt sau (ví dụ `ho_ten` = "{ly_do}" trong `ACCOUNT_LOCKED_FOLLOW_UP_APP`). Mức thấp; trả bằng cách cho renderer quét một lượt theo ngữ pháp mục 3 và giữ parity test.
  - **D018** mở rộng: nội dung mẫu không giới hạn, kéo theo message BR-QT-14 và snapshot audit.
- **FE (T22):** chưa gọi `/notification-templates`; khi làm phải gửi `subject` cho mọi kênh và hiển thị nguyên message BR-QT-14.
