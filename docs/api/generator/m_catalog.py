from lib import Module

m = Module(
    key="catalog", title="Catalog API v1", codes="SP (và phần sản phẩm / dịch vụ của CK)", owner="BE-2",
    sources="01 UC15, UC16, UC28–UC31; 02 BR-SP-01…07, BR-CK-01…03; 04 §4; 05 §4 (product_categories, products, services, kennel_types, vaccine_types, vaccination_protocols)",
    intro="Danh mục sản phẩm, sản phẩm, dịch vụ (gồm loại chuồng), loại vaccine, phác đồ tiêm; trang công khai dịch vụ và sản phẩm.",
    frozen=[
        "UC32 phí vận chuyển & phí khám tại nhà là tầng 3, không có endpoint.",
        "Định mức vật tư tiêu hao của dịch vụ (UC30, ST07) là tầng 3.",
        "Bật / tắt dịch vụ tại chi nhánh (UC33) thuộc module branch.",
        "Tồn kho thuộc module inventory; trang công khai chỉ nhận danh sách chi nhánh còn hàng qua `StockQueryApi`.",
    ],
    tags={
        "Product categories": "Danh mục sản phẩm (UC28)",
        "Products": "Sản phẩm, thuốc, vaccine (UC29)",
        "Services": "Dịch vụ và loại chuồng (UC30)",
        "Vaccines": "Loại vaccine và phác đồ (UC31)",
        "Public": "Dịch vụ, sản phẩm cho khách xem (UC15, UC16)",
    },
    security=[
        "Ghi danh mục chỉ SUPER_MANAGER. Đọc (không public) mở cho mọi nhân viên để dùng ở POS, kê đơn, tiêm.",
        "Đổi giá sản phẩm / dịch vụ ghi audit (BR-QT-15); dòng Order đã có giữ giá snapshot (BR-BH-03).",
        "API public không bao giờ trả thuốc kê đơn, sản phẩm / dịch vụ ngừng kinh doanh, hay số lượng tồn (BR-CK-01, 03, BR-SP-01).",
    ],
    assumptions=[
        ("A1", "Nhóm dịch vụ (`group`) và loại sản phẩm (`productType`) không đổi sau khi tạo", "Quota, gán nhân viên, dòng Order, phác đồ đều phụ thuộc; docs không có thao tác đổi"),
        ("A2", "Ngừng kinh doanh / ngừng sử dụng = PATCH `isActive = false`; không xóa sản phẩm, dịch vụ, danh mục", "04 nguyên tắc 2 (không xóa cứng); chỉ loại vaccine có rule xóa (BR-SP-07)"),
        ("A3", "Phác đồ: sửa `intervalDays`, `minAgeWeeks`, `requiredForBoarding`, `isActive`; loài, loại vaccine, mũi thứ không đổi", "Bộ ba này là khóa duy nhất (05 `vaccination_protocols`); BR-SP-03"),
        ("A4", "Danh sách sản phẩm cho nhân viên có bộ lọc `retailOnly` (bỏ thuốc kê đơn) để dùng ở POS", "BR-SP-01: thuốc kê đơn không bán lẻ"),
    ],
    questions=[
        ("Q1", "Ảnh sản phẩm tải lên bằng cơ chế nào? Contract chỉ nhận URL.", "TBD (BE + FE)", "05 `products.image_url`"),
    ],
)

m.param("CategoryId", "categoryId", "path", "int64")
m.param("ProductId", "productId", "path", "int64")
m.param("ServiceId", "serviceId", "path", "int64")
m.param("VaccineTypeId", "vaccineTypeId", "path", "int64")
m.param("ProtocolId", "protocolId", "path", "int64")

m.schema("Species", None, enum=["DOG", "CAT", "OTHER"])
m.schema("ServiceGroup", None, enum=["MEDICAL", "GROOMING", "BOARDING"], desc="Khám/Tiêm, Thẩm mỹ, Lưu trú (05 §0)")
m.schema("MedicalType", None, enum=["EXAM", "VACCINE"], desc="Chỉ nhóm MEDICAL (BR-SP-06)")
m.schema("ProductType", None, enum=["GOODS", "DRUG", "VACCINE"])
m.schema("ProductCategory", {"categoryId": "int64", "name": "string:100", "isActive": "bool"})
m.schema("ProductCategoryRequest", {"name": "string:100"})
m.schema("UpdateProductCategoryRequest", {"name": "string:100?", "isActive": "bool?"}, required=[])
m.schema("Product", {
    "productId": "int64", "categoryId": "int64", "sku": "string:50", "name": "string:200",
    "productType": "ref:ProductType", "isPrescription": "bool", "tracksExpiry": "bool",
    "vaccineTypeId": "int64?", "unit": "string:20", "price": "money", "description": "string?",
    "imageUrl": "url?", "isActive": "bool",
})
m.schema("CreateProductRequest", {
    "categoryId": "int64", "sku": "string:50", "name": "string:200", "productType": "ref:ProductType",
    "isPrescription": "bool|Chỉ DRUG (BR-SP-01)",
    "tracksExpiry": "bool|Bắt buộc true với thuốc kê đơn và vaccine (BR-SP-05)",
    "vaccineTypeId": "int64?|Bắt buộc với VACCINE (BR-SP-07)",
    "unit": "string:20", "price": "money", "description": "string?", "imageUrl": "url?",
})
m.schema("UpdateProductRequest", {
    "categoryId": "int64?", "name": "string:200?", "isPrescription": "bool?", "tracksExpiry": "bool?",
    "vaccineTypeId": "int64?", "unit": "string:20?", "price": "money?", "description": "string?",
    "imageUrl": "url?", "isActive": "bool?|false = ngừng kinh doanh (A2)",
}, required=[])
m.schema("KennelTypeSpec", {"species": "ref:Species", "maxWeightKg": "decimal|> 0"},
         desc="Chỉ với dịch vụ nhóm BOARDING (BR-SP-04)")
m.schema("Service", {
    "serviceId": "int64", "name": "string:150", "group": "ref:ServiceGroup", "medicalType": "ref:MedicalType",
    "price": "money|Với BOARDING là giá theo đêm", "priceIsFrom": "bool|Hiển thị \"Từ X đ\" (BR-CK-02)",
    "description": "string?", "isActive": "bool", "kennelType": "ref:KennelTypeSpec",
}, required=["serviceId", "name", "group", "price", "priceIsFrom", "isActive"])
m.schema("CreateServiceRequest", {
    "name": "string:150", "group": "ref:ServiceGroup",
    "medicalType": "ref:MedicalType", "price": "money|> 0 với BOARDING (BR-SP-04)", "priceIsFrom": "bool?",
    "description": "string?", "kennelType": "ref:KennelTypeSpec",
}, required=["name", "group", "price"])
m.schema("UpdateServiceRequest", {
    "name": "string:150?", "medicalType": "ref:MedicalType", "price": "money?", "priceIsFrom": "bool?",
    "description": "string?", "isActive": "bool?|false = ngừng (A2)", "kennelType": "ref:KennelTypeSpec",
}, required=[])
m.schema("VaccineType", {"vaccineTypeId": "int64", "name": "string:100", "species": "ref:Species", "isActive": "bool"})
m.schema("VaccineTypeRequest", {"name": "string:100", "species": "ref:Species"})
m.schema("UpdateVaccineTypeRequest", {"name": "string:100?", "isActive": "bool?"}, required=[])
m.schema("VaccinationProtocol", {
    "protocolId": "int64", "species": "ref:Species", "vaccineTypeId": "int64", "doseNumber": "int",
    "intervalDays": "int|Khoảng cách tới mũi kế (ngày)", "minAgeWeeks": "int", "requiredForBoarding": "bool",
    "isActive": "bool",
})
m.schema("CreateProtocolRequest", {
    "species": "ref:Species", "vaccineTypeId": "int64", "doseNumber": "int|≥ 1", "intervalDays": "int|> 0 (BR-SP-02)",
    "minAgeWeeks": "int|≥ 0", "requiredForBoarding": "bool",
})
m.schema("UpdateProtocolRequest", {
    "intervalDays": "int?", "minAgeWeeks": "int?", "requiredForBoarding": "bool?", "isActive": "bool?",
}, required=[], desc="Chỉ áp dụng cho mũi tiêm sau thời điểm sửa (BR-SP-03)")
m.schema("PublicService", {
    "serviceId": "int64", "name": "string", "group": "ref:ServiceGroup", "price": "money", "priceIsFrom": "bool",
    "description": "string?", "kennelType": "ref:KennelTypeSpec",
    "branchIds": "array:int64|Chi nhánh ACTIVE đang bật dịch vụ (BR-CK-01)",
}, required=["serviceId", "name", "group", "price", "priceIsFrom", "branchIds"])
m.schema("PublicProduct", {
    "productId": "int64", "name": "string", "categoryId": "int64", "categoryName": "string", "price": "money",
    "unit": "string", "description": "string?", "imageUrl": "url?",
    "inStockBranchIds": "array:int64|Chi nhánh có tồn khả dụng > 0, không kèm số lượng (BR-CK-03)",
})

A04 = "Chỉ SUPER_MANAGER"
STAFF_READ = "Mọi nhân viên"
m.op("get", "/product-categories", "listProductCategories", "Danh mục sản phẩm", "Product categories", "UC28", "—",
     "A04–A08", STAFF_READ, resp="array:ProductCategory", query=["isActive=bool"], errors=(401, 403))
m.op("post", "/product-categories", "createProductCategory", "Tạo danh mục", "Product categories", "UC28", "—",
     "A04", A04, body="ProductCategoryRequest", resp="ProductCategory", status=201, errors=(400, 401, 403),
     err_desc={400: "Tên trùng"})
m.op("patch", "/product-categories/{categoryId}", "updateProductCategory", "Sửa / ẩn danh mục", "Product categories",
     "UC28", "—", "A04", A04, body="UpdateProductCategoryRequest", resp="ProductCategory",
     path_params=["CategoryId"], errors=(400, 401, 403, 404))

m.op("get", "/products", "listProducts", "Danh sách sản phẩm", "Products", "UC29, UC66", "BR-SP-01", "A04–A08",
     STAFF_READ, resp="Product", page=True, errors=(401, 403),
     query=["q=string|Tên hoặc SKU", "categoryId=int64", "productType=enum:GOODS,DRUG,VACCINE",
            "vaccineTypeId=int64", "isActive=bool", "retailOnly=bool|Bỏ thuốc kê đơn, dùng ở POS (A4)", "page", "size"])
m.op("post", "/products", "createProduct", "Tạo sản phẩm", "Products", "UC29", "BR-SP-01, 05, 07", "A04", A04,
     body="CreateProductRequest", resp="Product", status=201, errors=(400, 401, 403),
     err_desc={400: "SKU trùng · `BR-SP-01` cờ kê đơn trên loại khác DRUG · `BR-SP-05` thuốc kê đơn / vaccine tắt quản lý hạn dùng · `BR-SP-07` vaccine chưa gắn loại vaccine"})
m.op("get", "/products/{productId}", "getProduct", "Chi tiết sản phẩm", "Products", "UC29", "—", "A04–A08", STAFF_READ,
     resp="Product", path_params=["ProductId"], errors=(401, 403, 404))
m.op("patch", "/products/{productId}", "updateProduct", "Sửa sản phẩm / đổi giá / ngừng kinh doanh", "Products", "UC29",
     "BR-SP-01, 05, 07, BR-BH-03, BR-QT-15", "A04", A04, body="UpdateProductRequest", resp="Product",
     path_params=["ProductId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-SP-05` tắt quản lý hạn dùng khi còn tồn ở bất kỳ chi nhánh nào · `BR-SP-01`, `BR-SP-07` như khi tạo"},
     notes="Đổi giá ghi audit, không ảnh hưởng dòng Order đã có.")

m.op("get", "/services", "listServices", "Danh sách dịch vụ", "Services", "UC30", "BR-SP-04, 06", "A04–A08",
     STAFF_READ, resp="array:Service", query=["group=enum:MEDICAL,GROOMING,BOARDING", "isActive=bool"],
     errors=(401, 403))
m.op("post", "/services", "createService", "Tạo dịch vụ / loại chuồng", "Services", "UC30", "BR-SP-04, 06", "A04", A04,
     body="CreateServiceRequest", resp="Service", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-SP-06` nhóm MEDICAL chưa chọn loại · `BR-SP-04` loại chuồng thiếu loài hoặc giá ≤ 0 · tên trùng"})
m.op("get", "/services/{serviceId}", "getService", "Chi tiết dịch vụ", "Services", "UC30", "—", "A04–A08", STAFF_READ,
     resp="Service", path_params=["ServiceId"], errors=(401, 403, 404))
m.op("patch", "/services/{serviceId}", "updateService", "Sửa dịch vụ / đổi giá / ngừng", "Services", "UC30",
     "BR-SP-04, 06, BR-BH-03, BR-QT-15", "A04", A04, body="UpdateServiceRequest", resp="Service",
     path_params=["ServiceId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-SP-06`, `BR-SP-04` như khi tạo · đổi nhóm (A1)"},
     notes="Đổi giá ghi audit; giá đêm đã snapshot ở đặt chỗ lưu trú không đổi (BR-LT-02).")

m.op("get", "/vaccine-types", "listVaccineTypes", "Danh sách loại vaccine", "Vaccines", "UC31, UC49", "BR-SP-07",
     "A04–A08", STAFF_READ, resp="array:VaccineType", query=["species=enum:DOG,CAT,OTHER", "isActive=bool"],
     errors=(401, 403))
m.op("post", "/vaccine-types", "createVaccineType", "Tạo loại vaccine", "Vaccines", "UC31", "BR-SP-07", "A04", A04,
     body="VaccineTypeRequest", resp="VaccineType", status=201, errors=(400, 401, 403),
     err_desc={400: "Trùng tên trong cùng loài"})
m.op("patch", "/vaccine-types/{vaccineTypeId}", "updateVaccineType", "Sửa / ngừng loại vaccine", "Vaccines", "UC31",
     "BR-SP-07", "A04", A04, body="UpdateVaccineTypeRequest", resp="VaccineType", path_params=["VaccineTypeId"],
     errors=(400, 401, 403, 404))
m.op("delete", "/vaccine-types/{vaccineTypeId}", "deleteVaccineType", "Xóa loại vaccine chưa dùng", "Vaccines",
     "UC31", "BR-SP-07", "A04", A04, status=204, path_params=["VaccineTypeId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-SP-07` đã có phác đồ, sản phẩm hoặc mũi tiêm — chỉ ngừng sử dụng"})
m.op("get", "/vaccination-protocols", "listVaccinationProtocols", "Phác đồ tiêm chủng", "Vaccines", "UC31, UC49",
     "BR-SP-02", "A04–A08", STAFF_READ, resp="array:VaccinationProtocol",
     query=["species=enum:DOG,CAT,OTHER", "vaccineTypeId=int64", "isActive=bool"], errors=(401, 403))
m.op("post", "/vaccination-protocols", "createVaccinationProtocol", "Thêm dòng phác đồ", "Vaccines", "UC31",
     "BR-SP-02, 07", "A04", A04, body="CreateProtocolRequest", resp="VaccinationProtocol", status=201,
     errors=(400, 401, 403), err_desc={400: "`BR-SP-02` thiếu trường hoặc khoảng cách ≤ 0 · trùng (loài, loại vaccine, mũi) · loài khác loài của loại vaccine"})
m.op("patch", "/vaccination-protocols/{protocolId}", "updateVaccinationProtocol", "Sửa dòng phác đồ", "Vaccines",
     "UC31", "BR-SP-02, 03", "A04", A04, body="UpdateProtocolRequest", resp="VaccinationProtocol",
     path_params=["ProtocolId"], errors=(400, 401, 403, 404),
     notes="Ngày tái chủng đã tính giữ nguyên (BR-SP-03).")

m.op("get", "/public/services", "listPublicServices", "Dịch vụ công khai", "Public", "UC15", "BR-CK-01, 02, BR-SP-04",
     "A01, A02", "Public", resp="array:PublicService", public=True, errors=(400,),
     query=["group=enum:MEDICAL,GROOMING,BOARDING", "branchId=int64"],
     notes="Chỉ dịch vụ đang kinh doanh và bật ở ≥ 1 chi nhánh ACTIVE.")
m.op("get", "/public/product-categories", "listPublicProductCategories", "Danh mục sản phẩm công khai", "Public",
     "UC16", "BR-CK-01", "A01, A02", "Public", resp="array:ProductCategory", public=True, errors=(400,))
m.op("get", "/public/products", "listPublicProducts", "Xem & tìm sản phẩm", "Public", "UC16",
     "BR-CK-01, 02, 03, BR-SP-01", "A01, A02", "Public", resp="PublicProduct", page=True, public=True, errors=(400,),
     query=["q=string", "categoryId=int64", "branchId=int64|Chỉ sản phẩm còn hàng tại chi nhánh", "page", "size"])
m.op("get", "/public/products/{productId}", "getPublicProduct", "Chi tiết sản phẩm công khai", "Public", "UC16",
     "BR-CK-01, 03, BR-SP-01", "A01, A02", "Public", resp="PublicProduct", path_params=["ProductId"], public=True,
     errors=(404,), notes="Thuốc kê đơn hoặc ngừng kinh doanh trả 404.")
