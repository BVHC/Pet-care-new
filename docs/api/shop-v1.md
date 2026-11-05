# Online Shop API v1 (tầng 3) — Pet Care v16

> **Module:** BH (thương mại điện tử) · **Owner:** BE-2. Giỏ hàng, đặt hàng online, theo dõi / hủy / xác nhận đã nhận của khách và xử lý, giao đơn của lễ tân. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Thanh toán ở payment-v1, trả hàng và hoàn tiền ở returns-v1.
> **Nguồn chân lý:** 01 UC61, UC62, UC64, UC65, ST09, ST10, ST12; 08-tang3-be2-spec.md §B (BR-BH-07…12; 03 #14 Đơn hàng online, Order#11–13); 05 bổ sung carts, cart_items, online_orders, stock_reservations.
> **Contract máy đọc:** [`./openapi/shop-v1.yaml`](./openapi/shop-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Path `/orders`, `/payments` của sales-v1 là nghiệp vụ tại quầy; đơn online dùng path riêng `/me/online-orders`, `/online-orders` (08 đầu file).
- Chuyển trạng thái do hệ thống (Đơn hàng online#2, #4, #12) không có endpoint: thanh toán thành công do callback cổng (payment-v1), hủy quá hạn do ST10, tự xác nhận do ST12.
- Khách không có endpoint trả hàng: trả hàng do lễ tân tạo (UC68, returns-v1).

---

## A. Danh sách endpoint (19)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /me/cart` | Xem giỏ hàng | UC61 | BR-BH-07 |
| 2 | `PUT /me/cart/items/{productId}` | Thêm hoặc đặt số lượng sản phẩm trong giỏ | UC61 | BR-BH-07 |
| 3 | `DELETE /me/cart/items/{productId}` | Xóa sản phẩm khỏi giỏ | UC61 | BR-BH-07 |
| 4 | `DELETE /me/cart` | Xóa toàn bộ giỏ | UC61 | BR-BH-07 |
| 5 | `GET /me/online-orders/fulfillment-branches` | Chi nhánh xử lý được cho giỏ hiện tại | UC62 | BR-BH-08 |
| 6 | `GET /me/online-orders/quote` | Báo giá đơn online (ST09) | UC62 | BR-BH-08, BR-SP-08, ST09 |
| 7 | `POST /me/online-orders` | Đặt hàng online | UC62 | BR-BH-08, 09, BR-SP-08, 11 |
| 8 | `GET /me/online-orders` | Danh sách đơn online của tôi | UC64 | BR-BH-10 |
| 9 | `GET /me/online-orders/{onlineOrderId}` | Chi tiết đơn online của tôi | UC64 | BR-BH-10 |
| 10 | `POST /me/online-orders/{onlineOrderId}/cancel` | Hủy đơn online | UC64 | BR-BH-09, 10, BR-TG-12 |
| 11 | `POST /me/online-orders/{onlineOrderId}/confirm-received` | Xác nhận đã nhận hàng | UC64 | BR-BH-10 |
| 12 | `GET /online-orders` | Danh sách đơn online của chi nhánh | UC65 | BR-BH-11 |
| 13 | `GET /online-orders/{onlineOrderId}` | Chi tiết đơn online (nhân viên) | UC65 | BR-BH-11 |
| 14 | `POST /online-orders/{onlineOrderId}/start-processing` | Bắt đầu xử lý | UC65 | BR-BH-11 |
| 15 | `POST /online-orders/{onlineOrderId}/ship` | Bàn giao cho đơn vị vận chuyển | UC65 | BR-BH-11 |
| 16 | `POST /online-orders/{onlineOrderId}/mark-delivered` | Cập nhật đã giao | UC65 | BR-BH-11, 12 |
| 17 | `POST /online-orders/{onlineOrderId}/fail-delivery` | Ghi nhận giao thất bại | UC65 | BR-BH-11 |
| 18 | `POST /online-orders/{onlineOrderId}/redeliver` | Giao lại | UC65 | BR-BH-11 |
| 19 | `POST /online-orders/{onlineOrderId}/cancel-failed` | Hủy đơn sau giao thất bại | UC65 | BR-BH-11, 09, BR-TG-12 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Xem giỏ hàng | A02 | CUSTOMER, chỉ dữ liệu của mình | — | — | Sản phẩm ngừng kinh doanh tự bị loại khỏi kết quả. |
| Thêm hoặc đặt số lượng sản phẩm trong giỏ | A02 | CUSTOMER, chỉ dữ liệu của mình | — | Có | — |
| Xóa sản phẩm khỏi giỏ | A02 | CUSTOMER, chỉ dữ liệu của mình | — | Có | Sản phẩm không có trong giỏ vẫn trả giỏ hiện tại. |
| Xóa toàn bộ giỏ | A02 | CUSTOMER, chỉ dữ liệu của mình | — | Có | — |
| Chi nhánh xử lý được cho giỏ hiện tại | A02 | CUSTOMER, chỉ dữ liệu của mình | — | — | Chỉ chi nhánh `ACTIVE` còn đủ hàng cho mọi dòng của giỏ (đã trừ phần giữ, BR-BH-09). |
| Báo giá đơn online (ST09) | A02 | CUSTOMER, chỉ dữ liệu của mình | — | — | Không giữ hàng, không ghi gì; phí chốt thật lúc đặt. |
| Đặt hàng online | A02 | CUSTOMER, chỉ dữ liệu của mình | — → PENDING_PAYMENT (Đơn hàng online#1); Order#11 | — | Giữ hàng, chốt phí, tạo Order `ONLINE`, dọn giỏ trong một transaction. Bước tiếp: `POST /me/online-orders/{onlineOrderId}/pay` (payment-v1). |
| Danh sách đơn online của tôi | A02 | CUSTOMER, chỉ dữ liệu của mình | — | — | Mới nhất trước (`createdAt` giảm dần, trùng thì `orderId` giảm dần). |
| Chi tiết đơn online của tôi | A02 | CUSTOMER, chỉ dữ liệu của mình | — | — | — |
| Hủy đơn online | A02 | CUSTOMER, chỉ dữ liệu của mình | PENDING_PAYMENT / PAID / PROCESSING → CANCELLED (Đơn hàng online#3, #13) | — | Đơn đã thanh toán: hoàn hàng về lô gốc và sinh yêu cầu hoàn tiền (returns-v1, ST21). |
| Xác nhận đã nhận hàng | A02 | CUSTOMER, chỉ dữ liệu của mình | DELIVERED → COMPLETED (Đơn hàng online#11) | — | — |
| Danh sách đơn online của chi nhánh | A05, A06 | A06 của chi nhánh xử lý; A05 chỉ xem | — | — | Tự lấy chi nhánh của người gọi. `status` vắng = `PAID` + `PROCESSING` + `DELIVERY_FAILED` (việc cần làm). Cũ nhất trước. |
| Chi tiết đơn online (nhân viên) | A05, A06 | A06 của chi nhánh xử lý; A05 chỉ xem | — | — | — |
| Bắt đầu xử lý | A06 | Lễ tân chi nhánh xử lý | PAID → PROCESSING (Đơn hàng online#5) | — | — |
| Bàn giao cho đơn vị vận chuyển | A06 | Lễ tân chi nhánh xử lý | PROCESSING → SHIPPED (Đơn hàng online#6) | — | — |
| Cập nhật đã giao | A06 | Lễ tân chi nhánh xử lý | SHIPPED → DELIVERED (Đơn hàng online#7) | — | Bắt đầu đếm hạn trả hàng và hạn tự xác nhận (BR-BH-12). |
| Ghi nhận giao thất bại | A06 | Lễ tân chi nhánh xử lý | SHIPPED → DELIVERY_FAILED (Đơn hàng online#8) | — | — |
| Giao lại | A06 | Lễ tân chi nhánh xử lý | DELIVERY_FAILED → SHIPPED (Đơn hàng online#9) | — | — |
| Hủy đơn sau giao thất bại | A06 | Lễ tân chi nhánh xử lý | DELIVERY_FAILED → CANCELLED (Đơn hàng online#10) | — | Hoàn hàng về lô gốc và sinh yêu cầu hoàn tiền (returns-v1, ST21). |

---

## C. Chi tiết endpoint

### Cart

- **`GET /me/cart`** — Xem giỏ hàng. Response `200` `Cart`. Lỗi: `401` · `403`.
- **`PUT /me/cart/items/{productId}`** — Thêm hoặc đặt số lượng sản phẩm trong giỏ. Request `SetCartItemRequest`. Response `200` `Cart`. Lỗi: `400` `BR-BH-07` sản phẩm không phải `GOODS` / là thuốc kê đơn / quá 30 dòng · số lượng ngoài 1–99 · `401` · `403` · `404` Không tìm thấy sản phẩm đang kinh doanh.
- **`DELETE /me/cart/items/{productId}`** — Xóa sản phẩm khỏi giỏ. Response `200` `Cart`. Lỗi: `400` · `401` · `403`.
- **`DELETE /me/cart`** — Xóa toàn bộ giỏ. Response `204` rỗng. Lỗi: `401` · `403`.
### Checkout

- **`GET /me/online-orders/fulfillment-branches`** — Chi nhánh xử lý được cho giỏ hiện tại. Response `200` mảng `FulfillmentBranch`. Lỗi: `401` · `403`.
- **`GET /me/online-orders/quote`** — Báo giá đơn online (ST09). Query: `branchId`, `addressId`. Response `200` `OnlineOrderQuote`. Lỗi: `400` `BR-BH-08` giỏ rỗng · chi nhánh không đủ hàng · địa chỉ không phải của khách · `401` · `403` · `404` Không tìm thấy địa chỉ / chi nhánh.
### My online orders

- **`POST /me/online-orders`** — Đặt hàng online. Request `PlaceOnlineOrderRequest`. Response `201` `OnlineOrder`. Lỗi: `400` `BR-BH-08` giỏ rỗng · chi nhánh không `ACTIVE` / thiếu hàng · địa chỉ không phải của khách · `401` · `403` · `404` · `409` `CONCURRENCY_CONFLICT` hàng vừa bị người khác giữ.
- **`GET /me/online-orders`** — Danh sách đơn online của tôi. Query: `status?`, `page?`, `size?`. Response `200` trang `OnlineOrderSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /me/online-orders/{onlineOrderId}`** — Chi tiết đơn online của tôi. Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404` Không tìm thấy đơn — cả khi đơn thuộc người khác (ADR-0006).
- **`POST /me/online-orders/{onlineOrderId}/cancel`** — Hủy đơn online. Request `ReasonRequest`. Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /me/online-orders/{onlineOrderId}/confirm-received`** — Xác nhận đã nhận hàng. Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
### Online order fulfillment

- **`GET /online-orders`** — Danh sách đơn online của chi nhánh. Query: `status?`, `page?`, `size?`. Response `200` trang `OnlineOrderSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /online-orders/{onlineOrderId}`** — Chi tiết đơn online (nhân viên). Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /online-orders/{onlineOrderId}/start-processing`** — Bắt đầu xử lý. Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /online-orders/{onlineOrderId}/ship`** — Bàn giao cho đơn vị vận chuyển. Request `ShipRequest`. Response `200` `OnlineOrder`. Lỗi: `400` `BR-BH-11` thiếu đơn vị vận chuyển hoặc mã vận đơn · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /online-orders/{onlineOrderId}/mark-delivered`** — Cập nhật đã giao. Response `200` `OnlineOrder`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /online-orders/{onlineOrderId}/fail-delivery`** — Ghi nhận giao thất bại. Request `ReasonRequest`. Response `200` `OnlineOrder`. Lỗi: `400` `BR-BH-11` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /online-orders/{onlineOrderId}/redeliver`** — Giao lại. Request `ShipRequest`. Response `200` `OnlineOrder`. Lỗi: `400` `BR-BH-11` quá số lần giao lại tối đa [CFG] · thiếu mã vận đơn · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).
- **`POST /online-orders/{onlineOrderId}/cancel-failed`** — Hủy đơn sau giao thất bại. Request `ReasonRequest`. Response `200` `OnlineOrder`. Lỗi: `400` `BR-BH-11` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14).

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`OnlineOrderStatus`** — enum: `PENDING_PAYMENT`, `PAID`, `PROCESSING`, `SHIPPED`, `DELIVERY_FAILED`, `DELIVERED`, `COMPLETED`, `CANCELLED`. 03 #14 (08 §B)
- **`CartItem`** — {`productId`: int64, `name`: string, `unit`: string, `unitPrice`: int64, `quantity`: int32, `imageUrl?`: string, `available`: boolean}
- **`Cart`** — {`items`: [CartItem], `subtotal`: int64}
- **`SetCartItemRequest`** — {`quantity`: int32}
- **`FulfillmentBranch`** — {`branchId`: int64, `name`: string, `address`: string}
- **`OnlineOrderQuote`** — {`branchId`: int64, `addressId`: int64, `subtotal`: int64, `shippingFee`: int64, `freeShippingApplied`: boolean, `total`: int64}
- **`PlaceOnlineOrderRequest`** — {`branchId`: int64, `addressId`: int64}
- **`OnlineOrderLine`** — {`productId`: int64, `name`: string, `unit`: string, `unitPrice`: int64, `quantity`: int32, `lineTotal`: int64}
- **`OnlineOrderSummary`** — {`onlineOrderId`: int64, `salesOrderId`: int64, `code`: string, `status`: OnlineOrderStatus, `branchName`: string, `itemCount`: int32, `total`: int64, `createdAt`: date-time}
- **`OnlineOrder`** — {`onlineOrderId`: int64, `salesOrderId`: int64, `code`: string, `status`: OnlineOrderStatus, `branchId`: int64, `branchName`: string, `receiverName`: string, `receiverPhone`: string, `shipAddress`: string, `lines`: [OnlineOrderLine], `subtotal`: int64, `shippingFee`: int64, `total`: int64, `carrier?`: string, `trackingCode?`: string, `redeliveryCount`: int32, `paymentDueAt`: date-time, `paidAt?`: date-time, `shippedAt?`: date-time, `deliveredAt?`: date-time, `completedAt?`: date-time, `cancelledAt?`: date-time, `cancelReason?`: string, `createdAt`: date-time}
- **`ReasonRequest`** — {`reason`: string}
- **`ShipRequest`** — {`carrier`: string, `trackingCode`: string}

---

## D. Bảo mật & độ tin cậy

1. `/me/cart`, `/me/online-orders…` chỉ CUSTOMER và chỉ dữ liệu của chính khách; đơn của người khác trả 404 (ADR-0006).
2. `/online-orders…` chỉ lễ tân của **chi nhánh xử lý** của đơn (A06); BRANCH_MANAGER chỉ xem (A05) trong chi nhánh mình; chi nhánh khác trả 403 / 404 (BR-QT-03).
3. Giỏ chỉ nhận sản phẩm `GOODS` không kê đơn (BR-SP-01, BR-BH-07); địa chỉ giao phải thuộc sổ địa chỉ của chính khách (BR-BH-08).
4. Giữ hàng, xuất kho FEFO, hoàn kho chạy trong cùng transaction với chuyển trạng thái (BR-BH-09); khóa đơn trước khi đọc như ADR-0106.

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Phí vận chuyển cố định kèm ngưỡng miễn phí (BR-SP-08) | YAGNI |
| A2 | Không bán thuốc, vaccine online; giỏ chỉ nhận `GOODS` | UC61 'không có thuốc kê đơn'; phương án an toàn |
| A3 | Mỗi đơn xử lý ở đúng một chi nhánh, chọn lúc đặt; không tách đơn | Tồn kho theo chi nhánh; tách đơn là phát minh thêm |
| A4 | Chỉ thanh toán online, không COD | UC62 «include» UC63 |
| A5 | Hoàn tiền sau khi đơn `PAID` không đổi Order, báo cáo trừ hoàn tiền | 03 #5: `PAID` là trạng thái cuối |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Có cho khách chọn nhận tại chi nhánh thay vì giao tận nơi không | TBD (PO) | UC62 |
| Q2 | Đơn vị vận chuyển là nhập tay (chuỗi) hay danh mục | TBD (BE-2) | BR-BH-11 |
| Q3 | Chi nhánh xử lý do khách chọn (đề xuất) hay hệ thống tự chọn | TBD (PO) | BR-BH-08 |
| Q4 | Báo cáo doanh thu có tính phí vận chuyển và trừ hoàn tiền thế nào | TBD (BE-2) | 08 §B ASSUMPTION A5 |

---

## F. OpenAPI 3.1

[`./openapi/shop-v1.yaml`](./openapi/shop-v1.yaml)
