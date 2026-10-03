from lib import Module

m = Module(
    key="customer", title="Customer & Pet API v1", codes="KH (và phần hồ sơ khách của UC06)", owner="BE-2",
    sources="01 UC06, UC22–UC26; 02 BR-KH-01…10, BR-TK-15, 18, 19; 04 §3; 05 §3 (customers, addresses, pets, weight_records)",
    intro="Hồ sơ khách (online và tại quầy), sổ địa chỉ, tra cứu, thú cưng, cân nặng, đánh dấu đã mất, chuyển chủ.",
    frozen=[
        "UC27 gộp hồ sơ khách trùng là tầng 3, không có endpoint.",
        "Hồ sơ sức khỏe (chẩn đoán, đơn thuốc, mũi tiêm) thuộc module visit: `GET /pets/{petId}/health-record`. File này chỉ có lịch sử cân nặng.",
        "Sửa email tài khoản khách và liên kết hồ sơ tại quầy thuộc module identity (`/customers/{customerId}/account-email-change`, `/customers/{customerId}/link-account`).",
        "Cân nặng do VET ghi khi khám và do người nhận thú ghi khi nhận lưu trú đi qua module visit / boarding (gọi `PetApi.recordWeight`); ở đây chỉ có cân nặng chủ tự khai.",
        "ST19 (tự hủy Care Task khi thú mất) chạy trong cùng transaction đánh dấu đã mất qua `PetDeceasedEvent`, không có endpoint riêng.",
    ],
    tags={
        "My profile": "Hồ sơ khách và sổ địa chỉ của khách đang đăng nhập (UC06)",
        "Customers": "Lễ tân quản lý và tra cứu hồ sơ khách (UC22, UC23)",
        "Pets": "Thú cưng (UC24, UC26)",
        "Weights": "Lịch sử cân nặng (BR-KH-04)",
    },
    security=[
        "Khách chỉ thao tác hồ sơ và thú cưng của chính mình (chủ hiện tại). Sau khi chuyển chủ, chủ cũ nhận 404 với thú đó (BR-KH-08).",
        "Hồ sơ online còn cờ `linkDecisionPending`: mọi thao tác thú cưng của khách trả 400 `BUSINESS_RULE_VIOLATION`, message kèm `BR-TK-19` (BR-TK-19 ẩn quản lý thú cưng).",
        "Thú đã mất là chỉ đọc: mọi thao tác ghi trả 400 `BR-KH-05` (A2).",
        "Tra cứu (UC23) trả email đã che; nhân viên mọi chi nhánh đều tra được vì hồ sơ dùng chung toàn chuỗi (BR-KH-01, 04 nguyên tắc 8).",
        "Đánh dấu đã mất và chuyển chủ phát sự kiện đồng bộ; hủy lịch hẹn / đặt chỗ / Care Task chạy trong cùng transaction (06-module-contracts §3). Chuyển chủ ghi audit.",
    ],
    assumptions=[
        ("A1", "Khách tự sửa SĐT không nhận cảnh báo nghi trùng; cảnh báo BR-KH-10 chỉ trả cho nhân viên", "Trả danh sách hồ sơ khác cho khách làm lộ dữ liệu người khác"),
        ("A2", "Thao tác ghi trên thú đã mất trả 400 `BR-KH-05`", "BR-KH-05: hồ sơ giữ ở dạng chỉ đọc"),
        ("A3", "Xác nhận 2 bước khi đánh dấu đã mất làm ở giao diện; API yêu cầu `confirmed = true`", "BR-KH-05 'xác nhận 2 bước'"),
        ("A4", "Chuyển chủ yêu cầu `previousOwnerConfirmed = true` do lễ tân tích", "BR-KH-08 'khi chủ cũ có mặt hoặc đã xác nhận' — hệ thống không có kênh xác nhận riêng"),
        ("A5", "Email hồ sơ chỉ sửa trực tiếp được khi hồ sơ chưa gắn tài khoản; hồ sơ có tài khoản thì email theo tài khoản (sửa ở identity)", "BR-TK-16, BR-TK-19: email hồ sơ cập nhật theo email tài khoản"),
    ],
    questions=[
        ("Q1", "Ảnh đại diện / ảnh thú cưng tải lên bằng cơ chế nào (upload trực tiếp, presigned URL)? Contract chỉ nhận URL.", "TBD (BE + FE)", "05 chỉ có cột `*_url`"),
    ],
)

m.param("CustomerId", "customerId", "path", "int64")
m.param("PetId", "petId", "path", "int64")
m.param("AddressId", "addressId", "path", "int64")

m.schema("Species", None, enum=["DOG", "CAT", "OTHER"], desc="Chó, Mèo, Khác (BR-KH-02)")
m.schema("CustomerProfile", {
    "customerId": "int64", "fullName": "string:100", "phone": "phone?|Bắt buộc với hồ sơ tại quầy",
    "email": "email?", "avatarUrl": "url?", "createdChannel": "enum:ONLINE,COUNTER",
    "hasAccount": "bool", "linkDecisionPending": "bool",
})
m.schema("UpdateMyCustomerProfileRequest", {"fullName": "string:100?", "phone": "phone?", "avatarUrl": "url?"},
         required=[], desc="Email không tự sửa được (BR-TK-15)")
m.schema("Address", {
    "addressId": "int64", "receiverName": "string:100", "receiverPhone": "phone", "addressLine": "string:300",
    "ward": "string:100?", "province": "string:100", "isDefault": "bool",
})
m.schema("AddressRequest", {
    "receiverName": "string:100", "receiverPhone": "phone", "addressLine": "string:300", "ward": "string:100?",
    "province": "string:100", "isDefault": "bool?|Địa chỉ đầu tiên luôn là mặc định",
})
m.schema("UpdateAddressRequest", {
    "receiverName": "string:100?", "receiverPhone": "phone?", "addressLine": "string:300?", "ward": "string:100?",
    "province": "string:100?",
}, required=[])
m.schema("PetBrief", {"petId": "int64", "name": "string", "species": "ref:Species", "deceased": "bool"})
m.schema("CustomerSearchItem", {
    "customerId": "int64", "fullName": "string", "phone": "phone?", "maskedEmail": "string?",
    "createdChannel": "enum:ONLINE,COUNTER", "hasAccount": "bool", "pets": "array:ref:PetBrief",
}, desc="Một SĐT có thể ra nhiều hồ sơ; nhân viên chọn theo họ tên, email che và thú cưng (BR-KH-10)")
m.schema("CreateCounterCustomerRequest", {
    "fullName": "string:100", "phone": "phone|Bắt buộc (BR-KH-01), không duy nhất", "email": "email?",
})
m.schema("UpdateCustomerRequest", {
    "fullName": "string:100?", "phone": "phone?", "email": "email?|Chỉ khi hồ sơ chưa gắn tài khoản (A5)",
}, required=[])
m.schema("CustomerWriteResult", {
    "customer": "ref:CustomerProfile",
    "duplicatePhoneProfiles": "array:ref:CustomerSearchItem|Cảnh báo nghi trùng, không chặn (BR-KH-10)",
})
m.schema("CustomerDetail", {"customer": "ref:CustomerProfile", "pets": "array:ref:PetResponse"})
m.schema("PetResponse", {
    "petId": "int64", "customerId": "int64|Chủ hiện tại", "name": "string:100", "species": "ref:Species",
    "sex": "enum:MALE,FEMALE,UNKNOWN", "breed": "string:100?", "birthDate": "date?", "birthDateEstimated": "bool",
    "color": "string:50?", "isNeutered": "bool?", "photoUrl": "url?", "deceasedOn": "date?",
    "speciesLocked": "bool|Đã có bệnh án hoặc mũi tiêm (BR-KH-03)",
    "currentWeightKg": "decimal?|Bản ghi cân mới nhất (BR-KH-04)",
    "deletable": "bool|Chưa phát sinh lịch hẹn, đặt chỗ, Visit, Order (BR-KH-06)",
})
m.schema("CreatePetRequest", {
    "name": "string:100", "species": "ref:Species", "sex": "enum:MALE,FEMALE,UNKNOWN", "breed": "string:100?",
    "birthDate": "date?|Không ở tương lai", "birthDateEstimated": "bool?", "color": "string:50?",
    "isNeutered": "bool?", "photoUrl": "url?",
})
m.schema("UpdatePetRequest", {
    "name": "string:100?", "species": "ref:Species", "sex": "enum:MALE,FEMALE,UNKNOWN?", "breed": "string:100?",
    "birthDate": "date?", "birthDateEstimated": "bool?", "color": "string:50?", "isNeutered": "bool?",
    "photoUrl": "url?",
}, required=[])
m.schema("PetWriteResult", {
    "pet": "ref:PetResponse",
    "duplicateNameWarning": "bool|Chủ đã có thú cùng tên, cùng loài — cảnh báo, không chặn (BR-KH-02)",
})
m.schema("MarkDeceasedRequest", {"deceasedOn": "date|Không ở tương lai", "confirmed": "bool|Phải true (A3)"})
m.schema("TransferOwnerRequest", {
    "newCustomerId": "int64|Chủ mới đã có hồ sơ (BR-KH-08)",
    "previousOwnerConfirmed": "bool|Phải true (A4)",
})
m.schema("WeightRecord", {
    "weightRecordId": "int64", "weightKg": "decimal", "source": "enum:VET,INTAKE,OWNER",
    "visitId": "int64?", "boardingBookingId": "int64?", "measuredAt": "datetime",
})
m.schema("AddWeightRequest", {"weightKg": "decimal|> 0 (BR-KH-04)", "measuredAt": "datetime?|Mặc định là lúc gửi"})

PET_OWNER = "Khách là chủ hiện tại · A06"
m.op("get", "/me/customer-profile", "getMyCustomerProfile", "Hồ sơ khách của tôi", "My profile", "UC06",
     "BR-KH-01, BR-TK-15", "A02", "Khách đang đăng nhập", resp="CustomerProfile", errors=(401, 403))
m.op("patch", "/me/customer-profile", "updateMyCustomerProfile", "Sửa hồ sơ khách của tôi", "My profile", "UC06",
     "BR-TK-15, BR-KH-01", "A02", "Khách đang đăng nhập", body="UpdateMyCustomerProfileRequest",
     resp="CustomerProfile", errors=(400, 401, 403), err_desc={400: "`BR-TK-15` cố sửa email · dữ liệu không hợp lệ"},
     notes="Không trả cảnh báo nghi trùng cho khách (A1).")
m.op("get", "/me/addresses", "listMyAddresses", "Sổ địa chỉ", "My profile", "UC06", "BR-TK-18", "A02",
     "Khách đang đăng nhập", resp="array:Address", errors=(401, 403))
m.op("post", "/me/addresses", "createMyAddress", "Thêm địa chỉ", "My profile", "UC06", "BR-TK-18", "A02",
     "Khách đang đăng nhập", body="AddressRequest", resp="Address", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-TK-18` đã đủ 5 địa chỉ [CFG]"})
m.op("patch", "/me/addresses/{addressId}", "updateMyAddress", "Sửa địa chỉ", "My profile", "UC06", "BR-TK-18",
     "A02", "Chủ địa chỉ", body="UpdateAddressRequest", resp="Address", path_params=["AddressId"],
     errors=(400, 401, 403, 404))
m.op("delete", "/me/addresses/{addressId}", "deleteMyAddress", "Xóa địa chỉ", "My profile", "UC06", "BR-TK-18",
     "A02", "Chủ địa chỉ", status=204, path_params=["AddressId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-TK-18` không xóa địa chỉ mặc định khi còn địa chỉ khác"})
m.op("post", "/me/addresses/{addressId}/set-default", "setDefaultAddress", "Đặt địa chỉ mặc định", "My profile",
     "UC06", "BR-TK-18", "A02", "Chủ địa chỉ", resp="Address", path_params=["AddressId"], errors=(401, 403, 404),
     notes="Bỏ cờ mặc định của địa chỉ cũ trong cùng transaction.")

m.op("get", "/customers", "searchCustomers", "Tra cứu khách & thú cưng", "Customers", "UC23", "BR-KH-01, 10",
     "A06, A07, A08", "Nhân viên mọi chi nhánh", resp="CustomerSearchItem", page=True, errors=(400, 401, 403),
     query=["phone=string|Khớp đúng SĐT đã chuẩn hóa", "name=string|Họ tên chứa chuỗi", "email=string",
            "petName=string", "page", "size"],
     notes="Cần ít nhất một điều kiện tìm.",
     err_desc={400: "Không có điều kiện tìm nào"})
m.op("post", "/customers", "createCounterCustomer", "Tạo hồ sơ khách tại quầy", "Customers", "UC22, UC44",
     "BR-KH-01, 10", "A06", "Lễ tân", body="CreateCounterCustomerRequest", resp="CustomerWriteResult", status=201,
     errors=(400, 401, 403), notes="SĐT trùng hồ sơ khác: vẫn tạo, trả danh sách nghi trùng (BR-KH-10).")
m.op("get", "/customers/{customerId}", "getCustomer", "Chi tiết hồ sơ khách kèm thú cưng", "Customers", "UC22, UC23",
     "BR-KH-01", "A06, A07, A08", "Nhân viên", resp="CustomerDetail", path_params=["CustomerId"], errors=(401, 403, 404))
m.op("patch", "/customers/{customerId}", "updateCustomer", "Sửa hồ sơ khách", "Customers", "UC22",
     "BR-KH-01, 10, BR-TK-16", "A06", "Lễ tân", body="UpdateCustomerRequest", resp="CustomerWriteResult",
     path_params=["CustomerId"], errors=(400, 401, 403, 404),
     err_desc={400: "Sửa email khi hồ sơ đã gắn tài khoản (dùng identity, A5) · thiếu SĐT với hồ sơ tại quầy (BR-KH-01)"})
m.op("post", "/customers/{customerId}/pets", "createPetForCustomer", "Lễ tân thêm thú cưng cho khách", "Pets",
     "UC24", "BR-KH-02", "A06", "Lễ tân", body="CreatePetRequest", resp="PetWriteResult", status=201,
     path_params=["CustomerId"], errors=(400, 401, 403, 404))

m.op("get", "/me/pets", "listMyPets", "Thú cưng của tôi", "Pets", "UC24", "BR-KH-05, BR-TK-19", "A02",
     "Khách đang đăng nhập", resp="array:PetResponse", errors=(400, 401, 403),
     err_desc={400: "`BR-TK-19` hồ sơ còn chờ quyết định liên kết"})
m.op("post", "/me/pets", "createMyPet", "Thêm thú cưng", "Pets", "UC24", "BR-KH-02, BR-TK-19", "A02",
     "Khách đang đăng nhập", body="CreatePetRequest", resp="PetWriteResult", status=201, errors=(400, 401, 403),
     err_desc={400: "Ngày sinh ở tương lai · thiếu trường bắt buộc (BR-KH-02) · `BR-TK-19` hồ sơ còn chờ quyết định liên kết"})
m.op("get", "/pets/{petId}", "getPet", "Chi tiết thú cưng", "Pets", "UC24", "BR-KH-03, 06, 08", "A02, A06, A07, A08",
     "Chủ hiện tại; nhân viên mọi chi nhánh", resp="PetResponse", path_params=["PetId"], errors=(401, 403, 404))
m.op("patch", "/pets/{petId}", "updatePet", "Sửa thông tin thú cưng", "Pets", "UC24", "BR-KH-02, 03, 05", "A02, A06",
     PET_OWNER, body="UpdatePetRequest", resp="PetWriteResult", path_params=["PetId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KH-03` đổi loài khi đã có bệnh án / mũi tiêm · `BR-KH-05` thú đã mất (chỉ đọc)"})
m.op("post", "/pets/{petId}/mark-deceased", "markPetDeceased", "Đánh dấu thú đã mất", "Pets", "UC24, ST19",
     "BR-KH-05, BR-LT-12, BR-TB-03", "A02, A06", PET_OWNER, "Phát PetDeceasedEvent → Lịch hẹn#5, Đặt chỗ#5, Care Task#4",
     body="MarkDeceasedRequest", resp="PetResponse", path_params=["PetId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-KH-05` thú có Visit mở hoặc đang lưu trú (kết thúc lưu trú trước theo BR-LT-12) · chưa xác nhận",
               409: "Thú đã được đánh dấu đã mất (không hoàn tác)"})
m.op("delete", "/pets/{petId}", "deletePet", "Xóa thú cưng chưa phát sinh giao dịch", "Pets", "UC24", "BR-KH-06",
     "A02, A06", PET_OWNER, status=204, path_params=["PetId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KH-06` đã có lịch hẹn, đặt chỗ, Visit hoặc Order — dùng đánh dấu đã mất"})
m.op("post", "/pets/{petId}/transfer-owner", "transferPetOwner", "Chuyển chủ thú cưng", "Pets", "UC26",
     "BR-KH-08", "A06", "Lễ tân", "Phát PetOwnerTransferredEvent → Lịch hẹn#5, Đặt chỗ#5; Care Task OPEN sang chủ mới",
     body="TransferOwnerRequest", resp="PetResponse", path_params=["PetId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KH-08` thú có Visit mở, Order PENDING hoặc đang lưu trú · chưa xác nhận chủ cũ · `BR-KH-05` thú đã mất"},
     notes="Order cũ giữ chủ cũ; ghi audit.")

m.op("get", "/pets/{petId}/weights", "listPetWeights", "Lịch sử cân nặng", "Weights", "UC24, UC25", "BR-KH-04, 07",
     "A02, A06, A07, A08", "Chủ hiện tại; nhân viên", resp="array:WeightRecord", path_params=["PetId"],
     errors=(401, 403, 404), notes="Mới nhất trước.")
m.op("post", "/pets/{petId}/weights", "addOwnerWeight", "Chủ tự khai cân nặng", "Weights", "UC24", "BR-KH-04",
     "A02", "Khách là chủ hiện tại", body="AddWeightRequest", resp="WeightRecord", status=201, path_params=["PetId"],
     errors=(400, 401, 403, 404), err_desc={400: "`BR-KH-04` cân nặng ≤ 0 · `BR-KH-05` thú đã mất"},
     notes="source = OWNER (đánh dấu chủ tự khai).")
