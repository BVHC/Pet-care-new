from lib import Module

m = Module(
    key="stockops", title="Stock Operations API v1 (tầng 3)", codes="KO (chuyển kho, kiểm kê, truy vết lô)", owner="BE-2",
    sources="01 UC77, UC78, UC79; 08-tang3-be2-spec.md §E (BR-KO-08…13; 03 #18 Phiếu chuyển kho, #19 Phiên kiểm kê); 05 bổ sung stock_transfers, stock_transfer_lines, stock_counts, stock_count_lines",
    intro="Chuyển kho giữa chi nhánh, kiểm kê kho của chi nhánh, truy vết lô hàng. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Điều chỉnh tồn (UC76) tạo từ kiểm kê dùng chung module inventory-v1; không có endpoint điều chỉnh mới ở đây.",
        "Không nhận lệch khi nhận hàng: lệch xử lý bằng điều chỉnh tồn UC76 (08 §E A1).",
    ],
    tags={
        "Stock transfers": "Chuyển kho (UC77)",
        "Stock counts": "Kiểm kê kho (UC78)",
        "Lot trace": "Truy vết lô hàng (UC79)",
    },
    security=[
        "Chuyển kho: A05 thấy phiếu mà chi nhánh mình là nguồn hoặc đích; chỉ nguồn tạo, chỉ đích nhận, cả hai hủy được (BR-KO-11).",
        "Kiểm kê: A05 chỉ kho chi nhánh mình, mỗi chi nhánh tối đa một phiên `OPEN` (BR-KO-12).",
        "Truy vết: A05 chỉ lô của chi nhánh mình, A07 xem lô thuốc / vaccine; thông tin thú / khách chỉ hiện với lô thuốc, vaccine; mọi lượt xem ghi audit (BR-KO-13).",
        "Trừ kho FEFO và hoàn kho chạy trong cùng transaction với chuyển trạng thái; khóa lô trước khi đọc (ADR-0042, 0106).",
    ],
    assumptions=[
        ("A1", "Nhận hàng chuyển kho phải đủ, không nhận lệch", "Lệch xử lý bằng điều chỉnh UC76"),
        ("A2", "Kiểm kê không khóa nhập / xuất; lệch tính theo tồn hệ thống lúc xác nhận", "YAGNI; BR-KO-12"),
        ("A3", "Truy vết chỉ lộ thú / khách cho lô thuốc, vaccine", "Phục vụ truy hồi lô lỗi, giảm lộ dữ liệu"),
    ],
    questions=[
        ("Q1", "Phiếu `IN_TRANSIT` quá bao nhiêu ngày thì cảnh báo", "TBD (BE-2)", "08 §E"),
        ("Q2", "VET xem tên khách trong truy vết hay chỉ mã thú", "TBD (PO)", "BR-KO-13"),
    ],
)

m.param("TransferId", "transferId", "path", "int64")
m.param("CountId", "countId", "path", "int64")
m.param("LotId", "lotId", "path", "int64")

m.schema("StockTransferStatus", None, enum=["IN_TRANSIT", "RECEIVED", "CANCELLED"], desc="03 #18")
m.schema("StockTransferLineInput", {"productId": "int64", "quantity": "int|≤ tồn khả dụng của nguồn (BR-KO-08)"})
m.schema("CreateStockTransferRequest", {"destBranchId": "int64", "lines": "array:ref:StockTransferLineInput|≥ 1 dòng"})
m.schema("StockTransferLine", {
    "productId": "int64", "productName": "string", "quantity": "int", "lotNumber": "string?", "expiryDate": "date?",
})
m.schema("StockTransferSummary", {
    "transferId": "int64", "code": "string", "sourceBranchId": "int64", "destBranchId": "int64",
    "status": "ref:StockTransferStatus", "lineCount": "int", "createdAt": "datetime",
})
m.schema("StockTransfer", {
    "transferId": "int64", "code": "string", "sourceBranchId": "int64", "sourceBranchName": "string",
    "destBranchId": "int64", "destBranchName": "string", "status": "ref:StockTransferStatus",
    "lines": "array:ref:StockTransferLine", "createdAt": "datetime", "receivedAt": "datetime?",
    "cancelledAt": "datetime?", "cancelReason": "string?",
})
m.schema("ReasonRequest", {"reason": "string:300"})
m.schema("StockCountStatus", None, enum=["OPEN", "CONFIRMED", "CANCELLED"], desc="03 #19")
m.schema("StockCountScope", None, enum=["ALL", "CATEGORY"])
m.schema("OpenStockCountRequest", {"scope": "ref:StockCountScope", "categoryId": "int64?|Bắt buộc khi scope = CATEGORY"})
m.schema("StockCountLine", {
    "stockLotId": "int64", "productId": "int64", "productName": "string", "lotNumber": "string?", "expiryDate": "date?",
    "countedQty": "int?|Chưa nhập = chưa đếm", "systemQty": "int|Tồn hệ thống hiện tại",
})
m.schema("StockCountSummary", {
    "countId": "int64", "code": "string", "status": "ref:StockCountStatus", "scope": "ref:StockCountScope",
    "createdAt": "datetime", "confirmedAt": "datetime?",
})
m.schema("StockCount", {
    "countId": "int64", "code": "string", "branchId": "int64", "status": "ref:StockCountStatus",
    "scope": "ref:StockCountScope", "categoryId": "int64?", "lines": "array:ref:StockCountLine",
    "createdAt": "datetime", "confirmedAt": "datetime?", "cancelReason": "string?",
})
m.schema("CountedQtyInput", {"stockLotId": "int64", "countedQty": "int|≥ 0"})
m.schema("SaveStockCountLinesRequest", {"lines": "array:ref:CountedQtyInput"})
m.schema("LotSummary", {
    "lotId": "int64", "productId": "int64", "productName": "string", "lotNumber": "string?", "expiryDate": "date?",
    "branchId": "int64", "quantity": "int",
})
m.schema("LotTraceEvent", {
    "occurredAt": "datetime", "kind": "enum:RECEIPT,TRANSFER_IN,TRANSFER_OUT,TRANSFER_CANCEL,ORDER_ISSUE,VACCINATION,ADJUSTMENT",
    "quantity": "int|Dương = nhập, âm = xuất", "documentCode": "string?", "branchId": "int64",
    "petName": "string?|Chỉ với lô thuốc, vaccine (BR-KO-13)", "customerName": "string?|Như trên",
})
m.schema("LotTrace", {"lot": "ref:LotSummary", "events": "array:ref:LotTraceEvent|Cũ nhất trước", "remaining": "int"})

BM = "BRANCH_MANAGER của chi nhánh nguồn / đích"
CNT = "BRANCH_MANAGER của chi nhánh"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #18, #19)"

# ---- UC77 chuyển kho
m.op("post", "/stock-transfers", "createStockTransfer", "Tạo phiếu chuyển kho", "Stock transfers", "UC77", "BR-KO-08",
     "A05", "BRANCH_MANAGER của chi nhánh nguồn", "— → IN_TRANSIT (Phiếu chuyển kho#1)", body="CreateStockTransferRequest",
     resp="StockTransfer", status=201, errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KO-08` đích trùng nguồn / không `ACTIVE` · dòng vượt tồn khả dụng · trùng sản phẩm",
               404: "Không tìm thấy chi nhánh đích hoặc sản phẩm"},
     notes="Trừ FEFO lô nguồn và ghi lô vào dòng ngay khi tạo.")
m.op("get", "/stock-transfers", "listStockTransfers", "Danh sách phiếu chuyển kho", "Stock transfers", "UC77", "BR-KO-11",
     "A05", BM, resp="StockTransferSummary", page=True,
     query=["direction=enum:OUTGOING,INCOMING", "status=enum:IN_TRANSIT,RECEIVED,CANCELLED", "page", "size"],
     errors=(400, 401, 403), notes="Mới nhất trước. `direction` vắng = cả hai chiều.")
m.op("get", "/stock-transfers/{transferId}", "getStockTransfer", "Chi tiết phiếu chuyển kho", "Stock transfers", "UC77",
     "BR-KO-11", "A05", BM, resp="StockTransfer", path_params=["TransferId"], errors=(400, 401, 403, 404))
m.op("post", "/stock-transfers/{transferId}/receive", "receiveStockTransfer", "Xác nhận nhận hàng", "Stock transfers",
     "UC77", "BR-KO-09", "A05", "BRANCH_MANAGER của chi nhánh đích", "IN_TRANSIT → RECEIVED (Phiếu chuyển kho#2)",
     resp="StockTransfer", path_params=["TransferId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})
m.op("post", "/stock-transfers/{transferId}/cancel", "cancelStockTransfer", "Hủy phiếu chuyển kho", "Stock transfers",
     "UC77", "BR-KO-10", "A05", BM, "IN_TRANSIT → CANCELLED (Phiếu chuyển kho#3)", body="ReasonRequest",
     resp="StockTransfer", path_params=["TransferId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KO-10` thiếu lý do", 409: TRANS}, notes="Hoàn đúng lô nguồn.")

# ---- UC78 kiểm kê
m.op("post", "/stock-counts", "openStockCount", "Mở phiên kiểm kê", "Stock counts", "UC78", "BR-KO-12", "A05", CNT,
     "— → OPEN (Phiên kiểm kê#1)", body="OpenStockCountRequest", resp="StockCount", status=201, errors=(400, 401, 403, 409),
     err_desc={400: "`BR-KO-12` thiếu `categoryId` khi scope = CATEGORY", 409: "Chi nhánh đã có phiên `OPEN`"})
m.op("get", "/stock-counts", "listStockCounts", "Danh sách phiên kiểm kê", "Stock counts", "UC78", "BR-KO-12", "A05",
     CNT, resp="StockCountSummary", page=True, query=["status=enum:OPEN,CONFIRMED,CANCELLED", "page", "size"],
     errors=(400, 401, 403), notes="Mới nhất trước.")
m.op("get", "/stock-counts/{countId}", "getStockCount", "Chi tiết phiên kiểm kê", "Stock counts", "UC78", "BR-KO-12",
     "A05", CNT, resp="StockCount", path_params=["CountId"], errors=(400, 401, 403, 404))
m.op("put", "/stock-counts/{countId}/lines", "saveStockCountLines", "Lưu số đếm theo lô", "Stock counts", "UC78",
     "BR-KO-12", "A05", CNT, body="SaveStockCountLinesRequest", resp="StockCount", path_params=["CountId"],
     errors=(400, 401, 403, 404, 409), idem="Có", err_desc={400: "Lô không thuộc phạm vi phiên · số đếm âm", 409: TRANS},
     notes="Ghi đè số đếm của các lô gửi lên; lô không gửi giữ nguyên.")
m.op("post", "/stock-counts/{countId}/confirm", "confirmStockCount", "Xác nhận kiểm kê", "Stock counts", "UC78",
     "BR-KO-12", "A05", CNT, "OPEN → CONFIRMED (Phiên kiểm kê#2)", resp="StockCount", path_params=["CountId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-KO-12` chưa nhập số đếm cho lô nào", 409: TRANS},
     notes="Tạo điều chỉnh `COUNT_DIFF` cho lô lệch (inventory-v1, UC76); ghi audit.")
m.op("post", "/stock-counts/{countId}/cancel", "cancelStockCount", "Hủy phiên kiểm kê", "Stock counts", "UC78", "BR-KO-12",
     "A05", CNT, "OPEN → CANCELLED (Phiên kiểm kê#3)", body="ReasonRequest", resp="StockCount", path_params=["CountId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-KO-12` thiếu lý do", 409: TRANS})

# ---- UC79 truy vết
m.op("get", "/stock-lots", "searchStockLots", "Tìm lô hàng", "Lot trace", "UC79", "BR-KO-13", "A05, A07",
     "A05 lô của chi nhánh mình; A07 lô thuốc / vaccine", resp="LotSummary", page=True,
     query=["lotNumber=string|Khớp một phần, không phân biệt hoa thường", "productId=int64", "page", "size"],
     errors=(400, 401, 403), notes="Phải có `lotNumber` hoặc `productId`. Mới nhập trước.")
m.op("get", "/stock-lots/{lotId}/trace", "traceStockLot", "Truy vết đường đi của lô", "Lot trace", "UC79", "BR-KO-13",
     "A05, A07", "A05 lô của chi nhánh mình; A07 lô thuốc / vaccine", resp="LotTrace", path_params=["LotId"],
     errors=(400, 401, 403, 404),
     notes="Tên thú / khách chỉ có với lô thuốc, vaccine. Mỗi lượt xem ghi audit (BR-KO-13).")
