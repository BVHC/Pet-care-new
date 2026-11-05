from lib import Module

m = Module(
    key="surgery", title="Surgery API v1 (tầng 3)", codes="KB (phẫu thuật)", owner="BE-1",
    sources="01 UC50; 09-tang3-be1-spec.md §B (BR-KB-08…11; 03 #23 Phẫu thuật); 05 bổ sung surgeries",
    intro="VET lập kế hoạch phẫu thuật từ lượt khám, lễ tân lập cam kết với khách, VET thực hiện và hoàn tất (dịch vụ phẫu thuật vào Order của Visit). **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Phẫu thuật không phải lịch hẹn riêng (BR-LH-01): chỉ phát sinh từ lượt khám `IN_PROGRESS`.",
        "Dịch vụ phẫu thuật vào Order do hệ thống thêm khi hoàn tất (`VisitOrderApi`, UC67), không có endpoint thêm dòng ở đây.",
        "Hủy phẫu thuật do hủy Visit (Phẫu thuật#6) là chuyển do hệ thống, không có endpoint.",
        "Nhập viện sau phẫu thuật dùng inpatient-v1 (UC55).",
    ],
    tags={"Surgeries": "Phẫu thuật gắn lượt khám (UC50)"},
    security=[
        "Chỉ VET đang phụ trách Visit lập kế hoạch, bắt đầu, hoàn tất (BR-KB-08, 10); lễ tân của chi nhánh của Visit lập cam kết và hủy trước khi bắt đầu (BR-KB-09, 11).",
        "CARETAKER (A08) chỉ xem, như bệnh án (BR-KB-01); chi nhánh khác trả 403 / 404 (BR-QT-03).",
        "Cam kết là bản chụp không sửa sau khi lập; ghi audit lập cam kết, hoàn tất, hủy (BR-QT-15 mở rộng).",
        "Khóa Visit rồi mới đổi trạng thái phẫu thuật và thêm dòng Order, cùng transaction (04 nguyên tắc 6).",
    ],
    assumptions=[
        ("A1", "Thêm loại `SURGERY` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm (BR-SP-06)", "Cần để chọn dịch vụ phẫu thuật; BE-2 xác nhận"),
        ("A2", "Cam kết chỉ ghi nhận đã ký trên giấy, không ký điện tử", "YAGNI; UC50 'lập cam kết'"),
        ("A3", "Dừng phẫu thuật giữa chừng không tính dịch vụ", "BR-KB-11; BE-1 / PO xác nhận"),
    ],
    questions=[
        ("Q1", "BE-2 xác nhận thêm `SURGERY`, `INPATIENT` vào `services.medical_type`", "TBD (BE-2)", "BR-SP-06"),
        ("Q2", "Có cần nhiều bác sĩ (phụ phẫu), gây mê riêng không", "TBD (PO)", "BR-KB-08"),
        ("Q3", "Dừng phẫu thuật có tính phí một phần không", "TBD (PO)", "BR-KB-11"),
    ],
)

m.param("VisitId", "visitId", "path", "int64")
m.param("SurgeryId", "surgeryId", "path", "int64")

m.schema("SurgeryStatus", None, enum=["PLANNED", "CONSENTED", "IN_PROGRESS", "COMPLETED", "CANCELLED"], desc="03 #23")
m.schema("PlanSurgeryRequest", {"serviceId": "int64|Dịch vụ loại `SURGERY` (BR-KB-08)", "planNote": "string:500?"})
m.schema("ConsentSurgeryRequest", {
    "consentText": "string|Rủi ro, chi phí dự kiến, phương pháp; bản chụp (BR-KB-09)",
    "signerName": "string:100", "signerRelation": "string:50|Chủ thú, người đại diện…",
})
m.schema("CompleteSurgeryRequest", {"resultNote": "string|Kết quả, biến chứng nếu có (BR-KB-10)"})
m.schema("CancelSurgeryRequest", {"reason": "string:300"})
m.schema("SurgerySummary", {
    "surgeryId": "int64", "visitId": "int64", "petName": "string", "serviceName": "string",
    "status": "ref:SurgeryStatus", "createdAt": "datetime",
})
m.schema("Surgery", {
    "surgeryId": "int64", "visitId": "int64", "petId": "int64", "petName": "string", "branchId": "int64",
    "serviceId": "int64", "serviceName": "string", "status": "ref:SurgeryStatus", "planNote": "string?",
    "consentText": "string?", "consentSignerName": "string?", "consentSignerRelation": "string?",
    "consentedAt": "datetime?", "startedAt": "datetime?", "completedAt": "datetime?", "resultNote": "string?",
    "cancelReason": "string?", "orderLineId": "int64?|Có sau khi hoàn tất", "createdAt": "datetime",
})

VET = "VET phụ trách Visit"
LT = "Lễ tân chi nhánh của Visit"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái phẫu thuật không cho phép (03 #23)"

m.op("post", "/visits/{visitId}/surgeries", "planSurgery", "Lập kế hoạch phẫu thuật từ lượt khám", "Surgeries", "UC50",
     "BR-KB-08", "A07", VET, "— → PLANNED (Phẫu thuật#1)", body="PlanSurgeryRequest", resp="Surgery", status=201,
     path_params=["VisitId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KB-08` Visit không `IN_PROGRESS` · dịch vụ không phải `SURGERY` hoặc không bật ở chi nhánh"},
     notes="Thông báo lễ tân chi nhánh để lập cam kết.")
m.op("get", "/visits/{visitId}/surgeries", "listVisitSurgeries", "Phẫu thuật của một lượt khám", "Surgeries", "UC50",
     "BR-KB-08", "A06, A07, A08", "A07 / A06 của chi nhánh; A08 chỉ xem", resp="array:Surgery", path_params=["VisitId"],
     errors=(400, 401, 403, 404), notes="Không phân trang (ít dòng). Cũ nhất trước.")
m.op("get", "/surgeries", "listSurgeries", "Phẫu thuật của chi nhánh", "Surgeries", "UC50", "BR-KB-09", "A06, A07",
     "A06, A07 của chi nhánh", resp="SurgerySummary", page=True,
     query=["status=enum:PLANNED,CONSENTED,IN_PROGRESS,COMPLETED,CANCELLED", "page", "size"], errors=(400, 401, 403),
     notes="`status` vắng = `PLANNED` + `CONSENTED` + `IN_PROGRESS` (việc cần làm). Cũ nhất trước.")
m.op("get", "/surgeries/{surgeryId}", "getSurgery", "Chi tiết phẫu thuật", "Surgeries", "UC50", "BR-KB-08",
     "A06, A07, A08", "A06, A07 của chi nhánh; A08 chỉ xem", resp="Surgery", path_params=["SurgeryId"],
     errors=(400, 401, 403, 404))
m.op("post", "/surgeries/{surgeryId}/consent", "consentSurgery", "Lập cam kết phẫu thuật", "Surgeries", "UC50",
     "BR-KB-09", "A06", LT, "PLANNED → CONSENTED (Phẫu thuật#2)", body="ConsentSurgeryRequest", resp="Surgery",
     path_params=["SurgeryId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-09` thiếu nội dung hoặc người ký", 409: TRANS}, notes="Bản chụp, không sửa sau khi lập.")
m.op("post", "/surgeries/{surgeryId}/start", "startSurgery", "Bắt đầu phẫu thuật", "Surgeries", "UC50", "BR-KB-09, 10",
     "A07", VET, "CONSENTED → IN_PROGRESS (Phẫu thuật#3)", resp="Surgery", path_params=["SurgeryId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-KB-09` chưa có cam kết · Visit không còn `IN_PROGRESS`", 409: TRANS})
m.op("post", "/surgeries/{surgeryId}/complete", "completeSurgery", "Hoàn tất phẫu thuật", "Surgeries", "UC50", "BR-KB-10",
     "A07", VET, "IN_PROGRESS → COMPLETED (Phẫu thuật#4)", body="CompleteSurgeryRequest", resp="Surgery",
     path_params=["SurgeryId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-10` thiếu ghi chú kết quả", 409: TRANS},
     notes="Thêm dịch vụ phẫu thuật vào Order của Visit với giá chốt lúc này (BR-BH-03).")
m.op("post", "/surgeries/{surgeryId}/cancel", "cancelSurgery", "Hủy hoặc dừng phẫu thuật", "Surgeries", "UC50", "BR-KB-11",
     "A07, A06", "A07 phụ trách; A06 chỉ khi `PLANNED` / `CONSENTED`",
     "PLANNED / CONSENTED / IN_PROGRESS → CANCELLED (Phẫu thuật#5)", body="CancelSurgeryRequest", resp="Surgery",
     path_params=["SurgeryId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-11` thiếu lý do", 403: "Lễ tân không hủy được phẫu thuật `IN_PROGRESS`", 409: TRANS})
