# ADR-0124: Nội trú — cách tính ngày và tiền, chuồng, y lệnh, theo dõi, người phụ trách

- **Trạng thái:** Accepted (Mạnh quyết ngày 2026-11-09 trong phiên đặc tả; BE-2 xác nhận bằng lời qua Mạnh cùng ngày ba điểm: `addServiceLine` có `unitPrice` và `actorId`, giá trị `INPATIENT`, guard boarding cho cả `PET_DECEASED`; chưa có văn bản của Dũng)
- **Ngày:** 2026-11-09
- **Liên quan:** `docs/09` §C (BR-LT-13…17, FSM #24 và #25), `docs/api/inpatient-v1`, `docs/06` §6b dòng 6, ADR-0113 (schema V38), ADR-0125 (bỏ phẫu thuật), ADR-0122 (mẫu), BR-BH-03 (snapshot giá), BR-LT-09 / BR-LT-12 (trả thú, thú mất), BR-KH-05, BR-TN-08

## Bối cảnh

Bản T54 của nội trú (`docs/09` §C) có bốn chỗ hở khi rà lại cho T69, và một phần tên gọi không còn đúng sau khi bỏ phẫu thuật (ADR-0125):

- "Số ngày" nội trú không nói có cộng ngày vào viện hay không.
- Thú nhập viện từ lưu trú bị tính hai khoản (đêm lưu trú và ngày nội trú) mà chưa nói thẳng.
- `VisitOrderApi.addServiceLine(visitId, serviceId, quantity)` không có đơn giá, nên không thể giữ "giá chốt lúc nhập viện" (`admissions.unit_price`, BR-BH-03). Interface này chưa có trong `sales/api`.
- Lỗi 400 của xuất viện "`DECEASED` mà thú chưa đánh dấu đã mất" không thể xảy ra: BR-KH-05 cấm đánh dấu mất khi còn Visit mở, mà Visit còn mở lúc xuất viện.
- petcare.vn (bệnh viện thú y tham chiếu) không công bố giá, quy trình hay cách tính nội trú; trang Lưu chuồng chỉ nói có bác sĩ khám hằng ngày và camera. Không có mô hình thật để sao chép, nên các điểm dưới đây là lựa chọn của đồ án.

## Quyết định

1. **Số ngày = ngày xuất − ngày nhập + 1**, theo ngày lịch giờ Việt Nam (`Clock`), gồm cả hai đầu. Cùng ngày = 1; nhập T2 xuất T3 = 2; nhập 23:50 xuất 00:10 hôm sau = 2 (điểm bất lợi chấp nhận). Ngưỡng tối thiểu 1 là hệ quả, không còn là rule riêng.
2. **Giá chốt lúc nhập viện, hai khoản tách riêng.** `admissions.unit_price` lấy từ dịch vụ `INPATIENT` lúc nhập viện. Phí lưu trú theo đêm (đặt chỗ) và phí nội trú theo ngày tính **riêng** (giữ giả định A3), nên thú nhập viện từ lưu trú bị tính cả hai cho những ngày nằm điều trị. Đây là chấp nhận có chủ đích; hướng sửa ở mục "Hướng phát triển".
3. **Một dòng Order lúc xuất viện, kèm tiền tạm tính.** Nhập viện không thêm dòng. Xuất viện (một transaction) thêm đúng một dòng dịch vụ nội trú: số lượng = số ngày, đơn giá = `unit_price`. Trong lúc `ADMITTED`, API đọc trả `billedDays` (đến hôm nay) và `estimatedAmount` = `billedDays × unitPrice`: hai trường **tính toán, không lưu**; sau xuất viện tính đến ngày xuất. Không có job thêm dòng mỗi ngày.
4. **Đơn giá đi cùng dòng.** `VisitOrderApi.addServiceLine(visitId, serviceId, quantity, unitPrice, actorId)` (theo khuôn `addDrugLine`): BE-2 không tự tra giá hiện hành mà dùng `unitPrice` được truyền. Người gọi nội trú là VET phụ trách Visit.
5. **Chuồng.** Nhập viện **từ lượt khám**: VET chọn một chuồng `AVAILABLE` bất kỳ của chi nhánh (`KennelApi.occupyForTreatment`), `admissions.kennel_id` có giá trị, xuất viện giải phóng (`release`). Nhập viện **từ lưu trú**: không gửi `kennelId`, thú ở nguyên chuồng của đặt chỗ, `kennel_id` để trống, không gọi `occupy` / `release`. Không kiểm sức chứa đặt chỗ (BR-LT-03) và không kiểm loài / cân nặng của chuồng điều trị (VET chịu trách nhiệm). Nếu một đặt chỗ `BOOKED` đến ngày nhận mà hết chuồng đúng loại vì có chuồng đang điều trị, áp dụng BR-LT-08 sẵn có (từ chối nhận, hủy như phòng khám hủy, báo BRANCH_MANAGER). **Không có cảnh báo tự động** ở GĐ2.
6. **Một y lệnh = một lần thực hiện.** Đợt thuốc nhiều lần là nhiều y lệnh; nhiều y lệnh có thể trỏ cùng một dòng đơn thuốc (V38 không có `UNIQUE` trên `prescription_item_id`). Hệ thống không đối chiếu số lần thực hiện với số lượng đã kê; kho vẫn trừ theo Order lúc `PAID` (BR-BH-04). Đóng Q3 của `inpatient-v1`.
7. **Theo dõi nội trú, không còn "hậu phẫu".** `admission_observations` và hai endpoint giữ nguyên, là nhật ký chỉ số chung của ca nội trú (nhiệt độ, nhịp tim, nhịp thở, cân nặng, ăn uống, cờ bất thường). BR-LT-16 đổi tên, bỏ chữ "hậu phẫu".
8. **Người phụ trách ca = người phụ trách Visit.** Không lưu bác sĩ riêng cho ca nội trú. Nhập viện, ra / hủy y lệnh, xuất viện là việc của VET đang được gán Visit (như BR-KB-01); khi Visit được gán lại (BR-TN-08), quyền sang người mới. `admitted_by`, `discharged_by` chỉ là lịch sử. Nhập viện từ lưu trú tạo Visit điều trị gán đúng VET đó và **không có dòng dịch vụ tự sinh** (dòng nội trú chỉ có lúc xuất viện).
9. **Hoàn tất Visit.** Lượt không hoàn tất được khi còn ca `ADMITTED` (BR-LT-14). Lượt mà dịch vụ duy nhất là `INPATIENT` (nhập từ lưu trú) không cần chẩn đoán hay mũi tiêm của BR-KB-02: tóm tắt xuất viện thay chẩn đoán. Lượt nhập từ khám vẫn theo BR-KB-02 cho dịch vụ khám đã có.
10. **Thú mất khi nằm viện: bốn bước theo thứ tự.** (1) VET xuất viện `DECEASED` (vẫn thêm dòng tính tiền đến ngày xuất); (2) VET hoàn tất Visit; (3) nếu nhập từ lưu trú, lễ tân kết thúc lưu trú `PET_DECEASED` (BR-LT-12; Order `BOARDING` sang `PENDING`); (4) lễ tân đánh dấu thú đã mất (BR-KH-05, hai bước). Xuất viện **không** tự đánh dấu mất hay tự kết thúc lưu trú. Gỡ lỗi 400 "chưa đánh dấu đã mất" khỏi hợp đồng.
11. **Guard ở boarding rộng hơn.** `AdmissionQueryApi.hasActiveAdmission(petId)` chặn **mọi** kết thúc lưu trú khi còn ca `ADMITTED`, cả `RETURNED` lẫn `PET_DECEASED` (409 `BR-LT-17`), không chỉ trả thú.
12. **Trạng thái và mã viết bằng tiếng Anh.** Spec nội trú ghi `ADMITTED`, `DISCHARGED`, `ORDERED`, `DONE`, `CANCELLED`, `HOME`, `DECEASED`; kết thúc lưu trú ghi `RETURNED` / `PET_DECEASED` (mã ở `05`), không dùng `TRẢ_THÚ`, `THÚ_MẤT`. Không đổi schema hay mã trong code (đã là tiếng Anh).

## Hệ quả

| Phần | Thay đổi |
|---|---|
| `docs/09` §C, `inpatient-v1` | Sửa BR-LT-13…17, thêm `billedDays`, `estimatedAmount` vào `Admission` / `AdmissionSummary`, gỡ lỗi 400 `DECEASED`, `kennelId` vắng ⇔ nhập từ lưu trú, sinh lại contract (10 endpoint, không đổi số) |
| `docs/06` §6b dòng 6 | Chữ ký có `unitPrice`, `actorId`; bên gọi chỉ còn `inpatient`; hạn giao dời từ 26/11 (T61) sang 30/11 (T69). BE-2 đã xác nhận bằng lời qua Mạnh (chưa có văn bản; ghi ở `06` vì BE-2 không thấy `docs/dept`) |
| Schema | Không migration: `unit_price`, `kennel_id` nullable, `order_line_id` đã có ở V38. Dịch vụ `INPATIENT` do SUPER_MANAGER tạo qua UC30, không seed |
| T69 (`visit`) | `VisitCompletionService` thêm guard "còn ca `ADMITTED`" và ngoại lệ "chỉ dịch vụ `INPATIENT`" (BR-LT-14); `VisitApi.startTreatmentVisit` mở Order không dòng tự sinh |
| FE | Màn chi tiết ca hiện số ngày và tiền tạm tính; đổi chữ "hậu phẫu" thành "theo dõi nội trú" (D064) |
| Test cần có | Ca biên số ngày (cùng ngày, qua nửa đêm, qua nhiều ngày); xuất viện thêm đúng một dòng với `unit_price` dù giá dịch vụ đã đổi sau khi nhập; hai xuất viện đồng thời chỉ một thành công; guard boarding chặn cả hai kiểu kết thúc; hoàn tất Visit còn `ADMITTED` bị từ chối; chuỗi bốn bước thú mất |

## Phương án đã loại

- **Số ngày theo đêm cắt mốc 12:00 (như lưu trú) hoặc khối 24 giờ làm tròn lên:** thêm quy tắc giờ, dễ lẫn với phí lưu trú, khó test.
- **Job thêm một dòng mỗi ngày:** phải xử lý nhập / xuất giữa ngày, quyền thêm dòng (BR-BH-02) và khóa Order hằng đêm; phần "thấy tiền chạy" đã có nhờ `estimatedAmount`.
- **Bỏ `unit_price`, lấy giá hiện hành lúc xuất viện:** trái "giá chốt lúc nhập viện" và BR-BH-03.
- **Thêm dòng số lượng 1 lúc nhập viện rồi sửa số lượng:** phải mở khóa sửa dòng Order.
- **Trừ đêm lưu trú trùng ngày nội trú ngay bây giờ:** cần `boarding` biết các ngày nội trú, là thay đổi chéo module của BE-2 và dễ lệch số; để ở hướng phát triển.
- **Chuồng nội trú tách riêng:** không tranh chuồng, nhưng thêm cấu hình và đụng module BE-2.
- **Y lệnh có chu kỳ lặp:** thêm trường tần suất, số ngày và cách sinh lần thực hiện; không cần cho phạm vi đồ án.
- **Bỏ bảng `admission_observations` (dùng y lệnh `MONITORING` + ghi chú):** mất chỉ số có cấu trúc và cờ bất thường báo khách; cần migration xóa bảng và sinh lại contract.

## Hướng phát triển (ngoài phạm vi đồ án)

1. **Không tính chồng phí.** `boarding` nhận danh sách ngày nội trú của đặt chỗ (qua `AdmissionQueryApi`) và trừ các đêm trùng khi tính Order lưu trú (BR-LT-09); hoặc dịch vụ nội trú ghi rõ đã gồm phí chuồng. Cần BE-2 đồng ý và quy tắc làm tròn khi nhập / xuất giữa ngày.
2. **Chuồng nội trú tách riêng** (loại chuồng hoặc cờ ở `kennels`) kèm cảnh báo khi chuồng điều trị làm một đặt chỗ `BOOKED` sắp tới có nguy cơ hết chỗ.
3. **Tính tiền rõ ràng cho nằm lâu:** dòng Order theo ngày (job), tạm ứng và thanh toán một phần (cần sửa BR-TG-02); báo cáo thất thu nội trú.
4. **Y lệnh có chu kỳ**, nhắc việc cho CARETAKER, ngưỡng chỉ số tự đánh dấu bất thường.
5. **Khách xem tình trạng nội trú** (một màn hình đọc; đã loại khỏi GĐ2 ở T54 Q4).
6. **Đổi dịch vụ nội trú giữa chừng** (ví dụ chăm sóc đặc biệt sang thường) và nhiều bậc giá trong một ca.
7. **Kiểm loài / cân nặng của chuồng điều trị** và lịch thay ca của người phụ trách.

## Nợ ghi nhận

Chưa có Java cho nội trú; FE đã có trang và mock theo hợp đồng cũ (thiếu `billedDays`, `estimatedAmount`, còn nhãn "hậu phẫu"): D064.
