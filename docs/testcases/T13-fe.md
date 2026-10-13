# Test case FE — T13 (hồ sơ cá nhân, sổ địa chỉ, hồ sơ giới thiệu VET, liên kết hồ sơ)

> Hợp đồng: `docs/api/identity-v1.md` #8, 10–14 và `docs/api/customer-v1.md` #1–7 (UC06, UC07; BR-TK-15, 18, 19, 20, BR-KH-01). Cập nhật 2026-10-12.
> Cột cuối mỗi bảng ghi test tự động tương ứng.

## 1. Chuẩn bị

**Chạy với mock (mặc định, không cần BE):**
- `cd FE && npm run dev`, mở `http://localhost:5173`. Mật khẩu chung **`Petcare123`**, mọi mã OTP là **`123456`**.
- Khách đăng nhập ở `/auth/login`.
- Nhân viên đăng nhập nhanh ở `/admin/login`.

| Tài khoản | Role | Dùng cho |
|---|---|---|
| `khach@petcare.vn` | CUSTOMER | Hồ sơ online đã có thú cưng, 2 địa chỉ. Sửa hồ sơ, sổ địa chỉ, thử tự liên kết (bị từ chối) |
| `khachmoi@petcare.vn` | CUSTOMER | Hồ sơ online đang **chờ quyết định liên kết**, 1 địa chỉ. Liên kết / "Không phải tôi" |
| `reception@store1.vn` | RECEPTIONIST | Hồ sơ nhân viên (không có phần giới thiệu) |
| `doctor@store1.vn` | VET | Hồ sơ nhân viên + hồ sơ giới thiệu công khai |

**Dữ liệu mẫu của mock:**

| Hồ sơ khách | Loại | Tài khoản | SĐT | Ghi chú |
|---|---|---|---|---|
| #1 Nguyễn Thu Trang | Online | `khach@petcare.vn` | 0912345678 | Đã có thú cưng; địa chỉ mặc định "12 Trần Thái Tông", thêm "45 Láng Hạ" |
| #2 Lê Hoàng Long | Online | `khachmoi@petcare.vn` | 0905111222 | Chờ quyết định liên kết; 1 địa chỉ "8 Nguyễn Chí Thanh" |
| #3 Lê Hoàng Long | Tại quầy | chưa liên kết | 0905111222 | Email `long.lh@gmail.com` → hiện "Lê\*\*\* H\*\* L\*" |
| #4 Lê Thị Hoa | Tại quầy | chưa liên kết | 0905111222 | Không có email → hiện "Lê\*\*\* T\*\* H\*", phải ra quầy |

Hồ sơ nhân viên mẫu:
- SĐT là `0900000` + id 3 chữ số, ví dụ lễ tân `0900000004`, bác sĩ `0900000005`.
- BS. Minh Anh có chuyên môn "Nội khoa, da liễu thú y" và một mô tả ngắn.

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-customer`, `petcare-mock-identity`, `petcare-session`, rồi F5.

**Chạy với BE thật** (đặt `VITE_API_MOCK=false` trong `FE/.env`). BE thật hiện có các giới hạn sau:
- **Phần nhân viên chạy được:** `GET /me`, `PATCH /me/staff-profile`. Tuy vậy chưa có API tạo nhân viên (T14), nên tài khoản phải tạo bằng SQL.
- **Phần khách chưa chạy được.** Đăng nhập khách trả 500 vì `CustomerQueryApi` còn là placeholder (nợ D010). Liên kết hồ sơ cần `CustomerApi` (nợ D001). Cả hai thuộc T17 (BE-2).
  - Code BE của `/me/customer-profile` và `/me/addresses` đã chạy, nhưng chưa có token khách để gọi.
- **Họ tên che:** mock theo stub trong test của BE: "Lê Hoàng Long" → "Lê\*\*\* H\*\* L\*". Ví dụ trong BR-TK-19 là "Ng\*\*\* V\*\* A", không có `*` cuối. Khi T17 xong cần đối chiếu lại.
- **Ảnh đại diện:** hợp đồng chỉ nhận URL `https://`. Cách tải ảnh lên còn để mở (customer-v1 Q1).

## 2. Trang và đường dẫn

| Trang | Path | Ai mở được |
|---|---|---|
| Hồ sơ cá nhân của khách | `/account` | CUSTOMER |
| Sổ địa chỉ | `/account/addresses` | CUSTOMER |
| Liên kết hồ sơ | `/account/link` | CUSTOMER |
| Hồ sơ nhân viên (VET có thêm hồ sơ giới thiệu) | `/admin/profile` | Mọi nhân viên |
| Lời đề nghị liên kết (thay trang khi còn cờ) | `/pets`, `/booking`, `/review` | CUSTOMER còn cờ chờ liên kết |

Lối vào:
- Nhân viên mở `/account` thì tự chuyển sang `/admin/profile`.
- Ở admin: menu bánh răng trên header → **Hồ sơ cá nhân**.
- Ở bàn làm việc: biểu tượng người ở góc dưới thanh bên.

## 3. Hồ sơ khách (UC06)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T13-01 | Lối vào theo role | 1. Đăng nhập `khach@petcare.vn`, mở `/account`. 2. Đăng nhập nhân viên `doctor@store1.vn`, mở `/account` | 1: Có 3 mục **Hồ sơ cá nhân · Sổ địa chỉ · Liên kết hồ sơ**; form hồ sơ hiện **một lần**. 2: Chuyển sang `/admin/profile` | `AccountPage.test`, `App.routes.test` |
| TC-T13-02 | Xem và sửa hồ sơ (BR-TK-15) | Đổi Họ tên thành *Nguyễn Thu Trang Anh* → **Lưu thay đổi** | Ô Email chỉ đọc, có ghi chú liên hệ lễ tân. Thông báo *Đã cập nhật hồ sơ*. Tên ở thẻ bên trái và trên header đổi theo | `AccountPage.test` |
| TC-T13-03 | Kiểm dữ liệu | Lần lượt: SĐT `0912`; Ảnh đại diện `http://anh.vn/a.png`; xóa trống SĐT rồi lưu | Lỗi hiện ngay dưới ô: *Số điện thoại phải có dạng 0xxxxxxxxx*; *Ảnh đại diện phải là đường dẫn https://*. Hồ sơ online xóa được SĐT (lưu thành công) | `AccountPage.test`, `profile.mock.test` |
| TC-T13-04 | Hồ sơ tại quầy không xóa SĐT (BR-KH-01) | Sau TC-T13-12, ở `/account` xóa trống SĐT → lưu | Nhãn "Hồ sơ tại phòng khám". Báo *Hồ sơ tại quầy bắt buộc có số điện thoại* | `profile.mock.test` |

## 4. Sổ địa chỉ (UC06, BR-TK-18) — `khach@petcare.vn`, `/account/addresses`

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T13-05 | Xem sổ | Mở trang | Bộ đếm **2/5**. "12 Trần Thái Tông" đứng đầu, nhãn **Mặc định**, nút xóa bị khóa (rê chuột: *Đặt địa chỉ khác làm mặc định trước khi xóa*) | `AccountPage.test` |
| TC-T13-06 | Thêm địa chỉ | 1. **Thêm địa chỉ**, chỉ nhập SĐT `0911` → **Lưu địa chỉ**. 2. Nhập đủ: *Trần Minh*, `0911222333`, *1 Kim Mã*, *Hà Nội*, tick "Đặt làm địa chỉ mặc định" → **Lưu địa chỉ** | 1: Lỗi dưới từng ô: *Vui lòng nhập tên người nhận*, *Số điện thoại người nhận phải có dạng 0xxxxxxxxx*, *Vui lòng nhập địa chỉ*, *Vui lòng nhập tỉnh/thành phố*. 2: *Đã thêm địa chỉ*, **3/5**, địa chỉ mới đứng đầu và là mặc định | `AccountPage.test`, `profile.mock.test` |
| TC-T13-07 | Sửa, đặt mặc định, xóa | Sửa "45 Láng Hạ" thành "47 Láng Hạ" → lưu. Bấm **Đặt mặc định** ở một địa chỉ khác. Xóa một địa chỉ không mặc định (xác nhận **Xóa**) | Lần lượt: *Đã cập nhật địa chỉ*; *Đã đặt địa chỉ mặc định*, địa chỉ đó lên đầu; *Đã xóa địa chỉ*, bộ đếm giảm | `AccountPage.test`, `profile.mock.test` |
| TC-T13-08 | Tối đa 5 địa chỉ | Thêm tới khi đủ 5 | **5/5**, nút **Thêm địa chỉ** bị khóa, dòng *Sổ địa chỉ đã đủ 5 địa chỉ…* | `AccountPage.test`, `profile.mock.test` |
| TC-T13-09 | Sổ rỗng | Đặt địa chỉ còn lại làm mặc định rồi xóa lần lượt hết → **Thêm địa chỉ** | Xóa được địa chỉ mặc định cuối cùng. Form thêm có ô "Địa chỉ đầu tiên luôn là địa chỉ mặc định" đã tick và bị khóa | `profile.mock.test` |

## 5. Liên kết hồ sơ (UC07, BR-TK-19)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T13-10 | Nhắc ở mỗi lần đăng nhập, ẩn chức năng | Đăng nhập `khachmoi@petcare.vn` ở `/auth/login`. Sau đó mở `/pets`, `/booking`, `/review`, `/account` | Vào thẳng `/account/link`. Ba trang kia thay bằng **Hoàn tất liên kết hồ sơ** + nút **Đến bước liên kết hồ sơ**. `/account` có khung vàng "Bạn đã từng đến phòng khám Pet Care?" + link **Liên kết hồ sơ ngay** | `App.routes.test`, e2e `profile.spec` |
| TC-T13-11 | Danh sách hồ sơ trùng SĐT | Ở `/account/link` | Khung "Có vẻ bạn đã có hồ sơ tại phòng khám". Hai hồ sơ: **Lê\*\*\* H\*\* L\*** (có email) và **Lê\*\*\* T\*\* H\*** (*Chưa có email, vui lòng ra quầy để liên kết*, nút **Chọn** bị khóa). Có nút **Không phải tôi** | `AccountPage.test`, `profile.mock.test` |
| TC-T13-12 | Liên kết thành công | **Chọn** "Lê\*\*\* H\*\* L\*" → đọc cảnh báo → **Gửi mã xác thực** → nhập `000000` → **Xác nhận liên kết** → nhập `123456` → **Xác nhận liên kết** | Cảnh báo: hồ sơ online cùng *sổ địa chỉ hiện tại (1 địa chỉ) sẽ bị xóa*. Sau khi gửi: *Mã xác thực đã gửi tới lo\*\*\*@gmail.com*, nút gửi lại đếm ngược 60 giây. Mã sai: *OTP không đúng hoặc đã hết hạn*. Mã đúng: *Liên kết hồ sơ thành công*, "Tài khoản đã gắn với hồ sơ tại phòng khám". Hồ sơ cá nhân thành "Hồ sơ tại phòng khám", sổ địa chỉ **0/5**, `/pets` mở bình thường | `AccountPage.test`, `profile.mock.test`, e2e `profile.spec` |
| TC-T13-13 | "Không phải tôi" | Đặt lại dữ liệu mock, đăng nhập `khachmoi@petcare.vn` → **Không phải tôi** → **Xác nhận** | *Đã ghi nhận "Không phải tôi". Bạn tiếp tục dùng hồ sơ online…*. `/pets` mở bình thường. Mục liên kết chuyển sang ô "Số điện thoại trong hồ sơ tại phòng khám"; tìm `0905111222` lại ra 2 hồ sơ, vẫn tự liên kết được | `AccountPage.test`, `profile.mock.test` |
| TC-T13-14 | Tự tìm theo SĐT | Ở ô tìm (sau TC-T13-13): `12345`; rồi `0977000000` | `12345`: *Số điện thoại phải gồm 10–11 chữ số, bắt đầu bằng 0*. `0977000000`: *Không có hồ sơ tại quầy nào chưa liên kết với số điện thoại này.* | `profile.mock.test` |
| TC-T13-15 | Hồ sơ online đã có dữ liệu | Đăng nhập `khach@petcare.vn` → `/account/link` → tìm `0905111222` | *Hồ sơ của bạn đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không thể liên kết với hồ sơ tại quầy. Vui lòng liên hệ quầy để được hỗ trợ* | `AccountPage.test`, `profile.mock.test` |
| TC-T13-16 | Đăng ký mới có SĐT trùng hồ sơ tại quầy | `/auth/register` với SĐT `0905111222` → OTP `123456` → đăng nhập | Sau khi đăng nhập vào thẳng `/account/link` với 2 hồ sơ như TC-T13-11 | `profile.mock.test` |

## 6. Hồ sơ nhân viên (UC06, BR-TK-15, 20)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T13-17 | Xem và sửa | Đăng nhập `reception@store1.vn` → menu bánh răng → **Hồ sơ cá nhân**. Đổi Họ tên thành *Nguyễn Lan Chi* → **Lưu thông tin** | Trang `/admin/profile`: "Lễ tân · PetCare Cầu Giấy", SĐT `0900000004`, Email chỉ đọc. *Đã cập nhật hồ sơ*; header đổi thành "Xin chào, Nguyễn Lan Chi". Không có mục hồ sơ giới thiệu | `StaffProfilePage.test` |
| TC-T13-18 | Kiểm dữ liệu, ảnh đại diện | SĐT `0123`, Ảnh `http://anh.vn/a.png` → lưu. Sau đó Ảnh = một URL `https://…` hợp lệ → lưu | Lỗi dưới ô: *Số điện thoại phải có dạng 0xxxxxxxxx*; *Ảnh đại diện phải là đường dẫn https://*. URL https: ảnh hiện ở vòng tròn đại diện. Để trống ô Ảnh rồi lưu thì bỏ ảnh | `StaffProfilePage.test`, `profile.mock.test` |
| TC-T13-19 | Hồ sơ giới thiệu của bác sĩ | Đăng nhập `doctor@store1.vn` → bàn làm việc → biểu tượng **Hồ sơ cá nhân**. Xóa trống Chuyên môn → **Lưu hồ sơ giới thiệu** | Có mục **Hồ sơ giới thiệu công khai** và thẻ **Xem trước** (tên, chuyên môn, mô tả, chi nhánh). *Đã lưu hồ sơ giới thiệu*, xem trước không còn chuyên môn | `StaffProfilePage.test`, e2e `profile.spec` |
| TC-T13-20 | Mô tả ngắn tối đa 500 ký tự | Dán mô tả 501 ký tự → lưu | Bộ đếm **501/500**, báo *Mô tả ngắn tối đa 500 ký tự* | `StaffProfilePage.test`, `profile.mock.test` |

## 7. Chạy test tự động

```bash
cd FE
npm run test          # Vitest
npx playwright test   # e2e trên Chrome máy, tự bật `npm run dev` với mock
```

Các file test của T13:
- `src/pages/customer/AccountPage.test.tsx`
- `src/pages/admin/StaffProfilePage.test.tsx`
- `src/shared/api/mock/profile.mock.test.ts`
- `src/shared/stores/session.store.test.ts`
- `src/app/App.routes.test.tsx`
- `e2e/profile.spec.ts`
