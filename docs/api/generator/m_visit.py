from lib import Module

m = Module(
    key="visit", title="Visit & Clinical API v1", codes="TN, KB", owner="BE-1",
    sources="01 UC25, UC44–UC49, UC52, UC53; 02 BR-TN-01…09, BR-KB-01…06, BR-KH-04, 05, 07, BR-CN-05, BR-BH-02; 03 #4 Visit (+ Lịch hẹn#3, #7, #8, Order#1, #4, #6, Care Task#5, #6); 04 §6; 05 §6 (visits, visit_assignments, medical_records, medical_record_addenda, prescription_items, vaccinations)",
    intro="Tiếp nhận, hàng đợi, gán / gọi / gán lại lượt, hoàn tất / hủy lượt; bệnh án, bản bổ sung, kê đơn, tiêm chủng; hồ sơ sức khỏe thú cưng.",
    frozen=[
        "UC50 phẫu thuật, UC51 khám tại nhà, UC55–UC56 nội trú là tầng 3.",
        "Thêm / đổi / xóa dòng dịch vụ, hàng của Order nguồn VISIT (UC67) thuộc module sales: `/orders/{orderId}/lines`.",
        "ST04 nhắc tái chủng / tái khám, ST06 trừ kho khi tiêm (chạy trong `POST …/vaccinations`) và ST07 (tầng 3) không có endpoint riêng.",
        "Lịch sử cân nặng đọc ở module customer: `GET /pets/{petId}/weights`.",
    ],
    tags={
        "Queue": "Tiếp nhận và hàng đợi (UC44–UC47)",
        "Completion": "Hoàn tất, hủy lượt (UC45, UC48, UC49, UC52)",
        "Medical record": "Bệnh án, cân nặng, bản bổ sung (UC48, UC53)",
        "Prescriptions": "Kê đơn (UC48, BR-KB-03)",
        "Vaccinations": "Tiêm chủng (UC49, BR-KB-04)",
        "Health record": "Hồ sơ sức khỏe thú cưng (UC25, UC53)",
    },
    security=[
        "Nhân viên chỉ thấy và thao tác Visit của chi nhánh mình. Chỉ người được gán gọi lượt, ghi bệnh án, kê đơn, tiêm, hoàn tất (BR-TN-05, BR-KB-01).",
        "Tiếp nhận tạo Visit, chuyển lịch hẹn và mở Order trong một transaction (03 Phụ lục, 06 §4.1). Hoàn tất / hủy lượt tương tự.",
        "Ghi mũi tiêm: khóa lô FEFO, sinh dòng Order, ghi mũi, trừ kho trong một transaction theo thứ tự ở 06-module-contracts §5. Xóa mũi ghi nhầm đi ngược lại.",
        "`internalNote` và bản bổ sung `isInternal` không bao giờ trả cho khách (BR-KB-01, BR-KH-07).",
        "Gán lại lượt đã gọi, bản bổ sung bệnh án ghi audit (BR-TN-08, BR-KB-01, BR-QT-15).",
        "Thú đã mất không được tiếp nhận (BR-TN-01, BR-KH-05).",
    ],
    assumptions=[
        ("A1", "Danh sách hàng đợi trả theo thứ tự ưu tiên của BR-TN-04 (`priorityClass`, rồi `queueSortKey`), mặc định Visit hôm nay chưa kết thúc", "BR-TN-04"),
        ("A2", "UC47 'Xác định ca cấp cứu' sau khi đã tiếp nhận: `POST …/mark-emergency` khi Visit còn WAITING", "UC47 có actor A07, A06; tiếp nhận chỉ do A06"),
        ("A3", "Sắp lại thứ tự hàng đợi bằng cách đặt Visit trước một Visit khác; server tính lại `queue_sort_key`", "05 `visits.queue_sort_key`: lễ tân sắp lại thì sửa cột này"),
        ("A4", "Lý do hủy lượt không bắt buộc", "BR-TN-07 không yêu cầu; `visits.cancel_reason` cho phép null"),
        ("A5", "Thiếu tồn khi kê đơn: request đầu trả 400 `BR-KB-03` kèm số tồn trong message; FE gửi lại với `shortageResolution`", "BR-KB-03: báo số tồn rồi VET chọn giảm số lượng hoặc mua ngoài"),
        ("A6", "Ngày tiêm = ngày hiện tại theo giờ Việt Nam, không cho chọn", "BR-KB-04 trừ kho ngay khi ghi nhận; 05 `administered_on` không ở tương lai"),
        ("A7", "Bản bổ sung bệnh án do VET của chi nhánh nơi khám ghi, sau khi Visit COMPLETED", "BR-KB-01: chỉ VET; sau khi khóa mới có bản bổ sung"),
        ("A8", "Cân nặng khi khám ghi bằng endpoint riêng, mỗi lần là một bản ghi", "05 `weight_records` là LOG"),
    ],
    questions=[
        ("Q1", "Dòng dịch vụ tự sinh khi tiếp nhận có thể bị VET đổi (BR-TN-01). Nếu đổi từ dịch vụ thường sang dịch vụ loại Khám thì có hủy nhắc tái khám như Visit#1 có dịch vụ Khám không?", "TBD (PO), tạm chỉ xét tại Visit#1 (06 G5)", "03 Care Task#6"),
    ],
)

m.param("VisitId", "visitId", "path", "int64")
m.param("PetId", "petId", "path", "int64")
m.param("PrescriptionItemId", "itemId", "path", "int64")
m.param("VaccinationId", "vaccinationId", "path", "int64")

m.schema("VisitStatus", None, enum=["WAITING", "IN_PROGRESS", "COMPLETED", "CANCELLED"], desc="03 #4")
m.schema("PriorityClass", None, enum=["EMERGENCY", "APPOINTMENT", "WALK_IN"], desc="BR-TN-03, 04")
m.schema("CreateVisitRequest", {
    "petId": "int64",
    "appointmentId": "int64?|Có = check-in lịch hẹn; không = walk-in",
    "serviceId": "int64?|Bắt buộc với walk-in; có lịch hẹn thì lấy dịch vụ của lịch",
    "isEmergency": "bool?|Bắt buộc true khi tiếp nhận ngoài giờ (BR-CN-05)",
}, required=["petId"])
m.schema("Visit", {
    "visitId": "int64", "code": "string", "branchId": "int64", "customerId": "int64", "customerName": "string",
    "petId": "int64", "petName": "string", "species": "enum:DOG,CAT,OTHER", "appointmentId": "int64?",
    "serviceGroup": "enum:MEDICAL,GROOMING", "status": "ref:VisitStatus", "priorityClass": "ref:PriorityClass",
    "queueSortKey": "datetime", "assigneeId": "int64?", "assigneeName": "string?",
    "needsReassign": "bool|Người phụ trách bị khóa (BR-QT-11, BR-TN-08)", "orderId": "int64",
    "checkedInAt": "datetime", "calledAt": "datetime?", "completedAt": "datetime?", "cancelledAt": "datetime?",
    "cancelReason": "string?",
})
m.schema("AssignRequest", {"assigneeId": "int64|VET cho MEDICAL, CARETAKER cho GROOMING (BR-TN-05)"})
m.schema("AssignResult", {
    "visit": "ref:Visit",
    "assigneeOffline": "bool|true = đã cảnh báo người được gán đang offline, không chặn (BR-TN-06)",
})
m.schema("MoveVisitRequest", {"placeBeforeVisitId": "int64|Visit WAITING cùng chi nhánh (A3)"})
m.schema("ReassignRequest", {"assigneeId": "int64", "reason": "string:300|Bắt buộc (BR-TN-08)"})
m.schema("CancelVisitRequest", {"reason": "string:300?"}, required=[])
m.schema("PrescriptionItem", {
    "itemId": "int64", "productId": "int64", "productName": "string", "quantity": "int",
    "dosageInstructions": "string:500", "isExternalPurchase": "bool|Mua ngoài: vẫn in trên đơn, không sinh dòng Order",
    "externalReason": "enum:OUT_OF_STOCK_AT_PRESCRIBE,OUT_OF_STOCK_AT_PAYMENT?", "orderLineId": "int64?",
})
m.schema("Addendum", {
    "addendumId": "int64", "content": "string", "isInternal": "bool", "createdBy": "int64",
    "createdByName": "string", "createdAt": "datetime",
})
m.schema("VaccinationRecord", {
    "vaccinationId": "int64", "visitId": "int64", "branchId": "int64", "vaccineTypeId": "int64",
    "vaccineTypeName": "string", "protocolId": "int64", "doseNumber": "int", "productId": "int64",
    "productName": "string", "lotNumber": "string?", "expiryDate": "date?", "administeredOn": "date",
    "nextDueDate": "date|= ngày tiêm + khoảng cách của mũi trong phác đồ (BR-KB-04)",
    "superseded": "bool|Đã có mũi mới hơn cùng loại vaccine (BR-TB-03)", "recordedBy": "int64",
    "orderLineId": "int64",
})
m.schema("MedicalRecord", {
    "visitId": "int64", "examination": "string?", "diagnosis": "string?", "treatmentPlan": "string?",
    "internalNote": "string?|Chỉ nhân viên", "followUpDate": "date?",
    "locked": "bool|Visit COMPLETED: chỉ thêm bản bổ sung (BR-KB-01)", "lastEditedBy": "int64?",
    "prescriptions": "array:ref:PrescriptionItem", "vaccinations": "array:ref:VaccinationRecord",
    "addenda": "array:ref:Addendum",
})
m.schema("UpdateMedicalRecordRequest", {
    "examination": "string?|Gồm tiền sử tiêm do chủ khai (04 nguyên tắc 9)", "diagnosis": "string?",
    "treatmentPlan": "string?", "internalNote": "string?",
    "followUpDate": "date?|Sau ngày khám, xa nhất 180 ngày [CFG] (BR-KB-06)",
}, required=[])
m.schema("RecordWeightRequest", {"weightKg": "decimal|> 0 (BR-KH-04)"})
m.schema("WeightRecordRef", {"weightRecordId": "int64", "weightKg": "decimal", "measuredAt": "datetime"})
m.schema("AddendumRequest", {"content": "string", "isInternal": "bool?|Bổ sung cho ghi chú nội bộ, khách không thấy"},
         required=["content"])
m.schema("AddPrescriptionRequest", {
    "productId": "int64|Thuốc kê đơn (BR-KB-03, BR-SP-01)", "quantity": "int|> 0",
    "dosageInstructions": "string:500",
    "shortageResolution": "enum:REDUCE_TO_AVAILABLE,EXTERNAL_PURCHASE?|Chỉ gửi khi lần trước bị từ chối vì thiếu tồn (A5)",
})
m.schema("VaccineProductOption", {"productId": "int64", "name": "string", "availableQuantity": "int"})
m.schema("VaccinationOptions", {
    "vaccineTypeId": "int64",
    "protocol": "array:ref:ProtocolDoseOption|Các mũi trong phác đồ của loài",
    "suggestedProtocolId": "int64?|Gợi ý theo lịch sử tiêm cùng loại tại hệ thống, chỉ tham khảo (BR-KB-04)",
    "lastDose": "ref:VaccinationRecord",
    "products": "array:ref:VaccineProductOption|Chỉ sản phẩm thuộc loại còn tồn khả dụng tại chi nhánh",
    "underMinAge": "bool|Thú chưa đủ tuổi tối thiểu của mũi gợi ý — cảnh báo, không chặn",
}, required=["vaccineTypeId", "protocol", "products", "underMinAge"])
m.schema("ProtocolDoseOption", {
    "protocolId": "int64", "doseNumber": "int", "intervalDays": "int", "minAgeWeeks": "int",
    "requiredForBoarding": "bool",
})
m.schema("RecordVaccinationRequest", {
    "vaccineTypeId": "int64", "protocolId": "int64|Mũi VET chọn, có thể khác gợi ý",
    "productId": "int64|Vaccine thuộc loại đã chọn, còn tồn khả dụng",
})
m.schema("RecordVaccinationResult", {
    "vaccination": "ref:VaccinationRecord",
    "warnings": "array:string|Ví dụ UNDER_MIN_AGE",
})
m.schema("HealthVisitEntry", {
    "visitId": "int64", "visitDate": "date", "branchName": "string", "serviceGroup": "enum:MEDICAL,GROOMING",
    "examination": "string?", "diagnosis": "string?", "treatmentPlan": "string?",
    "internalNote": "string?|Chỉ trả cho nhân viên", "followUpDate": "date?",
    "prescriptions": "array:ref:PrescriptionItem", "addenda": "array:ref:Addendum|Khách không thấy bản isInternal",
})
m.schema("UpcomingVaccination", {"vaccineTypeId": "int64", "vaccineTypeName": "string", "nextDueDate": "date"})
m.schema("HealthRecord", {
    "petId": "int64", "visits": "array:ref:HealthVisitEntry", "vaccinations": "array:ref:VaccinationRecord",
    "upcomingVaccinations": "array:ref:UpcomingVaccination|Mũi chưa bị thay thế",
    "upcomingFollowUpDate": "date?",
})

VET_ASSIGNED = "Người được gán lượt"
m.op("post", "/visits", "checkInVisit", "Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu)", "Queue", "UC44, UC47",
     "BR-TN-01…04, BR-CN-05, BR-LH-01, BR-KH-05", "A06", "Lễ tân; chi nhánh của lễ tân",
     "— → WAITING (Visit#1) · Lịch hẹn#3 · Order#1 (OPEN + dòng dịch vụ tự sinh) · có dịch vụ Khám → Care Task#6",
     body="CreateVisitRequest", resp="Visit", status=201, errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TN-01` thú đã mất / walk-in thiếu dịch vụ · `BR-TN-02` ngoài cửa sổ check-in · `BR-CN-05` ngoài giờ mà chi nhánh không có cờ hoặc chưa đánh dấu cấp cứu · `BR-CN-01` chi nhánh chưa ACTIVE · `BR-LH-01` dịch vụ đang tắt",
               409: "Lịch hẹn không còn BOOKED (đã check-in / NO_SHOW: tiếp nhận như walk-in, BR-TN-03)"},
     notes="Trễ 15–30 phút [CFG] xếp như walk-in. Khách mang nhiều thú thì tạo nhiều Visit.")
m.op("get", "/visits", "listQueue", "Hàng đợi của chi nhánh", "Queue", "UC45", "BR-TN-04, 09", "A05, A06, A07, A08",
     "Nhân viên chi nhánh", resp="array:Visit", query=["date=date|Mặc định hôm nay",
                                                        "status=enum:WAITING,IN_PROGRESS,COMPLETED,CANCELLED",
                                                        "serviceGroup=enum:MEDICAL,GROOMING", "assigneeId=int64",
                                                        "needsReassign=bool"], errors=(400, 401, 403),
     notes="A1.")
m.op("get", "/me/queue", "listMyQueue", "Hàng đợi của tôi", "Queue", "UC46", "BR-TN-05", "A07, A08",
     "VET, CARETAKER", resp="array:Visit", errors=(401, 403), notes="Visit WAITING / IN_PROGRESS gán cho tôi.")
m.op("get", "/visits/{visitId}", "getVisit", "Chi tiết lượt", "Queue", "UC45, UC46", "—", "A05–A08",
     "Nhân viên chi nhánh", resp="Visit", path_params=["VisitId"], errors=(401, 403, 404))
m.op("post", "/visits/{visitId}/assign", "assignVisit", "Gán / gán lại nhân viên (lượt chưa gọi)", "Queue", "UC45",
     "BR-TN-05, 06, 08", "A06", "Lễ tân chi nhánh", "WAITING → WAITING (Visit#2)", body="AssignRequest",
     resp="AssignResult", path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TN-05` nhân viên không ACTIVE, khác chi nhánh hoặc sai chức vụ", 409: "Lượt đã được gọi"},
     notes="Dòng dịch vụ tự sinh chuyển quyền cho người được gán.")
m.op("post", "/visits/{visitId}/move", "moveVisitInQueue", "Sắp lại thứ tự hàng đợi", "Queue", "UC45", "BR-TN-04",
     "A06", "Lễ tân chi nhánh", body="MoveVisitRequest", resp="array:Visit", path_params=["VisitId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: "Lượt đã được gọi"}, notes="A3. Trả hàng đợi sau khi sắp.")
m.op("post", "/visits/{visitId}/mark-emergency", "markVisitEmergency", "Xác định ca cấp cứu", "Queue", "UC47",
     "BR-TN-04", "A06, A07", "Nhân viên chi nhánh", resp="Visit", path_params=["VisitId"],
     errors=(401, 403, 404, 409), err_desc={409: "Lượt đã được gọi hoặc đã là cấp cứu"},
     notes="Lượt lên đầu hàng đợi (A2).")
m.op("post", "/visits/{visitId}/call", "callVisit", "Gọi lượt", "Queue", "UC46", "BR-TN-05, 09", "A07, A08",
     "Người được gán lượt", "WAITING → IN_PROGRESS (Visit#3)", resp="Visit", path_params=["VisitId"],
     errors=(401, 403, 404, 409), err_desc={403: "`BR-TN-05` lượt không gán cho người gọi", 409: "Lượt không còn WAITING"})
m.op("post", "/visits/{visitId}/reassign", "reassignCalledVisit", "Gán lại lượt đã gọi", "Queue", "UC45",
     "BR-TN-05, 08, BR-QT-15", "A05 (A04 khi chi nhánh không còn BRANCH_MANAGER ACTIVE)",
     "BRANCH_MANAGER chi nhánh; SUPER_MANAGER theo điều kiện", "IN_PROGRESS → IN_PROGRESS (Visit#4)",
     body="ReassignRequest", resp="Visit", path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TN-08` người phụ trách vẫn ACTIVE · thiếu lý do · `BR-TN-05` người mới sai chức vụ",
               409: "Lượt không IN_PROGRESS"},
     notes="Bệnh án giữ nguyên kèm tên người ghi; quyền xóa dòng Order chuyển cho người mới; ghi audit.")

m.op("post", "/visits/{visitId}/complete", "completeVisit", "Hoàn tất lượt", "Completion", "UC48, UC49, UC52",
     "BR-KB-01, 02, BR-SP-06, BR-LH-11", "A07, A08", VET_ASSIGNED,
     "IN_PROGRESS → COMPLETED (Visit#5) · Lịch hẹn#7 · Order#4 (→ PENDING) · bệnh án khóa", resp="Visit",
     path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-02` có dịch vụ loại Khám mà chưa có chẩn đoán · chỉ có dịch vụ loại Tiêm mà chưa ghi mũi nào",
               409: "Lượt không IN_PROGRESS"})
m.op("post", "/visits/{visitId}/cancel", "cancelVisit", "Hủy lượt khách bỏ về", "Completion", "UC45", "BR-TN-07",
     "A06", "Lễ tân chi nhánh", "WAITING → CANCELLED (Visit#6) · Lịch hẹn#8 · Order#6", body="CancelVisitRequest",
     resp="Visit", path_params=["VisitId"], errors=(401, 403, 404, 409),
     err_desc={409: "`BR-TN-07` lượt đã được gọi — nhân viên hoàn tất theo BR-KB-02"},
     notes="Lịch hẹn → CANCELLED, không tính hủy muộn.")

m.op("get", "/visits/{visitId}/medical-record", "getMedicalRecord", "Xem bệnh án của lượt", "Medical record",
     "UC48, UC53", "BR-KB-01", "A07, A08", "VET, CARETAKER chi nhánh (CARETAKER chỉ xem)", resp="MedicalRecord",
     path_params=["VisitId"], errors=(401, 403, 404), err_desc={404: "Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án)"})
m.op("put", "/visits/{visitId}/medical-record", "updateMedicalRecord", "Ghi bệnh án / hẹn tái khám",
     "Medical record", "UC48", "BR-KB-01, 06", "A07", "VET được gán lượt", body="UpdateMedicalRecordRequest",
     resp="MedicalRecord", path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-06` ngày tái khám không sau ngày khám hoặc quá 180 ngày [CFG]",
               409: "Lượt không IN_PROGRESS — bệnh án đã khóa, dùng bản bổ sung"})
m.op("post", "/visits/{visitId}/weight", "recordVisitWeight", "Ghi cân nặng khi khám", "Medical record", "UC48",
     "BR-KH-04", "A07", "VET được gán lượt", body="RecordWeightRequest", resp="WeightRecordRef", status=201,
     path_params=["VisitId"], errors=(400, 401, 403, 404, 409), err_desc={409: "Lượt không IN_PROGRESS"},
     notes="source = VET (A8).")
m.op("post", "/visits/{visitId}/medical-record/addenda", "addMedicalRecordAddendum", "Thêm bản bổ sung bệnh án",
     "Medical record", "UC53", "BR-KB-01, BR-QT-15", "A07", "VET chi nhánh nơi khám (A7)", body="AddendumRequest",
     resp="Addendum", status=201, path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "Visit chưa COMPLETED — sửa trực tiếp bệnh án"}, notes="Không sửa / xóa nội dung cũ; ghi audit.")

m.op("post", "/visits/{visitId}/prescriptions", "addPrescriptionItem", "Kê đơn một dòng thuốc", "Prescriptions",
     "UC48, UC67", "BR-KB-03, BR-SP-01, BR-BH-02", "A07", "VET được gán lượt", body="AddPrescriptionRequest",
     resp="PrescriptionItem", status=201, path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-03` thiếu tồn khả dụng (message ghi số tồn hiện có) — gửi lại kèm shortageResolution · sản phẩm không phải thuốc kê đơn",
               409: "Lượt không IN_PROGRESS"},
     notes="Đủ tồn → sinh 1 dòng Order DRUG; mua ngoài → không sinh dòng; không tách dòng.")
m.op("delete", "/visits/{visitId}/prescriptions/{itemId}", "removePrescriptionItem", "Xóa dòng đơn thuốc",
     "Prescriptions", "UC48", "BR-KB-03, BR-BH-02", "A07", "VET được gán lượt", status=204,
     path_params=["VisitId", "PrescriptionItemId"], errors=(401, 403, 404, 409),
     err_desc={409: "Lượt không IN_PROGRESS"}, notes="Xóa kèm dòng Order DRUG tương ứng.")

m.op("get", "/visits/{visitId}/vaccination-options", "getVaccinationOptions", "Gợi ý mũi và sản phẩm vaccine",
     "Vaccinations", "UC49", "BR-KB-04, BR-SP-02, 07, BR-KO-01", "A07", "VET được gán lượt", resp="VaccinationOptions",
     path_params=["VisitId"], query=["vaccineTypeId!=int64|Loại vaccine đúng loài của thú"],
     errors=(400, 401, 403, 404), err_desc={400: "Loại vaccine không áp dụng cho loài của thú"})
m.op("post", "/visits/{visitId}/vaccinations", "recordVaccination", "Ghi nhận mũi tiêm", "Vaccinations", "UC49, ST06",
     "BR-KB-04, BR-KO-05, BR-TB-03", "A07", "VET được gán lượt",
     "IN_PROGRESS giữ nguyên · dòng Order VACCINE · trừ kho FEFO · Care Task#5", body="RecordVaccinationRequest",
     resp="RecordVaccinationResult", status=201, path_params=["VisitId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KB-04` sản phẩm không thuộc loại vaccine, không còn tồn khả dụng, hoặc mũi không thuộc phác đồ đúng loài",
               409: "Lượt không IN_PROGRESS"},
     notes="Ngày tiêm = hôm nay (A6). Chưa đủ tuổi: cảnh báo trong warnings, không chặn.")
m.op("delete", "/visits/{visitId}/vaccinations/{vaccinationId}", "deleteVaccination", "Xóa mũi tiêm ghi nhầm",
     "Vaccinations", "UC49", "BR-KB-04", "A07", "VET đã ghi mũi đó", status=204,
     path_params=["VisitId", "VaccinationId"], errors=(401, 403, 404, 409),
     err_desc={409: "Visit đã COMPLETED — sai sót ghi bằng bản bổ sung"},
     notes="Hoàn kho đúng lô, xóa dòng Order vaccine trong cùng transaction.")

m.op("get", "/pets/{petId}/health-record", "getPetHealthRecord", "Hồ sơ sức khỏe thú cưng", "Health record",
     "UC25, UC53", "BR-KH-07, BR-KB-01, 04, 06", "A02, A06, A07, A08", "Khách là chủ hiện tại; nhân viên",
     resp="HealthRecord", path_params=["PetId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-TK-19` hồ sơ còn chờ quyết định liên kết", 403: "Không phải chủ hiện tại"},
     notes="Khách không nhận internalNote và bản bổ sung nội bộ. Chỉ mũi tiêm tại hệ thống.")
