# ADR-0090: Báo cáo #5 tái chủng — thêm trường minh bạch `pendingCount` (G11), làm rõ chữ 403 phạm vi của `report`

- **Trạng thái:** **Proposed (chờ BE-2 / BE-1 xác nhận; công thức mẫu số vẫn là câu hỏi PO của ADR-0087 G11)**
- **Ngày:** 2026-11-01
- **Liên quan:**
  - ADR-0087 (G4 thứ tự lỗi, G9 lượt dịch vụ, **G11 câu hỏi PO mở**); 02 BR-BC-03, BR-BC-04; report-v1 #3, #5; `docs/06-module-contracts.md` §4 (hàng báo cáo) và §6.
  - Quy ước 403 phạm vi: `docs/api/00-method.md:65` ("message chung, không lộ cấu trúc phân quyền"), ADR-0029:38, `docs/convention/backend/04-exception-handling.md:14`.
  - Ghi chú kiểm thử: `docs/testcases/T49-fe.md` C.11 (K11–K14), `docs/testcases/test-tay-ghep-api-theo-cum.md` L-16…L-19 (cụm 9, 1/11/2026).
- **Triển khai:**
  - Người viết: FE-1 (Hiến); **người dùng cho phép sửa BE module `report` và hai file `visit` của BE-1** cho việc này (1/11/2026). BE-1 và BE-2 chưa xem.
  - Sửa (visit, của BE-1; do BE-2 cài thay theo ADR-0087 G1): `visit/api/VaccinationReportApi` (record `ReturnRateRow` thêm `pendingCount`; chữ ký thêm tham số `today`), `visit/repository/VaccinationReportRepository` (cột `pending`, tham số `:today`), `visit/service/VaccinationReportService` (truyền `today`).
  - Sửa (report): `report/service/ReportService` (tính `today` từ `Clock` giờ VN, ánh xạ trường), `report/dto/ReturnRateRow` (thêm `pendingCount`), `report/controller/ReportController` (7 `@ApiErrorDoc(status = 403)` bỏ `description`, dùng `$ref Forbidden`).
  - Hợp đồng: `docs/api/generator/m_report.py` (schema `ReturnRateRow`, bỏ override 403, giả định A4/A5, câu hỏi mở G11) → `report-v1.md`, `openapi/report-v1.yaml` sinh lại bằng `generate.py report`.
  - Test: `VaccinationReportIT` (đổi chữ ký + 4 ca `pendingCount`), `ReportServiceTest` (`today` và `pendingCount`), `SalesReportIT` (biên 23:59/00:00 của #2 `cancelled_at` và #3 `pending_at`, hai chi nhánh cho #2 và #3), `StockReportIT` (`expiringLots` hai chi nhánh).
  - FE: `shared/types/report.ts`, `shared/api/mock/report.mock.ts` (+ test), `pages/admin/reports/{RevaccinationTab,ServicesTab,RevenueTab}.tsx` (+ `AdminReportsPage*.test.tsx`).

## Bối cảnh

ADR-0087 G11 ghi: mẫu số báo cáo #5 gồm mọi mũi có `next_due_date` trong kỳ (BR-BC-04), kể cả mũi **chưa tới** `next_due_date + grace` ([CFG] `report.revaccination_grace_days`, 7 ngày), nên tỷ lệ của kỳ gần đây thấp giả; đây là **câu hỏi mở cho PO**, chưa có trả lời. Đo trên DB demo 1/11/2026: ba mũi tiêm có hạn tái chủng 20/11/2026 (hết grace 27/11) vẫn hiện "5 bệnh 0 / 1 · 0,0%" và "4 bệnh 0 / 2 · 0,0%", dù khách còn gần ba tuần để quay lại: người xem đọc thành "không ai quay lại".

Cùng đợt đối chiếu, hai điểm khác bị nhìn nhầm là lỗi BE nhưng thật ra là quyết định đã có:
- 403 phạm vi của `report` trả `ACCESS_DENIED_SCOPE_MISMATCH` kèm message chung (không có mã rule), theo quy ước toàn hệ thống; chỉ chữ trong hợp đồng ghi "403 `BR-BC-01`" dễ bị hiểu là message có mã rule (và mock cũ của FE ghi sai như vậy).
- Báo cáo #3 đếm cả Order chưa thu hay bị hủy vì không thanh toán là **có chủ ý** (ADR-0087 G9, `docs/06-module-contracts.md` §4: "dịch vụ đã làm"); chỉ nhãn "Lượt dịch vụ hoàn tất" và thiếu mô tả khiến người dùng so sánh với doanh thu và thấy lệch (7 lượt so với 5 Order đã thu, vì có 2 Order PENDING).

## Quyết định

### 1. Giữ nguyên công thức G11, chỉ thêm một trường minh bạch

`dueCount`, `returnedCount`, `rate` và BR-BC-04 **không đổi**. Mỗi dòng #5 thêm `pendingCount` (số nguyên ≥ 0):

> `pendingCount` = số mũi **trong mẫu số**, **chưa quay lại** (không tồn tại mũi khác cùng thú + cùng `vaccine_type_id` thỏa điều kiện tử số của G11) và `next_due_date + graceDays >= today`.

- Bao gồm cả ngày cuối của grace (`today = next_due_date + grace` vẫn còn pending); mũi chưa tới `next_due_date` cũng tính (còn chờ).
- `today` là **ngày giờ VN** lấy từ `Clock` của `ReportService` rồi truyền xuống `VaccinationReportApi.revaccinationReturn(branchId, from, to, today)`. **Không** dùng `CURRENT_DATE`/`now()` trong SQL: múi giờ phiên DB có thể là UTC, lệch ngày trong khung 00:00–07:00 giờ VN (cùng cách `StockReportService`).
- Bất biến: `0 ≤ pendingCount ≤ dueCount − returnedCount`.

### 2. Tương thích ngược

Trường thêm vào, không đổi trường cũ: FE cũ bỏ qua; FE mới xử lý thiếu trường (BE cũ) bằng "không hiện gì". Hợp đồng sinh lại, `OpenApiContractParityIT` (so cây thuộc tính) xanh.

### 3. FE chỉ trình bày, không tự tính lại

Mỗi dòng có `pendingCount > 0` hiện thêm "còn N mũi đang trong thời gian chờ quay lại" dưới số `đã quay lại / đến hạn`, kèm một chú thích đầu khối "Mũi chưa hết thời gian chờ quay lại vẫn nằm ở mẫu số nên tỷ lệ kỳ gần đây thấp hơn thực tế". Mock FE tính `pendingCount` bằng cùng định nghĩa (grace 7 ngày, mốc hôm nay giờ VN).

### 4. Hai điểm "chỉ làm rõ, không đổi hành vi"

- **403 phạm vi (L-16):** BE giữ message chung. Hợp đồng bỏ override "`BR-BC-01` chi nhánh ngoài phạm vi" và dùng câu mặc định dùng chung của `docs/api/generator/lib.py` ("ACCESS_DENIED: sai vai trò · ACCESS_DENIED_SCOPE_MISMATCH: ngoài phạm vi … Message chung"); ghi chú D.1 nói rõ rule BR-BC-01 đứng sau 403 nhưng message không kèm mã. 7 `@ApiErrorDoc(status = 403)` bỏ `description` để khớp `$ref` của yaml (`OpenApiContractParityIT` so `$ref` khi hợp đồng là `$ref`, như 115 chỗ khác trong repo).
- **#3 (G9):** BE không đổi. Hợp đồng thêm giả định A4 (định nghĩa: số **dòng** `SERVICE`, không cộng `quantity`, của Order VISIT có `pending_at` trong kỳ, mọi trạng thái Order kể cả hủy vì không thanh toán). FE thêm mô tả một câu dưới tiêu đề.

## Phương án đã loại

- **Loại mũi chưa hết grace khỏi mẫu số** (đổi BR-BC-04): thay đổi quy tắc nghiệp vụ cần PO trả lời G11 trước; trên DB demo sẽ làm tab Tái chủng rỗng hoàn toàn (cả 3 mũi còn trong grace).
- **Trả thêm "tỷ lệ lạc quan" `(returned + pending) / due` ở BE:** đặt sẵn một cách đọc số mà PO chưa chọn; FE vẫn tính được từ `dueCount`, `returnedCount`, `pendingCount` nếu PO muốn.
- **FE tự suy "kỳ gần đây" theo ngày:** không biết `report.revaccination_grace_days` đang cấu hình bao nhiêu (0–30), sai khi ADMIN đổi.
- **Sửa BE để 403 kèm mã rule** (lớp con kiểu `OrderLineAccessDeniedException`): phá quy ước chung của 11 module dùng `BranchScope`; không có tiêu chí §4.2 của `04-exception-handling.md` thỏa.

## Hệ quả

- `VaccinationReportApi` (module BE-1) đổi chữ ký và record: mọi chỗ gọi và test phải cập nhật (chỉ `ReportService` gọi); `VaccinationReportIT` đổi 7 chỗ gọi.
- Khi PO trả lời G11, ADR mới `Supersedes` G11 của ADR-0087 và ADR này; `pendingCount` có thể giữ như trường phụ hoặc bỏ.
- Tab Tái chủng vẫn hiện `rate` thấp cho kỳ gần đây, nhưng nay có chú thích và số mũi còn chờ.

## Chưa làm / câu hỏi mở

- **G11 (PO):** mẫu số có nên loại mũi chưa hết grace không; nếu có, `rate` của kỳ chứa ngày hôm nay phải hiểu thế nào.
- **G9 (PO, nhẹ):** có muốn nhãn "Lượt dịch vụ hoàn tất" đổi thành tên nói rõ "lượt phát sinh, chưa nhất thiết đã thu tiền" không.
