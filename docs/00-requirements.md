# Pet Care Ecosystem — Requirements Specification(FROZEN)

> **Vai trò của tài liệu này:** 
>
> Tài liệu này là **SOURCE OF TRUTH** cho toàn bộ `docs/01-06`.
>
> **Quy ước đọc:** 

---

## 1. Purpose

Tài liệu này **không** phải là: kế hoạch triển khai, phân rã sprint/phase, thiết kế kỹ thuật, hay tài liệu quản lý dự án.

---

## 2. Scope

### 2.1. Trong phạm vi (In Scope)

Hệ thống Pet Care Ecosystem phiên bản mục tiêu (target specification, không phải hiện trạng code — xem `docs/architecture/system-overview.md` §7 để biết hiện trạng triển khai thực tế: 0/25 module đã code) bao gồm **25 module nghiệp vụ**, tổ chức thành 3 nhóm domain:

| Nhóm | Module |
|---|---|
| **Core Domains** | Appointment & Scheduling (06), Walk-in & Queue (07), Veterinary/Clinical (09), Vaccination (10), Grooming (11), Order Management v1 In-Store (14), Billing & Invoice (15), Payment (16), Refund (17), Package (20), Consent & Privacy (22) |
| **Supporting Domains** | Organization & Store (03), Customer & Pet (04), Service & Product Catalog (05), Workforce (08), Inventory & Warehouse (12), Procurement (13), Promotion & Voucher (18), Membership & Loyalty (19), Incident (21) |
| **Generic/Infrastructure Domains** | Authentication & OTP (01), Identity & Access Management (02), Notification (23), Reporting & Analytics (24), Audit (25) |

Phạm vi vận hành: **Multi-tenancy 5 tầng** (`PLATFORM` → `ORGANIZATION` → `STORE`/`WAREHOUSE` → `CUSTOMER`), 9 Canonical Roles, cách ly dữ liệu 100% giữa các Organization.

### 2.2. Ngoài phạm vi (Out of Scope)

Xem chi tiết và căn cứ tại §9.
---

## 3. Actors / Stakeholders


| Actor | System Role Code | Scope | Vai trò chính |
|---|---|---|---|
| Platform Admin | `SUPER_ADMIN` | `PLATFORM` | Quản trị toàn nền tảng SaaS, vòng đời Organization (Tenant), cấu hình toàn cục |
| Organization Admin | `ORGANIZATION_ADMIN` | `ORGANIZATION` | Quản lý chuỗi Store, Warehouse trung tâm, danh mục gốc, chính sách tổ chức |
| Store Manager | `STORE_MANAGER` | `STORE` | Vận hành Store, phân ca, phê duyệt Maker-Checker (Refund/StockTransfer/PurchaseRequest/InventoryAdjustment), xử lý Incident |
| Receptionist | `RECEPTIONIST` | `STORE` | Tiếp đón, đặt lịch, check-in/out, hàng đợi Walk-in, POS, hóa đơn, thu tiền mặt |
| Veterinarian | `VETERINARIAN` | `STORE` (+ Cross-Store Consent) | Khám, chẩn đoán, kê đơn, tiêm phòng, EMR, Emergency Override |
| Groomer | `GROOMER` | `STORE` | Kiểm tra thể trạng, thực hiện grooming, đề xuất dịch vụ phát sinh |
| Inventory Staff | `INVENTORY_STAFF` | `STORE` / `WAREHOUSE` | Nhập/xuất/kiểm kê/chuyển kho, tiếp nhận hàng NCC |
| Finance Staff | `FINANCE_STAFF` | `ORGANIZATION` / `STORE` | Phát hành hóa đơn, đối soát, xử lý hoàn tiền qua cổng/thủ công |
| Customer | `CUSTOMER` | `CUSTOMER` | Chủ thú cưng: tự phục vụ đặt lịch, mua hàng, thanh toán, ủy quyền chăm sóc |
| Caregiver | *(không có Role riêng — tài khoản `CUSTOMER` được ủy quyền)* | Pet Delegation | Thay mặt chủ Pet thực hiện hành vi trong phạm vi `PetCaregiverDelegation.status = ACTIVE` |
| System | *(Automated Runtime, không phải User)* | Cross-cutting | Cron job hết hạn (OTP/Hold/Package/Consent/Delegation), webhook thanh toán, notification engine |

---

## 4. Business Goals

| Mã | Business Goal |
|---|---|
| BG-01 | Cho phép một doanh nghiệp (Organization) vận hành **nhiều chi nhánh** (Store) cung cấp dịch vụ chăm sóc thú cưng (khám bệnh, tiêm phòng, grooming) và bán lẻ sản phẩm, với dữ liệu cách ly 100% giữa các Organization khác nhau. |
| BG-02 | Số hóa toàn bộ vòng đời phục vụ khách hàng: đặt lịch trước (Appointment) hoặc phục vụ trực tiếp (Walk-in) → khám/tiêm/grooming → thanh toán → hoàn tất, trên **cùng một mô hình dữ liệu thống nhất** bất kể kênh tiếp nhận. |
| BG-03 | Đảm bảo tính toàn vẹn tài chính: mọi giao dịch tiền (Payment/Invoice/Refund/Order) phải nhất quán, có khả năng đối soát, và **không đánh mất bằng chứng lịch sử tất toán** kể cả khi có hoàn tiền một phần. |
| BG-04 | Hỗ trợ mô hình kinh doanh dịch vụ trả trước (Package) và khách hàng thân thiết (Membership/Loyalty) để tăng giữ chân khách hàng. |
| BG-05 | Quản lý chuỗi cung ứng nội bộ (Inventory/Warehouse/Procurement) với kiểm soát rủi ro gian lận qua cơ chế **Maker-Checker** cho các nghiệp vụ tài chính/tài sản rủi ro cao. |
| BG-06 | Bảo vệ dữ liệu y tế nhạy cảm của thú cưng (EMR) theo nguyên tắc **Consent-based access** liên chi nhánh, có lối thoát khẩn cấp (Break-Glass) cho tình huống nguy kịch, luôn có audit trail. |
| BG-07 | Cung cấp minh bạch vận hành và tài chính qua báo cáo phân quyền theo Scope, và nhật ký kiểm toán bất biến phục vụ hậu kiểm/pháp lý. |
| BG-08 | Tự động hóa các tác vụ có tính thời gian (OTP hết hạn, giữ chỗ hết hạn, gói/điểm thưởng/ủy quyền hết hạn) để giảm thao tác thủ công và tránh khóa tài nguyên treo (deadlock tài nguyên/tồn kho). |

---

## 5. Functional Requirements

### 5.1 Authentication & Account Lifecycle (REQ-ACC)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-ACC-001 | Hệ thống phải cho phép Customer tự đăng ký tài khoản; tài khoản mới khởi tạo ở trạng thái chưa xác thực và không được cấp quyền hoạt động đầy đủ cho đến khi xác thực OTP thành công. | Customer |
| REQ-ACC-002 | Hệ thống phải gửi một mã xác thực một-lần (OTP) có thời hạn hiệu lực xác định (300 giây) ngay sau khi Customer đăng ký. | System |
| REQ-ACC-003 | Hệ thống phải từ chối xác thực OTP đã hết hạn hiệu lực hoặc không khớp mã đã phát hành. | System |
| REQ-ACC-004 | Hệ thống phải cho phép người dùng yêu cầu gửi lại OTP, đồng thời vô hiệu hóa ngay mã OTP đang hoạt động trước đó và áp dụng thời gian chờ tối thiểu giữa hai lần gửi liên tiếp. | Customer |
| REQ-ACC-005 | Hệ thống phải giới hạn số lần gửi lại OTP và số lần nhập sai OTP trong một khung thời gian xác định; khi vượt ngưỡng, phiên xác thực phải bị tạm khóa. | System |
| REQ-ACC-006 | Hệ thống phải cho phép Platform Admin hoặc Organization Admin khởi tạo tài khoản nhân viên trực tiếp ở trạng thái hoạt động đầy đủ kèm mật khẩu tạm thời, không yêu cầu xác thực OTP đăng ký, và bắt buộc nhân viên đổi mật khẩu ở lần đăng nhập đầu tiên. | Platform Admin, Organization Admin |
| REQ-ACC-007 | Hệ thống chỉ được cho phép đăng nhập khi thông tin xác thực hợp lệ **và** tài khoản đang ở trạng thái hoạt động đầy đủ; mọi yêu cầu đăng nhập từ tài khoản chưa xác thực, đang bị khóa, hoặc đã vô hiệu hóa phải bị từ chối. | Customer, Staff |
| REQ-ACC-008 | Hệ thống phải tự động chuyển tài khoản sang trạng thái tạm khóa sau một số lần đăng nhập sai liên tiếp xác định (5 lần), nhằm ngăn chặn tấn công dò mật khẩu. | System |
| REQ-ACC-009 | Sau khi logout, access credential tương ứng của phiên đó không được tiếp tục được hệ thống chấp nhận cho bất kỳ yêu cầu nào khác. | Customer, Staff |
| REQ-ACC-010 | Khi tài khoản chuyển sang trạng thái bị khóa hoặc bị vô hiệu hóa, hệ thống phải thu hồi tức thì toàn bộ phiên làm việc đang hoạt động của tài khoản đó; các access credential đã cấp trước đó không còn được chấp nhận. | System |
| REQ-ACC-011 | Hệ thống phải cho phép người có thẩm quyền (Platform Admin/Organization Admin) chủ động mở khóa một tài khoản đang bị khóa bất kỳ lúc nào, bất kể lý do khóa. Riêng tài khoản bị khóa tự động do 5 lần đăng nhập sai liên tiếp, hệ thống phải tự động mở khóa lại sau đúng 15 phút mà không cần thao tác thủ công; tài khoản bị khóa do quyết định chủ động của người có thẩm quyền thì KHÔNG được tự động mở khóa — chỉ mở khi người có thẩm quyền chủ động thực hiện. | Platform Admin, Organization Admin, System |
| REQ-ACC-012 | Hệ thống phải cho phép người có thẩm quyền vô hiệu hóa vĩnh viễn tài khoản nhân viên nghỉ việc, và tái kích hoạt lại khi cần với lý do giải trình bắt buộc được ghi nhận. | Platform Admin, Organization Admin |
| REQ-ACC-013 | Hệ thống phải tự động quét và đánh dấu hết hiệu lực mọi OTP chưa xác thực khi quá thời hạn quy định. | System |
| REQ-ACC-014 | Hệ thống phải từ chối đăng ký/khởi tạo tài khoản mới nếu số điện thoại hoặc email đã tồn tại trên **toàn nền tảng** (không giới hạn theo Organization); một Account là danh tính đăng nhập duy nhất, có thể được dùng để tương tác với nhiều Organization độc lập khác nhau. | Customer, Platform Admin, Organization Admin, System |

### 5.2 Identity & Access Management (REQ-IAM)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-IAM-001 | Hệ thống phải giới hạn mọi thao tác của một Actor trong đúng phạm vi quản lý (Scope) mà Actor đó được gán; thao tác ngoài phạm vi phải bị từ chối. | System |
| REQ-IAM-002 | Hệ thống phải hỗ trợ đúng 9 vai trò chuẩn hóa (Canonical Roles), mỗi vai trò gắn với đúng một hoặc nhiều Scope quản trị xác định trước. | System |
| REQ-IAM-003 | Việc gán quyền cho người dùng không được vượt quá phạm vi thẩm quyền của người thực hiện gán quyền (không cho phép leo thang đặc quyền — privilege escalation). | Platform Admin, Organization Admin, Store Manager |
| REQ-IAM-004 | Hệ thống phải cho phép Platform Admin quản lý người dùng/vai trò/quyền trên toàn nền tảng; Organization Admin trong phạm vi Organization của mình; Store Manager phân công vận hành trong phạm vi Store của mình. | Platform Admin, Organization Admin, Store Manager |
| REQ-IAM-005 | Customer phải có toàn quyền xem/cập nhật hồ sơ cá nhân của chính mình; nhân viên không được tự ý thay đổi vai trò hoặc tự nâng quyền hạn của bản thân. | Customer, Staff |
| REQ-IAM-006 | Receptionist chỉ được tạo/tra cứu/cập nhật hồ sơ khách hàng trong phạm vi phục vụ tại quầy, không được thay thế quyền tự quản lý hồ sơ của Customer. | Receptionist |

### 5.3 Organization & Store Management (REQ-ORG)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-ORG-001 | Hệ thống phải cho phép Organization Admin tạo và cập nhật thông tin Organization; mỗi Store phải thuộc về đúng một Organization cha duy nhất. | Organization Admin |
| REQ-ORG-002 | Dữ liệu vận hành (đơn hàng, lịch hẹn, bệnh án, tồn kho) giữa các Organization độc lập phải được cách ly hoàn toàn 100%. | System |
| REQ-ORG-003 | Store mới khởi tạo phải ở trạng thái bản nháp (chưa nhận khách), và chỉ được chuyển sang hoạt động khi đã cấu hình đầy đủ: giờ hoạt động hợp lệ, ít nhất một tài nguyên cơ sở vật chất khả dụng, và danh mục dịch vụ khả dụng. | Organization Admin |
| REQ-ORG-004 | Store chỉ được tiếp nhận lịch hẹn, xếp hàng Walk-in, tạo đơn hàng và thực hiện dịch vụ khi đang ở trạng thái hoạt động (`ACTIVE`). | System |
| REQ-ORG-005 | Hệ thống phải cho phép tạm ngưng (Suspend) và ngừng kích hoạt (Deactivate) một Store; khi đó Store phải tự động ngừng nhận lịch hẹn mới, Walk-in mới và đơn hàng mới, trong khi các giao dịch dở dang vẫn được xử lý/hủy theo chính sách. | Organization Admin |
| REQ-ORG-006 | Hệ thống chỉ cho phép lưu trữ vĩnh viễn (Archive) một Store khi đồng thời thỏa mãn: không còn đơn hàng/lịch hẹn đang mở, tồn kho thực tế bằng 0, và không còn công nợ/yêu cầu hoàn tiền đang xử lý. Sau khi Archive, toàn bộ dữ liệu cấu hình con của Store (giờ hoạt động, tài nguyên, danh mục dịch vụ/sản phẩm override, lịch làm việc) phải trở thành bất biến chỉ đọc; hệ thống không được xóa cứng dữ liệu này. | Organization Admin |
| REQ-ORG-007 | Chính sách cấp Organization phải áp dụng nhất quán cho toàn bộ Store trực thuộc; chính sách cấp Store chỉ có hiệu lực cục bộ và không được mâu thuẫn với chính sách Organization. | Organization Admin, Store Manager |
| REQ-ORG-008 | Mọi lịch hẹn và tiếp đón Walk-in phải nằm trong khung giờ hoạt động đã cấu hình của Store; yêu cầu ngoài giờ mở cửa phải bị từ chối. | System |
| REQ-ORG-009 | Store Manager phải cấu hình được danh mục tài nguyên cơ sở vật chất (phòng khám, bàn grooming, thiết bị) và công suất tối đa của từng loại, làm căn cứ kiểm tra xung đột lịch. | Store Manager |

### 5.4 Customer & Pet Management (REQ-PET)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-PET-001 | Mỗi Pet phải thuộc quyền sở hữu chính thức của đúng một Customer (Primary Owner) tại một thời điểm. | System |
| REQ-PET-002 | Chỉ Primary Owner mới được thay đổi thông tin định danh cốt lõi của Pet (loài, giống, ngày sinh, giới tính) hoặc chuyển giao quyền sở hữu. | Customer |
| REQ-PET-003 | Hệ thống phải cho phép Customer hoặc Receptionist thêm/cập nhật hồ sơ Pet, gắn liền với Customer sở hữu hợp lệ. | Customer, Receptionist |
| REQ-PET-004 | Receptionist phải tra cứu được Customer/Pet theo các tiêu chí xác thực (SĐT, CCCD, mã Pet, mã Customer) trong phạm vi phục vụ tại Store. | Receptionist |
| REQ-PET-005 | Chỉ Primary Owner của Pet mới được gửi lời mời ủy quyền chăm sóc (Caregiver) hoặc thu hồi ủy quyền đã cấp. | Customer |
| REQ-PET-006 | Lời mời ủy quyền phải có thời hạn hiệu lực xác định (7 ngày); nếu không được xác nhận trong thời hạn, hệ thống phải tự động chuyển lời mời sang trạng thái hết hạn. | System |
| REQ-PET-007 | Caregiver được mời phải có khả năng chấp nhận hoặc từ chối lời mời; chấp nhận kích hoạt quan hệ ủy quyền có hiệu lực ngay lập tức. | Caregiver |
| REQ-PET-008 | Quan hệ ủy quyền phải có thời hạn hiệu lực xác định; khi hết hạn, hệ thống phải tự động chấm dứt mọi quyền hạn ủy quyền. | System |
| REQ-PET-009 | Khi Primary Owner thu hồi ủy quyền, quan hệ ủy quyền và mọi quyền thao tác của Caregiver đối với Pet đó phải bị chấm dứt ngay lập tức. | Customer |
| REQ-PET-010 | Caregiver đang có ủy quyền hiệu lực chỉ được thực hiện các hành vi nằm trong phạm vi được ủy quyền (xem Pet, đặt lịch, đưa Pet đi khám/spa, check-in/out, xem lịch sử y tế được chia sẻ); mọi thao tác ngoài phạm vi phải bị từ chối. | Caregiver |
| REQ-PET-011 | Caregiver tuyệt đối không được mời thêm Caregiver khác, không được chuyển nhượng quyền sở hữu Pet, và không được sửa đổi thông tin định danh cốt lõi của Pet. | System |
| REQ-PET-012 | Khi Pet được chuyển sang chủ sở hữu chính mới, toàn bộ quan hệ ủy quyền Caregiver đang hiệu lực hoặc đang chờ của chủ sở hữu cũ phải tự động bị thu hồi. | System |
| REQ-PET-013 | **[MỚI — Phase 3 Audit]** Hồ sơ Pet phải có trạng thái vòng đời xác định (đang hoạt động, đã qua đời, hoặc đã rời khỏi hệ sinh thái); chỉ Pet đang hoạt động mới được tạo lịch hẹn/đơn hàng/gói dịch vụ mới. Chuyển chủ sở hữu nội bộ (giữa hai Customer trong cùng hệ thống) không làm thay đổi trạng thái này. | Customer, Receptionist |

### 5.5 Service & Product Catalog (REQ-CAT)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-CAT-001 | Danh mục Product và Service gốc phải do Organization Admin sở hữu và quản lý toàn quyền ở phạm vi Organization, dùng chung cho toàn bộ Store trực thuộc. | Organization Admin |
| REQ-CAT-002 | Mỗi Product phải thuộc về đúng một Organization; không được xóa cứng Product đã phát sinh giao dịch tồn kho/đơn hàng, chỉ được vô hiệu hóa. | Organization Admin |
| REQ-CAT-003 | Store Manager phải cấu hình được tính khả dụng của từng Service tại Store của mình, độc lập với trạng thái gốc ở cấp Organization. | Store Manager |
| REQ-CAT-004 | Store Manager phải cấu hình được giá bán Service/Product áp dụng riêng tại Store; nếu Store chưa cấu hình giá riêng, giá gốc của Organization phải được áp dụng mặc định. | Store Manager |
| REQ-CAT-005 | Khi Store chuyển sang trạng thái hoạt động, hệ thống phải tự động khởi tạo bản ghi giá/khả dụng riêng cho toàn bộ Product/Service của Organization tại Store đó, kế thừa giá trị mặc định từ danh mục gốc. | System |
| REQ-CAT-006 | Customer chỉ được xem và tra cứu Product/Service đang ở trạng thái khả dụng và được Store công bố. | Customer |

### 5.6 Appointment & Scheduling (REQ-APT)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-APT-001 | Hệ thống phải cho phép giữ chỗ tạm thời (Hold) một khung giờ hẹn với thời hạn hiệu lực chính xác 15 phút, trong thời gian đó khung giờ không thể bị chọn bởi người dùng khác. | Customer, Receptionist |
| REQ-APT-002 | Người dùng phải chủ động giải phóng được khung giờ đã giữ chỗ trước khi hết hạn. | Customer, Receptionist |
| REQ-APT-003 | Nếu quá 15 phút giữ chỗ mà chưa hoàn tất xác nhận/thanh toán, hệ thống phải tự động giải phóng khung giờ đó. | System |
| REQ-APT-004 | Lịch hẹn chỉ được tạo hợp lệ khi đồng thời thỏa mãn: Store đang hoạt động, khung giờ nằm trong giờ mở cửa, dịch vụ đang khả dụng tại Store, người đặt có quyền quản trị hợp lệ trên hồ sơ Pet, và không có xung đột về nhân sự/tài nguyên/lịch Pet. | Customer, Receptionist, Caregiver |
| REQ-APT-005 | Hệ thống phải ngăn một Pet có từ hai lịch hẹn/lượt phục vụ trực tiếp trở lên đang ở trạng thái hoạt động (đã đặt/đã xác nhận/đã check-in/đang phục vụ) giao thoa thời gian, trên toàn bộ Store **trong cùng một Organization** (không phân biệt chi nhánh nội bộ; không kiểm tra xuyên Organization khác). | System |
| REQ-APT-006 | Lịch hẹn chỉ được xác nhận/tiếp nhận khi cả nhân sự phụ trách không trùng lịch **và** tài nguyên cơ sở vật chất chuyên dụng còn công suất trống trong toàn bộ khung giờ thực hiện dịch vụ. | System |
| REQ-APT-007 | Hệ thống phải cho phép đổi lịch hẹn theo cơ chế nguyên tử: khóa giữ khung giờ mới thành công trước, chỉ khi đó mới giải phóng khung giờ cũ; nếu khung giờ mới không khả dụng, toàn bộ thao tác phải được hủy bỏ và khung giờ cũ được giữ nguyên. | Customer, Receptionist |
| REQ-APT-008 | Hệ thống phải cho phép hủy lịch hẹn trước khi bắt đầu phục vụ, yêu cầu bắt buộc nhập lý do hủy, và tự động giải phóng tài nguyên/lịch nhân sự đã giữ. | Customer, Receptionist |
| REQ-APT-009 | Hệ thống phải hỗ trợ đầy đủ chuỗi trạng thái tiếp nhận phục vụ: tiếp nhận check-in → bắt đầu phục vụ chuyên môn → hoàn tất/check-out; không được phép bỏ qua bước bắt đầu phục vụ chuyên môn để chuyển thẳng từ check-in sang hoàn tất. | Receptionist, Veterinarian, Groomer |
| REQ-APT-010 | Khi hoàn tất phục vụ, hệ thống phải giải phóng tài nguyên phòng/bàn đã sử dụng và chuyển tiếp sang lập hóa đơn thanh toán. | Receptionist |
| REQ-APT-011 | Hệ thống phải cho phép Veterinarian/Groomer dừng khẩn cấp một phiên đang phục vụ kèm lý do bắt buộc; khi đó tài nguyên phải được giải phóng ngay, một hồ sơ sự cố phải được tự động tạo, và yêu cầu hoàn tiền cho phần dịch vụ chưa thực hiện phải được phát sinh. | Veterinarian, Groomer |
| REQ-APT-012 | Nếu khách hàng không đến trong thời gian ân hạn sau giờ hẹn, hệ thống (tự động hoặc qua Receptionist) phải đánh dấu vắng mặt, giải phóng ngay tài nguyên phòng/bàn và lịch nhân sự, đồng thời xử lý khấu trừ tiền cọc theo chính sách Store. | Receptionist, System |
| REQ-APT-013 | Hệ thống phải tự động gửi thông báo nhắc lịch hẹn cho khách hàng trước thời điểm hẹn theo cấu hình của Store. | System |
| REQ-APT-014 | Customer/Caregiver chỉ được xem lịch hẹn của Pet thuộc quyền quản lý của mình; Staff/Store Manager được xem toàn bộ lịch hẹn trong phạm vi Store quản lý. | System |
| REQ-APT-015 | Store Manager phải phân công được nhân sự vào ca trực/lịch hẹn, và điều phối lại lịch khi cần, chỉ với nhân sự thuộc Store và không trùng lịch làm việc/nghỉ phép. | Store Manager |
| REQ-APT-016 | Receptionist phải cập nhật được chi tiết lịch hẹn (dịch vụ, ghi chú thể trạng/triệu chứng) trước khi khách hàng check-in. | Receptionist |

### 5.7 Walk-in & Queue Management (REQ-QUE)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-QUE-001 | Hệ thống phải cấp số thứ tự hàng đợi theo cơ chế FIFO cho khách vãng lai, riêng theo từng Store và từng ngày. | Customer, Receptionist |
| REQ-QUE-002 | Thứ tự phục vụ Walk-in phải tuân thủ FIFO, ngoại trừ ca cấp cứu y tế được ưu tiên tuyệt đối bỏ qua hàng đợi thông thường. | System |
| REQ-QUE-003 | Hệ thống phải hỗ trợ gọi số thứ tự tiếp theo và tự động gửi thông báo tới lượt cho khách hàng. | Receptionist, Veterinarian, Groomer |
| REQ-QUE-004 | Hệ thống phải cho phép hủy lượt chờ khi phiếu còn đang chờ hoặc vừa được gọi; sau khi hủy phải tự động điều chỉnh thứ tự các phiếu phía sau. | Customer, Receptionist |
| REQ-QUE-005 | Khi bắt đầu phục vụ một lượt Walk-in, hệ thống phải tự động khởi tạo một bản ghi lịch hẹn nội bộ (kênh Walk-in) ở trạng thái đang phục vụ, cho phép các module khám bệnh/tiêm phòng/grooming/hóa đơn vận hành trên cùng một mô hình dữ liệu với lịch hẹn thông thường. | System |
| REQ-QUE-006 | Khách hàng không có mặt sau ba lần gọi số (với khoảng giãn cách quy định) phải bị đánh dấu vắng mặt, tự động hủy lượt và chuyển gọi số tiếp theo. | Receptionist |
| REQ-QUE-007 | Khi hoàn tất lượt phục vụ Walk-in, bản ghi lịch hẹn nội bộ liên kết phải được đồng bộ hoàn tất, tài nguyên phòng/bàn được giải phóng, và bàn giao cho lập hóa đơn thanh toán. | Veterinarian, Groomer |
| REQ-QUE-008 | Store Manager phải giám sát được hàng đợi và điều phối phân luồng (mở thêm bàn/phòng, điều động nhân sự, ưu tiên đặc biệt). | Store Manager |

### 5.8 Workforce Management (REQ-WFM)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-WFM-001 | Nhân viên chỉ được phân công làm việc và cập nhật hồ sơ vận hành tại Store mà nhân viên được Organization cấp quyền hoạt động. | Organization Admin, Store Manager |
| REQ-WFM-002 | Lịch làm việc của nhân viên không được phép trùng lặp với ca làm việc khác hoặc thời gian nghỉ phép đã phê duyệt; mọi thao tác xếp ca/phân công giao thoa với nghỉ phép hợp lệ phải bị từ chối. | Store Manager |
| REQ-WFM-003 | Khi nhân viên vắng mặt đột xuất, hệ thống phải đánh dấu vắng mặt trên lịch làm việc, cảnh báo các lịch hẹn/ca trực bị ảnh hưởng, và khóa phân công công việc mới cho nhân viên đó trong khung giờ vắng mặt. | Store Manager |
| REQ-WFM-004 | Phân công nhân sự thay thế chỉ hợp lệ khi nhân sự thay thế cùng Store (hoặc điều động hợp lệ), có vai trò/năng lực chuyên môn tương đương, và không trùng lịch làm việc/nghỉ phép. | Store Manager |
| REQ-WFM-005 | Đơn xin nghỉ phép chỉ có hiệu lực sau khi được Store Manager phê duyệt; khi phê duyệt, hệ thống phải tự động khóa khung giờ nghỉ trên lịch làm việc. | Store Manager |
| REQ-WFM-006 | Mỗi nhân viên chuyên môn chỉ được xem lịch làm việc của chính mình; Store Manager được xem/điều phối toàn bộ lịch làm việc trong phạm vi Store. | Veterinarian, Groomer, Receptionist, Store Manager |
| REQ-WFM-007 | Organization Admin phải quản lý được danh sách nhân sự trên toàn Organization (khởi tạo, tra cứu tổng hợp), tách biệt với việc phân công vận hành cụ thể tại từng Store (REQ-WFM-001). | Organization Admin |

### 5.9 Veterinary / Clinical Management (REQ-CLN)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-CLN-001 | Chỉ Veterinarian có phân công chuyên môn tại Store mới được mở phiên khám, khởi tạo/cập nhật bệnh án, ghi nhận triệu chứng và kết quả kiểm tra. | Veterinarian |
| REQ-CLN-002 | Kết luận chẩn đoán phải gắn liền với một phiên khám lâm sàng cụ thể và làm cơ sở thiết lập phác đồ điều trị. | Veterinarian |
| REQ-CLN-003 | Đơn thuốc phải thuộc về bệnh án của Pet tương ứng, do Veterinarian ký duyệt, và xác định rõ thuốc, liều lượng, đường dùng, tần suất, thời gian điều trị. | Veterinarian |
| REQ-CLN-004 | Veterinarian phải lập được lịch tái khám cho Pet sau đợt điều trị; hệ thống phải tự động ghi vào lịch theo dõi và gửi nhắc trước ngày tái khám. | Veterinarian, System |
| REQ-CLN-005 | Veterinarian tại Store khác trong cùng Organization muốn xem lịch sử y tế của Pet phải gửi yêu cầu và chỉ được cấp quyền sau khi chủ Pet xác thực đồng ý (OTP có hiệu lực 5 phút, kích hoạt quyền xem tối đa 24 giờ); chủ Pet có quyền chủ động thu hồi quyền này bất kỳ lúc nào. | Veterinarian, Customer |
| REQ-CLN-006 | Trong tình huống cấp cứu đe dọa tính mạng Pet không kịp xác thực đồng ý, Veterinarian phải được phép kích hoạt truy cập khẩn cấp kèm lý do lâm sàng bắt buộc; hành động này phải tự động tạo một hồ sơ sự cố y tế và gửi cảnh báo tới chủ Pet và Store Manager. | Veterinarian |
| REQ-CLN-007 | Customer và Caregiver được ủy quyền chỉ được xem bệnh án/lịch sử y tế của Pet mà mình có quyền sở hữu hoặc ủy quyền hợp lệ. | Customer, Caregiver |
| REQ-CLN-008 | Hệ thống phải phân định rõ dịch vụ tiêm phòng định kỳ thông thường (không bắt buộc tạo bệnh án đầy đủ) với tiêm vaccine trong phác đồ điều trị bệnh lý (bắt buộc gắn với bệnh án và phác đồ điều trị). | Veterinarian |
| REQ-CLN-009 | Phác đồ điều trị phải luôn thuộc về đúng một bệnh án của Pet tương ứng, làm căn cứ chỉ định dược phẩm/xét nghiệm/thủ thuật bổ sung. | Veterinarian |
| REQ-CLN-010 | Bệnh án phải tự động chuyển sang trạng thái "đã chốt phiên" khi lịch hẹn liên kết hoàn tất; trong 24 giờ tiếp theo vẫn cho phép chỉnh sửa nhưng phải ghi vết kiểm toán từng lần sửa; sau 24 giờ, bệnh án phải trở thành bất biến tuyệt đối, không còn khả năng chỉnh sửa dưới bất kỳ hình thức nào. | Veterinarian, System |

### 5.10 Vaccination Management (REQ-VAC)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-VAC-001 | Mỗi hồ sơ tiêm chủng phải gắn với một Pet cụ thể; Customer phải xem được toàn bộ lịch sử và kế hoạch tiêm chủng của Pet mình sở hữu/ủy quyền. | Customer |
| REQ-VAC-002 | Vaccine đã hết hạn sử dụng hoặc bị thu hồi tuyệt đối không được phép sử dụng để tiêm phòng. | System |
| REQ-VAC-003 | Trước khi tiêm phòng, Veterinarian phải kiểm tra lịch sử tiêm chủng, phác đồ khuyến nghị theo độ tuổi/loài, và khám sàng lọc thể trạng đảm bảo đủ điều kiện sức khỏe tiêm phòng. | Veterinarian |
| REQ-VAC-004 | Tại thời điểm tiêm, hệ thống phải xác thực thời gian thực: lô vaccine đang khả dụng, còn hạn sử dụng, còn tồn kho khả dụng (≥1), và tương thích với loài/thể trạng Pet trước khi cho phép ghi nhận mũi tiêm. | System |
| REQ-VAC-005 | Sau khi tiêm thành công, hệ thống phải ghi nhận đầy đủ thông tin lô vaccine vào hồ sơ tiêm chủng của Pet, tự động trừ 1 liều khỏi tồn kho khả dụng của lô, và tự động lập lịch tiêm nhắc lại tiếp theo kèm gửi thông báo nhắc. | System |
| REQ-VAC-006 | Khi một liều vaccine bị hư hỏng trước khi tiêm thành công, hệ thống phải yêu cầu lập phiếu điều chỉnh tồn kho với phê duyệt kép (Maker-Checker) trước khi được trừ chính thức khỏi tồn kho sổ sách; tuyệt đối không được ghi nhận mũi tiêm cho liều đã hỏng. | Veterinarian, Inventory Staff, Store Manager |
| REQ-VAC-007 | Inventory Staff phải quản lý được danh mục vaccine và thông tin lô vaccine (số lô, nhà sản xuất, hạn sử dụng), độc lập với thao tác tiêm phòng thực tế. | Inventory Staff |

### 5.11 Grooming Management (REQ-GRM)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-GRM-001 | Dịch vụ Grooming chỉ được thực hiện cho Pet có lịch hẹn hợp lệ hoặc yêu cầu trực tiếp đã tiếp nhận tại Store; hệ thống phải cho phép hủy trước khi bắt đầu phục vụ. | Customer, Receptionist |
| REQ-GRM-002 | Trước khi bắt đầu grooming, Groomer bắt buộc phải kiểm tra thể trạng Pet và ghi nhận biên bản; nếu phát hiện rủi ro an toàn/lây nhiễm, hệ thống phải cho phép từ chối phục vụ. | Groomer |
| REQ-GRM-003 | Khi phát sinh nhu cầu dịch vụ thêm trong lúc đang phục vụ, hệ thống phải tạm dừng công đoạn hiện tại và gửi yêu cầu phê duyệt kèm báo giá tới khách hàng trước khi thực hiện dịch vụ phát sinh. | Groomer |
| REQ-GRM-004 | Nếu khách hàng đồng ý dịch vụ phát sinh, hệ thống phải tạo một hóa đơn độc lập cho phần phát sinh (không sửa đổi hóa đơn gốc đã lập/đã thanh toán) và tiếp tục thực hiện dịch vụ. | System |
| REQ-GRM-005 | Nếu khách hàng từ chối dịch vụ phát sinh, Groomer phải chỉ hoàn thành các hạng mục trong gói dịch vụ cơ bản ban đầu. | Groomer |
| REQ-GRM-006 | Groomer phải cập nhật được tiến độ và kết quả từng công đoạn để chủ Pet theo dõi. | Groomer |
| REQ-GRM-007 | Khi hoàn thành, hệ thống phải giải phóng tài nguyên bàn Grooming và bàn giao cho tiếp tân thực hiện check-out; phiên đã hoàn thành phải là bất biến (không thể sửa đổi hoặc thêm dịch vụ). | Groomer, Receptionist |
| REQ-GRM-008 | Khi phát sinh sự cố an toàn nghiêm trọng giữa chừng, Groomer/Store Manager phải dừng khẩn cấp phiên phục vụ kèm lý do bắt buộc; hệ thống phải tự động tạo hồ sơ sự cố, giải phóng tài nguyên, và phát sinh yêu cầu hoàn tiền cho phần chưa thực hiện. | Groomer, Store Manager |

### 5.12 Inventory & Warehouse Management (REQ-INV)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-INV-001 | Tồn kho phải được theo dõi và hạch toán riêng biệt theo từng Store/Warehouse; dữ liệu tồn kho giữa các Organization phải cách ly tuyệt đối. | System |
| REQ-INV-002 | Phiếu điều chỉnh tồn kho phải gắn với Store/Warehouse phát sinh chênh lệch và bắt buộc ghi rõ lý do hợp lệ (hư hỏng, hết hạn, mất mát, sai lệch kiểm kê, sai lệch vận chuyển). | Inventory Staff |
| REQ-INV-003 | Mọi phiếu điều chỉnh tồn kho phải được phê duyệt trước khi cập nhật số lượng sổ sách; người tạo phiếu tuyệt đối không được là người phê duyệt phiếu đó (Maker-Checker). | Inventory Staff, Store Manager |
| REQ-INV-004 | Hệ thống phải cho phép tạo yêu cầu chuyển kho liên chi nhánh trong cùng một Organization, xác định rõ điểm xuất, điểm nhận, hàng hóa, số lượng, thông tin lô/hạn dùng. | Inventory Staff |
| REQ-INV-005 | Không được xuất bán hoặc chuyển kho vượt quá số lượng tồn kho khả dụng; hàng hóa đã hết hạn sử dụng hoặc thuộc diện thu hồi tuyệt đối không được xuất bán/điều chuyển. | System |
| REQ-INV-006 | Yêu cầu chuyển kho phải được phê duyệt bởi người khác với người tạo yêu cầu (Maker-Checker) trước khi xuất hàng vận chuyển. | Store Manager |
| REQ-INV-007 | Khi xuất kho chuyển hàng, số lượng phải được trừ khỏi tồn khả dụng tại điểm xuất và chuyển sang trạng thái trung gian "đang vận chuyển"; hàng đang vận chuyển không được tính vào tồn khả dụng của điểm nhận cho đến khi xác nhận nhập kho thực tế. | Inventory Staff |
| REQ-INV-008 | Khi tiếp nhận hàng chuyển kho, hệ thống phải phân biệt trường hợp nguyên vẹn (tăng ngay tồn khả dụng điểm nhận) với trường hợp phát hiện sai lệch (thiếu/thừa/hư hỏng, phải cách ly phần hư hại và lập phiếu điều chỉnh cân bằng trước khi đóng phiếu chuyển kho). | Inventory Staff, Store Manager |
| REQ-INV-009 | Hệ thống phải quản lý hàng hóa/thuốc/vaccine theo số lô và hạn sử dụng, ưu tiên xuất kho theo nguyên tắc hết hạn trước - xuất trước (FEFO). | System |
| REQ-INV-010 | Hệ thống phải tự động cảnh báo khi tồn kho khả dụng xuống dưới ngưỡng an toàn, và khi lô hàng sắp hết hạn sử dụng trước một khoảng thời gian cấu hình. | System |
| REQ-INV-011 | Organization Admin phải quản lý được Warehouse trung tâm; Warehouse tiếp nhận hàng từ nhà cung cấp hoặc điều chuyển nội bộ để tái bổ sung cho các Store. | Organization Admin, Inventory Staff |
| REQ-INV-012 | Inventory Staff phải ghi nhận được việc nhập hàng vào kho tại Store/Warehouse, làm tăng tồn kho thực tế và khả dụng. | Inventory Staff |
| REQ-INV-013 | Inventory Staff phải hủy được yêu cầu chuyển kho khi phiếu còn ở trạng thái chưa được phê duyệt. | Inventory Staff |

### 5.13 Procurement Management (REQ-PRC)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-PRC-001 | Yêu cầu mua hàng nội bộ phải xuất phát từ Store/Warehouse có nhu cầu, xác định rõ danh mục, số lượng, đơn giá dự kiến, nhà cung cấp khuyến nghị. | Inventory Staff |
| REQ-PRC-002 | Yêu cầu mua hàng phải được phê duyệt hoặc từ chối (kèm lý do) bởi người khác với người tạo yêu cầu (Maker-Checker) trước khi tạo đơn đặt hàng nhà cung cấp. | Store Manager, Organization Admin |
| REQ-PRC-003 | Yêu cầu mua hàng chỉ được phép hủy khi chưa được phê duyệt. | Inventory Staff |
| REQ-PRC-004 | Đơn đặt hàng nhà cung cấp phải được khởi tạo từ một yêu cầu mua hàng đã duyệt, gắn với nhà cung cấp hợp lệ đang hoạt động. | Inventory Staff |
| REQ-PRC-005 | Khi nhận hàng từ nhà cung cấp, hệ thống phải yêu cầu kiểm tra thực tế (số lượng, tình trạng, hạn dùng) trước khi ghi nhận tiếp nhận; hàng không đạt chuẩn phải bị từ chối tiếp nhận. | Inventory Staff |
| REQ-PRC-006 | Hàng nhận đạt chuẩn phải tự động cập nhật tăng tồn kho thực tế và khả dụng tại đúng nơi nhận, kèm đầy đủ thông tin lô/hạn dùng. | System |
| REQ-PRC-007 | Hệ thống phải hỗ trợ nhận hàng nhiều đợt cho một đơn đặt hàng; nếu nhà cung cấp không thể giao tiếp phần còn thiếu, hệ thống phải cho phép đóng đơn hàng lại và hủy nghĩa vụ nhận số lượng còn thiếu. | Inventory Staff, Store Manager |
| REQ-PRC-008 | Đơn đặt hàng đã kết thúc (đã đóng hoặc đã hủy) phải là bất biến — tuyệt đối không được tiếp nhận thêm hàng hoặc điều chỉnh số lượng. | System |
| REQ-PRC-009 | **[MỚI — Phase 3 Audit]** Organization Admin phải quản lý được danh mục nhà cung cấp (thông tin liên hệ, trạng thái hoạt động) làm căn cứ tạo đơn đặt hàng. | Organization Admin |

### 5.14 Order Management — v1 In-Store Fulfillment (REQ-ORD)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-ORD-001 | Mỗi đơn hàng phải thuộc về một Customer xác định và gắn liền với đúng một Store xử lý/bàn giao. | System |
| REQ-ORD-002 | Sản phẩm trong đơn hàng phải thuộc danh mục đang mở bán tại Store và có tồn kho khả dụng đáp ứng đủ số lượng đặt mua. | System |
| REQ-ORD-003 | Đối với đơn bán lẻ tại quầy POS, thanh toán và bàn giao phải hoàn tất tức thời trong cùng một phiên giao dịch; đối với đơn Online/App, quy trình phải đi qua các bước tuần tự thanh toán → xác nhận → xử lý → sẵn sàng → bàn giao. | System |
| REQ-ORD-004 | Đơn hàng Online/App khi checkout phải tạm giữ tồn kho tương ứng với thời hạn hiệu lực tối đa 15 phút; quá thời hạn chưa thanh toán, hệ thống phải tự động hủy đơn và giải phóng số lượng đã giữ. | System |
| REQ-ORD-005 | Hệ thống phải cho phép khách hàng hủy đơn hàng chưa thanh toán. | Customer |
| REQ-ORD-006 | Hệ thống phải cho phép hủy đơn hàng đã thanh toán trước khi bàn giao, kèm tự động kích hoạt hoàn tiền 100% và hoàn kho. | Store Manager, Receptionist |
| REQ-ORD-007 | Đơn hàng đã hủy phải là trạng thái bất biến — tuyệt đối không được xử lý tiếp, đóng gói, bàn giao hay khôi phục lại. | System |
| REQ-ORD-008 | Đối với đơn đã bàn giao thành công, hệ thống phải phân biệt: đổi trả toàn bộ và hoàn 100% tiền chuyển đơn sang trạng thái kết thúc "đã hoàn trả"; đổi trả một phần giữ nguyên trạng thái "đã bàn giao" và cập nhật lũy kế số tiền đã hoàn. | System |
| REQ-ORD-009 | Hệ thống phải tự động gửi thông báo cho khách hàng tại các mốc chuyển trạng thái quan trọng của đơn hàng. | System |

### 5.15 Billing & Invoice Management (REQ-BIL)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-BIL-001 | Mỗi hóa đơn phải gắn với đúng một Customer và một Store phát sinh giao dịch; mọi mục dịch vụ/sản phẩm trên hóa đơn phải thuộc danh mục hợp lệ đang mở tại Store đó. | Receptionist, Finance Staff |
| REQ-BIL-002 | Tổng tiền hóa đơn phải được tính tự động từ tổng giá trị các mục sau khi trừ chiết khấu hợp lệ; chiết khấu không được vượt quá tổng giá trị hóa đơn. | System |
| REQ-BIL-003 | Hóa đơn bản nháp sau khi hoàn tất phải được phát hành chính thức mới được phép tiếp nhận thanh toán. | Receptionist, Finance Staff |
| REQ-BIL-004 | Hệ thống phải cho phép hủy bản nháp hóa đơn tạo sai (chưa phát hành), và vô hiệu hóa hóa đơn đã phát hành nhưng chưa thanh toán. | Receptionist, Finance Staff |
| REQ-BIL-005 | Hóa đơn đã thanh toán đủ 100% tuyệt đối không được hủy/vô hiệu hóa; mọi yêu cầu hoàn tiền cho hóa đơn đã thanh toán phải đi qua quy trình Hoàn tiền (Refund) độc lập. | System |
| REQ-BIL-006 | Khi phát sinh dịch vụ/phụ phí ngoài dự kiến (ví dụ trong ca Grooming), hệ thống phải phát hành một hóa đơn độc lập cho phần phát sinh, không được ghi đè hay chèn mục vào hóa đơn gốc. | Receptionist, Finance Staff |
| REQ-BIL-007 | Hóa đơn chỉ chuyển sang trạng thái đã tất toán khi tổng các khoản thanh toán thành công tích lũy đạt đủ 100% tổng tiền hóa đơn. | System |
| REQ-BIL-008 | Sau khi hóa đơn đã tất toán, hệ thống phải giữ nguyên trạng thái tất toán đó vĩnh viễn kể cả khi phát sinh hoàn tiền một phần hoặc toàn phần; số tiền hoàn phải được theo dõi lũy kế qua một thuộc tính riêng trên hóa đơn, không được thay đổi trạng thái hóa đơn về chưa thanh toán/đã hoàn/vô hiệu. | System |
| REQ-BIL-009 | Customer phải xem được chi tiết hóa đơn của chính mình. | Customer |
| REQ-BIL-010 | Finance Staff phải thực hiện được đối soát định kỳ giữa hóa đơn, giao dịch thanh toán thực tế và khoản hoàn tiền. | Finance Staff |

### 5.16 Payment Management (REQ-PAY)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-PAY-001 | Mỗi giao dịch thanh toán phải gắn liền với đúng một hóa đơn đích đang ở trạng thái đã phát hành; tổng các khoản thanh toán thành công cho một hóa đơn không được vượt quá tổng số tiền phải thanh toán của hóa đơn đó. | System |
| REQ-PAY-002 | Hệ thống phải hỗ trợ đồng thời hai kênh thanh toán: điện tử qua cổng thanh toán (bất đồng bộ, đi qua các bước chờ xử lý xác nhận) và tiền mặt tại quầy (quyết toán tức thời trong cùng phiên giao dịch). | Customer, Receptionist |
| REQ-PAY-003 | Mọi callback/webhook xác nhận từ cổng thanh toán điện tử phải được xác thực tính toàn vẹn (chữ ký số, khớp mã giao dịch và số tiền) trước khi được chấp nhận; một callback nhận được nhiều lần chỉ được xử lý đúng một lần duy nhất. | System |
| REQ-PAY-004 | Khi một giao dịch thanh toán đạt trạng thái thành công, hệ thống phải tự động kích hoạt tất toán hóa đơn liên quan và các hành vi tiếp nối (xác nhận đơn hàng, kích hoạt gói dịch vụ). | System |
| REQ-PAY-005 | Hệ thống phải cho phép hủy giao dịch thanh toán điện tử đang ở trạng thái chờ/đang xử lý khi khách hàng chủ động hủy hoặc cổng thanh toán phản hồi hết hạn/thất bại; kênh tiền mặt không đi qua cơ chế hủy này vì được quyết toán tức thời. | Customer, System |
| REQ-PAY-006 | Khi phát sinh hoàn tiền, trạng thái giao dịch thanh toán gốc phải phản ánh đúng mức độ hoàn: hoàn một phần chuyển sang "đã hoàn một phần", hoàn đủ 100% chuyển sang "đã hoàn toàn phần". | System |
| REQ-PAY-007 | Finance Staff phải thực hiện được đối soát giao dịch thanh toán với sổ phụ ngân hàng/báo cáo cổng thanh toán định kỳ. | Finance Staff |

### 5.17 Refund Management (REQ-RFD)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-RFD-001 | Mỗi yêu cầu hoàn tiền phải gắn với đúng một giao dịch thanh toán gốc đã thành công; khách hàng tự tạo yêu cầu qua ứng dụng hoặc Receptionist tạo tại quầy theo đề nghị của khách. | Customer, Receptionist |
| REQ-RFD-002 | Tổng số tiền hoàn trả lũy kế cho một giao dịch thanh toán không được vượt quá số tiền đã thanh toán của giao dịch đó. | System |
| REQ-RFD-003 | Yêu cầu hoàn tiền chỉ được chấp nhận trong thời hạn tối đa xác định (30 ngày) kể từ ngày giao dịch thanh toán gốc thành công, trừ trường hợp có phê duyệt ngoại lệ từ Organization Admin. | System |
| REQ-RFD-004 | Yêu cầu hoàn tiền phải được phê duyệt hoặc từ chối (kèm lý do) bởi người khác với người tạo yêu cầu (Maker-Checker) trước khi thực thi chi trả. | Store Manager |
| REQ-RFD-005 | Việc chi trả hoàn tiền cho giao dịch tiền mặt phải được thực hiện trực tiếp tại quầy; đối với giao dịch điện tử phải được thực hiện qua cổng thanh toán gốc bởi Finance Staff. | Receptionist, Store Manager, Finance Staff |
| REQ-RFD-006 | Khi hoàn tiền qua cổng gặp lỗi kỹ thuật, hệ thống phải cho phép thử lại tối đa một số lần xác định (3 lần); nếu vẫn thất bại, yêu cầu phải chuyển sang trạng thái cần xử lý ngoại tuyến thủ công. | System, Finance Staff |
| REQ-RFD-007 | Hệ thống phải cho phép xử lý hoàn tiền thủ công ngoại tuyến (chuyển khoản/tiền mặt) khi kênh tự động thất bại, yêu cầu bắt buộc chứng từ đối soát hợp lệ để hoàn tất. | Finance Staff, Store Manager |
| REQ-RFD-008 | Khi hoàn tiền hoàn tất, hệ thống phải đồng bộ cập nhật: số tiền hoàn lũy kế trên hóa đơn gốc, trạng thái giao dịch thanh toán liên quan, và số tiền hoàn lũy kế/trạng thái trên đơn hàng liên quan (nếu có). | System |
| REQ-RFD-009 | Hệ thống phải tự động gửi thông báo kết quả hoàn tiền cho khách hàng. | System |
| REQ-RFD-010 | Finance Staff phải thực hiện được đối soát định kỳ các khoản hoàn tiền với báo cáo cổng thanh toán và phiếu chi nội bộ (đồng nhất với yêu cầu đối soát đã có ở Invoice và Payment). | Finance Staff |

### 5.18 Promotion & Voucher Management (REQ-PRM)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-PRM-001 | Organization Admin phải khởi tạo và quản lý được chương trình khuyến mãi, xác định thời gian, điều kiện, phạm vi dịch vụ/sản phẩm áp dụng và ngân sách. | Organization Admin |
| REQ-PRM-002 | Store Manager phải cấu hình được việc kích hoạt/tạm dừng một chương trình khuyến mãi tại Store của mình trong khung chính sách Organization. | Store Manager |
| REQ-PRM-003 | Organization Admin phải phát hành được mã Voucher xác định rõ loại giảm giá, giá trị, giá trị đơn hàng tối thiểu, thời hạn hiệu lực và đối tượng áp dụng. | Organization Admin |
| REQ-PRM-004 | Voucher chỉ được áp dụng khi thỏa mãn đồng thời: đang hoạt động, còn hiệu lực thời gian, thuộc Store đang mở áp dụng, đơn hàng đạt giá trị tối thiểu, và khách hàng thuộc đối tượng được hưởng. | System |
| REQ-PRM-005 | Hệ thống phải kiểm soát tổng lượt sử dụng Voucher trên toàn hệ thống và số lượt sử dụng theo từng khách hàng, từ chối áp dụng khi vượt một trong hai giới hạn. | System |
| REQ-PRM-006 | Mỗi lượt áp dụng Voucher thành công phải được ghi nhận (khách hàng, đơn hàng/hóa đơn liên quan, số tiền giảm, thời điểm) phục vụ đối soát ngân sách. | System |
| REQ-PRM-007 | Khi đơn hàng/hóa đơn có áp dụng Voucher bị hủy hoàn toàn trước khi hoàn tất giao dịch, hệ thống phải tự động hoàn trả lượt sử dụng Voucher cho khách hàng nếu Voucher còn hiệu lực. | System |
| REQ-PRM-008 | Mỗi hóa đơn/đơn hàng chỉ được áp dụng tối đa 01 Voucher (không cộng dồn nhiều Voucher trừ khi chiến dịch cho phép); một Promotion tự động và một Voucher nhập tay ĐƯỢC PHÉP cộng dồn trên cùng một hóa đơn/đơn hàng, tính chiết khấu Promotion trước trên Subtotal rồi mới tính Voucher trên phần còn lại, tổng chiết khấu không vượt quá Subtotal. | System |
| REQ-PRM-009 | Bản thân đối tượng chương trình khuyến mãi và mã Voucher phải có vòng đời trạng thái riêng (bản nháp/hoạt động/tạm dừng/hết hạn đối với chương trình; hoạt động/vô hiệu hóa/hết hạn đối với Voucher), độc lập với việc áp dụng vào một giao dịch cụ thể tại runtime; hệ thống phải tự động chuyển sang hết hạn khi quá thời hạn hiệu lực cấu hình. | Organization Admin, System |

### 5.19 Membership & Loyalty Management (REQ-MEM)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-MEM-001 | Mỗi hồ sơ hội viên phải thuộc về đúng một Customer trong phạm vi một Organization; Customer phải xem được thông tin hội viên và điểm tích lũy của mình. | Customer |
| REQ-MEM-002 | Hệ thống phải cho phép gia hạn gói hội viên đang hoạt động, giữ nguyên trạng thái hoạt động sau gia hạn. | Customer, Receptionist |
| REQ-MEM-003 | Khi khách hàng đủ điều kiện nâng hạng, hệ thống phải đóng bản ghi hội viên hạng cũ và tự động kích hoạt bản ghi hội viên hạng mới với quyền lợi tương ứng. | System, Store Manager |
| REQ-MEM-004 | Gói hội viên quá thời hạn hiệu lực mà không gia hạn phải tự động chuyển sang trạng thái hết hạn. Hạng hội viên KHÔNG có cơ chế hạ hạng (downgrade) khi khách hàng không duy trì điều kiện chi tiêu — hạng chỉ tăng (REQ-MEM-003) hoặc chấm dứt hoàn toàn khi hết hạn; đây là chủ đích thiết kế đã xác nhận. | System |
| REQ-MEM-005 | Hệ thống phải tự động tích lũy điểm thưởng cho khách hàng sau mỗi giao dịch thanh toán thành công, theo tỷ lệ chính sách hạng hội viên. | System |
| REQ-MEM-006 | Điểm tích lũy chỉ được sử dụng bởi chính khách hàng sở hữu và không được trừ vượt quá số điểm khả dụng; điểm quá hạn phải tự động mất hiệu lực. | System |
| REQ-MEM-007 | Store Manager/Organization Admin phải điều chỉnh thủ công được điểm tích lũy khi có lý do nghiệp vụ chính đáng, với lý do bắt buộc được ghi nhận. | Store Manager |
| REQ-MEM-008 | Dữ liệu hạng hội viên và điểm tích lũy phải cách ly độc lập theo từng Organization; điểm tích lũy ở Organization này không được sử dụng tại Organization khác. | System |
| REQ-MEM-009 | Khi tài khoản khách hàng bị khóa hoặc phát hiện gian lận tích điểm, toàn bộ quyền lợi hội viên và số dư điểm thưởng phải bị tạm đình chỉ cho đến khi có quyết định xử lý từ Organization Admin. | System |

### 5.20 Package Management (REQ-PKG)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-PKG-001 | Hệ thống phải cho phép khách hàng mua gói dịch vụ trả trước nhiều lượt, gắn với danh mục quyền lợi (số lượt, thời hạn hiệu lực). | Customer |
| REQ-PKG-002 | Gói dịch vụ phải được kích hoạt qua một trong các kênh: tại quầy sau thanh toán, tự động khi thanh toán online thành công, hoặc tự động khi khách hàng check-in sử dụng lượt đầu tiên. | Receptionist, System |
| REQ-PKG-003 | Lượt sử dụng gói chỉ được xác nhận trừ khi gói đang hoạt động/còn lượt, còn hiệu lực thời gian, và số lượt còn lại đủ để trừ. | Receptionist |
| REQ-PKG-004 | Gói quá hạn hiệu lực phải tự động chuyển sang trạng thái hết hạn; gói đã hết hạn hoặc đã dùng hết lượt tuyệt đối không được tiếp tục cấn trừ. | System |
| REQ-PKG-005 | Mọi lượt cấn trừ gói phải được ghi nhận vào lịch sử sử dụng (giao dịch, thời điểm, dịch vụ, Store, nhân sự, Pet thụ hưởng). | System |
| REQ-PKG-006 | Hệ thống phải cho phép Store Manager hủy gói theo chính sách hoàn gói của Organization, tự động phát sinh yêu cầu hoàn tiền cho giá trị các lượt chưa sử dụng theo công thức tính đã định. | Store Manager |
| REQ-PKG-007 | Khi lịch hẹn được giữ chỗ bằng lượt gói nhưng khách vắng mặt, hệ thống phải tự động khấu trừ 1 lượt của gói; Store Manager phải có quyền hoàn lại lượt đã trừ khi có lý do bất khả kháng chính đáng, kèm lý do bắt buộc lưu vết. | System, Store Manager |
| REQ-PKG-008 | Store Manager phải điều chỉnh thủ công được số lượt còn lại của gói khi có lý do nghiệp vụ, kèm lý do bắt buộc lưu vết. | Store Manager |
| REQ-PKG-009 | Quyền lợi sử dụng gói chỉ áp dụng cho chính Customer sở hữu gói hoặc Pet thuộc quyền sở hữu/ủy quyền hợp lệ của Customer đó. | System |
| REQ-PKG-010 | Khi đặt lịch hẹn, hệ thống phải cho phép khách hàng chọn thanh toán bằng một Gói dịch vụ cụ thể và phải kiểm tra ngay tại thời điểm đặt lịch rằng gói đang hoạt động, còn hiệu lực thời gian, và còn ít nhất 1 lượt khả dụng; từ chối đặt lịch bằng gói nếu không đạt điều kiện. Việc gán gói tại bước đặt lịch không tự trừ lượt — lượt chỉ trừ chính thức khi xác nhận sử dụng lúc check-in hoặc khi phạt vắng mặt. | Customer, Receptionist, System |

### 5.21 Incident Management (REQ-INC)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-INC-001 | Hệ thống phải cho phép ghi nhận sự cố từ ba nguồn: vận hành/dịch vụ tại quầy, sự cố trong khám/chữa bệnh/tiêm phòng, và sự cố trong quy trình grooming. | Receptionist, Veterinarian, Groomer |
| REQ-INC-002 | Khi dừng khẩn cấp một phiên khám/phẫu thuật hoặc grooming, hệ thống phải tự động khởi tạo hồ sơ sự cố tương ứng, ghi nhận lý do và nhân sự thực hiện. | System |
| REQ-INC-003 | Store Manager phải phân loại được mức độ nghiêm trọng của sự cố (Nhẹ/Trung bình/Nghiêm trọng/Đặc biệt nghiêm trọng) trước khi tiến hành điều tra chi tiết. | Store Manager |
| REQ-INC-004 | Đối với sự cố mức độ Nghiêm trọng hoặc Đặc biệt nghiêm trọng, hệ thống phải tự động gửi thông báo khẩn tới khách hàng liên quan và Store Manager. | System |
| REQ-INC-005 | Khi sự cố vượt quá thẩm quyền xử lý tại Store, Store Manager phải chuyển cấp xử lý lên Organization Admin/Platform Admin. | Store Manager |
| REQ-INC-006 | Quá trình xử lý sự cố phải ghi nhận kết quả điều tra nguyên nhân và biện pháp khắc phục đã thực hiện. | Store Manager |
| REQ-INC-007 | Hồ sơ sự cố chỉ được đóng sau khi toàn bộ biện pháp khắc phục đã hoàn tất và được nghiệm thu; sau khi đóng, hồ sơ phải là bất biến, không được chỉnh sửa hoặc mở lại nếu không có phê duyệt kiểm toán đặc biệt từ cấp trên. | Store Manager, Organization Admin |

### 5.22 Consent & Privacy Management (REQ-CNS)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-CNS-001 | Mọi quyền đồng thuận xử lý dữ liệu phải gắn với đúng Customer sở hữu Pet, xác định rõ mục đích xử lý và thời hạn hiệu lực; Customer phải cấp/thu hồi được quyền đồng thuận. | Customer |
| REQ-CNS-002 | Mọi thao tác truy cập/xử lý dữ liệu hồ sơ cá nhân và bệnh án thú cưng phải thỏa mãn đồng thời: người thực hiện có vai trò/quyền hợp lệ trong phạm vi quản lý, **và** có sự đồng thuận hợp lệ còn hiệu lực từ Customer — ngoại trừ giao thức truy cập khẩn cấp. | System |
| REQ-CNS-003 | Yêu cầu truy cập bệnh án liên chi nhánh (chuẩn) phải phát sinh mã xác thực có hiệu lực 5 phút gửi tới chủ Pet; xác thực thành công cấp quyền xem tối đa 24 giờ. | System |
| REQ-CNS-004 | Khách hàng phải chủ động thu hồi được quyền chia sẻ bệnh án liên chi nhánh bất kỳ lúc nào trước khi hết hạn. | Customer |
| REQ-CNS-005 | Veterinarian phải kích hoạt được quyền truy cập khẩn cấp hồ sơ bệnh án (không cần xác thực đồng thuận) trong tình huống nguy kịch, kèm lý do lâm sàng bắt buộc; hành động này phải tự động lập biên bản sự cố y tế, gửi cảnh báo tới chủ Pet và Store Manager, và ghi nhật ký kiểm toán chi tiết. | Veterinarian |
| REQ-CNS-006 | Khách hàng phải yêu cầu được trích xuất toàn bộ dữ liệu cá nhân của mình dưới định dạng tiêu chuẩn. | Customer |
| REQ-CNS-007 | Khách hàng phải yêu cầu được xóa/ẩn danh dữ liệu cá nhân; việc thực thi phải tuân thủ chính sách lưu trữ pháp lý (hóa đơn/chứng từ kế toán/bệnh án phải giữ đủ thời hạn luật định trước khi xóa vĩnh viễn). | Customer, System |
| REQ-CNS-008 | Organization Admin phải cấu hình được chính sách bảo mật và chính sách lưu trữ dữ liệu áp dụng đồng bộ cho toàn bộ Store trực thuộc. | Organization Admin |
| REQ-CNS-009 | Hệ thống phải tự động chuyển quyền truy cập bệnh án liên chi nhánh sang hết hạn khi vượt quá 24 giờ kể từ khi kích hoạt. | System |

### 5.23 Notification Management (REQ-NOT)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-NOT-001 | Mọi thông báo phát sinh phải có người nhận xác định và kênh gửi cụ thể. | System |
| REQ-NOT-002 | Thông báo tự động chỉ được phát sinh dựa trên các sự kiện nghiệp vụ hợp lệ đã xảy ra trong hệ thống (xác nhận đặt lịch, nhắc lịch hẹn, nhắc tiêm phòng, nhắc tái khám, xác nhận thanh toán, cập nhật đơn hàng, quyền lợi/thay đổi hạng hội viên, cảnh báo sự cố khẩn cấp). | System |
| REQ-NOT-003 | Thông báo gửi qua kênh bên thứ ba gặp lỗi tạm thời phải được tự động thử gửi lại với số lần thử tối đa xác định (3 lần); nếu vẫn thất bại, bản ghi phải chuyển sang trạng thái thất bại. | System |
| REQ-NOT-004 | Người dùng phải tra cứu và đánh dấu đã đọc được danh sách thông báo cá nhân của mình. | Customer, Staff |
| REQ-NOT-005 | Hệ thống phải tôn trọng tùy chọn nhận thông báo tiếp thị của khách hàng (opt-in/opt-out); thông báo giao dịch cốt lõi (OTP, xác nhận thanh toán, cảnh báo cấp cứu y tế) là bắt buộc và không bị ảnh hưởng bởi opt-out. | System |
| REQ-NOT-006 | Nội dung thông báo không được chứa thông tin nhạy cảm ở dạng thô (mật khẩu, mã thẻ thanh toán, toàn bộ nội dung bệnh án). | System |

### 5.24 Reporting & Analytics (REQ-RPT)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-RPT-001 | Store Manager/Finance Staff chỉ được xem báo cáo doanh thu, hoạt động, tồn kho, hiệu suất dịch vụ và nhân sự trong phạm vi Store mình quản lý. | Store Manager, Finance Staff |
| REQ-RPT-002 | Organization Admin phải xem được báo cáo tổng hợp doanh thu toàn chuỗi và so sánh hiệu suất giữa các Store cùng Organization. | Organization Admin |
| REQ-RPT-003 | Platform Admin phải xem được báo cáo tổng quan toàn nền tảng. | Platform Admin |
| REQ-RPT-004 | Báo cáo doanh thu, khách hàng và vận hành giữa các Organization độc lập phải được cách ly 100%, không cho phép tổng hợp/so sánh chéo giữa các Organization khác nhau. | System |
| REQ-RPT-005 | Báo cáo đối soát doanh thu phải phản ánh nhất quán giữa tổng hóa đơn đã tất toán, tổng thanh toán thành công thực nhận, và tổng tiền hoàn trả lũy kế. | Finance Staff |
| REQ-RPT-006 | Báo cáo phân tích hành vi khách hàng chỉ được trích xuất khi tuân thủ đầy đủ chính sách bảo vệ dữ liệu cá nhân. | System |
| REQ-RPT-007 | Dữ liệu báo cáo tài chính/vận hành của các kỳ đã khóa sổ phải là bất biến; mọi điều chỉnh hồi tố phải thực hiện qua bút toán điều chỉnh trong kỳ hiện tại kèm lý do kiểm toán rõ ràng, không được sửa trực tiếp số liệu lịch sử. | System, Finance Staff |

### 5.25 Audit Management (REQ-AUD)

| ID | Requirement | Actor(s) |
|---|---|---|
| REQ-AUD-001 | Hệ thống phải tự động ghi nhận vào nhật ký kiểm toán mọi biến động trạng thái dữ liệu trọng yếu, giao dịch tài chính, thay đổi phân quyền và truy cập bệnh án y tế. | System |
| REQ-AUD-002 | Nhật ký kiểm toán phải là dữ liệu chỉ-ghi-thêm (append-only); trong thời gian lưu trữ bắt buộc tối thiểu, không một người dùng nào — kể cả quản trị viên cấp cao nhất — được phép sửa hoặc xóa thủ công. | System |
| REQ-AUD-003 | Hệ thống phải ghi nhật ký chuyên biệt cho mọi thay đổi liên quan đến người dùng/phân quyền (tạo tài khoản, đổi mật khẩu, gán/thu hồi quyền, thay đổi vai trò, khóa/mở khóa). | System |
| REQ-AUD-004 | Hệ thống phải ghi nhật ký chi tiết cho mọi truy cập/sửa đổi bệnh án, đặc biệt là chia sẻ liên chi nhánh và truy cập khẩn cấp. | System |
| REQ-AUD-005 | Hệ thống phải ghi nhật ký chi tiết cho mọi giao dịch tài chính (thanh toán, phát hành/hủy hóa đơn, phê duyệt/xử lý hoàn tiền). | System |
| REQ-AUD-006 | Hệ thống phải ghi nhật ký chi tiết cho mọi biến động tồn kho và phê duyệt liên quan. | System |
| REQ-AUD-007 | Quyền tra cứu nhật ký kiểm toán phải phân định nghiêm ngặt theo cấp bậc quản trị (Platform/Organization/Store) và theo chuyên môn (Finance Staff/Inventory Staff chỉ tra cứu nhật ký chuyên môn tương ứng). | Platform Admin, Organization Admin, Store Manager, Finance Staff, Inventory Staff |

---

## 6. Non-Functional Requirements


| ID | Requirement |
|---|---|
| NFR-REL-001 | Khi một sự kiện nghiệp vụ ảnh hưởng nhiều thực thể (ví dụ: thanh toán thành công phải tất toán Hóa đơn, xác nhận Đơn hàng, kích hoạt Gói dịch vụ), hệ thống phải đảm bảo tất cả các hệ quả liên quan cuối cùng đều được áp dụng đúng-một-lần (exactly-once effect), kể cả khi có lỗi tạm thời giữa chừng — không được để mất sự kiện, và không được áp dụng lặp lại một sự kiện đã xử lý. |
| NFR-REL-002 | Không được để xảy ra tình trạng bán vượt tồn kho (overselling) khi nhiều giao dịch cùng tranh chấp một sản phẩm/slot hẹn tại cùng thời điểm. |
| NFR-REL-003 | Cơ chế giữ chỗ tạm thời (Slot hẹn, Tồn kho đơn hàng) phải tự động giải phóng đúng hạn khi hết TTL, không phụ thuộc vào hành động thủ công của người dùng. |
| NFR-PERF-001 | Các truy vấn báo cáo/phân tích dữ liệu lớn không được làm suy giảm hiệu năng xử lý giao dịch thời gian thực (đặt lịch, thanh toán, bán hàng). |
| NFR-PERF-002 | Phiên thu ngân tại quầy (POS) khi chờ xác nhận thanh toán (quẹt thẻ/tiền mặt) phải có giới hạn thời gian khóa phiên tối đa xác định (3 phút) để tránh treo giao dịch. |
| NFR-AVAIL-001 | Việc thông báo (SMS/Push/Email) gửi thất bại tạm thời không được làm gián đoạn luồng nghiệp vụ chính (ví dụ: đặt lịch vẫn thành công dù gửi SMS xác nhận thất bại); hệ thống phải tách rời và tự phục hồi retry riêng cho kênh thông báo. |
| NFR-DATA-001 | Số tiền phải được lưu trữ và tính toán không phát sinh sai số làm tròn (ví dụ: định lượng hàng hóa chiết rót phải lưu theo đơn vị nhỏ nhất nguyên, không dùng số thực dấu phẩy động cho định lượng chia nhỏ). |
| NFR-DATA-002 | Toàn bộ bản ghi giao dịch/thay đổi trạng thái quan trọng phải có dấu vết thời gian và định danh người/hệ thống thực hiện (audit trail tối thiểu ở mức entity, không chỉ ở AuditLog trung tâm). |
| NFR-SCAL-001 | Kiến trúc dữ liệu phải hỗ trợ mở rộng số lượng Organization/Store không giới hạn về mặt logic nghiệp vụ (không có ràng buộc cứng về số lượng Tenant). |
| NFR-USE-001 | Người dùng phải được thông báo lý do cụ thể khi một thao tác nghiệp vụ bị từ chối do vi phạm ràng buộc (ví dụ: xung đột lịch, vượt hạn mức hoàn tiền, vi phạm Maker-Checker) thay vì lỗi chung chung. |

---

## 7. Security Requirements

| ID | Requirement |
|---|---|
| REQ-SEC-001 | Mật khẩu người dùng phải được lưu trữ dưới dạng không thể khôi phục ngược (irreversible hash), không bao giờ lưu hoặc truyền dạng plain-text. Mật khẩu phải có độ dài tối thiểu 8 ký tự; không bắt buộc độ phức tạp bổ sung hay đổi định kỳ (ngoại trừ `must_change_password` cho Staff lần đầu theo D-04). | 
| REQ-SEC-002 | Sau khi đăng xuất hoặc khi tài khoản bị khóa/vô hiệu hóa, access credential (token/session) tương ứng không được tiếp tục được hệ thống chấp nhận cho bất kỳ yêu cầu nào khác. |
| REQ-SEC-003 | Hệ thống phải chống tấn công dò mật khẩu/OTP bằng giới hạn số lần thử và cơ chế khóa tạm thời tăng dần theo ngưỡng vi phạm. | 
| REQ-SEC-004 | Mọi thao tác thực thi phải được xác thực (authentication) và kiểm tra quyền hạn (authorization) đúng phạm vi Scope trước khi cho phép; vi phạm phải bị từ chối với lý do rõ ràng, không thực thi một phần. |
| REQ-SEC-005 | Hệ thống phải thực thi nguyên tắc phân tách trách nhiệm kép (Maker-Checker) cho 4 nhóm nghiệp vụ rủi ro cao: phê duyệt hoàn tiền, phê duyệt chuyển kho, phê duyệt yêu cầu mua hàng, phê duyệt điều chỉnh tồn kho — người khởi tạo tuyệt đối không được là người phê duyệt cùng yêu cầu đó. | 
| REQ-SEC-006 | Truy cập và xử lý dữ liệu cá nhân/bệnh án thú cưng phải thỏa mãn đồng thời quyền hạn hợp lệ của Actor **và** sự đồng thuận hợp lệ của chủ dữ liệu, ngoại trừ giao thức truy cập khẩn cấp có ghi vết đầy đủ. |
| REQ-SEC-007 | Mọi lượt truy cập khẩn cấp vượt quyền thông thường (Break-Glass) phải bắt buộc: có lý do nghiệp vụ tường minh, tự động tạo hồ sơ sự cố, tự động cảnh báo các bên liên quan, và ghi nhật ký kiểm toán pháp y chi tiết (định danh người thực hiện, đối tượng bị truy cập, thời điểm, địa chỉ nguồn truy cập). |
| REQ-SEC-008 | Webhook/callback nhận từ hệ thống bên ngoài (cổng thanh toán) phải được xác thực tính toàn vẹn (chữ ký số) trước khi được tin cậy xử lý. |
| REQ-SEC-009 | Nhật ký kiểm toán phải bất biến trong suốt thời hạn lưu trữ bắt buộc tối thiểu (5 năm); không một vai trò nào, kể cả quản trị viên cấp cao nhất, được phép sửa/xóa thủ công trong thời hạn này. | 
| REQ-SEC-010 | Dữ liệu vận hành và báo cáo giữa các Organization (Tenant) độc lập phải được cách ly tuyệt đối 100%, không có đường truy vấn/tổng hợp chéo Tenant. |
| REQ-SEC-011 | Nội dung thông báo gửi qua kênh bên ngoài (SMS/Email/Push) không được chứa dữ liệu nhạy cảm ở dạng thô. |
| REQ-SEC-012 | Việc xóa/ẩn danh dữ liệu cá nhân theo yêu cầu khách hàng phải tôn trọng ràng buộc lưu trữ pháp lý bắt buộc đối với chứng từ tài chính và bệnh án y tế, không được xóa sớm hơn thời hạn luật định. | 

---

## 8. Business Constraints


| ID | Constraint |
|---|---|
| BC-001 | **Settlement Immutability (D-01):** Hóa đơn đã tất toán (`PAID`) không bao giờ được chuyển sang trạng thái khác do hoàn tiền một phần hay toàn phần; toàn bộ hoàn tiền phản ánh qua thuộc tính lũy kế riêng và đối tượng Refund/Payment độc lập. |
| BC-002 | **Surcharge Invoice Protocol (D-02):** Dịch vụ/phụ phí phát sinh ngoài dự kiến trong lúc phục vụ (ví dụ Grooming) luôn tạo hóa đơn độc lập, không bao giờ chỉnh sửa hóa đơn gốc đã phát hành. |
| BC-003 | **Order Fulfillment Split (D-03):** Có đúng hai luồng hoàn tất đơn hàng — tức thời tại quầy POS và tuần tự nhiều bước cho Online/App; không có trạng thái "đang vận chuyển bởi bên thứ ba" (giao hàng ngoài Store nằm ngoài phạm vi v1). |
| BC-004 | **Direct Staff Provisioning (D-04):** Tài khoản nhân viên do Quản trị viên khởi tạo được kích hoạt thẳng, bỏ qua bước xác thực OTP đăng ký dành cho khách hàng tự đăng ký; đây là ngoại lệ được phê duyệt, không phải lỗi bảo mật. |
| BC-005 | **5-Tier Multi-Tenancy Scope Hierarchy:** Mọi dữ liệu và quyền hạn phải gắn với đúng một trong 5 tầng phạm vi (`PLATFORM` → `ORGANIZATION` → `STORE`/`WAREHOUSE` → `CUSTOMER`); không có thực thể nghiệp vụ "vô chủ" (không thuộc phạm vi nào). |
| BC-006 | **Maker-Checker Segregation of Duties:** Áp dụng bắt buộc cho 4 quy trình rủi ro tài chính/tài sản (Refund, StockTransfer, PurchaseRequest, InventoryAdjustment) — không có ngoại lệ kỹ thuật hay vai trò nào được miễn trừ. |
| BC-007 | **Currency:** Toàn bộ giá trị tiền tệ trong hệ thống là đơn vị VND; không có yêu cầu đa tiền tệ. |
| BC-008 | **FEFO cho hàng có hạn sử dụng:** Thuốc/vaccine/vật tư có hạn sử dụng phải luôn ưu tiên xuất kho theo nguyên tắc hết hạn trước - xuất trước. |
| BC-009 | **Pet Schedule Collision Guard áp dụng toàn hệ thống chi nhánh:** Một Pet không được có hai lịch phục vụ hoạt động chồng giờ dù ở cùng Store hay khác Store, kiểm tra trên toàn bộ Store **trong cùng một Organization**, không vượt ranh giới Organization (đã chốt — xem `RULE-06-11`, ~~AMBIGUITY-05~~ §10.2). |
| BC-010 | **Zero Hard-Delete cho dữ liệu đã phát sinh giao dịch:** Product/Customer/Pet/Invoice và các thực thể tài chính khác chỉ được vô hiệu hóa (soft-disable), không được xóa cứng khi đã có giao dịch liên quan. |

---

## 9. Out of Scope

Chỉ liệt kê nội dung có **bằng chứng tường minh** trong 01–06 rằng nó bị loại trừ hoặc chưa đưa vào milestone hiện tại. Không suy đoán thêm.

| ID | Nội dung ngoài phạm vi |
|---|---|
| OOS-001 | Giao hàng qua đơn vị vận chuyển thứ ba / trạng thái "Shipped-Carrier-Delivery" cho đơn Online. Hệ thống v1 chỉ hỗ trợ mô hình nhận hàng tại Store (In-Store Pickup/Retail Handover). |
| OOS-002 | Kênh thông báo Zalo ZNS. |
| OOS-003 | Đa tiền tệ (Multi-currency). |
| OOS-004 | Trạng thái Hóa đơn `PARTIALLY_PAID` và `REFUNDED` (bị cấm tường minh). |
| OOS-005 | Bước duyệt trung gian (`UNDER_REVIEW`) trong quy trình hoàn tiền. |
| OOS-006 | Cơ chế tự động mở khóa cho tài khoản bị khóa **chủ động bởi quản trị viên** (`lock_reason = ADMIN_LOCK`) — loại khóa này bắt buộc mở qua thao tác thủ công (`UnlockAccount`). **Không áp dụng** cho tài khoản khóa tự động do 5 lần đăng nhập sai liên tiếp (`lock_reason = AUTO_FAILED_LOGIN`), vốn CÓ auto-unlock sau 15 phút theo `RULE-01-07`/`REQ-ACC-011` (xem CONTRADICTION-07 §10.1 — đã sửa). |

---

## 10. Open Issues / TBD

> Theo yêu cầu nhiệm vụ: các mục dưới đây **không được tự ý giải quyết**. Mỗi mục được phân loại: **[CONTRADICTION]** (hai nguồn khẳng định trái ngược nhau), **[AMBIGUITY]** (một nguồn không đủ rõ ràng để implement), **[GAP]** (nhu cầu nghiệp vụ có bằng chứng nhưng thiếu requirement/command/rule tương ứng), **[ORPHAN]** (artifact tồn tại — schema field, RULE-ID reference, decision log entry — không có nguồn gốc/không được sử dụng nhất quán).
>
> **Trạng thái sau Phase 2 (Reconciliation) & Phase 3 (Traceability Audit) — xem chi tiết §12 Change History v1.1/v1.2:**
> - **Đã giải quyết (user quyết định + đã sửa `01→06`):** CONTRADICTION-01 → 06 (6/6, riêng CONTRADICTION-02 hoàn tất dứt điểm ở Phase 5 — xem dưới), AMBIGUITY-01 → 06 (6/6), ORPHAN-01 → 04 (4/4, riêng ORPHAN-01 hoàn tất dứt điểm ở Phase 5), GAP-CLN-01, GAP-RFD-01, GAP-MEM-01, GAP-PKG-01 (4/8).
> - **Đã giải quyết ở Phase 4 (xem chi tiết dưới):** GAP-ACC-01 (Password Policy — `RULE-01-09`), GAP-GRM-01 (Vet Referral — xác nhận điều phối thủ công), GAP-ORG-01 (Archived Store Data — bất biến chỉ đọc), GAP-PRC-01 (Supplier — bảng `suppliers`), GAP-CLN-02 (Treatment — bảng `treatments`); GAP-RFD-02 xác định là false positive ở Phase 3, đã sửa citation.
> - **Đã giải quyết ở Phase 5 (sau FREEZE v1.4, xem §12 Change History v1.5):** CONTRADICTION-02 / ORPHAN-01 (Phone/Email Global Uniqueness) — user xác nhận Account là danh tính đăng nhập cấp Platform; bổ sung `RULE-01-10` + `REQ-ACC-014`.
> - **Ngoài phạm vi (Out of Scope), không xử lý ở Phase 4/5:** GAP-PLT-01 (platform monetization/billing) — đây là toàn bộ một tầng business model mới (không thuộc 25 module gốc), khuyến nghị làm sáng kiến/phase riêng có chủ đích, không mở rộng scope reconciliation hiện tại.
> - **Phát hiện mới ở Phase 3 Traceability Audit — đã xử lý theo quyết định user:** C1 (PetStatus ERD upgrade), C2 (Promotion/Voucher FSM chính thức), C3 (hợp nhất `ConfigureStoreService`/`ConfigureServiceAvailability`), 13 missing-traceability rule citations, ~10 real FR gap (xem §11 các dòng đánh dấu MỚI/LÀM RÕ và §5 các REQ đánh dấu **[MỚI]**/**[SỬA]**).
> - Các bảng dưới đây được **giữ nguyên nội dung gốc** của Phase 1 làm hồ sơ lịch sử; không xóa/viết lại — trạng thái hiện tại tham chiếu qua ghi chú trên.

### 10.1 Contradictions

| ID | Mô tả | Nguồn xung đột |
|---|---|---|
| ~~CONTRADICTION-01~~ | **[ĐÃ SỬA — Phase 2 (đóng ORPHAN-02), xác nhận lại phiên làm việc hiện tại]** `docs/03-state-machines.md` FSM 1 nay đã có cạnh tường minh `LOCKED -> ACTIVE: AutoUnlockAccount [System, RULE-01-07, chỉ khi lock_reason = AUTO_FAILED_LOGIN]`, khớp đúng mô tả cột `accounts.locked_until` trong `06-erd.md` (chỉ áp dụng cho khóa `AUTO_FAILED_LOGIN`; khóa `ADMIN_LOCK` không có auto-unlock, `locked_until` luôn NULL). Câu trích dẫn gốc "FSM không có cạnh tự động mở khóa" không còn đúng với bản `03` hiện tại. | `03` §1 vs `06` §3.1 bảng `accounts` |
| ~~CONTRADICTION-02~~ | **[ĐÃ SỬA HOÀN TOÀN — trích dẫn sai đã sửa Phase 2, orphan invariant đóng dứt điểm ở Phase 5]** `docs/05-domain-model.md` §4.1 nay trích dẫn đúng `RULE-01-01` (chỉ về điều kiện đăng nhập). Đã bổ sung `RULE-01-10` (mới, Phase 5 — user xác nhận): Account là danh tính đăng nhập cấp Platform, số điện thoại/email duy nhất trên toàn nền tảng, không giới hạn theo Organization. Đóng luôn `REQ-ACC-014` mới tại §5.1/§11.1. | `05` §4.1 vs `02` RULE-01-01/RULE-01-10 vs `06` `accounts` |
| ~~CONTRADICTION-03~~ | **[ĐÃ SỬA — Phase 2 (đóng GAP-CLN-01), xác nhận lại phiên làm việc hiện tại]** `docs/06-erd.md` bảng `medical_records` nay trích dẫn đúng `RULE-09-09` (EMR Finalization & 24h Window) hậu thuẫn cho `status`/`finalized_at`/`is_locked`/`locked_at`, không còn trích dẫn sai `RULE-09-06` (vốn chỉ về `CreateFollowUp`). | `06` §3.4 vs `02` RULE-09-09 |
| ~~CONTRADICTION-04~~ | **[ĐÃ SỬA — Phase 2 (đóng ORPHAN-04), xác nhận lại phiên làm việc hiện tại]** `docs/06-erd.md` §6 nay đã có đủ 3 dòng khớp tiêu đề "Cả 3 mục": `RHD-DB-01`, `RHD-DB-02`, và `RHD-DB-03` (chính sách lưu trữ/purge `audit_logs`/`outbox_events`, khớp `RULE-25-08`). | `06` §6 vs `02` RULE-25-08 |
| ~~CONTRADICTION-05~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** Đã verify trực tiếp bảng Traceability §26/§28.2 trong `docs/04-glossary.md`: Module 04 nay đúng `RULE-04-01 → RULE-04-11`, Module 05 đúng `RULE-05-01 → RULE-05-07`, Module 20 đúng `RULE-20-01 → RULE-20-09` — khớp 100% nội dung đầy đủ tại `02-business-rules.md` (không còn thiếu `RULE-04-10/11`, `RULE-20-08/09` như mô tả gốc). | `04` §28.2 vs `02` (nội dung đầy đủ từng module) |
| ~~CONTRADICTION-06~~ | **[ĐÃ SỬA — phiên làm việc hiện tại]** Đếm thủ công toàn bộ RULE-ID hiện tại ra đúng **217**. `docs/INDEX.md` đã đúng "217" sẵn; đã sửa nốt "207" → "217" tại `04-glossary.md` §26 và "~150+" → "217" tại `architecture/system-overview.md` §8. Cả 3 nguồn nay đồng nhất 217. | `INDEX.md` vs `04` §28.2 vs đếm thực tế trên `02` |
| ~~CONTRADICTION-07~~ | **[PHÁT HIỆN & SỬA — ngoài chu trình audit chính thức, phiên làm việc hiện tại]** `§9 OOS-006` (bản gốc) khẳng định tuyệt đối không tồn tại cơ chế auto-unlock, mâu thuẫn trực tiếp với `REQ-ACC-011` (§5.1, cùng tài liệu), `RULE-01-07`, FSM 1 (`AutoUnlockAccount`) và cột `accounts.locked_until` trong `06-erd.md` — cả 4 nguồn đều minh định tài khoản `AUTO_FAILED_LOGIN` được tự động mở khóa sau 15 phút. Đã sửa `OOS-006` để thu hẹp đúng phạm vi loại trừ (chỉ khóa `ADMIN_LOCK` mới bắt buộc mở thủ công). | `00` §9 OOS-006 vs `00` §5.1 REQ-ACC-011 vs `02` RULE-01-07 vs `03` FSM 1 vs `06` accounts.locked_until |

### 10.2 Ambiguities

| ID | Mô tả |
|---|---|
| ~~AMBIGUITY-01 (Grace Period No-Show)~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** `RULE-06-09` (`02` dòng 129) nay đã chốt: Grace Period là **giá trị chính sách kinh doanh cấu hình được** theo Organization/Store qua `system_configs` (khóa `APPOINTMENT_GRACE_PERIOD_MINUTES`), mặc định toàn nền tảng **15 phút** nếu không override. Không còn là "ví dụ" mơ hồ. |
| ~~AMBIGUITY-02 (Loyalty Point Expiry)~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** `RULE-19-07` (`02` dòng 472) nay đã chốt: cấu hình được theo Organization qua `system_configs` (khóa `LOYALTY_POINT_EXPIRY_MONTHS`), mặc định toàn nền tảng **12 tháng** nếu không override. |
| ~~AMBIGUITY-03 (Low-Stock / Expiry Warning Threshold)~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** `RULE-12-12` (`02` dòng 329) nay đã chốt: cấu hình được theo Organization/Store qua `system_configs` (khóa `EXPIRY_WARNING_DAYS`, cho phép nhiều mốc), mặc định toàn nền tảng **30 ngày** trước `expiry_date` nếu không override. |
| ~~AMBIGUITY-04 (Promotion + Voucher Stacking)~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** `RULE-18-08` (`02` dòng 458) nay đã chốt tường minh (tiêu đề rule tự ghi "đã chốt"): tối đa 01 Voucher/hóa đơn (trừ cấu hình Stackable đặc biệt), nhưng 01 Promotion + 01 Voucher **ĐƯỢC PHÉP** cộng dồn đồng thời (2 cơ chế độc lập: tự động vs chủ động nhập mã), kèm thứ tự tính chiết khấu rõ ràng (Promotion trước, Voucher sau, tổng không vượt quá Subtotal). |
| ~~AMBIGUITY-05 (Phạm vi Pet Schedule Collision Guard)~~ | **[ĐÃ SỬA — xác nhận lại phiên làm việc hiện tại]** `RULE-06-11` (`02` dòng 131) nay đã chốt tường minh: phạm vi kiểm tra là **toàn bộ Store trong cùng một Organization**, KHÔNG vượt ranh giới Organization — nhất quán với cách ly dữ liệu 100% giữa các Organization (`RULE-02-01`, `RULE-03-01`). Đã sửa luôn tham chiếu lỗi thời "OPEN-APT-01" tại `BC-009` (§9, trỏ tới một ID không tồn tại) sang đúng mục này. |
| **AMBIGUITY-06 (System Config cho các tham số TTL)** | `docs/06-erd.md` có bảng `system_configs` với ví dụ khóa `HOLD_TTL_SECONDS`, `OTP_MAX_ATTEMPTS` — ngụ ý các hằng số nghiệp vụ (300s, 900s, 5 lần, 24h...) có thể là **cấu hình được** theo Organization/Store thay vì hằng số cứng toàn hệ thống như văn bản `02-business-rules.md` mô tả (dùng ký hiệu toán học `= 300s`, `= 900s` như hằng số). Chưa rõ giá trị nào là hằng số bắt buộc toàn nền tảng và giá trị nào Organization được quyền override. |

### 10.3 Requirement Gaps

| ID | Mô tả |
|---|---|
| ~~GAP-CLN-01 (EMR 24h Immutability Rule)~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** User quyết định vòng đời: `CheckOut → Finalized → cho chỉnh sửa có kiểm soát trong 24h → Immutable`. Đã bổ sung `RULE-09-09` (EMR Finalization & 24h Window), cột `medical_records.status`/`finalized_at`, FSM state `MedicalRecordStatus`. |
| ~~GAP-ACC-01~~ | **[ĐÃ SỬA — Phase 4]** Thêm `RULE-01-09`: độ dài tối thiểu 8 ký tự, không ép độ phức tạp/đổi định kỳ. |
| ~~GAP-RFD-01 (Refund Window Exception Approval)~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** Bổ sung Command `ApproveRefundWindowException` [OrganizationAdmin] vào Module 17 (`01`), `RULE-17-03` cập nhật tham chiếu tường minh tới Command này. |
| ~~GAP-GRM-01~~ | **[ĐÃ XÁC NHẬN — Phase 4]** Quyết định giữ nguyên là điều phối vận hành thủ công ngoài hệ thống, không formalize thành Command/FSM. `RULE-11-02` đã bổ sung ghi chú xác nhận phạm vi này. |
| ~~GAP-MEM-01 (Membership Tier Downgrade)~~ | **[ĐÃ XÁC NHẬN — Phase 2, chuẩn hóa annotation ở Phase 5]** User xác nhận chủ đích nghiệp vụ: hạng thành viên chỉ tăng hoặc hết hạn, không có downgrade tự động. FSM Membership (`03` §9) giữ nguyên không có transition downgrade; `RULE-19-02` bổ sung xác nhận Non-Downgrade Policy, FSM Technical Invariant #4 đồng bộ. |
| ~~GAP-PKG-01 (Package-to-Appointment Binding tại thời điểm đặt lịch)~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** Bổ sung `booking_holds.service_package_id`/`appointments.service_package_id` (FK) để gắn cờ đặt-trước-bằng-gói ngay tại thời điểm đặt lịch; `RULE-20-09` (Package-Appointment Binding) bổ sung làm rule hậu thuẫn. |
| **GAP-PLT-01 (Platform Monetization Model)** | **[OUT OF SCOPE — xác định ở Phase 4, không xử lý ở Phase 4/5]** Hệ thống được mô tả là "nền tảng SaaS đa tổ chức" (`01` §0, `05` §2) nhưng không có bất kỳ module/rule nào mô tả cách Platform Admin thu phí/quản lý gói dịch vụ (subscription/billing) đối với các Organization sử dụng nền tảng. Đây là khoảng trống toàn bộ một tầng nghiệp vụ mới (Module 26+), cố tình không xử lý trong phạm vi reconciliation `01-25` hiện tại để tránh mở rộng scope ngoài các issue đã xác định — khuyến nghị làm sáng kiến/phase riêng có chủ đích khi có yêu cầu kinh doanh cụ thể. |
| ~~GAP-ORG-01~~ | **[ĐÃ SỬA — Phase 4]** `RULE-03-06` bổ sung: dữ liệu cấu hình con của Store đã Archive trở thành bất biến chỉ đọc, không xóa cứng. |

### 10.4 Orphan Artifacts / Nội dung không có Business Justification rõ ràng

| ID | Mô tả |
|---|---|
| ~~ORPHAN-01~~ | **[ĐÃ SỬA — Phase 5]** User xác nhận phạm vi: toàn nền tảng (Global). Thêm `RULE-01-10` hậu thuẫn ràng buộc `UNIQUE(phone)`/`UNIQUE(email)` trên bảng `accounts` (xem CONTRADICTION-02). |
| ~~ORPHAN-02~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** User quyết định giữ theo hướng ERD (có auto-unlock), nhưng thu hẹp phạm vi có kiểm soát: `accounts.lock_reason` phân biệt `AUTO_FAILED_LOGIN` (có auto-unlock qua `AutoUnlockAccount`, `locked_until` áp dụng) và `ADMIN_LOCK` (không có auto-unlock, `locked_until` luôn NULL). `RULE-01-07` viết lại, FSM 1 Technical Invariant #5 đồng bộ — `locked_until` nay có mục đích sử dụng hợp lệ, không còn orphan. |
| ~~ORPHAN-03~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** `medical_records.is_locked`/`locked_at` nay được hậu thuẫn bởi `RULE-09-09` (EMR Finalization & 24h Window, bổ sung theo quyết định GAP-CLN-01): `locked_at = finalized_at + 24 giờ`, `is_locked` tương đương `status = 'LOCKED'`. |
| ~~ORPHAN-04~~ | **[ĐÃ SỬA — Phase 2, chuẩn hóa annotation ở Phase 5]** Đã bổ sung dòng `RHD-DB-03` vào bảng quyết định `06-erd.md` §6, khớp nội dung "5 năm" đã chốt trong `RULE-25-08`. |

### 10.5 Meta Issue

| ID | Mô tả |
|---|---|
| **OPEN-META-01** | Số liệu tổng hợp toàn cục (tổng số Rules, tổng số Commands "293", tổng số FSM) được các tài liệu tự công bố ở nhiều nơi khác nhau nhưng không có một script/cơ chế đối soát tự động nào đảm bảo các con số này luôn khớp với nội dung thật — khuyến nghị phase reconciliation tiếp theo nên coi các con số tổng hợp này là **mô tả, không phải nguồn chân lý**, và luôn đếm lại từ nội dung chi tiết khi cần con số chính xác. |

### 10.6 Phase 3 Traceability Audit — Phát hiện mới

> Bổ sung sau audit bidirectional `00 ↔ 01 ↔ 02 ↔ 03 ↔ 04 ↔ 05 ↔ 06`. Trạng thái xử lý ghi trực tiếp trong mỗi dòng.

**Contradictions mới (đã xử lý theo quyết định user):**

| ID | Mô tả | Trạng thái |
|---|---|---|
| **C1 (PetStatus)** | Glossary định nghĩa `PetStatus` 3 giá trị `[ACTIVE, DECEASED, TRANSFERRED]` nhưng `pets` chỉ có `is_active BOOLEAN`. | **Đã sửa:** `pets.is_active` → `pets.status` (`pet_status_enum`), thêm `RULE-04-11`. |
| **C2 (Promotion/Voucher Stateless vs Status Column)** | Glossary khẳng định "Stateless, không cần FSM" nhưng `promotion_campaigns.status`/`vouchers.status` đã có lifecycle values từ trước. | **Đã sửa:** Thêm FSM 18 (Promotion), FSM 19 (Voucher) vào `03`; làm rõ "áp dụng runtime" (stateless) khác với "vòng đời đối tượng" (có FSM). |
| **C3 (Command Duplicate)** | `ConfigureStoreService` (Module 03, `RULE-03-05`) và `ConfigureServiceAvailability` (Module 05, `RULE-05-04`) là 2 tên cho cùng 1 hành vi. | **Đã sửa:** `ConfigureServiceAvailability` là canonical; `ConfigureStoreService` đã bị xóa khỏi `01`/`04`, `RULE-03-05` hợp nhất tham chiếu chéo tới `RULE-05-04`. |

**Missing Traceability (Rule tồn tại trong `02`, không được `00` cite) — trạng thái sau khi đóng gap:**

`RULE-03-05` (hợp nhất, xem C3), `RULE-04-11` (mới, REQ-PET-013), `RULE-06-03` (REQ-APT-016), `RULE-08-01` (REQ-WFM-007), `RULE-09-04` (REQ-CLN-009), `RULE-09-09` (REQ-CLN-010), `RULE-12-10` (REQ-INV-013), `RULE-14-05`/`RULE-14-06` (đã gộp citation vào REQ-ORD-003), `RULE-17-06` (giữ nguyên — chỉ là restatement của FSM 8, không cần REQ riêng), `RULE-19-09` (REQ-MEM-009), `RULE-20-05` (REQ-PKG-009), `RULE-20-09` (mới, REQ-PKG-010), `RULE-24-07` (REQ-RPT-007). **13/13 đã đóng.**

**Real FR Gap (đã bổ sung REQ mới vào §5/§11):** REQ-APT-016, REQ-WFM-007, REQ-CLN-009, REQ-CLN-010, REQ-VAC-007, REQ-INV-012, REQ-INV-013, REQ-PRC-009, REQ-RFD-010, REQ-MEM-009, REQ-PKG-009, REQ-PKG-010, REQ-RPT-007, REQ-PRM-008, REQ-PRM-009, REQ-PET-013.

**Còn mở (chưa có RULE-ID/bảng dữ liệu hậu thuẫn, ghi nhận nhưng không chặn Phase 4):**

| ID | Mô tả |
|---|---|
| ~~GAP-PRC-01~~ | **[ĐÃ SỬA — Phase 4]** Thêm bảng `suppliers` (Aggregate Root riêng, `RULE-13-04` hậu thuẫn), `purchase_orders.supplier_id` FK thay thế `supplier_name` tự do. |
| ~~GAP-RFD-02~~ | **[FALSE POSITIVE — đã sửa ở Phase 4]** Audit Phase 3 kết luận sai rằng `ReconcileRefund` không có RULE-ID; thực tế `RULE-17-10` đã cite `ReconcileRefund` tường minh. Đã sửa citation `REQ-RFD-010` tại §11 từ `UNTRACED` sang `RULE-17-10`. |
| ~~GAP-CLN-02~~ | **[ĐÃ SỬA — Phase 4]** Thêm bảng `treatments` (theo mẫu tối giản như `diagnoses`: mô tả phác đồ, liên kết `medical_record_id`/`diagnosis_id`). `medical_records.treatment_plan` giữ nguyên như tóm tắt nhanh, không xóa. |

---

