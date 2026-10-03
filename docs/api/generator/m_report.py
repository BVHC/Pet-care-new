from lib import Module

m = Module(
    key="report", title="Report API v1", codes="BC (tầng 2)", owner="BE-2 (số liệu lịch hẹn, tiêm chủng do BE-1 cung cấp qua *ReportApi)",
    sources="01 UC89; 02 BR-BC-01…04, BR-BH-05, BR-KO-07, BR-DG-03; 04 *Ngoài phạm vi* (báo cáo không có model); 06-module-contracts §8 Q3",
    intro="Sáu báo cáo cố định của BR-BC-03, tính tại thời điểm xem.",
    frozen=[
        "Không có báo cáo tùy biến, xuất file hay lưu báo cáo: BR-BC-03 là danh mục cố định.",
        "Báo cáo công suất chuồng đã bỏ ở v15 (BR-BC-03).",
    ],
    tags={"Reports": "Báo cáo (UC89)"},
    security=[
        "SUPER_MANAGER xem toàn chuỗi và lọc theo chi nhánh; BRANCH_MANAGER chỉ chi nhánh mình — truyền chi nhánh khác trả 403 (BR-BC-01).",
        "Kỳ báo cáo tối đa 12 tháng [CFG], ngày theo giờ Việt Nam; vượt trả 400 `BR-BC-01`.",
        "Chỉ đọc; module report không có bảng riêng, gọi `*ReportApi` của từng module sở hữu dữ liệu (06 Q3).",
    ],
    assumptions=[
        ("A1", "Mỗi báo cáo là một endpoint riêng, cùng bộ tham số `branchId`, `from`, `to`", "BR-BC-03 liệt kê 6 báo cáo độc lập"),
        ("A2", "Báo cáo tồn kho (5) không theo kỳ, lấy tại thời điểm xem; `withinDays` mặc định 30 [CFG]", "BR-KO-07: lô hết hạn trong 30 ngày [CFG]"),
        ("A3", "Tỷ lệ trả về dạng số thập phân 0–1, kèm tử số và mẫu số", "FE tự định dạng phần trăm"),
    ],
    questions=[],
)

PERIOD = ["branchId=int64|SUPER_MANAGER: bỏ trống = toàn chuỗi · BRANCH_MANAGER: luôn là chi nhánh mình",
          "from!=date", "to!=date|Kỳ ≤ 12 tháng [CFG]"]

m.schema("RevenueRow", {
    "branchId": "int64", "branchName": "string", "source": "enum:VISIT,RETAIL,BOARDING", "orderCount": "int",
    "amount": "money",
})
m.schema("RevenueReport", {"from": "date", "to": "date", "rows": "array:ref:RevenueRow", "totalAmount": "money"},
         desc="BR-BC-02: Order PAID có thời điểm thanh toán trong kỳ, theo đơn giá snapshot")
m.schema("LostRevenueRow", {
    "orderId": "int64", "orderCode": "string", "branchId": "int64", "source": "enum:VISIT,RETAIL,BOARDING",
    "amount": "money", "reason": "string", "cancelledAt": "datetime",
})
m.schema("LostRevenueReport", {"from": "date", "to": "date", "rows": "array:ref:LostRevenueRow", "count": "int",
                               "totalAmount": "money"},
         desc="BR-BC-02: chỉ Order hủy theo BR-BH-05 (UNPAID); hủy phiên trả thú không tính")
m.schema("ServiceCountRow", {
    "branchId": "int64", "serviceGroup": "enum:MEDICAL,GROOMING", "serviceId": "int64", "serviceName": "string",
    "count": "int",
})
m.schema("CompletedServicesReport", {"from": "date", "to": "date", "rows": "array:ref:ServiceCountRow"})
m.schema("NoShowRow", {
    "branchId": "int64", "dueCount": "int|Lịch hẹn có khung giờ trong kỳ", "noShowCount": "int",
    "lateCancelCount": "int", "noShowRate": "decimal", "lateCancelRate": "decimal",
})
m.schema("NoShowReport", {"from": "date", "to": "date", "rows": "array:ref:NoShowRow"})
m.schema("ReturnRateRow", {
    "branchId": "int64|Chi nhánh của mũi trước", "vaccineTypeId": "int64", "vaccineTypeName": "string",
    "dueCount": "int", "returnedCount": "int", "rate": "decimal",
})
m.schema("RevaccinationReport", {"from": "date", "to": "date", "rows": "array:ref:ReturnRateRow"},
         desc="BR-BC-04: đo khách quay lại tiêm đúng hạn tại hệ thống")
m.schema("LowStockRow", {
    "branchId": "int64", "productId": "int64", "productName": "string", "availableQuantity": "int", "minQuantity": "int",
})
m.schema("ExpiringLotRow", {
    "branchId": "int64", "productId": "int64", "productName": "string", "stockLotId": "int64", "lotNumber": "string?",
    "expiryDate": "date", "quantity": "int", "expired": "bool",
})
m.schema("StockAlertReport", {"lowStock": "array:ref:LowStockRow", "expiringLots": "array:ref:ExpiringLotRow"})
m.schema("FeedbackStatRow", {
    "branchId": "int64?|null = feedback không gắn chi nhánh (chỉ SUPER_MANAGER thấy)", "branchName": "string?",
    "count": "int", "averageRating": "decimal?|Chỉ tính feedback có chấm điểm",
})
m.schema("FeedbackReport", {"from": "date", "to": "date", "rows": "array:ref:FeedbackStatRow"})

SCOPE = "SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh mình"
ERR = {400: "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to", 403: "`BR-BC-01` chi nhánh ngoài phạm vi"}
m.op("get", "/reports/revenue", "getRevenueReport", "Doanh thu theo nguồn", "Reports", "UC89", "BR-BC-01, 02, 03",
     "A04, A05", SCOPE, resp="RevenueReport", query=PERIOD, errors=(400, 401, 403), err_desc=ERR)
m.op("get", "/reports/lost-revenue", "getLostRevenueReport", "Thất thu", "Reports", "UC89", "BR-BC-01, 02, BR-BH-05",
     "A04, A05", SCOPE, resp="LostRevenueReport", query=PERIOD, errors=(400, 401, 403), err_desc=ERR)
m.op("get", "/reports/completed-services", "getCompletedServicesReport", "Lượt dịch vụ hoàn tất", "Reports", "UC89",
     "BR-BC-01, 03", "A04, A05", SCOPE, resp="CompletedServicesReport", query=PERIOD, errors=(400, 401, 403),
     err_desc=ERR)
m.op("get", "/reports/no-show", "getNoShowReport", "Tỷ lệ NO_SHOW và hủy muộn", "Reports", "UC89", "BR-BC-01, 03",
     "A04, A05", SCOPE, resp="NoShowReport", query=PERIOD, errors=(400, 401, 403), err_desc=ERR)
m.op("get", "/reports/revaccination-return", "getRevaccinationReport", "Tỷ lệ quay lại tái chủng", "Reports", "UC89",
     "BR-BC-01, 03, 04", "A04, A05", SCOPE, resp="RevaccinationReport", query=PERIOD, errors=(400, 401, 403),
     err_desc=ERR)
m.op("get", "/reports/stock-alerts", "getStockAlertReport", "Tồn dưới ngưỡng và lô sắp / đã hết hạn", "Reports",
     "UC89", "BR-BC-01, 03, BR-KO-07", "A04, A05", SCOPE, resp="StockAlertReport",
     query=["branchId=int64", "withinDays=int|Mặc định 30 [CFG] (A2)"], errors=(400, 401, 403),
     err_desc={403: "`BR-BC-01` chi nhánh ngoài phạm vi"})
m.op("get", "/reports/feedback", "getFeedbackReport", "Feedback theo chi nhánh", "Reports", "UC89",
     "BR-BC-01, 03, BR-DG-03", "A04, A05", SCOPE, resp="FeedbackReport", query=PERIOD, errors=(400, 401, 403),
     err_desc=ERR)
