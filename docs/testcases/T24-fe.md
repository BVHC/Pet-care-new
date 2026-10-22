# Test case FE — T24 (kho: nhà cung cấp, phiếu nhập, tồn theo lô / hạn dùng, điều chỉnh tồn, tồn tối thiểu)

> Hợp đồng: `docs/api/inventory-v1.md` #1–17 (UC73–UC76). BR-KO-01…07, BR-SP-05; 03 §9 Phiếu nhập kho #1–#4. Cập nhật 2026-10-16.
> Cột cuối ghi test tự động tương ứng; "(test sau)" là bước chỉ kiểm tay.

## 1. Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`, đăng nhập ở `/admin/login`, mật khẩu chung **`Petcare123`**, mở menu **Kho hàng** (`/admin/warehouse`).

| Tài khoản | Role | Thấy gì |
|---|---|---|
| `manager@store1.vn` | BRANCH_MANAGER chi nhánh 1 | 4 tab: Tồn kho, Phiếu nhập, Điều chỉnh tồn, Nhà cung cấp |
| `manager2@store2.vn` | BRANCH_MANAGER chi nhánh 2 | 4 tab, chỉ dữ liệu chi nhánh 2 (Royal Canin 7 túi) |
| `reception@store1.vn` | RECEPTIONIST | Chỉ tab Tồn kho, không đặt được tồn tối thiểu (UC75 chỉ xem) |
| `doctor@store1.vn`, `chuoi@petcare.vn` | VET, SUPER_MANAGER | 403 (UC73–76 chỉ A05, A06; hợp đồng không có tham số chi nhánh cho cấp chuỗi) |

**Dữ liệu mẫu của mock** (ngày tính từ hôm nay; chi nhánh 1):

| Sản phẩm | Lô | Số lượng | Tồn tối thiểu | Ghi chú |
|---|---|---|---|---|
| Hạt Royal Canin Mini Adult 2kg | mặc định | 12 túi | 5 | không quản lý hạn dùng |
| Pate Whiskas 85g | mặc định | 3 gói | 20 | dưới tồn tối thiểu |
| Amoxicillin 250mg | AMX2405 (+20 ngày) / AMX2501 (+400) | 200 / 500 viên | 100 | có lô sắp hết hạn trong 30 ngày |
| Vaccine Dại Rabisin | RB2401 (+260) / RB2309 (đã hết hạn 16 ngày) | 15 / 2 liều | 10 | khả dụng 15 / tổng 17 |
| Vaccine 5 bệnh Vanguard | VG2402 (+160) | 4 liều | 5 | dưới tồn tối thiểu |

| Phiếu | Trạng thái | Nội dung |
|---|---|---|
| PN-…-0001 | Đã nhập kho | NCC Hanvet: Amoxicillin AMX2501 × 500, Rabisin RB2401 × 18 (đã dùng 3 → không hủy được) |
| PN-…-0002 | Nháp | NCC Royal Canin: Royal Canin × 10, Vanguard × 10 **chưa có số lô / hạn** |
| PN-…-0003 | Đã hủy | NCC Hanvet: Vitamin × 20 |

Nhà cung cấp: Royal Canin, Hanvet (đang hợp tác, có phiếu) · Pet Mart (ngừng hợp tác) · Virbac (chưa có phiếu).

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-inventory`, rồi F5.

**Chạy với BE thật:** chưa được — BE T18 chưa có endpoint kho (chỉ có `StockQueryApiPlaceholder`).

## 2. Trang

| Trang | Path | Ai mở được |
|---|---|---|
| Kho hàng | `/admin/warehouse/{stock, receipts, adjustments, suppliers}` | BRANCH_MANAGER (4 tab), RECEPTIONIST (tab `stock`; tab khác chuyển về `stock`) |
| Mua hàng (cũ) | `/admin/purchasing` → `/admin/warehouse/receipts` | ẩn khỏi menu, gỡ ở T57 |
| Vaccine (cũ) | `/admin/vaccines` → `/admin/warehouse/stock?productType=VACCINE` | ẩn khỏi menu, gỡ ở T57 |

## 3. Tồn kho (UC75, BR-KO-01, 07)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 3.1 | Lễ tân mở `/admin/warehouse/receipts` | Chuyển về tab Tồn kho; chỉ có 1 tab; không có nút tồn tối thiểu | `AdminWarehousePage.test` |
| 3.2 | Xem dòng Rabisin | "15 / 17 liều", cột Đã hết hạn 2, huy hiệu "Có lô hết hạn" | `AdminWarehousePage.test`, `inventory.mock.test` |
| 3.3 | Tích "Chỉ hàng dưới tồn tối thiểu" | Còn Pate Whiskas và Vanguard | `AdminWarehousePage.test`, `inventory.mock.test` |
| 3.4 | Tích "Có lô hết hạn trong 30 ngày" | Còn Amoxicillin | `inventory.mock.test` |
| 3.5 | Rabisin → Xem lô | RB2309 (đã hết hạn) trước, RB2401 sau — thứ tự hạn dùng (FEFO) | `AdminWarehousePage.test`, `inventory.mock.test` |
| 3.6 | Quản lý → Pate → Tồn tối thiểu = 2 → Lưu | Thông báo đã đặt; Pate hết cảnh báo dưới ngưỡng | `AdminWarehousePage.test` |
| 3.7 | Nhập -1 hoặc 1,5 | "Tồn tối thiểu phải là số nguyên ≥ 0" | `inventory.mock.test` (BE) |
| 3.8 | `/admin/vaccines` | Mở tab Tồn kho lọc loại Vaccine | (test sau) |

## 4. Nhà cung cấp (UC73, BR-KO-02)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 4.1 | Thêm "NCC mới", SĐT `12345` | Lỗi SĐT (bắt đầu bằng 0, 10–11 số) | `inventory.mock.test` |
| 4.2 | Thêm với SĐT `0987654321` | Thêm được, "Đang hợp tác" | `inventory.mock.test` |
| 4.3 | Xóa Royal Canin | "Nhà cung cấp đã có phiếu nhập, chỉ chuyển ngừng hợp tác được" | `AdminWarehousePage.test`, `inventory.mock.test` |
| 4.4 | Xóa Virbac | Xóa được | `inventory.mock.test` |
| 4.5 | Hanvet → Ngừng hợp tác | Trạng thái "Ngừng hợp tác", nút đổi thành "Hợp tác lại"; không còn trong ô chọn nhà cung cấp của phiếu nhập mới | `AdminWarehousePage.test` |

## 5. Phiếu nhập kho (UC74, 03 §9)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 5.1 | Mở PN-…-0002 → Xác nhận nhập kho | "Vaccine 5 bệnh Vanguard: sản phẩm quản lý hạn dùng phải có số lô và hạn dùng" (BR-KO-03), phiếu vẫn nháp | `AdminWarehousePage.test`, `inventory.mock.test` |
| 5.2 | Sửa → dòng Vanguard nhập lô `VG2402`, hạn = hạn lô hiện có → Lưu nháp → Xác nhận | "Đã nhập kho"; Vanguard khả dụng 14, vẫn một lô VG2402 (cùng lô + hạn cộng vào lô cũ); Royal Canin 22 | `AdminWarehousePage.test`, `inventory.mock.test` |
| 5.3 | Tạo phiếu, hạn dùng = ngày nhập → Xác nhận | BR-KO-03 hạn dùng phải sau ngày nhập | `inventory.mock.test` |
| 5.4 | Tạo phiếu với nhà cung cấp ngừng hợp tác | BR-KO-02 (ô chọn đã ẩn NCC ngừng; chỉ gặp khi NCC bị ngừng sau khi lập nháp) | `inventory.mock.test` |
| 5.5 | Mở PN-…-0001 → Hủy phiếu → Xác nhận hủy | "Không hủy được: lô RB2401 còn 15, cần trừ 18. Dùng phiếu điều chỉnh tồn…" + nút **Lập phiếu điều chỉnh** sang tab điều chỉnh (BR-KO-04) | `AdminWarehousePage.test`, `inventory.mock.test` |
| 5.6 | Hủy phiếu nháp | "Đã hủy", tồn không đổi | `inventory.mock.test` |
| 5.7 | Phiếu đã nhập: không có nút Sửa / Xác nhận; phiếu đã hủy: không có thao tác | Đúng FSM #1–#4; API trả 409 nếu cố gọi | `inventory.mock.test` |
| 5.8 | Phiếu hai dòng cùng lô, nhập rồi giảm lô bằng điều chỉnh, hủy | BR-KO-04 (cộng dồn theo lô) | `inventory.mock.test` |
| 5.9 | `manager2@store2.vn` mở phiếu của chi nhánh 1 qua API | 403 `ACCESS_DENIED_SCOPE_MISMATCH` | `inventory.mock.test` |

## 6. Điều chỉnh tồn (UC76, BR-KO-06)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 6.1 | Lý do "Khác", bỏ trống ghi chú → Lưu | "Lý do "Khác" bắt buộc ghi chú" | `AdminWarehousePage.test`, `inventory.mock.test` |
| 6.2 | Chọn Royal Canin | Ô lô tự chọn "Lô mặc định" (A4) | `AdminWarehousePage.test` |
| 6.3 | Rabisin lô RB2401, số điều chỉnh -16 | "Giảm quá tồn của lô" (còn 15) | `inventory.mock.test` (BE), FE chặn trước khi gửi |
| 6.4 | Lý do "Hết hạn", Rabisin lô RB2309, -2 → Lưu | Phiếu DC-…-0002 đầu danh sách; tab Tồn kho: Rabisin đã hết hạn 0 | `AdminWarehousePage.test`, `inventory.mock.test` |
| 6.5 | Xem phiếu DC-…-0001 | Dòng Royal Canin, lô mặc định, -1, còn 12 | (test sau) |
| 6.6 | Lọc từ – đến ngày | Chỉ phiếu lập trong khoảng | `inventory.mock.test` |

## 7. Phân quyền (tổng hợp)

| Tài khoản | `/admin/warehouse` | Ghi |
|---|---|---|
| BRANCH_MANAGER | 4 tab | Mọi thao tác ở chi nhánh mình |
| RECEPTIONIST | Tab Tồn kho | Không |
| VET, CARETAKER, SUPER_MANAGER, ADMIN | 403 | — |

`App.routes.test` (VET 403, `/admin/purchasing` mở tab Phiếu nhập), `staff-menu.test` (menu lễ tân không còn mục "Vaccine").

## 8. Giả định của mock chờ BE-2 (T18) chốt vào `inventory-v1.md`

| # | Giả định |
|---|---|
| M1 | `GET /inventory` chỉ liệt kê sản phẩm đã có dòng tồn ở chi nhánh |
| M2 | `GET /inventory/{productId}`: sản phẩm có trong danh mục nhưng chưa có dòng tồn → trả tồn 0 (không 404) |
| M3 | `PUT …/min-quantity` tạo dòng tồn nếu chưa có |
| M4 | Lô hết hạn khi hạn dùng trước hôm nay (giờ Việt Nam) |
| M5 | `/lots` trả mọi lô kể cả số lượng 0, hạn dùng tăng dần, lô mặc định cuối |
| M6 | Lưu nháp kiểm số lượng > 0 và giá nhập ≥ 0 (ràng buộc cột `stock_receipt_lines`); số lô, hạn dùng, có ít nhất một dòng kiểm khi xác nhận; nhà cung cấp ngừng hợp tác bị từ chối cả khi lưu nháp |
| M7 | `expiringWithinDays=N`: có lô chưa hết hạn, còn hàng, hạn ≤ hôm nay + N |
| M8 | Mã phiếu `PN-yyMMdd-nnnn`, `DC-yyMMdd-nnnn` (hợp đồng chỉ ghi "dãy số liên tục") |

## 9. Chưa làm, giới hạn

- Mock chưa ghi `stock_movements` / audit khi xác nhận, hủy phiếu và điều chỉnh (BR-QT-15) — việc của BE.
- Người lập / xác nhận phiếu chỉ có id (hợp đồng không trả tên), nên giao diện chưa hiện tên.
- Bộ lọc "sắp hết hạn" dùng 30 ngày cố định ở FE: tham số [CFG] `stock.expiry_warning_days` chỉ ADMIN đọc được.
- Chuyển kho, kiểm kê, truy vết lô (UC77–79) là tầng 3.
