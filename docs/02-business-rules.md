# Pet Care Ecosystem — Business Rules Catalog

Tài liệu này đặc tả toàn bộ các quy tắc nghiệp vụ (Business Rules), điều kiện ràng buộc bất biến (Invariants), điều kiện bảo vệ chuyển trạng thái (State Transition Guards) và phạm vi áp dụng (Scopes) của hệ thống Pet Care Ecosystem.

---

## 01. Authentication & OTP

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-01-01 | Mỗi Account chỉ được đăng nhập khi thông tin xác thực (Credentials) hợp lệ và Account đang ở trạng thái ACTIVE. | Platform / Account |
| RULE-01-02 | OTP chỉ được chấp nhận xác thực trong thời gian hiệu lực (TTL) quy định. | Platform / OTP |
| RULE-01-03 | OTP phải được kiểm tra xác thực thành công trước khi hoàn tất đăng ký tài khoản hoặc kích hoạt nghiệp vụ bảo mật. | Platform / OTP |
| RULE-01-04 | Mỗi lần yêu cầu gửi lại OTP (`ResendOTP`) phải vô hiệu hóa OTP trước đó và tạo một mã OTP mới có thời hạn hiệu lực độc lập. | Platform / OTP |
| RULE-01-05 | Hệ thống phải giới hạn số lần gửi lại OTP và số lần thử sai OTP trong một khoảng thời gian để ngăn chặn lạm dụng. | Platform / OTP |

---

## 02. Identity & Access Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-02-01 | Mỗi User chỉ được thực hiện Permission thuộc phạm vi mà User được cấp (Platform, Organization hoặc Store). | Platform / Organization / Store / User |
| RULE-02-02 | Role chỉ có hiệu lực trong scope quản lý mà Role được gán. | Platform / Organization / Store / Role |
| RULE-02-03 | Permission gán cho User không được vượt quá phạm vi quyền của Role hoặc phạm vi quản lý của Actor thực hiện gán quyền. | Platform / Organization / Store |
| RULE-02-04 | Account ở trạng thái LOCKED không được phép đăng nhập hoặc thực hiện bất kỳ nghiệp vụ nào yêu cầu tài khoản hoạt động. | Platform / Account |
| RULE-02-05 | Việc quản lý User, Role, Permission và Khóa/Mở khóa tài khoản phải tuân thủ nghiêm ngặt cấp bậc quản trị: Platform Admin quản lý toàn hệ thống; Organization Admin quản lý trong phạm vi Organization; Store Manager quản lý nhân viên trực thuộc Store. | Platform / Organization / Store |

---

## 03. Organization & Store Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-03-01 | Mỗi Store phải thuộc đúng một Organization cha duy nhất. | Organization / Store |
| RULE-03-02 | Store chỉ được tiếp nhận khách, tạo đơn hàng và cung cấp dịch vụ khi Store đang ở trạng thái ACTIVE. | Store |
| RULE-03-03 | Chính sách Organization chỉ áp dụng trong phạm vi Organization tương ứng; Chính sách vận hành Store chỉ áp dụng trong phạm vi Store tương ứng. | Organization / Store |
| RULE-03-04 | Store ở trạng thái SUSPENDED hoặc DEACTIVATED không thể nhận lịch hẹn mới hoặc tạo đơn hàng mới. | Store |
| RULE-03-05 | Service được cấu hình khả dụng cho Store chỉ có hiệu lực vận hành trong phạm vi Store đó. | Store / Service |
| RULE-03-06 | Store chỉ được chuyển sang trạng thái ARCHIVED khi không còn bất kỳ đơn hàng, lịch hẹn hoặc nghĩa vụ tài chính nào đang mở. | Store |

---

## 04. Customer & Pet Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-04-01 | Mỗi Pet phải thuộc quyền sở hữu (PetOwnership) hoặc quyền quản lý hợp lệ của một Customer chính thức. | Customer / Pet |
| RULE-04-02 | Caregiver chỉ được xem thông tin và thao tác với Pet khi lời mời ủy quyền đang ở trạng thái ACTIVE và trong phạm vi quyền được cấp. | Customer / Pet / Caregiver |
| RULE-04-03 | Khi quyền Caregiver bị thu hồi (`REVOKED`) hoặc hết hạn (`EXPIRED`), Caregiver lập tức mất quyền thực hiện mọi nghiệp vụ đối với Pet đó. | Customer / Pet / Caregiver |
| RULE-04-04 | Chỉ Customer sở hữu hợp lệ của Pet mới có quyền gửi lời mời ủy quyền (`InviteCaregiver`) hoặc thu hồi quyền ủy quyền (`RevokeCaregiver`). | Customer / Pet |
| RULE-04-05 | Khi Caregiver chấp nhận lời mời (`AcceptCaregiverInvitation`), quan hệ ủy quyền chuyển thẳng sang trạng thái ACTIVE và có hiệu lực ngay lập tức. | Customer / Pet / Caregiver |
| RULE-04-06 | Lời mời Caregiver ở trạng thái INVITED nếu không được chấp nhận trong thời hạn quy định sẽ tự động chuyển sang trạng thái EXPIRED. | CaregiverInvitation |

---

## 05. Service & Product Catalog

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-05-01 | Product được quản lý trong phạm vi Catalog mà Product thuộc về. | Platform / Organization / Product |
| RULE-05-02 | Service được cung cấp tại Store chỉ khi Service được cấu hình khả dụng (`ACTIVE`) tại Store đó. | Store / Service |
| RULE-05-03 | Giá Service áp dụng tại Store phải là giá được cấu hình hợp lệ cho Store đó tại thời điểm phát sinh giao dịch. | Store / Service |
| RULE-05-04 | Giá Product áp dụng tại Store phải là giá được cấu hình hợp lệ cho Store đó tại thời điểm phát sinh giao dịch. | Store / Product |
| RULE-05-05 | Product thuộc Product Catalog cấp Platform; Organization chỉ được chọn và quản lý Product trong phạm vi Catalog mà Organization được cấp quyền, không tự tạo Catalog độc lập ngoài Platform. | Platform / Organization / ProductCatalog |

---

## 06. Appointment & Scheduling

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-06-01 | Appointment chỉ được đặt trong khoảng thời gian mà Store đang hoạt động và Service/Staff có trạng thái khả dụng (Availability). | Store / Appointment |
| RULE-06-02 | Appointment chỉ được tạo cho Pet mà Customer hoặc Caregiver có quyền hợp lệ. | Customer / Pet / Appointment |
| RULE-06-03 | Staff chỉ được phân công vào Appointment khi Staff thuộc Store tương ứng và không bị trùng lịch làm việc hoặc nghỉ phép. | Store / Staff / Appointment |
| RULE-06-04 | Một Appointment chỉ có duy nhất một khung thời gian áp dụng tại một thời điểm. | Appointment |
| RULE-06-05 | Appointment ở trạng thái CANCELLED hoặc COMPLETED không được phép thao tác thay đổi thời gian hoặc tiếp nhận dịch vụ. | Appointment |
| RULE-06-06 | Tiếp nhận Check-in và Check-out chỉ áp dụng cho Appointment thuộc Store tương ứng. | Store / Appointment |
| RULE-06-07 | Appointment chỉ được đặt khi Service đang khả dụng tại Store và Staff được phân công có lịch làm việc phù hợp. | Store / Service / Staff / Appointment |
| RULE-06-08 | Hủy lịch hẹn (`CancelAppointment`) chỉ được thực hiện khi lịch hẹn đang ở trạng thái `BOOKED` hoặc `CONFIRMED` và tuân thủ thời hạn hủy tối thiểu trước giờ hẹn theo chính sách Store. | Appointment |
| RULE-06-09 | Hệ thống hoặc Receptionist chỉ đánh dấu `NO_SHOW` khi Appointment ở trạng thái `BOOKED`/`CONFIRMED` và khách không Check-in sau khoảng thời gian ân hạn (Grace Period) quy định. | Appointment |

---

## 07. Walk-in & Queue Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-07-01 | Mỗi lượt Walk-in và số thứ tự Queue chỉ thuộc về một Store cụ thể nơi tiếp nhận khách. | Store / WalkIn |
| RULE-07-02 | Khách chỉ có thể được xếp vào Queue của Store nơi Walk-in được tạo. | Store / Queue / WalkIn |
| RULE-07-03 | Mỗi lượt Walk-in đang chờ chỉ có duy nhất một vị trí `QueuePosition` tại một thời điểm. | Store / Queue |
| RULE-07-04 | Thứ tự phục vụ trong Queue phải tuân thủ nguyên tắc FIFO (vào trước phục vụ trước) trừ trường hợp cấp cứu y tế được ưu tiên. | Store / Queue |

---

## 08. Workforce Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-08-01 | Staff chỉ được phân công làm việc vào Store mà Staff được Organization cho phép hoạt động. | Organization / Store / Staff |
| RULE-08-02 | Lịch làm việc (`WorkSchedule`) của Staff không được phép trùng lặp giữa thời gian làm việc và thời gian nghỉ phép trong cùng một khoảng thời gian. | Store / Staff / WorkSchedule |
| RULE-08-03 | Staff được ghi nhận vắng mặt (`StaffAbsence`) không được tiếp tục nhận phân công công việc trong khoảng thời gian vắng mặt đó. | Store / Staff |
| RULE-08-04 | Phân công nhân sự thay thế (`StaffReplacement`) chỉ được gán cho Staff có năng lực chuyên môn tương đương và có lịch trống trong Store. | Store / Staff |
| RULE-08-05 | Nghỉ phép (`Leave`) chỉ có hiệu lực sau khi được Store Manager phê duyệt và ghi nhận vào lịch làm việc. | Store / Staff / Leave |
| RULE-08-06 | Nhân viên chuyên môn (Veterinarian, Groomer, Receptionist) chỉ được xem WorkSchedule của chính mình; Store Manager có quyền xem và điều phối lịch của toàn bộ nhân viên trong Store. | Store / Staff |

---

## 09. Veterinary / Clinical Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-09-01 | Bệnh án (`MedicalRecord`) chỉ được tạo và cập nhật bởi Bác sĩ thú y (`Veterinarian`) có thẩm quyền chuyên môn đối với Pet đang khám. | Organization / Store / Pet / MedicalRecord |
| RULE-09-02 | Lịch sử y tế (`MedicalHistory`) Cross-store chỉ được truy cập khi tuân thủ chính sách chia sẻ dữ liệu của Organization và có sự đồng thuận của khách hàng. | Organization / Store / Pet / MedicalHistory |
| RULE-09-03 | Chẩn đoán (`Diagnosis`) phải gắn liền với một phiên khám bệnh cụ thể được ghi nhận trong bệnh án. | Clinical / Pet / Diagnosis |
| RULE-09-04 | Phác đồ điều trị (`Treatment`) phải thuộc về bệnh án của Pet tương ứng. | Clinical / Pet / Treatment |
| RULE-09-05 | Đơn thuốc (`Prescription`) phải thuộc về bệnh án của Pet và do Bác sĩ thú y chịu trách nhiệm ký duyệt. | Clinical / Pet / Prescription |
| RULE-09-06 | Kế hoạch tái khám (`FollowUp`) phải gắn liền với Pet và hoạt động khám chữa bệnh liên quan. | Clinical / Pet / FollowUp |
| RULE-09-07 | Customer hoặc Caregiver chỉ được xem bệnh án và lịch sử y tế của Pet khi có quyền sở hữu hoặc ủy quyền hợp lệ. | Customer / Caregiver / MedicalHistory |

---

## 10. Vaccination Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-10-01 | Mỗi hồ sơ tiêm chủng (`Vaccination`) phải gắn với một Pet cụ thể. | Pet / Vaccination |
| RULE-10-02 | Lô vaccine (`VaccineBatch`) được sử dụng phải thuộc về đúng danh mục Vaccine đăng ký trong kho Store. | Store / Vaccine / VaccineBatch |
| RULE-10-03 | Vaccine đã hết hạn sử dụng (`Expired`) tuyệt đối không được phép sử dụng để tiêm phòng cho Pet. | Store / Vaccine / VaccineBatch |
| RULE-10-04 | Lịch tiêm phòng nhắc lại (`VaccinationSchedule`) phải gắn với Pet và loại vaccine tương ứng. | Pet / VaccinationSchedule |
| RULE-10-05 | Mũi tiêm vaccine chỉ được ghi nhận thành công khi vaccine còn hạn sử dụng và thuộc tồn kho khả dụng tại Store tiêm. | Store / Vaccine / Inventory / Vaccination |

---

## 11. Grooming Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-11-01 | Dịch vụ Grooming chỉ được thực hiện cho Pet có lịch hẹn hợp lệ hoặc yêu cầu dịch vụ trực tiếp đã tiếp nhận tại Store. | Store / Pet / Grooming |
| RULE-11-02 | Dịch vụ phát sinh (`AdditionalService`) chỉ được thêm vào khi quy trình Grooming đang ở trạng thái xử lý (`IN_PROGRESS`). | Store / Grooming |
| RULE-11-03 | Dịch vụ phát sinh bắt buộc phải được Customer xác nhận đồng ý (`ConfirmAdditionalService`) trước khi thực hiện và tính phí. | Customer / Grooming |
| RULE-11-04 | Phiên Grooming đã hoàn thành (`COMPLETED`) không được tiếp tục thêm dịch vụ phát sinh hoặc chỉnh sửa kết quả. | Store / Grooming |
| RULE-11-05 | Các dịch vụ grooming và dịch vụ bổ sung phải thuộc danh mục dịch vụ đang được Store cung cấp. | Store / Service / Grooming |

---

## 12. Inventory & Warehouse Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-12-01 | Tồn kho (`Inventory`) phải được theo dõi và quản lý riêng biệt theo từng Store hoặc Warehouse cụ thể. | Store / Warehouse / Inventory |
| RULE-12-02 | Phiếu điều chỉnh tồn kho (`InventoryAdjustment`) phải gắn với Store/Warehouse phát sinh chênh lệch và phải có lý do điều chỉnh rõ ràng. | Store / Warehouse / InventoryAdjustment |
| RULE-12-03 | Phiếu chuyển kho (`StockTransfer`) phải xác định rõ địa điểm nguồn, địa điểm đích và thuộc quyền quản lý của Organization. | Store / StockTransfer |
| RULE-12-04 | Không được phép xuất kho hoặc chuyển kho số lượng hàng hóa vượt quá số lượng tồn kho khả dụng (`Available Quantity`). | Store / Inventory |
| RULE-12-05 | Hàng hóa đã hết hạn sử dụng không được phép xuất bán hoặc chuyển kho sang Store khác. | Store / Inventory / Expiry |
| RULE-12-06 | Phiếu điều chỉnh tồn kho và phiếu chuyển kho bắt buộc phải được Store Manager phê duyệt (`APPROVED`) trước khi thực hiện xuất hàng. | Store / Inventory |
| RULE-12-07 | Chuyển kho bổ sung hàng từ Warehouse đến Store (Replenishment) chỉ hợp lệ khi Warehouse và Store nhận thuộc cùng một Organization. | Organization / Warehouse / Store / StockTransfer |
| RULE-12-08 | Hủy phiếu chuyển kho (`CancelStockTransfer`) chỉ được thực hiện khi phiếu đang ở trạng thái `REQUESTED`. | StockTransfer |

---

## 13. Procurement Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-13-01 | Yêu cầu mua hàng (`PurchaseRequest`) phải xuất phát từ Store hoặc Warehouse có nhu cầu bổ sung hàng hóa. | Organization / Store / PurchaseRequest |
| RULE-13-02 | Đơn đặt hàng nhà cung cấp (`PurchaseOrder`) phải gắn với Supplier hợp lệ được Organization quản lý. | Organization / Supplier / PurchaseOrder |
| RULE-13-03 | Hàng nhận từ Purchase Order chỉ được cập nhật tăng tồn kho cho đúng Store/Warehouse nhận hàng thực tế. | Store / PurchaseOrder / Inventory |
| RULE-13-04 | Purchase Request phải được Store Manager phê duyệt (`APPROVED`) trước khi tạo Purchase Order tương ứng. | Store / PurchaseRequest |
| RULE-13-05 | Hàng nhận từ Purchase Order làm tăng số lượng tồn kho khả dụng tại thời điểm ghi nhận tiếp nhận hàng (`ReceiveGoods`). | Store / PurchaseOrder / Inventory |
| RULE-13-06 | Đơn đặt hàng đã hủy (`CANCELLED`) không được phép tiếp nhận hàng hoặc cập nhật tồn kho. | PurchaseOrder |

---

## 14. Order Management (v1 Store Fulfillment)

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-14-01 | Mỗi Order phải thuộc về một Customer và một Store cụ thể nơi đơn hàng được tạo và bàn giao. | Store / Customer / Order |
| RULE-14-02 | Sản phẩm trong Order phải là sản phẩm đang được Store phân phối và có tồn kho khả dụng đáp ứng đủ số lượng. | Store / Product / Order |
| RULE-14-03 | Đơn hàng đã hủy (`CANCELLED`) không được phép tiếp tục xử lý, đóng gói hoặc bàn giao cho khách. | Order |
| RULE-14-04 | Trạng thái OrderStatus phải phản ánh chính xác quy trình: `PENDING_PAYMENT -> PAID -> CONFIRMED -> PROCESSING -> READY -> DELIVERED`. | Order |
| RULE-14-05 | Bàn giao đơn hàng (`CompleteStoreOrder`) chỉ được thực hiện tại Store nơi đơn hàng được xử lý và khi đơn hàng ở trạng thái `READY`. | Store / Order |
| RULE-14-06 | Hủy đơn hàng (`CancelOrder`) chỉ được phép thực hiện khi đơn hàng đang ở trạng thái `PENDING_PAYMENT` hoặc `CONFIRMED` (chưa vào giai đoạn soạn hàng `PROCESSING`). | Order |

---

## 15. Billing & Invoice Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-15-01 | Mỗi Hóa đơn (`Invoice`) phải gắn với một Customer và một Store cụ thể. | Store / Customer / Invoice |
| RULE-15-02 | Các mục dịch vụ và sản phẩm ghi nhận trên Invoice phải thuộc phạm vi cung cấp hợp lệ của Store tại thời điểm tạo hóa đơn. | Store / Invoice |
| RULE-15-03 | Mức giảm giá (Discount/Voucher) áp dụng trên Invoice phải thỏa mãn đầy đủ các điều kiện áp dụng và không vượt quá tổng giá trị hóa đơn. | Invoice / Discount |
| RULE-15-04 | Invoice ở trạng thái `VOID` không được phép phát hành, thanh toán hoặc sử dụng cho bất kỳ nghiệp vụ nào. | Invoice |
| RULE-15-05 | Invoice chỉ nhận thanh toán khi ở trạng thái `ISSUED` hoặc `PARTIALLY_PAID`, và tổng số tiền thanh toán không được vượt quá số tiền còn lại phải trả. | Invoice / Payment |
| RULE-15-06 | Invoice chỉ ghi nhận dịch vụ, sản phẩm và chiết khấu hợp lệ thuộc phạm vi Store. | Store / Service / Product / Invoice |
| RULE-15-07 | Invoice chỉ chuyển sang trạng thái `REFUNDED` khi toàn bộ các giao dịch Payment thuộc Invoice đó đã được hoàn tiền đầy đủ 100%. | Invoice / Refund |

---

## 16. Payment Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-16-01 | Mỗi giao dịch Thanh toán (`Payment`) phải gắn với một nghĩa vụ thanh toán hoặc Invoice cụ thể. | Store / Invoice / Payment |
| RULE-16-02 | Tổng các khoản Payment thành công (`SUCCESS`) cho một Invoice không được vượt quá tổng số tiền phải thanh toán của Invoice đó. | Invoice / Payment |
| RULE-16-03 | Giao dịch Payment chỉ được công nhận là hợp lệ (`SUCCESS`) sau khi có xác nhận từ cổng thanh toán trực tuyến hoặc xác nhận tiền mặt từ Thu ngân. | Payment |
| RULE-16-04 | Trạng thái PaymentStatus phải phản ánh đúng kết quả: `PENDING -> PROCESSING -> SUCCESS / FAILED / CANCELLED`. | Payment |
| RULE-16-05 | Callback/Webhook từ cổng thanh toán chỉ được áp dụng cho giao dịch Payment khớp đúng mã giao dịch và số tiền đã đăng ký. | Payment |

---

## 17. Refund Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-17-01 | Mỗi yêu cầu Hoàn tiền (`Refund`) bắt buộc phải gắn với đúng một giao dịch `Payment` gốc đã thanh toán thành công (`SUCCESS`). | Payment / Refund |
| RULE-17-02 | Tổng số tiền hoàn trả cho một Payment không được vượt quá số tiền đã thanh toán của chính Payment đó. | Payment / Refund |
| RULE-17-03 | Hoàn tiền bắt buộc phải được thực hiện qua đúng kênh/cổng thanh toán hoặc phương thức thanh toán gốc của Payment tương ứng. | Payment / Refund |
| RULE-17-04 | Xử lý hoàn tiền (`ProcessRefund`) chỉ được phép tiến hành sau khi yêu cầu hoàn tiền đã được Store Manager phê duyệt (`APPROVED`). | Refund |
| RULE-17-05 | Trạng thái RefundStatus phải phản ánh chính xác kết quả xử lý: `REQUESTED -> APPROVED / REJECTED -> PROCESSING -> COMPLETED / FAILED`. | Refund |
| RULE-17-06 | Từ chối hoàn tiền (`RejectRefund`) chỉ được thực hiện bởi Store Manager khi yêu cầu đang ở trạng thái `REQUESTED` và phải ghi rõ lý do từ chối. | StoreManager / Refund |

---

## 18. Promotion & Voucher Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-18-01 | Promotion chỉ có hiệu lực áp dụng trong phạm vi Organization hoặc Store được cấu hình và trong khoảng thời gian diễn ra chương trình. | Organization / Store / Promotion |
| RULE-18-02 | Voucher chỉ được áp dụng khi đơn hàng hoặc hóa đơn thỏa mãn toàn bộ điều kiện: giá trị tối thiểu, danh mục áp dụng, hạn dùng và đối tượng khách hàng. | Organization / Store / Voucher |
| RULE-18-03 | Mỗi lượt sử dụng Voucher (`VoucherUsage`) phải được ghi nhận định danh gắn với Customer và Invoice/Order cụ thể. | Store / Customer / Voucher |
| RULE-18-04 | Voucher không được phép sử dụng vượt quá tổng ngân sách hoặc giới hạn lượt dùng tối đa cho phép trên toàn hệ thống hoặc trên mỗi khách hàng. | Organization / Store / Voucher |
| RULE-18-05 | Voucher chỉ được áp dụng cho Order hoặc Invoice khi thỏa mãn điều kiện và thuộc phạm vi Store áp dụng. | Store / Voucher / Order / Invoice |

---

## 19. Membership & Loyalty Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-19-01 | Mỗi hồ sơ Hội viên (`Membership`) phải thuộc về đúng một Customer duy nhất. | Customer / Membership |
| RULE-19-02 | Điểm tích lũy (`LoyaltyPoint`) phải thuộc về một Customer cụ thể và chỉ được sử dụng bởi chính Customer đó. | Customer / LoyaltyPoint |
| RULE-19-03 | Không được phép trừ điểm tích lũy vượt quá số điểm khả dụng (`Available Balance`) của khách hàng. | Customer / LoyaltyPoint |
| RULE-19-04 | Điểm tích lũy đã hết hạn (`Expired`) không còn giá trị quy đổi ưu đãi hoặc trừ tiền. | Customer / LoyaltyPoint |
| RULE-19-05 | Việc điều chỉnh điểm tích lũy thủ công của Store Manager phải có lý do nghiệp vụ và được ghi nhận đầy đủ vào Audit Log. | Store / Customer / LoyaltyPoint |
| RULE-19-06 | Điểm tích lũy chỉ được sử dụng cho Customer sở hữu số điểm còn hiệu lực. | Customer / LoyaltyPoint |
| RULE-19-07 | Gia hạn hội viên (`RenewMembership`) chỉ được thực hiện khi Membership đang ở trạng thái `ACTIVE` hoặc trong thời gian ân hạn cho phép gia hạn. | Membership |
| RULE-19-08 | Nâng cấp hạng hội viên (`UpgradeMembership`) làm chuyển trạng thái Membership cũ sang `UPGRADED` và kích hoạt Membership hạng mới tương ứng. | Membership |
| RULE-19-09 | Gói hội viên quá thời hạn hiệu lực mà không được gia hạn sẽ tự động chuyển sang trạng thái `EXPIRED`. | Membership |

---

## 20. Package Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-20-01 | Gói dịch vụ trả trước (`Package`) sau khi mua phải được gắn định danh với Customer sở hữu. | Customer / Package |
| RULE-20-02 | Lượt sử dụng gói (`PackageUsage`) không được vượt quá số lượng quyền lợi dịch vụ còn lại trong gói. | Customer / Package |
| RULE-20-03 | Gói dịch vụ đã hết hạn sử dụng (`EXPIRED`) không được tiếp tục cấn trừ dịch vụ. | Customer / Package |
| RULE-20-04 | Mọi lượt sử dụng dịch vụ trong gói phải được ghi nhận định danh vào lịch sử sử dụng của Package tương ứng. | Customer / Package / PackageUsage |
| RULE-20-05 | Quyền lợi sử dụng Package chỉ áp dụng cho chính Customer sở hữu gói hoặc thú cưng được ủy quyền hợp lệ. | Customer / Package / PackageUsage |
| RULE-20-06 | Hủy gói dịch vụ (`CancelPackage`) chỉ được thực hiện bởi Store Manager khi gói ở trạng thái `PURCHASED`, `ACTIVATED` hoặc `PARTIALLY_CONSUMED` theo chính sách hoàn gói của Organization. | StoreManager / Package |

---

## 21. Incident Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-21-01 | Mỗi Sự cố (`Incident`) phải thuộc Store nơi sự cố phát sinh hoặc được tiếp nhận ghi nhận. | Store / Incident |
| RULE-21-02 | Sự cố y tế (`ClinicalIncident`) chỉ áp dụng cho các vấn đề phát sinh trong quá trình khám, chữa bệnh hoặc tiêm phòng. | Store / ClinicalIncident |
| RULE-21-03 | Sự cố làm đẹp (`GroomingIncident`) chỉ áp dụng cho các vấn đề phát sinh trong quá trình thực hiện dịch vụ Grooming. | Store / GroomingIncident |
| RULE-21-04 | Hồ sơ sự cố đã đóng (`CLOSED`) không được phép chỉnh sửa hoặc tiếp tục xử lý như sự cố đang mở. | Store / Incident |
| RULE-21-05 | Sự cố phải được phân loại mức độ nghiêm trọng (`ClassifyIncident`) trước khi thực hiện các biện pháp điều tra và khắc phục. | Store / Incident |
| RULE-21-06 | Mọi sự cố nghiêm trọng phải được thông báo kịp thời cho Customer sở hữu Pet và báo cáo lên cấp quản lý Organization. | Store / Incident |

---

## 22. Consent & Privacy Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-22-01 | Quyền đồng ý (`Consent`) phải thuộc một Customer cụ thể và quy định rõ phạm vi xử lý dữ liệu được cho phép. | Organization / Customer / Consent |
| RULE-22-02 | Consent đã bị khách hàng thu hồi (`Revoked`) lập tức vô hiệu hóa các hoạt động xử lý dữ liệu dựa trên sự đồng thuận đó. | Customer / Consent |
| RULE-22-03 | Trích xuất dữ liệu (`DataExport`) chỉ được cung cấp trong phạm vi dữ liệu cá nhân thuộc quyền sở hữu của Customer yêu cầu. | Organization / Customer / DataExport |
| RULE-22-04 | Xóa hoặc ẩn danh dữ liệu (`DataDeletion`) phải tuân thủ nghiêm ngặt Chính sách bảo mật (`PrivacyPolicy`) và Thời hạn lưu trữ pháp lý (`RetentionPolicy`). | Organization / Customer / DataDeletion |
| RULE-22-05 | Chính sách lưu trữ dữ liệu (`RetentionPolicy`) của Organization áp dụng nhất quán cho tất cả các Store trực thuộc. | Organization |
| RULE-22-06 | Chính sách bảo mật (`PrivacyPolicy`) của Organization quy định quyền và nghĩa vụ bảo vệ dữ liệu khách hàng trên toàn nền tảng. | Organization |
| RULE-22-07 | Mọi hoạt động truy cập và xử lý dữ liệu Customer và Pet bắt buộc phải thỏa mãn cả hai điều kiện: User có Permission hợp lệ và Customer có Consent còn hiệu lực. | Organization / Customer / Pet |

---

## 23. Notification Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-23-01 | Thông báo (`Notification`) gửi đi bắt buộc phải có đối tượng nhận xác định (Customer hoặc Staff). | Platform / Notification |
| RULE-23-02 | Notification chỉ được phát sinh tự động dựa trên các sự kiện nghiệp vụ được cấp phép cấu hình thông báo. | Platform / Notification |
| RULE-23-03 | Thông báo gửi thất bại chỉ được tự động gửi lại (`RetryNotification`) theo số lần tối đa và khoảng cách thời gian được hệ thống quy định. | Platform / Notification |

---

## 24. Reporting & Analytics

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-24-01 | Báo cáo (`Report`) chỉ bao gồm các số liệu thuộc đúng phạm vi quản lý và quyền truy cập của người xem. | Platform / Organization / Store |
| RULE-24-02 | Báo cáo doanh thu (`RevenueReport`) phải được tổng hợp từ các giao dịch tài chính hợp lệ thuộc phạm vi báo cáo. | Organization / Store / RevenueReport |
| RULE-24-03 | Doanh thu của Organization chỉ bao gồm tổng hợp doanh thu từ các Store thuộc quyền sở hữu của Organization đó. | Organization |
| RULE-24-04 | So sánh doanh thu giữa các Store chỉ được thực hiện giữa các Store thuộc cùng một Organization. | Organization / Store |
| RULE-24-05 | Đối soát doanh thu (`RevenueReconciliation`) phải phản ánh sự đồng nhất giữa Hóa đơn, Giao dịch thanh toán thực tế và Khoản hoàn tiền. | Organization / Store |
| RULE-24-06 | Báo cáo thông tin khách hàng và thú cưng chỉ được trích xuất khi tuân thủ quy định bảo vệ dữ liệu cá nhân theo RULE-22-01 và RULE-22-06. | Organization / Customer / Pet |

---

## 25. Audit Management

| ID | Quy tắc nghiệp vụ (Business Rule) | Phạm vi (Scope) |
|---|---|---|
| RULE-25-01 | Nhật ký kiểm toán (`AuditLog`) phải được ghi nhận tự động, bất biến và gắn đúng phạm vi hoạt động (Platform, Organization, Store). | Platform / Organization / Store |
| RULE-25-02 | Audit Log tuyệt đối không được phép chỉnh sửa, làm sai lệch hoặc xóa bỏ thông tin truy vết hoạt động đã diễn ra trong hệ thống. | Platform / Organization / Store |
| RULE-25-03 | Lịch sử thay đổi phân quyền (`PermissionChange`) chỉ được tra cứu bởi Actor có thẩm quyền kiểm toán tương ứng. | Platform / Organization |
| RULE-25-04 | Lịch sử truy cập hồ sơ bệnh án (`MedicalRecordAccess`) chỉ được tra cứu trong phạm vi quản lý của người có quyền Audit. | Platform / Organization |
| RULE-25-05 | Nhật ký giao dịch tài chính (`PaymentRefundAudit`) chỉ được tra cứu trong phạm vi phân quyền tài chính của Organization/Store. | Organization / Store |
| RULE-25-06 | Nhật ký biến động tồn kho (`InventoryAudit`) chỉ được tra cứu trong phạm vi quản lý kho của Store/Warehouse tương ứng. | Organization / Store |
