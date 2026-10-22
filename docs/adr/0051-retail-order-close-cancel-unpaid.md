# ADR-0051: Order bán lẻ tại quầy — tạo, chốt, hủy chưa chốt, BRANCH_MANAGER hủy Order khách không thanh toán, danh sách Order (T29)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-20
- **Liên quan:**
  - sales-v1 #1, #2, #7, #8, #10 (UC66, ST13); 02 BR-BH-01, 04, 05, 06, BR-SP-01, BR-LT-09, 12, BR-BC-02, BR-QT-15; 03 #5 Order#2, #5, #7, #10; 05 §8 `orders` (`cancel_reason VARCHAR(300)`, `cancel_type`); convention 07, 08 §8.3.
  - ADR-0038 (truy vấn chỉ ghép điều kiện đã gửi; readOnly + REPEATABLE READ), 0040 (Swagger = hợp đồng), 0043 (mã chứng từ), 0044 (Order lõi, khóa trước đọc sau, `OrderLinePolicy`).
- **Triển khai:**
  - `sales/service/{RetailOrderService, OrderCancellationService, OrderListService, SalesAuditActions}`, `sales/repository/OrderListRepository`, `sales/dto/{CreateRetailOrderRequest, OrderReasonRequest, OrderSummaryResponse}`, `SalesOrder.openRetail`, 5 handler mới trong `sales/controller/OrderController`.
  - `boarding/service/BoardingQueryApiPlaceholder.findBooking` cài thật bằng SQL (đọc không khóa, kèm `boarding_check_ins.emergency_phone`).
  - Test: unit `RetailOrderServiceTest`, `OrderCancellationServiceTest`, `OrderListServiceTest`, `RetailOrderRequestValidationTest`, `OrderControllerSecurityTest`, `BoardingQueryApiPlaceholderTest`; IT `module/sales/RetailOrderIT` (17 ca), `CrossModuleQueryIT.findBookingReadsTheBookingRowOrNothing`, `OpenApiContractParityIT` (91 endpoint). Không migration.

## Bối cảnh

T19 (ADR-0044) dựng Order lõi và thêm / sửa / xóa dòng cho mọi nguồn. Thêm hàng bán lẻ đã có đủ BR-BH-04 (cảnh báo thiếu tồn `stockWarning`, không chặn) và BR-SP-01 (thuốc kê đơn 400). T29 cần phần còn lại của UC66: tạo Order `RETAIL`, chốt, hủy chưa chốt, BRANCH_MANAGER hủy Order `PENDING` khách không thanh toán, và danh sách Order (quản lý không có cách nào khác để tìm Order `PENDING` cần hủy; ST13 dùng cùng bộ lọc). Đặc tả để ngỏ: ai trong lễ tân được chốt / hủy, có chặn chi nhánh `DRAFT` / ngoài giờ không, "quá 1 ngày" tính thế nào, thứ tự giữa 409 và rule.

## Quyết định

### 1. Tạo (Order#2)
1. Chỉ `RECEPTIONIST` (`@PreAuthorize`). Chi nhánh = chi nhánh của lễ tân, không nhận `branchId` trong body.
2. Khách bắt buộc (hợp đồng A1); không có → 404 "Không tìm thấy khách hàng #id". Không kiểm cờ chờ liên kết (BR-TK-19 chỉ ẩn chức năng phía khách) và không kiểm khóa tài khoản khách: bán tại quầy cho người đang đứng trước quầy là nghiệp vụ phổ biến.
3. **Không chặn chi nhánh `DRAFT` hay ngoài giờ mở cửa:** BR-CN-01 chỉ giới hạn công khai / đặt lịch / lưu trú / tiếp nhận; tiền chỉ thu được khi có ca thu ngân (BR-TG-01, T30) nên bán lẻ ngoài giờ không tạo doanh thu sai.
4. Không khóa `customers`: khóa FK `FOR KEY SHARE` của câu INSERT đã đủ để việc xóa hồ sơ online song song (UC07, ST02) gặp lỗi FK thay vì làm mồ côi Order. Mã `DH-yyMM-nnnnn` lấy **sau cùng** (ADR-0043), `validateInitial(OPEN)` trước đó.
5. Không có khóa idempotency: bấm đúp tạo hai Order `OPEN` rỗng (nợ, FE khóa nút).

### 2. Chốt (Order#5) và hủy chưa chốt (Order#7)
1. Mọi lễ tân của chi nhánh làm được (không riêng người tạo — giao ca tại quầy là chuyện thường); lễ tân chi nhánh khác 403.
2. Khóa dòng `orders` trước khi đọc (ADR-0044), cùng khóa với thêm / xóa dòng, nên "chốt ∥ xóa dòng cuối" và "chốt ∥ hủy" luôn tuần tự (IT, mutation bỏ khóa làm đỏ 2 ca).
3. Thứ tự kiểm: 404 → 403 chi nhánh → **409 nếu không phải `RETAIL` `OPEN`** (kể cả Order Visit `OPEN`) → 400 BR-BH-01 khi chưa có dòng (chỉ với chốt) → `validateTransition`. 409 trước rule như ADR-0049 Q6 và hợp đồng.
4. Hủy chưa chốt: `cancel_type = RETAIL_UNCLOSED`, không lý do, không audit (BR-QT-15 chỉ đòi audit hủy từ `PENDING`), các dòng giữ nguyên làm lịch sử, kho không đổi (chưa trừ gì).
5. Sau khi chốt, lễ tân vẫn thêm / xóa dòng GOODS (BR-BH-02, đã có ở T19); Order `PENDING` xóa hết dòng vẫn ở `PENDING` tổng 0 (nợ — T30 quyết thu 0 đồng hay buộc hủy).

### 3. Hủy Order khách không thanh toán (Order#10)
1. Chỉ `BRANCH_MANAGER` (`@PreAuthorize`) của chi nhánh Order (`BranchScope.check`). Mọi nguồn: `VISIT`, `RETAIL`, `BOARDING`.
2. Thứ tự: khóa Order (404) → 403 chi nhánh → 409 nếu không `PENDING` → lý do trống / chỉ khoảng trắng (sau `strip`) → **400 BR-BH-05** (convention 08: kiểm "có lý do" là rule, ở service) → Order `BOARDING` mà đặt chỗ đang `CHECKED_IN` / `OVERDUE` → 400 BR-BH-05 (message chỉ sang hủy phiên trả thú, BR-LT-09) → `validateTransition` → ghi → audit.
3. Lý do quá 300 ký tự (cột `VARCHAR(300)`) → 400 `VALIDATION_FAILED` bằng `@Size` trên `OrderReasonRequest` (không dùng `ReasonRequest` 500 ký tự của identity). Body bắt buộc như hợp đồng; thiếu body → 400 `MALFORMED_REQUEST`.
4. Ghi: `status = CANCELLED`, `cancel_type = UNPAID` (thất thu BR-BC-02), `cancelled_by`, `cancelled_at`, `cancel_reason` đã strip. **Không đổi kho** (vaccine đã trừ khi tiêm vẫn trừ), bệnh án, lịch hẹn, lưu trú; không phát sự kiện, không thông báo.
5. Audit `ORDER_CANCELLED_UNPAID` (`entity = orders`, before `{status: PENDING}`, after `{status, cancelType, totalAmount}`, `reason`) trong cùng transaction (`record`).
6. **Đặt chỗ đọc không khóa** (`BoardingQueryApi.findBooking`): thú đang trong chuồng chỉ rời chuồng bằng cách thu chính Order này (phải chờ khóa Order đang giữ) hoặc kết thúc vì thú mất (trạng thái cuối), nên kết quả đọc không đảo chiều trước khi commit — không cần cạnh khóa `orders → boarding_bookings` (tránh vòng với trả thú T43, vốn khóa đặt chỗ trước Order).
7. Hai lần hủy song song: lần sau chờ khóa, thấy `CANCELLED` → 409; đúng một dòng audit (IT).

### 4. Danh sách (sales-v1 #2)
1. A05–A08, luôn chi nhánh mình (`BranchScope.resolve(null)`); ADMIN / SUPER_MANAGER không nằm trong actor của hợp đồng → 403.
2. `OrderListRepository` dùng `NamedParameterJdbcTemplate`, chỉ ghép điều kiện đã gửi (ADR-0038); mọi dạng có `branch_id = :branchId` nên dùng tiền tố `ix_orders_branch_status_pending_at`, lọc khách dùng `ix_orders_customer_status`. Không JOIN `customers`: tên ghép theo lô bằng một lần `CustomerQueryApi.findContacts` cho cả trang.
3. `date` = ngày tạo theo giờ Việt Nam (nửa khoảng `[00:00, 00:00 hôm sau)`).
4. `pendingOverdue = true` = `PENDING` và `pending_at` trước 00:00 của ngày (hôm nay − N + 1), N = `order.pending_alert_days` [CFG] (mặc định 1 → chốt từ hôm qua trở về trước — báo cáo cuối ngày của ST13 theo ngày nghiệp vụ, không theo 24 giờ trượt), sắp `pending_at, id` tăng dần (cũ nhất trước). `false` = không lọc. Không lọc thì sắp `created_at DESC, id DESC`.
5. `size` kẹp 1–100, `page` âm = 0. readOnly + REPEATABLE READ để tổng số và trang cùng một snapshot.

### 5. `BoardingQueryApi.findBooking`
Boarding chưa có module (T31, T43). Như T17, BE-2 cài thật **một phần** trong `BoardingQueryApiPlaceholder`: `findBooking` đọc `boarding_bookings` LEFT JOIN `boarding_check_ins` (không khóa). Hiện chưa có đường tạo Order `BOARDING` (`BoardingOrderApi`, T43), nên nhánh này chỉ IT chạm tới.

## Lý do

- Khóa dòng `orders` là điểm tuần tự hóa duy nhất cho mọi thao tác trên một Order (ADR-0044) — chốt / hủy dùng lại thay vì thêm `version` hay khóa khác.
- Tách `RetailOrderService` (nguồn RETAIL, lễ tân) và `OrderCancellationService` (mọi nguồn, quản lý, có audit; T43 đặt `abort-checkout` cạnh đây) theo người kích hoạt của 03 #5.
- Kiểm lý do ở service thay vì `@NotBlank` để client nhận đúng mã BR-BH-05 như hợp đồng.

## Phương án bị loại

- **Chỉ người tạo được chốt / hủy:** không có trong BR-BH-01/02; quầy đông thường có nhiều lễ tân phục vụ một khách.
- **Khóa `boarding_bookings` khi hủy Order lưu trú:** thêm cạnh khóa ngược với trả thú, có thể deadlock; không cần vì trạng thái "trong chuồng" không thể quay lại khi Order còn bị khóa.
- **`pendingOverdue` theo 24 giờ trượt (`pending_at < now − N ngày`):** danh sách cuối ngày đổi theo giờ chạy; quản lý cần "chưa thu từ hôm qua" theo ngày.
- **JOIN `customers` trong truy vấn danh sách:** phá ranh giới module (ADR-0042 §6 chỉ cho phép ngoại lệ có chủ đích); một lần gọi theo lô là đủ.

## Hệ quả

- Lễ tân bán lẻ trọn vòng tới `PENDING`; thu tiền (Order#8) chờ T30. Order `PENDING` không bao giờ tự hủy (BR-BH-01); chỉ quản lý hủy, có audit.
- Nợ (file local `docs/dept/T29-ban-le.md`): không idempotency khi tạo; Order `OPEN` rỗng bị bỏ dở không có job dọn và vẫn tiêu số `DH-` (trần 99 999 / tháng toàn chuỗi, dùng chung với Visit); Order `PENDING` tổng 0 sau khi xóa hết dòng; chưa index `(branch_id, created_at)` cho lọc `date`; FE chưa nối.
