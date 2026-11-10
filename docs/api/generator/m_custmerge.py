from lib import Module

m = Module(
    key="custmerge", title="Customer Merge API v1 (tầng 3)", codes="KH (gộp hồ sơ khách, UC27)", owner="BE-2",
    sources="01 UC27; 08-tang3-be2-spec.md §F.2 (BR-KH-11…14); 05 bổ sung customers.merged_into_id, customer_merges",
    intro="Xem trước và gộp hồ sơ khách trùng của cùng một khách. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Gợi ý hồ sơ nghi trùng dùng chức năng tra cứu sẵn có của customer-v1 (UC22); không lặp ở đây.",
        "Hệ quả lên module khác chạy qua `CustomerMergedEvent` (06 §3), không có endpoint từng module.",
        "Gộp không hoàn tác; không có endpoint hủy gộp (BR-KH-13).",
    ],
    tags={"Customer merge": "Gộp hồ sơ khách trùng (UC27)"},
    security=[
        "Chỉ BRANCH_MANAGER (A05); gộp chạm dữ liệu dùng chung toàn chuỗi (hồ sơ khách dùng chung, 04 nguyên tắc 8) nên mọi lượt gộp ghi `customer_merges` và audit (BR-KH-13, BR-QT-15).",
        "Không gộp khi cả hai hồ sơ đã liên kết tài khoản (BR-TK-19, BR-KH-11).",
        "Toàn bộ chuyển dữ liệu chạy trong một transaction (04 nguyên tắc 6); khóa hai hồ sơ theo thứ tự `id` tăng dần để tránh deadlock.",
    ],
    assumptions=[
        ("A1", "BRANCH_MANAGER gộp được mọi hồ sơ khách dùng chung toàn chuỗi, không chỉ khách của chi nhánh mình", "Hồ sơ khách không thuộc chi nhánh (04 nguyên tắc 8)"),
        ("A2", "Không tự gộp thú trùng; lễ tân xử lý sau", "BR-KH-12"),
        ("A3", "Gộp không hoàn tác", "BR-KH-13; YAGNI"),
    ],
    questions=[
        ("Q1", "Phạm vi quản lý của A05: chỉ khách đã có giao dịch tại chi nhánh mình hay toàn chuỗi", "TBD (PO)", "BR-KH-11, UC27"),
        ("Q2", "Có cần SUPER_MANAGER duyệt khi hồ sơ có đơn hàng lớn không", "TBD (PO)", "BR-KH-11"),
    ],
)

m.param("CustomerId", "customerId", "path", "int64")

m.schema("MergePreview", {
    "targetCustomerId": "int64", "sourceCustomerId": "int64", "petCount": "int", "addressCount": "int",
    "duplicateAddressCount": "int|Địa chỉ trùng sẽ bỏ", "appointmentCount": "int", "boardingCount": "int",
    "orderCount": "int", "onlineOrderCount": "int", "feedbackCount": "int",
    "careTaskCount": "int",
    "warnings": "array:string|Cảnh báo (ví dụ nghi trùng thú, khác SĐT)",
    "blockers": "array:string|Điều kiện chặn BR-KH-11; rỗng = gộp được",
})
m.schema("CustomerMergeRequest", {
    "sourceCustomerId": "int64", "targetCustomerId": "int64",
    "confirm": "bool|Phải là true sau khi đã xem merge-preview (BR-KH-14)",
})
m.schema("CustomerMergeResult", {
    "mergeId": "int64", "sourceCustomerId": "int64", "targetCustomerId": "int64",
    "moved": "object|Số bản ghi đã chuyển theo loại", "createdAt": "datetime",
})

BM = "BRANCH_MANAGER (xem Q1)"
m.op("get", "/customers/{customerId}/merge-preview", "previewCustomerMerge", "Xem trước gộp hồ sơ khách", "Customer merge",
     "UC27", "BR-KH-11, 12, 14", "A05", BM, resp="MergePreview", path_params=["CustomerId"],
     query=["sourceCustomerId!=int64|Hồ sơ nguồn sẽ gộp vào hồ sơ `customerId` (đích)"], errors=(400, 401, 403, 404),
     err_desc={400: "`sourceCustomerId` trùng đích hoặc thiếu", 404: "Không tìm thấy một trong hai hồ sơ"},
     notes="Không ghi gì. `blockers` rỗng mới gộp được.")
m.op("post", "/customer-merges", "mergeCustomers", "Gộp hồ sơ khách trùng", "Customer merge", "UC27",
     "BR-KH-11…14", "A05", BM, body="CustomerMergeRequest", resp="CustomerMergeResult", status=201,
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KH-11` cả hai đã liên kết tài khoản · đích không phải bên có tài khoản · nguồn còn Visit mở / Order `OPEN` / `PENDING` · đã `MERGED` · `BR-KH-14` chưa `confirm`",
               404: "Không tìm thấy hồ sơ", 409: "`CONCURRENCY_CONFLICT` hồ sơ vừa bị đổi"},
     notes="Chuyển dữ liệu và phát `CustomerMergedEvent` trong một transaction (BR-KH-12). Không hoàn tác (BR-KH-13).")
