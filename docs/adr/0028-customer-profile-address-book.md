# ADR-0028: Hồ sơ khách và sổ địa chỉ tự sửa (khóa `customers`, mặc định, xóa cứng địa chỉ, email theo tài khoản)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-10
- **Liên quan:**
  - BR-KH-01 (hồ sơ tại quầy bắt buộc có SĐT; hồ sơ online lấy email theo tài khoản), BR-TK-15 (phạm vi tự sửa, email không tự sửa), BR-TK-18 (tối đa 5 địa chỉ [CFG], đúng 1 mặc định, không xóa mặc định khi còn địa chỉ khác), BR-TK-19 (cờ chờ liên kết), BR-TK-11, BR-QT-13; UC06.
  - customer-v1 #1–7 `GET/PATCH /me/customer-profile`, `/me/addresses…`, giả định A1 (không trả nghi trùng cho khách), A5 (email hồ sơ có tài khoản theo tài khoản).
  - `docs/05-erd.md` `customers` (L304–320), `addresses` (L322–335, `uq_addresses_default_per_customer` không deferrable), §0 L41 (danh sách xóa cứng); `docs/06-module-contracts.md` §6, §7 G6.
  - ADR-0004, 0006, 0015, 0020, 0026 (null = giữ / rỗng = xóa, SĐT `mobile`, ảnh `https`), 0027 (UC07: khóa `accounts → customers`, sổ địa chỉ online xóa cùng hồ sơ). Nợ D001, D010, D013.
- **Triển khai (task 08 phần 4, BE-1 làm thay owner BE-2 theo thỏa thuận 10/10):**
  - `customer/entity/{Customer, CustomerChannel, Address}`, `customer/repository/{CustomerRepository, AddressRepository}`, `customer/service/{MyCustomerLookup, MyCustomerProfileService, AddressBookService, Values}`, `customer/controller/MyCustomerController`, `customer/dto/{CustomerProfileResponse, UpdateMyCustomerProfileRequest, AddressResponse, AddressRequest, UpdateAddressRequest}`, `customer/mapper/{CustomerProfileMapper, AddressMapper}`.
  - `V10__address_customer_index.sql` (`ix_addresses_customer_id`; số V10 chốt với BE-2 ngày 10/10).
  - Test: `MyCustomerLookupTest`, `MyCustomerProfileServiceTest`, `AddressBookServiceTest`, `CustomerMappersTest`, `module/customer/CustomerProfileIT`, `module/customer/AddressBookIT`, `SchemaMigrationIT.foreignKeysToCustomersWithoutUsableIndexAreExactlyDebtD013`.

## Bối cảnh

- Phần identity của T8 đã xong (`GET /me`, hồ sơ nhân viên, UC07). UC06 phía khách — hồ sơ khách và sổ địa chỉ — nằm trong customer-v1 (module của BE-2), nhưng module `customer` chưa có entity nào và T17 của BE-2 chưa bắt đầu. FE (T13) đang chờ các endpoint này.
- Hibernate ghi **đủ mọi cột** của entity bẩn: đọc hồ sơ không khóa rồi ghi lại sẽ ghi đè thay đổi đã commit của luồng khác (UC07 gỡ cờ / gán tài khoản, lễ tân sửa hồ sơ ở UC22).
- BR-TK-18 là phép đếm (tối đa N) và một bất biến (đúng 1 mặc định). DB chỉ chặn được phần "≤ 1 mặc định" (partial unique, **không deferrable**); hai `POST` song song có thể cùng thấy 4 địa chỉ. Hibernate flush **INSERT trước UPDATE**, nên gỡ cờ cũ bằng setter rồi chèn địa chỉ mặc định mới sẽ vấp index → 409.
- `customers.email` của hồ sơ online có thể NULL: `CustomerApi.createOnlineProfile(accountId, fullName, phone)` không nhận email (câu hỏi đang treo cho BE-2 ở 06 §6), trong khi BR-KH-01 ghi "email lấy theo tài khoản".
- erd §0 L41 liệt kê các trường hợp được xóa cứng (BR-KH-06, BR-TK-08, BR-TK-19, BR-BV-02, BR-KB-04) nhưng không có BR-TK-18, dù UC06 / BR-TK-18 cho khách xóa địa chỉ và bảng `addresses` không có cột trạng thái.

## Quyết định

1. **BE-1 tạo khung entity `Customer` (đủ mọi cột của V1) và `Address` trong module `customer`**, cùng 7 endpoint customer-v1 #1–7. `CustomerApi`, `CustomerQueryApi` vẫn là placeholder (D001, D010 không đổi). BE-2 mở rộng chính các entity này cho T17, không tạo entity thứ hai cho cùng bảng.
2. **Mọi lệnh ghi khóa dòng `customers` của chủ trước** (`@Lock(PESSIMISTIC_WRITE)` → `FOR NO KEY UPDATE`, theo `account_id` của token), rồi mới đọc entity / đếm / đổi cờ. Thứ tự khóa **`customers → addresses`**; không bao giờ khóa `accounts`, nên không tạo vòng chờ với UC07 (`accounts → customers`). Hai `GET` đọc không khóa (`readOnly`).
3. **Đọc lại khi hồ sơ bị thay trong lúc chờ khóa.** UC07 có thể xóa hồ sơ online O và gán tài khoản vào hồ sơ tại quầy C trong lúc request chờ khóa O; snapshot của câu khóa đầu không thấy C → không có dòng. `MyCustomerLookup.lockOwner` khi đó đọc lại **id** bằng câu mới (READ COMMITTED, snapshot mới), khóa theo id và kiểm lại `account_id` dưới khóa; vẫn không có → `IllegalStateException` (500, dữ liệu sai BR-KH-01). Chỉ đọc id để entity được nạp lần đầu dưới khóa.
4. **Đổi địa chỉ mặc định:** gỡ cờ cũ bằng câu `UPDATE … SET is_default = false, updated_at = :now WHERE customer_id = ? AND is_default` chạy **ngay** (`@Modifying(flushAutomatically = true)`, không `clearAutomatically`; tự đặt `updated_at` vì câu bulk bỏ qua auditing — ADR-0015) **trước** khi INSERT / UPDATE dòng có cờ mới. Bước sau lỗi thì rollback cả bước gỡ cờ.
5. **Quy tắc mặc định (BR-TK-18):** sổ đang rỗng thì địa chỉ thêm vào **luôn** là mặc định, bỏ qua `isDefault = false` (customer-v1 A6); `isDefault = true` khi sổ có địa chỉ → chuyển cờ. Xóa địa chỉ mặc định khi còn địa chỉ khác → 400 BR-TK-18; là địa chỉ duy nhất thì xóa được (sổ rỗng, không còn mặc định). Đặt mặc định cho địa chỉ đã mặc định → 200, không ghi. Giới hạn `address.max_per_customer` [CFG] đếm dưới khóa; ADMIN hạ giới hạn chỉ chặn thêm mới (BR-QT-13).
6. **Xóa cứng địa chỉ.** BR-TK-18 / UC06 cho xóa, `addresses` không có cột trạng thái, không bảng nào có FK trỏ tới `addresses`, UC07 vốn đã xóa cứng sổ địa chỉ của hồ sơ online (ADR-0027). Bổ sung BR-TK-18 vào danh sách xóa cứng ở erd §0 và ghi erd §13 mục 16. Không thêm cột xóa mềm.
7. **`email` trong `CustomerProfile` là email của tài khoản đang đăng nhập** (`SecurityPrincipal.email()`, đọc từ `accounts` mỗi request), không đọc `customers.email` — đúng BR-KH-01 / A5 và không lệch khi sửa hộ email (BR-TK-16).
8. **PATCH hồ sơ:** có `email` → 400 BR-TK-15 trước mọi đọc; `null` = giữ, rỗng sau `strip` = xóa với `phone`, `avatarUrl` (như ADR-0026); hồ sơ `COUNTER` (đã liên kết) xóa SĐT → 400 BR-KH-01, kiểm trước khi ghi nên không bao giờ rơi xuống CHECK `ck_customers_counter_phone` (409). Không đụng `link_decision_pending`, `created_channel`, `account_id`, `customers.email`; không trả danh sách nghi trùng (A1).
9. **Định dạng (chốt G6 phía customer):** `phone` (request) và `receiverPhone` theo kiểu `mobile` `^0\d{9}$`; `avatarUrl` (request) chỉ `https://`. **Response** `CustomerProfile.phone` / `avatarUrl` giữ kiểu chung, vì hồ sơ tại quầy do BE-2 tạo có thể có SĐT 11 số hoặc ảnh khác `https`; PATCH không gửi `phone` thì giá trị cũ giữ nguyên.
10. **Địa chỉ của người khác = không có** → 404 cùng message với id không tồn tại (customer-v1 A8); chủ lọc ngay trong SQL (`id AND customer_id`), sau khi đã khóa hồ sơ. Danh sách: mặc định trước, rồi theo `id` (A7), mảng thẳng trong `data`.
11. **Cờ chờ liên kết không chặn** hồ sơ và sổ địa chỉ: BR-TK-19 chỉ ẩn thú cưng, đặt lịch, lưu trú, hồ sơ sức khỏe, feedback.
12. **Không audit** (BR-QT-15 và convention 08 §8.3 không liệt kê). Không dùng `noRollbackFor`: không có bộ đếm hay dấu vết nào phải giữ khi bị từ chối.
13. **Thứ tự lỗi:** Bean Validation của body chạy **trước** `@PreAuthorize` (Spring dựng tham số trước khi gọi method qua proxy), nên nhân viên gửi body sai hình thức nhận 400 thay vì 403 — giữ như `PATCH /me/staff-profile`, không thêm luật theo URL ở platform. Sau đó: 404 → guard 400 (convention 02).
14. **Không kiểm lại BR-TK-11 dưới khóa** (khác ADR-0026 mục 2): module customer không khóa được `accounts`. Tài khoản bị khóa đúng lúc request đã qua filter thì request đó vẫn hoàn tất; cửa sổ chỉ dài một request, khóa / vô hiệu hóa hủy mọi phiên (BR-TK-11).
15. **V10 `ix_addresses_customer_id`** cho câu đọc / đếm theo chủ và kiểm FK khi xóa `customers` — trả phần `addresses` của nợ D013.

## Lý do

- Khóa một dòng `customers` là cách nhỏ nhất tuần tự hóa cả phép đếm lẫn đổi cờ của cùng một khách; dữ liệu của các khách khác không bị chặn. Khóa `accounts` (như ADR-0026) đòi interface mới sang identity và thêm một bảng vào thứ tự khóa.
- Câu UPDATE chạy ngay tránh được thứ tự flush của Hibernate mà không cần index deferrable (sửa V1) hay hai lần `flush()` rải trong service.
- Lấy email từ tài khoản thì đúng ngay cả khi BE-2 chưa quyết `createOnlineProfile` có lưu email hay không.

## Hệ quả

- **BE-2 (T17):** mở rộng `Customer` / `Address`; mọi luồng ghi `customers` / `addresses` của UC22 (lễ tân sửa hồ sơ, liên kết hộ) theo thứ tự `customers → addresses`, và đi sau `accounts` nếu có khóa `accounts`. `deleteOnlineProfileOfUnverifiedAccount` (ST02) phải xóa `addresses` trước hồ sơ (javadoc `CustomerApi`).
- **FE (T13):** sửa hồ sơ khách gọi `PATCH /me/customer-profile`; đổi mặc định qua `set-default`; SĐT 10 số; ảnh `https://`.
- Endpoint chạy được trên app thật kể cả khi D001 / D010 còn mở (không qua `CustomerQueryApi`), nhưng tới khi có đăng ký / đăng nhập khách thật thì chỉ thử được bằng dữ liệu SQL.
- Hai `POST` song song khi đang có N−1 địa chỉ: đúng một 201, một 400 BR-TK-18 (`AddressBookIT.concurrentAddsNeverExceedMax`).
