# T46 (FE): Kê đơn thuốc (mua ngoài) và trang công khai: dịch vụ, chi nhánh, bác sĩ, sản phẩm, bài viết (UC48, UC15–17)

> Phạm vi: **A. Kê đơn thuốc** (visit-v1 #16–17: types, `visit.api.ts`, hooks, mock `visit-clinical.mock.ts` + `sales.mock.ts`, `PrescriptionSection`) và **B. Trang công khai** (`/api/public/*`: types, api, hooks, mock `public.mock.ts` + `content.mock.ts`, bảy trang). **Hẹn tái khám (BR-KB-06) đã làm ở T37** (trường `followUpDate` của PUT bệnh án #13), T46 chỉ rà lại (A.7).
> Hợp đồng: `docs/api/visit-v1.md` (#16–17, `AddPrescriptionRequest`, `PrescriptionItem`, A5), `catalog-v1.md` (#19–22), `branch-v1.md` (public), `identity-v1.md` (`/public/vets`), `content-v1.md` (public). Rule: BR-KB-03, 06, BR-SP-01, BR-KO-01, BR-BH-02, BR-CK-01, 02, 03, BR-TK-20, BR-BV-01, 03, 04. ADR-0005 (public ẩn danh), ADR-0071 (bài viết, lọc HTML ở BE).
> **BE:** 11 endpoint public **đã có** (catalog, branch, identity, content). **Kê đơn #16–17 chưa có** (T38: `PrescriptionApi` mới là interface, `medical-record.prescriptions` luôn `[]` tới lúc đó), nên phần A chỉ theo hợp đồng + BR-KB-03; chỗ hợp đồng im lặng là ASSUMPTION (A.3). **Cả hai phần chưa chạy thử với BE thật** (`VITE_API_MOCK=false`).
> Kế hoạch và quyết định thiết kế: `docs/superpowers/plans/2026-10-26-t46-fe-prescription-public.md` (thư mục gitignore, chỉ ở máy dev).

## A. Kê đơn thuốc

### A.1 Tài khoản và dữ liệu mẫu (mật khẩu `Petcare123`)

- `doctor@store1.vn` (VET, chi nhánh 1). Lượt đang khám: **2002** (Lu). Lượt đã hoàn tất, bệnh án khóa: **61** (Mochi), có sẵn dòng đơn Amoxicillin ×10 gắn `orderLineId 22`.
- Thuốc kê đơn duy nhất trong seed: **productId 3 "Amoxicillin 250mg"** (DRUG, `isPrescription`), tồn chi nhánh 1 = **700**. Muốn thiếu tồn thì kê **số lượng > 700** (800 trong các ca bên dưới). Không đổi seed catalog / kho / nhân viên.
- Đưa dữ liệu về ban đầu: xóa localStorage `petcare-mock-clinical`, `petcare-mock-visit`, `petcare-mock-sales`, `petcare-mock-inventory`, `petcare-mock-catalog`.

### A.2 Endpoint mock (test: `visit-clinical.mock.test.ts`, 88 ca; `sales.mock.ts`)

| # | Route mock | Quyền | Rule đã mô phỏng |
|---|---|---|---|
| 16 | `POST /api/visits/:visitId/prescriptions` → 201 `PrescriptionItem` | VET được gán lượt `IN_PROGRESS` | Đủ tồn: dòng đơn + 1 dòng Order `DRUG` (`isExternalPurchase=false`, `orderLineId≠null`). Thiếu tồn không `shortageResolution`: 400 `BR-KB-03` kèm số tồn, **không ghi gì**. `REDUCE_TO_AVAILABLE`: `quantity` = tồn, có dòng Order. `EXTERNAL_PURCHASE`: giữ `quantity`, `isExternalPurchase=true`, `externalReason=OUT_OF_STOCK_AT_PRESCRIBE`, `orderLineId=null`, **không** dòng Order. Bất biến `isExternalPurchase === (orderLineId == null)`. Kê **không trừ kho** (kho trừ khi `PAID`, BR-BH-04) |
| 17 | `DELETE /api/visits/:visitId/prescriptions/:itemId` → 204 | như trên | Xóa dòng đơn kèm dòng Order `DRUG` của nó; dòng mua ngoài xóa không đụng Order. Dòng `DRUG` không xóa / sửa trực tiếp qua `/orders/{id}/lines` (BR-BH-02, đã có ở `lineDenial`) |

`GET …/medical-record` (#12) và `GET /api/pets/:petId/health-record` (#21) trả đúng `prescriptions` (trước T46 luôn `[]`). Sau hoàn tất lượt (#10): #16 / #17 → 409, bệnh án khóa vẫn liệt kê đơn.

**Thứ tự kiểm (A1, theo mẫu #19):** body / path 400 → vai trò (chỉ VET) 403 → 404 lượt → chi nhánh 403 → lượt thẩm mỹ 404 → người phụ trách 403 → lượt không `IN_PROGRESS` 409 → 404 sản phẩm → 400 `BR-KB-03`. #17 thay hai bước cuối bằng 404 dòng đơn (không thuộc lượt này).

### A.3 ASSUMPTION (hợp đồng im lặng, mock tự chọn)

| # | Nội dung | Vì sao |
|---|---|---|
| A1 | Thứ tự lỗi như A.2 | visit-v1 không nêu thứ tự riêng cho #16–17 |
| A2 | Sản phẩm không phải thuốc kê đơn / ngừng kinh doanh → 400 `BR-KB-03` | hợp đồng ghi 400 nhưng không gắn mã rule; guard nội bộ BE (`VisitOrderService`) dùng BR-KB-03 |
| A3 | `REDUCE_TO_AVAILABLE` khi tồn = 0 → 400 `BR-KB-03` (bảo chọn mua ngoài), vì `quantity > 0` là CHECK của `prescription_items` | không có quy định |
| A4 | Gửi `shortageResolution` khi tồn đã đủ → xử lý như kê thường (có dòng Order) | A5 của hợp đồng chỉ nói "chỉ gửi khi lần trước bị từ chối" |
| A5 | Cùng thuốc kê hai lần → hai dòng riêng | hợp đồng không cấm |
| A6 | Câu báo 400 thiếu tồn do mock tự viết ("… chỉ còn N … cần M … (BR-KB-03)") | hợp đồng chỉ nói "message ghi số tồn"; **FE hiện nguyên văn, không regex số tồn** |

### A.4 Giao diện (`components/staff/doctor/PrescriptionSection.tsx`, nằm trong `MedicalRecordEditor`)

Mục **Đơn thuốc** nằm giữa "Chỉ định trong lượt" và "Bổ sung". VET được gán lượt đang khám: danh sách + form (chọn thuốc, *Số lượng* ≥ 1, *Liều dùng* bắt buộc ≤ 500 ký tự có bộ đếm, nút *Kê thuốc* tắt khi thiếu). Thuốc lấy từ `useProducts({productType:'DRUG', isActive:true, size:100})` rồi **lọc client `isPrescription`** (API không có bộ lọc này; `retailOnly=true` là ngược ý).

Thiếu tồn (400 `BR-KB-03` khi request **chưa** có `shortageResolution`): hiện khung với câu của BE và ba nút *Giảm xuống vừa tồn* / *Mua ngoài, giữ nguyên số lượng* / *Hủy*. Bấm một nút = gửi lại **đúng body vừa gửi** kèm `shortageResolution`; sửa bất kỳ ô nhập nào thì bỏ khung. Lỗi của request đã có `shortageResolution` (vd. giảm khi tồn = 0) hiện như lỗi thường. Dòng đơn có huy hiệu *Tính vào hóa đơn* hoặc *Mua ngoài* (kèm "hết tồn khi kê" / "hết tồn khi thu tiền"); nút *Xóa* chỉ khi được kê. Bệnh án khóa hoặc lượt của người khác: chỉ danh sách (BR-KB-01); chưa có dòng nào và không được kê thì ẩn cả mục.

### A.5 Ca kiểm thử (tài khoản `doctor@store1.vn`, Phòng khám `/staff/doctor`)

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | Lượt 2002: chọn Amoxicillin, SL 10, nhập liều, *Kê thuốc* | Dòng đơn huy hiệu *Tính vào hóa đơn*; "Chỉ định trong lượt" có dòng Thuốc; tồn kho vẫn 700 | `DoctorWorkspacePage` › đủ tồn; mock › đủ tồn |
| 2 | Kê SL 800 | Khung cảnh báo "chỉ còn 700 …, cần 800" + 3 nút; chưa ghi dòng nào | › thiếu tồn (800 > 700) |
| 3 | Ca 2, *Giảm xuống vừa tồn* | Dòng SL 700, tính vào hóa đơn, Chỉ định "700 ×" | › Giảm xuống vừa tồn; mock REDUCE |
| 4 | Ca 2, *Mua ngoài, giữ nguyên số lượng* | Dòng SL 800, huy hiệu *Mua ngoài*, "hết tồn khi kê"; Chỉ định không có thuốc | › Mua ngoài…; mock EXTERNAL |
| 5 | Ca 2, *Hủy* hoặc sửa một ô nhập | Không gửi request; khung biến mất; dữ liệu nhập còn | › Hủy; › it.each 3 ô |
| 6 | Xóa dòng thuốc thường / dòng mua ngoài | Dòng đơn và dòng Order DRUG cùng mất; dòng mua ngoài thì Order không đổi | › xóa dòng đơn / xóa dòng mua ngoài; mock #17 |
| 7 | Để trống thuốc, liều (hoặc chỉ khoảng trắng), hoặc SL 0 | *Kê thuốc* tắt; bộ đếm `n/500` chạy | › nút kê chỉ bật khi… |
| 8 | **Gõ chẩn đoán, KHÔNG lưu**, rồi kê hoặc xóa thuốc | Chẩn đoán còn nguyên; "Có thay đổi chưa lưu"; chưa PUT; *Lưu* ghi đúng bản nháp | › 3 ca "bản nháp bệnh án chưa lưu…" |
| 9 | "Đã khám hôm nay" → Mochi (61) | Chỉ danh sách đơn (Amoxicillin ×10, *Tính vào hóa đơn*); không form, không nút xóa | › bệnh án đã khóa… |
| 10 | Mở lượt đang khám của BS khác | Thấy đơn đã kê, không form / nút xóa; chưa kê gì thì không có mục Đơn thuốc | › lượt đang khám của bác sĩ khác |
| 11 | Lượt vừa hoàn tất ở nơi khác (409) / không phải người phụ trách (403) | Câu báo theo thao tác kê đơn; dữ liệu đã nhập không mất | › lỗi khác của BE / BE từ chối xóa; `useVisits.test` |
| 12 | Kê thuốc rồi *Hoàn tất* | Dòng thuốc vào Order chờ thu; kê / xóa tiếp trả 409; bệnh án khóa vẫn liệt kê đơn | mock › tổng Order…, sau khi hoàn tất (#10) |

### A.6 Đột biến: 33 đột biến, 33 bị bắt

Sửa một chỗ, chạy test liên quan, thấy đỏ, hoàn nguyên (so `cmp` với bản sao). Tiêu biểu: đảo `isExternalPurchase` (9 ca đỏ); bỏ xóa dòng Order ở #17; bỏ kiểm tồn (8 ca); đảo thứ tự lỗi; bỏ `??= []` cho state localStorage cũ; **thêm `useEffect` re-seed form theo `record` làm 3 ca bảo toàn bản nháp đỏ**; REDUCE không giảm SL; EXTERNAL vẫn sinh dòng Order; `recordView` / hồ sơ sức khỏe trả `[]`; bỏ kiểm `isPrescription`; bỏ giới hạn 500 ký tự; kê mà trừ kho; bỏ kiểm vai trò VET; thêm CARETAKER vào `VISIT_ROLES.prescribe`; khung thiếu tồn hiện cả khi request đã có resolution; nút *Giảm* gửi sai enum; *Hủy* vẫn gửi; bỏ invalidate `['sales']`.

### A.7 Hẹn tái khám (đã làm ở T37, rà lại ở T46)

`followUpDate` của PUT bệnh án #13: sau ngày khám, tối đa 180 ngày [CFG], tối đa một ngày mỗi bệnh án, khóa cùng bệnh án (BR-KB-06). Còn test ở `visit-clinical.mock.test.ts`, `DoctorWorkspacePage.test.tsx`, `useExam.test.tsx`. Không đổi.

## B. Trang công khai (UC15, UC16, UC17)

### B.1 Mock `/api/public/*` (không cần token, ADR-0005)

| Endpoint | File mock | Hành vi đáng nhớ |
|---|---|---|
| `GET /public/branches[/{id}]` | `branch.mock.ts` (đã có) | Chỉ chi nhánh `ACTIVE` (seed: chỉ **#1 PetCare Cầu Giấy**); id ≤ 0 → 400 |
| `GET /public/services` | `branch.mock.ts` (đã có) | Không `branchId`: dịch vụ đang kinh doanh bật ở ≥ 1 chi nhánh ACTIVE, `branchIds` chỉ chi nhánh ACTIVE (seed: **5** dịch vụ; "Chuồng mèo" tắt ở chi nhánh 1 nên ẩn); `group=SPA` → 400 `MALFORMED_REQUEST` |
| `GET /public/product-categories`, `/products`, `/products/{id}` | `public.mock.ts` | Đang kinh doanh, **không** thuốc kê đơn (BR-SP-01), có thể gồm vaccine (BE chưa loại); `inStockBranchIds` chỉ chi nhánh ACTIVE còn tồn khả dụng **và không có số lượng** (BR-CK-03); `q` ≤ 100 ký tự (101 → 400 `VALIDATION_FAILED`); `branchId` không ACTIVE → trang rỗng, không 404; `page` / `size` bị kẹp (size 1–100, mặc định 20); id ≤ 0 → 400; thuốc kê đơn / ngừng KD / lạ → cùng một 404 |
| `GET /public/vets` | `public.mock.ts` | VET `ACTIVE` không bị khóa (`is_locked`) ở chi nhánh ACTIVE (BR-TK-20; khóa tạm do đăng nhập sai vẫn hiện, đúng BE); `branchId` không ACTIVE → `[]`; không lộ email / SĐT. Seed: **1 bác sĩ** (BS. Minh Anh, chi nhánh 1) |
| `GET /public/article-categories`, `/articles`, `/articles/{slug}` | `content.mock.ts` (+ `content.seed.ts`) | Chỉ `PUBLISHED`, mới xuất bản trước; chuyên mục ẩn không có trong danh sách nhưng vẫn lọc được bằng `categorySlug` và bài trong đó vẫn đọc được (BR-BV-04); `DRAFT` / `HIDDEN` / slug lạ / slug sai định dạng → cùng một 404 (BR-BV-03). Seed: 10 bài (8 PUBLISHED, 1 DRAFT `meo-cao-sofa-5-cach-ngan-chan`, 1 HIDDEN `cho-so-phao-hoa-4-buoc-binh-tinh`), 4 chuyên mục (`luu-tru-dich-vu` ẩn). Khóa localStorage `petcare-mock-content` |

Dữ liệu đọc chéo từ mock catalog / kho / chi nhánh / nhân viên như BE đọc qua `QueryApi`: tắt sản phẩm, đổi tồn kho, khóa bác sĩ ở màn nhân viên thì trang công khai đổi theo. Đưa về ban đầu: xóa `petcare-mock-content`, `petcare-mock-branch`, `petcare-mock-catalog`, `petcare-mock-inventory`.

### B.2 Trang và file

| Route | File | Ghi chú |
|---|---|---|
| `/services` (mới) | `pages/services/ServicesPage.tsx` | Nhóm Khám & tiêm / Thẩm mỹ / Lưu trú. Giá `servicePriceLabel` ("Từ X đ", lưu trú "X đ / đêm"); ghi chú chi phí phụ thuộc chỉ định (BR-CK-02) cho khám / tiêm / thẩm mỹ; bộ lọc chi nhánh chỉ hiện khi có ≥ 2 chi nhánh |
| `/branches` (mới) | `pages/branches/BranchesPage.tsx`, `opening-hours.ts` | Địa chỉ, SĐT `tel:`, huy hiệu *Cấp cứu 24/7*, bảng giờ 7 ngày ("Nghỉ" khi `ranges` rỗng), link bản đồ, bác sĩ của chi nhánh (một request cho cả trang) |
| `/doctors` (mới) | `pages/doctors/DoctorsPage.tsx`, `initials.ts` | Thẻ bác sĩ, ảnh lỗi / thiếu thì chữ cái đầu, lọc chi nhánh |
| `/shop`, `/shop/:id` | `pages/customer/ShopPage.tsx`, `ProductDetailPage.tsx` | Lọc trên URL `q` / `category` / `branch` / `page`; chỉ **tên** chi nhánh còn hàng; **bỏ** giỏ hàng, nút mua, yêu thích, đánh giá, "đã bán", lọc chó / mèo (BR-CK-03: không đặt mua online) |
| `/news`, `/news/:slug` (mới) | `pages/news/NewsPage.tsx`, `ArticleDetailPage.tsx` | Chip chuyên mục, phân trang. Nội dung bài là HTML đã lọc ở BE: `dangerouslySetInnerHTML` ở **đúng một chỗ** (ADR-0071), FE không thêm thư viện sanitize |
| `/` | `pages/customer/HomePage.tsx`, `components/customer/OurServicesStage.tsx`, `ContactVetCTA.tsx` | Sản phẩm, bác sĩ, tin, dịch vụ, chi nhánh lấy từ API; section không có dữ liệu thì ẩn gọn, không placeholder. Hoạt ảnh GSAP khởi tạo lại theo dữ liệu đã nạp |
| `/about` | `pages/about/AboutPage.tsx` | Đội ngũ bác sĩ và nhóm dịch vụ lấy từ API; `STATS`, `VALUES` giữ tĩnh |

Lớp dữ liệu dùng chung: `shared/types/public-site.ts`, `shared/api/{catalog,identity,branch,content}.api.ts`, `shared/hooks/usePublicSite.ts` (khóa `['public', …]`), `shared/utils/public-format.ts` (`servicePriceLabel`, `productPriceLabel`, `formatPublishedDate` theo giờ Việt Nam tường minh, nhận cả `+07:00` lẫn `Z`, `branchNamesByIds`). Header thêm 3 link Dịch vụ / Chi nhánh / Bác sĩ, footer thêm 3 link.

### B.3 Ca kiểm thử (khách chưa đăng nhập; không cần tài khoản)

Chạy `cd FE && npm run dev`. Ca nào đánh dấu "chỉ tự động" thì ép lỗi / rỗng bằng `vi.spyOn` nên không làm tay được.

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | Mở `/services`, `/branches`, `/doctors`, `/news/<slug>` | Mở được, không về login | `App.routes.test` › opens … |
| 2 | Header "Dịch vụ", "Chi nhánh", "Bác sĩ"; footer "Dịch vụ", "Chi nhánh", "Đội ngũ bác sĩ" | Tới đúng trang | `App.routes.test` › reaches the page… |
| 3 | `/services` | 3 nhóm; "150.000 đ", "Từ 50.000 đ", "200.000 đ / đêm", "Dành cho chó, tối đa 10 kg"; ghi chú bác sĩ chỉ ở khám và thẩm mỹ | `ServicesPage.test` › theo nhóm, giá theo đêm, ghi chú |
| 4 | "Đặt lịch" ở khám / thẩm mỹ; nhóm Lưu trú | Tới `/booking` (khách vãng lai về login); Lưu trú **không** có nút, có link `/branches` (đặt chỗ lưu trú là luồng riêng, UC58) | › "Đặt lịch" dẫn tới /booking |
| 5 | Có ≥ 2 chi nhánh ACTIVE, chọn chip chi nhánh | Chỉ dịch vụ của chi nhánh đó; 1 chi nhánh thì không có bộ lọc | › lọc theo chi nhánh / ẩn bộ lọc |
| 6 | `/branches` | Địa chỉ, SĐT bấm gọi (`tel:`), *Cấp cứu 24/7*, Thứ Hai 08:00 - 12:00 và 13:30 - 19:00; "Xem bản đồ" mở tab mới | `BranchesPage.test` › chi nhánh seed, link bản đồ |
| 7 | Đặt Chủ nhật nghỉ cho chi nhánh (màn giờ làm việc), mở `/branches` | Dòng Chủ nhật ghi "Nghỉ" | › nhiều chi nhánh: cờ cấp cứu, ngày nghỉ |
| 8 | `/doctors` và thẻ bác sĩ trong `/branches` | "BS. Minh Anh", chuyên môn, mô tả, chi nhánh, "MA" thay ảnh; không email / SĐT | `DoctorsPage.test`, `BranchesPage.test` |
| 9 | Lỗi → *Thử lại*; rỗng; đang tải (chỉ tự động) | Thông báo lỗi có nút; câu rỗng có nghĩa; khung chờ không nháy | 3 trang › lỗi tải / chưa có … / đang tải |
| 10 | `/shop` | 5 sản phẩm theo tên, giá + đơn vị, "Còn hàng tại: PetCare Cầu Giấy"; Vitamin "Tạm hết hàng…"; **không số lượng, không giỏ, không nút mua** | `ShopPage.test` 1–2 |
| 11 | Gõ "vitamin" (thử 101+ ký tự); chip "Vaccine"; "Còn hàng tại" Cầu Giấy | Sau ~300 ms URL `?q=vitamin`, 1 thẻ; ô giới hạn 100; `?category=3`, `?branch=1`; chip "Đang lọc" gỡ được | `ShopPage.test` 3–7 |
| 12 | `/shop?petType=dog&category=food&page=2` | Không lỗi, bỏ qua tham số lạ; `page=2` gửi API `page=1` | `ShopPage.test` 8–9 |
| 13 | `/shop/1`, `/shop/4`; `/shop/3` (kê đơn), `/shop/abc`, `/shop/0` | Chi tiết + chi nhánh còn hàng (link `/branches`) + ghi chú bán tại quầy; id 4 ghi hết hàng; ba id sau "Không tìm thấy sản phẩm" + link `/shop`, id sai không gọi API | `ProductDetailPage.test` |
| 14 | `/news`; chip chuyên mục; `?category=luu-tru-dich-vu`; `?page=2` | Bài mới nhất nổi bật; chuyên mục ẩn không có chip nhưng vẫn ra bài; `page=1` gửi BE | `NewsPage.test` |
| 15 | `/news/meo-bo-an-3-ngay-khi-nao-can-cap-cuu` | Tiêu đề, chuyên mục (link lọc), tác giả, ngày VN, ảnh bìa; h2 và danh sách từ HTML; không tràn ngang ở 360 px | `ArticleDetailPage.test` 1–2 |
| 16 | Slug DRAFT, HIDDEN, slug lạ | Cùng "Không tìm thấy bài viết" + link `/news`; không bị đẩy sang đăng nhập | `ArticleDetailPage.test` 5; `App.routes.test` |
| 17 | `/`, cuộn qua stage, sản phẩm, bác sĩ, tin, liên hệ | Dữ liệu thật; thẻ hiện đủ (không kẹt ẩn); stage ≤ 3 bước có ghi chú chỉ định bác sĩ; chip chi nhánh có `tel:` và *Cấp cứu 24/7* | `HomePage.test` 1–8 |
| 18 | `/` khi API lỗi hoặc rỗng (chỉ tự động) | Section dữ liệu ẩn gọn, không placeholder, FAQ vẫn còn | `HomePage.test` 9–12 |
| 19 | `/about` | Bác sĩ thật (chữ cái đầu nếu chưa có ảnh), nhóm dịch vụ + link `/services`; lỗi hoặc rỗng thì ẩn section | `AboutPage.test` |

### B.4 Đột biến

- 14 đột biến cho `content.mock.ts` và dịch vụ công khai (bỏ lọc PUBLISHED, đảo thứ tự xuất bản, lộ chuyên mục ẩn, cho slug DRAFT lọt, bỏ kẹp `size`, dịch vụ không bật ở chi nhánh nào vẫn hiện, `branchIds` lộ chi nhánh DRAFT, dịch vụ ngừng KD vẫn hiện, enum `group` sai không báo lỗi…): 14 bị bắt.
- 6 đột biến cho `public.mock.ts` (lọt thuốc kê đơn: 14 ca đỏ; tồn 0 vẫn "còn hàng": 3; lọc `branchId` bỏ kiểm tồn: 2; `q` 101 ký tự được chấp nhận: 1; bác sĩ bỏ lọc `branchId`: 2; sản phẩm ngừng KD lọt: 12): 6 bị bắt. Hoàn nguyên, `cmp` khớp. (Lần chạy đầu của đợt này vô tình không áp dụng được đột biến vì máy không có Python; đã làm lại bằng node và kiểm "đã áp dụng" trước khi tin kết quả.)

### B.5 ASSUMPTION của phần công khai

1. Vaccine vẫn hiện trong `/public/products` (BR-CK-01 chỉ loại thuốc kê đơn; BE chưa loại).
2. `q` tìm theo tên, không phân biệt hoa thường nhưng **có** phân biệt dấu (như BE `lower(name) LIKE`), không tìm theo SKU.
3. Slug bài sai định dạng ở `/articles/{slug}` → 404 (BE không kiểm định dạng); `categorySlug` lạ → trang rỗng 200.
4. Ảnh bìa bài viết và ảnh sản phẩm của mock là đường dẫn nội bộ `/imgs/…`; BE thật chỉ nhận URL `https`.
5. `<ol start>`, `<h3>` có trong nội dung seed (hợp `Safelist.relaxed()` của BE).
6. `publishedAt` hợp đồng ghi `+07:00`, BE thật trả `Z`: FE định dạng theo giờ Việt Nam tường minh nên đúng cho cả hai.

## C. Còn lại, không làm ở T46

- **UC21** (`/public/pages/{key}`: banner, giới thiệu, chính sách, FAQ) là **T47**: `STATS`, `VALUES`, `FAQS`, khối "Liên hệ" của About (địa chỉ Quận 1 lệch chi nhánh thật), dòng "24/7 / đưa đón" và hotline trong `ContactVetCTA`, và địa chỉ / hotline cứng ở `SiteFooter` vẫn tĩnh.
- **Header (T57):** mega-menu `SHOP_CATEGORIES` ở `SiteHeader.tsx` (dòng ~31–35) vẫn link `/shop?petType=dog|cat`: trang mở được nhưng bỏ qua tham số (hợp đồng không có loài). Header bị đè chữ ở điện thoại (~390 px) là lỗi có sẵn, các trang mới vẫn vào được qua footer.
- **Mã chết còn tham chiếu (gỡ ở T57, không sửa ở đây):** `pages/customer/shop.mock.ts` (RecommendPage), `shared/components/ui/ProductCard.tsx` ("Thêm vào giỏ"), export mồ côi sẵn có trong `home.mock.ts` (`CATEGORIES`, `WHY`, `TESTIMONIALS`, `TRUST_ITEMS`, `STEPS`, `HERO_POPS`), `useCartStore` và các route `/cart`, `/checkout`…. Đã xóa: `pages/customer/news.mock.ts`.
- **Kê đơn trên BE thật** chờ T38: khi có controller cần đối chiếu mock với code BE như đã làm cho tiêm chủng (T37-fe.md A.3b), đặc biệt câu báo 400 thiếu tồn, mã lỗi của "không phải thuốc kê đơn" (A2) và thứ tự lỗi (A1). Hợp đồng không phân biệt được lỗi A2 với thiếu tồn (cùng 400 `BR-KB-03`): FE vẫn hiện khung ba nút, gửi lại sẽ ra lỗi thường; hiếm vì ô chọn đã lọc thuốc kê đơn đang kinh doanh.
- #17 báo 404 dòng đơn **sau** 409 (theo A1), ngược với #20 xóa mũi tiêm.
- Lỗi 5xx / mạng thật trên trang công khai hiện sau ~7 giây (hook thử lại 3 lần: 1 s + 2 s + 4 s); lỗi 4xx hiện ngay.
- `LoadError`, `EmptyNotice`, `BranchFilter` được viết lặp ở vài trang công khai; chưa đưa lên `shared/`.
- Sửa kèm theo: `CommonPageHero.module.css` (`padding: 36px 0 32px` đè `px-5 sm:px-8` làm chữ hero dính mép trái ở About, News, Pets…) đổi thành `padding-block: 36px 32px`.

## D. Kiểm chứng

Chạy từ `FE/` sau mọi thay đổi code:
- `npx tsc --noEmit`: sạch.
- `npm run lint` (`--max-warnings 0`): sạch.
- `npx vitest run`: **103 file, 1441 test xanh**, thoát mã 0 (nền trước T46: 88 file, 1159 test, trong đó 1 ca `App.shell` flaky khi máy tải, qua khi chạy riêng). Riêng T46: 21 file, 470 ca (`public.mock` 55, `content.mock` 24, `usePublicSite` 3, `public-format` 12, `visit-clinical.mock` 88, `useExam` 8, `useVisits` 55, `DoctorWorkspacePage` 60, `mock-api` 24, `ServicesPage` 11, `BranchesPage` 10 + `opening-hours` 4, `DoctorsPage` 8 + `initials` 3, `ShopPage` 14, `ProductDetailPage` 15, `HomePage` 12, `NewsPage` 10, `ArticleDetailPage` 10, `AboutPage` 7, `App.routes` 37; một phần là ca có sẵn từ trước).
- `npm run build`: thành công.
- Lỗi lúc tích hợp: `HomePage.test` nạp GSAP `ScrollTrigger` bật `setInterval` 250 ms; interval sống qua lúc jsdom bị hủy và gọi `requestAnimationFrame` (đã mất) ở file test khác cùng worker, làm vitest thoát mã 1 dù mọi ca xanh ("Unhandled Errors"). Sửa bằng `afterAll(() => ScrollTrigger.killAll())` trong `HomePage.test.tsx`. File test nào khác render component GSAP sau này cần cùng cách dọn.
- Trình duyệt thật (Playwright, Chromium, `npm run dev` với mock), chụp và đo `scrollWidth`:
  - `/shop`, `/shop/1`, `/news`, `/news/meo-bo-an-3-ngay-khi-nao-can-cap-cuu`, `/about`, `/services` ở **1280 và 390 px**: không cuộn ngang, không lỗi console. Đã xem ảnh: `/shop` ở 1280 (thẻ không giỏ, không số lượng, hero có lề trái), bài viết ở 390 (đọc tốt, danh sách và tiêu đề đúng), hero `/services` ở 390 (lề trái 20 px sau khi sửa `CommonPageHero.module.css`). Các ảnh còn lại (`/shop/1`, `/news`, `/about`, `/services` ở 1280, `/shop` ở 390) chỉ kiểm tràn ngang và console, chưa xem ảnh.
  - Bàn Phòng khám (`/staff/doctor`, `doctor@store1.vn`, lượt 2002) ở 1440 px: kê Amoxicillin 800 → khung thiếu tồn nền vàng kèm câu BE và ba nút → *Mua ngoài* → dòng huy hiệu "Mua ngoài" + "hết tồn khi kê"; kê 10 → huy hiệu "Tính vào hóa đơn"; form được làm mới sau khi kê; không lỗi console. Phát hiện và sửa: ô *Số lượng* bị `w-full` của `fieldClass` đè `w-24` nên tràn cả hàng (nay `max-w-24`, đo được 96 px).
  - Trang chủ ở 1440 và 390 px với ba chế độ chuyển động (thường, giảm chuyển động, di động): thẻ hiện đủ khi cuộn, không kẹt ẩn, không lỗi console (agent WS-B3, chưa chụp lại lúc tích hợp).
  - **Header ở 390 px vỡ** (ô tìm đè logo, hàng menu bị cắt): đã đối chiếu với bản `HEAD` của `SiteHeader.tsx`, vỡ y hệt nên **có sẵn từ trước**; ba link mới chỉ thêm vài mảnh chữ chồng trong hàng đã vỡ. Chưa sửa (T57).
  - Chưa xem bằng trình duyệt: `/branches` và `/doctors` sau lần sửa hero (agent WS-B2 đã xem ở 1280 / 360 / 390 trước đó), màn tối (dark), và mọi thứ với BE thật.
