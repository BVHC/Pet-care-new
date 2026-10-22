# ADR-0048: Xem lịch hẹn — khách theo `customer_id`, nhân viên theo chi nhánh, bộ lọc AND, cửa sổ check-in có chặn trên, tên ghép theo lô, cờ theo người xem

- **Trạng thái:** Accepted
- **Đánh số lại:** ban đầu là ADR-0044; đổi thành số hiện tại khi gộp `dev` ngày 2026-10-20 vì trùng số với ADR-0041…0044 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0041…0046 trong code / tài liệu lịch hẹn đã đổi theo (+4); migration `V13__appointment_code_sequence.sql` đổi thành V16.
- **Ngày:** 2026-10-15
- **Liên quan:**
  - BR-LH-06 (02 L127), 07 (L128), 08 (L129), 11 (L132); BR-TN-02 (L142), 03 (L143); BR-KH-08 (L94).
  - UC40, UC44 (01 L105, L114).
  - appointment-v1 #3, #4 (`docs/api/appointment-v1.md` mục A, B; A3 "danh sách chờ check-in là bộ lọc `checkInReady`").
  - `docs/api/00-method.md`: L40 (phạm vi chi nhánh, khách không có phạm vi chi nhánh), L48 (phân trang), L64–66 (403 / 404), L70 (thứ tự kiểm).
  - [CFG] `visit.check_in_early_minutes`, `appointment.no_show_after_minutes`, `appointment.max_reschedules`, `appointment.reschedule_min_lead_hours`.
  - ADR-0006 (phạm vi khách), 0020 (OSIV tắt), 0038 (không `:x IS NULL OR`; readOnly + REPEATABLE READ), 0046 (một snapshot chỉ đọc), 0047 (`AppointmentActions`; thú của khách khác = 404).
  - Nợ D010, D028, D031, D032.
- **Triển khai (T16 phần 3):**
  - `appointment/controller/AppointmentController` (`GET /api/appointments`, `GET /api/appointments/{appointmentId}`).
  - `appointment/service/{AppointmentQueryService, CheckInWindow}`; `AppointmentActions.Flags.NONE`.
  - `appointment/dto/AppointmentListFilter`; `AppointmentRepository extends JpaSpecificationExecutor`.
  - `customer/api/PetQueryApi.findPets`, `customer/api/CustomerQueryApi.findContacts` (thêm vào hợp đồng với BE-2) + hai placeholder.
  - Hợp đồng: `docs/api/generator/m_appointment.py` #3 (notes, 400, 403), #4 (thêm 400; 403, 404, notes).
  - Test:
    - Unit: `CheckInWindowTest`, `AppointmentQueryServiceTest`, `AppointmentActionsTest`, `AppointmentControllerSecurityTest`, `PetQueryApiPlaceholderTest`, `CustomerQueryApiPlaceholderTest`.
    - IT: `module/appointment/{AppointmentViewIT, AppointmentViewPlaceholderIT}`, `JdbcPetCustomerModule` (thêm hai hàm theo lô có bộ đếm); `OpenApiContractParityIT` (49 endpoint); `BookAppointmentIT` (405 đổi sang PUT vì GET đã có handler).

## Bối cảnh

Hợp đồng #3, #4 chỉ ghi tham số, actor và một câu phạm vi "Khách: lịch của mình · nhân viên: chi nhánh mình". Docs để ngỏ:

- **Lịch "của mình":** sau khi chuyển chủ (BR-KH-08), lịch đã đặt vẫn mang `customer_id` của chủ cũ.
- **Nghĩa của `checkInReady`:** BR-TN-02 chỉ nói "sớm nhất 30 phút trước giờ hẹn". Còn BR-TN-03 / BR-LH-08 nói quá 30 phút là NO_SHOW, mà ST05 (T25) chưa có.
- **Thứ tự sắp xếp.**
- **Tên thú / tên khách cho cả trang:** `customer.api` chỉ có `findPet(id)` / `findContact(id)`.
- **403 hay 404:** chưa rõ cho lịch của khách khác, chi nhánh khác, và vai trò không có trong hợp đồng.

Người dùng chốt (2026-10-15) bốn điểm:
1. Thêm hàm theo lô.
2. Sắp xếp tăng dần theo giờ hẹn.
3. Lọc theo `appointments.customer_id`.
4. `checkInReady` có chặn trên.

## Quyết định

1. **Vai trò:** #3 cho CUSTOMER, BRANCH_MANAGER, RECEPTIONIST; #4 thêm VET, CARETAKER. ADMIN, SUPER_MANAGER không có trong hợp đồng → 403 `ACCESS_DENIED` (`@PreAuthorize`, không audit).

2. **Phạm vi của khách:**
   - Luôn thêm `customer_id` = hồ sơ của tài khoản (`CustomerQueryApi.findCustomerIdByAccountId`; không có → 500, BR-KH-01). Đây là **chủ lúc đặt**: chủ cũ vẫn thấy lịch sử lịch hẹn của thú đã chuyển; chủ mới không thấy. Cùng tinh thần "Order cũ ở lại với chủ cũ" (BR-KH-08).
   - `customerId`, `petId`, `branchId` khách gửi chỉ là bộ lọc AND. `customerId` của người khác → trang rỗng.
   - Khách không đi qua `BranchScope` (ADR-0006 §4).

3. **Phạm vi nhân viên:** `BranchScope.resolve(branchId)` chạy trước mọi lần đọc DB. `branchId` vắng hoặc trùng → chi nhánh của mình; khác (kể cả ≤ 0) → 403 `ACCESS_DENIED_SCOPE_MISMATCH`.

4. **Chi tiết #4:**
   - Không có → 404 "Không tìm thấy lịch hẹn #id".
   - Khách xem lịch của khách khác → **cùng 404** (00-method L66).
   - Nhân viên xem lịch chi nhánh khác → 403 qua `BranchScope.check`, sau bước tra (00-method L40).

5. **Bộ lọc:**
   - JPA `Specification` chỉ ghép điều kiện được gửi, không bao giờ `:x IS NULL OR`. Mọi điều kiện là AND với nhau và với phạm vi.
   - `date` khớp đúng ngày; `[from, to]` là khoảng đóng; `from > to` cho trang rỗng (không 400). `checkInReady` vắng / rỗng / false = không lọc.
   - Tham số tùy chọn rỗng (`status=`) coi như không gửi. Sai kiểu → 400 `MALFORMED_REQUEST`. Enum phân biệt hoa thường; boolean nhận `1` / `true`.
   - Gửi lặp `status=A&status=B` → lấy giá trị đầu. Đây là hành vi của Spring, IT đã khóa.

6. **`checkInReady=true`** (lớp thuần `CheckInWindow`):
   - Điều kiện: `status = BOOKED`, `slot_date` = hôm nay (giờ Việt Nam), và `giờ hẹn − visit.check_in_early_minutes ≤ now ≤ giờ hẹn + appointment.no_show_after_minutes`. Hai biên đều tính là được.
   - Quy về `earliestStart ≤ slot_start ≤ latestStart`. Mốc dưới rơi sang hôm trước → `00:00`. Mốc trên rơi sang hôm sau → không chặn trên. Không bind `LocalTime.MAX`, vì pgjdbc làm tròn thành `24:00:00`.
   - **Nghĩa vụ:** Visit#1 (T26) phải dùng `CheckInWindow.contains` khi check-in. ST05 (T25) chuyển NO_SHOW khi `now > giờ hẹn + noShowAfter`, khớp chặn trên này.

7. **Sắp xếp / trang:** `slot_date, slot_start, id` tăng dần cho mọi người xem. `page < 0 → 0`, `size` kẹp 1–100 (khuôn #5 care, #28 identity).

8. **Transaction:**
   - Hai method là `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`: count, trang, tên và cờ cùng một snapshot. Không khóa, không ghi.
   - Lời gọi sang customer / branch / catalog tham gia transaction này.
   - Transaction chỉ đọc ở REPEATABLE READ không gặp serialization failure, nên không có đường 409.

9. **Tên hiển thị:**
   - Gom id khác nhau của trang:
     - `PetQueryApi.findPets` và `CustomerQueryApi.findContacts`: mới, mỗi trang một lần.
     - `CatalogQueryApi.findServices`: có sẵn, không lọc `active`, nên dịch vụ đã ngừng vẫn hiện tên.
     - `BranchQueryApi.findBranch`: gọi mỗi id chi nhánh khác nhau.
   - Ghép theo id, không theo thứ tự trả về. Id thiếu hoặc trùng → `IllegalStateException` (500), không trả tên null.
   - **Trang rỗng không gọi module nào.**
   - #4 dùng cùng đường đi với một phần tử.

10. **Cờ `canReschedule` / `canCancel`:**
    - CUSTOMER → cờ của khách; RECEPTIONIST → cờ của lễ tân (`AppointmentActions.forViewer`, ADR-0047 §6).
    - BRANCH_MANAGER, VET, CARETAKER → `Flags.NONE`, vì hợp đồng #5 / #6 chỉ cho A02, A06.
    - `now` và hai [CFG] đọc một lần cho cả trang. Phần 4 (đổi / hủy) phải dùng đúng các quy tắc này làm guard.

11. **Không làm:**
    - Không chặn BR-TK-19: hồ sơ online còn cờ chờ liên kết thì không thể có lịch.
    - Không audit (convention 08 §8.3).
    - `visitId` vẫn null (D028).
    - Không thêm index (D032).

## Lý do và phương án bị loại

- **Gọi `findPet` / `findContact` từng dòng:** tới ~200 lời gọi mỗi trang 100 dòng khi BE-2 cài thật (N+1). Loại.
- **Khách xem theo chủ hiện tại của thú:** cần thêm hàm "thú của khách", và chủ cũ mất lịch sử lịch hẹn. Loại.
- **`checkInReady` không chặn trên (đúng nguyên văn BR-TN-02):** khi ST05 chưa chạy, lịch trễ cả buổi vẫn nằm trong danh sách chờ, trái BR-TN-03 / BR-LH-08. Loại.
- **`from > to` → 400 như ADR-0038 R1:** cần thêm ràng buộc chéo và validator riêng. Hợp đồng không yêu cầu; trang rỗng là kết quả đúng của phép AND. Loại.
- **Thêm index `(branch_id, slot_date, slot_start)` bằng V14 ngay:** V13 còn chưa được BE-2 xác nhận số. Hai dạng nóng đã dùng index (IT EXPLAIN). Để nợ D032.
- **Thứ tự giảm dần / theo vai trò:** màn hình lễ tân theo ngày và danh sách chờ check-in cần tăng dần. Khách xem lịch sắp tới bằng `status=BOOKED`. Loại.

## Hệ quả

- **Hợp đồng với BE-2 đổi:** `PetQueryApi`, `CustomerQueryApi` có thêm một method trừu tượng mỗi bên (06 §6). Placeholder ném lỗi.
- **App thật:**
  - Khách luôn 500 (D010).
  - Nhân viên 500 khi trang có dòng (D031, D010). Trang rỗng 200.
  - Vai trò sai vẫn 403 (`AppointmentViewPlaceholderIT`).
- **Hợp đồng #4 thêm 400** (id sai kiểu, BR-TK-17), cùng mẫu identity #17 và care #7.
- **Thứ tự lỗi thực tế:** 401 → 400 BR-TK-17 (interceptor `preHandle`) → 400 `MALFORMED_REQUEST` (ép kiểu) → 403 vai trò → 403 phạm vi / 404. IT khóa thứ tự này.
- **Đã chứng minh bằng IT + mutation** (bỏ cơ chế thì test đỏ):
  - bỏ điều kiện `customer_id`;
  - bỏ `resolve`;
  - bỏ chặn trên `checkInReady`;
  - biên `≤` thành `<` ở hai đầu;
  - bỏ `REPEATABLE_READ`.
- **Không đảm bảo:**
  - Câu "cả ngày của chi nhánh, mọi trạng thái" chưa có index riêng: EXPLAIN dùng `ix_appointments_status_slot` với `slot_date` không phải cột đầu, rồi lọc `branch_id`. `count(*)` chính xác + OFFSET (D032).
  - Plan thật của Hibernate dùng tham số bind. Sau 5 lần thực thi, pgjdbc có thể chuyển sang generic plan khác plan literal đã EXPLAIN.
