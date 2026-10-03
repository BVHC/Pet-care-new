from lib import Module

m = Module(
    key="boarding", title="Boarding API v1", codes="LT (tầng 2)", owner="BE-2",
    sources="01 UC54, UC57–UC60; 02 BR-LT-01…12, BR-LH-09, BR-KH-04, 05, BR-SP-04, BR-BH-06, BR-TK-19; 03 #6 Đặt chỗ lưu trú, #7 Chuồng (+ Order#3); 04 §7; 05 §7 (kennels, boarding_bookings, boarding_check_ins, care_logs, care_log_addenda)",
    intro="Chuồng, sức chứa theo đêm, đặt chỗ theo loại chuồng, gia hạn / hủy, nhận thú, trả thú, thú mất khi lưu trú, nhật ký chăm sóc.",
    frozen=[
        "UC55, UC56 nội trú là tầng 3: thú ốm khi lưu trú xử lý bằng Visit khám thường (BR-LT-12).",
        "Hủy phiên trả thú (Order#9) là thao tác trên Order: `POST /orders/{orderId}/abort-checkout` (sales). Thu tiền trả thú: `POST /payments`.",
        "Hủy do phòng khám vì ngày nghỉ, thú mất, chuyển chủ (Đặt chỗ#5) chạy theo sự kiện, không có endpoint. Riêng trường hợp hết chuồng lúc nhận có endpoint `…/cancel-no-kennel`.",
        "ST05 (NO_SHOW, Đặt chỗ#6) và ST15 (OVERDUE, Đặt chỗ#7) là tác vụ hệ thống.",
        "Loại chuồng (dịch vụ nhóm BOARDING) quản lý ở catalog; bật / tắt tại chi nhánh ở branch.",
    ],
    tags={
        "Kennels": "Chuồng (UC54)",
        "Availability": "Sức chứa theo đêm (BR-LT-03)",
        "Bookings": "Đặt chỗ, gia hạn, hủy (UC58)",
        "Stay": "Nhận thú, trả thú, thú mất (UC59)",
        "Care logs": "Nhật ký chăm sóc (UC57, UC60)",
    },
    security=[
        "Khách chỉ đặt / xem / hủy / gia hạn đặt chỗ của thú mình; nhân viên chỉ thao tác đặt chỗ của chi nhánh mình.",
        "Kiểm sức chứa từng đêm và INSERT / gia hạn / nhận sớm trong cùng transaction có khóa theo chi nhánh × loại chuồng (BR-LT-03).",
        "Nhận thú: lễ tân hoặc CARETAKER. Trả thú, bắt đầu trả thú, kết thúc do thú mất: chỉ lễ tân (BR-LT-08, 09).",
        "Giao thú chỉ khi Order lưu trú đã PAID, không ngoại lệ (BR-LT-09, BR-BH-06).",
        "Nhật ký đánh dấu bất thường gửi thông báo ngay cho khách và lễ tân chi nhánh trong cùng transaction (outbox) (BR-LT-12).",
    ],
    assumptions=[
        ("A1", "Khách chọn khoảng ngày rồi xem còn chỗ từng đêm; đêm = ngày nhận … ngày trả − 1", "BR-LT-02, 03; `check_out_date > check_in_date`"),
        ("A2", "Lý do hủy đặt chỗ không bắt buộc với khách / lễ tân", "BR-LT-06 không yêu cầu"),
        ("A3", "Hủy vì hết chuồng đúng loại lúc nhận là endpoint riêng do lễ tân bấm sau khi nhận thất bại", "BR-LT-08: 'đặt chỗ được hủy như hủy do phòng khám' — cần người kích hoạt và thời điểm rõ ràng"),
        ("A4", "Bản bổ sung nhật ký thêm được bất cứ lúc nào bởi CARETAKER / VET chi nhánh", "BR-LT-11 'sau đó chỉ thêm bổ sung', không giới hạn thời gian"),
        ("A5", "Ảnh nhật ký là URL; tối đa 5 [CFG]", "05 `care_logs.photo_urls`"),
    ],
    questions=[
        ("Q1", "Cơ chế tải ảnh nhật ký chăm sóc (upload trực tiếp hay presigned URL)?", "TBD (BE + FE)", "05 chỉ lưu URL"),
    ],
)

m.param("KennelId", "kennelId", "path", "int64")
m.param("BranchId", "branchId", "path", "int64")
m.param("BookingId", "bookingId", "path", "int64")
m.param("CareLogId", "careLogId", "path", "int64")

m.schema("KennelStatus", None, enum=["AVAILABLE", "OCCUPIED", "MAINTENANCE"], desc="03 #7")
m.schema("BookingStatus", None, enum=["BOOKED", "CHECKED_IN", "OVERDUE", "CHECKED_OUT", "CANCELLED", "NO_SHOW"], desc="03 #6")
m.schema("Kennel", {
    "kennelId": "int64", "branchId": "int64", "kennelTypeId": "int64", "kennelTypeName": "string",
    "code": "string:20", "status": "ref:KennelStatus", "note": "string:300?",
    "currentBookingId": "int64?|Khi OCCUPIED",
})
m.schema("CreateKennelRequest", {"kennelTypeId": "int64|Loại chuồng đang bật tại chi nhánh", "code": "string:20|Duy nhất trong chi nhánh (BR-LT-01)", "note": "string:300?"})
m.schema("UpdateKennelRequest", {"code": "string:20?", "note": "string:300?"}, required=[])
m.schema("AffectedBookingBrief", {"bookingId": "int64", "code": "string", "checkInDate": "date", "checkOutDate": "date", "petName": "string"})
m.schema("MaintenanceResult", {
    "kennel": "ref:Kennel",
    "shortageBookings": "array:ref:AffectedBookingBrief|Đặt chỗ thiếu chỗ sau khi giảm sức chứa — cảnh báo, không tự hủy (BR-LT-01)",
})
m.schema("NightAvailability", {"date": "date", "capacity": "int", "occupied": "int", "remaining": "int"})
m.schema("BoardingAvailability", {
    "kennelTypeId": "int64", "nightlyPrice": "money", "nights": "array:ref:NightAvailability",
    "available": "bool|Mọi đêm còn chỗ",
})
m.schema("VaccineGap", {
    "vaccineTypeId": "int64", "vaccineTypeName": "string",
    "lastAdministeredOn": "date?|null = chưa từng tiêm tại hệ thống", "nextDueDate": "date?",
})
m.schema("CreateBookingRequest", {
    "petId": "int64", "branchId": "int64", "kennelTypeId": "int64", "checkInDate": "date", "checkOutDate": "date",
})
m.schema("Booking", {
    "bookingId": "int64", "code": "string", "customerId": "int64", "customerName": "string", "petId": "int64",
    "petName": "string", "branchId": "int64", "kennelTypeId": "int64", "kennelTypeName": "string",
    "kennelId": "int64?", "kennelCode": "string?", "checkInDate": "date", "checkOutDate": "date|Ngày trả dự kiến",
    "actualCheckInAt": "datetime?", "actualCheckOutAt": "datetime?", "nightlyPrice": "money|Snapshot lúc đặt (BR-LT-02)",
    "status": "ref:BookingStatus", "endReason": "enum:RETURNED,PET_DECEASED?", "channel": "enum:ONLINE,COUNTER",
    "lateCancel": "bool", "cancelSource": "enum:CUSTOMER,STAFF,CLINIC?", "cancelReason": "string?",
    "overdueSince": "datetime?", "activeOrderId": "int64?|Order BOARDING OPEN/PENDING nếu có",
    "missingCareLogToday": "bool|Đang lưu trú mà hôm nay chưa có nhật ký (BR-LT-11)",
})
m.schema("BookingResult", {
    "booking": "ref:Booking",
    "vaccineWarnings": "array:ref:VaccineGap|Dự kiến chưa đạt mũi bắt buộc vào ngày nhận — cảnh báo, gợi ý đặt lịch tiêm (BR-LT-05)",
})
m.schema("ExtendBookingRequest", {"checkOutDate": "date|Sau ngày trả hiện tại, tổng ≤ 30 đêm [CFG]"})
m.schema("CancelBookingRequest", {"reason": "string:300?"}, required=[])
m.schema("CheckInReadiness", {
    "vaccineGaps": "array:ref:VaccineGap|Rỗng mới nhận được (BR-LT-05)",
    "availableKennels": "array:ref:Kennel|Chuồng AVAILABLE đúng loại đã đặt",
    "withinOpeningHours": "bool", "canCheckIn": "bool",
})
m.schema("CheckInRequest", {
    "kennelId": "int64|AVAILABLE, đúng loại đã đặt (BR-LT-08)", "weightKg": "decimal|> 0, bắt buộc",
    "healthCondition": "string|Tình trạng quan sát được", "belongings": "string?", "dietInstructions": "string?",
    "emergencyPhone": "phone|SĐT liên hệ khẩn, dùng cho Care Task quá hạn đón (BR-TB-05)",
})
m.schema("CheckoutStarted", {
    "booking": "ref:Booking", "orderId": "int64", "nights": "int|Tối thiểu 1; trả sau 12:00 [CFG] +1 đêm",
    "amount": "money",
})
m.schema("DeceasedEndRequest", {"note": "string|Ghi rõ diễn biến (BR-LT-12)"})
m.schema("CareLogAddendum", {"addendumId": "int64", "content": "string", "createdBy": "int64", "createdAt": "datetime"})
m.schema("CareLog", {
    "careLogId": "int64", "bookingId": "int64", "logDate": "date", "eating": "string:300?", "drinking": "string:300?",
    "hygiene": "string:300?", "activity": "string:300?", "note": "string?", "photoUrls": "array:string",
    "isAbnormal": "bool", "recordedBy": "int64", "recordedByName": "string", "createdAt": "datetime",
    "editableUntil": "datetime|created_at + 1 giờ [CFG]", "addenda": "array:ref:CareLogAddendum",
})
m.schema("CareLogRequest", {
    "logDate": "date?|Mặc định hôm nay", "eating": "string:300?", "drinking": "string:300?", "hygiene": "string:300?",
    "activity": "string:300?", "note": "string?", "photoUrls": "array:string|Tối đa 5 [CFG] (A5)",
    "isAbnormal": "bool?|true → thông báo ngay khách và lễ tân (BR-LT-12)",
}, required=["photoUrls"])
m.schema("UpdateCareLogRequest", {
    "eating": "string:300?", "drinking": "string:300?", "hygiene": "string:300?", "activity": "string:300?",
    "note": "string?", "photoUrls": "array:string",
}, required=[])
m.schema("AddendumRequest", {"content": "string"})

BM = "BRANCH_MANAGER chi nhánh"
OWN_OR_BRANCH = "Khách: thú của mình · lễ tân chi nhánh"
m.op("get", "/kennels", "listKennels", "Danh sách chuồng của chi nhánh", "Kennels", "UC54, UC59", "BR-LT-01",
     "A05, A06, A08", "Nhân viên chi nhánh", resp="array:Kennel",
     query=["kennelTypeId=int64", "status=enum:AVAILABLE,OCCUPIED,MAINTENANCE"], errors=(401, 403))
m.op("post", "/kennels", "createKennel", "Tạo chuồng", "Kennels", "UC54", "BR-LT-01, BR-SP-04", "A05", BM,
     "— → AVAILABLE (Chuồng#1)", body="CreateKennelRequest", resp="Kennel", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-LT-01` mã trùng trong chi nhánh · loại chuồng không phải dịch vụ BOARDING đang bật"})
m.op("patch", "/kennels/{kennelId}", "updateKennel", "Sửa mã / ghi chú chuồng", "Kennels", "UC54", "BR-LT-01", "A05",
     BM, body="UpdateKennelRequest", resp="Kennel", path_params=["KennelId"], errors=(400, 401, 403, 404))
m.op("delete", "/kennels/{kennelId}", "deleteKennel", "Xóa chuồng", "Kennels", "UC54", "BR-LT-01", "A05", BM,
     status=204, path_params=["KennelId"], errors=(401, 403, 404, 409), err_desc={409: "Chuồng đang OCCUPIED"})
m.op("post", "/kennels/{kennelId}/start-maintenance", "startKennelMaintenance", "Chuyển chuồng sang bảo trì",
     "Kennels", "UC54", "BR-LT-01, 03", "A05", BM, "AVAILABLE → MAINTENANCE (Chuồng#4)", resp="MaintenanceResult",
     path_params=["KennelId"], errors=(401, 403, 404, 409), err_desc={409: "Chuồng không AVAILABLE (OCCUPIED không chuyển được)"})
m.op("post", "/kennels/{kennelId}/end-maintenance", "endKennelMaintenance", "Hết bảo trì", "Kennels", "UC54",
     "BR-LT-01", "A05", BM, "MAINTENANCE → AVAILABLE (Chuồng#5)", resp="Kennel", path_params=["KennelId"],
     errors=(401, 403, 404, 409), err_desc={409: "Chuồng không MAINTENANCE"})

m.op("get", "/branches/{branchId}/boarding-availability", "getBoardingAvailability", "Xem còn chỗ theo đêm",
     "Availability", "UC58", "BR-LT-01, 02, 03, 04", "A02, A06", "Khách đã đăng nhập; lễ tân",
     resp="BoardingAvailability", path_params=["BranchId"],
     query=["kennelTypeId!=int64", "checkInDate!=date", "checkOutDate!=date"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-LT-02` quá 30 đêm [CFG] hoặc ngày trả không sau ngày nhận · loại chuồng không bật tại chi nhánh"},
     notes="A1.")

BOOK_ERR = ("`BR-LT-02` thú sai loài / quá cân nặng tối đa / ngoài 1–30 đêm [CFG] / chồng ngày với đặt chỗ khác · "
            "`BR-LT-03` hết chỗ (message ghi các đêm thiếu) · `BR-LT-04` khách đặt ngoài khoảng 1–60 ngày [CFG], ngày nhận / trả là ngày nghỉ · "
            "`BR-LH-09` đang bị hạn chế đặt online · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ chờ liên kết · loại chuồng không bật tại chi nhánh ACTIVE")
m.op("post", "/boarding-bookings", "createBoardingBooking", "Đặt chỗ lưu trú", "Bookings", "UC58",
     "BR-LT-02…05, BR-LH-09, BR-KH-05, BR-TK-19", "A02, A06", OWN_OR_BRANCH, "— → BOOKED (Đặt chỗ#1)",
     body="CreateBookingRequest", resp="BookingResult", status=201, errors=(400, 401, 403, 404),
     err_desc={400: BOOK_ERR}, notes="Snapshot giá đêm. Thiếu mũi bắt buộc dự kiến: cảnh báo, không chặn.")
m.op("get", "/boarding-bookings", "listBoardingBookings", "Danh sách đặt chỗ", "Bookings", "UC58, UC59, UC60",
     "BR-LT-07, 11", "A02, A05, A06, A07, A08", "Khách: của mình · nhân viên chi nhánh", resp="Booking", page=True,
     query=["status=enum:BOOKED,CHECKED_IN,OVERDUE,CHECKED_OUT,CANCELLED,NO_SHOW", "petId=int64", "customerId=int64",
            "from=date", "to=date", "inStay=bool|CHECKED_IN hoặc OVERDUE", "missingCareLogToday=bool", "page", "size"],
     errors=(400, 401, 403))
m.op("get", "/boarding-bookings/{bookingId}", "getBoardingBooking", "Chi tiết đặt chỗ", "Bookings", "UC58, UC60",
     "—", "A02, A05–A08", "Khách: của mình · nhân viên chi nhánh", resp="Booking", path_params=["BookingId"],
     errors=(401, 403, 404))
m.op("post", "/boarding-bookings/{bookingId}/extend", "extendBoardingBooking", "Gia hạn ngày trả", "Bookings", "UC58",
     "BR-LT-02, 03, 06", "A02, A06", OWN_OR_BRANCH, "BOOKED / CHECKED_IN giữ nguyên (Đặt chỗ#2)",
     body="ExtendBookingRequest", resp="Booking", path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-06` đã qua ngày trả dự kiến · `BR-LT-03` hết chỗ các đêm thêm · `BR-LT-02` vượt 30 đêm",
               409: "Đặt chỗ OVERDUE (không gia hạn) hoặc đã kết thúc"})
m.op("post", "/boarding-bookings/{bookingId}/cancel", "cancelBoardingBooking", "Hủy đặt chỗ", "Bookings", "UC58",
     "BR-LT-06, BR-LH-09", "A02, A06", OWN_OR_BRANCH, "BOOKED → CANCELLED (Đặt chỗ#4)", body="CancelBookingRequest",
     resp="Booking", path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Đã tới ngày nhận", 409: "Đặt chỗ không còn BOOKED"},
     notes="Còn < 24 giờ [CFG] trước ngày nhận → hủy muộn, đánh giá hạn chế đặt online (BR-LH-09).")

m.op("get", "/boarding-bookings/{bookingId}/check-in-readiness", "getCheckInReadiness",
     "Kiểm tra điều kiện nhận thú", "Stay", "UC59", "BR-LT-04, 05, 08", "A06, A08", "Lễ tân, CARETAKER chi nhánh",
     resp="CheckInReadiness", path_params=["BookingId"], errors=(401, 403, 404, 409),
     err_desc={409: "Đặt chỗ không còn BOOKED"})
m.op("post", "/boarding-bookings/{bookingId}/check-in", "checkInBoarding", "Nhận thú", "Stay", "UC59",
     "BR-LT-04, 05, 08, BR-KH-04", "A06, A08", "Lễ tân, CARETAKER chi nhánh",
     "BOOKED → CHECKED_IN (Đặt chỗ#3) · Chuồng#2 (→ OCCUPIED)", body="CheckInRequest", resp="Booking",
     path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-05` thiếu / quá hạn mũi bắt buộc (lễ tân tiếp nhận walk-in để tiêm trước) · `BR-LT-08` chuồng không AVAILABLE hoặc sai loại, thiếu cân nặng · `BR-LT-04` ngoài giờ mở cửa · `BR-LT-03` nhận sớm khi hết chỗ",
               409: "Đặt chỗ không còn BOOKED"},
     notes="Cân nặng ghi vào lịch sử (source INTAKE); nhận sớm được nếu còn chỗ.")
m.op("post", "/boarding-bookings/{bookingId}/cancel-no-kennel", "cancelBoardingNoKennel",
     "Hủy vì không còn chuồng đúng loại lúc nhận", "Stay", "UC59", "BR-LT-08, BR-LH-09", "A06", "Lễ tân chi nhánh",
     "BOOKED → CANCELLED (Đặt chỗ#5, phòng khám hủy)", resp="Booking", path_params=["BookingId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "Vẫn còn chuồng AVAILABLE đúng loại", 409: "Đặt chỗ không còn BOOKED"},
     notes="Không tính hủy muộn; thông báo khách và BRANCH_MANAGER (A3).")
m.op("post", "/boarding-bookings/{bookingId}/start-checkout", "startBoardingCheckout", "Bắt đầu trả thú", "Stay",
     "UC59", "BR-LT-04, 09, BR-BH-01", "A06", "Chỉ lễ tân chi nhánh",
     "CHECKED_IN / OVERDUE giữ nguyên (Đặt chỗ#9) · Order#3 (BOARDING → PENDING)", resp="CheckoutStarted",
     path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-04` ngoài giờ mở cửa", 409: "Thú không còn trong chuồng"},
     notes="Đã có Order BOARDING PENDING thì dùng lại và tính lại số đêm. Tiếp theo: `POST /payments`.")
m.op("post", "/boarding-bookings/{bookingId}/hand-over", "handOverBoardingPet", "Giao thú (trả thú)", "Stay", "UC59",
     "BR-LT-09, BR-BH-06", "A06", "Chỉ lễ tân chi nhánh", "CHECKED_IN / OVERDUE → CHECKED_OUT, RETURNED (Đặt chỗ#10) · Chuồng#3",
     resp="Booking", path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-09` Order lưu trú chưa PAID — không có ngoại lệ", 409: "Thú không còn trong chuồng"})
m.op("post", "/boarding-bookings/{bookingId}/end-deceased", "endBoardingDeceased", "Kết thúc lưu trú do thú mất",
     "Stay", "UC59", "BR-LT-12, BR-KH-05", "A06", "Chỉ lễ tân chi nhánh",
     "CHECKED_IN / OVERDUE → CHECKED_OUT, PET_DECEASED (Đặt chỗ#11) · Chuồng#3 · Order#3 (tính đến ngày mất)",
     body="DeceasedEndRequest", resp="CheckoutStarted", path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "Thú không còn trong chuồng"},
     notes="Sau đó mới đánh dấu thú đã mất (`POST /pets/{petId}/mark-deceased`). Order xử lý như PENDING thường.")

m.op("get", "/boarding-bookings/{bookingId}/care-logs", "listCareLogs", "Xem nhật ký chăm sóc", "Care logs",
     "UC57, UC60", "BR-LT-11", "A02, A05–A08", "Khách: thú của mình · nhân viên chi nhánh", resp="array:CareLog",
     path_params=["BookingId"], errors=(401, 403, 404), notes="Khách xem ngay khi được ghi.")
m.op("post", "/boarding-bookings/{bookingId}/care-logs", "createCareLog", "Ghi nhật ký chăm sóc", "Care logs", "UC57",
     "BR-LT-11, 12", "A07, A08", "CARETAKER, VET chi nhánh", "CHECKED_IN / OVERDUE giữ nguyên (Đặt chỗ#8 nếu bất thường)",
     body="CareLogRequest", resp="CareLog", status=201, path_params=["BookingId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Quá 5 ảnh [CFG]", 409: "Thú không đang lưu trú"},
     notes="Bất thường: thông báo CARE_LOG_ABNORMAL cho khách và lễ tân.")
m.op("patch", "/care-logs/{careLogId}", "updateCareLog", "Sửa nhật ký trong 1 giờ", "Care logs", "UC57", "BR-LT-11",
     "A07, A08", "Người ghi, trong 1 giờ [CFG]", body="UpdateCareLogRequest", resp="CareLog", path_params=["CareLogId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: "`BR-LT-11` đã quá thời hạn sửa — dùng bản bổ sung"})
m.op("post", "/care-logs/{careLogId}/addenda", "addCareLogAddendum", "Thêm bản bổ sung nhật ký", "Care logs", "UC57",
     "BR-LT-11", "A07, A08", "CARETAKER, VET chi nhánh (A4)", body="AddendumRequest", resp="CareLogAddendum",
     status=201, path_params=["CareLogId"], errors=(400, 401, 403, 404))
