# ADR-0156: Bỏ trả hàng và hoàn tiền (module `returns`, ST21, Đơn hàng online #10 / #13); gỡ DB ở V66

- **Trạng thái:** Proposed (BE-2, 27/11/2026; chủ sản phẩm quyết định bỏ hẳn tính năng)
- **Liên quan:** thay phần trả hàng / hoàn tiền của ADR-0117 (mục D, TBD-1 phần "hoàn hàng", TBD-3), ADR-0137 D3 (giao dịch thành công về muộn → hoàn qua ST21), toàn bộ ADR-0138 (chốt đặc tả returns); ADR-0145 C1 (khách chỉ hủy đơn `PENDING_PAYMENT`) giờ là quy tắc vĩnh viễn, không còn "chờ task 138". Cùng khuôn ADR-0122 / 0131 (bỏ khiếu nại, gỡ DB bằng migration riêng). ADR đã Accepted không sửa.
- **Đánh số:** V65 giữ cho ADR-0153 (trigger khóa bệnh án, chưa commit lúc viết), nên migration này là **V66**. DB nào đã chạy V66 trước khi V65 vào repo sẽ fail Flyway validation khi V65 tới (`out-of-order` tắt): chốt thứ tự với người làm ADR-0153 trước khi áp V66 lên DB dùng chung.

## Bối cảnh

Đặc tả GĐ2 (08 §D, T54) có trả hàng (UC68, UC69, FSM #16), hoàn tiền (FSM #17, ST21 hoàn qua cổng, hoàn tại quầy vào ca thu ngân) và hai cạnh hủy đơn online đã thanh toán (#10 hủy sau giao thất bại, #13 khách hủy trước bàn giao). Tới 27/11/2026 BE đã có 3 endpoint (`POST /return-requests`, `/reject`, `POST /refunds/{id}/confirm`), ST21 `GatewayRefundJob`, `PaymentGatewayClient.refund`; #10, #13, các endpoint còn lại và `StockApi` hoàn hàng chưa có. Không mã production nào tạo dòng `refunds`. Chủ sản phẩm quyết định bỏ hẳn tính năng.

## Quyết định

1. **Bỏ UC68, UC69, ST21, FSM #16 (Yêu cầu trả hàng), FSM #17 (Hoàn tiền)** và hợp đồng `returns-v1` (9 endpoint). Giữ mã UC / ST / FSM / BR làm khoảng trống (như ADR-0122).
2. **Bỏ quy tắc** BR-BH-13, 14, 15 và BR-TG-12, 13, 14.
3. **Đơn hàng online:**
   - BR-BH-10: khách chỉ hủy được ở `PENDING_PAYMENT`; đơn đã thanh toán (`PAID` / `PROCESSING`) trả 409 `INVALID_STATE_TRANSITION`, xử lý ngoài hệ thống. Bỏ cạnh **#13**.
   - BR-BH-11: `DELIVERY_FAILED` chỉ có giao lại (#9, tối đa [CFG] `shop.max_redelivery`). Bỏ cạnh **#10** và shop-v1 #19 `cancel-failed`. Đơn hết lượt giao lại đứng ở `DELIVERY_FAILED`; chi nhánh xử lý ngoài hệ thống. **Không** thêm trạng thái kết thúc mới (tối thiểu; mở lại bằng ADR mới nếu cần).
   - BR-BH-12: ST12 tự xác nhận mọi đơn `DELIVERED` quá [CFG] `shop.auto_confirm_days`; không còn điều kiện "không có yêu cầu trả hàng đang mở". `OnlineOrder.refundStatus` bỏ khỏi shop-v1.
4. **Thanh toán:** giao dịch thành công về muộn cho đơn đã hủy (ADR-0137 D3) không tự hoàn; chênh lệch hiện ở đối soát (UC63 / ST14) để xử lý tay. `PaymentGatewayClient.refund` bỏ.
5. **Ca thu ngân:** đối soát ca (UC72) chỉ tính `payments`; `expectedCash` / `expectedTransfer` lại không âm. `CashierShiftApi` (chỉ có `recordRefund`) và `CashierShiftQueryApi.lockOpenShiftForShare` bỏ.
6. **Chi nhánh:** BR-CN-07 xem trước tồn đọng bỏ mục "hoàn tiền chưa xong" (`ClosureImpact.pendingRefunds`).
7. **Báo cáo:** bỏ giả định 08 §B A5 (doanh thu trừ hoàn tiền).
8. **Interface chéo module bỏ cùng:** `ReturnRequestQueryApi`, `shop.api.OnlineOrderQueryApi` (chỉ returns gọi), `OrderQueryApi.findLinesForReturn` / `findBranchForReturn` / `lockForReturn` + các record trả hàng, `sales.api.CollectMethod`, `StockApi.restock` / `restockToOriginalLot` / `discardReturned`.

## V66 (`V66__drop_returns_and_refunds.sql`)

- `DROP TABLE refunds`, `return_request_lines`, `return_requests` (FK duy nhất trỏ vào ba bảng là FK giữa chính chúng; không kiểm "bảng rỗng" vì không mã nào ghi).
- Xóa [CFG] `payment.refund_max_attempts`, `returns.window_days` (mỗi khóa một câu `DELETE`, như V27 / V51) → 84 khóa.
- Xóa 6 mẫu `RETURN_REQUESTED_APP`, `RETURN_APPROVED_APP`, `RETURN_REJECTED_APP`, `REFUND_REQUESTED`, `REFUND_REQUESTED_APP`, `REFUND_FAILED_APP`; còn dòng `notification_outbox` trỏ tới thì FK `RESTRICT` làm migration lỗi chứ không xóa lặng lẽ.
- `ck_stock_adjustments_reason` bỏ `RETURN_DISCARD` (V47); còn dòng dùng lý do này thì `ADD CONSTRAINT` lỗi.

## Hệ quả

- Số liệu: `SchemaMigrationIT.ERD_TABLES` 86 → 83; FK tới `accounts` 73 → 70; [CFG] 86 → 84; mẫu thông báo trong mock FE 65 → 59; job `@Scheduled` 26 → 25. `OpenApiContractParityIT` giữ **248** (returns-v1 không nằm trong danh sách module đã cài). Hợp đồng FE ↔ BE: 347 → 337 operation (− returns 9, − shop #19; `check-contracts.mjs` PASS 23 file).
- FE: bỏ trang `/admin/refunds` (menu "Trả hàng & hoàn tiền"), mock `returns`, nút "Hủy đơn" sau giao thất bại, trường `refundStatus`, `pendingRefunds` ở xem trước đóng chi nhánh, phần hoàn tiền trong đối soát ca của mock thu ngân; dữ liệu mẫu đơn online #10 đổi thành đơn hủy quá hạn thanh toán.
- Rủi ro chấp nhận: đơn `DELIVERY_FAILED` hết lượt giao lại không có lối ra trong hệ thống; tiền khách đã trả cho đơn hủy muộn chỉ thấy ở đối soát.
- Kiểm chứng 27/11/2026: `mvn test` (unit) xanh sau khi sửa ngưỡng `JobMetricsCoverageTest`; IT `SchemaMigrationIT`, `Tier3Be2SchemaIT`, `Tier3Be2ContractWiringIT`, `SystemConfigIT`, `OpenApiContractParityIT`, `PrometheusEndpointIT`, `module/shop|payment|sales/**/*IT` (376 ca) xanh; FE `tsc`, lint, Vitest.
