from lib import Module

m = Module(
    key="inpatient", title="Inpatient API v1 (tầng 3)", codes="LT (nội trú)", owner="BE-1",
    sources="01 UC55, UC56; 09-tang3-be1-spec.md §C (BR-LT-13…17; 03 #24 Nhập viện, #25 Y lệnh nội trú); 05 bổ sung admissions, admission_orders, admission_observations",
    intro="Nhập / xuất viện (gồm chuyển thú đang lưu trú sang điều trị), y lệnh và theo dõi hậu phẫu. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.** Package thuộc BE-1 (đề xuất, Q2), không phải `boarding` của BE-2.",
    frozen=[
        "Dịch vụ nội trú vào Order do hệ thống thêm khi xuất viện (`VisitOrderApi`, UC67), không có endpoint thêm dòng ở đây.",
        "Kê đơn cho y lệnh `MEDICATION` dùng `POST /visits/{visitId}/prescriptions` của visit-v1 (#16); y lệnh chỉ liên kết dòng đơn.",
        "Khách chưa có endpoint xem nội trú (09 Q4).",
        "Đánh dấu thú đã mất khi kết cục `DECEASED` dùng `POST /pets/{petId}/mark-deceased` của customer-v1 (BR-KH-05).",
    ],
    tags={
        "Admissions": "Nhập / xuất viện (UC55)",
        "Admission orders": "Y lệnh nội trú (UC56)",
        "Observations": "Theo dõi hậu phẫu (UC56)",
    },
    security=[
        "Chỉ nhân viên của chi nhánh của ca nội trú (BR-QT-03): VET nhập / xuất viện, ra và hủy y lệnh; A08 chỉ làm việc không phải thuốc và ghi theo dõi; A06 chỉ xem để lập phiếu thu.",
        "Y lệnh thuốc chỉ VET thực hiện (BR-LT-15); theo dõi chỉ thêm, không sửa, không xóa (BR-LT-16).",
        "Nhập viện từ lưu trú khóa đặt chỗ và thú (`PetQueryApi.findPetForShare`, ADR-0041) rồi tạo Visit điều trị trong một transaction.",
        "Chi nhánh khác trả 403 / 404; mỗi thú tối đa một ca `ADMITTED` (BR-LT-13).",
    ],
    assumptions=[
        ("A1", "Thêm loại `INPATIENT` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm, giá theo ngày", "BR-LT-13; BE-2 xác nhận"),
        ("A2", "Nội trú dùng chung chuồng với lưu trú", "UC55 không nêu chuồng riêng"),
        ("A3", "Phí lưu trú theo đêm và phí nội trú theo ngày tính riêng", "BR-LT-17"),
    ],
    questions=[
        ("Q1", "BE-2 xác nhận `INPATIENT` trong `services.medical_type` và dùng chung chuồng", "TBD (BE-2)", "09 §C"),
        ("Q2", "Package `inpatient` thuộc BE-1 (đề xuất) hay `boarding` của BE-2", "TBD (BE-1, BE-2)", "06 §2, đề cương 2.4.4"),
        ("Q3", "Y lệnh có chu kỳ lặp (mỗi N giờ) hay mỗi y lệnh một lần", "TBD (PO)", "BR-LT-15"),
        ("Q4", "Khách có xem được tình trạng nội trú không", "TBD (PO)", "BR-LT-17"),
    ],
)

m.param("AdmissionId", "admissionId", "path", "int64")
m.param("AdmissionOrderId", "admissionOrderId", "path", "int64")

m.schema("AdmissionStatus", None, enum=["ADMITTED", "DISCHARGED"], desc="03 #24")
m.schema("DischargeOutcome", None, enum=["HOME", "DECEASED"])
m.schema("AdmissionOrderType", None, enum=["MEDICATION", "FEEDING", "MONITORING", "PROCEDURE", "OTHER"])
m.schema("AdmissionOrderStatus", None, enum=["ORDERED", "DONE", "CANCELLED"], desc="03 #25")
m.schema("Appetite", None, enum=["NORMAL", "REDUCED", "NONE"])
m.schema("AdmitRequest", {
    "visitId": "int64?|Nhập viện từ lượt khám `IN_PROGRESS` của mình", "boardingBookingId": "int64?|Hoặc chuyển từ đặt chỗ `CHECKED_IN` (BR-LT-13). Đúng một trong hai",
    "serviceId": "int64|Dịch vụ loại `INPATIENT`", "kennelId": "int64?|Chuồng `AVAILABLE`; vắng = dùng chuồng đang ở (từ lưu trú)",
})
m.schema("Admission", {
    "admissionId": "int64", "code": "string", "visitId": "int64", "petId": "int64", "petName": "string", "branchId": "int64",
    "boardingBookingId": "int64?", "kennelId": "int64?", "serviceId": "int64", "serviceName": "string",
    "unitPrice": "money|Giá theo ngày, chốt lúc nhập viện", "status": "ref:AdmissionStatus", "admittedAt": "datetime",
    "dischargeOutcome": "ref:DischargeOutcome?", "dischargeSummary": "string?", "dischargedAt": "datetime?",
    "orderLineId": "int64?|Có sau khi xuất viện",
})
m.schema("AdmissionSummary", {
    "admissionId": "int64", "code": "string", "petName": "string", "status": "ref:AdmissionStatus",
    "admittedAt": "datetime", "kennelCode": "string?",
})
m.schema("DischargeRequest", {"outcome": "ref:DischargeOutcome", "summary": "string|Tóm tắt điều trị (BR-LT-14)"})
m.schema("AdmissionOrder", {
    "admissionOrderId": "int64", "admissionId": "int64", "orderType": "ref:AdmissionOrderType", "content": "string",
    "scheduledAt": "datetime", "status": "ref:AdmissionOrderStatus", "prescriptionItemId": "int64?",
    "doneAt": "datetime?", "doneNote": "string?", "cancelReason": "string?",
})
m.schema("CreateAdmissionOrderRequest", {
    "orderType": "ref:AdmissionOrderType", "content": "string:500", "scheduledAt": "datetime",
    "prescriptionItemId": "int64?|Bắt buộc với `MEDICATION` (dòng đơn của Visit, BR-LT-15)",
})
m.schema("CompleteAdmissionOrderRequest", {"note": "string:300"})
m.schema("ReasonRequest", {"reason": "string:300"})
m.schema("Observation", {
    "observationId": "int64", "admissionId": "int64", "recordedByName": "string", "temperatureC": "decimal?",
    "heartRate": "int?", "respiratoryRate": "int?", "weightKg": "decimal?", "appetite": "ref:Appetite?",
    "note": "string?", "abnormal": "bool", "createdAt": "datetime",
})
m.schema("CreateObservationRequest", {
    "temperatureC": "decimal?", "heartRate": "int?", "respiratoryRate": "int?", "weightKg": "decimal?",
    "appetite": "ref:Appetite?", "note": "string:500?", "abnormal": "bool|true báo VET và khách (BR-LT-16)",
})

VET = "VET của chi nhánh"
ALL = "A06 chỉ xem; A07; A08 theo BR-LT-15"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25)"

m.op("post", "/admissions", "admitPet", "Nhập viện", "Admissions", "UC55", "BR-LT-13", "A07", VET,
     "— → ADMITTED (Nhập viện#1)", body="AdmitRequest", resp="Admission", status=201, errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-13` không đúng một trong `visitId` / `boardingBookingId` · Visit không `IN_PROGRESS` / không của mình · đặt chỗ không `CHECKED_IN` · dịch vụ không `INPATIENT` · chuồng không `AVAILABLE` · thú đã mất",
               404: "Không tìm thấy Visit / đặt chỗ / dịch vụ / chuồng", 409: "Thú đã có ca nội trú `ADMITTED`"},
     notes="Từ lưu trú: tạo Visit điều trị gán VET gọi, đặt chỗ giữ nguyên `CHECKED_IN` (BR-LT-17).")
m.op("get", "/admissions", "listAdmissions", "Ca nội trú của chi nhánh", "Admissions", "UC55", "BR-LT-17", "A06, A07, A08",
     ALL, resp="AdmissionSummary", page=True, query=["status=enum:ADMITTED,DISCHARGED", "page", "size"],
     errors=(400, 401, 403), notes="`status` vắng = `ADMITTED`. Nhập viện sớm nhất trước.")
m.op("get", "/admissions/{admissionId}", "getAdmission", "Chi tiết ca nội trú", "Admissions", "UC55", "BR-LT-17",
     "A06, A07, A08", ALL, resp="Admission", path_params=["AdmissionId"], errors=(400, 401, 403, 404))
m.op("post", "/admissions/{admissionId}/discharge", "dischargeAdmission", "Xuất viện", "Admissions", "UC55",
     "BR-LT-14", "A07", VET, "ADMITTED → DISCHARGED (Nhập viện#2)", body="DischargeRequest", resp="Admission",
     path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-14` còn y lệnh `ORDERED` · thiếu tóm tắt · `DECEASED` mà thú chưa đánh dấu đã mất", 409: TRANS},
     notes="Thêm dòng dịch vụ nội trú (số ngày × giá đã chốt) vào Order của Visit; giải phóng chuồng nếu được cấp riêng.")

m.op("post", "/admissions/{admissionId}/orders", "createAdmissionOrder", "Ra y lệnh", "Admission orders", "UC56",
     "BR-LT-15", "A07", VET, "— → ORDERED (Y lệnh nội trú#1)", body="CreateAdmissionOrderRequest", resp="AdmissionOrder",
     status=201, path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-15` `MEDICATION` thiếu dòng đơn thuốc · dòng đơn không thuộc Visit", 409: "Ca không `ADMITTED`"})
m.op("get", "/admissions/{admissionId}/orders", "listAdmissionOrders", "Y lệnh của ca nội trú", "Admission orders", "UC56",
     "BR-LT-15", "A06, A07, A08", ALL, resp="array:AdmissionOrder", path_params=["AdmissionId"],
     query=["status=enum:ORDERED,DONE,CANCELLED"], errors=(400, 401, 403, 404),
     notes="Không phân trang. Sắp `scheduledAt` tăng dần, trùng thì `orderId` tăng dần.")
m.op("post", "/admission-orders/{admissionOrderId}/complete", "completeAdmissionOrder", "Đánh dấu đã thực hiện y lệnh",
     "Admission orders", "UC56", "BR-LT-15", "A07, A08", "A07; A08 chỉ việc không phải thuốc",
     "ORDERED → DONE (Y lệnh nội trú#2)", body="CompleteAdmissionOrderRequest", resp="AdmissionOrder",
     path_params=["AdmissionOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-15` thiếu ghi chú", 403: "A08 không thực hiện y lệnh thuốc", 409: TRANS})
m.op("post", "/admission-orders/{admissionOrderId}/cancel", "cancelAdmissionOrder", "Hủy y lệnh", "Admission orders", "UC56",
     "BR-LT-15", "A07", VET, "ORDERED → CANCELLED (Y lệnh nội trú#3)", body="ReasonRequest", resp="AdmissionOrder",
     path_params=["AdmissionOrderId"], errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-LT-15` thiếu lý do", 409: TRANS})

m.op("post", "/admissions/{admissionId}/observations", "createObservation", "Ghi chỉ số theo dõi", "Observations", "UC56",
     "BR-LT-16", "A07, A08", "A07, A08 của chi nhánh", body="CreateObservationRequest", resp="Observation", status=201,
     path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-16` không có chỉ số hay ghi chú nào · giá trị ngoài khoảng", 409: "Ca không `ADMITTED`"},
     notes="Chỉ thêm, không sửa / xóa. `abnormal = true` báo VET và khách (BR-LT-12); cân nặng ghi vào hồ sơ cân (BR-KH-04).")
m.op("get", "/admissions/{admissionId}/observations", "listObservations", "Chỉ số theo dõi của ca nội trú", "Observations",
     "UC56", "BR-LT-16", "A06, A07, A08", ALL, resp="Observation", page=True, path_params=["AdmissionId"],
     query=["page", "size"], errors=(400, 401, 403, 404), notes="Mới nhất trước.")
