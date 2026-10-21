# ADR-0060: Xem hàng đợi — `GET /api/visits`, `GET /api/me/queue`, `GET /api/visits/{visitId}`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-20
- **Liên quan:**
  - BR-TN-04, 05, 09 (02 L144, L145, L149), BR-QT-03, BR-QT-08 (L46, L51), BR-TK-11, BR-TK-17 (L27, L33), BR-KB-01, BR-KH-07 (dữ liệu không trả), BR-TN-06 (D024).
  - 01 UC23, UC45, UC46, UC47 (L73, L115–117); 03 §4 Visit; 04 nguyên tắc 8; erd §6 `visits`, `orders.visit_id`.
  - visit-v1 #2, #3, #4 (A1 viết lại, A13 mới, Q2 mới); `docs/api/00-method.md` §3.3, §3.4, §3.5.
  - ADR-0006 (khách không có phạm vi chi nhánh), 0020 (OSIV tắt, reset connection), 0038 (khuôn readOnly + REPEATABLE READ và cách chứng minh), 0048 (khuôn xem lịch hẹn), 0058 (nền visit, D028), 0059 (check-in, `VisitResponse`).
  - Nợ: mở **D041**. D028 đã trả ở 0058, không làm lại.
- **Triển khai (T26 phần 2):**
  - visit: `controller/{VisitController (+listQueue, getVisit), MyQueueController}`, `dto/{QueueServiceGroup, VisitQueueFilter}`, `service/{VisitQueueService, VisitResponder, QueueOrder}`, `repository/VisitRepository` (+`JpaSpecificationExecutor`, `findByAssigneeIdAndStatusIn`), `dto/VisitResponse` (chỉ mô tả `customerName`).
  - identity: `StaffDirectoryApi.findStaffByIds` (+ `StaffProfileRepository.findStaffRows`).
  - sales (file của BE-2, BE-1 viết): `OrderQueryApi.findOrderIdsByVisitIds` (+ `SalesOrderRepository.findVisitOrderIds`).
  - Hợp đồng: `docs/api/generator/m_visit.py` (A1, A13, Q2, mô tả `date`, lỗi 400 / 403 của #1–4, mô tả `customerName`).
  - Test:
    - Unit: `QueueOrderTest`, `QueueServiceGroupTest`, `VisitResponderTest`, `VisitQueueServiceTest`, `VisitControllerSecurityTest`, `StaffDirectoryServiceTest` (+1), `OrderQueryVisitLookupTest`.
    - IT: `VisitQueueIT`, `VisitQueueCountIT`, `VisitQueueIndexIT`, `OrderQueryVisitLookupIT`, `StaffDirectoryIT` (+1), `OpenApiContractParityIT` (95 → 98).
  - Không migration, không event, không job.

## Bối cảnh

Phần 1 tạo được lượt nhưng nhân viên chưa xem được hàng đợi. Phần 3 (gán, sắp, cấp cứu, gọi, gán lại, hủy) cần danh sách để thao tác. Các điểm docs để ngỏ:

- A1 ghi "mặc định Visit hôm nay chưa kết thúc", trong khi yaml ghi tham số `date` "Mặc định hôm nay". Nếu hiểu đúng chữ, lượt dở của hôm trước biến mất khỏi hàng đợi mặc định (cấp cứu qua nửa đêm, lượt quên hoàn tất, lượt cần gán lại).
- Schema `Visit` bắt buộc `orderId`, nhưng liên kết nằm ở `orders.visit_id` (module sales) và chưa có cách tra theo lô.
- Chưa có cách lấy tên nhân viên theo lô.
- Hợp đồng cho CARETAKER xem hàng đợi chi nhánh, trong khi 01 không có use case nguồn.
- `ORDER BY priority_class` theo chữ cái cho `APPOINTMENT < EMERGENCY`, sai BR-TN-04.

## Quyết định

1. **Phân quyền (người dùng chốt A13 ngày 20/10).**
   - #2, #4: BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER (hằng `QUEUE_ROLES`). #3: VET, CARETAKER.
   - Nguồn: A06 = UC45; A05 = UC45 "gán lại lượt đã gọi", Visit#4; A07 = UC47 (đánh dấu cấp cứu lượt chưa gán cho mình); A08 = **giả định A13**: BR-TN-04 "một hàng đợi chung", UC23 cho A08 tra khách / thú toàn chuỗi, schema `Visit` không có dữ liệu y tế. 01 không có use case cho A08 xem lượt của người khác.
   - Loại ADMIN (01 L13), CUSTOMER (ADR-0006), SUPER_MANAGER (hợp đồng). SUPER_MANAGER được gán lại ở Visit#4 nhưng không xem được lượt: ghi **visit-v1 Q2**, chốt cùng phần gán lại.
   - **Hai lớp:** `@PreAuthorize` + `VisitQueueService.requireRole` chạy trước mọi lần đọc DB. Lý do lớp hai: `BranchScope.resolve` của CHAIN trả `null` và `check` không chặn (`BranchScope.java:40`, L60–61), nên nếu `@PreAuthorize` bị sửa nhầm thì SUPER_MANAGER sẽ xem được mọi chi nhánh. Role ngoài tập → `IllegalStateException` (500, lỗi lập trình), khuôn `AppointmentQueryService.viewerOf`.
2. **Phạm vi dữ liệu.**
   - #2 luôn `branch_id` = chi nhánh của người gọi, đọc từ DB mỗi request (ADR-0003). Không có tham số `branchId`; tham số lạ bị bỏ qua.
   - #3 `assignee_id` = người gọi, `WAITING` / `IN_PROGRESS`, không lọc chi nhánh (BR-QT-08 chặn điều chuyển khi còn lượt dở).
   - #4: `findById` → 404, rồi `BranchScope.check` → 403 `ACCESS_DENIED_SCOPE_MISMATCH`. 403 làm lộ việc id tồn tại ở chi nhánh khác: đúng 00-method §3.3; 404 "không được biết là có" chỉ dành cho dữ liệu theo chủ. Message chung, không lộ mã / tên. Tiền lệ lịch hẹn #4.
3. **Bộ lọc #2 (A1 viết lại, người dùng chốt 20/10).**
   - Không gửi `date`: `WAITING` / `IN_PROGRESS` của **mọi ngày**; `status` là `COMPLETED` / `CANCELLED` thì chỉ lượt check-in hôm nay.
   - Không gửi `status`: chỉ `WAITING` + `IN_PROGRESS`, trừ khi gửi `date` (khi đó mọi trạng thái của ngày đó).
   - `date` = ngày của `checked_in_at` theo giờ Việt Nam, `[00:00, 00:00 hôm sau)`.
   - `serviceGroup`, `assigneeId`, `needsReassign`: AND khi được gửi. `assigneeId` lạ / chi nhánh khác → mảng rỗng, không lỗi.
   - Chỉ ghép điều kiện được gửi (`Specification`, khuôn `AppointmentQueryService.filters`).
4. **Thứ tự BR-TN-04** (`QueueOrder`): hạng tường minh EMERGENCY 0 < APPOINTMENT 1 < WALK_IN 2, rồi `queueSortKey`, rồi `id`. Sắp trong bộ nhớ vì danh sách nhỏ (một chi nhánh). Không dùng `ordinal()`. **Ràng buộc cho phần 3 (move):** thứ tự đi theo hạng trước, nên sắp lại bằng `queue_sort_key` chỉ có tác dụng trong cùng hạng.
5. **`orderId` qua `OrderQueryApi.findOrderIdsByVisitIds` (người dùng chốt 20/10).**
   - BE-1 viết trong file của BE-2: một câu `IN` qua `uq_orders_visit_id`, projection, đọc không khóa, chạy trong transaction của bên gọi, tập rỗng không chạm DB, mọi trạng thái Order (Visit#6 để lại Order `CANCELLED`).
   - Không đặt ở `VisitOrderApi`: `VisitOrderServiceTest.everyApiMethodRunsInTheCallersTransaction` (L449–459) bắt mọi method của interface đó là `MANDATORY`; ADR-0059 §10 đã chỉ định `OrderQueryApi`.
   - Lượt không có Order trong cùng snapshot = dữ liệu hỏng → 500 (check-in ghi cả hai trong một transaction, ADR-0059 §9).
6. **Tên theo lô** (`VisitResponder`, khuôn `AppointmentResponder`):
   - Mỗi module một lời gọi cho mỗi lô ≤ 100 id phân biệt, **thứ tự cố định** Order → thú → khách → nhân viên (để IT chèn thay đổi ở lời gọi Order và chứng minh cả response cùng một snapshot).
   - Ghép theo id, không theo thứ tự trả về; thiếu / trùng id → 500; lượt chưa gán không gọi identity.
   - `StaffDirectoryApi.findStaffByIds` (mới): một câu `IN`, mọi trạng thái kể cả bị khóa / vô hiệu hóa (tên người phụ trách là lịch sử), dùng lại `StaffRow` / `toSummary`.
   - `customerName` = tên chủ **lúc tiếp nhận** (`visits.customer_id`, erd §6), không phải chủ hiện tại của thú. Sửa mô tả `@Schema` và hợp đồng cho rõ.
   - Mapper `VisitMapper` dùng lại nguyên; `Parties` không bao giờ `null` (bản MapStruct sinh ra trả tên `null` im lặng khi `parties == null`).
7. **Transaction:** mỗi use case `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` (khuôn ADR-0038, 0048).
   - Snapshot lấy ở câu SQL đầu tiên (câu `visits`); `BranchScope` và `Clock` không chạm DB.
   - Bốn callee tham gia transaction (`readOnly` REQUIRED hoặc không annotation); không `REQUIRES_NEW`, không `try/catch`.
   - Không ghi gì: không audit (08 L50), không outbox, không khóa. Lần ghi duy nhất của request là `accounts.last_seen_at` ở transaction xác thực riêng (BR-TN-06, nợ D024 có sẵn).
8. **Validate / exception.**
   - Không có body, không Bean Validation. Mọi lỗi tham số là binding: 400 `MALFORMED_REQUEST` "Tham số '<tên>' sai kiểu dữ liệu".
   - `serviceGroup` bind vào enum riêng `QueueServiceGroup {MEDICAL, GROOMING}`, nên `BOARDING` → 400.
   - Hành vi Spring đã có bằng chứng chạy ở `AppointmentViewIT` L474–534 và `VisitQueueIT`: enum phân biệt hoa thường; tham số rỗng = không gửi; tham số lặp lấy giá trị đầu; `needsReassign=yes` / `1` / `on` = `true` (`StringToBooleanConverter`), chấp nhận như `checkInReady` của lịch hẹn.
   - Thứ tự: 401 → 400 BR-TK-17 (interceptor, trước binding) → 400 malformed → 403 role → 404 → 403 phạm vi.
   - Hợp đồng sửa: #3 thêm 400 (BR-TK-17 — tiền lệ care #5, appointment L140); #4 thêm 400 (id sai kiểu); mô tả 400 / 403 của #1–4; mô tả tham số `date` theo mục 3.
9. **Response:**
   - #2, #3: `data` là mảng (00-method §3.4), rỗng `[]`, không `PageResponse`; #4: object. `message` "success".
   - 22 trường, `null` vẫn có mặt (Jackson mặc định ALWAYS), thời điểm ISO UTC giữ µs: response của POST #1 bằng GET #4 từng trường.

## Không đảm bảo

- Xem lịch sử theo `date` quét các lượt cùng chi nhánh qua `ix_visits_queue` rồi lọc `checked_in_at`: **nợ D041**.
- Không phân trang: chi nhánh tích tụ nhiều lượt dở (không ai hoàn tất / hủy) thì response lớn theo.
- `needsReassign` luôn `false` tới khi có listener `AccountLockedEvent` (D017, phần sau của T26).
- CARETAKER xem được mọi lượt của chi nhánh (A13) — PO chưa xác nhận.
- SUPER_MANAGER chưa có cách xem lượt cần gán lại (visit-v1 Q2).
- Dữ liệu cũ tới lần poll kế tiếp (đọc theo snapshot).

## Hệ quả

- FE có hàng đợi chi nhánh, hàng đợi của tôi, chi tiết lượt trên BE thật. Mock FE (`mock-api.test.ts:77–82` assert 404 cho `/api/visits`) không đổi: FE-1 sửa khi thêm mock hàng đợi.
- `OrderQueryApi`, `StaffDirectoryApi` có thêm một method; mock `mock()` / `@MockitoBean` hiện có không phải sửa (không class nào `implements`, không test nào duyệt method của hai interface).
- Mỗi lần poll hàng đợi đúng 6 câu SQL có index cho tới 100 lượt, bất kể số lượt: `visits`, `orders`, 2 câu thú (thú + cân nặng mới nhất — `PetQueryService.findPets` cố ý dùng 2 câu cho cả lô), `customers`, nhân viên; danh sách rỗng 1 câu (đã đo ở `VisitQueueCountIT`).

## Mutation (mỗi thay đổi phải làm một test đỏ)

Đã chạy 2026-10-20 bằng script tạm (sửa một chỗ → chạy test đích → khôi phục; có phát hiện lỗi biên dịch để mutation hỏng không bị tính là "bắt được"). 19/19 đỏ:

| # | Mutation | Test đỏ |
|---|---|---|
| M1 | Ép `date` = hôm nay khi không gửi | `VisitQueueIT.defaultQueueIsUnfinishedAndKeepsUnfinishedVisitsOfEarlierDays` |
| M2 | Ranh giới ngày theo UTC | `VisitQueueIT.dateUsesVietnamDayBoundaries` |
| M3 | Sắp theo tên hằng số `priorityClass` | `QueueOrderTest`, `VisitQueueIT.queueFollowsBrTn04` |
| M4 | Bỏ điều kiện `branch_id` | `VisitQueueIT.filtersAreAndedWithBranchScope` |
| M5 | Bỏ `branchScope.check` ở #4 | `VisitQueueIT.otherBranchDetailIs403WithScopeCodeAndLeaksNothing` |
| M6 | Tra tên từng id (lô = 1) | `VisitQueueCountIT` |
| M7 | Bỏ chia lô 100 | `VisitResponderTest.moreThanOneHundredIdsAreSplit` |
| M8 | Bỏ `readOnly` | `VisitQueueIT.queueReadsRunInOneReadOnlyRepeatableReadTransaction` |
| M9 | READ COMMITTED | `VisitQueueIT.wholeResponseSeesOneSnapshot` |
| M10 | `REQUIRES_NEW` trên `StaffDirectoryService.findStaffByIds` | `VisitQueueIT.wholeResponseSeesOneSnapshot` |
| M11 | `QUEUE_ROLES` thêm SUPER_MANAGER | `VisitControllerSecurityTest`, `VisitQueueIT.queueRolesFollowTheContract` |
| M12 | Bỏ `requireRole` trong service | `VisitQueueServiceTest.chainRolesNeverReachTheRepository` |
| M13 | Bỏ `@ApiErrorDoc(400)` của #3 | `OpenApiContractParityIT` |
| M14 | `findOrderIdsByVisitIds` bỏ chặn tập rỗng | `OrderQueryVisitLookupTest.emptySetTouchesNoRepository` |
| M15 | Đảo khóa / giá trị của map Order | `OrderQueryVisitLookupIT` (sau khi sửa — xem dưới), `OrderQueryVisitLookupTest` |
| M16 | `Parties` null vào mapper | `VisitResponderTest` |
| M17 | Bỏ mặc định "chưa kết thúc" khi không gửi `status` | `VisitQueueIT.defaultQueueIsUnfinishedAndKeepsUnfinishedVisitsOfEarlierDays` |

**Lỗ hổng mutation tìm ra và đã sửa:** lần chạy đầu M15 **xanh** ở `OrderQueryVisitLookupIT`. Các IT khác chèn lượt + Order liền nhau nên `visits.id` và `orders.id` trùng số; đảo map không đổi kết quả. Helper `order(…)` của IT nay bảo đảm `orders.id ≠ visits.id` (trùng thì xóa, chèn lại), kèm assert điều kiện đó.

**Không chạy mutation (ghi rõ):** `customerName` lấy theo chủ hiện tại của thú (cần đổi cả nguồn id lẫn lời gọi; IT `customerNameIsTheOwnerAtCheckIn` khóa hành vi); thêm `/api/me/queue` vào danh sách miễn BR-TK-17 và cache role theo token (code hạ tầng của identity, không sửa ở phần này; IT `precedence…` và `branchAndRoleComeFromTheDatabaseOnEveryRequest` khóa hành vi).

## Đã cân nhắc

- **Đúng chữ A1 (mặc định chỉ hôm nay)** — giấu lượt dở của hôm trước khỏi hàng đợi chung, trong khi `/me/queue` không lọc ngày và BR-QT-08 cần tìm được lượt dở.
- **Không lọc ngày khi thiếu `date`** — `status=COMPLETED` không `date` sẽ trả toàn bộ lịch sử, trái "Mặc định hôm nay" của yaml.
- **`ORDER BY` bằng `CASE` trong SQL** — được, nhưng danh sách nhỏ và sắp trong bộ nhớ dễ kiểm bằng unit test; index không cung cấp thứ tự này dù sao.
- **Đặt `findOrderIdsByVisitIds` ở `VisitOrderApi`** — xem mục 5.
- **Migration `(branch_id, checked_in_at)` ngay** — cần thống nhất số V19 với BE-2; xem lịch sử theo ngày không phải đường nóng (D041).
- **Thu hẹp quyền của CARETAKER theo 01** — người dùng chọn giữ hợp đồng (A13).
