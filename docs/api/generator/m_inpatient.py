from lib import Module

m = Module(
    key="inpatient", title="Inpatient API v1 (tầng 3)", codes="LT (nội trú)", owner="BE-1",
    sources="01 UC55, UC56; 09-tang3-be1-spec.md §C (BR-LT-13…17; 03 #24 Nhập viện, #25 Y lệnh nội trú); 05 bổ sung admissions, admission_orders, admission_observations; ADR-0124",
    intro="Nhập / xuất viện (gồm chuyển thú đang lưu trú sang điều trị), y lệnh và theo dõi nội trú. **Đặc tả bổ sung GĐ2, duyệt ở T54 (Hiến, 5/11/2026), sửa ở ADR-0124 (9/11/2026), ADR-0148; #1–#10 đã cài (T69 N3–N6, ADR-0149).** Package thuộc BE-1 (T54), không phải `boarding` của BE-2.",
    frozen=[
        "Dịch vụ nội trú vào Order do hệ thống thêm **một dòng** khi xuất viện (`VisitOrderApi.addServiceLine` với đơn giá đã chốt, UC67), nhập viện không thêm dòng; không có endpoint thêm dòng ở đây.",
        "Kê đơn cho y lệnh `MEDICATION` dùng `POST /visits/{visitId}/prescriptions` của visit-v1 (#16); y lệnh chỉ liên kết dòng đơn.",
        "Khách chưa có endpoint xem nội trú (09 Q4).",
        "Kết cục `DECEASED`: xuất viện không tự đánh dấu mất hay kết thúc lưu trú; lễ tân làm sau khi Visit hoàn tất (kết thúc lưu trú `PET_DECEASED`, rồi `POST /pets/{petId}/mark-deceased` của customer-v1, BR-LT-14).",
    ],
    tags={
        "Admissions": "Nhập / xuất viện (UC55)",
        "Admission orders": "Y lệnh nội trú (UC56)",
        "Observations": "Theo dõi nội trú (UC56)",
    },
    security=[
        "Chỉ nhân viên của chi nhánh của ca nội trú (BR-QT-03): VET được gán Visit nhập / xuất viện, ra và hủy y lệnh (gán lại Visit chuyển quyền, BR-TN-08); A08 chỉ làm việc không phải thuốc và ghi theo dõi; A06 chỉ xem để lập phiếu thu.",
        "Y lệnh thuốc chỉ VET thực hiện (BR-LT-15); theo dõi chỉ thêm, không sửa, không xóa (BR-LT-16).",
        "Nhập viện khóa thú `FOR SHARE` (`PetQueryApi.findPetForShare`, ADR-0041), rồi khóa lượt khám (từ khám) hoặc đặt chỗ `FOR SHARE` (từ lưu trú), rồi mới tạo Visit điều trị / chiếm chuồng và ca trong một transaction (ADR-0149).",
        "Chi nhánh khác trả 403 / 404; mỗi thú tối đa một ca `ADMITTED` (BR-LT-13).",
    ],
    assumptions=[
        ("A1", "Thêm loại `INPATIENT` vào thuộc tính loại của dịch vụ nhóm Khám / Tiêm, giá theo ngày", "BR-LT-13; BE-2 xác nhận"),
        ("A2", "Nội trú dùng chung chuồng với lưu trú: nhập từ lưu trú giữ chuồng cũ, nhập từ khám lấy chuồng `AVAILABLE`", "UC55 không nêu chuồng riêng; ADR-0124"),
        ("A3", "Phí lưu trú theo đêm và phí nội trú theo ngày tính riêng (thú bị tính cả hai cho những ngày điều trị)", "BR-LT-17; ADR-0124"),
        ("A5", "Dịch vụ nội trú khi nhập viện phải đang kinh doanh và đang bật tại chi nhánh của ca (cùng nghĩa bật / tắt dịch vụ của walk-in); lượt khám phải thuộc nhóm Khám/Tiêm", "BR-LT-13 chỉ nêu loại `INPATIENT`; BR-LH-01 (ghi chú ADR-0088); ADR-0149"),
        ("A6", "`serviceName`, `petName`, `kennelCode` là tên / mã hiện tại; chỉ `unitPrice` là giá đã chốt", "BR-BH-03; ADR-0149"),
        ("A7", "Y lệnh thuốc (`MEDICATION`) chỉ liên kết dòng đơn thuốc **của phòng khám** (có dòng Order): dòng mua ngoài, dòng của lượt khác hoặc không tồn tại bị 400; loại y lệnh khác `MEDICATION` mà gửi `prescriptionItemId` cũng 400. `scheduledAt` không giới hạn (cho ghi bù y lệnh đã làm)", "BR-LT-15; Mạnh chốt 2026-11-24; ADR-0149 mục 27"),
        ("A4", "Chưa có khoảng lâm sàng cho nhiệt độ, nhịp tim, nhịp thở: 400 \"giá trị ngoài khoảng\" của #9 hiện chỉ là ngoài giới hạn lưu trữ của cột (nhiệt độ `NUMERIC(4,1)`, nhịp 1…32 767) và cân nặng ≤ 0 (BR-KH-04); PO chốt khoảng thì mới chặn theo lâm sàng", "BR-LT-16; ADR-0149 mục 8"),
    ],
    questions=[
        ("Q1", "BE-2 xác nhận `INPATIENT` trong `services.medical_type`, dùng chung chuồng, `VisitOrderApi.addServiceLine(visitId, serviceId, quantity, unitPrice, actorId)` và guard kết thúc lưu trú", "Đã xác nhận bằng lời qua Mạnh 2026-11-09, chưa có văn bản của BE-2", "09 §C; ADR-0124"),
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
    "visitId": "int64?|Nhập viện từ lượt khám `IN_PROGRESS` của mình", "boardingBookingId": "int64?|Hoặc chuyển từ đặt chỗ `CHECKED_IN` hoặc `OVERDUE` (BR-LT-13). Đúng một trong hai",
    "serviceId": "int64|Dịch vụ loại `INPATIENT`", "kennelId": "int64?|Chuồng `AVAILABLE`. Bắt buộc với `visitId`; **cấm** với `boardingBookingId` (thú ở nguyên chuồng của đặt chỗ, BR-LT-13)",
})
m.schema("Admission", {
    "admissionId": "int64", "code": "string", "visitId": "int64", "petId": "int64", "petName": "string", "branchId": "int64",
    "boardingBookingId": "int64?", "kennelId": "int64?", "serviceId": "int64", "serviceName": "string",
    "unitPrice": "money|Giá theo ngày, chốt lúc nhập viện", "status": "ref:AdmissionStatus", "admittedAt": "datetime",
    "billedDays": "int|Số ngày tính tiền = ngày xuất (hoặc hôm nay nếu `ADMITTED`) − ngày nhập + 1, giờ Việt Nam; tính khi đọc, không lưu (BR-LT-14)",
    "estimatedAmount": "money|`billedDays` × `unitPrice`; tiền tạm tính, dòng Order chỉ có sau xuất viện",
    "dischargeOutcome": "ref:DischargeOutcome?", "dischargeSummary": "string?", "dischargedAt": "datetime?",
    "orderLineId": "int64?|Có sau khi xuất viện",
})
m.schema("AdmissionSummary", {
    "admissionId": "int64", "code": "string", "petName": "string", "status": "ref:AdmissionStatus",
    "admittedAt": "datetime", "kennelCode": "string?", "billedDays": "int", "estimatedAmount": "money",
})
m.schema("DischargeRequest", {"outcome": "ref:DischargeOutcome", "summary": "string:2000|Tóm tắt điều trị (BR-LT-14), tối đa 2000 ký tự; rỗng sau khi bỏ khoảng trắng đầu / cuối là `BR-LT-14`"})
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
m.schema("AdmissionOrderReasonRequest", {"reason": "string:300|Lý do hủy y lệnh (BR-LT-15); rỗng sau khi bỏ khoảng trắng đầu / cuối là `BR-LT-15`. Tên riêng vì `ReasonRequest` đã có ở identity và shop"})
m.schema("Observation", {
    "observationId": "int64", "admissionId": "int64", "recordedByName": "string", "temperatureC": "decimal?",
    "heartRate": "int?", "respiratoryRate": "int?", "weightKg": "decimal?", "appetite": "ref:Appetite?",
    "note": "string?", "abnormal": "bool", "createdAt": "datetime",
})
m.schema("CreateObservationRequest", {
    "temperatureC": "decimal?", "heartRate": "int?", "respiratoryRate": "int?", "weightKg": "decimal?",
    "appetite": "ref:Appetite?", "note": "string:500?", "abnormal": "bool|true báo VET phụ trách và khách (BR-LT-16)",
})

VET = "VET của chi nhánh"
ALL = "A06 chỉ xem; A07; A08 theo BR-LT-15"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25)"

m.op("post", "/admissions", "admitPet", "Nhập viện", "Admissions", "UC55", "BR-LT-13", "A07", VET,
     "— → ADMITTED (Nhập viện#1)", body="AdmitRequest", resp="Admission", status=201, errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-13` không đúng một trong `visitId` / `boardingBookingId` · thiếu `kennelId` khi nhập từ khám / có `kennelId` khi nhập từ lưu trú · Visit không `IN_PROGRESS` / không của mình / không thuộc nhóm Khám/Tiêm · đặt chỗ không `CHECKED_IN` / `OVERDUE` · thú đang lưu trú mà nhập từ khám · dịch vụ không `INPATIENT` / ngừng kinh doanh / chưa bật ở chi nhánh (A5) · chuồng không `AVAILABLE` / khác chi nhánh · thú đã mất; `VALIDATION_FAILED` thiếu `serviceId` hoặc id ≤ 0; `MALFORMED_REQUEST` sai kiểu; BR-TK-17",
               403: "Không phải VET · Visit / đặt chỗ của chi nhánh khác",
               404: "Không tìm thấy Visit / đặt chỗ / dịch vụ / chuồng",
               409: "Thú đã có ca nội trú `ADMITTED` (`INVALID_STATE_TRANSITION`; hai lần nhập viện đồng thời: bên thua `CONCURRENCY_CONFLICT`)"},
     notes="Từ lưu trú: tạo Visit điều trị gán VET gọi (Order không có dòng tự sinh), đặt chỗ giữ nguyên trạng thái (`CHECKED_IN` hoặc `OVERDUE`), thú ở nguyên chuồng (BR-LT-13, BR-LT-17). Visit điều trị là Visit#7, Order#14; chuồng điều trị là Chuồng#6, #7 (03). Thứ tự lỗi: 400 hình thức → 403 vai trò → 400 `BR-LT-13` nguồn → 404 Visit / đặt chỗ → 403 chi nhánh → 409 → 400 `BR-LT-13` điều kiện → 404 / 400 dịch vụ → 404 / 400 chuồng (ADR-0149).")
m.op("get", "/admissions", "listAdmissions", "Ca nội trú của chi nhánh", "Admissions", "UC55", "BR-LT-17", "A06, A07, A08",
     ALL, resp="AdmissionSummary", page=True, query=["status=enum:ADMITTED,DISCHARGED", "page", "size"],
     errors=(400, 401, 403), err_desc={403: "Sai vai trò"},
     notes="`status` vắng = `ADMITTED`. Chỉ ca của chi nhánh người gọi. Nhập viện sớm nhất trước (trùng thì `admissionId` tăng dần). `kennelCode`: chuồng điều trị (nhập từ khám) hoặc chuồng của đặt chỗ (nhập từ lưu trú).")
m.op("get", "/admissions/{admissionId}", "getAdmission", "Chi tiết ca nội trú", "Admissions", "UC55", "BR-LT-17",
     "A06, A07, A08", ALL, resp="Admission", path_params=["AdmissionId"], errors=(400, 401, 403, 404),
     err_desc={403: "Sai vai trò · ca của chi nhánh khác", 404: "Không tìm thấy ca nội trú"})
m.op("post", "/admissions/{admissionId}/discharge", "dischargeAdmission", "Xuất viện", "Admissions", "UC55",
     "BR-LT-14", "A07", VET, "ADMITTED → DISCHARGED (Nhập viện#2)", body="DischargeRequest", resp="Admission",
     path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-14` còn y lệnh `ORDERED` · thiếu tóm tắt (rỗng sau `strip()`); `VALIDATION_FAILED` thiếu kết cục / tóm tắt hoặc tóm tắt quá 2000 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17", 403: "Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit", 404: "Không tìm thấy ca nội trú", 409: "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED` (03 #24); hai xuất viện đồng thời: bên sau 409"},
     notes="Thêm **một** dòng dịch vụ nội trú (số lượng = ngày xuất − ngày nhập + 1 theo ngày lịch giờ Việt Nam, đơn giá = `unitPrice` đã chốt, không phải giá danh mục hiện hành) vào Order của Visit trong cùng transaction; giải phóng chuồng nếu được cấp riêng. `DECEASED` không tự đánh dấu thú mất (BR-LT-14). Dòng này sau đó không sửa / xóa tay được (BR-LT-18). Thứ tự lỗi: 400 hình thức → 403 vai trò → 400 `BR-LT-14` tóm tắt rỗng → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-14` còn y lệnh.")

m.op("post", "/admissions/{admissionId}/orders", "createAdmissionOrder", "Ra y lệnh", "Admission orders", "UC56",
     "BR-LT-15", "A07", VET, "— → ORDERED (Y lệnh nội trú#1)", body="CreateAdmissionOrderRequest", resp="AdmissionOrder",
     status=201, path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-15` `MEDICATION` thiếu dòng đơn thuốc · dòng đơn không thuộc Visit của ca · dòng đơn mua ngoài · loại khác `MEDICATION` mà có dòng đơn · nội dung rỗng sau `strip()`; `VALIDATION_FAILED` thiếu `orderType` / `content` / `scheduledAt`, `content` quá 500 ký tự hoặc `prescriptionItemId` ≤ 0; `MALFORMED_REQUEST` sai kiểu; BR-TK-17",
               403: "Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit",
               404: "Không tìm thấy ca nội trú",
               409: "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED`"},
     notes="Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-15`.")
m.op("get", "/admissions/{admissionId}/orders", "listAdmissionOrders", "Y lệnh của ca nội trú", "Admission orders", "UC56",
     "BR-LT-15", "A06, A07, A08", ALL, resp="array:AdmissionOrder", path_params=["AdmissionId"],
     query=["status=enum:ORDERED,DONE,CANCELLED"], errors=(400, 401, 403, 404),
     err_desc={403: "Sai vai trò · ca của chi nhánh khác", 404: "Không tìm thấy ca nội trú"},
     notes="Không phân trang. Sắp `scheduledAt` tăng dần, trùng thì `orderId` tăng dần.")
m.op("post", "/admission-orders/{admissionOrderId}/complete", "completeAdmissionOrder", "Đánh dấu đã thực hiện y lệnh",
     "Admission orders", "UC56", "BR-LT-15", "A07, A08", "A07; A08 chỉ việc không phải thuốc",
     "ORDERED → DONE (Y lệnh nội trú#2)", body="CompleteAdmissionOrderRequest", resp="AdmissionOrder",
     path_params=["AdmissionOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-15` ghi chú rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17",
               403: "Sai vai trò · y lệnh của chi nhánh khác · A08 không thực hiện y lệnh thuốc",
               404: "Không tìm thấy y lệnh", 409: TRANS},
     notes="Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 A08 với y lệnh thuốc → 409 → 400 `BR-LT-15`.")
m.op("post", "/admission-orders/{admissionOrderId}/cancel", "cancelAdmissionOrder", "Hủy y lệnh", "Admission orders", "UC56",
     "BR-LT-15", "A07", VET, "ORDERED → CANCELLED (Y lệnh nội trú#3)", body="AdmissionOrderReasonRequest", resp="AdmissionOrder",
     path_params=["AdmissionOrderId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-15` lý do rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17",
               403: "Không phải VET · y lệnh của chi nhánh khác · không phải VET phụ trách Visit",
               404: "Không tìm thấy y lệnh", 409: TRANS},
     notes="Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 `BR-LT-15`.")

m.op("post", "/admissions/{admissionId}/observations", "createObservation", "Ghi chỉ số theo dõi", "Observations", "UC56",
     "BR-LT-16", "A07, A08", "A07, A08 của chi nhánh", body="CreateObservationRequest", resp="Observation", status=201,
     path_params=["AdmissionId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-LT-16` không có chỉ số hay ghi chú nào (rỗng sau `strip()`) · giá trị ngoài giới hạn lưu trữ (nhiệt độ, nhịp tim / nhịp thở 1…32767, cân nặng 0.01…9999.99 kg); `VALIDATION_FAILED` thiếu `abnormal` hoặc ghi chú quá 500 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17",
               403: "Không phải VET / CARETAKER · ca của chi nhánh khác",
               404: "Không tìm thấy ca nội trú",
               409: "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED`"},
     notes="Chỉ thêm, không sửa / xóa. Mọi VET / CARETAKER của chi nhánh ghi được, không cần là người phụ trách Visit. Có `weightKg` thì ghi thêm vào hồ sơ cân của thú (BR-KH-04) với nguồn `VET` gắn Visit của ca. `abnormal = true` báo khách (kênh theo BR-TB-02) và VET phụ trách (IN_APP; không gửi cho chính VET đó nếu họ là người ghi) bằng cặp mẫu `INPATIENT_OBSERVATION_ABNORMAL` / `_APP` (BR-LT-16, ADR-0148). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 409 → 400 `BR-LT-16` (ADR-0149).")
m.op("get", "/admissions/{admissionId}/observations", "listObservations", "Chỉ số theo dõi của ca nội trú", "Observations",
     "UC56", "BR-LT-16", "A06, A07, A08", ALL, resp="Observation", page=True, path_params=["AdmissionId"],
     query=["page", "size"], errors=(400, 401, 403, 404),
     err_desc={403: "Sai vai trò · ca của chi nhánh khác", 404: "Không tìm thấy ca nội trú"},
     notes="Mới nhất trước (trùng thì `observationId` giảm dần). Đọc được cả ca đã xuất viện.")
