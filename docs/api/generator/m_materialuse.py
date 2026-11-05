from lib import Module

m = Module(
    key="materialuse", title="Material Usage API v1 (tầng 3)", codes="KB (trừ vật tư tiêu hao, ST07)", owner="BE-1",
    sources="01 ST07; 09-tang3-be1-spec.md §E (BR-KB-12…14); 05 bổ sung visit_material_usages",
    intro="Xem vật tư tiêu hao đã trừ theo định mức khi hoàn tất lượt và xử lý thiếu hụt. ST07 là tác vụ hệ thống, không có endpoint kích hoạt. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "ST07 chạy trong cùng transaction với hoàn tất lượt khám / thẩm mỹ (Visit#5), không có endpoint kích hoạt (BR-KB-12).",
        "Định mức vật tư cấu hình ở fees-v1 (`/services/{serviceId}/material-norms`); điều chỉnh tồn xử lý thiếu hụt dùng inventory-v1 (UC76).",
        "Vật tư tiêu hao không thành dòng Order (BR-KB-12).",
    ],
    tags={"Material usage": "Vật tư tiêu hao theo lượt và thiếu hụt (ST07)"},
    security=[
        "BRANCH_MANAGER chỉ xem và xử lý thiếu hụt của chi nhánh mình; VET chỉ xem vật tư của lượt mình phụ trách (BR-QT-03).",
        "Thiếu tồn không chặn hoàn tất lượt (BR-KB-13); xuất kho FEFO qua `StockApi` trong cùng transaction hoàn tất.",
    ],
    assumptions=[
        ("A1", "Thiếu tồn vật tư không chặn hoàn tất lượt; ghi thiếu hụt và báo quản lý", "Không để việc kho cản việc khám (BR-KB-13)"),
        ("A2", "Mỗi Visit trừ vật tư đúng một lần", "BR-KB-14; bệnh án khóa nên không hoàn tác"),
    ],
    questions=[
        ("Q1", "Báo thiếu vật tư vào ST08 hiện có hay thông báo riêng `MATERIAL_SHORTAGE`", "TBD (BE-1, BE-2)", "BR-KB-13"),
        ("Q2", "Thiếu hụt chưa xử lý quá bao lâu thì nhắc lại", "TBD (PO)", "BR-KB-13"),
    ],
)

m.param("VisitId", "visitId", "path", "int64")
m.param("UsageId", "usageId", "path", "int64")

m.schema("MaterialUsage", {
    "usageId": "int64", "visitId": "int64", "serviceId": "int64", "serviceName": "string", "productId": "int64",
    "productName": "string", "unit": "string", "normQty": "int|Theo định mức (BR-SP-10)", "issuedQty": "int|Đã xuất kho",
    "shortageQty": "int|Thiếu tồn lúc hoàn tất; 0 = đủ", "resolvedAt": "datetime?", "resolvedNote": "string?",
})
m.schema("ResolveShortageRequest", {"note": "string:300|Đã làm gì (điều chỉnh tồn, nhập thêm…)"})

m.op("get", "/visits/{visitId}/material-usages", "listVisitMaterialUsages", "Vật tư tiêu hao của một lượt", "Material usage",
     "ST07", "BR-KB-12, 14", "A05, A07", "A05 của chi nhánh; A07 phụ trách lượt", resp="array:MaterialUsage",
     path_params=["VisitId"], errors=(400, 401, 403, 404), notes="Không phân trang. Rỗng khi lượt chưa hoàn tất hoặc dịch vụ không có định mức.")
m.op("get", "/material-shortages", "listMaterialShortages", "Thiếu hụt vật tư chưa xử lý", "Material usage", "ST07",
     "BR-KB-13", "A05", "BRANCH_MANAGER của chi nhánh", resp="MaterialUsage", page=True,
     query=["resolved=bool|Vắng = false (chưa xử lý)", "page", "size"], errors=(400, 401, 403),
     notes="Chỉ dòng `shortageQty > 0`. Cũ nhất trước.")
m.op("post", "/material-shortages/{usageId}/resolve", "resolveMaterialShortage", "Đánh dấu đã xử lý thiếu hụt",
     "Material usage", "ST07", "BR-KB-13", "A05", "BRANCH_MANAGER của chi nhánh", body="ResolveShortageRequest",
     resp="MaterialUsage", path_params=["UsageId"], errors=(400, 401, 403, 404, 409), idem="Có",
     err_desc={400: "Dòng không có thiếu hụt", 409: "Đã được xử lý"},
     notes="Chỉ ghi nhận; không tự điều chỉnh tồn (dùng inventory-v1).")
