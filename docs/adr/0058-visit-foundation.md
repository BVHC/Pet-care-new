# ADR-0058: Nền module visit — FSM, tên command, mã lượt, thứ tự khóa, `VisitQueryApi` thật

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-20
- **Liên quan:**
  - BR-TN-01…09 (02 L141–149), BR-QT-08, BR-QT-11 (L51, L54), BR-KH-03, 05, 06, 08 (L89–94), BR-BH-02 (L190), BR-CN-03 (L67).
  - 03 §4 Visit (L100–107); erd §6 `visits`, `visit_assignments`; visit-v1 (operationId, schema `CreateVisitRequest`, `AssignRequest`, `ReassignRequest`, `CancelVisitRequest`); appointment-v1 schema `Appointment.visitId`.
  - Convention 03 §Tên command, 05, 07; 06 §1 (placeholder), §4.2, §6.
  - ADR-0029 §9 / 0031 §9 / 0033 (nghĩa vụ `accounts FOR SHARE`), 0043 (`DocumentCodeGenerator`), 0044 (`VisitOrderApi`), 0045 (khóa đặt lịch), 0050 (`AppointmentApi`, nghĩa vụ khóa của visit).
  - Nợ: trả D028; trả một phần D016 (phía visit).
- **Triển khai (T26 phần 0):**
  - `visit/entity/{Visit, VisitAssignment, VisitStatus, PriorityClass}`, `visit/fsm/VisitTransitionHandler`, `visit/service/{VisitCodeGenerator, VisitQueryService}`, `visit/repository/VisitRepository`, `visit/mapper/VisitQueryMapper`; `VisitQueryApi.findVisitIdsByAppointmentIds` (mới).
  - Xóa `VisitQueryApiPlaceholder` cùng test của nó.
  - appointment: `AppointmentMapper.toResponse(…, Long visitId)`, `AppointmentResponder` tra `visitId` theo lô.
  - Test:
    - Unit: `VisitTransitionHandlerTest`, `VisitCodeGeneratorTest`, `VisitQueryMapperTest`, `VisitQueryServiceTest`, `AppointmentQueryServiceTest.visitIdsAreReadInOneBatchCallForThePage`.
    - IT: `VisitQueryApiIT`, `VisitQueryCountIT`, `VisitCodeIT`, `AppointmentViewIT.visitIdComesFromTheVisitOfTheAppointment`.
  - Không endpoint, không migration.

## Bối cảnh

T26 (Tiếp nhận) cần nền chung trước khi làm từng thao tác. Các điểm docs để ngỏ:

- Tên command: không có bảng Actor↔Command, convention 03 bắt buộc người dùng duyệt danh sách.
- Định dạng và cơ chế sinh `visits.code`.
- Thứ tự khóa của Visit với identity (khóa tài khoản, điều chuyển), customer (thú mất), appointment và sales.
- `VisitQueryApi` đang là placeholder: BE-2 viết SQL cho 5 method, còn `findUnfinishedVisitsAssignedTo` ném lỗi (D016).
- appointment-v1 trả `visitId` nhưng chưa có query nào (D028).

## Quyết định

1. **Entity và enum.**
   - `Visit` map đủ 17 cột của `visits`; `VisitAssignment` là LOG, mọi cột `updatable = false`.
   - Id của module khác để dạng `Long`.
   - Có hai enum trạng thái: `visit.entity.VisitStatus` và `VisitQueryApi.VisitStatus` (khuôn `BranchStatus`). MapStruct bắt lệch theo chiều entity → api lúc biên dịch; `VisitQueryMapperTest` bắt cả hai chiều.
   - Method đổi trạng thái của `Visit` thêm cùng phần dựng thao tác đó.
2. **FSM** chép đúng 03 §4: `WAITING → {WAITING #2, IN_PROGRESS #3, CANCELLED #6}`, `IN_PROGRESS → {IN_PROGRESS #4, COMPLETED #5}`, khởi tạo `{WAITING #1}`. `fsmName()` = "lượt tiếp nhận".
3. **Tên command (người dùng duyệt 20/10)** theo operationId của visit-v1:

   | Dòng | Command |
   |---|---|
   | #1 | `checkInVisit` |
   | #2 | `assignVisit` |
   | #3 | `callVisit` |
   | #4 | `reassignCalledVisit` |
   | #5 | `completeVisit` |
   | #6 | `cancelVisit` |

   - `moveVisitInQueue` và `markVisitEmergency` không phải dòng của bảng 03. Chúng không đổi trạng thái và không dùng cạnh FSM nào. Lượt không còn `WAITING` thì 409, đúng như hợp đồng ghi "Lượt đã được gọi".
   - DTO giữ đúng tên schema của hợp đồng (`CreateVisitRequest`, `AssignRequest`…), dù lệch khuôn `{Command}Request` của convention 03, vì `OpenApiContractParityIT` so tên schema.
4. **Mã lượt** `TN-<yyMM>-<5 chữ số>` qua `DocumentCodeGenerator` (docType `visits`).
   - Dãy liên tục, reset theo tháng nghiệp vụ, không cần migration, nên không lấy số V19.
   - Check-in vốn đã tuần tự hóa toàn chuỗi trên bộ đếm `ORDER` (`VisitOrderService.openForVisit`), nên thêm bộ đếm `visits` không làm giảm thông lượng.
   - Visit#1 gọi `nextCode()` sau khóa lịch hẹn, ngay trước INSERT `visits`, rồi mới `openForVisit`. Thứ tự bộ đếm luôn là `visits → ORDER`. Bán lẻ chỉ lấy `ORDER`, nên không có vòng chờ.
   - `nextCode()` là `MANDATORY` và từ chối transaction readOnly.
5. **Thứ tự khóa của Visit:**

   ```text
   accounts (nhân viên, FOR SHARE, id ↑) → pets (FOR SHARE, PetQueryApi.findPetForShare)
     → visits (FOR NO KEY UPDATE, id ↑) → appointments (AppointmentApi)
     → document_counters (visits → ORDER) → orders (VisitOrderApi) → stock lots (StockApi)
   ```

   - **`accounts` đứng trước `visits`.** Khóa tài khoản đi theo chiều `accounts (FOR NO KEY UPDATE) → [listener D017] → visits`.
     - Gán / gán lại (Visit#2, #4) biết người nhận từ request, nên khóa `FOR SHARE` tài khoản trước (D016 *Cách trả* 4: FK chỉ lấy `FOR KEY SHARE`, không chặn được điều chuyển / vô hiệu hóa).
     - Với #4: đọc người phụ trách cũ không khóa, khóa cả hai tài khoản theo id tăng dần, rồi khóa và đọc lại visit. Assignee đã đổi thì 409.
     - Chiều ngược lại (`visits → accounts`) deadlock với khóa tài khoản người cũ (40P01 → 409).
   - **Check-in không khóa `branches`.**
     - `branches FOR SHARE` sẽ xung đột với `lockForBooking` (`FOR NO KEY UPDATE`) và làm check-in tuần tự với đặt lịch tại chi nhánh.
     - Trạng thái chi nhánh chỉ đi một chiều `DRAFT → ACTIVE` (đóng chi nhánh thuộc tầng 3). Giờ mở cửa mới có hiệu lực sớm nhất từ ngày mai (BR-CN-04).
     - Ngày nghỉ thêm được cho chính hôm nay (`HolidayService`), nhưng hủy hàng loạt và check-in có lịch hẹn cùng tranh dòng `appointments` (ADR-0050 §5, 0052), nên tuần tự.
     - *Giả định:* một walk-in được tiếp nhận đúng lúc chi nhánh thêm ngày nghỉ hôm nay là chấp nhận được (không rule nào hủy Visit theo ngày nghỉ).
     - Nếu sau này cần khóa chi nhánh, nó đứng **đầu**, trước `accounts` (cùng thứ tự với quản trị nhân viên).
   - **Không có vòng chờ với các luồng khác:**
     - Thú mất / chuyển chủ (`pets → appointments`): cùng chiều với check-in.
     - Đổi khung (`advisory 1001 → branches → appointments`): visit không giữ `branches`.
     - Điều chuyển / vô hiệu hóa (`branches → accounts`, đọc `visits` không khóa).
   - Phần 0 chỉ chốt thứ tự. Method khóa (visit `FOR NO KEY UPDATE`, `accounts FOR SHARE` qua identity api) đến cùng phần đầu tiên dùng nó, mỗi method khóa đều `MANDATORY`.
6. **`VisitQueryApi` thật (`VisitQueryService`).**
   - Class `@Transactional(readOnly = true)`, REQUIRED: tham gia transaction ghi của bên gọi.
   - JPQL constructor trả record (không nạp entity vào persistence context của bên gọi); mỗi method một câu SQL.
   - Không `BranchScope`, không kiểm role.
   - Ngữ nghĩa giữ nguyên javadoc của interface và bản SQL của BE-2: `findUnfinishedVisitsAssignedTo` lấy `WAITING` / `IN_PROGRESS`, sắp `id`; `hasMedicalHistory` đọc `medical_records` + `vaccinations`.
7. **`findVisitIdsByAppointmentIds(Collection)` → `Map<appointmentId, visitId>`** (D028).
   - Một câu `IN (…)` qua `uq_visits_appointment_id`; tập rỗng thì không chạy SQL.
   - `AppointmentResponder.forRows` gọi một lần cho cả trang, với mọi trạng thái: lịch `CANCELLED` do hủy lượt (Lịch hẹn#8) cũng có Visit.
   - Đặt lịch truyền `null` (lịch `BOOKED` mới tạo).

## Hệ quả

- Visit có nền cho phần 1 (check-in) trở đi. Identity điều chuyển / vô hiệu hóa / khóa A05–A08 qua được bước visit; vẫn 500 ở `CashierShiftQueryApiPlaceholder` (BE-2, phần còn lại của D016).
- appointment-v1 #3, #4, #5, #6 trả `visitId` thật.
- Mỗi lần xem / đổi / hủy lịch hẹn thêm một câu SQL tới `visits`.

## Mutation (mỗi thay đổi phải làm một test đỏ)

| Mutation | Test đỏ |
|---|---|
| Thêm / bớt một cạnh FSM | `VisitTransitionHandlerTest` |
| Bỏ lọc trạng thái ở `findUnfinishedAssignedTo` | `VisitQueryApiIT.unfinishedVisitsOfOneStaffAreWaitingAndInProgressInIdOrder` |
| `REQUIRES_NEW` / `NOT_SUPPORTED` ở `VisitQueryService` | `VisitQueryApiIT.lookupJoinsCallerWriteTransaction` |
| Tra visitId từng id | `VisitQueryCountIT`, `AppointmentQueryServiceTest.visitIdsAreReadInOneBatchCallForThePage` |
| Bỏ chặn tập rỗng | `VisitQueryServiceTest.emptyAppointmentIdsReadNothing`, `VisitQueryCountIT` |
| Đổi docType / prefix / ngày của mã lượt | `VisitCodeGeneratorTest`, `VisitCodeIT` |
| Thêm hằng số vào một trong hai enum trạng thái | `VisitQueryMapperTest` (hoặc lỗi biên dịch) |

## Đã cân nhắc

- **Sequence riêng như lịch hẹn (V19)** — không khóa, nhưng cần migration và thống nhất số với BE-2. Lợi thế "không tuần tự hóa" mất ý nghĩa vì check-in đã tuần tự trên bộ đếm `ORDER`.
- **`visits → accounts`** — đơn giản hơn nhưng deadlock với khóa tài khoản (mục 5).
- **Dùng chung `VisitQueryApi.VisitStatus` làm enum của entity** — trái convention 03 (enum ở `entity/`) và buộc `api/` đổi theo mọi thay đổi nội bộ.
- **Chỉ thêm query, để phần 1 nối vào appointment** — nối chỉ khoảng 15 dòng và trả hẳn D028.
