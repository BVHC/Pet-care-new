# Pet Care Ecosystem — Ubiquitous Language Glossary

Tài liệu này chuẩn hóa toàn bộ thuật ngữ chuyên ngành (Ubiquitous Language) của hệ thống Pet Care Ecosystem theo nguyên lý Domain-Driven Design (DDD). Tất cả các thuật ngữ được phân loại rõ ràng thành: Actor, Aggregate Candidate, Entity Candidate, Value Object Candidate, Command Candidate, Status-Enum, và Domain Event Candidate.

---

## 01. Authentication & OTP

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Customer | Actor | Người sử dụng nền tảng để quản lý thú cưng, đặt lịch và thanh toán dịch vụ. | — | Pet Owner |
| System | Actor | Chủ thể tự động thực hiện các tác vụ nền, kiểm tra hết hạn và xử lý sự kiện hệ thống. | — | Cron / Background Worker |
| Account | Entity Candidate | Tài khoản định danh cho phép User đăng nhập và sử dụng hệ thống. | — | User Account |
| AccountStatus | Status-Enum | Trạng thái vòng đời của tài khoản người dùng. | PENDING_VERIFICATION, ACTIVE, LOCKED | — |
| OTP | Value Object Candidate | Mã xác thực một lần dùng để xác minh số điện thoại hoặc email. | — | Verification Code |
| RegisterAccount | Command Candidate | Thao tác đăng ký tài khoản Customer mới trên nền tảng. | — | SignUp |
| SendRegistrationOTP | Command Candidate | Thao tác hệ thống gửi mã OTP xác thực đăng ký. | — | — |
| ResendOTP | Command Candidate | Thao tác người dùng yêu cầu gửi lại mã OTP mới. | — | — |
| VerifyOTP | Command Candidate | Thao tác xác thực mã OTP do người dùng cung cấp. | — | ValidateOTP |
| CheckOTP | Command Candidate | Thao tác hệ thống kiểm tra tính hợp lệ và thời hạn của OTP. | — | — |
| ExpireOTP | Command Candidate | Thao tác vô hiệu hóa mã OTP khi hết thời gian hiệu lực. | — | — |
| Login | Command Candidate | Thao tác người dùng đăng nhập vào hệ thống. | — | SignIn |
| Logout | Command Candidate | Thao tác đăng xuất và hủy phiên làm việc. | — | SignOut |
| AccountRegistered | Domain Event Candidate | Sự kiện phát sinh khi tài khoản mới được đăng ký. | — | — |
| AccountActivated | Domain Event Candidate | Sự kiện phát sinh khi tài khoản được kích hoạt thành công sau xác thực OTP. | — | — |
| AccountLocked | Domain Event Candidate | Sự kiện phát sinh khi tài khoản bị khóa bởi quản trị viên. | — | — |
| AccountUnlocked | Domain Event Candidate | Sự kiện phát sinh khi tài khoản được mở khóa. | — | — |

---

## 02. Identity & Access Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| PlatformAdmin | Actor | Người quản trị toàn bộ nền tảng ở cấp cao nhất (System Role: `SUPER_ADMIN`, Scope: `PLATFORM`). Quản lý tenant, master catalog, cấu hình toàn cục. | — | Super Admin |
| OrganizationAdmin | Actor | Người quản trị cấp chuỗi/tổ chức doanh nghiệp (System Role: `ORGANIZATION_ADMIN`, Scope: `ORGANIZATION`). Quản lý warehouse trung tâm, các store trực thuộc, nhân sự tenant và chính sách chuỗi. | — | Org Admin |
| StoreManager | Actor | Người quản lý và điều hành vận hành tại một Store chi nhánh cụ thể (System Role: `STORE_MANAGER`, Scope: `STORE`). Thẩm quyền phê duyệt Maker-Checker. | — | Store Admin |
| Receptionist | Actor | Nhân viên tiếp đón khách, check-in và thu ngân tại Store (System Role: `RECEPTIONIST`, Scope: `STORE`). | — | Front Desk |
| Veterinarian | Actor | Bác sĩ thú y phụ trách khám bệnh, chẩn đoán, kê đơn và tiêm phòng tại Store (System Role: `VETERINARIAN`, Scope: `STORE`). | — | Doctor, Vet |
| Groomer | Actor | Chuyên viên spa/làm đẹp thú cưng tại Store (System Role: `GROOMER`, Scope: `STORE`). | — | Pet Stylist |
| InventoryStaff | Actor | Nhân viên quản lý và vận hành kho tại Store hoặc Warehouse trung tâm (Functional Role: `INVENTORY_STAFF`, Scope: `STORE / WAREHOUSE`). | — | Stock Keeper |
| FinanceStaff | Actor | Nhân viên kế toán, tài chính phụ trách chi tiền hoàn, đối soát hóa đơn và quyết toán (Functional Role: `FINANCE_STAFF`, Scope: `ORGANIZATION / STORE`). | — | Accountant |
| RoleScope | Status-Enum | Cấp độ phạm vi dữ liệu và quyền lực áp dụng cho một vai trò người dùng trong hệ thống Multi-tenancy. | PLATFORM, ORGANIZATION, STORE, CUSTOMER | ScopeLevel |
| Role | Entity Candidate | Vai trò xác định nhóm quyền hạn của người dùng gắn với một `RoleScope` cụ thể. | — | User Role |
| Permission | Entity Candidate | Quyền cho phép thực hiện một nghiệp vụ hoặc Command cụ thể. | — | Privilege |
| User | Entity Candidate | Thực thể người dùng được định danh, cấp tài khoản và gán vai trò trong hệ thống. | — | System User |
| ManageUser | Command Candidate | Thao tác quản lý người dùng trong phạm vi quyền hạn được phép. | — | — |
| ManageRole | Command Candidate | Thao tác quản lý vai trò trong phạm vi quyền hạn được phép. | — | — |
| ManagePermission | Command Candidate | Thao tác quản lý quyền hạn trong phạm vi quyền hạn được phép. | — | — |
| LockAccount | Command Candidate | Thao tác khóa tài khoản không cho phép đăng nhập hoặc thao tác. | — | SuspendAccount |
| UnlockAccount | Command Candidate | Thao tác mở khóa tài khoản người dùng. | — | UnbanAccount |
| AssignPermission | Command Candidate | Thao tác phân quyền cho nhân viên trong phạm vi Store/Org. | — | GrantPermission |

---

## 03. Organization & Store Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| StoreStatus | Status-Enum | Trạng thái vận hành của một Store. | ACTIVE, SUSPENDED, DEACTIVATED, ARCHIVED | — |
| Organization | Entity Candidate | Tổ chức doanh nghiệp sở hữu và quản lý một hoặc nhiều Store và Warehouse trung tâm (Tenant Entity). | — | Tenant, Chain |
| Store | Entity Candidate | Cơ sở / chi nhánh thuộc Organization nơi dịch vụ được cung cấp trực tiếp cho khách hàng. | — | Branch, Clinic |
| OrganizationPolicy | Entity Candidate | Chính sách quy định cách Organization vận hành và chia sẻ dữ liệu nội bộ. | — | Org Policy |
| StorePolicy | Entity Candidate | Chính sách quy định cách Store vận hành tại chỗ. | — | Operational Policy |
| OperatingHour | Value Object Candidate | Khung thời gian làm việc quy định của Store trong ngày/tuần. | — | Store Hours |
| StoreResource | Entity Candidate | Tài nguyên phòng khám/bàn grooming chuyên dụng được Store quản lý để tránh xung đột lịch hẹn. | — | Facility Resource |
| CreateOrganization | Command Candidate | Thao tác tạo Organization mới trên nền tảng. | — | — |
| UpdateOrganization | Command Candidate | Thao tác cập nhật thông tin Organization. | — | — |
| ManageOrganizationPolicy| Command Candidate | Thao tác quản lý chính sách của Organization. | — | — |
| CreateStore | Command Candidate | Thao tác tạo Store mới thuộc Organization. | — | — |
| UpdateStore | Command Candidate | Thao tác cập nhật thông tin Store. | — | — |
| ActivateStore | Command Candidate | Thao tác kích hoạt Store đi vào vận hành. | — | — |
| SuspendStore | Command Candidate | Thao tác tạm đình chỉ hoạt động của Store. | — | — |
| DeactivateStore | Command Candidate | Thao tác ngừng kích hoạt Store. | — | — |
| ArchiveStore | Command Candidate | Thao tác lưu trữ / đóng cửa vĩnh viễn Store. | — | — |
| ConfigureOperatingHour | Command Candidate | Thao tác thiết lập khung giờ hoạt động của Store. | — | — |
| ConfigureStoreService | Command Candidate | Thao tác cấu hình danh mục dịch vụ cung cấp tại Store. | — | — |
| ConfigureStoreResource | Command Candidate | Thao tác cấu hình tài nguyên vật chất của Store. | — | — |
| ConfigureStorePolicy | Command Candidate | Thao tác cấu hình chính sách vận hành tại Store. | — | — |
| StoreCreated | Domain Event Candidate | Sự kiện phát sinh khi Store mới được tạo. | — | — |
| StoreActivated | Domain Event Candidate | Sự kiện phát sinh khi Store được kích hoạt hoạt động. | — | — |
| StoreSuspended | Domain Event Candidate | Sự kiện phát sinh khi Store bị tạm đình chỉ hoạt động. | — | — |
| StoreDeactivated | Domain Event Candidate | Sự kiện phát sinh khi Store bị ngừng hoạt động. | — | — |
| StoreArchived | Domain Event Candidate | Sự kiện phát sinh khi Store bị lưu trữ vĩnh viễn. | — | — |

---

## 04. Customer & Pet Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Customer | Actor | Người sử dụng nền tảng sở hữu tài khoản và hồ sơ thú cưng cá nhân (Scope: User/Account). | — | Pet Owner |
| Caregiver | Actor | Người chăm sóc được chủ thú cưng ủy quyền thông qua `PetCaregiverDelegation` để thay mặt thao tác với Pet. | — | Authorized Person |
| PetCaregiverDelegation| Aggregate Candidate | Hồ sơ quan hệ ủy quyền chăm sóc Pet giữa chủ thú cưng (Customer) và người được ủy quyền (Caregiver). | — | DelegationRecord, CaregiverInvitation |
| CaregiverStatus | Status-Enum | Trạng thái vòng đời của lời mời và quan hệ ủy quyền Caregiver. | INVITED, ACTIVE, REJECTED, EXPIRED, REVOKED | — |
| Pet | Entity Candidate | Thú cưng được Customer đăng ký quản lý và sử dụng dịch vụ trong hệ sinh thái. | — | Animal Patient |
| CustomerProfile | Value Object Candidate | Thông tin định danh và liên hệ của Customer. | — | Profile Info |
| PetOwnership | Entity Candidate | Quyền sở hữu và trách nhiệm pháp lý của Customer đối với Pet. | — | Pet Owner Link |
| AuthorizedPet | Value Object Candidate | Phạm vi Pet cụ thể mà Caregiver được cấp quyền thao tác. | — | Delegated Pet |
| ManageCustomerProfile | Command Candidate | Thao tác quản lý hồ sơ thông tin Customer. | — | — |
| AddPet | Command Candidate | Thao tác thêm Pet mới vào danh sách quản lý. | — | CreatePet |
| UpdatePet | Command Candidate | Thao tác cập nhật thông tin Pet. | — | — |
| ViewPet | Command Candidate | Thao tác xem thông tin chi tiết của Pet. | — | — |
| ManagePetOwnership | Command Candidate | Thao tác chuyển giao hoặc quản lý quyền sở hữu Pet. | — | — |
| InviteCaregiver | Command Candidate | Thao tác Customer gửi lời mời ủy quyền cho Caregiver. | — | DelegateCaregiver |
| AcceptCaregiverInvitation| Command Candidate | Thao tác Caregiver chấp nhận ủy quyền (chuyển sang ACTIVE). | — | ConfirmCaregiver |
| RejectCaregiverInvitation| Command Candidate | Thao tác Caregiver từ chối lời mời ủy quyền. | — | DeclineCaregiver |
| ProcessInvitationExpiry | Command Candidate | Thao tác hệ thống xử lý lời mời ủy quyền hết hạn. | — | — |
| RevokeCaregiver | Command Candidate | Thao tác Customer thu hồi quyền ủy quyền của Caregiver. | — | RemoveCaregiver |
| PerformDelegatedAction | Command Candidate | Thao tác Caregiver thực hiện hành vi trong phạm vi được ủy quyền. | — | — |
| SearchCustomerPet | Command Candidate | Thao tác tra cứu thông tin Customer hoặc Pet. | — | — |
| CaregiverInvited | Domain Event Candidate | Sự kiện phát sinh khi lời mời ủy quyền được gửi đi. | — | — |
| CaregiverInvitationAccepted| Domain Event Candidate | Sự kiện phát sinh khi Caregiver chấp nhận và kích hoạt ủy quyền. | — | — |
| CaregiverInvitationRejected| Domain Event Candidate | Sự kiện phát sinh khi Caregiver từ chối lời mời ủy quyền. | — | — |
| CaregiverInvitationExpired | Domain Event Candidate | Sự kiện phát sinh khi lời mời ủy quyền hết thời hạn. | — | — |
| CaregiverRevoked | Domain Event Candidate | Sự kiện phát sinh khi Customer thu hồi quyền ủy quyền. | — | — |

---

## 05. Service & Product Catalog

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| ProductCatalog | Entity Candidate | Danh mục sản phẩm mẫu được quản lý ở cấp Platform. | — | Master Catalog |
| Product | Entity Candidate | Sản phẩm hàng hóa được phân phối hoặc bán lẻ trong hệ thống. | — | Item, Merchandise |
| Service | Entity Candidate | Dịch vụ chăm sóc thú cưng (khám, tiêm, grooming) được cung cấp. | — | Service Item |
| ServiceAvailability | Value Object Candidate | Điều kiện xác định dịch vụ có thể cung cấp tại Store hay không. | — | Availability State |
| ProductPrice | Value Object Candidate | Giá bán của sản phẩm được cấu hình riêng tại từng Store. | — | Store Product Price |
| ServicePrice | Value Object Candidate | Giá dịch vụ được cấu hình riêng tại từng Store. | — | Store Service Price |
| ManageProductCatalog | Command Candidate | Thao tác Platform Admin quản lý Product Catalog mẫu. | — | — |
| GrantProductCatalogAccess | Command Candidate | Thao tác cấp quyền sử dụng Product Catalog cho Organization. | — | — |
| ManageProduct | Command Candidate | Thao tác quản lý sản phẩm trong phạm vi Organization. | — | — |
| ManageService | Command Candidate | Thao tác quản lý dịch vụ trong phạm vi Organization. | — | — |
| ConfigureServiceAvailability| Command Candidate | Thao tác cấu hình trạng thái khả dụng của Service tại Store. | — | — |
| ConfigureProductPrice | Command Candidate | Thao tác cấu hình giá sản phẩm tại Store. | — | — |
| ConfigureServicePrice | Command Candidate | Thao tác cấu hình giá dịch vụ tại Store. | — | — |
| ViewProduct | Command Candidate | Thao tác xem danh mục sản phẩm. | — | — |
| ViewService | Command Candidate | Thao tác xem danh mục dịch vụ. | — | — |

---

## 06. Appointment & Scheduling

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Receptionist | Actor | Nhân viên tiếp đón khách, đặt lịch và thực hiện check-in/out tại Store. | — | Front Desk Staff |
| Appointment | Aggregate Candidate | Cuộc hẹn dịch vụ giữa khách hàng và Store cho Pet vào khung giờ cụ thể. | — | Booking |
| AppointmentStatus | Status-Enum | Trạng thái vòng đời của một cuộc hẹn. | BOOKED, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW | — |
| Schedule | Aggregate Candidate | Lịch vận hành tổng thể và phân bổ thời gian của Store và nhân sự. | — | Master Schedule |
| StaffAssignment | Entity Candidate | Việc phân công nhân viên chuyên môn vào một ca hoặc lịch hẹn. | — | Staff Allocation |
| Availability | Value Object Candidate | Khoảng thời gian trống khả dụng để nhận lịch hẹn tại Store. | — | Free Slot |
| BookAppointment | Command Candidate | Thao tác tạo đặt lịch hẹn dịch vụ mới. | — | CreateAppointment |
| ViewAppointment | Command Candidate | Thao tác xem thông tin chi tiết lịch hẹn. | — | — |
| ConfirmAppointment | Command Candidate | Thao tác xác nhận lịch hẹn (bởi Store hoặc tự động). | — | — |
| UpdateAppointment | Command Candidate | Thao tác cập nhật chi tiết lịch hẹn. | — | — |
| CancelAppointment | Command Candidate | Thao tác hủy lịch hẹn đã đặt. | — | — |
| RescheduleAppointment | Command Candidate | Thao tác thay đổi khung thời gian của lịch hẹn. | — | ChangeAppointment |
| CheckInAppointment | Command Candidate | Thao tác tiếp nhận khách và thú cưng đến Store theo lịch hẹn. | — | Check-in |
| StartAppointmentService | Command Candidate | Thao tác nhân viên bắt đầu thực hiện dịch vụ cho thú cưng. | — | BeginService |
| CheckOutAppointment | Command Candidate | Thao tác hoàn tất dịch vụ và tiếp nhận bàn giao sau lịch hẹn. | — | Check-out |
| MarkNoShow | Command Candidate | Thao tác đánh dấu khách vắng mặt không đến lịch hẹn. | — | — |
| ManageStoreSchedule | Command Candidate | Thao tác quản lý cấu hình lịch của Store. | — | — |
| AssignStaff | Command Candidate | Thao tác phân công nhân viên phục vụ lịch hẹn. | — | — |
| CoordinateSchedule | Command Candidate | Thao tác điều phối lịch làm việc và lịch hẹn tại Store. | — | — |
| CheckAvailability | Command Candidate | Thao tác kiểm tra thời gian trống khả dụng. | — | — |
| AppointmentBooked | Domain Event Candidate | Sự kiện phát sinh khi lịch hẹn được tạo thành công. | — | — |
| AppointmentConfirmed | Domain Event Candidate | Sự kiện phát sinh khi lịch hẹn được xác nhận. | — | — |
| AppointmentCheckedIn | Domain Event Candidate | Sự kiện phát sinh khi khách đã check-in tại Store. | — | — |
| AppointmentStarted | Domain Event Candidate | Sự kiện phát sinh khi dịch vụ được bắt đầu thực hiện. | — | — |
| AppointmentCompleted | Domain Event Candidate | Sự kiện phát sinh khi lịch hẹn hoàn thành đầy đủ. | — | — |
| AppointmentCancelled | Domain Event Candidate | Sự kiện phát sinh khi lịch hẹn bị hủy. | — | — |
| AppointmentRescheduled | Domain Event Candidate | Sự kiện phát sinh khi lịch hẹn được dời thời gian. | — | — |
| AppointmentNoShow | Domain Event Candidate | Sự kiện phát sinh khi khách không đến lịch hẹn. | — | — |
| AppointmentReminder | Domain Event Candidate | Sự kiện nhắc nhở khách hàng trước giờ hẹn. | — | — |

---

## 07. Walk-in & Queue Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| WalkIn | Aggregate Candidate | Yêu cầu sử dụng dịch vụ trực tiếp tại quầy không qua đặt lịch trước. | — | Direct Visit |
| Queue | Aggregate Candidate | Hàng đợi quản lý danh sách khách đang chờ phục vụ tại Store. | — | Waiting Line |
| QueuePosition | Value Object Candidate | Vị trí thứ tự cụ thể của khách trong hàng đợi. | — | Ticket Number |
| QueueEntryStatus | Status-Enum | Trạng thái lượt xếp hàng của khách trong Queue. | WAITING, CALLED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW | — |
| RequestWalkInService | Command Candidate | Thao tác khách hàng yêu cầu dịch vụ trực tiếp tại quầy. | — | — |
| CreateWalkIn | Command Candidate | Thao tác tiếp tân tạo hồ sơ tiếp nhận lượt Walk-in. | — | — |
| EnqueueCustomer | Command Candidate | Thao tác xếp khách vào hàng đợi của Store. | — | AddToQueue |
| CallCustomer | Command Candidate | Thao tác gọi khách tiếp theo vào phục vụ. | — | NextCustomer |
| CheckInWalkIn | Command Candidate | Thao tác tiếp nhận khách Walk-in vào thực hiện dịch vụ. | — | — |
| CoordinateQueue | Command Candidate | Thao tác Store Manager điều phối thứ tự hàng đợi. | — | — |
| ManageQueueOrder | Command Candidate | Thao tác hệ thống duy trì thứ tự FIFO trong hàng đợi. | — | — |
| SendTurnNotification | Command Candidate | Thao tác gửi thông báo đến lượt phục vụ cho khách. | — | — |
| TurnNotification | Domain Event Candidate | Sự kiện thông báo khi đến lượt khách được phục vụ. | — | Queue Alert |

---

## 08. Workforce Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Staff | Entity Candidate | Nhân sự làm việc tại Organization hoặc Store. | — | Employee |
| WorkSchedule | Entity Candidate | Lịch làm việc và ca trực được phân công của Staff. | — | Roster |
| Leave | Entity Candidate | Khoảng thời gian nghỉ phép được phê duyệt của Staff. | — | Time Off |
| StaffAbsence | Entity Candidate | Ghi nhận tình trạng Staff vắng mặt đột xuất trong ca trực. | — | Absence Record |
| StaffReplacement | Entity Candidate | Việc điều động Staff khác vào thay thế ca vắng mặt. | — | Backup Staff |
| ManageStaff | Command Candidate | Thao tác quản lý danh sách nhân sự. | — | — |
| CreateStaff | Command Candidate | Thao tác tạo hồ sơ nhân sự mới. | — | — |
| UpdateStaff | Command Candidate | Thao tác cập nhật hồ sơ nhân sự. | — | — |
| AssignStaffToStore | Command Candidate | Thao tác phân công nhân sự vào Store làm việc. | — | — |
| ManageWorkSchedule | Command Candidate | Thao tác quản lý lịch làm việc của nhân sự. | — | — |
| ManageLeave | Command Candidate | Thao tác quản lý và duyệt nghỉ phép của nhân sự. | — | — |
| HandleStaffAbsence | Command Candidate | Thao tác ghi nhận trường hợp nhân sự vắng mặt. | — | — |
| AssignStaffReplacement | Command Candidate | Thao tác phân công nhân viên thay thế ca trực. | — | — |
| ViewWorkSchedule | Command Candidate | Thao tác xem lịch làm việc cá nhân hoặc Store. | — | — |

---

## 09. Veterinary / Clinical Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Veterinarian | Actor | Bác sĩ thú y chịu trách nhiệm khám, chẩn đoán và điều trị Pet. | — | Vet |
| MedicalRecord | Aggregate Candidate | Bệnh án ghi nhận toàn bộ quá trình khám chữa bệnh của Pet. | — | Clinical Record |
| Symptom | Entity Candidate | Triệu chứng lâm sàng được ghi nhận trong quá trình khám. | — | Clinical Finding |
| ExaminationResult | Entity Candidate | Kết quả khám tổng quát hoặc kết quả cận lâm sàng. | — | Test Result |
| Diagnosis | Entity Candidate | Kết luận bệnh lý do Bác sĩ thú y xác định. | — | — |
| Treatment | Entity Candidate | Phác đồ và thủ thuật điều trị áp dụng cho Pet. | — | Treatment Plan |
| Prescription | Entity Candidate | Đơn thuốc do Bác sĩ thú y kê cho thú cưng. | — | Drug Order |
| MedicalHistory | Entity Candidate | Toàn bộ lịch sử y tế và các đợt điều trị của Pet. | — | Patient History |
| FollowUp | Entity Candidate | Kế hoạch và lịch hẹn tái khám sau điều trị. | — | Follow-up Plan |
| ExaminePet | Command Candidate | Thao tác khám lâm sàng cho thú cưng. | — | — |
| RecordSymptom | Command Candidate | Thao tác ghi nhận triệu chứng bệnh lý. | — | — |
| RecordExaminationResult | Command Candidate | Thao tác ghi nhận kết quả khám và xét nghiệm. | — | — |
| DiagnosePet | Command Candidate | Thao tác xác định chẩn đoán bệnh cho Pet. | — | — |
| CreateTreatment | Command Candidate | Thao tác thiết lập phác đồ điều trị. | — | — |
| CreatePrescription | Command Candidate | Thao tác kê đơn thuốc cho Pet. | — | — |
| UpdateMedicalRecord | Command Candidate | Thao tác cập nhật bệnh án thú cưng. | — | — |
| ViewMedicalHistory | Command Candidate | Thao tác tra cứu lịch sử y tế của Pet. | — | — |
| CreateFollowUp | Command Candidate | Thao tác lập lịch tái khám cho Pet. | — | — |

---

## 10. Vaccination Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Vaccination | Entity Candidate | Hồ sơ ghi nhận một lần tiêm phòng vaccine cho Pet. | — | Vaccine Shot |
| Vaccine | Entity Candidate | Loại thuốc vaccine phòng bệnh cho thú cưng. | — | Vaccine Item |
| VaccineBatch | Entity Candidate | Lô nhập vaccine cụ thể gắn với hạn sử dụng trong kho. | — | Vaccine Lot |
| VaccineExpiry | Value Object Candidate | Hạn sử dụng của lô vaccine. | — | Expiration Date |
| VaccinationSchedule | Entity Candidate | Lộ trình và lịch tiêm phòng nhắc lại của Pet. | — | Immunization Schedule |
| CheckVaccinationSchedule| Command Candidate | Thao tác kiểm tra lịch tiêm chủng của Pet. | — | — |
| AdministerVaccine | Command Candidate | Thao tác thực hiện tiêm phòng cho Pet. | — | Vaccinate |
| RecordVaccination | Command Candidate | Thao tác ghi nhận mũi tiêm vào hồ sơ tiêm chủng. | — | — |
| ScheduleNextVaccination | Command Candidate | Thao tác thiết lập ngày tiêm mũi nhắc lại tiếp theo. | — | — |
| ManageVaccine | Command Candidate | Thao tác quản lý danh mục vaccine. | — | — |
| ManageVaccineBatch | Command Candidate | Thao tác quản lý lô vaccine nhập kho. | — | — |
| ManageVaccineExpiry | Command Candidate | Thao tác quản lý hạn sử dụng của vaccine. | — | — |
| ViewVaccinationSchedule | Command Candidate | Thao tác xem lịch tiêm chủng của Pet. | — | — |
| VaccineReminder | Domain Event Candidate | Sự kiện nhắc nhở lịch tiêm phòng định kỳ. | — | — |

---

## 11. Grooming Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Groomer | Actor | Nhân viên chuyên môn thực hiện dịch vụ làm đẹp, cắt tỉa lông cho Pet tại Store (System Role: `GROOMER`, Scope: `STORE`). | — | Pet Stylist |
| Grooming | Aggregate Candidate | Phiên thực hiện dịch vụ chăm sóc ngoại hình và vệ sinh cho Pet. | — | Grooming Session |
| GroomingStatus | Status-Enum | Trạng thái vòng đời của một ca dịch vụ grooming. | WAITING, IN_PROGRESS, AWAITING_CUSTOMER_APPROVAL, COMPLETED, CANCELLED | — |
| GroomingResult | Entity Candidate | Kết quả tình trạng và hình ảnh sau khi hoàn thành grooming. | — | Grooming Outcome |
| AdditionalService | Entity Candidate | Dịch vụ phát sinh thêm trong quá trình grooming (gỡ rối, spa đặc biệt). | — | Extra Service |
| CheckInGrooming | Command Candidate | Thao tác tiếp nhận Pet vào khu vực grooming. | — | — |
| InspectPet | Command Candidate | Thao tác kiểm tra thể trạng và da lông Pet trước grooming. | — | Pre-Grooming Check |
| PerformGrooming | Command Candidate | Thao tác tiến hành thực hiện các bước grooming (chuyển sang IN_PROGRESS). | — | — |
| UpdateGroomingResult | Command Candidate | Thao tác cập nhật tiến độ và kết quả phiên grooming. | — | — |
| AddGroomingService | Command Candidate | Thao tác đề xuất dịch vụ phát sinh (chuyển sang AWAITING_CUSTOMER_APPROVAL). | — | — |
| ConfirmAdditionalService | Command Candidate | Thao tác khách hàng xác nhận đồng ý dịch vụ phát sinh. | — | — |
| RejectAdditionalService | Command Candidate | Thao tác khách hàng từ chối dịch vụ phát sinh thêm. | — | — |
| CompleteGrooming | Command Candidate | Thao tác hoàn tất quy trình grooming (chuyển sang COMPLETED). | — | — |
| CancelGrooming | Command Candidate | Thao tác hủy phiên dịch vụ grooming (chuyển sang CANCELLED). | — | — |
| GroomingCheckedIn | Domain Event Candidate | Sự kiện phát sinh khi thú cưng được tiếp nhận vào phòng grooming. | — | — |
| GroomingStarted | Domain Event Candidate | Sự kiện phát sinh khi bắt đầu thực hiện ca làm đẹp. | — | — |
| AdditionalServiceRequested | Domain Event Candidate | Sự kiện phát sinh khi Groomer đề xuất dịch vụ phát sinh thêm. | — | — |
| AdditionalServiceConfirmed | Domain Event Candidate | Sự kiện phát sinh khi khách hàng đồng ý dịch vụ phát sinh. | — | — |
| AdditionalServiceRejected | Domain Event Candidate | Sự kiện phát sinh khi khách hàng từ chối dịch vụ phát sinh. | — | — |
| GroomingCompleted | Domain Event Candidate | Sự kiện phát sinh khi ca làm đẹp hoàn tất toàn bộ. | — | — |
| GroomingCancelled | Domain Event Candidate | Sự kiện phát sinh khi ca làm đẹp bị hủy bỏ. | — | — |

---

## 12. Inventory & Warehouse Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| InventoryStaff | Actor | Nhân viên quản lý kho: nhập/xuất/kiểm kê/chuyển kho tại Store/Warehouse. | — | Warehouse Staff |
| Inventory | Aggregate Candidate | Quản lý số lượng tồn kho thực tế và khả dụng của sản phẩm tại một vị trí kho. | — | Stock |
| Warehouse | Aggregate Candidate | Kho tổng / kho trung tâm cấp Organization cung cấp hàng cho các Store. | — | Central Warehouse |
| StockTransfer | Aggregate Candidate | Phiếu điều chuyển hàng hóa giữa Store↔Store hoặc Warehouse→Store (Replenishment). | — | Inventory Transfer |
| StockTransferStatus | Status-Enum | Trạng thái vòng đời của phiếu điều chuyển kho. | REQUESTED, APPROVED, REJECTED, IN_TRANSIT, RECEIVED, DISCREPANCY, CANCELLED | — |
| InventoryAdjustment | Entity Candidate | Phiếu điều chỉnh cân bằng lại số lượng tồn kho thực tế sau kiểm kê hoặc xử lý hao hụt chuyển kho. | — | Stock Adjustment |
| Expiry | Value Object Candidate | Thông tin về thời hạn sử dụng của lô sản phẩm. | — | Expiration Date |
| ReceiveInventory | Command Candidate | Thao tác nhập hàng vào kho. | — | Inward Stock |
| IssueInventory | Command Candidate | Thao tác xuất hàng ra khỏi kho. | — | Outward Stock |
| AdjustInventory | Command Candidate | Thao tác lập phiếu điều chỉnh số lượng tồn kho. | — | — |
| CountInventory | Command Candidate | Thao tác thực hiện kiểm kê kho thực tế. | — | Stocktake |
| TrackInventory | Command Candidate | Thao tác tra cứu và theo dõi số lượng tồn kho. | — | — |
| TrackBatch | Command Candidate | Thao tác theo dõi thông tin lô hàng nhập. | — | — |
| TrackExpiry | Command Candidate | Thao tác theo dõi hạn dùng của sản phẩm trong kho. | — | — |
| CreateStockTransfer | Command Candidate | Thao tác lập phiếu yêu cầu chuyển kho. | — | — |
| ApproveStockTransfer | Command Candidate | Thao tác Store Manager phê duyệt chuyển kho. | — | — |
| RejectStockTransfer | Command Candidate | Thao tác Store Manager từ chối phiếu chuyển kho. | — | — |
| CancelStockTransfer | Command Candidate | Thao tác hủy phiếu yêu cầu chuyển kho. | — | — |
| ShipStockTransfer | Command Candidate | Thao tác xuất hàng vận chuyển sang kho đích (chuyển sang IN_TRANSIT). | — | DispatchStockTransfer |
| ReceiveStockTransfer | Command Candidate | Thao tác tiếp nhận và nhập kho hàng chuyển đến nguyên vẹn (chuyển sang RECEIVED). | — | — |
| ReceiveStockTransferWithDiscrepancy | Command Candidate | Thao tác ghi nhận phát hiện hàng hóa chuyển kho bị hư hỏng/thiếu hụt (chuyển sang DISCREPANCY). | — | — |
| ResolveStockTransferDiscrepancy | Command Candidate | Thao tác Store Manager giải quyết hao hụt chuyển kho qua phiếu InventoryAdjustment (chuyển sang RECEIVED). | — | — |
| ManageWarehouse | Command Candidate | Thao tác quản lý thông tin kho tổng Warehouse. | — | — |
| ReceiveAtWarehouse | Command Candidate | Thao tác nhập hàng trực tiếp vào kho tổng Warehouse. | — | — |
| ApproveInventoryAdjustment| Command Candidate | Thao tác phê duyệt phiếu điều chỉnh tồn kho. | — | — |
| LowStock | Domain Event Candidate | Sự kiện tồn kho khả dụng giảm xuống dưới mức an toàn. | — | Low Stock Alert |
| StockTransferCreated | Domain Event Candidate | Sự kiện phát sinh khi tạo phiếu chuyển kho. | — | — |
| StockTransferApproved | Domain Event Candidate | Sự kiện phát sinh khi phiếu chuyển kho được duyệt. | — | — |
| StockTransferRejected | Domain Event Candidate | Sự kiện phát sinh khi phiếu chuyển kho bị từ chối. | — | — |
| StockTransferCancelled | Domain Event Candidate | Sự kiện phát sinh khi phiếu chuyển kho bị hủy. | — | — |
| StockTransferShipped | Domain Event Candidate | Sự kiện phát sinh khi hàng bắt đầu được vận chuyển. | — | — |
| StockTransferReceived | Domain Event Candidate | Sự kiện phát sinh khi kho đích đã nhận đủ hàng. | — | — |
| StockTransferDiscrepancyReported | Domain Event Candidate | Sự kiện phát sinh khi phát hiện hàng hóa chuyển kho bị hư hỏng hoặc sai lệch số lượng. | — | — |
| StockTransferDiscrepancyResolved | Domain Event Candidate | Sự kiện phát sinh khi sự cố sai lệch chuyển kho đã được giải quyết qua điều chỉnh kiểm kê. | — | — |

---

## 13. Procurement Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Supplier | Entity Candidate | Nhà cung cấp hàng hóa / vật tư cho Organization. | — | Vendor |
| PurchaseRequest | Entity Candidate | Phiếu yêu cầu mua hàng nội bộ do nhân viên kho đề xuất. | — | Procurement Request |
| PurchaseRequestStatus | Status-Enum | Trạng thái vòng đời của phiếu yêu cầu mua hàng. | DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED | — |
| PurchaseOrder | Aggregate Candidate | Đơn đặt hàng chính thức gửi tới nhà cung cấp (Supplier). | — | PO |
| PurchaseOrderStatus | Status-Enum | Trạng thái vòng đời của đơn đặt hàng nhà cung cấp. | ISSUED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED | — |
| CreatePurchaseRequest | Command Candidate | Thao tác tạo phiếu đề xuất mua hàng. | — | — |
| SubmitPurchaseRequest | Command Candidate | Thao tác gửi yêu cầu mua hàng lên cấp quản lý. | — | — |
| ApprovePurchaseRequest | Command Candidate | Thao tác Store Manager duyệt yêu cầu mua hàng. | — | — |
| RejectPurchaseRequest | Command Candidate | Thao tác từ chối yêu cầu mua hàng. | — | — |
| CancelPurchaseRequest | Command Candidate | Thao tác hủy yêu cầu mua hàng. | — | — |
| CreatePurchaseOrder | Command Candidate | Thao tác lập đơn đặt hàng nhà cung cấp. | — | — |
| TrackPurchaseOrder | Command Candidate | Thao tác theo dõi tiến độ đơn đặt hàng. | — | — |
| ReceiveGoods | Command Candidate | Thao tác tiếp nhận và kiểm tra hàng giao từ Supplier. | — | Receive PO |
| InspectGoods | Command Candidate | Thao tác kiểm tra chất lượng hàng hóa nhập. | — | Quality Check |
| CancelPurchaseOrder | Command Candidate | Thao tác hủy đơn đặt hàng nhà cung cấp khi chưa nhận hàng. | — | — |
| CancelRemainingPurchaseOrder | Command Candidate | Thao tác hủy số lượng hàng còn lại chưa giao khi đã nhận một phần (chuyển sang CLOSED). | — | ClosePO |
| UpdateInventory | Command Candidate | Thao tác cập nhật tăng tồn kho sau khi nhận hàng từ PO. | — | — |
| ManageSupplier | Command Candidate | Thao tác quản lý danh mục nhà cung cấp. | — | — |
| PurchaseRequestApproved | Domain Event Candidate | Sự kiện phát sinh khi yêu cầu mua hàng được duyệt. | — | — |
| GoodsReceived | Domain Event Candidate | Sự kiện phát sinh khi hàng mua từ nhà cung cấp đã nhập kho. | — | — |
| PurchaseOrderRemainingCancelled | Domain Event Candidate | Sự kiện phát sinh khi phần hàng chưa giao của đơn đặt hàng bị hủy và đóng đơn. | — | — |

---

## 14. Order Management (v1 In-Store Fulfillment)

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Order | Aggregate Candidate | Đơn hàng mua sản phẩm hoặc gói dịch vụ do khách hàng tạo. | — | Sales Order |
| OrderStatus | Status-Enum | Trạng thái vòng đời của đơn hàng trong v1 (In-store fulfillment). | PENDING_PAYMENT, PAID, CONFIRMED, PROCESSING, READY, DELIVERED, CANCELLED, REFUNDED | — |
| CheckoutOrder | Command Candidate | Thao tác xác nhận thông tin giỏ hàng và tạo đơn hàng. | — | Checkout |
| CreateOrder | Command Candidate | Thao tác tạo đơn hàng mới. | — | — |
| ViewOrder | Command Candidate | Thao tác tra cứu thông tin chi tiết đơn hàng. | — | — |
| CancelOrder | Command Candidate | Thao tác hủy đơn hàng khi chưa bàn giao hoặc chưa thanh toán. | — | — |
| ProcessOrderTimeout | Command Candidate | Thao tác hệ thống tự động hủy đơn hàng và giải phóng tồn kho giữ chỗ sau 15 phút chưa thanh toán. | — | ExpireOrder |
| CancelOrderWithRefund | Command Candidate | Thao tác Store Manager / Tiếp tân hủy đơn hàng đang soạn hoặc đã sẵn sàng và kích hoạt hoàn tiền. | — | CancelAndRefundOrder |
| ConfirmOrder | Command Candidate | Thao tác xác nhận đơn hàng sau khi đã thanh toán thành công. | — | — |
| ProcessOrder | Command Candidate | Thao tác chuyển đơn hàng vào giai đoạn soạn hàng tại Store. | — | — |
| PrepareProductOrder | Command Candidate | Thao tác nhân viên kho đóng gói sản phẩm (chuyển sang READY). | — | Pack Order |
| CompleteStoreOrder | Command Candidate | Thao tác bàn giao hàng cho khách tại quầy (chuyển sang DELIVERED). | — | Fulfill Order |
| SendOrderNotification | Command Candidate | Thao tác gửi thông báo cập nhật trạng thái đơn hàng. | — | — |
| OrderCreated | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng mới được tạo. | — | — |
| OrderPaid | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng được thanh toán thành công. | — | — |
| OrderConfirmed | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng được xác nhận hợp lệ. | — | — |
| OrderProcessed | Domain Event Candidate | Sự kiện phát sinh khi bắt đầu xử lý đóng gói hàng. | — | — |
| ProductOrderPrepared | Domain Event Candidate | Sự kiện phát sinh khi hàng đã sẵn sàng nhận tại quầy (READY). | — | — |
| OrderDelivered | Domain Event Candidate | Sự kiện phát sinh khi hàng đã bàn giao thành công cho khách. | — | — |
| OrderCancelled | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng bị hủy. | — | — |
| OrderTimedOut | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng hết hạn thanh toán 15 phút và bị hủy tự động. | — | — |
| OrderRefunded | Domain Event Candidate | Sự kiện phát sinh khi đơn hàng được hoàn tiền đầy đủ. | — | — |
| OrderNotification | Domain Event Candidate | Sự kiện thông báo trạng thái đơn hàng tới khách hàng. | — | — |

---

## 15. Billing & Invoice Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| FinanceStaff | Actor | Nhân sự tài chính quản lý hóa đơn, đối soát và xử lý hoàn tiền (Scope: Organization/Store). | — | Accountant |
| Invoice | Aggregate Candidate | Hóa đơn tài chính ghi nhận nghĩa vụ thanh toán của Customer đối với Store. | — | Bill |
| InvoiceStatus | Status-Enum | Trạng thái vòng đời của hóa đơn tài chính. | DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID, REFUNDED | — |
| Discount | Value Object Candidate | Khoản giảm trừ giá trị áp dụng trên hóa đơn từ voucher/khuyến mãi. | — | Price Reduction |
| PaymentObligation | Value Object Candidate | Nghĩa vụ số tiền khách hàng còn phải thanh toán cho hóa đơn. | — | Amount Due |
| CreateInvoice | Command Candidate | Thao tác tạo hóa đơn dạng bản nháp (DRAFT). | — | DraftInvoice |
| DiscardInvoice | Command Candidate | Thao tác hủy bỏ bản nháp hóa đơn tạo sai (chuyển sang VOID). | — | CancelDraftInvoice |
| AddServiceToInvoice | Command Candidate | Thao tác thêm dịch vụ vào hóa đơn. | — | — |
| AddProductToInvoice | Command Candidate | Thao tác thêm sản phẩm vào hóa đơn. | — | — |
| ApplyDiscount | Command Candidate | Thao tác áp dụng mã khuyến mãi/giảm giá vào hóa đơn. | — | — |
| IssueInvoice | Command Candidate | Thao tác phát hành hóa đơn chính thức (chuyển sang ISSUED). | — | PublishInvoice |
| VoidInvoice | Command Candidate | Thao tác vô hiệu hóa/hủy hóa đơn đã phát hành (chuyển sang VOID). | — | InvalidateInvoice |
| VoidPartiallyPaidInvoice | Command Candidate | Thao tác vô hiệu hóa hóa đơn thanh toán một phần sau khi đã hoàn trả đủ tiền đã nhận. | — | — |
| ReconcileInvoice | Command Candidate | Thao tác đối soát số liệu hóa đơn với doanh thu thực tế. | — | — |
| ViewInvoice | Command Candidate | Thao tác xem chi tiết nội dung hóa đơn. | — | — |
| InvoiceCreated | Domain Event Candidate | Sự kiện phát sinh khi hóa đơn được tạo nháp. | — | — |
| InvoiceIssued | Domain Event Candidate | Sự kiện phát sinh khi hóa đơn được phát hành chính thức. | — | — |
| InvoicePartiallyPaid | Domain Event Candidate | Sự kiện phát sinh khi hóa đơn nhận thanh toán một phần tiền. | — | — |
| InvoicePaid | Domain Event Candidate | Sự kiện phát sinh khi hóa đơn được thanh toán đủ 100% số tiền. | — | — |
| InvoiceVoided | Domain Event Candidate | Sự kiện phát sinh khi hóa đơn bị vô hiệu hóa. | — | — |
| InvoiceDraftDiscarded | Domain Event Candidate | Sự kiện phát sinh khi bản nháp hóa đơn bị hủy. | — | — |
| InvoiceRefunded | Domain Event Candidate | Sự kiện phát sinh khi toàn bộ thanh toán của hóa đơn đã hoàn tiền. | — | — |

---

## 16. Payment Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Payment | Aggregate Candidate | Giao dịch tài chính thanh toán tiền của Customer cho nghĩa vụ thanh toán. | — | Transaction |
| PaymentStatus | Status-Enum | Trạng thái vòng đời của một giao dịch thanh toán. | PENDING, PROCESSING, SUCCESS, PARTIALLY_REFUNDED, FAILED, CANCELLED, REFUNDED | — |
| RemainingRefundableAmount| Value Object Candidate | Số tiền còn lại có thể hoàn trả của giao dịch (`TotalAmount - Sum(CompletedRefunds)`). | — | Refundable Balance |
| CashPayment | Value Object Candidate | Phương thức thanh toán trực tiếp bằng tiền mặt tại quầy. | — | Cash Method |
| PaymentMethod | Value Object Candidate | Phương thức thanh toán sử dụng (CASH, ONLINE_GATEWAY). | — | Channel |
| MakePayment | Command Candidate | Thao tác khách hàng khởi tạo thanh toán qua cổng điện tử. | — | PayOnline |
| RecordCashPayment | Command Candidate | Thao tác thu ngân ghi nhận thanh toán tiền mặt tại quầy. | — | PayCash |
| VerifyPayment | Command Candidate | Thao tác xác minh tính hợp lệ của giao dịch thanh toán. | — | — |
| ReceivePaymentCallback | Command Candidate | Thao tác hệ thống tiếp nhận Webhook kết quả từ cổng thanh toán. | — | HandleCallback |
| SettlePayment | Command Candidate | Thao tác quyết toán xác nhận giao dịch thanh toán thành công (SUCCESS). | — | ConfirmPayment |
| CancelPayment | Command Candidate | Thao tác hủy giao dịch thanh toán đang ở trạng thái PENDING. | — | — |
| ReconcilePayment | Command Candidate | Thao tác đối soát giao dịch thanh toán với sổ phụ ngân hàng. | — | — |
| PaymentCreated | Domain Event Candidate | Sự kiện phát sinh khi giao dịch thanh toán được khởi tạo. | — | — |
| PaymentProcessing | Domain Event Candidate | Sự kiện phát sinh khi giao dịch đang được cổng thanh toán xử lý. | — | — |
| PaymentSucceeded | Domain Event Candidate | Sự kiện phát sinh khi giao dịch thanh toán thành công hoàn toàn. | — | PaymentSuccess |
| PaymentPartiallyRefunded | Domain Event Candidate | Sự kiện phát sinh khi giao dịch thanh toán được hoàn trả một phần tiền. | — | — |
| PaymentFailed | Domain Event Candidate | Sự kiện phát sinh khi giao dịch thanh toán thất bại hoặc bị từ chối. | — | — |
| PaymentCancelled | Domain Event Candidate | Sự kiện phát sinh khi giao dịch thanh toán bị hủy. | — | — |
| PaymentRefunded | Domain Event Candidate | Sự kiện phát sinh khi số tiền giao dịch đã được hoàn trả lại toàn bộ (100%). | — | — |

---

## 17. Refund Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Refund | Aggregate Candidate | Khoản tiền hoàn trả cho khách gắn với một giao dịch `Payment` gốc cụ thể. | — | Refund Record |
| RefundRequest | Entity Candidate | Hồ sơ yêu cầu hoàn tiền do khách hàng hoặc tiếp tân đề xuất. | — | Refund Proposal |
| RefundStatus | Status-Enum | Trạng thái vòng đời của một khoản hoàn tiền (`FAILED` là trạng thái xử lý sự cố có thể thử lại). | REQUESTED, APPROVED, REJECTED, PROCESSING, COMPLETED, FAILED | — |
| RequestRefund | Command Candidate | Thao tác khách hàng gửi yêu cầu hoàn tiền cho giao dịch Payment. | — | — |
| CreateRefundRequest | Command Candidate | Thao tác tiếp tân tạo yêu cầu hoàn tiền tại quầy cho khách. | — | — |
| ApproveRefund | Command Candidate | Thao tác Store Manager phê duyệt yêu cầu hoàn tiền. | — | — |
| RejectRefund | Command Candidate | Thao tác Store Manager từ chối yêu cầu hoàn tiền có ghi lý do. | — | DeclineRefund |
| ProcessRefund | Command Candidate | Thao tác Finance Staff tiến hành hoàn tiền qua cổng thanh toán/tiền mặt. | — | — |
| CompleteRefund | Command Candidate | Thao tác xác nhận giao dịch hoàn tiền hoàn tất thành công (COMPLETED). | — | SettleRefund |
| FailRefund | Command Candidate | Thao tác hệ thống ghi nhận lỗi xử lý hoàn tiền từ phía cổng thanh toán (chuyển sang FAILED, cho phép Retry/Manual). | — | — |
| RetryRefund | Command Candidate | Thao tác thử lại lệnh hoàn tiền qua cổng thanh toán sau khi bị lỗi. | — | ReattemptRefund |
| ResolveRefundManually | Command Candidate | Thao tác Finance Staff hoàn tất hoàn tiền ngoại tuyến (chuyển khoản trực tiếp/tiền mặt) khi cổng lỗi. | — | ManualRefundSettlement |
| ReconcileRefund | Command Candidate | Thao tác đối soát các khoản hoàn tiền với báo cáo tài chính. | — | — |
| SendRefundNotification | Command Candidate | Thao tác gửi thông báo kết quả hoàn tiền cho khách. | — | — |
| RefundRequested | Domain Event Candidate | Sự kiện phát sinh khi yêu cầu hoàn tiền được tạo. | — | — |
| RefundApproved | Domain Event Candidate | Sự kiện phát sinh khi yêu cầu hoàn tiền được phê duyệt. | — | — |
| RefundRejected | Domain Event Candidate | Sự kiện phát sinh khi yêu cầu hoàn tiền bị từ chối. | — | — |
| RefundProcessing | Domain Event Candidate | Sự kiện phát sinh khi giao dịch hoàn tiền đang được thực thi. | — | — |
| RefundCompleted | Domain Event Candidate | Sự kiện phát sinh khi tiền đã được hoàn trả thành công về tài khoản/tiền mặt của khách. | — | — |
| RefundFailed | Domain Event Candidate | Sự kiện phát sinh khi giao dịch hoàn tiền gặp lỗi kỹ thuật. | — | — |
| RefundRetried | Domain Event Candidate | Sự kiện phát sinh khi lệnh hoàn tiền được kích hoạt thử lại. | — | — |
| RefundManuallyResolved | Domain Event Candidate | Sự kiện phát sinh khi khoản hoàn tiền được đối soát và xử lý thành công ngoại tuyến. | — | — |

---

## 18. Promotion & Voucher Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Promotion | Entity Candidate | Chương trình khuyến mãi áp dụng tự động theo điều kiện kinh doanh. | — | Promo Campaign |
| Voucher | Entity Candidate | Mã giảm giá có điều kiện áp dụng và giới hạn lượt sử dụng. | — | Coupon |
| VoucherUsage | Entity Candidate | Lịch sử ghi nhận một lần áp dụng mã Voucher vào đơn hàng / hóa đơn. | — | Coupon Use |
| CreatePromotion | Command Candidate | Thao tác tạo chương trình khuyến mãi mới. | — | — |
| ManagePromotion | Command Candidate | Thao tác quản lý và cấu hình chương trình khuyến mãi. | — | — |
| ConfigureStorePromotion | Command Candidate | Thao tác cấu hình áp dụng Promotion cho Store. | — | — |
| CreateVoucher | Command Candidate | Thao tác tạo mã Voucher mới. | — | — |
| ManageVoucher | Command Candidate | Thao tác quản lý điều kiện và số lượng Voucher. | — | — |
| UseVoucher | Command Candidate | Thao tác áp dụng mã Voucher khi thanh toán. | — | RedeemVoucher |
| ValidateVoucher | Command Candidate | Thao tác hệ thống kiểm tra điều kiện áp dụng Voucher. | — | CheckVoucher |
| TrackVoucherUsage | Command Candidate | Thao tác theo dõi và lưu vết lịch sử sử dụng Voucher. | — | — |

---

## 19. Membership & Loyalty Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Membership | Aggregate Candidate | Hồ sơ gói hội viên khách hàng đăng ký để hưởng các quyền lợi ưu đãi. | — | Member Plan |
| MembershipStatus | Status-Enum | Trạng thái vòng đời của gói hội viên. | ACTIVE, UPGRADED, EXPIRED | — |
| LoyaltyPoint | Entity Candidate | Điểm thưởng tích lũy của khách hàng từ các giao dịch thanh toán. | — | Reward Points |
| RegisterMembership | Command Candidate | Thao tác khách hàng đăng ký tham gia gói hội viên mới. | — | EnrollMembership |
| ViewMembership | Command Candidate | Thao tác xem thông tin và quyền lợi gói hội viên. | — | — |
| RenewMembership | Command Candidate | Thao tác gia hạn thời gian hiệu lực của gói hội viên (giữ ACTIVE). | — | ExtendMembership |
| UpgradeMembership | Command Candidate | Thao tác nâng cấp gói hội viên lên hạng cao hơn (chuyển sang UPGRADED). | — | — |
| ManageMembership | Command Candidate | Thao tác quản lý cấu hình các gói hội viên. | — | — |
| ProcessMembershipExpiry | Command Candidate | Thao tác hệ thống xử lý chuyển trạng thái gói hội viên hết hạn (EXPIRED). | — | ExpireMembership |
| ViewLoyaltyPoint | Command Candidate | Thao tác xem số dư điểm thưởng tích lũy. | — | — |
| RedeemLoyaltyPoint | Command Candidate | Thao tác sử dụng điểm tích lũy để đổi quà hoặc trừ tiền hóa đơn. | — | SpendPoints |
| AddLoyaltyPoint | Command Candidate | Thao tác cộng điểm tích lũy sau giao dịch thanh toán. | — | EarnPoints |
| DeductLoyaltyPoint | Command Candidate | Thao tác trừ điểm tích lũy khi sử dụng. | — | — |
| ExpireLoyaltyPoint | Command Candidate | Thao tác hủy điểm tích lũy khi quá hạn sử dụng. | — | — |
| AdjustLoyaltyPoint | Command Candidate | Thao tác Store Manager điều chỉnh số dư điểm tích lũy thủ công. | — | — |
| MembershipCreated | Domain Event Candidate | Sự kiện phát sinh khi khách hàng đăng ký hội viên thành công. | — | — |
| MembershipRenewed | Domain Event Candidate | Sự kiện phát sinh khi gói hội viên được gia hạn thêm thời hạn. | — | — |
| MembershipUpgraded | Domain Event Candidate | Sự kiện phát sinh khi khách hàng nâng cấp lên hạng hội viên mới. | — | — |
| MembershipExpired | Domain Event Candidate | Sự kiện phát sinh khi gói hội viên hết hạn hiệu lực. | — | — |

---

## 20. Package Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Package | Aggregate Candidate | Gói dịch vụ trả trước nhiều lần (ví dụ: combo 10 lần tắm sấy). | — | Service Bundle |
| PackageStatus | Status-Enum | Trạng thái vòng đời của gói dịch vụ trả trước. | PURCHASED, ACTIVATED, PARTIALLY_CONSUMED, FULLY_CONSUMED, CANCELLED, EXPIRED | — |
| PackageUsage | Entity Candidate | Ghi nhận một lần sử dụng cấn trừ quyền lợi từ gói dịch vụ. | — | Package Redemption |
| PackageExpiry | Value Object Candidate | Thời hạn sử dụng của gói dịch vụ trả trước. | — | Bundle Expiry |
| PurchasePackage | Command Candidate | Thao tác khách hàng mua gói dịch vụ trả trước. | — | BuyPackage |
| ViewPackage | Command Candidate | Thao tác xem số dư và lịch sử sử dụng gói dịch vụ. | — | — |
| ActivatePackage | Command Candidate | Thao tác kích hoạt gói dịch vụ sau khi mua để bắt đầu sử dụng. | — | — |
| ConfirmPackageUsage | Command Candidate | Thao tác tiếp tân xác nhận cấn trừ lượt sử dụng dịch vụ từ gói. | — | ConsumePackage |
| CancelPackage | Command Candidate | Thao tác Store Manager hủy gói dịch vụ theo chính sách hoàn tiền. | — | VoidPackage |
| AdjustPackage | Command Candidate | Thao tác Store Manager điều chỉnh số lượt sử dụng còn lại trong gói. | — | — |
| TrackPackageUsage | Command Candidate | Thao tác theo dõi và lưu vết lịch sử trừ quyền lợi gói. | — | — |
| ProcessPackageExpiry | Command Candidate | Thao tác hệ thống xử lý chuyển trạng thái gói đã hết hạn (EXPIRED). | — | — |
| PackagePurchased | Domain Event Candidate | Sự kiện phát sinh khi khách hàng mua gói dịch vụ. | — | — |
| PackageActivated | Domain Event Candidate | Sự kiện phát sinh khi gói dịch vụ được kích hoạt. | — | — |
| PackagePartiallyConsumed | Domain Event Candidate | Sự kiện phát sinh khi gói dịch vụ đã được sử dụng một phần quyền lợi. | — | — |
| PackageFullyConsumed | Domain Event Candidate | Sự kiện phát sinh khi gói dịch vụ đã dùng hết 100% quyền lợi. | — | — |
| PackageCancelled | Domain Event Candidate | Sự kiện phát sinh khi gói dịch vụ bị hủy bỏ. | — | — |
| PackageExpired | Domain Event Candidate | Sự kiện phát sinh khi gói dịch vụ hết hạn sử dụng. | — | — |

---

## 21. Incident Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Incident | Aggregate Candidate | Hồ sơ sự cố phát sinh trong quá trình vận hành dịch vụ tại Store. | — | Issue Record |
| IncidentStatus | Status-Enum | Trạng thái vòng đời xử lý sự cố. | RECORDED, CLASSIFIED, UNDER_INVESTIGATION, ESCALATED, RESOLVED, CLOSED | — |
| ClinicalIncident | Entity Candidate | Sự cố chuyên môn phát sinh trong quá trình khám chữa bệnh hoặc tiêm phòng. | — | Medical Incident |
| GroomingIncident | Entity Candidate | Sự cố phát sinh trong quá trình thực hiện dịch vụ Grooming làm đẹp. | — | Salon Incident |
| IncidentClassification | Value Object Candidate | Phân loại mức độ nghiêm trọng và loại hình sự cố (LOW, MEDIUM, HIGH, CRITICAL). | — | Severity Level |
| RecordIncident | Command Candidate | Thao tác ghi nhận sự cố vận hành chung tại Store. | — | ReportIncident |
| RecordClinicalIncident | Command Candidate | Thao tác bác sĩ ghi nhận sự cố y tế lâm sàng. | — | — |
| RecordGroomingIncident | Command Candidate | Thao tác nhân viên ghi nhận sự cố trong lúc grooming. | — | — |
| ClassifyIncident | Command Candidate | Thao tác Store Manager phân loại mức độ nghiêm trọng sự cố. | — | — |
| InvestigateIncident | Command Candidate | Thao tác tiến hành điều tra nguyên nhân sự cố. | — | — |
| EscalateIncident | Command Candidate | Thao tác chuyển cấp xử lý sự cố lên ban quản lý Organization. | — | — |
| HandleIncident | Command Candidate | Thao tác thực hiện các biện pháp khắc phục và bồi thường (chuyển sang RESOLVED). | — | ResolveIncident |
| CloseIncident | Command Candidate | Thao tác đóng hồ sơ sự cố sau khi đã giải quyết thỏa đáng (chuyển sang CLOSED). | — | ArchiveIncident |
| NotifyIncident | Command Candidate | Thao tác gửi thông báo về sự cố cho khách hàng liên quan. | — | — |
| IncidentRecorded | Domain Event Candidate | Sự kiện phát sinh khi sự cố mới được ghi nhận vào hệ thống. | — | — |
| IncidentClassified | Domain Event Candidate | Sự kiện phát sinh khi sự cố được phân loại mức độ. | — | — |
| IncidentInvestigated | Domain Event Candidate | Sự kiện phát sinh khi bắt đầu tiến trình điều tra sự cố. | — | — |
| IncidentEscalated | Domain Event Candidate | Sự kiện phát sinh khi sự cố được chuyển lên cấp quản lý cao hơn. | — | — |
| IncidentResolved | Domain Event Candidate | Sự kiện phát sinh khi phương án khắc phục sự cố được hoàn tất. | — | — |
| IncidentClosed | Domain Event Candidate | Sự kiện phát sinh khi hồ sơ sự cố chính thức được đóng lại. | — | — |
| IncidentNotification | Domain Event Candidate | Sự kiện phát thông báo cập nhật về sự cố cho khách hàng. | — | — |

---

## 22. Consent & Privacy Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Consent | Entity Candidate | Sự đồng thuận của Customer cho phép hệ thống thu thập và xử lý dữ liệu. | — | Privacy Consent |
| DataExport | Entity Candidate | Yêu cầu và gói dữ liệu cá nhân trích xuất theo yêu cầu của khách hàng. | — | Data Portability Package |
| DataDeletion | Entity Candidate | Yêu cầu xóa bỏ hoặc ẩn danh hóa dữ liệu cá nhân theo quyền riêng tư. | — | Right to be Forgotten |
| PrivacyPolicy | Entity Candidate | Chính sách bảo vệ dữ liệu và quyền riêng tư của Organization. | — | Privacy Terms |
| RetentionPolicy | Entity Candidate | Chính sách quy định thời hạn lưu trữ pháp lý của từng loại dữ liệu. | — | Data Retention Rule |
| DataAnonymization | Entity Candidate | Việc loại bỏ định danh cá nhân trên các bản ghi lịch sử theo chính sách. | — | Anonymization |
| GrantConsent | Command Candidate | Thao tác Customer cấp quyền đồng ý xử lý dữ liệu. | — | GiveConsent |
| RevokeConsent | Command Candidate | Thao tác Customer rút lại sự đồng ý xử lý dữ liệu. | — | WithdrawConsent |
| RequestDataExport | Command Candidate | Thao tác Customer yêu cầu trích xuất dữ liệu cá nhân. | — | ExportData |
| RequestDataDeletion | Command Candidate | Thao tác Customer yêu cầu xóa hoặc ẩn danh dữ liệu. | — | DeleteAccountData |
| ManagePrivacyPolicy | Command Candidate | Thao tác Organization Admin cấu hình chính sách bảo mật. | — | — |
| ManageRetentionPolicy | Command Candidate | Thao tác Organization Admin cấu hình thời hạn lưu trữ dữ liệu. | — | — |
| ProcessDataExport | Command Candidate | Thao tác hệ thống tự động tổng hợp gói dữ liệu trích xuất. | — | — |
| ProcessDataDeletion | Command Candidate | Thao tác hệ thống thực hiện xóa hoặc ẩn danh hóa dữ liệu. | — | — |

---

## 23. Notification Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| Notification | Entity Candidate | Thông điệp thông báo gửi đến người dùng qua các kênh (In-app, SMS, Email). | — | System Message |
| SendNotification | Command Candidate | Thao tác phát thông báo chung tới người dùng. | — | PushNotification |
| SendAppointmentNotification | Command Candidate | Thao tác gửi thông báo xác nhận đặt lịch hẹn. | — | — |
| SendAppointmentReminder | Command Candidate | Thao tác gửi tin nhắn nhắc lịch hẹn sắp tới. | — | — |
| SendPaymentNotification | Command Candidate | Thao tác gửi thông báo biến động giao dịch thanh toán. | — | — |
| SendOrderNotification | Command Candidate | Thao tác gửi thông báo cập nhật đơn hàng. | — | — |
| SendVaccineReminder | Command Candidate | Thao tác gửi nhắc nhở lịch tiêm phòng vaccine định kỳ. | — | — |
| SendFollowUpReminder | Command Candidate | Thao tác gửi nhắc nhở lịch tái khám thú cưng. | — | — |
| SendMembershipNotification | Command Candidate | Thao tác gửi thông báo quyền lợi hội viên. | — | — |
| RetryNotification | Command Candidate | Thao tác gửi lại thông báo khi lần gửi trước gặp lỗi mạng. | — | ResendNotification |
| ViewNotification | Command Candidate | Thao tác người dùng xem danh sách thông báo đã nhận. | — | — |
| AppointmentNotification | Domain Event Candidate | Sự kiện thông báo liên quan đến lịch hẹn. | — | — |
| PaymentNotification | Domain Event Candidate | Sự kiện thông báo liên quan đến thanh toán. | — | — |

---

## 24. Reporting & Analytics

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| RevenueReport | Entity Candidate | Báo cáo phân tích doanh thu theo Store hoặc Organization. | — | Sales Report |
| AppointmentReport | Entity Candidate | Báo cáo thống kê tần suất và tỷ lệ phục vụ lịch hẹn. | — | Booking Stats |
| ServiceReport | Entity Candidate | Báo cáo hiệu suất và tỷ trọng sử dụng các dịch vụ. | — | Service Metrics |
| InventoryReport | Entity Candidate | Báo cáo biến động nhập xuất tồn và cảnh báo hàng tồn kho. | — | Stock Report |
| StaffReport | Entity Candidate | Báo cáo năng suất và ca trực làm việc của nhân sự. | — | Staff Productivity |
| CustomerPetReport | Entity Candidate | Báo cáo tăng trưởng khách hàng và hồ sơ thú cưng. | — | Customer Analytics |
| PlatformReport | Entity Candidate | Báo cáo tổng hợp số liệu vận hành toàn bộ nền tảng. | — | Global Dashboard |
| RevenueReconciliation | Entity Candidate | Báo cáo đối soát doanh thu giữa hóa đơn, cổng thanh toán và quỹ tiền mặt. | — | Financial Reconciliation |
| ViewRevenueReport | Command Candidate | Thao tác xem báo cáo doanh thu theo phạm vi quyền. | — | — |
| ViewAppointmentReport | Command Candidate | Thao tác xem báo cáo lịch hẹn. | — | — |
| ViewServiceReport | Command Candidate | Thao tác xem báo cáo dịch vụ. | — | — |
| ViewInventoryReport | Command Candidate | Thao tác xem báo cáo tồn kho. | — | — |
| ViewStaffReport | Command Candidate | Thao tác xem báo cáo nhân sự. | — | — |
| ViewOrganizationRevenue | Command Candidate | Thao tác xem tổng hợp doanh thu Organization. | — | — |
| CompareStoreRevenue | Command Candidate | Thao tác so sánh doanh thu giữa các Store trực thuộc. | — | — |
| ViewCustomerPetReport | Command Candidate | Thao tác xem báo cáo tăng trưởng khách hàng. | — | — |
| ReconcileRevenue | Command Candidate | Thao tác thực hiện đối soát tài chính định kỳ. | — | — |
| ViewPlatformReport | Command Candidate | Thao tác xem báo cáo toàn hệ thống nền tảng. | — | — |

---

## 25. Audit Management

| Term | Loại | Định nghĩa | Values (Status-Enum) | Deprecated synonym |
|---|---|---|---|---|
| AuditLog | Entity Candidate | Bản ghi kiểm toán bất biến ghi nhận mọi hành vi và biến động dữ liệu nhạy cảm. | — | Audit Trail |
| PermissionChange | Entity Candidate | Bản ghi chi tiết việc thay đổi phân quyền của User hoặc Role. | — | Security Audit |
| MedicalRecordAccess | Entity Candidate | Bản ghi truy vết hành vi mở và xem hồ sơ bệnh án của thú cưng. | — | Clinical Access Log |
| PaymentRefundAudit | Entity Candidate | Bản ghi truy vết toàn bộ hoạt động thanh toán, đối soát và hoàn tiền. | — | Financial Audit Log |
| InventoryAudit | Entity Candidate | Bản ghi truy vết mọi hoạt động nhập, xuất, chuyển kho và điều chỉnh tồn kho. | — | Stock Audit Log |
| RecordAuditLog | Command Candidate | Thao tác hệ thống tự động ghi nhật ký kiểm toán. | — | LogEvent |
| ViewAuditLog | Command Candidate | Thao tác tra cứu nhật ký kiểm toán theo phạm vi được cấp phép. | — | QueryAudit |
| TrackPermissionChange | Command Candidate | Thao tác tra cứu lịch sử thay đổi phân quyền. | — | — |
| TrackMedicalRecordAccess | Command Candidate | Thao tác tra cứu lịch sử truy cập hồ sơ bệnh án. | — | — |
| TrackPaymentRefundAudit | Command Candidate | Thao tác tra cứu lịch sử giao dịch tài chính. | — | — |
| TrackInventoryAudit | Command Candidate | Thao tác tra cứu lịch sử biến động kho hàng. | — | — |
