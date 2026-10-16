# ADR-0041: Hồ sơ khách tại quầy, tra cứu, thú cưng, cân nặng, đã mất, xóa, chuyển chủ (T17)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-16
- **Liên quan:**
  - customer-v1 #8 `GET /customers`, #9 `POST /customers`, #10 `GET /customers/{id}`, #11 `PATCH /customers/{id}`, #12 `POST /customers/{id}/pets`, #13–14 `GET/POST /me/pets`, #15–16 `GET/PATCH /pets/{id}`, #17 `POST /pets/{id}/mark-deceased`, #18 `DELETE /pets/{id}`, #19 `POST /pets/{id}/transfer-owner`, #20–21 `GET/POST /pets/{id}/weights` (UC22–UC26).
  - 02 BR-KH-01…08, 10; BR-TK-16, 17, 19; 03 Phụ lục (Thú đã mất, Chuyển chủ); 04 §3; 05 §3 (`customers`, `pets`, `weight_records`); 06 §3 (`PetDeceasedEvent`, `PetOwnerTransferredEvent`), §4, §6; convention 08 §8.3.
  - ADR-0006 (phạm vi dữ liệu của khách), 0013 (ST02), 0025, 0027 (UC07), 0028 (khung customer), 0029 (khóa trước đọc sau), 0032, 0038 (khuôn tra cứu), 0040 (Swagger = hợp đồng). Trả nợ D001, D010, D013; nợ mới ghi ở `docs/dept`.
- **Triển khai:**
  - `customer/controller/{CustomerController, PetController}`; `customer/service/{CustomerSearchService, CounterCustomerService, PetService, PetWeightService, PetLifecycleService, PetAccess, PetViews, CustomerApiService, CustomerQueryService, CustomerDataProbe, PetQueryService, PetWeightApiService, Maskers, CustomerAuditActions}`; `customer/entity/{Pet, PetSex, WeightRecord}` (+ `Customer` mở rộng); `customer/repository/{PetRepository, WeightRecordRepository, CustomerSearchRepository}`; `customer/dto/*`; `customer/mapper/{CustomerStaffMapper, PetMapper}`.
  - Cài thật một phần ở module khác: `visit/service/VisitQueryApiPlaceholder`, `appointment/service/AppointmentQueryApiPlaceholder`, `boarding/service/BoardingQueryApiPlaceholder`, `sales/service/OrderQueryApiPlaceholder` (giữ tên, method T17 cần chạy SQL, còn lại vẫn ném), `content/service/FeedbackQueryService` (mới). Xóa `CustomerApiPlaceholder`, `CustomerQueryApiPlaceholder`, `PetQueryApiPlaceholder`.
  - Thay đổi `api/`: `PetQueryApi.findPetForShare`, `BoardingQueryApi.findBookingIdsByPet`.
  - Migration `V13__pet_customer_fk_indexes.sql`.
  - Test: unit `MaskersTest`, `CustomerApiServiceTest`, `CustomerQueryServiceTest`, `PetWeightApiServiceTest`, `CounterCustomerServiceTest`, `PetServiceTest`, `PetWeightServiceTest`, `PetLifecycleServiceTest`, `PetMapperTest`, `CustomerRequestValidationTest`, `PetRequestValidationTest`; IT `CrossModuleQueryIT`, `CustomerModuleApiIT`, `CounterCustomerIT`, `PetIT`, `PetLifecycleIT`; `SchemaMigrationIT` (V13, D013 hết); `OpenApiContractParityIT` (59 endpoint).

## Bối cảnh

Từ ADR-0028 module customer mới có khung `Customer` / `Address` và sổ địa chỉ của khách. Hồ sơ tại quầy, tra cứu, thú cưng, cân nặng, đánh dấu đã mất, xóa và chuyển chủ chưa có. `CustomerApi`, `CustomerQueryApi`, `PetQueryApi` còn là placeholder ném lỗi nên đăng ký, đăng nhập và `GET /api/me` của khách trả 500 (D001, D010). Mọi guard của thú cưng (khóa loài, xóa, đã mất, chuyển chủ) cần dữ liệu của visit / appointment / boarding / sales, bốn module này cũng chưa có bản thật.

## Quyết định

### 1. Phân quyền và phạm vi (customer-v1 B)

1. Hồ sơ khách và thú dùng chung toàn chuỗi: **không** gọi `BranchScope.check/resolve`. Tra cứu / xem chi tiết: RECEPTIONIST, VET, CARETAKER; tạo / sửa hồ sơ, thêm thú cho khách, chuyển chủ: chỉ RECEPTIONIST; ADMIN, SUPER_MANAGER, BRANCH_MANAGER **không** có quyền nào trong nhóm này (hợp đồng không liệt kê — FE phải ẩn).
2. Endpoint dùng chung khách + nhân viên (#15, #16, #17, #18, #20) rẽ nhánh theo `accessScope == OWNER` trong `PetAccess`. Thú của người khác — kể cả chủ cũ sau chuyển chủ — là "không có": **404 cùng message** với id không tồn tại (customer-v1 D1), không bao giờ 403.
3. Hồ sơ khách còn cờ `link_decision_pending` thì **mọi** thao tác thú cưng của khách trả 400 `BR-TK-19`, kiểm trước chủ sở hữu (customer-v1 D2). Lễ tân vẫn thao tác được trên hồ sơ đó. Hợp đồng được sửa: thêm 400 `BR-TK-19` cho #15, #20 và vào mô tả 400 của #16, #17, #18, #21 (sửa generator `m_customer.py`, chạy lại `generate.py`).
4. Không endpoint nào miễn BR-TK-17.

### 2. Khóa và thứ tự kiểm

Thứ tự khóa của module: `accounts → customers (mã tăng dần) → pets → weight_records`.

- Thêm thú: khóa dòng `customers` của chủ trước (#14 qua `MyCustomerLookup.lockOwner`, #12 qua `findByIdForUpdate`) — tuần tự với UC07, vốn kiểm "chưa có thú" dưới khóa rồi xóa hồ sơ online.
- Mọi lệnh ghi thú (#16–#18, #21, `PetApi.recordWeight`): `PetRepository.findByIdForUpdate` (`FOR NO KEY UPDATE`) là lần nạp entity đầu tiên (bẫy persistence context, ADR-0029 mục 4); chủ và trạng thái đã mất kiểm **dưới khóa**.
- Chuyển chủ: đọc projection `customer_id` (không nạp entity) → khóa `customers` {chủ cũ, chủ mới} theo mã tăng dần → khóa `pets` → chủ đổi trong lúc chờ → 409 `ConcurrencyConflictException`.
- Mọi câu hỏi sang visit / boarding / sales (`hasOpenVisit`, `isPetInStay`, `hasPendingOrderForPet`, `existsByPet`…) chạy **sau** khi đã giữ khóa dòng thú.

Thứ tự kiểm khi sửa: BR-TK-19 → 404 → chủ (404) → đã mất (BR-KH-05) → đổi loài khi có bệnh án / mũi tiêm (BR-KH-03) → ngày sinh tương lai (BR-KH-02) → ghi. Mọi lần từ chối trước lệnh ghi đầu tiên.

### 3. Nghĩa vụ khóa cho module chèn dòng trỏ tới thú (R1)

Khóa FK `FOR KEY SHARE` của câu INSERT **không** xung đột với `FOR NO KEY UPDATE` customer giữ khi đánh dấu đã mất / chuyển chủ. Nếu không có gì khác, một Visit có thể chen vào giữa lúc customer vừa kiểm `hasOpenVisit` và lúc commit. Vì vậy thêm `PetQueryApi.findPetForShare` (`MANDATORY`, `SELECT … FOR SHARE`): tiếp nhận (Visit#1), đặt / đổi lịch (Lịch hẹn#1, #2), đặt chỗ lưu trú (Đặt chỗ#1, #3) phải gọi nó **trước khi chèn** rồi kiểm lại `deceased` và chủ dưới khóa. `FOR SHARE` xung đột với `FOR NO KEY UPDATE`: bên đến sau chờ bên đến trước commit rồi đọc lại dữ liệu đã commit (READ COMMITTED). Cùng loại nghĩa vụ với ADR-0031 §9. `PetLifecycleIT` có ba race (đánh dấu đã mất, xóa, chuyển chủ ∥ chèn dòng giao dịch dưới `FOR SHARE`); đảo thứ tự "đọc trước khi khóa" làm race đầu đỏ.

### 4. Tra cứu (#8, BR-KH-10)

- Cần ít nhất một điều kiện (`phone`, `name`, `email`, `petName`, rỗng = không gửi) → không có thì 400 `VALIDATION_FAILED` (constraint cấp lớp `@ValidCustomerSearchFilter`, lỗi gắn vào `phone`). Các điều kiện nối bằng AND.
- `phone` và `email` khớp đúng (`email` chuẩn hóa chữ thường; mọi lệnh ghi `customers.email` lưu chữ thường), `name` và `petName` "chứa chuỗi" bằng `ILIKE '%x%' ESCAPE '!'` — **không phân biệt hoa thường nhưng có phân biệt dấu** (không `unaccent`, không `pg_trgm`: quét bảng — nợ ghi ở docs/dept).
- Truy vấn dựng bằng `NamedParameterJdbcTemplate`, chỉ ghép điều kiện đã gửi (không `IS NULL OR`, như ADR-0038); sắp `full_name, id`; `size` kẹp 1–100, `page` âm = 0. Thú của cả trang đọc bằng **một** câu `customer_id IN (…)`. Đọc ở READ COMMITTED (số đếm và trang có thể lệch một dòng nếu hồ sơ được tạo giữa hai câu).
- Email luôn che (`la***@gmail.com`; quy tắc như `identity/EmailMasker`), họ tên ứng viên liên kết che theo ví dụ BR-TK-19: từ đầu 2 ký tự + `***`, từ giữa 1 ký tự + `**`, từ cuối chỉ chữ đầu → "Ng*** V** A". Cả hai là giả định (đặc tả chỉ cho ví dụ).

### 5. Hồ sơ tại quầy (#9, #11, BR-KH-01, 10, BR-TK-16)

- Tạo: `COUNTER`, `account_id` null, `created_by` = lễ tân; họ tên + SĐT bắt buộc (`^0\d{9,10}$`, 10–11 số như hợp đồng); email tùy chọn, lưu chữ thường.
- SĐT trùng hồ sơ khác vẫn tạo (201) và trả `duplicatePhoneProfiles` — hồ sơ **khác** (mọi nguồn) cùng SĐT, tối đa **20** (giả định: một SĐT dùng chung có thể khớp hàng trăm hồ sơ), theo mã tăng dần. Ở #11 chỉ tính khi SĐT thực sự đổi sang giá trị khác; ngoài ra `[]`.
- Sửa: khóa dòng rồi mới đọc → 404 → `null` giữ, rỗng xóa → hồ sơ tại quầy xóa SĐT → 400 `BR-KH-01`; `email` gửi lên khác email hiện tại khi hồ sơ đã gắn tài khoản → 400 `BR-TK-16` (A5; gửi lại đúng email hiện tại được coi là không đổi). Email của hồ sơ chưa gắn sửa / xóa được.
- Không audit (BR-KH-01 không ghi "ghi audit"). `CustomerProfile.email` cho nhân viên là `customers.email`; cho khách (`/me`) vẫn là email tài khoản (ADR-0028).

### 6. Thú cưng (#12–#16, BR-KH-02, 03)

- Bắt buộc `name` (≤ 100), `species` (`DOG|CAT|OTHER`), `sex`; giá trị enum lạ → 400 `MALFORMED_REQUEST`. Ngày sinh không ở tương lai kiểm bằng bean `Clock` (không `@PastOrPresent`) → 400 `BR-KH-02`; `photoUrl` chỉ `https://` (06 G6).
- `duplicateNameWarning`: có thú **khác** của cùng chủ, cùng loài, tên trùng không phân biệt hoa thường (kể cả thú đã mất); ở PATCH chỉ tính khi tên hoặc loài đổi. Không chặn.
- `speciesLocked` = `VisitQueryApi.hasMedicalHistory` (có `medical_records` của Visit của thú hoặc `vaccinations`); `deletable` = chưa mất và `appointments`, `boarding_bookings`, `visits` không có dòng nào của thú; `currentWeightKg` = `weight_records` mới nhất theo `(measured_at, id)`. Danh sách N thú gọi 3 query API cho mỗi thú (chấp nhận).
- Đổi loài: chỉ hỏi visit khi loài gửi lên **khác** loài hiện tại.

### 7. Đánh dấu đã mất, xóa, chuyển chủ (#17–#19)

- **Đã mất:** khóa thú → chủ → **đã mất rồi → 409 `InvalidStateTransitionException`** (bấm hai lần, không hoàn tác) → ngày mất tương lai hoặc trước ngày sinh → 400 `BR-KH-05` → Visit mở / đang lưu trú (`CHECKED_IN`, `OVERDUE`) → 400 `BR-KH-05` nêu đủ lý do → `markDeceased` → `flush()` → `PetDeceasedEvent`. Thân `confirmed` bắt buộc `true` (A3). Không audit.
- **Xóa:** thú đã mất → 400 `BR-KH-05` (A2); có lịch hẹn / đặt chỗ / Visit (kể cả đã hủy, đã hoàn tất) → 400 `BR-KH-06`; ngoài ra xóa cứng cân nặng của thú (chỉ có thể là `OWNER`) rồi thú → 204. Order luôn kèm Visit / đặt chỗ nên không gọi sales. Không audit.
- **Chuyển chủ:** `previousOwnerConfirmed = true` (A4); chủ mới trùng chủ hiện tại → 400 `BR-KH-08`; chủ mới không có → 404; đã mất → 400 `BR-KH-05`; Visit mở / Order `PENDING` / đang lưu trú → 400 `BR-KH-08` nêu đủ lý do; ghi `PET_OWNER_TRANSFERRED` (`entity = pets`, before/after `{customerId}`) và phát `PetOwnerTransferredEvent` sau `flush()`. Order, Visit, hóa đơn cũ giữ `customer_id` cũ; lịch sử khám, tiêm, cân nặng đi theo thú. Chuyển vào hồ sơ online còn cờ chờ liên kết không bị chặn (không có rule) — hồ sơ đó thành "đã phát sinh dữ liệu" nên khách mất đường liên kết UC07: câu hỏi mở cho PO.
- Sự kiện phát đồng bộ trong cùng transaction; listener ném lỗi thì rollback cả việc đổi thú và audit (`PetLifecycleIT`). **Chưa module nào cài listener** (appointment, boarding, care ST19, visit) — nợ ghi ở docs/dept; hiện các bảng đó còn rỗng nên chưa sai dữ liệu.

### 8. Cân nặng (#20, #21, `PetApi.recordWeight`, BR-KH-04)

- Mới nhất trước, không phân trang. Cân ≤ 0 → 400 `BR-KH-04` từ service (không `@Positive`, để mã rule đúng); `@Digits(4, 2)` khớp `NUMERIC(6,2)`; `measuredAt` mặc định bây giờ, không quá **5 phút** ở tương lai (lệch đồng hồ trình duyệt) → 400 `BR-KH-04`. Cân bù ngày cũ không thay cân hiện tại nếu đã có bản mới hơn (so theo `measured_at`).
- `PetApi.recordWeight` (`MANDATORY`): VET bắt buộc `visitId`, INTAKE bắt buộc `boardingBookingId`, OWNER không có cả hai — sai là `IllegalArgumentException`; khóa thú, đã mất → `BR-KH-05`.
- Hồ sơ sức khỏe cho khách (BR-KH-07) ngoài lịch sử cân nặng là `GET /pets/{petId}/health-record` của module visit (visit-v1 #21, T27/T28), không làm ở đây.

### 9. `CustomerApi` / `CustomerQueryApi` / `PetApi` / `PetQueryApi` thật

- Mỗi interface một bean (`CustomerApiService`, `CustomerQueryService`, `PetWeightApiService`, `PetQueryService`): một bean cho hai interface làm hai `@MockitoBean` của IT module khác đè lên nhau (đã gặp ở lần chạy đầu).
- Lệnh ghi của `CustomerApi` là `MANDATORY`, không `noRollbackFor`, chỉ ném `BusinessRuleViolationException("BR-TK-19")` ở luồng liên kết, trước lệnh ghi đầu tiên. `linkAccountToCounterProfile` khóa hồ sơ online và hồ sơ tại quầy theo mã tăng dần, kiểm lại dưới khóa (online chưa có thú / lịch hẹn / đặt chỗ / Order / feedback; tại quầy chưa có tài khoản và đúng email đã nhận mã), xóa `addresses` → hồ sơ online → `flush()` → gán `account_id`, `email`.
- `createOnlineProfile(accountId, fullName, phone)` **không nhận email** (chữ ký của BE-1): `customers.email` của hồ sơ online để null; `/api/me` vẫn đúng vì lấy email tài khoản. Hệ quả: kênh nhắc email (BR-TB-02) của khách online hiện chưa có địa chỉ — cần thêm tham số `email` khi hai bên chốt (06 §6 dòng `CustomerApi`).
- Cài thật một phần ở module khác để guard chạy được (xem phần Triển khai): đọc thẳng bảng V1 trong transaction của bên gọi, giữ nguyên tên lớp `*Placeholder` (chủ module chỉ cần thay thân method, không xung đột xóa/sửa tệp). Method còn lại vẫn ném `UnsupportedOperationException`.

### 10. Migration V13

`ix_boarding_bookings_pet_id`, `ix_boarding_bookings_customer_id`, `ix_care_tasks_pet_id`, `ix_care_tasks_customer_id`, `ix_visits_customer_id`, `ix_payments_customer_id`: trả nợ D013 (xóa hồ sơ online của ST02 / UC07 không còn quét bảng) và cho `existsByPet` / `isPetInStay`. `SchemaMigrationIT.everyForeignKeyToCustomersHasUsableIndex` thay test "đúng danh sách nợ D013".

## Hệ quả

- Đăng ký, xác thực OTP, đăng nhập, `GET /api/me` và `/api/me/link-*` của khách chạy thật; khóa tài khoản khách vẫn 500 vì `findBookedByCustomer`, `findActiveStaysByCustomer`, `findUnpaidOrdersByCustomer` còn ném.
- Chủ module visit / appointment / boarding / sales thay phần cài tạm bằng bản của mình và **phải** gọi `PetQueryApi.findPetForShare` khi chèn dòng trỏ tới thú.
- Chưa có: listener của hai sự kiện, tìm khách không dấu / trigram, `createOnlineProfile` có email, `health-record`.

## Phương án đã loại

- **Trả 403 cho thú của người khác:** lộ việc thú tồn tại; hợp đồng và ADR-0028 đã chọn 404.
- **Để placeholder ném lỗi:** mọi API thú cưng trả 500 trên app thật.
- **Gộp `CustomerApi` và `CustomerQueryApi` vào một bean:** `@MockitoBean` của hai interface đè lên nhau trong ITs của identity.
- **`@Positive` cho cân nặng:** trả `VALIDATION_FAILED` thay vì `BR-KH-04` mà hợp đồng ghi.
- **Chặn chuyển thú sang hồ sơ online còn cờ chờ liên kết:** không có rule; ghi câu hỏi mở thay vì tự thêm rule.
