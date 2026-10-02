# Pet Care Ecosystem — Domain Model

> Danh sách domain model của hệ thống, đi kèm `business-rules.md`, `use-case.md`, `state-machine.md`, `erd.md` (v16). File này chỉ mô tả **model nào tồn tại, nghĩa là gì, quan hệ với model nào, rule nào chi phối**. Thuộc tính chi tiết, kiểu dữ liệu, khóa và index thuộc `erd.md`.
>
> Khi có mâu thuẫn: `business-rules.md` là nguồn gốc → `state-machine.md` → file này → `erd.md`.

## Quy ước

- **Loại model:**
  - `ROOT` — aggregate root. Được tạo, sửa, chuyển trạng thái qua service riêng; các model khác chỉ tham chiếu bằng ID.
  - `PART` — thuộc về một ROOT (ghi trong cột *Thuộc*). Không tồn tại độc lập, được tạo/sửa thông qua ROOT.
  - `REF` — dữ liệu danh mục hoặc cấu hình, ít thay đổi, được nhiều aggregate tham chiếu.
  - `LOG` — chỉ thêm mới, không sửa, không xóa.
- **Quan hệ:** `A 1–1 B`, `A 1–n B`, `A n–1 B`. `?` là tùy chọn (nullable). Chỉ ghi quan hệ quan trọng cho nghiệp vụ.
- **Trạng thái:** model có cột *SM* trỏ tới mục trong `state-machine.md`; mọi chuyển trạng thái phải theo whitelist ở đó.
- **Tầng:** 1 làm đầy đủ · 2 làm bản mỏng (theo bảng module của business rules).
- Tên model dùng tiếng Anh (dùng trực tiếp làm tên class/bảng); mô tả tiếng Việt.

## Nguyên tắc xuyên suốt

1. **Customer là thực thể gốc, Account là phần gắn thêm.** Mọi dữ liệu nghiệp vụ (Pet, Appointment, Order…) trỏ tới Customer, không trỏ tới Account. Liên kết tài khoản chỉ là gán `Customer.account_id` (BR-TK-19, BR-KH-01).
2. **Không xóa cứng dữ liệu nghiệp vụ.** Dùng trạng thái cuối hoặc cờ: Pet đã mất, Supplier ngừng hợp tác, Order/StockReceipt `CANCELLED`, Article `HIDDEN`. Ngoại lệ: Pet chưa phát sinh giao dịch (BR-KH-06), Account `PENDING` quá hạn cùng hồ sơ online tạo kèm (BR-TK-08), hồ sơ online chưa phát sinh dữ liệu khi liên kết vào hồ sơ tại quầy (BR-TK-19), Article chưa từng xuất bản.
3. **Snapshot giá.** `OrderLine` chốt đơn giá lúc thêm dòng (BR-BH-03); `BoardingBooking` chốt giá đêm lúc đặt (BR-LT-02). Không tính tiền từ giá hiện tại.
4. **Mọi sản phẩm đều tồn theo lô.** Sản phẩm không quản lý hạn dùng có đúng 1 `StockLot` không số lô, không hạn. Nhờ vậy kiểm tra tồn khả dụng, trừ kho FEFO, điều chỉnh, hủy phiếu nhập dùng chung một cách cài đặt (BR-KO-01, 05).
5. **Mọi thay đổi tồn kho sinh `StockMovement`.** `StockLot.quantity` là số dư; `StockMovement` là lịch sử để truy vết.
6. **Hệ quả liên aggregate chạy trong một transaction**, do service của aggregate phát sự kiện điều phối (xem Phụ lục của `state-machine.md`).
7. **Vaccine tính theo loại, không theo nhãn hàng.** Phác đồ, khớp mũi tiêm lại, điều kiện lưu trú, nhắc và báo cáo đều dùng `VaccineType`; `Product` chỉ là nhãn hàng dùng để trừ kho và tính tiền (BR-SP-07).
8. **Phạm vi theo chi nhánh.** Model có `branch_id` bị giới hạn theo chi nhánh của nhân viên (A05–A08). Customer, Pet, danh mục sản phẩm/dịch vụ, Supplier dùng chung toàn chuỗi.
9. **Chỉ tin dữ liệu tiêm chủng do hệ thống ghi nhận.** Mũi tiêm ở nơi khác không được ghi nhận, vì không kiểm chứng được loại, lô, bảo quản và ngày tiêm. Gợi ý mũi, ngày tái chủng, điều kiện lưu trú, nhắc và báo cáo chỉ dùng `Vaccination` của hệ thống; tiền sử do chủ khai ghi dạng văn bản trong bệnh án (BR-KB-05).
10. *(v16)* **Định danh chỉ dựa trên dữ liệu đã xác thực.** Email (xác thực bằng OTP) là định danh duy nhất của tài khoản. SĐT là thông tin liên lạc và khóa tra cứu, không duy nhất. CCCD chỉ được lễ tân kiểm tra trực tiếp, hệ thống không lưu (BR-TK-01, 16, BR-KH-10).

---

## 1. Định danh & quản trị (TK, QT)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Account | ROOT | | Tài khoản đăng nhập của khách và nhân viên. Role là enum cố định: `CUSTOMER`, `ADMIN`, `SUPER_MANAGER`, `BRANCH_MANAGER`, `RECEPTIONIST`, `VET`, `CARETAKER` | 1–1? StaffProfile; 1–1? Customer (qua `Customer.account_id`) | #1 | Khóa là cờ `is_locked` độc lập với `status`. Email là định danh duy nhất; SĐT chỉ bắt buộc với nhân viên, không duy nhất. BR-TK-01, 08, 09, 11, 17, 19; BR-QT-01, 07, 11, 12 | 1 |
| StaffProfile | PART | Account | Thông tin nhân viên: chi nhánh làm việc, hồ sơ giới thiệu công khai của VET | n–1? Branch (null với ADMIN, SUPER_MANAGER) | | BR-QT-03, BR-TK-20 | 1 |
| OtpToken | LOG | | Mã OTP gửi email theo mục đích (đăng ký, quên mật khẩu, đổi email, liên kết hồ sơ) | n–1? Account; n–1? Customer (hồ sơ tại quầy cần liên kết) | | Sinh mã mới thì vô hiệu mã cũ cùng mục đích. BR-TK-04…07 | 1 |
| Session | PART | Account | Phiên đăng nhập, dùng để hủy mọi phiên khi khóa, vô hiệu hóa, đổi/đặt lại mật khẩu | n–1 Account | | BR-TK-11, 13, 14 | 1 |
| AuditLog | LOG | | Nhật ký thao tác nhạy cảm: người, thời điểm, hành động, đối tượng, giá trị trước/sau | n–1 Account (người thực hiện) | | Chỉ ADMIN xem; không sửa/xóa. BR-QT-15, 16 | 1 |
| SystemConfig | REF | | Tham số [CFG] kèm khoảng hợp lệ min–max | | | Giá trị mới chỉ áp dụng cho giao dịch tạo sau. BR-QT-13 | 1 |
| NotificationTemplate | REF | | Mẫu thông báo định sẵn, chỉ sửa nội dung, có bản mặc định để khôi phục | | | Phải đủ biến bắt buộc. BR-QT-14 | 1 |

## 2. Chi nhánh (CN)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Branch | ROOT | | Chi nhánh; có cờ nhận cấp cứu ngoài giờ | 1–n StaffProfile, Kennel, InventoryItem | #2 | Kích hoạt khi có ≥ 1 BRANCH_MANAGER và đã cấu hình giờ. BR-CN-01, 05; BR-QT-04 | 1 |
| OpeningHours | PART | Branch | Giờ mở cửa theo thứ trong tuần, tối đa 2 khoảng/ngày, có ngày hiệu lực | | | Bản ghi áp dụng = bản có ngày hiệu lực lớn nhất ≤ ngày cần tra. BR-CN-02, 04 | 1 |
| Holiday | PART | Branch | Ngày nghỉ cụ thể, nghỉ cả ngày | | | BR-CN-03, BR-LH-10 | 1 |
| BranchService | PART | Branch | Bật/tắt một Service (kể cả loại chuồng) tại chi nhánh | n–1 Service | | BR-LH-01, BR-SP-04 | 1 |
| BranchQuotaDefault | PART | Branch | Quota mặc định của một nhóm dịch vụ tại chi nhánh, áp dụng cho mọi khung giờ | | | Không có bản ghi = 1. BR-LH-03 | 1 |
| SlotQuota | PART | Branch | Quota riêng của một khung giờ cụ thể, theo nhóm dịch vụ; ghi đè quota mặc định | | | Thứ tự áp dụng: SlotQuota → BranchQuotaDefault → 1. BR-LH-03 | 1 |

## 3. Khách hàng & thú cưng (KH)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Customer | ROOT | | Hồ sơ chủ thú cưng, dùng chung toàn chuỗi. Nguồn online (tạo khi đăng ký, có cờ chờ quyết định liên kết) hoặc tại quầy | 1–1? Account; 1–n Pet, Address, Appointment, Order | | *(v16)* SĐT không duy nhất; bắt buộc với hồ sơ tại quầy, không bắt buộc với hồ sơ online. Hồ sơ online chưa phát sinh dữ liệu bị xóa khi liên kết vào hồ sơ tại quầy. BR-TK-19; BR-KH-01, 10 | 1 |
| Address | PART | Customer | Sổ địa chỉ | | | Tối đa 5, đúng 1 mặc định. BR-TK-18 | 1 |
| Pet | ROOT | | Thú cưng; có ngày mất (đã mất thì chỉ đọc) | n–1 Customer (chủ hiện tại); 1–n WeightRecord, Vaccination, Visit, BoardingBooking | | Khóa loài khi đã có bệnh án/mũi tiêm. Chuyển chủ chỉ đổi chủ hiện tại; Order cũ giữ `customer_id` của chủ cũ. BR-KH-02, 03, 05, 06, 08 | 1 |
| WeightRecord | LOG | | Một lần đo cân nặng (VET khi khám, người nhận thú lưu trú, hoặc chủ tự khai) | n–1 Pet; n–1? Visit; n–1? BoardingBooking | | Cân nặng hiện tại = bản ghi mới nhất. BR-KH-04 | 1 |

## 4. Danh mục sản phẩm & dịch vụ (SP)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| ProductCategory | REF | | Danh mục sản phẩm | 1–n Product | | | 1 |
| Product | REF | | Hàng bán lẻ, thuốc, vaccine. Có cờ thuốc kê đơn, cờ quản lý hạn dùng | n–1 ProductCategory; n–1? VaccineType (bắt buộc với vaccine) | | Thuốc kê đơn không bán lẻ, không hiển thị công khai. Thuốc kê đơn và vaccine bắt buộc quản lý hạn dùng. BR-SP-01, 05, 07 | 1 |
| Service | REF | | Dịch vụ theo nhóm: Khám/Tiêm, Thẩm mỹ, Lưu trú (nhóm khác thuộc tầng 3). Nhóm Khám/Tiêm có loại `KHÁM` hoặc `TIÊM` | 1–1? KennelType | | Quota và gán nhân viên tính theo nhóm; điều kiện hoàn tất lượt tính theo loại. BR-SP-06, BR-LH-01 | 1 |
| KennelType | PART | Service | Phần mở rộng của Service nhóm Lưu trú: loài phù hợp, cân nặng tối đa. Giá theo đêm là giá của Service | 1–n Kennel, BoardingBooking | | BR-SP-04 | 2 |
| VaccineType | REF | | Loại vaccine theo bệnh phòng (ví dụ Dại, 5 bệnh cho chó), có loài áp dụng | 1–n VaccinationProtocol, Product, Vaccination | | Đã được dùng thì chỉ ngừng sử dụng, không xóa. BR-SP-07 | 1 |
| VaccinationProtocol | REF | | Một dòng phác đồ: loài, loại vaccine, mũi thứ, khoảng cách tới mũi kế, tuổi tối thiểu, cờ bắt buộc khi lưu trú | n–1 VaccineType | | Sửa chỉ áp dụng cho mũi tiêm sau thời điểm sửa. BR-SP-02, 03 | 1 |

## 5. Lịch hẹn (LH)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Appointment | ROOT | | Lịch hẹn: 1 thú, 1 dịch vụ, 1 chi nhánh, 1 khung 30 phút. Không gắn nhân viên | n–1 Customer, Pet, Branch, Service; 1–1? Visit | #3 | Có số lần đổi, cờ hủy muộn. BR-LH-01…11 | 1 |
| BookingRestriction | ROOT | | Một lần khách bị hạn chế đặt online, kèm thông tin gỡ sớm | n–1 Customer | | Số lần vi phạm tính động từ Appointment và BoardingBooking. BR-LH-09 | 1 |

## 6. Tiếp nhận & khám (TN, KB)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Visit | ROOT | | Lượt tiếp nhận 1 thú tại 1 chi nhánh; nằm trong hàng đợi, có mức ưu tiên và nhân viên phụ trách | n–1 Pet, Customer, Branch; 1–1? Appointment; n–1? Account (phụ trách); 1–1 Order | #4 | Tạo Visit đồng thời mở Order kèm dòng dịch vụ tự sinh. BR-TN-01…09, BR-KB-02 | 1 |
| VisitAssignment | LOG | | Lịch sử gán / gán lại nhân viên cho lượt, kèm lý do | n–1 Visit | | Gán lại lượt đã gọi chỉ khi người cũ không còn `ACTIVE`. BR-TN-08 | 1 |
| MedicalRecord | PART | Visit | Bệnh án của lượt Khám/Tiêm: khám, chẩn đoán, ghi chú nội bộ, ngày tái khám (kèm cờ đã nhắc) | 1–n PrescriptionItem | | Khóa khi Visit `COMPLETED`; ghi chú nội bộ không trả cho khách. Ngày tái khám được ST04 quét để nhắc, không có model nhắc riêng. BR-KB-01, 06, BR-KH-07, BR-TB-06 | 1 |
| MedicalRecordAddendum | LOG | MedicalRecord | Bản bổ sung sau khi bệnh án khóa | | | Ghi audit. BR-KB-01 | 1 |
| PrescriptionItem | PART | MedicalRecord | Một dòng đơn thuốc; hoặc sinh 1 OrderLine, hoặc đánh dấu mua ngoài | n–1 Product; 1–1? OrderLine | | Không tách một dòng thành hai phần. BR-KB-03, BR-BH-04 | 1 |
| Vaccination | ROOT | | Một mũi tiêm thực hiện tại hệ thống. Mang ngày tái chủng và cờ đã nhắc / đã sinh Care Task quá hạn | n–1 Pet, VaccineType, Visit (chi nhánh lấy từ Visit), VaccinationProtocol, Product, StockLot; 1–1 OrderLine | | Trừ kho ngay theo FEFO; xóa được khi Visit còn `IN_PROGRESS` (hoàn kho đúng lô, xóa OrderLine). Tiêm lại = mũi mới hơn cùng VaccineType. Không ghi nhận mũi tiêm ở nơi khác (nguyên tắc 9). Không có model nhắc riêng: ST04 quét theo ngày tái chủng. BR-KB-04, 05; BR-TB-01, 03, 04 | 1 |

## 7. Lưu trú (LT)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Kennel | ROOT | | Chuồng cụ thể tại chi nhánh, chứa 1 thú | n–1 Branch, KennelType | #7 | Sức chứa = số chuồng không `MAINTENANCE`. Chỉ gán cho đặt chỗ cùng KennelType. BR-LT-01, 08 | 2 |
| BoardingBooking | ROOT | | Đặt chỗ lưu trú theo loại chuồng; chuồng cụ thể gán lúc nhận và phải đúng loại đã đặt; giá đêm snapshot | n–1 Customer, Pet, Branch, KennelType; n–1? Kennel; 1–n Order (tối đa 1 chưa kết thúc); 1–n CareLog | #6 | BR-LT-02…10, 12 | 2 |
| BoardingCheckIn | PART | BoardingBooking | Tình trạng lúc nhận: sức khỏe quan sát, đồ gửi kèm, chế độ ăn, SĐT khẩn | 1–1 WeightRecord | | Bắt buộc có cân nặng. BR-LT-08 | 2 |
| CareLog | PART | BoardingBooking | Nhật ký chăm sóc hằng ngày, kèm ảnh và bản bổ sung; có cờ bất thường | n–1 Account (người ghi) | | Sửa trong 1 giờ, sau đó chỉ bổ sung. Bất thường thì thông báo ngay. BR-LT-11, 12 | 2 |

## 8. Bán hàng & thu ngân (BH, TG)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Order | ROOT | | Đơn tại quầy, nguồn `VISIT` / `RETAIL` / `BOARDING` | n–1 Customer, Branch; 1–1? Visit; n–1? BoardingBooking; n–1? Payment; 1–n OrderLine | #5 | Đúng 1 FK nguồn theo `source` (RETAIL không có). Chỉ giao hàng/thú khi `PAID`. BR-BH-01, 05, 06 | 1 |
| OrderLine | PART | Order | Một dòng: dịch vụ, hàng, thuốc kê đơn, vaccine hoặc tiền lưu trú; đơn giá snapshot; ghi người thêm và cờ tự sinh | n–1? Service, Product; 1–1? PrescriptionItem, Vaccination | | Quyền xóa theo người thêm. Dòng vaccine không xóa trực tiếp, chỉ xóa kèm khi xóa mũi tiêm. BR-BH-02, 03; BR-KB-04; BR-TN-01 | 1 |
| Payment | ROOT | | Một phiếu thu, có thể gộp nhiều Order cùng khách, cùng chi nhánh | n–1 CashierShift, Customer; 1–n Order | | Thu đủ một lần, tiền mặt hoặc chuyển khoản. BR-TG-02, 03, 04 | 1 |
| CashierShift | ROOT | | Ca thu ngân của 1 lễ tân: ca thường (trong giờ mở cửa) hoặc ca ngoài giờ (thu tiền cấp cứu, được qua nửa đêm) | n–1 Branch, Account (lễ tân); 1–n Payment | #8 | Tối đa 1 ca `OPEN`/lễ tân. Ca thường tự chốt cuối ngày; ca ngoài giờ tự chốt khi đến giờ mở cửa kế tiếp. BR-CN-05, BR-TG-01, 05 | 1 |

## 9. Kho (KO)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| Supplier | REF | | Nhà cung cấp, dùng chung toàn chuỗi | 1–n StockReceipt | | Đã có phiếu nhập thì chỉ ngừng hợp tác. BR-KO-02 | 2 |
| InventoryItem | ROOT | | Tồn của 1 sản phẩm tại 1 chi nhánh, kèm tồn tối thiểu | n–1 Branch, Product; 1–n StockLot | | Tồn khả dụng = tổng lô chưa hết hạn. BR-KO-01, 07 | 2 |
| StockLot | PART | InventoryItem | Một lô (số lô, hạn dùng, số lượng). Sản phẩm không quản lý hạn dùng có 1 lô mặc định | 1–n StockMovement | | Không âm; không xuất lô hết hạn; xuất theo FEFO. BR-KO-01, 05 | 2 |
| StockReceipt | ROOT | | Phiếu nhập kho kèm các dòng (sản phẩm, số lượng, giá nhập, số lô, hạn dùng) | n–1 Supplier, Branch | #9 | Hủy phiếu đã xác nhận khi lô còn đủ tồn. BR-KO-03, 04 | 2 |
| StockAdjustment | ROOT | | Phiếu điều chỉnh tăng/giảm tồn theo lô, kèm lý do | n–1 Branch | | Hiệu lực ngay, ghi audit. BR-KO-06 | 2 |
| StockMovement | LOG | | Một biến động tồn của một lô: nhập, hủy nhập, bán (Order `PAID`), tiêm, điều chỉnh; trỏ về chứng từ nguồn | n–1 StockLot | | Mọi thay đổi `StockLot.quantity` phải có movement tương ứng. BR-BH-04, BR-KB-04 | 2 |

## 10. Nội dung & feedback (BV, CK, DG)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| ArticleCategory | REF | | Chuyên mục bài viết, có thể ẩn | 1–n Article | | Còn bài thì chỉ ẩn. BR-BV-04 | 2 |
| Article | ROOT | | Bài viết do SUPER_MANAGER soạn và đăng | n–1 ArticleCategory | BR-BV-02 | Slug khóa sau lần xuất bản đầu. BR-BV-01…03 | 2 |
| PageContent | REF | | Nội dung trang tĩnh: banner, giới thiệu, chính sách, FAQ | | | UC21 | 2 |
| Feedback | ROOT | | Ý kiến không công khai của khách về chi nhánh hoặc website | n–1 Customer; n–1? Branch | BR-DG-04 | Chỉ SUPER_MANAGER và BRANCH_MANAGER (chi nhánh mình) xem. BR-DG-01…04 | 2 |

## 11. Chăm sóc khách & thông báo (TB)

| Model | Loại | Thuộc | Ý nghĩa | Quan hệ chính | SM | Rule then chốt | Tầng |
|---|---|---|---|---|---|---|---|
| CareTask | ROOT | | Việc gọi điện cho khách, loại `TÁI_CHỦNG`, `QUÁ_HẠN_TÁI_CHỦNG`, `TÁI_KHÁM`, `QUÁ_HẠN_ĐÓN`. Chỉ lễ tân chi nhánh phụ trách thực hiện | n–1 Branch, Customer, Pet; n–1? Vaccination, MedicalRecord, BoardingBooking (theo loại) | #10 | Tối đa 1 task quá hạn/mũi. BR-TB-02, 04, 05, 06; BR-LT-10 | 1 |
| Notification | ROOT | | Thông báo trong ứng dụng của một tài khoản | n–1 Account | | UC88 | 1 |
| NotificationOutbox | LOG | | Hàng đợi gửi email/thông báo, có số lần thử lại | | | Gửi thất bại thì thử lại (ST20) | 1 |

---

## Ngoài phạm vi (tầng 3, không có model)

Giữ trên sơ đồ use case, không thiết kế model: ca làm việc & nghỉ phép (UC34–38), phẫu thuật (UC50), khám tại nhà (UC43, UC51), nội trú (UC55, UC56), giỏ hàng & đơn online & thanh toán cổng (UC61–65), trả hàng (UC68, 69), chuyển kho & kiểm kê & truy vết lô (UC77–79), bài viết của VET & bình luận (UC80, 82), khiếu nại (UC85, 86), gộp hồ sơ khách (UC27), tạm ngừng/đóng chi nhánh (UC13).

Báo cáo (UC89) không có model; số liệu tính tại thời điểm xem từ Order, Payment, Visit, Appointment, Vaccination, InventoryItem, Feedback (BR-BC-01…04). Tỷ lệ quay lại tái chủng khớp theo VaccineType. *(v15: bỏ báo cáo công suất chuồng nên không dùng Kennel, BoardingBooking.)*
