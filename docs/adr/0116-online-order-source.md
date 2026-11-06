# ADR-0116: Order nguồn `ONLINE` — hóa đơn ở `orders`, tiền ở `payment_transactions`, không vào `payments` (V42)

- **Trạng thái:** Proposed (BE-1 làm thay BE-2 theo yêu cầu của Hiến, 6/11/2026; Dũng chưa đọc. Chỉ nới DB; chưa có logic shop, chưa có `OrderSource.ONLINE` trong Java)
- **Ngày:** 2026-11-06
- **Liên quan:** `docs/08` §B (FSM #14, #14b), §C (FSM #15, BR-TG-06…09), §D (BR-TG-12), `docs/05` §13 mục 26, 27; `docs/06` §6b hàng 7; ADR-0113 §2 (lý do V41 không làm phần này); ADR-0044 (Order, D045), ADR-0064 (ca thu ngân, ST13); BR-BH-05, BR-TG-01.
- **Triển khai:** `V42__orders_online_source.sql`; `Tier3Be1SchemaIT.onlineOrderHasNoVisitBoardingOrCounterPaymentAndCounterInvariantsStay`; `SchemaMigrationIT` (`orders.source` thêm `ONLINE`, danh sách phiên bản thêm 42).

## 1. Vấn đề

08 §B #14b: Order `ONLINE` sinh ở `PENDING` (Order#11), lên `PAID` "thu qua cổng, không qua ca thu ngân" (Order#12), về `CANCELLED` (Order#13). Schema GĐ1 vướng bốn chỗ:

| Chỗ | Vướng |
|---|---|
| `ck_orders_source` | không có `ONLINE` |
| `ck_orders_single_source` | `ONLINE` không khớp nhánh nào (không phải Visit, lưu trú, bán lẻ) |
| `ck_orders_paid_payment` | `PAID ⇔ payment_id`, mà `payments.cashier_shift_id` và `received_by` là `NOT NULL`, `method` chỉ `CASH` / `TRANSFER` |
| `ck_orders_cancel_type` | không có loại hủy của đơn online |

## 2. Quyết định

`orders` là hóa đơn (dòng hàng đã chốt giá, tổng tiền hàng); `online_orders` giữ vòng đời giao hàng và `shipping_fee` (08 L461: phí vận chuyển báo riêng, nên **không** có dòng phí trong Order); `payment_transactions` giữ tiền (số tiền gửi cổng = tiền hàng + `shipping_fee`). **Tiền online không đi vào `payments`.**

V42 chỉ sửa bốn CHECK của `orders`, không đụng `payments`:

1. `ck_orders_source`: thêm `ONLINE`.
2. `ck_orders_single_source`: `ONLINE` như `RETAIL` (không Visit, không đặt chỗ lưu trú).
3. `ck_orders_paid_payment`: `ONLINE` thì `payment_id` luôn NULL, kể cả khi `PAID`; nguồn khác giữ `PAID ⇔ payment_id`. Hệ quả: DB tự chặn thu tiền quầy cho đơn online (`orders.payment_id` chỉ trỏ tới biên lai quầy).
4. `ck_orders_cancel_type`: thêm `ONLINE_CUSTOMER` (Đơn hàng online#3, khách hủy) và `ONLINE_EXPIRED` (#4, ST10 quá hạn thanh toán). **Không dùng `UNPAID`** cho đơn online: `SalesReportRepository.lostRevenue` đếm `UNPAID` là doanh thu mất của quầy (BR-BH-05). Hủy sau `PAID` không đổi Order (08 L97), hoàn tiền ghi ở `refunds` nên không cần loại hủy.

Bất biến **"Order `ONLINE` `PAID` ⇒ có giao dịch `SUCCEEDED`" không thành CHECK**: FK `orders → payment_transactions` tạo vòng với `online_orders`. Service giữ trong một transaction chuỗi Giao dịch#2 → Đơn online#2 → Order#12 (nguyên tắc 6), cần một IT riêng (T65) và thứ tự khóa ghi ở ADR của T65 (theo D045: khóa Order trước khi đọc).

`orders.created_by` (`NOT NULL`) dùng tài khoản của khách đặt hàng (đặt online cần tài khoản); `branch_id` là chi nhánh xử lý; `customer_id` là hồ sơ khách.

## 3. Việc bắt buộc khi T63 ghi Order `ONLINE` đầu tiên

V42 làm DB nhận `ONLINE` nhưng Java chưa biết. Cho tới khi có dòng `ONLINE` đầu tiên không có gì hỏng; PR đầu tiên ghi dòng đó phải làm cùng lúc:

1. `OrderSource.ONLINE` và `OrderCancelType.ONLINE_CUSTOMER` / `ONLINE_EXPIRED` (`sales/entity/OrderCancelType.java`) + enum trong contract (`m_sales.py`: `OrderSource` dòng 46, tham số `source` dòng 122, `cancelType` dòng 60; chạy lại generator; `report-v1` nếu có enum) — `SalesReportRepository` gọi `OrderSource.valueOf(...)` và entity `SalesOrder.source` sẽ ném lỗi khi gặp giá trị lạ.
2. Loại `ONLINE` khỏi: truy vấn Order `PENDING` của ST13 digest (`SalesOrderRepository`), danh sách Order của quầy nếu không muốn lẫn đơn online (`OrderListRepository`, `GET /orders`); từ chối `ONLINE` ở endpoint thu tiền quầy bằng 400 có thông điệp rõ (CHECK ở DB chỉ là lưới cuối). Đơn online `PENDING` là chờ khách trả cổng trong `shop.payment_window_hours`, hạn do ST10, không phải đơn lễ tân quên thu.
3. Đặt `paid_at` khi lên `PAID`: báo cáo doanh thu tính theo `orders.status = 'PAID'` và `paid_at`, không join `payments`, nên đơn online tự vào; phần trừ hoàn tiền làm ở T73 (08 L461).
4. Kho: giữ hàng (`stock_reservations`) lúc đặt, `consumeReservation` xuất FEFO lúc `PAID` (khác Order quầy xuất kho lúc thu).

## 4. Phương án đã bỏ

- **`payments` thêm `GATEWAY`, `cashier_shift_id` / `received_by` nullable:** gọn số bảng nhưng làm bẩn bảng lõi; mọi truy vấn ca và đối soát (UC72) phải lọc đơn online ra, rủi ro cao cho phần GĐ1 đang chạy.
- **`orders.payment_transaction_id` FK:** DB giữ được "PAID ⇒ có giao dịch" nhưng tạo vòng FK `payment_transactions → online_orders → orders → payment_transactions`; chỉ nên làm nếu BE-2 muốn DB giữ cứng.

## 5. Câu hỏi mở cho BE-2

Tên hai giá trị `cancel_type` (đổi bằng migration mới nếu muốn khác); có muốn CHECK / trigger cho "PAID ⇒ có giao dịch `SUCCEEDED`" không; đơn online `PENDING` quá hạn do ST10 hủy với `ONLINE_EXPIRED` có tính vào báo cáo "doanh thu mất" riêng của kênh online không (hiện không); cách tính doanh thu ròng khi có hoàn tiền (T73, 08 Q4).

## 6. Kiểm

Test chạy mọi INSERT trong transaction hoàn tác: Order `ONLINE` `PENDING` hợp lệ; có `visit_id` bị `ck_orders_single_source`; `PAID` không `payment_id` hợp lệ; có `payment_id` (ở `PAID` lẫn `PENDING`) bị `ck_orders_paid_payment`; đơn quầy vẫn `PAID ⇔ payment_id` (hai chiều); `cancel_type` `ONLINE_CUSTOMER`, `ONLINE_EXPIRED`, `UNPAID` hợp lệ, `ONLINE_OTHER` bị `ck_orders_cancel_type`. RED trước khi có V42 (Order `ONLINE` bị `ck_orders_single_source`). Kết quả các nhóm sales, report, boarding, parity, schema sau V42: xem ADR-0113 §7.
