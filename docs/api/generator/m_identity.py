from lib import Module

m = Module(
    key="identity", title="Identity API v1", codes="TK, QT", owner="BE-1",
    sources="01 UC01–UC11, UC22 (phần tài khoản); 02 BR-TK-01…20, BR-QT-01…16; 03 #1 Tài khoản; 04 §1; 05 §1 (accounts, staff_profiles, sessions, otp_tokens, audit_logs, system_configs, notification_templates)",
    intro="Đăng ký, OTP, đăng nhập, mật khẩu, hồ sơ nhân viên, liên kết hồ sơ khách, quản lý nhân viên, khóa/mở khóa, tham số, mẫu thông báo, audit, hồ sơ bác sĩ công khai.",
    frozen=[
        "ST01 (khóa tạm do đăng nhập sai) và ST02 (dọn tài khoản `PENDING`) là tác vụ hệ thống, không có endpoint.",
        "Hồ sơ khách (họ tên, SĐT, ảnh) và sổ địa chỉ của khách thuộc module customer: `/me/customer-profile`, `/me/addresses` (A5).",
        "Gửi email/thông báo là việc của module care qua `NotificationApi`; ở đây không có endpoint gửi thư.",
    ],
    tags={
        "Auth": "Đăng ký, OTP, đăng nhập, mật khẩu (UC01–UC05)",
        "Me": "Thông tin và hồ sơ của người đang đăng nhập (UC05, UC06)",
        "Profile link": "Liên kết tài khoản với hồ sơ khách có sẵn (UC07, BR-TK-19)",
        "Staff": "Quản lý tài khoản nhân viên (UC08)",
        "Customer account": "Lễ tân xử lý tài khoản của khách tại quầy (UC22)",
        "Accounts": "Khóa / mở khóa mọi tài khoản (UC09)",
        "Configuration": "Tham số [CFG] và mẫu thông báo (UC10)",
        "Audit": "Nhật ký audit (UC11)",
        "Public": "Hồ sơ bác sĩ công khai (UC15)",
    },
    security=[
        "Mật khẩu, mã OTP và token phiên chỉ lưu dạng hash (`password_hash`, `otp_tokens.code_hash`, `sessions.token_hash`).",
        "Mỗi request kiểm phiên còn hạn và chưa bị hủy; khóa, vô hiệu hóa, đổi/đặt lại mật khẩu hủy phiên có hiệu lực ngay ở request kế tiếp (BR-TK-11, 13, 14).",
        "Đăng nhập và quên mật khẩu trả thông điệp chung, không lộ email có tồn tại (BR-TK-10). Riêng đăng ký được báo email trùng (BR-TK-01).",
        "Giới hạn OTP kiểm ở server: 60 giây giữa hai lần gửi, 5 mã/giờ/email, 5 lần nhập sai/mã (BR-TK-06, 07).",
        "Ghi audit: đăng nhập thành công và thất bại, mọi thao tác Staff / Accounts / Configuration, sửa email hộ, liên kết hồ sơ (BR-QT-15, BR-TK-16, 19).",
        "Phân cấp: request ngoài phạm vi BR-QT-01 / BR-QT-07 trả 403 và ghi audit. BRANCH_MANAGER chỉ thấy và thao tác nhân viên chi nhánh mình.",
        "Khi `mustChangePassword = true`, mọi API trừ `GET /me`, `POST /me/password`, `POST /auth/logout` trả 400 `BUSINESS_RULE_VIOLATION`, message kèm `BR-TK-17` (A4). Path public (§3.1 của `00-method`: `/auth/register…`, `/auth/login`, `/auth/password/…`, `/public/…`) không bị chặn vì luôn xử lý như chưa đăng nhập, token gửi kèm bị bỏ qua (ADR-0005).",
        "Request của nhân viên cập nhật `last_seen_at` (tối đa một lần mỗi 60 giây, ADR-0003); đăng xuất đặt về offline ngay (BR-TN-06).",
    ],
    assumptions=[
        ("A1", "Access token gắn 1-1 với một phiên (`sessions`), không có refresh token; phiên hết hạn thì đăng nhập lại", "Docs chỉ có bảng `sessions` có `expires_at`, `revoked_at`; không nhắc refresh"),
        ("A2", "Xác thực OTP đăng ký không tự đăng nhập; client gọi `POST /auth/login` sau đó", "Ít phát minh nhất; 03 Tài khoản#2 không có hệ quả tạo phiên"),
        ("A3", "Đăng ký lại bằng email còn `PENDING` bị báo trùng (BR-TK-01); người dùng dùng gửi lại OTP", "Email duy nhất trong tập tài khoản, `PENDING` vẫn là tài khoản"),
        ("A4", "Chặn mọi chức năng khi chưa đổi mật khẩu lần đầu bằng 400, message kèm `BR-TK-17`; path public (đăng ký, đăng nhập, quên / đặt lại mật khẩu, `/public/…`) không bị chặn vì luôn xử lý như chưa đăng nhập, kể cả khi client gửi token (ADR-0005)", "BR-TK-17 'chặn mọi chức năng khác'"),
        ("A5", "Khách tự sửa họ tên, SĐT, ảnh ở module customer; identity chỉ sửa hồ sơ nhân viên", "BR-TK-15: với khách, họ tên và SĐT là thông tin của hồ sơ khách"),
        ("A6", "`verificationMethod` khi sửa email hộ / liên kết tại quầy chỉ có `ID_CARD_IN_PERSON`", "BR-TK-16: audit chỉ ghi phương thức xác minh, docs chỉ nêu CCCD"),
        ("A7", "Gửi lại OTP quên mật khẩu = gọi lại `POST /auth/password/forgot` (cùng quota BR-TK-07)", "Tránh endpoint thứ hai lộ trạng thái tài khoản"),
        ("A8", "BR-TK-04 'gửi thất bại thì báo lỗi': đăng ký trả 201 khi đã ghi `notification_outbox`; gửi thất bại do ST20 thử lại, người dùng gửi lại bằng `POST /auth/register/resend-otp`", "06-module-contracts §1: `NotificationApi.enqueue` chỉ ghi outbox; convention 07 §7.2 cấm gửi trực tiếp trong use case"),
    ],
    questions=[
        ("Q1", "Thời hạn phiên đăng nhập bao lâu, có thêm vào [CFG] không, có cần refresh token cho app không?", "Đã chốt (ADR-0003): hạn tuyệt đối `session.ttl_hours` [CFG], mặc định 12 giờ (1–72), không gia hạn, không refresh token; `exp` của token = `expiresAt`", "Docs không quy định (A1)"),
        ("Q2", "Lỗi `BR-TK-09` (khóa tạm, kèm giờ thử lại) cho biết email tồn tại, lệch với tinh thần BR-TK-10. Chấp nhận như BR-TK-09 yêu cầu?", "Đã chốt (ADR-0019): BR-TK-08/09/11 chỉ trả khi mật khẩu đúng; mật khẩu sai luôn 401 chung, nên người không biết mật khẩu không phân biệt được email có tồn tại, đang khóa hay chưa xác thực", "BR-TK-09 vs BR-TK-10"),
    ],
)

# ---------------- parameters
m.param("StaffId", "id", "path", "int64|accountId của nhân viên")
m.param("AccountId", "id", "path", "int64")
m.param("CustomerId", "customerId", "path", "int64")
m.param("ConfigKey", "key", "path", "string|Ví dụ otp.ttl_minutes")
m.param("TemplateCode", "code", "path", "string|Ví dụ VACCINE_REMINDER")

ROLES = "CUSTOMER,ADMIN,SUPER_MANAGER,BRANCH_MANAGER,RECEPTIONIST,VET,CARETAKER"
STAFF_ROLES = "SUPER_MANAGER,BRANCH_MANAGER,RECEPTIONIST,VET,CARETAKER"

# ---------------- schemas
m.schema("Role", None, enum=ROLES.split(","), desc="Role cố định (04 §1)")
m.schema("AccountStatus", None, enum=["PENDING", "ACTIVE", "DISABLED"], desc="03 #1; khóa là cờ isLocked riêng")
m.schema("RegisterRequest", {
    "email": "email|Định danh duy nhất, lưu chữ thường (BR-TK-01)",
    "password": "string|≥ 8 ký tự [CFG], có chữ và số, ≤ 72 byte UTF-8 (BR-TK-03, ADR-0009)",
    "fullName": "string:100|Họ tên của hồ sơ online (BR-KH-01)",
    "phone": "phone?|Không bắt buộc, không kiểm trùng (BR-TK-01, v16)",
    "isAdult": "bool|Phải true (BR-TK-02)",
    "termsAccepted": "bool|Phải true (BR-TK-02)",
})
m.schema("RegistrationResponse", {
    "accountId": "int64", "email": "email", "status": "enum:PENDING",
    "otpResendAvailableAt": "datetime|Mốc được gửi lại OTP (BR-TK-07)",
})
m.schema("VerifyOtpRequest", {"email": "email", "code": "string|6 chữ số (BR-TK-05)"})
m.schema("VerificationResponse", {
    "accountId": "int64", "status": "enum:ACTIVE",
    "linkDecisionPending": "bool|true nếu SĐT trùng hồ sơ tại quầy chưa liên kết → mở màn hình liên kết (BR-TK-19)",
})
m.schema("EmailRequest", {"email": "email"})
m.schema("OtpSentResponse", {
    "resendAvailableAt": "datetime|Mốc được gửi lại (BR-TK-07)",
    "maskedEmail": "string?|Email nhận mã đã che, chỉ có ở luồng liên kết hồ sơ",
})
m.schema("LoginRequest", {"email": "email", "password": "string"})
m.schema("AccountSummary", {
    "id": "int64", "email": "email", "role": "ref:Role", "status": "ref:AccountStatus",
    "isLocked": "bool", "mustChangePassword": "bool",
})
m.schema("LoginResponse", {
    "accessToken": "string|Bearer token gắn với một phiên (A1)",
    "expiresAt": "datetime",
    "account": "ref:AccountSummary",
    "linkDecisionPending": "bool|Khách còn cờ chờ quyết định liên kết thì FE nhắc lại lời đề nghị (BR-TK-19)",
})
m.schema("ResetPasswordRequest", {"email": "email", "code": "string", "newPassword": "string"})
m.schema("ChangePasswordRequest", {"currentPassword": "string", "newPassword": "string"})
m.schema("StaffProfile", {
    "accountId": "int64", "fullName": "string:100", "avatarUrl": "https_url?", "phone": "mobile",
    "branchId": "int64?|null với ADMIN, SUPER_MANAGER (BR-QT-03)",
    "specialty": "string:200?|Chỉ VET (BR-TK-20)", "bio": "string:500?|Chỉ VET, ≤ 500 ký tự [CFG]",
})
m.schema("MeResponse", {
    "account": "ref:AccountSummary",
    "staffProfile": "ref:StaffProfile",
    "customerId": "int64?|Hồ sơ khách gắn tài khoản (chỉ CUSTOMER)",
    "linkDecisionPending": "bool",
}, required=["account", "linkDecisionPending"])
m.schema("UpdateStaffProfileRequest", {
    "fullName": "string:100?|null = giữ; không được rỗng", "avatarUrl": "string:500?|`https://…`; null = giữ; chuỗi rỗng = xóa (ADR-0026)",
    "phone": "mobile?|null = giữ; nhân viên luôn có SĐT, không xóa được (BR-TK-01)",
    "specialty": "string:200?|Chỉ VET; null = giữ; chuỗi rỗng = xóa",
    "bio": "string:500?|Chỉ VET; null = giữ; chuỗi rỗng = xóa; ≤ vet.bio_max_length [CFG], vượt → BR-TK-20",
}, required=[])
m.schema("LinkCandidate", {
    "customerId": "int64", "maskedFullName": "string|Ví dụ Ng*** V** A (BR-TK-19)",
    "hasEmail": "bool|false: không liên kết online được, hướng dẫn ra quầy",
})
m.schema("LinkOtpRequest", {
    "customerId": "int64|Hồ sơ tại quầy chọn từ danh sách gợi ý",
    "phone": "phone?|SĐT đã dùng ở `GET /me/link-candidates`; bỏ trống = SĐT đã khai trên hồ sơ online. Chỉ gửi mã khi `customerId` là ứng viên của SĐT này (ADR-0027)",
})
m.schema("LinkConfirmRequest", {"customerId": "int64", "code": "string"})
m.schema("LinkResult", {"customerId": "int64|Hồ sơ khách mà tài khoản đang gắn sau khi liên kết"})
m.schema("StaffResponse", {
    "accountId": "int64", "email": "email", "phone": "mobile", "fullName": "string:100",
    "role": "ref:Role", "status": "ref:AccountStatus", "isLocked": "bool", "branchId": "int64?",
    "specialty": "string?", "bio": "string?", "mustChangePassword": "bool",
    "online": "bool|Thao tác trong 10 phút [CFG] gần nhất (BR-TN-06)",
})
m.schema("CreateStaffRequest", {
    "email": "email", "phone": "mobile|Bắt buộc với nhân viên (BR-TK-01)", "fullName": "string:100",
    "role": f"enum:{STAFF_ROLES}|Theo phân cấp BR-QT-01",
    "branchId": "int64?|Bắt buộc với BRANCH_MANAGER…CARETAKER; chi nhánh DRAFT/ACTIVE (BR-QT-03)",
})
m.schema("ChangeRoleRequest", {"role": "enum:BRANCH_MANAGER,RECEPTIONIST,VET,CARETAKER|BR-QT-05"})
m.schema("TransferStaffRequest", {"branchId": "int64"})
m.schema("ReactivateStaffRequest", {"branchId": "int64?|Gán lại chi nhánh nếu cần (BR-QT-10)"}, required=[])
m.schema("EmailChangeRequest", {"newEmail": "email"})
m.schema("OtpCodeRequest", {"code": "string"})
m.schema("CustomerEmailChangeRequest", {
    "newEmail": "email",
    "verificationMethod": "enum:ID_CARD_IN_PERSON|Lễ tân đã đối chiếu CCCD với họ tên hồ sơ; không lưu CCCD (BR-TK-16, A6)",
})
m.schema("CounterLinkRequest", {
    "accountEmail": "email|Email tài khoản online của khách",
    "verificationMethod": "enum:ID_CARD_IN_PERSON|BR-TK-19, A6",
})
m.schema("ReasonRequest", {"reason": "string:500|Bắt buộc (BR-QT-11)"})
m.schema("PendingWorkItem", {
    "type": "enum:VISIT_IN_PROGRESS,VISIT_WAITING,CASHIER_SHIFT_OPEN,APPOINTMENT_BOOKED,BOARDING_ACTIVE,ORDER_UNPAID",
    "entityId": "int64", "branchId": "int64",
    "needsReassign": "bool|true với Visit IN_PROGRESS (BR-TN-08)",
})
m.schema("LockResult", {
    "account": "ref:AccountSummary",
    "pendingWork": "array:ref:PendingWorkItem|Quy trình dở dang hiển thị sau khi khóa (BR-QT-11)",
})
m.schema("SystemConfig", {
    "key": "string", "value": "string", "valueType": "enum:INT,DECIMAL,BOOL,TIME",
    "minValue": "string?", "maxValue": "string?", "unit": "string?", "description": "string",
})
m.schema("UpdateConfigRequest", {"value": "string|Phải nằm trong [minValue, maxValue] (BR-QT-13)"})
m.schema("NotificationTemplate", {
    "code": "string", "channel": "enum:EMAIL,IN_APP", "subject": "string?", "body": "string",
    "allowedVars": "array:string", "requiredVars": "array:string",
    "isDefault": "bool|Nội dung đang trùng bản mặc định",
})
m.schema("UpdateTemplateRequest", {"subject": "string:255?", "body": "string|Chỉ dùng biến trong allowedVars, đủ requiredVars (BR-QT-14)"},
         required=["body"])
m.schema("AuditLogEntry", {
    "id": "int64", "actorAccountId": "int64?", "actorEmail": "string?", "action": "string",
    "entityType": "string?", "entityId": "int64?", "beforeData": "object?", "afterData": "object?",
    "reason": "string?", "ipAddress": "string?", "createdAt": "datetime",
})
m.schema("PublicVet", {
    "accountId": "int64", "fullName": "string", "avatarUrl": "https_url?", "specialty": "string?", "bio": "string?",
    "branchId": "int64", "branchName": "string|Tên chi nhánh ACTIVE (BR-CK-01)",
})

# ---------------- operations
OTP_ERR = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy"
m.op("post", "/auth/register", "registerAccount", "Đăng ký tài khoản", "Auth", "UC01",
     "BR-TK-01, 02, 03, 04, 05, 07, BR-KH-01", "A01", "Public", "— → PENDING (Tài khoản#1); tạo hồ sơ online",
     body="RegisterRequest", resp="RegistrationResponse", status=201, public=True, errors=(400, 409),
     err_desc={400: "`BR-TK-01` email đã được sử dụng · `BR-TK-02` chưa xác nhận ≥ 18 tuổi / điều khoản · `BR-TK-03` mật khẩu yếu hoặc quá 72 byte · `BR-TK-07` email đã nhận quá 5 mã OTP/giờ [CFG]",
               409: "`CONCURRENCY_CONFLICT` hai request đăng ký cùng email cùng lúc"},
     notes="Gửi OTP_REGISTER qua outbox (A8).")
m.op("post", "/auth/register/verify", "verifyRegistration", "Xác thực OTP đăng ký", "Auth", "UC02",
     "BR-TK-05, 06, 19", "A01", "Public", "PENDING → ACTIVE (Tài khoản#2)",
     body="VerifyOtpRequest", resp="VerificationResponse", public=True, errors=(400, 404),
     err_desc={400: OTP_ERR, 404: "Không có tài khoản PENDING với email này"}, notes="Không tự đăng nhập (A2).")
m.op("post", "/auth/register/resend-otp", "resendRegistrationOtp", "Gửi lại OTP đăng ký", "Auth", "UC02",
     "BR-TK-04, 05, 07", "A01", "Public", "—", body="EmailRequest", resp="OtpSentResponse", public=True,
     errors=(400, 404), err_desc={400: "`BR-TK-07` chưa đủ 60 giây hoặc vượt 5 mã/giờ", 404: "Không có tài khoản PENDING với email này"},
     notes="Mã cũ cùng mục đích mất hiệu lực (BR-TK-05).")
m.op("post", "/auth/login", "login", "Đăng nhập", "Auth", "UC03",
     "BR-TK-08, 09, 10, 11, 17, BR-QT-15", "A02–A08", "Public", "—", body="LoginRequest", resp="LoginResponse",
     public=True, errors=(400, 401),
     err_desc={400: "Chỉ khi mật khẩu đúng (ADR-0019): `BR-TK-08` tài khoản chưa xác thực → FE chuyển màn OTP · `BR-TK-09` đang khóa tạm, message có giờ thử lại (`HH:mm dd/MM/yyyy`) · `BR-TK-11` tài khoản bị khóa / vô hiệu hóa",
               401: "Sai email hoặc mật khẩu — thông điệp chung \"Email hoặc mật khẩu không đúng\" (BR-TK-10); sai lần thứ 5 trong 15 phút [CFG] kích hoạt ST01 (khóa tạm + email cảnh báo); sai trong lúc khóa không đếm"},
     notes="Ghi audit thành công và thất bại (ADR-0019). `account.mustChangePassword = true` vẫn đăng nhập được (BR-TK-17). Request sai hình thức (400 `VALIDATION_FAILED` / `MALFORMED_REQUEST`) không tính là một lần đăng nhập sai.")
m.op("post", "/auth/logout", "logout", "Đăng xuất", "Auth", "UC03", "BR-TK-11, BR-TN-06", "A02–A08",
     "Người đang đăng nhập", "—", status=204, errors=(401,), notes="Hủy phiên hiện tại; nhân viên chuyển offline ngay (ADR-0021). Body không được đọc. Gọi lại sau khi thành công → 401. Request cùng token đang chạy vẫn hoàn tất (§3.1 của `00-method`). Không ghi audit (ADR-0019).")
m.op("post", "/auth/password/forgot", "forgotPassword", "Quên mật khẩu — gửi OTP", "Auth", "UC04",
     "BR-TK-04, 07, 10, 12", "A02–A08", "Public", "—", body="EmailRequest", resp="OtpSentResponse", status=202,
     public=True, errors=(400,), err_desc={400: "`VALIDATION_FAILED` email trống / sai định dạng / quá 255 ký tự"},
     notes="Luôn trả 202 cùng nội dung (BR-TK-10), kể cả email không tồn tại, tài khoản không đủ điều kiện BR-TK-12 (`PENDING`, bị khóa, `DISABLED` — không gửi mã), đã chạm quota BR-TK-07 (không gửi mã, `resendAvailableAt` vẫn = now + 60 giây [CFG]). Tài khoản đang khóa tạm BR-TK-09 vẫn nhận mã. Gửi lại = gọi lại endpoint này (A7). ADR-0023.")
m.op("post", "/auth/password/reset", "resetPassword", "Đặt lại mật khẩu bằng OTP", "Auth", "UC04",
     "BR-TK-03, 05, 06, 13", "A02–A08", "Public", "—", body="ResetPasswordRequest", status=204, public=True,
     errors=(400,), err_desc={400: OTP_ERR + " — cũng là lỗi cho email không có tài khoản, tài khoản không đủ điều kiện BR-TK-12 hoặc chưa có mã (BR-TK-10) · `BR-TK-03` mật khẩu mới không hợp lệ (kiểm trước mã, không tiêu lượt nhập) hoặc trùng mật khẩu hiện tại (chỉ kiểm khi mã đúng; mã vẫn dùng lại được)"},
     notes="Thứ tự kiểm: hình thức → chính sách mật khẩu BR-TK-03 → mã BR-TK-05/06 → trùng mật khẩu hiện tại BR-TK-03. Thành công: hủy mọi phiên, gỡ khóa tạm, gỡ bắt đổi mật khẩu lần đầu BR-TK-17, chuyển offline (BR-TN-06), gửi email PASSWORD_CHANGED (BR-TK-13); không audit. ADR-0023.")

m.op("get", "/me", "getMe", "Thông tin người đang đăng nhập", "Me", "UC06", "BR-TK-17, 19", "A02–A08",
     "Người đang đăng nhập", resp="MeResponse", errors=(401,))
m.op("post", "/me/password", "changePassword", "Đổi mật khẩu", "Me", "UC05", "BR-TK-03, 09, 14, 17", "A02–A08",
     "Người đang đăng nhập", body="ChangePasswordRequest", status=204, errors=(400, 401),
     err_desc={400: "`BR-TK-14` mật khẩu hiện tại sai (tính vào bộ đếm BR-TK-09; lần chạm ngưỡng khóa tạm đăng nhập, message kèm giờ mở khóa) · `BR-TK-03` mật khẩu mới không hợp lệ hoặc trùng mật khẩu cũ (chỉ kiểm khi mật khẩu hiện tại đúng) · `BR-TK-09` đang khóa tạm, message có giờ thử lại · `BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa"},
     notes="Đăng xuất mọi phiên khác, phiên đang dùng giữ nguyên; gỡ `mustChangePassword`; xóa bộ đếm đăng nhập sai. Không gửi email. Request sai hình thức (400 `VALIDATION_FAILED` / `MALFORMED_REQUEST`) không tính là một lần nhập sai (ADR-0022).")
m.op("patch", "/me/staff-profile", "updateMyStaffProfile", "Sửa hồ sơ nhân viên của tôi", "Me", "UC06",
     "BR-TK-15, 20", "A03–A08", "Nhân viên đang đăng nhập; specialty, bio chỉ VET", body="UpdateStaffProfileRequest",
     resp="StaffProfile", errors=(400, 401, 403),
     err_desc={400: "`BR-TK-15` body có `email` (email không tự sửa được) · `BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa · `BR-TK-20` mô tả ngắn vượt 500 ký tự [CFG] · `BR-TK-17` chưa đổi mật khẩu lần đầu · `VALIDATION_FAILED` họ tên / SĐT rỗng, SĐT khác 10 số, `avatarUrl` không phải `https://`",
               403: "`ACCESS_DENIED` tài khoản khách · `ACCESS_DENIED_SCOPE_MISMATCH` không phải VET mà gửi `specialty` / `bio`"},
     notes="null = giữ nguyên; chuỗi rỗng = xóa (`avatarUrl`, `specialty`, `bio`). Thứ tự kiểm: hình thức → BR-TK-15 → BR-TK-11 → 403 → BR-TK-20 (chỉ khi gửi `bio`). Field khác (`role`, `branchId`…) bị bỏ qua. Không audit (ADR-0026).")

m.op("get", "/me/link-candidates", "listLinkCandidates", "Danh sách hồ sơ tại quầy có thể liên kết", "Profile link",
     "UC07", "BR-TK-19, BR-KH-10", "A02", "Khách đang đăng nhập", resp="array:LinkCandidate",
     query=["phone=phone|SĐT của hồ sơ tại quầy (UC07 khi không có cờ); bỏ trống = SĐT đã khai trên hồ sơ online"],
     errors=(400, 401, 403), err_desc={400: "`BR-TK-19` hồ sơ online đã phát sinh dữ liệu, hoặc tài khoản đã liên kết hồ sơ tại quầy · `VALIDATION_FAILED` `phone` sai định dạng"},
     notes="Chỉ trả hồ sơ COUNTER chưa liên kết, họ tên đã che; không có thì mảng rỗng (ADR-0027).")
m.op("post", "/me/link/otp", "sendLinkOtp", "Gửi OTP tới email hồ sơ tại quầy", "Profile link", "UC07",
     "BR-TK-04, 07, 19", "A02", "Khách đang đăng nhập", body="LinkOtpRequest", resp="OtpSentResponse",
     errors=(400, 401, 403, 404), err_desc={400: "`BR-TK-19` hồ sơ không có email (ra quầy), hồ sơ online đã có dữ liệu hoặc tài khoản đã liên kết · `BR-TK-11` tài khoản vừa bị khóa · `BR-TK-07` gửi quá nhanh / quá 5 mã/giờ",
                                          404: "Hồ sơ không tồn tại hoặc không phải ứng viên của SĐT (một thông báo chung)"},
     notes="Thứ tự kiểm: BR-TK-11 → 404 → BR-TK-19 → BR-TK-07. Mã gửi tới email ghi trong hồ sơ tại quầy (BR-TK-04); gọi lại = gửi lại (BR-TK-07, mã mới hủy mã liên kết cũ của tài khoản). `maskedEmail` là email đó đã che (ADR-0027).")
m.op("post", "/me/link/confirm", "confirmLink", "Xác nhận liên kết", "Profile link", "UC07",
     "BR-TK-05, 06, 19", "A02", "Khách đang đăng nhập", body="LinkConfirmRequest", resp="LinkResult",
     errors=(400, 401, 403, 404), err_desc={400: OTP_ERR + " (sai mã vẫn được tính) · `BR-TK-19` hồ sơ online đã phát sinh dữ liệu / tài khoản đã liên kết / hồ sơ tại quầy vừa được liên kết hoặc đổi email — mã vẫn dùng lại được · `BR-TK-11` tài khoản vừa bị khóa",
                                          404: "Hồ sơ không tồn tại"},
     notes="Gắn tài khoản vào hồ sơ tại quầy, xóa hồ sơ online cùng sổ địa chỉ của nó, email hồ sơ theo email tài khoản, SĐT giữ nguyên, ghi audit `CUSTOMER_PROFILE_LINKED` (ADR-0027). Sau lỗi mạng, đọc lại `GET /me` để biết đã liên kết chưa.")
m.op("post", "/me/link/decline", "declineLink", "Chọn \"Không phải tôi\"", "Profile link", "UC07", "BR-TK-19",
     "A02", "Khách đang đăng nhập", status=204, errors=(400, 401, 403, 409),
     err_desc={400: "`BR-TK-11` tài khoản vừa bị khóa", 409: "`INVALID_STATE_TRANSITION` hồ sơ không còn cờ chờ quyết định"},
     notes="Không audit (ADR-0027).")

m.op("get", "/staff", "listStaff", "Danh sách nhân viên", "Staff", "UC08", "BR-QT-01, 07", "A03, A04, A05",
     "ADMIN: SUPER_MANAGER · SUPER_MANAGER: A05–A08 · BRANCH_MANAGER: A06–A08 chi nhánh mình", resp="StaffResponse",
     page=True, errors=(400, 401, 403),
     err_desc={400: "tham số sai kiểu · `branchId` ≤ 0",
               403: "BRANCH_MANAGER gửi `branchId` của chi nhánh khác (không ghi audit)"},
     query=["branchId=int64", f"role=enum:{STAFF_ROLES}", "status=enum:ACTIVE,DISABLED", "q=string|Tìm theo họ tên, email", "page", "size"],
     notes="Lọc `role` ngoài phạm vi xem → trang rỗng; ADMIN gửi `branchId` → rỗng (SUPER_MANAGER không thuộc chi nhánh). Không gửi `status` = ACTIVE và DISABLED. `q` không phân biệt hoa thường. ADR-0030.")
m.op("post", "/staff", "createStaff", "Tạo tài khoản nhân viên", "Staff", "UC08", "BR-QT-01, 02, 03, BR-TK-01, 17",
     "A03, A04, A05", "Theo BR-QT-01", "— → ACTIVE (Tài khoản#4)", body="CreateStaffRequest", resp="StaffResponse",
     status=201, errors=(400, 401, 403, 409),
     err_desc={400: "`BR-TK-01` email đã dùng · `BR-QT-03` thiếu chi nhánh, chi nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId`",
               403: "`BR-QT-01` chức vụ / chi nhánh ngoài quyền (ghi audit)",
               409: "hai request cùng email chạy song song"},
     notes="Sinh mật khẩu ngẫu nhiên gửi STAFF_TEMP_PASSWORD; người tạo không thấy mật khẩu; mustChangePassword = true. BRANCH_MANAGER không gửi `branchId` = chi nhánh của mình. Sau lỗi mạng, đọc lại `GET /staff?q=<email>` để biết đã tạo chưa (gửi lại nhận `BR-TK-01`). ADR-0030.")
m.op("get", "/staff/{id}", "getStaff", "Chi tiết nhân viên", "Staff", "UC08", "BR-QT-01, 07", "A03, A04, A05",
     "Trong phạm vi như listStaff", resp="StaffResponse", path_params=["StaffId"], errors=(400, 401, 403, 404),
     err_desc={403: "ngoài phạm vi xem (không ghi audit)", 404: "không có, hoặc là tài khoản khách (cùng message)"})
m.op("post", "/staff/{id}/change-role", "changeStaffRole", "Đổi chức vụ", "Staff", "UC08", "BR-QT-04, 05", "A04",
     "Chỉ SUPER_MANAGER", body="ChangeRoleRequest", resp="StaffResponse", path_params=["StaffId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-QT-04` là BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE",
               403: "người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit)",
               404: "không có, hoặc là tài khoản khách",
               409: "chi nhánh của nhân viên vừa bị đổi bởi thao tác khác"},
     notes="Trùng chức vụ hiện tại → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. VET đổi sang chức vụ khác thì specialty, bio bị xóa. Không hủy phiên: request kế tiếp của nhân viên dùng chức vụ mới. ADR-0031.")
m.op("post", "/staff/{id}/transfer", "transferStaff", "Điều chuyển chi nhánh", "Staff", "UC08", "BR-QT-03, 04, 06, 08",
     "A04", "Chỉ SUPER_MANAGER", body="TransferStaffRequest", resp="StaffResponse", path_params=["StaffId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN (message liệt kê) · `BR-QT-04` BRANCH_MANAGER cuối cùng · `BR-QT-03` chi nhánh không hợp lệ · `branchId` số thực → `MALFORMED_REQUEST`",
               403: "người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit)",
               404: "không có, hoặc là tài khoản khách",
               409: "chi nhánh của nhân viên vừa bị đổi bởi thao tác khác"},
     notes="Đã ở chi nhánh đích → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. Không hủy phiên: request kế tiếp của nhân viên dùng chi nhánh mới. ADR-0031, ADR-0032.")
m.op("post", "/staff/{id}/disable", "disableStaff", "Vô hiệu hóa nhân viên", "Staff", "UC08",
     "BR-QT-04, 07, 08, 09, 12, BR-TK-11", "A03, A04, A05", "Theo BR-QT-07; không tự vô hiệu hóa mình",
     "ACTIVE → DISABLED (Tài khoản#5)", resp="StaffResponse", path_params=["StaffId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-QT-12` đang bị khóa, cần mở khóa trước · `BR-QT-04` BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE · `BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN (message liệt kê) · `id` sai kiểu",
               403: "ngoài phạm vi BR-QT-07 hoặc chính mình (ghi audit, message chung)",
               404: "không có, hoặc là tài khoản khách",
               409: "đã DISABLED (`INVALID_STATE_TRANSITION`) · chi nhánh của nhân viên vừa bị đổi bởi thao tác khác (`CONCURRENCY_CONFLICT`)"},
     notes="Không body. Hủy mọi phiên, nhân viên chuyển offline ngay (BR-QT-09, BR-TK-11, BR-TN-06); không gửi email; dữ liệu lịch sử giữ nguyên. SUPER_MANAGER không kiểm BR-QT-08. Tạm thời A05–A08 trả 500 tới khi visit / sales có bản thật (D016, 21/10). Bấm lại sau lỗi mạng nhận 409 → đọc lại `GET /staff/{id}`. ADR-0033.")
m.op("post", "/staff/{id}/reactivate", "reactivateStaff", "Kích hoạt lại nhân viên", "Staff", "UC08",
     "BR-QT-02, 03, 10, 12", "A03, A04, A05", "Cùng phạm vi với vô hiệu hóa", "DISABLED → ACTIVE (Tài khoản#6)",
     body="ReactivateStaffRequest", resp="StaffResponse", path_params=["StaffId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-QT-10` đang bị khóa, cần mở khóa trước · `BR-QT-03` chi nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId` · `branchId` ≤ 0 (`VALIDATION_FAILED`) / số thực (`MALFORMED_REQUEST`)",
               403: "ngoài phạm vi BR-QT-10 (như vô hiệu hóa), chính mình, hoặc BRANCH_MANAGER gửi chi nhánh khác chi nhánh mình (ghi audit, message chung)",
               404: "không có, hoặc là tài khoản khách",
               409: "đang ACTIVE (`INVALID_STATE_TRANSITION`)"},
     notes="Body không bắt buộc: thiếu / `{}` / `branchId` null = giữ chi nhánh hiện tại. Sinh mật khẩu tạm mới gửi STAFF_TEMP_PASSWORD (response không có mật khẩu), mustChangePassword = true, xóa bộ đếm đăng nhập sai và khóa tạm, hủy mọi phiên. Bấm lại sau lỗi mạng nhận 409 (không gửi thêm mật khẩu) → đọc lại `GET /staff/{id}`. ADR-0033, ADR-0032.")
m.op("post", "/staff/{id}/resend-initial-password", "resendInitialPassword", "Gửi lại mật khẩu ban đầu", "Staff",
     "UC08", "BR-QT-02", "A03, A04, A05", "Người có quyền tạo tài khoản đó (BR-QT-01)", status=204,
     path_params=["StaffId"], errors=(401, 403, 404, 409),
     err_desc={403: "`BR-QT-01` ngoài phân cấp hoặc chính mình (ghi audit)", 404: "không có, hoặc là tài khoản khách",
               409: "Nhân viên đã đổi mật khẩu lần đầu, hoặc tài khoản đang DISABLED"},
     notes="Sinh mật khẩu mới, mã cũ hết hiệu lực. Chỉ khi ACTIVE và chưa đổi mật khẩu lần đầu (đang bị khóa vẫn được). Hủy mọi phiên, xóa bộ đếm đăng nhập sai và khóa tạm, nhân viên chuyển offline; ghi audit. ADR-0030.")
m.op("post", "/staff/{id}/email-change", "startStaffEmailChange", "Sửa email nhân viên — gửi OTP tới email mới",
     "Staff", "UC08", "BR-TK-04, 07, 16, BR-QT-01", "A03, A04, A05", "Cấp trên theo BR-QT-01",
     body="EmailChangeRequest", resp="OtpSentResponse", path_params=["StaffId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-TK-01` email mới đã dùng (kể cả tài khoản PENDING) hoặc trùng email hiện tại · `BR-TK-07` gửi lại trong 60 giây / quá 5 mã/giờ [CFG] tới email đó · `VALIDATION_FAILED` `newEmail` trống / sai định dạng / quá 255 ký tự",
               403: "ngoài phân cấp BR-QT-01 hoặc chính mình (ghi audit, message chung)",
               404: "không có, hoặc là tài khoản khách (cùng message)"},
     notes="Email mới được chuẩn hóa chữ thường. Mã gửi tới email mới (BR-TK-04), `maskedEmail` = null. Được với mọi trạng thái (DISABLED, đang bị khóa). Mỗi nhân viên tối đa một mã đổi email còn hiệu lực: gửi mã mới (kể cả tới email khác) hủy mã cũ của nhân viên đó; tài khoản khác xin mã tới cùng email không hủy được mã này. Không ghi audit ở bước này. Gửi lại = gọi lại endpoint. ADR-0034.")
m.op("post", "/staff/{id}/email-change/confirm", "confirmStaffEmailChange", "Sửa email nhân viên — xác nhận OTP",
     "Staff", "UC08", "BR-TK-01, 05, 06, 16", "A03, A04, A05", "Cấp trên theo BR-QT-01", body="OtpCodeRequest",
     resp="StaffResponse", path_params=["StaffId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: OTP_ERR + " (sai mã vẫn được tính) · không còn mã đang mở → `BR-TK-05` · `BR-TK-01` email mới vừa được tài khoản khác dùng (mã vẫn dùng lại được) · `VALIDATION_FAILED` `code` trống / không phải 1–8 chữ số",
               403: "ngoài phân cấp BR-QT-01 hoặc chính mình — kiểm trước khi tra mã và lại dưới khóa (ghi audit, message chung)",
               404: "không có, hoặc là tài khoản khách (cùng message)",
               409: "`CONCURRENCY_CONFLICT` hai lần xác nhận khác nhân viên cùng một email mới chạy song song"},
     notes="Email mới lấy từ mã đang mở của nhân viên. Đổi email, gửi EMAIL_CHANGED_NOTICE tới email cũ và mới, ghi audit `STAFF_EMAIL_CHANGED` (before/after email, `verificationMethod = EMAIL_CODE`). Trạng thái, khóa, mật khẩu không đổi; không hủy phiên. Bấm lại sau lỗi mạng nhận `BR-TK-05` → đọc lại `GET /staff/{id}`. Nhân viên chưa đổi mật khẩu lần đầu (`mustChangePassword`) thì gọi tiếp `POST /staff/{id}/resend-initial-password` để mật khẩu tạm đi tới email mới. ADR-0034.")

m.op("post", "/customers/{customerId}/account-email-change", "startCustomerEmailChange",
     "Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP", "Customer account", "UC22",
     "BR-TK-01, 04, 07, 16, BR-KH-10", "A06", "Lễ tân", body="CustomerEmailChangeRequest", resp="OtpSentResponse",
     path_params=["CustomerId"], errors=(400, 401, 403, 404),
     err_desc={400: "Hồ sơ chưa gắn tài khoản · `BR-TK-01` email mới đã dùng · `BR-TK-07` gửi quá nhanh"})
m.op("post", "/customers/{customerId}/account-email-change/confirm", "confirmCustomerEmailChange",
     "Sửa email tài khoản khách — xác nhận OTP", "Customer account", "UC22", "BR-TK-05, 06, 16", "A06", "Lễ tân",
     body="OtpCodeRequest", status=204, path_params=["CustomerId"], errors=(400, 401, 403, 404), err_desc={400: OTP_ERR},
     notes="Ghi audit kèm phương thức xác minh; gửi EMAIL_CHANGED_NOTICE tới email cũ và mới.")
m.op("post", "/customers/{customerId}/link-account", "linkAccountAtCounter", "Liên kết hồ sơ tại quầy với tài khoản",
     "Customer account", "UC22", "BR-TK-16, 19", "A06", "Lễ tân", body="CounterLinkRequest", resp="LinkResult",
     path_params=["CustomerId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-TK-19` hồ sơ không phải COUNTER / đã liên kết / hồ sơ online đã phát sinh dữ liệu"},
     notes="Không cần OTP: lễ tân đã đối chiếu CCCD. Ghi audit.")

m.op("get", "/accounts", "listAccounts", "Tìm tài khoản (khách và nhân viên)", "Accounts", "UC09", "BR-QT-11",
     "A03", "Chỉ ADMIN", resp="AccountSummary", page=True, errors=(401, 403),
     query=["q=string|Tìm theo email", f"role=enum:{ROLES}", "isLocked=bool", "page", "size"])
m.op("post", "/accounts/{id}/lock", "lockAccount", "Khóa tài khoản", "Accounts", "UC09",
     "BR-QT-04, 11, 12, BR-TN-08, BR-TK-11", "A03", "Chỉ ADMIN; không khóa chính mình", "is_locked false → true (Tài khoản#7)",
     body="ReasonRequest", resp="LockResult", path_params=["AccountId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Thiếu lý do (`VALIDATION_FAILED`) · `BR-QT-11` khóa chính mình",
               409: "`INVALID_STATE_TRANSITION` đã bị khóa · `CONCURRENCY_CONFLICT` chức vụ / chi nhánh của người bị khóa vừa đổi"},
     notes="Mọi role, mọi trạng thái (kể cả PENDING, DISABLED) trừ chính mình; status giữ nguyên (BR-QT-12). Hủy mọi phiên; pendingWork sắp theo thứ tự enum rồi entityId (Visit IN_PROGRESS → needsReassign); phát AccountLockedEvent; thông báo trong app theo BR-QT-11 (nhân viên A06–A08: BRANCH_MANAGER chi nhánh, không còn thì SUPER_MANAGER; BRANCH_MANAGER: SUPER_MANAGER, quản lý cuối của chi nhánh ACTIVE → LAST_BRANCH_MANAGER_LOCKED_APP; khách: lễ tân các chi nhánh có việc dở — 06 Q2); ghi audit kèm lý do (docs/adr/0035).")
m.op("post", "/accounts/{id}/unlock", "unlockAccount", "Mở khóa tài khoản", "Accounts", "UC09", "BR-QT-11, 12",
     "A03", "Chỉ ADMIN", "is_locked true → false (Tài khoản#8)", body="ReasonRequest", resp="AccountSummary",
     path_params=["AccountId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Thiếu lý do (`VALIDATION_FAILED`)", 409: "`INVALID_STATE_TRANSITION` không bị khóa"},
     notes="status giữ nguyên giá trị trước khi khóa (BR-QT-12); giữ lý do khóa gần nhất; không mở phiên, không thông báo; ghi audit kèm lý do (docs/adr/0035).")

m.op("get", "/system-configs", "listSystemConfigs", "Danh sách tham số [CFG]", "Configuration", "UC10", "BR-QT-13",
     "A03", "Chỉ ADMIN", resp="array:SystemConfig", errors=(401, 403))
m.op("put", "/system-configs/{key}", "updateSystemConfig", "Sửa tham số", "Configuration", "UC10", "BR-QT-13, 15",
     "A03", "Chỉ ADMIN", body="UpdateConfigRequest", resp="SystemConfig", path_params=["ConfigKey"],
     errors=(400, 401, 403, 404), err_desc={400: "`BR-QT-13` ngoài khoảng hợp lệ, message ghi khoảng cho phép"},
     notes="Chỉ áp dụng cho giao dịch tạo sau; ghi audit.")
m.op("get", "/notification-templates", "listNotificationTemplates", "Danh sách mẫu thông báo", "Configuration",
     "UC10", "BR-QT-14", "A03", "Chỉ ADMIN", resp="array:NotificationTemplate", errors=(401, 403))
m.op("put", "/notification-templates/{code}", "updateNotificationTemplate", "Sửa nội dung mẫu", "Configuration",
     "UC10", "BR-QT-14, 15", "A03", "Chỉ ADMIN", body="UpdateTemplateRequest", resp="NotificationTemplate",
     path_params=["TemplateCode"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-QT-14` dùng biến không tồn tại hoặc thiếu biến bắt buộc (message chỉ rõ biến)"},
     notes="Không có endpoint thêm / xóa mẫu.")
m.op("post", "/notification-templates/{code}/restore-default", "restoreNotificationTemplate", "Khôi phục mẫu mặc định",
     "Configuration", "UC10", "BR-QT-14, 15", "A03", "Chỉ ADMIN", resp="NotificationTemplate",
     path_params=["TemplateCode"], errors=(401, 403, 404))

m.op("get", "/audit-logs", "listAuditLogs", "Xem nhật ký audit", "Audit", "UC11", "BR-QT-15, 16", "A03", "Chỉ ADMIN",
     resp="AuditLogEntry", page=True, errors=(400, 401, 403),
     query=["actorAccountId=int64", "action=string", "entityType=string", "entityId=int64", "from=datetime",
            "to=datetime", "page", "size"],
     notes="Chỉ đọc; không có endpoint sửa / xóa (BR-QT-16).")

m.op("get", "/public/vets", "listPublicVets", "Đội ngũ bác sĩ", "Public", "UC15", "BR-TK-20, BR-CK-01", "A01, A02",
     "Public", resp="array:PublicVet", public=True, errors=(400,), query=["branchId=int64"],
     notes="Chỉ VET ACTIVE, không khóa (khóa tạm vẫn hiện), thuộc chi nhánh ACTIVE; `branchId` không phải chi nhánh ACTIVE → mảng rỗng. Sắp theo họ tên (ADR-0026).")
