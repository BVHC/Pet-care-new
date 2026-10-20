# ADR-0047: Đặt lịch hẹn — đọc thú sau khóa thú, thú của khách khác là 404, BR-LH-05 tính lịch đang chiếm chỗ, chưa nối Care Task#6

- **Trạng thái:** Accepted
- **Đánh số lại:** ban đầu là ADR-0043; đổi thành số hiện tại khi gộp `dev` ngày 2026-10-20 vì trùng số với ADR-0041…0044 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0041…0046 trong code / tài liệu lịch hẹn đã đổi theo (+4); migration `V13__appointment_code_sequence.sql` đổi thành V16.
- **Ngày:** 2026-10-15
- **Thay một phần:** ADR-0045 §1, câu "Phân quyền (`@PreAuthorize`, phạm vi khách / chi nhánh) **xong trước** khi lấy khóa nào". Từ ADR này, bước kiểm "thú có thuộc khách không" chạy **ngay sau** `lockPet`. Mọi phần khác của ADR-0045 giữ nguyên.
- **Liên quan:**
  - BR-LH-01 (02 L122), 02 (L123), 03 (L124), 04 (L125), 05 (L126), 09 (L130); BR-KH-05 (L91); BR-TK-19 (L35); BR-TB-06 (L254); BR-QT-15 (L58).
  - 03 §3 Lịch hẹn #1 (L81).
  - UC39 (01 L104).
  - appointment-v1 #2 (`docs/api/appointment-v1.md` L57, ma trận L39, A2 L98); `docs/api/00-method.md` L66 (404 khi người gọi không được biết là có), L69 (thứ tự kiểm).
  - erd §5 `appointments`, `booking_restrictions` ("Khách đang bị hạn chế ⇔ …").
  - ADR-0006 (phạm vi khách), 0020 (OSIV tắt), 0032 (số thực vào trường số nguyên), 0045 (khóa, mã, khung transaction), 0046 (`BookingWindow`). Nợ D027, D028, D029, D030, D031.
- **Triển khai (T16 phần 2):**
  - `appointment/controller/AppointmentController` (`POST /api/appointments`).
  - `appointment/service/{AppointmentBookingService, AppointmentActions, BookingRestrictionService}`.
  - `appointment/dto/{BookAppointmentRequest, AppointmentResponse}`, `appointment/mapper/AppointmentMapper`.
  - `appointment/entity/BookingRestriction`, `appointment/repository/BookingRestrictionRepository`.
  - `Appointment.book(...)`; `AppointmentRepository.{countBookedByPet, existsActiveSameSlot, existsActiveSameGroupDay, countActiveInSlot}`.
  - Test:
    - Unit: `AppointmentBookingServiceTest`, `AppointmentActionsTest`, `AppointmentMapperTest`, `BookAppointmentRequestValidationTest`, `BookingRestrictionServiceTest`, `AppointmentControllerSecurityTest`.
    - IT: `module/appointment/{BookAppointmentIT, BookAppointmentPlaceholderIT, JdbcPetCustomerModule}`.

## Bối cảnh

Phần 0 (ADR-0045) đã có khóa thú, khóa chi nhánh, mã lịch hẹn và khung transaction. Phần 1 (ADR-0046) có cửa sổ BR-LH-04. ADR-0045 để lại ba câu hỏi cho phần 2:

1. Hợp đồng #2 ghi `403` khi thú không thuộc khách, còn 00-method L66 ghi 404.
2. Có gọi `FollowUpApi` (Care Task#6) hay không.
3. (Phần 4) đổi khung / hủy lịch không còn BOOKED trả 400 hay 409.

Khi lập plan còn phát hiện thêm:

- **`PetQueryApi` là của BE-2, chưa có bản thật.** Nếu bản thật nạp entity `Pet` (`findById`), thì trong cùng transaction lần đọc thứ hai trả lại đúng object đã nạp mà không truy vấn lại. Đọc thú để phân quyền **trước** `lockPet` rồi đọc lại **sau** khóa thì không thấy thay đổi đã commit trong lúc chờ. Khi đó khóa thú mà D027 yêu cầu BE-2 lấy sẽ không còn tác dụng.
- **404 của chi nhánh chỉ biết được sau `lockForBooking`** (ADR-0045 §2: không gọi `BranchQueryApi.*` trước khóa, vì `findBranch` nạp entity `Branch`). Muốn giữ "404 trước 400" (00-method L69), mọi guard BR phải chạy sau khóa.
- BR-LH-05 chỉ ghi rõ "tối đa 2 lịch `BOOKED`". Hai mệnh đề "không trùng nhóm trong ngày" và "không trùng khung" không nói tính trạng thái nào.
- Chưa có bean nào cài `BookingRestrictionApi` hay `FollowUpApi`.

## Quyết định

1. **Thứ tự kiểm** (một `@Transactional` ghi, không `noRollbackFor`, theo khung ADR-0045 §4):
   1. Bean Validation (400). Chạy trước `@PreAuthorize`, như ADR-0028.
   2. `@PreAuthorize("hasAnyRole('CUSTOMER','RECEPTIONIST')")` (403, không audit).
   3. Lễ tân: `BranchScope.check(branchId)` (403, trước mọi lần đọc DB / khóa). Khách: `findCustomerIdByAccountId`; không có → `IllegalStateException` 500, vì BR-KH-01 nói tài khoản khách luôn có hồ sơ.
   4. `lockPet`.
   5. **Đọc thú một lần**: không có, hoặc khách mà không phải chủ → 404.
   6. Đọc dịch vụ → 404.
   7. `lockForBooking`: không có, hoặc khách + `DRAFT` → 404 (như ADR-0046).
   8. `now = clock.instant()`, lấy **sau** khi đã khóa xong.
   9. Guard 400, theo đúng thứ tự:
      1. BR-LH-01: lễ tân + `DRAFT` → "Chi nhánh chưa nhận đặt lịch"; dịch vụ ngừng / nhóm ∉ {MEDICAL, GROOMING} / chưa bật → "Dịch vụ không đặt được tại chi nhánh này".
      2. BR-TK-19 (chỉ khách).
      3. BR-LH-09 (chỉ khách).
      4. BR-KH-05.
      5. BR-LH-04 (`BookingWindow`).
      6. BR-LH-02 (`SlotGrid.isSlotStart` trên `openingRangesOn`; ngày nghỉ cho danh sách rỗng).
      7. BR-LH-05 (a) → (b) → (c).
      8. BR-LH-03.
   10. `validateInitial(BOOKED)` → `nextCode()` → `Appointment.book` → `saveAndFlush`.
   11. Map trong transaction → `201`, message "Đã đặt lịch hẹn".
2. **Thú của khách khác → 404**, cùng message với thú không tồn tại ("Không tìm thấy thú cưng #id"). Hợp đồng #2 sửa theo:
   - 403 chỉ còn "Lễ tân đặt lịch cho chi nhánh khác";
   - 404 = "Thú cưng (kể cả thú của khách khác), dịch vụ hoặc chi nhánh không tồn tại (khách: cả chi nhánh chưa hoạt động)".

   Lễ tân đặt được cho thú của mọi chi nhánh (Pet dùng chung toàn chuỗi, 04 nguyên tắc 8); khách của lịch là chủ hiện tại của thú (A2).
3. **BR-LH-05:**
   - (a) số lịch `BOOKED` của thú trên mọi chi nhánh `≥ appointment.max_booked_per_pet` [CFG] → "Thú cưng đã có {n} lịch hẹn đang chờ";
   - (b) đã có lịch `BOOKED` / `CHECKED_IN` / `COMPLETED` đúng khung đó, mọi nhóm → "… trùng khung giờ này";
   - (c) đã có lịch `BOOKED` / `CHECKED_IN` / `COMPLETED` cùng nhóm trong ngày → "Thú cưng đã có lịch {Khám/Tiêm | Thẩm mỹ} trong ngày này".

   `CANCELLED`, `NO_SHOW` không tính, cùng tập trạng thái chiếm quota của BR-LH-03. Partial unique `uq_appointments_pet_slot_booked` (chỉ `BOOKED`) giữ làm chốt chặn cuối ở DB.
4. **BR-LH-09** — `BookingRestrictionService.isRestricted(customerId, at)`:
   - Định nghĩa: `starts_at ≤ at < ends_at AND lifted_at IS NULL` (erd §5). `ends_at` đúng bằng `at` là đã hết hạn chế.
   - Không khóa. Dùng `ix_booking_restrictions_customer_ends_at`.
   - Message chung, không kèm ngày hết hạn chế; FE lấy ngày qua #7.
   - `evaluateAfterViolation` ném `UnsupportedOperationException` tới phần 4 / ST05 (nợ D030; hiện không có bên gọi).
5. **Chưa gọi `FollowUpApi`** (Care Task#6, BR-TB-06), nợ **D029**. Chưa có bean, và chưa có nhắc tái khám nào để hủy (chưa có MedicalRecord, chưa có ST04), nên hiện không thiếu hành vi nào. Nối lời gọi khi visit cài (hạn 27/10, T38/T39). Notes của hợp đồng #2 ghi rõ điều này.
6. **`canReschedule` / `canCancel`** (`AppointmentActions`, lớp thuần, dùng chung cho phần 3–4):
   - `canCancel` = `BOOKED` và `now <` giờ hẹn (BR-LH-07, cho cả hai vai trò).
   - `canReschedule` = `BOOKED`, `rescheduleCount < appointment.max_reschedules` [CFG], và:
     - khách: còn ít nhất `appointment.reschedule_min_lead_hours` [CFG] (biên tính là được);
     - lễ tân: chưa tới giờ hẹn (BR-LH-06).
7. **Tự thêm, không có trong docs:**
   - `note` được `strip()`, rỗng thì lưu null.
   - `@Positive` cho 3 id, vì `lockPet` từ chối id ≤ 0 bằng `IllegalArgumentException` (500).
   - Không gửi email xác nhận, không ghi audit (convention 08 §8.3 / BR-QT-15 không liệt kê đặt lịch).
   - Trường lạ trong body (`customerId`, `channel`, `status`…) bị bỏ qua.

## Lý do và phương án bị loại

- **Đọc thú trước khóa để phân quyền, đọc lại sau khóa (giữ nguyên văn ADR-0045 §1):** phải đặt nghĩa vụ "đọc mới mỗi lần gọi" lên `PetQueryApi` của BE-2. Nếu nghĩa vụ đó bị vi phạm, lần đọc lại trả dữ liệu cũ, D027 vô hiệu mà không test nào của BE-1 phát hiện. Người dùng chọn đọc một lần sau khóa. Cái giá: request đặt cho thú của người khác có thể chờ khóa của thú đó vài ms.
- **Kiểm BR-TK-19, BR-LH-09, BR-LH-04 trước khóa** (thứ tự người dùng nêu khi mở bàn): request bị từ chối sẽ không giữ khóa, nhưng một 400 có thể thắng 404 chi nhánh (trái 00-method L69). Ngoài ra BR-LH-04 phải tính `now` sau khi đã chờ khóa (ADR-0045 §1). Loại.
- **Giữ 403 như hợp đồng:** lộ cho người gọi biết petId có tồn tại, trái 00-method L66. Loại.
- **BR-LH-05 (b), (c) chỉ tính `BOOKED`:** cho phép đặt lịch tắm 9:00 cho thú đã check-in lịch khám 9:00 (check-in được sớm 30 phút, BR-TN-02). Loại.
- **Gọi `FollowUpApi` qua placeholder:** mọi lượt đặt nhóm MEDICAL trả 500 tới 27/10, kể cả khi BE-2 đã cài `PetQueryApi`. Loại.
- **Kèm ngày hết hạn chế trong message BR-LH-09:** phải đổi `BookingRestrictionApi` (boarding cũng gọi). Endpoint #7 đã trả đủ thông tin. Không làm.

## Hệ quả

- **Lần ghi đầu tiên của module appointment.** Luồng ghi của branch (ngày nghỉ, giờ mở cửa, quota, bật / tắt dịch vụ) và quản trị nhân viên trên chi nhánh X có thể chờ vài ms sau một lượt đặt lịch ở X, và ngược lại. Đây là chủ đích của ADR-0045.
- **App thật trả 500** cho cả khách lẫn lễ tân cho tới khi BE-2 cài `CustomerQueryApi` (D010) và `PetQueryApi` (D031). Không dòng nào được ghi, không số sequence nào bị tiêu, không khóa nào còn treo (`BookAppointmentPlaceholderIT`).
- **Bấm hai lần / mất response:** request thứ hai chờ khóa thú, rồi thấy lịch vừa tạo → 400 BR-LH-05 (b). Không có lịch thứ hai. Docs không có quy định idempotency, nên không thêm cơ chế.
- **Đã chứng minh bằng IT + mutation** (bỏ cơ chế thì test đỏ):
  - chỗ cuối của quota;
  - ≤ 2 BOOKED và trùng nhóm trong ngày trên hai chi nhánh;
  - ngày nghỉ / quota 0 / tắt dịch vụ commit trong lúc chờ khóa chi nhánh;
  - `now` lấy sau khóa;
  - thú bị đánh dấu mất trong lúc chờ khóa thú.
- **Không đảm bảo:**
  - Đặt lịch ∥ đánh dấu thú mất / chuyển chủ: D027, BE-2 phải lấy khóa 1001.
  - Đặt lịch ∥ tạo hạn chế (phần 4 / ST05): không có khóa chung. Lượt đặt kịp trước thì giữ nguyên, khớp câu "Lịch đã đặt trước đó giữ nguyên" của BR-LH-09.
  - Đặt lịch ∥ catalog ngừng dịch vụ: đã chấp nhận ở ADR-0045.
  - Đặt lịch ∥ UC07 xóa hồ sơ online: kiểm FK làm hai luồng chờ nhau; bên thua nhận 409.
  - Pool: mỗi request đang chờ khóa giữ một connection (D011).
