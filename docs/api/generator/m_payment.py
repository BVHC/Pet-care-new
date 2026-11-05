from lib import Module

m = Module(
    key="payment", title="Online Payment API v1 (tầng 3)", codes="TG (thanh toán online)", owner="BE-2",
    sources="01 UC63, ST11, ST14; 08-tang3-be2-spec.md §C (BR-TG-06…10; 03 #15 Giao dịch thanh toán online); 05 bổ sung payment_transactions, payment_reconciliations, reconciliation_items",
    intro="Tạo giao dịch thanh toán online cho đơn, nhận kết quả từ cổng (callback), đối soát. Trung lập với cổng: chọn VNPay / ZaloPay / MoMo là việc của nhóm. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Chọn cổng, đăng ký sandbox và ánh xạ tham số riêng của cổng vào `GatewayCallbackRequest` là việc của lớp `PaymentGatewayClient` (08 §C), không nằm trong hợp đồng này.",
        "ST11 (hết hạn giao dịch) và ST14 (đối soát) là tác vụ hệ thống, không có endpoint ngoài các endpoint xem và ghi chú đối soát.",
        "Hoàn tiền (ST21) ở returns-v1.",
    ],
    tags={
        "Online payment": "Thanh toán đơn online của tôi (UC63)",
        "Gateway callback": "Kết quả do cổng thanh toán gọi về (S02)",
        "Reconciliation": "Đối soát cổng (ST14)",
    },
    security=[
        "`POST /me/online-orders/{onlineOrderId}/pay` chỉ CUSTOMER và chỉ đơn của mình; đơn người khác trả 404 (ADR-0006).",
        "Callback là **công khai** (ADR-0005) nhưng chỉ xử lý khi chữ ký hợp lệ và idempotent theo mã tham chiếu; chữ ký sai trả 400 và không ghi gì ngoài log (BR-TG-07). Khóa bí mật của cổng chỉ ở biến môi trường.",
        "Số tiền cổng báo phải khớp số tiền giao dịch; lệch thì `FAILED` và đi vào đối soát, không tự đánh dấu đơn đã trả (BR-TG-08).",
        "Đối soát chỉ SUPER_MANAGER; không có chức năng tự sửa (BR-TG-10).",
    ],
    assumptions=[
        ("A1", "Callback của mọi cổng được ánh xạ về một dạng chung `GatewayCallbackRequest` ở lớp tích hợp", "Giữ hợp đồng trung lập với cổng"),
        ("A2", "Chỉ một cổng được tích hợp ở GĐ2", "YAGNI; 08 §C"),
        ("A3", "Khách quay lại từ cổng thì giao diện hỏi `GET /me/payment-transactions/{id}` (không tin tham số trên URL quay về)", "Kết quả chỉ tin từ callback đã ký"),
    ],
    questions=[
        ("Q1", "Chọn VNPay, ZaloPay hay MoMo và đăng ký tài khoản sandbox", "TBD (nhóm)", "time.md T65"),
        ("Q2", "Có giới hạn IP của cổng được gọi callback không", "TBD (BE-1, BE-2)", "BR-TG-07"),
        ("Q3", "Cổng có hỗ trợ hủy phiên chưa thanh toán không", "TBD (BE-2)", "03 #15 Giao dịch#5"),
    ],
)

m.param("OnlineOrderId", "onlineOrderId", "path", "int64")
m.param("TransactionId", "transactionId", "path", "int64")
m.param("ReconciliationId", "reconciliationId", "path", "int64")
m.param("ItemId", "itemId", "path", "int64")

m.schema("PaymentTransactionStatus", None, enum=["PENDING", "SUCCEEDED", "FAILED", "EXPIRED", "CANCELLED"], desc="03 #15")
m.schema("PaymentSession", {
    "transactionId": "int64", "code": "string|Mã tham chiếu gửi cổng", "amount": "money|Tiền hàng + phí vận chuyển (BR-TG-06)",
    "redirectUrl": "url|Địa chỉ chuyển khách sang cổng", "expiresAt": "datetime|Bây giờ + 15 phút [CFG] (BR-TG-09)",
})
m.schema("PaymentTransaction", {
    "transactionId": "int64", "onlineOrderId": "int64", "status": "ref:PaymentTransactionStatus", "amount": "money",
    "expiresAt": "datetime", "paidAt": "datetime?", "failureReason": "string?",
})
m.schema("GatewayCallbackRequest", {
    "transactionCode": "string|Mã tham chiếu hệ thống đã gửi cổng", "gatewayTxnId": "string?|Mã giao dịch của cổng",
    "status": "string|Mã trạng thái nguyên văn của cổng", "amount": "money", "signature": "string|Chữ ký của cổng, kiểm trước khi xử lý (BR-TG-07)",
    "payload": "object?|Các trường còn lại của cổng, nguyên văn",
})
m.schema("CallbackAck", {"received": "bool"})
m.schema("ReconciliationStatus", None, enum=["RUNNING", "MATCHED", "MISMATCHED", "FAILED"])
m.schema("ReconciliationSummary", {
    "reconciliationId": "int64", "reconDate": "date", "status": "ref:ReconciliationStatus", "txnCount": "int",
    "mismatchCount": "int", "finishedAt": "datetime?",
})
m.schema("ReconciliationItemKind", None, enum=["MISSING_LOCAL", "MISSING_GATEWAY", "AMOUNT_DIFF", "STATUS_DIFF"])
m.schema("ReconciliationItem", {
    "itemId": "int64", "kind": "ref:ReconciliationItemKind", "transactionId": "int64?", "gatewayRef": "string?",
    "ourAmount": "money?", "gatewayAmount": "money?", "resolved": "bool", "resolvedNote": "string?", "resolvedAt": "datetime?",
})
m.schema("ResolveItemRequest", {"note": "string:300"})

CUS = "CUSTOMER, chỉ đơn của mình"
A04 = "Chỉ SUPER_MANAGER"

m.op("post", "/me/online-orders/{onlineOrderId}/pay", "payOnlineOrder", "Bắt đầu thanh toán đơn online", "Online payment", "UC63",
     "BR-TG-06, 09, 11", "A02", CUS, "— → PENDING (Giao dịch#1)", resp="PaymentSession", status=201,
     path_params=["OnlineOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TG-06` đơn đã quá hạn thanh toán (`BR-BH-12`)",
               409: "Đơn không ở `PENDING_PAYMENT` · đã có giao dịch `PENDING` (`BR-TG-06`)"},
     notes="Giao diện chuyển khách tới `redirectUrl`. Kết quả chỉ tin từ callback đã ký (A3).")
m.op("get", "/me/payment-transactions/{transactionId}", "getMyPaymentTransaction", "Trạng thái giao dịch của tôi",
     "Online payment", "UC63", "BR-TG-06", "A02", CUS, resp="PaymentTransaction", path_params=["TransactionId"],
     errors=(400, 401, 403, 404), err_desc={404: "Không tìm thấy giao dịch — cả khi thuộc người khác"},
     notes="Trang kết quả sau khi quay lại từ cổng hỏi endpoint này.")
m.op("post", "/public/payment-gateway/callback", "paymentGatewayCallback", "Nhận kết quả thanh toán từ cổng",
     "Gateway callback", "UC63", "BR-TG-07, 08, 09", "S02", "Public, kiểm chữ ký",
     "PENDING → SUCCEEDED / FAILED (Giao dịch#2, #3)", body="GatewayCallbackRequest", resp="CallbackAck", public=True,
     errors=(400,), idem="Có",
     err_desc={400: "`BR-TG-07` chữ ký không hợp lệ · mã tham chiếu không tồn tại (không ghi gì ngoài log)"},
     notes="Idempotent theo mã tham chiếu: gọi lại cùng kết quả vẫn 200 và không đổi gì. Số tiền lệch → `FAILED` + mục đối soát (BR-TG-08).")
m.op("get", "/payment-reconciliations", "listPaymentReconciliations", "Danh sách phiên đối soát", "Reconciliation", "ST14",
     "BR-TG-10", "A04", A04, resp="ReconciliationSummary", page=True,
     query=["status=enum:RUNNING,MATCHED,MISMATCHED,FAILED", "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`reconDate` giảm dần).")
m.op("get", "/payment-reconciliations/{reconciliationId}/items", "listReconciliationItems", "Các mục lệch của một phiên đối soát",
     "Reconciliation", "ST14", "BR-TG-10", "A04", A04, resp="ReconciliationItem", page=True,
     path_params=["ReconciliationId"], query=["resolved=bool", "page", "size"], errors=(400, 401, 403, 404))
m.op("post", "/payment-reconciliation-items/{itemId}/resolve", "resolveReconciliationItem", "Ghi chú đã xử lý mục lệch",
     "Reconciliation", "ST14", "BR-TG-10", "A04", A04, body="ResolveItemRequest", resp="ReconciliationItem",
     path_params=["ItemId"], errors=(400, 401, 403, 404, 409), idem="Có",
     err_desc={409: "Mục đã được xử lý"}, notes="Chỉ ghi chú; không sửa giao dịch hay đơn (BR-TG-10).")
