from lib import Module

m = Module(
    key="fees", title="Fee & Material-norm Config API v1 (tầng 3)", codes="SP (mở rộng UC30)", owner="BE-2",
    sources="01 UC32; 08-tang3-be2-spec.md §A (BR-SP-08…11); 05 bổ sung shipping_fee_configs, branch_home_visit_fees, service_material_norms",
    intro="Cấu hình phí vận chuyển (toàn chuỗi), phí khám tại nhà (theo chi nhánh) và định mức vật tư tiêu hao của dịch vụ. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Phí vận chuyển của một giỏ hàng cụ thể lấy qua `GET /me/online-orders/quote` (shop-v1), không có endpoint tính phí ở đây (ST09).",
        "ST07 (tự trừ vật tư theo định mức) là tác vụ hệ thống của BE-1, đọc định mức qua interface (08 §A, 09).",
    ],
    tags={
        "Shipping fee": "Phí vận chuyển toàn chuỗi (UC32)",
        "Home visit fee": "Phí khám tại nhà theo chi nhánh (UC32)",
        "Material norms": "Định mức vật tư của dịch vụ (UC32)",
    },
    security=[
        "Cấu hình và sửa: chỉ SUPER_MANAGER; đọc phí khám tại nhà thêm CUSTOMER (xem phí khi đặt lịch) và A06, A07 của chính chi nhánh.",
        "Mọi thay đổi ghi audit, mở rộng BR-QT-15 (BR-SP-11).",
        "Giá trị đã chốt vào đơn / lịch không đổi khi cấu hình đổi (BR-SP-11).",
    ],
    assumptions=[
        ("A1", "Phí vận chuyển là phí cố định kèm ngưỡng miễn phí, không theo vùng hay khoảng cách", "YAGNI; BR-SP-08"),
        ("A2", "Định mức chỉ nhận sản phẩm `GOODS` / `DRUG`, không nhận vaccine", "Vaccine đã trừ kho khi tiêm (BR-KO-05)"),
        ("A3", "Mỗi chi nhánh một mức phí khám tại nhà cố định", "UC32 không nêu cách tính; phương án ít phát minh nhất"),
    ],
    questions=[
        ("Q1", "Phí khám tại nhà có cần theo khoảng cách hoặc theo vùng không", "TBD (PO)", "BR-SP-09"),
        ("Q2", "Định mức vật tư theo dịch vụ hay theo dịch vụ × loài / cân nặng", "TBD (BE-2, BE-1)", "BR-SP-10"),
    ],
)

m.param("BranchId", "branchId", "path", "int64")
m.param("ServiceId", "serviceId", "path", "int64")

m.schema("ShippingFeeConfig", {"baseFee": "money|Phí cơ bản (BR-SP-08)", "freeThreshold": "money|0 = không miễn phí",
                               "updatedAt": "datetime"})
m.schema("SaveShippingFeeConfigRequest", {"baseFee": "money", "freeThreshold": "money"})
m.schema("HomeVisitFee", {"branchId": "int64", "enabled": "bool", "fee": "money"})
m.schema("SaveHomeVisitFeeRequest", {"enabled": "bool", "fee": "money"})
m.schema("MaterialNorm", {"productId": "int64", "productName": "string", "unit": "string", "quantity": "int"})
m.schema("MaterialNormInput", {"productId": "int64", "quantity": "int|Số nguyên ≥ 1"})
m.schema("SaveMaterialNormsRequest", {"items": "array:ref:MaterialNormInput|Ghi đè toàn bộ danh sách; rỗng = xóa hết"})

A04 = "Chỉ SUPER_MANAGER"
m.op("get", "/shipping-fee-config", "getShippingFeeConfig", "Xem cấu hình phí vận chuyển", "Shipping fee", "UC32",
     "BR-SP-08", "A04", A04, resp="ShippingFeeConfig", errors=(401, 403))
m.op("put", "/shipping-fee-config", "saveShippingFeeConfig", "Lưu cấu hình phí vận chuyển", "Shipping fee", "UC32",
     "BR-SP-08, 11", "A04", A04, body="SaveShippingFeeConfigRequest", resp="ShippingFeeConfig", errors=(400, 401, 403),
     idem="Có", err_desc={400: "`BR-SP-08` số âm hoặc sai kiểu"})
m.op("get", "/branches/{branchId}/home-visit-fee", "getHomeVisitFee", "Xem phí khám tại nhà của chi nhánh",
     "Home visit fee", "UC32", "BR-SP-09", "A02, A04, A06, A07", "SUPER_MANAGER; CUSTOMER (xem phí khi đặt); A06, A07 của chính chi nhánh",
     resp="HomeVisitFee", path_params=["BranchId"], errors=(400, 401, 403, 404),
     notes="Chi nhánh chưa cấu hình trả `enabled = false`, `fee = 0`.")
m.op("put", "/branches/{branchId}/home-visit-fee", "saveHomeVisitFee", "Lưu phí khám tại nhà của chi nhánh",
     "Home visit fee", "UC32", "BR-SP-09, 11", "A04", A04, body="SaveHomeVisitFeeRequest", resp="HomeVisitFee",
     path_params=["BranchId"], errors=(400, 401, 403, 404), idem="Có", err_desc={400: "`BR-SP-09` phí âm"})
m.op("get", "/services/{serviceId}/material-norms", "listMaterialNorms", "Xem định mức vật tư của dịch vụ",
     "Material norms", "UC32", "BR-SP-10", "A04", A04, resp="array:MaterialNorm", path_params=["ServiceId"],
     errors=(400, 401, 403, 404), notes="Không phân trang (ít dòng).")
m.op("put", "/services/{serviceId}/material-norms", "saveMaterialNorms", "Lưu định mức vật tư của dịch vụ",
     "Material norms", "UC32", "BR-SP-10, 11", "A04", A04, body="SaveMaterialNormsRequest", resp="array:MaterialNorm",
     path_params=["ServiceId"], errors=(400, 401, 403, 404), idem="Có",
     err_desc={400: "`BR-SP-10` sản phẩm là vaccine / không tồn tại · số lượng < 1 · trùng sản phẩm"},
     notes="Ghi đè toàn bộ; chỉ ảnh hưởng các lần thực hiện sau (BR-SP-10).")
