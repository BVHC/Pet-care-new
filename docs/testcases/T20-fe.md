# Test case FE — T20 (chi nhánh, giờ mở cửa, ngày nghỉ, dịch vụ tại chi nhánh, quota)

> Hợp đồng: `docs/api/branch-v1.md` #1–19 (UC12, UC14, UC33, UC42). Cập nhật 2026-10-11.
> Test tự động tương ứng ghi ở cột cuối mỗi bảng.

## 1. Chuẩn bị

**Chạy với mock (mặc định, không cần BE):** `cd FE && npm run dev`, mở `http://localhost:5173`. Đăng nhập nhanh ở `/admin/login`, mật khẩu chung **`Petcare123`**.

| Tài khoản | Role | Dùng cho |
|---|---|---|
| `chuoi@petcare.vn` | SUPER_MANAGER | Tạo, sửa, kích hoạt chi nhánh; xem cấu hình (không sửa) |
| `manager@store1.vn` | BRANCH_MANAGER, chi nhánh 1 | Giờ mở cửa, ngày nghỉ, dịch vụ, quota của chi nhánh 1 |
| `manager2@store2.vn` | BRANCH_MANAGER, chi nhánh 2 | Đặt giờ cho chi nhánh nháp trước khi kích hoạt |
| `reception@store1.vn` | RECEPTIONIST | Kiểm tra bị chặn |

**Dữ liệu mẫu của mock** (ngày tính theo hôm nay lúc tạo dữ liệu; "thứ Ba tới" là thứ Ba gần nhất sau hôm nay):

| Chi nhánh | Trạng thái | Quản lý | Giờ mở cửa |
|---|---|---|---|
| 1 · PetCare Cầu Giấy | Đang hoạt động, nhận cấp cứu ngoài giờ | `manager@store1.vn` | T2–T7 08:00–12:00 và 13:30–19:00; CN 08:00–12:00 (từ 01/01/2026) |
| 2 · PetCare Hà Đông | Nháp | `manager2@store2.vn` | chưa có |
| 3 · PetCare Long Biên | Nháp | chưa có | chưa có |

Các dữ liệu khác của chi nhánh 1:
- **Lịch hẹn đã đặt:**
  - thứ Ba tới 09:00: Mochi, khách Nguyễn Thu Trang, 0912345678;
  - thứ Ba tới 18:30: Lucky, khách Trần Đức Huy, 0912554321;
  - thứ Năm tới 14:00: Miu, khách Lê Minh Châu, không có SĐT.
- **Đặt chỗ lưu trú:** Bơ, nhận thứ Sáu tới, trả Chủ nhật kế đó.
- **Ngày nghỉ:** 30 ngày trước ("Nghỉ lễ"); 20 ngày tới ("Bảo trì hệ thống điện").
- **Dịch vụ:** bật 5/6; "Chuồng mèo" đang tắt.
- **Quota:**
  - mặc định Khám/Tiêm = 3;
  - Thẩm mỹ chưa đặt, nên mỗi khung nhận 1 lượt;
  - khung 08:00 thứ Ba tới (Khám/Tiêm) đang khóa (quota riêng = 0).

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage. Xóa `petcare-mock-branch` (cùng `petcare-mock-catalog`, `petcare-session` nếu cần), rồi F5.

**Chạy với BE thật** (đặt `VITE_API_MOCK=false` trong `FE/.env`, BE ở `VITE_API_URL`). BE thật hiện có các giới hạn sau:
- **Chưa có API tạo nhân viên (T14).** Tài khoản SUPER_MANAGER, BRANCH_MANAGER (kèm `staff_profiles.branch_id`) phải tự tạo bằng SQL.
- **Chi nhánh đang hoạt động** gặp lỗi 500 `Lỗi hệ thống, vui lòng thử lại sau` ở các thao tác:
  - đổi giờ mở cửa;
  - thêm ngày nghỉ;
  - xem trước danh sách bị ảnh hưởng.

  Lý do: `AppointmentQueryApi`, `BoardingQueryApi`, `PetQueryApi` còn là placeholder. Chi nhánh nháp chạy bình thường. Dịch vụ và quota chạy bình thường.
- **Câu báo lỗi BR-CN-02 của BE** in số thứ theo ISO: thứ Hai là "Thứ 1", Chủ nhật là "Thứ 7". FE tự kiểm trước và báo bằng tên thứ, nên người dùng hiếm khi thấy câu này.

## 2. Trang và đường dẫn

| Trang | Path | Ai mở được |
|---|---|---|
| Danh sách chi nhánh (menu "Chi nhánh", thay mục "Tổ chức" cũ) | `/admin/branches` | SUPER_MANAGER. BRANCH_MANAGER được chuyển thẳng tới chi nhánh của mình |
| Giờ mở cửa | `/admin/branches/{id}/hours` | SUPER_MANAGER xem; BRANCH_MANAGER của chi nhánh sửa |
| Ngày nghỉ | `/admin/branches/{id}/holidays` | như trên |
| Dịch vụ tại chi nhánh | `/admin/branches/{id}/services` | như trên |
| Quota lịch hẹn | `/admin/branches/{id}/quota` | Chỉ BRANCH_MANAGER (SUPER_MANAGER không đọc được quota) |

`/admin/branches/{id}` tự chuyển sang tab `hours`.

## 3. Chi nhánh (UC12)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T20-01 | Quyền truy cập | 1. Đăng nhập `chuoi@petcare.vn`, xem menu trái. 2. Đăng nhập `manager@store1.vn`, bấm menu "Chi nhánh". 3. Đăng nhập `reception@store1.vn`, mở `/admin/branches` | 1: Có mục "Chi nhánh", không còn mục "Tổ chức". 2: Vào thẳng `/admin/branches/1/hours`, có tab "Quota lịch hẹn". 3: Trang 403 | `App.routes.test`, `AdminBranchesPage.test` |
| TC-T20-02 | Tạo chi nhánh | Bấm **Thêm chi nhánh**. Nhập: tên *PetCare Tây Hồ*, địa chỉ, SĐT `0243123456`, vĩ độ `21.06`, kinh độ `105.83`. Bấm **Tạo chi nhánh** | Form đóng, có thông báo xanh. Dòng mới ở trạng thái **Nháp**, cột cấp cứu "—" | `AdminBranchesPage.test`, `branch.mock.test` |
| TC-T20-03 | Kiểm dữ liệu chi nhánh | Ở form thêm, lần lượt thử: SĐT `12345`; tên `PetCare Cầu Giấy`; vĩ độ `100` | Lần lượt báo: *Số điện thoại gồm 10–11 số, bắt đầu bằng 0.*; *Tên chi nhánh đã tồn tại*; *Vĩ độ phải từ -90 đến 90.* | `AdminBranchesPage.test`, `branch.mock.test` |
| TC-T20-04 | Sửa, cờ cấp cứu ngoài giờ (BR-CN-05) | Bấm biểu tượng sửa của *PetCare Hà Đông*, tick "Nhận cấp cứu ngoài giờ", lưu | Cột cấp cứu thành "Có". Trang cấu hình chi nhánh hiện dòng đỏ "Nhận cấp cứu ngoài giờ" | — |
| TC-T20-05 | Kích hoạt khi còn thiếu điều kiện (BR-QT-04, BR-CN-01) | Bấm nút nguồn (Kích hoạt) của *PetCare Long Biên*, xác nhận. Làm lại với *PetCare Hà Đông* | Long Biên: *Không kích hoạt được chi nhánh: chưa có BRANCH_MANAGER, chưa cấu hình giờ mở cửa*. Hà Đông: *…: chưa cấu hình giờ mở cửa*. Cả hai vẫn ở trạng thái Nháp | `AdminBranchesPage.test`, `branch.mock.test` |
| TC-T20-06 | Kích hoạt thành công | 1. Đăng nhập `manager2@store2.vn` → tự vào chi nhánh 2 → **Đặt giờ mở cửa mới**, giữ ngày hiệu lực hôm nay → **Kiểm tra và lưu**. 2. Đăng nhập `chuoi@petcare.vn` → kích hoạt *PetCare Hà Đông* | Bước 2: thông báo *Đã kích hoạt chi nhánh PetCare Hà Đông*; trạng thái **Đang hoạt động**; nút Kích hoạt biến mất | e2e `branch.spec`, `branch.mock.test` |
| TC-T20-07 | Đổi người dùng không lộ dữ liệu của người trước | SUPER_MANAGER mở `/admin/branches` (thấy 3 chi nhánh). Đăng xuất. Đăng nhập `manager@store1.vn`, mở `/admin/branches` | Chuyển thẳng tới chi nhánh 1; không hiện danh sách 3 chi nhánh của người trước | `App.routes.test` |

## 4. Giờ mở cửa (UC14, đăng nhập `manager@store1.vn`)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T20-08 | Xem giờ đang áp dụng | Mở `/admin/branches/1/hours` | Thẻ "Đang áp dụng từ 01/01/2026", Chủ nhật 08:00–12:00. SUPER_MANAGER xem cùng trang: không có nút **Đặt giờ mở cửa mới**, không có tab Quota | `AdminBranchDetailPage.test` |
| TC-T20-09 | Kiểm khoảng giờ (BR-CN-02) | Bấm **Đặt giờ mở cửa mới**. Thứ Ba khoảng 2 đổi giờ mở thành 11:00, bấm **Kiểm tra và lưu**. Sau đó thử giờ mở 12:00 > giờ đóng 08:00. Xóa hết khoảng của một ngày | Lần lượt báo *Thứ Ba: các khoảng giờ mở cửa không được chồng nhau*; *…: giờ mở cửa phải trước giờ đóng cửa*. Ngày 0 khoảng hiện "Nghỉ". Có 2 khoảng thì không còn nút "+ Thêm khoảng" | `AdminBranchDetailPage.test`, `branch-schedule.test` |
| TC-T20-10 | Ngày hiệu lực (BR-CN-04) | Chi nhánh 1: xem ô Ngày hiệu lực; gõ ngày hôm nay rồi lưu. Chi nhánh 2 (DRAFT, `manager2`): xem ô ngày | Chi nhánh 1: mặc định ngày mai; hôm nay báo *Ngày hiệu lực của giờ mở cửa mới sớm nhất là ngày mai.* Chi nhánh 2: mặc định hôm nay | `branch.mock.test` |
| TC-T20-11 | Thu hẹp giờ có lịch bị ảnh hưởng (BR-CN-04, BR-LH-10) | Thứ Ba khoảng 2 đổi giờ đóng thành 18:00 → **Kiểm tra và lưu** → bấm **Lưu và hủy hàng loạt** khi chưa tick → tick "Hủy hàng loạt các lịch trên…" → bấm lại | Hiện danh sách 1 lịch hẹn: Lucky 18:30, Trần Đức Huy, 0912554321. Chưa tick: nhắc *Chọn hủy hàng loạt để lưu…*. Tick: thông báo *…hủy 1 lịch hẹn và 0 đặt chỗ lưu trú*, có thêm thẻ "Áp dụng từ {ngày mai}" | `AdminBranchDetailPage.test`, `branch.mock.test` |
| TC-T20-12 | Ngày trả lưu trú thành ngày nghỉ | Trong trình soạn, xóa khoảng của Chủ nhật (thành "Nghỉ") → **Kiểm tra và lưu** | Danh sách có đặt chỗ lưu trú của Bơ (trả Chủ nhật) | `branch.mock.test` |

## 5. Ngày nghỉ (UC14)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T20-13 | Thêm ngày nghỉ có lịch (BR-LH-10) | Tab **Ngày nghỉ** → **Thêm ngày nghỉ** → ngày = thứ Ba tới, lý do *Sửa điện* → **Kiểm tra và thêm** → tick → **Thêm và hủy hàng loạt** | Hiện Mochi và Lucky. Thông báo *Đã thêm ngày nghỉ … hủy 2 lịch hẹn và 0 đặt chỗ lưu trú*. Có dòng mới "Sửa điện" · "Sắp tới" | `AdminBranchDetailPage.test`, e2e `branch.spec` |
| TC-T20-14 | Ngày không hợp lệ (BR-CN-03) | Gõ tay ngày hôm qua; chọn ngày nghỉ có sẵn (20 ngày tới); chọn hôm nay | Hôm qua: *Không thêm được ngày nghỉ trong quá khứ.* Trùng: *Ngày yyyy-MM-dd đã là ngày nghỉ của chi nhánh*. Hôm nay: thêm được (sự cố đột xuất) | `branch.mock.test` |
| TC-T20-15 | Xóa ngày nghỉ | Chọn "Hiển thị: Tất cả". Xem nút xóa; xóa ngày 20 ngày tới | Chỉ ngày sắp tới có nút xóa; ngày đã qua ghi "Đã qua". Xóa có hỏi xác nhận (lịch đã hủy không khôi phục), xong thì dòng biến mất | `AdminBranchDetailPage.test`, `branch.mock.test` |

## 6. Dịch vụ tại chi nhánh (UC33)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T20-16 | Bật / tắt dịch vụ | Tab **Dịch vụ**: tick ô của "Chuồng mèo" (đang tắt), rồi bỏ tick | Có 3 nhóm: Khám/Tiêm, Thẩm mỹ, Lưu trú (loại chuồng). Thông báo *Đã bật Chuồng mèo tại chi nhánh* / *Đã tắt …*. SUPER_MANAGER: các ô bị khóa | `AdminBranchDetailPage.test`, `branch.mock.test` |
| TC-T20-17 | Dịch vụ ngừng kinh doanh | SUPER_MANAGER ngừng "Cắt tỉa lông" ở `/admin/catalog/services`. Quay lại tab Dịch vụ của chi nhánh 1 | "Cắt tỉa lông" không còn trong danh sách (BR-LH-01) | `branch.mock.test` |

## 7. Quota lịch hẹn (UC42)

| ID | Kiểm tra | Bước | Kết quả mong đợi | Test tự động |
|---|---|---|---|---|
| TC-T20-18 | Quota mặc định (BR-LH-03) | Tab **Quota lịch hẹn**: nhập `2` cho Thẩm mỹ → **Lưu quota Thẩm mỹ**. Sau đó nhập `40000` | Khám/Tiêm "Đang đặt: 3 lượt / khung"; Thẩm mỹ "Chưa đặt, mỗi khung nhận 1 lượt". Lưu 2: *Đã đặt quota mặc định Thẩm mỹ: 2*. 40000: *Quota phải là số nguyên từ 0 đến 32767.* | `AdminBranchDetailPage.test`, `branch.mock.test` |
| TC-T20-19 | Khóa / đặt quota riêng một khung | Chọn ngày thứ Ba tới, nhóm Khám/Tiêm. Bấm khung 09:30, nhập `0`, **Lưu quota riêng**. Bấm lại 09:30 → **Bỏ quota riêng** | Khung 08:00 đã "Khóa" (có viền). Sau khi lưu: 09:30 thành "Khóa", có viền quota riêng, có trong bảng "Quota riêng 30 ngày tới". Bỏ: về "3 lượt" | `AdminBranchDetailPage.test`, `branch.mock.test` |
| TC-T20-20 | Ngày không có khung (BR-LH-02) | Chọn ngày nghỉ 20 ngày tới; chọn một ngày Chủ nhật; đổi nhóm sang Thẩm mỹ | Ngày nghỉ: *Ngày nghỉ của chi nhánh, không có khung đặt lịch.* Chủ nhật: chỉ có khung 08:00–11:30. Thẩm mỹ: mỗi khung "1 lượt" | `AdminBranchDetailPage.test`, `branch-schedule.test` |

## 8. Chạy test tự động

```bash
cd FE
npm run test          # Vitest
npx playwright test   # e2e trên Chrome máy, tự bật `npm run dev` với mock
```

Các file test của T20:
- `src/pages/admin/AdminBranchesPage.test.tsx`
- `src/pages/admin/AdminBranchDetailPage.test.tsx`
- `src/shared/api/mock/branch.mock.test.ts`
- `src/shared/utils/branch-schedule.test.ts`
- `src/app/App.routes.test.tsx`
- `e2e/branch.spec.ts`
