# Pet Care Ecosystem — Business Operations Catalog

> Mục đích: Liệt kê **các nghiệp vụ chính** và **actor chính** của hệ thống chăm sóc thú cưng đa cửa hàng.
>
> Tài liệu này trả lời: **Ai thực hiện nghiệp vụ gì?** (Actor ↔ Business Operation Mapping).
>
> Tất cả các nghiệp vụ trong tài liệu này đồng bộ 1:1 với các Command Candidate trong Ubiquitous Language Glossary và State Machine Specifications.

---

# 1. Actors chính & Phạm vi Quyền lực (Scopes)

Hệ thống phân định rõ 11 Actors nghiệp vụ được phân bổ trên 4 cấp phạm vi quản trị Multi-tenancy (**Platform Scope**, **Organization Scope**, **Store Scope**, **Customer Scope**) cùng cơ chế Ủy quyền dữ liệu (**Pet Delegation**):

| Actor | Phạm vi (Scope) | Vai trò & Trách nhiệm chính |
|---|---|---|
| **Platform Admin** (`SUPER_ADMIN`) | **Platform** | Quản trị toàn bộ nền tảng SaaS đa tổ chức, quản lý vòng đời Tenant (Organizations), danh mục sản phẩm mẫu và cấu hình hệ thống toàn cục. |
| **Organization Admin** (`ORGANIZATION_ADMIN`) | **Organization** | Quản lý Organization/chuỗi chi nhánh, Warehouse trung tâm, chính sách tổ chức, danh mục bảng giá dịch vụ và các Store trực thuộc. Cách ly dữ liệu 100% giữa các Organization. |
| **StoreManager** (`STORE_MANAGER`) | **Store** | Quản lý, điều phối vận hành Store chi nhánh, phân ca nhân sự, thẩm định và phê duyệt Maker-Checker (`ApproveRefund`, `ApproveStockTransfer`, `ApprovePurchaseRequest`, `ApproveInventoryAdjustment`). |
| **Receptionist** (`RECEPTIONIST`) | **Store** | Tiếp đón khách, quản lý đặt lịch/check-in hẹn khám, xếp hàng walk-in, tạo đơn/hóa đơn POS tại quầy, ghi nhận tiền mặt, tạo yêu cầu hoàn tiền. |
| **Veterinarian** (`VETERINARIAN`) | **Store** | Khám bệnh, chẩn đoán, điều trị, kê đơn thuốc, thực hiện tiêm phòng vaccine và quản lý hồ sơ bệnh án thú cưng (`MedicalRecord`). |
| **Groomer** (`GROOMER`) | **Store** | Kiểm tra thể trạng trước grooming, thực hiện dịch vụ làm đẹp/spa thú cưng, đề xuất dịch vụ phát sinh thêm. |
| **InventoryStaff** | **Store / Warehouse** | *Vai trò vận hành kho:* Nhập, xuất, kiểm kê, tạo phiếu điều chỉnh, điều phối chuyển kho và tiếp nhận hàng hóa mua từ nhà cung cấp. |
| **FinanceStaff** | **Organization / Store** | *Vai trò vận hành tài chính:* Phát hành hóa đơn chính thức, đối soát doanh thu/hóa đơn, thực thi lệnh chi tiền hoàn (`ProcessRefund`, `CompleteRefund`) và đối soát thanh toán. |
| **Customer** (`CUSTOMER`) | **User / Account** | Chủ thú cưng: đăng ký tài khoản, quản lý hồ sơ Pet, đặt lịch hẹn, mua hàng online, thanh toán điện tử, gửi yêu cầu hoàn tiền và quản lý ủy quyền chăm sóc. |
| **Caregiver** | **Pet Delegation** | Người được ủy quyền chăm sóc: tài khoản `CUSTOMER` được chủ pet ủy quyền thông qua quan hệ `PetCaregiverDelegation` để thay mặt đặt lịch, đưa pet đi khám/spa trong phạm vi hiệu lực. |
| **System** | **Automated Runtime** | Tác vụ nền tự động: gửi thông báo, nhắc lịch tiêm, kiểm tra hết hạn (OTP, Order timeout 15p, Package, Voucher), tiếp nhận webhook cổng thanh toán. |

---

# 2. Authentication & OTP

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đăng ký tài khoản | `RegisterAccount` |
| System | Gửi OTP xác thực đăng ký | `SendRegistrationOTP` |
| Customer | Yêu cầu gửi lại OTP | `ResendOTP` |
| Customer | Xác thực mã OTP | `VerifyOTP` |
| System | Kiểm tra tính hợp lệ của OTP | `CheckOTP` |
| System | Xử lý OTP hết hạn | `ExpireOTP` |
| Customer | Đăng nhập hệ thống | `Login` |
| Customer | Đăng xuất khỏi hệ thống | `Logout` |

---

# 3. Identity & Access Management

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

# 4. Organization & Store Management

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

# 5. Customer & Pet Management

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
| System | Xử lý lời mời ủy quyền hết hạn | `ProcessInvitationExpiry` |
| Caregiver | Xem thông tin Pet được ủy quyền | `ViewPet` |
| Caregiver | Thực hiện thao tác được ủy quyền | `PerformDelegatedAction` |
| Receptionist | Tạo Customer tại quầy | `ManageCustomerProfile` |
| Receptionist | Cập nhật thông tin Customer | `ManageCustomerProfile` |
| Receptionist | Tạo hồ sơ Pet tại quầy | `AddPet` |
| Receptionist | Cập nhật hồ sơ Pet | `UpdatePet` |
| Receptionist | Tra cứu Customer và Pet | `SearchCustomerPet` |

---

# 6. Service & Product Catalog

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

# 7. Appointment & Scheduling

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đặt lịch hẹn mới | `BookAppointment` |
| Customer | Xem thông tin lịch hẹn | `ViewAppointment` |
| Customer | Hủy lịch hẹn | `CancelAppointment` |
| Customer | Đổi thời gian lịch hẹn | `RescheduleAppointment` |
| Caregiver | Đặt lịch hẹn cho Pet được ủy quyền | `BookAppointment` |
| Receptionist | Tạo lịch hẹn tại quầy/qua điện thoại | `BookAppointment` |
| Receptionist / System | Xác nhận lịch hẹn | `ConfirmAppointment` |
| Receptionist | Cập nhật chi tiết lịch hẹn | `UpdateAppointment` |
| Receptionist | Hủy lịch hẹn | `CancelAppointment` |
| Receptionist | Đổi lịch hẹn | `RescheduleAppointment` |
| Receptionist | Tiếp nhận check-in lịch hẹn | `CheckInAppointment` |
| Veterinarian / Groomer | Bắt đầu thực hiện dịch vụ theo lịch hẹn | `StartAppointmentService` |
| Receptionist | Tiếp nhận check-out hoàn tất lịch hẹn | `CheckOutAppointment` |
| Receptionist / System | Đánh dấu khách vắng mặt (No-Show) | `MarkNoShow` |
| StoreManager | Quản lý lịch Store | `ManageStoreSchedule` |
| StoreManager | Phân công Staff vào ca/lịch hẹn | `AssignStaff` |
| StoreManager | Điều phối lịch Store | `CoordinateSchedule` |
| System | Kiểm tra thời gian trống (Availability) | `CheckAvailability` |
| System | Gửi thông báo nhắc lịch hẹn | `SendAppointmentReminder` |

---

# 8. Walk-in & Queue Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Yêu cầu dịch vụ trực tiếp (Walk-in) | `RequestWalkInService` |
| Receptionist | Tạo tiếp nhận Walk-in | `CreateWalkIn` |
| Receptionist | Đưa khách vào Queue hàng đợi | `EnqueueCustomer` |
| Receptionist | Gọi khách vào phục vụ | `CallCustomer` |
| Receptionist | Check-in lượt phục vụ Walk-in | `CheckInWalkIn` |
| StoreManager | Điều phối hàng đợi Queue | `CoordinateQueue` |
| System | Quản lý thứ tự số thứ tự Queue | `ManageQueueOrder` |
| System | Gửi thông báo khi đến lượt phục vụ | `SendTurnNotification` |

- **Cơ chế Cầu nối Dữ liệu Walk-in sang Appointment (Walk-in to Appointment Lifecycle Bridge):**
  - Khi tiếp tân thực hiện lệnh `CheckInWalkIn`, hệ thống tự động khởi tạo một bản ghi `Appointment` nội bộ với kênh tiếp nhận `Channel = WALK_IN`, gắn mã phiếu hàng đợi (`QueueTicketId`), nhân viên phục vụ (`StaffId`) và tài nguyên cơ sở vật chất (`StoreResourceId`).
  - Bản ghi Appointment này được chuyển thẳng sang trạng thái `IN_PROGRESS`, cho phép các module khám bệnh, tiêm phòng, grooming, xuất hóa đơn và báo cáo doanh thu vận hành trên cùng một mô hình dữ liệu đồng nhất.

---

# 9. Workforce Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Organization Admin | Quản lý danh sách Staff toàn Organization | `ManageStaff` |
| StoreManager | Tạo hồ sơ Staff | `CreateStaff` |
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

# 10. Veterinary / Clinical Management

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
| Veterinarian / Receptionist | Xác thực mã OTP đồng thuận truy cập bệnh án Cross-store | `VerifyCrossStoreConsentOTP` |
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

# 11. Vaccination Management

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
  1. Khách hàng đặt lịch hẹn (`BookAppointment`) hoặc đến trực tiếp (`CreateWalkIn`).
  2. Tiếp nhận check-in tại quầy (`CheckInAppointment` / `CheckInWalkIn`).
  3. Bác sĩ thú y thực hiện khám sàng lọc thể trạng nhanh (Pre-vaccination Screening: kiểm tra thân nhiệt, thể trạng, lịch sử phản ứng phụ) mà không cần tạo bệnh án phức tạp (`MedicalRecord`).
  4. Bác sĩ quét mã vạch (Barcode/QR code) của lọ vaccine (`AdministerVaccine` theo `RULE-10-06`). Hệ thống tự động xác thực hạn sử dụng và tồn kho thời gian thực.
  5. Hệ thống ghi nhận `RecordVaccination` vào hồ sơ tiêm chủng của Pet, tự động trừ tồn kho khả dụng của lô vaccine, và tự động lập lịch tiêm nhắc lại (`ScheduleNextVaccination`).
  6. Tiếp tân hoàn tất check-out (`CheckOutAppointment`) và tạo hóa đơn thanh toán tại quầy (`CreateInvoice`).

---

# 12. Grooming Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Đặt dịch vụ Grooming | `BookAppointment` |
| Receptionist | Tiếp nhận và check-in dịch vụ Grooming | `CheckInGrooming` |
| Groomer | Kiểm tra thể trạng trước Grooming | `InspectPet` |
| Groomer | Thực hiện dịch vụ Grooming | `PerformGrooming` |
| Groomer | Cập nhật kết quả quá trình Grooming | `UpdateGroomingResult` |
| Groomer | Đề xuất dịch vụ phát sinh thêm | `AddGroomingService` |
| Customer | Xác nhận đồng ý dịch vụ phát sinh | `ConfirmAdditionalService` |
| Groomer | Hoàn thành dịch vụ Grooming | `CompleteGrooming` |

---

# 13. Inventory & Warehouse Management

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
| InventoryStaff | Nhận hàng và nhập kho chuyển đến | `ReceiveStockTransfer` |
| StoreManager | Phê duyệt điều chỉnh tồn kho | `ApproveInventoryAdjustment` |
| Organization Admin | Quản lý Warehouse trung tâm | `ManageWarehouse` |
| InventoryStaff | Nhập kho tại Warehouse | `ReceiveAtWarehouse` |
| System | Cảnh báo tồn kho dưới ngưỡng an toàn (Low Stock) | `TriggerLowStockAlert` |
| System | Cảnh báo hàng sắp hết hạn (Expiry Warning) | `TriggerExpiryWarning` |

---

# 14. Procurement Management

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
| InventoryStaff / StoreManager | Hủy đơn đặt hàng nhà cung cấp | `CancelPurchaseOrder` |
| InventoryStaff | Cập nhật tăng tồn kho sau mua hàng | `UpdateInventory` |
| Organization Admin | Quản lý danh sách nhà cung cấp (Supplier) | `ManageSupplier` |

---

# 15. Order Management (v1 Store Fulfillment)

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Tạo đơn hàng bán lẻ / dịch vụ | `CreateOrder` |
| Customer | Xác nhận giỏ hàng thanh toán | `CheckoutOrder` |
| Customer | Xem thông tin đơn hàng | `ViewOrder` |
| Customer | Hủy đơn hàng chưa thanh toán/chưa xử lý | `CancelOrder` |
| Receptionist | Tạo đơn hàng tại quầy | `CreateOrder` |
| Receptionist / System | Xác nhận đơn hàng đã thanh toán | `ConfirmOrder` |
| Receptionist | Tiếp nhận và xử lý đơn hàng tại Store | `ProcessOrder` |
| InventoryStaff | Soạn và đóng gói sản phẩm bán lẻ | `PrepareProductOrder` |
| Receptionist | Bàn giao và hoàn thành đơn hàng tại quầy | `CompleteStoreOrder` |
| System | Gửi thông báo trạng thái đơn hàng | `SendOrderNotification` |

---

# 16. Billing & Invoice Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Receptionist / FinanceStaff | Tạo bản nháp hóa đơn (Draft Invoice) | `CreateInvoice` |
| Receptionist | Thêm mục dịch vụ vào hóa đơn | `AddServiceToInvoice` |
| Receptionist | Thêm mục sản phẩm vào hóa đơn | `AddProductToInvoice` |
| Receptionist | Áp dụng mã giảm giá / khuyến mãi | `ApplyDiscount` |
| FinanceStaff / Receptionist | Phát hành hóa đơn chính thức (Issue) | `IssueInvoice` |
| FinanceStaff | Hủy vô hiệu hóa đơn đã phát hành (Void) | `VoidInvoice` |
| FinanceStaff | Thực hiện đối soát hóa đơn định kỳ | `ReconcileInvoice` |
| Customer | Xem thông tin hóa đơn | `ViewInvoice` |

---

# 17. Payment Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Thực hiện thanh toán điện tử (Online Gateway) | `MakePayment` |
| Receptionist | Ghi nhận thanh toán tiền mặt tại quầy | `RecordCashPayment` |
| FinanceStaff / System | Xác minh tính hợp lệ của giao dịch thanh toán | `VerifyPayment` |
| System | Tiếp nhận Webhook/Callback từ cổng thanh toán | `ReceivePaymentCallback` |
| FinanceStaff | Quyết toán giao dịch thanh toán | `SettlePayment` |
| Customer / System | Hủy giao dịch thanh toán đang chờ | `CancelPayment` |
| FinanceStaff | Đối soát giao dịch thanh toán với ngân hàng/cổng | `ReconcilePayment` |

---

# 18. Refund Management

> Theo quyết định nghiệp vụ đã duyệt: Hoàn tiền được tạo theo từng giao dịch `Payment` gốc cụ thể.

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Yêu cầu hoàn tiền giao dịch thanh toán | `RequestRefund` |
| Receptionist | Tạo yêu cầu hoàn tiền tại quầy cho khách | `CreateRefundRequest` |
| StoreManager | Thẩm định và phê duyệt yêu cầu hoàn tiền | `ApproveRefund` |
| StoreManager | Từ chối yêu cầu hoàn tiền | `RejectRefund` |
| FinanceStaff | Tiến hành xử lý hoàn tiền qua cổng/tiền mặt | `ProcessRefund` |
| FinanceStaff / System | Ghi nhận giao dịch hoàn tiền thành công | `CompleteRefund` |
| System | Ghi nhận lỗi xử lý hoàn tiền từ cổng | `FailRefund` |
| FinanceStaff | Đối soát các khoản tiền hoàn trả | `ReconcileRefund` |
| System | Gửi thông báo kết quả hoàn tiền cho khách | `SendRefundNotification` |

---

# 19. Promotion & Voucher Management

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

# 20. Membership & Loyalty Management

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

# 21. Package Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Mua gói dịch vụ trả trước (Package) | `PurchasePackage` |
| Customer | Xem số dư và lịch sử dùng gói | `ViewPackage` |
| Receptionist | Kích hoạt gói dịch vụ cho khách | `ActivatePackage` |
| Receptionist | Xác nhận trừ lượt sử dụng gói tại quầy | `ConfirmPackageUsage` |
| StoreManager | Hủy gói dịch vụ theo chính sách hoàn gói | `CancelPackage` |
| StoreManager | Điều chỉnh lượt sử dụng còn lại của gói | `AdjustPackage` |
| System | Theo dõi và ghi nhận lịch sử trừ lượt gói | `TrackPackageUsage` |
| System | Xử lý gói dịch vụ hết hạn | `ProcessPackageExpiry` |

---

# 22. Incident Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Receptionist | Ghi nhận sự cố vận hành/dịch vụ tại quầy | `RecordIncident` |
| Veterinarian | Ghi nhận sự cố trong khám/chữa bệnh | `RecordClinicalIncident` |
| Groomer | Ghi nhận sự cố trong quá trình Grooming | `RecordGroomingIncident` |
| StoreManager | Phân loại mức độ nghiêm trọng của sự cố | `ClassifyIncident` |
| StoreManager | Điều tra nguyên nhân sự cố | `InvestigateIncident` |
| StoreManager | Chuyển cấp xử lý sự cố (Escalate) | `EscalateIncident` |
| StoreManager | Thực hiện biện pháp khắc phục/xử lý | `HandleIncident` |
| StoreManager | Đóng hồ sơ sự cố sau khi hoàn tất | `CloseIncident` |
| System | Gửi thông báo về sự cố cho khách hàng | `SendIncidentNotification` |

---

# 23. Consent & Privacy Management

| Actor | Nghiệp vụ | Mã Command tương ứng |
|---|---|---|
| Customer | Cấp quyền đồng ý xử lý dữ liệu (Consent) | `GrantConsent` |
| Customer | Thu hồi quyền đồng ý xử lý dữ liệu | `RevokeConsent` |
| Customer / Receptionist | Xác nhận mã OTP đồng thuận chia sẻ bệnh án Cross-store | `VerifyCrossStoreConsentOTP` |
| Veterinarian | Kích hoạt quyền truy cập khẩn cấp hồ sơ bệnh án Cross-store | `EmergencyOverrideAccess` |
| Customer | Yêu cầu trích xuất dữ liệu cá nhân | `RequestDataExport` |
| Customer | Yêu cầu xóa/ẩn danh dữ liệu cá nhân | `RequestDataDeletion` |
| Organization Admin | Cấu hình chính sách bảo mật (Privacy Policy) | `ManagePrivacyPolicy` |
| Organization Admin | Cấu hình thời hạn lưu trữ (Retention Policy) | `ManageRetentionPolicy` |
| System | Tự động xử lý gói trích xuất dữ liệu | `ProcessDataExport` |
| System | Tự động xóa hoặc ẩn danh dữ liệu theo quy định | `ProcessDataDeletion` |

- **Cơ chế Đồng thuận Chia sẻ Dữ liệu Bệnh án Cross-Store (Cross-Store Medical Record Consent Protocol):**
  - *Cơ chế 1 (Tiêu chuẩn):* Bác sĩ gửi yêu cầu (`RequestCrossStoreConsent`), hệ thống phát sinh mã OTP gửi về số điện thoại hoặc thông báo App của Customer sở hữu Pet. Tiếp tân/Bác sĩ nhập mã xác thực (`VerifyCrossStoreConsentOTP`) để mở khóa quyền xem hồ sơ bệnh án trong 24 giờ.
  - *Cơ chế 2 (Cấp cứu Khẩn cấp):* Trong tình huống cấp cứu nguy kịch cần tra cứu tiền sử bệnh/dị ứng ngay lập tức, Bác sĩ kích hoạt `EmergencyOverrideAccess` kèm lý do lâm sàng. Hệ thống mở quyền truy cập tức thì, đồng thời ghi nhận nhật ký kiểm toán đặc biệt `EMERGENCY_ACCESS_LOG` và gửi tin nhắn cảnh báo bảo mật tới chủ Pet.

---

# 24. Notification Management

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

# 25. Reporting & Analytics

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

# 26. Audit Management

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
