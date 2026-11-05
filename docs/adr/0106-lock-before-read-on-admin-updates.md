# ADR-0106: Sửa chi nhánh và danh mục phải khóa dòng trước khi đọc (mất cập nhật trên `branches`, `services`, `product_categories`, `vaccine_types`, `vaccination_protocols`)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-05
- **Liên quan:** ADR-0020 (OSIV tắt), 0029 §4 (khóa trước khi đọc entity), 0042 (khóa sản phẩm), 0022 / `BranchRepository.findByIdForUpdate`; nợ tự rà soát T31-9, T42-9, T43-10, T44-10 (phần BE-2).
- **Triển khai:** `BranchService.updateBranch`; `ProductCategoryService.updateCategory`, `ServiceCatalogService.updateService`, `VaccinationProtocolService.updateProtocol`, `VaccineTypeService.updateVaccineType`; bốn repository catalog thêm `findByIdForUpdate`. Không migration, không đổi API.

## Phát hiện

Quét các module BE-2 theo các lỗi đã thành quy ước (giờ không qua `Clock`, `findById` rồi mới khóa trong luồng ghi, `catch` trong transaction, truy vấn khóa thiếu `MANDATORY`). Hai nhóm đầu và nhóm cuối sạch; nhóm `findById` rồi ghi có 5 chỗ không khóa:

| Hàm | Ghi những cột nào | Ghi đè nguy hiểm nhất |
|---|---|---|
| `BranchService.updateBranch` | `name`, `address`, `phone`, tọa độ, `accepts_after_hours_emergency` | **`status`, `activated_at`** |
| `ProductCategoryService.updateCategory` | `name`, `is_active` | thay đổi song song của cột còn lại |
| `ServiceCatalogService.updateService` | tên, giá, cờ, loại chuồng… | thay đổi song song (giá!) |
| `VaccinationProtocolService.updateProtocol` | khoảng cách mũi, tuổi tối thiểu, cờ bắt buộc, `is_active` | như trên |
| `VaccineTypeService.updateVaccineType` | `name`, `is_active` | như trên |

Mọi entity này không có `@Version` và không dùng `@DynamicUpdate`, nên `saveAndFlush` phát `UPDATE` **tất cả cột** từ bản đã đọc. Đọc không khóa rồi ghi nghĩa là ghi đè mọi thay đổi commit giữa hai bước.

Nghiêm trọng nhất là chi nhánh: các writer khác của `branches` (kích hoạt, giờ mở cửa, ngày nghỉ, hạn mức, bật / tắt dịch vụ, và `BranchApi.lockForBooking` / `lockForStaffChange` của appointment / boarding / identity) đều `FOR NO KEY UPDATE`, riêng `PATCH /api/branches/{id}` thì không. Kịch bản (đã tái hiện trên Postgres thật): SUPER_MANAGER A kích hoạt chi nhánh (`DRAFT → ACTIVE`, đã có quản lý và giờ mở cửa); trong cùng mili-giây SUPER_MANAGER B sửa số điện thoại. B đọc `DRAFT` trước khi A commit, rồi `UPDATE` của B chờ khóa dòng của A, chạy sau và ghi lại `status = 'DRAFT'`, `activated_at = NULL`. Chi nhánh đã bắt đầu nhận đặt lịch quay về `DRAFT` mà không FSM nào cho phép chuyển đó (03 chỉ có `DRAFT → ACTIVE`); lịch hẹn, đặt chỗ, ca thu ngân mới bị BR-CN-01 chặn cho tới khi có người kích hoạt lại.

## Quyết định

1. **Khóa trước khi đọc** ở năm hàm trên: `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`, Hibernate phát `FOR NO KEY UPDATE`), bám quy ước "authorize → lock → read → guards → write" của CLAUDE.md. Chỉ khóa một dòng của bảng tương ứng, không thêm cạnh vào thứ tự khóa: `updateBranch` chỉ chạm `branches`; `updateService` chỉ khóa `services` (dòng `kennel_types` dùng chung khóa chính, đọc sau).
2. **Repository catalog đặt `@Transactional(propagation = MANDATORY)` trên phương thức khóa** (quy ước: gọi ngoài transaction thì khóa nhả ngay). `BranchRepository.findByIdForUpdate` đã có từ trước và giữ nguyên.
3. **Không thêm `@Version`.** Khóa pessimistic phù hợp mẫu của dự án và cho kết quả đúng ngay (yêu cầu sau chờ rồi đọc bản mới) thay vì trả 409 cho người dùng vì một sửa đổi không xung đột trường.
4. **Không đổi các hàm xóa** (`deleteVaccineType`…): khóa ngoại `RESTRICT` đã chặn xóa khi có tham chiếu chen vào.

## Bảng đảm bảo

| # | Đảm bảo | Test |
|---|---|---|
| G1 | PATCH chi nhánh chờ khóa của một lần kích hoạt đang chạy rồi đọc lại; sau đó chi nhánh vẫn `ACTIVE` và có tên mới | `BranchFsmIT.anEditWaitingBehindAnActivationDoesNotRevertIt` (đỏ trước khi sửa: chi nhánh về `DRAFT`) |
| G2 | Năm hàm khóa trước khi lưu và không đọc không khóa | `BranchServiceTest.updateLocksTheBranchRowAndNeverReadsItUnlocked`; `…ServiceTest.updateLocksTheRowAndNeverReadsItUnlocked` ở 4 service catalog |
| G3 | Đường sửa qua HTTP chạy được với khóa `MANDATORY` | `CatalogAdminIT` (13 lệnh PATCH), `BranchFsmIT` / `BranchAdminIT` |

Mutation: trả `updateBranch` về `findById` → `BranchFsmIT.anEditWaiting…` và `BranchServiceTest` đỏ (đã đỏ trước khi sửa, đó là bằng chứng RED). Bốn service catalog: đổi lại `findById` → test đơn vị `InOrder` đỏ; không viết thêm IT đua cho chúng (cơ chế đã chứng minh ở chi nhánh, tần suất sửa thấp: chỉ SUPER_MANAGER).

## Ngoài phạm vi

`PetService`, `PetWeightService`, `CustomerQueryService`, `Content*`, `Inventory*`, `Sales*Query*`: đọc thuần (`readOnly`) hoặc đã khóa đúng (kiểm từng chỗ). Module của BE-1 (identity, appointment, visit, care) chưa quét lần này.

## Còn lại sau lần quét này

- **Phương thức khóa thiếu `@Transactional(MANDATORY)`:** khoảng 15 phương thức cũ của BE-2 (`BranchRepository`, `CustomerRepository`, `PetRepository`, `ProductRepository`, `SalesOrderRepository.findByIdForUpdate` / `findByVisitIdForUpdate`, `StockReceiptRepository`, `SupplierRepository`, `StockLotDao`…) chưa có lớp phòng thủ này. Mọi nơi gọi hiện đều nằm trong `@Transactional` nên chưa có lỗi; chỉ thêm khi đụng tới từng repository (nhiều repository dùng chung với BE-1, đổi hàng loạt không đáng rủi ro).
- Module BE-1 (identity, appointment, visit, care) chưa quét theo cùng bốn tiêu chí; việc của BE-1.
