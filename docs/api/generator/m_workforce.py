from lib import Module

m = Module(
    key="workforce", title="Workforce API v1 (tầng 3)", codes="NS", owner="BE-1",
    sources="01 UC34–UC38; 02 BR-NS-01…10; 03 #11 Yêu cầu nghỉ / đổi ca, #12 Phân ca; 04 §12.1; 05 §14.1 (shift_definitions, shift_assignments, shift_requests)",
    intro="Ca làm việc, phân ca, lịch làm việc của nhân viên, yêu cầu nghỉ / đổi ca và ghi nhận vắng đột xuất. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Danh sách nhân viên của chi nhánh (UC34 'xem nhân viên chi nhánh') dùng `GET /staff` của identity (UC08), không lặp ở đây.",
        "Hủy phân ca, từ chối yêu cầu khi nhân viên bị vô hiệu hóa / điều chuyển (BR-NS-09; Yêu cầu#5, Phân ca#6) là hệ quả hệ thống, không có endpoint.",
        "Lịch làm việc không chặn đặt lịch, hàng đợi, thu ngân (02 §7 ghi chú đầu mục).",
    ],
    tags={
        "Shift definitions": "Ca làm việc của chi nhánh (UC34)",
        "Shift assignments": "Phân ca, lịch làm việc, vắng đột xuất (UC34, UC35, UC38)",
        "Shift requests": "Yêu cầu nghỉ / đổi ca và duyệt (UC36, UC37)",
    },
    security=[
        "Ca, phân ca, duyệt yêu cầu, vắng đột xuất: chỉ BRANCH_MANAGER của chính chi nhánh (BR-NS-01, 02, 06, 07). SUPER_MANAGER không có chức năng nhân sự.",
        "Nhân viên A06–A08 chỉ xem lịch và yêu cầu của chính mình, chỉ gửi / rút yêu cầu cho phân ca của mình (BR-NS-03, 04, 05). Phân ca người khác trả 404, không lộ sự tồn tại.",
        "Thao tác của BRANCH_MANAGER ghi audit (BR-NS-10). Chồng giờ phân ca kiểm dưới khóa dòng tài khoản nhân viên để hai thao tác song song không cùng lọt (05 §13 mục 24).",
        "Thông báo ghi `notification_outbox` trong cùng transaction nghiệp vụ (BR-NS-08).",
    ],
    assumptions=[
        ("A1", "Đổi ca (`SWAP`) là đổi sang một ca khác của chi nhánh trong cùng ngày, không phải đổi với đồng nghiệp", "UC36 không nói rõ; phương án ít phát minh nhất vì không cần xác nhận hai chiều"),
        ("A2", "Lịch làm việc chỉ để xem, không chặn hay đổi hành vi của hàng đợi / đặt lịch / thu ngân", "02 §7 ghi chú: mệnh đề về ca làm trong QT đã gỡ ở v10; BR-TN-05 chỉ xét nhân viên ACTIVE"),
        ("A3", "Không có định biên ca (số người tối thiểu) nên duyệt nghỉ không kiểm số người còn lại", "UC37 chỉ nói duyệt / từ chối; YAGNI"),
        ("A4", "Gửi yêu cầu trước giờ bắt đầu ca ít nhất 24 giờ và ghi vắng đột xuất trong 7 ngày là [CFG] mới (`workforce.request_lead_hours`, `workforce.absence_report_max_days`)", "Cần ConfigKey mới ở T55 (06 §1, BR-QT-13)"),
        ("A5", "Hủy phân ca chỉ khi ca chưa bắt đầu; ca đã bắt đầu thì chỉ có ghi vắng đột xuất", "Tách rõ hai thao tác của BRANCH_MANAGER (UC34 và UC38)"),
    ],
    questions=[
        ("Q1", "Khi nhân viên bị vô hiệu hóa / điều chuyển, identity phát sự kiện nào cho workforce? Đề xuất `StaffLeftBranchEvent(staffId, branchId)` ở `identity.api` (06 §3).", "TBD (BE-1)", "BR-NS-09, BR-QT-06, BR-QT-09"),
        ("Q2", "Chặn chồng giờ ở DB bằng cột sao chép + `EXCLUDE USING gist` hay chỉ kiểm trong service?", "TBD (BE-1)", "05 §13 mục 24"),
        ("Q3", "BRANCH_MANAGER có được tự phân ca / xin nghỉ không (UC35–36 chỉ nêu A06–A08)?", "TBD (PO)", "01 UC35, UC36"),
    ],
)

m.param("ShiftDefinitionId", "shiftDefinitionId", "path", "int64")
m.param("ShiftAssignmentId", "shiftAssignmentId", "path", "int64")
m.param("ShiftRequestId", "shiftRequestId", "path", "int64")

m.schema("ShiftDefinition", {
    "shiftDefinitionId": "int64", "branchId": "int64", "name": "string:60",
    "startTime": "time|Giờ Việt Nam", "endTime": "time|Giờ Việt Nam, sau startTime (BR-NS-01)", "active": "bool",
})
m.schema("CreateShiftDefinitionRequest", {"name": "string:60", "startTime": "time", "endTime": "time"})
m.schema("UpdateShiftDefinitionRequest", {
    "name": "string:60?", "startTime": "time?|Chỉ sửa được khi ca chưa có phân ca nào (BR-NS-01)",
    "endTime": "time?", "active": "bool?|false = ngừng dùng (BR-NS-01)",
}, required=[])
m.schema("ShiftAssignmentStatus", None, enum=["SCHEDULED", "ON_LEAVE", "ABSENT", "CANCELLED"], desc="03 #12")
m.schema("ShiftAssignment", {
    "shiftAssignmentId": "int64", "branchId": "int64", "staffId": "int64", "staffName": "string",
    "shiftDefinitionId": "int64", "shiftName": "string", "startTime": "time", "endTime": "time",
    "workDate": "date", "status": "ref:ShiftAssignmentStatus", "reason": "string?|Lý do vắng hoặc hủy",
    "pendingRequestId": "int64?|Yêu cầu PENDING của phân ca, nếu có (BR-NS-04)",
})
m.schema("CreateShiftAssignmentRequest", {"staffId": "int64", "shiftDefinitionId": "int64", "workDate": "date"})
m.schema("ReasonRequest", {"reason": "string:300"})
m.schema("ShiftRequestType", None, enum=["LEAVE", "SWAP"])
m.schema("ShiftRequestStatus", None, enum=["PENDING", "APPROVED", "REJECTED", "WITHDRAWN"], desc="03 #11")
m.schema("ShiftRequest", {
    "shiftRequestId": "int64", "shiftAssignmentId": "int64", "requesterId": "int64", "requesterName": "string",
    "workDate": "date", "currentShiftName": "string", "type": "ref:ShiftRequestType",
    "targetShiftDefinitionId": "int64?|Chỉ có với SWAP", "targetShiftName": "string?", "reason": "string",
    "status": "ref:ShiftRequestStatus", "decidedBy": "int64?", "decisionNote": "string?",
    "closedAt": "datetime?", "createdAt": "datetime",
})
m.schema("CreateShiftRequestRequest", {
    "shiftAssignmentId": "int64", "type": "ref:ShiftRequestType",
    "targetShiftDefinitionId": "int64?|Bắt buộc với SWAP, không được có với LEAVE", "reason": "string:300",
})
m.schema("RejectShiftRequestRequest", {"decisionNote": "string:300"})

BM = "BRANCH_MANAGER của chi nhánh"
STAFF = "Nhân viên A06–A08, chỉ dữ liệu của mình"

# ---- UC34 ca làm việc
m.op("get", "/shift-definitions", "listShiftDefinitions", "Danh sách ca làm việc của chi nhánh", "Shift definitions",
     "UC34", "BR-NS-01", "A05", BM, resp="array:ShiftDefinition", query=["activeOnly=bool"], errors=(400, 401, 403),
     notes="Không phân trang. `activeOnly` vắng = false (cả ca ngừng dùng). Sắp theo `startTime` tăng dần.")
m.op("post", "/shift-definitions", "createShiftDefinition", "Tạo ca làm việc", "Shift definitions", "UC34",
     "BR-NS-01, BR-NS-10", "A05", BM, body="CreateShiftDefinitionRequest", resp="ShiftDefinition", status=201,
     errors=(400, 401, 403),
     err_desc={400: "`BR-NS-01` tên trùng trong chi nhánh · giờ bắt đầu không nhỏ hơn giờ kết thúc; `VALIDATION_FAILED` sai kiểu / quá dài"})
m.op("patch", "/shift-definitions/{shiftDefinitionId}", "updateShiftDefinition", "Sửa / ngừng dùng ca làm việc",
     "Shift definitions", "UC34", "BR-NS-01, BR-NS-10", "A05", BM, body="UpdateShiftDefinitionRequest",
     resp="ShiftDefinition", path_params=["ShiftDefinitionId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-NS-01` tên trùng · sửa giờ khi ca đã có phân ca · ngừng dùng khi ca còn phân ca `SCHEDULED` từ hôm nay"},
     notes="Chỉ các trường có trong body được đổi.")

# ---- UC34/35/38 phân ca
m.op("get", "/shift-assignments", "listShiftAssignments", "Lịch phân ca của chi nhánh", "Shift assignments", "UC34",
     "BR-NS-03", "A05", BM, resp="ShiftAssignment", page=True,
     query=["from!=date", "to!=date", "staffId=int64", "status=enum:SCHEDULED,ON_LEAVE,ABSENT,CANCELLED", "page", "size"],
     errors=(400, 401, 403),
     err_desc={400: "`from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày (`BR-NS-03`)"},
     notes="Sắp `workDate`, `startTime` tăng dần, trùng thì `shiftAssignmentId` tăng dần.")
m.op("post", "/shift-assignments", "createShiftAssignment", "Phân ca cho nhân viên", "Shift assignments", "UC34",
     "BR-NS-02, BR-NS-08, BR-NS-10", "A05", BM, "— → SCHEDULED (Phân ca#1)", body="CreateShiftAssignmentRequest",
     resp="ShiftAssignment", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-NS-02` ngày trong quá khứ · ca ngừng dùng · nhân viên không phải A06–A08 `ACTIVE` của chi nhánh · trùng hoặc chồng giờ phân ca khác",
               404: "Không tìm thấy nhân viên hoặc ca trong chi nhánh"})
m.op("post", "/shift-assignments/{shiftAssignmentId}/cancel", "cancelShiftAssignment", "Hủy phân ca",
     "Shift assignments", "UC34", "BR-NS-02, BR-NS-08, BR-NS-10", "A05", BM, "SCHEDULED → CANCELLED (Phân ca#2)",
     body="ReasonRequest", resp="ShiftAssignment", path_params=["ShiftAssignmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-02` ca đã bắt đầu (dùng ghi vắng đột xuất); `VALIDATION_FAILED` thiếu lý do",
               409: "Phân ca không còn `SCHEDULED`"},
     notes="Yêu cầu `PENDING` của phân ca tự `REJECTED` (Yêu cầu#5).")
m.op("post", "/shift-assignments/{shiftAssignmentId}/absent", "recordShiftAbsence", "Ghi nhận vắng đột xuất",
     "Shift assignments", "UC38", "BR-NS-07, BR-NS-08, BR-NS-10", "A05", BM, "SCHEDULED → ABSENT (Phân ca#4)",
     body="ReasonRequest", resp="ShiftAssignment", path_params=["ShiftAssignmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-07` ca của ngày tương lai · quá 7 ngày **[CFG]**; `VALIDATION_FAILED` thiếu lý do",
               409: "Phân ca không còn `SCHEDULED`"},
     notes="Yêu cầu `PENDING` của phân ca tự `REJECTED` (Yêu cầu#5).")
m.op("get", "/me/shift-assignments", "listMyShiftAssignments", "Lịch làm việc của tôi", "Shift assignments", "UC35",
     "BR-NS-03", "A06–A08", STAFF, resp="array:ShiftAssignment", query=["from!=date", "to!=date"],
     errors=(400, 401, 403),
     err_desc={400: "`from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày (`BR-NS-03`)"},
     notes="Không phân trang (tối đa 62 ngày). Sắp `workDate`, `startTime` tăng dần. Gồm cả phân ca đã nghỉ / vắng / hủy để hiển thị lịch sử.")

# ---- UC36/37 yêu cầu nghỉ / đổi ca
m.op("post", "/me/shift-requests", "createShiftRequest", "Gửi yêu cầu nghỉ / đổi ca", "Shift requests", "UC36",
     "BR-NS-04, BR-NS-08", "A06–A08", STAFF, "— → PENDING (Yêu cầu#1)", body="CreateShiftRequestRequest",
     resp="ShiftRequest", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-NS-04` phân ca không `SCHEDULED` · còn dưới 24 giờ **[CFG]** · đã có yêu cầu chờ · `SWAP` thiếu / thừa ca đích, ca đích khác ngày hoặc chồng giờ",
               404: "Không tìm thấy phân ca của mình"})
m.op("get", "/me/shift-requests", "listMyShiftRequests", "Yêu cầu nghỉ / đổi ca của tôi", "Shift requests", "UC36",
     "BR-NS-03", "A06–A08", STAFF, resp="ShiftRequest", page=True,
     query=["status=enum:PENDING,APPROVED,REJECTED,WITHDRAWN", "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`createdAt` giảm dần, trùng thì `shiftRequestId` giảm dần).")
m.op("post", "/me/shift-requests/{shiftRequestId}/withdraw", "withdrawShiftRequest", "Rút yêu cầu", "Shift requests",
     "UC36", "BR-NS-05", "A06–A08", "Người gửi", "PENDING → WITHDRAWN (Yêu cầu#2)", resp="ShiftRequest",
     path_params=["ShiftRequestId"], errors=(400, 401, 403, 404, 409), err_desc={409: "Yêu cầu không còn `PENDING`"})
m.op("get", "/shift-requests", "listShiftRequests", "Yêu cầu nghỉ / đổi ca của chi nhánh", "Shift requests", "UC37",
     "BR-NS-06", "A05", BM, resp="ShiftRequest", page=True,
     query=["status=enum:PENDING,APPROVED,REJECTED,WITHDRAWN", "page", "size"], errors=(400, 401, 403),
     notes="`status` vắng = `PENDING`. Cũ nhất trước (`createdAt` tăng dần, trùng thì `shiftRequestId` tăng dần) để xử lý theo thứ tự gửi.")
m.op("post", "/shift-requests/{shiftRequestId}/approve", "approveShiftRequest", "Duyệt yêu cầu", "Shift requests",
     "UC37", "BR-NS-06, BR-NS-08, BR-NS-10", "A05", BM, "PENDING → APPROVED (Yêu cầu#3); Phân ca#3 hoặc #5",
     resp="ShiftRequest", path_params=["ShiftRequestId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-06` phân ca đã bắt đầu · `SWAP`: ca đích ngừng dùng hoặc chồng giờ", 409: "Yêu cầu không còn `PENDING`"})
m.op("post", "/shift-requests/{shiftRequestId}/reject", "rejectShiftRequest", "Từ chối yêu cầu", "Shift requests",
     "UC37", "BR-NS-06, BR-NS-08, BR-NS-10", "A05", BM, "PENDING → REJECTED (Yêu cầu#4)",
     body="RejectShiftRequestRequest", resp="ShiftRequest", path_params=["ShiftRequestId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`VALIDATION_FAILED` thiếu lý do từ chối (`BR-NS-06`)", 409: "Yêu cầu không còn `PENDING`"})
