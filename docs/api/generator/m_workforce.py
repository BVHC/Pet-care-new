from lib import Module

m = Module(
    key="workforce", title="Workforce API v1 (tầng 3)", codes="NS", owner="BE-1",
    sources="01 UC34–UC38; 02 BR-NS-01…12; 03 #11 Yêu cầu nghỉ / đổi ca, #12 Phân ca; ADR-0123; 04 §12.1; 05 §14.1 (shift_definitions, shift_assignments, shift_requests)",
    intro="Ca làm việc, đăng ký ca theo tuần của nhân viên (và phân ca hộ của BRANCH_MANAGER), lịch làm việc, yêu cầu nghỉ / đổi ca và ghi nhận vắng đột xuất. *(ADR-0123, 2026-11-08)* nhân viên tự đăng ký ca cả tuần, mỗi tuần tối thiểu 10 ca **[CFG]**. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026). Đã cài: ca làm việc #1–3 (T58 bước 2, ADR-0138); các endpoint còn lại chưa có cài đặt.**",
    frozen=[
        "Danh sách nhân viên của chi nhánh (UC34 'xem nhân viên chi nhánh') dùng `GET /staff` của identity (UC08), không lặp ở đây.",
        "Hủy phân ca, từ chối yêu cầu khi nhân viên bị vô hiệu hóa / điều chuyển (BR-NS-09; Yêu cầu#5, Phân ca#6) là hệ quả hệ thống, không có endpoint.",
        "Lịch làm việc không chặn đặt lịch, hàng đợi, thu ngân (02 §7 ghi chú đầu mục). Kể cả phân ca `ON_LEAVE` / `ABSENT`: nhân viên đang nghỉ vẫn được gán Visit (ADR-0123).",
        "BRANCH_MANAGER không nằm trong lịch phân ca (chỉ A06–A08).",
    ],
    tags={
        "Shift definitions": "Ca làm việc của chi nhánh (UC34)",
        "Shift assignments": "Phân ca, lịch làm việc, vắng đột xuất (UC34, UC35, UC38)",
        "Shift requests": "Yêu cầu nghỉ / đổi ca và duyệt (UC36, UC37)",
        "Shift registrations": "Đăng ký ca theo tuần và thống kê số ca (UC35, UC34)",
    },
    security=[
        "Ca (tạo, sửa), phân ca hộ, hủy phân ca, duyệt yêu cầu, vắng đột xuất, lịch phân ca và thống kê số ca theo tuần: chỉ BRANCH_MANAGER của chính chi nhánh (BR-NS-01, 02, 06, 07, 12). SUPER_MANAGER không có chức năng nhân sự. Riêng `GET /shift-definitions` còn mở cho A06–A08 của chi nhánh, chỉ đọc và chỉ ca đang dùng, để nhân viên chọn ca khi đăng ký tuần và đổi ca (A10).",
        "Đăng ký ca theo tuần (BR-NS-11): nhân viên A06–A08 chỉ đăng ký cho chính mình; chỉ đụng phân ca `source = SELF` của mình, không hủy được ca `MANAGER` do quản lý phân hộ.",
        "Nhân viên A06–A08 chỉ xem lịch và yêu cầu của chính mình, chỉ gửi / rút yêu cầu cho phân ca của mình (BR-NS-03, 04, 05). Phân ca hoặc yêu cầu của người khác trả 404, không lộ sự tồn tại.",
        "BRANCH_MANAGER gọi với id phân ca / yêu cầu / ca thuộc chi nhánh khác trả 403 `ACCESS_DENIED_SCOPE_MISMATCH` (phạm vi chi nhánh, `00-method` §3.3), không phải 404.",
        "Mọi hệ quả của một thao tác (đổi yêu cầu + đổi phân ca + từ chối yêu cầu `PENDING` của phân ca bị hủy / ghi vắng + outbox) chạy trong **một transaction**; một bước lỗi thì rollback tất cả (03 mục Quy ước, ADR-0137). Thứ tự khóa của hủy phân ca, ghi vắng, rút / duyệt / từ chối: đọc không khóa để lấy `staffId` → `accounts` của nhân viên `FOR NO KEY UPDATE` → phân ca → yêu cầu → đọc lại dưới khóa rồi mới kiểm; cùng thứ tự với `PUT` đăng ký và phân hộ (ADR-0123 mục 8) để không đảo khóa.",
        "Thứ tự kiểm lỗi ở các thao tác chuyển trạng thái (hủy phân ca, ghi vắng, rút / duyệt / từ chối yêu cầu): quyền → tồn tại và phạm vi → **chuyển trạng thái hợp lệ (409)** → guard thời gian / nghiệp vụ (400). Khác thứ tự chung ở `00-method` §3.5, theo khuôn appointment (ADR-0049); xem A11.",
        "Thao tác của BRANCH_MANAGER ghi audit (BR-NS-10). Chồng giờ phân ca kiểm dưới khóa dòng tài khoản nhân viên (`FOR NO KEY UPDATE`) cộng `UNIQUE` partial ở DB để hai thao tác song song (đăng ký của nhân viên, phân hộ của quản lý) không cùng lọt (05 §13 mục 24, ADR-0123).",
        "Thông báo ghi `notification_outbox` trong cùng transaction nghiệp vụ (BR-NS-08).",
    ],
    assumptions=[
        ("A1", "Đổi ca (`SWAP`) là đổi sang một ca khác của chi nhánh trong cùng ngày, không phải đổi với đồng nghiệp", "Đã chốt (PO, 2026-11-08, ADR-0123): không đổi ca với người khác, chỉ đổi / nghỉ ca của bản thân"),
        ("A2", "Lịch làm việc chỉ ghi nhận việc đi làm tại chi nhánh, không chặn hay đổi hành vi của gán Visit / hàng đợi / đặt lịch / thu ngân; nhân viên `ON_LEAVE` / `ABSENT` vẫn được gán Visit", "Đã chốt (PO, 2026-11-08, ADR-0123); 02 §7 ghi chú: mệnh đề về ca làm trong QT đã gỡ ở v10; BR-TN-05 chỉ xét nhân viên ACTIVE"),
        ("A3", "Không có định biên ca (số người tối đa / tối thiểu mỗi ca): nhiều nhân viên đăng ký cùng một ca; duyệt nghỉ không kiểm số người còn lại", "Đã chốt (PO, 2026-11-08, ADR-0123)"),
        ("A4", "Gửi yêu cầu trước giờ bắt đầu ca ít nhất 24 giờ và ghi vắng đột xuất trong 7 ngày là [CFG] mới (`workforce.request_lead_hours`, `workforce.absence_report_max_days`)", "Đã có ở V40 và `ConfigKey` (T55b). Khóa thứ ba `workforce.weekly_required_shifts` (A7) thêm ở T58 (D062); BR-QT-13: giá trị mới chỉ áp cho lần gửi / đăng ký sau"),
        ("A5", "Hủy phân ca chỉ khi ca chưa bắt đầu; ca đã bắt đầu thì chỉ có ghi vắng đột xuất", "Tách rõ hai thao tác của BRANCH_MANAGER (UC34 và UC38)"),
        ("A6", "Nhân viên tự đăng ký ca cả tuần (thứ Hai–Chủ nhật) bằng một lần `PUT` thay cả tuần; hạn đến 00:00 thứ Hai của tuần đó; có hiệu lực ngay, không duyệt; không giới hạn xa", "PO chốt 2026-11-08 (ADR-0123)"),
        ("A7", "Mỗi tuần tối thiểu 10 ca là [CFG] chung `workforce.weekly_required_shifts` (ADMIN đổi qua UC10), chỉ chặn lúc đăng ký; sau đó nghỉ / hủy / vắng làm tuần dưới định mức thì không chặn, không cảnh báo trong phản hồi — BRANCH_MANAGER xem thống kê tuần", "PO chốt 2026-11-08 (ADR-0123); tránh TBD cách trả cảnh báo (convention)"),
        ("A8", "`PUT` chỉ thay các phân ca `source = SELF` của chính nhân viên; ca `MANAGER` không hủy được ở đây; một mục lỗi thì không lưu gì (một transaction)", "PO duyệt bản tổng hợp 2026-11-08 (ADR-0123)"),
        ("A9", "Trần 62 ngày của `from` / `to` áp cả cho `GET /shift-assignments` của BRANCH_MANAGER (BR-NS-03 chỉ nêu trần này cho lịch của nhân viên)", "Một trần chung, đơn giản và có cận cho danh sách phân trang; không có số khác trong đặc tả"),
        ("A10", "A06–A08 gọi `GET /shift-definitions` được (chỉ đọc, chỉ ca đang dùng của chi nhánh mình, `activeOnly` bị bỏ qua). `PATCH` với `active = true` bật lại ca đã ngừng dùng", "A06–A08 cần danh sách ca để điền `items` của `PUT` (BR-NS-11) và chọn ca đích của `SWAP` (BR-NS-04); BR-NS-01 chỉ nói 'ngừng dùng', ca không xóa được và tên không được trùng kể cả ca đã ngừng (05 §14.1) nên không bật lại được thì tên bị khóa vĩnh viễn"),
        ("A11", "Thứ tự kiểm lỗi của hủy phân ca, ghi vắng, rút / duyệt / từ chối yêu cầu: chuyển trạng thái hợp lệ (409) trước, guard thời gian / nghiệp vụ (400) sau", "Theo khuôn appointment (ADR-0049) và quyết định 14/11/2026 (ADR-0137): phân ca đã `ON_LEAVE` / `CANCELLED` mà ca đã qua thì báo 'không còn SCHEDULED' (409), không báo 'ca đã bắt đầu' (400). `POST /me/shift-requests` giữ 400 BR-NS-04 cho phân ca không `SCHEDULED` vì đó là điều kiện tạo Yêu cầu#1"),
        ("A12", "`ShiftRegistration.assignments` (kết quả của `PUT` và `GET` đăng ký tuần) chỉ gồm phân ca `SCHEDULED` của tuần, khớp `shiftCount`; `items` rỗng vẫn đi qua kiểm định mức (BR-NS-12)", "Ít phát minh nhất; lịch sử nghỉ / vắng / hủy xem ở `GET /me/shift-assignments`"),
    ],
    questions=[
        ("Q1", "Khi nhân viên bị vô hiệu hóa / điều chuyển, identity phát sự kiện nào cho workforce? `StaffLeftBranchEvent(staffId, branchId)` ở `identity.api` (06 §3).", "Đã chốt (T55b, 06 §6b #1): sự kiện đã khai báo, chưa phát; identity phát, workforce nhận (`MANDATORY`) ở T58", "BR-NS-09, BR-QT-06, BR-QT-09"),
        ("Q2", "Chặn chồng giờ ở DB bằng cột sao chép + `EXCLUDE USING gist` hay chỉ kiểm trong service?", "Đã chốt (ADR-0123): chỉ kiểm trong service dưới khóa `accounts` + `UNIQUE` partial ở DB", "05 §13 mục 24"),
        ("Q3", "BRANCH_MANAGER có được tự phân ca / xin nghỉ không (UC35–36 chỉ nêu A06–A08)?", "Đã chốt (PO, 2026-11-08): không, quản lý không nằm trong lịch", "01 UC35, UC36"),
        ("Q4", "Trần số phần tử `items` của `PUT /me/shift-registrations/{weekStart}`: có đặt trần để chặn request quá lớn không, và bao nhiêu?", "Đã chốt (2026-11-17, ADR-0137 mục 12): tối đa 100 phần tử (một tuần chỉ có 7 ngày, 100 chặn request quá lớn mà không cản nghiệp vụ); vượt → 400 `VALIDATION_FAILED`", "BR-NS-11, BR-NS-02"),
    ],
)

m.param("ShiftDefinitionId", "shiftDefinitionId", "path", "int64")
m.param("ShiftAssignmentId", "shiftAssignmentId", "path", "int64")
m.param("ShiftRequestId", "shiftRequestId", "path", "int64")
m.param("WeekStart", "weekStart", "path", "date|Thứ Hai của tuần (giờ Việt Nam), dạng yyyy-MM-dd")

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
m.schema("ShiftAssignmentSource", None, enum=["SELF", "MANAGER"], desc="SELF = nhân viên tự đăng ký (BR-NS-11); MANAGER = BRANCH_MANAGER phân hộ (BR-NS-02)")
m.schema("ShiftAssignment", {
    "shiftAssignmentId": "int64", "branchId": "int64", "staffId": "int64", "staffName": "string",
    "shiftDefinitionId": "int64", "shiftName": "string", "startTime": "time", "endTime": "time",
    "workDate": "date", "status": "ref:ShiftAssignmentStatus", "source": "ref:ShiftAssignmentSource", "reason": "string?|Lý do vắng hoặc hủy",
    "pendingRequestId": "int64?|Yêu cầu PENDING của phân ca, nếu có (BR-NS-04)",
})
m.schema("CreateShiftAssignmentRequest", {"staffId": "int64", "shiftDefinitionId": "int64", "workDate": "date"})
m.schema("ShiftReasonRequest", {"reason": "string:300"})
m.schema("ShiftRegistrationItem", {"workDate": "date|Phải nằm trong tuần của đường dẫn", "shiftDefinitionId": "int64|Ca đang dùng của chi nhánh"})
m.schema("PutShiftRegistrationRequest", {"items": "array:ref:ShiftRegistrationItem|Toàn bộ (ngày, ca) nhân viên muốn có trong tuần; thay thế các phân ca SELF hiện có (BR-NS-11)"})
m.schema("ShiftRegistration", {
    "weekStart": "date", "requiredShifts": "int|[CFG] workforce.weekly_required_shifts (BR-NS-12)",
    "shiftCount": "int|Số phân ca SCHEDULED của tuần, cả SELF và MANAGER",
    "assignments": "array:ref:ShiftAssignment|Mọi phân ca của nhân viên trong tuần sau khi lưu",
})
m.schema("ShiftWeeklySummary", {
    "staffId": "int64", "staffName": "string", "role": "enum:RECEPTIONIST,VET,CARETAKER",
    "shiftCount": "int|Số phân ca SCHEDULED của tuần", "requiredShifts": "int", "meetsRequirement": "bool",
})
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
     "UC34, UC35, UC36", "BR-NS-01", "A05, A06–A08", "BRANCH_MANAGER của chi nhánh; A06–A08 của chi nhánh mình (chỉ đọc)",
     resp="array:ShiftDefinition", query=["activeOnly=bool"], errors=(400, 401, 403),
     notes="Không phân trang. BRANCH_MANAGER: `activeOnly` vắng = false (cả ca ngừng dùng). A06–A08 chỉ nhận ca đang dùng, `activeOnly` bị bỏ qua (A10); nhân viên cần danh sách này để điền `items` của `PUT` đăng ký tuần và chọn ca đích của `SWAP`. Sắp theo `startTime` tăng dần.")
m.op("post", "/shift-definitions", "createShiftDefinition", "Tạo ca làm việc", "Shift definitions", "UC34",
     "BR-NS-01, BR-NS-10", "A05", BM, body="CreateShiftDefinitionRequest", resp="ShiftDefinition", status=201,
     errors=(400, 401, 403, 409),
     err_desc={400: "`BR-NS-01` tên trùng trong chi nhánh · giờ bắt đầu không nhỏ hơn giờ kết thúc; `VALIDATION_FAILED` sai kiểu / quá dài",
               409: "`CONCURRENCY_CONFLICT` hai yêu cầu cùng tạo / đổi tên thành một tên trùng nhau cùng lúc (ràng buộc tên duy nhất): tải lại và thử lại"})
m.op("patch", "/shift-definitions/{shiftDefinitionId}", "updateShiftDefinition", "Sửa / ngừng dùng ca làm việc",
     "Shift definitions", "UC34", "BR-NS-01, BR-NS-10", "A05", BM, body="UpdateShiftDefinitionRequest",
     resp="ShiftDefinition", path_params=["ShiftDefinitionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-01` tên trùng · giờ bắt đầu không nhỏ hơn giờ kết thúc · sửa giờ khi ca đã có phân ca · ngừng dùng khi ca còn phân ca `SCHEDULED` từ hôm nay; `VALIDATION_FAILED` body không có trường nào · sai kiểu / quá dài",
               403: "`ACCESS_DENIED_SCOPE_MISMATCH` ca thuộc chi nhánh khác",
               409: "`CONCURRENCY_CONFLICT` hai yêu cầu cùng tạo / đổi tên thành một tên trùng nhau cùng lúc (ràng buộc tên duy nhất): tải lại và thử lại"},
     notes="Chỉ các trường có trong body được đổi; body không có trường nào là lỗi. `active = true` bật lại ca đã ngừng dùng (A10).")

# ---- UC34/35/38 phân ca
m.op("get", "/shift-assignments", "listShiftAssignments", "Lịch phân ca của chi nhánh", "Shift assignments", "UC34",
     "BR-NS-03", "A05", BM, resp="ShiftAssignment", page=True,
     query=["from!=date", "to!=date", "staffId=int64", "status=enum:SCHEDULED,ON_LEAVE,ABSENT,CANCELLED", "page", "size"],
     errors=(400, 401, 403),
     err_desc={400: "`from` / `to` thiếu hoặc sai kiểu (`MALFORMED_REQUEST`) · `to` < `from` hoặc khoảng quá 62 ngày tính cả hai đầu (`BR-NS-03`, A9)"},
     notes="Sắp `workDate`, `startTime` tăng dần, trùng thì `shiftAssignmentId` tăng dần. Chi nhánh đông nhân viên có thể vượt một trang (`size` tối đa 100): màn lịch tuần gọi thêm trang. Luôn là chi nhánh của người gọi; `staffId` của chi nhánh khác cho trang rỗng.")
m.op("post", "/shift-assignments", "createShiftAssignment", "Phân ca cho nhân viên", "Shift assignments", "UC34",
     "BR-NS-02, BR-NS-10", "A05", BM, "— → SCHEDULED (Phân ca#1)", body="CreateShiftAssignmentRequest",
     resp="ShiftAssignment", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-NS-02` ngày trong quá khứ · ca ngừng dùng · nhân viên không phải A06–A08 `ACTIVE` của chi nhánh · trùng hoặc chồng giờ phân ca khác",
               404: "Không tìm thấy nhân viên hoặc ca trong chi nhánh (id của chi nhánh khác và id không tồn tại trả cùng một dạng lỗi)"},
     notes="Phân hộ (đường dự phòng, ví dụ nhân viên mới vào giữa tuần): tạo phân ca `source = MANAGER`. Không thông báo cho nhân viên (BR-NS-08 không liệt kê phân hộ). Không kiểm định mức tuần (BR-NS-12). Chồng giờ là hai khoảng giờ giao nhau thật sự; hai ca liền kề (kết thúc = bắt đầu) không chồng. Nhân viên tự đăng ký dùng `PUT /me/shift-registrations/{weekStart}`.")
m.op("post", "/shift-assignments/{shiftAssignmentId}/cancel", "cancelShiftAssignment", "Hủy phân ca",
     "Shift assignments", "UC34", "BR-NS-02, BR-NS-08, BR-NS-10", "A05", BM, "SCHEDULED → CANCELLED (Phân ca#2)",
     body="ShiftReasonRequest", resp="ShiftAssignment", path_params=["ShiftAssignmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-02` ca đã bắt đầu (dùng ghi vắng đột xuất); `VALIDATION_FAILED` thiếu lý do",
               403: "`ACCESS_DENIED_SCOPE_MISMATCH` phân ca thuộc chi nhánh khác",
               409: "`INVALID_STATE_TRANSITION` phân ca không còn `SCHEDULED` (kiểm trước guard 400, A11)"},
     notes="Cùng một transaction: phân ca → `CANCELLED` và yêu cầu `PENDING` của nó → `REJECTED` (Yêu cầu#5; `decision_note` do hệ thống ghi, `decided_by` rỗng); một bước lỗi thì rollback cả hai. Thông báo ghi outbox cùng transaction (BR-NS-08).")
m.op("post", "/shift-assignments/{shiftAssignmentId}/absent", "recordShiftAbsence", "Ghi nhận vắng đột xuất",
     "Shift assignments", "UC38", "BR-NS-07, BR-NS-08, BR-NS-10", "A05", BM, "SCHEDULED → ABSENT (Phân ca#4)",
     body="ShiftReasonRequest", resp="ShiftAssignment", path_params=["ShiftAssignmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-07` ca của ngày tương lai · quá 7 ngày **[CFG]**; `VALIDATION_FAILED` thiếu lý do",
               403: "`ACCESS_DENIED_SCOPE_MISMATCH` phân ca thuộc chi nhánh khác",
               409: "`INVALID_STATE_TRANSITION` phân ca không còn `SCHEDULED` (kiểm trước guard 400, A11)"},
     notes="Cùng một transaction: phân ca → `ABSENT` và yêu cầu `PENDING` của nó → `REJECTED` (Yêu cầu#5; `decision_note` do hệ thống ghi, `decided_by` rỗng); một bước lỗi thì rollback cả hai. Thông báo ghi outbox cùng transaction (BR-NS-08).")
m.op("get", "/me/shift-assignments", "listMyShiftAssignments", "Lịch làm việc của tôi", "Shift assignments", "UC35",
     "BR-NS-03", "A06–A08", STAFF, resp="array:ShiftAssignment", query=["from!=date", "to!=date"],
     errors=(400, 401, 403),
     err_desc={400: "`from` / `to` thiếu hoặc `to` < `from` hoặc khoảng quá 62 ngày (`BR-NS-03`)"},
     notes="Không phân trang (tối đa 62 ngày). Sắp `workDate`, `startTime` tăng dần. Gồm cả phân ca đã nghỉ / vắng / hủy để hiển thị lịch sử.")

# ---- UC36/37 yêu cầu nghỉ / đổi ca
m.op("post", "/me/shift-requests", "createShiftRequest", "Gửi yêu cầu nghỉ / đổi ca", "Shift requests", "UC36",
     "BR-NS-04, BR-NS-08", "A06–A08", STAFF, "— → PENDING (Yêu cầu#1)", body="CreateShiftRequestRequest",
     resp="ShiftRequest", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-NS-04` phân ca không `SCHEDULED` · còn dưới 24 giờ **[CFG]** · đã có yêu cầu chờ · `SWAP` thiếu ca đích, `LEAVE` có ca đích · ca đích không đang dùng hoặc trùng ca hiện tại · ca đích chồng giờ phân ca *khác* của người đó trong ngày (không tính chính ca đang đổi); `VALIDATION_FAILED` thiếu / quá dài `reason`",
               404: "Không tìm thấy phân ca của mình · ca đích không có trong chi nhánh"})
m.op("get", "/me/shift-requests", "listMyShiftRequests", "Yêu cầu nghỉ / đổi ca của tôi", "Shift requests", "UC36",
     "BR-NS-03", "A06–A08", STAFF, resp="ShiftRequest", page=True,
     query=["status=enum:PENDING,APPROVED,REJECTED,WITHDRAWN", "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`createdAt` giảm dần, trùng thì `shiftRequestId` giảm dần).")
m.op("post", "/me/shift-requests/{shiftRequestId}/withdraw", "withdrawShiftRequest", "Rút yêu cầu", "Shift requests",
     "UC36", "BR-NS-05", "A06–A08", "Người gửi", "PENDING → WITHDRAWN (Yêu cầu#2)", resp="ShiftRequest",
     path_params=["ShiftRequestId"], errors=(400, 401, 403, 404, 409),
     err_desc={404: "Yêu cầu không có hoặc không phải của mình (kể cả yêu cầu của đồng nghiệp cùng chi nhánh)",
               409: "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING`"},
     notes="Chỉ đổi yêu cầu → `WITHDRAWN` (Yêu cầu#2); phân ca giữ nguyên.")
m.op("get", "/shift-requests", "listShiftRequests", "Yêu cầu nghỉ / đổi ca của chi nhánh", "Shift requests", "UC37",
     "BR-NS-06", "A05", BM, resp="ShiftRequest", page=True,
     query=["status=enum:PENDING,APPROVED,REJECTED,WITHDRAWN", "page", "size"], errors=(400, 401, 403),
     notes="`status` vắng = `PENDING`. Cũ nhất trước (`createdAt` tăng dần, trùng thì `shiftRequestId` tăng dần) để xử lý theo thứ tự gửi.")
m.op("post", "/shift-requests/{shiftRequestId}/approve", "approveShiftRequest", "Duyệt yêu cầu", "Shift requests",
     "UC37", "BR-NS-06, BR-NS-08, BR-NS-10", "A05", BM, "PENDING → APPROVED (Yêu cầu#3); Phân ca#3 hoặc #5",
     resp="ShiftRequest", path_params=["ShiftRequestId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-NS-06` phân ca đã bắt đầu · `SWAP`: ca đích ngừng dùng hoặc chồng giờ",
               403: "`ACCESS_DENIED_SCOPE_MISMATCH` yêu cầu thuộc chi nhánh khác",
               409: "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING` (kiểm trước guard 400, A11)"},
     notes="Cùng một transaction: yêu cầu → `APPROVED` (Yêu cầu#3) và phân ca → `ON_LEAVE` (Phân ca#3, `LEAVE`) hoặc đổi sang ca đích (Phân ca#5, `SWAP`); một bước lỗi thì rollback cả hai. Thông báo ghi outbox cùng transaction (BR-NS-08).")
m.op("post", "/shift-requests/{shiftRequestId}/reject", "rejectShiftRequest", "Từ chối yêu cầu", "Shift requests",
     "UC37", "BR-NS-06, BR-NS-08, BR-NS-10", "A05", BM, "PENDING → REJECTED (Yêu cầu#4)",
     body="RejectShiftRequestRequest", resp="ShiftRequest", path_params=["ShiftRequestId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`VALIDATION_FAILED` thiếu lý do từ chối (`BR-NS-06`)",
               403: "`ACCESS_DENIED_SCOPE_MISMATCH` yêu cầu thuộc chi nhánh khác",
               409: "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING` (kiểm trước guard 400, A11)"},
     notes="Chỉ đổi yêu cầu → `REJECTED` (Yêu cầu#4); phân ca giữ nguyên. Thông báo ghi outbox cùng transaction (BR-NS-08).")

# ---- UC35/34 đăng ký ca theo tuần (ADR-0123)
m.op("put", "/me/shift-registrations/{weekStart}", "putMyShiftRegistration", "Đăng ký ca theo tuần (thay cả tuần)",
     "Shift registrations", "UC35", "BR-NS-11, BR-NS-12", "A06–A08", STAFF,
     "— → SCHEDULED (Phân ca#1) · SCHEDULED → CANCELLED (Phân ca#7)", body="PutShiftRegistrationRequest",
     resp="ShiftRegistration", path_params=["WeekStart"], errors=(400, 401, 403, 409),
     err_desc={400: "`BR-NS-11` `weekStart` không phải thứ Hai · quá hạn (sau 00:00 thứ Hai của tuần) · ngày ngoài tuần · ca ngừng dùng hoặc không thuộc chi nhánh · trùng cặp (ngày, ca) trong lô · chồng giờ với phân ca khác (kể cả `MANAGER`); `BR-NS-12` tập kết quả của tuần chưa đủ định mức (kể cả `items` rỗng). Một lỗi thì không lưu gì, `message` liệt kê mọi mục lỗi; `VALIDATION_FAILED` thiếu `items`, sai kiểu hoặc quá 100 phần tử",
               409: "`CONCURRENCY_CONFLICT` trùng cặp ca – ngày do thao tác song song"},
     notes="Idempotent: gửi lại cùng nội dung không đổi kết quả. Mục thiếu so với phân ca `SELF` hiện có bị hủy (lý do hệ thống ghi 'Nhân viên sửa đăng ký tuần'), mục mới được tạo, mục giữ nguyên không đổi; phân ca `MANAGER` không bị đụng nhưng được tính vào định mức. Không thông báo, không audit (BR-NS-08, 10). Yêu cầu `PENDING` của phân ca bị hủy tự `REJECTED` (Yêu cầu#5). Mọi mục tạo / hủy và Yêu cầu#5 nằm trong **một** transaction. Response chỉ gồm phân ca `SCHEDULED` của tuần (A12). Đọc đăng ký hiện có và định mức trước khi sửa: `GET /me/shift-registrations/{weekStart}`; xem lịch đầy đủ (cả nghỉ / vắng / hủy): `GET /me/shift-assignments`. `items` tối đa 100 phần tử, vượt thì 400 `VALIDATION_FAILED` (Q4, ADR-0137 mục 12).")
m.op("get", "/shift-weekly-summaries", "listShiftWeeklySummaries", "Thống kê số ca mỗi nhân viên trong tuần",
     "Shift registrations", "UC34", "BR-NS-12", "A05", BM, resp="array:ShiftWeeklySummary", query=["weekStart!=date"],
     errors=(400, 401, 403),
     err_desc={400: "`weekStart` thiếu hoặc không phải thứ Hai (`BR-NS-11`)"},
     notes="Không phân trang. Mọi nhân viên A06–A08 `ACTIVE` của chi nhánh, kể cả người chưa có ca nào. Sắp `staffName` tăng dần. Chỉ để quản lý theo dõi, không chặn gì (BR-NS-12).")
m.op("get", "/me/shift-registrations/{weekStart}", "getMyShiftRegistration", "Đăng ký ca của tôi trong tuần",
     "Shift registrations", "UC35", "BR-NS-11, BR-NS-12", "A06–A08", STAFF, resp="ShiftRegistration",
     path_params=["WeekStart"], errors=(400, 401, 403),
     err_desc={400: "`BR-NS-11` `weekStart` không phải thứ Hai"},
     notes="Chỉ đọc, dùng để nạp phân ca hiện có và `requiredShifts` (định mức [CFG]) trước khi gọi `PUT` thay cả tuần. Không kiểm hạn đăng ký: xem được cả tuần đã qua hạn. Cùng dạng response với `PUT`: chỉ phân ca `SCHEDULED` của tuần (A12); tuần chưa có phân ca trả `assignments` rỗng, `shiftCount` = 0.")
