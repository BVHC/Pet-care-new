| HỌC VIỆN CÔNG NGHỆ BƯU CHÍNH VIỄN THÔNG | CỘNG HOÀ XÃ HỘI CHỦ NGHĨA VIỆT NAM |
|:---:|:---:|
| **KHOA CÔNG NGHỆ THÔNG TIN 1** | **Độc lập – Tự do – Hạnh phúc** |

# ĐỀ CƯƠNG ĐỒ ÁN TỐT NGHIỆP ĐẠI HỌC

**Sinh viên thực hiện:**

| STT | Họ và tên | Mã SV | Email | Số điện thoại | Vai trò trong nhóm |
|:---:|---|:---:|---|:---:|---|
| 1 | Bùi Văn Hiến | B22DCCN292 | HienBV.B22CN292@stu.ptit.edu.vn | 0344330410 | Nhóm trưởng – Frontend (FE-1) |
| 2 | Nguyễn Đức Mạnh | B22DCCN521 | ManhND.B22CN521@stu.ptit.edu.vn | 0367170223 | Backend 1 (BE-1) |
| 3 | Phạm Quang Dũng | B22DCCN136 | DungPQ.B22CN136@stu.ptit.edu.vn | 0971760561 | Backend 2 (BE-2) |

- **Ngành đào tạo:** Công nghệ thông tin
- **Hệ đào tạo:** Chính quy
- **Giảng viên hướng dẫn:** Vũ Văn Thỏa

---

## 1. Tên đề tài

**Xây dựng hệ thống quản lý chuỗi dịch vụ và chăm sóc thú cưng**

---

## 2. Nội dung chính của đồ án

### 2.1. Mục tiêu của đồ án

1. Xây dựng hệ thống web quản lý tập trung cho chuỗi phòng khám – cửa hàng chăm sóc thú cưng nhiều chi nhánh, số hóa trọn vẹn luồng vận hành tại quầy: **đặt lịch → tiếp nhận và điều phối hàng đợi → khám, tiêm chủng → lập đơn (Order) → thu ngân → trừ kho → nhắc tái chủng, tái khám**; đồng thời hỗ trợ bán lẻ tại quầy, bán hàng trực tuyến, dịch vụ lưu trú (khách sạn thú cưng) và quản lý ca làm việc của nhân viên.
2. Quản lý thống nhất dữ liệu dùng chung toàn chuỗi (hồ sơ khách hàng, thú cưng, danh mục sản phẩm – dịch vụ, nhà cung cấp), đồng thời giới hạn phạm vi thao tác theo chi nhánh của từng vai trò nhân viên; ngăn các sai sót thường gặp khi quản lý thủ công như tồn kho âm, xuất lô hàng hết hạn, giao hàng khi chưa thanh toán, thu tiền ngoài ca, sửa bệnh án không để lại dấu vết.
3. Xây dựng hồ sơ sức khỏe điện tử của thú cưng: bệnh án theo từng lượt khám (khóa khi hoàn tất, chỉ được bổ sung và có ghi vết), đơn thuốc, lịch sử tiêm chủng theo phác đồ và loại vaccine, lịch sử cân nặng, nhật ký chăm sóc khi lưu trú; khách hàng tự tra cứu trực tuyến.
4. Nâng cao trải nghiệm và khả năng giữ chân khách hàng: đặt lịch trực tuyến theo khung giờ còn trống, nhắc lịch hẹn, nhắc tái chủng – tái khám qua email và thông báo trong ứng dụng, tự sinh việc gọi điện chăm sóc (Care Task) cho khách không dùng email; cung cấp cho cấp quản lý các báo cáo doanh thu, thất thu, tỷ lệ không đến, tỷ lệ quay lại tái chủng.
5. Áp dụng quy trình phân tích – thiết kế hướng nghiệp vụ (use case, quy tắc nghiệp vụ có mã định danh, máy trạng thái hữu hạn với danh sách chuyển trạng thái hợp lệ, mô hình miền theo DDD, ERD) và triển khai theo kiến trúc modular monolith có hợp đồng giao tiếp rõ ràng giữa các module, cho phép các thành viên phát triển song song và kiểm thử tự động từng chuyển trạng thái.
6. Nghiên cứu và ứng dụng trí tuệ nhân tạo (AI) nhằm hỗ trợ tư vấn sức khỏe, dinh dưỡng cơ bản và cá nhân hóa việc đề xuất dịch vụ, sản phẩm phù hợp với đặc điểm và nhu cầu của từng thú cưng.

### 2.2. Các chức năng chính của hệ thống

#### 2.2.1. Tác nhân của hệ thống

Hệ thống có 8 tác nhân người (A01–A08) và 3 tác nhân hệ thống (S01–S03). Nhân viên từ A05 đến A08 thuộc đúng một chi nhánh và chỉ thao tác trên dữ liệu của chi nhánh mình; ADMIN và SUPER_MANAGER không gắn với chi nhánh nào. Hồ sơ khách hàng, thú cưng, danh mục sản phẩm – dịch vụ và nhà cung cấp dùng chung toàn chuỗi.

| Mã | Tác nhân | Vai trò chính | Số use case (GĐ1 + GĐ2) |
|---|---|---|:---:|
| A01 | Khách vãng lai (GUEST) | Chưa đăng nhập; xem thông tin công khai và đăng ký tài khoản. Muốn đặt lịch phải đăng ký hoặc nhờ lễ tân đặt hộ | 5 (5 + 0) |
| A02 | Khách hàng (CUSTOMER) | Chủ thú cưng đã có tài khoản, chỉ truy cập dữ liệu của chính mình: hồ sơ cá nhân và thú cưng, đặt lịch, đặt chỗ lưu trú, hồ sơ sức khỏe, feedback; mua hàng và thanh toán online, đặt lịch khám tại nhà, khiếu nại, bình luận bài viết | 24 (17 + 7) |
| A03 | Quản trị hệ thống (ADMIN) | Quản trị kỹ thuật: tài khoản SUPER_MANAGER, khóa/mở khóa người dùng, tham số cấu hình, mẫu thông báo, nhật ký audit; không tham gia nghiệp vụ phòng khám, bán hàng | 10 (10 + 0) |
| A04 | Quản lý chuỗi (SUPER_MANAGER) | Quản lý cấp chuỗi, phạm vi mọi chi nhánh: chi nhánh (gồm tạm ngừng, đóng cửa), danh mục sản phẩm – dịch vụ, phí vận chuyển và phí khám tại nhà, tài khoản nhân viên, nội dung trang, bài viết (gồm duyệt bài của bác sĩ), feedback, khiếu nại, báo cáo toàn chuỗi | 19 (16 + 3) |
| A05 | Quản lý chi nhánh (BRANCH_MANAGER) | Quản lý một chi nhánh: nhân viên cấp dưới, ca làm việc và nghỉ phép, giờ mở cửa, quota lịch hẹn, dịch vụ tại chi nhánh, chuồng, kho (gồm chuyển kho, kiểm kê, truy vết lô), duyệt trả hàng, gộp hồ sơ khách trùng, đối soát ca thu ngân, feedback, khiếu nại và báo cáo chi nhánh | 29 (20 + 9) |
| A06 | Lễ tân kiêm thu ngân (RECEPTIONIST) | Đầu mối vận hành hằng ngày tại quầy: hồ sơ khách, lịch hẹn, tiếp nhận, hàng đợi, bán hàng, thu tiền, nhận/trả thú lưu trú, Care Task; xử lý và giao đơn online, tạo yêu cầu trả hàng, lập cam kết phẫu thuật, tạo khiếu nại thay khách | 30 (23 + 7) |
| A07 | Bác sĩ thú y (VET) | Khám, chẩn đoán, kê đơn, tiêm chủng, hẹn tái khám, bổ sung bệnh án, ghi nhật ký chăm sóc; phẫu thuật, khám tại nhà, điều trị nội trú, viết bài chuyên môn, truy vết lô; có hồ sơ giới thiệu công khai | 22 (14 + 8) |
| A08 | Nhân viên chăm sóc (CARETAKER) | Dịch vụ thẩm mỹ (tắm, cắt tỉa), nhận thú lưu trú, ghi nhật ký chăm sóc; chỉ được xem bệnh án | 15 (13 + 2) |
| S01 | Hệ thống (System) | Tự chạy tác vụ định kỳ và tác vụ theo sự kiện: nhắc lịch, trừ kho, cảnh báo tồn kho, tự chốt ca, sinh Care Task, xử lý đơn online quá hạn, leo thang khiếu nại | 21 tác vụ (12 + 9) |
| S02 | Cổng thanh toán (Payment Gateway) | Xử lý thanh toán và hoàn tiền trực tuyến qua VNPay, ZaloPay hoặc MoMo | GĐ2: 1 use case, 3 tác vụ |
| S03 | Dịch vụ Email/Push | Gửi OTP, thông báo và nhắc lịch tới người dùng | Kênh gửi |

GĐ1 là giai đoạn 1 (05–30/10/2026), GĐ2 là giai đoạn 2 (11–12/2026), xem mục 2.2.2. Ở GĐ2, mọi nhân viên A06–A08 còn xem lịch làm việc và gửi yêu cầu nghỉ, đổi ca. Use case của từng tác nhân xem mục 2.2.5; tác vụ của S01 xem mục 2.2.6.

#### 2.2.2. Phạm vi và giai đoạn triển khai

Phạm vi đồ án gồm toàn bộ 18 module nghiệp vụ trong bộ đặc tả của nhóm (phiên bản v16) và phân hệ trí tuệ nhân tạo, triển khai theo 2 giai đoạn:

- **Giai đoạn 1 – GĐ1 (05–30/10/2026):** các module tầng 1 (làm đầy đủ) và tầng 2 (bản rút gọn), đã có đủ quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu và hợp đồng API.
  + Tầng 1 (12 module): Tài khoản & xác thực (TK), Quản trị hệ thống (QT), Chi nhánh (CN), Thông tin công khai (CK), Khách hàng & thú cưng (KH), Sản phẩm & dịch vụ (SP), Lịch hẹn (LH), Tiếp nhận & hàng đợi (TN), Khám & điều trị (KB), Bán hàng & đơn hàng (BH), Thu ngân (TG), Chăm sóc khách hàng & thông báo (TB).
  + Tầng 2 (5 module): Lưu trú (LT), Kho (KO), Bài viết (BV), Feedback (DG), Báo cáo (BC).
- **Giai đoạn 2 – GĐ2 (11–12/2026):** các use case tầng 3 và phân hệ trí tuệ nhân tạo. Tầng 3 hiện mới được đặc tả ở mức use case; đầu giai đoạn 2, nhóm bổ sung quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu và API trước khi cài đặt.
  + Tầng 3: phân hệ Nhân sự (NS) và phần mở rộng của 10 module – thương mại điện tử và thanh toán trực tuyến, trả hàng – hoàn tiền, khám tại nhà, phẫu thuật, điều trị nội trú, chuyển kho, kiểm kê, truy vết lô, khiếu nại, bài viết của bác sĩ và bình luận, tạm ngừng/đóng cửa chi nhánh, gộp hồ sơ khách trùng.
  + Phân hệ trí tuệ nhân tạo: chatbot tư vấn sức khỏe – dinh dưỡng và hệ thống gợi ý cá nhân hóa.

**Quy mô đặc tả:**

| Thành phần | Hiện có trong đặc tả v16 | Giai đoạn 1 (tầng 1–2) | Giai đoạn 2 (tầng 3 + AI) |
|---|:---:|:---:|:---:|
| Module nghiệp vụ | 18 | 17 | Nhân sự (NS), phần mở rộng của 10 module, phân hệ AI (chưa có trong đặc tả) |
| Use case | 85 (UC01–UC89, đã bỏ 4 mã) | 58 | 27 |
| Tác vụ hệ thống tự động | 21 (ST01–ST21) | 12 | 9 |
| Quy tắc nghiệp vụ (BR) | 134 quy tắc còn hiệu lực | 134 | Đặc tả bổ sung |
| Máy trạng thái (FSM) | 10 đối tượng – 66 chuyển trạng thái | 10 – 66 | Đặc tả bổ sung |
| Mô hình miền / bảng CSDL | 52 model / 55 bảng | 52 / 55 | Đặc tả bổ sung |
| API (hợp đồng v1) | 230 endpoint – 12 module backend | 230 | Đặc tả bổ sung |

#### 2.2.3. Phân hệ và tác nhân quản lý trực tiếp

- **Tác nhân quản lý trực tiếp:** tác nhân chịu trách nhiệm chính về dữ liệu và nghiệp vụ của phân hệ (tạo lập, cấu hình, phê duyệt hoặc vận hành chính).
- **Tác nhân tham gia:** tác nhân sử dụng một phần chức năng của phân hệ trong phạm vi được phép.
- **Bổ sung ở giai đoạn 2:** use case tầng 3 của phân hệ, kèm tác nhân thực hiện.

| Mã | Phân hệ | Tầng | Tác nhân quản lý trực tiếp (use case) | Tác nhân tham gia (use case) | Bổ sung ở giai đoạn 2 |
|---|---|:---:|---|---|---|
| TK | Tài khoản & xác thực | 1 | Từng người dùng tự quản lý tài khoản của mình: A02–A08 (UC03–UC06); A02 liên kết hồ sơ (UC07) | A01 đăng ký (UC01, UC02); A06 sửa email, khôi phục tài khoản, liên kết hồ sơ hộ khách tại quầy (UC22) | — |
| QT | Quản trị hệ thống | 1 | A03 (UC08 với SUPER_MANAGER, UC09, UC10, UC11); A04, A05 quản lý tài khoản nhân viên cấp dưới (UC08) | — | — |
| CN | Chi nhánh | 1 | A04 (UC12); A05 với chi nhánh mình (UC14) | A01, A02 xem chi nhánh (UC15) | A04 tạm ngừng / đóng cửa chi nhánh (UC13) |
| CK | Thông tin công khai | 1 | A04 (UC21) | A01, A02 xem thông tin công khai (UC15–UC17); A07 cập nhật hồ sơ giới thiệu công khai (UC06) | — |
| KH | Khách hàng & thú cưng | 1 | A06 (UC22, UC24, UC26) | A02 quản lý thú cưng của mình, xem hồ sơ sức khỏe (UC24, UC25); A06, A07, A08 tra cứu (UC23) | A05 gộp hồ sơ khách trùng (UC27) |
| SP | Sản phẩm & dịch vụ | 1 | A04 (UC28–UC31) | A05 bật/tắt dịch vụ tại chi nhánh (UC33); A01, A02 xem (UC15, UC16) | A04 cấu hình phí vận chuyển, phí khám tại nhà (UC32) và định mức vật tư của dịch vụ (UC30) |
| LH | Lịch hẹn | 1 | A06 (UC39, UC40); A05 thiết lập quota (UC42) | A02 tự đặt, đổi, hủy lịch (UC39, UC40); S01 nhắc lịch, chuyển lịch sang không đến – NO_SHOW (ST03, ST05) | A02, A06 đặt lịch khám tại nhà (UC43) |
| TN | Tiếp nhận & hàng đợi | 1 | A06 (UC44, UC45, UC47) | A05 gán lại lượt đã gọi (UC45); A07, A08 xem hàng đợi và gọi lượt (UC46); A07 xác định ca cấp cứu (UC47) | — |
| KB | Khám & điều trị | 1 | A07 (UC48, UC49, UC53) | A08 thực hiện dịch vụ thẩm mỹ, xem bệnh án (UC52, UC53); A02 xem hồ sơ sức khỏe (UC25) | A07 phẫu thuật (UC50, A06 lập cam kết), khám tại nhà (UC51); S01 trừ vật tư tiêu hao (ST07) |
| LT | Lưu trú & nội trú | 2 | A05 quản lý chuồng (UC54); A06 đặt chỗ, nhận/trả thú (UC58, UC59) | A02 đặt chỗ, xem nhật ký (UC58, UC60); A08 nhận thú, ghi nhật ký (UC59, UC57); A07 ghi nhật ký (UC57); S01 (ST05, ST15) | A07 nhập/xuất viện (UC55), điều trị nội trú (UC56) |
| BH | Bán hàng & đơn hàng | 1 | A06 (UC66, UC67) | A05 hủy Order khách không thanh toán (UC66); A07, A08 thêm dịch vụ, thuốc vào Order (UC67) | A02 giỏ hàng, đặt hàng, thanh toán online, đơn hàng của tôi (UC61–UC64, cùng S02); A06 xử lý và giao đơn online (UC65), tạo yêu cầu trả hàng (UC68); A05 duyệt trả hàng (UC69); S01 (ST09–ST12, ST21) |
| TG | Thu ngân | 1 | A06 (UC70, UC71) | A05 đối soát ca (UC72); S01 tự chốt ca (ST13) | S01, S02 đối soát cổng thanh toán (ST14) |
| KO | Kho | 2 | A05 (UC73–UC76) | A06 xem tồn kho (UC75); S01 trừ kho theo FEFO – hết hạn trước, xuất trước – và cảnh báo tồn (ST06, ST08) | A05 chuyển kho, kiểm kê (UC77, UC78); A05, A07 truy vết lô (UC79) |
| BV | Bài viết | 2 | A04 (UC81) | A01, A02 xem bài viết (UC17) | A07 soạn và gửi duyệt bài viết (UC80); A04 duyệt bài, ẩn bình luận (UC81); A02 bình luận (UC82) |
| DG | Feedback & khiếu nại | 2 | A04 với mọi feedback; A05 với feedback chi nhánh mình (UC84) | A02 gửi feedback (UC83) | A02, A06 gửi và theo dõi khiếu nại (UC85); A05, A04 xử lý khiếu nại (UC86); S01 leo thang, tự đóng khiếu nại (ST16, ST17) |
| TB | Chăm sóc khách hàng & thông báo | 1 | A06 thực hiện Care Task (UC87) | A02–A08 xem thông báo, A02 cài đặt nhận thông báo (UC88); A03 sửa mẫu thông báo (UC10); S01 (ST04, ST18–ST20) | — |
| BC | Báo cáo | 2 | A04 toàn chuỗi; A05 chi nhánh mình (UC89) | — | — |
| NS | Nhân sự | 3 | A05 (UC34, UC37, UC38) | A06, A07, A08 (UC35, UC36) | Toàn bộ phân hệ |
| AI | Trí tuệ nhân tạo & đề xuất thông minh | — | — | A01, A02 hỏi đáp với chatbot; A02 nhận gợi ý dịch vụ, sản phẩm | Toàn bộ phân hệ |

#### 2.2.4. Chức năng theo phân hệ

Chức năng ghi *(GĐ2)* thực hiện ở giai đoạn 2 (11–12/2026); các chức năng còn lại thuộc giai đoạn 1 (05–30/10/2026).

- **Phân hệ Tài khoản và Quản trị hệ thống (Identity & Administration):**
  + Quản lý tài khoản và phân quyền theo vai trò (RBAC), giới hạn dữ liệu theo chi nhánh, gồm 7 vai trò: Khách hàng, Lễ tân kiêm thu ngân, Bác sĩ thú y, Nhân viên chăm sóc, Quản lý chi nhánh, Quản lý chuỗi và Quản trị viên hệ thống.
  + Hỗ trợ đăng ký, đăng nhập, quên/đổi mật khẩu; xác thực bằng JWT kết hợp OTP qua email; tự khóa tạm tài khoản khi đăng nhập sai nhiều lần.
  + Quản lý tài khoản nhân viên theo phân cấp (tạo, gán chi nhánh và chức vụ, điều chuyển, vô hiệu hóa), khóa/mở khóa người dùng; liên kết tài khoản online với hồ sơ khách lập tại quầy.
  + Cấu hình tham số hệ thống, mẫu thông báo và lưu nhật ký audit các thao tác nhạy cảm (chỉ thêm mới, không sửa/xóa).
- **Phân hệ Chi nhánh và Danh mục sản phẩm – dịch vụ (Branch & Catalog):**
  + Quản lý chi nhánh: thông tin, kích hoạt, giờ mở cửa (tối đa 2 khoảng mỗi ngày), ngày nghỉ, chi nhánh nhận cấp cứu ngoài giờ.
  + Quản lý danh mục dùng chung toàn chuỗi: sản phẩm (thuốc kê đơn, hàng có hạn dùng), dịch vụ khám/tiêm, thẩm mỹ, lưu trú (loại chuồng), loại vaccine và phác đồ tiêm chủng theo loài.
  + Bật/tắt dịch vụ tại từng chi nhánh và cấu hình số lịch hẹn tối đa (quota) theo nhóm dịch vụ, khung giờ.
  + *(GĐ2)* Tạm ngừng hoặc đóng cửa chi nhánh kèm chuyển giao công việc tồn đọng; cấu hình phí vận chuyển, phí khám tại nhà và định mức vật tư tiêu hao của dịch vụ.
- **Phân hệ Khách hàng và Hồ sơ thú cưng (Customer & Pet Profile):**
  + Quản lý hồ sơ khách hàng dùng chung toàn chuỗi (tạo khi đăng ký online hoặc tại quầy); tra cứu theo số điện thoại, họ tên, email, tên thú cưng; cảnh báo hồ sơ nghi trùng.
  + Quản lý thông tin thú cưng như loài, giống, ngày sinh, giới tính, cân nặng theo từng lần đo; đánh dấu thú đã mất, chuyển chủ thú cưng.
  + Cung cấp hồ sơ sức khỏe điện tử cho khách hàng: chẩn đoán, đơn thuốc, lịch sử tiêm chủng, ngày tái chủng và tái khám.
  + *(GĐ2)* Gộp hồ sơ khách hàng trùng.
- **Phân hệ Đặt lịch hẹn (Appointment):**
  + Cho phép khách hàng đặt lịch trực tuyến các dịch vụ khám bệnh, tiêm chủng, thẩm mỹ theo khung giờ 30 phút và chi nhánh; lễ tân đặt hộ tại quầy.
  + Quản lý vòng đời lịch hẹn từ khi đặt, đổi giờ, tiếp nhận đến khi hoàn thành, hủy hoặc không đến (NO_SHOW).
  + Kiểm soát quota theo khung giờ, giới hạn số lịch của mỗi thú cưng; hạn chế đặt online với khách nhiều lần không đến hoặc hủy muộn; nhắc lịch trước 24 giờ.
  + *(GĐ2)* Đặt lịch khám tại nhà.
- **Phân hệ Tiếp nhận, Khám chữa bệnh và Tiêm chủng (Reception, Clinical & Vaccination):**
  + Tiếp nhận khách có hẹn, khách không hẹn trước và ca cấp cứu; tự động tạo đơn (Order) cho mỗi lượt tiếp nhận.
  + Điều phối hàng đợi tại chi nhánh theo thứ tự ưu tiên cấp cứu → lịch hẹn → khách không hẹn; gán bác sĩ hoặc nhân viên chăm sóc phù hợp.
  + Quản lý khám bệnh và hồ sơ bệnh án điện tử (EMR): chẩn đoán, kê đơn có kiểm tra tồn kho, hẹn tái khám; bệnh án khóa khi hoàn tất, chỉ được bổ sung có ghi vết.
  + Tiêm chủng theo phác đồ và loại vaccine, tự trừ kho vaccine và tính ngày tái chủng; thực hiện dịch vụ thẩm mỹ (tắm, cắt tỉa).
  + *(GĐ2)* Phẫu thuật (lập cam kết, thực hiện) phát sinh từ lượt khám; khám tại nhà; tự trừ vật tư tiêu hao theo định mức khi hoàn tất khám, thẩm mỹ.
- **Phân hệ Bán hàng, Thu ngân và Đơn hàng (Retail POS, Cashier & Orders):**
  + Bán hàng tại quầy với đơn từ lượt khám, bán lẻ và lưu trú; chốt giá tại thời điểm bán, không bán lẻ thuốc kê đơn.
  + Thu tiền mặt hoặc chuyển khoản theo ca thu ngân của từng lễ tân, thu gộp nhiều đơn của cùng khách; chỉ giao hàng sau khi thanh toán; đối soát ca thu ngân.
  + *(GĐ2)* Hỗ trợ giỏ hàng, đặt hàng online có tính phí vận chuyển; tích hợp thanh toán trực tuyến qua VNPay, ZaloPay hoặc MoMo; xử lý, giao đơn và theo dõi đơn hàng.
  + *(GĐ2)* Tự hủy đơn online chưa thanh toán, hết hạn giao dịch, tự xác nhận đã nhận hàng; xử lý trả hàng và hoàn tiền (Refund) theo quy trình duyệt; đối soát cổng thanh toán.
- **Phân hệ Quản lý kho (Inventory & Warehouse):**
  + Quản lý kho theo chi nhánh và lô hàng: nhà cung cấp, nhập kho, xuất kho theo nguyên tắc hết hạn trước – xuất trước (FEFO), điều chỉnh tồn.
  + Cảnh báo tồn kho dưới ngưỡng tối thiểu, lô hàng sắp hết hạn và đã hết hạn.
  + *(GĐ2)* Điều chuyển hàng hóa giữa các chi nhánh, kiểm kê kho và truy vết lô hàng.
- **Phân hệ Lưu trú và Nội trú thú cưng (Pet Boarding & Inpatient):**
  + Quản lý chuồng theo loại; đặt chỗ lưu trú theo loại chuồng với kiểm tra sức chứa từng đêm.
  + Nhận thú (kiểm tra mũi tiêm bắt buộc, gán chuồng, ghi tình trạng sức khỏe), trả thú và tính tiền theo số đêm thực tế.
  + Ghi nhật ký chăm sóc hằng ngày kèm ảnh, báo ngay cho khách khi có bất thường; xử lý thú quá hạn đón.
  + *(GĐ2)* Nhập/xuất viện (gồm chuyển thú từ lưu trú sang điều trị) và điều trị nội trú: ra y lệnh, theo dõi hậu phẫu.
- **Phân hệ Thông tin công khai, Bài viết, Feedback và Khiếu nại (Public Portal, Articles, Feedback & Complaints):**
  + Trang thông tin công khai: dịch vụ kèm giá tham khảo, chi nhánh (gồm chi nhánh cấp cứu 24/7), đội ngũ bác sĩ, sản phẩm và chi nhánh còn hàng.
  + Quản lý nội dung trang (banner, giới thiệu, chính sách, FAQ), chuyên mục và bài viết.
  + Tiếp nhận feedback không công khai của khách hàng về dịch vụ và website; theo dõi trạng thái xử lý.
  + *(GĐ2)* Bác sĩ soạn bài chuyên môn gửi duyệt; khách hàng bình luận bài viết.
  + *(GĐ2)* Khiếu nại: khách hàng gửi và theo dõi (lễ tân tạo thay khách), quản lý xử lý; tự leo thang khiếu nại quá hạn và tự đóng khiếu nại.
- **Phân hệ Chăm sóc khách hàng và Thông báo (Customer Care & Notification):**
  + Tự động nhắc tái chủng, tái khám qua email và thông báo trong ứng dụng; tự hủy nhắc khi thú đã tiêm lại, đã đặt lịch khám hoặc đã mất.
  + Tự sinh việc gọi điện chăm sóc (Care Task) cho lễ tân với khách không có email, quá hạn tái chủng hoặc quá hạn đón thú lưu trú.
  + Trung tâm thông báo, cài đặt nhận thông báo; tự gửi lại khi gửi thất bại.
- **Phân hệ Nhân sự (Workforce):** *(GĐ2)*
  + Định nghĩa ca làm việc, phân ca và xem danh sách nhân viên của chi nhánh.
  + Nhân viên xem lịch làm việc, gửi hoặc rút yêu cầu nghỉ, đổi ca; quản lý chi nhánh duyệt yêu cầu.
  + Ghi nhận vắng đột xuất của nhân viên.
- **Phân hệ Báo cáo và Thống kê (Reporting & Analytics):**
  + Thống kê doanh thu theo nguồn (lượt khám, bán lẻ, lưu trú) và thất thu của từng chi nhánh.
  + Thống kê số lượt dịch vụ hoàn tất, tỷ lệ không đến và hủy muộn, tỷ lệ khách quay lại tái chủng đúng hạn.
  + Báo cáo tồn kho dưới ngưỡng, lô sắp hết hạn và mức độ hài lòng của khách hàng qua feedback.
  + Quản lý chuỗi xem toàn chuỗi, quản lý chi nhánh xem chi nhánh mình; hiển thị trực quan bằng biểu đồ.
- **Phân hệ Ứng dụng Trí tuệ nhân tạo và Đề xuất thông minh (AI & Recommendation):** *(GĐ2)*
  + Chatbot AI tư vấn sức khỏe và dinh dưỡng: ứng dụng mô hình RAG kết hợp LLM để giải đáp các câu hỏi thường gặp về chăm sóc thú cưng, hướng dẫn chăm sóc cơ bản và tra cứu thông tin liên quan đến lịch tiêm phòng.
  + Hệ thống gợi ý cá nhân hóa (Recommendation Engine): đề xuất dịch vụ thẩm mỹ, lưu trú và sản phẩm phù hợp dựa trên loài, giống, độ tuổi, cân nặng và lịch sử sử dụng dịch vụ của thú cưng.

#### 2.2.5. Use case của từng tác nhân

Phần này liệt kê toàn bộ 85 use case theo từng tác nhân; cột *Phạm vi quyền / ghi chú* nêu giới hạn theo quy tắc nghiệp vụ. Use case ghi *(GĐ2)* thuộc tầng 3, thực hiện ở giai đoạn 2; quy tắc chi tiết của các use case này được đặc tả bổ sung đầu giai đoạn 2.

##### Use case chung của mọi người dùng đã có tài khoản (A02–A08)

| Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|
| UC02 | Xác thực OTP (gồm gửi lại OTP) | Dùng khi đăng ký, quên mật khẩu, đổi email, liên kết hồ sơ; OTP gửi qua email. A01 dùng khi đăng ký |
| UC03 | Đăng nhập / đăng xuất | Đăng nhập bằng email; nhân viên bắt buộc đổi mật khẩu ở lần đăng nhập đầu tiên |
| UC04 | Quên mật khẩu | Đặt lại mật khẩu qua OTP; đăng xuất mọi phiên |
| UC05 | Đổi mật khẩu | Phải nhập đúng mật khẩu hiện tại; đăng xuất các phiên khác |
| UC06 | Quản lý hồ sơ cá nhân | Họ tên, ảnh, số điện thoại; khách hàng có sổ địa chỉ (tối đa 5); bác sĩ có hồ sơ giới thiệu công khai. Email chỉ được sửa hộ |
| UC88 | Xem thông báo & cài đặt nhận thông báo | Xem thông báo trong ứng dụng; riêng A02 được cài đặt nhận thông báo |

##### A01 – Khách vãng lai (GUEST) · 5 use case

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| TK | UC01 | Đăng ký tài khoản | Xác nhận đủ 18 tuổi, đồng ý điều khoản; email không trùng tài khoản khác |
| TK | UC02 | Xác thực OTP (gồm gửi lại OTP) | OTP 6 chữ số gửi qua email, hiệu lực 5 phút |
| CK | UC15 | Xem dịch vụ, chi nhánh & đội ngũ bác sĩ | Gồm số điện thoại chi nhánh, chi nhánh nhận cấp cứu 24/7 |
| CK | UC16 | Xem & tìm kiếm sản phẩm | Kèm danh sách chi nhánh còn hàng; không hiển thị thuốc kê đơn; muốn mua online phải đăng nhập (GĐ2) |
| CK | UC17 | Xem bài viết | Chỉ bài đã xuất bản |

Muốn đặt lịch, khách vãng lai phải đăng ký tài khoản (UC01) hoặc nhờ lễ tân đặt hộ (UC39).

##### A02 – Khách hàng (CUSTOMER) · 24 use case (GĐ1: 17, GĐ2: 7)

Gồm 6 use case chung ở trên và các use case sau (chỉ trên dữ liệu của chính mình):

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| TK | UC07 | Liên kết tài khoản với hồ sơ khách có sẵn | Khi số điện thoại trùng hồ sơ tại quầy chưa liên kết: chọn hồ sơ và xác thực OTP gửi tới email của hồ sơ, hoặc chọn "Không phải tôi" |
| CK | UC15 | Xem dịch vụ, chi nhánh & đội ngũ bác sĩ | Như A01 |
| CK | UC16 | Xem & tìm kiếm sản phẩm | Như A01 |
| CK | UC17 | Xem bài viết | Như A01 |
| KH | UC24 | Quản lý thú cưng | Chỉ thú của mình: thêm, cập nhật, đánh dấu đã mất, xóa thú chưa phát sinh giao dịch |
| KH | UC25 | Xem hồ sơ sức khỏe thú cưng | Chẩn đoán, đơn thuốc, lịch sử tiêm, ngày tái chủng, tái khám, cân nặng; không thấy ghi chú nội bộ |
| LH | UC39 | Đặt lịch hẹn | Dịch vụ Khám/Tiêm hoặc Thẩm mỹ; trước ≥ 24 giờ, xa nhất 30 ngày; mỗi thú tối đa 2 lịch đang chờ; không bị hạn chế đặt online |
| LH | UC40 | Quản lý lịch hẹn | Xem; đổi khung giờ trước ≥ 12 giờ, tối đa 3 lần; hủy trước giờ hẹn (dưới 12 giờ tính hủy muộn) |
| LT | UC58 | Đặt chỗ lưu trú theo loại chuồng | Đặt trước ≥ 1 ngày, xa nhất 60 ngày, từ 1 đến 30 đêm; hủy, gia hạn |
| LT | UC60 | Xem lưu trú & nhật ký chăm sóc của thú cưng | Xem nhật ký ngay khi được ghi |
| DG | UC83 | Gửi feedback | Không công khai; tối đa 5 feedback mỗi ngày; xem lại được nhưng không sửa, xóa |
| LH | UC43 | Đặt lịch khám tại nhà | *(GĐ2)* Đặt lịch để bác sĩ đến khám tại nhà |
| BH | UC61 | Quản lý giỏ hàng | *(GĐ2)* Thêm, sửa, xóa sản phẩm trong giỏ; không có thuốc kê đơn |
| BH | UC62 | Đặt hàng online | *(GĐ2)* Chọn địa chỉ giao hàng từ sổ địa chỉ; hệ thống tính phí vận chuyển (ST09) |
| BH | UC63 | Thanh toán online | *(GĐ2)* Thanh toán qua cổng thanh toán (S02) |
| BH | UC64 | Quản lý đơn hàng của tôi | *(GĐ2)* Xem, theo dõi giao hàng, hủy đơn, xác nhận đã nhận hàng |
| BV | UC82 | Bình luận bài viết | *(GĐ2)* Đăng, sửa, xóa bình luận của mình |
| DG | UC85 | Gửi & theo dõi khiếu nại | *(GĐ2)* Tạo, xem, đóng hoặc mở lại khiếu nại |

Khi tài khoản còn chờ quyết định liên kết hồ sơ (BR-TK-19), các chức năng quản lý thú cưng, đặt lịch, đặt lưu trú, xem hồ sơ sức khỏe và gửi feedback bị ẩn.

##### A03 – Quản trị hệ thống (ADMIN) · 10 use case

Gồm 6 use case chung ở trên và các use case sau:

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| QT | UC08 | Quản lý tài khoản nhân viên | Chỉ với SUPER_MANAGER: tạo, vô hiệu hóa, kích hoạt lại, sửa email |
| QT | UC09 | Khóa / mở khóa người dùng | Mọi tài khoản trừ chính mình; bắt buộc nhập lý do; có hiệu lực ngay, ghi audit |
| QT | UC10 | Cấu hình tham số hệ thống & mẫu thông báo | Tham số có khoảng giá trị hợp lệ; mẫu thông báo chỉ sửa nội dung, khôi phục được bản mặc định |
| QT | UC11 | Xem nhật ký audit | Chỉ ADMIN được xem; audit không sửa, không xóa |

ADMIN không tham gia nghiệp vụ phòng khám và bán hàng.

##### A04 – Quản lý chuỗi (SUPER_MANAGER) · 19 use case (GĐ1: 16, GĐ2: 3)

Gồm 6 use case chung ở trên và các use case sau (phạm vi mọi chi nhánh):

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| QT | UC08 | Quản lý tài khoản nhân viên | Tạo BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER, gán chi nhánh và chức vụ; duy nhất được đổi chức vụ và điều chuyển; vô hiệu hóa, kích hoạt lại nhân viên A05–A08 |
| CN | UC12 | Quản lý chi nhánh | Tạo, cập nhật, kích hoạt (khi đã có quản lý chi nhánh và giờ mở cửa), bật cờ nhận cấp cứu ngoài giờ |
| CK | UC21 | Quản lý nội dung trang | Banner trang chủ, giới thiệu, chính sách, FAQ |
| SP | UC28 | Quản lý danh mục sản phẩm | Danh mục dùng chung toàn chuỗi |
| SP | UC29 | Quản lý sản phẩm | Tạo, cập nhật, đổi giá, ngừng kinh doanh; cờ thuốc kê đơn, cờ quản lý hạn dùng; sản phẩm vaccine gắn loại vaccine |
| SP | UC30 | Quản lý dịch vụ | Nhóm Khám/Tiêm (loại Khám hoặc Tiêm), Thẩm mỹ, Lưu trú (loại chuồng); đổi giá, ngừng. Định mức vật tư tiêu hao của dịch vụ thuộc GĐ2 |
| SP | UC31 | Quản lý loại vaccine & phác đồ tiêm chủng | Phác đồ theo loài: mũi thứ, khoảng cách tới mũi kế, tuổi tối thiểu, cờ bắt buộc khi lưu trú |
| BV | UC81 | Quản lý bài viết, chuyên mục & bình luận | Soạn, xuất bản, ẩn bài; chuyên mục còn bài thì chỉ ẩn. Duyệt bài của bác sĩ, ẩn bình luận thuộc GĐ2 |
| DG | UC84 | Xem & xử lý feedback | Mọi feedback, kể cả feedback không gắn chi nhánh |
| BC | UC89 | Xem báo cáo | Toàn chuỗi, lọc theo chi nhánh |
| CN | UC13 | Tạm ngừng / đóng cửa chi nhánh | *(GĐ2)* Gồm chuyển giao công việc tồn đọng |
| SP | UC32 | Cấu hình phí vận chuyển & phí khám tại nhà | *(GĐ2)* Dùng khi đặt hàng online và đặt lịch khám tại nhà |
| DG | UC86 | Xử lý khiếu nại | *(GĐ2)* Cùng quản lý chi nhánh xử lý khiếu nại |

Trường hợp đặc biệt: gán lại lượt đã gọi (UC45) khi chi nhánh không còn BRANCH_MANAGER đang hoạt động (BR-TN-08).

##### A05 – Quản lý chi nhánh (BRANCH_MANAGER) · 29 use case (GĐ1: 20, GĐ2: 9)

Gồm 6 use case chung ở trên và các use case sau (chỉ trên dữ liệu chi nhánh mình):

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| QT | UC08 | Quản lý tài khoản nhân viên | Tạo, vô hiệu hóa, kích hoạt lại RECEPTIONIST, VET, CARETAKER của chi nhánh mình |
| CN | UC14 | Cấu hình giờ mở cửa & ngày nghỉ | Tối đa 2 khoảng giờ mỗi ngày, có ngày hiệu lực; chọn hủy hàng loạt khi lịch đã đặt bị ảnh hưởng |
| SP | UC33 | Bật / tắt dịch vụ tại chi nhánh | Gồm cả loại chuồng lưu trú |
| LH | UC42 | Thiết lập quota lịch hẹn | Quota mặc định theo nhóm dịch vụ; quota riêng từng khung giờ, cho phép 0 để khóa khung |
| TN | UC45 | Điều phối hàng đợi | Chỉ gán lại lượt đã gọi khi người phụ trách bị khóa; bắt buộc nhập lý do, ghi audit |
| LT | UC54 | Quản lý chuồng | Tạo chuồng theo loại chuồng; chuyển sẵn sàng ⇄ bảo trì |
| BH | UC66 | Bán hàng & chốt Order tại quầy | Chỉ hủy Order chờ thanh toán khi khách không trả tiền; bắt buộc nhập lý do; tính vào thất thu |
| TG | UC72 | Đối soát ca thu ngân | Nhập hoặc xác nhận số tiền thực đếm, ghi chênh lệch và ghi chú |
| KO | UC73 | Quản lý nhà cung cấp | Nhà cung cấp đã có phiếu nhập chỉ chuyển ngừng hợp tác, không xóa |
| KO | UC74 | Nhập kho | Tạo, sửa phiếu nháp, xác nhận, hủy; ghi số lô, hạn dùng với sản phẩm quản lý hạn dùng |
| KO | UC75 | Xem tồn kho & thiết lập tồn tối thiểu | Tồn theo lô và hạn dùng |
| KO | UC76 | Điều chỉnh tồn kho | Lý do: hỏng, hết hạn, lệch kiểm đếm, khác; ghi audit |
| DG | UC84 | Xem & xử lý feedback | Feedback gắn với chi nhánh mình |
| BC | UC89 | Xem báo cáo | Chi nhánh mình |
| KH | UC27 | Gộp hồ sơ khách trùng | *(GĐ2)* Gộp các hồ sơ trùng của cùng một khách |
| NS | UC34 | Quản lý ca làm việc | *(GĐ2)* Định nghĩa ca, phân ca, xem nhân viên chi nhánh |
| NS | UC37 | Duyệt yêu cầu nghỉ, đổi ca | *(GĐ2)* Yêu cầu của nhân viên chi nhánh mình |
| NS | UC38 | Ghi nhận vắng đột xuất | *(GĐ2)* Nhân viên chi nhánh mình |
| BH | UC69 | Duyệt yêu cầu trả hàng | *(GĐ2)* Duyệt yêu cầu do lễ tân tạo; hoàn tiền online qua cổng thanh toán (ST21) |
| KO | UC77 | Chuyển kho | *(GĐ2)* Tạo, hủy, xác nhận nhận hàng |
| KO | UC78 | Kiểm kê kho | *(GĐ2)* Kho của chi nhánh mình |
| KO | UC79 | Truy vết lô hàng | *(GĐ2)* Cùng bác sĩ thú y |
| DG | UC86 | Xử lý khiếu nại | *(GĐ2)* Khiếu nại liên quan đến chi nhánh mình |

Ngoài ra, quản lý chi nhánh được gỡ sớm hạn chế đặt online của khách (BR-LH-09) và nhận cảnh báo từ hệ thống: ca thu ngân tự chốt, Order chờ thanh toán quá hạn, tồn kho dưới ngưỡng và lô sắp hết hạn, thú quá hạn đón từ 7 ngày, hết chuồng khi nhận thú.

##### A06 – Lễ tân kiêm thu ngân (RECEPTIONIST) · 30 use case (GĐ1: 23, GĐ2: 7)

Gồm 6 use case chung ở trên và các use case sau (tại chi nhánh mình):

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| KH | UC22 | Quản lý hồ sơ khách tại quầy | Tạo, cập nhật, cảnh báo nghi trùng số điện thoại; sửa email tài khoản khách, khôi phục tài khoản khi khách mất email (đối chiếu CCCD trực tiếp, không lưu); liên kết hồ sơ với tài khoản tại quầy |
| KH | UC23 | Tra cứu khách & thú cưng | Theo số điện thoại, họ tên, email, tên thú; hồ sơ dùng chung toàn chuỗi |
| KH | UC24 | Quản lý thú cưng | Thêm, cập nhật, đánh dấu đã mất, xóa thú chưa phát sinh giao dịch |
| KH | UC26 | Chuyển chủ thú cưng | Khi chủ cũ có mặt hoặc đã xác nhận và chủ mới đã có hồ sơ |
| LH | UC39 | Đặt lịch hẹn | Đặt hộ mọi khung giờ chưa bắt đầu, còn quota |
| LH | UC40 | Quản lý lịch hẹn | Đổi khung giờ bất kỳ lúc nào trước giờ hẹn, hủy theo yêu cầu khách; gỡ sớm hạn chế đặt online |
| TN | UC44 | Tiếp nhận khách | Check-in lịch hẹn (sớm nhất 30 phút trước giờ hẹn) và khách không hẹn trước; tạo Visit, mở Order |
| TN | UC45 | Điều phối hàng đợi | Gán nhân viên đúng chức vụ (ưu tiên người đang trực tuyến), sắp thứ tự, hủy lượt khách bỏ về |
| TN | UC47 | Xác định ca cấp cứu | Ca cấp cứu được ưu tiên đầu hàng đợi; ngoài giờ chỉ ở chi nhánh có cờ cấp cứu |
| LT | UC58 | Đặt chỗ lưu trú theo loại chuồng | Đặt hộ, kể cả trong ngày nếu còn chỗ; hủy, gia hạn |
| LT | UC59 | Nhận / trả thú cưng lưu trú | Kiểm tra tiêm phòng khi nhận; thu tiền trước khi trả; hủy phiên trả thú khi khách không thanh toán; kết thúc lưu trú khi thú mất |
| BH | UC66 | Bán hàng & chốt Order tại quầy | Tạo, xóa dòng, chốt Order bán lẻ; hủy Order bán lẻ chưa chốt |
| BH | UC67 | Thêm dịch vụ / hàng vào Order | Thêm hàng bán lẻ, không thêm thuốc kê đơn |
| TG | UC70 | Thu tiền tại quầy | Cần ca thu ngân đang mở của chính mình; tiền mặt hoặc chuyển khoản, thu đủ một lần, thu gộp Order cùng khách; giao hàng sau khi thu |
| TG | UC71 | Mở / chốt ca thu ngân | Ca thường trong giờ mở cửa; ca ngoài giờ ở chi nhánh có cờ cấp cứu; chốt bằng số tiền thực đếm |
| KO | UC75 | Xem tồn kho & thiết lập tồn tối thiểu | Chỉ xem |
| TB | UC87 | Thực hiện Care Task | Gọi điện nhắc tái chủng, tái khám, quá hạn đón; ghi kết quả hoặc hủy kèm lý do |
| NS | UC35 | Xem lịch làm việc của tôi | *(GĐ2)* Ca làm việc được phân |
| NS | UC36 | Gửi / rút yêu cầu nghỉ, đổi ca | *(GĐ2)* Quản lý chi nhánh duyệt (UC37) |
| LH | UC43 | Đặt lịch khám tại nhà | *(GĐ2)* Đặt hộ khách |
| KB | UC50 | Phẫu thuật | *(GĐ2)* Lập cam kết phẫu thuật với khách |
| BH | UC65 | Xử lý & giao đơn online | *(GĐ2)* Xử lý, bàn giao, cập nhật trạng thái, ghi nhận giao thất bại |
| BH | UC68 | Tạo yêu cầu trả hàng | *(GĐ2)* Quản lý chi nhánh duyệt (UC69) |
| DG | UC85 | Gửi & theo dõi khiếu nại | *(GĐ2)* Tạo khiếu nại thay khách |

##### A07 – Bác sĩ thú y (VET) · 22 use case (GĐ1: 14, GĐ2: 8)

Gồm 6 use case chung ở trên (UC06 gồm hồ sơ giới thiệu công khai: ảnh, chuyên môn, mô tả ngắn) và các use case sau:

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| KH | UC23 | Tra cứu khách & thú cưng | Theo số điện thoại, họ tên, email, tên thú |
| TN | UC46 | Xem hàng đợi của tôi & gọi lượt | Chỉ gọi lượt nhóm Khám/Tiêm được gán cho mình |
| TN | UC47 | Xác định ca cấp cứu | Cùng lễ tân đánh dấu ca cấp cứu |
| KB | UC48 | Khám bệnh | Khám, chẩn đoán, xét nghiệm, kê đơn (thiếu tồn: giảm số lượng hoặc mua ngoài), hẹn tái khám (xa nhất 180 ngày), hoàn tất lượt |
| KB | UC49 | Tiêm chủng | Chọn loại vaccine theo phác đồ đúng loài rồi sản phẩm còn tồn; được chọn mũi khác gợi ý; xóa mũi ghi nhầm khi lượt còn mở |
| KB | UC53 | Xem & bổ sung bệnh án | Chỉ bác sĩ được gán lượt mới ghi bệnh án; bệnh án đã khóa chỉ thêm bản bổ sung |
| LT | UC57 | Ghi nhật ký chăm sóc | Thú đang lưu trú tại chi nhánh |
| BH | UC67 | Thêm dịch vụ / hàng vào Order | Thêm dịch vụ và thuốc (qua kê đơn) cho lượt mình phụ trách |
| NS | UC35 | Xem lịch làm việc của tôi | *(GĐ2)* Ca làm việc được phân |
| NS | UC36 | Gửi / rút yêu cầu nghỉ, đổi ca | *(GĐ2)* Quản lý chi nhánh duyệt (UC37) |
| KB | UC50 | Phẫu thuật | *(GĐ2)* Phát sinh từ lượt khám (UC48); lễ tân lập cam kết |
| KB | UC51 | Khám tại nhà | *(GĐ2)* Thực hiện lịch khám tại nhà (UC43) |
| LT | UC55 | Nhập / xuất viện | *(GĐ2)* Gồm chuyển thú từ lưu trú sang điều trị |
| LT | UC56 | Điều trị nội trú | *(GĐ2)* Ra y lệnh, theo dõi hậu phẫu |
| KO | UC79 | Truy vết lô hàng | *(GĐ2)* Cùng quản lý chi nhánh |
| BV | UC80 | Soạn & gửi duyệt bài viết | *(GĐ2)* Quản lý chuỗi duyệt (UC81) |

##### A08 – Nhân viên chăm sóc (CARETAKER) · 15 use case (GĐ1: 13, GĐ2: 2)

Gồm 6 use case chung ở trên và các use case sau:

| Phân hệ | Mã | Use case | Phạm vi quyền / ghi chú |
|---|---|---|---|
| KH | UC23 | Tra cứu khách & thú cưng | Theo số điện thoại, họ tên, email, tên thú |
| TN | UC46 | Xem hàng đợi của tôi & gọi lượt | Chỉ gọi lượt nhóm Thẩm mỹ được gán cho mình |
| KB | UC52 | Thực hiện dịch vụ thẩm mỹ | Tắm, cắt tỉa; hoàn tất lượt |
| KB | UC53 | Xem & bổ sung bệnh án | Chỉ xem, không được sửa hay bổ sung |
| LT | UC57 | Ghi nhật ký chăm sóc | Ăn, uống, vệ sinh, hoạt động, ghi chú, tối đa 5 ảnh; sửa được trong 1 giờ; đánh dấu bất thường |
| LT | UC59 | Nhận / trả thú cưng lưu trú | Chỉ nhận thú, không trả thú |
| BH | UC67 | Thêm dịch vụ / hàng vào Order | Thêm dịch vụ thẩm mỹ |
| NS | UC35 | Xem lịch làm việc của tôi | *(GĐ2)* Ca làm việc được phân |
| NS | UC36 | Gửi / rút yêu cầu nghỉ, đổi ca | *(GĐ2)* Quản lý chi nhánh duyệt (UC37) |

##### Tác nhân hệ thống

- **S01 – Hệ thống:** 21 tác vụ tự động (GĐ1: 12, GĐ2: 9), chi tiết ở mục 2.2.6.
- **S02 – Cổng thanh toán:** tham gia UC63 Thanh toán online cùng các tác vụ ST11 Hết hạn giao dịch thanh toán, ST14 Đối soát cổng thanh toán, ST21 Hoàn tiền online (GĐ2).
- **S03 – Dịch vụ Email/Push:** kênh gửi OTP (UC02), nhắc lịch, nhắc tái chủng – tái khám và mọi thông báo của hệ thống (ST03, ST04, ST20).

#### 2.2.6. Tác vụ hệ thống tự động (tác nhân S01)

Tác vụ ghi *(GĐ2)* thực hiện ở giai đoạn 2.

| Mã | Tác vụ | Kích hoạt | Tác nhân liên quan |
|---|---|---|---|
| ST01 | Khóa tạm tài khoản do đăng nhập sai | Sai mật khẩu 5 lần liên tiếp trong 15 phút (UC03) | Người dùng A02–A08: khóa 15 phút, nhận email cảnh báo |
| ST02 | Dọn tài khoản chưa xác thực | Định kỳ; quá 24 giờ chưa xác thực OTP | A01: xóa tài khoản cùng hồ sơ online tạo kèm để đăng ký lại được |
| ST03 | Nhắc lịch hẹn | Định kỳ; trước giờ hẹn 24 giờ | A02 nhận email và thông báo trong ứng dụng |
| ST04 | Nhắc tái chủng và nhắc tái khám | Định kỳ; trước ngày tái chủng 7 ngày, trước ngày tái khám 3 ngày | A02 và hồ sơ có email nhận nhắc; hồ sơ không có email chuyển thành Care Task cho A06 (ST18) |
| ST05 | Chuyển lịch hẹn và đặt chỗ lưu trú sang không đến (NO_SHOW) | Định kỳ; quá giờ hẹn 30 phút, hoặc hết giờ làm việc của ngày nhận thú | A02: tính vào hạn chế đặt online |
| ST06 | Trừ kho theo FEFO | Khi A07 ghi nhận mũi tiêm (UC49); khi A06 thu tiền Order (UC70) | Tồn kho chi nhánh do A05 quản lý |
| ST07 | *(GĐ2)* Trừ vật tư tiêu hao | Khi hoàn tất khám (UC48) hoặc dịch vụ thẩm mỹ (UC52), theo định mức vật tư của dịch vụ | Tồn kho chi nhánh do A05 quản lý |
| ST08 | Cảnh báo tồn dưới ngưỡng, lô sắp hết hạn và đã hết hạn | Hằng ngày | A05 nhận bản tổng hợp |
| ST09 | *(GĐ2)* Tính phí vận chuyển | Khi A02 đặt hàng online (UC62), theo cấu hình phí (UC32) | A02 |
| ST10 | *(GĐ2)* Hủy đơn online chưa thanh toán | Định kỳ | A02 |
| ST11 | *(GĐ2)* Hết hạn giao dịch thanh toán | Định kỳ | A02, S02 |
| ST12 | *(GĐ2)* Tự xác nhận đã nhận hàng | Định kỳ | A02 |
| ST13 | Tự chốt ca thu ngân chưa chốt, cảnh báo Order chờ thanh toán quá hạn | Ca thường: 30 phút sau giờ đóng cửa cuối ngày; ca ngoài giờ: khi đến giờ mở cửa kế tiếp | Ca thu ngân của A06; A05 nhận thông báo |
| ST14 | *(GĐ2)* Đối soát cổng thanh toán | Hằng ngày | S02 |
| ST15 | Đánh dấu lưu trú quá hạn đón (OVERDUE) | Định kỳ; quá giờ trả 12:00 của ngày trả dự kiến | A02 nhận thông báo mỗi ngày; quá hạn từ 1 ngày sinh Care Task cho A06; từ 7 ngày báo A05 |
| ST16 | *(GĐ2)* Leo thang khiếu nại quá hạn | Định kỳ | A05, A04 (người xử lý khiếu nại) |
| ST17 | *(GĐ2)* Tự đóng khiếu nại | Định kỳ | A02 |
| ST18 | Sinh Care Task | Từ ST04 (hồ sơ không có email, quá hạn tái chủng 7 ngày) và ST15 | A06 của chi nhánh phụ trách |
| ST19 | Tự hủy Care Task khi thú cưng đã mất | Khi A02 hoặc A06 đánh dấu thú đã mất (UC24) | Care Task đang mở của A06 bị hủy |
| ST20 | Gửi thông báo và thử lại khi thất bại | Mọi sự kiện cần thông báo | Mọi tác nhân nhận thông báo, qua S03 |
| ST21 | *(GĐ2)* Hoàn tiền online qua cổng thanh toán | Từ UC64 (hủy đơn online) hoặc UC69 (duyệt trả hàng) | A02, S02 |

#### 2.2.7. Luồng nghiệp vụ chính

1. **Khách hàng (A02)** đặt lịch trực tuyến hoặc **lễ tân (A06)** đặt hộ (UC39); hệ thống nhắc lịch trước 24 giờ (ST03).
2. **Lễ tân (A06)** tiếp nhận khách, tạo lượt tiếp nhận (Visit) – hệ thống tự mở Order kèm dòng dịch vụ – và gán bác sĩ (UC44, UC45).
3. **Bác sĩ (A07)** gọi lượt, khám, kê đơn, tiêm chủng; vaccine được trừ kho ngay theo FEFO (UC46, UC48, UC49, ST06).
4. **Bác sĩ (A07)** hoàn tất lượt: bệnh án khóa, lịch hẹn hoàn tất, Order chuyển sang chờ thanh toán.
5. **Lễ tân (A06)** thu tiền trong ca thu ngân; kho trừ hàng, thuốc theo FEFO; giao hàng cho khách (UC70, ST06).
6. **Hệ thống (S01)** nhắc tái chủng, tái khám qua email và thông báo trong ứng dụng, hoặc sinh Care Task để **lễ tân (A06)** gọi điện (ST04, ST18, UC87).
7. **Quản lý chi nhánh (A05)** đối soát ca thu ngân, theo dõi tồn kho và báo cáo chi nhánh; **quản lý chuỗi (A04)** theo dõi báo cáo toàn chuỗi (UC72, UC75, UC89).
8. *(GĐ2)* **Khách hàng (A02)** đặt hàng online và thanh toán qua **cổng thanh toán (S02)**; **lễ tân (A06)** xử lý và giao đơn; hệ thống tính phí vận chuyển, tự hủy đơn chưa thanh toán và tự xác nhận đã nhận hàng (UC61–UC65, ST09–ST12).

### 2.3. Các công nghệ sử dụng

**Kiến trúc tổng thể:**

- Kho mã nguồn gồm 2 ứng dụng triển khai độc lập: Backend (REST API) và Frontend (Single Page Application), giao tiếp qua HTTP/JSON, xác thực bằng JWT Bearer; Frontend build tĩnh, phục vụ qua Nginx và chuyển tiếp `/api` tới Backend.
- Backend theo kiến trúc **modular monolith** tổ chức theo **Domain-Driven Design**: tầng `platform` dùng chung (cấu hình, bảo mật, xử lý ngoại lệ và mã lỗi chuẩn, lớp cơ sở FSM, định dạng phản hồi, ghi audit) và 12 module nghiệp vụ (`identity`, `branch`, `customer`, `catalog`, `appointment`, `visit`, `boarding`, `sales`, `inventory`, `content`, `care`, `report`); giai đoạn 2 bổ sung module nhân sự và module trí tuệ nhân tạo. Mỗi module chỉ công bố package `api/` (interface, dữ liệu trao đổi, sự kiện) cho module khác sử dụng.
- Luồng xử lý một request: lọc trace-id → Spring Security (JWT, phân quyền) → Controller → Service (transaction, kiểm tra quy tắc nghiệp vụ `BR-…`) → bộ xử lý chuyển trạng thái (FSM) → Repository; lỗi được chuẩn hóa tập trung thành phản hồi thống nhất.
- Hệ quả liên module (ví dụ hoàn tất lượt khám kéo theo lịch hẹn và Order) chạy trong **cùng một transaction**, qua gọi interface hoặc sự kiện đồng bộ; thông báo được ghi vào hàng đợi outbox và gửi lại khi thất bại.

**Backend:**

- Ngôn ngữ, framework: Java 21, Spring Boot 3.5 (Spring Web, Spring Data JPA/Hibernate, Spring Security, Bean Validation, Spring AOP, Spring Mail, Actuator).
- Xác thực, bảo mật: JWT (thư viện jjwt) kết hợp quản lý phiên đăng nhập trong CSDL để thu hồi tức thì; OTP qua email; phân quyền theo vai trò (RBAC) và phạm vi chi nhánh.
- Thiết kế: RESTful API, Finite State Machine với danh sách chuyển trạng thái hợp lệ (whitelist); tài liệu API theo chuẩn OpenAPI 3/Swagger UI (springdoc-openapi); hợp đồng API v1 được sinh tự động từ khai báo và kiểm tra bằng công cụ riêng.
- Thư viện hỗ trợ: MapStruct (ánh xạ DTO), Lombok; log có cấu trúc (Logback, Logstash encoder) gắn trace-id cho từng request; ghi audit tập trung qua `AuditRecorder`.

**Frontend:**

- Nền tảng: ReactJS 18, TypeScript, Vite; định tuyến bằng React Router.
- Giao diện: Tailwind CSS 4, Radix UI, lucide-react (icon), GSAP (hiệu ứng chuyển động), Recharts (biểu đồ báo cáo).
- Quản lý state và dữ liệu: TanStack React Query (dữ liệu từ server), Zustand (state phía client), Axios.
- Xử lý form và validation: React Hook Form, Zod.

**Cơ sở dữ liệu và hạ tầng:**

- PostgreSQL 17: 55 bảng tổ chức theo nhóm nghiệp vụ; ràng buộc toàn vẹn ở mức CSDL (CHECK cho trạng thái, unique có điều kiện, ràng buộc EXCLUDE chống chồng ngày lưu trú, khóa ngoại RESTRICT không xóa dây chuyền); trigger bảo đảm nhật ký audit chỉ được thêm mới.
- Flyway: quản lý migration schema cơ sở dữ liệu.
- Redis 7: đã khai báo trong hạ tầng (Docker Compose, Spring Data Redis); vai trò cụ thể (bộ nhớ đệm, hỗ trợ kiểm soát truy cập đồng thời) sẽ được chốt bằng ADR khi triển khai.
- SMTP: gửi email OTP và thông báo.
- Đóng gói và triển khai: Docker, Docker Compose (PostgreSQL, Redis, Backend, Frontend/Nginx).
- Quản lý mã nguồn: Git, GitHub; tích hợp liên tục bằng GitHub Actions.

**Tích hợp thanh toán trực tuyến (giai đoạn 2):**

- Cổng thanh toán VNPay, ZaloPay hoặc MoMo (môi trường sandbox): thanh toán đơn hàng online, xác thực kết quả giao dịch trả về, hoàn tiền và đối soát giao dịch.

**Trí tuệ nhân tạo (giai đoạn 2):**

- Kiến trúc Retrieval-Augmented Generation (RAG) trên tri thức chăm sóc thú cưng và lịch tiêm phòng.
- Mô hình ngôn ngữ lớn (LLM) cho chatbot tư vấn.
- Recommendation Engine phục vụ đề xuất dịch vụ và sản phẩm theo đặc điểm thú cưng.

**Kiểm thử và đảm bảo chất lượng:**

- Backend: JUnit 5, Mockito, Spring Security Test; Testcontainers (chạy trên PostgreSQL 17 thật với migration thật); bộ test FSM tự sinh kiểm tra mọi cặp trạng thái hợp lệ/không hợp lệ theo bảng chuyển trạng thái; integration test các chuỗi tác động liên module; kiểm thử end-to-end luồng nghiệp vụ chính.
- Frontend: Vitest, Testing Library, Playwright (end-to-end).
- Quy trình: bộ quy ước code backend thống nhất (cấu trúc package, phân tầng và DTO, đặt tên, xử lý ngoại lệ, FSM, validation, transaction, logging và audit, kiểm thử); quyết định kỹ thuật ghi lại bằng ADR (Architecture Decision Record).

### 2.4. Phân công công việc

Mục này phân công các thành viên nhóm phát triển phần mềm. Tác nhân sử dụng và quản lý trực tiếp từng phân hệ trong hệ thống được trình bày ở mục 2.2.3 và 2.2.5.

#### 2.4.1. Nguyên tắc phân công

- Mỗi module nghiệp vụ có **đúng một thành viên backend phụ trách phát triển**: thiết kế chi tiết, cài đặt API và quy tắc nghiệp vụ, máy trạng thái, tác vụ định kỳ và kiểm thử của module. Module khác chỉ truy cập qua interface hoặc sự kiện mà module đó công bố.
- Backend chia theo trục nghiệp vụ: **BE-1 – Định danh & luồng khám**; **BE-2 – Dữ liệu nền & thương mại**. Bên cung cấp dữ liệu nền (BE-2: Danh mục → Chi nhánh → Khách & thú cưng → Kho → Order lõi) làm trước để BE-1 kịp sử dụng cho Lịch hẹn, Tiếp nhận và Tiêm chủng.
- Frontend: một thành viên phụ trách giao diện của toàn bộ các phân hệ, làm trên mock API theo hợp đồng API v1 và nối API thật khi backend hoàn thành từng module.
- Giai đoạn 2: use case tầng 3 của mỗi module do thành viên phụ trách module đó thực hiện; riêng nội trú (gần nghiệp vụ khám) và khiếu nại (gần chăm sóc khách hàng) giao BE-1 để cân bằng khối lượng. Phân hệ Nhân sự giao BE-1 như đề cương ban đầu; phân hệ trí tuệ nhân tạo do cả nhóm thực hiện.

#### 2.4.2. Bảng phân công phát triển module

| Mã | Module | Tầng | Module backend | Thành viên phụ trách backend | Thành viên phụ trách frontend | FSM thuộc module | Tuần thực hiện |
|---|---|:---:|---|---|---|---|:---:|
| TK | Tài khoản & xác thực | 1 | `identity` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Tài khoản (8 chuyển) | W1 |
| QT | Quản trị hệ thống | 1 | `identity` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Dùng chung FSM Tài khoản | W2 |
| LH | Lịch hẹn | 1 | `appointment` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Lịch hẹn (8 chuyển) | W2–W3; GĐ2 |
| TN | Tiếp nhận & hàng đợi | 1 | `visit` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Visit (6 chuyển) | W3 |
| KB | Khám & điều trị (bệnh án, kê đơn, tiêm chủng, tái khám, thẩm mỹ) | 1 | `visit` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Dùng chung FSM Visit | W3–W4; GĐ2 |
| TB | Chăm sóc khách hàng & thông báo | 1 | `care` | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | Care Task (6 chuyển) | W1, W4 |
| CN | Chi nhánh | 1 | `branch` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | Chi nhánh (2 chuyển) | W1; GĐ2 |
| SP | Sản phẩm & dịch vụ | 1 | `catalog` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | — | W1; GĐ2 |
| KH | Khách hàng & thú cưng | 1 | `customer` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | — | W2; GĐ2 |
| BH | Bán hàng & đơn hàng | 1 | `sales` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | Order (10 chuyển) | W2–W3; GĐ2 |
| TG | Thu ngân | 1 | `sales` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | Ca thu ngân (6 chuyển) | W3; GĐ2 |
| CK | Thông tin công khai | 1 | `catalog`, `branch`, `content` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | — | W3 |
| KO | Kho | 2 | `inventory` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | Phiếu nhập kho (4 chuyển) | W2; GĐ2 |
| LT | Lưu trú & nội trú | 2 | `boarding` | **Phạm Quang Dũng** (BE-2); nội trú: **Nguyễn Đức Mạnh** (GĐ2) | Bùi Văn Hiến | Đặt chỗ lưu trú (11 chuyển), Chuồng (5 chuyển) | W3–W4; GĐ2 |
| BV | Bài viết | 2 | `content` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | — | W3; GĐ2 |
| DG | Feedback & khiếu nại | 2 | `content` | **Phạm Quang Dũng** (BE-2); khiếu nại: **Nguyễn Đức Mạnh** (GĐ2) | Bùi Văn Hiến | — | W3; GĐ2 |
| BC | Báo cáo | 2 | `report` | **Phạm Quang Dũng** (BE-2) | Bùi Văn Hiến | — | W4 |
| NS | Nhân sự (ca làm, nghỉ phép) | 3 | Module mới | **Nguyễn Đức Mạnh** (BE-1) | Bùi Văn Hiến | — | GĐ2 |
| AI | Trí tuệ nhân tạo & đề xuất thông minh | — | Module mới | Cả nhóm | Bùi Văn Hiến | — | GĐ2 |

*Ghi chú:* CK là phần hiển thị công khai nên lấy dữ liệu từ nhiều module; riêng API hồ sơ bác sĩ công khai nằm trong module `identity` do BE-1 phụ trách.

**Tổng hợp theo thành viên – giai đoạn 1:**

| Thành viên | Vai trò | Module phụ trách phát triển | Module backend | FSM | Endpoint (API v1) |
|---|---|---|---|:---:|:---:|
| Bùi Văn Hiến | Nhóm trưởng – FE-1 | Giao diện của cả 17 module (4 cổng: Công khai, Khách hàng, Nhân viên, Quản trị) | — | — | Tích hợp toàn bộ 230 |
| Nguyễn Đức Mạnh | BE-1 | 6 module: TK, QT, LH, TN, KB, TB | `identity`, `appointment`, `visit`, `care` và nền tảng `platform` | 4 FSM – 28 chuyển | 77 |
| Phạm Quang Dũng | BE-2 | 11 module: CN, SP, KH, BH, TG, KO, LT, CK, BV, DG, BC | `branch`, `catalog`, `customer`, `sales`, `inventory`, `boarding`, `content`, `report` | 6 FSM – 38 chuyển | 153 |

**Phân công giai đoạn 2 (tầng 3 và trí tuệ nhân tạo):**

| Thành viên | Use case bổ sung | Tác vụ tự động |
|---|---|---|
| Bùi Văn Hiến | Giao diện của toàn bộ chức năng giai đoạn 2, gồm chatbot và khu vực gợi ý | — |
| Nguyễn Đức Mạnh | 12 use case: Nhân sự (UC34–UC38); đặt lịch và khám tại nhà (UC43, UC51); phẫu thuật (UC50); nhập/xuất viện, điều trị nội trú (UC55, UC56); khiếu nại (UC85, UC86) | ST07, ST16, ST17 |
| Phạm Quang Dũng | 15 use case: thương mại điện tử và thanh toán online (UC61–UC65); trả hàng – hoàn tiền (UC68, UC69); tạm ngừng/đóng cửa chi nhánh (UC13); gộp hồ sơ khách (UC27); phí vận chuyển, phí khám tại nhà (UC32) và định mức vật tư (thuộc UC30); chuyển kho, kiểm kê, truy vết lô (UC77–UC79); bài viết của bác sĩ, bình luận (UC80, UC82) | ST09–ST12, ST14, ST21 |
| Cả nhóm | Phân hệ trí tuệ nhân tạo: chatbot tư vấn (RAG + LLM), gợi ý cá nhân hóa; đặc tả bổ sung quy tắc, máy trạng thái, mô hình dữ liệu, API cho tầng 3 | — |

#### 2.4.3. Nhiệm vụ chi tiết của từng thành viên

**Công việc chung của cả nhóm**

- Phân tích nghiệp vụ, biên soạn và review bộ đặc tả (use case, quy tắc nghiệp vụ, máy trạng thái, mô hình miền, ERD); duyệt các quy tắc đề xuất mới và giá trị mặc định của tham số cấu hình.
- Chốt kiến trúc, quy ước code, ranh giới 17 module; thống nhất hợp đồng giữa các module backend (interface, sự kiện) và hợp đồng API v1 giữa frontend – backend.
- Giai đoạn 2: đặc tả bổ sung quy tắc nghiệp vụ, máy trạng thái, mô hình dữ liệu, API cho các use case tầng 3; nghiên cứu và xây dựng phân hệ trí tuệ nhân tạo (chatbot RAG + LLM, gợi ý cá nhân hóa).
- Review chéo mã nguồn, kiểm thử tích hợp, tổng duyệt demo và hoàn thiện báo cáo đồ án.

**1. Bùi Văn Hiến – B22DCCN292 (Nhóm trưởng) — Frontend (FE-1): toàn bộ giao diện và trải nghiệm người dùng**

- Kiến trúc frontend: khởi tạo dự án (ReactJS, TypeScript, Vite), design system, router và chặn route theo 7 vai trò; 4 bố cục giao diện Công khai / Khách hàng / Nhân viên / Quản trị; lớp mock API theo hợp đồng API v1 để làm song song với backend.
- Cổng công khai và khách hàng: trang chủ, dịch vụ, chi nhánh, bác sĩ, sản phẩm, bài viết; đăng ký, xác thực OTP, đăng nhập, quên/đổi mật khẩu; hồ sơ cá nhân, sổ địa chỉ, liên kết hồ sơ; quản lý thú cưng và hồ sơ sức khỏe; đặt lịch theo khung giờ trống, lịch hẹn của tôi; đặt chỗ lưu trú, xem nhật ký chăm sóc; trung tâm thông báo; gửi feedback.
- Cổng nhân viên: lễ tân (tiếp nhận và điều phối hàng đợi, đặt lịch hộ, hồ sơ khách tại quầy, bán lẻ tại quầy, thu tiền gộp Order, mở/chốt ca, nhận/trả thú, Care Task); bác sĩ (hàng đợi của tôi, màn hình khám – bệnh án, kê đơn và mua ngoài, tiêm chủng, hẹn tái khám); nhân viên chăm sóc (dịch vụ thẩm mỹ, nhận thú, nhật ký chăm sóc).
- Cổng quản trị: chi nhánh (giờ mở cửa, ngày nghỉ, dịch vụ tại chi nhánh, quota); danh mục sản phẩm, dịch vụ, loại chuồng, loại vaccine và phác đồ; tài khoản nhân viên, khóa/mở khóa, tham số cấu hình, mẫu thông báo, nhật ký audit; kho (nhà cung cấp, phiếu nhập, tồn theo lô và hạn dùng, điều chỉnh tồn, tồn tối thiểu); chuồng lưu trú; đối soát ca thu ngân; nội dung trang và bài viết; feedback; báo cáo và dashboard.
- Tích hợp và hoàn thiện: nối toàn bộ API thật của 2 backend; quản lý state và cache (React Query, Zustand); xử lý lỗi theo bộ mã lỗi chuẩn; tối ưu responsive; kiểm thử giao diện (Vitest, Playwright).
- Vai trò nhóm trưởng: điều phối chung, theo dõi tiến độ, tổ chức tổng duyệt demo.
- Giai đoạn 2: giao diện cửa hàng online (giỏ hàng, đặt hàng, thanh toán, đơn hàng của tôi), xử lý giao đơn và trả hàng; lịch làm việc, nghỉ phép; khám tại nhà, phẫu thuật, nội trú; chuyển kho, kiểm kê, truy vết lô; bài viết của bác sĩ, bình luận; khiếu nại; chatbot tư vấn và khu vực gợi ý dịch vụ, sản phẩm.

**2. Nguyễn Đức Mạnh – B22DCCN521 — Backend 1 (BE-1): Định danh & luồng khám**

- Nền tảng backend dùng chung (`platform`): khởi tạo dự án, cấu hình hạ tầng (CORS, OpenAPI/Swagger, trace-id, log có cấu trúc), xử lý ngoại lệ và bộ mã lỗi chuẩn, lớp cơ sở FSM và bộ test FSM; migration schema CSDL V1 (55 bảng) kèm kiểm thử đối chiếu schema với ERD; hạ tầng ghi audit; quy ước code backend và các ADR.
- Bảo mật: xác thực JWT và phiên đăng nhập, phân quyền 7 vai trò kết hợp phạm vi chi nhánh, đọc tham số cấu hình dùng chung cho mọi module.
- **Tài khoản & xác thực (TK):** đăng ký, gửi/gửi lại/xác thực OTP; hàng đợi gửi email và thử lại (ST20); dọn tài khoản chưa xác thực (ST02); đăng nhập/đăng xuất, khóa tạm khi đăng nhập sai (ST01), quên/đổi mật khẩu, bắt đổi mật khẩu lần đầu; hồ sơ cá nhân, sổ địa chỉ, hồ sơ công khai của bác sĩ, liên kết hồ sơ khách có sẵn.
- **Quản trị hệ thống (QT):** tài khoản nhân viên theo phân cấp (tạo, đổi chức vụ, điều chuyển, vô hiệu hóa/kích hoạt lại, sửa email); khóa/mở khóa; tham số cấu hình có khoảng hợp lệ; mẫu thông báo (khôi phục mặc định); xem audit; thông báo trong ứng dụng.
- **Lịch hẹn (LH):** sinh khung 30 phút, tính quota, đặt/đổi/hủy lịch; chuyển NO_SHOW (ST05); hạn chế đặt online và gỡ hạn chế sớm; nhắc lịch (ST03); xử lý sự kiện hủy hàng loạt (ngày nghỉ, thú mất, chuyển chủ).
- **Tiếp nhận & hàng đợi (TN):** Visit (check-in, khách không hẹn, cấp cứu ngoài giờ), thứ tự hàng đợi, gán/gán lại nhân viên, gọi lượt, hủy lượt khách bỏ về.
- **Khám & điều trị (KB):** bệnh án, chẩn đoán, ghi chú nội bộ, hoàn tất lượt theo loại dịch vụ, khóa bệnh án và bản bổ sung; dịch vụ thẩm mỹ; tiêm chủng (trừ kho FEFO qua dịch vụ kho, dòng Order vaccine, ngày tái chủng, xóa mũi ghi nhầm); kê đơn (thiếu tồn thì giảm số lượng hoặc mua ngoài); hẹn tái khám; cung cấp dữ liệu bệnh án, mũi tiêm cho hồ sơ sức khỏe và interface kiểm tra "đủ mũi bắt buộc" cho phân hệ Lưu trú.
- **Chăm sóc khách hàng & thông báo (TB):** Care Task; nhắc tái chủng, tái khám (ST04); sinh Care Task (ST18); hủy Care Task khi thú mất (ST19); hủy nhắc khi thú đã tiêm lại hoặc đã đặt lịch khám.
- Kiểm thử: integration test FSM Tài khoản, Lịch hẹn, Visit, Care Task (đủ cặp hợp lệ/không hợp lệ); nối thật các interface chéo; kiểm thử end-to-end luồng chính đặt lịch → tiếp nhận → khám/tiêm → Order → thu tiền → nhắc tái chủng.
- Giai đoạn 2: phân hệ Nhân sự (ca làm việc, lịch làm việc, nghỉ phép – đổi ca, vắng đột xuất); đặt lịch và khám tại nhà; phẫu thuật; nhập/xuất viện và điều trị nội trú; khiếu nại, leo thang và tự đóng khiếu nại (ST16, ST17); trừ vật tư tiêu hao theo định mức (ST07).

**3. Phạm Quang Dũng – B22DCCN136 — Backend 2 (BE-2): Dữ liệu nền & thương mại**

- Hợp đồng và hạ tầng tích hợp: hợp đồng API v1 giữa frontend – backend (12 module, 230 endpoint, đặc tả OpenAPI sinh tự động kèm công cụ kiểm tra); bộ interface giao tiếp giữa các module backend; khung phát/nhận sự kiện liên module trong cùng transaction.
- **Sản phẩm & dịch vụ (SP):** danh mục sản phẩm, sản phẩm (thuốc kê đơn, quản lý hạn dùng), dịch vụ (nhóm, loại Khám/Tiêm), loại chuồng, loại vaccine và phác đồ tiêm chủng.
- **Chi nhánh (CN):** tạo/kích hoạt chi nhánh, giờ mở cửa, ngày nghỉ và thu hẹp giờ (phát sự kiện hủy hàng loạt), cờ cấp cứu ngoài giờ; bật/tắt dịch vụ tại chi nhánh, quota mặc định và quota từng khung giờ.
- **Khách hàng & thú cưng (KH):** hồ sơ khách tại quầy, tra cứu và cảnh báo nghi trùng; thú cưng (khóa loài, cân nặng); đánh dấu đã mất và chuyển chủ (phát sự kiện cho các module liên quan); xóa thú chưa phát sinh giao dịch.
- **Kho (KO):** nhà cung cấp; tồn theo lô và lịch sử biến động; phiếu nhập kho; dịch vụ xuất FEFO và hoàn lô (cung cấp cho Thu ngân và Tiêm chủng); điều chỉnh tồn; tồn tối thiểu; cảnh báo tồn và hạn dùng (ST08).
- **Bán hàng & đơn hàng (BH):** Order lõi (FSM, chốt giá theo dòng, quyền thêm/xóa dòng) và API cho luồng khám (mở Order cho Visit, thêm dòng thuốc/vaccine, chuyển trạng thái); Order bán lẻ tại quầy; quản lý chi nhánh hủy Order chờ thanh toán.
- **Thu ngân (TG):** ca thường và ca ngoài giờ, thu tiền gộp kèm trừ kho FEFO, đối soát ca, tự chốt ca và cảnh báo Order chờ thanh toán quá hạn (ST13).
- **Lưu trú (LT):** chuồng, đặt chỗ theo loại chuồng (sức chứa theo đêm, chốt giá), gia hạn, hủy, NO_SHOW (ST05); nhận thú (kiểm tra mũi bắt buộc qua interface của BE-1, chuồng đúng loại, cân nặng); nhật ký chăm sóc và báo bất thường; trả thú → Order lưu trú, giao thú khi đã thanh toán, hủy phiên trả thú, thú mất; quá hạn đón (ST15, phát sinh Care Task).
- **Thông tin công khai, Bài viết, Feedback (CK, BV, DG):** API trang công khai (dịch vụ, chi nhánh, sản phẩm còn hàng theo chi nhánh), nội dung trang, chuyên mục và bài viết, feedback.
- **Báo cáo (BC):** báo cáo chi nhánh và toàn chuỗi; mỗi module công bố số liệu tổng hợp của mình, module Báo cáo kiểm tra phạm vi, kỳ báo cáo và ghép kết quả.
- Kiểm thử: integration test FSM Chi nhánh, Order, Ca thu ngân, Phiếu nhập kho, Đặt chỗ lưu trú, Chuồng; kiểm thử end-to-end luồng bán lẻ và luồng lưu trú.
- Giai đoạn 2: thương mại điện tử (giỏ hàng, đặt hàng online, phí vận chuyển, xử lý và giao đơn); tích hợp cổng thanh toán VNPay, ZaloPay hoặc MoMo, hết hạn giao dịch, đối soát cổng; tự hủy đơn chưa thanh toán, tự xác nhận đã nhận hàng (ST09–ST12, ST14); trả hàng – hoàn tiền (ST21); chuyển kho, kiểm kê, truy vết lô; tạm ngừng/đóng cửa chi nhánh; gộp hồ sơ khách trùng; phí khám tại nhà, định mức vật tư; bài viết của bác sĩ và bình luận.

#### 2.4.4. Ranh giới phối hợp giữa các thành viên

Các điểm module của hai thành viên backend gọi nhau. Bên cung cấp chịu trách nhiệm interface và quy tắc nghiệp vụ của mình; mọi hệ quả chạy trong cùng một transaction với thao tác gốc.

| Điểm phối hợp | Bên gọi | Bên cung cấp | Nội dung |
|---|---|---|---|
| Tiếp nhận khách | TN – Mạnh | BH – Dũng | Tạo Visit đồng thời mở Order nguồn lượt khám kèm dòng dịch vụ tự sinh |
| Hoàn tất / hủy lượt | TN, KB – Mạnh | BH – Dũng | Order chuyển chờ thanh toán khi hoàn tất, chuyển hủy khi hủy lượt |
| Tiêm chủng | KB – Mạnh | KO, BH – Dũng | Khóa lô FEFO, trừ kho vaccine, sinh dòng Order vaccine; hoàn kho khi xóa mũi ghi nhầm |
| Kê đơn | KB – Mạnh | KO, BH – Dũng | Kiểm tra tồn khả dụng; sinh dòng Order thuốc hoặc đánh dấu mua ngoài |
| Thu tiền thiếu thuốc | TG – Dũng | KB – Mạnh | Thuốc kê đơn thiếu tồn lúc thu được chuyển sang mua ngoài trên đơn thuốc |
| Nhận thú lưu trú | LT – Dũng | KB – Mạnh | Kiểm tra mũi tiêm bắt buộc khi lưu trú, chỉ tính mũi tiêm tại hệ thống |
| Quá hạn đón thú | LT – Dũng | TB – Mạnh | Sinh Care Task gọi điện cho lễ tân chi nhánh lưu trú |
| Đặt lịch, đặt chỗ | LH – Mạnh; LT – Dũng | CN, SP, KH – Dũng; LH – Mạnh | Tra giờ mở cửa, quota, dịch vụ đang bật, thông tin thú; hạn chế đặt online dùng chung cho lịch hẹn và lưu trú |
| Vô hiệu hóa, điều chuyển nhân viên | QT – Mạnh | TG, CN – Dũng; TN – Mạnh | Chặn khi nhân viên còn ca thu ngân đang mở, còn lượt khám dở dang, hoặc là quản lý cuối cùng của chi nhánh |
| Thú mất, chuyển chủ | KH – Dũng (phát sự kiện) | LH, KB, TB – Mạnh; LT – Dũng | Hủy lịch hẹn và đặt chỗ chưa thực hiện; hủy hoặc chuyển Care Task; hủy nhắc tái khám |
| Ngày nghỉ, thu hẹp giờ mở cửa | CN – Dũng (phát sự kiện) | LH – Mạnh; LT – Dũng | Hủy hàng loạt lịch hẹn, đặt chỗ bị ảnh hưởng và thông báo khách |
| Báo cáo | BC – Dũng | `sales`, `inventory`, `content` – Dũng; `appointment`, `visit` – Mạnh | Mỗi module tính số liệu tổng hợp của mình; module Báo cáo chỉ ghép |
| Trừ vật tư tiêu hao (GĐ2) | KB – Mạnh | SP, KO – Dũng | Hoàn tất khám, thẩm mỹ thì trừ kho vật tư theo định mức của dịch vụ |
| Khám tại nhà (GĐ2) | LH, KB – Mạnh | SP – Dũng | Lấy phí khám tại nhà từ cấu hình phí |
| Nhập viện từ lưu trú (GĐ2) | LT (nội trú) – Mạnh | LT (lưu trú) – Dũng | Chuyển thú đang lưu trú sang điều trị nội trú |

Quy tắc phối hợp: bên gọi viết unit test trên interface (mock) mà không chờ bên cung cấp; bên cung cấp chưa cài đặt thì đưa lớp placeholder báo lỗi rõ ràng thay vì trả giá trị giả; mọi thay đổi chữ ký interface phải báo bên kia trước khi merge.

#### 2.4.5. Kế hoạch thực hiện

| Giai đoạn | Thời gian | BE-1 – Nguyễn Đức Mạnh | BE-2 – Phạm Quang Dũng | FE-1 – Bùi Văn Hiến | Mốc |
|---|---|---|---|---|---|
| GĐ 0 – Thiết kế & khởi tạo | 05–06/10/2026 | Khung dự án backend, schema CSDL V1, bảo mật, audit, tham số cấu hình | Hợp đồng API v1, interface giữa các module, khung sự kiện liên module | Khung dự án frontend, design system, router, mock API | M0 (06/10): hợp đồng giữa module, khung BE/FE, schema |
| W1 – Nền tảng | 07–09/10/2026 | Tài khoản & xác thực | Danh mục sản phẩm – dịch vụ, Chi nhánh | Màn hình tài khoản, hồ sơ cá nhân | M1 (09/10) |
| W2 – Dữ liệu nền & Lịch hẹn | 12–16/10/2026 | Quản trị hệ thống, Lịch hẹn | Khách hàng & thú cưng, Kho, Order lõi | Chi nhánh, danh mục, quản trị, khách hàng, kho | M2 (16/10) |
| W3 – Luồng khám & bán hàng | 19–23/10/2026 | Hoàn thiện Lịch hẹn, Tiếp nhận & hàng đợi, Khám, Tiêm chủng | Bán lẻ, Thu ngân, Đặt chỗ lưu trú, Thông tin công khai – Bài viết – Feedback | Đặt lịch, bán lẻ tại quầy, thu ngân, hàng đợi, màn hình khám | M3 (23/10) |
| W4 – Lưu trú, CSKH, Báo cáo | 26–29/10/2026 | Kê đơn, hẹn tái khám, Care Task và nhắc; test FSM, end-to-end luồng chính | Nhận/trả thú, nhật ký chăm sóc, Báo cáo, cảnh báo kho; test FSM, end-to-end | Kê đơn, trang công khai, nội dung, lưu trú, báo cáo; nối API thật toàn bộ | M4 (30/10): sẵn sàng demo |
| Demo giai đoạn 1 | 30/10/2026 | Cả nhóm | Cả nhóm | Cả nhóm | Tổng duyệt demo GĐ1 |
| GĐ2 – Tầng 3 & AI | 02/11–11/12/2026 | Đặc tả bổ sung; Nhân sự; đặt lịch và khám tại nhà, phẫu thuật; nội trú; khiếu nại; trừ vật tư tiêu hao; phân hệ AI (cả nhóm) | Đặc tả bổ sung; thương mại điện tử, thanh toán online, trả hàng – hoàn tiền; chuyển kho, kiểm kê, truy vết lô; chi nhánh, hồ sơ khách, bài viết mở rộng; phân hệ AI (cả nhóm) | Giao diện các chức năng GĐ2, chatbot và gợi ý | M5 (11/12): hoàn thành tầng 3 và AI |
| Hoàn thiện đồ án | 14–31/12/2026 | Cả nhóm | Cả nhóm | Cả nhóm | Kiểm thử tổng thể, hoàn thiện sản phẩm, viết báo cáo đồ án |

---

## 3. Cơ sở dữ liệu ban đầu

1. Eric Evans, *Domain-Driven Design: Tackling Complexity in the Heart of Software*, Addison-Wesley.
2. Vaughn Vernon, *Implementing Domain-Driven Design*, Addison-Wesley.
3. Tài liệu chính thức Spring Boot: https://spring.io/projects/spring-boot
4. Tài liệu chính thức React: https://react.dev
5. Tài liệu chính thức PostgreSQL: https://www.postgresql.org/docs/
6. Tài liệu chính thức Redis: https://redis.io/docs/
7. Đặc tả OpenAPI: https://spec.openapis.org/oas/latest.html
8. Tài liệu TanStack Query: https://tanstack.com/query/latest
9. Tài liệu Testcontainers for Java: https://java.testcontainers.org/
10. Bộ tài liệu đặc tả nội bộ của nhóm (phiên bản v16): Use Case Catalog, Business Rules, State Machine, Domain Model, ERD, hợp đồng giữa các module, hợp đồng API v1, quy ước code backend và ADR (thư mục `docs/` trong kho mã nguồn của đồ án).

---

## 4. Ngày giao đề tài: 09/2026

## 5. Ngày hoàn thiện (dự kiến): 12/2026

---

*Hà Nội, ngày …… tháng …… năm 2026*

| GIẢNG VIÊN HƯỚNG DẪN | SINH VIÊN | | |
|:---:|:---:|:---:|:---:|
| *(Ký, ghi rõ họ tên)* | *(Ký, ghi rõ họ tên)* | | |
| | | | |
| **Vũ Văn Thỏa** | **Bùi Văn Hiến** | **Phạm Quang Dũng** | **Nguyễn Đức Mạnh** |

---

## Phụ lục: Điều chỉnh so với bản đề cương trước

| Hạng mục | Bản đề cương trước | Bản cập nhật |
|---|---|---|
| Căn cứ xác định phạm vi | 7 phân hệ chức năng, triển khai theo kế hoạch 25 module ban đầu | Bộ đặc tả nghiệp vụ v16: 18 module, 85 use case, 21 tác vụ tự động; giai đoạn 1 triển khai tầng 1–2 (17 module, 58 use case, 12 tác vụ), giai đoạn 2 triển khai tầng 3 (27 use case, 9 tác vụ) và phân hệ AI |
| Tác nhân và use case | 5 vai trò: Khách hàng, Nhân viên, Kỹ thuật viên/Bác sĩ, Quản lý chi nhánh, Quản trị viên hệ thống | 8 tác nhân người (thêm Khách vãng lai, Quản lý chuỗi; tách "Nhân viên", "Kỹ thuật viên/Bác sĩ" thành Lễ tân kiêm thu ngân, Bác sĩ thú y, Nhân viên chăm sóc) và 3 tác nhân hệ thống; xác định tác nhân quản lý trực tiếp từng phân hệ (mục 2.2.3) và use case của từng tác nhân (mục 2.2.5) |
| Nghiệp vụ bổ sung | — | Lưu trú (chuồng, đặt chỗ, nhật ký chăm sóc); thu ngân theo ca và đối soát; Care Task chăm sóc khách; nhắc tái khám; tiêm chủng theo loại vaccine và phác đồ; cấp cứu ngoài giờ; quota theo khung giờ; trang công khai, bài viết, feedback; giai đoạn 2: khám tại nhà, phẫu thuật, điều trị nội trú, kiểm kê, truy vết lô, khiếu nại, bình luận bài viết |
| Chia giai đoạn | Không chia giai đoạn | Thanh toán trực tuyến (VNPay, ZaloPay, MoMo), giỏ hàng và đơn online, trả hàng – hoàn tiền, điều chuyển hàng giữa chi nhánh, lịch làm việc – phân ca nhân viên thực hiện ở giai đoạn 2 (11–12/2026) |
| Loại khỏi phạm vi | Có trong phân công | Đa tổ chức (Organization), ủy quyền người chăm sóc (Caregiver), Consent & Break-Glass, khách hàng thân thiết – voucher – gói trả trước, quản lý sự cố (Incident), quy trình mua hàng PR/PO Maker-Checker, hóa đơn (Invoice) tách riêng (thay bằng Order và phiếu thu) |
| Trí tuệ nhân tạo | Phân hệ dự kiến trong phạm vi | Giữ trong phạm vi, thực hiện ở giai đoạn 2 (cả nhóm) |
| Phân công backend | BE-1: Auth, IAM, Tổ chức – Chi nhánh, Nhân sự, Lịch hẹn, Hàng đợi, Thông báo, Audit, Sự cố, Báo cáo. BE-2: Khách hàng – thú cưng, Khám bệnh, Tiêm chủng, Grooming, Danh mục, POS, Kho – mua hàng, Tài chính, Loyalty, Event Bridges | BE-1 (Mạnh): TK, QT, LH, TN, KB, TB. BE-2 (Dũng): CN, SP, KH, BH, TG, KO, LT, CK, BV, DG, BC. Thay đổi chính: Chi nhánh và Báo cáo chuyển sang BE-2; Khám bệnh, Tiêm chủng, Thẩm mỹ chuyển sang BE-1. Giai đoạn 2: BE-1 thêm Nhân sự, khám tại nhà, phẫu thuật, nội trú, khiếu nại; BE-2 thêm thương mại điện tử, thanh toán online, trả hàng – hoàn tiền, kho mở rộng |
| Công nghệ | Ant Design; GitLab; Redis lưu session, OTP, khóa slot | Tailwind CSS và Radix UI; GitHub và GitHub Actions; phiên đăng nhập và OTP lưu trong PostgreSQL, vai trò Redis chốt bằng ADR; bổ sung Testcontainers, Playwright |
