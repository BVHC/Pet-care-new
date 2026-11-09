# Pet Care Ecosystem — Đặc tả bổ sung tầng 3, phần BE-2 (T52)

> **Đặc tả chính thức phần BE-2 · T52 · đã duyệt ở T54 (Hiến, nhóm trưởng, 5/11/2026).** BE-2 (Dũng) đọc khi bắt đầu task tương ứng; muốn đổi thì qua ADR. File này gom quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu / ERD, API và hợp đồng chéo module cho: **UC13, UC27, UC32, UC61–65, UC68, UC69, UC77–79, UC80, UC82 · ST09–ST12, ST14, ST21** (15 UC, 6 ST).
>
> **Vì sao đặt ở file riêng:** Nhân sự (NS) đã viết thẳng vào `02`–`06`; phân hệ AI ở `07`; phần này ở `08` (BE-2) và `09` (BE-1). Lý do: `02`–`06` đang được BE-1, BE-2 sửa song song, nên file riêng không gây xung đột và dễ rà soát. **Đây là nguồn chính thức; `02`–`05` chỉ có mục trỏ tới đây** (không chép trùng quy tắc để hai nơi không lệch nhau); interface chéo gộp ở `06`.
> **Thứ tự các mục theo hạn trong `time.md`:** A (UC32, T62, 16/11) → B (UC61, 62, 64, 65, ST09, ST10, ST12 — T63, T64) → C (UC63, ST11, ST14 — T65) → D (UC68, 69, ST21 — T73) → E (UC77–79 — T74) → F (UC13, 27, 80, 82 — T75).
> **Hợp đồng máy đọc:** [`api/fees-v1.md`](./api/fees-v1.md), [`shop-v1.md`](./api/shop-v1.md), [`payment-v1.md`](./api/payment-v1.md), [`returns-v1.md`](./api/returns-v1.md), [`stockops-v1.md`](./api/stockops-v1.md), [`branchlife-v1.md`](./api/branchlife-v1.md), [`custmerge-v1.md`](./api/custmerge-v1.md), [`articlex-v1.md`](./api/articlex-v1.md).

**Quy ước đánh số (đã chốt ở T54):** mã BR tiếp nối tiền tố hiện có (đã kiểm số lớn nhất trong `02`: BR-SP-07, BR-BH-06, BR-TG-05, BR-KO-07, BR-CN-05, BR-KH-10, BR-BV-04); FSM bắt đầu từ **#14** (#11, #12 là NS, #13 là AI); ST dùng mã sẵn có ở `01`. BE-2 thêm rule mới phải lấy số sau số lớn nhất ở **cả** `02` và file này.

**Quy ước cột (cho migration ở T55):** cột `*_at` là `TIMESTAMPTZ`; `*_date`, `*_on` là `DATE`; `*_by` và `*_id` (trừ khi ghi khác) là `BIGINT` khóa ngoại; `id` là `BIGINT` khóa chính; `quantity`, `*_qty`, `*_count` là `INT`; tiền là `BIGINT` (đồng); trạng thái và loại là `VARCHAR(30)` + `CHECK` (05 §0); `?` sau tên cột là cho phép NULL. Cột liệt kê không ghi kiểu thì theo quy ước này.

**Đặt tên path:** `/orders`, `/payments`, `/articles` đã dành cho nghiệp vụ tại quầy / nội dung hiện có, nên phần mới dùng path riêng (`/me/online-orders`, `/online-orders`, `/return-requests`, `/stock-transfers`, `/me/articles`…), đã grep `docs/api/openapi/*.yaml` để không trùng.

---

## A. Phí cấu hình và định mức vật tư (UC32 · T62)

| Mã | Use case | Actor |
|---|---|---|
| UC32 | Cấu hình phí vận chuyển, định mức vật tư tiêu hao (mở rộng UC30) | A04 |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-SP-08 | Phí vận chuyển | SUPER_MANAGER cấu hình **một bộ chung toàn chuỗi**: phí cơ bản `base_fee` (đ, ≥ 0) và ngưỡng miễn phí `free_threshold` (đ, 0 = không miễn phí). Phí của đơn = 0 nếu tiền hàng ≥ ngưỡng và ngưỡng > 0, ngược lại bằng phí cơ bản. *ASSUMPTION A1:* phí cố định, không theo vùng hay khoảng cách (YAGNI). | UC32, UC62, ST09 | 400 nêu giá trị sai. |
| BR-SP-10 | Định mức vật tư | SUPER_MANAGER khai báo cho mỗi dịch vụ danh sách (sản phẩm, số lượng mỗi lần thực hiện, số nguyên ≥ 1). Sản phẩm phải là `GOODS` hoặc `DRUG`, **không** là vaccine (vaccine đã trừ kho khi tiêm, BR-KO-05); mỗi cặp dịch vụ – sản phẩm tối đa một dòng. ST07 dùng để tự trừ vật tư. Sửa định mức chỉ ảnh hưởng các lần thực hiện sau. | UC32, ST07 | 400 nêu dòng sai. |
| BR-SP-11 | Chốt giá trị | Phí vận chuyển **chốt tại lúc tạo đơn** (cùng tinh thần BR-BH-03); đổi cấu hình không ảnh hưởng đơn đã tạo. Mọi thay đổi cấu hình ghi audit (mở rộng BR-QT-15, như BR-AI-17). | UC32 | — |

### Dữ liệu (05 bổ sung)

- `shipping_fee_configs` — REF, **một dòng duy nhất**: `id`, `base_fee BIGINT NN CHECK ≥ 0`, `free_threshold BIGINT NN CHECK ≥ 0`, `updated_by FK→accounts`. `CHECK (id = 1)`.
- `service_material_norms` — REF: `id`, `service_id FK→services NN`, `product_id FK→products NN`, `quantity INT NN CHECK ≥ 1`, `UNIQUE (service_id, product_id)`; Index `(product_id)` kiểm FK. Ràng buộc loại sản phẩm kiểm trong service (BR-SP-10).

### API (`fees-v1`, 6 endpoint)

`GET`/`PUT /shipping-fee-config` · `GET`/`PUT /services/{serviceId}/material-norms` (PUT ghi đè cả danh sách). Đọc: A04. Phí vận chuyển của một giỏ hàng cụ thể lấy qua `GET /me/online-orders/quote` (mục B).

---

## B. Thương mại điện tử: giỏ hàng, đặt hàng, theo dõi, xử lý và giao (UC61, 62, 64, 65 · ST09, ST10, ST12 · T63, T64)

| Mã | Use case | Actor |
|---|---|---|
| UC61 | Quản lý giỏ hàng | A02 |
| UC62 | Đặt hàng online (chọn địa chỉ, chi nhánh xử lý; ST09 tính phí; gồm UC63) | A02 |
| UC64 | Quản lý đơn hàng của tôi (xem, theo dõi giao hàng, hủy, xác nhận đã nhận) | A02 |
| UC65 | Xử lý & giao đơn online (xử lý, bàn giao, cập nhật trạng thái, giao thất bại) | A06 |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-BH-07 | Giỏ hàng | Chỉ CUSTOMER; mỗi khách **một giỏ lưu ở server**. Chỉ sản phẩm `GOODS` đang kinh doanh và không kê đơn (BR-SP-01); thuốc và vaccine **không bán online** (*ASSUMPTION A2*). Tối đa 30 dòng, mỗi dòng 1–99. Giá trong giỏ chỉ để xem, chốt khi đặt (BR-BH-03). Sản phẩm ngừng kinh doanh tự bị loại khi xem giỏ. | UC61 | 400 `BR-BH-07`. |
| BR-BH-08 | Đặt hàng online | Đặt từ giỏ. Địa chỉ giao chọn từ **sổ địa chỉ của chính khách** (BR-TK-18). Chọn **một chi nhánh xử lý** trong các chi nhánh `ACTIVE` còn đủ hàng cho **mọi** dòng (*ASSUMPTION A3:* không tách đơn giữa chi nhánh). Kiểm tồn khả dụng (BR-KO-01) đã trừ phần đang giữ; ST09 tính phí (BR-SP-08). Thanh toán **chỉ online**, không COD (*ASSUMPTION A4*). Sau khi đặt: giữ hàng tại chi nhánh đó (BR-BH-09), xóa các dòng đã đặt khỏi giỏ, tạo Order nguồn `ONLINE`. | UC62 | 400 `BR-BH-08` nêu dòng thiếu hàng / địa chỉ hoặc chi nhánh không hợp lệ. |
| BR-BH-09 | Giữ hàng và trừ kho | Giữ hàng khi đặt (`stock_reservations`), **nhả** khi hủy hoặc hết hạn thanh toán; khi thanh toán thành công chuyển giữ thành **xuất kho FEFO** (BR-KO-05) trong cùng transaction với `PAID`; hủy sau thanh toán mà chưa bàn giao thì **hoàn hàng về lô gốc**. Tồn khả dụng = tồn trừ phần đang giữ. | UC62, UC64, ST06 | — |
| BR-BH-10 | Theo dõi, hủy, xác nhận | Khách chỉ xem đơn của mình (ADR-0006). Hủy được khi `PENDING_PAYMENT` (nhả giữ hàng) hoặc `PAID` / `PROCESSING` **chưa bàn giao** (hoàn kho + hoàn tiền, BR-TG-12). Xác nhận đã nhận khi `DELIVERED`. Từ `SHIPPED` khách không tự hủy; trả hàng đi theo UC68. | UC64 | 409 `INVALID_STATE_TRANSITION`. |
| BR-BH-11 | Xử lý và giao đơn | Lễ tân **của chi nhánh xử lý** làm: bắt đầu xử lý → bàn giao (bắt buộc đơn vị vận chuyển và mã vận đơn) → cập nhật đã giao hoặc ghi giao thất bại (lý do bắt buộc). Giao lại tối đa 2 lần **[CFG]** `shop.max_redelivery`; hủy sau thất bại thì hoàn hàng về kho và hoàn tiền. Mọi cập nhật thông báo khách qua `NotificationApi` (ST20). | UC65 | 400 / 409. |
| BR-BH-12 | Hạn thanh toán, tự hủy, tự xác nhận | Đơn `PENDING_PAYMENT` quá 24 giờ **[CFG]** `shop.payment_window_hours` bị ST10 hủy (nhả giữ hàng). Đơn `DELIVERED` quá 7 ngày **[CFG]** `shop.auto_confirm_days` mà **không có** yêu cầu trả hàng đang mở được ST12 chuyển `COMPLETED`. Hạn trả hàng cùng 7 ngày kể từ `delivered_at` (BR-BH-13). | ST10, ST12 | — |

**ST09 Tính phí vận chuyển:** chạy khi báo giá và khi đặt đơn theo BR-SP-08; kết quả chốt vào `online_orders.shipping_fee`. **ST10 Hủy đơn chưa thanh toán** và **ST12 Tự xác nhận đã nhận hàng:** tác vụ định kỳ theo ADR-0007, mỗi 15 phút / mỗi giờ; mỗi lượt khóa đơn rồi mới đổi trạng thái (BR-KO khóa trước khi đọc, ADR-0106).

### Máy trạng thái #14 — Đơn hàng online

`online_orders.status` · BH · Tầng 3. Order tương ứng (`orders.source = 'ONLINE'`) đi theo bảng bổ sung #14b bên dưới.

| Trạng thái | Loại | Ý nghĩa | Ràng buộc khi ở trạng thái này |
|---|---|---|---|
| `PENDING_PAYMENT` | INIT | Đã đặt, chờ thanh toán | Đang giữ hàng; có `payment_due_at` |
| `PAID` | MID | Đã thanh toán | Kho đã trừ FEFO; chờ xử lý |
| `PROCESSING` | MID | Chi nhánh đang chuẩn bị hàng | — |
| `SHIPPED` | MID | Đã bàn giao đơn vị vận chuyển | Có `carrier`, `tracking_code` |
| `DELIVERY_FAILED` | MID | Giao thất bại | Có lý do; chờ giao lại hoặc hủy |
| `DELIVERED` | MID | Đã giao | Có `delivered_at`; trong hạn trả hàng |
| `COMPLETED` | FINAL | Khách đã nhận (xác nhận hoặc tự xác nhận) | — |
| `CANCELLED` | FINAL | Đã hủy | Có `cancel_reason` |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `PENDING_PAYMENT` | Đặt hàng | A02 | Giỏ không rỗng; địa chỉ của khách; chi nhánh `ACTIVE` đủ hàng mọi dòng | Giữ hàng; tạo Order `ONLINE` `PENDING` (Order#11); ST09 chốt phí; dọn giỏ | BR-BH-08, 09 · UC62 |
| 2 | `PENDING_PAYMENT` → `PAID` | Thanh toán thành công | SYS ← Giao dịch#2 | Số tiền khớp (BR-TG-08) | Xuất kho FEFO từ phần giữ; Order → `PAID` (Order#12); thông báo | BR-BH-09 · UC63 |
| 3 | `PENDING_PAYMENT` → `CANCELLED` | Khách hủy | A02 | Chưa có giao dịch `SUCCEEDED` | Nhả giữ hàng; hủy giao dịch `PENDING` (Giao dịch#5); Order → `CANCELLED` (Order#13) | BR-BH-10 · UC64 |
| 4 | `PENDING_PAYMENT` → `CANCELLED` | Quá hạn thanh toán | ST10 | Quá `payment_due_at` | Như #3, lý do hệ thống | BR-BH-12 |
| 5 | `PAID` → `PROCESSING` | Bắt đầu xử lý | A06 (chi nhánh xử lý) | — | — | BR-BH-11 · UC65 |
| 6 | `PROCESSING` → `SHIPPED` | Bàn giao vận chuyển | A06 | Có đơn vị vận chuyển và mã vận đơn | Thông báo khách | BR-BH-11 · UC65 |
| 7 | `SHIPPED` → `DELIVERED` | Cập nhật đã giao | A06 | — | `delivered_at` = bây giờ | BR-BH-11 · UC65 |
| 8 | `SHIPPED` → `DELIVERY_FAILED` | Giao thất bại | A06 | Có lý do | Thông báo khách | BR-BH-11 · UC65 |
| 9 | `DELIVERY_FAILED` → `SHIPPED` | Giao lại | A06 | `redelivery_count` < tối đa [CFG] | `redelivery_count` + 1 | BR-BH-11 |
| 10 | `DELIVERY_FAILED` → `CANCELLED` | Hủy sau giao thất bại | A06 | Có lý do | Hoàn hàng về lô gốc; sinh hoàn tiền (Hoàn tiền#1) | BR-BH-11, BR-TG-12 |
| 11 | `DELIVERED` → `COMPLETED` | Khách xác nhận đã nhận | A02 | — | — | BR-BH-10 · UC64 |
| 12 | `DELIVERED` → `COMPLETED` | Tự xác nhận | ST12 | Quá hạn, không có yêu cầu trả hàng đang mở | — | BR-BH-12 |
| 13 | `PAID` / `PROCESSING` → `CANCELLED` | Khách hủy trước bàn giao | A02 | Chưa `SHIPPED` | Hoàn hàng về lô gốc; sinh hoàn tiền (Hoàn tiền#1); thông báo | BR-BH-10 · UC64 |

**#14b — Order nguồn `ONLINE`** (bổ sung cho `03` #5): Order#11 — → `PENDING` (SYS ← Đơn hàng online#1); Order#12 `PENDING` → `PAID` (SYS ← Đơn hàng online#2, thu qua cổng, không qua ca thu ngân); Order#13 `PENDING` → `CANCELLED` (SYS ← Đơn hàng online#3, #4). Hủy sau khi `PAID` **không** đổi Order (vẫn `PAID`); hoàn tiền ghi ở `refunds` (mục D). *ASSUMPTION A5:* báo cáo doanh thu (BC) trừ hoàn tiền; BE-2 xác nhận cách tính (Q4).

### Dữ liệu (05 bổ sung)

- `carts` — ROOT: `id`, `customer_id FK→customers UQ NN`.
- `cart_items` — PART: `cart_id FK`, `product_id FK`, `quantity SMALLINT NN CHECK 1–99`, `UNIQUE (cart_id, product_id)`.
- `online_orders` — ROOT · SM #14: `id`, `code VARCHAR(20) UQ` (`DH-yyMMdd-xxxx`, cùng cơ chế mã chứng từ ADR-0043), `order_id FK→orders UQ NN`, `customer_id FK NN`, `branch_id FK NN`, `status VARCHAR(30) NN CHECK`, `receiver_name VARCHAR(100)`, `receiver_phone VARCHAR(15)`, `ship_address VARCHAR(300)` (**snapshot** từ sổ địa chỉ), `shipping_fee BIGINT NN CHECK ≥ 0`, `carrier VARCHAR(100)?`, `tracking_code VARCHAR(100)?`, `redelivery_count SMALLINT NN DEFAULT 0`, `payment_due_at TIMESTAMPTZ NN`, `paid_at`, `shipped_at`, `delivered_at`, `completed_at`, `cancelled_at TIMESTAMPTZ?`, `cancel_reason VARCHAR(300)?`, `cancelled_by FK→accounts?` (NULL = hệ thống). Ràng buộc: `CHECK` thời điểm ↔ trạng thái (cùng khuôn `care_tasks`, ADR-0081); `SHIPPED` có `carrier` và `tracking_code`. Index: `(branch_id, status, payment_due_at)`; `(customer_id, created_at DESC)`; `(status, payment_due_at) WHERE status = 'PENDING_PAYMENT'` cho ST10; `(delivered_at) WHERE status = 'DELIVERED'` cho ST12.
- `stock_reservations` — PART: `id`, `online_order_id FK`, `branch_id FK`, `product_id FK`, `quantity INT NN CHECK > 0`, `status VARCHAR(30) CHECK ('HELD','CONSUMED','RELEASED')`, `UNIQUE (online_order_id, product_id)`; Index `(branch_id, product_id) WHERE status = 'HELD'` (tính tồn khả dụng).
- `orders.source` thêm giá trị `ONLINE` (05 §8); `order_lines` giữ snapshot giá (BR-BH-03).

### API

- **`shop-v1`, 19 endpoint.** Giỏ (A02): `GET /me/cart`, `PUT /me/cart/items/{productId}` (đặt số lượng 1–99), `DELETE /me/cart/items/{productId}`, `DELETE /me/cart`. Đặt hàng (A02): `GET /me/online-orders/fulfillment-branches`, `GET /me/online-orders/quote` (`branchId`, `addressId` → tiền hàng, phí, tổng), `POST /me/online-orders`. Theo dõi (A02): `GET /me/online-orders`, `GET /me/online-orders/{onlineOrderId}`, `POST /me/online-orders/{onlineOrderId}/cancel`, `POST /me/online-orders/{onlineOrderId}/confirm-received`. Xử lý (A06, chi nhánh của mình; A05 xem): `GET /online-orders`, `GET /online-orders/{onlineOrderId}`, `POST /online-orders/{onlineOrderId}/start-processing`, `…/ship`, `…/mark-delivered`, `…/fail-delivery`, `…/redeliver`, `…/cancel-failed`.
- Mỗi chuyển trạng thái do người kích hoạt có đúng một endpoint; chuyển do hệ thống (#2, #4, #12) không có.

### Hợp đồng chéo (cho T55)

| Interface | Owner | Dùng cho |
|---|---|---|
| `StockApi.reserve(branchId, lines)`, `release(orderRef)`, `consumeReservation(orderRef)` (xuất FEFO từ phần giữ), `restock(orderRef)` (hoàn về lô gốc) | BE-2 (inventory) | BR-BH-09, #2, #3, #10, #13 |
| `StockQueryApi.findAvailableQuantity(branchId, productIds)` (đã trừ phần giữ) | BE-2 (inventory) | BR-BH-08, giỏ hàng |
| `CustomerQueryApi.findAddress(customerId, addressId)` | BE-2 (customer) | BR-BH-08 (snapshot địa chỉ) |
| Thông báo: mã mẫu `ONLINE_ORDER_*` (đã đặt, đã thanh toán, bàn giao, giao thất bại, đã hủy) | BE-1 (identity/care) | `06` §8 Q4 |

### Giả định

A1 phí cố định; A2 không bán thuốc / vaccine online; A3 một chi nhánh xử lý cho mỗi đơn, không tách; A4 không COD; A5 hoàn tiền trừ vào doanh thu ở báo cáo.

---

## C. Thanh toán online (UC63 · ST11, ST14 · T65)

| Mã | Use case | Actor |
|---|---|---|
| UC63 | Thanh toán online qua cổng thanh toán (S02) | A02, S02 |

**Cổng trung lập.** Hệ thống làm việc với cổng qua interface `PaymentGatewayClient`: tạo phiên thanh toán (trả địa chỉ chuyển hướng), xác thực chữ ký callback, truy vấn giao dịch, hoàn tiền, lấy báo cáo đối soát. **Cổng chọn: VNPay** (đề xuất đã chốt ở T54; ZaloPay, MoMo vẫn thay được vì cổng đứng sau interface). **Đăng ký sandbox là việc của Hiến**, hướng dẫn ở [`payment-gateway-setup.md`](./payment-gateway-setup.md); đặc tả không viết chi tiết API của cổng nào.

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-TG-06 | Giao dịch thanh toán online | Mỗi đơn online có tối đa **một** giao dịch `PENDING` tại một thời điểm; thử lại bằng giao dịch mới khi giao dịch trước `FAILED` / `EXPIRED`, miễn đơn còn `PENDING_PAYMENT` chưa quá hạn (BR-BH-12). Số tiền giao dịch = tiền hàng + phí vận chuyển của đơn (BR-SP-08). | UC63 | 409 nêu giao dịch đang chờ. |
| BR-TG-07 | Callback từ cổng | Endpoint callback **công khai** (không token, ADR-0005) nhưng **chỉ xử lý khi chữ ký hợp lệ**; xử lý **idempotent** theo mã tham chiếu giao dịch của hệ thống (gọi lại cùng kết quả không đổi gì). Chữ ký sai trả 400, không ghi gì ngoài log. Khóa bí mật và địa chỉ cổng ở biến môi trường, không vào CSDL, log hay audit (như BR-AI-25). | UC63 | 400. |
| BR-TG-08 | Số tiền khớp | Số tiền cổng báo phải bằng số tiền giao dịch. Lệch → giao dịch `FAILED` và đưa vào danh sách đối soát để xử lý tay; **không** tự đánh dấu đơn đã trả. | UC63 | — |
| BR-TG-09 | Hết hạn giao dịch (ST11) | Giao dịch `PENDING` quá 15 phút **[CFG]** `payment.txn_ttl_minutes`: trước khi chuyển `EXPIRED`, ST11 **hỏi cổng** trạng thái giao dịch; cổng báo đã thành công thì xử lý như thành công (Giao dịch#2), ngược lại `EXPIRED`. Đơn vẫn `PENDING_PAYMENT` để khách thử lại tới `payment_due_at`. | ST11 | — |
| BR-TG-10 | Đối soát cổng (ST14) | Hằng ngày sau 02:00 (giờ Việt Nam), lấy báo cáo giao dịch hôm trước của cổng, so với `payment_transactions` theo mã tham chiếu; lệch (thiếu bên hệ thống, thiếu bên cổng, lệch số tiền, lệch trạng thái) ghi `reconciliation_items`, **không tự sửa**. SUPER_MANAGER xem và ghi chú đã xử lý. | ST14 | — |
| BR-TG-11 | Cổng trung lập | Hệ thống chỉ làm việc với cổng qua interface `PaymentGatewayClient` (tạo phiên, xác thực chữ ký callback, truy vấn giao dịch, hoàn tiền, báo cáo đối soát); mọi tham số riêng của cổng được ánh xạ về dạng chung ở lớp tích hợp. Khóa bí mật, địa chỉ cổng ở biến môi trường `PAYMENT_GATEWAY`, `PAYMENT_MERCHANT_CODE`, `PAYMENT_SECRET`, `PAYMENT_BASE_URL`, `PAYMENT_RETURN_URL`, `PAYMENT_IPN_URL` (BR-TG-07; không ghi vào repo). Cổng chọn VNPay, đăng ký sandbox là việc của Hiến (Q1). Giao dịch dùng cổng nào ghi vào `payment_transactions.gateway`. | UC63, ST11, ST14, ST21 | — |

### Máy trạng thái #15 — Giao dịch thanh toán online

`payment_transactions.status` · TG · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `PENDING` | INIT | Đã tạo phiên ở cổng, chờ kết quả; có `expires_at` |
| `SUCCEEDED` | FINAL | Cổng xác nhận thành công, số tiền khớp |
| `FAILED` | FINAL | Cổng báo thất bại, khách hủy tại cổng hoặc số tiền lệch |
| `EXPIRED` | FINAL | Quá hạn giao dịch (ST11) |
| `CANCELLED` | FINAL | Đơn bị hủy khi giao dịch còn chờ |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `PENDING` | Bắt đầu thanh toán | A02 | Đơn `PENDING_PAYMENT` chưa quá hạn; không có giao dịch `PENDING` khác | Tạo phiên ở cổng; trả địa chỉ chuyển hướng; `expires_at` = bây giờ + 15 phút | BR-TG-06 · UC63 |
| 2 | `PENDING` → `SUCCEEDED` | Cổng báo thành công | SYS ← callback hợp lệ, hoặc ST11 hỏi cổng | Chữ ký hợp lệ; số tiền khớp | Đơn hàng online#2 (`PAID`, xuất kho FEFO) | BR-TG-07, 08, 09 |
| 3 | `PENDING` → `FAILED` | Cổng báo thất bại / lệch tiền | SYS ← callback hợp lệ | — | Lệch tiền: thêm mục đối soát | BR-TG-07, 08 |
| 4 | `PENDING` → `EXPIRED` | Hết hạn | ST11 | Quá `expires_at` và cổng không báo thành công | — | BR-TG-09 |
| 5 | `PENDING` → `CANCELLED` | Đơn bị hủy | SYS ← Đơn hàng online#3, #4 | — | Hủy phiên ở cổng nếu cổng hỗ trợ | BR-BH-10, 12 |

### Dữ liệu (05 bổ sung)

- `payment_transactions` — ROOT · SM #15: `id`, `code VARCHAR(40) UQ NN` (mã tham chiếu gửi cổng), `online_order_id FK NN`, `gateway VARCHAR(20) NN` (tên cổng, ví dụ `VNPAY`), `amount BIGINT NN CHECK > 0`, `status VARCHAR(30) NN CHECK`, `gateway_txn_id VARCHAR(100)?`, `redirect_url VARCHAR(1000)?`, `expires_at TIMESTAMPTZ NN`, `paid_at?`, `failure_reason VARCHAR(300)?`. `UNIQUE (online_order_id) WHERE status = 'PENDING'` (BR-TG-06); `UNIQUE (gateway, gateway_txn_id) WHERE gateway_txn_id IS NOT NULL`; Index `(status, expires_at) WHERE status = 'PENDING'` cho ST11.
- `payment_reconciliations` — ROOT: `id`, `recon_date DATE UQ NN`, `status VARCHAR(30) CHECK ('RUNNING','MATCHED','MISMATCHED','FAILED')`, `txn_count INT`, `mismatch_count INT`, `finished_at?`.
- `reconciliation_items` — PART: `id`, `reconciliation_id FK`, `kind VARCHAR(30) CHECK ('MISSING_LOCAL','MISSING_GATEWAY','AMOUNT_DIFF','STATUS_DIFF')`, `transaction_id FK?`, `gateway_ref VARCHAR(100)?`, `our_amount BIGINT?`, `gateway_amount BIGINT?`, `resolved_by FK→accounts?`, `resolved_note VARCHAR(300)?`, `resolved_at?`.

### API (`payment-v1`, 6 endpoint)

`POST /me/online-orders/{onlineOrderId}/pay` (A02, tạo giao dịch, trả `redirectUrl`) · `GET /me/payment-transactions/{transactionId}` (A02, trang kết quả hỏi trạng thái) · `POST /public/payment-gateway/callback` (công khai, kiểm chữ ký) · `GET /payment-reconciliations` (A04) · `GET /payment-reconciliations/{reconciliationId}/items` (A04) · `POST /payment-reconciliation-items/{itemId}/resolve` (A04, ghi chú).

### Hợp đồng chéo

`OnlineOrderApi.markPaid(orderId, transactionCode)` và `markPaymentCancelled(orderId)` (shop, cùng BE-2); mã mẫu thông báo `PAYMENT_*` (`06` §8 Q4). Giả định cổng chỉ cần **một** loại tích hợp ở GĐ2.

**Câu hỏi:** Q1 chọn cổng (đã chốt VNPay) và đăng ký sandbox (Hiến, còn mở); Q2 có cần danh sách IP cổng được phép gọi callback không; Q3 cổng có cho hủy phiên chưa thanh toán không (ảnh hưởng #5).

---

## D. Trả hàng và hoàn tiền (UC68, 69 · ST21 · T73)

| Mã | Use case | Actor |
|---|---|---|
| UC68 | Tạo yêu cầu trả hàng (cho Order tại quầy hoặc đơn online) | A06 |
| UC69 | Duyệt yêu cầu trả hàng; hoàn tiền (ST21 với đơn online) | A05 |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-BH-13 | Điều kiện trả hàng | Chỉ Order `PAID` (tại quầy) hoặc đơn online đã `DELIVERED` / `COMPLETED`; trong **7 ngày** **[CFG]** `returns.window_days` kể từ lúc thanh toán (quầy) hoặc `delivered_at` (online); chỉ dòng `GOODS` không kê đơn — **không** trả dịch vụ, thuốc, vaccine; số lượng ≤ đã mua trừ phần đã / đang trả; mỗi yêu cầu có ≥ 1 dòng và lý do (`DEFECTIVE`, `WRONG_ITEM`, `CHANGED_MIND`, `OTHER`). Lễ tân tạo hộ khách, ở chi nhánh của Order. | UC68 | 400 `BR-BH-13`. |
| BR-BH-14 | Duyệt và từ chối | BRANCH_MANAGER của chi nhánh của Order duyệt hoặc từ chối (từ chối bắt buộc lý do); lễ tân rút được khi `PENDING`. Khi duyệt chọn `restock`: **nhập lại kho vào lô gốc** hoặc **loại bỏ** (điều chỉnh giảm, lý do `RETURN_DISCARD`). Tiền hoàn = Σ(đơn giá snapshot × số lượng); **phí vận chuyển không hoàn** khi trả hàng (*ASSUMPTION A6*). | UC69 | 409 `INVALID_STATE_TRANSITION`. |
| BR-BH-15 | Trả hàng không đổi trạng thái đơn | Duyệt trả hàng không đổi trạng thái Order hay đơn online (hoàn tiền một phần); đơn online có yêu cầu trả hàng đang mở thì ST12 không tự xác nhận (BR-BH-12). | UC68, UC69 | — |
| BR-TG-12 | Khi nào sinh hoàn tiền | Tự sinh trong hai trường hợp: (a) yêu cầu trả hàng được duyệt (số tiền theo BR-BH-14); (b) hủy đơn online đã `PAID` (Đơn hàng online#10, #13), hoàn **toàn bộ** tiền đã trả gồm phí vận chuyển. Hình thức hoàn = hình thức đã thu: đơn online → `GATEWAY`; tại quầy → `CASH` hoặc `TRANSFER` theo `payments.method`. | UC64, UC69 | — |
| BR-TG-13 | Hoàn tiền tại quầy | Lễ tân xác nhận đã hoàn trong **ca `OPEN` của chính mình** ở chi nhánh của Order (BR-TG-01); số hoàn tính vào ca (đối soát ca trừ hoàn tiền, UC72); ghi audit (BR-QT-15 đã có "giao dịch tiền"). | UC69 | 400 `BR-TG-01` nếu không có ca mở. |
| BR-TG-14 | Hoàn tiền qua cổng (ST21) | ST21 gọi cổng hoàn tiền cho `refunds` `GATEWAY` ở `REQUESTED`, **idempotent** theo mã tham chiếu hoàn tiền; lỗi tạm thời thử lại tối đa 3 lần **[CFG]** `payment.refund_max_attempts` cách 15 phút; hết lần → `FAILED`, BRANCH_MANAGER thử lại (`retry`) hoặc xử lý ngoài hệ thống kèm ghi chú. | ST21 | — |

### Máy trạng thái #16 — Yêu cầu trả hàng

`return_requests.status` · BH · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `PENDING` | INIT | Lễ tân đã tạo, chờ quản lý chi nhánh |
| `APPROVED` | MID | Đã duyệt, đang hoàn tiền |
| `REJECTED` | FINAL | Bị từ chối (có lý do) |
| `CANCELLED` | FINAL | Lễ tân rút |
| `REFUNDED` | FINAL | Đã hoàn tiền xong |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `PENDING` | Tạo yêu cầu | A06 | BR-BH-13 | Thông báo BRANCH_MANAGER | UC68 |
| 2 | `PENDING` → `CANCELLED` | Rút yêu cầu | A06 (người tạo) | — | — | BR-BH-14 |
| 3 | `PENDING` → `APPROVED` | Duyệt | A05 | Chọn `restock` | Nhập lại lô gốc hoặc điều chỉnh giảm; sinh Hoàn tiền#1; thông báo; ghi audit | BR-BH-14, BR-TG-12 · UC69 |
| 4 | `PENDING` → `REJECTED` | Từ chối | A05 | Có lý do | Thông báo | BR-BH-14 · UC69 |
| 5 | `APPROVED` → `REFUNDED` | Hoàn tiền xong | SYS ← Hoàn tiền#2 | — | — | BR-TG-12 |

### Máy trạng thái #17 — Hoàn tiền

`refunds.status` · TG · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `REQUESTED` | INIT | Chờ hoàn (cổng chưa xử lý, hoặc lễ tân chưa xác nhận tại quầy) |
| `SUCCEEDED` | FINAL | Đã hoàn |
| `FAILED` | MID | Cổng không hoàn được sau số lần thử tối đa; chờ xử lý tay |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `REQUESTED` | Sinh hoàn tiền | SYS ← Yêu cầu trả hàng#3, Đơn hàng online#10, #13 | — | Thông báo khách | BR-TG-12 |
| 2 | `REQUESTED` → `SUCCEEDED` | Hoàn xong | ST21 (cổng xác nhận) hoặc A06 (tiền mặt / chuyển khoản tại quầy) | `GATEWAY`: cổng xác nhận; quầy: ca `OPEN` của lễ tân | Quầy: ghi vào ca. Yêu cầu trả hàng → `REFUNDED` (Yêu cầu#5) | BR-TG-13, 14 |
| 3 | `REQUESTED` → `FAILED` | Hết lần thử | ST21 | `attempts` ≥ tối đa [CFG] | Cảnh báo BRANCH_MANAGER | BR-TG-14 |
| 4 | `FAILED` → `REQUESTED` | Thử lại | A05 | Chỉ `GATEWAY` | `attempts` về 0 | BR-TG-14 |

### Dữ liệu (05 bổ sung)

- `return_requests` — ROOT · SM #16: `id`, `code` (`TH-yyMMdd-xxxx`), `order_id FK→orders NN`, `branch_id FK NN`, `status`, `reason_code VARCHAR(30) NN CHECK`, `reason_note VARCHAR(300)?`, `created_by FK NN`, `decided_by FK?`, `decision_note VARCHAR(300)?`, `decided_at?`, `restock BOOLEAN?`, `refund_amount BIGINT?`. `CHECK` quyết định ↔ trạng thái; Index `(branch_id, status, created_at)`.
- `return_request_lines` — PART: `request_id FK`, `order_line_id FK`, `quantity INT NN CHECK > 0`, `UNIQUE (request_id, order_line_id)`.
- `refunds` — ROOT · SM #17: `id`, `code` (`HT-yyMMdd-xxxx`), `return_request_id FK?`, `online_order_id FK?` với `CHECK ((return_request_id IS NULL) <> (online_order_id IS NULL))`, `branch_id FK NN`, `method VARCHAR(20) CHECK ('CASH','TRANSFER','GATEWAY')`, `amount BIGINT NN CHECK > 0`, `status`, `attempts SMALLINT NN DEFAULT 0`, `next_attempt_at?`, `gateway_refund_id VARCHAR(100)?`, `failure_reason VARCHAR(300)?`, `confirmed_by FK?`, `cashier_shift_id FK?`, `completed_at?`. `CHECK (method = 'GATEWAY' OR status <> 'SUCCEEDED' OR cashier_shift_id IS NOT NULL)`; Index `(status, next_attempt_at) WHERE method = 'GATEWAY' AND status = 'REQUESTED'` cho ST21.

### API (`returns-v1`, 9 endpoint)

**Lưu ý ID:** `orderId` trong `POST /return-requests` là `orders.id` (Order bán hàng); với đơn online dùng `salesOrderId` của `OnlineOrder` (shop-v1), **không** dùng `onlineOrderId` (`online_orders.id`) để tránh khớp nhầm với một Order tại quầy. Yêu cầu trả hàng: `POST /return-requests` (A06), `GET /return-requests` (A05, A06 — chi nhánh của mình), `GET /return-requests/{requestId}`, `POST …/cancel` (A06), `POST …/approve` (A05, `restock`), `POST …/reject` (A05, lý do). Hoàn tiền: `GET /refunds` (A05, A06), `POST /refunds/{refundId}/confirm` (A06, tiền mặt / chuyển khoản tại quầy), `POST /refunds/{refundId}/retry` (A05, cổng `FAILED`).

### Hợp đồng chéo

| Interface | Owner | Dùng cho |
|---|---|---|
| `StockApi.restockToOriginalLot(orderLineId, quantity)`, `StockApi.discardReturned(orderLineId, quantity)` | BE-2 (inventory) | BR-BH-14 |
| `OrderQueryApi.findLinesForReturn(orderId)` (dòng, đã trả / đang trả, hình thức thu) | BE-2 (sales) | BR-BH-13 |
| `CashierShiftApi.recordRefund(shiftId, method, amount)`; đối soát ca trừ hoàn tiền | BE-2 (sales) | BR-TG-13, UC72 |
| `PaymentGatewayClient.refund(...)` | BE-2 (payment) | ST21 |
| Điều chỉnh tồn lý do mới `RETURN_DISCARD` | BE-2 (inventory) | BR-BH-14 |

**Giả định:** A6 phí vận chuyển không hoàn khi trả hàng. **Câu hỏi:** Q4 khách có xem được trạng thái hoàn tiền của đơn không (đề xuất có, thêm trường `refundStatus` vào `OnlineOrder`); Q5 hoàn chuyển khoản tại quầy có cần ghi mã giao dịch ngân hàng không.

---

## E. Kho mở rộng: chuyển kho, kiểm kê, truy vết lô (UC77–79 · T74)

| Mã | Use case | Actor |
|---|---|---|
| UC77 | Chuyển kho (tạo, hủy, xác nhận nhận hàng) | A05 |
| UC78 | Kiểm kê kho (kho của chi nhánh mình) | A05 |
| UC79 | Truy vết lô hàng | A05, A07 |

### Quy tắc

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-KO-08 | Tạo phiếu chuyển kho | BRANCH_MANAGER của chi nhánh **nguồn** tạo phiếu tới một chi nhánh đích `ACTIVE` khác. Mỗi dòng: sản phẩm, số lượng ≤ tồn khả dụng của nguồn (BR-KO-01). Hệ thống **trừ ngay khỏi lô nguồn theo FEFO** và ghi lô cụ thể (số lô, hạn dùng) vào dòng; phiếu `IN_TRANSIT`. Mã chứng từ `CK-yyMMdd-xxxx` (BR-KO-03, ADR-0043). | UC77 | 400 `BR-KO-08`. |
| BR-KO-09 | Nhận hàng | BRANCH_MANAGER chi nhánh **đích** xác nhận nhận: hệ thống tạo lô ở đích với đúng số lô, hạn dùng, số lượng; ghi `stock_movements` hai phía. Nhận **đủ**, không nhận lệch (*ASSUMPTION A1*: lệch xử lý bằng điều chỉnh tồn UC76). | UC77 | 409 `INVALID_STATE_TRANSITION`. |
| BR-KO-10 | Hủy chuyển kho | Chi nhánh nguồn **hoặc** đích hủy khi `IN_TRANSIT` (lý do bắt buộc): hoàn đúng lô nguồn. Không hủy sau `RECEIVED`. | UC77 | 409. |
| BR-KO-11 | Phạm vi phiếu | A05 thấy phiếu mà chi nhánh mình là nguồn hoặc đích (BR-QT-03 mở rộng cho hai chi nhánh); chỉ nguồn tạo, chỉ đích nhận, cả hai hủy được. | UC77 | 403 / 404. |
| BR-KO-12 | Kiểm kê | BRANCH_MANAGER mở **một phiên** `OPEN` mỗi chi nhánh, toàn kho hoặc theo một danh mục sản phẩm; nhập số đếm theo từng lô; xác nhận → với mỗi lô có số đếm lệch tồn hệ thống **lúc xác nhận**, tự tạo điều chỉnh (UC76, lý do lệch kiểm đếm `COUNT_DIFF`) bằng (số đếm − tồn hệ thống). Lô chưa nhập số đếm coi là không đếm, không điều chỉnh. *ASSUMPTION A2:* không khóa nhập / xuất trong lúc đếm; người đếm chịu trách nhiệm; phiên quá 24 giờ chỉ còn xác nhận hoặc hủy. | UC78 | 400 / 409. |
| BR-KO-13 | Truy vết lô | BRANCH_MANAGER (lô của chi nhánh mình) và VET xem **đường đi** của một lô: nhập (phiếu nhập / chuyển kho) → xuất (dòng Order; với vaccine và thuốc kê đơn: thú, lượt khám, mũi tiêm) → điều chỉnh → tồn còn lại, để truy hồi lô lỗi. Tìm lô theo số lô hoặc sản phẩm. Dữ liệu khách / thú chỉ hiện với lô thuốc, vaccine (*ASSUMPTION A3*); mọi lượt xem ghi audit (mở rộng BR-QT-15). | UC79 | 403 / 404. |

### Máy trạng thái #18 — Phiếu chuyển kho

`stock_transfers.status` · KO · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `IN_TRANSIT` | INIT | Đã trừ kho nguồn, hàng đang chuyển |
| `RECEIVED` | FINAL | Đích đã nhận, lô đã tạo ở đích |
| `CANCELLED` | FINAL | Đã hủy, hàng hoàn về lô nguồn |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `IN_TRANSIT` | Tạo phiếu | A05 (chi nhánh nguồn) | Đích `ACTIVE` khác nguồn; đủ tồn khả dụng | Trừ FEFO lô nguồn, ghi lô vào dòng; `stock_movements` `TRANSFER_OUT` | BR-KO-08 · UC77 |
| 2 | `IN_TRANSIT` → `RECEIVED` | Xác nhận nhận | A05 (chi nhánh đích) | — | Tạo lô ở đích; `TRANSFER_IN` | BR-KO-09 · UC77 |
| 3 | `IN_TRANSIT` → `CANCELLED` | Hủy | A05 (nguồn hoặc đích) | Có lý do | Hoàn đúng lô nguồn; `TRANSFER_CANCEL` | BR-KO-10 · UC77 |

### Máy trạng thái #19 — Phiên kiểm kê

`stock_counts.status` · KO · Tầng 3.

| Trạng thái | Loại | Ý nghĩa |
|---|---|---|
| `OPEN` | INIT | Đang đếm, nhập số đếm theo lô |
| `CONFIRMED` | FINAL | Đã xác nhận, điều chỉnh đã tạo |
| `CANCELLED` | FINAL | Đã hủy, không điều chỉnh |

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `OPEN` | Mở phiên | A05 | Chi nhánh chưa có phiên `OPEN` | — | BR-KO-12 · UC78 |
| 2 | `OPEN` → `CONFIRMED` | Xác nhận | A05 | Có ≥ 1 lô đã nhập số đếm | Tạo điều chỉnh `COUNT_DIFF` cho lô lệch; ghi audit | BR-KO-12 · UC78 |
| 3 | `OPEN` → `CANCELLED` | Hủy | A05 | Có lý do | — | BR-KO-12 · UC78 |

### Dữ liệu (05 bổ sung)

- `stock_transfers` — ROOT · SM #18: `id`, `code VARCHAR(20) UQ`, `source_branch_id FK`, `dest_branch_id FK` (`CHECK (source_branch_id <> dest_branch_id)`), `status`, `created_by FK`, `received_by FK?`, `received_at?`, `cancelled_by FK?`, `cancel_reason VARCHAR(300)?`, `cancelled_at?`. Index `(source_branch_id, status)`, `(dest_branch_id, status)`.
- `stock_transfer_lines` — PART: `transfer_id FK`, `product_id FK`, `source_lot_id FK→stock_lots`, `quantity INT NN CHECK > 0`, `lot_number VARCHAR(50)?`, `expiry_date DATE?`, `dest_lot_id FK→stock_lots?`.
- `stock_counts` — ROOT · SM #19: `id`, `code VARCHAR(20) UQ`, `branch_id FK`, `status`, `scope VARCHAR(20) CHECK ('ALL','CATEGORY')`, `category_id FK?`, `created_by FK`, `confirmed_by FK?`, `confirmed_at?`, `cancel_reason VARCHAR(300)?`. `UNIQUE (branch_id) WHERE status = 'OPEN'` (BR-KO-12).
- `stock_count_lines` — PART: `count_id FK`, `stock_lot_id FK`, `counted_qty INT NN CHECK ≥ 0`, `system_qty_at_confirm INT?`, `adjustment_id FK→stock_adjustments?`, `UNIQUE (count_id, stock_lot_id)`.
- `stock_movements.type` thêm `TRANSFER_OUT`, `TRANSFER_IN`, `TRANSFER_CANCEL` (05 §9); `stock_adjustments.reason` thêm `COUNT_DIFF`. Mã chứng từ thêm tiền tố `CK`, `KK` (`document_code_sequences`, ADR-0043).

### API (`stockops-v1`, 13 endpoint)

Chuyển kho: `POST /stock-transfers`, `GET /stock-transfers` (lọc `direction` = `OUTGOING` / `INCOMING`, `status`), `GET /stock-transfers/{transferId}`, `POST …/receive`, `POST …/cancel`. Kiểm kê: `POST /stock-counts`, `GET /stock-counts`, `GET /stock-counts/{countId}`, `PUT /stock-counts/{countId}/lines` (lưu số đếm), `POST …/confirm`, `POST …/cancel`. Truy vết: `GET /stock-lots` (tìm theo `lotNumber`, `productId`), `GET /stock-lots/{lotId}/trace`.

### Hợp đồng chéo

| Interface | Owner | Dùng cho |
|---|---|---|
| `StockApi.transferOut/transferIn/transferCancel` (nội bộ BE-2) | BE-2 (inventory) | #1–#3 |
| `VaccinationQueryApi.findByStockLot(lotId)` (mũi tiêm dùng lô: thú, lượt, ngày) | BE-1 (visit) | BR-KO-13 |
| `StockQueryApi.findMovementsOfLot(lotId)` | BE-2 (inventory) | BR-KO-13 |
| `PetQueryApi.findPets`, `CustomerQueryApi.findContacts` (sẵn có) | BE-2 | tên thú / chủ trong truy vết |

**Giả định:** A1 nhận đủ, không nhận lệch; A2 không khóa xuất nhập khi kiểm kê; A3 truy vết chỉ lộ thú / khách cho lô thuốc, vaccine. **Câu hỏi:** Q1 phiếu `IN_TRANSIT` quá bao nhiêu ngày thì cảnh báo (đề xuất 7); Q2 VET có được xem tên khách trong truy vết không hay chỉ mã thú.

---

## F. Chi nhánh, khách, bài viết mở rộng (UC13, 27, 80, 82 · T75)

### F.1 Tạm ngừng / đóng cửa chi nhánh (UC13)

| Mã | Use case | Actor |
|---|---|---|
| UC13 | Tạm ngừng / đóng cửa chi nhánh (gồm chuyển giao tồn đọng) | A04 |

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-CN-06 | Trạng thái mở rộng | `branches.status` thêm `SUSPENDED` (tạm ngừng, mở lại được) và `CLOSED` (đóng vĩnh viễn, trạng thái cuối). Chỉ SUPER_MANAGER; chỉ từ `ACTIVE` hoặc `SUSPENDED`; chi nhánh `DRAFT` không tạm ngừng / đóng. | UC13 | 409 `INVALID_STATE_TRANSITION`. |
| BR-CN-07 | Xem trước tồn đọng | Trước khi tạm ngừng / đóng, hệ thống trả **danh sách tồn đọng** của chi nhánh: lịch hẹn `BOOKED`, đặt chỗ lưu trú `BOOKED` / `CHECKED_IN`, Visit mở, ca thu ngân `OPEN`, Order `OPEN` / `PENDING`, đơn online chưa kết thúc, Care Task `OPEN`, hoàn tiền chưa xong, phiếu chuyển kho `IN_TRANSIT`, phiên kiểm kê `OPEN`, tồn kho (số mặt hàng, tổng số lượng), nhân viên `ACTIVE`. | UC13 | — |
| BR-CN-08 | Điều kiện chặn | **Tạm ngừng** bị chặn khi còn Visit mở, thú đang trong chuồng (đặt chỗ `CHECKED_IN` / `OVERDUE`) hoặc ca thu ngân `OPEN`. **Đóng cửa** bị chặn thêm khi còn Order `OPEN` / `PENDING`, đơn online chưa `COMPLETED` / `CANCELLED`, hoàn tiền chưa `SUCCEEDED`, phiếu chuyển kho `IN_TRANSIT`, phiên kiểm kê `OPEN`, **tồn kho > 0** (phải chuyển kho UC77 hoặc điều chỉnh giảm UC76 trước) và nhân viên `ACTIVE` còn gắn chi nhánh (phải điều chuyển hoặc vô hiệu hóa, BR-QT-06, 09). | UC13 | 400 `BR-CN-08`, message liệt kê điều kiện còn vướng. |
| BR-CN-09 | Xử lý lịch đặt trước và việc dở | SUPER_MANAGER **chọn** hủy hàng loạt lịch hẹn và đặt chỗ `BOOKED` (thông báo từng khách, không tính hủy muộn, tái dùng `BranchClinicCancellationEvent`, BR-LH-10); bắt buộc chọn khi còn lịch. Care Task `OPEN` chuyển sang chi nhánh khác do SUPER_MANAGER chọn hoặc hủy kèm lý do. | UC13 | 400. |
| BR-CN-10 | Hiệu lực | `SUSPENDED` và `CLOSED`: không nhận lịch hẹn, đặt chỗ, đơn online mới; không hiện công khai (BR-CK-01 chỉ `ACTIVE`); lịch sử giữ nguyên. Mở lại `SUSPENDED` → `ACTIVE` cần cấu hình giờ mở cửa hợp lệ (BR-CN-01). `CLOSED` không mở lại. BR-QT-04 (≥ 1 BRANCH_MANAGER) chỉ áp dụng cho chi nhánh `ACTIVE`. | UC13 | — |

**Máy trạng thái #20 — Chi nhánh (mở rộng `03` #2):**

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | `ACTIVE` → `SUSPENDED` | Tạm ngừng | A04 | Không vướng BR-CN-08 (mức tạm ngừng); có lý do; xử lý lịch theo BR-CN-09 | Phát `BranchClinicCancellationEvent` nếu chọn hủy; ẩn công khai | BR-CN-06…10 · UC13 |
| 2 | `SUSPENDED` → `ACTIVE` | Mở lại | A04 | Giờ mở cửa hợp lệ; còn ≥ 1 BRANCH_MANAGER `ACTIVE` (BR-QT-04) | Hiện lại công khai | BR-CN-10 · UC13 |
| 3 | `ACTIVE` → `CLOSED` | Đóng cửa | A04 | Không vướng BR-CN-08 (mức đóng); có lý do | Như #1; không mở lại | BR-CN-06…10 · UC13 |
| 4 | `SUSPENDED` → `CLOSED` | Đóng cửa | A04 | Như #3 | — | BR-CN-08 · UC13 |

Dữ liệu: `branches` thêm `suspended_at`, `suspend_reason VARCHAR(300)?`, `expected_resume_on DATE?`, `closed_at`, `close_reason VARCHAR(300)?`; `status CHECK` thêm hai giá trị; `CHECK` cột ↔ trạng thái. **API `branchlife-v1` (4):** `GET /branches/{branchId}/closure-impact`, `POST …/suspend`, `POST …/resume`, `POST …/close`.

Hợp đồng chéo (số đếm tồn đọng, mỗi owner một method đọc): `VisitQueryApi.countOpenByBranch`, `AppointmentQueryApi.countBookedByBranch`, `BoardingQueryApi.countActiveStaysByBranch`, `CashierShiftQueryApi.hasOpenShift`, `OrderQueryApi.countOpenByBranch`, `StockQueryApi.sumQuantityByBranch`, `StaffDirectoryApi.countActiveByBranch`, `CareTaskApi.reassignBranch(fromBranchId, toBranchId)`.

### F.2 Gộp hồ sơ khách trùng (UC27)

| Mã | Use case | Actor |
|---|---|---|
| UC27 | Gộp hồ sơ khách trùng của cùng một khách | A05 |

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-KH-11 | Điều kiện gộp | BRANCH_MANAGER gộp hồ sơ **nguồn** vào hồ sơ **đích**: hai hồ sơ khác nhau, đều chưa `MERGED`. **Không gộp nếu cả hai đã liên kết tài khoản** (BR-TK-19: mỗi hồ sơ tối đa một tài khoản); nếu một bên có tài khoản thì hồ sơ **đích** phải là bên đó. Không gộp khi nguồn còn Visit mở hoặc Order `OPEN` / `PENDING`. | UC27 | 400 `BR-KH-11`. |
| BR-KH-12 | Hệ quả | Chuyển thú, sổ địa chỉ (bỏ địa chỉ trùng), lịch hẹn, đặt chỗ lưu trú, Visit / Order lịch sử, feedback, Care Task, giỏ hàng và đơn online sang hồ sơ đích, trong **một transaction** (04 nguyên tắc 6), qua sự kiện `CustomerMergedEvent(sourceId, targetId)` mà mỗi module tự xử lý. Hồ sơ nguồn thành `MERGED`, trỏ `merged_into_id`, ẩn khỏi tra cứu, giữ để audit. **Không tự gộp thú trùng** (lễ tân xử lý sau, BR-KH-06). | UC27 | — |
| BR-KH-13 | Không hoàn tác | Gộp không hoàn tác được (*ASSUMPTION A3*); ghi `customer_merges` (nguồn, đích, người làm, số bản ghi đã chuyển theo loại) và audit. | UC27 | — |
| BR-KH-14 | Xem trước bắt buộc | Gộp phải kèm `confirm = true` sau khi xem `merge-preview` (số thú, lịch, đơn… sẽ chuyển; cảnh báo xung đột). | UC27 | 400. |

Dữ liệu: `customers` thêm `merged_into_id BIGINT? FK→customers` (`CHECK (merged_into_id <> id)`); `customer_merges` — LOG: `id`, `source_customer_id`, `target_customer_id`, `executed_by FK`, `moved JSONB` (đếm theo loại), `created_at`. Không có máy trạng thái riêng (thao tác một bước, không hoàn tác). **API `custmerge-v1` (2):** `GET /customers/{customerId}/merge-preview?sourceCustomerId=…`, `POST /customer-merges`.

Hợp đồng chéo: mỗi module giữ `customer_id` lắng nghe `CustomerMergedEvent` (`@EventListener` đồng bộ, như `PetOwnerTransferredEvent`): appointment, visit, boarding, sales (orders, carts, online orders), care, content (feedback) — BE-1 và BE-2 cùng làm; Hạn đề xuất trước 7/12.

### F.3 Bài viết của bác sĩ và bình luận (UC80, UC82; mở rộng UC81)

| Mã | Use case | Actor |
|---|---|---|
| UC80 | Soạn và gửi duyệt bài viết | A07 |
| UC82 | Bình luận bài viết (đăng, sửa, xóa của mình) | A02 |

| Mã | Tên | Mô tả | UC | Xử lý khi vi phạm |
|---|---|---|---|---|
| BR-BV-05 | Bài viết của VET | VET soạn bài (đủ trường BR-BV-01) ở trạng thái nháp riêng, `author_account_id` = VET; chỉ tác giả xem / sửa nháp. Tên tác giả hiển thị công khai lấy từ hồ sơ VET (BR-TK-20). | UC80 | 403 / 404. |
| BR-BV-06 | Gửi duyệt | VET gửi `DRAFT` → `PENDING_REVIEW`; khi chờ duyệt VET không sửa (rút về `DRAFT` để sửa). SUPER_MANAGER (UC81) duyệt → `PUBLISHED` (ghi `published_at` lần đầu, BR-BV-02) hoặc từ chối → `REJECTED` (lý do bắt buộc); VET sửa bài bị từ chối rồi gửi lại. | UC80, UC81 | 409. |
| BR-BV-07 | Bài đã xuất bản | VET **không sửa** bài `PUBLISHED` của mình (*ASSUMPTION A4*): muốn đổi nội dung thì SUPER_MANAGER ẩn (`HIDDEN`, BR-BV-02) và VET soạn bài mới. Bài do SUPER_MANAGER tự soạn giữ luồng cũ, không qua duyệt. | UC80 | 409. |
| BR-BV-08 | Bình luận | CUSTOMER bình luận bài `PUBLISHED` (1–1 000 ký tự); sửa và xóa **bình luận của mình** (xóa mềm); chỉ bình luận `ACTIVE` hiện công khai; SUPER_MANAGER ẩn (kèm lý do) hoặc bỏ ẩn (UC81); bài chuyển `HIDDEN` thì bình luận không hiện. Giới hạn 10 bình luận / giờ / khách **[CFG]** `content.comment_per_hour`. Hiển thị tên khách **rút gọn** (*ASSUMPTION A5*). | UC82, UC81 | 400 / 429. |

**Máy trạng thái #21 — Bài viết (mở rộng cho VET):**

| # | Từ → Sang | Sự kiện | Người kích hoạt | Điều kiện | Hệ quả | Nguồn |
|---|---|---|---|---|---|---|
| 1 | — → `DRAFT` | Soạn bài | A07 (hoặc A04 như cũ) | — | — | BR-BV-05 · UC80 |
| 2 | `DRAFT` → `PENDING_REVIEW` | Gửi duyệt | A07 (tác giả) | Đủ trường BR-BV-01 | `submitted_at`; thông báo SUPER_MANAGER | BR-BV-06 · UC80 |
| 3 | `PENDING_REVIEW` → `DRAFT` | Rút lại | A07 (tác giả) | — | — | BR-BV-06 |
| 4 | `PENDING_REVIEW` → `PUBLISHED` | Duyệt | A04 | — | `published_at` nếu lần đầu; thông báo VET; ghi audit | BR-BV-06 · UC81 |
| 5 | `PENDING_REVIEW` → `REJECTED` | Từ chối | A04 | Có `review_note` | Thông báo VET; ghi audit | BR-BV-06 · UC81 |
| 6 | `REJECTED` → `DRAFT` | Sửa lại | A07 (tác giả) | — | Xóa `review_note` hiển thị | BR-BV-06 |

Dữ liệu: `articles` thêm `author_account_id FK→accounts?`, `review_note VARCHAR(300)?`, `submitted_at?`, `reviewed_by FK?`, `reviewed_at?`; `status CHECK` thêm `PENDING_REVIEW`, `REJECTED`; Index `(author_account_id, status)`. `article_comments` — ROOT: `id`, `article_id FK`, `customer_id FK`, `content VARCHAR(1000) NN`, `status VARCHAR(20) CHECK ('ACTIVE','HIDDEN','DELETED')`, `edited_at?`, `hidden_by FK?`, `hide_reason VARCHAR(300)?`, `hidden_at?`; Index `(article_id, status, created_at)`. **API `articlex-v1` (14):** VET `POST /me/articles`, `GET /me/articles`, `GET /me/articles/{articleId}`, `PATCH /me/articles/{articleId}`, `POST …/submit`, `POST …/withdraw`; SUPER_MANAGER `POST /articles/{articleId}/approve`, `POST …/reject`; bình luận `GET /public/articles/{slug}/comments`, `POST /me/articles/{articleId}/comments`, `PATCH /me/comments/{commentId}`, `DELETE /me/comments/{commentId}`, `POST /comments/{commentId}/hide`, `POST …/unhide`. Danh sách bài chờ duyệt dùng `GET /articles?status=PENDING_REVIEW` của content-v1 (mở rộng giá trị `status`).

**Giả định:** A4 VET không sửa bài đã xuất bản; A5 tên khách rút gọn. **Câu hỏi:** Q3 VET có được chọn chuyên mục không hay SUPER_MANAGER gán khi duyệt; Q4 bình luận có cần duyệt trước khi hiện không (đề xuất không, ẩn sau).

---

## G. Tổng hợp T52

| Nhóm | UC / ST | FSM | Bảng mới | Module API (endpoint) |
|---|---|---|---|---|
| A. Phí cấu hình | UC32 | — | 3 | `fees` (6) |
| B. Thương mại điện tử | UC61, 62, 64, 65 · ST09, 10, 12 | #14, Order#11–13 | 4 | `shop` (19) |
| C. Thanh toán online | UC63 · ST11, 14 | #15 | 3 | `payment` (6) |
| D. Trả hàng, hoàn tiền | UC68, 69 · ST21 | #16, #17 | 3 | `returns` (9) |
| E. Kho mở rộng | UC77, 78, 79 | #18, #19 | 4 | `stockops` (13) |
| F. Chi nhánh, khách, bài viết | UC13, 27, 80, 82 | #20, #21 | 2 + cột mới | `branchlife` (4), `custmerge` (2), `articlex` (14) |

**Đủ phạm vi T52:** 15 UC (UC13, 27, 32, 61–65, 68, 69, 77–79, 80, 82) và 6 ST (ST09–ST12, ST14, ST21) đều có quy tắc, máy trạng thái (nơi có chuyển trạng thái), dữ liệu và API. Tổng khoảng 73 endpoint, 8 module generator.

### Sự kiện chéo mới

`CustomerMergedEvent` (customer → mọi module giữ `customer_id`); `BranchClinicCancellationEvent` tái dùng cho tạm ngừng / đóng chi nhánh; thông báo mới: `ONLINE_ORDER_*`, `PAYMENT_*`, `REFUND_*`, `RETURN_*`, `ARTICLE_REVIEW_*` (mã chốt ở `06` §8 Q4).

### Cấu hình [CFG] mới (thêm vào `ConfigKey` ở T55)

`shop.payment_window_hours` (24), `shop.auto_confirm_days` (7), `shop.max_redelivery` (2), `payment.txn_ttl_minutes` (15), `payment.refund_max_attempts` (3), `returns.window_days` (7), `content.comment_per_hour` (10).

### Rủi ro và việc cần BE-2 xác nhận

1. Hoàn tiền sau `PAID` không đổi Order (Q4 ở B) ảnh hưởng báo cáo doanh thu UC89.
2. `stock_reservations` đổi định nghĩa "tồn khả dụng" (BR-KO-01) — mọi nơi tính tồn (kê đơn, bán lẻ, báo cáo) phải trừ phần giữ.
3. Gộp khách (UC27) chạm mọi module có `customer_id`; cần danh sách đầy đủ ở T55.
4. Cổng thanh toán chưa chọn (Q1 ở C) chặn T65.
5. Đóng chi nhánh (UC13) phụ thuộc UC77 (chuyển kho) và UC73–76; làm UC77 trước.

### Liên kết với `02`–`06` (đã làm ở T54)

| File | Tình trạng |
|---|---|
| `01` | UC đã có đủ; không đổi |
| `02` | Mục 19 trỏ tới BR-SP-08…11, BR-BH-07…15, BR-TG-06…14, BR-KO-08…13, BR-CN-06…10, BR-KH-11…14, BR-BV-05…08 ở file này |
| `03` | Mục trỏ tới FSM #14…#21 và Order#11–13 |
| `04`, `05` | Mục trỏ tới model và bảng ở file này (§A–§F) |
| `06` | Interface và sự kiện chéo gộp vào bảng tầng 3 kèm hạn giao; `ConfigKey` mới làm ở T55 |
| `api/INDEX.md` | Đã thêm 8 module `fees`, `shop`, `payment`, `returns`, `stockops`, `branchlife`, `custmerge`, `articlex` |

### Quyết định T54 (Hiến duyệt 5/11/2026) cho các câu hỏi của BE-2

| Câu hỏi | Quyết định |
|---|---|
| Chọn cổng thanh toán (C, Q1) | **VNPay** (đề xuất, vì có đăng ký sandbox tự phục vụ); hướng dẫn ở [`payment-gateway-setup.md`](./payment-gateway-setup.md). Chặn T65 tới khi có sandbox |
| Hoàn tiền và báo cáo doanh thu (B, Q4) | Hoàn tiền sau `PAID` không đổi Order; báo cáo doanh thu **trừ hoàn tiền**, phí vận chuyển báo riêng (BE-2 chi tiết hóa ở T73) |
| Tồn khả dụng trừ phần giữ (G, rủi ro 2) | **Bắt buộc**: mọi nơi tính tồn dùng `findAvailableQuantity` |
| Care Task `OPEN` khi đóng chi nhánh (F.1) | Chuyển sang chi nhánh SUPER_MANAGER chọn; không chọn thì hủy kèm lý do |
| Phạm vi gộp khách (F.2, custmerge Q1) | BRANCH_MANAGER gộp mọi hồ sơ khách (hồ sơ dùng chung toàn chuỗi), audit đầy đủ (A1) |
| Còn lại (A1…A6, Q nhỏ) | **Chốt theo đề xuất** đã ghi ở từng mục; BE-2 muốn đổi thì qua ADR |
