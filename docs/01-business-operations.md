# Pet Care Ecosystem — Use Case Catalog (bản gọn, v15)

> Danh sách use case và tác vụ hệ thống của web Pet Care. Gồm **89 mã use case** (UC01–UC89; còn hiệu lực 85 sau khi bỏ UC18, UC19, UC20, UC41) + **21 tác vụ hệ thống** (không vẽ trên sơ đồ use case).

---

# 0. Actors

| Mã | Actor | Vai trò |
|---|---|---|
| A01 | GUEST | Khách vãng lai, chưa đăng nhập. Được xem thông tin công khai (dịch vụ, chi nhánh kèm số điện thoại, bác sĩ, sản phẩm, bài viết) và đăng ký tài khoản. Muốn đặt lịch phải đăng ký tài khoản (UC01) hoặc nhờ lễ tân đặt hộ (UC39). |
| A02 | CUSTOMER | Chủ thú cưng đã có tài khoản; chỉ truy cập được dữ liệu của chính mình. **Trong phạm vi đồ án:** quản lý hồ sơ cá nhân và thú cưng, đặt lịch khám và lưu trú, xem hồ sơ sức khỏe và nhật ký chăm sóc của thú, nhận nhắc lịch hẹn, tái chủng, tái khám, gửi feedback. **Hướng phát triển (tầng 3):** mua hàng online, khiếu nại, bình luận bài viết. |
| A03 | ADMIN | Quản trị kỹ thuật của hệ thống: tạo tài khoản SUPER_MANAGER, khóa/mở khóa người dùng, cấu hình tham số và mẫu thông báo, xem audit. Không tham gia nghiệp vụ phòng khám và bán hàng. |
| A04 | SUPER_MANAGER | Quản lý cấp chuỗi, phạm vi mọi chi nhánh. **Trong phạm vi đồ án:** quản lý chi nhánh (tạo, kích hoạt, cờ cấp cứu ngoài giờ); quản lý danh mục sản phẩm, dịch vụ, giá, loại vaccine và phác đồ tiêm chủng; tạo, đổi chức vụ, điều chuyển, vô hiệu hóa nhân viên; quản lý nội dung trang và đăng bài viết; xem feedback; xem báo cáo toàn chuỗi. **Hướng phát triển (tầng 3):** tạm ngừng / đóng chi nhánh, phí vận chuyển, duyệt bài viết của VET, xử lý khiếu nại. |
| A05 | BRANCH_MANAGER | Quản lý một chi nhánh, chỉ thao tác trên dữ liệu của chi nhánh mình. **Trong phạm vi đồ án:** tạo và vô hiệu hóa nhân viên cấp dưới; cấu hình giờ mở cửa, ngày nghỉ, quota lịch hẹn; bật/tắt dịch vụ tại chi nhánh; quản lý chuồng; nhập kho, điều chỉnh tồn, đặt tồn tối thiểu; hủy Order khách không thanh toán; đối soát ca thu ngân; gán lại lượt đã gọi khi nhân viên phụ trách bị khóa; xem feedback và báo cáo chi nhánh. **Hướng phát triển (tầng 3):** phân ca và duyệt nghỉ, gộp hồ sơ khách trùng, duyệt trả hàng, chuyển kho, kiểm kê, xử lý khiếu nại. |
| A06 | RECEPTIONIST | Lễ tân kiêm thu ngân tại một chi nhánh, là đầu mối vận hành hằng ngày. **Trong phạm vi đồ án:** tiếp nhận khách, điều phối hàng đợi, đặt lịch hộ khách, quản lý hồ sơ khách tại quầy, bán hàng, thu tiền, nhận/trả thú lưu trú, thực hiện Care Task gọi điện chăm sóc khách. **Hướng phát triển (tầng 3):** xử lý và giao đơn online, tạo yêu cầu trả hàng, tạo khiếu nại thay khách. |
| A07 | VET | Bác sĩ thú y tại một chi nhánh. **Trong phạm vi đồ án:** khám, chẩn đoán, kê đơn, hẹn tái khám, tiêm chủng, cập nhật và bổ sung bệnh án, ghi nhật ký chăm sóc thú lưu trú; có hồ sơ giới thiệu hiển thị công khai. **Hướng phát triển (tầng 3):** phẫu thuật, khám tại nhà, điều trị nội trú, viết bài chuyên môn, truy vết lô. |
| A08 | CARETAKER | Nhân viên chăm sóc tại một chi nhánh; chỉ được xem bệnh án, không được sửa. **Trong phạm vi đồ án:** thực hiện dịch vụ thẩm mỹ (tắm, cắt tỉa), nhận thú lưu trú, ghi nhật ký chăm sóc thú lưu trú. **Hướng phát triển (tầng 3):** chăm sóc thú nội trú. |
| S01 | System | Tự động chạy các tác vụ định kỳ và tác vụ kích hoạt theo sự kiện (mục B): nhắc lịch, hủy đơn quá hạn, cảnh báo tồn kho, sinh Care Task… |
| S02 | Payment Gateway | *(Tầng 3)* Cổng thanh toán bên ngoài. Xử lý thanh toán online, hoàn tiền, trả kết quả giao dịch về hệ thống. |
| S03 | Email / Push Provider | Dịch vụ gửi email (OTP, thông báo) và push notification bên ngoài. |

---

# A. Danh sách use case

## 1. Tài khoản & xác thực

| Mã | Use case | Actor |
|---|---|---|
| UC01 | Đăng ký tài khoản | A01 |
| UC02 | Xác thực OTP (gồm gửi lại OTP) «include» | A01–A08 |
| UC03 | Đăng nhập / đăng xuất | A02–A08 |
| UC04 | Quên mật khẩu | A02–A08 |
| UC05 | Đổi mật khẩu | A02–A08 |
| UC06 | Quản lý hồ sơ cá nhân (không gồm email, SĐT; gồm sổ địa chỉ giao hàng; với A07 gồm thông tin giới thiệu hiển thị công khai: ảnh, chuyên môn, mô tả ngắn) | A02–A08 |
| UC07 | Liên kết tài khoản với hồ sơ khách có sẵn (gắn tài khoản chờ liên kết vào hồ sơ tại quầy, không gộp dữ liệu — BR-TK-19) | A02 |

## 2. Quản trị hệ thống

| Mã | Use case | Actor |
|---|---|---|
| UC08 | Quản lý tài khoản nhân viên (tạo, gán chi nhánh & chức vụ, đổi chức vụ, điều chuyển, vô hiệu hóa, kích hoạt lại, sửa email/SĐT) | A03, A04, A05 (phạm vi theo BR-QT-01, BR-QT-07) |
| UC09 | Khóa / mở khóa người dùng | A03 |
| UC10 | Cấu hình tham số hệ thống & mẫu thông báo | A03 |
| UC11 | Xem nhật ký audit | A03 |

## 3. Chi nhánh

| Mã | Use case | Actor |
|---|---|---|
| UC12 | Quản lý chi nhánh (tạo, cập nhật, kích hoạt, bật cờ nhận cấp cứu ngoài giờ) | A04 |
| UC13 | Tạm ngừng / đóng cửa chi nhánh (gồm chuyển giao tồn đọng) | A04 |
| UC14 | Cấu hình giờ mở cửa (tối đa 2 khoảng mỗi ngày) & ngày nghỉ | A05 |

## 4. Thông tin công khai

| Mã | Use case | Actor |
|---|---|---|
| UC15 | Xem dịch vụ, chi nhánh (gồm số điện thoại, chi nhánh nhận cấp cứu 24/7) & đội ngũ bác sĩ | A01, A02 |
| UC16 | Xem & tìm kiếm sản phẩm | A01, A02 |
| UC17 | Xem bài viết | A01, A02 |
| ~~UC18~~ | ~~Gửi liên hệ~~ — bỏ ở v13, khách hỏi qua số điện thoại chi nhánh hiển thị ở UC15. Giữ mã đến khi đánh số lại một lần. | — |
| ~~UC19~~ | ~~Xem đánh giá công khai~~ — bỏ ở v10, feedback không công khai (UC83, UC84). Giữ mã đến khi đánh số lại một lần. | — |
| ~~UC20~~ | ~~Xử lý liên hệ~~ — bỏ ở v13 cùng UC18. | — |
| UC21 | Quản lý nội dung trang (banner trang chủ, giới thiệu, chính sách, FAQ) | A04 |

## 5. Khách hàng & thú cưng

| Mã | Use case | Actor |
|---|---|---|
| UC22 | Quản lý hồ sơ khách tại quầy (tạo, cập nhật, sửa email/SĐT tài khoản khách, liên kết hồ sơ với tài khoản tại quầy) | A06 |
| UC23 | Tra cứu khách & thú cưng | A06, A07, A08 |
| UC24 | Quản lý thú cưng (thêm, cập nhật, đánh dấu đã mất, xóa thú chưa phát sinh giao dịch) | A02, A06 |
| UC25 | Xem hồ sơ sức khỏe thú cưng | A02 |
| UC26 | Chuyển chủ thú cưng | A06 |
| UC27 | Gộp hồ sơ khách trùng | A05 |

## 6. Sản phẩm & dịch vụ

| Mã | Use case | Actor |
|---|---|---|
| UC28 | Quản lý danh mục sản phẩm | A04 |
| UC29 | Quản lý sản phẩm (tạo, cập nhật, đổi giá, ngừng kinh doanh; sản phẩm vaccine gắn loại vaccine — BR-SP-07) | A04 |
| UC30 | Quản lý dịch vụ (tạo, cập nhật, đổi giá, ngừng; gồm loại chuồng lưu trú — BR-SP-04; loại Khám/Tiêm của dịch vụ — BR-SP-06; định mức vật tư thuộc tầng 3) | A04 |
| UC31 | Quản lý loại vaccine & phác đồ tiêm chủng (phác đồ theo loại vaccine, gồm cờ bắt buộc khi lưu trú — BR-SP-02, BR-SP-07) | A04 |
| UC32 | Cấu hình phí vận chuyển & phí khám tại nhà | A04 |
| UC33 | Bật / tắt dịch vụ tại chi nhánh | A05 |

## 7. Nhân sự (phạm vi mở rộng)

| Mã | Use case | Actor |
|---|---|---|
| UC34 | Quản lý ca làm việc (định nghĩa ca, phân ca, xem nhân viên chi nhánh) | A05 |
| UC35 | Xem lịch làm việc của tôi | A06, A07, A08 |
| UC36 | Gửi / rút yêu cầu nghỉ, đổi ca | A06, A07, A08 |
| UC37 | Duyệt yêu cầu nghỉ, đổi ca | A05 |
| UC38 | Ghi nhận vắng đột xuất | A05 |

## 8. Lịch hẹn

| Mã | Use case | Actor |
|---|---|---|
| UC39 | Đặt lịch hẹn (gồm xem khung giờ trống) | A02, A06 |
| UC40 | Quản lý lịch hẹn (xem danh sách, đổi khung giờ trong cùng chi nhánh, hủy) | A02, A06 |
| ~~UC41~~ | ~~Phân công nhân sự cho lịch hẹn~~ — bỏ ở v8, nhân viên được gán khi tiếp nhận (UC45). Giữ mã đến khi chốt phạm vi rồi đánh số lại một lần. | — |
| UC42 | Thiết lập quota lịch hẹn (quota mặc định theo nhóm dịch vụ; quota riêng cho từng khung giờ, cho phép 0 để khóa khung — BR-LH-03) | A05 |
| UC43 | Đặt lịch khám tại nhà | A02, A06 |

## 9. Tiếp nhận & hàng đợi

| Mã | Use case | Actor |
|---|---|---|
| UC44 | Tiếp nhận khách (check-in lịch hẹn, khách không hẹn trước; tạo Lượt tiếp nhận và mở Order) | A06 |
| UC45 | Điều phối hàng đợi (gán nhân viên — ưu tiên nhân viên đang online, sắp thứ tự, hủy lượt khách bỏ về; gán lại lượt đã gọi khi người phụ trách bị khóa — BR-TN-08) | A06, A05 (gán lại lượt đã gọi) |
| UC46 | Xem hàng đợi của tôi & gọi lượt | A07, A08 |
| UC47 | Xác định ca cấp cứu | A07, A06 |

## 10. Khám & điều trị

| Mã | Use case | Actor |
|---|---|---|
| UC48 | Khám bệnh (khám, chẩn đoán, xét nghiệm, kê đơn, hẹn tái khám — BR-KB-06, hoàn tất) | A07 |
| UC49 | Tiêm chủng (chọn loại vaccine theo phác đồ rồi chọn sản phẩm còn tồn; lượt tiêm riêng, hoặc tiêm ngay trong lượt khám trên cùng Visit; VET được chọn mũi thứ khác với gợi ý; xóa mũi ghi nhầm khi lượt còn mở; chỉ ghi nhận mũi tiêm tại hệ thống — BR-KB-04, BR-KB-05) | A07 |
| UC50 | Phẫu thuật (lập cam kết, thực hiện) | A07, A06 |
| UC51 | Khám tại nhà | A07 |
| UC52 | Thực hiện dịch vụ thẩm mỹ | A08 |
| UC53 | Xem & bổ sung bệnh án | A07, A08 (chỉ xem) |

## 11. Lưu trú & nội trú

| Mã | Use case | Actor |
|---|---|---|
| UC54 | Quản lý chuồng (tạo chuồng theo loại chuồng, đổi trạng thái `AVAILABLE` / `MAINTENANCE`) | A05 |
| UC55 | Nhập / xuất viện (gồm chuyển từ lưu trú sang điều trị) | A07 |
| UC56 | Điều trị nội trú (ra y lệnh, theo dõi hậu phẫu) | A07 |
| UC57 | Ghi nhật ký chăm sóc | A08, A07 |
| UC58 | Đặt chỗ lưu trú theo loại chuồng (đặt, hủy, gia hạn) | A02, A06 |
| UC59 | Nhận / trả thú cưng lưu trú (kiểm tra tiêm phòng khi nhận; thu tiền trước khi trả; hủy phiên trả thú khi khách không thanh toán; kết thúc lưu trú khi thú mất) | A06, A08 (A08 chỉ nhận thú) |
| UC60 | Xem lưu trú & nhật ký chăm sóc của thú cưng | A02 |

## 12. Bán hàng & đơn hàng

| Mã | Use case | Actor |
|---|---|---|
| UC61 | Quản lý giỏ hàng | A02 |
| UC62 | Đặt hàng online | A02 |
| UC63 | Thanh toán online | A02, S02 |
| UC64 | Quản lý đơn hàng của tôi (xem, theo dõi giao hàng, hủy, xác nhận đã nhận) | A02 |
| UC65 | Xử lý & giao đơn online (xử lý, bàn giao, cập nhật trạng thái, giao thất bại) | A06 |
| UC66 | Bán hàng & chốt Order tại quầy (tạo, xóa dòng, chốt, hủy; BRANCH_MANAGER hủy Order `PENDING` khách không thanh toán — BR-BH-05) | A06, A05 |
| UC67 | Thêm dịch vụ / hàng vào Order | A06, A07, A08 |
| UC68 | Tạo yêu cầu trả hàng | A06 |
| UC69 | Duyệt yêu cầu trả hàng | A05 |

## 13. Thu ngân

| Mã | Use case | Actor |
|---|---|---|
| UC70 | Thu tiền tại quầy (thu xong mới giao hàng — BR-BH-06) | A06 |
| UC71 | Mở / chốt ca thu ngân (ca thường trong giờ mở cửa; ca ngoài giờ để thu tiền cấp cứu ở chi nhánh có cờ cấp cứu; hệ thống tự chốt ca chưa chốt — BR-CN-05, BR-TG-05) | A06 |
| UC72 | Đối soát ca thu ngân | A05 |

## 14. Kho

| Mã | Use case | Actor |
|---|---|---|
| UC73 | Quản lý nhà cung cấp | A05 |
| UC74 | Nhập kho (tạo, sửa phiếu nháp, xác nhận, hủy phiếu nhập; ghi số lô & hạn dùng với sản phẩm quản lý hạn dùng) | A05 |
| UC75 | Xem tồn kho (theo lô, hạn dùng) & thiết lập tồn tối thiểu | A05, A06 (chỉ xem) |
| UC76 | Điều chỉnh tồn kho (hỏng, hết hạn, lệch kiểm đếm) | A05 |
| UC77 | Chuyển kho (tạo, hủy, xác nhận nhận hàng) | A05 |
| UC78 | Kiểm kê kho | A05 |
| UC79 | Truy vết lô hàng | A05, A07 |

## 15. Bài viết

| Mã | Use case | Actor |
|---|---|---|
| UC80 | Soạn & gửi duyệt bài viết | A07 |
| UC81 | Quản lý bài viết, chuyên mục & bình luận (soạn, duyệt, gỡ bài, ẩn bình luận) | A04 |
| UC82 | Bình luận bài viết (đăng, sửa, xóa của mình) | A02 |

## 16. Feedback & khiếu nại

| Mã | Use case | Actor |
|---|---|---|
| UC83 | Gửi feedback (về dịch vụ tại chi nhánh hoặc website; không công khai) | A02 |
| UC84 | Xem & xử lý feedback | A04, A05 (chi nhánh mình) |
| UC85 | Gửi & theo dõi khiếu nại (tạo, xem, đóng / mở lại) | A02, A06 (tạo thay khách) |
| UC86 | Xử lý khiếu nại | A05, A04 |

## 17. Chăm sóc khách hàng & thông báo

| Mã | Use case | Actor |
|---|---|---|
| UC87 | Thực hiện Care Task (thực hiện, hủy) | A06 |
| UC88 | Xem thông báo & cài đặt nhận thông báo | A02–A08 (cài đặt: A02) |

## 18. Báo cáo

| Mã | Use case | Actor |
|---|---|---|
| UC89 | Xem báo cáo | A04, A05 |

---

# B. Tác vụ hệ thống

Các mục dưới đây do hệ thống tự chạy theo thời gian hoặc theo sự kiện, không phải mục tiêu của một actor. Mô tả chúng trong phần đặc tả kỹ thuật (background job / event handler). Nếu muốn thể hiện trên sơ đồ, có thể dùng actor "Thời gian" cho ST03 và ST04.

| Mã | Tác vụ | Kích hoạt bởi / liên quan |
|---|---|---|
| ST01 | Khóa tạm tài khoản do đăng nhập sai | UC03 |
| ST02 | Dọn tài khoản chưa xác thực | Định kỳ |
| ST03 | Nhắc lịch hẹn | Định kỳ |
| ST04 | Nhắc tái chủng & nhắc tái khám | Định kỳ, dựa trên UC31, UC48, UC49 |
| ST05 | Chuyển lịch hẹn & đặt chỗ lưu trú sang NO_SHOW | Định kỳ |
| ST06 | Giữ hàng (đơn online) & trừ kho theo FEFO (vaccine khi tiêm; hàng, thuốc khi Order `PAID`) | UC49 (vaccine), UC62, UC65, UC66, UC70 |
| ST07 | Trừ vật tư tiêu hao | UC48, UC52 |
| ST08 | Cảnh báo tồn kho dưới ngưỡng & lô sắp / đã hết hạn | Định kỳ |
| ST09 | Tính phí vận chuyển | UC62, dùng cấu hình phí từ UC32 |
| ST10 | Hủy đơn chưa thanh toán | Định kỳ |
| ST11 | Hết hạn giao dịch thanh toán | Định kỳ |
| ST12 | Tự xác nhận đã nhận hàng | Định kỳ |
| ST13 | Tự chốt ca thu ngân chưa chốt & cảnh báo Order `PENDING` quá hạn | Ca thường: cuối ngày, theo giờ đóng cửa chi nhánh. Ca ngoài giờ: khi đến giờ mở cửa kế tiếp của chi nhánh |
| ST14 | Đối soát cổng thanh toán | Hằng ngày |
| ST15 | Đánh dấu lưu trú quá hạn đón (OVERDUE) | Định kỳ |
| ST16 | Leo thang khiếu nại quá hạn | Định kỳ |
| ST17 | Tự đóng khiếu nại | Định kỳ |
| ST18 | Sinh Care Task | ST04 (nhắc tái chủng / tái khám với hồ sơ không có email; quá hạn tái chủng), ST15 (quá hạn đón) |
| ST19 | Tự hủy Care Task khi thú cưng đã mất | UC24 |
| ST20 | Gửi thông báo & thử lại khi thất bại | Mọi sự kiện cần thông báo |
| ST21 | Hoàn tiền online qua cổng thanh toán | UC64, UC69 |

---

# C. Quan hệ include / extend chính

| Use case gốc | Quan hệ | Use case liên quan |
|---|---|---|
| UC01 Đăng ký, UC04 Quên mật khẩu, UC07 Liên kết tài khoản | «include» | UC02 Xác thực OTP |
| UC08 Quản lý tài khoản nhân viên, UC22 Quản lý hồ sơ khách tại quầy (khi sửa email) | «extend» | UC02 Xác thực OTP |
| UC44 Tiếp nhận khách (khách chưa có hồ sơ) | «extend» | UC22 Quản lý hồ sơ khách tại quầy |
| UC48 Khám bệnh | «extend» | UC50 Phẫu thuật *(tầng 3)*, UC55 Nhập viện *(tầng 3)* |
| UC50 Phẫu thuật, UC55 Nhập viện *(tầng 3)* | «extend» | UC67 Thêm dịch vụ / hàng vào Order |
| UC62 Đặt hàng online *(tầng 3)* | «include» | UC63 Thanh toán online |
| UC66 Chốt Order tại quầy | «include» | UC70 Thu tiền tại quầy |
| UC59 Nhận / trả thú lưu trú (khi trả thú — BR-LT-09) | «include» | UC70 Thu tiền tại quầy |
| UC59 Nhận thú lưu trú (chưa đủ tiêm phòng — BR-LT-05) | «extend» | UC44 Tiếp nhận khách (tiêm trước khi nhận) |
| UC57 Ghi nhật ký chăm sóc (mục bất thường — BR-LT-12) | «extend» | UC44 Tiếp nhận khách, UC47 Xác định ca cấp cứu |
