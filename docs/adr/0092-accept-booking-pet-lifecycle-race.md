# ADR-0092: Chấp nhận race đặt/đổi lịch ∥ thú mất / chuyển chủ (D027), xử lý ở quầy

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-02
- **Liên quan:** BR-KH-05, BR-KH-08, BR-LH-05, BR-LH-07, BR-LH-09, BR-TN-01; 03 Lịch hẹn#1, #2, #5; ADR-0045 (khóa đặt lịch), 0047, 0049, 0050 §10, 0052 §2–3 (nghĩa vụ "customer lấy advisory 1001"), 0084 (`@Order` listener), 0057 (người dùng chốt thay PO). Nợ: D027 (đóng ở dạng chấp nhận, không trả).
- **Triển khai:** `visit/service/VisitCheckInService` (check-in lịch mồ côi sau chuyển chủ: 500 → 400 `BR-TN-01`); `VisitCheckInIT.orphanAppointmentOfAPreviousOwnerIsRejectedAndRollsBackTheAppointmentUpdate`. Không migration, không contract, không [CFG].

Người dùng chốt ngày 2026-11-02: đồ án, không có người dùng thật; không đóng race, để lễ tân xử lý ở quầy. Các ADR cũ giữ nguyên (immutable); ADR này thay các nghĩa vụ của chúng.

## Vấn đề

Đặt / đổi khung giờ giữ advisory `(1001, pet)` rồi đọc thú một lần. `PetLifecycleService.markDeceased / transferOwner` (customer, BE-2) chỉ khóa `pets` / `customers` `FOR NO KEY UPDATE`, **không** lấy 1001, và đặt lịch dùng `PetQueryApi.findPet` (không khóa `pets`). Hai luồng trên cùng một thú, chen nhau trong vài ms: customer hủy các lịch `BOOKED` mà listener *thấy*, lượt đặt commit sau đó → còn một lịch `BOOKED` của thú đã mất / chủ cũ. Không mất dữ liệu, không deadlock.

## Quyết định

1. **Chấp nhận race.** Customer **không bắt buộc** lấy advisory 1001; không thêm `AppointmentApi.lockPetForScheduling`. Listener của appointment vẫn không tự khóa (ADR-0052 §2; nếu lấy khóa sẽ đảo thứ tự `pets` → advisory). Nếu BE-2 vẫn muốn lấy 1001 thì phải **trước** `pets` và không khóa `medical_records` / `care_tasks` trước các listener (ADR-0084).
2. **`existsByPet` (xóa thú, BR-KH-06) không còn điều kiện "sau khóa 1001".** Lượt đặt chen vào sau phép kiểm bị FK `RESTRICT` chặn (409); không mất dữ liệu.
3. **Sửa duy nhất ở code:** check-in một lịch hẹn mà `appointments.customer_id` ≠ chủ hiện tại của thú trả **400 `BR-TN-01`** ("… thuộc khách khác (thú đã chuyển chủ): hủy lịch hẹn rồi tiếp nhận thú như khách walk-in") thay cho `IllegalStateException` (500). Ném sau `AppointmentApi.checkIn` trong cùng transaction nên rollback hết (lịch vẫn `BOOKED`, không Visit / Order). Thú đã mất vốn đã là 400 `BR-TN-01` ("Thú cưng đã mất, không tiếp nhận được").

## Quy trình quầy (thay cho khóa)

- **Nhận biết:** hệ thống không có cảnh báo riêng cho lịch mồ côi. Dấu hiệu: khách gọi, hoặc check-in báo 400 `BR-TN-01` như trên (thú đã mất / chuyển chủ).
- **Xử lý:** lễ tân hủy lịch ngay (appointment-v1 #6), rồi tiếp nhận thú như khách walk-in cho đúng chủ (Visit#1).
- **Hủy muộn oan:** `cancelAppointment` tính `late` theo giờ, không phân biệt lịch mồ côi. Còn ≥ 12 giờ: không tính muộn. Còn < 12 giờ: chủ cũ bị ghi hủy muộn và có thể bị hạn chế đặt online (BR-LH-09); lễ tân hoặc BRANCH_MANAGER **gỡ hạn chế sớm** (có audit, sẵn có). Không thêm cờ "hủy do phòng khám" cho lễ tân.
- **Không ai xử lý:** ST05 chuyển lịch sang `NO_SHOW`, tính vào BR-LH-09 của chủ cũ; lịch còn chiếm quota khung giờ và 1 trong 2 suất `BOOKED` của thú.

## Cách trả nếu cần đóng thật

Appointment (BE-1, một mình) gọi `PetQueryApi.findPetForShare` thay `findPet` sau `locks.lockPet` ở `AppointmentBookingService` và `AppointmentChangeService.rescheduleAppointment`: thứ tự `advisory(1001) → pets FOR SHARE → branches → appointments`. `FOR SHARE` xung đột với `FOR NO KEY UPDATE` của customer nên hai bên xếp hàng (đặt trước → listener hủy được; customer trước → đặt thấy thú đã mất / đổi chủ). Hủy lịch (`cancelAppointment`) không khóa `pets` nên không tạo vòng chờ với customer (`pets` → `appointments`). Cần IT race hai thứ tự + mutation bỏ `FOR SHARE` (quy ước). Là cách duy nhất còn lại; hướng cũ (customer lấy 1001) bị thay.

## Không làm

Không đổi hợp đồng hủy lịch, không cảnh báo lịch mồ côi trên danh sách lễ tân, không mutation / bảo đảm đồng thời mới (chấp nhận, không thêm khóa).
