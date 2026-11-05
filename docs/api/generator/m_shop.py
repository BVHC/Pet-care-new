from lib import Module

m = Module(
    key="shop", title="Online Shop API v1 (tầng 3)", codes="BH (thương mại điện tử)", owner="BE-2",
    sources="01 UC61, UC62, UC64, UC65, ST09, ST10, ST12; 08-tang3-be2-spec.md §B (BR-BH-07…12; 03 #14 Đơn hàng online, Order#11–13); 05 bổ sung carts, cart_items, online_orders, stock_reservations",
    intro="Giỏ hàng, đặt hàng online, theo dõi / hủy / xác nhận đã nhận của khách và xử lý, giao đơn của lễ tân. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Thanh toán ở payment-v1, trả hàng và hoàn tiền ở returns-v1.",
    frozen=[
        "Path `/orders`, `/payments` của sales-v1 là nghiệp vụ tại quầy; đơn online dùng path riêng `/me/online-orders`, `/online-orders` (08 đầu file).",
        "Chuyển trạng thái do hệ thống (Đơn hàng online#2, #4, #12) không có endpoint: thanh toán thành công do callback cổng (payment-v1), hủy quá hạn do ST10, tự xác nhận do ST12.",
        "Khách không có endpoint trả hàng: trả hàng do lễ tân tạo (UC68, returns-v1).",
    ],
    tags={
        "Cart": "Giỏ hàng của tôi (UC61)",
        "Checkout": "Chi nhánh xử lý và báo giá (UC62)",
        "My online orders": "Đơn hàng online của tôi (UC62, UC64)",
        "Online order fulfillment": "Xử lý và giao đơn online (UC65)",
    },
    security=[
        "`/me/cart`, `/me/online-orders…` chỉ CUSTOMER và chỉ dữ liệu của chính khách; đơn của người khác trả 404 (ADR-0006).",
        "`/online-orders…` chỉ lễ tân của **chi nhánh xử lý** của đơn (A06); BRANCH_MANAGER chỉ xem (A05) trong chi nhánh mình; chi nhánh khác trả 403 / 404 (BR-QT-03).",
        "Giỏ chỉ nhận sản phẩm `GOODS` không kê đơn (BR-SP-01, BR-BH-07); địa chỉ giao phải thuộc sổ địa chỉ của chính khách (BR-BH-08).",
        "Giữ hàng, xuất kho FEFO, hoàn kho chạy trong cùng transaction với chuyển trạng thái (BR-BH-09); khóa đơn trước khi đọc như ADR-0106.",
    ],
    assumptions=[
        ("A1", "Phí vận chuyển cố định kèm ngưỡng miễn phí (BR-SP-08)", "YAGNI"),
        ("A2", "Không bán thuốc, vaccine online; giỏ chỉ nhận `GOODS`", "UC61 'không có thuốc kê đơn'; phương án an toàn"),
        ("A3", "Mỗi đơn xử lý ở đúng một chi nhánh, chọn lúc đặt; không tách đơn", "Tồn kho theo chi nhánh; tách đơn là phát minh thêm"),
        ("A4", "Chỉ thanh toán online, không COD", "UC62 «include» UC63"),
        ("A5", "Hoàn tiền sau khi đơn `PAID` không đổi Order, báo cáo trừ hoàn tiền", "03 #5: `PAID` là trạng thái cuối"),
    ],
    questions=[
        ("Q1", "Có cho khách chọn nhận tại chi nhánh thay vì giao tận nơi không", "TBD (PO)", "UC62"),
        ("Q2", "Đơn vị vận chuyển là nhập tay (chuỗi) hay danh mục", "TBD (BE-2)", "BR-BH-11"),
        ("Q3", "Chi nhánh xử lý do khách chọn (đề xuất) hay hệ thống tự chọn", "TBD (PO)", "BR-BH-08"),
        ("Q4", "Báo cáo doanh thu có tính phí vận chuyển và trừ hoàn tiền thế nào", "TBD (BE-2)", "08 §B ASSUMPTION A5"),
    ],
)

m.param("ProductId", "productId", "path", "int64")
m.param("OnlineOrderId", "onlineOrderId", "path", "int64")

m.schema("OnlineOrderStatus", None,
         enum=["PENDING_PAYMENT", "PAID", "PROCESSING", "SHIPPED", "DELIVERY_FAILED", "DELIVERED", "COMPLETED", "CANCELLED"],
         desc="03 #14 (08 §B)")
m.schema("CartItem", {
    "productId": "int64", "name": "string", "unit": "string", "unitPrice": "money|Giá hiện tại, chỉ để xem (BR-BH-07)",
    "quantity": "int", "imageUrl": "url?", "available": "bool|false nếu hết hàng toàn chuỗi",
})
m.schema("Cart", {"items": "array:ref:CartItem", "subtotal": "money"})
m.schema("SetCartItemRequest", {"quantity": "int|1–99 (BR-BH-07)"})
m.schema("FulfillmentBranch", {"branchId": "int64", "name": "string", "address": "string"})
m.schema("OnlineOrderQuote", {
    "branchId": "int64", "addressId": "int64", "subtotal": "money", "shippingFee": "money|BR-SP-08",
    "freeShippingApplied": "bool", "total": "money",
})
m.schema("PlaceOnlineOrderRequest", {"branchId": "int64", "addressId": "int64"})
m.schema("OnlineOrderLine", {"productId": "int64", "name": "string", "unit": "string", "unitPrice": "money|Snapshot (BR-BH-03)",
                             "quantity": "int", "lineTotal": "money"})
m.schema("OnlineOrderSummary", {
    "onlineOrderId": "int64", "salesOrderId": "int64|Order bán hàng tương ứng (`orders.id`), dùng khi tạo yêu cầu trả hàng (returns-v1)", "code": "string", "status": "ref:OnlineOrderStatus", "branchName": "string",
    "itemCount": "int", "total": "money", "createdAt": "datetime",
})
m.schema("OnlineOrder", {
    "onlineOrderId": "int64", "salesOrderId": "int64|Order bán hàng tương ứng (`orders.id`), dùng khi tạo yêu cầu trả hàng (returns-v1)", "code": "string", "status": "ref:OnlineOrderStatus", "branchId": "int64", "branchName": "string",
    "receiverName": "string", "receiverPhone": "string", "shipAddress": "string",
    "lines": "array:ref:OnlineOrderLine", "subtotal": "money", "shippingFee": "money", "total": "money",
    "carrier": "string?", "trackingCode": "string?", "redeliveryCount": "int",
    "paymentDueAt": "datetime", "paidAt": "datetime?", "shippedAt": "datetime?", "deliveredAt": "datetime?",
    "completedAt": "datetime?", "cancelledAt": "datetime?", "cancelReason": "string?", "createdAt": "datetime",
})
m.schema("ReasonRequest", {"reason": "string:300"})
m.schema("ShipRequest", {"carrier": "string:100", "trackingCode": "string:100"})

CUS = "CUSTOMER, chỉ dữ liệu của mình"
LT = "Lễ tân chi nhánh xử lý"
STS = "status=enum:PENDING_PAYMENT,PAID,PROCESSING,SHIPPED,DELIVERY_FAILED,DELIVERED,COMPLETED,CANCELLED"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái đơn không cho phép (03 #14)"

# ---- UC61 giỏ hàng
m.op("get", "/me/cart", "getMyCart", "Xem giỏ hàng", "Cart", "UC61", "BR-BH-07", "A02", CUS, resp="Cart", errors=(401, 403),
     notes="Sản phẩm ngừng kinh doanh tự bị loại khỏi kết quả.")
m.op("put", "/me/cart/items/{productId}", "setCartItem", "Thêm hoặc đặt số lượng sản phẩm trong giỏ", "Cart", "UC61",
     "BR-BH-07", "A02", CUS, body="SetCartItemRequest", resp="Cart", path_params=["ProductId"],
     errors=(400, 401, 403, 404), idem="Có",
     err_desc={400: "`BR-BH-07` sản phẩm không phải `GOODS` / là thuốc kê đơn / quá 30 dòng · số lượng ngoài 1–99",
               404: "Không tìm thấy sản phẩm đang kinh doanh"})
m.op("delete", "/me/cart/items/{productId}", "removeCartItem", "Xóa sản phẩm khỏi giỏ", "Cart", "UC61", "BR-BH-07",
     "A02", CUS, resp="Cart", path_params=["ProductId"], errors=(400, 401, 403), idem="Có",
     notes="Sản phẩm không có trong giỏ vẫn trả giỏ hiện tại.")
m.op("delete", "/me/cart", "clearMyCart", "Xóa toàn bộ giỏ", "Cart", "UC61", "BR-BH-07", "A02", CUS, status=204,
     errors=(401, 403), idem="Có")

# ---- UC62 đặt hàng
m.op("get", "/me/online-orders/fulfillment-branches", "listFulfillmentBranches",
     "Chi nhánh xử lý được cho giỏ hiện tại", "Checkout", "UC62", "BR-BH-08", "A02", CUS, resp="array:FulfillmentBranch",
     errors=(401, 403), notes="Chỉ chi nhánh `ACTIVE` còn đủ hàng cho mọi dòng của giỏ (đã trừ phần giữ, BR-BH-09).")
m.op("get", "/me/online-orders/quote", "quoteOnlineOrder", "Báo giá đơn online (ST09)", "Checkout", "UC62",
     "BR-BH-08, BR-SP-08, ST09", "A02", CUS, resp="OnlineOrderQuote",
     query=["branchId!=int64", "addressId!=int64"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-BH-08` giỏ rỗng · chi nhánh không đủ hàng · địa chỉ không phải của khách",
               404: "Không tìm thấy địa chỉ / chi nhánh"},
     notes="Không giữ hàng, không ghi gì; phí chốt thật lúc đặt.")
m.op("post", "/me/online-orders", "placeOnlineOrder", "Đặt hàng online", "My online orders", "UC62",
     "BR-BH-08, 09, BR-SP-08, 11", "A02", CUS, "— → PENDING_PAYMENT (Đơn hàng online#1); Order#11",
     body="PlaceOnlineOrderRequest", resp="OnlineOrder", status=201, errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BH-08` giỏ rỗng · chi nhánh không `ACTIVE` / thiếu hàng · địa chỉ không phải của khách",
               409: "`CONCURRENCY_CONFLICT` hàng vừa bị người khác giữ"},
     notes="Giữ hàng, chốt phí, tạo Order `ONLINE`, dọn giỏ trong một transaction. Bước tiếp: `POST /me/online-orders/{onlineOrderId}/pay` (payment-v1).")

# ---- UC64 đơn của tôi
m.op("get", "/me/online-orders", "listMyOnlineOrders", "Danh sách đơn online của tôi", "My online orders", "UC64",
     "BR-BH-10", "A02", CUS, resp="OnlineOrderSummary", page=True, query=[STS, "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`createdAt` giảm dần, trùng thì `orderId` giảm dần).")
m.op("get", "/me/online-orders/{onlineOrderId}", "getMyOnlineOrder", "Chi tiết đơn online của tôi", "My online orders", "UC64",
     "BR-BH-10", "A02", CUS, resp="OnlineOrder", path_params=["OnlineOrderId"], errors=(400, 401, 403, 404),
     err_desc={404: "Không tìm thấy đơn — cả khi đơn thuộc người khác (ADR-0006)"})
m.op("post", "/me/online-orders/{onlineOrderId}/cancel", "cancelMyOnlineOrder", "Hủy đơn online", "My online orders", "UC64",
     "BR-BH-09, 10, BR-TG-12", "A02", CUS,
     "PENDING_PAYMENT / PAID / PROCESSING → CANCELLED (Đơn hàng online#3, #13)", body="ReasonRequest",
     resp="OnlineOrder", path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: TRANS}, notes="Đơn đã thanh toán: hoàn hàng về lô gốc và sinh yêu cầu hoàn tiền (returns-v1, ST21).")
m.op("post", "/me/online-orders/{onlineOrderId}/confirm-received", "confirmOnlineOrderReceived", "Xác nhận đã nhận hàng",
     "My online orders", "UC64", "BR-BH-10", "A02", CUS, "DELIVERED → COMPLETED (Đơn hàng online#11)",
     resp="OnlineOrder", path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})

# ---- UC65 xử lý và giao
m.op("get", "/online-orders", "listOnlineOrders", "Danh sách đơn online của chi nhánh", "Online order fulfillment",
     "UC65", "BR-BH-11", "A05, A06", "A06 của chi nhánh xử lý; A05 chỉ xem", resp="OnlineOrderSummary", page=True,
     query=[STS, "page", "size"], errors=(400, 401, 403),
     notes="Tự lấy chi nhánh của người gọi. `status` vắng = `PAID` + `PROCESSING` + `DELIVERY_FAILED` (việc cần làm). Cũ nhất trước.")
m.op("get", "/online-orders/{onlineOrderId}", "getOnlineOrder", "Chi tiết đơn online (nhân viên)", "Online order fulfillment",
     "UC65", "BR-BH-11", "A05, A06", "A06 của chi nhánh xử lý; A05 chỉ xem", resp="OnlineOrder",
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404))
m.op("post", "/online-orders/{onlineOrderId}/start-processing", "startProcessingOnlineOrder", "Bắt đầu xử lý", "Online order fulfillment",
     "UC65", "BR-BH-11", "A06", LT, "PAID → PROCESSING (Đơn hàng online#5)", resp="OnlineOrder",
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})
m.op("post", "/online-orders/{onlineOrderId}/ship", "shipOnlineOrder", "Bàn giao cho đơn vị vận chuyển", "Online order fulfillment",
     "UC65", "BR-BH-11", "A06", LT, "PROCESSING → SHIPPED (Đơn hàng online#6)", body="ShipRequest", resp="OnlineOrder",
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BH-11` thiếu đơn vị vận chuyển hoặc mã vận đơn", 409: TRANS})
m.op("post", "/online-orders/{onlineOrderId}/mark-delivered", "markOnlineOrderDelivered", "Cập nhật đã giao",
     "Online order fulfillment", "UC65", "BR-BH-11, 12", "A06", LT, "SHIPPED → DELIVERED (Đơn hàng online#7)",
     resp="OnlineOrder", path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS},
     notes="Bắt đầu đếm hạn trả hàng và hạn tự xác nhận (BR-BH-12).")
m.op("post", "/online-orders/{onlineOrderId}/fail-delivery", "failOnlineOrderDelivery", "Ghi nhận giao thất bại",
     "Online order fulfillment", "UC65", "BR-BH-11", "A06", LT, "SHIPPED → DELIVERY_FAILED (Đơn hàng online#8)",
     body="ReasonRequest", resp="OnlineOrder", path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BH-11` thiếu lý do", 409: TRANS})
m.op("post", "/online-orders/{onlineOrderId}/redeliver", "redeliverOnlineOrder", "Giao lại", "Online order fulfillment", "UC65",
     "BR-BH-11", "A06", LT, "DELIVERY_FAILED → SHIPPED (Đơn hàng online#9)", body="ShipRequest", resp="OnlineOrder",
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BH-11` quá số lần giao lại tối đa [CFG] · thiếu mã vận đơn", 409: TRANS})
m.op("post", "/online-orders/{onlineOrderId}/cancel-failed", "cancelFailedOnlineOrder", "Hủy đơn sau giao thất bại",
     "Online order fulfillment", "UC65", "BR-BH-11, 09, BR-TG-12", "A06", LT,
     "DELIVERY_FAILED → CANCELLED (Đơn hàng online#10)", body="ReasonRequest", resp="OnlineOrder",
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BH-11` thiếu lý do", 409: TRANS},
     notes="Hoàn hàng về lô gốc và sinh yêu cầu hoàn tiền (returns-v1, ST21).")
