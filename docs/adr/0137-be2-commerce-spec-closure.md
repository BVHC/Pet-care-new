# ADR-0137: Chốt đặc tả thương mại điện tử và thanh toán online (BE-2, đóng TBD-1, TBD-2, TBD-5)

- **Trạng thái:** Accepted (autopilot BE-2, 15/11/2026)
- **Ngày:** 2026-11-15
- **Liên quan:** `docs/08-tang3-be2-spec.md` §A–§C, §G, ADR-0117 mục 5 (TBD-1, TBD-2, TBD-5), ADR-0124 (bỏ khám tại nhà), ADR-0106 (khóa trước khi đọc), ADR-0064 (`issueForPayment`), `docs/payment-gateway-setup.md`, `docs/06-module-contracts.md` §6b.
- **Triển khai:** chỉ tài liệu. Không mã Java, không migration ở ADR này; các migration nêu bên dưới đi cùng task triển khai.

## 1. Bối cảnh

Rà soát đặc tả B (giỏ hàng, đặt hàng, xử lý và giao) và C (thanh toán online) trước khi làm các task thương mại điện tử cho thấy ba câu mở của ADR-0117 (TBD-1, TBD-2, TBD-5), một số câu hỏi đã được quyết ở T54 nhưng còn ghi "TBD" trong generator, một chỗ chưa có thứ tự khóa khi giữ hàng, và vài lệch số liệu (fees-v1 ghi 6 endpoint, hợp đồng có 4; bảng `branch_home_visit_fees` mồ côi). ADR này chốt hết để task triển khai không phải đoán.

## 2. Quyết định

**D1. Hoàn về lô gốc (TBD-1).** `stock_movements` đã có `(source_type, source_id)`. "Hoàn về lô gốc" = với các movement **âm** của chứng từ nguồn (`source_type = 'orders'`, `source_id` = id Order), cộng lại số lượng vào **chính các lô đó**, bắt đầu từ lô có hạn dùng muộn nhất, mỗi lô không quá số đã xuất từ lô đó. Loại movement mới `SALE_RETURN` (delta dương) dùng cho hủy sau thanh toán (nguồn `orders`) và cho yêu cầu trả hàng được duyệt (nguồn mới `return_requests`). Migration đi cùng task triển khai đầu tiên (trả hàng hoặc hủy sau thanh toán), dùng số V49 để dành nếu còn trống, không thì số kế tiếp. *Lý do:* không cần thêm cột liên kết lô vào `stock_reservations`; dữ liệu truy vết đã nằm ở sổ movement, và hoàn đúng lô giữ nguyên FEFO và hạn dùng.

**D2. Lý do giao thất bại (TBD-2).** Thêm `online_orders.delivery_failure_reason VARCHAR(500)` NULL (bắt buộc khi ghi giao thất bại #8, giữ lý do gần nhất). Migration đi cùng task xử lý & giao đơn. *Lý do:* FSM #8 đòi "Có lý do" và `cancel_reason` chỉ dùng khi hủy; ghi vào audit làm lý do khó hiển thị cho khách.

**D3. `OnlineOrderApi.markPaymentCancelled` (TBD-5, 08 §C Q3).** Payment gọi khi cổng báo khách hủy hoặc thanh toán thất bại. Đơn **vẫn** `PENDING_PAYMENT` (khách thử lại bằng giao dịch mới tới `payment_due_at`, sau đó ST10 hủy); không đổi trạng thái; idempotent; enqueue mẫu `PAYMENT_FAILED` (EMAIL) và `PAYMENT_FAILED_APP` (IN_APP) đã seed ở V40, và payment chỉ gọi ở lần chuyển `PENDING → FAILED` để gọi lại callback không gửi thêm thư. VNPay không có API hủy phiên chưa thanh toán: giao dịch cục bộ hết hạn bằng ST11. Callback **thành công** đến cho đơn đã `CANCELLED`: giao dịch → `SUCCEEDED`, đơn vẫn `CANCELLED`, hoàn tiền toàn bộ qua ST21 (đoạn bổ sung ở 08 §C, vì bảng #15 và BR-TG-12 chưa nêu).

**D4. Tin cậy callback và khóa cổng (08 §C Q2).** Không có allowlist IP. Tin cậy = chữ ký HMAC-SHA512 (`vnp_SecureHash`) + số tiền khớp + idempotent theo mã giao dịch. Khóa cổng ở biến môi trường các biến môi trường ở `docs/payment-gateway-setup.md` mục 3 (`PAYMENT_GATEWAY`, `PAYMENT_MERCHANT_CODE`, `PAYMENT_SECRET`, `PAYMENT_BASE_URL`, `PAYMENT_RETURN_URL`, `PAYMENT_IPN_URL`), không phải [CFG]. VNPay là cổng đã chọn (T54); đăng ký sandbox là bước của con người (`payment-gateway-setup.md`), ghi nhận là **phụ thuộc bên ngoài**, không còn là câu hỏi mở của đặc tả. *Lý do:* địa chỉ IP của cổng đổi và khó đảm bảo; chữ ký mới là bảo đảm thực.

**D5. Thứ tự khóa khi giữ hàng (BR-BH-08, 09; ST06).** Đặt hàng = một transaction: `carts` của khách `FOR NO KEY UPDATE` → `branches` (chỉ đọc, kiểm `ACTIVE`; **không** dùng `BranchApi.lockForBooking`) → mọi `stock_lots` của các sản phẩm đặt tại chi nhánh trong **một** câu `ORDER BY id FOR NO KEY UPDATE` (như `StockApi.issueForPayment`) → tồn khả dụng = Σ `quantity` lô chưa hết hạn − Σ `HELD` → INSERT `orders` / `online_orders` / `stock_reservations` → `document_counters` cuối. Bán tại quầy, kê đơn và thu tiền phải trừ `HELD` khi kiểm tồn. *Lý do:* cùng thứ tự khóa lô với thu tiền nên hai luồng không deadlock; khóa lô một câu tránh thứ tự lô xen kẽ giữa các đơn.

**D6. ST06 một phần.** Tất cả hoặc không: một dòng thiếu hàng thì không giữ gì và không ghi gì (400 `BR-BH-08`). *Lý do:* đơn không tách giữa chi nhánh (A3) và không nhận nửa đơn.

Bổ sung ở 08: bảng "Quyết định T54" thêm dòng chi nhánh xử lý đơn (khách chọn một chi nhánh `ACTIVE` đủ hàng mọi dòng, BR-BH-08, A3) và các quyết định D1–D6; sửa `fees-v1` còn 4 endpoint; ghi `branch_home_visit_fees` (V43) mồ côi từ ADR-0124, giữ nguyên, gỡ bằng migration sau; câu hỏi đã chốt trong `m_shop.py` / `m_payment.py` đánh dấu "Đã chốt".

## 3. Hệ quả

- Task giỏ / đặt hàng, hủy sau thanh toán, trả hàng, xử lý & giao đơn và thanh toán online có đủ quy tắc để làm, không còn TBD chặn trong §B, §C.
- Cần hai migration: giá trị `SALE_RETURN` và `source_type` `return_requests` (task trả hàng hoặc hủy sau thanh toán, V49 nếu còn); cột `delivery_failure_reason` (task xử lý & giao đơn).
- Mọi chỗ kiểm tồn mới phải trừ `HELD`; chi phí là một câu khóa lô dài hơn cho mỗi lần đặt hàng, chấp nhận vì đơn online ít và khóa theo `id`.
- Thông tin xác thực cổng lấy từ các biến môi trường liệt kê ở `docs/payment-gateway-setup.md` mục 3 (BR-TG-11 giữ nguyên tên trung lập `PAYMENT_*`).
- ADR-0117 là Proposed; TBD-1, TBD-2, TBD-5 được đánh dấu "Đã chốt ở ADR-0137". TBD-3, TBD-4, TBD-6 vẫn mở.
