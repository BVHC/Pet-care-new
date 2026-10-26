# ADR-0076: Nền đơn thuốc (`PrescriptionItem`) và `PrescriptionApi` thật — đơn thuốc trong bệnh án #12/#13, chuyển mua ngoài lúc thu tiền

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-26
- **Liên quan:**
  - BR-KB-03, BR-KB-01, BR-BH-04, BR-BH-02, BR-SP-01; visit-v1 #12, #13, schema `PrescriptionItem`; sales-v1 #11, A10.
  - ADR-0066 (nền bệnh án), 0072 (khuôn reader), 0044 (khóa Order), 0064 (thu tiền); `06-module-contracts` §5, §6.
  - Nợ: D043 (index `prescription_items.medical_record_id`, CHECK lý do mua ngoài), D045.
- **Triển khai (T38 phần 0):** `PrescriptionItem`, `PrescriptionItemRepository`, `PrescriptionApiService` (bean của `PrescriptionApi`), `PrescriptionItemReader`, `MedicalRecordMapper.toPrescriptionItem` (+ tham số `prescriptions` của `toResponse`), `MedicalRecordService` #12/#13. Không endpoint mới, không migration. Test: `PrescriptionApiServiceTest`, `PrescriptionItemReaderTest`, `PrescriptionApiIT` (7), `PrescriptionItemConstraintIT` (9), `PrescriptionReadCountIT` (3), `MedicalRecordIT` (+6), `MedicalRecordPersistenceIT` (+1), `MedicalRecordServiceTest` (+6), `MedicalRecordMapperTest` (+2).
- **Chưa làm (T38 phần 1):** `POST/DELETE /api/visits/{id}/prescriptions[/{itemId}]` (#16, #17), factory `PrescriptionItem.prescribe`, dòng Order `DRUG` qua `VisitOrderApi.addDrugLine`.

## Quyết định

1. **`markExternalPurchaseAtPayment(orderLineId, actorId)` là một câu `UPDATE` duy nhất** (`PrescriptionItemRepository.markExternalAtPayment`): đặt `is_external_purchase = true`, `external_reason = OUT_OF_STOCK_AT_PAYMENT`, `order_line_id = NULL`, `updated_at` từ `Clock` (ADR-0015) trong cùng câu, nên CHECK "không tách dòng" không bao giờ bị vi phạm giữa chừng. Câu đi qua unique index của `order_line_id` và tự khóa dòng; không đọc trước. Thuốc, số lượng, liều **không đổi** và dòng vẫn in trên đơn (BR-KB-03).
2. **Trả về khác 1 dòng → `IllegalStateException`** (rollback cả transaction của sales). Không có dòng đơn thuốc nào trỏ tới `orderLineId` (dòng bán lẻ, tồn vẫn đủ, gọi lần hai, id lạ) là lỗi của bên gọi: sales tự chặn bằng 400 trước khi gọi. Chữ ký `void` giữ nguyên. `actorId` chỉ kiểm khác null (bảng không có cột người sửa; sales ghi audit).
3. **`MANDATORY`, không `catch`, không audit ở visit.** Đi theo transaction của sales: sales thất bại sau khi gọi thì dòng đơn thuốc về nguyên trạng.
4. **Khóa:** `orders` (sales đã khóa) → `prescription_items` (chính câu UPDATE). **Không khóa `visits`**: thứ tự của visit là `visits → orders`, khóa `visits` từ sales sẽ đảo chiều; Order `PENDING` nghĩa là lượt đã `COMPLETED`, không còn luồng visit nào ghi dòng này. Không chạm `SalesOrder` nên không dính D045.
5. **Bệnh án đã khóa là ngoại lệ có chủ đích (C8):** BR-KB-01 khóa nội dung bệnh án khi `COMPLETED`, nhưng BR-BH-04 cho phép rõ chuyển dòng đơn thuốc sang mua ngoài lúc thu. Phần 0 chỉ có đúng một đường UPDATE này. Trigger chặn sửa bệnh án (D043) sau này **phải** cho phép chuyển đổi này.
6. **#12/#13 trả đơn thuốc thật** (`prescriptions`): đọc `prescription_items` theo `medical_record_id` (= id lượt) `ORDER BY id`, gồm dòng mua ngoài; tên thuốc tra một lần cho cả lô qua `CatalogQueryApi.findProducts`, **không lọc `active`** (thuốc đã ngừng kinh doanh vẫn có tên trên đơn cũ), là tên hiện tại. Thiếu tên là dữ liệu hỏng (500). #12 chỉ đọc khi đã có dòng bệnh án; #13 đọc sau mọi guard và trước mọi lệnh ghi (lỗi đọc thì chưa ghi gì, khuôn ADR-0072 §4) và chỉ khi bệnh án đã tồn tại (lần ghi đầu chưa thể có đơn thuốc: FK). Response của PUT khớp GET.
7. **Không phân trang:** `prescriptions` là mảng nằm trong `MedicalRecord` (contract), như mũi tiêm và bản bổ sung; tài liệu không có [CFG] giới hạn số dòng, không tự đặt giới hạn.
8. **Không N+1:** entity chỉ giữ id, không `@ManyToOne`. Đọc đơn thuốc luôn **2 câu** (dòng đơn thuốc, tên thuốc) với 1 hay 20 dòng; **1 câu** (không chạm catalog) khi chưa có đơn.
9. **Entity chỉ đọc:** mọi cột `updatable = false` (contract không có sửa dòng; ba cột mua ngoài chỉ đổi bằng bulk UPDATE ở mục 1). Chưa có factory; bên ghi đến ở phần 1.
10. **Không migration:** D043 giữ nguyên (index `medical_record_id` quét bảng, chấp nhận ở giai đoạn 1); thêm vào D043 khoảng trống C3 dưới đây.

## Ràng buộc của `PrescriptionItem` (ai giữ)

| # | Ràng buộc | Nguồn | DB giữ? | Ứng dụng giữ |
|---|---|---|---|---|
| C1 | `quantity > 0`, liều NN ≤ 500 | erd §6 | ✅ CHECK / NN / `VARCHAR(500)` | Bean Validation của #16 (phần 1) |
| C2 | Không tách dòng: mua ngoài ⇔ không có dòng Order | BR-KB-03 | ✅ `ck_prescription_items_external_xor_line` | UPDATE đặt hai cột cùng câu (phần 0) |
| C3 | Mua ngoài ⇔ có lý do (`AT_PRESCRIBE` lúc kê, `AT_PAYMENT` lúc thu) | BR-KB-03, BR-BH-04 | ❌ CHECK chỉ giới hạn giá trị, NULL vẫn lọt | UPDATE đặt cả 3 cột (phần 0); factory (phần 1). **D043:** thêm `CHECK (is_external_purchase = (external_reason IS NOT NULL))` ở V22 |
| C4 | Một dòng Order ↔ tối đa một dòng đơn thuốc | erd §6, §8 | ✅ UQ `order_line_id` | — |
| C5 | Sản phẩm là thuốc kê đơn | BR-SP-01 | ❌ chỉ FK | #16 qua catalog → 400 (phần 1) |
| C6 | Dòng Order là `DRUG` cùng thuốc, số lượng, thuộc Order của lượt | BR-KB-03, BR-BH-02 | ❌ | `addDrugLine` → `LineRef` (phần 1); sales chỉ gọi API này cho dòng `DRUG` của Order `VISIT` (phần 0) |
| C7 | Nội dung dòng không đổi; chuyển mua ngoài giữ số lượng | BR-KB-03, BR-BH-04 | ❌ | `updatable = false`; câu UPDATE không chạm các cột đó |
| C8 | Bệnh án khóa thì đơn thuốc không đổi, **trừ** chuyển mua ngoài lúc thu | BR-KB-01, BR-BH-04 | ❌ | Chỉ một đường UPDATE (mục 5) |
| C9 | Mỗi dòng chuyển được một lần | BR-BH-04 | — | `WHERE order_line_id = :id`: dòng mua ngoài có `order_line_id` NULL → 0 dòng → `IllegalStateException` |
| C10 | Đơn thuốc chỉ thuộc bệnh án | 04 §6 | ✅ FK `medical_record_id` | #12 vẫn 404 với lượt `GROOMING` |
| C11 | `updated_at` từ `Clock` | ADR-0015 | — | Câu UPDATE tự đặt `:now` |

## Bảng đảm bảo

| # | Đảm bảo | Ai giữ | Test |
|---|---|---|---|
| G1 | Đổi đúng 3 cột + `updated_at` = clock; gỡ `order_line_id` nên sales xóa được dòng Order (FK `RESTRICT`) | câu UPDATE | `PrescriptionApiIT` `movesTheLine…` |
| G2 | Không phải dòng đơn thuốc / gọi lần hai → lỗi, không đổi dòng nào (`xmin`) | count `!= 1` | `PrescriptionApiIT` `aLineThatIsNotAPrescriptionLine…`, `callingTwice…` |
| G3 | Ngoài transaction → lỗi, không ghi; rollback theo bên gọi | `MANDATORY` | `PrescriptionApiIT` `outsideATransaction…`, `itRollsBack…`; `PrescriptionApiServiceTest` (annotation) |
| G4 | Null chặn trước khi chạm DB; `now` từ clock | service | `PrescriptionApiServiceTest` |
| G5 | #12 trả đơn thuốc của chính bệnh án theo `id`, có tên (kể cả thuốc ngừng kinh doanh), mua ngoài có lý do và `orderLineId` null | reader + mapper | `PrescriptionItemReaderTest`, `MedicalRecordMapperTest`, `MedicalRecordIT` `getReturnsThePrescription…`, `getKeepsThePrescriptionsOfDifferentVisitsApart` |
| G6 | #13 trả đơn thuốc như GET; đọc sau guard, trước ghi; lỗi đọc không ghi / audit | service | `MedicalRecordServiceTest` (`InOrder`), `MedicalRecordIT` `putReturnsThePrescription…`, `aFailureAfterThePrescriptionRead…` |
| G7 | Entity khớp schema (`ddl-auto=validate`), đọc theo thứ tự kê và theo bệnh án | entity | `MedicalRecordPersistenceIT` |
| G8 | Ràng buộc DB C1, C2, C3 (giá trị), C4 còn hiệu lực; **ghi lại khoảng trống C3** | V1 | `PrescriptionItemConstraintIT` (ca `knownGapD043…` phải đỏ khi V22 thêm CHECK) |
| G9 | Chuyển mua ngoài không đổi nội dung, chạy trên lượt `COMPLETED` đã khóa, không chạm dòng khác | câu UPDATE | `PrescriptionApiIT` `worksOnALockedRecord…`, `onlyTheMatchingItem…` |
| G10 | Không N+1: 2 câu với 1 và 20 dòng, 1 câu khi rỗng | reader | `PrescriptionReadCountIT`; `PrescriptionItemReaderTest` (`times(1)`, id khử trùng) |
| G11 | Đơn thuốc không lọt cho vai trò ngoài hợp đồng / chi nhánh khác | `@PreAuthorize` + `BranchScope` sẵn có | `MedicalRecordIT` `prescriptionDataNeverReaches…` |

## Hệ quả

- **Hành vi hiện có không đổi:** không luồng nào chèn `prescription_items` ở phần 0, bảng rỗng trên mọi DB thật, #12/#13 vẫn trả `prescriptions: []` cho tới phần 1; `markExternalPurchaseAtPayment` chưa có bên gọi cho tới khi BE-2 cài sales #11.
- **BE-2 làm được sales #11** (`move-to-external-purchase`, sales-v1 A10): nghĩa vụ ở `06-module-contracts` §6 và javadoc `PrescriptionApi`.
- **Cleanup của IT:** `AppointmentItSupport.cleanUp` và các cleanup khác xóa `medical_records` / `order_lines` / `products` mà không xóa `prescription_items`; IT nào chèn đơn thuốc phải tự xóa trước (`VaccinationItSupport.deleteDoses`).
- **Phần 1 phải:** gỡ `order_line_id` trước khi xóa dòng `DRUG` (FK `RESTRICT`: `OrderLineService`, `removeDrugLine` và cleanup test); giữ C3, C5, C6; chốt xóa cứng của #17 — erd §0 chưa liệt kê BR-KB-03 trong danh sách xóa cứng được phép, cần thêm theo tiền lệ BR-TK-18 (erd §13 mục 16) hoặc hỏi PO.

## Đã cân nhắc

- **Trả `boolean` thay vì ném lỗi** — đổi chữ ký đã công bố cho BE-2 mà sales chưa gọi; chọn giữ `void` và ghi nghĩa vụ.
- **Khóa `visits` trong `markExternalPurchaseAtPayment`** — đảo thứ tự `visits → orders` của visit, nguy cơ deadlock; không cần vì Order `PENDING` ⇒ lượt đã đóng.
- **Đọc dòng trước rồi UPDATE** — thêm một câu và một khoảng hở giữa đọc và ghi; một câu UPDATE có điều kiện là đủ.
- **V22 ngay (index + CHECK + trigger)** — cần chốt số V với BE-2; để D043.
