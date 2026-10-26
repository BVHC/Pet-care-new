# ADR-0077: Kê đơn một dòng thuốc — visit-v1 #16

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-26
- **Liên quan:**
  - BR-KB-03, BR-SP-01, BR-BH-02, BR-BH-04, BR-KO-01, BR-KH-03, BR-QT-15; visit-v1 #16, A5, A26–A29.
  - ADR-0044 (khóa Order), 0066 (bệnh án), 0074 (khuôn #19), 0076 (nền đơn thuốc, C1–C11).
  - Nợ: D043 (giữ mở), D045.
- **Triển khai (T38 phần 1):** `PrescriptionController`, `PrescriptionService.add`, `AddPrescriptionRequest`, `ShortageResolution`, factory `PrescriptionItem.prescribe` / `externalAtPrescribe`, `VisitAuditActions.PRESCRIPTION_ITEM_ADDED`. Không migration.
- **Test:** `PrescriptionServiceTest` (37), `PrescriptionItemTest` (7), `AddPrescriptionRequestValidationTest` (14), `PrescriptionControllerSecurityTest`, `PrescriptionIT` (25, era 2017).
- **Chưa làm:** #17 xóa dòng (gỡ `order_line_id` trước `removeDrugLine`; xóa cứng cần thêm BR-KB-03 vào erd §0).

## Quyết định

1. **Thứ tự** (khuôn #19, không advisory, không lô):
   - role → lượt (404) → chi nhánh (403) → `GROOMING` (404);
   - `pets FOR SHARE` → `visits` → người phụ trách (403) → `IN_PROGRESS` (409);
   - thuốc `FOR SHARE` (400) → tồn (400);
   - ghi: bệnh án rỗng nếu chưa có → `addDrugLine` (`orders`) → INSERT dòng đơn thuốc → audit.

   Lý do từng điểm:
   - Mọi guard chạy trước lệnh ghi đầu tiên.
   - Khóa thú vì tạo bệnh án làm thú bị khóa đổi loài (BR-KH-03 qua `hasMedicalHistory`).
   - Đọc thuốc `FOR SHARE` trước `orders` để thuốc ngừng kinh doanh chen giữa thành 400, không thành 500.
   - Không đọc Order phía visit (D045).
   - Không thêm cạnh khóa mới.
2. **Thuốc:** phải là `DRUG`, `prescription`, `active`; không tồn tại / sai loại / ngừng kinh doanh → 400 `BR-KB-03`, kể cả khi chọn mua ngoài (A29).
3. **Bảng quyết định** (`q` = số gửi lên, `n` = tồn khả dụng lúc ghi):

   | Điều kiện | Kết quả |
   |---|---|
   | `n ≥ q` | dòng `DRUG` số lượng `q`, bỏ qua `shortageResolution` (A26) |
   | `n < q`, không chọn | 400 `BR-KB-03` "…chỉ còn {n}…" (A5) |
   | `n < q`, `REDUCE_TO_AVAILABLE` | `n ≥ 1`: dòng `DRUG` số lượng `n`; `n = 0`: 400 `BR-KB-03` (A27) |
   | `n < q`, `EXTERNAL_PURCHASE` | mua ngoài `OUT_OF_STOCK_AT_PRESCRIBE`, giữ `q`, không dòng Order |

4. **Tồn khả dụng** = `StockQueryApi.availableQuantity` (lô chưa hết hạn của chi nhánh lượt), đọc không khóa và không trừ thuốc đã kê chưa thu (BR-KO-01, A28). Thiếu thật bị chặn lúc thu (BR-BH-04).
5. **Bệnh án rỗng** (5 trường null, `last_edited_by` null) tạo dưới khóa `visits`, không audit riêng. Bệnh án có sẵn không bị ghi lại.
6. **Audit** `PRESCRIPTION_ITEM_ADDED`:
   - `entity = prescription_items`;
   - `after = {visitId, productId, quantity, requestedQuantity, dosageInstructions, isExternalPurchase, externalReason, orderLineId}`.
   - `requestedQuantity` để truy vết khi giảm số lượng (tự đề xuất, không có trong docs).
7. **Không chống trùng:** gửi lại thì thêm một dòng nữa (A29, khuôn A23). Xóa nhầm bằng #17.
8. **D043 giữ mở:** factory đặt cả ba cột mua ngoài cùng lúc nên giữ C2 (CHECK V1) và C3 (chỉ ứng dụng). V22 (CHECK + index) chờ chốt số V với BE-2.

## Hệ quả

- **#12** trả đơn thuốc thật. Lượt mới chỉ có đơn thuốc giờ có dòng bệnh án.
- **#13:** PUT đầu tiên sau #16 có `before` = 5 trường null; PUT toàn null là no-op.
- **Đổi loài** (BR-KH-03) bị chặn sau lần kê đầu.
- **Hoàn tất #5** vẫn đòi chẩn đoán khi có dịch vụ Khám.
- **Gán lại #9** chuyển dòng `DRUG` cho VET mới (`transferLineOwnership`).
- **Thu tiền** trừ dòng `DRUG` theo FEFO.
- **Không đảm bảo:**
  - không giữ hàng (A28);
  - chưa chuyển sang mua ngoài lúc thu được cho tới khi BE-2 làm sales #11;
  - DB chưa có CHECK C3 hay index (D043).
- **Cleanup test:**
  - `AppointmentItSupport.cleanUp` xóa `prescription_items` trước `medical_records`;
  - IT kê qua API gọi `VaccinationItSupport.deletePrescriptionsOfPet` trước `deleteDoses`.

## Đã cân nhắc

- **Tôn trọng `shortageResolution` khi tồn đã đủ:** không có nguồn cho mua ngoài khi đủ tồn (BR-KB-03).
- **Tự chuyển mua ngoài khi giảm về 0:** đổi lựa chọn của VET một cách ngầm.
- **Trừ thuốc đã kê chưa thu khỏi tồn:** trái BR-KO-01, cần đọc Order (D045) hoặc API mới của BE-2.
