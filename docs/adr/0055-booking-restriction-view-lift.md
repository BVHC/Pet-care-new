# ADR-0055: Xem và gỡ sớm hạn chế đặt online — toàn chuỗi, khóa 1002, 409 khi không hiệu lực, mốc đếm giữ theo ADR-0049

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-19
- **Đánh số lại:** ban đầu là ADR-0050; đổi thành số hiện tại ngày 2026-10-20 khi gộp T25 lên `dev`, vì ADR-0045…0050 (lịch hẹn T16, +4) và ADR-0051 (BE-2) đã chiếm số. Mọi tham chiếu ADR-0047…0052 của T25 trong code / tài liệu đã đổi theo (+5).
- **Liên quan:** ADR-0047 (`isRestricted`), ADR-0049 §5 (`evaluateAfterViolation`, khóa 1002, mốc đếm), ADR-0006 (phạm vi khách), ADR-0028 (cờ chờ liên kết không chặn đọc), ADR-0035 (409 do service tạo cho cờ không có bảng FSM), ADR-0048 (tài khoản khách không có hồ sơ là 500). Trả nợ **D035**; mở nợ **D038**.
- **Triển khai:** T25 phần 3, appointment-v1 #7 `GET /api/me/booking-restriction`, #8 `GET /api/customers/{customerId}/booking-restriction`, #9 `POST /api/customers/{customerId}/booking-restriction/lift`.
  - Code: `appointment/controller/BookingRestrictionController`, `service/BookingRestrictionStatusService`, `BookingRestriction.lift`, `BookingRestrictionRepository.findActive`, `BookingRestrictionApi.findActiveUntil`, `mapper/BookingRestrictionMapper`, `dto/{BookingRestrictionResponse, RestrictionStatusResponse, LiftRestrictionRequest}`, `service/AppointmentAuditActions`.
  - Không migration: dùng index `(customer_id, ends_at)` của V1.

## Bối cảnh

BR-LH-09 (02 L130): "Lễ tân hoặc BRANCH_MANAGER được gỡ hạn chế sớm, có ghi audit"; khi khách bị chặn phải "hiển thị lý do kèm ngày hết hạn chế". Hạn chế đã được tạo thật từ hủy muộn (ADR-0049) và ST05 (ADR-0053), nhưng chưa có cách xem hay gỡ, và message 400 BR-LH-09 không có ngày (D035).

Tài liệu không nói:
- nhân viên chi nhánh nào được gỡ;
- gỡ khi không có hạn chế thì trả gì (hợp đồng chỉ ghi 409);
- sau khi gỡ, vi phạm cũ có bị tính lại không;
- gỡ phải tuần tự với lần đánh giá vi phạm đang chạy như thế nào.

## Quyết định

1. **"Đang hiệu lực" có một định nghĩa duy nhất:** `starts_at ≤ now < ends_at AND lifted_at IS NULL` (erd §5), cài ở `BookingRestrictionRepository.findActive` / `existsActive`.
   - #7, #8, guard đặt online (D035) và luồng gỡ đều dùng nó, nên `restricted` ở GET luôn khớp kết quả đặt online cùng thời điểm.
   - Có tối đa một dòng hiệu lực, vì hạn chế chỉ được tạo dưới khóa 1002 khi chưa có dòng nào hiệu lực. Nếu có hai dòng (dữ liệu hỏng) thì `IncorrectResultSizeDataAccessException` → 500, không chọn bừa.
2. **Phạm vi:**
   - #7: CUSTOMER, `customerId` lấy từ token (`findCustomerIdByAccountId`), không nhận tham số khách. Không chặn khi hồ sơ còn cờ chờ liên kết (chỉ là đọc, như ADR-0028). Tài khoản khách không có hồ sơ → 500 (dữ liệu hỏng, như ADR-0048).
   - #8, #9: chỉ RECEPTIONIST và BRANCH_MANAGER, **toàn chuỗi**, không qua `BranchScope`. Hạn chế gắn với khách, và `booking_restrictions` không có `branch_id`; khách dùng chung toàn chuỗi (04 nguyên tắc 8).
     - Hệ quả đã chấp nhận: nhân viên của bất kỳ chi nhánh nào, kể cả chi nhánh `DRAFT`, cũng gỡ được.
     - BRANCH_MANAGER được gỡ dù không có quyền vào `/api/customers` của module customer (BR-LH-09 cho phép); `customerId` lấy từ danh sách lịch hẹn.
   - SUPER_MANAGER và ADMIN không được: BR-LH-09 không liệt kê. 403 sai vai trò không ghi audit.
3. **Thứ tự của #9** (một `@Transactional`, READ COMMITTED, không `noRollbackFor`, không `catch`):
   1. khách tồn tại (đọc không khóa) → 404 "Không tìm thấy khách hàng #id". Kiểm trước khóa, vì `lockCustomerViolations` từ chối id ≤ 0 bằng lỗi 500;
   2. `AppointmentLocks.lockCustomerViolations`: advisory **1002**, cùng khóa với `evaluateAfterViolation`;
   3. `now` lấy sau khóa, cắt µs;
   4. `findActive(customerId, now)`; rỗng → 409;
   5. `BookingRestriction.lift(now, người gọi, lý do)` → `saveAndFlush`;
   6. audit `BOOKING_RESTRICTION_LIFTED` (`entity = booking_restrictions`, before / after `{liftedAt, liftedBy}`, `reason` = lý do).
   - Gỡ và audit cùng commit hoặc cùng rollback (BR-QT-15).
   - `ends_at` không đổi.
   - Không gửi thông báo cho khách, vì rule không yêu cầu.
   - Response = `restricted = false, restriction = null`, giống GET #8 sau khi gỡ.
4. **Khóa 1002 vẫn là khóa cuối của mọi luồng:**
   - Luồng gỡ chỉ có khóa này, và sau đó chỉ UPDATE dòng `booking_restrictions` (bảng chỉ được ghi dưới 1002).
   - Lần đánh giá giữ khóa dòng lịch hẹn / đặt chỗ → 1002 → INSERT `booking_restrictions`, không UPDATE dòng nào của bảng này.
   - Không có vòng chờ, nên không deadlock.
5. **READ COMMITTED là bắt buộc.** Câu đọc khách ở bước 1 chạy trước khóa. Với REPEATABLE READ, snapshot chốt từ câu đó: lần gỡ đang chờ một lần đánh giá vừa tạo hạn chế sẽ không thấy hạn chế ấy và trả 409 sai. Với READ COMMITTED, bước 4 là câu mới chạy sau khi có khóa nên thấy hàng vừa commit. Đây cũng là lập luận của ADR-0049 §5.
6. **409 bằng `InvalidStateTransitionException("hạn chế đặt online", "không còn hiệu lực", "gỡ sớm")`.**
   - Áp cho các trường hợp: chưa từng bị hạn chế, đã hết hạn, đã gỡ, hoặc chưa bắt đầu.
   - Hạn chế không có bảng FSM ở 03, nên service tự tạo exception, lệch convention 04 L73. Tiền lệ: ADR-0027, ADR-0035 ("chưa khóa → mở khóa").
7. **Mốc đếm vi phạm sau khi gỡ: giữ nguyên ADR-0049 §5** — `since = max(at − window_days, starts_at của lần hạn chế gần nhất, kể cả đã gỡ / hết hạn)`. Người dùng chọn ngày 2026-10-19; PO chưa xác nhận (nợ D038). Hai hệ quả:
   - **Gỡ sớm không làm vi phạm cũ bị tính lại.** Các vi phạm đã gây ra lần hạn chế có thời điểm ≤ `starts_at`, nên không bị đếm lại. Vừa gỡ xong, thêm một vi phạm không làm khách bị hạn chế lại ngay. Đây là chỗ lệch với cách đọc nguyên văn "3 lần trong 90 ngày".
   - **Vi phạm xảy ra trong lúc bị hạn chế vẫn được đếm.** Lúc đó `evaluateAfterViolation` trả về sớm vì đang có hạn chế, nên chúng không tạo hạn chế mới. Nhưng thời điểm của chúng `> starts_at`, nên được tính ở lần đánh giá sau khi gỡ hoặc hết hạn. Ví dụ: 2 NO_SHOW của lịch đặt từ trước, xảy ra khi đang bị hạn chế; gỡ; thêm 1 hủy muộn là bị hạn chế lại.
8. **D035 — message BR-LH-09 khi đặt online:** "Bạn đang bị hạn chế đặt lịch online đến HH:mm dd/MM/yyyy, vui lòng liên hệ chi nhánh để đặt lịch (BR-LH-09)".
   - Giờ Việt Nam. Có cả giờ vì `ends_at` là một thời điểm trong ngày: chỉ ghi ngày thì khách thử lại sớm hơn lúc được phép.
   - `BookingRestrictionApi.findActiveUntil(customerId, at)` (mới, readOnly) trả `ends_at`. Boarding (BE-2) dùng được cho UC58.
   - `isRestricted` giữ nguyên.
9. **Request `LiftRestrictionRequest.reason`:**
   - Bắt buộc, ≤ 300. Nguồn là hợp đồng (`string:300`, không có `?`) và cột `lift_reason VARCHAR(300)`; BR-LH-09 không tự bắt nhập lý do.
   - Compact constructor `strip()` **trước** Bean Validation. Lý do: `@NotBlank` của Hibernate dùng `trim()`, chỉ bỏ ký tự ≤ U+0020, nên chuỗi toàn U+3000 lọt qua rồi lưu thành rỗng nếu chỉ strip trong service. Đã chứng minh bằng `LiftRestrictionRequestValidationTest.notBlankAloneWouldAcceptIdeographicSpace`.
   - `ReasonRequest` (ADR-0035) còn lỗ này, chưa sửa.
10. **Response:**
    - `restriction = null` khi không bị hạn chế: key vẫn có, giá trị `null`. Hợp lệ theo `00-method.md` ("trường không bắt buộc có thể vắng hoặc `null`"), dù YAML khai `$ref` không có `null` (generator chỉ thêm `null` khi có `type`).
    - `violationCount` là số đã đếm khi tạo hạn chế, sau mốc ở mục 7. Mô tả trong hợp đồng đã sửa từ "trong 90 ngày".
    - Không trả `lifted_*`: chỉ dòng hiệu lực được trả, nên các cột này luôn null.
11. **Hợp đồng:**
    - #7 thêm 400 (BR-TK-17); #8 thêm 400 (`MALFORMED_REQUEST`, BR-TK-17), giống #4 ở ADR-0048.
    - #8 / #9 có mô tả riêng cho 403 / 404; #9 có mô tả 400 / 409.
    - Notes ghi phạm vi toàn chuỗi, không thông báo, mốc đếm.

## Phương án đã loại

- **Gỡ = xóa sạch** (mốc `max(starts_at, lifted_at)`): bỏ cả vi phạm trong lúc bị hạn chế. Tài liệu không nói; người dùng chọn giữ ADR-0049.
- **Đếm nguyên văn 90 ngày:** vừa gỡ mà vi phạm một lần là bị hạn chế lại ngay, nên gỡ sớm gần như vô nghĩa.
- **Giới hạn theo chi nhánh** (chỉ chi nhánh có lịch / đặt chỗ của khách): rule không có, và hạn chế không gắn chi nhánh.
- **Khóa dòng `booking_restrictions` (`FOR NO KEY UPDATE`) thay cho 1002:** không chặn được lần đánh giá đang INSERT dòng mới, nên gỡ và tạo có thể chạy chéo.
- **Trả kèm bản ghi vừa gỡ ở #9:** phải đổi schema; GET #8 ngay sau đó đã cho cùng thông tin.

## Hệ quả

- Lần gỡ chờ lần đánh giá đang chạy của cùng khách, việc này ngắn. Hai lần gỡ đồng thời: 200 + 409, đúng một audit.
- Hủy muộn thứ N ∥ gỡ xếp hàng trên 1002:
  - hủy trước → hạn chế được tạo rồi bị gỡ;
  - gỡ trước → 409 và hạn chế mới còn hiệu lực.
- Kiểm chứng:
  - `BookingRestrictionIT` (43 case): hình dạng ≡ SQL; `restricted` ≡ guard đặt online tại biên `ends_at − 1 µs` / `ends_at`; ma trận body / path / vai trò / 401 / BR-TK-17; rollback khi INSERT audit hoặc UPDATE lỗi; 404 / 409 / GET không ghi (`xmin`); 1002 nhả sau rollback; gỡ chờ đánh giá thấy hàng vừa commit; hai lần gỡ; hủy muộn ∥ gỡ theo hai thứ tự, không deadlock; hai hệ quả của mục 7; EXPLAIN dùng `ix_booking_restrictions_customer_ends_at`.
  - Unit: `BookingRestrictionStatusServiceTest`, `BookingRestrictionTest`, `BookingRestrictionMapperTest`, `LiftRestrictionRequestValidationTest`, `BookingRestrictionControllerSecurityTest`, `BookingRestrictionServiceTest`, `AppointmentBookingServiceTest`.
