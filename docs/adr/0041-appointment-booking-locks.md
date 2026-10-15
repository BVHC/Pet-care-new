# ADR-0041: Nền lịch hẹn — khóa chi nhánh + khóa thú khi đặt / đổi lịch, mã lịch hẹn từ sequence, khung 30 phút

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-15
- **Liên quan:**
  - BR-LH-01 (02 L122), 02 (L123), 03 (L124), 04 (L125), 05 (L126), 06 (L127), 07 (L128), 10 (L131), 11 (L132); BR-CN-02 (L66), 03 (L67), 04 (L68); BR-KH-05, 08 (L91, L94); BR-QT-15 (L58). 03 §3 Lịch hẹn #1–#8 (L79–88), Phụ lục (L237–253).
  - UC39, UC40 (01 L104–105); appointment-v1 #1–6 (`docs/api/appointment-v1.md` L54–66, ma trận L36–46, A1–A4 L95–100, D.2 L87); `docs/api/00-method.md` L66 (404 khi không được biết là có), L69 (thứ tự kiểm).
  - erd §5 `appointments` (L466–498, ghi chú khóa L496); convention 05, 07 §7.3 (điểm tranh chấp "lấy khung cuối của quota").
  - ADR-0003, 0006 (phạm vi khách), 0020 (OSIV tắt), 0029 (khóa `branches` của quản trị nhân viên, bẫy persistence context), 0033 (tên FSM trong message 409). Nợ mới D027, D028.
- **Triển khai (T16 phần 0, không endpoint):**
  - `appointment/entity/{Appointment, AppointmentStatus, AppointmentChannel, AppointmentCancelSource}`, `appointment/fsm/AppointmentTransitionHandler`, `appointment/repository/AppointmentRepository` (`acquireTransactionLock`, `nextCodeValue`), `appointment/service/{AppointmentLocks, AppointmentCodeGenerator, SlotGrid}`.
  - `branch/api/BranchApi.lockForBooking`, `branch/service/BranchStaffLockService.lockForBooking`, `BranchRepository.lockStatusForBooking`.
  - `V13__appointment_code_sequence.sql`.
  - Test:
    - Unit: `AppointmentTransitionHandlerTest`, `SlotGridTest`, `SlotGridParityTest`, `AppointmentLocksTest`, `AppointmentCodeGeneratorTest`, `BranchStaffLockServiceTest` (+4).
    - IT: `module/appointment/{AppointmentMappingIT, AppointmentLockIT, AppointmentCodeGeneratorIT}`, `module/branch/BranchBookingLockIT`, `SchemaMigrationIT` (V13).

## Bối cảnh

T16 (UC39, UC40) đặt / đổi / hủy lịch hẹn. Ba điều kiện của đặt lịch là **phép đếm**, DB không chặn được bằng ràng buộc:

- quota của khung (BR-LH-03);
- ≤ 2 lịch `BOOKED` của một thú, không trùng nhóm trong ngày (BR-LH-05; chỉ "trùng khung" có partial unique `uq_appointments_pet_slot_booked`);
- khung phải nằm trong giờ mở cửa, không vào ngày nghỉ, dịch vụ phải bật tại chi nhánh (BR-LH-01, 02).

Convention 07 §7.3 bắt buộc có ADR trước khi cài điểm "lấy khung cuối của quota". Erd L496 gợi ý `FOR UPDATE` trên `branches` hoặc advisory lock.

Hiện trạng có liên quan:

- Mọi luồng ghi lịch / quota / dịch vụ của branch đã khóa dòng `branches` `FOR NO KEY UPDATE` trước khi kiểm:
  - `HolidayService.java:74`;
  - `OpeningHoursService.java:83`;
  - `QuotaService.java:67,105`;
  - `BranchServiceAvailabilityService.java:58`;
  - `BranchService.java:124` (kích hoạt).
- Nếu đặt lịch không khóa gì, có ba race:
  - **R1** – hai người cùng lấy chỗ cuối.
  - **R2** – `addHoliday` đọc `AppointmentQueryApi.findBookedFrom` trước khi một lượt đặt commit, nên để lại lịch `BOOKED` trên ngày nghỉ (BR-LH-10).
  - **R3** – đặt lịch song song với đổi quota / tắt dịch vụ.
- BR-LH-05 tính trên mọi chi nhánh, nên khóa một dòng `branches` không đủ (**R4**).
- `appointments.code VARCHAR(20) UNIQUE`: erd chỉ có ví dụ `LH-260115-0007`, không nói cách sinh.

## Quyết định

1. **Thứ tự khóa của mọi luồng ghi lịch hẹn:** `advisory(thú) → branches → appointments`.
   - Phân quyền (`@PreAuthorize`, phạm vi khách / chi nhánh) **xong trước** khi lấy khóa nào. Như vậy request 401 / 403 / 404 không giữ hay chờ khóa.
   - Mọi kiểm quota / BR-LH-04 / BR-LH-05 và mọi lần đọc lịch chi nhánh (`BranchQueryApi.*`) chạy **dưới** khóa.

   | Thao tác | Khóa |
   |---|---|
   | #1 đặt lịch | thú + chi nhánh |
   | #2 đổi khung | đọc projection không khóa (thú, chi nhánh) → thú → chi nhánh → dòng `appointments` → kiểm lại |
   | #4 hủy, #3 / #7 / #8 (từ Visit) | chỉ dòng `appointments` (trả quota, không đếm) |
   | Xem khung trống | không khóa (chỉ hiển thị) |

   Không luồng nào khóa theo chiều ngược lại:
   - quản trị nhân viên: `branches → accounts → staff_profiles → sessions` (ADR-0029), không khóa thú;
   - hủy hàng loạt của branch: `branches → appointments`;
   - Visit chỉ khóa `appointments`.

2. **`BranchApi.lockForBooking(Long branchId)`** (module branch, BE-1 thêm thay BE-2 như ADR-0028, 0029):
   - Câu native vô hướng `SELECT status FROM branches WHERE id = :id FOR NO KEY UPDATE`: không nạp entity, nên không trả `Branch` cũ của persistence context, và commit không sinh `UPDATE branches`.
   - `@Transactional(MANDATORY)` ở cả service lẫn method repository: method query của interface Spring Data không tự có transaction, gọi ngoài transaction thì câu khóa chạy autocommit, khóa rồi nhả ngay mà không báo lỗi.
   - Transaction `readOnly` → `IllegalStateException` trước SQL.
   - Trả `Optional<BranchQueryApi.BranchStatus>`. Id lạ → rỗng, không ném (ném qua proxy `MANDATORY` làm bên gọi rollback-only).
   - Bên gọi phải gọi **trước** mọi `BranchQueryApi.*` trong transaction.
   - Cùng dòng, cùng chế độ khóa với mọi luồng ghi của branch, nên đóng R1, R2, R3 bằng một khóa.
   - Cái giá: các lượt đặt của **một** chi nhánh xếp hàng nhau (transaction ngắn, không BCrypt), và chờ ngắn sau luồng quản trị nhân viên / sửa lịch của chính chi nhánh đó.

3. **Khóa thú:**
   - Câu lệnh: `pg_advisory_xact_lock(1001, (pet_id % 2147483647)::int)` (`AppointmentLocks.lockPet`, `MANDATORY`, cấm readOnly, `petId` phải dương).
   - Khóa theo **transaction**: tự nhả khi commit / rollback, không kẹt trên connection của pool. ADR-0007 từng loại advisory lock **theo session** cho job vì lý do này.
   - Hai thú trùng key chỉ làm chờ thừa, không sai nghiệp vụ.
   - **Bảng đăng ký namespace advisory** (classid của dạng hai số `int`; module mới thêm dòng ở ADR của mình):

     | Namespace | Chủ | Khóa gì |
     |---|---|---|
     | 1001 | appointment | thú khi đặt / đổi lịch hẹn (BR-LH-05) |

4. **Khung transaction bắt buộc cho phần 2–4** (một `@Transactional` REQUIRED, không `noRollbackFor`, không readOnly):
   1. Kiểm tham số thuần.
   2. Phân quyền / phạm vi (không khóa).
   3. `lockPet`.
   4. `lockForBooking`.
   5. Đọc dưới khóa.
   6. Guard → `BusinessRuleViolationException`.
   7. `nextCode()`, sau guard, để lượt bị từ chối không tốn số.
   8. `saveAndFlush`, để lỗi DB lộ ra 409 trong service, không phải lúc commit bên ngoài.
   9. Map response trong transaction (ADR-0020).

   Ghi chú cho khung này:
   - Không rule LH nào yêu cầu giữ lại gì khi bị từ chối (khác BR-TK-06, 09), nên không có `noRollbackFor`.
   - Cấm `try/catch` lỗi khóa / lỗi DB bên trong transaction. Lỗi khóa đánh dấu rollback-only; bắt rồi commit sẽ ra `UnexpectedRollbackException` 500 (`AppointmentLockIT.lockFailureMarksCallerRollbackOnly`).
   - Repo không đặt `lock_timeout`, nên đường 409 thực tế là deadlock 40P01. Hibernate trả về `PessimisticLockingFailureException`, `GlobalExceptionHandler` trả 409 `CONCURRENCY_CONFLICT` (`AppointmentLockIT.deadlockSurfacesAsPessimisticLockingFailure`).

5. **Mã lịch hẹn:** `LH-<yyMMdd ngày đặt theo giờ Việt Nam>-<số của appointment_code_seq, ít nhất 4 chữ số>` (`AppointmentCodeGenerator`).
   - V13: `CREATE SEQUENCE appointment_code_seq AS BIGINT MAXVALUE 9999999999 NO CYCLE`.
   - Duy nhất theo cấu tạo, không cần khóa. Số không reset theo ngày.
   - Số **nhảy cóc** khi transaction rollback (sequence không lùi). Lịch hẹn không yêu cầu số liên tục, khác phiếu nhập BR-KO-03.
   - Tối đa 20 ký tự. Hết số thì lỗi (không quay vòng sinh trùng): PostgreSQL 2200H → đúng `DataIntegrityViolationException` → 409 message chung. Thực tế không chạm tới.
   - `nextval` cần transaction ghi.
   - Ngày lấy theo `TimeConfig.BUSINESS_ZONE` từ `clock.instant()`, không theo múi giờ của `Clock`.
   - Khuôn này dùng lại được cho mã Visit / Order / phiếu thu sau này, **trừ** phiếu nhập kho (cần số liên tục).

6. **Khung 30 phút** (`SlotGrid`, lớp thuần ở appointment):
   - Ngữ nghĩa bằng đúng `BranchSchedule.isGeneratedSlot` của branch: bước 30 phút tính từ giờ mở của **từng** khoảng (mở 08:15 → 08:15, 08:45…), khung nằm trọn trong khoảng, khung cuối kết thúc ≤ giờ đóng.
   - appointment không được import `branch.schedule` (06 §1). `SlotGridParityTest` (10 000 ngày ngẫu nhiên × từng phút) giữ hai bên khớp, khuôn ADR-0037.
   - Giờ có phần lẻ dưới giây không phải khung.
   - Khoảng sai (mở ≥ đóng, chồng nhau, mà BR-CN-02 đã chặn lúc ghi) → `IllegalArgumentException` (500), không tự sinh khung từ dữ liệu hỏng.

7. **FSM** `AppointmentTransitionHandler`:
   - Chép 03 §3: `BOOKED → {BOOKED #2, CHECKED_IN #3, CANCELLED #4/#5, NO_SHOW #6}`, `CHECKED_IN → {COMPLETED #7, CANCELLED #8}`, khởi tạo `{BOOKED}` #1.
   - Message 409: "Không thể chuyển trạng thái lịch hẹn từ X sang Y" (khuôn ADR-0033).
   - Tên command đã duyệt (convention 03): `bookAppointment` #1, `rescheduleAppointment` #2, `checkIn` #3, `cancelAppointment` #4, `cancelByClinic` #5, `markNoShow` #6, `completeByVisit` #7, `cancelByVisit` #8.

8. **Entity** `Appointment` khai đủ 19 cột V1:
   - FK để dạng `Long`.
   - `reschedule_count SMALLINT` → `short`.
   - `serviceGroup` (`catalog.api.ServiceGroup`) là bản sao lúc đặt (erd L476).
   - Không có cột `visit_id`: quan hệ nằm ở `visits.appointment_id` (module visit). `visitId` trong response cần query của visit, ở T26 — nợ D028.
   - Method hành vi thêm ở phần dùng tới chúng.

## Lý do và phương án bị loại

- **Advisory lock theo (chi nhánh, nhóm, ngày, khung) cộng `FOR SHARE` trên `branches`:** song song tốt hơn giữa các khung khác nhau, nhưng hai cơ chế khóa và một hàm băm. Lưu lượng đặt lịch của một chi nhánh phòng khám thấp. Người dùng chọn khóa dòng `branches`.
- **Chỉ advisory theo khung:** không đóng được R2, R3. Loại.
- **`SERIALIZABLE`:** bên gọi phải retry khi lỗi 40001; không tiền lệ trong repo. Loại.
- **Khóa dòng `pets`:** đóng luôn race với thú mất / chuyển chủ nếu customer khóa cùng dòng. Nhưng `PetApi` chưa có bean, `pets` chưa có entity, và module đó thuộc BE-2. Người dùng chọn advisory, ghi nợ D027.
- **Mã theo số thứ tự reset mỗi ngày** (đúng hình ví dụ `0007`): thêm một khóa và một câu đọc tiền tố mỗi lượt. **Mã theo `id`:** cột identity `GENERATED ALWAYS` buộc INSERT với mã tạm rồi UPDATE. Không chọn.
- **Gọi `BranchSchedule` qua api:** không có method trên `BranchQueryApi` trả khung; thêm method vào module của BE-2 chỉ để tránh 40 dòng thuần. Chọn bản sao + parity test.

## Hệ quả

- Phần 2–4 dùng khung ở mục 4. Phần 0 **chưa thêm lời gọi nào trong code chạy thật**, nên không hành vi runtime nào đổi ngoài V13 và 4 bean mới.
- Từ phần 2, luồng ghi của branch và luồng quản trị nhân viên trên chi nhánh X có thể chờ vài ms sau một lượt đặt lịch của X, và ngược lại. Đây là điều R1–R3 cần.
- `FOR NO KEY UPDATE` không xung đột `FOR KEY SHARE` của kiểm FK: INSERT `appointments` không bị chặn bởi khóa `branches` của chính nó hay của người khác.
- Mỗi request đang chờ khóa giữ một connection Hikari. Một chi nhánh bị dội rất nhiều lượt đặt cùng lúc có thể làm cạn pool. Rate limit thuộc nợ D011.
- **Không đảm bảo:**
  - Đặt lịch ∥ đánh dấu thú mất / chuyển chủ (BR-KH-05, 08): luồng customer chưa có; khi BE-2 làm (T17) phải lấy cùng advisory key 1001 trước khi kiểm. Nghĩa vụ ghi ở 06 §4.2, nợ D027.
  - Dịch vụ bị catalog ngừng kinh doanh đúng lúc đặt: catalog không khóa `branches`. Chấp nhận.
- **Cần chốt ở plan phần sau:**
  - Phần 2: hợp đồng #2 ghi `403` khi thú không thuộc khách, còn `00-method.md` L66 ghi 404. Đề xuất 404, như ADR-0027, 0028, 0039.
  - Phần 2: gọi `FollowUpApi` (Care Task#6) hay để T38/T39.
  - Phần 4: đổi khung / hủy lịch đã CANCELLED — 400 BR-LH-06/07 (theo thứ tự `00-method` L69) hay 409.
