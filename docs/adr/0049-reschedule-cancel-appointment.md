# ADR-0049: Đổi khung và hủy lịch hẹn — 409 trước rule, trùng khung là no-op, hủy muộn đánh giá hạn chế đặt online dưới khóa theo khách

- **Trạng thái:** Accepted
- **Đánh số lại:** ban đầu là ADR-0045; đổi thành số hiện tại khi gộp `dev` ngày 2026-10-20 vì trùng số với ADR-0041…0044 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0041…0046 trong code / tài liệu lịch hẹn đã đổi theo (+4); migration `V13__appointment_code_sequence.sql` đổi thành V16.
- **Ngày:** 2026-10-15
- **Liên quan:**
  - BR-LH-02 (02 L123), 03 (L124), 04 (L125), 05 (L126), 06 (L127), 07 (L128), 09 (L130), 12 (L133); BR-TK-19 (L35); BR-KH-05 (L91).
  - 03 §3 Lịch hẹn #2, #4, #8 (L82–88).
  - UC40 (01 L105).
  - appointment-v1 #5, #6 (`docs/api/appointment-v1.md`); A4 "lý do hủy không bắt buộc".
  - `docs/api/00-method.md` L69 (thứ tự kiểm); convention 05 L64–82 (guard trước `validateTransition`).
  - [CFG] `appointment.max_reschedules`, `appointment.reschedule_min_lead_hours`, `appointment.late_cancel_hours`, `appointment.min_lead_hours`, `appointment.max_advance_days`, `booking_restriction.violation_threshold`, `booking_restriction.window_days`, `booking_restriction.duration_days`.
  - ADR-0029 §4 (bẫy persistence context), 0031 Q2 (trùng giá trị = 200 không ghi), 0045 (thứ tự khóa, khung transaction; câu hỏi mở L144), 0047 (BR-LH-05, `isRestricted`), 0048 (phạm vi xem, `AppointmentActions`).
  - Nợ: trả D030; mở D033; cập nhật D010, D027, D031, D032.
- **Triển khai (T16 phần 4):**
  - `appointment/controller/AppointmentController` (`POST /api/appointments/{id}/reschedule`, `…/cancel`).
  - `appointment/service/{AppointmentChangeService, AppointmentResponder, BookingRestrictionService, AppointmentLocks}`.
  - `appointment/entity/{Appointment.reschedule, Appointment.cancel, BookingRestriction.start}`.
  - `appointment/repository/{AppointmentRepository, BookingRestrictionRepository}`.
  - `appointment/dto/{RescheduleAppointmentRequest, CancelAppointmentRequest}`.
  - `boarding/api/BoardingQueryApi.countViolations` (javadoc: `> since`).
  - Hợp đồng: `docs/api/generator/m_appointment.py` #5, #6 và dòng phạm vi trong `security`.
  - Test:
    - Unit: `AppointmentChangeServiceTest`, `BookingRestrictionServiceTest`, `AppointmentLocksTest`, `RescheduleCancelRequestValidationTest`, `AppointmentControllerSecurityTest`.
    - IT: `module/appointment/{RescheduleCancelIT, RescheduleCancelPlaceholderIT}`, `OpenApiContractParityIT` (51 endpoint).

## Bối cảnh

Hợp đồng #5, #6 ghi rule, actor, một câu phạm vi và "Lịch không còn BOOKED → 409". Docs để ngỏ:

- Thứ tự 409 với các guard 400 (ADR-0045 L144).
- Đổi sang đúng khung hiện tại.
- Kiểm lại những rule nào của lúc đặt (BR-LH-01, 09, KH-05, TK-19).
- Mốc đếm "3 lần trong 90 ngày" sau khi một lần hạn chế đã kết thúc / bị gỡ sớm.
- Thời điểm của một vi phạm NO_SHOW (bảng không có cột riêng).
- Khóa gì để hai vi phạm đồng thời không tạo hai dòng hạn chế.
- Mốc `reminded_at` khi đổi khung (ST03, BR-LH-12).

## Quyết định

1. **Phân quyền trước mọi khóa** (ADR-0047):
   - Đọc projection không khóa `AppointmentRepository.findAccessRow` → `(customer_id, pet_id, branch_id)`. Ba cột `updatable = false`, nên đọc không khóa vẫn an toàn; là projection nên không nạp entity (bẫy ADR-0029 §4).
   - Khách: `customer_id` phải là hồ sơ của tài khoản; lịch của khách khác = 404 cùng message với lịch không có.
   - Lễ tân: `BranchScope.check(branch_id)` → 403.
   - `switch` đúng hai role, role lạ → 500.
2. **Thứ tự khóa** (ADR-0045 §3, không đổi):
   - Đổi khung: `lockPet` → `lockForBooking` → `findByIdForUpdate` (dòng `appointments`, `FOR NO KEY UPDATE`).
   - Hủy chỉ khóa dòng `appointments`: đoạn cuối của cùng thứ tự. Hủy chỉ trả quota và làm BR-LH-05 lỏng hơn, nên không cần khóa thú / chi nhánh.
   - Khóa theo khách (mục 7) luôn là khóa cuối cùng.
3. **409 ngay sau khi khóa dòng, trước mọi guard 400** — lệch có chủ đích so với `00-method.md` L69 và convention 05:
   - Với lịch đã hủy / đã check-in, các guard như "hết lượt đổi", "còn < 12 giờ" vô nghĩa và chỉ làm FE hiển thị sai lý do.
   - Đổi khung: `validateTransition(status, BOOKED)`.
   - Hủy: ném `InvalidStateTransitionException` **tường minh** khi khác `BOOKED`. FSM có cạnh `CHECKED_IN → CANCELLED` (#8), nhưng cạnh đó chỉ dành cho Visit#6; đi qua FSM thì lịch đã check-in sẽ bị hủy bởi command #4.
4. **Đổi khung (Lịch hẹn#2):**
   - Khung mới trùng khung hiện tại → 200, không ghi, không tăng số lần đổi (khuôn ADR-0031 Q2; bấm hai lần không mất lượt).
   - BR-LH-06: còn lượt (`reschedule_count < max_reschedules`, tính chung khách và lễ tân); khách còn ≥ `reschedule_min_lead_hours` trước giờ hẹn hiện tại (đúng bằng vẫn được, như `AppointmentActions`); lễ tân chỉ cần chưa tới giờ hẹn.
   - Khung mới qua BR-LH-04 (`BookingWindow`, cùng message với đặt lịch) → BR-LH-02 (`SlotGrid`, đọc giờ mở cửa dưới khóa chi nhánh) → BR-LH-05 (trùng khung, trùng nhóm trong ngày, mọi chi nhánh, **không tính chính lịch này**; không đếm số lịch BOOKED vì đổi khung không làm tăng số lịch) → BR-LH-03.
   - **Không kiểm lại** BR-LH-01 (dịch vụ ngừng / tắt sau khi đặt — lịch đã đặt được giữ, giống "giảm quota không ảnh hưởng lịch đã đặt"), BR-LH-09 (rule chỉ áp cho UC39, UC58; "lịch đã đặt trước đó giữ nguyên"), BR-KH-05 (đánh dấu thú mất hủy mọi lịch BOOKED; tranh chấp do D027 lo), BR-TK-19 (rule chỉ ẩn đặt lịch mới).
   - Đổi khung **xóa `reminded_at`** để ST03 nhắc lại đúng 24 giờ trước giờ hẹn mới (BR-LH-12). Nghĩa vụ cho T25: ST03 chỉ quét lịch BOOKED có `reminded_at IS NULL`.
   - Không gọi `PetQueryApi` để kiểm rule; chỉ dùng để lấy tên trong response.
5. **Hủy (Lịch hẹn#4):**
   - `now ≥ giờ hẹn` → 400 BR-LH-07.
   - `late = (giờ hẹn − now) < late_cancel_hours`; đúng bằng 12 giờ → không muộn.
   - `cancel_source` = CUSTOMER (khách) / STAFF (lễ tân), suy ra từ role, không nhận từ client.
   - `reason` không bắt buộc, `strip()`, rỗng → null; body không bắt buộc (`@RequestBody(required = false)`).
   - `now` cắt về **microsecond** như cột `TIMESTAMPTZ`: response map từ entity trong bộ nhớ, không cắt thì POST trả nanosecond còn GET trả microsecond. Cùng giá trị đó dùng cho `starts_at` của lần hạn chế.
6. **Response:** cùng schema `Appointment` như #2–#4, qua `AppointmentResponder`.
   - Đây là lớp hỗ trợ thuần tách khỏi `AppointmentQueryService`: tên theo lô, cờ theo người xem.
   - Mỗi service tự dựng responder từ dependency đã inject, nên `AppointmentQueryServiceTest` giữ nguyên.
   - Message: "Đã đổi khung giờ", "Đã hủy lịch hẹn".
7. **`BookingRestrictionApi.evaluateAfterViolation(customerId, at)`** — `MANDATORY`, gọi sau khi vi phạm đã flush:
   - Khóa advisory theo transaction namespace **1002** (`customer_id % Integer.MAX_VALUE`), luôn là khóa cuối của mọi luồng (hủy muộn, ST05, boarding).
   - Đang có hạn chế hiệu lực → không tạo thêm.
   - Đếm vi phạm có thời điểm **> `since`**, với `since = max(at − window_days, starts_at của lần hạn chế gần nhất — kể cả đã hết hạn / đã gỡ sớm)`. *Giả định, TBD cho PO:* mỗi vi phạm chỉ góp vào một lần hạn chế; vừa hết hạn chế hay vừa được gỡ sớm thì không bị hạn chế lại chỉ vì vi phạm cũ còn trong 90 ngày.
   - Vi phạm lịch hẹn: `CANCELLED AND late_cancel` theo `cancelled_at`; `NO_SHOW` theo giờ hẹn `(slot_date + slot_start) AT TIME ZONE 'Asia/Ho_Chi_Minh'` (bảng không có cột thời điểm NO_SHOW; ST05 đánh dấu ở giờ hẹn + 30 phút nên lệch ≤ 30 phút, chấp nhận).
   - Cộng `BoardingQueryApi.countViolations(customerId, since)`; hợp đồng đổi thành "`> since`" (loại trừ).
   - Đủ `violation_threshold` → INSERT hạn chế `[at, at + duration_days)`, `violation_count` = số đếm.
   - Loại trừ (`>`) vì vi phạm gây ra lần hạn chế có cùng `at` với `starts_at`, nên không bị đếm lại.
   - Isolation phải là READ COMMITTED (mặc định): vi phạm thứ hai chờ khóa 1002, câu đếm là câu lệnh mới sau khi có khóa nên thấy vi phạm thứ nhất vừa commit. Với REPEATABLE READ thì đếm thiếu và không ai tạo hạn chế.
   - Lỗi trong bước này (kể cả `BoardingQueryApi` còn placeholder) làm rollback cả lần hủy: không có lịch hủy muộn nào bỏ qua bước đánh giá (06 §1 cấm giá trị "an toàn").
8. **Transaction:**
   - Mỗi command một `@Transactional` REQUIRED, không readOnly, không `noRollbackFor`, không `try/catch`.
   - Ghi bằng `saveAndFlush`, nên lỗi DB lộ ra trong service.
   - Không có ràng buộc `DEFERRABLE` nào trong migration.
   - Không audit (BR-QT-15 không liệt kê đổi / hủy lịch hẹn), không thông báo, không sự kiện.
9. **Không migration.** Index dùng được đều có ở V1: `ix_appointments_pet_status`, `ix_appointments_quota`, `(customer_id, created_at)`, `(customer_id, ends_at)`.

## Hệ quả

- **Chạy trên app thật** (placeholder của BE-2, `RescheduleCancelPlaceholderIT`):
  - Khách luôn 500 ở bước đọc hồ sơ (D010).
  - Lễ tân 500 ở bước lấy tên cho response (D031); lịch y nguyên vì UPDATE đã flush bị rollback.
  - Hủy muộn còn 500 ở `BoardingQueryApi.countViolations` (D033) — **mọi lần hủy khi còn < 12 giờ, của cả khách lẫn lễ tân**, tới khi BE-2 cài (06 §6: 22/10).
  - Các nhánh dừng trước những bước đó (403, 404, 409, 400 BR) trả đúng mã.
- **Không đảm bảo:**
  - Đổi khung / hủy ∥ đánh dấu thú mất / chuyển chủ (T17 chưa lấy khóa 1001 — D027).
  - Chủ cũ sau chuyển chủ vẫn đổi / hủy được lịch mình đã đặt cho tới khi T17 hủy các lịch BOOKED (BR-KH-08).
  - Danh sách #3 lật trang trong lúc có người đổi khung có thể lặp / sót một dòng (OFFSET, D032).
- `reason` gửi dạng số / bool bị Jackson ép thành chuỗi (mặc định, giống `note` của #2); không chặn.
- **Nghĩa vụ cho T25 / T26 / boarding** (ghi ở 06 §4):
  - Ghi dòng `appointments` theo thứ tự `advisory(thú) → branches → appointments`.
  - Gọi `evaluateAfterViolation` sau khi vi phạm đã flush, trong cùng transaction, không bắt lỗi của nó.
  - ST03 quét `reminded_at IS NULL`.

## Phương án đã loại

- **Khóa dòng `appointments` trước rồi mới khóa thú / chi nhánh** (bản nháp ban đầu): đảo thứ tự ADR-0045, có thể deadlock với đặt lịch, ST05, check-in.
- **Guard 400 trước 409** (convention 05): lịch đã hủy trả "hết lượt đổi" / "còn < 12 giờ", sai lý do.
- **Trùng khung → 400:** bấm hai lần thì lần sau báo lỗi dù thao tác đã thành công.
- **Đếm nguyên văn 90 ngày:** vừa hết hạn chế (hoặc vừa được gỡ sớm) mà vi phạm một lần là bị hạn chế lại ngay.
- **Không khóa, dựa vào UNIQUE:** `booking_restrictions` không có ràng buộc ngăn hai dòng hiệu lực chồng nhau; thêm cần migration và vẫn phải đếm dưới khóa.
- **Để `evaluateAfterViolation` ném lỗi (D030), hoặc tách sang phần 4b:** hủy muộn 500 mà không có thiết kế; nợ dồn sang ST05.
