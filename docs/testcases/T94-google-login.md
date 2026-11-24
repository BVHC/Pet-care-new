# T94 (BE + FE): Đăng nhập / đăng ký bằng Google: ca kiểm tay trên BE thật

> Phạm vi: `POST /api/auth/google` (identity-v1 #40, ADR-0152, V64) và các màn `/auth/login`, `/auth/register`. Hợp đồng: `docs/api/identity-v1.md` #40. Thiết kế: `docs/superpowers/specs/2026-11-24-google-login-design.md`.
> **Không có kết quả nào trong file này đã được chạy.** Cột Kết quả để Hiến tự điền sau khi chạy tay; tất cả bắt đầu ở "Chưa test". Mọi ca cần **client ID thật** của Hiến (Google Cloud) nên không chạy tự động được; phần tự động (unit, `GoogleLoginIT`, Vitest) nằm ở ADR-0152 mục "Test".
> Ghi DB: các ca G-01, G-03, G-04, G-05, G-09, G-10, G-11 tạo hoặc sửa dòng trong DB mà BE đang dùng. Nếu đó là DB demo 5433 thì **hỏi Hiến trước** (đặc biệt G-04 xóa dòng `PENDING`, G-05 và G-10 sửa tài khoản nhân viên / khách).

## 1. Chuẩn bị Google Cloud Console (Hiến làm một lần)

1. Tạo project, vào **APIs & Services → OAuth consent screen**: loại **External**, tên app, email hỗ trợ, scope `openid`, `email`, `profile`. Để chế độ **Testing** thì thêm **Test users** (tối đa 100): chỉ email trong danh sách này đăng nhập được. Muốn ai cũng đăng nhập được thì Publish app; ba scope trên không cần Google duyệt.
2. **Credentials → Create credentials → OAuth client ID → Web application.** *Authorized JavaScript origins*: `http://localhost`, `http://localhost:5173`, `http://localhost:3000` (Google yêu cầu thêm cả `http://localhost` không cổng khi thử trên localhost). Không cần redirect URI, không dùng client secret.
3. **Mở app bằng `localhost`, không dùng `127.0.0.1`** (Google không chấp nhận, nút báo lỗi origin). FE đã có `<meta name="referrer" content="no-referrer-when-downgrade">` trong `FE/index.html` vì chạy `http` trên localhost.
4. Email thử phải nằm trong Test users. Chuẩn bị tối thiểu **hai** tài khoản Google: một Gmail (ca G-01 đến G-05, G-08 đến G-11) và, nếu có, một tài khoản Google dùng địa chỉ **không phải Gmail và không thuộc Workspace** (ví dụ lập bằng email Yahoo) cho G-06 và G-11(b).
5. Client secret **không dùng ở đâu cả**. Nếu từng dán secret `GOCSPX-…` vào chat hoặc file thì xóa hoặc xoay nó ở Google Cloud.

## 2. Chuẩn bị môi trường

- Đặt **cùng một giá trị** client ID cho BE (`.env` gốc: `GOOGLE_CLIENT_ID=…`) và FE (`FE/.env`: `VITE_GOOGLE_CLIENT_ID=…`). `FE/.env` và `.env` gốc **chưa có** hai dòng này: thiếu thì nút Google **ẩn** ở dev (G-07). Sau khi đặt, **khởi động lại Vite và BE** (Vite đọc biến lúc khởi động; `mvn spring-boot:run` không đọc `.env`, nên export `GOOGLE_CLIENT_ID` trong shell trước khi chạy BE; docker compose đọc `.env`).
- Chạy BE trên cổng 8082 (lệnh ở `docs/testcases/test-tay-ghep-api-theo-cum.md` mục 0, cần Redis và Postgres bật) và Vite với `VITE_API_MOCK=false` và `VITE_API_URL=http://localhost:8082`. BE phải chạy mã có V64 (Flyway tự áp V64 lên DB khi khởi động). **V64 là migration một chiều:** trỏ BE này vào DB demo dùng chung (5433) là áp V64 lên DB đó, một việc **riêng** cần hỏi Hiến trước (ngoài các ca ghi dòng ở đầu file). Cách thay thế: dựng DB thử từ dump (`BE/scripts/demo-db`) hoặc sao chép DB 5433 sang một DB tạm rồi chạy BE vào DB đó (`DB_NAME=…`).
- Email (thư OTP, nếu có): Mailpit `http://localhost:8025`. Xem DB (chỉ đọc): DBeaver hoặc pgAdmin, `localhost:5433`.
- Truy vấn kiểm chung (thay `<email>` bằng Gmail thử, chữ thường):

```sql
SELECT id, email, role, status, is_locked, google_sub, pending_expires_at FROM accounts WHERE email = '<email>';
SELECT account_id, full_name, link_decision_pending FROM customers WHERE account_id = (SELECT id FROM accounts WHERE email = '<email>');
SELECT created_at, action, after_data, reason FROM audit_logs WHERE actor_email = '<email>' OR actor_account_id = (SELECT id FROM accounts WHERE email = '<email>') ORDER BY id DESC LIMIT 5;
```

- Dọn giữa các ca (khi dùng DB thử, không dùng DB demo): xóa dòng thử bằng chính chức năng của hệ thống nếu có, hoặc dựng lại DB thử.

## 3. Ca kiểm tay

Quy ước: **Gmail thử** = tài khoản Google `@gmail.com` có trong Test users. Cột Kết quả điền `Đạt` / `Lỗi` / `Bỏ qua` kèm ghi chú; ban đầu `Chưa test`.

### 3.1 Luồng chính

| Mã | Ca | Điều kiện đầu | Bước | Mong đợi | Kết quả |
|---|---|---|---|---|---|
| G-01 | Tạo tài khoản mới (BR-TK-02, bỏ OTP) | Gmail thử **chưa có** tài khoản trong hệ thống | 1) Mở `http://localhost:5173/auth/register`. 2) Bấm nút Google, chọn Gmail thử. 3) Hộp thoại xác nhận mở: nút "Tiếp tục" đang tắt. 4) Tích "đủ 18 tuổi" rồi "đồng ý điều khoản"; để trống SĐT. 5) Bấm "Tiếp tục" | Vào hệ thống như khách vừa đăng nhập (trang chủ khách), **không** hỏi OTP, Mailpit **không** có thư OTP mới. DB: một dòng `accounts` `CUSTOMER` `ACTIVE`, `google_sub` có giá trị, `pending_expires_at` NULL; dòng `customers` có `full_name` lấy từ tên Google (tối đa 100 ký tự); audit `LOGIN_SUCCEEDED` có `after_data.method = GOOGLE` và `accountCreated = true`. `/auth/register` và `/auth/login` gọi cùng một endpoint nên kết quả như nhau; muốn thử cả hai trang thì dùng **hai** Gmail chưa có tài khoản (lần thứ hai với cùng Gmail là G-02, không có hộp thoại) | Chưa test |
| G-02 | Đăng nhập lại cùng tài khoản Google | Sau G-01 | 1) Đăng xuất. 2) Mở `/auth/login`, bấm nút Google, chọn đúng Gmail thử | Vào thẳng, **không** hộp thoại; cùng `accounts.id` với G-01 (không tạo dòng thứ hai); audit `LOGIN_SUCCEEDED` có `method = GOOGLE`, không có `accountCreated`. Mật khẩu: tài khoản này không có mật khẩu dùng được, nên đăng nhập bằng email + mật khẩu bất kỳ ra 401 chung. **Chỉ thử đúng một lần**: mỗi lần sai mật khẩu vẫn đếm vào khóa tạm BR-TK-09; sai 5 lần thì tài khoản bị khóa tạm và đăng nhập Google lúc đó cũng trả 400 `BR-TK-09`, làm hỏng các ca sau | Chưa test |
| G-03 | Gắn vào tài khoản có sẵn (mật khẩu cũ vẫn dùng) | Một khách `ACTIVE` đăng ký bằng mật khẩu với email = Gmail thử, đã xác thực OTP, **chưa** gắn Google (`google_sub` NULL); có thú cưng hoặc lịch hẹn để nhận ra tài khoản | 1) Đăng nhập Google bằng Gmail thử ở `/auth/login`. 2) Kiểm trang hồ sơ / thú cưng. 3) Đăng xuất, đăng nhập lại bằng email + mật khẩu cũ | Bước 1 vào thẳng, **không** hộp thoại, đúng tài khoản cũ (cùng `accounts.id`, vẫn thấy thú cưng, lịch sử); `google_sub` được gắn. Bước 3 mật khẩu cũ **vẫn** đăng nhập được | Chưa test |
| G-04 | `PENDING` có sẵn của người khác bị thay | Đăng ký bằng mật khẩu (ví dụ `Attacker123`) với email = Gmail thử, **không** nhập OTP (tài khoản `PENDING`, còn hạn) | 1) Đăng nhập Google bằng Gmail thử. 2) Hộp thoại xác nhận mở (chưa có tài khoản `ACTIVE`): tích đủ hai ô, "Tiếp tục". 3) Đăng xuất. 4) Thử đăng nhập bằng email + mật khẩu `Attacker123` | Bước 2 vào được. DB: dòng `PENDING` cũ, mã OTP và hồ sơ khách online của nó **biến mất**; có **một** dòng `accounts` cho email, `id` **mới** khác `id` cũ, `ACTIVE`, có `google_sub`. Bước 4 ra **401** (mật khẩu của người đăng ký trước không còn vào được). Giao diện không cho bấm "Tiếp tục" khi chưa tích đủ hai ô; nếu BE nhận request thiếu xác nhận thì 400 `BR-TK-02` và dòng `PENDING` còn nguyên (IT `pendingWithoutConsentIs400AndNothingIsDeleted`) | Chưa test |
| G-05 | Email của nhân viên (BR-TK-17) | Một tài khoản **nhân viên** (ví dụ lễ tân) có email = Gmail thử (tạo mới ở trang quản trị, hoặc cấp trên sửa hộ email của nhân viên theo UC08 / BR-TK-16; UC22 chỉ dành cho tài khoản khách) | 1) Đăng nhập Google bằng Gmail thử ở `/auth/login` | 400 `BR-TK-17`, toast "Tài khoản nhân viên đăng nhập bằng email và mật khẩu". **Không** có phiên, **không** chuyển sang màn "bắt đổi mật khẩu" (cờ `must_change_password` của FE không bật vì đây là endpoint public); audit `LOGIN_FAILED` lý do `GOOGLE_STAFF`; `accounts` của nhân viên không đổi, không có `google_sub` | Chưa test |
| G-06 | Email không có thẩm quyền đã có tài khoản (BR-TK-01) | Tài khoản Google dùng địa chỉ **không phải** `@gmail.com` và không thuộc Workspace (token không có `hd`); trong hệ thống đã có tài khoản khách `ACTIVE` (hoặc `PENDING`) cùng email | 1) Đăng nhập Google bằng tài khoản đó | 400 `BR-TK-01` "Email này đã có tài khoản. Vui lòng đăng nhập bằng email và mật khẩu". **Không** gắn `google_sub`, **không** xóa dòng `PENDING`; audit `LOGIN_FAILED` lý do `GOOGLE_EMAIL_NOT_AUTHORITATIVE`. Biến thể: cùng tài khoản Google nhưng email **chưa** có tài khoản thì vẫn tạo mới như G-01. Nếu không có tài khoản Google loại này, ghi `Bỏ qua` (đã có unit và IT `nonAuthoritativeExistingAccountIs400BrTk01`) | Chưa test |

### 3.2 Cấu hình và lỗi

| Mã | Ca | Điều kiện đầu | Bước | Mong đợi | Kết quả |
|---|---|---|---|---|---|
| G-07 | Thiếu client ID (BE trống; FE trống) | (a) BE chạy **không** đặt `GOOGLE_CLIENT_ID` nhưng FE có `VITE_GOOGLE_CLIENT_ID`; (b) FE **không** đặt `VITE_GOOGLE_CLIENT_ID` | (a) Bấm nút Google, chọn tài khoản. (b) Mở `/auth/login` và `/auth/register` | (a) Toast "Đăng nhập Google tạm thời không khả dụng. Vui lòng đăng nhập bằng email và mật khẩu" (503 `SERVICE_UNAVAILABLE`); BE không sập; đăng nhập bằng mật khẩu vẫn dùng bình thường. (b) **Không** có nút Google và **không** có dòng "hoặc" ở cả hai trang | Chưa test |
| G-08 | Token hết hạn trong hộp thoại | Gmail thử chưa có tài khoản (như G-01) | 1) Bấm nút Google, chọn Gmail thử; hộp thoại xác nhận mở. 2) **Chờ hơn 1 giờ** (ID token sống khoảng 1 giờ). 3) Tích hai ô, "Tiếp tục" | 401: hộp thoại **đóng**, `credential` bị xóa khỏi bộ nhớ, toast "Phiên đăng nhập Google đã hết hạn. Vui lòng bấm đăng nhập lại"; không tạo tài khoản. Bấm lại nút Google thì làm được như G-01. Ca này mất khoảng 1 giờ chờ; có thể `Bỏ qua` (đã có Vitest cho nhánh 401 trong hộp thoại) | Chưa test |

### 3.3 Liên kết hồ sơ và đổi email

| Mã | Ca | Điều kiện đầu | Bước | Mong đợi | Kết quả |
|---|---|---|---|---|---|
| G-09 | SĐT trùng hồ sơ tại quầy (BR-TK-19) | Có một hồ sơ khách tại quầy (`COUNTER`) **chưa liên kết** với SĐT X (lấy từ DB, hoặc nhờ lễ tân tạo); Gmail thử chưa có tài khoản | 1) Đăng ký bằng Google như G-01, nhưng ở hộp thoại nhập SĐT X (dạng `0xxxxxxxxx`). 2) "Tiếp tục" | Vào hệ thống rồi được chuyển tới `/account/link` (có hồ sơ tại quầy trùng SĐT cần quyết định); `customers.link_decision_pending = true`. Biến thể: SĐT không trùng hồ sơ nào thì **không** chuyển, cờ vẫn `false`. SĐT sai dạng (ví dụ thiếu số) thì hộp thoại báo "Số điện thoại phải gồm 10 số và bắt đầu bằng 0." tại ô SĐT và không gọi BE; ô SĐT cho phép gõ khoảng trắng hoặc gạch (bị bỏ trước khi gửi) | Chưa test |
| G-10 | Đổi email qua UC22 gỡ liên kết Google | Tài khoản đã gắn Google (sau G-01 hoặc G-03) | 1) Lễ tân (hoặc quản trị) sửa hộ email của khách sang một email khác (UC22, BR-TK-16), hoàn tất xác thực OTP của email mới. 2) Đăng nhập Google lại bằng Gmail thử cũ | Bước 1: `accounts.google_sub` về NULL; audit của UC22 có `googleUnlinked = true`. Bước 2: vì `sub` không còn gắn và email cũ không còn thuộc tài khoản nào, hệ thống coi là **chưa có tài khoản** nên mở hộp thoại xác nhận (BR-TK-02) và tạo tài khoản **mới** (khác `accounts.id`). Nếu tài khoản được dựng theo G-03 (có mật khẩu cũ) thì đăng nhập bằng email mới + mật khẩu cũ vẫn được; nếu dựng theo G-01 (chỉ có Google, không có mật khẩu dùng được) thì khách phải dùng "Quên mật khẩu" với email **mới** để đặt mật khẩu | Chưa test |
| G-11 | Đặt lại mật khẩu (UC04) gỡ liên kết Google (ADR-0152) | (a) Gmail thử đã gắn Google (sau G-01 hoặc G-03; `google_sub` khác NULL). (b) Tài khoản Google dùng địa chỉ **không phải Gmail và không thuộc Workspace** (ví dụ Yahoo, như G-06), đã tạo mới qua Google như G-01 (có `google_sub`), hộp thư đó nhận được mã OTP | 1) Mở `/auth/forgot`, nhập email của tài khoản, nhập mã OTP và mật khẩu mới (UC04). 2) Chạy truy vấn kiểm chung. 3) Đăng xuất, mở `/auth/login`, bấm nút Google chọn lại đúng tài khoản Google đó. 4) Đăng nhập bằng email + mật khẩu mới | Bước 2: `accounts.google_sub` về NULL ở cả (a) và (b). Bước 3 (a): Gmail có thẩm quyền, tài khoản `ACTIVE` chưa gắn nên hệ thống tự **gắn lại** `sub` và vào thẳng, **không** hộp thoại, cùng `accounts.id` (`google_sub` có lại). Bước 3 (b): 400 `BR-TK-01` "Email này đã có tài khoản. Vui lòng đăng nhập bằng email và mật khẩu", không gắn lại, `google_sub` vẫn NULL. Bước 4: vào được ở cả (a) và (b) | Chưa test |

## 4. Lỗi tìm thấy

(điền khi chạy: mã ca, hiện tượng, ảnh chụp hoặc log, đã mở việc nào)

| Mã ca | Hiện tượng | Ghi chú |
|---|---|---|
| | | |
