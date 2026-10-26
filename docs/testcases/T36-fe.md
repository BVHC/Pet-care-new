# T36 (FE): Tiếp nhận, hàng đợi, gọi lượt, cấp cứu (UC44–UC47)

> Phạm vi của tài liệu này: **A. Mock tiếp nhận, hàng đợi, gọi lượt** (types, `visit.api.ts`, hooks, `visit.mock.ts`). Phần **B. Giao diện tiếp nhận, hàng đợi, gọi lượt** (màn hình, ma trận vai trò, ca kiểm thử, giả định, phần để lại cho T37) ở cuối tài liệu.
> Hợp đồng: `docs/api/visit-v1.md` + `docs/api/openapi/visit-v1.yaml` (#1–9, #11, `GET /me/queue`), `docs/api/appointment-v1.md` #3 (`checkInReady`); ADR 0058–0063. Rule: BR-TN-01…09, BR-LH-08, BR-KH-05, BR-CN-05, BR-QT-08, BR-QT-11. **Khi docs lệch BE thì theo BE** (mục A.8).
> File: `FE/src/shared/types/visit.ts`, `api/visit.api.ts`, `hooks/useVisits.ts`, `api/mock/visit.mock.ts` (+ test cạnh từng file). Route mock đăng ký trong `mock-api.ts` (`...visitRoutes`).

## A. Mock tiếp nhận, hàng đợi, gọi lượt

### A.1 Tài khoản mock (mật khẩu `Petcare123`)

| Email | Vai trò | Id | Chi nhánh | Dùng cho |
|---|---|---|---|---|
| `reception@store1.vn` | RECEPTIONIST | 4 | 1 | tiếp nhận, gán, sắp lại, hủy lượt, đánh dấu cấp cứu |
| `doctor@store1.vn` | VET | 5 | 1 | hàng đợi của tôi, gọi lượt, đánh dấu cấp cứu |
| `caretaker@store1.vn` | CARETAKER | 6 | 1 | hàng đợi của tôi (Thẩm mỹ), gọi lượt |
| `manager@store1.vn` | BRANCH_MANAGER | 3 | 1 | xem hàng đợi, gán lại lượt đã gọi |
| `chuoi@petcare.vn` | SUPER_MANAGER | 2 | — | chi tiết lượt mọi chi nhánh; gán lại khi chi nhánh không còn BRANCH_MANAGER ACTIVE |
| `admin@petcare.vn` | ADMIN | 1 | — | khóa / mở khóa tài khoản (dựng "người phụ trách không còn đủ điều kiện") |
| `manager2@store2.vn` | BRANCH_MANAGER | 9 | 2 (DRAFT) | chi nhánh khác, chưa ACTIVE |

Mock chỉ có **một** VET (#5) và **một** CARETAKER (#6) ở chi nhánh 1; thêm người bằng `POST /api/staff` (SM tạo, id ≥ 200). Nhân viên tạo lúc chạy **không đăng nhập được** trong mock (có từ trước T36), nên gán / gán lại cho họ được nhưng đóng vai họ phải gọi thẳng handler (xem test).

BE thật (DB dùng-một-lần `petcare_seed`, mật khẩu `Petcare@2026`): `mai.le@` (#4) và `anh.pham@` (#5) lễ tân; `khoi.vo@` (#6, online) và `hang.dang@` (#7, offline) VET; `tai.bui@` (#8) và `lan.ngo@` (#9) CARETAKER (offline); `hung.tran@` (#3) BRANCH_MANAGER; `ha.nguyen@` (#2) SUPER_MANAGER; `admin@` ADMIN. Chi nhánh 1 "PetCare Tây Hồ", giờ mở T2–T7 08:00–12:00 và 13:30–19:00, Chủ nhật sáng, cờ cấp cứu ngoài giờ BẬT. Mã khách / thú / dịch vụ của mock **khác** DB thật (vd thật: thú #3 là Lu của khách #2, #4 là Miu; mock: #3 Tôm, #4 Lu, #5 Miu).

### A.2 Kết quả dò BE thật (24/10/2026, `http://localhost:8081`)

Dò bằng HTTP thật, ghi được vì DB dùng-một-lần (`petcare-seed-pg:5433`; không đụng `localhost:5432`). Mọi dòng dưới đây là QUAN SÁT.

**Hình dạng.** `Visit` (cùng một kiểu cho hàng đợi #2, hàng đợi của tôi #3, chi tiết #4, và kết quả #1, #7, #8, #9, #11; #6 trả mảng `Visit`) luôn đủ **22 trường**, chưa có giá trị thì `null`:
`visitId, code, branchId, customerId, customerName, petId, petName, species, appointmentId, serviceGroup, status, priorityClass, queueSortKey, assigneeId, assigneeName, needsReassign, orderId, checkedInAt, calledAt, completedAt, cancelledAt, cancelReason`.
- `code` `TN-2610-00001` (`TN-yyMM-nnnnn`, bộ đếm toàn chuỗi, reset theo tháng).
- Thời điểm là `Instant` có tới **6 chữ số lẻ**: `2026-10-24T07:46:58.501073Z`; chẵn giây thì không có phần lẻ (`2026-10-24T08:00:00Z`). `queueSortKey` sau khi sắp lại lệch nhau 1 µs (`…58.146223Z` đứng trước `…58.146224Z`) mà `Date.parse` của JS chỉ tới mili giây: **FE không tự sắp lại, dùng thứ tự mảng BE trả**.
- Thứ tự hàng đợi: hạng (cấp cứu → lịch hẹn → walk-in) → `queueSortKey` → `visitId`, **không phân biệt WAITING / IN_PROGRESS** (lượt đang khám xen vào đúng hạng của nó).
- `GET /visits`, `GET /me/queue`, `POST /visits/{id}/move` trả **mảng trần** (không phân trang); envelope `{ data, message, code }`. #5 trả `{ visit, assigneeOffline }`.
- Lỗi: `{ success:false, errorCode, message, statusCode, timestamp, traceId }`, mã rule nối cuối message `(BR-…)`.
- Đăng nhập thật **không** trả `branchId` trong `account`; chi nhánh ở `GET /api/me` → `staffProfile.branchId` (mock thì có trong `account.branchId`).

**Thông điệp thành công:** `Đã tiếp nhận` (201), `Đã gán nhân viên`, `Đã sắp lại hàng đợi`, `Đã xác định ca cấp cứu`, `Đã gọi lượt`, `Đã gán lại lượt`, `Đã hủy lượt`.

**Ma trận vai trò (dò `99999` để chỉ thấy lớp quyền; ô trống = 403):**

| Endpoint | RECEPTIONIST | VET | CARETAKER | BRANCH_MANAGER | SUPER_MANAGER | ADMIN |
|---|---|---|---|---|---|---|
| `POST /visits` | OK | | | | | |
| `GET /visits` | OK | OK | OK | OK | | |
| `GET /me/queue` | | OK | OK | | | |
| `GET /visits/{id}` | OK | OK | OK | OK | OK | |
| `POST …/assign`, `…/move`, `…/cancel` | OK | | | | | |
| `POST …/mark-emergency` | OK | OK | | | | |
| `POST …/call` | | OK | OK | | | |
| `POST …/reassign` | | | | OK | OK (điều kiện) | |
| `GET /staff` | **403** | 403 | 403 | OK | OK | OK |
| `GET /appointments?checkInReady=true` | OK | 403 | 403 | OK | 403 | 403 |

**"Vai trò ngoài ma trận bị 500" (`requireRole` ném `IllegalStateException`) — KHÔNG tái hiện được, không phải lỗi BE.** Chạy 6 vai trò × 12 endpoint ở trên không có mã 5xx nào. Lý do (đọc code): tập vai trò của `@PreAuthorize` (`VisitController` L55–71, `MyQueueController` L27) trùng từng chữ với tập `Set.of(...)` trong service (`VisitQueueService` L53–60, `VisitDispatchService` L62–65, `VisitProgressService` L52–55, `VisitReassignService` L62), nên `requireRole` là lớp phòng thủ thứ hai không bao giờ chạm tới. Mock trả 403 cho vai trò ngoài ma trận. (CUSTOMER không dò được: DB mẫu không có tài khoản khách; cùng lập luận: không endpoint nào có CUSTOMER trong `@PreAuthorize`.)

**Thứ tự lỗi quan sát:** đọc JSON / tham số path sai kiểu và Bean Validation → 400 **trước** quyền 403, kể cả ADMIN (`GET /visits/abc` → 400 `Tham số 'visitId' sai kiểu dữ liệu`; VET gửi `{}` vào `/assign` → 400 `assigneeId: Thiếu nhân viên được gán`).

**Staff picker.** `GET /api/staff?role=VET&status=ACTIVE&size=100` với lễ tân → **403** `ACCESS_DENIED` (đã dò). Quản lý / quản lý chuỗi / admin gọi được; `StaffResponse.online` có thật (VET #6 online, #7 offline; CARETAKER #8 / #9 offline). Tài khoản bị khóa **vẫn `status: ACTIVE`** (chỉ `isLocked: true`) nên FE phải loại `isLocked` (gán sẽ 400 BR-TN-05).

**Jackson:** trường lạ bị bỏ qua (`{"emergency":true}` → 201 nhưng `WALK_IN`); số id dạng chuỗi `"3"`, `isEmergency` `"true"` / `1` và `reason: 5` (thành `"5"`) được ép kiểu; `3.5`, `"yes"`, `reason: ["x"]` → 400 `MALFORMED_REQUEST`.

**Check-in lịch hẹn trễ (đã dò lúc 15:16:34 giờ VN, lịch hẹn 15:00):** trễ 16 phút vẫn check-in được: 201, `priorityClass: WALK_IN`, **`appointmentId` giữ nguyên**, `queueSortKey = checkedInAt` (giờ check-in, không phải giờ hẹn). Lúc 14:49 (sớm 11 phút) thì `APPOINTMENT` với `queueSortKey` = giờ hẹn (`2026-10-24T08:00:00Z`). Lịch ngoài cửa sổ → 400 BR-TN-02 `Chỉ tiếp nhận lịch hẹn của hôm nay, sớm nhất 30 phút trước giờ hẹn và trễ nhất 30 phút sau giờ hẹn`. Check-in lại lịch đã CHECKED_IN / CANCELLED → 409 `Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang CHECKED_IN`. BR-TN-03 **không bao giờ thành lỗi gửi cho client**: trễ chỉ đổi hạng ưu tiên (WALK_IN), nên `visitErrorMessage` không có nhánh cho nó.

**Hủy dây chuyền (đã dò):** hủy lượt có lịch hẹn → lịch `CANCELLED`, `cancelSource: STAFF`, **`cancelReason: null`** (lý do của lượt không chép sang), `lateCancel: false` dù sát giờ (hạn chế đặt online của khách không đổi), `visitId` giữ nguyên, `cancelledAt` = lúc hủy lượt. Order → `CANCELLED`, `cancelType: VISIT_CANCELLED`, `cancelReason: null`, dòng tự sinh còn nguyên.

**BR-QT-08 (đã dò):** SUPER_MANAGER vô hiệu hóa VET #7 đang giữ lượt #1 IN_PROGRESS → 400 `Không vô hiệu hóa được: còn lượt tiếp nhận #1 chưa hoàn tất (BR-QT-08)`.

**Job ST05 (NO_SHOW):** `application.yml:89` `NO_SHOW_CRON` mặc định `0 */5 * * * *`: mỗi 5 phút.

**Dấu vết để lại trên DB dùng-một-lần `petcare_seed`** (không đụng DB nào khác): lượt #1–#13 (tiếp nhận walk-in / cấp cứu / lịch hẹn; #1 và #2 IN_PROGRESS, #3 #5 #6 #8 CANCELLED, còn lại WAITING), lịch hẹn #1–#5 hôm 24/10, thú "Ghost" (#5, khách #2, đã đánh dấu mất), các Order VISIT tương ứng. Các tài khoản đã khóa để thử (#3 quản lý, #6 VET, #9 CARETAKER) đã **mở khóa lại**; không còn tài khoản nào bị khóa hay vô hiệu hóa.

### A.3 Dữ liệu mẫu (visit.mock.ts, dựng lười lúc chạm visit lần đầu, tính theo giờ nạp, chi nhánh 1)

Dựng một lần rồi lưu (`petcare-mock-visit`), kèm: Order VISIT OPEN cho lượt đang mở (tạo bên `sales.mock`, theo `visitId`, không lặp) và `busy: OPEN_VISIT` cho thú của lượt đang mở (bên `customer-pet.mock`). **Hai việc này chỉ xảy ra sau lần chạm visit đầu tiên**: trước đó `GET /api/orders` chưa thấy Order VISIT OPEN và thú chưa bị khóa đánh dấu mất.

| Lượt | Mã (nạp lúc 23/10/2026 10:40) | Thú / khách | Nhóm | Trạng thái | Hạng | Người phụ trách | Lịch hẹn | Order | Ghi chú |
|---|---|---|---|---|---|---|---|---|---|
| 2101 | TN-2610-00008 | Miu / Lê Thị Hoa (#4) | MEDICAL | WAITING | EMERGENCY | chưa gán | — | mới (OPEN) | cấp cứu chờ gán (check-in 20' trước) |
| 2002 | TN-2610-00005 | Lu / Lê Hoàng Long (#3) | MEDICAL | IN_PROGRESS | APPOINTMENT (khóa = 08:30) | VET #5 | #10 (CHECKED_IN) | mới (OPEN) | khớp lịch hẹn mẫu #10 |
| 2102 | TN-2610-00007 | Tôm / Nguyễn Thu Trang (#1) | MEDICAL | WAITING | WALK_IN | VET #5 | — | mới (OPEN) | VET #5 gọi được |
| 2103 | TN-2610-00009 | Bim / Nguyễn Thu Trang | GROOMING | WAITING | WALK_IN | CARETAKER #6 | — | mới (OPEN) | CARETAKER #6 gọi được |
| 2104 | TN-2610-00006 | Mochi / Nguyễn Thu Trang | GROOMING | IN_PROGRESS | WALK_IN | **#12 "Phạm Quốc Việt" (không còn trong danh bạ)** | — | mới (OPEN), dòng thuộc #12 | `needsReassign: true`, gán lại được |
| 61 | TN-2610-00004 | Mochi / #1 | MEDICAL | COMPLETED hôm nay | WALK_IN | VET #5 | — | **#2** (PENDING) | checkedIn = Order.createdAt, completedAt = Order.pendingAt |
| 58 | TN-2610-00003 | Miu / #4 | MEDICAL | COMPLETED 2 ngày trước | WALK_IN | VET #5 | — | **#3** (PENDING) | |
| 40 | TN-2610-00002 | Miu / #4 | MEDICAL | COMPLETED 6 ngày trước | WALK_IN | VET #5 | — | **#7** (CANCELLED UNPAID) | |
| 2001 | TN-2610-00001 | Mochi / #1 | MEDICAL | COMPLETED 13 ngày trước | APPOINTMENT | VET #5 | #4 (COMPLETED) | **`orderId: 0` (không có Order trong seed bán lẻ)** | |

Mã cấp theo giờ check-in tăng dần trong từng tháng `yyMM` (bộ đếm toàn chuỗi như BE), nên dữ liệu mẫu nạp gần ranh giới tháng sẽ có tiền tố khác (vd lượt cũ thuộc tháng trước mang `TN-2609-…`); bảng trên là bản nạp lúc 23/10/2026 10:40. Order mới của lượt đang mở có id từ 101 (id Order và id dòng dùng chung một bộ đếm của `sales.mock`).

Với giờ 10:40: thứ tự hàng đợi mặc định `2101 → 2002 → 2104 → 2102 → 2103`. Lịch hẹn mẫu còn BOOKED: #1 Mochi Khám 11:00 hôm nay (vào danh sách chờ check-in từ 10:30 đến 11:30), #11 Lu mai 10:00. Mã `TN-yyMM-nnnnn` cấp theo giờ check-in tăng dần; lượt mới bắt đầu từ `visitId` **3001**.

Người phụ trách #12 và lượt 2001 `orderId 0` là hai chỗ dữ liệu mẫu **không khớp hoàn toàn** thực tế (xem A.8). Người phụ trách #12 nằm ngoài danh bạ nên `findStaff` rỗng → không còn đủ điều kiện (BR-TN-08), nên gán lại được; BE thật dựng cờ này bằng khóa tài khoản.

### A.4 Từng endpoint: rule và test (visit.mock.test.ts, 84 test)

Thứ tự kiểm chung (chép từ BE): **đọc JSON / tham số path + Bean Validation → vai trò 403 → lượt 404 → chi nhánh 403 (ACCESS_DENIED_SCOPE_MISMATCH) → guard 400 / 409** (thứ tự riêng ghi ở từng dòng).

| # | Route mock | Quyền | Rule đã mô phỏng (thứ tự kiểm) | Test (describe › it) |
|---|---|---|---|---|
| 1 | `POST /api/visits` | RECEPTIONIST | body (petId @NotNull @Positive; appointmentId, serviceId @Positive; `isEmergency` Boolean) → thú 404 → lịch 404 / lịch chi nhánh khác 400 BR-TN-02 → dịch vụ 404 → BR-TN-01 (walk-in thiếu dịch vụ → thú không khớp lịch → thú đã mất) → BR-CN-01 → BR-CN-05 → BR-LH-01 → lịch 409 → cửa sổ 400 BR-TN-02 → tạo Lượt WAITING + Order VISIT OPEN + lịch → CHECKED_IN. Hạng: cấp cứu → EMERGENCY (khóa = lúc check-in, kể cả có lịch); có lịch và `now ≤ giờ hẹn + 15'` → APPOINTMENT (khóa = giờ hẹn); còn lại WALK_IN. | `tiếp nhận walk-in`, `check-in lịch hẹn`, `tiếp nhận: thứ tự kiểm và rule`, `tiếp nhận: ngoài giờ mở cửa`, `tiếp nhận: dữ liệu vào và quyền` |
| 2 | `GET /api/visits` | A05–A08 | tham số sai kiểu → 400 theo thứ tự `date, status, serviceGroup, assigneeId, needsReassign`; lọc theo chi nhánh người gọi; A1 (xem A.2); sắp BR-TN-04 | `hàng đợi chi nhánh (#2…)` |
| 3 | `GET /api/me/queue` | VET, CARETAKER | WAITING / IN_PROGRESS gán cho người gọi, mọi chi nhánh | `hàng đợi của tôi (#3…)` |
| 4 | `GET /api/visits/:visitId` | A04–A08 | path → vai trò → 404 → chi nhánh 403 (SUPER_MANAGER toàn chuỗi) | `chi tiết lượt (#4)` |
| 5 | `POST /api/visits/:visitId/assign` | RECEPTIONIST | path → body (`assigneeId`) → vai trò → 404 → chi nhánh → **400 BR-TN-05 TRƯỚC 409** → ghi; gán đúng người hiện tại: 200 không ghi; `assigneeOffline = !online`; dòng tự sinh + dòng của người cũ chuyển quyền | `gán nhân viên (#5…)` |
| 6 | `POST /api/visits/:visitId/move` | RECEPTIONIST | path → body → vai trò → nguồn 404 → chi nhánh → chính nó 400 → đích 404 → đích khác chi nhánh 400 → ranh giới cấp cứu 400 → nguồn 409 → đích 409; khóa đích − 1 µs, dồn +1 µs; nhận hạng đích; trả hàng đợi chưa kết thúc | `sắp lại hàng đợi (#6…)` |
| 7 | `POST /api/visits/:visitId/mark-emergency` | RECEPTIONIST, VET | path → vai trò → 404 → chi nhánh → 409 (trạng thái) → 409 (đã là cấp cứu, thông điệp "hạng ưu tiên của lượt") → EMERGENCY, khóa = lúc đánh dấu | `xác định ca cấp cứu (#7…)` |
| 8 | `POST /api/visits/:visitId/call` | VET, CARETAKER | path → vai trò → 404 → chi nhánh → **không phải người được gán 403 (kể cả chưa gán)** → 400 BR-TN-05 cho người gọi (khóa / sai chức vụ) → 409 → IN_PROGRESS, `calledAt` | `gọi lượt (#8…)` |
| 9 | `POST /api/visits/:visitId/reassign` | BRANCH_MANAGER (SUPER_MANAGER khi hết quản lý) | path → body (`assigneeId`, `reason` bắt buộc ≤ 300 sau khi bỏ khoảng trắng) → vai trò → 404 → phạm vi 403 → **409 không IN_PROGRESS** → 400 BR-TN-08 (người cũ còn đủ điều kiện) → 400 BR-TN-05 (người mới) → gỡ cờ, chuyển quyền dòng Order | `gán lại lượt đã gọi (#9…)` |
| 11 | `POST /api/visits/:visitId/cancel` | RECEPTIONIST | path → body tùy chọn (`reason` ≤ 300) → vai trò → 404 → chi nhánh → 409 (chỉ WAITING) → Lượt CANCELLED + lịch CANCELLED (STAFF, không lý do, **không hủy muộn**) + Order CANCELLED (`VISIT_CANCELLED`, dòng giữ lại) + thú hết khóa | `hủy lượt khách bỏ về (#11…)` |

Test đáng chú ý (tên rút gọn, đều trong `visit.mock.test.ts`): cửa sổ check-in đúng biên (10:30:00 được, 10:29:59 không); trễ 11:15:00 giữ ưu tiên, 11:15:01 thành WALK_IN vẫn gắn `appointmentId`; 11:30:00 còn check-in, 11:30:01 → 409 NO_SHOW; trường sai tên `emergency` → vẫn WALK_IN; thứ tự lỗi move / assign / call / reassign; tầng quyền SM chỉ khi `lock` hết BRANCH_MANAGER; `404` / `403` phạm vi bằng handler gọi thẳng với người gọi giả (chi nhánh 2).

Khác: `identity-admin.mock.test.ts` mô tả `GET /api/staff cho lễ tân` (5 test); `visit.api.test.ts` (6 test, qua `apiClient` + mock); `useVisits.test.tsx` (43 test: ma trận `visitPermissions`, `enabled`, làm mới ba gốc kể cả khi lỗi, `useAssignableStaff`, `visitErrorMessage`); `mock-api.test.ts` sửa 1 chỗ (A.6).

`visitErrorMessage(e, action?)`: 409 `INVALID_STATE_TRANSITION` tách theo thực thể **và trạng thái cũ** trong thông điệp. Lịch hẹn `CHECKED_IN` → "đã được tiếp nhận rồi, xem hàng đợi" (KHÔNG khuyên tiếp nhận thêm, vì BE không chặn thú đã có lượt mở nên sẽ ra lượt + Order thứ hai); `NO_SHOW` → tiếp nhận như khách vãng lai; `CANCELLED` / `COMPLETED` → không tiếp nhận được. "hạng ưu tiên" → đã là cấp cứu. `IN_PROGRESS → CANCELLED` (BR-TN-07) → lượt đã gọi không hủy được, nhân viên phụ trách hoàn tất (BR-KB-02). Còn lại → đã đổi ở nơi khác, đã tải lại. `ACCESS_DENIED_SCOPE_MISMATCH` theo `action` (`call` = chưa được gán cho mình), `CONCURRENCY_CONFLICT` = vừa được gán lại, `BR-TN-08` viết lại, `BR-TN-01/02/05`, `BR-CN-05` giữ thông điệp BE bỏ mã. BR-TN-03 (khách trễ) không bao giờ thành lỗi nên không có nhánh.

### A.5 Chỗ nối với mock khác (chỉ THÊM export; ngoại lệ có chủ đích duy nhất: `GET /api/staff` cho lễ tân)

| File | Export mới | Dùng cho |
|---|---|---|
| `mock/appointment.mock.ts` | `findCheckInRef`, `checkInAppointment`, `cancelAppointmentByVisit`, type `AppointmentCheckIn` | `AppointmentQueryApi.findCheckInRef`; Lịch hẹn#3 (409 → chi nhánh → cửa sổ, gắn `visitId`); Lịch hẹn#8 (CANCELLED, STAFF, không muộn) |
| `mock/sales.mock.ts` | `orderOfVisit`, `openOrderForVisit`, `setVisitOrderAssignee`, `transferVisitOrderLines`, `cancelOrderOfVisit` | `VisitOrderApi` (mở Order OPEN + dòng tự sinh giá chụp, đổi chủ dòng, chuyển quyền, hủy `VISIT_CANCELLED`); `openOrderForVisit` idempotent theo `visitId` |
| `mock/identity-admin.mock.ts` | `staffSummaryOf`, `assignableStaffOf`, `hasActiveBranchManager`, `setStaffOnline`, type `StaffSummary`; **`GET /api/staff` cho RECEPTIONIST** | `StaffDirectoryApi` (active = ACTIVE ∧ không khóa; tìm được nhân viên tạo lúc chạy); bảng hiện diện `petcare-mock-presence` (mặc định ai cũng online) |
| `mock/customer-pet.mock.ts` | `setPetOpenVisit` | đặt / gỡ `busy: OPEN_VISIT` (BR-KH-05, 06, 08) |
| `mock/mock-api.ts` | `...visitRoutes` | đăng ký |

### A.6 TDD: red trước, và chỗ phải sửa test cũ

**Red trước (đã chạy, đúng lý do):**
- `visit.mock.test.ts`: đăng ký `visitRoutes = {}` rỗng trước, 15 test đọc đỏ với "Chưa có mock cho GET /api/visits/…" (404) chứ không phải lỗi import; thêm 27 test tiếp nhận: đỏ cả 42 test; thêm 41 test điều phối (gán, sắp lại, cấp cứu, gọi, hủy, gán lại): đỏ 41 với "Chưa có mock cho POST /api/visits/…" (và `visitRoutes[key] is not a function` ở 2 test gọi handler trực tiếp). Sau khi cài: xanh, trừ vài test phải sửa vì chính **fixture của test sai** (không phải code): thú #1 đã có 2 lịch BOOKED (BR-LH-05) khi đặt lịch 13:30 → đổi sang Lu; `abc` + ADMIN phải ra 400 trước 403 (lúc đầu bọc `allow(roles)` chạy vai trò trước, sửa bằng kiểm path trước); dữ liệu mẫu phải khóa thú của lượt mở (lần đầu chỉ khóa khi check-in).
- `identity-admin.mock.test.ts`: 4 / 5 test staff-picker đỏ (lễ tân thấy cả chuỗi, không 403, không có `setStaffOnline`); test `role=VET&status=ACTIVE` **xanh ngay** vì mock có sẵn lọc theo `role` (teeth nằm ở 4 test còn lại).
- `visit.api.test.ts`: `visitApi = {}` rỗng, 6 / 6 đỏ ("visitApi.listQueue is not a function"), xanh sau khi cài.
- `useVisits.test.tsx`: file rỗng `export {}`, 30 / 42 đỏ ("useArrivals is not a function"); 12 test `visitPermissions` **xanh ngay** vì ma trận đã nằm trong `types/visit.ts` (kiểm bằng mutation, bên dưới). Sau review: viết lại test 409 thành hai test (lịch hẹn theo trạng thái cũ; hạng ưu tiên + BR-TN-07), đỏ 2/2 ("đã được tiếp nhận" không có trong thông điệp cũ, vốn khuyên tiếp nhận như khách vãng lai cả với `CHECKED_IN`), rồi xanh → 43.
- `visit.mock.test.ts` sau khi dò thêm BE: đổi `{ reason: 5 }` từ "MALFORMED" sang 200 + lý do `"5"` (Jackson ép số thành chuỗi), thêm `{ reason: ['x'] }` → MALFORMED: đỏ 1/1, xanh sau khi thêm `asText`.

**Xanh ngay (có chủ ý, đã chứng minh có răng bằng mutation):** `visitPermissions` (12), test scope của `GET /api/visits/:id` cho nhân viên chi nhánh khác, test `role=VET&status=ACTIVE` của staff picker.

**Mutation (sửa một chỗ, chạy, khôi phục byte-for-byte rồi so sánh): 30 mutation, 30 bị bắt.** Hai mutation sống sót ban đầu → tăng cường test rồi bắt được: (1) bỏ `setPetOpenVisit` lúc check-in sống vì dữ liệu mẫu đã khóa sẵn mọi thú → thêm bước hủy lượt mẫu của Tôm trước khi check-in lại; (2) bỏ "online xếp trước" sống vì dữ liệu thử có người offline mang id lớn nhất → đổi id.

| Nhóm | Mutation đã bị bắt |
|---|---|
| mock visit | ngưỡng trễ 15 → 16 phút · biên cửa sổ sớm `<` → `<=` · assign đảo 409/400 · hủy không hủy Order · hủy tính hủy muộn · hủy chép lý do sang lịch · bỏ BR-TN-08 · SUPER_MANAGER luôn được gán lại · gọi lượt ai cũng được · bỏ dồn +1 µs · bỏ ranh giới cấp cứu · bỏ `isEmergency` ngoài giờ · bỏ hạng ưu tiên khi sắp xếp · COMPLETED/CANCELLED không giới hạn hôm nay · check-in không khóa thú |
| hook / types | bỏ làm mới `sales` · bỏ làm mới `appointments` · chỉ làm mới khi thành công · không loại tài khoản khóa · không xếp online trước · `staffPickerBlocked` luôn false · bỏ `refetchInterval` · mất `branchId` · `useMyQueue` bỏ `enabled` · VET được check-in · SM xem hàng đợi |
| identity | lễ tân thấy tài khoản khóa · không giới hạn chi nhánh · lọc `role=RECEPTIONIST` không 403 · bỏ bảng hiện diện |

**Phải sửa một test cũ (không tránh được):** `mock-api.test.ts` › `answers 404 for a path that has no mock yet` dùng chính `GET /api/visits` làm ví dụ "chưa có mock". T36 bắt buộc mock đường này nên đổi đường dẫn thành `GET /api/visits/1/medical-record` (T37 mới mock), giữ nguyên mọi assertion. Không còn test cũ nào bị đụng.

### A.7 Staff picker cho lễ tân: ASSUMPTION và yêu cầu gửi BE-1

Lễ tân là vai trò duy nhất được gán (UC45) nhưng **không có nguồn HTTP** cho danh sách nhân viên gán được: identity-v1 #15 `GET /api/staff` loại RECEPTIONIST và `docs/06-module-contracts.md:115` ghi "Danh sách chọn người (`findAssignableStaff`) cho FE chưa có endpoint". Đã dò BE thật: **403**.

Quyết định (FE): dùng lại #15. Mock mở `GET /api/staff` cho RECEPTIONIST với đúng phạm vi *VET / CARETAKER ACTIVE, không khóa, của chi nhánh mình*; `role` khác, `status` khác `ACTIVE`, `branchId` khác → 403. Hook `useAssignableStaff(role, enabled, branchId?)` loại `isLocked`, xếp online trước, offline vẫn chọn được kèm `offlineWarning` (BR-TN-06), và trả `staffPickerBlocked = true` khi BE trả 403 để UI báo "danh sách nhân viên chờ BE-1 mở GET /api/staff cho lễ tân" thay vì để trống.

**Yêu cầu gửi BE-1:** mở `GET /api/staff` cho RECEPTIONIST, giới hạn `status = ACTIVE`, `role ∈ {VET, CARETAKER}`, `branchId` = chi nhánh người gọi, loại `isLocked`; khuyến nghị DTO rút gọn (`accountId, fullName, role, online`, bỏ email / SĐT / `mustChangePassword`) vì lễ tân không cần; cập nhật `docs/06-module-contracts.md:115`. Phương án thay thế: endpoint riêng dưới visit (`findAssignableStaff` đã có sẵn ở `StaffDirectoryApi`). Cho tới khi BE mở, màn gán của lễ tân chạy được với mock nhưng **không** chạy với BE thật.

### A.8 Lệch hợp đồng ↔ BE

**Docs lệch code (theo BE):**

| Chỗ | Docs | BE (file:dòng) |
|---|---|---|
| Thú đã mất khi tiếp nhận | `02-business-rules.md:91` BR-KH-05 chặn tiếp nhận; `visit-v1.md:77` liệt kê BR-KH-05 ở đầu mục | `VisitCheckInService.java:122-124` ném **BR-TN-01** "Thú cưng đã mất, không tiếp nhận được" (ADR-0059 mục 5). FE bắt `BR-TN-01`, không `BR-KH-05` |
| Thứ tự 409 / 400 khi check-in lịch hẹn | `visit-v1.md:49` "404 → 400 → 409" | `AppointmentVisitService.java:62-70`: 409 trạng thái lịch → 400 chi nhánh → 400 cửa sổ BR-TN-02 (ADR-0059 mục 5 gọi là lệch sẵn có của ADR-0050) |
| Cửa sổ check-in | `02-business-rules.md:142` BR-TN-02 chỉ nói "sớm nhất 30 phút trước giờ hẹn" | `CheckInWindow` + `AppointmentVisitService.java:66-70`: `[giờ hẹn − 30', giờ hẹn + 30']` (biên tính là được), thông điệp nêu cả hai mốc |
| Đánh dấu cấp cứu | `visit-v1.md:55` "Nhân viên chi nhánh" | `VisitController.java:64-65, 203-204`: chỉ RECEPTIONIST, VET (BRANCH_MANAGER, CARETAKER → 403, đã dò) |
| Một thú, nhiều lượt mở | `02-business-rules.md:141` BR-TN-01 "1 Visit cho đúng 1 thú" (hiểu: mỗi lần tiếp nhận) | không có guard "thú đã có lượt mở": check-in lần hai vẫn 201 (ADR-0059 "Không đảm bảo"; đã dò). UI nên tự cảnh báo |
| `isEmergency` | `visit-v1.md:115` `isEmergency?` | tên đúng; BE **bỏ qua trường lạ** nên `emergency: true` vẫn 201 nhưng không thành cấp cứu. `CheckInRequest` đặt `isEmergency` và test chốt lại |
| Staff picker | `visit-v1.md` / `06-module-contracts.md:115` chưa có endpoint | `GET /api/staff` 403 với lễ tân (A.7) |
| Kiểu `queueSortKey` | `visit-v1.md:116` "date-time" | tới µs, bỏ phần lẻ khi chẵn giây (`Instant.toString`); không so bằng `Date.parse` |
| Đăng nhập | `identity-v1` `AccountSummary` | BE không trả `branchId` ở `POST /auth/login`; chi nhánh ở `GET /me` `staffProfile.branchId` |

**Mock lệch BE thật (không xóa được):**

1. **NO_SHOW lười:** mock chuyển BOOKED → NO_SHOW ngay lúc đọc khi `now > giờ hẹn + 30'`, nên check-in lịch → **409** `…từ NO_SHOW sang CHECKED_IN`. BE thật chuyển bằng job ST05 chạy **mỗi 5 phút** (`application.yml:89`), nên trong tối đa 5 phút sau mốc đó lịch còn BOOKED mà đã ngoài cửa sổ → **400 BR-TN-02** (cửa sổ), sau tick kế tiếp mới 409. **Đã dò:** lịch 15:30 check-in lúc 16:01:10 giờ VN (trễ 31') → BE trả **409** `Không thể chuyển trạng thái lịch hẹn từ NO_SHOW sang CHECKED_IN` và `GET` lịch cho `status: NO_SHOW` (job đã chạy lúc 16:00), nghĩa là sai khác chỉ còn ở khoảng ≤ 5 phút giữa hai mốc. Cả hai cùng nghĩa với lễ tân: tiếp nhận lại như khách vãng lai (bỏ `appointmentId`).
2. **Cờ `needsReassign`:** BE bật cờ khi ADMIN khóa tài khoản (`AccountLockedListener`, `VisitReassignFlagService`). Mock **không** nối khóa với cờ; chỉ lượt mẫu 2104 có cờ. Gán lại trong mock dựa vào điều kiện "người cũ không còn đủ điều kiện" (khóa / vô hiệu hóa / ngoài danh bạ), cùng điều kiện với BE (A15, "không xét cờ"). Mở khóa không gỡ cờ (ADR-0062 Q5) nên không có gì để mô phỏng thêm.
3. **`online`:** BE tính theo `last_seen_at` trong 10'. Mock: mặc định ai cũng online; `setStaffOnline(id, false)` đặt offline (bảng hiện diện riêng). Dữ liệu mẫu **không** có ai offline, nên cảnh báo BR-TN-06 không hiện nếu không gọi `setStaffOnline`.
4. **Không có audit** (`VISIT_REASSIGNED`), `visit_assignments`, khóa đồng thời, `CONCURRENCY_CONFLICT` của gán lại.
5. **Dữ liệu mẫu nạp lười:** Order VISIT OPEN của lượt đang mở và `busy` của thú chỉ có sau lần chạm visit đầu tiên. Lượt 2001 có `orderId: 0` (seed bán lẻ không có Order). Người phụ trách #12 ngoài danh bạ.
6. **`GET /api/staff` cho vai trò khác lễ tân** giữ hành vi cũ của mock (VET / CARETAKER / ADMIN thấy cả chuỗi), BE thật: VET / CARETAKER 403. Không sửa vì ngoài phạm vi.
7. **Thông điệp BR-KH-05 của `customer-pet.mock`** ("Thú đang có lượt khám chưa kết thúc") khác BE ("Không thể đánh dấu đã mất: thú cưng đang có lượt khám chưa kết thúc"); có từ trước.
8. **BR-CN-05 chưa dò được trên BE thật** (không đổi được giờ máy; thêm ngày nghỉ hôm nay không xóa được): thông điệp chép từ `VisitCheckInService.java:166-178`. Mock có test cho cả hai nhánh.
9. Mã khách / thú / dịch vụ và id lượt khác DB thật (mock: lượt mẫu 2001…, mới từ 3001; BE: 1…).
10. Ép kiểu JSON: mock chấp nhận số nguyên dạng chuỗi, boolean `"true"/"false"/0/1` và `reason` số / boolean (thành chuỗi) như Jackson đã dò; `3.0` coi như `3` (JS không phân biệt).
11. **BR-QT-08 chưa nối:** BE từ chối vô hiệu hóa nhân viên còn lượt chưa hoàn tất (400 `Không vô hiệu hóa được: còn lượt tiếp nhận #1 chưa hoàn tất (BR-QT-08)`, đã dò). Route `POST /api/staff/:id/disable` của mock không biết Lượt nên vô hiệu hóa được VET #5 dù đang giữ 2002 / 2102 (cùng lý do không nối khóa → cờ: `identity-admin.mock` không import `visit.mock`). Sau khi vô hiệu hóa, lượt đó thành "người phụ trách không còn đủ điều kiện" và gán lại được.
12. **Quyền `GET /api/appointments?checkInReady=true`** (danh sách chờ check-in) chỉ RECEPTIONIST / BRANCH_MANAGER (đã dò và mock lịch hẹn khớp); VET, CARETAKER, SUPER_MANAGER, ADMIN → 403 nên `useArrivals` chỉ bật cho hai vai trò đó (`visitPermissions(role).arrivals`).

### A.9 Kiểm chứng

Chạy từ `FE/` (cuối cùng, sau mọi thay đổi code):

- `npx tsc --noEmit`: sạch.
- `npx eslint <các file T36> --max-warnings 0` (types/visit, visit.api(+test), useVisits(+test), visit.mock(+test), identity-admin.mock(+test), sales.mock, appointment.mock, customer-pet.mock, mock-api(+test)): sạch.
- `npx vitest run src/shared`: 44 file, 720 test xanh (trước phần sửa cuối).
- `npx vitest run` đầy đủ: **84 file, 979 test xanh** (nền 81 file / 841 test + 3 file mới + 138 test mới: `visit.mock.test.ts` 84, `visit.api.test.ts` 6, `useVisits.test.tsx` 43, `identity-admin.mock.test.ts` +5).
- **Một lần chạy đầy đủ trước đó có 1 test đỏ không do T36:** `src/app/App.shell.test.tsx › staff shell › keeps the same sidebar when the desk opens the overview` (đỏ ở 1594 ms khi cả bộ chạy song song; chạy riêng 3/3 xanh; chạy đầy đủ lại 2 lần xanh). Là flake theo tải máy: test dựng cả `<App>` (trang tải lười, `findByRole` mặc định 1 s); chưa có màn nào gọi `visitApi`/`useVisits`, nên T36 không thể ảnh hưởng.

## B. Giao diện tiếp nhận, hàng đợi, gọi lượt

> Phạm vi: bàn **Lễ tân** (`/staff/reception`: RECEPTIONIST và BRANCH_MANAGER), bàn **Phòng khám** (`/staff/doctor`: VET), bàn **Grooming** (`/staff/grooming`: CARETAKER), số đếm ở thanh bên và toast "lượt mới vào hàng chờ của bạn". Tất cả đọc / ghi qua `useVisits.ts` (A), không còn đi qua `clinic-db`. Hợp đồng và rule như đầu tài liệu. Ghi bệnh án, kê đơn, tiêm chủng và **hoàn tất lượt** là T37 và **chưa nối** (xem B.7).

### B.1 Màn hình và file

| Màn / khối | File (FE/src) | Ghi chú |
|---|---|---|
| Quầy lễ tân | `pages/staff/ReceptionWorkspacePage.tsx` | Nút "Tiếp nhận khách" và phím `N` chỉ khi `visitPermissions(role).checkIn`; `F1`, `Ctrl+Enter` (thu tiền) giữ nguyên |
| Danh sách ở quầy (3 tab) | `components/staff/reception/FrontDeskPanel.tsx` | **Đón tiếp** (`useArrivals`), **Hàng chờ** (`useVisitQueue`), **Chờ thu** (sales-v1 từ T35, giữ nguyên) |
| Hộp thoại tiếp nhận | `reception/CheckInDialog.tsx` | Lịch hẹn / khách vãng lai / cấp cứu; ô tìm khách dùng lại `CustomerLookup` (thêm prop `copy`, mặc định giữ nguyên chữ của màn thu tiền) và `useCustomerDetail`. Tải lười từ trang lễ tân |
| Hàng chờ chi nhánh | `reception/VisitQueueList.tsx` | Đúng thứ tự mảng BE trả; nút theo vai trò |
| Hộp thoại thao tác | `reception/VisitActionDialogs.tsx` | `AssignVisitDialog`, `MoveVisitDialog`, `CancelVisitDialog`, `ReassignVisitDialog` (tải lười từ `VisitQueueList`) |
| Chọn người phụ trách | `reception/AssigneePicker.tsx` | VET cho Khám/Tiêm, CARETAKER cho Thẩm mỹ, online trước, cảnh báo ngoại tuyến, báo `staffPickerBlocked` |
| Quy tắc thuần | `reception/arrival.ts`, `reception/queue-rules.ts` | Gợi ý giờ đến (BR-TN-03); đích "Đưa lên trước"; điều kiện hiện "Gán lại" |
| Phòng khám | `pages/staff/DoctorWorkspacePage.tsx`, `doctor/DoctorQueue.tsx`, `doctor/ExamPendingPanel.tsx` | Hàng đợi của tôi (`useMyQueue`), gọi lượt, cấp cứu; khung bệnh nhân của API |
| Khu grooming | `pages/staff/GroomingWorkspacePage.tsx`, `grooming/GroomingBoard.tsx`, `grooming/useBoardDrag.ts`, `shared/hooks/useKanban.ts` | Cột Chờ làm / Đang làm từ API; cột "Xong" chỉ giữ chỗ |
| Thanh bên | `components/staff/StaffSidebar.tsx` (`ClinicToday`) | Chờ tiếp nhận / Đang chờ / Đang làm đọc API, theo vai trò |
| Toast lượt mới | `shared/hooks/useWorkspaceSignals.ts` (`useHandoffNotices`) | `useMyQueue` cho bàn Phòng khám / Grooming; bỏ tham số `userId` (sửa 1 dòng gọi ở `StaffShell.tsx`) |
| Dùng chung | `components/staff/visit-ui.tsx`, `shared/hooks/useStaffBranch.ts` | Mã lượt, nhãn hạng / nguồn, chấm trực tuyến; chi nhánh của nhân viên (login thật không trả `branchId`, hỏi `GET /me`) |
| Test dùng chung | `src/test/visit-harness.tsx` | Ghim `Date` ở thứ Năm 22/10/2026 10:40 giờ VN, đăng nhập mock, `makeVisit`, `makeStaff` |

Làm mới: hàng đợi chi nhánh, hàng đợi của tôi, danh sách chờ check-in tự hỏi lại 10 giây (`VISIT_REFRESH_MS`); không còn dựa vào sự kiện `storage` của `clinic-db` cho các danh sách này (`useClinicSync` vẫn có cho phần bệnh án còn trên `clinic-db`).

### B.2 Ma trận vai trò trên giao diện

Mọi `enabled` tính từ `visitPermissions(role)` (`VISIT_ROLES`): vai trò không có quyền thì **không có nút và không bắn request** (test dùng spy như `ShiftControls.test`).

| Việc | RECEPTIONIST | BRANCH_MANAGER | VET | CARETAKER |
|---|---|---|---|---|
| Tab Đón tiếp: xem lịch hẹn chờ check-in (`useArrivals`) | có | xem, **không có nút** | không hỏi | không hỏi |
| "Tiếp nhận", "Khách vãng lai", phím `N`, nút đầu trang | có | không | — | — |
| Tab Hàng chờ (`useVisitQueue`) | có | xem | chỉ số đếm ở thanh bên | bảng "Cả khu" và số đếm ở thanh bên |
| Gán / Đổi người (WAITING) | có | không | — | — |
| Đưa lên trước (WAITING) | có | không | — | — |
| Cấp cứu (WAITING, chưa là cấp cứu) | có (hàng chờ) | **không** (BE 403) | có (khung bệnh nhân) | không (BE 403) |
| Hủy lượt khách bỏ về (WAITING) | có | không | — | — |
| Gán lại (lượt đã gọi, người phụ trách không đủ điều kiện) | không | có, lý do bắt buộc | — | — |
| Hàng đợi của tôi (`useMyQueue`) | không hỏi | không hỏi | có | có |
| Gọi lượt (WAITING gán cho mình) | — | — | có | có (Bắt đầu / kéo thả) |

### B.3 Hành vi từng màn

**Tab Đón tiếp (UC44).** Lịch BOOKED trong cửa sổ check-in: giờ hẹn, thú, khách, dịch vụ (kèm nhóm), gợi ý giờ đến theo `arrivalWindow`: "Đến sớm N phút" / "Đúng giờ" / "Trễ N phút: vẫn giữ ưu tiên lịch hẹn" (≤ 15 phút) / "Trễ N phút: xếp như khách vãng lai" (15–30 phút, BR-TN-03) / "Quá 30 phút: lịch tính là không đến, tiếp nhận như khách vãng lai" (BR-LH-08, BE thường đã loại khỏi danh sách). Giờ hẹn lấy từ `slotDate` + `slotStart` (giờ Việt Nam).

**Hộp thoại tiếp nhận (UC44, UC47).**
- Lịch hẹn: tóm tắt điền sẵn; gửi `{ appointmentId, petId }` (`petId` bắt buộc và phải là thú của lịch, A11; không gửi `serviceId`).
- Khách vãng lai: tìm khách (SĐT 10 số / họ tên / email, cùng ô tra cứu với màn thu tiền) → chọn thú (thú đã mất bị khóa) → chọn dịch vụ Khám/Tiêm hoặc Thẩm mỹ đang bật tại chi nhánh của nhân viên (`useBranchServices`, bỏ Lưu trú) → gửi `{ petId, serviceId }`. Chưa biết chi nhánh (BE thật không trả `branchId` khi đăng nhập, `GET /me` chưa về hoặc không có) thì **không hỏi dịch vụ** (không gọi `/branches/0/services`) và nói rõ; tải dịch vụ lỗi thì có lỗi và nút Thử lại.
- Cấp cứu: ô "Cấp cứu" gửi `isEmergency: true` (đúng tên trường; `emergency` bị BE bỏ qua). Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05); lỗi của BE hiện nguyên văn trong hộp thoại.
- Cảnh báo (không chặn): thú đã có lượt chờ / đã gọi trong hàng đợi → "Tiếp nhận thêm sẽ tạo lượt và Order thứ hai" (BE không chặn, A.8).
- Lỗi hiện trong hộp thoại bằng `visitErrorMessage(e, 'checkIn')`, hộp thoại **vẫn mở**; thành công: toast "Đã tiếp nhận <thú>, mã TN-…" kèm nhắc gán người ở tab Hàng chờ; `Ctrl+Enter` tiếp nhận.
- **Không có ô chọn người phụ trách ở đây**: `CheckInRequest` không có trường gán nhân viên (types), nên gán là bước riêng ở tab Hàng chờ (B.5-1).

**Tab Hàng chờ (UC45).** Mỗi dòng: mã lượt, thú, trạng thái ("Đang chờ" / "Đã gọi"), nhãn "Cấp cứu", nguồn "Lịch hẹn" / "Vãng lai" / "Lịch hẹn trễ" (có lịch nhưng xếp vãng lai), khách, nhóm, người phụ trách kèm chấm Trực tuyến / Ngoại tuyến (chỉ khi lấy được danh sách nhân viên), nhãn "Cần gán lại" khi `needsReassign`, thời gian chờ. Thứ tự là thứ tự mảng BE; **không sắp lại ở FE**. Lượt đã gọi chỉ để xem. Nút (đúng thứ tự trên dòng): Gán người / Đổi người · Đưa lên trước · Cấp cứu · Hủy lượt · Gán lại.
- **Gán (BR-TN-05, BR-TN-06):** chọn người đúng chức vụ, online trước (`· trực tuyến` / `· ngoại tuyến`); chọn người ngoại tuyến hiện cảnh báo trong hộp thoại và vẫn gán được, `assigneeOffline` thì toast cảnh báo. BE chưa mở `GET /api/staff` cho lễ tân (403): hiện **"Danh sách nhân viên chờ BE-1 mở GET /api/staff cho lễ tân"**, nút Gán tắt.
- **Đưa lên trước (BR-TN-04):** chọn lượt đích trong các lượt đứng trước, còn WAITING, cùng phía ranh giới cấp cứu (`moveTargets`, chỉ dùng thứ tự mảng); mặc định là lượt đầu nhóm; gửi `placeBeforeVisitId`; hàng đợi theo mảng BE trả lại.
- **Cấp cứu (UC47):** một chạm, thành công thì lượt lên nhóm cấp cứu; 409 "đã là cấp cứu" báo "Lượt này đã là ca cấp cứu."
- **Hủy lượt khách bỏ về (BR-TN-07):** hộp thoại ghi rõ Order của lượt và lịch hẹn liên quan bị hủy, không tính là hủy muộn; lý do không bắt buộc, ≤ 300; để trống / chỉ khoảng trắng thì **không gửi body** (A4); 409 lượt đã gọi → "Lượt đã được gọi nên không hủy được…".
- **Gán lại (BR-TN-08, quản lý chi nhánh):** hiện khi lượt IN_PROGRESS và (`needsReassign` hoặc người phụ trách không còn trong danh sách nhân viên gán được của đúng chức vụ); người mới đúng chức vụ, lý do bắt buộc (cắt khoảng trắng, ≤ 300); BE 400 BR-TN-08 hiện bằng câu viết lại của `visitErrorMessage`.

**Phòng khám (UC46, UC47).** "Hàng chờ của bạn" = `useMyQueue`, **một danh sách theo thứ tự BE trả**, mỗi dòng có chip trạng thái ("Đang khám" / "Đang chờ"), nhãn cấp cứu, mã lượt. Chỉ lượt WAITING gán cho mình có "Gọi lượt"; BE 403 (`ACCESS_DENIED_SCOPE_MISMATCH`) / 409 hiện bằng toast `visitErrorMessage(e, 'call')`. Khung bên phải (`ExamPendingPanel`): thú, loài, chủ nuôi, nhóm dịch vụ, trạng thái, người phụ trách, mã lượt, "Gọi <thú> vào khám", "Cấp cứu" (VET), và ghi chú **"Ghi bệnh án, kê đơn, tiêm chủng và hoàn tất lượt: chưa nối API (T37)"**. Mở sẵn ở lượt đang khám, không có thì lượt chờ đầu tiên. Phím ↑ / ↓ đi qua hàng đợi.

**Grooming (UC46).** Cột "Chờ làm" / "Đang làm" lấy từ `useMyQueue` ("Của tôi") hoặc `useVisitQueue({ serviceGroup: 'GROOMING' })` ("Cả khu"); thứ tự thẻ là thứ tự mảng BE. "Bắt đầu" hoặc kéo thẻ của mình sang "Đang làm" = gọi lượt; thẻ chuyển ngay (giữ cục bộ cho tới khi gọi xong và hàng đợi tải lại) và tự về cột cũ nếu BE từ chối (toast). Thẻ của người khác chỉ để xem. "Xong" **tắt**, kèm "Hoàn tất lượt: chưa nối API (T37)"; cột "Xong, chờ đón" chỉ giữ chỗ (thả vào đó bị từ chối).

**Thanh bên và toast.** "Chờ tiếp nhận" (lịch hẹn chờ check-in), "Đang chờ", "Đang làm" (hàng đợi chi nhánh), "Chờ thu tiền" (sales, T35). VET / CARETAKER không hỏi lịch hẹn chờ check-in (BE 403) nên ô đó hiện "–". Bàn Phòng khám / Grooming báo toast "<mã>: <thú> vào hàng chờ của bạn" khi lượt WAITING mới xuất hiện trong hàng đợi của tôi (lượt có sẵn lúc mở trang không báo).

### B.4 Ca kiểm thử

Chạy với mock: `cd FE && npm run dev`, mật khẩu `Petcare123`, nhân viên đăng nhập ở `/admin/login`. Dữ liệu mẫu tính theo giờ nạp (A.3). **Lịch hẹn mẫu #1 (Mochi, 11:00) chỉ hiện ở tab Đón tiếp từ 10:30 đến 11:30** của ngày nạp; ngoài khung đó dùng "Khách vãng lai" hoặc đặt lịch hộ rồi chờ tới cửa sổ. Đưa dữ liệu về ban đầu: xóa `petcare-mock-visit`, `petcare-mock-appointment`, `petcare-mock-sales`, `petcare-mock-presence` trong Local Storage. Test tự động chạy với đồng hồ ghim thứ Năm 22/10/2026 10:40 (hàng đợi mẫu: 2101 Miu cấp cứu chờ gán · 2002 Lu có lịch hẹn đã gọi (VET #5) · 2104 Mochi grooming đã gọi, cần gán lại · 2102 Tôm vãng lai chờ (VET #5) · 2103 Bim grooming chờ (CARETAKER #6)).

| # | Tài khoản | Làm | Kỳ vọng | Test tự động (describe › it, rút gọn) |
|---|---|---|---|---|
| 1 | reception | Mở Quầy lễ tân lúc 10:40 | Tab Đón tiếp có Mochi: Nguyễn Thu Trang, Khám tổng quát, 11:00, "Đến sớm 20 phút"; số trên tab = 1 | `FrontDeskPanel` › tab Đón tiếp › liệt kê lịch hẹn… |
| 2 | reception | Giả lập 11:20 | Chip "Trễ 20 phút: xếp như khách vãng lai" | › khách trễ 15–30 phút…; `arrival.test` |
| 3 | reception | Bấm "Tiếp nhận" | Hộp thoại điền sẵn; tiếp nhận → toast mã `TN-…`, hộp thoại đóng, lượt hạng Lịch hẹn vào Hàng chờ, số trên tab Hàng chờ tăng, lịch rời tab Đón tiếp | `CheckInDialog` › gửi {appointmentId, petId}…; `ReceptionWorkspacePage` › "Tiếp nhận" ở lịch hẹn mở hộp thoại… |
| 4 | reception | Tiếp nhận lịch trễ 20 phút | Lượt hạng Vãng lai, vẫn gắn lịch hẹn, nhãn "Lịch hẹn trễ" | `CheckInDialog` › khách trễ 20 phút… |
| 5 | reception | Tiếp nhận lúc 10:29, 11:31, và lịch đã tiếp nhận | 400 cửa sổ (nêu cả hai mốc) · 409 NO_SHOW "tiếp nhận như khách vãng lai" · 409 "đã được tiếp nhận rồi"; hộp thoại vẫn mở | `CheckInDialog` › lỗi của BE hiện ngay trong hộp thoại (3 ca) |
| 6 | reception | Phím `N` → gõ "Long" → chọn hồ sơ `lo***@gmail.com` → thú Lu → "Khám tổng quát" | Cảnh báo "Lu đang có lượt … Order thứ hai"; tiếp nhận được; lượt Vãng lai nhóm Khám/Tiêm | `CheckInDialog` › tìm khách, chọn thú và dịch vụ…; thú không có lượt mở thì không cảnh báo |
| 7 | reception | Mở danh sách dịch vụ | Chỉ Khám/Tiêm và Thẩm mỹ đang bật, không Lưu trú; thú đã mất bị khóa; phiên không có `branchId` thì lấy từ `GET /me`, không biết chi nhánh thì không hỏi dịch vụ; lỗi tải có Thử lại | `CheckInDialog` › chỉ liệt kê dịch vụ…; thú đã mất không chọn được; chi nhánh của nhân viên (3 ca) |
| 8 | reception | Tick "Cấp cứu" rồi tiếp nhận | Request có `isEmergency: true` (không có `emergency`); lượt EMERGENCY | `CheckInDialog` › cấp cứu: gửi đúng tên trường… (cả lịch hẹn lẫn vãng lai) |
| 9 | reception | Giả lập 12:30 (nghỉ trưa) | Không tick cấp cứu: "Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu"; tick → được; SM tắt cờ cấp cứu ngoài giờ → "chi nhánh không nhận cấp cứu ngoài giờ" | `CheckInDialog` › ngoài giờ mở cửa (BR-CN-05) (2 ca) |
| 10 | reception | BE trả 400 BR-TN-01 (thú đã mất) | Thông điệp hiện, bỏ mã rule | `CheckInDialog` › BE vẫn từ chối thú đã mất… |
| 11 | reception | Tab Hàng chờ | Thứ tự Miu, Lu, Mochi, Tôm, Bim; nhãn hạng / nguồn / trạng thái; chấm trực tuyến; "Cần gán lại" ở Mochi; số trên tab = 5 | `FrontDeskPanel` › tab Hàng chờ (4 ca) |
| 12 | reception | BE trả mảng "sai hạng" | Giao diện giữ nguyên thứ tự mảng | › không tự sắp lại… |
| 13 | reception | Gán người cho Miu / Bim | Danh sách đúng chức vụ, online trước; ngoại tuyến → cảnh báo, vẫn gán; 403 → câu chờ BE-1, nút Gán tắt; 400 BR-TN-05 hiện trong hộp thoại | `FrontDeskPanel` › gán người phụ trách (5 ca) |
| 14 | reception | Đưa Bim lên trước Tôm | `placeBeforeVisitId` đúng, hàng đợi đổi theo BE; Tôm không có nút này (không có đích) | › "Đưa lên trước"…; nút theo từng lượt chờ…; `queue-rules.test` |
| 15 | reception | Cấp cứu cho Tôm | Tôm lên ngay sau Miu, nút biến mất; 409 → "Lượt này đã là ca cấp cứu." | › "Cấp cứu"… (2 ca) |
| 16 | reception | Hủy lượt Bim | Hộp thoại nói Order + lịch hẹn bị hủy, không tính hủy muộn; không lý do → không body; có lý do → `{reason}`; "Giữ lại" không gửi gì; 409 lượt đã gọi → báo không hủy được | › hủy lượt khách bỏ về (5 ca) |
| 17 | manager | Mở Quầy lễ tân | Thấy tab Hàng chờ và Đón tiếp nhưng **không** có nút gán / đưa lên trước / cấp cứu / hủy / Tiếp nhận / Khách vãng lai / "Tiếp nhận khách"; phím `N` không mở gì; không request ghi, không `GET /me/queue` | `FrontDeskPanel` › quản lý chi nhánh (5 ca); `ReceptionWorkspacePage` › chỉ có quản lý chi nhánh… |
| 18 | manager | "Gán lại" Mochi (người phụ trách không còn) | Cần chọn người đúng chức vụ (Thu Hà) và lý do; cờ biến mất; Lu (người phụ trách còn đủ điều kiện) không có nút | › lượt đã gọi có cờ cần gán lại…; người phụ trách còn đủ điều kiện thì ẩn…; BE từ chối gán lại (BR-TN-08) |
| 19 | doctor | Mở Phòng khám | Hàng chờ: Lu (Đang khám), Tôm (Đang chờ) đúng thứ tự BE; mở sẵn Lu; khung bệnh nhân đủ thông tin + ghi chú T37, không chữ lỗi | `DoctorWorkspacePage` › hàng đợi của tôi (5 ca); › ghi bệnh án chờ T37 |
| 20 | doctor | "Gọi lượt" Tôm | `POST …/call`, Tôm thành Đang khám, khung bệnh nhân chuyển sang Tôm, toast; lỗi 403 / 409 → toast `visitErrorMessage('call')` | › gọi lượt (UC46; BR-TN-05) (5 ca) |
| 21 | doctor | "Cấp cứu" ở khung bệnh nhân | Tôm lên đầu hàng đợi, nút biến mất | › VET đánh dấu ca đang chờ là cấp cứu… |
| 22 | caretaker | Mở khu Grooming | "Chờ làm" có Bim; "Đang làm" trống; cột "Xong" tắt kèm hint; tiêu đề đếm 1 bé | `GroomingBoard` › cột lấy từ hàng đợi của tôi…; `GroomingWorkspacePage` |
| 23 | caretaker | Bắt đầu / kéo Bim sang "Đang làm" | Thẻ chuyển ngay; BE từ chối → về lại + toast; "Xong" tắt + hint | `GroomingBoard` › moves a card… / puts the card back… / Bắt đầu gọi lượt… / kéo thẻ của mình… |
| 24 | caretaker | Kéo Bim thẳng sang cột "Xong" | Từ chối "Bắt đầu làm trước khi báo xong.", không gọi API | › kéo bỏ qua "Đang làm"… |
| 25 | caretaker | "Cả khu" | Thấy Mochi (người khác) chỉ để xem; hỏi `GET /visits?serviceGroup=GROOMING`, không hỏi `GET /me/queue` | › "Cả khu"…; "Của tôi"… |
| 26 | reception / doctor / manager | Nhìn thanh bên | Chờ tiếp nhận 1 · Đang chờ 3 · Đang làm 2; VET: "Chờ tiếp nhận" là "–" và không có request lịch hẹn | `StaffSidebar` › counts … (3 ca) |
| 27 | doctor / caretaker | Lượt mới được gán khi đang mở bàn | Toast "<mã>: <thú> vào hàng chờ của bạn"; lượt có sẵn / đã gọi không báo; vai trò khác không hỏi hàng đợi của tôi | `useWorkspaceSignals.test` › lượt mới vào hàng đợi của tôi (7 ca) |
| 28 | reception | Đợi 10 giây | Lượt tiếp nhận ở quầy khác tự hiện (số trên tab tăng) | `FrontDeskPanel` › hàng đợi tự hỏi lại sau 10 giây |
| 29 | reception | Phím `N`, `F1`, `Ctrl+Enter` | `N` mở hộp thoại, `F1` sang khu bán hàng và focus ô tìm khách, `Ctrl+Enter` không thu tiền khi hộp thoại tiếp nhận đang mở | `ReceptionWorkspacePage` › phím N…; phím F1…; never takes payment while the check-in dialog is open |

### B.5 Giả định

1. **Gán người không nằm trong hộp thoại tiếp nhận.** `CheckInRequest` (types, theo `visit-v1` `CreateVisitRequest`) không có trường người phụ trách, nên không chuỗi "tiếp nhận rồi gán" thành một thao tác (sẽ có trạng thái nửa chừng "đã tạo lượt nhưng chưa gán được"). Lễ tân gán ở tab Hàng chờ sau khi tiếp nhận; toast tiếp nhận nhắc việc này. So với bàn cũ (chọn người ngay trong hộp thoại, gợi ý người ít lượt nhất), đây là một bước thêm.
2. **"Đưa lên trước" = chọn lượt đích**, mặc định lượt đầu nhóm (cùng phía ranh giới cấp cứu, còn WAITING). Contract chỉ có `placeBeforeVisitId`; không suy ra "lên một bậc".
3. **Điều kiện hiện "Gán lại"** = lượt IN_PROGRESS và (`needsReassign` hoặc người phụ trách không còn trong danh sách nhân viên gán được đúng chức vụ). Theo A15 (BE không xét cờ, xét người phụ trách còn đủ điều kiện); chưa tải được danh sách thì chỉ tin cờ. BE vẫn chặn (400 BR-TN-08).
4. **Ô "Cấp cứu" hiện cho cả Khám/Tiêm và Thẩm mỹ.** Bàn cũ ẩn ô này với Grooming; contract / ADR-0059 / UC47 không cấm, nên không giữ luật tự đặt đó.
5. **Cảnh báo, không chặn, khi thú đã có lượt mở** (BE không chặn, A.8). Cảnh báo lấy từ hàng đợi chi nhánh đang có sẵn trong cache.
6. **Hàng đợi của tôi là một danh sách theo thứ tự BE**, không chia nhóm "Đang khám / Chờ khám" (chia nhóm sẽ đổi thứ tự). Mất các nhóm cũ "Chưa gán bác sĩ", "Của bác sĩ khác", "Đã khám hôm nay": `GET /me/queue` chỉ trả lượt WAITING / IN_PROGRESS gán cho mình.
7. **Tên trạng thái theo bàn:** quầy lễ tân "Đã gọi" (từ ngữ BR-TN-07 / BR-TN-08), phòng khám "Đang khám", grooming "Đang làm".
8. **Nhãn nguồn:** chỉ lượt có `appointmentId` mà hạng `WALK_IN` mới ghi "Lịch hẹn trễ" (BR-TN-03); nguồn lấy từ `appointmentId`, không từ hạng.
9. **Chi nhánh của nhân viên** (để lấy dịch vụ của chi nhánh) lấy từ phiên, thiếu thì hỏi `GET /me` (`useStaffBranchId`), vì BE thật không trả `branchId` khi đăng nhập.
10. **Thông điệp toast / lỗi** viết thẳng ở UI (sonner), lấy `visitErrorMessage(e, action)` cho mọi lỗi từ BE.

### B.6 Chấm online và `GET /api/staff`

Chấm trực tuyến ở hàng đợi và danh sách chọn người lấy từ `GET /api/staff` (identity-v1 #15). Với lễ tân trên BE thật hiện **403** nên: hàng đợi không có chấm, hộp thoại gán báo "chờ BE-1 mở GET /api/staff cho lễ tân", quản lý chi nhánh vẫn dùng bình thường. Mỗi lần mở tab Hàng chờ lễ tân bắn 2 request (VET, CARETAKER) và nhận 2 lần 403 (`retry` tắt cho 4xx, lưu cache 30 giây); chỉ gây ồn console.

### B.7 Còn để lại cho T37 (không sửa trong T36)

- **Chưa nối API, đã báo trên giao diện:** ghi bệnh án, kê đơn, tiêm chủng, hoàn tất lượt (visit-v1 #10, #12–#20). `SoapPanel` / `PatientPanel` còn nguyên và **không với tới được** từ bàn Phòng khám mới (bàn không còn render chúng); T37 nối lại từ `DoctorWorkspacePage` (đã có `ExamPendingPanel` để thay). Hai ca hoàn tất BR-KB-02 của `DoctorWorkspacePage.test` được chuyển nguyên sang `components/staff/doctor/SoapPanel.test.tsx` (dựng `useVisitDetail('visit-5')` trên `clinic-db`) để không mất độ phủ.
- **Phím tắt nói sai tạm thời:** `shared/constants/shortcuts.ts` còn ghi `Ctrl+S` (lưu bệnh án), `Ctrl+Enter` (hoàn tất lượt, bàn bác sĩ) và "bấm Bắt đầu hoặc Xong" (grooming); các phím này chưa có tác dụng tới khi T37 nối. `ShortcutHelp` hiện phím `N` cho cả quản lý chi nhánh dù bàn của ông không mở hộp thoại tiếp nhận.
- **Code của clinic không còn ai dùng** (để T37 gỡ cùng lúc nối bệnh án; không sửa vì `clinic.api.ts` / `clinic-db.ts` / `types/clinic.ts` còn cần cho T37):
  - `clinic.api.ts`: `listTodayVisits`, `listArrivals`, `searchCustomers`, `listStaff`, `checkIn` (+ `CheckInInput`), `assignVisit`, `cancelVisit` (chỉ còn `clinic.api.test.ts` gọi). `callVisit` / `completeVisit` / `getVisitDetail` / `saveRecord` / `add|removeVisitLine` / `replaceVisitService` / `getCatalog` / `getDashboardSummary` / `resetDemo` vẫn có người dùng (`useMoveVisit`, `useVisitDetail`, `useSaveRecord`, `useVisitLines`, `useCatalog`, `useDashboardSummary`, `useResetDemo`). Dead từ T35: `listPendingOrders`, `getOpenShift`, `openShift`, `collectPayment`.
  - `types/clinic.ts`: `ArrivalView` (hết người dùng khi FrontDeskPanel / CheckInDialog đổi sang `Appointment`), `CustomerWithPets`, `StaffPosition`, `Customer` (chỉ `clinic.api.ts` còn dùng).
  - `clinic-db.ts`: dữ liệu lượt / lịch hẹn / nhân viên / số thứ tự `queueNo` mà chỉ các hàm trên dùng.
  - `constants/queryKeys.ts`: `QUERY_KEYS.visits.today` (chỉ `useMoveVisit` còn đặt cache lạc quan vào đó), `appointments.arrivals`, `customers.search`, `staff.all`, `orders.pending`; `INVALIDATE.checkIn`, `assignVisit`, `cancelVisit`, `saveRecord`.
  - `utils/clinic-rules.ts`: `positionFor`, `dropTransition`, `boardColumnOf`, `arrivalState`, `queuePriority`, `sortQueue` (chỉ `clinic.api.ts` / test); `callViolation` / `canTransitionVisit` còn qua `clinic.api.callVisit`. `utils/grooming-board.ts`: `buildBoard` / `BoardColumn` hết người dùng (còn `BoardScope`).
  - `hooks/useClinic.ts` đã gỡ `useTodayVisits`, `useArrivals`, `useCustomerSearch`, `useStaffList`, `useCheckIn`, `useAssignVisit`, `useCancelVisit` (không còn importer, kể cả test).
  - Lưu ý khóa cache: gốc `['visits']` của clinic (`QUERY_KEYS.visits`) trùng gốc của `useVisits`; `['visits','detail', id]` dùng cho cả hai (id chuỗi `visit-5` của clinic, id số của API), nên T37 nên đổi sang một khóa riêng khi nối bệnh án.
- **Để nguyên:** `FE/e2e/workspaces.spec.ts` (kịch bản bàn cũ), `AdminDashboardPage` (KPI trên `clinic-db`), `ui.tsx` (`Ticket`, `VisitStatusChip`, `PriorityChip` còn kiểu `clinic`).
- **Chưa làm:** khi lịch hẹn quá hạn (409 NO_SHOW) chưa có nút "Tiếp nhận như khách vãng lai" đổi thẳng sang walk-in với thú đã chọn (hiện chỉ báo cách làm); chưa có ô đổi người phụ trách ngay lúc tiếp nhận (B.5-1).

### B.8 TDD, mutation và kiểm chứng

**Đỏ trước (đúng lý do: route đã có, đỏ vì UI chưa có hành vi):**

| File test | Đỏ trước | Lý do đỏ | Xanh ngay |
|---|---|---|---|
| `reception/arrival.test.ts` | 5/5 | bản giả trả `{ON_TIME, ''}` nên lệch kỳ vọng | — |
| `reception/queue-rules.test.ts` | 5/10 | bản giả trả `[]` / `false` | 5 ca "rỗng / false" (trùng bản giả; mutation R1–R4 bảo chứng) |
| `reception/FrontDeskPanel.test.tsx` | 28/31 | panel cũ đọc `clinic-db`: không có Mochi, mã `TN-…`, nhãn, nút, hộp thoại | 3 ca "Chờ thu" (T35, giữ nguyên ý nghĩa) |
| `reception/CheckInDialog.test.tsx` | 17/18 (+3/3 ca chi nhánh thêm sau review) | hộp thoại cũ đọc `arrival.service.id` (không có ở `Appointment` của API), không có ô "Tìm khách tiếp nhận" | 1 ca "thiếu thú hoặc dịch vụ" (cùng câu chữ với hộp thoại cũ). 6 ca đỏ thêm là lỗi của chính test (hai hồ sơ cùng tên; toast có 2 tham số), sửa test, không sửa code |
| `pages/staff/ReceptionWorkspacePage.test.tsx` | 2/4 ca mới | nút "Tiếp nhận khách" và phím `N` còn cho quản lý chi nhánh; đếm tab | phím `N` / `F1` của lễ tân (hành vi đã có) |
| `pages/staff/DoctorWorkspacePage.test.tsx` | 12/12 | trang cũ đọc `clinic-db` | 2 ca về sau (chỉ gọi lượt của mình; thứ tự sắc hơn) viết khi trang đã xong |
| `components/staff/doctor/SoapPanel.test.tsx` | — | hai ca BR-KB-02 **chuyển nguyên** từ `DoctorWorkspacePage.test` (panel không đổi) | 2/2 |
| `shared/hooks/useKanban.test.ts` | 2/8 | hàm cũ nhận `VisitView`; thiếu lời nhắn T37 | 6 ca trùng kết quả ở nhánh BR-TN-09 / BR-TN-05 |
| `grooming/GroomingBoard.test.tsx` | 10/10 | bảng cũ đọc `clinic-db` | — |
| `pages/staff/GroomingWorkspacePage.test.tsx` | 2/3 | viết sau khi trang đã đổi; kiểm đỏ bằng cách đặt tạm trang cũ từ HEAD, rồi khôi phục và `cmp` | ca "chưa có bé nào" (cả hai trang cùng câu) |
| `shared/hooks/useWorkspaceSignals.test.tsx` | 3/9 | hook cũ đọc `clinic-db`, không gọi `getMyQueue` | 3 ca "vai trò khác không hỏi hàng đợi của tôi" (mã cũ không hỏi gì; mutation G9 bảo chứng) |
| `components/staff/StaffSidebar.test.tsx` | 3/3 ca mới | số đếm cũ đọc `clinic-db` | — |

**Mutation (sửa một chỗ, chạy test liên quan, thấy đỏ, khôi phục byte-for-byte rồi so bằng `Buffer.compare`): 35 mutation, 35 bị bắt** (33 ngay lần đầu; G11 sống vì không test nào dựng vai trò thiếu quyền trước danh sách hàng đợi, đã thêm ca "bác sĩ vào `FrontDeskPanel`" rồi bắt được; O2 ban đầu chỉ bị bắt bởi ca khác vì hai khóa bằng nhau, đã đổi khóa trong test; sau khi hộp thoại chuyển sang tải lười, G7, G8, X1, X2, P1–P3 chạy lại đều vẫn bị bắt).

| Nhóm | Mutation đã bị bắt |
|---|---|
| Quyền theo vai trò (G1–G11) | thanh bên: VET hỏi lịch hẹn chờ check-in · nút tiếp nhận / Khách vãng lai / phím `N` / nút đầu trang cho mọi vai trò · nút gán, đưa lên trước, cấp cứu, hủy, gán lại thiếu `perms.*` · toast lượt mới hỏi `GET /me/queue` cho mọi vai trò · danh sách nhân viên hỏi cho mọi vai trò |
| `ExamPendingPanel` không đưa mã API vào clinic-db (E1) | trang bác sĩ gọi `useVisitDetail(String(visitId))` (13 test đỏ: gọi `clinicApi.getVisitDetail`, hiện lỗi) |
| Tên trường tiếp nhận (C1–C3, B1) | `emergency` thay `isEmergency` (lịch hẹn và vãng lai) · lịch hẹn thiếu `petId` · hỏi dịch vụ khi chưa biết chi nhánh |
| Thứ tự BE (O1–O3) | hàng chờ lễ tân / hàng đợi bác sĩ / cột grooming sắp lại theo `queueSortKey` |
| Hủy lượt (X1–X2) | gửi `reason: ''` thay vì không có body · không cắt khoảng trắng |
| Quy tắc thuần (R1–R5) | bỏ ranh giới cấp cứu · cho đích đã gọi · bỏ điều kiện người phụ trách không đủ điều kiện · luôn hiện "Gán lại" · mốc trễ 15 phút lệch một biên |
| Chọn người (P1–P3) | bỏ câu chờ BE-1 · bỏ cảnh báo ngoại tuyến · luôn lấy VET |
| Grooming (K1–K4) | không giữ thẻ ngay · không trả thẻ về khi BE từ chối · "Xong" bật · cho bắt đầu thẻ của người khác |
| Gọi lượt (D1–D2) | nút gọi cho lượt đã gọi · cho lượt của người khác |

**Kiểm chứng (từ `FE/`):**
- `npx tsc --noEmit`: sạch.
- `npx eslint <các file T36 UI> --max-warnings 0`: sạch (13 lỗi `no-unused-vars` có sẵn ở `AdminAccountsPage`, `AdminSystemConfigsPage`, `identity.mock.ts` không thuộc T36).
- `npx vitest run`: **88 file, 1077 test xanh** (nền 84 / 979: +5 file mới `arrival`, `queue-rules`, `CheckInDialog`, `SoapPanel`, `GroomingWorkspacePage`; −1 file `assignee.test.ts` đã xóa cùng `assignee.ts`).
  - Máy rảnh (lần chạy đầu, 48 giây): 88 / 88 xanh. Máy bận (game client, Docker, BE chạy nền; cả bộ ~90 giây thay vì 48): `npx vitest run` mặc định **đỏ** `src/app/App.shell.test.tsx › staff shell › keeps the same sidebar when the desk opens the overview` ở 5 trên 6 lần chạy sau đó (lần cuối thêm `pages/customer/BookingPage.test.tsx` đỏ; một lần khác thêm `ForgotPasswordPage.test`, `SecurityPage.test`), toàn là hết hạn `findBy` 1 giây. Với `App.shell`: lần đầu mở trang lễ tân tải lười mất khoảng 0,9 giây khi rảnh, vượt 1 giây khi bận. Chạy riêng `App.shell.test.tsx` 9/9 xanh (nhiều lần); chạy cả bộ với `--maxWorkers=4` 88 / 88 xanh (1077 test). Loại các file test của T36 khỏi bộ không làm hết lỗi, nên đây là độ trễ theo tải máy chứ không phải do số test thêm. Trang lễ tân đã chuyển hộp thoại tiếp nhận và hộp thoại thao tác sang tải lười để nhẹ phần tải đầu.
- `npm run build`: thành công.
