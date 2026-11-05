from lib import Module

m = Module(
    key="returns", title="Returns & Refunds API v1 (tầng 3)", codes="BH, TG (trả hàng, hoàn tiền)", owner="BE-2",
    sources="01 UC68, UC69, ST21; 08-tang3-be2-spec.md §D (BR-BH-13…15, BR-TG-12…14; 03 #16 Yêu cầu trả hàng, #17 Hoàn tiền); 05 bổ sung return_requests, return_request_lines, refunds",
    intro="Lễ tân tạo yêu cầu trả hàng, quản lý chi nhánh duyệt, hoàn tiền (tiền mặt / chuyển khoản tại quầy, qua cổng cho đơn online). **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Khách không tạo yêu cầu trả hàng: UC68 chỉ có lễ tân (A06).",
        "Sinh hoàn tiền (Hoàn tiền#1) và hoàn tiền qua cổng (ST21) là chuyển do hệ thống, không có endpoint; riêng hoàn tiền tại quầy do lễ tân xác nhận (`confirm`).",
        "Hủy đơn online đã thanh toán sinh hoàn tiền ở shop-v1 (`cancel`, `cancel-failed`), không đi qua yêu cầu trả hàng.",
    ],
    tags={
        "Return requests": "Yêu cầu trả hàng (UC68, UC69)",
        "Refunds": "Hoàn tiền (UC69, ST21)",
    },
    security=[
        "Mọi endpoint chỉ nhân viên của chi nhánh của Order: A06 tạo / rút / xác nhận hoàn tiền; A05 duyệt / từ chối / thử lại; chi nhánh khác trả 403 / 404 (BR-QT-03).",
        "Hoàn tiền tại quầy chỉ trong ca `OPEN` của chính lễ tân (BR-TG-13); ghi audit (BR-QT-15).",
        "Hoàn kho và điều chỉnh tồn chạy trong cùng transaction với duyệt (BR-BH-14, 04 nguyên tắc 6).",
    ],
    assumptions=[
        ("A1", "Phí vận chuyển không hoàn khi trả hàng", "BR-BH-14; chỉ hoàn toàn bộ khi hủy đơn chưa bàn giao"),
        ("A2", "Hình thức hoàn bằng hình thức đã thu", "BR-TG-12"),
        ("A3", "Trả hàng không đổi trạng thái Order / đơn online", "BR-BH-15"),
    ],
    questions=[
        ("Q1", "Khách có xem được trạng thái hoàn tiền của đơn không (thêm `refundStatus` vào shop-v1 `OnlineOrder`)", "TBD (PO)", "08 §D Q4"),
        ("Q2", "Hoàn chuyển khoản tại quầy có cần ghi mã giao dịch ngân hàng không", "TBD (BE-2)", "08 §D Q5"),
        ("Q3", "Hạn trả hàng 7 ngày có áp cho mọi sản phẩm không", "TBD (PO)", "BR-BH-13"),
    ],
)

m.param("ReturnRequestId", "requestId", "path", "int64")
m.param("RefundId", "refundId", "path", "int64")

m.schema("ReturnReason", None, enum=["DEFECTIVE", "WRONG_ITEM", "CHANGED_MIND", "OTHER"])
m.schema("ReturnRequestStatus", None, enum=["PENDING", "APPROVED", "REJECTED", "CANCELLED", "REFUNDED"], desc="03 #16")
m.schema("ReturnLineInput", {"orderLineId": "int64", "quantity": "int|≤ đã mua trừ phần đã / đang trả (BR-BH-13)"})
m.schema("CreateReturnRequestRequest", {
    "orderId": "int64|`orders.id` (Order bán hàng). Với đơn online dùng `salesOrderId` của `OnlineOrder` (shop-v1), **không** dùng `onlineOrderId`", "lines": "array:ref:ReturnLineInput|≥ 1 dòng",
    "reasonCode": "ref:ReturnReason", "reasonNote": "string:300?",
})
m.schema("ReturnLine", {"orderLineId": "int64", "productName": "string", "unitPrice": "money|Snapshot (BR-BH-03)", "quantity": "int"})
m.schema("ReturnRequestSummary", {
    "requestId": "int64", "code": "string", "orderId": "int64", "status": "ref:ReturnRequestStatus",
    "reasonCode": "ref:ReturnReason", "refundAmount": "money?", "createdAt": "datetime",
})
m.schema("ReturnRequest", {
    "requestId": "int64", "code": "string", "orderId": "int64", "branchId": "int64", "status": "ref:ReturnRequestStatus",
    "reasonCode": "ref:ReturnReason", "reasonNote": "string?", "lines": "array:ref:ReturnLine",
    "restock": "bool?|Có sau khi duyệt", "refundAmount": "money?", "decisionNote": "string?",
    "createdAt": "datetime", "decidedAt": "datetime?",
})
m.schema("ApproveReturnRequestRequest", {"restock": "bool|true = nhập lại lô gốc; false = loại bỏ (`RETURN_DISCARD`, BR-BH-14)"})
m.schema("RejectReturnRequestRequest", {"reason": "string:300"})
m.schema("RefundMethod", None, enum=["CASH", "TRANSFER", "GATEWAY"])
m.schema("RefundStatus", None, enum=["REQUESTED", "SUCCEEDED", "FAILED"], desc="03 #17")
m.schema("Refund", {
    "refundId": "int64", "code": "string", "returnRequestId": "int64?", "onlineOrderId": "int64?",
    "method": "ref:RefundMethod", "amount": "money", "status": "ref:RefundStatus", "attempts": "int",
    "failureReason": "string?", "completedAt": "datetime?", "createdAt": "datetime",
})
m.schema("ConfirmRefundRequest", {"note": "string:300?"})

BR = "A06, A05 của chi nhánh của Order"
STS = "status=enum:PENDING,APPROVED,REJECTED,CANCELLED,REFUNDED"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17)"

m.op("post", "/return-requests", "createReturnRequest", "Tạo yêu cầu trả hàng", "Return requests", "UC68",
     "BR-BH-13", "A06", "Lễ tân chi nhánh của Order", "— → PENDING (Yêu cầu trả hàng#1)",
     body="CreateReturnRequestRequest", resp="ReturnRequest", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-BH-13` Order chưa `PAID` / đơn online chưa giao · quá hạn 7 ngày [CFG] · dòng không phải `GOODS` hoặc là thuốc / vaccine / dịch vụ · vượt số lượng được trả · thiếu lý do",
               404: "Không tìm thấy Order hoặc dòng"})
m.op("get", "/return-requests", "listReturnRequests", "Danh sách yêu cầu trả hàng của chi nhánh", "Return requests", "UC68",
     "BR-BH-14", "A05, A06", BR, resp="ReturnRequestSummary", page=True, query=[STS, "page", "size"], errors=(400, 401, 403),
     notes="Tự lấy chi nhánh của người gọi. `status` vắng = `PENDING`. Cũ nhất trước.")
m.op("get", "/return-requests/{requestId}", "getReturnRequest", "Chi tiết yêu cầu trả hàng", "Return requests", "UC68",
     "BR-BH-14", "A05, A06", BR, resp="ReturnRequest", path_params=["ReturnRequestId"], errors=(400, 401, 403, 404))
m.op("post", "/return-requests/{requestId}/cancel", "cancelReturnRequest", "Rút yêu cầu trả hàng", "Return requests",
     "UC68", "BR-BH-14", "A06", "Người tạo", "PENDING → CANCELLED (Yêu cầu trả hàng#2)", resp="ReturnRequest",
     path_params=["ReturnRequestId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})
m.op("post", "/return-requests/{requestId}/approve", "approveReturnRequest", "Duyệt yêu cầu trả hàng", "Return requests",
     "UC69", "BR-BH-14, BR-TG-12", "A05", "BRANCH_MANAGER của chi nhánh của Order",
     "PENDING → APPROVED (Yêu cầu trả hàng#3); Hoàn tiền#1", body="ApproveReturnRequestRequest", resp="ReturnRequest",
     path_params=["ReturnRequestId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS},
     notes="Nhập lại lô gốc hoặc điều chỉnh giảm, sinh hoàn tiền theo hình thức đã thu (BR-TG-12), trong một transaction.")
m.op("post", "/return-requests/{requestId}/reject", "rejectReturnRequest", "Từ chối yêu cầu trả hàng", "Return requests",
     "UC69", "BR-BH-14", "A05", "BRANCH_MANAGER của chi nhánh của Order", "PENDING → REJECTED (Yêu cầu trả hàng#4)",
     body="RejectReturnRequestRequest", resp="ReturnRequest", path_params=["ReturnRequestId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-BH-14` thiếu lý do", 409: TRANS})

m.op("get", "/refunds", "listRefunds", "Danh sách hoàn tiền của chi nhánh", "Refunds", "UC69", "BR-TG-12",
     "A05, A06", BR, resp="Refund", page=True,
     query=["status=enum:REQUESTED,SUCCEEDED,FAILED", "method=enum:CASH,TRANSFER,GATEWAY", "page", "size"],
     errors=(400, 401, 403), notes="`status` vắng = `REQUESTED` + `FAILED`. Cũ nhất trước.")
m.op("post", "/refunds/{refundId}/confirm", "confirmRefund", "Xác nhận đã hoàn tiền tại quầy", "Refunds", "UC69",
     "BR-TG-13", "A06", "Lễ tân có ca `OPEN` của chính mình", "REQUESTED → SUCCEEDED (Hoàn tiền#2)",
     body="ConfirmRefundRequest", resp="Refund", path_params=["RefundId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TG-01` không có ca `OPEN` · hoàn tiền `GATEWAY` không xác nhận tay (do ST21)", 409: TRANS},
     notes="Số hoàn tính vào ca; yêu cầu trả hàng tương ứng → `REFUNDED` (Yêu cầu trả hàng#5).")
m.op("post", "/refunds/{refundId}/retry", "retryRefund", "Thử lại hoàn tiền qua cổng", "Refunds", "UC69", "BR-TG-14",
     "A05", "BRANCH_MANAGER của chi nhánh", "FAILED → REQUESTED (Hoàn tiền#4)", resp="Refund",
     path_params=["RefundId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Chỉ hoàn tiền `GATEWAY`", 409: TRANS})
