from lib import Module

m = Module(
    key="complaint", title="Complaint API v1 (tầng 3)", codes="DG (khiếu nại)", owner="BE-1",
    sources="01 UC85, UC86, ST16, ST17; 09-tang3-be1-spec.md §D (BR-DG-05…09; 03 #26 Khiếu nại); 05 bổ sung complaints, complaint_messages",
    intro="Khách gửi và theo dõi khiếu nại (lễ tân tạo thay), quản lý chi nhánh và quản lý chuỗi xử lý. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Package thuộc BE-1 (đề xuất, Q1), không phải `content` của BE-2.",
    frozen=[
        "Feedback (UC83, UC84) ở content-v1, không có vòng đời xử lý; khiếu nại là chức năng khác (09 §D).",
        "ST16 (leo thang quá hạn) và ST17 (tự đóng) là tác vụ hệ thống, không có endpoint; chuyển #8, #9 của 03 #26 do hệ thống.",
        "Chưa có đính kèm ảnh (09 Q5).",
    ],
    tags={
        "My complaints": "Khiếu nại của tôi (UC85)",
        "Complaints": "Xử lý khiếu nại (UC85 lễ tân tạo thay, UC86)",
    },
    security=[
        "Khách chỉ thấy và trao đổi khiếu nại của mình; của người khác trả 404 (ADR-0006).",
        "BRANCH_MANAGER chỉ khiếu nại gắn chi nhánh mình; SUPER_MANAGER mọi khiếu nại, kể cả khiếu nại không gắn chi nhánh và đã leo thang; lễ tân chỉ khiếu nại do mình tạo thay khách (BR-DG-06, BR-QT-03).",
        "Đối tượng liên quan (Order, đơn online, Visit, đặt chỗ) phải thuộc chính khách khiếu nại (BR-DG-05).",
        "Mọi chuyển trạng thái ghi một dòng `STATUS` vào `complaint_messages` (vết xử lý); khóa khiếu nại trước khi đổi trạng thái (ADR-0106).",
    ],
    assumptions=[
        ("A1", "Khiếu nại bị rút (`WITHDRAWN`) không mở lại", "BR-DG-07"),
        ("A2", "Leo thang chỉ là cờ, không đổi trạng thái, và chỉ một lần", "BR-DG-08"),
        ("A3", "Lễ tân chỉ thấy khiếu nại do mình tạo thay khách", "BR-DG-06; hạn chế dữ liệu nhạy"),
    ],
    questions=[
        ("Q1", "Package `complaint` thuộc BE-1 (đề xuất) hay `content` của BE-2", "TBD (BE-1, BE-2)", "06 §2 vs time.md T70"),
        ("Q2", "SLA 24 giờ nhận, 72 giờ giải quyết có phù hợp không", "TBD (PO)", "BR-DG-08"),
        ("Q3", "Có cho khách đánh giá hài lòng khi đóng không", "TBD (PO)", "UC85"),
    ],
)

m.param("ComplaintId", "complaintId", "path", "int64")

m.schema("ComplaintStatus", None, enum=["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"], desc="03 #26")
m.schema("ComplaintTopic", None, enum=["SERVICE_QUALITY", "GROOMING", "BOARDING", "PURCHASE", "STAFF", "OTHER"])
m.schema("ComplaintRelatedType", None, enum=["ORDER", "ONLINE_ORDER", "VISIT", "BOARDING"])
m.schema("ComplaintClosedReason", None, enum=["CUSTOMER", "WITHDRAWN", "AUTO"])
m.schema("CreateComplaintRequest", {
    "topic": "ref:ComplaintTopic", "branchId": "int64?|Bắt buộc trừ chủ đề `OTHER` (BR-DG-05)",
    "relatedType": "ref:ComplaintRelatedType?", "relatedId": "int64?|Cùng có hoặc cùng vắng với `relatedType`; phải thuộc khách",
    "content": "string:2000|10–2 000 ký tự",
})
m.schema("CreateComplaintForCustomerRequest", {
    "customerId": "int64|Khách được tạo thay (lễ tân)", "topic": "ref:ComplaintTopic", "branchId": "int64?",
    "relatedType": "ref:ComplaintRelatedType?", "relatedId": "int64?", "content": "string:2000",
})
m.schema("ComplaintSummary", {
    "complaintId": "int64", "code": "string", "topic": "ref:ComplaintTopic", "status": "ref:ComplaintStatus",
    "branchId": "int64?", "escalated": "bool", "lastActivityAt": "datetime", "createdAt": "datetime",
})
m.schema("ComplaintMessageAuthorType", None, enum=["CUSTOMER", "STAFF", "SYSTEM"])
m.schema("ComplaintMessageKind", None, enum=["MESSAGE", "STATUS"])
m.schema("ComplaintMessage", {
    "messageId": "int64", "authorType": "ref:ComplaintMessageAuthorType", "authorName": "string?",
    "kind": "ref:ComplaintMessageKind", "body": "string", "createdAt": "datetime",
})
m.schema("Complaint", {
    "complaintId": "int64", "code": "string", "customerId": "int64", "customerName": "string", "branchId": "int64?",
    "topic": "ref:ComplaintTopic", "relatedType": "ref:ComplaintRelatedType?", "relatedId": "int64?",
    "content": "string", "status": "ref:ComplaintStatus", "createdByStaff": "bool|Lễ tân tạo thay khách",
    "handlerName": "string?", "resolution": "string?", "resolvedAt": "datetime?", "closedAt": "datetime?",
    "closedReason": "ref:ComplaintClosedReason?", "reopenCount": "int", "escalatedAt": "datetime?",
    "lastActivityAt": "datetime", "createdAt": "datetime", "messages": "array:ref:ComplaintMessage|Cũ nhất trước",
})
m.schema("PostMessageRequest", {"body": "string:2000"})
m.schema("ResolveComplaintRequest", {"resolution": "string:2000|Kết luận (BR-DG-07)"})

CUS = "CUSTOMER, chỉ khiếu nại của mình"
HDL = "BRANCH_MANAGER chi nhánh của khiếu nại; SUPER_MANAGER mọi khiếu nại"
STS = "status=enum:OPEN,IN_PROGRESS,RESOLVED,CLOSED"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái khiếu nại không cho phép (03 #26)"

# ---- UC85 khách
m.op("post", "/me/complaints", "createMyComplaint", "Gửi khiếu nại", "My complaints", "UC85", "BR-DG-05", "A02", CUS,
     "— → OPEN (Khiếu nại#1)", body="CreateComplaintRequest", resp="Complaint", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-DG-05` nội dung < 10 / > 2 000 ký tự · thiếu chi nhánh · đối tượng liên quan không thuộc khách · quá 10 khiếu nại đang mở [CFG]",
               404: "Không tìm thấy chi nhánh / đối tượng liên quan"})
m.op("get", "/me/complaints", "listMyComplaints", "Khiếu nại của tôi", "My complaints", "UC85", "BR-DG-06", "A02", CUS,
     resp="ComplaintSummary", page=True, query=[STS, "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`createdAt` giảm dần, trùng thì `complaintId` giảm dần).")
m.op("get", "/me/complaints/{complaintId}", "getMyComplaint", "Chi tiết khiếu nại của tôi", "My complaints", "UC85",
     "BR-DG-06", "A02", CUS, resp="Complaint", path_params=["ComplaintId"], errors=(400, 401, 403, 404),
     err_desc={404: "Không tìm thấy khiếu nại — cả khi thuộc người khác (ADR-0006)"})
m.op("post", "/me/complaints/{complaintId}/messages", "postMyComplaintMessage", "Trao đổi thêm", "My complaints", "UC85",
     "BR-DG-07", "A02", CUS, body="PostMessageRequest", resp="Complaint", status=201, path_params=["ComplaintId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: "Khiếu nại đã `CLOSED`"})
m.op("post", "/me/complaints/{complaintId}/close", "closeMyComplaint", "Đóng hoặc rút khiếu nại", "My complaints", "UC85",
     "BR-DG-07", "A02", CUS, "RESOLVED → CLOSED (Khiếu nại#5); OPEN / IN_PROGRESS → CLOSED (Khiếu nại#4)",
     resp="Complaint", path_params=["ComplaintId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS},
     notes="Đang xử lý thì là **rút** (`WITHDRAWN`, không mở lại); đã kết luận thì là đóng (`CUSTOMER`).")
m.op("post", "/me/complaints/{complaintId}/reopen", "reopenMyComplaint", "Mở lại khiếu nại", "My complaints", "UC85",
     "BR-DG-07", "A02", CUS, "RESOLVED / CLOSED → OPEN (Khiếu nại#6, #7)", body="PostMessageRequest", resp="Complaint",
     path_params=["ComplaintId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-DG-07` quá 7 ngày [CFG] · quá số lần mở lại tối đa [CFG] · khiếu nại đã rút", 409: TRANS},
     notes="Thân là lý do mở lại, ghi thành tin nhắn.")

# ---- UC85 lễ tân, UC86 xử lý
m.op("post", "/complaints", "createComplaintForCustomer", "Lễ tân tạo khiếu nại thay khách", "Complaints", "UC85",
     "BR-DG-05, 06", "A06", "Lễ tân, khách ở chi nhánh của mình", "— → OPEN (Khiếu nại#1)",
     body="CreateComplaintForCustomerRequest", resp="Complaint", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-DG-05` như tạo của khách", 404: "Không tìm thấy khách / chi nhánh / đối tượng liên quan"})
m.op("get", "/complaints", "listComplaints", "Danh sách khiếu nại cần xử lý", "Complaints", "UC86", "BR-DG-06, 08",
     "A04, A05, A06", HDL + "; A06 chỉ cái mình tạo", resp="ComplaintSummary", page=True,
     query=[STS, "escalated=bool|true = chỉ khiếu nại đã leo thang (quá hạn)", "page", "size"], errors=(400, 401, 403),
     notes="`status` vắng = `OPEN` + `IN_PROGRESS`. Cũ nhất trước (`lastActivityAt` tăng dần). Tự lọc theo phạm vi người gọi.")
m.op("get", "/complaints/{complaintId}", "getComplaint", "Chi tiết khiếu nại", "Complaints", "UC86", "BR-DG-06",
     "A04, A05, A06", HDL + "; A06 chỉ cái mình tạo", resp="Complaint", path_params=["ComplaintId"],
     errors=(400, 401, 403, 404))
m.op("post", "/complaints/{complaintId}/take", "takeComplaint", "Nhận xử lý khiếu nại", "Complaints", "UC86", "BR-DG-07",
     "A04, A05", HDL, "OPEN → IN_PROGRESS (Khiếu nại#2)", resp="Complaint", path_params=["ComplaintId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})
m.op("post", "/complaints/{complaintId}/messages", "postComplaintMessage", "Trả lời khách", "Complaints", "UC86",
     "BR-DG-07", "A04, A05, A06", HDL + "; A06 chỉ cái mình tạo", body="PostMessageRequest", resp="Complaint",
     status=201, path_params=["ComplaintId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "Khiếu nại đã `CLOSED`"})
m.op("post", "/complaints/{complaintId}/resolve", "resolveComplaint", "Kết luận khiếu nại", "Complaints", "UC86",
     "BR-DG-07", "A04, A05", HDL + " (người đang xử lý)", "IN_PROGRESS → RESOLVED (Khiếu nại#3)",
     body="ResolveComplaintRequest", resp="Complaint", path_params=["ComplaintId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-DG-07` thiếu kết luận", 409: TRANS})
