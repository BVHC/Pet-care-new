# ADR-0138: Chốt đặc tả trả hàng và hoàn tiền (BE-2, đóng TBD-3, TBD-6, tinh chỉnh ADR-0137 D1)

- **Trạng thái:** Accepted (autopilot BE-2, 15/11/2026)
- **Ngày:** 2026-11-15
- **Liên quan:** `docs/08-tang3-be2-spec.md` §D, §G, ADR-0117 mục 5 (TBD-3, TBD-6), ADR-0137 (D1, D3), `V46__returns_and_refunds.sql`, BR-TG-04, BR-TG-05, BR-KO-01, BR-KO-05, BR-BC-02, `docs/06-module-contracts.md` §6b.
- **Triển khai:** chỉ tài liệu. Không mã Java, không migration ở ADR này; migration `SALE_RETURN` / `return_requests` đi cùng task 137, lấy số V kế tiếp còn trống tại thời điểm làm.

## 1. Bối cảnh

Rà soát đặc tả D (trả hàng, hoàn tiền) trước task 136–138 thấy các chỗ chưa quyết được: hàng trả nhập vào lô nào khi một dòng Order được trừ từ nhiều lô; yêu cầu có tiền hoàn 0 đồng (TBD-3); hạn trả của đơn online tính từ `delivered_at` mà `OrderQueryApi` không biết (TBD-6); hoàn tiền `FAILED` chưa có lối ra "xử lý ngoài hệ thống"; hoàn tại quầy và khóa idempotent của ST21 chưa rõ; và `02` chưa sửa theo T54.

## 2. Quyết định

**R1. Lô nhận hàng trả (tinh chỉnh ADR-0137 D1).** `StockApi.restockToOriginalLot(orderLineId, qty)`: từ dòng Order lấy (Order, sản phẩm). Lô ứng viên = các lô của sản phẩm đó có movement **âm** với `source_type = 'orders'`, `source_id` = Order. Nhập lại theo thứ tự hạn dùng muộn nhất trước, mỗi lô tối đa (đã xuất − đã trả từ lô đó cho Order này). Movement `SALE_RETURN`, `source_type = 'return_requests'`, `source_id` = id yêu cầu trả hàng. Lô đã hết hạn vẫn nhận lại đúng lô (sổ lô chính xác; FEFO không xuất lô hết hạn nên BR-KO-05 giữ nguyên; ST08 báo, quản lý loại bỏ bằng điều chỉnh). `restock = false`: không đổi tồn, chỉ ghi nhận. Migration (giá trị `movement_type`, `source_type`) đi cùng task 137.

**R2. Hoàn 0 đồng (TBD-3).** Duyệt yêu cầu có `refund_amount = 0` hoàn tất yêu cầu ngay, không sinh dòng `refunds` (`refunds.amount > 0` giữ nguyên). Trạng thái cuối dùng có sẵn là `REFUNDED` (V46 `ck_return_requests_decision` đã cho `REFUNDED` có người duyệt, `restock`, `refund_amount`). FSM #16 thêm chuyển #6 `PENDING → REFUNDED`; #3 `PENDING → APPROVED` chỉ khi `refund_amount > 0`; tồn (nhập lại hoặc điều chỉnh giảm) vẫn xử lý như #3. Ghi ở BR-BH-14.

**R3. Hạn trả đơn online (TBD-6).** `shop.api` thêm `OnlineOrderQueryApi.findDeliveredAtByOrderId(orderId)` → `Optional<Instant>`; `orderId` = `orders.id`; rỗng khi Order không phải ONLINE hoặc chưa giao. `returns` dùng cho hạn 7 ngày (BR-BH-13). Owner BE-2 (shop), làm ở task 125/126; thêm dòng 21 vào `06` §6b.

**R4. Hoàn `FAILED` xử lý ngoài hệ thống (BR-TG-14).** FSM #17 thêm #5 `FAILED → SUCCEEDED` "Xác nhận đã hoàn ngoài hệ thống": A05 của chi nhánh của Order; bắt buộc ghi chú, lưu ở lý do audit (`refunds` không có cột ghi chú; `failure_reason` giữ lý do cổng); đặt `completed_at` (`ck_refunds_completed_at`) và `confirmed_by`; yêu cầu trả hàng tương ứng → `REFUNDED` (#5). Dùng lại `POST /refunds/{refundId}/confirm` (thêm A05 cho hoàn `GATEWAY` ở `FAILED`, `note` bắt buộc), không thêm endpoint (vẫn 9). Khớp ADR-0137 D3: giao dịch cổng thành công về muộn cho đơn đã hủy cũng sinh hoàn tiền (BR-TG-12(b)) và đi qua ST21 như mọi hoàn `GATEWAY`.

**R5. Hoàn tại quầy (BR-TG-13).** Ghi vào ca `OPEN` của chính người xác nhận; hệ thống không kiểm tiền trong két (việc vật lý); đối soát ca (BR-TG-05, UC72) trừ hoàn tiền tại quầy khỏi tiền mặt kỳ vọng. Mã giao dịch ngân hàng của hoàn `TRANSFER`: văn bản tự do, không bắt buộc, lưu ở lý do audit (V46 không có cột); đóng 08 §D Q5.

**R6. Idempotency ST21.** `refunds.code` là mã tham chiếu hoàn tiền gửi cổng và dùng lại ở mọi lần thử; `gateway_refund_id` là mã cổng trả về. Khi tạo: `attempts = 0`, `next_attempt_at = created_at`.

**R7. `refundStatus` (shop-v1, 08 §D Q4).** Có: chi tiết đơn online của khách (`OnlineOrder`) thêm `refundStatus` (`REQUESTED` / `SUCCEEDED` / `FAILED`), null khi không có hoàn tiền. Sửa `m_shop.py`, sinh lại hợp đồng.

**R8. Sửa `02` tại chỗ.** BR-TG-04 (Order `PAID` vẫn `PAID`, hoàn tiền theo BR-TG-12…14), BR-TG-05 (hoàn tại quầy trừ tiền mặt kỳ vọng), BR-KO-01 và BR-KO-05 (hàng trả vào lại lô gốc, có movement), BR-BC-02 (doanh thu trừ hoàn tiền `SUCCEEDED` theo kỳ của `completed_at`), mỗi chỗ đánh dấu "(sửa ở T105, 15/11/2026, xem `08` §D)".

**R9.** Bảng FSM #16 (6 chuyển) và #17 (5 chuyển) vẫn nằm ở `08` §D; `03` chỉ có dòng chỉ mục, nay ghi số chuyển.

## 3. Hệ quả

- Task 136–138 không còn TBD chặn trong §D (cổng VNPay sandbox vẫn là phụ thuộc bên ngoài của ST21).
- Task 137 cần migration cho `movement_type` `SALE_RETURN` và `source_type` `return_requests`; task 125/126 cài `OnlineOrderQueryApi`.
- `05` ghi `SALE_RETURN` / `return_requests` là "thêm ở task 137" (dòng `stock_movements` và §13 mục 26).
- Báo cáo doanh thu (UC89, task 136+) phải trừ hoàn tiền theo `completed_at`.
- ADR-0117 là Proposed; TBD-3, TBD-6 đánh dấu "Đã chốt ở ADR-0138".
