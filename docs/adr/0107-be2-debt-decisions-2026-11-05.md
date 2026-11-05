# ADR-0107: Quyết định nợ BE-2 còn treo (2026-11-05) — trần mã chứng từ, đóng task đón thú khi thú đã rời, hạn chốt ca ngoài giờ, và các mục chốt hoặc chấp nhận

- **Trạng thái:** Accepted (quyết định của người phụ trách kỹ thuật BE-2 theo ủy quyền của chủ dự án ngày 2026-11-05: "tự quyết hết theo trình độ senior"; không có ý kiến PO. PO không đồng ý mục nào thì sửa bằng ADR mới)
- **Ngày:** 2026-11-05
- **Liên quan:** ADR-0043 (mã chứng từ), 0051, 0064, 0065, 0085, 0086, 0087, 0090, 0103; BR-KO-03, BR-LT-10, BR-TB-05, BR-TG-05; nợ `docs/dept/*` của BE-2.
- **Triển khai:** `DocumentCodeGenerator.MAX_PER_PERIOD`; `CareTaskApi.cancelPickupOverdue` (+ `CareTaskService`, `CareTaskRepository.cancelOpenPickupTasks`) và `BoardingCheckoutService`; `CashierShiftAutoCloseService.deadlineOf`. Không migration, không đổi endpoint.

## A. Ba thay đổi code

### A1. Trần mã chứng từ 99 999 → 999 999 mỗi loại mỗi tháng (thay ADR-0043 mục 4; trả T29-D02 phần số mã)
`orders` là một dãy chung cho Order bán lẻ, khám và lưu trú toàn chuỗi. 10 chi nhánh × 300 Order / ngày ≈ 90 000 / tháng: sát trần cũ, vượt thì **mọi Order mới trả 500** cho tới đầu tháng sau. Định dạng `%05d` đã tự dài ra sáu chữ số từ số 100 000 (`DH-2611-100000`, 14 ký tự, vừa `VARCHAR(20)`); chỉ cần nâng hằng số. Mã dãy vẫn liên tục tuyệt đối (BR-KO-03); sắp xếp theo mã không còn đúng thứ tự thời gian qua mốc 100 000 nhưng không màn hình nào sắp theo mã (danh sách sắp theo `created_at`, `id`). Test: `DocumentCodeIT.theHundredThousandthDocumentOfAMonthGetsASixDigitNumber`.

### A2. Thú đã rời khách sạn thì Care Task `PICKUP_OVERDUE` còn `OPEN` tự đóng (trả T43-2 / F6)
Trước đây task "đón thú quá hạn" nằm mãi trong danh sách của lễ tân sau khi thú đã được giao hoặc đã mất (BR-TB-05 không liệt kê, hệ quả là lễ tân phải hủy tay một task đã hết nghĩa). Quyết định: giao thú (#17) và thú mất (#18) gọi `CareTaskApi.cancelPickupOverdue(List.of(bookingId))` ngay sau khi lưu đặt chỗ và chuồng, **cùng transaction**.
- **Care Task#7** (cùng khuôn #5, #6): một câu `UPDATE … WHERE status = 'OPEN' AND task_type = 'PICKUP_OVERDUE' AND boarding_booking_id IN (…)` → `CANCELLED`, lý do `Thú đã rời khách sạn (BR-LT-10)`, `handled_at` = `Clock`, `handled_by` NULL; đi `uq_care_tasks_pickup_overdue`; `MANDATORY`; tập rỗng không chạm DB; task đã `DONE` / `CANCELLED` không đổi.
- **Khóa:** không thêm cạnh. Boarding đã giữ đặt chỗ `FOR NO KEY UPDATE`; ST15 giữ `pets FOR SHARE` rồi lấy đặt chỗ bằng `SKIP LOCKED` nên bỏ qua đặt chỗ đang được giao, không tạo vòng. Cập nhật không khóa trước hàng `care_tasks` (như #5, #6; ADR-0079).
- **Đã mở rộng `CareTaskApi` của BE-1** (một phương thức mới; không bean khác cài interface, không stand-in trong test). Báo BE-1 ở 06 §6.
- Test: `CareTaskServiceTest`, `CareTaskApiIT.pickupCancellationHitsOnlyTheOpenPickupTasksOfTheGivenBookings` (ma trận trạng thái × đặt chỗ × loại task), `BoardingCheckoutServiceTest`, `BoardingBookingFsmIT.handingOverOrEndingAsDeceasedClosesTheOpenPickupTask` (OPEN trước, CANCELLED sau).

### A3. Ca ngoài giờ không còn khoảng mở cửa kế tiếp tự chốt lúc 00:00 ngày thứ hai sau ngày mở ca (trả T30-4, T30-11; thay ADR-0103 mục 12)
Chi nhánh nghỉ dài hạn (không còn khoảng mở cửa trong 366 ngày) làm `nextOpeningStart` rỗng: trước đây ca ngoài giờ không bao giờ tự chốt, lễ tân bị kẹt "đã có ca mở" (BR-TG-01 chặn mở ca mới). Quyết định: hạn dự phòng `business_date + 2 ngày, 00:00` (từ 26 đến 50 giờ sau lúc mở, đủ để thu nốt khoản cuối ca). `Result.NO_DEADLINE` bỏ vì ca nào cũng có hạn. Test: `CashierShiftAutoCloseServiceTest.afterHoursShiftWithoutANextOpeningClosesAtMidnightOfTheSecondDayAfterOpening`, `anAfterHoursShiftNotYetDueByTheFallbackNeverTakesTheLock`.

## B. Chốt làm baseline (trước đây "chờ PO")

| Nợ | Quyết định |
|---|---|
| T42-1 E1–E12, T43-1 F1–F13, T44-3 G1–G16 | Các giả định trong ADR-0085 / 0086 / 0087 là baseline của đợt giao hàng này. Đã có test từng giả định; PO muốn khác thì supersede bằng ADR mới |
| T44-2 G11 (tỷ lệ quay lại tiêm: mũi chưa hết `next_due_date + grace` ở mẫu số) | Giữ mẫu số theo đặc tả. ADR-0090 đã thêm `pendingCount` vào báo cáo để người xem biết bao nhiêu mũi còn trong hạn quay lại; không cần đổi thêm |
| T43-9 (quản lý không hủy phiên trả thú) | Giữ hợp đồng (A06). Quản lý dùng #10 sau khi thú rời chuồng |
| T29-D02 (Order bán lẻ `OPEN` rỗng bị bỏ dở) | Không làm job dọn. Order `OPEN` không giữ kho, không giữ tiền, chỉ chiếm một mã; `RETAIL_UNCLOSED` của ERD dành cho lễ tân hủy tay. Xem lại khi danh sách Order của lễ tân bị nhiễu thật |
| T18-D03 (điều chỉnh tăng cho sản phẩm chưa từng nhập) | Dùng phiếu nhập kho; điều chỉnh là để sửa chênh lệch của lô đã có |
| T18-D04 (lô số dư 0 ẩn khỏi danh sách) | Giữ; lịch sử lô thuộc UC79 (tầng 3) |
| T31-5 (xóa chuồng `AVAILABLE` làm giảm sức chứa không cảnh báo) | Giữ 204; quản lý dùng bảo trì (giao diện khuyên). Nếu đặt chỗ đã nhận không còn chuồng thì lễ tân dùng hủy vì hết chuồng (boarding-v1 #15, báo khách) |
| T42-3 (kiểm mũi tiêm lưu trú đọc không khóa) | Chấp nhận: cửa sổ vài mili-giây, chỉ khi xóa mũi cùng ngày đúng lúc nhận thú |
| T42-4 (ảnh nhật ký) | Giữ URL `http(s)` ≤ 500 ký tự; upload thật là tính năng riêng của giai đoạn sau |
| T17-D06 (ST02 xóa tài khoản `PENDING` mà hồ sơ đã có thú) | Chấp nhận: hiếm, tối đa một dòng log lỗi mỗi 15 phút cho mỗi tài khoản như vậy; việc của BE-1 (ST02) nếu muốn đổi |
| T17-D07 (chuyển thú vào hồ sơ online đang chờ liên kết) | Chấp nhận: khách mất đường liên kết `HAS_DATA` đúng như UC07 đã nêu, lễ tân xử lý tại quầy |
| T17-D09 (N+1 `PetResponse`), T17-D10 (cân bất thường) | Chấp nhận: vài thú mỗi khách; không có khoảng cân hợp lý theo loài trong đặc tả |
| T19-D03, T29-D01 (không idempotency khi thêm dòng / tạo Order) | Chấp nhận; FE khóa nút, tổng luôn tính lại từ dòng |
| T30-2, T44-4 (chạy lại digest gửi lại thông báo trong app) | Chấp nhận: chỉ khi khởi động lại đúng giờ chạy hoặc chạy tay |
| T43-4 (ST15 gọi `CareTaskApi.create` mỗi lượt) | Chấp nhận ở quy mô vài chục thú quá hạn (một `INSERT … ON CONFLICT DO NOTHING` cho mỗi thú) |
| T42-8, T31-8, T31-10, T43-5, T43-8, T45-3, T30-5, T18-D05, D06 | Chấp nhận như đã ghi trong từng file nợ |
| T30-15 (lời văn BR-BH-05 "quá 1 ngày") | Không sửa 02-business-rules; mốc thực hiện ghi ở ADR-0051 / 0064 |

## C. Không thuộc BE-2, giao lại

- **FE-1:** nút gọi sales #11 ở `ShortageNotice` của `CheckoutPanel` (hợp đồng đã ghi ở `docs/be-fe-integration.md`), `linkUrl` của thông báo ST13, đối chiếu mock FE với BE thật (T42-5, T43-6, T44-8). Không sửa UI đang được FE-1 làm dở.
- **BE-1:** `VisitCheckInServiceTest.ownerMismatch…` đỏ sẵn, nợ T31-12, quét khóa-trước-khi-đọc cho module của họ (ADR-0106).
