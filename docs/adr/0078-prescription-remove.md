# ADR-0078: Xóa một dòng đơn thuốc — visit-v1 #17

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-26
- **Liên quan:**
  - BR-KB-03, BR-BH-02, BR-QT-15, BR-KH-03, BR-TN-08; visit-v1 #17, A30.
  - ADR-0075 (khuôn xóa mũi #20), 0076 (nền đơn thuốc, C1–C11), 0077 (kê đơn #16), 0044 (khóa Order), 0061 (READ COMMITTED sau khóa).
  - erd §0, §13 mục 21. Nợ: D043 (cập nhật), D045.
- **Triển khai (T38 phần 2):** `PrescriptionService.remove`, `PrescriptionController.removePrescriptionItem`, `PrescriptionItemRepository.lockItemInVisit` / `deleteItem` (+ projection `LockedItem`), `VisitAuditActions.PRESCRIPTION_ITEM_REMOVED`. Không migration, không sửa module khác (`VisitOrderApi.removeDrugLine` đã có từ ADR-0044).
- **Test:** `PrescriptionRemoveServiceTest` (25), `PrescriptionControllerSecurityTest` (+1 handler), `PrescriptionIT` (+19, tổng 44, era 2017); `OpenApiContractParityIT` 158 endpoint.

## Quyết định

1. **Thứ tự** (khuôn #20, không khóa thú, không advisory):
   - role → lượt (404) → chi nhánh (403) → `GROOMING` (404);
   - `visits` → người phụ trách (403) → `IN_PROGRESS` (409);
   - dòng đơn của lượt `FOR UPDATE` (404);
   - ghi: DELETE dòng đơn → `removeDrugLine` (`orders`, bỏ qua với dòng mua ngoài) → audit.

   Lý do từng điểm:
   - Dòng đơn xóa **trước** dòng Order vì FK `prescription_items.order_line_id` là `RESTRICT`.
   - Không khóa thú: #17 không tạo bệnh án nên không làm thú bị khóa đổi loài.
   - Dòng đọc bằng projection `FOR UPDATE`, không nạp entity: bulk DELETE không để entity cũ trong persistence context.
   - Không đọc Order phía visit (D045); `removeDrugLine` tự khóa Order trước khi đọc.
2. **Khóa không deadlock với sales #11:** #11 khóa `orders → prescription_items` (ADR-0076 §4) nhưng chỉ chạy trên Order `PENDING`, tức lượt đã `COMPLETED`. #17 chỉ chạy khi lượt `IN_PROGRESS` đọc dưới khóa `visits`, và hoàn tất #5 cần chính khóa đó. Hai luồng không bao giờ cùng đụng một dòng.
3. **Transaction:** một `@Transactional` mặc định (REQUIRED, READ COMMITTED, không `noRollbackFor`, không `catch`). READ COMMITTED bắt buộc: dưới REPEATABLE READ, `findById` sau khi chờ khóa vẫn thấy lượt `IN_PROGRESS` (lỗi M5 của ADR-0061). Hai method repository `MANDATORY`. Cả ba lệnh ghi (dòng đơn, dòng Order + `total_amount`, audit) commit hoặc rollback cùng nhau; không có tác dụng phụ sau commit.
4. **Quyền:** chỉ VET phụ trách **hiện tại** (A30), không cần là người đã kê. Gán lại #9 đã chuyển chủ dòng Order cho người mới (`transferLineOwnership`), nên kiểm owner của `removeDrugLine` luôn khớp và chỉ là lớp phòng thủ. Lễ tân và VET không xóa được dòng `DRUG` qua sales (400 BR-BH-02, `OrderLinePolicy`), nên #17 là đường duy nhất.
5. **Dòng mua ngoài xóa được** (A30): chỉ xóa dòng đơn, không gọi sales (không có dòng Order).
6. **Bệnh án rỗng do #16 tạo được giữ nguyên** khi xóa dòng cuối: không có rule xóa `medical_records`; thú vẫn bị khóa đổi loài (BR-KH-03), như một lượt có bệnh án thường.
7. **Xóa cứng, cần bổ sung erd:** erd §0 chưa liệt kê BR-KB-03 trong các trường hợp xóa cứng. Đã thêm `BR-KB-03` vào §0 và mục 21 vào §13, theo tiền lệ BR-TK-18 (mục 16). Cơ sở: BR-BH-02 cho người thêm xóa dòng khi Order `OPEN`, contract #17 có sẵn, bảng không có cột trạng thái nên không có "xóa mềm", không bảng nào trỏ tới `prescription_items`.
8. **Audit** `PRESCRIPTION_ITEM_REMOVED`: `entity = prescription_items`, `before` = `{visitId, productId, quantity, dosageInstructions, isExternalPurchase, externalReason, orderLineId}` (cùng khóa và thứ tự với `after` của `PRESCRIPTION_ITEM_ADDED`, bỏ `requestedQuantity`), không có `after`.
9. **Lỗi** (không thêm exception, không sửa handler): 400 `MALFORMED_REQUEST` id sai kiểu (trước 403 vai trò) · 403 vai trò / chi nhánh / người phụ trách · 404 lượt, `GROOMING`, dòng không thuộc lượt (không lộ sự tồn tại) · 409 lượt không `IN_PROGRESS`. Phòng thủ, ngoài contract: `deleteItem ≠ 1` → 500; lỗi từ `removeDrugLine` lan ra, audit không chạy.

## Bảng đảm bảo

| # | Đảm bảo | Test |
|---|---|---|
| G1 | Dòng `DRUG`: dòng đơn + dòng Order cùng mất, tổng tính lại, kho không đổi, audit `before` đủ khóa | `PrescriptionIT.removeDeletesTheItemAndItsDrugLineAndAudits` |
| G2 | Mua ngoài: chỉ dòng đơn mất, Order không đổi (xmin) | `removeAnExternalItemLeavesTheOrderUntouched`; unit `anExternalPurchaseDeletesOnlyTheItemAndNeverCallsSales` |
| G3 | Bệnh án rỗng còn nguyên, loài vẫn khóa | `removingTheLastItemKeepsTheEmptyRecordAndTheSpeciesLock` |
| G4 | Thứ tự lỗi theo contract, guard sai không ghi gì | `removeGuardsAreCheckedInTheContractOrder`, `removeIdShapesAre400BeforeTheRoleCheck`, `anItemOfAnotherVisitAndASecondDeleteAre404`; unit từng guard |
| G5 | Thứ tự khóa / ghi: visit → dòng → DELETE → `removeDrugLine` → audit | `PrescriptionRemoveServiceTest` (`InOrder`) |
| G6 | Phân quyền: 401, BR-TK-17, role ngoài VET, khóa tài khoản / đổi chức vụ có hiệu lực ngay, sales không là cửa sau | `removeRolesAndSessionsAreCheckedBeforeAnythingIsRead`, `aReceptionistCannotDeleteTheDrugLineThroughSales`, `PrescriptionControllerSecurityTest` |
| G7 | Gán lại: VET mới xóa được, VET cũ 403 | `afterReassignOnlyTheNewVetCanRemove` |
| G8 | Rollback: lỗi xóa dòng Order hoặc audit (dòng DRUG và dòng mua ngoài) → DB nguyên vẹn; repo ngoài tx bị từ chối | `aFailureRemovingTheOrderLine…`, `aFailureWritingTheRemoveAudit…`, `lockAndDeleteOutsideATransactionAreRefused…` |
| G9 | Đồng thời: hai DELETE → 204 + 404; hoàn tất commit khi chờ → 409; gán lại commit khi chờ → 403; kê #16 xếp hàng sau → cả hai thành công | `twoConcurrentRemoves…`, `aCompletionCommitted…`, `aReassignCommitted…`, `aPrescriptionQueuedBehindARemoveIsKept` |
| G10 | Nghiệp vụ lân cận: hoàn tất chỉ tính thuốc còn lại; thuốc đã xóa không bị thu tiền hay trừ kho; dòng VACCINE, mũi, lô không đổi | `removeThenCompleteChargesOnlyTheRemainingLines`, `aRemovedDrugIsNeitherChargedNorIssued`, `removeLeavesTheVaccineLineAndItsLotAlone` |
| G11 | Thuộc tính transaction | `PrescriptionRemoveServiceTest` (reflection) |

## Hệ quả

- **#12 / #13** không còn dòng đã xóa (đọc lại từ DB mỗi lần).
- **Hoàn tất #5, thu tiền #8** không đổi code: thuốc đã xóa không còn trong Order nên không bị tính tiền hay xuất kho.
- **Không đảm bảo:**
  - dòng đã `PENDING` không xóa được (lượt đã `COMPLETED`; sai sót ghi bằng bản bổ sung);
  - DB chưa chặn xóa dòng đơn của bệnh án đã khóa (D043); ứng dụng giữ bằng guard `IN_PROGRESS`.
- **D043:** trigger chặn sửa bệnh án sau này phải cho phép DELETE `prescription_items` khi lượt `IN_PROGRESS` (đã ghi vào D043).
- **T38 DONE.** FE nối ở T46 (FE chưa gọi #17).

## Đã cân nhắc

- **Xóa mềm / cờ "đã hủy":** bảng không có cột trạng thái, cần migration (V22 chưa thống nhất với BE-2); đơn thuốc không có nghiệp vụ nào cần giữ dòng đã hủy.
- **Xóa bệnh án rỗng khi hết dòng:** thêm một trường hợp xóa cứng mới và mở lại việc đổi loài; không rule nào yêu cầu.
- **Dòng mua ngoài trả 400:** thêm mã lỗi không có trong contract; xóa dòng mua ngoài vô hại.
- **Xóa dòng đơn qua entity (`items.delete`) rồi để flush:** thứ tự DELETE so với `order_lines` trong cùng lần flush của sales không được đảm bảo; dùng bulk DELETE chạy ngay.
- **Gỡ `order_line_id` rồi để sales xóa dòng (như `markExternalAtPayment`):** thừa một câu UPDATE; xóa dòng đơn trước là đủ.
