# Pet Care Ecosystem — Hợp đồng giữa module

> Hợp đồng để BE-1 và BE-2 code song song: mỗi module công bố **interface** (gọi trực tiếp) và **sự kiện** (phát đồng bộ) cho module khác dùng. Chỉ căn cứ `01`–`05` + `INDEX` bản **v16**. Mã nguồn của hợp đồng nằm ở `BE/src/main/java/com/petcare/module/<module>/api/`; file này giải thích ai sở hữu, ai gọi, gọi khi nào và vì sao. Khi hai bên mâu thuẫn, sửa cả hai trong cùng một commit.

## 1. Quy ước

**Ranh giới module = 11 nhóm của domain-model (04).** Mỗi nhóm là một package `com.petcare.module.<module>`.

**Phần công khai của module là package `api/`.** Module khác chỉ được import `module.<x>.api.*`; không import entity, repository hay service nội bộ của module khác. `api/` chỉ chứa interface, record (dữ liệu trao đổi), enum dùng chung và record sự kiện. Mọi id là `Long` (erd §0: `BIGINT`).

**Hai cơ chế, chọn theo số bên bị kéo theo** (04 nguyên tắc 6: hệ quả liên đối tượng chạy trong **một transaction**, do service của bên phát điều phối):

| Cơ chế | Dùng khi | Cài đặt |
|---|---|---|
| **Gọi interface** | Hệ quả 1–1 đã biết trước (Visit#1 → mở Order, Order#8 → trừ kho) hoặc cần giá trị trả về | Bên phát inject interface của bên kia và gọi trong transaction của mình. Implementation của method ghi đặt `@Transactional(propagation = MANDATORY)` |
| **Sự kiện đồng bộ** | Một sự kiện lan ra nhiều module (thú mất, chuyển chủ, hủy hàng loạt, khóa tài khoản) | Bên phát `ApplicationEventPublisher.publishEvent(record)`. Bên nhận dùng `@EventListener` (**không** dùng `@TransactionalEventListener(AFTER_COMMIT)`), nên chạy cùng thread, cùng transaction. Listener ném lỗi thì toàn bộ rollback |

**Query** (`*QueryApi`) chỉ đọc, gọi được trong hoặc ngoài transaction.

**Lỗi nghiệp vụ** do bên sở hữu ném, kèm mã rule `BR-…` của rule bị vi phạm; bên gọi không bắt để "sửa" mà để lỗi đi lên tới use case. Riêng `StockApi.issueForSale` trả về danh sách thiếu thay vì ném lỗi, vì lễ tân cần biết từng dòng thiếu (BR-BH-04).

**Đọc tham số [CFG]:** đã chốt ở ADR-0004 và đã có implementation. Inject `identity.api.SystemConfigApi` và đọc `getInt/getDecimal/getBool/getTime(ConfigKey.X)`. Danh mục là enum `identity.api.ConfigKey` (52 tham số, seed ở `V2__seed_system_configs.sql`). Cần tham số mới thì thêm hằng số + migration seed, báo BE-1.

**Người đang đăng nhập và phạm vi chi nhánh:** `platform.security.BranchScope` (ADR-0003). `current().accountId()` là actor truyền vào các interface có tham số `actorId`; `resolve` / `check` dùng cho dữ liệu có `branch_id`.

**Thông báo và audit** luôn ghi trong transaction nghiệp vụ: `NotificationApi.enqueue` chỉ ghi `notification_outbox`, worker ST20 gửi sau (ADR-0012). Người nhận theo kênh: `EMAIL` bắt buộc `recipientEmail` (địa chỉ chốt lúc sự kiện), `IN_APP` bắt buộc `recipientAccountId`; tài khoản `PENDING` không truyền `recipientAccountId`. Outbox không chống use case chạy hai lần: use case tự chống lặp (rule/FSM; job nhắc đặt cờ "đã nhắc" cùng transaction với `enqueue`). Biến chứa bí mật đặt tên có `otp`/`mat_khau`/`password`/`token`/`secret` để bị xóa sau khi gửi. Audit dùng `platform.audit.AuditRecorder.record` (ADR-0001, đã có trong `platform/`), chỉ INSERT.

**Làm song song với stub.**
- Bên gọi viết unit test với Mockito trên interface, không chờ bên sở hữu.
- Khi bên gọi cần chạy app mà bên sở hữu chưa cài, bên sở hữu commit một lớp `<Interface>Placeholder` ném `UnsupportedOperationException`. Không trả giá trị mặc định "an toàn", vì giá trị giả (ví dụ `hasOpenShift = false`) làm guard đi qua sai mà không ai biết.
- Đổi chữ ký interface phải báo bên kia trước khi merge.

## 2. Module và chủ sở hữu

| Module (package) | Mã | Model (04) | Owner | `api/` công bố |
|---|---|---|---|---|
| `identity` | TK, QT | Account, StaffProfile, OtpToken, Session, AuditLog, SystemConfig, NotificationTemplate | BE-1 | `StaffDirectoryApi`, `SystemConfigApi` + `ConfigKey`, `ConfigValueType` (ADR-0004, xem §1), `NotificationTemplateCode`, `NotificationTemplateQueryApi` (đọc mẫu cho ST20, ADR-0012), `Role`, `AccountLockedEvent` |
| `branch` | CN | Branch, OpeningHours, Holiday, BranchService, BranchQuotaDefault, SlotQuota | BE-2 | `BranchQueryApi`, `BranchClinicCancellationEvent` |
| `customer` | KH | Customer, Address, Pet, WeightRecord | BE-2 | `CustomerApi`, `CustomerQueryApi`, `PetApi`, `PetQueryApi`, `Species`, `WeightSource`, `PetDeceasedEvent`, `PetOwnerTransferredEvent` |
| `catalog` | SP | ProductCategory, Product, Service, KennelType, VaccineType, VaccinationProtocol | BE-2 | `CatalogQueryApi`, `ServiceGroup`, `MedicalType`, `ProductType` |
| `appointment` | LH | Appointment, BookingRestriction | BE-1 | `AppointmentApi`, `AppointmentQueryApi`, `BookingRestrictionApi`, `AppointmentReportApi` |
| `visit` | TN, KB | Visit, VisitAssignment, MedicalRecord, MedicalRecordAddendum, PrescriptionItem, Vaccination | BE-1 | `VisitQueryApi`, `VaccinationQueryApi`, `PrescriptionApi`, `FollowUpApi`, `VaccinationReportApi` |
| `boarding` | LT | Kennel, BoardingBooking, BoardingCheckIn, CareLog | BE-2 | `BoardingQueryApi` |
| `sales` | BH, TG | Order, OrderLine, Payment, CashierShift | BE-2 | `VisitOrderApi`, `BoardingOrderApi`, `OrderQueryApi`, `CashierShiftQueryApi`, `SalesReportApi`, `OrderSource` |
| `inventory` | KO | Supplier, InventoryItem, StockLot, StockReceipt, StockAdjustment, StockMovement | BE-2 | `StockApi`, `StockQueryApi`, `StockReportApi` |
| `content` | CK, BV, DG | ArticleCategory, Article, PageContent, Feedback | BE-2 | `FeedbackQueryApi`, `FeedbackReportApi` |
| `care` | TB | CareTask, Notification, NotificationOutbox | BE-1 | `NotificationApi`, `CareTaskApi` |
| `report` | BC | — (không có model, 04 *Ngoài phạm vi*) | BE-2 | Không công bố gì; chỉ có controller UC89 gọi các `*ReportApi` (§8 Q3) |

## 3. Sự kiện

| Sự kiện | Bên phát · khi nào | Bên nhận phải làm gì | Nguồn |
|---|---|---|---|
| `PetDeceasedEvent` | customer · đánh dấu đã mất (sau khi đã kiểm: không có Visit mở, không đang lưu trú) | **appointment:** lịch `BOOKED` tương lai → `CANCELLED`, `cancel_source = CLINIC`, không hủy muộn, thông báo khách (Lịch hẹn#5). **boarding:** đặt chỗ `BOOKED` → `CANCELLED` tương tự (Đặt chỗ#5). **care:** Care Task `OPEN` của thú → `CANCELLED` (Care Task#4, ST19). **visit:** hủy nhắc tái khám còn chờ (`follow_up_cancelled_at`) | BR-KH-05, BR-TB-03, Phụ lục 03 |
| `PetOwnerTransferredEvent` | customer · chuyển chủ (sau khi đã kiểm: không Visit mở, không Order `PENDING`, không đang lưu trú) | **appointment**, **boarding:** hủy `BOOKED` của thú như trên, thông báo chủ cũ. Nhắc tái chủng/tái khám **không** hủy, đi theo thú. **care:** Care Task `OPEN` của thú đổi `customer_id` sang chủ mới (§8 Q1) | BR-KH-08, Phụ lục 03 |
| `BranchClinicCancellationEvent` | branch · BRANCH_MANAGER chọn hủy hàng loạt khi thêm ngày nghỉ / thu hẹp giờ mở cửa | **appointment:** các `appointmentIds` → `CANCELLED` (`CLINIC`, không hủy muộn, không tính lần đổi), thông báo từng khách. **boarding:** các `boardingBookingIds` → `CANCELLED` tương tự | BR-CN-03, 04, BR-LH-10, Lịch hẹn#5, Đặt chỗ#5 |
| `AccountLockedEvent` | identity · ADMIN khóa tài khoản (Tài khoản#7) | **visit:** Visit `IN_PROGRESS` đang gán cho người bị khóa → `needs_reassign = true`. Thông báo cho BRANCH_MANAGER / SUPER_MANAGER / lễ tân do identity tự gửi (người nhận khi khóa khách: §8 Q2) | BR-QT-11, BR-TN-08 |

## 4. Gọi interface — ai gọi ai

Mũi tên `A → B.m()`: module A gọi method m của module B. Cột *Chuyển* là mã chuyển trạng thái trong 03.

### 4.1 Chuỗi tác động trong Phụ lục của 03

| Sự kiện nguồn (03) | Cách cài | Lời gọi |
|---|---|---|
| Visit#1 Tiếp nhận | gọi | visit → `AppointmentApi.checkIn` (nếu có lịch) · visit → `VisitOrderApi.openForVisit` · visit có dịch vụ `EXAM` → nội bộ hủy nhắc tái khám + `CareTaskApi.cancelFollowUpTasks` |
| Visit#5 Hoàn tất | gọi | visit → `VisitOrderApi.findServiceIds` (kiểm điều kiện EXAM/VACCINE, BR-KB-02) → `AppointmentApi.completeByVisit` · `VisitOrderApi.markPendingByVisit`; khóa bệnh án là nội bộ visit |
| Visit#6 Hủy lượt | gọi | visit → `AppointmentApi.cancelByVisit` · `VisitOrderApi.cancelByVisit` |
| Visit — ghi nhận mũi tiêm | gọi | visit → `StockApi.lockFefoLot` → `VisitOrderApi.addVaccineLine` → INSERT `vaccinations` → `StockApi.issueForVaccination` → đánh dấu `superseded_at` các mũi cũ cùng loại → `CareTaskApi.cancelForRevaccination` |
| Visit — xóa mũi tiêm ghi nhầm | gọi | visit → `StockApi.revertVaccination` → `VisitOrderApi.removeVaccineLine` → xóa `vaccinations` |
| Lịch hẹn#1 / Visit#1 có dịch vụ Khám | gọi | appointment (nhóm MEDICAL) → `FollowUpApi.cancelPendingFollowUps` → (bên trong visit) `CareTaskApi.cancelFollowUpTasks` |
| Order#8 Thu tiền | gọi | sales → `StockApi.issueForSale` (trả danh sách thiếu); thiếu thuốc kê đơn → `PrescriptionApi.markExternalPurchaseAtPayment` rồi xóa dòng. Giao thú (Đặt chỗ#10) không cần đẩy, boarding hỏi `BoardingOrderApi.hasPaidOrder` |
| Đặt chỗ#3 Nhận thú | nội bộ boarding (Chuồng#2) + gọi | boarding → `VaccinationQueryApi.findBoardingVaccineGaps` (rỗng mới nhận) · `PetApi.recordWeight(INTAKE)` |
| Đặt chỗ#9 Bắt đầu trả thú | gọi | boarding → `BoardingOrderApi.openOrReuse` |
| Đặt chỗ#10, #11 Kết thúc lưu trú | nội bộ boarding (Chuồng#3) + gọi | #10 điều kiện `BoardingOrderApi.hasPaidOrder`; #11 → `BoardingOrderApi.openOrReuse` (tính đến ngày mất) |
| Thú đánh dấu đã mất | sự kiện | `PetDeceasedEvent` (§3) |
| Chuyển chủ | sự kiện | `PetOwnerTransferredEvent` (§3) |

### 4.2 Guard và tra cứu chéo

| Bên gọi · ngữ cảnh | Gọi | Nguồn |
|---|---|---|
| identity · Đăng ký (Tài khoản#1) | `CustomerApi.createOnlineProfile` | BR-KH-01 |
| identity · Xác thực OTP (Tài khoản#2) | `CustomerApi.flagLinkDecisionIfPhoneMatches` — identity đã gọi từ 06/10 (`RegistrationService.verifyAccount`, sau khi dùng mã và chuyển `ACTIVE`, cùng transaction) | BR-TK-19 |
| identity · ST02 (Tài khoản#3) | `CustomerApi.deleteOnlineProfileOfUnverifiedAccount` | BR-TK-08 |
| identity · Đăng nhập (UC03) | `CustomerQueryApi.findCustomerIdByAccountId` → `findContact(...).linkDecisionPending()` — chỉ tài khoản `CUSTOMER`, nhánh mật khẩu đúng và hợp lệ; identity đã gọi từ 08/10 (`LoginAttemptService`, docs/adr/0019) | BR-TK-19 |
| identity · Thông tin người đang đăng nhập (`GET /me`, UC06) | `CustomerQueryApi.findCustomerIdByAccountId` → `findContact(...).linkDecisionPending()` — chỉ tài khoản `CUSTOMER`; identity gọi từ 09/10 (`MeService`), placeholder → 500 (D010) | BR-KH-01, BR-TK-19 |
| identity · Liên kết hồ sơ (UC07, UC22) | `CustomerApi.linkAccountToCounterProfile(accountId, counterCustomerId, expectedCounterEmail, accountEmail)` / `declineLink` — **chữ ký đổi 10/10** (bỏ `actorId`, thêm email đã nhận mã; docs/adr/0027 mục 2): customer khóa hồ sơ tại quầy + hồ sơ online, kiểm lại dưới khóa, chỉ ném BR-TK-19, xóa `addresses` của hồ sơ online → hồ sơ online + `flush()` → gán hồ sơ tại quầy, không audit (identity ghi `CUSTOMER_PROFILE_LINKED`); customer tự kiểm dữ liệu qua `AppointmentQueryApi`, `BoardingQueryApi`, `OrderQueryApi`, `FeedbackQueryApi` `.existsByCustomer`. identity gọi từ 10/10 (`LinkProfileAttemptService`, sau khi khóa `accounts` và tiêu mã OTP) | BR-TK-19 |
| identity · Vô hiệu hóa, điều chuyển (Tài khoản#5) | `VisitQueryApi.findUnfinishedVisitIdsAssignedTo`, `CashierShiftQueryApi.hasOpenShift`, `BranchQueryApi.findBranch` (BRANCH_MANAGER cuối của chi nhánh `ACTIVE`) | BR-QT-04, 06, 08 |
| identity · Tạo nhân viên, kích hoạt lại | `BranchQueryApi.findBranch` (chi nhánh `DRAFT`/`ACTIVE`) | BR-QT-03, 10 |
| identity · Khóa tài khoản khách (Tài khoản#7) | `CustomerQueryApi.findCustomerIdByAccountId` → `AppointmentQueryApi.findBookedByCustomer`, `BoardingQueryApi.findActiveStaysByCustomer`, `OrderQueryApi.findUnpaidOrdersByCustomer` → `StaffDirectoryApi.findActiveStaffIds(…, RECEPTIONIST)` | BR-QT-11, §8 Q2 |
| identity · Gợi ý liên kết hồ sơ (UC07) | `CustomerQueryApi.findLinkCandidates` (không bao giờ null) và **`checkOnlineProfileLinkable(accountId)` → `LINKABLE / ALREADY_LINKED / HAS_DATA` (thêm 10/10, docs/adr/0027 mục 4)**; identity gọi ở `/me/link-candidates`, `/me/link/otp` | BR-TK-19 |
| catalog · Dịch vụ công khai | `BranchQueryApi.findActiveBranchIdsEnablingService` | BR-CK-01 |
| care · Danh sách Care Task (SĐT cần gọi) | `CustomerQueryApi.findContact`; `BoardingQueryApi.findBooking` (`emergencyPhone`) cho PICKUP_OVERDUE | BR-TB-05 |
| branch · Kích hoạt (Chi nhánh#2) | `StaffDirectoryApi.countBranchManagers` | BR-CN-01, BR-QT-04 |
| branch · Thêm ngày nghỉ / đổi giờ | `AppointmentQueryApi.findBookedFrom`, `BoardingQueryApi.findBookedFrom` → nếu có ảnh hưởng: từ chối hoặc phát `BranchClinicCancellationEvent` | BR-CN-03, 04, BR-LH-10 |
| customer · Khóa loài | `VisitQueryApi.hasMedicalHistory` | BR-KH-03 |
| customer · Đánh dấu đã mất | `VisitQueryApi.hasOpenVisit`, `BoardingQueryApi.isPetInStay` | BR-KH-05 |
| customer · Xóa thú | `AppointmentQueryApi`, `BoardingQueryApi`, `VisitQueryApi` `.existsByPet` (Order VISIT/BOARDING luôn kèm Visit/đặt chỗ) | BR-KH-06 |
| customer · Chuyển chủ | `VisitQueryApi.hasOpenVisit`, `OrderQueryApi.hasPendingOrderForPet`, `BoardingQueryApi.isPetInStay` | BR-KH-08 |
| catalog · Tắt cờ hạn dùng | `StockQueryApi.hasStockAnywhere` | BR-SP-05 |
| catalog · Xóa loại vaccine | `VaccinationQueryApi.existsByVaccineType` | BR-SP-07 |
| appointment · Đặt / đổi lịch (Lịch hẹn#1, #2) | `BranchQueryApi` (giờ, ngày nghỉ, dịch vụ bật, quota), `CatalogQueryApi.findService`, `PetQueryApi.findPet`, `CustomerQueryApi.findContact` (cờ chờ liên kết) | BR-LH-01…05, BR-TK-19 |
| appointment · Hạn chế đặt online | `BoardingQueryApi.countViolations` | BR-LH-09 |
| visit · Tiếp nhận (Visit#1) | `BranchQueryApi.findBranch`, `isOpenAt`, `isServiceEnabled`; `PetQueryApi.findPet`; `CatalogQueryApi.findService` | BR-TN-01…04, BR-CN-05 |
| visit · Gán / gán lại (Visit#2, #4) | `StaffDirectoryApi.findAssignableStaff`, `findStaff`; `VisitOrderApi.setAutoLineOwner`, `transferLineOwnership`; `AuditRecorder.record` | BR-TN-05, 06, 08 |
| visit · Kê đơn | `CatalogQueryApi.findProduct`, `StockQueryApi.availableQuantity` → `VisitOrderApi.addDrugLine` / `removeDrugLine` | BR-KB-03 |
| visit · Tiêm | `CatalogQueryApi.findProtocol`, `findProtocolDose`, `StockQueryApi.availableQuantity` | BR-KB-04 |
| visit · ST04 nhắc tái chủng / tái khám | `CustomerQueryApi.findContact`, `PetQueryApi.findPet` → `NotificationApi.enqueue` hoặc `CareTaskApi.create` | BR-TB-01, 02, 04, 06 |
| boarding · Đặt chỗ (Đặt chỗ#1) | `BookingRestrictionApi.isRestricted`, `CatalogQueryApi.findKennelType`, `PetQueryApi.findPet`, `BranchQueryApi`, `VaccinationQueryApi.findBoardingVaccineGaps` (chỉ cảnh báo) | BR-LT-02…05 |
| boarding · NO_SHOW (ST05), hủy muộn | `BookingRestrictionApi.evaluateAfterViolation` | BR-LH-09, BR-LT-06 |
| boarding · ST15 quá hạn ≥ 1 ngày | `CareTaskApi.create(PICKUP_OVERDUE)` | BR-LT-10 |
| sales · Thêm/xóa dòng của Order VISIT (UC67) | `VisitQueryApi.findVisit` (người phụ trách) | BR-BH-02, BR-TN-01 |
| sales · Hủy Order BOARDING (Order#9, #10) | `BoardingQueryApi.findBooking` (thú còn trong chuồng?) | BR-LT-09, BR-BH-05 |
| sales · Mở ca, ST13 | `BranchQueryApi.openingRangesOn`, `isHoliday`, `nextOpeningStart`, `findBranch` | BR-TG-05, BR-CN-05 |
| identity · Đội ngũ bác sĩ công khai (`GET /api/public/vets`, identity-v1 #37) | `BranchQueryApi.listActiveBranches` (lọc chi nhánh `ACTIVE`, lấy tên); trong module: `StaffDirectoryApi.listPublicVets` — **10/10, ADR-0026** thay ADR-0024 mục 5: content **không** cài #37 | BR-TK-20, BR-CK-01 |
| content · Trang công khai | `BranchQueryApi.listActiveBranches`, `StockQueryApi.branchIdsWithAvailableStock` | BR-CK-01, 03 |
| report · Xem báo cáo (UC89) | `SalesReportApi`, `AppointmentReportApi`, `VaccinationReportApi`, `StockReportApi`, `FeedbackReportApi` | BR-BC-01…04, §8 Q3 |
| mọi module | `AuditRecorder.record` (platform), `BranchScope` (platform), `SystemConfigApi.get*(ConfigKey)`, `NotificationApi.enqueue`, `StaffDirectoryApi.findActiveStaffIds` (người nhận thông báo) | BR-QT-13, 15 |

## 5. Hai luồng cần làm đúng thứ tự

**Ghi nhận mũi tiêm** (Visit `IN_PROGRESS`, một transaction). `vaccinations.stock_lot_id` và `order_line_id` là `NOT NULL`, nhưng `stock_movements.source_id` lại cần id của mũi tiêm, nên phải theo thứ tự:
1. `StockApi.lockFefoLot` — khóa lô FEFO chưa hết hạn.
2. `VisitOrderApi.addVaccineLine` — sinh dòng VACCINE.
3. INSERT `vaccinations` với lô và dòng ở trên.
4. `StockApi.issueForVaccination` — trừ lô, ghi movement nguồn là mũi tiêm.
5. Đánh dấu `superseded_at` các mũi cũ cùng loại vaccine, gọi `CareTaskApi.cancelForRevaccination`.

Xóa mũi tiêm ghi nhầm đi ngược lại: `revertVaccination` → `removeVaccineLine` → xóa `vaccinations`.

**Thu tiền** (Order#8, một transaction):
1. Khóa các Order, kiểm `PENDING`, cùng khách, cùng chi nhánh.
2. Gọi `StockApi.issueForSale`, bỏ dòng VACCINE.
3. Nếu danh sách thiếu khác rỗng: không thu, trả danh sách cho lễ tân. Lễ tân xóa/giảm dòng bán lẻ; với dòng thuốc kê đơn thì sales gọi `PrescriptionApi.markExternalPurchaseAtPayment` rồi xóa dòng và ghi audit. Sau đó thu lại.
4. Nếu rỗng: INSERT `payments`, Order → `PAID`, ghi audit.

## 6. Hạn giao implementation

Bên sở hữu phải có implementation thật trước ngày bên gọi bắt đầu dùng. Trước ngày đó bên gọi dùng mock / placeholder (§1). Ngày theo `timeline_petcare_v15.xlsx`.

| Interface | Owner · xong | Bên gọi · bắt đầu | Ghi chú |
|---|---|---|---|
| `SystemConfigApi` | BE-1 · 06/10 — **đã giao 04/10** | mọi module | Chữ ký đổi từ `String key` sang `ConfigKey` (ADR-0004). Audit có sẵn ở `platform/audit`; xác thực, `BranchScope` ở `platform/security` (ADR-0003) |
| `NotificationApi` | BE-1 · 07/10 — **đã giao 06/10** | mọi module | `care/service/NotificationService` ghi `notification_outbox`; worker ST20 gửi email từ 07/10 (ADR-0012) và giao thông báo IN_APP vào `notifications` (ADR-0014, 07/10). Mẫu đã seed: `OTP_REGISTER` (V3, câu chào sửa ở V4); `OTP_PASSWORD_RESET`, `LOGIN_LOCKED_WARNING`, `PASSWORD_CHANGED` (V8, task 07 — ADR-0019); `OTP_PROFILE_LINK` (V9, task 08 phần 0 — ADR-0025) |
| `CustomerApi` (create/flag/delete) | BE-2 · **07/10** | identity 07/10 | ⚠ Timeline xếp KH 12–13/10. **06/10: BE-1 đã commit `customer/service/CustomerApiPlaceholder`** (ném lỗi) để đăng ký chạy được; `POST /api/auth/register` trả 500 tới khi BE-2 thay bằng bản thật (nợ D001). Câu hỏi cho BE-2: `createOnlineProfile` không nhận `email` trong khi BR-KH-01 ghi hồ sơ online "email lấy theo tài khoản" — chọn thêm tham số hay đọc qua tài khoản, ghi lại ở đây |
| `CatalogQueryApi` | BE-2 · 07/10 | appointment 15/10, visit 20/10 | **09/10: thêm `listActiveServices()`** (mọi dịch vụ đang kinh doanh, theo tên) cho branch UC33. |
| `BranchQueryApi` | BE-2 · 09/10 | identity 10/10 (`GET /api/public/vets`, ADR-0026) và 12/10, appointment 15/10 | **09/10: đã cài** ở `branch/service/BranchQueryService` (đủ 9 method; 3 method thuộc UC33 / UC42 đọc qua entity `BranchServiceSetting`, `BranchQuotaDefault`, `SlotQuota`). `quotaFor` ném `IllegalArgumentException` với nhóm `BOARDING`. |
| `StaffDirectoryApi` (cả 7 method) | BE-1 · **08/10** — **đã giao 09/10** | branch kích hoạt 08/10; visit, content, mọi module (người nhận thông báo) | `identity/service/StaffDirectoryService` (ADR-0024); `StaffDirectoryApiPlaceholder` (BE-2 thêm 09/10) đã xóa cùng test, `POST /api/branches/{id}/activate` hết 500. `countBranchManagers` đếm BRANCH_MANAGER `status = ACTIVE` kể cả đang bị khóa (BR-QT-04); "active" = `ACTIVE` và không khóa; `listPublicVets` **không** lọc chi nhánh `ACTIVE` — content lọc và thêm `branchName`. **Cho BE-2 (sổ nợ `docs/dept` không lên git):** D014 — đếm không khóa; T14 cần một method trên `BranchApi` khóa dòng `branches` trong transaction bên gọi để điều chuyển / đổi chức vụ quản lý không chạy chéo với kích hoạt. D013 — 5 cột FK trỏ tới `customers` chưa có index (`visits`, `boarding_bookings`, `payments`, `care_tasks`, `addresses`; V9 chỉ thêm cho `otp_tokens`), mỗi lần xóa hồ sơ quét toàn bảng; cần migration của BE-2 trước khi UC07 chạy thật. Câu hỏi cho BE-2 (UC07, task 08 phần 1): `CustomerApi` cần method khóa hồ sơ tại quầy + liên kết (thứ tự khóa `accounts → customers`, kiểm lại `COUNTER` / chưa liên kết / email dưới khóa — ADR-0025 mục 8) |
| `PetQueryApi`, `PetApi`, `CustomerQueryApi` | BE-2 · 13/10 | appointment 15/10; identity 08/10 | **08/10: BE-1 đã thêm `customer/service/CustomerQueryApiPlaceholder`** (ném lỗi, chưa commit). Đăng nhập (`POST /api/auth/login`, có từ 08/10 — `identity/service/LoginAttemptService`) gọi `findCustomerIdByAccountId` → `findContact(...).linkDecisionPending()` với tài khoản `CUSTOMER` → khách đăng nhập trả 500 tới khi BE-2 thay bằng bản thật (nợ D010); nhân viên không ảnh hưởng **09/10: BE-2 thêm `customer/service/PetQueryApiPlaceholder`** (ném lỗi) cho danh sách lịch bị ảnh hưởng của branch (tên thú). **10/10: BE-1 thêm `CustomerQueryApi.checkOnlineProfileLinkable` và đổi chữ ký `CustomerApi.linkAccountToCounterProfile`** (docs/adr/0027, cần BE-2 xác nhận); `/api/me/link-*` (UC07) gọi cả hai interface → 500 tới khi BE-2 cài (D001, D010). Bản tham chiếu nghĩa vụ bằng SQL: `LinkProfileIT.JdbcCustomerModule`. |
| `StockQueryApi`, `StockApi` | BE-2 · 15/10 | sales 20/10, visit 23/10 | |
| `VisitOrderApi` | BE-2 · 16/10 | visit 20/10 | |
| `AppointmentApi`, `AppointmentQueryApi` | BE-1 · 16/10 | visit 20/10; branch (hủy hàng loạt) nối thật 19/10 | **09/10: BE-2 thêm `appointment/service/AppointmentQueryApiPlaceholder`** (ném lỗi); `BookedSlot` có thêm `code` (mã lịch hẹn, ngay sau `appointmentId`) để branch liệt kê lịch bị ảnh hưởng khi đổi giờ / thêm ngày nghỉ. |
| `BookingRestrictionApi` | BE-1 · 19/10 | boarding 22/10 | |
| `VisitQueryApi` | BE-1 · 21/10 | identity, customer, sales dùng placeholder tới 21/10 | Guard vô hiệu hóa / chuyển chủ chỉ kiểm thật được từ 22/10 |
| `CashierShiftQueryApi` | BE-2 · 21/10 | identity (placeholder tới 21/10) | |
| `BoardingQueryApi` | BE-2 · 22/10 | branch, customer, appointment (placeholder tới 22/10) | **09/10: BE-2 thêm `boarding/service/BoardingQueryApiPlaceholder`** (ném lỗi); `BookedStay` có thêm `code` (mã đặt chỗ, ngay sau `bookingId`). |
| `VaccinationQueryApi` | BE-1 · 23/10 | boarding nhận thú 26/10 | |
| `PrescriptionApi` | BE-1 · 26/10 | sales (luồng thiếu thuốc lúc thu nối thật 28/10) | |
| `FollowUpApi`, `CareTaskApi` | BE-1 · 27/10 | boarding ST15 27/10 (cùng ngày, nối thật 28/10) | |
| `OrderQueryApi`, `FeedbackQueryApi`, `BoardingOrderApi` | BE-2 · theo module | | |
| `SalesReportApi`, `StockReportApi`, `FeedbackReportApi` | BE-2 · 28/10 | report 28/10 | Cùng ngày với task Báo cáo của BE-2 |
| `AppointmentReportApi`, `VaccinationReportApi` | BE-1 · **27/10** | report 28/10 | ⚠ Timeline chưa có dòng này cho BE-1; mỗi interface chỉ 1 query, gộp vào ngày 27/10 |

**Module customer — khung entity do BE-1 tạo (10/10, docs/adr/0028).** Để làm UC06 phía khách (customer-v1 #1–7: `/me/customer-profile`, `/me/addresses…`), BE-1 đã tạo trong `module/customer`: entity `Customer` (đủ mọi cột của `customers`), `CustomerChannel`, `Address` (cột `is_default` ↔ field `defaultAddress`), `CustomerRepository`, `AddressRepository`, controller `MyCustomerController`, service `MyCustomerProfileService`, `AddressBookService`, migration V10 (`ix_addresses_customer_id`). Việc cho BE-2 (T17): **mở rộng các entity này, không tạo entity thứ hai cho cùng bảng**; mọi luồng ghi `customers` / `addresses` (UC22) khóa theo thứ tự `customers → addresses`, đi sau `accounts` nếu có; `CustomerProfile.email` ở `/me` là email tài khoản; `deleteOnlineProfileOfUnverifiedAccount` (ST02) và `linkAccountToCounterProfile` (UC07) phải xóa `addresses` của hồ sơ online trước khi xóa hồ sơ. `CustomerApi`, `CustomerQueryApi` vẫn là placeholder (D001, D010).

## 7. Giả định đã dùng — cần xác nhận

| # | Giả định | Vì sao |
|---|---|---|
| G1 | SystemConfig, NotificationTemplate thuộc `identity`; Notification, NotificationOutbox thuộc `care`. AuditLog thuộc nhóm 1 của 04 nhưng đã được cài ở `platform/audit` (ADR-0001) | Theo nhóm của 04 §1 và §11; ADR-0001 |
| G2 | ST04 (quét nhắc) đặt ở `visit`; ST18 là `CareTaskApi.create`, được ST04 (visit) và ST15 (boarding) gọi | Dữ liệu nhắc nằm ở `vaccinations`, `medical_records`; Care Task chỉ là đầu ra |
| G3 | Nhắc tái chủng của thú đã mất: ST04 bỏ qua thú có `pets.deceased_on`, không có cột hủy riêng | `vaccinations` chỉ có `due_reminded_at`, `superseded_at` (erd), trong khi BR-KH-05 yêu cầu hủy nhắc |
| G4 | Mỗi mũi tiêm là 1 dòng VACCINE số lượng 1 | BR-KB-04 "mỗi mũi tiêm sinh 1 dòng Order"; tài liệu không nói số lượng |
| G5 | "Visit mới có dịch vụ loại Khám" (Care Task#6) xét tại Visit#1 theo dịch vụ tự sinh | 03 ghi người kích hoạt là `Visit#1` |
| G6 | **Cần BE-2 xác nhận (10/10):** hệ thống chưa có quy tắc định dạng SĐT / URL chung. identity dùng kiểu `mobile` (`^0[0-9]{9}$`, theo erd `accounts.phone`) cho SĐT nhân viên và chỉ nhận `https://` cho `avatarUrl` nhân viên (ADR-0026); kiểu `phone` dùng chung vẫn là `^0[0-9]{9,10}$`. Hiện `CreateBranchRequest` / `UpdateBranchRequest` không kiểm định dạng SĐT; `avatarUrl` của khách (customer-v1) và `imageUrl` của catalog chưa kiểm `https`. Đề nghị dùng chung hai kiểu `mobile` / `https_url` của `docs/api/generator/lib.py` khi BE-2 cài các endpoint đó Phía `/me` của customer đã theo (10/10, docs/adr/0028): request `phone` / `receiverPhone` kiểu `mobile`, `avatarUrl` chỉ `https://`; response `CustomerProfile.phone` / `avatarUrl` giữ kiểu chung để không sai với hồ sơ tại quầy 10–11 số | Ảnh hiện ở trang công khai; SĐT khách dùng để đối chiếu (BR-TK-19, BR-KH-10) nên cần cùng một dạng chuẩn |

## 8. Quyết định bổ sung (Q1–Q4)

Bốn điểm tài liệu 01–05 chưa quy định, đã chốt ngày 03/10/2026. Q1 và Q2 là **quyết định nghiệp vụ**: cần chép ngược vào 02 / 03 / 05 ở lần sửa tài liệu kế tiếp để bộ tài liệu gốc tự đủ (xem "Cần sửa ở tài liệu gốc").

### Q1. Chuyển chủ: Care Task `OPEN` của thú chuyển sang chủ mới

**Quyết định.** Khi chuyển chủ, care nhận `PetOwnerTransferredEvent` và đổi `care_tasks.customer_id` của mọi task `OPEN` của thú sang chủ mới. Task giữ nguyên loại, chi nhánh phụ trách, ngày đến hạn. Không hủy rồi sinh lại.

**Lý do.**
- Việc gọi điện là để nhắc tiêm / tái khám **cho con thú**. BR-KH-08 nói nhắc đi theo thú, và chủ cũ mất quyền xem thú kể từ lúc chuyển. Gọi cho chủ cũ vừa vô ích vừa làm lộ thông tin thú cho người không còn quyền.
- Hủy rồi sinh lại không làm được với `VACCINE_OVERDUE`, vì ràng buộc "tối đa 1 task / mũi" (BR-TB-04, `UNIQUE (vaccination_id)`) không phân biệt trạng thái: task đã hủy vẫn chặn task mới.
- Task `PICKUP_OVERDUE` không bị ảnh hưởng, vì không chuyển chủ được khi thú đang lưu trú.
- Task đã `DONE` / `CANCELLED` giữ chủ cũ, đúng với lịch sử đã xảy ra.

**Hệ quả nhỏ chấp nhận được.** Chủ mới có thể có email (BR-TB-02 khi đó không cần gọi điện), nhưng task đã sinh vẫn giữ. Gọi thêm một cuộc không gây hại, còn hủy thì mất lần liên hệ.

### Q2. Khóa tài khoản khách: báo lễ tân của chi nhánh đang có việc dở với khách

**Quyết định.** "Chi nhánh liên quan" (BR-QT-11) là các chi nhánh nơi khách đang có ít nhất một trong:
- lịch hẹn `BOOKED`;
- đặt chỗ `BOOKED` / `CHECKED_IN` / `OVERDUE`;
- Order `OPEN` / `PENDING`.

Mỗi chi nhánh như vậy, mọi lễ tân `ACTIVE` nhận một thông báo trong app (`ACCOUNT_LOCKED_FOLLOW_UP_APP`) kèm danh sách việc dở ở chi nhánh đó. Khách không có việc dở ở đâu thì không gửi cho ai.

**Lý do.** BR-QT-11 giữ nguyên lịch hẹn, đơn và lưu trú của khách bị khóa "để xử lý tại quầy". Chỉ chi nhánh đang giữ những việc đó mới có gì để làm. Gửi cho mọi chi nhánh, hoặc cho "chi nhánh khách hay đến", tạo nhiễu mà không ai hành động. Visit đang mở luôn có Order `OPEN`/`PENDING` nên đã nằm trong điều kiện thứ ba.

**Interface thêm:** `AppointmentQueryApi.findBookedByCustomer`, `BoardingQueryApi.findActiveStaysByCustomer`, `OrderQueryApi.findUnpaidOrdersByCustomer` — trả cả id việc dở và chi nhánh, vì màn hình sau khi khóa phải liệt kê từng việc.

### Q3. Báo cáo: mỗi module công bố số liệu tổng hợp, module `report` chỉ ghép

**Quyết định.** Không đọc thẳng bảng của module khác, không tạo view chung. Mỗi báo cáo trong BR-BC-03 do module sở hữu dữ liệu tính, qua một `*ReportApi` chỉ đọc. Module `report` (BE-2) chỉ có controller UC89: kiểm phạm vi và kỳ (BR-BC-01: SUPER_MANAGER toàn chuỗi hoặc lọc chi nhánh, BRANCH_MANAGER chỉ chi nhánh mình, kỳ ≤ 12 tháng [CFG]), rồi gọi các API.

| Báo cáo (BR-BC-03) | Interface | Owner |
|---|---|---|
| (1) Doanh thu + thất thu (BR-BC-02) | `SalesReportApi.revenue`, `lostRevenue` | sales · BE-2 |
| (2) Lượt dịch vụ hoàn tất theo nhóm, theo dịch vụ | `SalesReportApi.completedServiceCounts` — đếm dòng SERVICE của Order VISIT có `pending_at` trong kỳ | sales · BE-2 |
| (3) Tỷ lệ NO_SHOW, hủy muộn | `AppointmentReportApi.noShowAndLateCancel` | appointment · BE-1 |
| (4) Tỷ lệ quay lại tái chủng (BR-BC-04) | `VaccinationReportApi.revaccinationReturn` | visit · BE-1 |
| (5) Tồn dưới ngưỡng, lô sắp hết hạn | `StockReportApi.lowStock`, `expiringLots` (dùng chung logic với ST08) | inventory · BE-2 |
| (6) Feedback theo chi nhánh | `FeedbackReportApi.feedbackStats` | content · BE-2 |

**Lý do.**
- Đúng quy tắc ranh giới §1, và mỗi công thức báo cáo nằm cạnh người hiểu dữ liệu nhất. Ví dụ "mẫu số trừ thú mất trước ngày tái chủng" (BR-BC-04) là chuyện của visit.
- Đổi schema của một module không làm gãy báo cáo của module khác.
- Danh mục báo cáo cố định chỉ 6 mục và số liệu tính tại thời điểm xem (BR-BC-01), nên không cần kho dữ liệu riêng.

Báo cáo (2) đếm theo dòng dịch vụ trong Order, vì dịch vụ thực hiện nằm ở `order_lines` (VET có thể đổi dòng tự sinh, BR-TN-01), còn `pending_at` của Order VISIT chính là thời điểm hoàn tất lượt (Visit#5). Order VISIT bị BRANCH_MANAGER hủy vì không thanh toán vẫn được đếm, vì dịch vụ đã làm. Chỉ Order hủy do hủy lượt thì không, vì không có `pending_at`.

### Q4. Mã mẫu thông báo: chốt danh sách ngay, liệt kê theo rule

**Quyết định.** Danh sách mẫu chốt từ bây giờ, nằm ở `identity.api.NotificationTemplateCode` (BR-QT-14: mẫu do hệ thống định sẵn, không thêm/xóa qua giao diện). Nội dung mẫu được **seed dần** (chốt 06/10/2026): mỗi mẫu được seed bằng migration trong cùng PR với lời gọi `NotificationApi.enqueue` đầu tiên dùng nó (`notification_outbox.template_code` có FK nên thiếu mẫu thì ghi outbox lỗi), báo số version Flyway cho bên kia trước, và đặt `body = default_body`, `subject = default_subject` để UC10 khôi phục được mẫu mặc định. Cách đặt mã:
- Mỗi mã một kênh, vì `notification_templates.channel` là một giá trị.
- Sự kiện gửi khách cả email lẫn trong app thì có hai mã, mã trong app thêm hậu tố `_APP`.

**Kênh theo người nhận.**
- **Khách:** theo BR-TB-02 cho mọi loại thông báo. Có tài khoản: email + trong app. Hồ sơ tại quầy có email: email. Không email: không tự động gửi. Riêng nhắc tái chủng / tái khám có Care Task gọi điện; các thông báo khác (hủy do phòng khám…) do lễ tân gọi theo danh sách hiện trên màn hình lúc hủy hàng loạt.
- **Nhân viên:** thông báo vận hành gửi trong app.
- **Email dành cho tài khoản:** OTP và mật khẩu chỉ gửi qua email (BR-TK-04, BR-QT-02).

| Mã | Kênh | Người nhận | Nguồn |
|---|---|---|---|
| `OTP_REGISTER`, `OTP_PASSWORD_RESET`, `OTP_EMAIL_CHANGE`, `OTP_PROFILE_LINK` | Email | Người dùng | BR-TK-04…07, 16, 19 |
| `LOGIN_LOCKED_WARNING` | Email | Chủ tài khoản | BR-TK-09 |
| `PASSWORD_CHANGED` | Email | Chủ tài khoản | BR-TK-13 |
| `EMAIL_CHANGED_NOTICE` | Email | Email cũ và email mới | BR-TK-16 |
| `STAFF_TEMP_PASSWORD` | Email | Nhân viên (tạo mới, gửi lại, kích hoạt lại) | BR-QT-02, 10 |
| `ACCOUNT_LOCKED_FOLLOW_UP_APP` | Trong app | BRANCH_MANAGER / SUPER_MANAGER / lễ tân (Q2) | BR-QT-11 |
| `LAST_BRANCH_MANAGER_LOCKED_APP` | Trong app | Mọi SUPER_MANAGER | BR-QT-04 |
| `APPOINTMENT_REMINDER` (+`_APP`) | Email / trong app | Khách | BR-LH-12, ST03 |
| `APPOINTMENT_CANCELLED_BY_CLINIC` (+`_APP`) | Email / trong app | Khách | BR-LH-10, BR-KH-05, 08 |
| `BOARDING_CANCELLED_BY_CLINIC` (+`_APP`) | Email / trong app | Khách | BR-LT-08, BR-LH-10 |
| `BOARDING_OVERDUE` (+`_APP`) | Email / trong app | Khách, mỗi ngày | BR-LT-10 |
| `CARE_LOG_ABNORMAL` (+`_APP`) | Email / trong app | Khách; lễ tân nhận bản `_APP` | BR-LT-12 |
| `BOARDING_NO_KENNEL_APP` | Trong app | BRANCH_MANAGER | BR-LT-08 |
| `BOARDING_OVERDUE_MANAGER_APP` | Trong app | BRANCH_MANAGER (quá hạn ≥ 7 ngày) | BR-LT-10 |
| `BOARDING_CAPACITY_WARNING_APP` | Trong app | Lễ tân | BR-LT-10 |
| `VACCINE_REMINDER` (+`_APP`) | Email / trong app | Khách | BR-TB-01, 02 |
| `FOLLOW_UP_REMINDER` (+`_APP`) | Email / trong app | Khách | BR-TB-06 |
| `CASHIER_SHIFT_AUTO_CLOSED_APP` | Trong app | BRANCH_MANAGER | BR-TG-05, ST13 |
| `PENDING_ORDERS_DIGEST_APP` | Trong app | BRANCH_MANAGER | BR-BH-05, ST13 |
| `STOCK_ALERT_DIGEST_APP` | Trong app | BRANCH_MANAGER | BR-KO-07, ST08 |

Biến của từng mẫu (`allowed_vars`, `required_vars`) do module gửi đề xuất trong PR đầu tiên dùng mẫu đó. Mẫu `_APP` (kênh `IN_APP`) bắt buộc có `subject` — thành `notifications.title`, quá 200 ký tự bị cắt; `notifications.type` là mã mẫu bỏ hậu tố `_APP` (ADR-0014). Thiếu mẫu thì thêm hằng số kèm migration seed, không tạo mẫu qua giao diện.

### Cần sửa ở tài liệu gốc

| Quyết định | File | Sửa gì |
|---|---|---|
| Q1 | 02 BR-KH-08, BR-TB-05; 03 Phụ lục (dòng Chuyển chủ); 05 `care_tasks.customer_id` | Ghi "Care Task `OPEN` của thú chuyển sang chủ mới"; đổi ghi chú cột thành "chủ hiện tại; task đã đóng giữ chủ lúc đóng" |
| Q2 | 02 BR-QT-11 | Định nghĩa "chi nhánh liên quan" như Q2 |
| Q4 | 05 `notification_templates` | Trỏ tới danh sách mã ở file này |
