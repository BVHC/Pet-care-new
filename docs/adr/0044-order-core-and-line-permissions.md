# ADR-0044: Order lõi — máy trạng thái, dòng snapshot giá, quyền theo chủ dòng, `VisitOrderApi` (T19)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-17
- **Liên quan:**
  - sales-v1 #3–6 (UC66, UC67); 02 BR-BH-01…03 (và BR-BH-04 phần cảnh báo, BR-SP-01, BR-TN-01, BR-TN-08, BR-KB-03, BR-KB-04); 03 #5 Order; 04 §8; 05 §8 (`orders`, `order_lines`); 06 §3, §4, §6.
  - ADR-0029 (khóa trước đọc sau), 0040 (Swagger = hợp đồng), 0042 (thứ tự khóa kho), 0043 (mã chứng từ), 0041 (khuôn module BE-2).
- **Triển khai:**
  - `sales/entity/{SalesOrder, OrderLine, OrderStatus, OrderLineType, OrderCancelType}`, `sales/fsm/OrderTransitionHandler`, `sales/repository/SalesOrderRepository`.
  - `sales/service/{VisitOrderService, OrderLineService, OrderViewService, OrderQueryService, OrderLinePolicy, OrderAssembler, OrderGuards}`, `sales/controller/OrderController`, `sales/dto/*`, `sales/mapper/OrderMapper`, `sales/exception/{OrderLineAccessDeniedException, InvalidOrderLineRequestException, OrderAmountOverflowException}`.
  - Xóa `OrderQueryApiPlaceholder`; `VisitQueryApiPlaceholder.findVisit` cài thật bằng SQL (BE-1 thay ở T26). Migration V15 (`order_lines(order_id)`).
  - Test: unit `OrderTransitionHandlerTest`, `SalesOrderTest`, `VisitOrderServiceTest`, `OrderLineServiceTest`, `OrderLinePolicyTest` (560 tổ hợp), `OrderQueryServiceTest`, `OrderMapperTest`, `OrderLineRequestValidationTest`, `OrderControllerSecurityTest`; IT `VisitOrderApiIT` (18), `OrderLineIT` (28), `CrossModuleQueryIT`, `OpenApiContractParityIT` (80 endpoint).

## Bối cảnh

Module `sales` chỉ có interface. BE-1 cần `VisitOrderApi` để nối Visit (T26–T28, T38) từ 20/10; Order là chứng từ mà kho (ADR-0042), thu ngân (T30) và lưu trú (T43) cùng chạm. Hai điểm dễ sai và khó sửa sau: (1) tổng tiền và dòng bị sửa song song; (2) quyền sửa dòng nằm rải rác theo vai trò, trạng thái, chủ dòng nên cờ hiển thị cho FE lệch với hành vi thật.

## Quyết định

### 1. Mô hình
1. Entity `SalesOrder` (bảng `orders`; tên tránh `Order` vì là từ khóa JPQL) có `lines` `@OneToMany(cascade ALL, orphanRemoval)`. `totalAmount` **luôn tính lại từ các dòng** bằng `addExact`, không cộng dồn chênh lệch; `OrderLine.lineTotal` bằng `multiplyExact`. Tràn `BIGINT` là 400 `VALIDATION_FAILED` (`OrderAmountOverflowException`, không mã BR vì docs không có rule) thay vì `bigint out of range` → 500; `addLine` hoàn tác danh sách dòng khi tràn.
2. FSM `OrderTransitionHandler` đúng 10 chuyển của 03 #5 (`OPEN → PENDING | CANCELLED`, `PENDING → PAID | CANCELLED`; khởi tạo `OPEN`, `PENDING`). Điều kiện theo **nguồn** (Order#6 chỉ `OPEN → CANCELLED`; #9 chỉ BOARDING; #10…) là guard của service chạy trước `validateTransition`: `cancelByVisit` gọi `requireOpen` trước, vì bảng cho phép `PENDING → CANCELLED` (#9, #10 là chuyển khác, người khác kích hoạt).
3. Mã Order `DH-yyMM-nnnnn` từ `DocumentCodeGenerator` (ADR-0043), lấy sau các kiểm tra trong transaction mở Order.
4. Giá và tên **chụp lúc thêm dòng** (BR-BH-03). Sản phẩm đọc `FOR SHARE` (`CatalogQueryApi.findProductForShare`) nên đổi giá song song bị xếp hàng và giá chụp là giá vừa commit.

### 2. Khóa
1. Mọi thao tác ghi khóa dòng `orders` (`FOR NO KEY UPDATE`) **trước khi đọc entity**, bằng `findByIdForUpdate` / `findByVisitIdForUpdate`; không `findById` rồi mới khóa. Với dòng: đọc id Order chứa dòng (không khóa) → khóa Order → đọc lại dòng dưới khóa (dòng có thể vừa bị xóa → 404).
2. **Thứ tự toàn hệ thống:** `visits` (visit tự khóa) → `orders` → `products` `FOR SHARE` → lô kho (ADR-0042). REST của sales **không bao giờ khóa `visits`**: đọc Visit không khóa qua `VisitQueryApi.findVisit` để kiểm người phụ trách, nên không có vòng.
3. **Gán lại lượt (Visit#4) đua với thêm dòng:** gán lại khóa Visit rồi `transferLineOwnership` chờ khóa Order. Thêm dòng thắng khóa: dòng mới có chủ là VET cũ và được chuyển cùng. Gán lại thắng: VET cũ đọc người phụ trách mới sau khi chờ → 403. Cả hai thứ tự nhất quán (`OrderLineIT.reassignment…`, `lineAddedBeforeReassignment…`).
4. **Nghĩa vụ cho BE-1 (sửa 06 §3):** ghi mũi tiêm gọi `addVaccineLine` (khóa Order) **trước** `StockApi.lockFefoLot` (chứng từ trước, lô sau — ADR-0042). Xóa mũi tiêm: `revertVaccination` → **xóa `vaccinations` trước** → `removeVaccineLine` (FK `vaccinations.order_line_id` RESTRICT). `removeDrugLine`: gỡ `prescription_items.order_line_id` trước (FK RESTRICT, UNIQUE).

### 3. Quyền dòng — một nguồn duy nhất
`OrderLinePolicy` thuần túy (không Spring, không DB): `evaluate(thao tác, vai trò, tài khoản, Order, dòng)` trả lý do từ chối hoặc rỗng. Guard của `OrderLineService` và cờ `canEdit` / `canDelete` trong response cùng gọi nó, nên cờ không thể lệch hành vi thật (`OrderLineIT.viewerFlagsMatch…`). Thứ tự đánh giá:

| Điều kiện | Kết quả |
|---|---|
| Order `PAID` / `CANCELLED` | 409 |
| Dòng VACCINE, BOARDING | 400 BR-BH-02 (xóa mũi tiêm / sinh từ trả thú) |
| Dòng DRUG | xóa: 400 BR-BH-02 (qua đơn thuốc); sửa: `PENDING` 403, `OPEN` 400 BR-BH-02 |
| Dòng SERVICE | `PENDING` 403 BR-BH-02 (khóa); `OPEN` chỉ `owner_account_id` (lễ tân, VET, CARETAKER), còn lại 403 |
| Dòng GOODS | mọi lễ tân (cả `OPEN` lẫn `PENDING`), vai trò khác 403 |

Thêm dòng: lễ tân → GOODS; VET → SERVICE nhóm MEDICAL; CARETAKER → SERVICE nhóm GROOMING, và VET / CARETAKER phải là `assignee_id` của Visit gắn với Order (Order không phải VISIT → 403). Order `OPEN` nhận mọi dòng, `PENDING` chỉ GOODS (03 #5). Thứ tự kiểm khi thêm: hình dạng 400 → vai trò–loại dòng 403 → **khóa Order** (404) → chi nhánh 403 → nhóm dịch vụ 403 → người phụ trách 403 → trạng thái 409 → danh mục / rule 400.

### 4. Hợp đồng và lỗi
1. 403 của BR-BH-02 mang mã rule: `OrderLineAccessDeniedException extends AccessDeniedScopeException`, chỉ khác `clientMessage()` ("… (BR-BH-02)"); chi tiết phạm vi chỉ ghi log.
2. Lỗi không có mã BR trong hợp đồng (thiếu `serviceId`/`productId`, tăng số lượng, dịch vụ ngừng kinh doanh hoặc không bật tại chi nhánh) là 400 `VALIDATION_FAILED` qua `InvalidOrderLineRequestException`.
3. **PATCH chỉ giảm số lượng** (tên endpoint là "giảm số lượng"); muốn thêm thì thêm dòng mới để có cảnh báo tồn. `serviceId` chỉ cho dòng SERVICE tự sinh của Order VISIT, cùng nhóm với vai trò, giá chụp lại tại thời điểm đổi (hợp đồng A5).
4. **Lễ tân không thêm VACCINE như hàng bán lẻ** (400 BR-KB-04): vaccine vào Order khi ghi mũi tiêm. Thuốc kê đơn: 400 BR-SP-01. Thuốc không kê đơn bán như GOODS.
5. `stockWarning.requested` = tổng số lượng sản phẩm đó trong Order sau khi thêm (lúc thu kiểm theo tổng); `available` = `StockQueryApi.availableQuantity`. Chỉ cảnh báo, không chặn (BR-BH-04). Số lượng 1…1 000 000 như dòng phiếu nhập.
6. `VisitOrderApi` thật, mọi method `MANDATORY`: Visit chưa có Order, sai loại sản phẩm… là lỗi của bên gọi (`IllegalStateException` / `IllegalArgumentException`); Order sai trạng thái là 409; mở Order lần hai cho cùng Visit vi phạm `uq_orders_visit_id` (409, rollback cả mã đã cấp).

## Hệ quả

- Thêm / sửa / xóa dòng cùng Order xếp hàng trên một dòng khóa; tần suất thao tác trên một Order nhỏ, chấp nhận. `OrderLineIT.parallelAdds…` chứng minh 10 luồng cho tổng đúng bằng Σ dòng; mutation bỏ khóa làm 5 IT đỏ.
- Thu tiền (T30) khóa nhiều Order theo **id tăng dần** rồi mới `StockApi` (ghi vào nghĩa vụ của T30).
- `sales` đọc `VisitQueryApi.findVisit` (BE-1) nên placeholder được cài thật bằng SQL; BE-1 thay ở T26 và giữ ngữ nghĩa ở javadoc.
- Không có idempotency cho "thêm dòng" (hợp đồng không có khóa); bấm đúp tạo hai dòng — FE khóa nút (nợ ghi ở `docs/dept/T19-order.md`).

## Phương án đã loại

- **Cộng dồn chênh lệch vào `total_amount`:** lệch khi một thao tác bị bỏ sót; tính lại từ dòng đơn giản và đúng.
- **Khóa `visits` trong REST để kiểm người phụ trách:** thêm cạnh khóa `orders → visits` ngược chiều với Visit (Visit khóa trước), nguy cơ deadlock; đọc không khóa đủ vì gán lại phải chờ khóa Order.
- **Quyền rải trong từng method:** đã thấy dễ lệch với cờ `canEdit`; một policy thuần kiểm thử được bằng ma trận.
- **Cho PATCH tăng số lượng:** bỏ qua cảnh báo tồn của BR-BH-04.
