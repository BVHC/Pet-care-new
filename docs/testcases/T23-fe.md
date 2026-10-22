# Test case FE — T23 (hồ sơ khách tại quầy, tra cứu khách & thú, thú cưng, hồ sơ sức khỏe, chuyển chủ)

> Hợp đồng: `docs/api/customer-v1.md` #8–21 (UC22–UC26), hồ sơ sức khỏe `docs/api/visit-v1.md` #21, tài khoản của hồ sơ tại quầy `docs/api/identity-v1.md` #25–27. BR-KH-01…08, 10; BR-TK-01, 05–07, 16, 19. Cập nhật 2026-10-16.
> Cột cuối ghi test tự động tương ứng; "(test sau)" là bước chỉ kiểm tay.

## 1. Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`, mở `http://localhost:5173`. Mật khẩu chung **`Petcare123`**. Nhân viên đăng nhập ở `/admin/login`, khách ở `/auth/login`.

| Tài khoản | Role | Dùng cho |
|---|---|---|
| `reception@store1.vn` | RECEPTIONIST | UC22, UC23, UC24 phía lễ tân, UC26 — `/staff/reception`, nút **Khách hàng** |
| `khach@petcare.vn` | CUSTOMER | UC24 phía khách, UC25 — `/pets` |
| `khachmoi@petcare.vn` | CUSTOMER, hồ sơ chờ quyết định liên kết | BR-TK-19 |
| `doctor@store1.vn`, `caretaker@store1.vn` | VET, CARETAKER | UC23 chỉ xem — `/admin/customers` (mục 9) |

**Dữ liệu mẫu của mock:**

| Hồ sơ | Nguồn | SĐT | Email | Thú cưng |
|---|---|---|---|---|
| #1 Nguyễn Thu Trang | Online (`khach@`) | 0912345678 | `khach@petcare.vn` | Mochi (mèo, có bệnh án → khóa loài), Bim (chó, có mũi tiêm Dại), Tôm (khác, xóa được) |
| #2 Lê Hoàng Long | Online (`khachmoi@`, chờ liên kết) | 0905111222 | `khachmoi@petcare.vn` | — |
| #3 Lê Hoàng Long | Tại quầy | 0905111222 | `long.lh@gmail.com` | Lu (chó, đang có lượt khám mở → không đánh dấu mất / chuyển chủ được) |
| #4 Lê Thị Hoa | Tại quầy | 0905111222 | — | Miu (mèo, xóa được) |

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-customer` và `petcare-mock-pets`, rồi F5.

**Chạy với BE thật:** chưa được — module customer mới có #1–7 (hồ sơ của tôi, sổ địa chỉ), chưa có #8–21; visit chưa có `GET /pets/{petId}/health-record`.

## 2. Trang

| Trang | Path | Ai mở được |
|---|---|---|
| Quầy lễ tân → Khách hàng | `/staff/reception`, nút **Khách hàng** ở đầu trang (màn hẹp: tab **Khách hàng**) | RECEPTIONIST |
| Thú cưng của tôi | `/pets` | CUSTOMER không còn cờ chờ liên kết |
| Tra cứu khách & thú (chỉ xem) | `/admin/customers` | VET, CARETAKER |

## 3. Tra cứu khách & thú (UC23, BR-KH-10)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 3.1 | Chế độ SĐT, gõ `0905` | Nhắc "Nhập đủ 10 số điện thoại để tra cứu.", chưa gọi API | `CustomerLookup.test` |
| 3.2 | Gõ `0905111222` | 3 hồ sơ: hai "Lê Hoàng Long" (online — `kh***@petcare.vn`; tại quầy — `lo***@gmail.com`, thú Lu) và "Lê Thị Hoa" (Miu) | `CustomerLookup.test`, `customer-pet.mock.test` |
| 3.3 | Chế độ Họ tên, gõ `hoang long` (không dấu) | Hai hồ sơ Lê Hoàng Long | `customer-pet.mock.test` |
| 3.4 | Chế độ Tên thú, gõ `mochi` | Hồ sơ Nguyễn Thu Trang | `customer-pet.mock.test` |
| 3.5 | Chế độ Email, gõ `long.lh@gmail.com` | Hồ sơ #3 | (test sau) |
| 3.6 | Chế độ SĐT, gõ `0999999999` | "Không tìm thấy hồ sơ phù hợp." kèm nút **Tạo hồ sơ tại quầy** | (test sau) |

## 4. Hồ sơ khách tại quầy (UC22)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 4.1 | **Tạo hồ sơ mới**, bỏ trống SĐT, bấm Tạo hồ sơ | Lỗi dưới ô SĐT, không gọi API (BR-KH-01) | `useCustomerPet.test` |
| 4.2 | Tạo "Trần Văn Nam", SĐT `0905111222` | Tạo được; màn kết quả cảnh báo SĐT đã có ở 3 hồ sơ, mỗi hồ sơ có nút **Mở** (BR-KH-10) | `customer-pet.mock.test` |
| 4.3 | Bấm **Mở hồ sơ vừa tạo** | Hộp hồ sơ mới, tab Thú cưng trống | (test sau) |
| 4.4 | Hồ sơ #3 → Thông tin khách → Sửa → xóa SĐT → Lưu | Lỗi "Hồ sơ tại quầy bắt buộc có số điện thoại" (BR-KH-01) | `useCustomerPet.test`, `customer-pet.mock.test` |
| 4.5 | Hồ sơ #1 (có tài khoản) → Sửa | Ô Email bị khóa, ghi chú email theo tài khoản; BE từ chối nếu cố gửi email (BR-TK-16) | `customer-pet.mock.test` |
| 4.6 | Hồ sơ #1 → đổi SĐT thành `0905111222` → Lưu | Lưu được, cảnh báo SĐT trùng với 3 hồ sơ | `customer-pet.mock.test` |

## 5. Thú cưng phía lễ tân (UC24)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 5.1 | Hồ sơ #4 → **Thêm thú cưng** "Miu", Mèo | Thêm được, có thông báo cảnh báo trùng tên cùng loài (BR-KH-02) | `customer-pet.mock.test` |
| 5.2 | Thêm thú với ngày sinh ở tương lai | Lỗi dưới ô Ngày sinh | `useCustomerPet.test`, `customer-pet.mock.test` |
| 5.3 | Hồ sơ #1 → Sửa Mochi | Ô Loài bị khóa kèm ghi chú BR-KH-03; BE từ chối đổi loài | `customer-pet.mock.test` |
| 5.4 | Hồ sơ #4 → Xóa Miu → **Xóa** | Miu biến mất (BR-KH-06) | `CustomerLookup.test` |
| 5.5 | Hồ sơ #1 → Mochi | Không có nút Xóa (đã có giao dịch) | `customer-pet.mock.test` (BE trả BR-KH-06) |
| 5.6 | Hồ sơ #3 → Lu → **Đã mất** → tích xác nhận → Tiếp tục → Xác nhận đã mất | Báo "Thú đang có lượt khám chưa kết thúc" (BR-KH-05) | `CustomerLookup.test` |
| 5.7 | Thú khác → Đã mất đủ 2 bước | Thú hiện "Đã mất ngày …, chỉ đọc", ẩn mọi nút; đánh dấu lại → 409, sửa → BR-KH-05 | `customer-pet.mock.test` |

## 6. Chuyển chủ (UC26, BR-KH-08)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 6.1 | Hồ sơ #1 → Bim → **Chuyển chủ** → gõ `0905111222` → chọn Lê Thị Hoa → không tích xác nhận → bấm chuyển | Báo cần xác nhận chủ cũ | `useCustomerPet.test`, `customer-pet.mock.test` |
| 6.2 | Tích "Chủ cũ có mặt hoặc đã đồng ý chuyển" → chuyển | Bim sang hồ sơ #4; `khach@` không còn thấy Bim ở `/pets`; lịch sử tiêm đi theo Bim | `customer-pet.mock.test` |
| 6.3 | Hồ sơ #3 → Lu → Chuyển chủ đủ bước | Báo lỗi BR-KH-08 (đang có lượt khám) | `customer-pet.mock.test` |

## 7. Khách: thú cưng của tôi & hồ sơ sức khỏe (UC24, UC25)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 7.1 | `khach@` → `/pets` | Mochi, Bim, Tôm | `PetsPage.test` |
| 7.2 | Thêm thú "mochi", Mèo | Thêm được, thông báo cảnh báo trùng tên (BR-KH-02) | `customer-pet.mock.test` |
| 7.3 | Mochi → tab **Sức khỏe** | Sắp tới: tái khám 24/10/2026; 2 lượt khám có chẩn đoán, đơn thuốc ("Dầu gội Medicated" ghi mua ngoài), bản bổ sung "Kết quả soi da"; **không** có "Chủ hay quên…" và "Cân nhắc đổi thức ăn…" (nội bộ, BR-KH-07) | `PetsPage.test`, `customer-pet.mock.test` |
| 7.4 | Bim → Sức khỏe | Mũi Dại mũi 1, tái chủng 01/08/2027; một lượt Thẩm mỹ | `useCustomerPet.test` |
| 7.5 | Bim → Cân nặng nhập `0` → Ghi | Lỗi "Cân nặng phải lớn hơn 0" (BR-KH-04) | `PetsPage.test` |
| 7.6 | Nhập `7,2` → Ghi | Dòng "7.2 kg · Chủ tự khai" đứng đầu; tab Hồ sơ: cân nặng hiện tại 7.2 kg | `PetsPage.test`, `customer-pet.mock.test` |
| 7.7 | Tôm → Xóa → Xóa | Tôm biến mất | `customer-pet.mock.test` |
| 7.8 | `khachmoi@` → `/pets` | Bị đưa sang màn liên kết hồ sơ (`RequireLinkDecision`); API trả 400 BR-TK-19 | `customer-pet.mock.test` |

## 8. Tài khoản của hồ sơ khách tại quầy (UC22, identity-v1 #25–27)

Mọi bước bắt đầu bằng tích **"Đã đối chiếu CCCD của khách với họ tên hồ sơ"** (hệ thống không lưu CCCD, BR-TK-16). Mã OTP của mock luôn là `123456`.

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 8.1 | Hồ sơ #1 → Thông tin khách → **Sửa email tài khoản** → bỏ trống tích CCCD → Gửi mã | Báo "Cần đối chiếu CCCD trước khi sửa email" | `CustomerLookup.test` |
| 8.2 | Tích CCCD, email mới `trang.moi@gmail.com` → Gửi mã → nhập `123456` → Xác nhận | Email hồ sơ đổi thành `trang.moi@gmail.com`; đăng nhập khách bằng email mới được, email cũ 401 | `CustomerLookup.test`, `customer-pet.mock.test` |
| 8.3 | Gửi mã lần hai ngay sau đó | Báo chờ 60 giây (BR-TK-07) | `customer-pet.mock.test` |
| 8.4 | Nhập mã sai | Báo mã không đúng (BR-TK-05); sai 5 lần thì mã bị hủy (BR-TK-06); email cũ giữ nguyên | `customer-pet.mock.test` (BR-TK-05) |
| 8.5 | Email mới là email của tài khoản khác (vd `reception@store1.vn`) | Báo BR-TK-01 | `customer-pet.mock.test` |
| 8.6 | Hồ sơ #4 (tại quầy, chưa gắn tài khoản) → **Liên kết tài khoản online** → tích CCCD → `khachmoi@petcare.vn` → Liên kết | Hồ sơ #4 "Tài khoản online: Đã gắn", email theo tài khoản; tra SĐT 0905111222 còn 2 hồ sơ (#2 online bị xóa); `khachmoi@` vào `/pets` thấy Miu, không còn bị hỏi liên kết | `customer-pet.mock.test` |
| 8.7 | Liên kết hồ sơ #3 với `khach@petcare.vn` | Báo BR-TK-19 (hồ sơ online của tài khoản đã có thú cưng) | `customer-pet.mock.test` |
| 8.8 | Liên kết với email chưa có tài khoản | Báo không tìm thấy tài khoản | `customer-pet.mock.test` |

## 9. Bác sĩ, nhân viên chăm sóc tra cứu (UC23, chỉ xem)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 9.1 | `doctor@store1.vn` → menu **Tra cứu khách & thú** (`/admin/customers`) | Trang tra cứu, không có nút tạo hồ sơ | `AdminCustomersPage.test`, `App.routes.test`, `staff-menu.test` |
| 9.2 | Tra `0912345678` → Mở hồ sơ | Chỉ có nút **Sức khỏe** ở mỗi thú; không có Sửa, Chuyển chủ, Đã mất, Xóa, Thêm thú; tab Thông tin khách không có nút sửa | `AdminCustomersPage.test` |
| 9.3 | Mochi → **Sức khỏe** | Ngăn kéo mở thẳng tab Sức khỏe; thấy "Ghi chú nội bộ: Chủ hay quên…" và bản bổ sung (nội bộ); không có ô tự khai cân nặng | `AdminCustomersPage.test` |
| 9.4 | `reception@store1.vn` mở `/admin/customers` | 403 — lễ tân làm ở bàn Lễ tân | `App.routes.test` |
| 9.5 | `caretaker@store1.vn` | Giống 9.1–9.3 | `staff-menu.test` |

## 10. Chưa làm, giới hạn

- Mock chưa gửi thông báo tới email cũ và email mới khi sửa email (BR-TK-16), chưa ghi audit khi sửa email / liên kết — việc của BE (identity #25–27).
- Tiếp nhận khách vãng lai (`CheckInDialog`, UC44) vẫn tra khách trong `clinic-db.ts` cũ: hồ sơ tạo ở T23 chưa hiện ở đó.
- Ảnh thú cưng chỉ nhận đường dẫn (customer-v1 Q1).
- BR-KH-05 / BR-KH-08 trên mock chỉ mô phỏng bằng thú "đang có lượt khám mở"; hủy lịch hẹn, đặt chỗ, Care Task khi đánh dấu mất / chuyển chủ là việc của BE.
