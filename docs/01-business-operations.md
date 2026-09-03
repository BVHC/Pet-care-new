# Pet Care Ecosystem — Business Operations Catalog

> Mục đích: Liệt kê **các nghiệp vụ chính** và **actor chính** của hệ thống chăm sóc thú cưng đa cửa hàng.
>
> Tài liệu này trả lời: **Ai thực hiện nghiệp vụ gì?** (Actor ↔ Business Operation Mapping).
>
> Tất cả các nghiệp vụ trong tài liệu này đồng bộ 1:1 với các Command Candidate trong Ubiquitous Language Glossary và State Machine Specifications.

---

# 0. Actors chính & Phạm vi Quyền lực (Scopes)

Hệ thống phân định rõ 11 Actors nghiệp vụ được phân bổ trên 5 cấp phạm vi quản trị Multi-tenancy (**PLATFORM**, **ORGANIZATION**, **STORE**, **WAREHOUSE**, **CUSTOMER**) cùng cơ chế Ủy quyền dữ liệu (**Pet Delegation**):

| Actor | Phạm vi (Scope) | Vai trò & Trách nhiệm chính |
|---|---|---|
| **Platform Admin** (`SUPER_ADMIN`) | **PLATFORM** | Quản trị toàn bộ nền tảng SaaS đa tổ chức, quản lý vòng đời Tenant (Organizations), danh mục sản phẩm mẫu và cấu hình hệ thống toàn cục. |
| **Organization Admin** (`ORGANIZATION_ADMIN`) | **ORGANIZATION** | Quản lý Organization/chuỗi chi nhánh, Warehouse trung tâm, chính sách tổ chức, danh mục bảng giá dịch vụ và các Store trực thuộc. Cách ly dữ liệu 100% giữa các Organization. |
| **StoreManager** (`STORE_MANAGER`) | **STORE** | Quản lý, điều phối vận hành Store chi nhánh, phân ca nhân sự, thẩm định và phê duyệt Maker-Checker (`ApproveRefund`, `ApproveStockTransfer`, `ApprovePurchaseRequest`, `ApproveInventoryAdjustment`). |
| **Receptionist** (`RECEPTIONIST`) | **STORE** | Tiếp đón khách, quản lý đặt lịch/check-in hẹn khám, xếp hàng walk-in, tạo đơn/hóa đơn POS tại quầy, ghi nhận tiền mặt, tạo yêu cầu hoàn tiền. |
| **Veterinarian** (`VETERINARIAN`) | **STORE** | Khám bệnh, chẩn đoán, điều trị, kê đơn thuốc, thực hiện tiêm phòng vaccine và quản lý hồ sơ bệnh án thú cưng (`MedicalRecord`). |
| **Groomer** (`GROOMER`) | **STORE** | Kiểm tra thể trạng trước grooming, thực hiện dịch vụ làm đẹp/spa thú cưng, đề xuất dịch vụ phát sinh thêm. |
| **InventoryStaff** | **STORE / WAREHOUSE** | *Vai trò vận hành kho:* Nhập, xuất, kiểm kê, tạo phiếu điều chỉnh, điều phối chuyển kho và tiếp nhận hàng hóa mua từ nhà cung cấp. |
| **FinanceStaff** | **ORGANIZATION / STORE** | *Vai trò vận hành tài chính:* Phát hành hóa đơn chính thức, đối soát doanh thu/hóa đơn, thực thi lệnh chi tiền hoàn (`ProcessRefund`, `CompleteRefund`) và đối soát thanh toán. |
| **Customer** (`CUSTOMER`) | **CUSTOMER** | Chủ thú cưng: đăng ký tài khoản, quản lý hồ sơ Pet, đặt lịch hẹn, mua hàng online, thanh toán điện tử, gửi yêu cầu hoàn tiền và quản lý ủy quyền chăm sóc. |
| **Caregiver** | **Pet Delegation** | Người được ủy quyền chăm sóc: tài khoản `CUSTOMER` được chủ pet ủy quyền thông qua quan hệ `PetCaregiverDelegation` để thay mặt đặt lịch, đưa pet đi khám/spa trong phạm vi hiệu lực. |
| **System** | **Automated Runtime** | Tác vụ nền tự động: gửi thông báo, nhắc lịch tiêm, kiểm tra hết hạn (OTP, Order timeout 15p, Package, Voucher), tiếp nhận webhook cổng thanh toán. |

---

# 1. Authentication & OTP

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đăng ký tài khoản tự phục vụ | `RegisterAccount` |
| System | Gửi OTP xác thực đăng ký | `SendRegistrationOTP` |
| Customer | Yêu cầu gửi lại OTP | `ResendOTP` |
| Customer | Xác thực mã OTP (kích hoạt ACTIVE) | `VerifyOTP` |
| Platform Admin / Organization Admin | Khởi tạo tài khoản Staff trực tiếp (kích hoạt ACTIVE với mật khẩu tạm, không qua OTP - D-04) | `CreateStaff` |
| System | Kiểm tra tính hợp lệ của OTP | `CheckOTP` |
| System | Xử lý OTP hết hạn | `ExpireOTP` |
| Customer / Staff | Đăng nhập hệ thống | `Login` |
| Customer / Staff | Đăng xuất khỏi hệ thống | `Logout` |

---

# 2. Identity & Access Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Quản lý thông tin cá nhân | `ManageCustomerProfile` |
| Platform Admin | Quản lý người dùng toàn nền tảng | `ManageUser` |
| Platform Admin | Quản lý Role nền tảng | `ManageRole` |
| Platform Admin | Quản lý Permission nền tảng | `ManagePermission` |
| Platform Admin | Khóa/mở khóa tài khoản toàn nền tảng | `LockAccount`, `UnlockAccount` |
| Organization Admin | Quản lý người dùng trong Organization | `ManageUser` |
| Organization Admin | Quản lý Role/Permission trong Organization | `ManageRole`, `ManagePermission` |
| Organization Admin | Khóa/mở khóa tài khoản trong Organization | `LockAccount`, `UnlockAccount` |
| StoreManager | Quản lý phân quyền Staff trong Store | `AssignPermission` |

---

# 3. Organization & Store Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Organization Admin | Tạo Organization | `CreateOrganization` |
| Organization Admin | Cập nhật thông tin Organization | `UpdateOrganization` |
| Organization Admin | Quản lý chính sách Organization | `ManageOrganizationPolicy` |
| Organization Admin | Tạo Store mới | `CreateStore` |
| Organization Admin | Cập nhật thông tin Store | `UpdateStore` |
| Organization Admin | Kích hoạt Store hoạt động | `ActivateStore` |
| Organization Admin | Tạm ngưng hoạt động Store | `SuspendStore` |
| Organization Admin | Ngừng kích hoạt Store | `DeactivateStore` |
| Organization Admin | Lưu trữ / đóng cửa vĩnh viễn Store | `ArchiveStore` |
| StoreManager | Quản lý thông tin vận hành Store | `UpdateStore` |
| StoreManager | Cấu hình giờ hoạt động Store | `ConfigureOperatingHour` |
| StoreManager | Cấu hình dịch vụ áp dụng tại Store | `ConfigureStoreService` |
| StoreManager | Quản lý tài nguyên vật tư của Store | `ConfigureStoreResource` |
| StoreManager | Quản lý chính sách vận hành Store | `ConfigureStorePolicy` |

---

# 4. Customer & Pet Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Quản lý hồ sơ Customer | `ManageCustomerProfile` |
| Customer | Thêm Pet vào danh sách | `AddPet` |
| Customer | Cập nhật thông tin Pet | `UpdatePet` |
| Customer | Xem thông tin Pet | `ViewPet` |
| Customer | Quản lý quyền sở hữu Pet | `ManagePetOwnership` |
| Customer | Gửi lời mời ủy quyền Caregiver | `InviteCaregiver` |
| Customer | Thu hồi quyền ủy quyền Caregiver | `RevokeCaregiver` |
| Caregiver | Chấp nhận lời mời ủy quyền (kích hoạt ACTIVE) | `AcceptCaregiverInvitation` |
| Caregiver | Từ chối lời mời ủy quyền | `RejectCaregiverInvitation` |
| System | Xử lý lời mời ủy quyền hết hạn (INVITED -> EXPIRED) | `ProcessInvitationExpiry` |
| System | Xử lý quan hệ ủy quyền Caregiver hết hạn hiệu lực (ACTIVE -> EXPIRED) | `ProcessDelegationExpiry` |
| Caregiver | Xem thông tin Pet được ủy quyền | `ViewPet` |
| Caregiver | Thực hiện thao tác được ủy quyền | `PerformDelegatedAction` |
| Receptionist | Tạo Customer tại quầy | `ManageCustomerProfile` |
| Receptionist | Cập nhật thông tin Customer | `ManageCustomerProfile` |
| Receptionist | Tạo hồ sơ Pet tại quầy | `AddPet` |
| Receptionist | Cập nhật hồ sơ Pet | `UpdatePet` |
| Receptionist | Tra cứu Customer và Pet | `SearchCustomerPet` |

---

# 5. Service & Product Catalog

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Platform Admin | Quản lý Master Product Catalog | `ManageProductCatalog` |
| Platform Admin | Cấp quyền sử dụng Catalog cho Organization | `GrantProductCatalogAccess` |
| Organization Admin | Quản lý Product trong phạm vi Organization | `ManageProduct` |
| Organization Admin | Quản lý Service của Organization | `ManageService` |
| StoreManager | Cấu hình khả dụng Service tại Store | `ConfigureServiceAvailability` |
| StoreManager | Cấu hình giá Service tại Store | `ConfigureServicePrice` |
| StoreManager | Cấu hình giá Product tại Store | `ConfigureProductPrice` |
| Customer | Xem danh mục Product | `ViewProduct` |
| Customer | Xem danh mục Service | `ViewService` |

---

# 6. Appointment & Scheduling

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer / Receptionist | Khóa giữ chỗ slot hẹn trước 15 phút (Pre-booking Slot Reservation 15m TTL) | `HoldSlot` |
| Customer / Receptionist | Giải phóng slot giữ chỗ | `ReleaseHold` |
| System | Xử lý hết hạn giữ chỗ 15 phút (HOLDING -> EXPIRED) | `ExpireHold` |
| Customer | Đặt lịch hẹn mới | `BookAppointment` |
| Customer | Xem thông tin lịch hẹn | `ViewAppointment` |
| Customer | Hủy lịch hẹn | `CancelAppointment` |
| Customer | Đổi thời gian lịch hẹn | `RescheduleAppointment` |
| Caregiver | Đặt lịch hẹn cho Pet được ủy quyền | `BookAppointment` |
| Receptionist | Tạo lịch hẹn tại quầy/qua điện thoại | `BookAppointment` |
| Receptionist / System | Xác nhận lịch hẹn | `ConfirmAppointment` |
| Receptionist | Cập nhật chi tiết lịch hẹn | `UpdateAppointment` |
| Receptionist | Hủy lịch hẹn (trước khi vào phục vụ) | `CancelAppointment` |
| Receptionist | Đổi lịch hẹn | `RescheduleAppointment` |
| Receptionist | Tiếp nhận check-in lịch hẹn | `CheckInAppointment` |
| Veterinarian / Groomer | Bắt đầu thực hiện dịch vụ theo lịch hẹn | `StartAppointmentService` |
| Veterinarian / Groomer | Dừng khẩn cấp phiên phục vụ (Abort) kèm lý do | `AbortAppointment` |
| Receptionist | Tiếp nhận check-out hoàn tất lịch hẹn | `CheckOutAppointment` |
| Receptionist / System | Đánh dấu khách vắng mặt (No-Show) | `MarkNoShow` |
| StoreManager | Quản lý lịch Store | `ManageStoreSchedule` |
| StoreManager | Phân công Staff vào ca/lịch hẹn | `AssignStaff` |
| StoreManager | Điều phối lịch Store | `CoordinateSchedule` |
| System | Kiểm tra thời gian trống & Xung đột lịch (Staff, Resource, Pet Collision) | `CheckAvailability` |
| System | Gửi thông báo nhắc lịch hẹn | `SendAppointmentReminder` |

- **Quy tắc Kiểm tra Xung đột Lịch Thú cưng (Pet Schedule Collision Guard, RULE-06-11):**
  - Khi thực hiện `BookAppointment`, hệ thống kiểm tra đảm bảo Pet không có bất kỳ lịch hẹn nào khác đang ở trạng thái `BOOKED`, `CONFIRMED`, `CHECKED_IN`, hoặc `IN_PROGRESS` giao thoa với khung giờ dự kiến trên toàn bộ hệ thống chi nhánh.
- **Quy tắc Đổi lịch Nguyên tử (Atomic Reschedule Guard, RULE-06-03, RULE-06-10, RULE-06-11):**
  - Khi `RescheduleAppointment`, hệ thống kiểm tra và khóa giữ chỗ khung giờ mới trước. Chỉ khi khung giờ mới thành công mới giải phóng khung giờ cũ và chuyển về `BOOKED`. Nếu thất bại, giao dịch rollback giữ nguyên lịch cũ.
- **Xử lý Khách Vắng mặt (No-Show Resource & Slot Release, RULE-06-09):**
  - Khi đánh dấu `MarkNoShow`, hệ thống lập tức giải phóng tài nguyên phòng/bàn (`ReleaseStoreResource`), giải phóng ca làm việc nhân sự (`ReleaseStaffSlot`) và xử lý phạt cọc theo chính sách.

---

# 7. Walk-in & Queue Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer / Receptionist | Tiếp nhận khách và cấp số thứ tự vào hàng đợi FIFO | `RegisterQueueEntry` |
| Receptionist / Veterinarian / Groomer | Gọi số thứ tự tiếp theo vào phòng khám/bàn grooming | `CallQueueEntry` |
| Veterinarian / Groomer | Bắt đầu phục vụ khách hàng đợi (chuyển sang IN_SERVICE & tạo Appointment) | `StartQueueService` |
| Veterinarian / Groomer | Hoàn tất lượt phục vụ hàng đợi (chuyển sang COMPLETED) | `CompleteQueueEntry` |
| Receptionist | Đánh dấu khách không có mặt sau 3 lần gọi (chuyển sang NO_SHOW) | `MarkQueueNoShow` |
| Customer / Receptionist | Hủy lượt chờ trong hàng đợi (chuyển sang CANCELLED) | `CancelQueueEntry` |
| StoreManager | Điều phối hàng đợi Queue | `CoordinateQueue` |
| System | Quản lý thứ tự số thứ tự Queue FIFO | `ManageQueueOrder` |
| System | Gửi thông báo khi đến lượt phục vụ | `SendTurnNotification` |

- **Cơ chế Cầu nối Dữ liệu Walk-in sang Appointment (Walk-in to Appointment Lifecycle Bridge):**
  - Khi bắt đầu phục vụ (`StartQueueService`), hệ thống tự động khởi tạo một bản ghi `Appointment` nội bộ với kênh tiếp nhận `Channel = WALK_IN`, gắn mã phiếu hàng đợi (`QueueTicketId`), nhân viên phục vụ (`StaffId`) và tài nguyên cơ sở vật chất (`StoreResourceId`).
  - Bản ghi Appointment này được chuyển thẳng sang trạng thái `IN_PROGRESS`, cho phép các module khám bệnh, tiêm phòng, grooming, xuất hóa đơn và báo cáo doanh thu vận hành trên cùng một mô hình dữ liệu đồng nhất.

---

# 8. Workforce Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Organization Admin | Quản lý danh sách Staff toàn Organization | `ManageStaff` |
| Organization Admin / Platform Admin | Khởi tạo tài khoản Staff trực tiếp (ACTIVE, mật khẩu tạm) | `CreateStaff` |
| StoreManager | Cập nhật hồ sơ Staff | `UpdateStaff` |
| StoreManager | Phân công Staff vào Store | `AssignStaffToStore` |
| StoreManager | Quản lý lịch làm việc (Work Schedule) | `ManageWorkSchedule` |
| StoreManager | Quản lý và phê duyệt nghỉ phép (Leave) | `ManageLeave` |
| StoreManager | Ghi nhận Staff vắng mặt đột xuất | `HandleStaffAbsence` |
| StoreManager | Phân công Staff thay thế | `AssignStaffReplacement` |
| Veterinarian | Xem lịch làm việc của bản thân | `ViewWorkSchedule` |
| Groomer | Xem lịch làm việc của bản thân | `ViewWorkSchedule` |
| Receptionist | Xem lịch làm việc của bản thân | `ViewWorkSchedule` |

---

# 9. Veterinary / Clinical Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Veterinarian | Khám lâm sàng thú cưng | `ExaminePet` |
| Veterinarian | Ghi nhận triệu chứng lâm sàng | `RecordSymptom` |
| Veterinarian | Ghi nhận kết quả kiểm tra/xét nghiệm | `RecordExaminationResult` |
| Veterinarian | Xác định chẩn đoán bệnh lý | `DiagnosePet` |
| Veterinarian | Lập phác đồ điều trị (Treatment) | `CreateTreatment` |
| Veterinarian | Kê đơn thuốc (Prescription) | `CreatePrescription` |
| Veterinarian | Cập nhật bệnh án (Medical Record) | `UpdateMedicalRecord` |
| Veterinarian | Xem lịch sử y tế trong Store | `ViewMedicalHistory` |
| Veterinarian | Yêu cầu quyền truy cập lịch sử y tế Cross-store | `RequestCrossStoreConsent` |
| Customer / Receptionist | Xác thực mã OTP (hiệu lực 5 phút) kích hoạt quyền truy cập bệnh án Cross-Store có hiệu lực 24 giờ | `VerifyCrossStoreConsentOTP` |
| Customer | Chủ động thu hồi quyền chia sẻ bệnh án Cross-Store | `RevokeCrossStoreConsent` |
| Veterinarian | Kích hoạt truy cập khẩn cấp hồ sơ bệnh án (Emergency Override) | `EmergencyOverrideAccess` |
| Veterinarian | Lập lịch tái khám (Follow-up) | `CreateFollowUp` |
| Customer | Xem lịch sử y tế của Pet được phép | `ViewMedicalHistory` |
| Caregiver | Xem lịch sử y tế của Pet được ủy quyền | `ViewMedicalHistory` |

- **Quy trình Khám Lâm sàng & Điều trị Bệnh lý (Clinical Medical Examination & Therapeutic Treatment Workflow):**
  1. Tiếp nhận thú cưng vào phòng khám chuyên dụng (`EXAMINATION_ROOM`).
  2. Bác sĩ thực hiện khám chuyên sâu (`ExaminePet`, `RecordSymptom`, `RecordExaminationResult`).
  3. Bác sĩ mở/cập nhật hồ sơ bệnh án (`UpdateMedicalRecord` / `CreateMedicalRecord`) và chẩn đoán (`DiagnosePet`).
  4. Bác sĩ lập phác đồ điều trị (`CreateTreatment`), có thể bao gồm kê đơn thuốc (`CreatePrescription`), chỉ định xét nghiệm/chẩn đoán hình ảnh, hoặc tiêm thuốc/vaccine điều trị.
  5. Đóng phiên khám, lập lịch tái khám (`CreateFollowUp`), và chuyển viện phí sang hóa đơn thanh toán.

---

# 10. Vaccination Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Veterinarian | Kiểm tra phác đồ tiêm vaccine | `CheckVaccinationSchedule` |
| Veterinarian | Quét mã vạch và thực hiện tiêm phòng vaccine | `AdministerVaccine` |
| Veterinarian | Ghi nhận mũi tiêm vào hồ sơ | `RecordVaccination` |
| Veterinarian | Thiết lập lịch tiêm nhắc lại tiếp theo | `ScheduleNextVaccination` |
| InventoryStaff | Quản lý danh mục vaccine | `ManageVaccine` |
| InventoryStaff | Quản lý lô vaccine (Batch) | `ManageVaccineBatch` |
| InventoryStaff | Theo dõi hạn sử dụng vaccine (Expiry) | `ManageVaccineExpiry` |
| System | Gửi thông báo nhắc tiêm phòng | `SendVaccineReminder` |
| Customer | Xem lịch sử và lịch hẹn tiêm phòng | `ViewVaccinationSchedule` |

- **Quy trình Dịch vụ Tiêm phòng Định kỳ Nhanh (Routine / Direct Vaccination Service Workflow):**
  1. Khách hàng đặt lịch hẹn (`BookAppointment`) hoặc đến trực tiếp (`RegisterQueueEntry`).
  2. Tiếp nhận check-in tại quầy (`CheckInAppointment` / `StartQueueService`).
  3. Bác sĩ thú y thực hiện khám sàng lọc thể trạng nhanh (Pre-vaccination Screening) mà không cần tạo bệnh án phức tạp (`MedicalRecord`).
  4. Bác sĩ quét mã vạch (Barcode/QR code) của lọ vaccine (`AdministerVaccine` theo `RULE-10-06`). Hệ thống tự động xác thực hạn sử dụng và tồn kho thời gian thực.
  5. Hệ thống ghi nhận `RecordVaccination` vào hồ sơ tiêm chủng của Pet, tự động trừ tồn kho khả dụng của lô vaccine, và tự động lập lịch tiêm nhắc lại (`ScheduleNextVaccination`).
  6. Tiếp tân hoàn tất check-out (`CheckOutAppointment`) và tạo hóa đơn thanh toán tại quầy (`CreateInvoice`).

---

# 11. Grooming Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đặt dịch vụ Grooming | `BookAppointment` |
| Receptionist | Tiếp nhận và check-in dịch vụ Grooming | `CheckInGrooming` |
| Customer / Receptionist | Hủy dịch vụ Grooming trước khi phục vụ (`WAITING -> CANCELLED`) | `CancelGrooming` |
| Groomer | Kiểm tra thể trạng trước Grooming | `InspectPet` |
| Groomer | Thực hiện dịch vụ Grooming | `PerformGrooming` |
| Groomer | Cập nhật kết quả quá trình Grooming | `UpdateGroomingResult` |
| Groomer | Đề xuất dịch vụ phát sinh thêm | `AddGroomingService` |
| Customer | Xác nhận đồng ý dịch vụ phát sinh (Tạo Hóa đơn Phụ phí, D-02) | `ConfirmAdditionalService` |
| Customer | Từ chối dịch vụ phát sinh | `RejectAdditionalService` |
| Groomer / StoreManager | Dừng khẩn cấp dịch vụ Grooming khi đang phục vụ (IN_PROGRESS -> ABORTED, tạo GroomingIncident) | `AbortGrooming` |
| Groomer | Hoàn thành dịch vụ Grooming | `CompleteGrooming` |

---

# 12. Inventory & Warehouse Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| InventoryStaff | Nhập kho hàng hóa | `ReceiveInventory` |
| InventoryStaff | Xuất kho hàng hóa | `IssueInventory` |
| InventoryStaff | Tạo phiếu điều chỉnh tồn kho | `AdjustInventory` |
| InventoryStaff | Kiểm kê kho định kỳ | `CountInventory` |
| InventoryStaff | Theo dõi số lượng tồn kho khả dụng | `TrackInventory` |
| InventoryStaff | Theo dõi lô hàng (Batch) | `TrackBatch` |
| InventoryStaff | Theo dõi hạn sử dụng (Expiry) | `TrackExpiry` |
| InventoryStaff | Tạo yêu cầu chuyển kho (Store↔Store, Warehouse→Store) | `CreateStockTransfer` |
| StoreManager | Phê duyệt yêu cầu chuyển kho | `ApproveStockTransfer` |
| StoreManager | Từ chối yêu cầu chuyển kho | `RejectStockTransfer` |
| InventoryStaff | Hủy yêu cầu chuyển kho chưa duyệt | `CancelStockTransfer` |
| InventoryStaff | Xuất kho chuyển hàng (vận chuyển) | `ShipStockTransfer` |
| InventoryStaff | Nhận hàng và nhập kho chuyển đến (Ghi nhận nguyên vẹn) | `ReceiveStockTransfer` |
| InventoryStaff | Nhận hàng phát hiện thừa/thiếu/hư hỏng chuyển sang `DISCREPANCY` | `ReceiveStockTransferWithDiscrepancy` |
| StoreManager | Xử lý chênh lệch chuyển kho bằng phiếu điều chỉnh và hoàn tất nhận hàng | `ResolveStockTransferDiscrepancy` |
| StoreManager | Lập phiếu điều chỉnh xử lý hàng sai lệch/hư hại vận chuyển (`TRANSIT_LOSS`) | `AdjustInventory` |
| StoreManager | Phê duyệt điều chỉnh tồn kho | `ApproveInventoryAdjustment` |
| Organization Admin | Quản lý Warehouse trung tâm | `ManageWarehouse` |
| InventoryStaff | Nhập kho tại Warehouse | `ReceiveAtWarehouse` |
| System | Cảnh báo tồn kho dưới ngưỡng an toàn (Low Stock) | `TriggerLowStockAlert` |
| System | Cảnh báo hàng sắp hết hạn (Expiry Warning) | `TriggerExpiryWarning` |

---

# 13. Procurement Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| InventoryStaff | Tạo yêu cầu mua hàng (Purchase Request) | `CreatePurchaseRequest` |
| InventoryStaff | Gửi yêu cầu mua hàng để phê duyệt | `SubmitPurchaseRequest` |
| StoreManager | Phê duyệt yêu cầu mua hàng | `ApprovePurchaseRequest` |
| StoreManager | Từ chối yêu cầu mua hàng | `RejectPurchaseRequest` |
| InventoryStaff | Hủy yêu cầu mua hàng | `CancelPurchaseRequest` |
| InventoryStaff | Tạo đơn đặt hàng nhà cung cấp (Purchase Order) | `CreatePurchaseOrder` |
| InventoryStaff | Theo dõi trạng thái đơn đặt hàng | `TrackPurchaseOrder` |
| InventoryStaff | Tiếp nhận hàng giao từ nhà cung cấp | `ReceiveGoods` |
| InventoryStaff | Kiểm tra chất lượng và số lượng hàng nhận | `InspectGoods` |
| InventoryStaff / StoreManager | Hủy đơn đặt hàng nhà cung cấp chưa giao | `CancelPurchaseOrder` |
| InventoryStaff / StoreManager | Hủy phần hàng còn lại của đơn giao thiếu (`PARTIALLY_RECEIVED -> CLOSED`) | `CancelRemainingPurchaseOrder` |
| InventoryStaff | Cập nhật tăng tồn kho sau mua hàng | `UpdateInventory` |
| Organization Admin | Quản lý danh sách nhà cung cấp (Supplier) | `ManageSupplier` |

---

# 14. Order Management (v1 Store Fulfillment)

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Tạo đơn hàng bán lẻ (Online / App) | `CreateOrder` |
| Customer | Xác nhận giỏ hàng thanh toán (Hold TTL 15 phút) | `CheckoutOrder` |
| Customer | Xem thông tin đơn hàng | `ViewOrder` |
| Customer | Hủy đơn hàng chưa thanh toán (`CANCELLED`) | `CancelOrder` |
| StoreManager / Receptionist | Hủy đơn hàng sau xác nhận kèm hoàn tiền và hoàn kho (`CANCELLED`) | `CancelOrderWithRefund` |
| Receptionist | Tạo và thanh toán đơn hàng trực tiếp tại quầy POS (`PAID -> DELIVERED`) | `CreateOrder` |
| Receptionist / System | Xác nhận đơn hàng đã thanh toán (Đơn Online/Pickup) | `ConfirmOrder` |
| Receptionist | Tiếp nhận và xử lý đơn hàng tại Store | `ProcessOrder` |
| InventoryStaff | Soạn và đóng gói sản phẩm bán lẻ | `PrepareProductOrder` |
| Receptionist | Bàn giao và hoàn thành đơn hàng tại quầy | `CompleteStoreOrder` |
| System | Tự động hủy đơn quá hạn 15p chưa thanh toán và giải phóng kho | `ProcessOrderTimeout` |
| System | Gửi thông báo trạng thái đơn hàng | `SendOrderNotification` |

- **Phân định Luồng Hoàn tất & Kết thúc Đơn hàng (Order Fulfillment & Terminal States, RULE-14-04, RULE-14-06, D-03):**
  - *Bán lẻ POS tại quầy:* `PAID -> DELIVERED` (Bàn giao tức thời sau khi thu tiền thành công).
  - *Đặt hàng Online/App:* `PENDING_PAYMENT -> PAID -> CONFIRMED -> PROCESSING -> READY -> DELIVERED`.
  - *Trạng thái Hủy:* Mọi đơn hủy trước khi giao (dù chưa thanh toán hay đã thanh toán và hoàn tiền) đều chuyển sang `CANCELLED`.
  - *Trạng thái Hoàn trả:* `REFUNDED` là trạng thái kết thúc áp dụng khi đơn đã giao (`DELIVERED`) được đổi trả và hoàn lại 100% giá trị tiền. Trường hợp đổi trả một phần (`Partial Return`), đơn hàng giữ nguyên `DELIVERED` và cập nhật `total_refunded_amount`.

---

# 15. Billing & Invoice Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Receptionist / FinanceStaff | Tạo bản nháp hóa đơn (Draft Invoice) | `CreateInvoice` |
| Receptionist / FinanceStaff | Hủy bỏ bản nháp hóa đơn tạo sai (`DRAFT -> CANCELLED`) | `DiscardInvoice` |
| Receptionist | Thêm mục dịch vụ vào hóa đơn | `AddServiceToInvoice` |
| Receptionist | Thêm mục sản phẩm vào hóa đơn | `AddProductToInvoice` |
| Receptionist | Áp dụng mã giảm giá / khuyến mãi | `ApplyDiscount` |
| FinanceStaff / Receptionist | Phát hành hóa đơn chính thức (Issue) | `IssueInvoice` |
| Receptionist / FinanceStaff | Phát hành hóa đơn phụ phí phát sinh (Surcharge Invoice D-02) | `IssueSurchargeInvoice` |
| FinanceStaff | Hủy vô hiệu hóa đơn đã phát hành chưa thanh toán (`ISSUED -> VOID`) | `VoidInvoice` |
| FinanceStaff | Thực hiện đối soát hóa đơn định kỳ | `ReconcileInvoice` |
| Customer | Xem thông tin hóa đơn | `ViewInvoice` |

- **Tính Bất biến Tất toán Hóa đơn (Settlement Immutability, D-01):**
  - Khi khách hàng thanh toán đủ 100%, Invoice chuyển sang `PAID`. Khi phát sinh hoàn tiền một phần hoặc toàn phần, Invoice giữ nguyên trạng thái `PAID`, số tiền hoàn được phản ánh lũy kế qua trường `total_refunded_amount`.

---

# 16. Payment Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Thực hiện thanh toán điện tử (Online Gateway) | `MakePayment` |
| Receptionist | Ghi nhận thanh toán tiền mặt tại quầy | `RecordCashPayment` |
| FinanceStaff / System | Xác minh tính hợp lệ của giao dịch thanh toán | `VerifyPayment` |
| System | Tiếp nhận Webhook/Callback từ cổng thanh toán | `ReceivePaymentCallback` |
| FinanceStaff | Quyết toán giao dịch thanh toán (`SUCCESS`) | `SettlePayment` |
| Customer / System | Hủy giao dịch thanh toán đang chờ hoặc quá hạn xử lý (`PENDING / PROCESSING -> CANCELLED`) | `CancelPayment` |
| FinanceStaff | Đối soát giao dịch thanh toán với ngân hàng/cổng | `ReconcilePayment` |

---

# 17. Refund Management

> Theo quyết định nghiệp vụ đã duyệt: Hoàn tiền được tạo theo từng giao dịch `Payment` gốc cụ thể.

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Yêu cầu hoàn tiền giao dịch thanh toán | `RequestRefund` |
| Receptionist | Tạo yêu cầu hoàn tiền tại quầy cho khách | `CreateRefundRequest` |
| StoreManager | Thẩm định và phê duyệt yêu cầu hoàn tiền | `ApproveRefund` |
| StoreManager | Từ chối yêu cầu hoàn tiền | `RejectRefund` |
| Receptionist / StoreManager | Xử lý chi tiền mặt hoàn trực tiếp tại quầy (cho giao dịch CASH) | `ProcessRefund`, `CompleteRefund` |
| FinanceStaff | Tiến hành xử lý hoàn tiền qua cổng thanh toán điện tử | `ProcessRefund` |
| FinanceStaff / System | Ghi nhận giao dịch hoàn tiền thành công (`COMPLETED`) | `CompleteRefund` |
| FinanceStaff / System | Thử lại hoàn tiền qua cổng (tối đa 3 lần) | `RetryRefund` |
| FinanceStaff / StoreManager | Xử lý hoàn tiền thủ công ngoại lệ (chuyển khoản/tiền mặt khi cổng lỗi) | `ResolveRefundManually` |
| System | Ghi nhận lỗi xử lý hoàn tiền từ cổng (`FAILED`) | `FailRefund` |
| FinanceStaff | Đối soát các khoản tiền hoàn trả | `ReconcileRefund` |
| System | Gửi thông báo kết quả hoàn tiền cho khách | `SendRefundNotification` |

---

# 18. Promotion & Voucher Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Organization Admin | Tạo chương trình khuyến mãi (Promotion) | `CreatePromotion` |
| Organization Admin | Quản lý chương trình khuyến mãi | `ManagePromotion` |
| StoreManager | Cấu hình áp dụng Promotion cho Store | `ConfigureStorePromotion` |
| Organization Admin | Tạo mã Voucher ưu đãi | `CreateVoucher` |
| Organization Admin | Quản lý phát hành và điều kiện Voucher | `ManageVoucher` |
| Customer | Sử dụng mã Voucher khi thanh toán | `UseVoucher` |
| System | Kiểm tra tính hợp lệ và điều kiện Voucher | `ValidateVoucher` |
| System | Ghi nhận lịch sử sử dụng Voucher | `TrackVoucherUsage` |

---

# 19. Membership & Loyalty Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đăng ký gói hội viên (Membership) | `RegisterMembership` |
| Customer | Xem thông tin gói hội viên | `ViewMembership` |
| Customer | Gia hạn gói hội viên | `RenewMembership` |
| Customer / StoreManager | Nâng cấp hạng gói hội viên | `UpgradeMembership` |
| StoreManager | Quản lý thông tin gói hội viên | `ManageMembership` |
| System | Xử lý gói hội viên hết hạn | `ProcessMembershipExpiry` |
| Customer | Xem điểm tích lũy (Loyalty Points) | `ViewLoyaltyPoint` |
| Customer | Sử dụng điểm đổi ưu đãi / giảm giá | `RedeemLoyaltyPoint` |
| System | Tích lũy điểm sau giao dịch thành công | `AddLoyaltyPoint` |
| System | Trừ điểm khi đổi thưởng | `DeductLoyaltyPoint` |
| System | Xử lý điểm thưởng hết hạn | `ExpireLoyaltyPoint` |
| StoreManager | Điều chỉnh điểm tích lũy thủ công có lý do | `AdjustLoyaltyPoint` |

---

# 20. Package Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Mua gói dịch vụ trả trước (Package) | `PurchasePackage` |
| Customer | Xem số dư và lịch sử dùng gói | `ViewPackage` |
| Receptionist / Customer / System | Kích hoạt gói dịch vụ (Tại quầy POS, qua PaymentSucceeded, hoặc tự động kích hoạt khi khách check-in sử dụng lượt đầu tiên) | `ActivatePackage` |
| Receptionist | Xác nhận trừ lượt sử dụng gói tại quầy | `ConfirmPackageUsage` |
| StoreManager | Hủy gói dịch vụ theo chính sách hoàn gói (Tạo RefundRequested) | `CancelPackage` |
| StoreManager | Điều chỉnh lượt sử dụng còn lại của gói | `AdjustPackage` |
| System | Theo dõi và ghi nhận lịch sử trừ lượt gói | `TrackPackageUsage` |
| System | Xử lý gói dịch vụ hết hạn | `ProcessPackageExpiry` |

---

# 21. Incident Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Receptionist | Ghi nhận sự cố vận hành/dịch vụ tại quầy | `RecordIncident` |
| Veterinarian | Ghi nhận sự cố trong khám/chữa bệnh hoặc hủy ca cấp cứu | `RecordClinicalIncident` |
| Groomer | Ghi nhận sự cố trong quá trình Grooming hoặc hủy ca | `RecordGroomingIncident` |
| StoreManager | Phân loại mức độ nghiêm trọng của sự cố | `ClassifyIncident` |
| StoreManager | Điều tra nguyên nhân sự cố | `InvestigateIncident` |
| StoreManager | Chuyển cấp xử lý sự cố (Escalate) | `EscalateIncident` |
| StoreManager | Thực hiện biện pháp khắc phục/xử lý | `HandleIncident` |
| StoreManager | Đóng hồ sơ sự cố sau khi hoàn tất | `CloseIncident` |
| System | Gửi thông báo về sự cố cho khách hàng và Admin | `SendIncidentNotification` |

---

# 22. Consent & Privacy Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Cấp quyền đồng ý xử lý dữ liệu (Consent) | `GrantConsent` |
| Customer | Thu hồi quyền đồng ý xử lý dữ liệu | `RevokeConsent` |
| Veterinarian | Yêu cầu quyền truy cập hồ sơ bệnh án Cross-Store | `RequestCrossStoreConsent` |
| Customer / Receptionist | Xác nhận mã OTP (hiệu lực 5 phút) kích hoạt quyền truy cập bệnh án Cross-Store có hiệu lực 24 giờ | `VerifyCrossStoreConsentOTP` |
| Customer | Chủ động thu hồi quyền chia sẻ bệnh án Cross-Store | `RevokeCrossStoreConsent` |
| Veterinarian | Kích hoạt quyền truy cập khẩn cấp hồ sơ bệnh án Cross-store (`is_emergency = true`) | `EmergencyOverrideAccess` |
| Customer | Yêu cầu trích xuất dữ liệu cá nhân | `RequestDataExport` |
| Customer | Yêu cầu xóa/ẩn danh dữ liệu cá nhân | `RequestDataDeletion` |
| Organization Admin | Cấu hình chính sách bảo mật (Privacy Policy) | `ManagePrivacyPolicy` |
| Organization Admin | Cấu hình thời hạn lưu trữ (Retention Policy) | `ManageRetentionPolicy` |
| System | Tự động xử lý gói trích xuất dữ liệu | `ProcessDataExport` |
| System | Tự động xóa hoặc ẩn danh dữ liệu theo quy định | `ProcessDataDeletion` |
| System | Tự động xử lý hết hạn quyền ủy quyền y tế (Quá 24h TTL) | `ProcessConsentExpiry` |

- **Cơ chế Đồng thuận Chia sẻ Dữ liệu Bệnh án Cross-Store (Cross-Store Medical Record Consent Protocol):**
  - *Cơ chế 1 (Tiêu chuẩn):* Bác sĩ gửi yêu cầu (`RequestCrossStoreConsent`), hệ thống phát sinh mã OTP (hiệu lực 5 phút) gửi về số điện thoại hoặc thông báo App của Customer sở hữu Pet. Khách hàng/Tiếp tân nhập mã xác thực (`VerifyCrossStoreConsentOTP`) để mở khóa quyền xem hồ sơ bệnh án trong thời hạn tối đa 24 giờ (`24h TTL`).
  - *Cơ chế 2 (Cấp cứu Khẩn cấp):* Trong tình huống cấp cứu nguy kịch cần tra cứu tiền sử bệnh/dị ứng ngay lập tức, Bác sĩ kích hoạt `EmergencyOverrideAccess` kèm lý do lâm sàng. Hệ thống mở quyền truy cập `ACTIVE` tức thì (`is_emergency = true`), đồng thời kích hoạt `EmergencyAccessOverridden` tự động lập biên bản sự cố y tế `ClinicalIncident` và gửi thông báo cảnh báo `SendIncidentNotification` tới chủ Pet và Store Manager.

---

# 23. Notification Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| System | Gửi thông báo chung | `SendNotification` |
| System | Gửi thông báo xác nhận lịch hẹn | `SendAppointmentNotification` |
| System | Gửi tin nhắn nhắc lịch hẹn sắp tới | `SendAppointmentReminder` |
| System | Gửi thông báo kết quả thanh toán | `SendPaymentNotification` |
| System | Gửi thông báo cập nhật trạng thái đơn hàng | `SendOrderNotification` |
| System | Gửi nhắc lịch tiêm vaccine định kỳ | `SendVaccineReminder` |
| System | Gửi nhắc lịch tái khám thú cưng | `SendFollowUpReminder` |
| System | Gửi thông báo quyền lợi hội viên | `SendMembershipNotification` |
| System | Gửi lại thông báo khi gặp lỗi tạm thời | `RetryNotification` |
| Customer | Xem danh sách thông báo đã nhận | `ViewNotification` |

---

# 24. Reporting & Analytics

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| StoreManager | Xem báo cáo doanh thu Store | `ViewRevenueReport` |
| StoreManager | Xem báo cáo hoạt động lịch hẹn | `ViewAppointmentReport` |
| StoreManager | Xem báo cáo hiệu suất dịch vụ | `ViewServiceReport` |
| StoreManager | Xem báo cáo tồn kho và biến động hàng | `ViewInventoryReport` |
| StoreManager | Xem báo cáo hiệu suất nhân sự | `ViewStaffReport` |
| Organization Admin | Xem báo cáo tổng hợp doanh thu Organization | `ViewOrganizationRevenue` |
| Organization Admin | Xem báo cáo so sánh doanh thu giữa các Store | `CompareStoreRevenue` |
| Organization Admin | Xem báo cáo phân tích khách hàng và thú cưng | `ViewCustomerPetReport` |
| FinanceStaff | Thực hiện đối soát doanh thu chi tiết | `ReconcileRevenue` |
| Platform Admin | Xem báo cáo tổng quan toàn nền tảng | `ViewPlatformReport` |

---

# 25. Audit Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| System | Ghi nhận nhật ký kiểm toán bất biến | `RecordAuditLog` |
| Platform Admin | Tra cứu Audit Log toàn nền tảng | `ViewAuditLog` |
| Organization Admin | Tra cứu Audit Log phạm vi Organization | `ViewAuditLog` |
| StoreManager | Tra cứu Audit Log trong phạm vi Store | `ViewAuditLog` |
| Platform Admin | Truy vết lịch sử thay đổi phân quyền toàn hệ thống | `TrackPermissionChange` |
| Organization Admin | Truy vết lịch sử thay đổi phân quyền trong Org | `TrackPermissionChange` |
| Platform Admin | Truy vết lịch sử truy cập hồ sơ bệnh án toàn hệ thống | `TrackMedicalRecordAccess` |
| Organization Admin | Truy vết lịch sử truy cập hồ sơ bệnh án trong Org | `TrackMedicalRecordAccess` |
| FinanceStaff | Truy vết nhật ký giao dịch tài chính (Payment/Refund) | `TrackPaymentRefundAudit` |
| InventoryStaff | Truy vết nhật ký biến động kho hàng | `TrackInventoryAudit` |
