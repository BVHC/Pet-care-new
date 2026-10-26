# ADR-0074: Ghi nhận mũi tiêm (visit-v1 #19) — thứ tự khóa, advisory 1004, `CareTaskApi` một phần

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-25
- **Liên quan:** BR-KB-04, BR-KO-05, BR-TB-03, BR-SP-07, BR-BH-02; 03 Visit L109, Phụ lục L244, Care Task#5; visit-v1 #19, A21–A23; ADR-0042, 0044, 0067, 0072, 0073; nợ D046, D047.
- **Triển khai (T28 phần 2):** `visit/service/VaccinationRecordService`, `VaccinationController.recordVaccination`, `Vaccination.record`, `VaccinationRepository.{acquirePetDoseLock, findUnsupersededIds, markSuperseded}`, `VaccinationOptionService.requireUsableType` (tách từ #18), 2 DTO, `VaccinationOptionsMapper.toRecordResult`; `care/service/CareTaskService` + `CareTaskWriteRepository` (chỉ `cancelForRevaccination`). Không migration.

## Quyết định

1. **Thứ tự lỗi:** 400 body → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → **khóa** `pets FOR SHARE` → advisory 1004 → `visits` → 403 người phụ trách → 409 không `IN_PROGRESS` → 404 loại → 400 BR-KB-04 khác loài → 400 BR-SP-07 → 400 BR-KB-04 mũi (không có / khác loại / khác loài / ngừng) → 400 BR-KB-04 sản phẩm (không có / không VACCINE / khác loại / ngừng KD) → 400 BR-KB-04 hết tồn. Guard sai thì chưa ghi gì.
2. **Thứ tự ghi** (06 §3, ADR-0044; sửa 06 §5): `addVaccineLine` → `lockFefoLot` → INSERT mũi → `issueForVaccination` → `findUnsupersededIds` → `markSuperseded` → `cancelForRevaccination`. Không `catch`: lỗi từ callee `MANDATORY` đã đánh rollback-only.
3. **Sản phẩm đọc `findProductForShare` trước `addVaccineLine`**, không bao giờ `findProduct`: `VisitOrderService.requireProduct` ném `IllegalArgumentException` (500) với sản phẩm ngừng kinh doanh, nên khóa `FOR SHARE` từ guard để việc ngừng chạy song song phải chờ rồi bị guard bắt (400). Lệch nhẹ khỏi thứ tự `orders → products` của Sales nhưng không chu trình: không luồng nào giữ `orders` rồi chờ khóa **ghi** `products`.
4. **Hết tồn (Q2):** kiểm trước bằng `StockQueryApi.availableQuantity` (< 1 → 400 `BR-KB-04`, đúng hợp đồng). Lô bị lấy hết giữa chừng thì `lockFefoLot` ném 400 `BR-KO-05` (vẫn 400, rollback cả dòng Order); ghi ở contract A22.
5. **Advisory 1004 theo thú** (`pg_advisory_xact_lock(1004, petId % Integer.MAX_VALUE)`), lấy ngay sau `pets FOR SHARE`, trước `visits`; #20 dùng chung. Lý do: `VisitCheckInService` cho hai lượt MEDICAL mở song song cùng thú, mà READ COMMITTED không thấy mũi chưa commit của bên kia nên `superseded_at` có thể sót. Chỉ #19, #20 lấy khóa này → không chu trình. Công thức khóa chép từ `AppointmentLocks.key` (không import service của module khác).
6. **`CareTaskApi` một phần (Q1):** chưa có bean nào nên inject là hỏng khởi động. `CareTaskService` cài thật `cancelForRevaccination` (một câu UPDATE `status='OPEN' AND task_type IN (VACCINE_DUE, VACCINE_OVERDUE) AND vaccination_id IN (…)`, `cancel_reason` = `"Đã tiêm lại (BR-TB-03)"` — **tự đặt, docs không quy định**); `create`, `cancelFollowUpTasks` ném `UnsupportedOperationException` tới T39 (D047).
7. **Không chặn trùng mũi (Q7):** gửi lại = mũi thứ hai (hợp đồng A23); sửa bằng #20. Không audit (BR-QT-15 không liệt kê; vết = `vaccinations` + `stock_movements`). Không khóa `accounts` (khuôn #13/#14).
8. **Giá trị chụp:** `administered_on` = `LocalDate.now(clock)`, `dose_number` và `next_due_date = hôm nay + interval_days` của mũi đã chọn, `branch_id` của lượt, `recorded_by` của phiên; `now` đọc sau mọi khóa. `warnings = ["UNDER_MIN_AGE"]` theo **mũi VET chọn** (đúng bằng tuổi tối thiểu là đủ; không ngày sinh thì không cảnh báo). `lotNumber`/`expiryDate` null (D046).

## Bảng đảm bảo

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| G1 | Thứ tự khóa và guard, guard sai không ghi gì | `VaccinationRecordService` | `VaccinationRecordServiceTest` (`InOrder`, `verifyNoInteractions`); IT `visitGuards…`, `vaccineTypeDoseAndProductGuards`, `noAvailableStock…` (so đếm dòng + `xmin`) |
| G2 | Lô FEFO theo hạn dùng, không theo id, không lô hết hạn; movement VACCINATION | `StockApi` (BE-2) | IT `recordsTheDose…`, `theNearestExpiryLot…` |
| G3 | Mỗi (thú, loại) tối đa 1 mũi chưa thay thế; Care Task `VACCINE_*` OPEN của mũi cũ bị hủy, task khác giữ nguyên | advisory 1004 + `markSuperseded` + `CareTaskService` | IT `recordsTheDose…`, `twoVisitsOfTheSamePet…` |
| G4 | Một bước ghi lỗi thì rollback cả dòng Order, mũi, lô, mũi cũ, task | một transaction | IT 3 trigger (`stock_movements` INSERT, `vaccinations` UPDATE, `care_tasks` UPDATE) |
| G5 | Hoàn tất lượt không chen vào; lô cạn → BR-KO-05; sản phẩm ngừng KD chen → 400 không phải 500 | khóa `visits`, lô, `products FOR SHARE` | IT `aCompletionCommitted…`, `aLotDrained…`, `aProductDiscontinued…` |
| G6 | Lượt chỉ tiêm hoàn tất được sau #19; dòng VACCINE không xóa trực tiếp | `VisitCompletionService`, `OrderLinePolicy` | IT `aVaccineOnlyVisitCompletes…` |
| G7 | Authn/authz, validate | `@PreAuthorize`, Bean Validation | `VaccinationControllerSecurityTest`; IT `bodyShapes…`, `unauthenticated…`, `rolesOutside…`; `OpenApiContractParityIT` (155) |

## Hệ quả

- Không đảm bảo: loại vaccine / dòng phác đồ bị tắt chen giữa vẫn ghi được (đọc không khóa; BR-SP-03 chỉ áp cho mũi sau); sát nửa đêm `today` của visit và của inventory có thể lệch 1 ngày (như ADR-0073); double-click = 2 mũi.
- #20 (xóa mũi): advisory 1004 sau `pets FOR SHARE`, rồi `visits`, xóa `vaccinations` → `removeVaccineLine` → `revertVaccination`; cần quyết định khôi phục `superseded_at` / Care Task (TBD ở system-overview §4b).
- T39 xem lại `cancel_reason`, và index `care_tasks.vaccination_id` cho `VACCINE_DUE` (chỉ có partial cho `VACCINE_OVERDUE`).

## Đã cân nhắc

- **Bỏ lời gọi Care Task, ghi nợ cho T39** — T39 quên thì task của mũi đã tiêm lại vẫn `OPEN`.
- **Nhờ BE-2 thêm `StockApi.findFefoLot` không ném** — sửa module BE-2, rủi ro xung đột merge; chọn kiểm trước + chấp nhận BR-KO-05 ở race.
- **Chặn mũi cùng loại trong một lượt** — rule tự thêm, docs không có.
- **Chấp nhận race hai lượt cùng thú, ghi nợ** — làm sai nhắc ST04 và BR-TB-03.
