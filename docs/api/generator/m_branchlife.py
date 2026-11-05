from lib import Module

m = Module(
    key="branchlife", title="Branch Lifecycle API v1 (tầng 3)", codes="CN (tạm ngừng / đóng cửa, UC13)", owner="BE-2",
    sources="01 UC13; 08-tang3-be2-spec.md §F.1 (BR-CN-06…10; 03 #20 Chi nhánh mở rộng); 05 bổ sung cột suspended_at, expected_resume_on, closed_at… của branches",
    intro="Xem trước tồn đọng, tạm ngừng, mở lại và đóng cửa chi nhánh. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Kích hoạt chi nhánh `DRAFT → ACTIVE` ở branch-v1 (`POST /branches/{branchId}/activate`), không lặp ở đây.",
        "Hủy hàng loạt lịch hẹn và đặt chỗ chỉ phát `BranchClinicCancellationEvent` (06 §3); module appointment / boarding tự hủy và thông báo khách.",
        "Chuyển tồn kho đi dùng stockops-v1 (UC77) hoặc điều chỉnh giảm inventory-v1 (UC76), không có endpoint chuyển giao trong module này.",
    ],
    tags={"Branch lifecycle": "Tạm ngừng, mở lại, đóng cửa chi nhánh (UC13)"},
    security=[
        "Mọi endpoint chỉ SUPER_MANAGER (BR-CN-06); chi nhánh `DRAFT` không tạm ngừng / đóng.",
        "Kiểm điều kiện chặn và thực hiện chạy trong cùng transaction, khóa chi nhánh trước khi đọc (ADR-0106; `BranchApi.lockForBooking`).",
        "Mọi thao tác ghi audit kèm lý do (BR-QT-15).",
    ],
    assumptions=[
        ("A1", "Tạm ngừng chỉ chặn việc đang dở tại chỗ (Visit mở, thú trong chuồng, ca thu ngân mở); lịch đặt trước xử lý bằng lựa chọn hủy hàng loạt", "BR-CN-08, BR-CN-09"),
        ("A2", "Đóng cửa phải dọn sạch tồn kho và nhân viên trước", "Tránh dữ liệu mồ côi; BR-CN-08"),
    ],
    questions=[
        ("Q1", "Care Task `OPEN` khi đóng chi nhánh: chuyển sang chi nhánh khác hay luôn hủy", "TBD (BE-1)", "BR-CN-09"),
        ("Q2", "Tạm ngừng có cần ngày dự kiến mở lại bắt buộc không", "TBD (PO)", "BR-CN-10"),
    ],
)

m.param("BranchId", "branchId", "path", "int64")

m.schema("BranchLifecycleStatus", None, enum=["DRAFT", "ACTIVE", "SUSPENDED", "CLOSED"], desc="03 #2 và #20")
m.schema("ClosureImpact", {
    "bookedAppointments": "int", "activeBoardingStays": "int", "openVisits": "int", "openCashierShifts": "int",
    "openOrders": "int", "unfinishedOnlineOrders": "int", "openCareTasks": "int", "pendingRefunds": "int",
    "inTransitTransfers": "int", "openStockCounts": "int", "stockItemCount": "int", "stockTotalQuantity": "int",
    "activeStaff": "int",
    "suspendBlockers": "array:string|Điều kiện còn vướng nếu tạm ngừng ngay (BR-CN-08); rỗng = được",
    "closeBlockers": "array:string|Điều kiện còn vướng nếu đóng cửa ngay (BR-CN-08); rỗng = được",
})
m.schema("SuspendBranchRequest", {
    "reason": "string:300", "expectedResumeOn": "date?", "cancelBookings": "bool|true = hủy hàng loạt lịch hẹn, đặt chỗ `BOOKED` và thông báo khách (BR-CN-09)",
    "careTaskTargetBranchId": "int64?|Chi nhánh nhận Care Task `OPEN`; vắng = hủy kèm lý do",
})
m.schema("CloseBranchRequest", {
    "reason": "string:300", "cancelBookings": "bool", "careTaskTargetBranchId": "int64?",
})
m.schema("BranchLifecycle", {
    "branchId": "int64", "status": "ref:BranchLifecycleStatus", "suspendedAt": "datetime?", "expectedResumeOn": "date?",
    "closedAt": "datetime?", "reason": "string?",
})

A04 = "Chỉ SUPER_MANAGER"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái chi nhánh không cho phép (03 #20)"
BLOCK = "`BR-CN-08` còn điều kiện chặn (message liệt kê) · `BR-CN-09` còn lịch `BOOKED` mà chưa chọn hủy hàng loạt"

m.op("get", "/branches/{branchId}/closure-impact", "getBranchClosureImpact", "Xem trước tồn đọng khi tạm ngừng / đóng cửa",
     "Branch lifecycle", "UC13", "BR-CN-07, 08", "A04", A04, resp="ClosureImpact", path_params=["BranchId"],
     errors=(400, 401, 403, 404), notes="Không ghi gì, giống `…/impact` của branch-v1 (BR-CN-07).")
m.op("post", "/branches/{branchId}/suspend", "suspendBranch", "Tạm ngừng chi nhánh", "Branch lifecycle", "UC13",
     "BR-CN-06…10", "A04", A04, "ACTIVE → SUSPENDED (Chi nhánh mở rộng#1)", body="SuspendBranchRequest",
     resp="BranchLifecycle", path_params=["BranchId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: BLOCK, 409: TRANS})
m.op("post", "/branches/{branchId}/resume", "resumeBranch", "Mở lại chi nhánh đang tạm ngừng", "Branch lifecycle", "UC13",
     "BR-CN-10, BR-QT-04", "A04", A04, "SUSPENDED → ACTIVE (Chi nhánh mở rộng#2)", resp="BranchLifecycle",
     path_params=["BranchId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-CN-01` chưa có giờ mở cửa hợp lệ · `BR-QT-04` chưa có BRANCH_MANAGER `ACTIVE`", 409: TRANS})
m.op("post", "/branches/{branchId}/close", "closeBranch", "Đóng cửa chi nhánh vĩnh viễn", "Branch lifecycle", "UC13",
     "BR-CN-06…10", "A04", A04, "ACTIVE / SUSPENDED → CLOSED (Chi nhánh mở rộng#3, #4)", body="CloseBranchRequest",
     resp="BranchLifecycle", path_params=["BranchId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: BLOCK + " · còn tồn kho > 0 · còn nhân viên `ACTIVE`", 409: TRANS},
     notes="Không mở lại được. Dữ liệu lịch sử giữ nguyên.")
