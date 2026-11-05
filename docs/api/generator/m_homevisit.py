from lib import Module

m = Module(
    key="homevisit", title="Home Visit API v1 (tầng 3)", codes="LH, KB (khám tại nhà)", owner="BE-1",
    sources="01 UC43, UC51; 09-tang3-be1-spec.md §A (BR-LH-13, 14, BR-KB-07, BR-BH-16; 03 #22 Lịch khám tại nhà); 05 bổ sung appointments.is_home_visit…, visits.is_home_visit, order_lines HOME_FEE",
    intro="Đặt lịch khám tại nhà (khách hoặc lễ tân đặt hộ) và bác sĩ thực hiện lịch khám tại nhà. Lịch khám tại nhà là một lịch hẹn nhóm Khám có cờ tại nhà. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Hủy và đổi giờ dùng lại `POST /appointments/{appointmentId}/cancel` và `…/reschedule` của appointment-v1 (BR-LH-14); khung giờ trống dùng `GET /branches/{branchId}/available-slots`.",
        "Phí khám tại nhà xem ở `GET /branches/{branchId}/home-visit-fee` (fees-v1, mở thêm cho khách A02).",
        "Hoàn tất khám tại nhà, bệnh án, kê đơn, tiêm đi theo visit-v1 (UC48, UC49) trên Visit do `start` tạo ra.",
        "ST05 (NO_SHOW tự động) bỏ qua lịch tại nhà (BR-LH-14).",
    ],
    tags={
        "Home visit booking": "Đặt lịch khám tại nhà (UC43)",
        "Home visit": "Bác sĩ thực hiện khám tại nhà (UC51)",
    },
    security=[
        "Khách (A02) chỉ đặt cho thú của mình và dùng địa chỉ trong sổ địa chỉ của chính mình (BR-TK-18); lịch của người khác trả 404 (ADR-0006).",
        "Lễ tân (A06) đặt hộ khách ở chi nhánh của mình; VET (A07) chỉ thấy và bắt đầu lịch tại nhà của chi nhánh mình (BR-QT-03).",
        "Chỉ chi nhánh đã bật khám tại nhà (BR-SP-09, fees-v1) nhận đặt lịch; phí và địa chỉ chốt lúc đặt (BR-SP-11).",
        "Bắt đầu khám: khóa lịch hẹn rồi tạo Visit và Order trong một transaction (BR-KB-07; `PetQueryApi.findPetForShare`, ADR-0041).",
    ],
    assumptions=[
        ("A1", "Lịch khám tại nhà tính vào quota nhóm Khám / Tiêm của chi nhánh", "Tái dùng BR-LH-03; không thêm quota mới"),
        ("A2", "Không giới hạn vùng phục vụ; nhân viên xác nhận qua điện thoại", "UC43 không nêu vùng"),
        ("A3", "ST05 bỏ qua lịch tại nhà; bác sĩ ghi 'không gặp được' → NO_SHOW", "Bác sĩ có thể đến muộn"),
        ("A4", "Thu tiền khám tại nhà tại chi nhánh qua lễ tân", "BR-BH-16"),
    ],
    questions=[
        ("Q1", "Giới hạn bán kính phục vụ hoặc phí theo khoảng cách", "TBD (PO)", "fees-v1 Q1"),
        ("Q2", "Có thu tiền tại nhà (bác sĩ nhận) không", "TBD (PO)", "BR-BH-16"),
        ("Q3", "Có cho phân công trước một bác sĩ cụ thể cho lịch tại nhà không (hiện bác sĩ tự bắt đầu)", "TBD (BE-1)", "BR-KB-07"),
    ],
)

m.param("AppointmentId", "appointmentId", "path", "int64")

m.schema("HomeVisitStatus", None, enum=["BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"], desc="03 #3 và #22")
m.schema("CreateHomeVisitAppointmentRequest", {
    "petId": "int64", "serviceId": "int64|Dịch vụ khám (`EXAM`) đang bật ở chi nhánh", "branchId": "int64",
    "slotDate": "date", "slotStart": "time|Khung 30 phút (BR-LH-02)",
    "addressId": "int64?|Địa chỉ trong sổ của khách (một trong `addressId` / `address`)",
    "address": "string:300?|Nhập tay, chỉ lễ tân đặt hộ", "contactPhone": "phone", "note": "string:300?",
    "customerId": "int64?|Chỉ lễ tân đặt hộ; khách tự đặt thì bỏ qua",
})
m.schema("HomeVisitAppointment", {
    "appointmentId": "int64", "code": "string", "petId": "int64", "petName": "string", "customerId": "int64",
    "serviceId": "int64", "serviceName": "string", "branchId": "int64", "slotDate": "date", "slotStart": "time",
    "status": "ref:HomeVisitStatus", "homeAddress": "string|Snapshot (BR-SP-11)", "homeContactPhone": "string",
    "homeFee": "money|Snapshot (BR-SP-11)", "note": "string?", "visitId": "int64?|Có sau khi bắt đầu",
    "createdAt": "datetime",
})
m.schema("HomeVisitAppointmentSummary", {
    "appointmentId": "int64", "code": "string", "petName": "string", "serviceName": "string", "slotDate": "date",
    "slotStart": "time", "status": "ref:HomeVisitStatus", "homeAddress": "string", "homeFee": "money",
})
m.schema("HomeVisitStarted", {"appointmentId": "int64", "visitId": "int64", "orderId": "int64"})
m.schema("MarkUnreachableRequest", {"note": "string:300|Bắt buộc (BR-LH-14)"})

CUS = "CUSTOMER, chỉ thú của mình"
STAFF = "A06, A07 của chi nhánh"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái lịch không cho phép (03 #22)"

m.op("post", "/home-visit-appointments", "createHomeVisitAppointment", "Đặt lịch khám tại nhà", "Home visit booking", "UC43",
     "BR-LH-13, BR-SP-09, 11", "A02, A06", "CUSTOMER cho thú của mình; A06 đặt hộ ở chi nhánh của mình",
     "— → BOOKED (Lịch khám tại nhà#1)", body="CreateHomeVisitAppointmentRequest", resp="HomeVisitAppointment", status=201,
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LH-13` chi nhánh chưa bật khám tại nhà · dịch vụ không phải `EXAM` / không bật · địa chỉ không phải của khách · thú đã mất; `BR-LH-04` / `05` / `09` như lịch thường",
               404: "Không tìm thấy thú / chi nhánh / dịch vụ / địa chỉ",
               409: "`CONCURRENCY_CONFLICT` khung vừa hết quota (BR-LH-03)"},
     notes="Chốt `homeFee` và địa chỉ lúc đặt. Dùng chung khóa quota với appointment-v1 (ADR-0045).")
m.op("get", "/me/home-visit-appointments", "listMyHomeVisitAppointments", "Lịch khám tại nhà của tôi", "Home visit booking",
     "UC43", "BR-LH-13", "A02", CUS, resp="HomeVisitAppointmentSummary", page=True,
     query=["status=enum:BOOKED,CHECKED_IN,COMPLETED,CANCELLED,NO_SHOW", "page", "size"], errors=(400, 401, 403),
     notes="Mới nhất trước (`slotDate` giảm dần, trùng thì `appointmentId` giảm dần).")
m.op("get", "/home-visit-appointments", "listHomeVisitAppointments", "Lịch khám tại nhà của chi nhánh", "Home visit",
     "UC51", "BR-KB-07", "A06, A07", STAFF, resp="HomeVisitAppointmentSummary", page=True,
     query=["date=date|Vắng = hôm nay (giờ Việt Nam)", "status=enum:BOOKED,CHECKED_IN,COMPLETED,CANCELLED,NO_SHOW", "page", "size"],
     errors=(400, 401, 403), notes="Tự lấy chi nhánh của người gọi. Sắp `slotStart` tăng dần.")
m.op("get", "/home-visit-appointments/{appointmentId}", "getHomeVisitAppointment", "Chi tiết lịch khám tại nhà",
     "Home visit", "UC51", "BR-KB-07", "A02, A06, A07", "Khách chủ lịch; A06, A07 của chi nhánh", resp="HomeVisitAppointment",
     path_params=["AppointmentId"], errors=(400, 401, 403, 404),
     err_desc={404: "Không tìm thấy lịch tại nhà — cả khi lịch thuộc người khác (ADR-0006)"})
m.op("post", "/home-visit-appointments/{appointmentId}/start", "startHomeVisit", "Bắt đầu khám tại nhà", "Home visit", "UC51",
     "BR-KB-07, BR-BH-01, 16", "A07", "VET của chi nhánh", "BOOKED → CHECKED_IN (Lịch khám tại nhà#2); Visit#1 biến thể",
     resp="HomeVisitStarted", path_params=["AppointmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-07` ngoài ngày hẹn · không phải lịch tại nhà · thú đã mất · `BR-TN-01` thú đã có Visit mở", 409: TRANS},
     notes="Tạo Visit `IN_PROGRESS` gán VET gọi, mở Order `VISIT` với dòng khám và dòng phí khám tại nhà (snapshot).")
m.op("post", "/home-visit-appointments/{appointmentId}/mark-unreachable", "markHomeVisitUnreachable",
     "Ghi nhận không gặp được khách", "Home visit", "UC51", "BR-LH-14, BR-LH-09", "A07", "VET của chi nhánh",
     "BOOKED → NO_SHOW (Lịch khám tại nhà#3)", body="MarkUnreachableRequest", resp="HomeVisitAppointment",
     path_params=["AppointmentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LH-14` ngoài ngày hẹn · thiếu ghi chú", 409: TRANS},
     notes="Tính vào hạn chế đặt online của khách (BR-LH-09); thông báo khách.")
