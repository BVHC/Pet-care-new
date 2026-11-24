# ADR-0148: Nội trú — chốt các chỗ hở của đặc tả trước T69

- **Trạng thái:** Proposed (Mạnh chốt các quyết định 1–4 trong phiên rà đặc tả 2026-11-22; phần BE-2 ở mục "Cần BE-2 xác nhận" chưa có văn bản)
- **Ngày:** 2026-11-22
- **Liên quan:** ADR-0124 (nội trú; bổ sung, không sửa), `docs/09` §C (BR-LT-13…18, FSM #24, #25), `docs/03` (Visit#7, Order#14, Chuồng#6, #7), `docs/api/inpatient-v1`, `docs/06` §6b dòng 26, V38, V41, ADR-0113, BR-LH-01, BR-TN-01, BR-CK-01, BR-KB-02, BR-LT-12

## Bối cảnh

Rà lại đặc tả nội trú (UC55, UC56) trước khi cài ở T69 thấy các chỗ hở mà ADR-0124 chưa đóng:

- `03` không có đường tạo Visit điều trị: Visit#1 chỉ tạo `WAITING` (cũng là điều `VisitTransitionHandler` đang cho phép), trong khi BR-LT-13 (b) tạo Visit thẳng `IN_PROGRESS`. Order#1 luôn kèm dòng tự sinh, Chuồng#2 chỉ do Đặt chỗ#3 kích hoạt, nên Visit điều trị, Order không dòng tự sinh và chuồng điều trị đều ngoài whitelist.
- Dịch vụ `INPATIENT` nằm trong nhóm `MEDICAL` (V1 buộc, V41 thêm giá trị), nên lọt vào đặt lịch (`AppointmentBookingService` cho mọi dịch vụ `MEDICAL`), walk-in, trang công khai và thêm dòng tay; enum Java `MedicalType` chưa có giá trị này.
- (b) chỉ nhận đặt chỗ `CHECKED_IN`, nhưng ST15 có thể chuyển đặt chỗ sang `OVERDUE` giữa lúc nằm viện; (a) không kiểm thú đang lưu trú, nên một thú có thể giữ hai chuồng.
- `CARE_LOG_ABNORMAL` bắt buộc biến `ma_dat_cho`, mà ca nhập từ khám không có đặt chỗ; người nhận cũng khác (VET phụ trách thay vì lễ tân).
- Mã ca ghi `NT-yyMMdd-xxxx`, lệch bộ sinh mã `DocumentCodeGenerator` (`PREFIX-yyMM-xxxxx`, ADR-0107).
- Trùng ca `ADMITTED` ghi 400 ở `09` nhưng 409 ở `inpatient-v1`.

## Quyết định

1. **Dịch vụ `INPATIENT` giữ làm một loại trong nhóm Khám/Tiêm (V41, không migration), thêm BR-LT-18:** chỉ dùng qua nhập viện — không đặt lịch (BR-LH-01), không chọn khi tiếp nhận walk-in (BR-TN-01), không hiện công khai (BR-CK-01), không thêm / đổi làm dòng dịch vụ tay (BR-BH-02, BR-KB-02). 400 `BR-LT-18`. Dòng nội trú vẫn chỉ sinh một lần lúc xuất viện (BR-LT-14).
2. **Thú đang lưu trú bắt buộc nhập bằng (b), và (b) nhận cả `OVERDUE`:** (b) nhận đặt chỗ `CHECKED_IN` hoặc `OVERDUE`; (a) trả 400 `BR-LT-13` khi thú có đặt chỗ `CHECKED_IN` / `OVERDUE`. Đặt chỗ giữ nguyên trạng thái của nó, ST15 vẫn chạy (sửa BR-LT-17).
3. **Giữ "người phụ trách ca = người phụ trách Visit" (ADR-0124 mục 8)** và ghi hạn chế đã biết: VET vắng mặt mà không bị khóa thì ca chờ, vì BR-TN-08 chỉ gán lại khi người phụ trách không còn `ACTIVE`.
4. **Cặp mẫu thông báo mới `INPATIENT_OBSERVATION_ABNORMAL` / `_APP`** (biến `ten_khach`, `ten_thu_cung`, `chi_nhanh`, `ma_noi_tru`, `ngay_ghi`): khách theo kênh của BR-TB-02, VET phụ trách IN_APP. Seed ở migration của T69 (số Flyway lấy lúc đó, không dùng V49). Không dùng lại `CARE_LOG_ABNORMAL`.
5. **Bổ sung `03` (theo nguồn trong bảng chuyển trạng thái):** Visit#7 `— → IN_PROGRESS` (SYS ← Nhập viện#1 đường b; không kiểm giờ mở cửa vì thú đã ở phòng khám); Order#14 `— → OPEN (VISIT)` không dòng tự sinh (SYS ← Visit#7); Chuồng#6 `AVAILABLE → OCCUPIED` và Chuồng#7 `OCCUPIED → AVAILABLE` (SYS ← Nhập viện#1, #2 đường a); Visit#5 thêm guard "còn ca `ADMITTED`" và ngoại lệ chỉ-`INPATIENT`.
6. **Trùng ca `ADMITTED` là 409** (khớp `ux_admissions_pet_admitted`), bỏ khỏi danh sách 400 của BR-LT-13.
7. **Mã ca `NT-yyMM-xxxxx`** sinh bằng `DocumentCodeGenerator` (doc type `admissions`), thay `NT-yyMMdd-xxxx`.
8. **Hạn chế đã biết (không sửa):** không hủy được ca nhập nhầm (phải xuất viện, tính ≥ 1 ngày); xóa dòng đơn thuốc đang gắn y lệnh → 409 do FK `RESTRICT`; kết cục `HOME` gồm cả "trở lại lưu trú"; BR-QT-15 chưa mở rộng cho nội trú.

## Cần BE-2 xác nhận bằng văn bản (`docs/06` §6b dòng 26)

*Cập nhật 22/11 (N0):* BE-1 đã cài các phần này trong một PR riêng cho BE-2 duyệt (duyệt PR là xác nhận bằng văn bản): `MedicalType.INPATIENT`, lọc ở `PublicCatalogService`, 400 `BR-LT-18` ở `OrderLineService`, `KennelApi` (`occupyForTreatment(kennelId, branchId)`, `release`), `BoardingQueryApi.findCurrentStay(petId)` (đổi tên từ `findCheckedInStay` vì nhận cả `OVERDUE`), `VisitOrderApi.openTreatmentOrder` và `addServiceLine`. Danh sách dưới đây là đề xuất ban đầu.

`MedicalType.INPATIENT` trong enum Java; loại `INPATIENT` khỏi `GET /public/services`; từ chối `INPATIENT` ở `POST /orders/{orderId}/lines` và `PATCH .../lines/{lineId}` (chỉ `VisitOrderApi.addServiceLine` của nội trú được thêm). Phần của BE-1: chặn ở `AppointmentBookingService` và `VisitCheckInService`. Cho tới khi BE-2 cài, không bật dịch vụ `INPATIENT` ngoài môi trường dev.

## Hệ quả

| Phần | Thay đổi |
|---|---|
| `docs/09` §C | Sửa BR-LT-13, 16, 17; thêm BR-LT-18, hàng hợp đồng chéo, "Hạn chế đã biết"; FSM #24 trỏ Visit#7, Order#14, Chuồng#6, #7; `code` theo `DocumentCodeGenerator` |
| `docs/03` | Thêm Visit#7, Order#14, Chuồng#6, #7, hai dòng ở Phụ lục; sửa Visit#5 |
| `docs/02`, `01`, `05` | Chú thích BR-LH-01, BR-TN-01, BR-CK-01, BR-KB-02; §19 `BR-LT-13…18`; UC56 actor; UC59 «extend» UC55; `services.medical_type` ghi `INPATIENT` |
| `inpatient-v1`, `catalog-v1` | Sinh lại: lỗi 400 / 409 của #1 và ghi chú #9 (`inpatient-v1`); ghi chú `GET /public/services` (`catalog-v1`, không có annotation). Không thêm endpoint, pin `OpenApiContractParityIT` không đổi |
| `appointment-v1`, `visit-v1`, `sales-v1` | **Chưa sửa ở ADR này:** `OpenApiContractParityIT` so mô tả lỗi của yaml với annotation Java (ADR-0040), nên sửa yaml mà không sửa code làm test đỏ và Swagger ghi hành vi chưa có. Sửa cùng Java ở T69: 400 `BR-LT-18` ở `POST /appointments`, `POST /visits` (walk-in), `POST /orders/{orderId}/lines`, `PATCH .../lines/{lineId}`; 409 ở `POST /visits/{visitId}/complete` khi còn ca `ADMITTED` (BR-LT-14) |
| Schema | Không migration ở ADR này; mẫu thông báo seed ở T69 |
| Test cần có (thêm vào danh sách của ADR-0124) | Nhập (a) khi thú đang lưu trú → 400; nhập (b) từ đặt chỗ `OVERDUE` thành công; hai nhập viện đồng thời chỉ một thành công (409); dịch vụ `INPATIENT` bị từ chối ở đặt lịch, walk-in, thêm dòng; Visit#7 và Order#14 qua `*FsmIT` |

## Phương án đã loại

- **Tách nhóm dịch vụ `INPATIENT` riêng:** các luồng cũ tự loại vì lọc theo nhóm, nhưng phải đổi CHECK của `services` (V1, V41) và `ServiceGroup`, xin BE-2 đồng ý lại; một rule chặn rẻ hơn.
- **Chỉ thêm `OVERDUE` vào (b), vẫn để (a) cấp chuồng thứ hai:** một thú giữ hai chuồng, thiếu chuồng cho đặt chỗ khác.
- **Mọi VET của chi nhánh làm thay người phụ trách; BRANCH_MANAGER gán lại Visit khi có ca `ADMITTED`:** đổi quyền sở hữu ở BR-KB-01 / BR-BH-02 hoặc FSM Visit#4; để hướng phát triển (lịch thay ca).
- **Mở rộng audit cho nhập / xuất viện, BRANCH_MANAGER xem nội trú, chặn xóa dòng đơn thuốc bằng 400:** chưa quyết ở phiên này; không đưa vào đặc tả.

## Nợ ghi nhận

D064 (FE mock nội trú) thêm: `OVERDUE` ở nhập từ lưu trú, 409 trùng ca, 400 `BR-LT-13` khi nhập (a) cho thú đang lưu trú, mã `NT-yyMM-xxxxx`.
