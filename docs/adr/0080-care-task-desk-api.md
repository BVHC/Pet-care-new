# ADR-0080: API Care Task cho lễ tân (care-v1 #1–4) — phạm vi chi nhánh, BR-TB-05 ở service, khóa dòng, ghép dữ liệu theo lô

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-27
- **Liên quan:**
  - UC87, BR-TB-05, BR-LT-10; 03 §10 (Care Task#2, #3); erd §11 `care_tasks`; care-v1 #1–4.
  - ADR-0079 (nền Care Task), 0071 (khuôn `FeedbackReviewService`: 404 → 403 → 400 → 409), 0033 (tên FSM trong 409), 0040 (Swagger = contract).
  - Nợ: D050 (mới), D049 (giữ mở).
- **Triển khai (T39 phần 1):** `care/controller/CareTaskController`, `care/service/CareTaskDeskService`, `care/dto/{CareTaskResponse, CompleteCareTaskRequest, CancelCareTaskRequest}`, `care/mapper/CareTaskMapper`; `CareTask.complete / cancel`; `CareTaskRepository` thêm `findBranchIdById`, `findByIdForUpdate`, `JpaSpecificationExecutor`; visit thêm `VaccinationQueryApi.findVaccineTypeIds`. Không migration, job, listener, audit.
- **Chưa làm (T39 phần sau):** ST04, ST18, ST19, listener thú mất / đổi chủ phía care, `FollowUpApi` (D029).

## Quyết định

1. **Chỉ RECEPTIONIST, chỉ chi nhánh của mình.** `@PreAuthorize("hasRole('RECEPTIONIST')")` ở cả 4 handler. Danh sách lấy chi nhánh từ `BranchScope.resolve(null)` (không có tham số chi nhánh; `?branchId=` bị bỏ qua). #2–#4: `BranchScope.check` ngay sau 404, trước mọi lời gọi module khác. Chi nhánh đọc từ DB mỗi request nên điều chuyển có hiệu lực ngay với cùng token.
2. **Thứ tự lỗi** (framework trước, service sau): 401 → 400 BR-TK-17 → 400 hình thức (`MALFORMED_REQUEST`, `VALIDATION_FAILED`, 415) → 403 role → 404 → 403 chi nhánh → 400 BR-TB-05 → 409. Bean Validation chạy **trước** `@PreAuthorize`, nên VET gửi `note` 2001 ký tự nhận 400, không phải 403 (khuôn các endpoint khác).
3. **BR-TB-05 ở service, không ở annotation.** Thiếu `result`, `note` / `reason` null hoặc trống sau `strip()` → `BusinessRuleViolationException("BR-TB-05")`. Request chỉ có `@Size` (`note` ≤ 2000, `reason` ≤ 300 = `cancel_reason VARCHAR(300)`): `@NotNull` / `@NotBlank` sẽ trả `VALIDATION_FAILED` không kèm mã rule, sai contract. `note` / `reason` lưu bản đã `strip()`; NBSP (U+00A0) không bị `strip()` tách (theo `Character.isWhitespace`) nên tính là nội dung.
4. **Ghi (#3, #4):** `findBranchIdById` (câu vô hướng, chưa nạp entity) → guard → `findByIdForUpdate` (`FOR NO KEY UPDATE`, `MANDATORY`) → `now` cắt µs → `fsm.validateTransition` → `CareTask.complete / cancel` → `saveAndFlush` → ghép → map. READ COMMITTED. Câu hủy hệ thống (`cancelOpen*`, `WHERE status = 'OPEN'`) chờ khóa này rồi kiểm lại trạng thái: không hủy đè task lễ tân vừa xử lý, và lễ tân đọc `CANCELLED` dưới khóa thì nhận 409. Chỉ giữ một khóa, không chờ khóa nào sau đó → không chu trình. Không audit, không outbox (BR-QT-15 không liệt kê Care Task). `handled_by` = lễ tân; NULL vẫn nghĩa là hệ thống hủy (ADR-0079 §3).
5. **Danh sách:** `readOnly` + REPEATABLE READ (count và trang cùng snapshot); `status` vắng = `OPEN`; sắp `due_date, id` (index `ix_care_tasks_branch_status_due`); `page` ≥ 0, `size` kẹp 1–100; `dueFrom > dueTo` → trang rỗng.
6. **Ghép dữ liệu theo lô, care không đọc bảng module khác:** `CustomerQueryApi.findContacts`, `PetQueryApi.findPets`, `VaccinationQueryApi.findVaccineTypeIds` (mới) → `CatalogQueryApi.findVaccineTypeNames`; tập rỗng không gọi. `customerId` / `customerName` theo **task** (06 Q1), không theo chủ hiện tại của thú. `phoneToCall`: `PICKUP_OVERDUE` = `BoardingQueryApi.findBooking(id).emergencyPhone` (mỗi đặt chỗ một lời gọi, **D050**), loại khác = SĐT hồ sơ khách. `context`: `Tái chủng <loại>` · `Quá hạn tái chủng <loại>` · `Tái khám ngày dd/MM/yyyy` · `Quá hạn đón thú — đặt chỗ #<id>` (thiếu tên loại thì bỏ phần tên). Thiếu khách hoặc thú (FK `RESTRICT` đảm bảo có) là dữ liệu sai → 500, không trả null cho trường bắt buộc.
7. **Đổi contract (`m_care.py`):** #2 thêm 400 (id sai kiểu, BR-TK-17; tiền lệ care #7); #3, #4 liệt kê đủ các loại 400; `note` thêm `maxLength: 2000`; mô tả #1. Số endpoint ghim trong `OpenApiContractParityIT` 158 → 162.

## Bảng đảm bảo

| # | Đảm bảo | Test |
|---|---|---|
| G1 | Chỉ RECEPTIONIST; 6 role khác 403 ở cả 4 endpoint; chi nhánh khác 403, danh sách không hiện; điều chuyển có hiệu lực với cùng token | `CareTaskDeskIT` |
| G2 | Danh sách: mặc định `OPEN`, lọc `status / type / due*`, sắp `dueDate, id`, kẹp page / size, khoảng ngược rỗng | `CareTaskDeskIT`, `CareTaskDeskServiceTest` |
| G3 | Ghép đủ 4 loại: `context`, `phoneToCall` (PICKUP = SĐT khẩn, chưa nhận thú = null), tên khách / thú; `customerId` theo task | `CareTaskDeskIT`, `CareTaskDeskServiceTest` |
| G4 | #3 / #4 ghi đúng cột, `handled_by` = token (bỏ qua `handledBy` trong body), `handled_at` = Clock cắt µs, `customer_id` không đổi, không sinh task gọi lại | `CareTaskDeskIT`, `CareTaskTest` |
| G5 | 400 / 409 không ghi gì (`xmin` giữ nguyên); thứ tự lỗi đủ ô của ma trận | `CareTaskDeskIT` |
| G6 | Thứ tự phân quyền → guard → khóa → FSM → ghi; lỗi trước khóa không khóa, không ghi | `CareTaskDeskServiceTest` |
| G7 | Race với hủy hệ thống: hệ thống giữ dòng → complete chờ rồi 409, giữ kết quả hệ thống; chạy song song 8 lần luôn đúng một kết cục nhất quán | `CareTaskDeskIT` |
| G8 | Lỗi DB lúc ghi (trigger 23514) → 409 `CONCURRENCY_CONFLICT`, task còn `OPEN`; lỗi **sau flush** (ghép dữ liệu) → rollback cả UPDATE, thử lại được đúng một lần | `CareTaskDeskIT`, `CareTaskDeskRollbackIT` |
| G9 | Bất biến cột theo trạng thái (D049) rỗng cuối mỗi ca | `CareTaskDeskIT` (`@AfterEach`) |
| G10 | `findVaccineTypeIds`: theo lô, rỗng không chạm DB, id lạ vắng | `VaccinationQueryServiceTest`, `VaccinationQueryApiIT` |
| G11 | Không N+1: cùng số câu SQL cho 1 và 20 task | `CareTaskDeskCountIT` |
| G12 | Mapper đủ 20 trường, đúng nguồn, mọi loại × trạng thái; tập enum và độ dài khớp contract yaml | `CareTaskMapperTest` |
| G13 | `@Size` biên 2000 / 2001, 300 / 301; null và trống đi qua Bean Validation | `CareTaskRequestValidationTest` |
| G14 | Swagger khớp contract, đủ 162 endpoint | `OpenApiContractParityIT` |

Một lần thử đột biến (bỏ khóa: `findByIdForUpdate` → `findById` trong `complete`) làm đỏ test race của G7; đã hoàn lại.

## Phát hiện khi làm (đã sửa)

- `Map.of().get(null)` ném NPE: trang chỉ có task tái khám / quá hạn đón (không có `vaccinationId`) sẽ 500. Service chặn key null; `CareTaskDeskServiceTest` bắt được.
- Jackson ép số thành chuỗi cho trường `String` (`"note": 12` → `"12"`); giữ nguyên như mọi endpoint khác, có test ghi lại.

## Hệ quả

- **Cho BE-2:** `VaccinationQueryApi` có thêm method trừu tượng; stand-in ẩn danh ở `BoardingBookingIT` phải thêm `findVaccineTypeIds` (BE-1 đã thêm, giữ khi gộp). D050 đề nghị `BoardingQueryApi.findBookings(Collection)` có `code`.
- **Phần sau của T39:** listener thú mất / đổi chủ (UPDATE theo `WHERE status = 'OPEN'`) không xung đột với #3 / #4 nhờ khóa dòng và cột định danh `updatable = false`.
- D049 vẫn mở: ứng dụng giữ bất biến ở `CareTask.complete / cancel`; DB chưa chặn.

## Đã cân nhắc

- **`@NotBlank` cho `note` / `reason`** — trả `VALIDATION_FAILED` không kèm mã rule, sai contract 400 `BR-TB-05`.
- **409 trước 400** (khuôn appointment ADR-0049) — chọn 400 trước theo feedback resolve (ADR-0071): lễ tân gửi nhầm form không cần biết trạng thái.
- **Gọi `findBooking` theo lô ngay** — cần BE-2 sửa interface; để D050.
- **Khóa `accounts FOR SHARE` cho `handled_by`** — `handled_by` là lịch sử, không phải việc dở theo BR-QT-08; tài khoản nhân viên không bị xóa cứng.
