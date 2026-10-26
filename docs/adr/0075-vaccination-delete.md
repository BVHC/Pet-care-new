# ADR-0075: Xóa mũi tiêm ghi nhầm (visit-v1 #20) — quyền, tính lại mũi cũ, Care Task không mở lại

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-26
- **Liên quan:** BR-KB-04, BR-TB-03, BR-TN-08, BR-BH-02, BR-KO-05; 03 Visit L109, Care Task#5; visit-v1 #20, A24, A25, Q4; ADR-0044, 0067, 0074; nợ D047.
- **Triển khai (T28 phần 3):** `VaccinationRecordService.delete`, `VaccinationController.deleteVaccination`, `VaccinationRepository.{lockDoseInVisit, deleteDose, restoreLatest}` + projection `LockedDose`. Không migration, không đổi `api/` của module nào.

## Quyết định

1. **Thứ tự lỗi** (chép #19): 400 id sai kiểu → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → **khóa** `pets FOR SHARE` → advisory 1004 → `visits` → 403 không phải người phụ trách → 409 không `IN_PROGRESS` → 404 mũi không thuộc lượt. Guard sai thì chưa ghi gì.
2. **Thứ tự khóa:** `pets FOR SHARE` → advisory **1004** (dùng chung với #19, ADR-0074 §5) → `visits` → `vaccinations FOR UPDATE` (dòng sắp xóa; ST04 `SKIP LOCKED` ở T39 sẽ bỏ qua mũi đang bị xóa) → `orders` → lô. READ COMMITTED: các lần đọc sau khóa phải thấy dữ liệu mới nhất (ADR-0061 M5).
3. **Thứ tự ghi:** DELETE mũi → `removeVaccineLine` → `revertVaccination(lô đã trừ, 1)` → tính lại. Xóa mũi trước vì FK `vaccinations.order_line_id` là `RESTRICT`; Order trước lô (ADR-0044). Khác chữ của erd L634 ("hoàn kho trước") nhưng tương đương trong một transaction.
4. **Quyền (A24):** người phụ trách **hiện tại**, không cần là người đã ghi. Chỉ người phụ trách ghi được mũi (#19) và BR-TN-08 chuyển quyền của người cũ sang người mới, nên "VET đã ghi mũi đó" trùng với người phụ trách hiện tại; sau gán lại, người cũ nhận 403.
5. **Tính lại (A25):** chỉ khi mũi bị xóa **chưa** bị thay thế, mũi còn lại mới nhất cùng (thú, loại) (`administered_on DESC, id DESC`, như `findLatestDoses`) được `superseded_at = NULL` để ST04 nhắc lại theo nó. Mũi bị xóa đã bị thay thế thì không đổi gì. Giữ bất biến ADR-0074 G3: mỗi (thú, loại) có ≤ 1 mũi chưa thay thế và đó là mũi mới nhất.
6. **Care Task không mở lại (Q4, TBD PO):** task `VACCINE_*` bị hủy lúc ghi mũi (Care Task#5) giữ `CANCELLED` vì đó là trạng thái cuối (03 §10). Không reset `due_reminded_at`.
7. **Không audit** (BR-QT-15 không liệt kê), như #19. Dấu vết: cặp movement `VACCINATION` + `VACCINATION_REVERT` (`source vaccinations/<id>`, `created_by`).
8. **204 không body**, handler `void` + `@ResponseStatus(NO_CONTENT)`.

## Bảng đảm bảo

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| G1 | Thứ tự khóa → guard → ghi; guard sai không ghi gì | `delete` | `VaccinationRecordServiceTest` (`InOrder`, `verifyNoInteractions`); IT `visitGuardsAreCheckedInTheContractOrder`, `aDoseOfAnotherVisit…` (so `State` + `xmin`) |
| G2 | Hoàn đúng lô kể cả lô đã hết hạn; cặp movement; Order, bệnh án, #18 đọc lại đúng | `StockApi`, `VisitOrderApi` | IT `deletesTheDoseHandsTheSameLot…` |
| G3 | Tính lại mũi cũ đúng thú / đúng loại; Care Task đã hủy giữ nguyên; ghi lại thay thế đúng một lần | `restoreLatest` | IT `deletingTheLatestDoseRestoresThePreviousOne…`, `deletingASupersededDoseChangesNoOtherDose` |
| G4 | Lượt chỉ tiêm không hoàn tất được sau khi xóa mũi duy nhất; lưu trú thấy lại thiếu mũi | `VisitCompletionService`, `VaccinationQueryApi` | IT `aVaccineOnlyVisitCannotComplete…`, `deletingTheOnlyDoseOfAType…` |
| G5 | Một transaction: lỗi ở dòng Order / movement / mũi cũ / Care Task trỏ tới mũi → rollback tất cả, trả 409 | `@Transactional` + callee `MANDATORY` | IT `aFailureRemovingTheOrderLine…`, `aFailureInTheRevertMovement…`, `aFailureRestoringTheOlderDose…`, `aCareTaskPointingAtTheDose…`; unit kiểm annotation (READ COMMITTED, ghi, không `noRollbackFor`) |
| G6 | Race: xóa đôi → 204 + 404, hoàn lô một lần; hoàn tất / gán lại chen → 409 / 403 không ghi; #19 ở lượt khác của cùng thú xếp hàng trên 1004 | advisory 1004, `visits` | IT `aSecondDeleteOfTheSameDose…`, `aCompletionCommitted…`, `aReassignCommitted…`, `aRecordInAnotherVisit…` |
| G7 | Authn / authz, id sai kiểu, quyền sau gán lại | `@PreAuthorize`, `BranchScope`, guard dưới khóa | `VaccinationControllerSecurityTest`; IT `malformedPaths…`, `unauthenticated…`, `rolesOutside…`, `afterReassignOnlyTheNewAssignee…`, `aLockedVetIs401…`; `OpenApiContractParityIT` (156) |

## Hệ quả

- **Nghĩa vụ cho T39 (D047):** ST04 / ST18 chỉ sinh task `VACCINE_*` cho mũi của lượt `COMPLETED`. Task trỏ tới mũi qua FK `RESTRICT` làm #20 trả 409 (`CONCURRENCY_CONFLICT`); lượt đã `COMPLETED` không xóa mũi được nên mũi của lượt đó không bao giờ bị #20 chạm.
- Báo cáo T44: mũi đã xóa biến mất, cặp movement cộng lại bằng 0.
- Không đảm bảo: xóa mũi X đã bị thay thế không phục hồi gì cho mũi khác (đúng: mũi mới hơn vẫn tồn tại); double-click là 204 rồi 404.

## Đã cân nhắc

- **Không dùng advisory 1004** — race với #19 ở lượt khác của cùng thú có thể để lại 0 hoặc 2 mũi chưa thay thế.
- **Quyền theo `recorded_by`** — sau gán lại lượt (BR-TN-08) người mới không sửa được sai sót của người cũ, người cũ lại xóa được dù không còn phụ trách.
- **Mở lại Care Task / reset `due_reminded_at`** — thêm cạnh FSM và rule nghiệp vụ mà docs không có; ST04 đã nhắc lại được nhờ mũi cũ hết `superseded`.
- **Không tính lại mũi cũ** — sai BR-TB-03: không còn "mũi mới hơn" nhưng mũi cũ vẫn bị coi là đã tiêm lại, ST04 không nhắc nữa.
