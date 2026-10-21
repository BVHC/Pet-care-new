# ADR-0052: Hủy lịch hẹn do phòng khám (Lịch hẹn#5 `cancelByClinic`) — ba listener đồng bộ, kiểm lại `BOOKED` sau khóa, thông báo theo BR-TB-02

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-18
- **Đánh số lại:** ban đầu là ADR-0047; đổi thành số hiện tại ngày 2026-10-20 khi gộp T25 lên `dev`, vì ADR-0045…0050 (lịch hẹn T16, +4) và ADR-0051 (BE-2) đã chiếm số. Mọi tham chiếu ADR-0047…0052 của T25 trong code / tài liệu đã đổi theo (+5). Ngoại lệ: comment trong `V17__seed_appointment_clinic_cancel_templates.sql` (0047 = ADR-0052) và `V18__seed_appointment_reminder_templates.sql` (0051 = ADR-0056) giữ số cũ, vì sửa migration đã chạy làm đổi checksum Flyway.
- **Liên quan:**
  - BR-LH-10 (02 L131), BR-CN-03 (L67), BR-CN-04 (L68), BR-KH-05 (L91), BR-KH-08 (L94), BR-LH-03 (L124), BR-LH-09 (L130), BR-TB-02 (L250), BR-QT-15 (L58).
  - 03 §3 Lịch hẹn#5 (L85); 03 Quy ước L11 ("một transaction"); 03 Phụ lục L251–252.
  - `docs/06-module-contracts.md` §3 (L54–56), §4.2, §8 Q4 (L237, L242, L256, L269); convention 03 L11 (tên listener), 07 §7.2 (L41–45).
  - ADR-0010 (rollback-only), 0012 / 0014 (outbox, IN_APP), 0029 §4 (persistence context), 0037 (ngữ pháp biến), 0045 (thứ tự khóa, tên command `cancelByClinic`), 0042-inventory (khóa nhiều dòng một câu, tăng dần), 0048 (ghép tên theo lô), 0049 (cắt micro giây), 0050 (§11 để T25, §14 không audit).
  - Nợ: trả D034; cập nhật D027, D033.
- **Triển khai (T25 phần 1):**
  - `appointment/listener/{BranchClinicCancellationListener, PetDeceasedListener, PetOwnerTransferredListener}`.
  - `appointment/service/{AppointmentClinicCancellationService, ClinicCancellationNotices, ClinicCancelCause}`.
  - `AppointmentRepository.{findAllByIdInForUpdate, findBookedIdsByPetId}`.
  - API mới `identity/api/AccountContactApi` + `identity/service/AccountContactService` + `AccountRepository.findContactsByIds`.
  - `V17__seed_appointment_clinic_cancel_templates.sql` (`APPOINTMENT_CANCELLED_BY_CLINIC`, `_APP`).
  - Test:
    - Unit: `AppointmentClinicCancellationServiceTest`, `ClinicCancellationNoticesTest`, `ClinicCancellationListenersTest`, `AccountContactServiceTest`.
    - IT: `module/appointment/ClinicCancellationIT`, `BranchAdminIT` (mock service mới), `SchemaMigrationIT` (V17).
  - Không endpoint HTTP mới.

## Bối cảnh

Branch (thêm ngày nghỉ / thu hẹp giờ có `cancelAffected`) và customer (đánh dấu thú mất / chuyển chủ) đã phát `BranchClinicCancellationEvent`, `PetDeceasedEvent`, `PetOwnerTransferredEvent`. Tuy vậy `src/main` chưa có `@EventListener` nào, nên lịch `BOOKED` vẫn nằm trong ngày nghỉ hoặc vẫn gắn với thú đã mất (D034). Điểm docs để ngỏ:

- Khóa thú khi nghe sự kiện của customer.
- Email nào dùng cho khách có tài khoản: hồ sơ online có `customers.email = null`.
- Lịch `BOOKED` đã quá giờ (ST05 chưa có).
- Audit.
- Lịch đã đổi trạng thái giữa lúc bên phát tính danh sách và lúc khóa.

## Quyết định

1. **Khóa thú (Q1).**
   - Listener **không** gọi `AppointmentLocks.lockPet`. Customer đã giữ `customers → pets`; lấy advisory 1001 sau đó sẽ đảo thứ tự `advisory(thú) → branches → appointments` (ADR-0045 §1).
   - Điều kiện tiên quyết là customer lấy advisory 1001 **trước** khóa dòng `pets` khi đánh dấu mất / chuyển chủ. Đây là nợ D027, việc của BE-2.
   - Trước khi D027 được trả, một lượt đặt lịch chạy song song có thể commit `BOOKED` cho thú vừa mất. Không có deadlock, vì đặt lịch không xin khóa `pets FOR UPDATE`.
2. **Người nhận (Q2, BR-TB-02).**
   - Hồ sơ gắn tài khoản đã xác thực: `IN_APP` (`recipientAccountId`, `linkUrl = /appointments`) + `EMAIL` tới **email của tài khoản**. Email này lấy qua API mới `AccountContactApi`.
   - Hồ sơ không tài khoản nhưng có `customers.email`: chỉ `EMAIL`.
   - Không kênh nào: không gửi, log INFO `APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL`. Lễ tân gọi theo danh sách lúc hủy hàng loạt (06 L242).
   - Tài khoản `PENDING` (lễ tân đặt hộ hồ sơ online chưa xác thực) được **coi như không có tài khoản**:
     - Không truyền `recipientAccountId`, vì FK sẽ chặn ST02 xóa tài khoản đó.
     - Không gửi tới email chưa xác thực.
3. **Phạm vi khi thú mất / chuyển chủ (Q3):** hủy **mọi** lịch `BOOKED` của thú, kể cả lịch đã quá giờ mà ST05 chưa chuyển `NO_SHOW`, để khách không bị tính vi phạm oan. Không audit: convention 08 §8.3 và BR-QT-15 không liệt kê hủy lịch hẹn (như ADR-0050 §14).
4. **Thứ tự (Q4).**
   - Bước 1: đọc id **không khóa, chỉ lấy id**.
     - Event branch đã mang `appointmentIds`.
     - Event thú dùng `findBookedIdsByPetId`, projection id nên không nạp entity — tránh bẫy ADR-0029 §4.
   - Bước 2: khử trùng, sắp tăng dần.
   - Bước 3: khóa từng lô ≤ 100 id trong **một** câu `… WHERE id IN :ids ORDER BY id FOR NO KEY UPDATE`.
   - Bước 4: kiểm lại `status == BOOKED` **sau khóa**. Không còn `BOOKED` (khách tự hủy, đã check-in) thì **bỏ qua, không lỗi**.
   - Id không tồn tại → `IllegalStateException` (500): lịch hẹn không bao giờ bị xóa cứng, nên thiếu id là dữ liệu hỏng.
5. **Ghi (Q5).**
   - `now = clock.instant()` cắt về micro giây, lấy **một lần sau khi khóa xong**.
   - Rồi `validateTransition(BOOKED, CANCELLED)` → `Appointment.cancel(CLINIC, lý do, now, false)` → `saveAndFlush`.
   - Không tăng `reschedule_count` (BR-LH-10).
   - Không gọi `BookingRestrictionApi`: hủy do phòng khám không tính BR-LH-09, và truy vấn đếm vi phạm chỉ đếm `late_cancel = true`.
6. **Lý do (Q6):** enum `ClinicCancelCause` gồm `HOLIDAY_ADDED`, `OPENING_HOURS_REDUCED`, `PET_DECEASED`, `PET_OWNER_TRANSFERRED`. Câu tiếng Việt của mỗi giá trị vừa ghi vào `cancel_reason` (khách đọc trong `cancelReason`), vừa là biến `ly_do` của mẫu.
7. **Transaction (Q7).**
   - Service là `MANDATORY` (chặn transaction `readOnly`), không `noRollbackFor`, không bắt lỗi.
   - Listener chỉ ủy quyền một lời gọi: `@EventListener`, không `@Async`, không `@TransactionalEventListener`, không `@Transactional`.
   - Lỗi ở bất kỳ lịch nào rollback **toàn bộ** use case của bên phát: ngày nghỉ, giờ mở cửa, `deceased_on`, chủ mới, audit chuyển chủ.
   - **Nghĩa vụ của bên phát:** không bắt lỗi quanh `publishEvent`, không `noRollbackFor`. Bắt lỗi sẽ dẫn tới `UnexpectedRollbackException` (ADR-0010).
8. **Chuyển chủ (Q8):** người nhận là `appointments.customer_id`, tức chủ lúc đặt = chủ cũ (06 L55). `toCustomerId` không dùng. Chủ cũ vẫn thấy lịch đã hủy trong #3 (ADR-0048: phạm vi theo chủ lúc đặt).
9. **Thông báo (Q9).**
   - Mỗi lịch bị hủy gửi một thông báo cho mỗi kênh.
   - Tên tra theo lô ≤ 100, ghép theo id; thiếu hoặc trùng id → `IllegalStateException`. Các module được gọi: `findContacts`, `findPets`, `findServices`, `findBranch` (mỗi chi nhánh khác nhau một lần), `AccountContactApi.findContacts`.
   - Mọi biến bắt buộc khác `null` được kiểm **trong transaction**. Lý do: `NotificationApi.enqueue` không kiểm `required_vars`; chỉ worker ST20 kiểm, sau commit, và đánh dấu `FAILED` âm thầm.
   - `gio_hen` định dạng `HH:mm dd/MM/yyyy` thẳng từ `slot_date` + `slot_start` (đã là giờ Việt Nam).
   - Luồng email là NORMAL (người dùng không chờ trên màn hình).
10. **Tên listener (Q10):** theo convention 03 L11, `{Sự kiện bỏ Event}Listener`, method `on(…)`. D034 ghi `…EventListener` là sai tên, đã sửa.
11. **Không `BranchScope`, không kiểm role:** API nội bộ, bên phát đã phân quyền. Thú dùng chung toàn chuỗi (BR-KH-01), nên lễ tân chi nhánh A đánh dấu thú mất thì lịch ở chi nhánh B cũng bị hủy. Khách tự đánh dấu (vai trò OWNER) không đi qua `BranchScope`.
12. **Đánh số migration khi gộp nhánh.**
    - V13–V15 là của BE-2 (đã push `origin/dung`).
    - Sequence mã lịch hẹn đổi `V13 → V16`.
    - Mẫu của ADR này là V17.
    - Ai đã chạy nhánh `manh` cũ trên DB dev (có V13 của lịch hẹn) cần `docker compose down -v` hoặc `flyway repair`.

## Phân tích khóa

- **Event branch:** bên phát giữ `branches FOR UPDATE`, rồi `appointments` tăng dần.
  - Đặt / đổi khung (`advisory → branches → appointments`) chờ ở `branches`.
  - Khách hủy và `checkIn` chỉ khóa dòng lịch.
  - Không có vòng chờ.
- **Event thú:** `customers → pets` (+ advisory 1001 sau D027) → `appointments` tăng dần.
  - Đặt / đổi khung chờ ở advisory 1001 (sau D027).
  - Hai sự kiện cùng đụng một lịch: bên sau thấy `CANCELLED` sau khóa, nên bỏ qua.
- Lỗi khóa / deadlock (40P01) → `PessimisticLockingFailureException` → 409, rollback toàn bộ.

## Hệ quả

- D034 đã trả. Tuy vậy, trên app thật, thêm ngày nghỉ hoặc thu hẹp giờ của chi nhánh `ACTIVE` vẫn 500 ở `BoardingQueryApi.findBookedFrom` trước khi phát event (D033). Phần của ta được chứng minh bằng IT có mock `BoardingQueryApi`.
- Customer đánh dấu mất / chuyển chủ trên app thật giờ hủy lịch và gửi thông báo.
- Boarding (BE-2, T31) thêm listener riêng cho cùng ba sự kiện. Các listener độc lập; lỗi của bất kỳ listener nào rollback cả use case.

## Đã cân nhắc

- **Listener tự lấy advisory 1001:** đảo thứ tự khóa trong cùng transaction (pets trước), nên có rủi ro deadlock với đặt lịch.
- **Chỉ hủy lịch có giờ hẹn trong tương lai:** lịch quá giờ còn `BOOKED` sẽ bị ST05 chuyển `NO_SHOW` và tính vi phạm cho khách đã mất thú / đã chuyển chủ.
- **Audit mỗi lần hủy:** không rule nào đòi hỏi; ADR-0050 §14 cũng không audit lịch hẹn.
- **Lỗi khi lịch không còn `BOOKED`:** danh sách được tính trước khi khóa, nên báo lỗi sẽ rollback ngày nghỉ chỉ vì khách vừa tự hủy.
- **Khóa từng id bằng `findByIdForUpdate`:** n câu thay vì ⌈n/100⌉.
- **Dùng `customers.email` cho khách có tài khoản:** hồ sơ online có email `null`, nên khách đăng ký online sẽ không nhận email.
