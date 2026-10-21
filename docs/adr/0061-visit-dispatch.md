# ADR-0061: Điều phối hàng đợi — gán lượt, sắp lại, xác định ca cấp cứu (`POST /api/visits/{visitId}/assign`, `/move`, `/mark-emergency`)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-21
- **Liên quan:**
  - BR-TN-01, 04, 05, 06, 08, 09 (02 L141, L144–149), BR-SP-06 (L109), BR-BH-02 (L190), BR-QT-06, 08, 11 (phía bên kia của khóa), BR-QT-15 (không audit), BR-TK-17.
  - 01 UC45, UC47 (L115, L117); 03 §4 Visit #2 (L103), whitelist (L8); 04 L89 (`VisitAssignment`); erd §6 `visits`, `visit_assignments`, `order_lines.owner_account_id`.
  - visit-v1 #5, #6, #7; A2, A3 viết lại; `PriorityClass` mô tả lại; `docs/api/00-method.md` L65–70.
  - ADR-0003 (role / chi nhánh đọc từ DB mỗi request), 0024 (`active`, `online`), 0031 mục 9 + 0033 (nghĩa vụ khóa `accounts FOR SHARE`), 0042 (khóa tập theo id), 0044 / 0059 Q6 (chủ dòng Order VISIT), 0049 (khuôn no-op khi bấm lại), 0058 (thứ tự khóa, tên command), 0060 (hàng đợi, `VisitResponder`, `requireRole`).
  - Nợ: trả **D016 *Cách trả* 4** phía visit (gán lượt khóa `accounts FOR SHARE`). Không mở nợ mới.
- **Triển khai (T26 phần 3):**
  - visit: `controller/VisitController` (+3 handler), `service/{VisitDispatchService, QueueReorder}`, `dto/{AssignRequest, MoveVisitRequest, AssignResult}`, `entity/Visit` (`assignTo`, `reorder`, `markEmergency`), `entity/VisitAssignment.of`, `entity/PriorityClass` (javadoc), `dto/VisitResponse` (`@Schema` của `priorityClass`), `repository/VisitRepository` (`lockById`, `lockWaitingIdsOfBranch`), `repository/VisitAssignmentRepository`, `mapper/VisitMapper.toAssignResult`.
  - identity: `StaffDirectoryApi.findStaffForShare` (`StaffDirectoryService`), `AccountRepository.lockForShare`.
  - sales: không sửa; gọi `VisitOrderApi.setAutoLineOwner` / `transferLineOwnership` có sẵn.
  - Hợp đồng: `docs/api/generator/m_visit.py` (A2, A3, mô tả `PriorityClass`, lỗi / ghi chú #5–7).
  - Test:
    - Unit: `VisitDispatchServiceTest` (33), `QueueReorderTest` (12), `StaffDirectoryServiceTest` (+5), `VisitMapperTest` (+1), `VisitAssignmentTest` (2), `AssignRequestValidationTest` (2), `VisitControllerSecurityTest` (sửa map).
    - IT: `VisitDispatchIT` (31: đường đúng, rule, thứ tự lỗi, ma trận đầu vào, vai trò / phiên / chéo chi nhánh, rollback T4–T8, race I1–I4 và T11), `VisitDispatchCountIT` (1), `OpenApiContractParityIT` (98 → 101).
  - Không migration, không event, không job, không audit, không thông báo.

## Bối cảnh

Phần 1, 2 tạo và hiển thị được hàng đợi, nhưng lễ tân chưa điều phối được. Các điểm docs để ngỏ:

- A3 "đặt Visit trước một Visit khác; server tính lại `queue_sort_key`", trong khi thứ tự BR-TN-04 đi theo hạng trước (ADR-0060 §4). Chỉ đổi khóa thì không đưa được walk-in lên trên lượt hẹn.
- Các lượt hẹn cùng khung có **cùng** khóa (= giờ hẹn, `QueuePlacement`), nên không lấy trung điểm được.
- Gán lại lượt chưa gọi: sales cho người được gán thêm dòng dịch vụ ngay khi lượt còn `WAITING` (`OrderLineService.requireAssignedVisit` không kiểm trạng thái). BR-TN-08 chỉ nói chuyển quyền xóa dòng cho lượt **đã gọi**.
- A2 không nói khóa sắp xếp của ca được xác định cấp cứu sau khi tiếp nhận.
- Bấm lại (mất response, bấm đúp) chưa có quy định.
- Nghĩa vụ của ADR-0031 §9 (D016 *Cách trả* 4): FK `visits.assignee_id` chỉ lấy `FOR KEY SHARE`, không chặn điều chuyển / vô hiệu hóa / khóa người được gán.

## Quyết định

**Q1–Q4 do người dùng chốt ngày 2026-10-21** (chọn phương án đề xuất); các mục còn lại là quyết định kỹ thuật theo docs.

1. **Q1 — Sắp lại nhận hạng của lượt đích** (`QueueReorder`).
   - Lượt được sắp lấy `priority_class` của lượt đích. Khóa = khóa đích − 1 µs, nhưng ít nhất bằng khóa lượt liền trước cùng hạng + 1 µs.
   - Các lượt cùng hạng từ lượt đích trở đi có khóa ≤ khóa vừa đặt thì dồn +1 µs; dừng ở lượt đầu tiên không phải đổi.
   - **EMERGENCY không vào và không ra bằng move.** Lượt thường đặt trước ca cấp cứu, hoặc ngược lại → 400 BR-TN-04. Vào nhóm cấp cứu thì dùng #7.
   - `priority_class` là **hạng trong hàng đợi**, không phải "có lịch hẹn hay không". Muốn biết lượt có lịch thì xem `appointment_id`. Javadoc `PriorityClass`, `@Schema` và yaml đã sửa theo nghĩa này.
2. **Q2 — Gán lại lượt chưa gọi A → B:**
   - `setAutoLineOwner(visit, B)` (BR-TN-01), rồi `transferLineOwnership(visit, A, B)`.
   - Đây là suy ra từ BR-TN-08 / BR-BH-02, không phải câu chữ của rule: người phụ trách toàn quyền với Order của lượt mình.
   - Dòng hàng lễ tân thêm (chủ = lễ tân) không đổi. `added_by` không đổi.
3. **Q3 — #7:** `priority_class = EMERGENCY`, `queue_sort_key` = thời điểm đánh dấu, cắt về µs. Khớp A10: cấp cứu lúc check-in thì khóa = lúc khai cấp cứu. Ca cấp cứu xếp theo lúc được xác định.
4. **Q4 — Bấm lại:**
   - Gán đúng người đang được gán, hoặc sắp vào đúng chỗ hiện tại (đã đứng ngay trước lượt đích, cùng hạng) → 200, không ghi gì: không UPDATE, không log, không gọi sales.
   - #7 khi lượt đã là cấp cứu → 409, đúng hợp đồng.
5. **Phân quyền:**
   - #5, #6: chỉ A06 (03 L103, UC45, BR-TN-04 "lễ tân sắp lại").
   - #7: A06, A07 (UC47). VET nào của chi nhánh cũng được, không cần là người được gán (ADR-0060 §1).
   - `@PreAuthorize`, cộng `VisitDispatchService.requireRole` chạy trước mọi lần đọc DB (lý do như ADR-0060 §1: CHAIN không bị `BranchScope` chặn).
   - 403 không audit.
6. **Thứ tự lỗi** (00-method L70, khuôn ADR-0059): 401 → 400 BR-TK-17 → 400 body / path → 403 role → 404 lượt → 403 lượt chi nhánh khác → **400 rule → 409**.
   - Ví dụ: lượt đã gọi + nhân viên sai chức vụ → 400 BR-TN-05.
   - #6 kiểm phạm vi của lượt nguồn trước khi đọc lượt đích.
7. **Guard:**
   - **#5 (dưới khóa):** người được gán là nhân viên `active` (= `ACTIVE` và không `is_locked`; khóa tạm `locked_until` không tính, ADR-0024), cùng chi nhánh với lượt, đúng chức vụ theo nhóm (`MEDICAL → VET`, `GROOMING → CARETAKER`; BR-TN-05, BR-SP-06). Mọi lý do "không hoạt động / khác chi nhánh / không phải nhân viên / id không có" trả **cùng** message 400 BR-TN-05, không lộ nhân viên chi nhánh khác.
   - **#5:** lượt `WAITING` — cạnh #2 qua `validateTransition` → 409.
   - **#6:**
     - đích = chính nó → 400 BR-TN-04;
     - đích không có → 404;
     - đích chi nhánh khác → 400 BR-TN-04 (khuôn BR-TN-02 của check-in);
     - vượt ranh giới cấp cứu (đọc dưới khóa) → 400 BR-TN-04;
     - nguồn / đích không `WAITING` → 409.
   - **Mã BR-TN-04 do ta gán:** cột "Xử lý khi vi phạm" của rule ghi "Không áp dụng", và hợp đồng chỉ ghi 400.
   - **#7:** `WAITING` và chưa `EMERGENCY` → 409 (`InvalidStateTransitionException("hạng ưu tiên của lượt", …)`).
   - **Cố ý không kiểm:**
     - online (BR-TN-06 chỉ cảnh báo, trả `assigneeOffline` + ghi `visit_assignments.assignee_was_offline`);
     - người cũ còn ACTIVE (điều kiện của #4, không phải #2);
     - lý do;
     - giờ mở cửa / trạng thái chi nhánh (không có trong 03 #2);
     - thú đã mất (BR-KH-05 chặn đánh dấu mất khi có Visit mở);
     - nhóm `GROOMING` khi xác định cấp cứu (check-in cũng không kiểm).
8. **Khóa** (thứ tự ADR-0058 mục 5):
   - **#5:** `accounts FOR SHARE` qua `StaffDirectoryApi.findStaffForShare` → `visits` (`lockById`, `FOR NO KEY UPDATE`) → `orders` (sales).
     - `findStaffForShare`: câu khóa vô hướng `SELECT id … FOR SHARE`, rồi **câu đọc riêng** `staff_profiles ⋈ accounts`, để câu đọc lấy snapshot mới sau khi chờ khóa. Gộp JOIN vào câu khóa thì Postgres chỉ đọc lại dòng bị khóa, có thể trả `branch_id` cũ.
     - `MANDATORY` trên method (ghi đè `readOnly` của class), chặn transaction readOnly.
   - **#6:** mọi lượt `WAITING` của chi nhánh bị khóa bằng **một** câu `ORDER BY id FOR NO KEY UPDATE` (khuôn lô kho ADR-0042), nên hai lần sắp cùng chi nhánh chạy lần lượt. Lượt nguồn / đích không nằm trong tập (đã được gọi, hoặc vừa check-in sau câu khóa tập) được `lockById` riêng **trước** khi nạp entity.
   - **#7:** `lockById` một dòng.
   - **Mọi entity được nạp sau khóa:** bước 404 / 403 dùng projection `findSummary`, vì `branch_id` không đổi sau khi tạo.
   - Method khóa của repository đều `MANDATORY`.
   - **Không vòng chờ:**
     - điều chuyển / vô hiệu hóa / khóa: `branches → accounts (FOR NO KEY UPDATE)` rồi đọc `visits` không khóa;
     - REST sales khóa `orders` mà không chờ `visits`;
     - check-in chỉ INSERT;
     - `touchLastSeen` dùng `FOR UPDATE OF a SKIP LOCKED` nên bỏ qua, không chờ khóa chia sẻ.
9. **Transaction:** mỗi command một `@Transactional` (REQUIRED, READ COMMITTED, không `noRollbackFor`), không try/catch.
   - #5 ghi theo thứ tự `saveAndFlush(visit)` → `saveAndFlush(log)` → sales (tự `flush`) → dựng response. Lỗi ở bất kỳ bước nào, kể cả bước dựng response, rollback cả lượt, log và chủ dòng.
   - READ COMMITTED là bắt buộc. Với REPEATABLE READ, snapshot lấy từ câu pre-read đầu tiên; câu đọc nhân viên sau khi chờ khóa vẫn thấy chi nhánh cũ, và **gán thành công cho người vừa bị điều chuyển** (đã chứng minh bằng mutation M5).
10. **Response:**
    - #5 `AssignResult{visit, assigneeOffline}` (MapStruct `toAssignResult`, `visit` gán thẳng).
    - #6 hàng đợi chi nhánh như #2 không lọc (mảng).
    - #7 `Visit`.
    - Cả ba 200; no-op cùng hình dạng.
    - `queueSortKey` giữ µs nên POST = GET từng trường.

## Lý do

- Q1 giữ đúng ý "sắp lại thủ công" của BR-TN-04 mà không cần migration (V19 phải thống nhất với BE-2). Ranh giới cấp cứu giữ ca cấp cứu chỉ đổi qua UC47.
- "Khóa đích − 1 µs + dồn" xử lý được khóa trùng (nhiều lượt hẹn cùng khung) với số dòng ghi tối thiểu, và bảo đảm thứ tự chặt từ lượt được sắp trở đi.
- Khóa `FOR SHARE` là cách duy nhất để luồng gán xếp hàng với điều chuyển / vô hiệu hóa / khóa: không thể khóa trước một dòng `visits.assignee_id` chưa tồn tại (ADR-0031 §9).
- No-op khi bấm lại tránh 409 giả và log thừa khi response bị mất (khuôn ADR-0031, 0049).

## Hệ quả

- Lễ tân điều phối được hàng đợi trên BE thật.
- `VisitQueryApi.findUnfinishedVisitsAssignedTo` nay thấy lượt được gán, nên điều chuyển / vô hiệu hóa / khóa nhân viên thấy việc dở (BR-QT-08). Vẫn 500 ở `CashierShiftQueryApi` (BE-2, phần còn lại của D016).
- Sales: người được gán mới sửa / xóa được dòng tự sinh và các dòng người cũ đã thêm; người cũ hết quyền.
- Hàng đợi #2–4 hiện người được gán, hạng và khóa mới.
- `priority_class` của lượt walk-in đã sắp lên trên lượt hẹn là `APPOINTMENT`; FE cần đọc `appointmentId` để biết có lịch.
- **Không đảm bảo:**
  - Lượt check-in chen vào **trong lúc** sắp lại (INSERT không bị khóa) có thể có khóa rơi vào giữa đoạn vừa sắp. Thứ tự vẫn hợp lệ, chỉ không đúng "ngay sau" như màn hình cũ.
  - A3 chỉ có "đặt trước", không đưa được lượt xuống cuối hàng.
  - Người gọi bị điều chuyển / khóa **trong lúc** request chạy: request đó vẫn hoàn tất theo quyền đọc ở đầu request (như mọi endpoint, ADR-0003).
  - Người được gán đang `mustChangePassword` vẫn được gán (BR-TN-05 chỉ đòi `ACTIVE`).

## Mutation (mỗi thay đổi phải làm một test đỏ)

Đã chạy 2026-10-21, sửa từng chỗ (hoặc nhóm chỗ mà mỗi chỗ có test đích riêng) → chạy test đích → khôi phục. M1–M12: 12/12 đỏ. M13, M14 **chưa chạy** (bị dừng cùng lần `mvn clean verify` do máy thiếu RAM); kết quả dự kiến dựa trên test so khớp chính xác, cần chạy lại:

| # | Mutation | Test đỏ |
|---|---|---|
| M1 | `findStaffForShare` bỏ câu khóa `FOR SHARE` | `VisitDispatchIT.assignWaitsForAConcurrentTransferDisableOrLockOfTheAssignee` |
| M2 | Bỏ `transferLineOwnership` khi gán lại | `VisitDispatchIT.reassigningMovesTheAutoLineAndTheOldAssigneesLinesButNotTheReceptionistsGoods` |
| M3 | Bỏ `branchScope.check` ở #5 | `VisitDispatchIT.nothingCrossesTheBranchBoundary` |
| M4 | Bỏ `truncatedTo(MICROS)` ở #7 | `VisitDispatchIT.markEmergencyByReceptionistOrVetPutsTheVisitFirstWithTheMarkingTime` (POST ≠ GET) |
| M5 | `isolation = REPEATABLE_READ` ở #5 | `VisitDispatchIT.assignWaits…` (gán thành công 200 cho người vừa bị điều chuyển) |
| M6 | Bỏ câu khóa tập `lockWaitingIdsOfBranch` | `VisitDispatchIT.concurrentMovesInOneBranchRunOneAfterTheOther`, `VisitDispatchServiceTest.moveLocksTheBranchWaitingSet…` |
| M7 | `noRollbackFor = PlatformException` ở #5 | `VisitDispatchIT.salesRejectingTheOrderAfterTheVisitWasWrittenRollsBackEverything`, `VisitDispatchServiceTest.everyCommandRunsInItsOwnReadCommittedWriteTransaction` |
| M8 | Đảo 400 BR-TN-05 / 409 | `VisitDispatchServiceTest.calledVisitIs409ButAnInvalidAssigneeIs400First` |
| M9 | Bỏ `requireRole` ở #5 | `VisitDispatchServiceTest.dispatchRolesOutsideTheContractNeverReachTheRepository` (6 ca) |
| M10 | Bỏ nhánh no-op (Q4) | `VisitDispatchServiceTest.assigningTheCurrentAssigneeAgainWritesNothing` |
| M11 | Khóa `visits` trước `accounts` | `VisitDispatchServiceTest.firstAssignmentLocksAccountThenVisitThenWritesInOrder` |
| M12 | Khóa mới = khóa đích (bỏ −1 µs) | `QueueReorderTest` (3 ca) |
| M13 | `@PreAuthorize` #5 = `QUEUE_ROLES` | *(chưa chạy)* `VisitControllerSecurityTest` (map chính xác) |
| M14 | Bỏ `@ApiErrorDoc` của một lỗi | *(chưa chạy)* `OpenApiContractParityIT` (mô tả lỗi khớp yaml) |

**Không phát hiện được bằng hành vi (ghi rõ):**
- `save` thay `saveAndFlush` cho visit; bỏ `flush()` của #6. Hibernate (FlushMode AUTO) tự flush entity bẩn trước câu JPQL / Criteria kế tiếp, nên kết quả cuối giống nhau. Giữ hai lệnh đó để lỗi DB lộ ở đúng bước; chặn bằng review và `verify(visits).saveAndFlush` / `verify(visits).flush()` trong unit.
- Bọc lời gọi sales bằng try/catch rồi đi tiếp: dữ liệu **vẫn** rollback (rollback-only), chỉ HTTP đổi 409 → 500.

## Đã cân nhắc

- **Chỉ sắp trong cùng hạng** (khác hạng → 400): đơn giản, nhưng lễ tân không đưa walk-in lên trên lượt hẹn được, hạn chế BR-TN-04.
- **Cột vị trí thủ công** (`manual_position`, migration): linh hoạt nhất, nhưng đổi schema (V19 cần BE-2), `QueueOrder` và phần 2.
- **Trung điểm hai khóa:** không chia được khi các lượt hẹn cùng khung có cùng khóa.
- **Chỉ chuyển dòng tự sinh khi gán lại lượt chưa gọi** (đúng chữ hợp đồng): người cũ còn quyền xóa dòng trên lượt không còn thuộc mình, người mới không động vào được.
- **`queue_sort_key` = `checked_in_at` khi xác định cấp cứu:** lượt đến sớm nhưng phát hiện cấp cứu muộn sẽ đứng trước ca cấp cứu đã khai trước.
- **Bấm lại vẫn ghi log:** lịch sử bị nhiễu bởi các lần bấm không đổi gì.
- **409 trước 400** (đề xuất ban đầu khi thảo luận): trái `00-method.md` L70; đổi về 400 trước 409 như check-in.
- **`assigneeId` không có → 404:** `assigneeId` là tham chiếu trong body, hợp đồng ghi 400 BR-TN-05; 404 sẽ lộ việc id tồn tại.
- **REPEATABLE READ cho đồng nhất với phần 2:** sai đúng chỗ race cần bảo vệ (M5).
