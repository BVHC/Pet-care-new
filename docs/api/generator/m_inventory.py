from lib import Module

m = Module(
    key="inventory", title="Inventory API v1", codes="KO (tầng 2)", owner="BE-2",
    sources="01 UC73–UC76; 02 BR-KO-01…07, BR-SP-05, BR-QT-15; 03 #9 Phiếu nhập kho; 04 §9; 05 §9 (suppliers, inventory_items, stock_lots, stock_receipts, stock_receipt_lines, stock_adjustments, stock_adjustment_lines, stock_movements)",
    intro="Nhà cung cấp, phiếu nhập kho, xem tồn theo lô / hạn dùng, tồn tối thiểu, điều chỉnh tồn.",
    frozen=[
        "UC77 chuyển kho, UC78 kiểm kê, UC79 truy vết lô là tầng 3.",
        "Trừ kho khi thu tiền và khi tiêm (ST06) chạy bên trong `POST /payments` (sales) và `POST /visits/{visitId}/vaccinations` (visit) qua `StockApi`, không có endpoint riêng.",
        "ST08 cảnh báo tồn là tác vụ hệ thống; số liệu tương ứng xem ở báo cáo `GET /reports/stock-alerts`.",
    ],
    tags={
        "Suppliers": "Nhà cung cấp (UC73)",
        "Stock receipts": "Phiếu nhập kho (UC74)",
        "Inventory": "Tồn kho, lô, tồn tối thiểu (UC75)",
        "Adjustments": "Điều chỉnh tồn (UC76)",
    },
    security=[
        "Tồn kho theo chi nhánh: mọi endpoint (trừ nhà cung cấp, dùng chung toàn chuỗi) làm việc trên chi nhánh của người gọi.",
        "Ghi (nhập kho, điều chỉnh, tồn tối thiểu, nhà cung cấp) chỉ BRANCH_MANAGER; lễ tân, VET, CARETAKER chỉ xem (UC75).",
        "Mọi thay đổi số dư lô ghi `stock_movements` cùng transaction; tồn không bao giờ âm (BR-KO-01, 04 nguyên tắc 5).",
        "Hủy phiếu đã xác nhận và điều chỉnh tồn ghi audit (BR-KO-04, 06, BR-QT-15).",
    ],
    assumptions=[
        ("A1", "Nhà cung cấp do BRANCH_MANAGER bất kỳ quản lý, dùng chung toàn chuỗi", "UC73 actor A05; BR-KO-02 dùng chung toàn chuỗi"),
        ("A2", "Phiếu nháp sửa bằng PUT thay toàn bộ phiếu (kể cả các dòng)", "BR-KO-03 'sửa tự do' khi DRAFT"),
        ("A3", "Lý do hủy phiếu nhập không bắt buộc", "BR-KO-03, 04 không yêu cầu; cột `cancel_reason` cho phép null"),
        ("A4", "Điều chỉnh sản phẩm không quản lý hạn dùng dùng lô mặc định (lấy id từ danh sách lô)", "04 nguyên tắc 4: mọi sản phẩm đều tồn theo lô"),
        ("A5", "Xóa nhà cung cấp chưa có phiếu nhập; đã có thì chỉ ngừng hợp tác", "BR-KO-02"),
    ],
    questions=[],
)

m.param("SupplierId", "supplierId", "path", "int64")
m.param("ReceiptId", "receiptId", "path", "int64")
m.param("ProductId", "productId", "path", "int64")
m.param("AdjustmentId", "adjustmentId", "path", "int64")

m.schema("Supplier", {
    "supplierId": "int64", "name": "string:200", "phone": "phone", "address": "string:300?",
    "isActive": "bool|false = ngừng hợp tác (BR-KO-02)",
})
m.schema("SupplierRequest", {"name": "string:200", "phone": "phone", "address": "string:300?"})
m.schema("UpdateSupplierRequest", {"name": "string:200?", "phone": "phone?", "address": "string:300?", "isActive": "bool?"},
         required=[])
m.schema("ReceiptLineRequest", {
    "productId": "int64", "quantity": "int|> 0", "unitCost": "money|Giá nhập",
    "lotNumber": "string:50?|Bắt buộc nếu sản phẩm quản lý hạn dùng (BR-SP-05)",
    "expiryDate": "date?|Bắt buộc nếu quản lý hạn dùng, sau ngày nhập",
})
m.schema("StockReceiptRequest", {
    "supplierId": "int64|Đang hợp tác", "receiptDate": "date", "note": "string?",
    "lines": "array:ref:ReceiptLineRequest",
})
m.schema("ReceiptLine", {
    "lineId": "int64", "productId": "int64", "productName": "string", "quantity": "int", "unitCost": "money",
    "lotNumber": "string?", "expiryDate": "date?", "stockLotId": "int64?|Gán khi xác nhận",
})
m.schema("StockReceipt", {
    "receiptId": "int64", "code": "string|Dãy số liên tục", "branchId": "int64", "supplierId": "int64",
    "supplierName": "string", "receiptDate": "date", "status": "enum:DRAFT,CONFIRMED,CANCELLED", "note": "string?",
    "lines": "array:ref:ReceiptLine", "createdBy": "int64", "confirmedBy": "int64?", "confirmedAt": "datetime?",
    "cancelledBy": "int64?", "cancelledAt": "datetime?", "cancelReason": "string?",
})
m.schema("StockReceiptSummary", {
    "receiptId": "int64", "code": "string", "supplierName": "string", "receiptDate": "date",
    "status": "enum:DRAFT,CONFIRMED,CANCELLED", "lineCount": "int", "totalCost": "money",
})
m.schema("CancelReceiptRequest", {"reason": "string:300?"}, required=[])
m.schema("InventoryItem", {
    "productId": "int64", "productName": "string", "sku": "string", "unit": "string",
    "productType": "enum:GOODS,DRUG,VACCINE", "tracksExpiry": "bool",
    "totalQuantity": "int|Tổng các lô", "availableQuantity": "int|Tổng lô chưa hết hạn (BR-KO-01)",
    "minQuantity": "int", "belowMin": "bool", "nearestExpiryDate": "date?",
    "expiredQuantity": "int|Tồn ở lô đã hết hạn, cần điều chỉnh lý do hết hạn (BR-KO-07)",
})
m.schema("StockLot", {
    "stockLotId": "int64", "lotNumber": "string?", "expiryDate": "date?", "quantity": "int",
    "isDefault": "bool|Lô duy nhất của sản phẩm không quản lý hạn dùng", "expired": "bool",
})
m.schema("SetMinQuantityRequest", {"minQuantity": "int|≥ 0"})
m.schema("AdjustmentLineRequest", {"stockLotId": "int64", "quantityDelta": "int|≠ 0; âm = giảm, dương = tăng"})
m.schema("StockAdjustmentRequest", {
    "reason": "enum:DAMAGED,EXPIRED,COUNT_MISMATCH,OTHER", "note": "string?|Bắt buộc khi OTHER",
    "lines": "array:ref:AdjustmentLineRequest",
})
m.schema("AdjustmentLine", {
    "stockLotId": "int64", "productId": "int64", "productName": "string", "lotNumber": "string?",
    "quantityDelta": "int", "balanceAfter": "int",
})
m.schema("StockAdjustment", {
    "adjustmentId": "int64", "code": "string", "branchId": "int64", "reason": "enum:DAMAGED,EXPIRED,COUNT_MISMATCH,OTHER",
    "note": "string?", "lines": "array:ref:AdjustmentLine", "createdBy": "int64", "createdAt": "datetime",
})

BM = "BRANCH_MANAGER chi nhánh"
m.op("get", "/suppliers", "listSuppliers", "Danh sách nhà cung cấp", "Suppliers", "UC73, UC74", "BR-KO-02", "A05",
     "BRANCH_MANAGER", resp="array:Supplier", query=["isActive=bool", "q=string"], errors=(401, 403))
m.op("post", "/suppliers", "createSupplier", "Thêm nhà cung cấp", "Suppliers", "UC73", "BR-KO-02", "A05",
     "BRANCH_MANAGER (A1)", body="SupplierRequest", resp="Supplier", status=201, errors=(400, 401, 403))
m.op("patch", "/suppliers/{supplierId}", "updateSupplier", "Sửa / ngừng hợp tác nhà cung cấp", "Suppliers", "UC73",
     "BR-KO-02", "A05", "BRANCH_MANAGER (A1)", body="UpdateSupplierRequest", resp="Supplier",
     path_params=["SupplierId"], errors=(400, 401, 403, 404),
     notes="Ngừng hợp tác thì không chọn được ở phiếu nhập mới.")
m.op("delete", "/suppliers/{supplierId}", "deleteSupplier", "Xóa nhà cung cấp chưa có phiếu nhập", "Suppliers",
     "UC73", "BR-KO-02", "A05", "BRANCH_MANAGER (A1)", status=204, path_params=["SupplierId"],
     errors=(400, 401, 403, 404), err_desc={400: "`BR-KO-02` đã có phiếu nhập — chỉ ngừng hợp tác"})

m.op("get", "/stock-receipts", "listStockReceipts", "Danh sách phiếu nhập", "Stock receipts", "UC74", "BR-KO-03",
     "A05", BM, resp="StockReceiptSummary", page=True,
     query=["status=enum:DRAFT,CONFIRMED,CANCELLED", "from=date", "to=date", "supplierId=int64", "page", "size"],
     errors=(400, 401, 403))
m.op("post", "/stock-receipts", "createStockReceipt", "Tạo phiếu nhập nháp", "Stock receipts", "UC74", "BR-KO-03",
     "A05", BM, "— → DRAFT (Phiếu nhập#1)", body="StockReceiptRequest", resp="StockReceipt", status=201,
     errors=(400, 401, 403, 404), notes="Chưa ảnh hưởng tồn.")
m.op("get", "/stock-receipts/{receiptId}", "getStockReceipt", "Chi tiết phiếu nhập", "Stock receipts", "UC74",
     "BR-KO-03", "A05", BM, resp="StockReceipt", path_params=["ReceiptId"], errors=(401, 403, 404))
m.op("put", "/stock-receipts/{receiptId}", "updateStockReceipt", "Sửa phiếu nháp", "Stock receipts", "UC74",
     "BR-KO-03", "A05", BM, body="StockReceiptRequest", resp="StockReceipt", path_params=["ReceiptId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: "Phiếu không còn DRAFT"}, notes="A2.")
m.op("post", "/stock-receipts/{receiptId}/confirm", "confirmStockReceipt", "Xác nhận phiếu nhập", "Stock receipts",
     "UC74", "BR-KO-02, 03, BR-SP-05", "A05", BM, "DRAFT → CONFIRMED (Phiếu nhập#2)", resp="StockReceipt",
     path_params=["ReceiptId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KO-02` nhà cung cấp ngừng hợp tác · `BR-KO-03` dòng số lượng ≤ 0 / thiếu giá nhập / thiếu số lô, hạn dùng hoặc hạn không sau ngày nhập",
               409: "Phiếu không còn DRAFT"},
     notes="Cộng tồn theo lô; cùng số lô và hạn thì cộng vào lô cũ.")
m.op("post", "/stock-receipts/{receiptId}/cancel", "cancelStockReceipt", "Hủy phiếu nhập", "Stock receipts", "UC74",
     "BR-KO-03, 04, 06, BR-QT-15", "A05", BM, "DRAFT → CANCELLED (Phiếu nhập#3) · CONFIRMED → CANCELLED (Phiếu nhập#4)",
     body="CancelReceiptRequest", resp="StockReceipt", path_params=["ReceiptId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KO-04` tồn hiện tại của lô không đủ để trừ lại — dùng phiếu điều chỉnh (BR-KO-06)",
               409: "Phiếu đã CANCELLED"},
     notes="Phiếu CONFIRMED: trừ lại tồn, ghi audit. Không xóa cứng phiếu.")

m.op("get", "/inventory", "listInventory", "Xem tồn kho chi nhánh", "Inventory", "UC75", "BR-KO-01, 07",
     "A05, A06, A07, A08", "Nhân viên chi nhánh (chỉ BRANCH_MANAGER sửa)", resp="InventoryItem", page=True,
     query=["q=string|Tên hoặc SKU", "productType=enum:GOODS,DRUG,VACCINE", "belowMin=bool",
            "expiringWithinDays=int|Có lô sắp hết hạn trong N ngày", "page", "size"], errors=(400, 401, 403))
m.op("get", "/inventory/{productId}", "getInventoryItem", "Tồn của một sản phẩm", "Inventory", "UC75, UC66, UC48",
     "BR-KO-01", "A05, A06, A07, A08", "Nhân viên chi nhánh", resp="InventoryItem", path_params=["ProductId"],
     errors=(401, 403, 404), notes="Dùng ở POS / kê đơn / tiêm để xem tồn khả dụng.")
m.op("get", "/inventory/{productId}/lots", "listStockLots", "Tồn theo lô, hạn dùng", "Inventory", "UC75, UC76",
     "BR-KO-01, 05", "A05, A06", "Nhân viên chi nhánh", resp="array:StockLot", path_params=["ProductId"],
     errors=(401, 403, 404), notes="Sắp theo hạn dùng tăng dần (thứ tự FEFO).")
m.op("put", "/inventory/{productId}/min-quantity", "setMinQuantity", "Đặt tồn tối thiểu", "Inventory", "UC75",
     "BR-KO-07", "A05", BM, body="SetMinQuantityRequest", resp="InventoryItem", path_params=["ProductId"],
     errors=(400, 401, 403, 404))

m.op("post", "/stock-adjustments", "createStockAdjustment", "Lập phiếu điều chỉnh tồn", "Adjustments", "UC76",
     "BR-KO-01, 06, BR-QT-15", "A05", BM, body="StockAdjustmentRequest", resp="StockAdjustment", status=201,
     errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KO-06` thiếu lý do / ghi chú khi OTHER · giảm quá tồn của lô · `BR-KO-01` tồn âm"},
     notes="Hiệu lực ngay, ghi movement ADJUSTMENT và audit. A4.")
m.op("get", "/stock-adjustments", "listStockAdjustments", "Danh sách phiếu điều chỉnh", "Adjustments", "UC76",
     "BR-KO-06", "A05", BM, resp="StockAdjustment", page=True, query=["from=date", "to=date", "page", "size"],
     errors=(400, 401, 403))
m.op("get", "/stock-adjustments/{adjustmentId}", "getStockAdjustment", "Chi tiết phiếu điều chỉnh", "Adjustments",
     "UC76", "BR-KO-06", "A05", BM, resp="StockAdjustment", path_params=["AdjustmentId"], errors=(401, 403, 404))
