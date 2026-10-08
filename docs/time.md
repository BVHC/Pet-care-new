# Pet Care Ecosystem - Team Timeline

> Nguồn: `docs/timeline_petcare_v15.xlsx` + kiểm tra thực tế trong `BE/` và `FE/`. Cập nhật: 2026-10-07.


## Tổng quan trạng thái


Tiến độ: **23/50 task** (46%) - W1 (07-09/10/2026).


| Trạng thái | Ý nghĩa |
|---|---|
| **DONE** | Có code thực tế trong `BE/` hoặc `FE/` |
| **IN_PROGRESS** | Đang làm dở |
| **NOT_STARTED** | Chưa có code |

## Milestone


| Milestone | Ngay | Ghi chú |
|---|---|---|
| M0: Hợp đồng giữa module + khung | 06/10 | |
| M1: Nền tảng | 09/10 | Tài khoản, Danh mục, Chi nhánh |
| M2: Dữ liệu nền | 16/10 | Quản trị, Lịch hẹn, KH, Kho |
| M3: Luồng khám & bán hàng | 23/10 | Visit, Khám, Tiem, Order |
| M4: Sẵn sàng demo | 30/10 | Lưu trú, CSKH, Báo cáo, E2E |
| M5: Tầng 3 + AI | 11/12 | Giai đoạn 2 |

## GD 0 - Thiết kế & Setup (05-06/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T1 | Chốt kiến trúc & tech stack mới (làm lại từ đầu, không dùng code/tài liệu cũ), q | Cả nhóm | **NOT_STARTED** | _(chưa có test hint)_ |
| T2 | Hợp đồng giữa module (để BE-1/BE-2 làm song song): danh sách sự kiện liên đối tư | Cả nhóm | **NOT_STARTED** | _(chưa có test hint)_ |
| T3 | Khởi tạo dự án BE mới + xác thực & phân quyền 7 role, giới hạn theo chi nhánh, b | BE-1 (Backend) | **NOT_STARTED** | _(chưa có test hint)_ |
| T4 | Schema DB toàn bộ theo 05-erd §1–11 + seed dữ liệu nền (ADMIN, cấu hình mặc định | BE-2 (Backend) | **NOT_STARTED** | _(chưa có test hint)_ |
| T5 | Khởi tạo dự án FE mới: design system, router, chặn route theo role, layout Publi | FE-1 (Frontend) | **NOT_STARTED** | _(chưa có test hint)_ |
## W1 - Nền tảng (07-09/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T6 | Tài khoản: Đăng ký + OTP (gửi, gửi lại, xác thực) + hàng đợi gửi email Notificat | BE-1 | **DONE** | POST /api/auth/register (>=18 tuoi, email unique) -> nhận OTP -> verify -> tài khoản ACTIVE. Thu: đăng ký duới 18 tuổi -> 400. |
| T7 | Tài khoản: Đăng nhập/đăng xuất + ST01 khóa tạm + Quên/Đổi mật khẩu + bắt đổi MK  | BE-1 | **DONE** | POST /api/auth/login -> JWT + session -> POST /api/auth/logout. OTP sai 5 lần -> ST01 khóa 15 phút. Thu: login với tài khoản bị khóa -> 423. |
| T8 | Tài khoản: Hồ sơ cá nhân + sổ địa chỉ + hồ sơ công khai VET + liên kết hồ sơ khá | BE-1 | **NOT_STARTED** | PUT /api/accounts/{id}/profile -> sổ địa chỉ (tối đa 5). Thu: sửa email hộ khách (A06) -> được. Sửa email chính mình -> 403. |
| T9 | Danh mục: danh mục SP, sản phẩm (thuốc kê đơn, hạn dùng), dịch vụ (nhóm, loại Kh | BE-2 | **NOT_STARTED** | CRUD danh mục: danh mục -> sản phẩm (thuốc kê đơn) -> dịch vụ (nhóm Khám/Tiêm/Thẩm mỹ/Lưu trú) -> loại vaccine -> phác đồ. |
| T10 | Chi nhánh: tạo/kích hoạt, giờ mở cửa (2 khoảng/ngày, ngày hiệu lực), ngày nghỉ & | BE-2 | **DONE** | POST /api/branches -> kích hoạt (cần BRANCH_MANAGER + giờ mở cửa). Thu: kích hoạt khi chưa có quản lý chi nhánh -> 400. |
| T11 | Chi nhánh: bật/tắt dịch vụ tại chi nhánh + quota mặc định theo nhóm & quota từng | BE-2 | **DONE** | PUT /api/branches/{id}/services (bật/tắt dịch vụ). PUT /api/branches/{id}/quota (quota theo nhóm dịch vụ x khung giờ). Thu: quota = 0 -> khung bị khóa. |
| T12 | FE: Đăng ký (≥18 tuổi, điều khoản), OTP (đếm ngược 60s), Đăng nhập, Quên/Đổi mật | FE-1 | **DONE** | FE: form đăng ký -> gọi T6. OTP -> gọi T6. Thu: gửi OTP nhiều lần -> rate limit 5 lần/15 phút. |
| T13 | FE: Hồ sơ cá nhân + sổ địa chỉ + hồ sơ giới thiệu VET + màn hình liên kết hồ sơ | FE-1 | **DONE** | FE: trang hồ sơ cá nhân -> gọi T8. Liên kết tài khoản T7. Thu: số điện thoại trùng -> cảnh báo nghi trùng. |
## W2 - Dữ liệu nền & Lịch hẹn (12-16/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T14 | Quản trị: tài khoản nhân viên (tạo theo phân cấp, đổi chức vụ, điều chuyển, vô h | BE-1 | **DONE** | POST /api/accounts (SUPER_MANAGER tạo BRANCH_MANAGER). PUT /api/accounts/{id}/role -> đổi chức vụ. Thu: ADMIN đổi chức vụ BRANCH_MANAGER -> 403. |
| T15 | Quản trị: API cấu hình tham số [CFG] (min–max) + mẫu thông báo (khôi phục mặc đị | BE-1 | **DONE** | GET/PUT /api/system-configs. GET /api/audit-logs. Thu: ADMIN đổi tham số -> audit ghi. Đọc audit khi không phải ADMIN -> 403. |
| T16 | Lịch hẹn: sinh khung 30 phút theo giờ mở cửa/ngày nghỉ, tính quota, đặt lịch (kh | BE-1 | **DONE** | POST /api/appointments (đặt lịch) -> sinh slot. GET /api/appointments/slots. Thu: đặt khi quota = 0 -> 400. Đặt khi khung đầy -> 400. |
| T17 | Khách hàng & thú cưng: hồ sơ tại quầy, tra cứu, thú cưng (khóa loài), cân nặng,  | BE-2 | **DONE** | POST /api/customers -> tạo hồ sơ. POST /api/pets -> thêm thú cưng. GET /api/pets?q= (tra cứu). Thu: số điện thoại trùng -> cảnh báo. |
| T18 | Kho: nhà cung cấp, tồn theo lô + StockMovement, phiếu nhập kho, dịch vụ xuất FEF | BE-2 | **DONE** | POST /api/stocks/inbound. GET /api/stocks?q=. PUT /api/stocks/{id}/min-stock. Thu: nhập kho với số lô trùng -> tạo lô mới hay cộng dồn. |
| T19 | Order lõi: FSM, dòng Order snapshot giá, quyền thêm/xóa dòng theo người thêm + A | BE-2 | **DONE** | POST /api/orders (Order lõi). Thu: tạo Order không có dòng -> 400. |
| T20 | FE: Quản lý chi nhánh + giờ mở cửa + ngày nghỉ + dịch vụ tại chi nhánh + quota | FE-1 | **NOT_STARTED** | FE: trang quản lý chi nhánh -> gọi T10, T11. Cấu hình giờ mở cửa, ngày nghỉ. |
| T21 | FE: Quản lý danh mục SP/DV, loại chuồng, loại vaccine & phác đồ | FE-1 | **NOT_STARTED** | FE: trang danh mục sản phẩm -> gọi T9. Thu: tạo sản phẩm thuốc kê đơn -> hiển thị cảnh báo. |
| T22 | FE Admin: tài khoản nhân viên, khóa/mở khóa, cấu hình tham số, mẫu thông báo, au | FE-1 | **NOT_STARTED** | FE: trang quản trị -> gọi T14, T15. Thu: non-admin truy cập -> redirect login. |
| T23 | FE: Hồ sơ khách tại quầy, tra cứu khách & thú, quản lý thú cưng (khách + lễ tân) | FE-1 | **NOT_STARTED** | FE: trang khách hàng tại quầy -> gọi T17. Thu: tạo hồ sơ trùng SĐT -> cảnh báo. |
| T24 | FE: Kho: nhà cung cấp, phiếu nhập, tồn theo lô/hạn dùng, điều chỉnh tồn, tồn tối | FE-1 | **DONE** | FE: trang kho -> gọi T18. Thu: lễ tân xem tồn kho -> được. Lễ tân sửa tồn kho -> 403. |
## W3 - Luồng khám & bán hàng (19-23/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T25 | Lịch hẹn: ST05 NO_SHOW, hạn chế đặt online (gỡ sớm), ST03 nhắc lịch, nhận sự kiệ | BE-1 | **DONE** | ST05: quá giờ hẹn 30 phút -> Appointment BOOKED -> NO_SHOW. BR-LH-09: 3 lần NO_SHOW/hủy muộn trong 90 ngày -> hạn chế đặt online 30 ngày. |
| T26 | Tiếp nhận: Visit (check-in, walk-in, cấp cứu ngoài giờ), thứ tự hàng đợi, gán/gá | BE-1 | **NOT_STARTED** | POST /api/visits (check-in). GET /api/visits/queue. PUT /api/visits/{id}/queue (gọi lượt). Thu: check-in trước 30 phút -> 400. |
| T27 | Khám: bệnh án, chẩn đoán, ghi chú nội bộ, hoàn tất lượt (điều kiện theo loại Khá | BE-1 | **DONE** | POST /api/medical-records -> bệnh án. POST /api/prescriptions -> kê đơn. Thu: hoàn tất khi chưa có chẩn đoán -> 400. Kê đơn thiếu tồn -> giảm SL hoặc "mua ngoài". |
| T28 | Tiêm chủng: ghi mũi theo phác đồ (trừ kho FEFO, dòng Order vaccine, ngày tái chủ | BE-1 | **NOT_STARTED** | POST /api/vaccinations -> ghi mũi tiêm. Thu: chọn vaccine hết tồn -> 400. Xóa mũi khi lượt đã hoàn tất -> 400. |
| T29 | Order bán lẻ tại quầy: tạo, thêm hàng (cảnh báo thiếu tồn, chặn thuốc kê đơn), c | BE-2 | **NOT_STARTED** | POST /api/orders/{id}/lines -> thêm dòng. DELETE /api/orders/{id}/lines/{lineId}. Thu: lễ tân thêm thuốc kê đơn -> 400. Bác sĩ thêm dịch vụ -> được. |
| T30 | Thu ngân: ca thường/ngoài giờ, thu tiền gộp + trừ kho FEFO, đối soát, ST13 tự ch | BE-2 | **NOT_STARTED** | POST /api/cashier-sessions -> mở ca. POST /api/cashier-sessions/{id}/close -> chốt ca. Thu: chốt ca khi đang đóng -> 400. |
| T31 | Lưu trú: chuồng (tạo, bảo trì), đặt chỗ theo loại chuồng (sức chứa theo đêm, sna | BE-2 | **NOT_STARTED** | POST /api/boardings -> đặt chỗ. Thu: đặt khi chuồng đầy -> 400. Đặt với thú chưa tiêm đủ -> cảnh báo. |
| T32 | Thông tin công khai + Bài viết + Feedback: dịch vụ, chi nhánh, bác sĩ, sản phẩm  | BE-2 | **NOT_STARTED** | FE: trang công khai -> gọi T9, T10. Bài viết -> gọi T9. Feedback -> POST /api/feedback. |
| T33 | FE: Đặt lịch (chọn khung trống), lịch hẹn của tôi, lễ tân đặt hộ / đổi / hủy | FE-1 | **DONE** | FE: trang đặt lịch -> gọi T16. Thu: đổi giờ 4 lần -> lần 4 bị từ chối. Hủy dưới 12 giờ -> ghi hủy muộn. |
| T34 | FE: Trung tâm thông báo & cài đặt nhận thông báo + bán lẻ tại quầy (POS) | FE-1 | **NOT_STARTED** | FE: trang thông báo -> GET /api/notifications. Thu: non-auth -> 401. |
| T35 | FE: Thu tiền (gộp Order), mở/chốt ca, đối soát ca | FE-1 | **DONE** | FE: trang thanh toán -> gọi T30. Thu: thu gộp 2 Order -> một lần thu. Chuyển khoản sai mã -> chưa PAID. |
| T36 | FE: Tiếp nhận & hàng đợi (lễ tân), hàng đợi của tôi & gọi lượt (VET/CARETAKER),  | FE-1 | **DONE** | FE: trang tiếp nhận -> gọi T26. Thu: check-in lịch không tồn tại -> 404. |
| T37 | FE: Màn hình khám VET (bệnh án, chẩn đoán, tiêm chủng, hoàn tất) + màn hình thẩm | FE-1 | **DONE** | FE: trang bác sĩ -> gọi T27, T28. Thu: hoàn tất lượt khi chưa có chẩn đoán -> nút bị disabled. |
## W4 - Lưu trú, CSKH, Báo cáo (26-29/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T38 | Khám: kê đơn (thiếu tồn → giảm SL hoặc mua ngoài, sinh dòng Order thuốc), hẹn tá | BE-1 | **DONE** | PUT /api/prescriptions/{id}/external -> "mua ngoài". Thu: kê đơn khi tồn đủ -> bình thường. |
| T39 | Care Task + ST04 nhắc tái chủng/tái khám + ST18 sinh task + ST19 hủy khi thú mất | BE-1 | **DONE** | GET /api/care-tasks -> danh sách. PUT /api/care-tasks/{id} -> cập nhật kết quả. Thu: lễ tân khác chi nhánh -> 403. |
| T40 | Nối thật các interface chéo (chặn vô hiệu hóa, khóa → gán lại Visit) + integrati | Cả nhóm | **NOT_STARTED** | Integration test: stub -> implementation thật. Thu: T26 gọi T19 (Order lõi BE-2) -> không lỗi. |
| T41 | E2E luồng chính: đặt lịch → tiếp nhận → khám/tiêm → Order → thu tiền → nhắc tái  | Cả nhóm | **NOT_STARTED** | E2E script (Playwright/Cypress): đặt lịch -> check-in -> khám -> thu tiền -> nhắc tái chủng. Thu: toàn bộ luồn không lỗi. |
| T42 | Lưu trú: nhận thú (kiểm tra mũi bắt buộc qua interface BE-1, chuồng đúng loại, c | BE-2 | **NOT_STARTED** | POST /api/boardings/{id}/checkin -> nhận thú. Thu: nhận thú chưa tiêm đủ -> 400. Nhận thú khi chuồng đầy -> 400. |
| T43 | Lưu trú: bắt đầu trả thú → Order BOARDING, giao thú khi PAID, hủy phiên trả thú, | BE-2 | **NOT_STARTED** | POST /api/boardings/{id}/checkout -> trả thú. Thu: trả khi chưa thanh toán -> 400. Thú mất -> kết thúc lưu trú. |
| T44 | Báo cáo chi nhánh/toàn chuỗi (doanh thu, thất thu, tỷ lệ quay lại tái chủng…) +  | BE-2 | **DONE** | GET /api/reports/revenue. GET /api/reports/no-show. Thu: BRANCH_MANAGER lọc chi nhánh khác -> 403. SUPER_MANAGER lọc toàn chuỗi -> được. |
| T45 | Integration test FSM: Chi nhánh, Order, Ca thu ngân, Phiếu nhập, Đặt chỗ, Chuồng | BE-2 | **NOT_STARTED** | FSM integration test: mỗi chuyển trạng thái hợp lệ / không hợp lệ. Ví dụ: Lịch hẹn BOOKED->CANCELLED chỉ khi đủ điều kiện hủy. |
| T46 | FE: Kê đơn (mua ngoài) & hẹn tái khám + trang công khai (dịch vụ, chi nhánh, bác | FE-1 | **NOT_STARTED** | FE: trang kê đơn -> gọi T27. Thu: kê đơn khi tồn đủ -> bình thường. Kê đơn khi thiếu tồn -> hiển thị cảnh báo. |
| T47 | FE: Quản lý nội dung trang, chuyên mục & bài viết, gửi/xem feedback + Care Task  | FE-1 | **NOT_STARTED** | FE: trang nội dung -> gọi T9 (bài viết). Thu: tạo bài viết -> chờ duyệt. |
| T48 | FE: Lưu trú: chuồng, đặt chỗ, nhận/trả thú, nhật ký chăm sóc; khách xem lưu trú  | FE-1 | **NOT_STARTED** | FE: trang lưu trú -> gọi T31, T42, T43. Thu: đặt chỗ -> nhận thú -> trả thú. |
| T49 | FE: Báo cáo + tích hợp API thật toàn bộ luồng, fix UI | FE-1 | **DONE** | FE: trang báo cáo -> gọi T44. Biểu đồ doanh thu, NO_SHOW. Thu: đổi chi nhánh -> biểu đồ cập nhật. |
## Demo & Ban giao (30/10)


| # | Công việc | Ai | Trạng thái | Test |
|---|---|---|---|---|
| T50 | Tổng duyệt demo + bàn giao + đóng milestone (cả nhóm) | Cả nhóm | **NOT_STARTED** | _(chưa có test hint)_ |

## Giai đoạn 2 - Tầng 3 & AI (11-12/2026)


> Chưa bắt đầu. TODO: bổ sung chi tiết khi bat dau Giai đoạn 2.


| Moc | Thời gian | Nội dung chính |
|---|---|---|
| M5a: Đặc tả bổ sung | 02-14/11 | BR, FSM, model, API tang 3 |
| M5b: Nhan su + TMĐT | 16-28/11 | Ca lam viec, gio hang, dat hang, thanh toan online |
| M5c: Noi tru, Khiếu nại, AI | 01-11/12 | Nhập/xuất viện, khieu nai, chatbot RAG+LLM, recommendation |
| M5d: Hoan thien | 14-31/12 | Kiểm thử tổng thể, demo, báo cáo đồ án |
