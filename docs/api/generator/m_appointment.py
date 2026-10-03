from lib import Module

m = Module(
    key="appointment", title="Appointment API v1", codes="LH", owner="BE-1",
    sources="01 UC39, UC40; 02 BR-LH-01…12, BR-TK-19, BR-KH-05, BR-TB-01, 06; 03 #3 Lịch hẹn; 04 §5; 05 §5 (appointments, booking_restrictions)",
    intro="Khung giờ trống, đặt lịch, xem, đổi khung, hủy lịch hẹn; hạn chế đặt online.",
    frozen=[
        "UC43 khám tại nhà là tầng 3, không có endpoint.",
        "Check-in lịch hẹn (Lịch hẹn#3) là một phần của tiếp nhận Visit: `POST /visits` ở module visit. Hoàn tất (#7) và hủy lượt (#8) cũng đi từ Visit.",
        "Hủy do phòng khám (#5) không có endpoint: chạy theo sự kiện ngày nghỉ / thu hẹp giờ, thú mất, chuyển chủ (06-module-contracts §3).",
        "ST03 nhắc lịch hẹn, ST05 chuyển NO_SHOW (#6) là tác vụ hệ thống.",
        "Cấu hình quota (UC42) thuộc module branch.",
    ],
    tags={
        "Slots": "Khung giờ trống (UC39)",
        "Appointments": "Đặt, xem, đổi, hủy lịch hẹn (UC39, UC40)",
        "Booking restrictions": "Hạn chế đặt online (BR-LH-09)",
    },
    security=[
        "Khách chỉ đặt / xem / đổi / hủy lịch của thú mình là chủ hiện tại; nhân viên chỉ thao tác lịch của chi nhánh mình.",
        "Đếm quota và INSERT trong cùng transaction có khóa theo chi nhánh × nhóm × khung (05 `appointments`), nên hai người không cùng lấy được chỗ cuối.",
        "Khách: kiểm thời hạn đặt 24 giờ / 30 ngày [CFG], hạn chế online, cờ chờ liên kết. Lễ tân: đặt mọi khung chưa bắt đầu và còn quota; giới hạn số lịch của thú vẫn áp dụng (BR-LH-04, 05).",
        "Gỡ hạn chế đặt online ghi audit (BR-LH-09).",
    ],
    assumptions=[
        ("A1", "Khung giờ trống trả theo từng ngày; khách chỉ thấy khung trong khoảng đặt được, khung hết quota hiện FULL", "BR-LH-02, 03, 04: không hiển thị khung ngoài khoảng, khung đủ quota hiện 'Hết chỗ'"),
        ("A2", "Lễ tân đặt hộ không truyền customerId; khách của lịch là chủ hiện tại của thú", "BR-LH-01: lịch gồm 1 thú; 05 `appointments.customer_id` = chủ"),
        ("A3", "Danh sách chờ check-in là bộ lọc `checkInReady=true` của danh sách lịch hẹn", "BR-TN-02: chỉ hiển thị lịch trong cửa sổ check-in"),
        ("A4", "Lý do hủy không bắt buộc khi khách / lễ tân hủy lịch BOOKED", "BR-LH-07 không yêu cầu lý do"),
    ],
    questions=[],
)

m.param("BranchId", "branchId", "path", "int64")
m.param("AppointmentId", "appointmentId", "path", "int64")
m.param("CustomerId", "customerId", "path", "int64")

m.schema("AppointmentStatus", None, enum=["BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"], desc="03 #3")
m.schema("Slot", {
    "slotStart": "time", "slotEnd": "time|slotStart + 30 phút",
    "quota": "int", "booked": "int|Lịch BOOKED, CHECKED_IN, COMPLETED của khung (BR-LH-03)",
    "status": "enum:AVAILABLE,FULL|FULL hiển thị 'Hết chỗ'; quota 0 cũng là FULL",
})
m.schema("BookAppointmentRequest", {
    "petId": "int64", "branchId": "int64", "serviceId": "int64|Nhóm MEDICAL hoặc GROOMING (BR-LH-01)",
    "slotDate": "date", "slotStart": "time", "note": "string:500?",
})
m.schema("Appointment", {
    "appointmentId": "int64", "code": "string|Giữ nguyên khi đổi lịch (BR-LH-06)", "customerId": "int64",
    "customerName": "string", "petId": "int64", "petName": "string", "branchId": "int64", "branchName": "string",
    "serviceId": "int64", "serviceName": "string", "serviceGroup": "enum:MEDICAL,GROOMING", "slotDate": "date",
    "slotStart": "time", "status": "ref:AppointmentStatus", "channel": "enum:ONLINE,COUNTER",
    "rescheduleCount": "int|Tối đa 3 [CFG]", "lateCancel": "bool",
    "cancelSource": "enum:CUSTOMER,STAFF,CLINIC?", "cancelReason": "string?", "cancelledAt": "datetime?",
    "note": "string?", "visitId": "int64?|Visit tạo khi check-in",
    "canReschedule": "bool|Theo vai trò người xem: còn lượt, còn đủ 12 giờ với khách (BR-LH-06)",
    "canCancel": "bool",
})
m.schema("RescheduleRequest", {"slotDate": "date", "slotStart": "time"})
m.schema("CancelAppointmentRequest", {"reason": "string:300?"}, required=[])
m.schema("BookingRestriction", {
    "restrictionId": "int64", "customerId": "int64", "startsAt": "datetime", "endsAt": "datetime",
    "violationCount": "int|Số NO_SHOW + hủy muộn trong 90 ngày [CFG] lúc bị hạn chế",
})
m.schema("RestrictionStatus", {
    "restricted": "bool", "restriction": "ref:BookingRestriction",
}, required=["restricted"])
m.schema("LiftRestrictionRequest", {"reason": "string:300"})

BOOK_ERR = ("`BR-LH-01` dịch vụ không đặt được / đang tắt tại chi nhánh, chi nhánh chưa ACTIVE · "
            "`BR-LH-02` khung không hợp lệ · `BR-LH-03` khung đã đủ quota · `BR-LH-04` ngoài khoảng 24 giờ – 30 ngày [CFG] (khách) "
            "hoặc khung đã bắt đầu (lễ tân) · `BR-LH-05` thú đã có 2 lịch BOOKED, trùng nhóm trong ngày hoặc trùng khung · "
            "`BR-LH-09` khách đang bị hạn chế đặt online · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ còn chờ quyết định liên kết")
m.op("get", "/branches/{branchId}/available-slots", "listAvailableSlots", "Xem khung giờ trống", "Slots", "UC39",
     "BR-LH-01, 02, 03, 04", "A02, A06", "Khách; lễ tân chi nhánh", resp="array:Slot", path_params=["BranchId"],
     query=["serviceId!=int64", "date!=date"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-LH-01` dịch vụ không đặt được tại chi nhánh"},
     notes="Ngày nghỉ hoặc ngày không có giờ mở cửa trả mảng rỗng (A1).")
m.op("post", "/appointments", "bookAppointment", "Đặt lịch hẹn", "Appointments", "UC39",
     "BR-LH-01…05, 09, BR-KH-05, BR-TK-19", "A02, A06", "Khách: thú của mình · lễ tân: chi nhánh mình",
     "— → BOOKED (Lịch hẹn#1); nhóm MEDICAL → Care Task#6", body="BookAppointmentRequest", resp="Appointment",
     status=201, errors=(400, 401, 403, 404), err_desc={400: BOOK_ERR, 403: "Thú không thuộc khách / chi nhánh ngoài phạm vi"},
     notes="Khách → channel ONLINE, lễ tân → COUNTER (A2). Nhóm MEDICAL: hủy nhắc tái khám đang chờ của thú (BR-TB-06).")
m.op("get", "/appointments", "listAppointments", "Danh sách lịch hẹn", "Appointments", "UC40, UC44", "BR-LH-11, BR-TN-02",
     "A02, A05, A06", "Khách: lịch của mình · nhân viên: chi nhánh mình", resp="Appointment", page=True,
     query=["branchId=int64", "date=date", "from=date", "to=date",
            "status=enum:BOOKED,CHECKED_IN,COMPLETED,CANCELLED,NO_SHOW", "petId=int64", "customerId=int64",
            "checkInReady=bool|Chỉ lịch BOOKED hôm nay trong cửa sổ check-in (A3)", "page", "size"],
     errors=(400, 401, 403))
m.op("get", "/appointments/{appointmentId}", "getAppointment", "Chi tiết lịch hẹn", "Appointments", "UC40", "—",
     "A02, A05, A06, A07, A08", "Khách: lịch của mình · nhân viên chi nhánh", resp="Appointment",
     path_params=["AppointmentId"], errors=(401, 403, 404))
m.op("post", "/appointments/{appointmentId}/reschedule", "rescheduleAppointment", "Đổi khung giờ", "Appointments",
     "UC40", "BR-LH-03, 04, 05, 06", "A02, A06", "Khách: lịch của mình · lễ tân chi nhánh", "BOOKED → BOOKED (Lịch hẹn#2)",
     body="RescheduleRequest", resp="Appointment", path_params=["AppointmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LH-06` hết lượt đổi (3 [CFG]) hoặc khách đổi khi còn < 12 giờ · `BR-LH-04`, `BR-LH-03`, `BR-LH-05` với khung mới",
               409: "Lịch không còn BOOKED"},
     notes="Cùng chi nhánh, cùng thú, cùng dịch vụ; muốn đổi khác thì hủy rồi đặt lại. Giữ mã lịch hẹn.")
m.op("post", "/appointments/{appointmentId}/cancel", "cancelAppointment", "Hủy lịch hẹn", "Appointments", "UC40",
     "BR-LH-07, 09", "A02, A06", "Khách: lịch của mình · lễ tân chi nhánh", "BOOKED → CANCELLED (Lịch hẹn#4)",
     body="CancelAppointmentRequest", resp="Appointment", path_params=["AppointmentId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "Đã qua giờ hẹn", 409: "Lịch không còn BOOKED"},
     notes="Còn < 12 giờ [CFG] → lateCancel = true và đánh giá hạn chế đặt online (BR-LH-09).")

m.op("get", "/me/booking-restriction", "getMyBookingRestriction", "Tình trạng hạn chế đặt online của tôi",
     "Booking restrictions", "UC39, UC58", "BR-LH-09", "A02", "Khách đang đăng nhập", resp="RestrictionStatus",
     errors=(401, 403), notes="FE hiển thị lý do và ngày hết hạn chế.")
m.op("get", "/customers/{customerId}/booking-restriction", "getCustomerBookingRestriction",
     "Tình trạng hạn chế đặt online của khách", "Booking restrictions", "UC39", "BR-LH-09", "A05, A06",
     "Nhân viên", resp="RestrictionStatus", path_params=["CustomerId"], errors=(401, 403, 404))
m.op("post", "/customers/{customerId}/booking-restriction/lift", "liftBookingRestriction", "Gỡ hạn chế sớm",
     "Booking restrictions", "UC39", "BR-LH-09, BR-QT-15", "A05, A06", "Lễ tân, BRANCH_MANAGER",
     body="LiftRestrictionRequest", resp="RestrictionStatus", path_params=["CustomerId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "Khách không đang bị hạn chế"}, notes="Ghi audit.")
