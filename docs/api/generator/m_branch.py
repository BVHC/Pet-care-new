from lib import Module

m = Module(
    key="branch", title="Branch API v1", codes="CN (và cấu hình quota UC42, bật/tắt dịch vụ UC33)", owner="BE-2",
    sources="01 UC12, UC14, UC15, UC33, UC42; 02 BR-CN-01…05, BR-LH-01, 03, 10, BR-QT-04, BR-CK-01; 03 #2 Chi nhánh; 04 §2; 05 §2 (branches, opening_hours, holidays, branch_services, branch_quota_defaults, slot_quotas)",
    intro="Chi nhánh, giờ mở cửa, ngày nghỉ (kèm hủy hàng loạt), bật/tắt dịch vụ, quota lịch hẹn, trang công khai chi nhánh.",
    frozen=[
        "UC13 tạm ngừng / đóng cửa chi nhánh là tầng 3, không có endpoint.",
        "Khung giờ trống để đặt lịch (BR-LH-02) thuộc module appointment: `GET /branches/{branchId}/available-slots`.",
        "Hủy hàng loạt chỉ phát `BranchClinicCancellationEvent`; module appointment / boarding tự hủy và thông báo khách (06-module-contracts §3).",
    ],
    tags={
        "Branches": "Chi nhánh (UC12)",
        "Opening hours": "Giờ mở cửa và ngày nghỉ (UC14)",
        "Branch services": "Bật / tắt dịch vụ tại chi nhánh (UC33)",
        "Quotas": "Quota lịch hẹn (UC42)",
        "Public": "Chi nhánh công khai (UC15)",
    },
    security=[
        "Tạo, sửa, kích hoạt chi nhánh và cờ cấp cứu ngoài giờ: chỉ SUPER_MANAGER (BR-CN-05).",
        "Giờ mở cửa, ngày nghỉ, dịch vụ tại chi nhánh, quota: chỉ BRANCH_MANAGER của chính chi nhánh đó.",
        "Thêm ngày nghỉ / đổi giờ có ảnh hưởng mà chưa chọn hủy hàng loạt thì từ chối (BR-CN-04, BR-LH-10). Kiểm tra ảnh hưởng và lưu chạy trong cùng transaction để không lọt lịch đặt chen giữa hai bước.",
        "API public chỉ trả chi nhánh ACTIVE (BR-CK-01).",
    ],
    assumptions=[
        ("A1", "Xem trước ảnh hưởng là endpoint riêng (`…/impact`) không ghi gì; lưu thật mới kiểm tra lại", "BR-CN-04 / BR-LH-10 yêu cầu hiển thị danh sách bị ảnh hưởng trước khi quản lý quyết định"),
        ("A2", "Chi nhánh DRAFT được đặt giờ mở cửa hiệu lực từ hôm nay; ràng buộc 'sớm nhất là ngày mai' áp dụng khi đổi giờ của chi nhánh đã có giờ", "BR-CN-04 nói về thay đổi; chi nhánh mới cần giờ để kích hoạt (BR-CN-01)"),
        ("A3", "Được xóa ngày nghỉ trong tương lai; lịch đã bị hủy hàng loạt không khôi phục", "UC14 'cấu hình ngày nghỉ'; docs không có rule khôi phục"),
        ("A4", "Xóa quota riêng của một khung để quay về quota mặc định", "BR-LH-03: quota riêng là ngoại lệ của quota mặc định"),
        ("A5", "PUT giờ mở cửa gửi đủ 7 ngày của một bản hiệu lực; ngày không gửi khoảng nào là nghỉ cố định", "05 `opening_hours`: mỗi dòng một thứ trong tuần theo ngày hiệu lực"),
    ],
    questions=[],
)

m.param("BranchId", "branchId", "path", "int64")
m.param("HolidayId", "holidayId", "path", "int64")
m.param("ServiceId", "serviceId", "path", "int64")
m.param("ServiceGroupPath", "serviceGroup", "path", "enum:MEDICAL,GROOMING")
m.param("SlotQuotaId", "slotQuotaId", "path", "int64")

m.schema("BranchStatus", None, enum=["DRAFT", "ACTIVE"], desc="03 #2")
m.schema("Branch", {
    "branchId": "int64", "name": "string:150", "address": "string:300", "phone": "phone",
    "latitude": "decimal", "longitude": "decimal", "status": "ref:BranchStatus",
    "acceptsAfterHoursEmergency": "bool|BR-CN-05", "activatedAt": "datetime?",
})
m.schema("CreateBranchRequest", {
    "name": "string:150", "address": "string:300", "phone": "phone", "latitude": "decimal", "longitude": "decimal",
    "acceptsAfterHoursEmergency": "bool?",
})
m.schema("UpdateBranchRequest", {
    "name": "string:150?", "address": "string:300?", "phone": "phone?", "latitude": "decimal?", "longitude": "decimal?",
    "acceptsAfterHoursEmergency": "bool?",
}, required=[])
m.schema("TimeRange", {"open": "time", "close": "time|> open"})
m.schema("DayHours", {
    "dayOfWeek": "int|1 = Thứ Hai … 7 = Chủ nhật (ISO)",
    "ranges": "array:ref:TimeRange|0–2 khoảng không chồng nhau; rỗng = nghỉ cố định (BR-CN-02)",
})
m.schema("OpeningHoursVersion", {
    "effectiveFrom": "date", "days": "array:ref:DayHours|Đủ 7 ngày",
})
m.schema("SetOpeningHoursRequest", {
    "effectiveFrom": "date|Sớm nhất là ngày mai khi đổi giờ (BR-CN-04, A2)",
    "days": "array:ref:DayHours|Đủ 7 ngày (A5)",
    "cancelAffected": "bool?|true = hủy hàng loạt lịch / đặt chỗ bị ảnh hưởng (BR-CN-04, BR-LH-10)",
}, required=["effectiveFrom", "days"])
m.schema("OpeningHoursImpactRequest", {"effectiveFrom": "date", "days": "array:ref:DayHours"})
m.schema("AffectedAppointment", {
    "appointmentId": "int64", "code": "string", "slotDate": "date", "slotStart": "time",
    "petName": "string", "customerName": "string", "customerPhone": "phone?",
})
m.schema("AffectedBoarding", {
    "bookingId": "int64", "code": "string", "checkInDate": "date", "checkOutDate": "date",
    "petName": "string", "customerName": "string", "customerPhone": "phone?",
})
m.schema("ScheduleImpact", {
    "appointments": "array:ref:AffectedAppointment|Lịch BOOKED rơi ra ngoài giờ / vào ngày nghỉ",
    "boardingBookings": "array:ref:AffectedBoarding|Đặt chỗ BOOKED có ngày nhận hoặc trả rơi vào ngày nghỉ",
}, desc="Danh sách hiển thị trước khi quyết định; khách không có email để lễ tân gọi điện (06 Q4)")
m.schema("Holiday", {"holidayId": "int64", "holidayDate": "date", "reason": "string:200?"})
m.schema("HolidayImpactRequest", {"holidayDate": "date"})
m.schema("CreateHolidayRequest", {
    "holidayDate": "date|Ngày trong tương lai", "reason": "string:200?",
    "cancelAffected": "bool?|true = hủy hàng loạt (BR-LH-10)",
}, required=["holidayDate"])
m.schema("BranchServiceItem", {
    "serviceId": "int64", "name": "string", "group": "enum:MEDICAL,GROOMING,BOARDING", "enabled": "bool",
})
m.schema("SetBranchServiceRequest", {"enabled": "bool"})
m.schema("QuotaDefault", {
    "serviceGroup": "enum:MEDICAL,GROOMING", "defaultQuota": "int?|null = chưa cấu hình, hiệu lực là 1 (BR-LH-03)",
})
m.schema("SetQuotaDefaultRequest", {"defaultQuota": "int|≥ 0"})
m.schema("SlotQuota", {
    "slotQuotaId": "int64", "serviceGroup": "enum:MEDICAL,GROOMING", "slotDate": "date",
    "slotStart": "time|Khung 30 phút bắt đầu lúc này", "quota": "int|0 = khóa khung",
})
m.schema("SetSlotQuotaRequest", {
    "serviceGroup": "enum:MEDICAL,GROOMING", "slotDate": "date", "slotStart": "time", "quota": "int|≥ 0",
})
m.schema("PublicBranch", {
    "branchId": "int64", "name": "string", "address": "string", "phone": "phone", "latitude": "decimal",
    "longitude": "decimal", "acceptsAfterHoursEmergency": "bool|Hiển thị nhận cấp cứu 24/7 (BR-CN-05)",
    "weeklyHours": "array:ref:DayHours|Giờ đang áp dụng",
})

SM = "Chỉ SUPER_MANAGER"
BM = "BRANCH_MANAGER của chi nhánh"
m.op("get", "/branches", "listBranches", "Danh sách chi nhánh", "Branches", "UC12", "BR-CN-01", "A03–A08",
     "SUPER_MANAGER, ADMIN: tất cả · nhân viên chi nhánh: chi nhánh mình", resp="array:Branch",
     query=["status=enum:DRAFT,ACTIVE"], errors=(401, 403))
m.op("post", "/branches", "createBranch", "Tạo chi nhánh", "Branches", "UC12", "BR-CN-01, 05", "A04", SM,
     "— → DRAFT (Chi nhánh#1)", body="CreateBranchRequest", resp="Branch", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-CN-01` thiếu tên, địa chỉ, SĐT hoặc tọa độ · tên trùng"})
m.op("get", "/branches/{branchId}", "getBranch", "Chi tiết chi nhánh", "Branches", "UC12", "—", "A03–A08",
     "Như listBranches", resp="Branch", path_params=["BranchId"], errors=(401, 403, 404))
m.op("patch", "/branches/{branchId}", "updateBranch", "Sửa chi nhánh / bật cờ cấp cứu ngoài giờ", "Branches",
     "UC12", "BR-CN-01, 05", "A04", SM, body="UpdateBranchRequest", resp="Branch", path_params=["BranchId"],
     errors=(400, 401, 403, 404))
m.op("post", "/branches/{branchId}/activate", "activateBranch", "Kích hoạt chi nhánh", "Branches", "UC12",
     "BR-CN-01, BR-QT-04", "A04", SM, "DRAFT → ACTIVE (Chi nhánh#2)", resp="Branch", path_params=["BranchId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-QT-04` chưa có BRANCH_MANAGER · `BR-CN-01` chưa cấu hình giờ mở cửa (message ghi điều kiện còn thiếu)",
               409: "Chi nhánh đã ACTIVE"},
     notes="Từ đây hiển thị công khai và sinh khung giờ đặt lịch.")

m.op("get", "/branches/{branchId}/opening-hours", "getOpeningHours", "Giờ mở cửa (bản đang áp dụng và các bản tương lai)",
     "Opening hours", "UC14", "BR-CN-02, 04", "A04–A08", "Nhân viên chi nhánh; SUPER_MANAGER",
     resp="array:OpeningHoursVersion", path_params=["BranchId"], errors=(401, 403, 404))
m.op("post", "/branches/{branchId}/opening-hours/impact", "previewOpeningHoursImpact",
     "Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng khi đổi giờ", "Opening hours", "UC14", "BR-CN-04, BR-LH-10", "A05",
     BM, body="OpeningHoursImpactRequest", resp="ScheduleImpact", path_params=["BranchId"],
     errors=(400, 401, 403, 404), notes="Không ghi gì (A1).")
m.op("put", "/branches/{branchId}/opening-hours", "setOpeningHours", "Đặt giờ mở cửa từ một ngày hiệu lực",
     "Opening hours", "UC14", "BR-CN-02, 04, BR-LH-10", "A05", BM,
     "Nếu cancelAffected: phát BranchClinicCancellationEvent → Lịch hẹn#5, Đặt chỗ#5",
     body="SetOpeningHoursRequest", resp="OpeningHoursVersion", path_params=["BranchId"],
     errors=(400, 401, 403, 404),
     err_desc={400: "`BR-CN-02` khoảng chồng nhau, giờ mở ≥ giờ đóng, quá 2 khoảng · `BR-CN-04` ngày hiệu lực trước ngày mai, hoặc có lịch / đặt chỗ bị ảnh hưởng mà chưa chọn hủy hàng loạt"},
     notes="Ghi đè bản cùng ngày hiệu lực nếu đã có.")
m.op("get", "/branches/{branchId}/holidays", "listHolidays", "Danh sách ngày nghỉ", "Opening hours", "UC14",
     "BR-CN-03", "A04–A08", "Nhân viên chi nhánh; SUPER_MANAGER", resp="array:Holiday", path_params=["BranchId"],
     query=["from=date", "to=date"], errors=(401, 403, 404))
m.op("post", "/branches/{branchId}/holidays/impact", "previewHolidayImpact",
     "Xem trước lịch hẹn / đặt chỗ bị ảnh hưởng của một ngày nghỉ", "Opening hours", "UC14", "BR-CN-03, BR-LH-10",
     "A05", BM, body="HolidayImpactRequest", resp="ScheduleImpact", path_params=["BranchId"],
     errors=(400, 401, 403, 404), notes="Không ghi gì (A1).")
m.op("post", "/branches/{branchId}/holidays", "createHoliday", "Thêm ngày nghỉ", "Opening hours", "UC14",
     "BR-CN-03, BR-LH-10, BR-LT-04", "A05", BM,
     "Nếu cancelAffected: phát BranchClinicCancellationEvent → Lịch hẹn#5, Đặt chỗ#5",
     body="CreateHolidayRequest", resp="Holiday", status=201, path_params=["BranchId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-LH-10` ngày còn lịch BOOKED / nhận-trả lưu trú BOOKED mà chưa chọn hủy hàng loạt · ngày đã là ngày nghỉ · ngày trong quá khứ"},
     notes="Lịch bị hủy không tính hủy muộn, không tính lần đổi; khách được thông báo.")
m.op("delete", "/branches/{branchId}/holidays/{holidayId}", "deleteHoliday", "Xóa ngày nghỉ tương lai", "Opening hours",
     "UC14", "BR-CN-03", "A05", BM, status=204, path_params=["BranchId", "HolidayId"], errors=(400, 401, 403, 404),
     err_desc={400: "Ngày nghỉ đã qua hoặc là hôm nay"}, notes="Không khôi phục lịch đã hủy (A3).")

m.op("get", "/branches/{branchId}/services", "listBranchServices", "Dịch vụ tại chi nhánh (kể cả loại chuồng)",
     "Branch services", "UC33", "BR-LH-01, BR-SP-04", "A04–A08", "Nhân viên chi nhánh; SUPER_MANAGER",
     resp="array:BranchServiceItem", path_params=["BranchId"], errors=(401, 403, 404))
m.op("put", "/branches/{branchId}/services/{serviceId}", "setBranchService", "Bật / tắt dịch vụ tại chi nhánh",
     "Branch services", "UC33", "BR-LH-01, BR-SP-04", "A05", BM, body="SetBranchServiceRequest",
     resp="BranchServiceItem", path_params=["BranchId", "ServiceId"], errors=(400, 401, 403, 404),
     err_desc={400: "Dịch vụ đã ngừng kinh doanh"},
     notes="Tắt dịch vụ chỉ chặn đặt mới; lịch và đặt chỗ đã có giữ nguyên.")

m.op("get", "/branches/{branchId}/quota-defaults", "listQuotaDefaults", "Quota mặc định theo nhóm dịch vụ", "Quotas",
     "UC42", "BR-LH-03", "A05, A06", "Nhân viên chi nhánh", resp="array:QuotaDefault", path_params=["BranchId"],
     errors=(401, 403, 404))
m.op("put", "/branches/{branchId}/quota-defaults/{serviceGroup}", "setQuotaDefault", "Đặt quota mặc định", "Quotas",
     "UC42", "BR-LH-03", "A05", BM, body="SetQuotaDefaultRequest", resp="QuotaDefault",
     path_params=["BranchId", "ServiceGroupPath"], errors=(400, 401, 403, 404),
     notes="Giảm quota không ảnh hưởng lịch đã đặt, chỉ chặn đặt mới.")
m.op("get", "/branches/{branchId}/slot-quotas", "listSlotQuotas", "Quota riêng của các khung", "Quotas", "UC42",
     "BR-LH-03", "A05, A06", "Nhân viên chi nhánh", resp="array:SlotQuota", path_params=["BranchId"],
     query=["from!=date", "to!=date", "serviceGroup=enum:MEDICAL,GROOMING"], errors=(400, 401, 403, 404))
m.op("put", "/branches/{branchId}/slot-quotas", "setSlotQuota", "Đặt quota riêng cho một khung (0 = khóa khung)",
     "Quotas", "UC42", "BR-LH-02, 03", "A05", BM, body="SetSlotQuotaRequest", resp="SlotQuota",
     path_params=["BranchId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-LH-02` khung không nằm trong giờ mở cửa ngày đó · quota < 0"},
     notes="Tạo mới hoặc ghi đè quota riêng của khung.")
m.op("delete", "/branches/{branchId}/slot-quotas/{slotQuotaId}", "deleteSlotQuota", "Bỏ quota riêng, về quota mặc định",
     "Quotas", "UC42", "BR-LH-03", "A05", BM, status=204, path_params=["BranchId", "SlotQuotaId"],
     errors=(401, 403, 404), notes="A4.")

m.op("get", "/public/branches", "listPublicBranches", "Chi nhánh công khai", "Public", "UC15", "BR-CK-01, BR-CN-05",
     "A01, A02", "Public", resp="array:PublicBranch", public=True, errors=(400,),
     query=["acceptsAfterHoursEmergency=bool|Lọc chi nhánh nhận cấp cứu 24/7"])
m.op("get", "/public/branches/{branchId}", "getPublicBranch", "Chi tiết chi nhánh công khai", "Public", "UC15",
     "BR-CK-01", "A01, A02", "Public", resp="PublicBranch", path_params=["BranchId"], public=True, errors=(404,),
     notes="Chi nhánh DRAFT trả 404.")
