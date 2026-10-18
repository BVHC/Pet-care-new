# ADR-0046: `AppointmentApi` và `AppointmentQueryApi` thật — chỉ khóa dòng lịch hẹn, tham gia transaction của bên gọi, 409 trước 400 ở check-in

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-16
- **Liên quan:**
  - BR-TN-01 (02 L141), BR-TN-02 (L142), BR-TN-03 (L143), BR-TN-07 (L147); BR-LH-08 (L129), BR-LH-10 (L131), BR-LH-11 (L132); BR-KH-06 (L92); BR-TK-19 (L35); BR-QT-11 (L54); BR-CN-04 (L68).
  - 03 §3 Lịch hẹn #3, #7, #8 (L83, L87, L88); 03 §4 Visit#1, #5, #6 (L102, L106, L107); 03 Quy ước L11 ("các thay đổi trong cùng một sự kiện thực hiện trong một transaction").
  - `docs/06-module-contracts.md` §1 (placeholder ném lỗi, không trả giá trị "an toàn"), §4.1 (L67–69), §4.2, §6; convention 05 L64–81, 07 §7.1–7.2.
  - ADR-0010 (lỗi đi qua `@Transactional` làm rollback-only), 0029 §4 (bẫy persistence context), 0041 (thứ tự khóa, `AppointmentLocks`), 0044 §6 (`CheckInWindow`), 0045 (409 trước rule, hủy bằng `cancel`).
  - Nợ: mở D034; cập nhật D033; ghi nhận nghĩa vụ cho D027.
- **Triển khai (T16 phần 5):**
  - `appointment/api/AppointmentApi` (chữ ký đổi), `appointment/service/{AppointmentVisitService, AppointmentLookupService}`.
  - `appointment/entity/Appointment.{checkIn, complete}`, `appointment/repository/AppointmentRepository` (4 query).
  - Xóa `AppointmentQueryApiPlaceholder` và test của nó.
  - `branch/service/ScheduleImpactFinder.describe` (BE-2): tên thú / khách theo lô.
  - Test:
    - Unit: `AppointmentVisitServiceTest`, `AppointmentLookupServiceTest`, `ScheduleImpactFinderTest`.
    - IT: `module/appointment/{AppointmentVisitTxIT, AppointmentVisitApiIT, AppointmentQueryApiIT, AppointmentQueryApiPlaceholderIT, AppointmentQueryCountIT}`, `BranchAdminIT`.
  - Không endpoint HTTP mới, không migration.

## Bối cảnh

Hai interface của appointment còn chặn nhiều luồng: branch (đổi giờ / thêm ngày nghỉ của chi nhánh `ACTIVE`, hai `…/impact`) và identity (khóa khách) gọi `AppointmentQueryApi` và nhận placeholder; visit (T26, 20/10) cần `AppointmentApi` cho Visit#1 / #5 / #6. Hạn giao theo 06 §6 là 16/10.

Điểm docs để ngỏ:

- Chữ ký `checkIn` / `cancelByVisit` có `actorId` nhưng không cột nào lưu.
- `cancel_source` của Lịch hẹn#8 (enum `CUSTOMER` / `STAFF` / `CLINIC`).
- Thứ tự lỗi của `checkIn` (404 / 409 / 400).
- Ai nghe `BranchClinicCancellationEvent` (Lịch hẹn#5).
- N+1 ở `ScheduleImpactFinder.describe`.

## Quyết định

1. **Chữ ký (Q1).**
   - `CheckInInfo checkIn(Long appointmentId, Long branchId, Instant at)`.
   - `void completeByVisit(Long appointmentId)`.
   - `void cancelByVisit(Long appointmentId, Instant at)`.
   - `CheckInInfo(appointmentId, customerId, petId, serviceId, ServiceGroup serviceGroup, Instant scheduledAt)`; `scheduledAt` là giờ hẹn.
   - Bỏ `actorId`: không cột nào của `appointments` lưu người check-in / hủy lượt (người check-in nằm ở `visits.checked_in_by`).
2. **`cancelByVisit` (Q2):** `cancel_source = STAFF`, `late_cancel = false`, `cancel_reason = null`, `cancelled_at = at` cắt về micro giây (như ADR-0045 §5). Không gọi `BookingRestrictionApi`: hủy lượt khách bỏ về không phải vi phạm BR-LH-09. Chốt `status = CHECKED_IN` **tường minh**; cạnh `BOOKED → CANCELLED` có trong FSM nhưng thuộc #4 / #5.
3. **Thứ tự lỗi `checkIn` (Q3):** 404 (không có lịch) → 409 (không còn `BOOKED`) → 400 BR-TN-02 (khác chi nhánh / ngoài cửa sổ). Lệch convention 05 (guard BR trước `validateTransition`) có chủ đích, như ADR-0045: với lịch đã hủy / đã check-in, "chưa tới giờ" là lý do sai. Cửa sổ dùng `CheckInWindow` (ADR-0044 §6) với `visit.check_in_early_minutes` và `appointment.no_show_after_minutes` đọc từ [CFG]; cả hai biên tính là được. Nhờ đó lịch hiện trong bộ lọc `checkInReady` ⇔ `checkIn` nhận (IT so hai tập với cùng `now`).
4. **Transaction.**
   - `AppointmentApi` là `MANDATORY`, không `noRollbackFor`, không `try/catch`, không `REQUIRES_NEW`: tham gia transaction của Visit#1 / #5 / #6 (03 Quy ước L11). Commit / rollback do bên phát quyết định.
   - Mọi kiểm tra đứng trước `saveAndFlush`, nên từ chối thì không ghi gì và lỗi DB lộ ngay trong lời gọi.
   - Chốt đầu method: transaction `readOnly` → `IllegalStateException` (khuôn `AppointmentLocks`).
   - **Nghĩa vụ của bên gọi (ghi ở javadoc `AppointmentApi`):** không bắt lỗi của các method này. Lỗi đã đánh dấu rollback-only; bắt rồi đi tiếp thì commit ném `UnexpectedRollbackException` (ADR-0010; `AppointmentVisitTxIT.callerSwallowingTheFailureCannotCommit`).
5. **Khóa.** Chỉ khóa dòng `appointments` (`findByIdForUpdate`, `FOR NO KEY UPDATE`) — đoạn cuối của `advisory(thú) → branches → appointments` (ADR-0041). Mọi guard đọc trạng thái **sau** khóa, không `findById` trước (ADR-0029 §4).
   - **Nghĩa vụ của visit:** gọi sau mọi khóa `branches` / advisory của nó; không bao giờ khóa dòng `appointments` rồi mới khóa `branches` (deadlock với đổi khung).
   - `at` là một mốc của use case, lấy sau các khóa của visit; có thể trễ bằng thời gian chờ khóa dòng lịch hẹn (chấp nhận, vì cửa sổ rộng ±30 phút).
6. **Guard thuộc đúng một bên.** Appointment kiểm: tồn tại, trạng thái, chi nhánh, cửa sổ check-in. Visit kiểm (không kiểm lại ở đây): role A06 và `BranchScope`, chi nhánh `ACTIVE` trong giờ mở cửa / cờ cấp cứu (BR-CN-05), thú chưa mất (BR-TN-01), ưu tiên trễ 15–30 phút (BR-TN-03, dùng `scheduledAt`), một Visit mỗi lịch (`uq_visits_appointment_id`), chẩn đoán / mũi tiêm (BR-KB-02), lượt chưa gọi (BR-TN-07).
7. **`completeByVisit` / `cancelByVisit` với id không có = `IllegalStateException` (500):** `visits.appointment_id` có FK, nên thiếu là dữ liệu hỏng, không phải 404.
8. **`AppointmentQueryApi`:**
   - `AppointmentLookupService`, class `@Transactional(readOnly = true)` **tham gia** transaction ghi của bên gọi (REQUIRED); không đổi flush mode / cờ readOnly của nó (`AppointmentQueryApiIT.lookupJoinsCallerWriteTransaction`).
   - JPQL constructor `SELECT new BookedSlot(…)` (không nạp entity vào persistence context của bên gọi), một câu SQL bất kể số dòng; `existsBy…` một câu `fetch first 1 rows`.
   - `findBookedFrom` / `findBookedByCustomer`: chỉ `BOOKED`, sắp `slot_date, slot_start, id`. `existsByPet` / `existsByCustomer`: mọi trạng thái (BR-KH-06 "chưa phát sinh lịch hẹn", BR-TK-19 "đã phát sinh dữ liệu").
   - Không `BranchScope`, không kiểm role: API nội bộ, bên gọi đã phân quyền.
   - **`findBookedFrom` không chặn trên.** Lễ tân đặt được mọi khung chưa bắt đầu (`BookingWindow`), nên kích thước tối đa là quota × số khung × số ngày còn lịch. Branch cần đủ danh sách để chặn (BR-LH-10), nên không phân trang.
9. **G1 — ngày nghỉ không bỏ sót lịch đang đặt:** branch khóa dòng `branches` (`HolidayService.addHoliday`) trước khi đọc `findBookedFrom`; đặt / đổi khung khóa cùng dòng (`lockForBooking`); READ COMMITTED. Ngày nghỉ chờ lượt đặt đang chạy rồi thấy lịch nó vừa commit (`holidayWaitsForInFlightBookingAndSeesIt`).
10. **Nghĩa vụ cho customer (T17, BE-2):** `existsByPet` / `existsByCustomer` gọi **sau** khóa thú advisory 1001 (D027) / khóa `customers`. Nếu không, một lượt đặt chen vào sẽ bị FK `RESTRICT` chặn (409) chứ không mất dữ liệu.
11. **Listener cho `BranchClinicCancellationEvent` (Lịch hẹn#5) để T25 (Q4)** — nợ D034. Hôm nay vẫn 500 trước khi có gì được ghi, vì `BoardingQueryApi` còn placeholder (D033; `AppointmentQueryApiPlaceholderIT`). D034 phải xong **trước** khi `BoardingQueryApi` thật, nếu không chi nhánh hủy hàng loạt sẽ commit mà lịch hẹn không bị hủy.
12. **N+1 ở `ScheduleImpactFinder.describe` (Q5):** BE-1 sửa thay BE-2 (tiền lệ `lockForBooking`). Gom `petId` / `customerId` của cả lịch hẹn lẫn đặt chỗ, khử trùng, chia lô ≤ 100 (nghĩa vụ của `findPets` / `findContacts`), ghép theo id. Id không trả về → "(không rõ)"; id trả về hai lần → `IllegalStateException`. Rỗng → không gọi module nào. Báo BE-2 qua 06 §4.2.
13. **Kiểm số câu SQL (Q6):** `AppointmentQueryCountIT` bật Hibernate Statistics trong context riêng, không thêm dependency. Mỗi query đúng 1 câu với 1 dòng và 50 dòng; mỗi lời gọi ghi đúng 2 câu (`SELECT … FOR NO KEY UPDATE` + `UPDATE`).
14. **Không audit** (convention 08 §8.3 không liệt kê check-in / hoàn tất / hủy lượt), không thông báo, không đọc module khác.

## Hệ quả

- Branch và identity hết 500 ở `AppointmentQueryApi`. Trên app thật vẫn 500 ở bước khác: branch ở `BoardingQueryApi.findBookedFrom` (D033, 22/10), identity khóa khách ở `CustomerQueryApi` (D010).
- `AppointmentApi` chưa có bên gọi trong code chạy thật (T26).
- `docs/06` §4.1 / §4.2 / §6 cập nhật chữ ký và nghĩa vụ.

## Đã cân nhắc

- **Giữ `actorId`** — không cột nào lưu; thêm cột = migration không có rule nào đòi hỏi.
- **`CLINIC` làm `cancel_source` cho Lịch hẹn#8** — `CLINIC` dành cho #5 (phòng khám hủy); #8 là lễ tân (A06) hủy lượt khách bỏ về, nên `STAFF`.
- **Phân trang `findBookedFrom`** — branch phải thấy toàn bộ để chặn; cắt trang làm lọt lịch.
- **Mở transaction riêng cho `AppointmentApi`** — vi phạm 03 Quy ước L11: Visit commit mà lịch không đổi.
