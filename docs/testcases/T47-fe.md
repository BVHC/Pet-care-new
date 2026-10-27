# T47 (FE): Nội dung trang, chuyên mục & bài viết, feedback, Care Task của lễ tân (UC21, UC81, UC83, UC84, UC87)

> Phạm vi: **A. Bài viết và chuyên mục** (UC81) · **B. Nội dung trang** (UC21: quản trị + trang công khai đọc `/public/pages/{key}`) · **C. Feedback** (UC83 khách, UC84 nhân viên) · **D. Care Task của lễ tân** (UC87). Mỗi phần gồm types, api, hooks, mock, giao diện. UC80, UC82, UC85, UC86 là tầng 3: không làm.
> Hợp đồng: `docs/api/content-v1.md` (#1–13, #18–22; Q1 mở), `docs/api/care-v1.md` (#1–4). Rule: BR-BV-01…04, BR-DG-01…04, BR-CK-01, BR-TB-05, BR-TK-19. ADR-0071 (nội dung công khai, lọc HTML, feedback). SM Care Task #10.
> **BE:** content-v1 (22 endpoint) **đã có** (T32), nhưng cả bốn phần **chưa chạy thử với BE thật**. **Care Task (care-v1 #1–4) BE chưa có** (T39): FE chạy mock; chạy BE thật cần `VITE_API_MOCK=false` + `VITE_API_MOCK_ONLY=/api/care-tasks` (`FE/.env.example`). Chỗ hợp đồng im lặng là ASSUMPTION (từng phần).
> Kế hoạch, quyết định, bàn giao: `docs/superpowers/plans/2026-10-26-t47-fe-content-feedback-caretask.md` và `…-t47-handoff.md` (thư mục gitignore, chỉ ở máy dev).

## 0. Khung chung

Mọi route / menu / quyền đăng ký một lần ở `App.tsx` + `staff-menu.ts` trước khi làm từng phần; mỗi trang giữ một `<h1>` cố định mà test route dựa vào.

| Route | Role | `<h1>` | Menu |
|---|---|---|---|
| `/admin/page-contents` | SUPER_MANAGER | Nội dung trang | Nội dung › Nội dung trang |
| `/admin/articles/:tab?` (`articles`, `categories`) | SUPER_MANAGER | Bài viết | Nội dung › Bài viết |
| `/admin/feedbacks` | SUPER_MANAGER, BRANCH_MANAGER | Góp ý của khách | Nội dung › Góp ý của khách |
| `/admin/care-tasks` | RECEPTIONIST (BRANCH_MANAGER, SUPER_MANAGER, ADMIN: 403) | Việc gọi điện | Nghiệp vụ › Việc gọi điện |
| `/review` (khách, sau `RequireLinkDecision`) | CUSTOMER | Góp ý của tôi | menu người dùng: "Góp ý của tôi" |

ADMIN kỹ thuật không có mục nào (BE: `hasRole('SUPER_MANAGER')`, không có RoleHierarchy). Test quyền: `App.routes.test.tsx` (6 ca mở được + 8 ca 403 tại chỗ), `staff-menu.test.ts`.

**Thứ tự kiểm của mock** (CLAUDE.md "Error precedence"): 401 → 400 body / query (kiểu, Bean Validation) → 403 vai trò → 404 / 403 phạm vi → 400 rule → 409. Mock đọc state localStorage ở MỖI request nên trang công khai đổi theo admin; **mọi mutation admin invalidate `['public']`** (trang công khai có `staleTime` 5 phút, dùng chung `QueryClient` với admin).

**State localStorage cũ phải sống:** `load()` của mock gộp mặc định khi state đã lưu thiếu trường (test: ghi hình dạng cũ rồi gọi route đầu tiên). Khóa: `petcare-mock-content` (bài, chuyên mục), `petcare-mock-pages` (nội dung trang), `petcare-mock-feedback`, `petcare-mock-care` (Care Task **và** thông báo / cài đặt: xóa là reset cả ba).

**Dữ liệu nhân viên mẫu:** thêm 3 bác sĩ (id 21–23: `doctor2@`, `doctor3@`, `doctor4@store1.vn`, ảnh `team2–4.jpg`; BS. Minh Anh #5 cũng được thêm ảnh `team1.jpg`) vào `identity.mock.ts` để trang chủ / giới thiệu / bác sĩ có đủ 4 thẻ; các assertion của T46 ở `HomePage.test`, `AboutPage.test`, `usePublicSite.test` được sửa theo. **Thay đổi này do một agent làm mà chưa được người dùng duyệt**, trái ràng buộc ban đầu ("không đổi seed staff", vì nhiều test đếm theo seed); đã giữ vì cả bộ test xanh và các trang đẹp hơn. Muốn hoàn nguyên: xóa 3 dòng nhân viên, 3 hồ sơ và ảnh `team1.jpg` ở `identity.mock.ts`, rồi sửa lại các assertion của T46 ở `HomePage.test`, `AboutPage.test`, `usePublicSite.test`. Các tài liệu T46 nhắc "1 bác sĩ" đã được sửa.

## A. Bài viết và chuyên mục (UC81)

### A.1 Tài khoản và dữ liệu mẫu (mật khẩu `Petcare123`)

- `chuoi@petcare.vn` (SUPER_MANAGER). Reset: xóa `petcare-mock-content`.
- 4 chuyên mục (1 Kiến thức nuôi thú cưng, 2 Sức khỏe thú y, 3 Chăm sóc hằng ngày, 4 **Lưu trú & dịch vụ (ẩn)**) và 10 bài: 8 PUBLISHED, #6 DRAFT (`meo-cao-sofa-5-cach-ngan-chan`), #8 HIDDEN (`cho-so-phao-hoa-4-buoc-binh-tinh`), #10 thuộc chuyên mục ẩn.

### A.2 Endpoint mock (content-v1 #3–13; test: `content.mock.test.ts`)

| # | Route mock | Rule đã mô phỏng |
|---|---|---|
| 3–6 | `GET/POST /api/article-categories`, `PATCH/DELETE …/:categoryId` | Chỉ SUPER_MANAGER (kể cả đọc). Tên ≤ 100, slug ≤ 120 đúng `^[a-z0-9]+(?:-[a-z0-9]+)*$`; trùng tên / slug → 400 `VALIDATION_FAILED`; `PATCH` chỉ `name`, `isHidden` (slug không đổi, A1); `articleCount` suy ra từ bài; xóa chuyên mục còn bài → 400 `BR-BV-04` "Chuyên mục còn N bài viết, chỉ ẩn được" |
| 7–10 | `GET/POST /api/articles`, `GET/PATCH …/:articleId` | Danh sách `ArticleSummary` sắp `updatedAt DESC`, lọc `status` / `categoryId` / `q` (≤ 100, chỉ tiêu đề), `status` sai → 400 `MALFORMED_REQUEST`, `categoryId` lạ → trang rỗng. Tạo: tiêu đề ≤ 250, slug ≤ 270, ảnh bìa `https` ≤ 500, tóm tắt ≤ 500, tác giả ≤ 100; slug trùng → 400 `BR-BV-01` "Đường dẫn bài viết đã tồn tại"; **nội dung qua bộ lọc HTML, trống sau lọc → 400 `BR-BV-01`**; `categoryId` không tồn tại → 404. `PATCH`: trường vắng / null giữ nguyên, `""` xóa ảnh bìa / tóm tắt; đổi slug sau lần xuất bản đầu → 400 `BR-BV-01` "Không đổi được đường dẫn sau lần xuất bản đầu tiên" (gửi lại đúng slug hiện tại là hợp lệ); `updatedAt` chỉ đổi khi dữ liệu đổi thật |
| 11, 12 | `POST …/:articleId/publish`, `…/hide` | DRAFT / HIDDEN → PUBLISHED (ghi `firstPublishedAt` MỘT lần, từ đó khóa slug); PUBLISHED → HIDDEN. Sai trạng thái → 409 `INVALID_STATE_TRANSITION` "Không thể chuyển trạng thái Article từ X sang Y" |
| 13 | `DELETE …/:articleId` | Bài có `firstPublishedAt` (kể cả đang HIDDEN) → 400 `BR-BV-02` "Bài viết đã từng xuất bản, chỉ ẩn được" |

Thứ tự lỗi sao BE: tạo bài lọc nội dung trước khi 404 chuyên mục; sửa bài 404 chuyên mục trước 404 bài. Trang công khai (`/news`, `/news/:slug`, `/public/article-categories`) đọc cùng state: đăng bài DRAFT → hiện ở `/public/articles`; ẩn → biến mất, đọc slug → 404; chuyên mục ẩn không có chip nhưng bài trong đó vẫn đọc được (BR-BV-04).

### A.3 Bộ lọc HTML và xem trước

Mock mô phỏng bộ lọc BE (`content-html.ts`, jsoup `Safelist.relaxed()`; allowlist lấy từ jar jsoup 1.18.3, có thêm `caption`, `col`, `colgroup` so với danh sách trong kế hoạch; **102 ca đối chiếu jsoup, chỉ lệch CDATA**): `a[href]`, `img[src]` chỉ `https` (thuộc tính khác bị bỏ), `<script>` / `<style>` bỏ cả nội dung; lưu và trả HTML đã lọc. **Xem trước** trong `<iframe sandbox="" srcDoc>` (không `allow-scripts`, không `allow-same-origin`): FE **không** thêm `dangerouslySetInnerHTML` thứ hai (chỗ duy nhất là `ArticleDetailPage`, ADR-0071) và không thêm thư viện.

### A.4 Giao diện (`pages/admin/AdminArticlesPage.tsx`, `pages/admin/articles/*`)

- Tab **Bài viết**: lọc trạng thái / chuyên mục / ô tìm (`maxLength=100`, debounce), phân trang; bảng tiêu đề + slug, chuyên mục (ghép tên từ danh sách chuyên mục vì `ArticleSummary` chỉ có `categoryId`), huy hiệu trạng thái, một cột "Cập nhật" hai dòng (ngày đăng lần đầu, cập nhật); nút Sửa, Đăng / Ẩn / Đăng lại (409 hiện ngay), Xóa (tắt kèm lý do với bài từng xuất bản; xác nhận bằng `ConfirmModal`).
- Form soạn / sửa: chuyên mục (chuyên mục ẩn ghi "(ẩn)"), tiêu đề, **slug tự sinh** từ tiêu đề (xử lý tường minh `đ`/`Đ`, vì `normalize('NFD')` không tách được: "Đường đi" → `duong-di`), khóa kèm giải thích khi bài đã xuất bản, ảnh bìa, tóm tắt (bộ đếm), tác giả (mặc định tên người đăng nhập), nội dung HTML + gợi ý thẻ được phép + nút Xem trước. `PATCH` chỉ gửi trường đổi. Lỗi 400 của BE hiện inline, không đóng form. Chi tiết bài luôn lấy mới (`gcTime: 0`): đóng form rồi người khác sửa, mở lại thấy bản mới nhất.
- Tab **Chuyên mục**: tạo (tên + slug), đổi tên, ẩn / hiện, xóa (tắt khi còn bài). Thông báo cũ biến mất khi bắt đầu thao tác mới (`setNotice(null)` ở hai tab).

### A.5 ASSUMPTION

Danh sách `categoryId` lạ → trang rỗng, còn `POST` / `PATCH` → 404 (BE thật; hợp đồng không liệt kê 404 cho `POST /articles`). Mutation làm mới `['articles']` và `['public']` cả khi lỗi. Ảnh bìa của mock là đường dẫn nội bộ `/imgs/…` (BE thật chỉ nhận `https`); form kiểm `https` khi người dùng đổi, không chặn ảnh cũ không phải `https` của bài mẫu khi không động tới.

### A.6 Ca kiểm thử (`chuoi@petcare.vn`; xóa `petcare-mock-content` để về dữ liệu mẫu)

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | Mở `/admin/articles` | Chuyển về `/articles`; 10 bài, Royal Canin đầu, "Đang đăng", "Đăng lần đầu 28/07/2026" | `AdminArticlesPage` › liệt kê 10 bài; URL tab sai |
| 2 | Lọc Nháp; chuyên mục "Lưu trú & dịch vụ (ẩn)"; hai bộ lọc cùng lúc | 1 bài; 1 bài; "Không có bài viết nào khớp bộ lọc." | › lọc theo trạng thái và chuyên mục |
| 3 | Gõ "vaccine" vào ô tìm | Chờ ~300 ms, còn 1 bài | › tìm theo tiêu đề |
| 4 | Soạn bài "Đường đi an toàn", chuyên mục Sức khỏe thú y, nội dung `<p>Xin chào</p>`, Lưu nháp | Slug tự "duong-di-an-toan"; tác giả "Đỗ Thanh Hà"; "Đã lưu bản nháp…"; trạng thái Nháp | › soạn bài mới |
| 5 | Lưu nháp khi form trống | 5 lỗi dưới từng ô, form vẫn mở | › thiếu trường bắt buộc |
| 6 | Nội dung `<script>alert(1)</script>` | "Nội dung bài viết trống sau khi lọc", form còn nguyên | › lỗi 400 của BE |
| 7 | Xem trước HTML có `onclick` và script | iframe sandbox; "Tiếp tục soạn" giữ nguyên nội dung | › hiện trong iframe sandbox |
| 8 | Sửa bài nháp "Mèo cào sofa", đổi tiêu đề | Chỉ gửi `title`; "Đã lưu…"; mở lại thấy giá trị mới | › Sửa lấy chi tiết; mở lại sau khi lưu |
| 9 | Sửa Royal Canin (đã xuất bản) | Ô slug bị khóa kèm "Đã khóa vì bài từng được xuất bản" | › bài đã từng xuất bản khóa slug |
| 10 | Đăng bài nháp → Ẩn → Đăng lại | Đang đăng, Đã ẩn, Đang đăng; nút đổi theo; **`/news` có bài khi Đang đăng, mất khi Ẩn** | › Đăng bài nháp → Đang đăng…; `content.mock` › công khai đổi theo admin |
| 11 | Xóa bài nháp (Hủy lần 1, Xóa lần 2) | 10 bài rồi 9 bài + "Đã xóa bài"; bài đã xuất bản có nút xóa tắt, hover "Đã từng xuất bản, chỉ ẩn được" | › xóa bài nháp; không xóa được |
| 12 | Hai tab trình duyệt: tab B đăng bài #6, tab A bấm Đăng | Tab A báo 409 "…từ PUBLISHED sang PUBLISHED", bảng tự làm mới | › 409 hiện ngay trên trang |
| 13 | Tab Chuyên mục: thêm "Dinh dưỡng đặc biệt", đổi tên, Ẩn / Hiện | Slug "dinh-duong-dac-biet", 0 bài; đổi tên giữ slug; thông báo cũ mất ngay khi bắt đầu thao tác mới | › tạo chuyên mục; đổi tên; bắt đầu thao tác mới |
| 14 | Xóa "Kiến thức nuôi thú cưng"; tạo bài rồi xem số bài | Nút xóa tắt "Còn 4 bài viết, chỉ ẩn được"; số bài tăng không cần tải lại | › chuyên mục còn bài; hai tab dùng chung dữ liệu |

Chỉ chạy tự động: phân trang bài (seed chỉ 10 bài), trạng thái đang tải / lỗi + "Thử lại" (cần devtools).

### A.7 Đột biến: 53 đột biến, 52 bị bắt, 1 tương đương

Script node (máy không có Python), hoàn nguyên và `cmp` với bản sao. Nhóm: mock (M01–M20: khóa slug, xóa bài từng xuất bản, xóa chuyên mục còn bài, đảo `updatedAt`, `firstPublishedAt` lần đầu / bị ghi đè, bỏ quyền, ADMIN lọt…), lọc HTML (H01–H08: `javascript:`, `http:`, `<script>`…, mỗi cái 1–10 test đỏ), slug (S01–S03: `đ`…), hook (U01–U04: bỏ invalidate `['public']`, bỏ `gcTime: 0`…), giao diện (P01–P11), bổ sung (N01–N02 `setNotice(null)`, C01–C04 tab Chuyên mục). **U04 sống sót lần đầu** (bỏ `gcTime: 0` thì form sửa mở lại từ cache cũ vì `onSettled` làm mới chi tiết khi form còn mở): đã thêm test "đóng form rồi người khác sửa bài" giết được. **P12 là mutant tương đương** (`!locked &&` trong `changesOf` không thể quyết định kết quả vì ô slug khóa không đổi được): không viết test.

## B. Nội dung trang (UC21)

### B.1 Endpoint mock (content-v1 #1, #2, `GET /public/pages/{key}`; test: `page-content.mock.test.ts`)

| # | Route | Quyền | Rule |
|---|---|---|---|
| 1 | `GET /api/page-contents` | SUPER_MANAGER | 4 trang HOME_BANNER, ABOUT, POLICY, FAQ, đúng 4 trường `key,title,content,updatedAt`; seed `content: {}`, `title: null` (như BE V21); role khác (cả ADMIN) 403; chưa đăng nhập 401 |
| 2 | `PUT /api/page-contents/:key` | SUPER_MANAGER | Ghi đè hoàn toàn (`title` vắng → null, không trộn); `updatedAt` = bây giờ |
| 3 | `GET /api/public/pages/:key` | công khai (token bị bỏ qua, ADR-0005) | Khóa lạ hoặc chữ thường → 404; state đọc lại mỗi request |

Thứ tự kiểm #2: 401 → 400 `MALFORMED_REQUEST` (body không phải object; `title` không phải chuỗi / null) → 400 `VALIDATION_FAILED` ("title: Tiêu đề tối đa 250 ký tự"; thiếu `content`: "content: Vui lòng nhập nội dung trang") → 403 → 404 khóa → 400 "Nội dung trang phải là một đối tượng JSON" (mảng, chuỗi, số, boolean, `null`) → 400 "Nội dung trang tối đa 64 KB". **64 KB = byte UTF-8 của JSON gọn** (`pageContentBytes`; chữ có dấu 2–3 byte, nên đếm ký tự và đếm byte lệch nhau); `title` không tính; đúng 65.536 nhận, 65.537 từ chối.

### B.2 Cấu trúc nội dung (Q1: FE đề xuất, **người dùng đã xác nhận 27/10**; cần báo BE-2 ghi vào hợp đồng)

BE chấp nhận mọi JSON object ≤ 64 KB; FE đề xuất cấu trúc nhỏ nhất ánh xạ 1-1 với các khối từng tĩnh (`shared/constants/page-content.ts`: zod, mặc định, giới hạn). Mọi chuỗi: trim, 1…N ký tự, chỉ văn bản thuần (ADR-0071), `\n` = xuống dòng / sang đoạn.

| Khóa | Trường | Kiểu / giới hạn | Trang dùng |
|---|---|---|---|
| HOME_BANNER | `headline` ≤ 20, `subheadline` ≤ 80, `ctaLabel` ≤ 24 | chữ hero | `HeroBanner` (`/`), nút trỏ `/booking` |
| HOME_BANNER | `heroStat` `{value ≤ 10, label ≤ 40}`, `stats` đúng 4 × `{value ≤ 10, label ≤ 40}` | | `HeroBanner`, dải "Sự tin tưởng…" của `HomePage` |
| ABOUT | `stats` đúng 4 × `{value, label}`; `values` đúng 4 × `{title ≤ 30, description ≤ 200}` | | `AboutPage` (biểu tượng theo vị trí) |
| POLICY | `terms`, `privacy`, mỗi cái `{lead ≤ 400, updatedOn ≤ 20, sections 1–20 × {heading ≤ 100, body ≤ 3000}}` (`body`: mỗi dòng một đoạn) | | `LegalPage` `/terms`, `/privacy` |
| FAQ | `items` 1–20 × `{question ≤ 150, answer ≤ 500}` | | `HomePage` (câu đầu mở sẵn) |

`PageContent.title` **không dùng** (mọi chữ nằm trong `content`; mỗi khóa có nhiều tiêu đề nên một `title` không ánh xạ 1-1). **HOME_BANNER** phủ chữ sửa được của hero (mong đợi của người bấm "Banner trang chủ") cộng dải số liệu.

**Trang công khai** (`parsePageContent`, `usePublicPage`): `{}`, thiếu trường, sai kiểu, rỗng, vượt giới hạn → **cả bộ** `PAGE_DEFAULTS` của khóa (không trộn từng trường); hợp lệ → trim, bỏ trường lạ. **Request lỗi: `ready=true` ngay sau lần lỗi đầu**, hiện mặc định, không đợi ~7 giây thử lại (không bao giờ làm trống trang). Lúc tải (`ready=false`): skeleton cho số liệu, FAQ, giá trị, điều khoản; chữ hero giữ khối `opacity-0` và chưa chạy GSAP, nên **không nháy từ mặc định sang chữ đã sửa**. GSAP khóa theo dữ liệu, `revertOnUpdate`, `ScrollTrigger.refresh()` sau render.

### B.3 Giao diện quản trị (`pages/admin/AdminPageContentsPage.tsx`, `pages/admin/page-contents/*`)

Tab theo `?trang=<KHÓA>` (khóa sai → tab đầu). **Nháp từng tab nằm ở state cha**: đổi tab, lưu hay tải lại danh sách không làm mất chữ đang soạn; nháp tự xóa khi lưu, hoàn tác, hoặc khi về đúng bản đã lưu (so sánh thô để không nuốt dấu cách cuối); tab có nháp ghi "Chưa lưu". Form có cấu trúc theo từng khóa (không có ô JSON thô): số ô cố định (4 số liệu, 4 giá trị) không thêm / xóa; POLICY và FAQ thêm / xóa / chuyển lên-xuống (1–20 mục), con trỏ đi theo thao tác. Lỗi theo trường dựng từ `PAGE_SCHEMAS[key].safeParse` (thông điệp tiếng Việt, giới hạn lấy từ schema); **không đặt `maxLength`** để vượt giới hạn vẫn hiện lỗi; bộ đếm theo `trim().length`; **phải `safeParse` trước khi PUT** (một trường sai thì trang công khai lặng lẽ về mặc định cả khóa); kiểm 64 KB theo byte trước khi gửi ("Nội dung quá dài (N KB / 64 KB)"); gửi `parsed.data` (đã trim), **không gửi `title`**. Thanh Lưu dính đáy ("Còn N trường chưa hợp lệ", "Có thay đổi chưa lưu"). Hoàn tác luôn hỏi xác nhận; Khôi phục mặc định chỉ hỏi khi đang có nháp. Dải "đang dùng nội dung mặc định trong mã" khi nội dung đã lưu là `{}` hoặc sai dạng. "Xem trang" mở trang công khai ở tab mới (`rel="noopener noreferrer"`). Sau khi lưu, danh sách quản trị cập nhật ngay từ kết quả `PUT` (không nháy lại chữ cũ).

### B.4 ASSUMPTION

`"content": null` rơi vào kiểm "đối tượng JSON" (Jackson `NullNode`), chỉ thiếu hẳn trường `content` mới là "Vui lòng nhập nội dung trang"; `title` không phải chuỗi / null là body không đọc được; BE không trim `title`.

### B.5 Ca kiểm thử

**Quản trị** (`chuoi@petcare.vn`; xóa `petcare-mock-pages` để về `{}`):

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | Mở `/admin/page-contents` | h1, 4 tab, dải mặc định, Lưu tắt | `AdminPageContentsPage` › giữ h1…; trang mới |
| 2 | Sửa "Chữ trên nút" | Lưu bật, tab "Chưa lưu", đếm 13/24 | › sửa một trường |
| 3 | Lưu, mở `/` | Báo đã lưu, hết dải, trang chủ có chữ mới | › lưu thành công |
| 4 | Xóa trống hoặc gõ 25 ký tự | Lỗi dưới ô, Lưu tắt | › để trống; vượt giới hạn |
| 5 | Sửa tab 1, lưu FAQ, quay lại | Nháp tab 1 còn | › không mất khi chuyển tab |
| 6 | Hoàn tác / Khôi phục | Hỏi xác nhận theo luật | › Hoàn tác; Khôi phục |
| 7 | FAQ thêm / xóa / chuyển | 1 ≤ n ≤ 20, thứ tự lưu đúng | 6 ca danh sách |
| 8 | Chính sách: dán chữ "ệ" nhiều byte | "Nội dung quá dài", không PUT | › giới hạn 64 KB |
| 9 | Gõ `<b>` `<script>` rồi lưu | Hiện nguyên chữ | › HTML gõ vào |
| 10 | `?trang=ABOUT` / `?trang=XYZ` | Đúng tab / tab đầu | › mở thẳng tab; khóa sai |
| 11 | 360 / 390 px | Không cuộn ngang | chỉ Playwright |

**Trang công khai** (khách vãng lai; sửa nội dung bằng localStorage `petcare-mock-pages`: `{"ABOUT":{"key":"ABOUT","title":null,"content":{…},"updatedAt":"…"}}` rồi F5, vì query công khai có `staleTime` 5 phút; `content` phải là object, nếu không khóa đó về seed):

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | `/` khi `{}` | Hero "Pet / Care", "Đặt Lịch Ngay" → `/booking`, "2.500+"; 4 số liệu 10K+, 500+, 24h, 4.9/5; FAQ 4 câu, câu đầu mở | `HomePage` › BE chưa có nội dung; `HeroBanner` › {} |
| 2 | `/about` khi `{}` | 4 số liệu 10K+, 15+, 50+, 4.9/5; 4 giá trị Yêu thương, An toàn, Chuyên nghiệp, Chất lượng | `AboutPage` › BE chưa có nội dung |
| 3 | `/terms`, `/privacy` khi `{}` | Mỗi trang 7 mục đánh số, mục lục `#muc-N`, "Cập nhật lần cuối: 16/09/2026", link chéo | `LegalPage` › Điều khoản…; Bảo mật… |
| 4 | Sửa HOME_BANNER và FAQ rồi mở `/` | Hero, số liệu, FAQ đổi, chữ mặc định mất; mở / đóng từng câu | `HomePage` › nội dung đã sửa; `HeroBanner` |
| 5 | Sửa ABOUT và POLICY rồi mở `/about`, `/terms`, `/privacy` | Mỗi trang lấy đúng tài liệu; dòng trống bị bỏ | `AboutPage`, `LegalPage` › nội dung đã sửa |
| 6 | Ghi sai dạng (stats 3 ô, `items: []`, thiếu `ctaLabel`, `sections: []`) | Cả khóa về mặc định, không trống | 4 file › sai dạng |
| 7 | Lỗi request (chỉ tự động) | Vẫn hiện mặc định, không kẹt skeleton, không alert | 4 file › request lỗi; `usePageContent` › 4xx / 5xx |
| 8 | Gõ `<b>1</b>`, `<img onerror>` vào số liệu / FAQ / điều khoản | Hiện thành chữ, không thành phần tử | › chỉ văn bản thuần |
| 9 | Không nháy lúc tải (chỉ tự động) | Skeleton, chưa lộ chữ mặc định; hero `opacity-0` tới khi có câu trả lời | › đang tải; `HeroBanner` › không nháy |

### B.6 Đột biến: 66 đột biến, mọi mutant bị bắt

PAGE-A 30 (bỏ ghi đè → trộn, bỏ kiểm 64 KB, đếm ký tự thay vì byte, bỏ kiểm "JSON object", bỏ kiểm quyền, bỏ `safeParse`, bỏ `invalidate ['public']`…). PAGE-B2 36 (bỏ kiểm 64 KB, gửi kèm `title`, gửi nháp thô thay vì kết quả `safeParse`, mất nháp khi đổi tab, cho Lưu khi không hợp lệ, bỏ dải mặc định, "lên" chuyển xuống, thêm quá 20 mục, `splice` làm hỏng `PAGE_DEFAULTS`…): 35 đỏ ngay lần đầu; **M24** (nháp không tự xóa khi về đúng bản lưu, gần tương đương) sống sót lần đầu: đã thêm test "bản lưu mới từ nơi khác hiện ra khi tải lại", nay đỏ.

## C. Feedback (UC83, UC84)

### C.1 Tài khoản và dữ liệu mẫu (mật khẩu `Petcare123`)

`khach@petcare.vn` (khách 1, có đủ 3 trạng thái), `khachmoi@petcare.vn` (chờ quyết định liên kết, BR-TK-19), `chuoi@petcare.vn` (SUPER_MANAGER), `manager@store1.vn` (BRANCH_MANAGER chi nhánh 1), `manager2@store2.vn` (chi nhánh 2, DRAFT). 6 feedback (#1 RESOLVED, #2 SEEN `WEBSITE` không gắn chi nhánh, #3 NEW, #4 SEEN, #5 NEW `OTHER` không gắn chi nhánh, #6 RESOLVED; #3 và #4 của khách 3, 4 là hồ sơ tại quầy dùng chung SĐT với `khachmoi@`); ngày tính lùi từ hôm nay theo giờ Việt Nam, đều trước 00:00 hôm nay nên không tính vào giới hạn hôm nay. Reset: xóa `petcare-mock-feedback`.

### C.2 Endpoint mock (content-v1 #18–22; test: `feedback.mock.test.ts`)

| # | Route mock | Quyền | Rule đã mô phỏng |
|---|---|---|---|
| 18 | `POST /api/me/feedbacks` | CUSTOMER | Thứ tự BR-TK-19 ("Hồ sơ của bạn đang chờ quyết định liên kết…") → nội dung 10–2000 **code point** (NFC + strip) → điểm 1–5 → BRANCH_SERVICE thiếu chi nhánh → chi nhánh không ACTIVE 404 → quá 5/ngày [CFG] "Bạn đã gửi đủ 5 feedback hôm nay, có thể gửi tiếp từ 00:00 ngày …" (ngày Việt Nam); sau khi gửi gọi `markHasData(customerId)` |
| 19 | `GET /api/me/feedbacks` | CUSTOMER | Mảng, không phân trang, mới nhất trước, chỉ của chính mình; **không chặn** khách chờ liên kết |
| 20 | `GET /api/feedbacks` | SUPER_MANAGER, BRANCH_MANAGER | Phân trang; lọc `branchId` / `status` / `topic` / `from` / `to` (`yyyy-MM-dd`, giờ Việt Nam, gồm hai đầu; ngày không có thật bị từ chối); `from > to` → 400 "Ngày bắt đầu không được sau ngày kết thúc"; `createdAt DESC`. BRANCH_MANAGER: bỏ `branchId` = chi nhánh mình, chi nhánh khác → 403 `ACCESS_DENIED_SCOPE_MISMATCH`, không thấy feedback không gắn chi nhánh; chi nhánh 2 → danh sách rỗng (không phải 403) |
| 21 | `GET /api/feedbacks/:id` | như trên | 404 / 403 phạm vi; **có tác dụng phụ** NEW → SEEN, ghi `seenAt` MỘT lần (mở lần hai không đổi) |
| 22 | `POST /api/feedbacks/:id/resolve` | như trên | Chỉ từ `SEEN`; ghi chú trống sau strip → 400 `BR-DG-04` "Vui lòng nhập ghi chú xử lý", tối đa 2000 (UTF-16); NEW hoặc RESOLVED → 409 "Không thể chuyển trạng thái Feedback từ … sang RESOLVED"; ghi `resolvedBy` (chỉ mã), `resolvedAt`, `resolutionNote` |

Khách chỉ thấy `MyFeedback` (không có ghi chú nội bộ, BR-DG-04).

### C.3 ASSUMPTION và chỗ BE khác kế hoạch (đã đọc code BE)

- `GET /me/feedbacks` **không** chặn khách còn chờ quyết định liên kết: BE (`FeedbackSubmitService.listMine`) chỉ kiểm BR-TK-19 ở POST (không có `MyFeedbackService` như kế hoạch giả định); khách đó nhận `[]` (thực tế rỗng vì chưa gửi được); trang `/review` nằm sau `RequireLinkDecision` nên không gọi tới.
- `GET /feedbacks`: BE kiểm `from > to` (400) **trước** phạm vi chi nhánh (403), ngược với thứ tự chung của kế hoạch.
- Gửi: chi nhánh có gửi `branchId` phải ACTIVE với **mọi** chủ đề (không chỉ BRANCH_SERVICE); không ACTIVE hoặc không có → 404.
- Ghi chú xử lý ≤ 2000 theo UTF-16 (`@Size`), khác nội dung feedback đếm code point. Giới hạn mỗi ngày là hằng `DAILY_LIMIT = 5` (mặc định của [CFG] `feedback.max_per_day`; mock identity chưa có tham số này). Thời điểm trả `…Z` như BE thật (hợp đồng ghi `+07:00`), FE định dạng giờ Việt Nam tường minh.

### C.4 Giao diện

- **Khách `/review` ("Góp ý của tôi")**: thay hẳn trang cũ toàn dữ liệu giả (tab Google, "hữu ích", modal chi nhánh cứng, "hiển thị sau khi duyệt" trái BR-DG-02). Nói rõ góp ý không hiển thị công khai. Form: chủ đề (Dịch vụ tại chi nhánh / Website / Khác), chi nhánh (bắt buộc khi chọn dịch vụ tại chi nhánh, từ chi nhánh ACTIVE công khai), điểm 1–5 tùy chọn (có "Bỏ chọn"), nội dung 10–2000 có bộ đếm theo code point. Danh sách của tôi: mới nhất trước, huy hiệu Mới gửi / Đã xem / Đã xử lý. Gửi xong form được làm sạch, thông báo mất khi gõ tiếp. Nhãn menu người dùng ở `SiteHeader` đổi "Đánh giá dịch vụ" → "Góp ý của tôi".
- **Quản trị `/admin/feedbacks`**: lọc trạng thái, chủ đề, chi nhánh (**chỉ SUPER_MANAGER**; BRANCH_MANAGER ngầm là chi nhánh mình), từ ngày / đến ngày, phân trang; bấm dòng mở chi tiết (gọi #21, NEW → SEEN, danh sách làm mới; không prefetch chi tiết) với ô "Ghi chú xử lý" (bắt buộc, ≤ 2000, bộ đếm) và nút "Đánh dấu đã xử lý" **chỉ khi SEEN**; khi RESOLVED chỉ đọc (ai, lúc nào, ghi chú).

### C.5 Ca kiểm thử

| # | Tài khoản | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|---|
| 1 | khach@ | Mở `/review` | h1 "Góp ý của tôi", câu "không hiển thị công khai"; 3 góp ý: Mới gửi (hôm qua 16:20), Đã xem, Đã xử lý; không có "ghi chú nội bộ" hay góp ý của khách khác | `ReviewsPage` › có `<h1>`; mới nhất trước; không hiện ghi chú nội bộ |
| 2 | khach@ | Chi nhánh Cầu Giấy, 4 sao, nội dung ≥ 10 ký tự, Gửi | "Đã gửi góp ý"; mục mới ở đầu, Mới gửi, 4 sao; form sạch | › hợp lệ: có thông báo thành công |
| 3 | khach@ | Chủ đề Website, không điểm; chọn 2 sao rồi "Bỏ chọn" | Gửi được, không chi nhánh, không sao | › chủ đề Website; Bỏ chọn |
| 4 | khach@ | Gõ "ngắn", Gửi | "Nội dung feedback phải từ 10 đến 2000 ký tự", nội dung giữ nguyên | › nội dung quá ngắn |
| 5 | khach@ | Chủ đề chi nhánh nhưng chưa chọn chi nhánh | "Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh" | › chưa chọn chi nhánh |
| 6 | khach@ | Gửi đủ 5 góp ý hôm nay (#3 hôm qua không tính), rồi lần 6 | "Bạn đã gửi đủ 5 feedback hôm nay, có thể gửi tiếp từ 00:00 ngày mai" | › quá giới hạn trong ngày |
| 7 | khach@ | Dán 9 emoji; gõ "   ngắn gọn   "; sau khi gửi xong gõ tiếp | Bộ đếm 9/2000 và 8/2000; thông báo thành công mất khi gõ tiếp | › bộ đếm; thông báo thành công mất |
| 8 | khachmoi@ | Mở `/review`, gửi Website | "Bạn chưa gửi góp ý nào"; gửi thì "Hồ sơ của bạn đang chờ quyết định liên kết…" (BR-TK-19), nội dung giữ | › hồ sơ còn chờ quyết định liên kết |
| 9 | khach@ | Menu người dùng ở header | Mục "Góp ý của tôi" dẫn tới `/review` | › mục "Góp ý của tôi" trong menu |
| 10 | chuoi@ | Mở `/admin/feedbacks` | h1 "Góp ý của khách"; 6 góp ý, mới nhất trước; #5 "Không gắn chi nhánh" và "Chưa chấm" | `AdminFeedbacksPage` › danh sách (UC84) |
| 11 | chuoi@ | Lọc Trạng thái "Mới gửi", Chủ đề Website, Chi nhánh, Từ / Đến ngày; Từ > Đến; Xóa lọc | Thu hẹp đúng; Từ > Đến → "Ngày bắt đầu không được sau ngày kết thúc"; Xóa lọc về đủ 6 | › bộ lọc và phân trang |
| 12 | chuoi@ | Bấm dòng "Mới gửi" | Hộp thoại "Góp ý #n"; thành "Đã xem" ở cả hộp thoại và bảng; có giờ xem lần đầu | › mở chi tiết |
| 13 | chuoi@ | Mở góp ý "Đã xem": ghi chú trống rồi Đánh dấu đã xử lý; sau đó nhập ghi chú, lưu | Lần 1 "Vui lòng nhập ghi chú xử lý", giữ nhập. Lần 2 chỉ đọc, "Bạn", "Đã đánh dấu xử lý góp ý #n". Tab thứ hai xử lý trước thì báo 409 | › xử lý (SEEN → RESOLVED…); người khác vừa xử lý xong: 409 |
| 14 | manager@store1.vn rồi manager2@store2.vn | Mở `/admin/feedbacks` | store1: #3, #4, #6, #1, không có bộ lọc chi nhánh, không thấy #5 và #2. store2: "Chưa có góp ý nào" (không phải 403) | › phạm vi theo vai trò; rỗng: chưa có góp ý nào |

### C.6 Đột biến: 97 đột biến, 97 bị bắt

M01–M50 (luật mock: giới hạn ngày, đếm code point, BR-TK-19, lộ ghi chú nội bộ, phạm vi chi nhánh, resolve từ NEW, mở lại đổi `seenAt`, `markHasData`, sắp xếp, `from > to`…), P01–P20 (trang khách), A01–A27 (trang admin: lần đầu lỗi hạ tầng `0xC0000142` khi chạy song song, chạy lại tuần tự: 27/27 đỏ).

## D. Care Task của lễ tân (UC87)

### D.1 Endpoint mock (care-v1 #1–4, `care.mock.ts`; test: `care.mock.test.ts`)

| # | Route mock | Quyền | Rule đã mô phỏng |
|---|---|---|---|
| 1 | `GET /api/care-tasks` | RECEPTIONIST | `status` vắng = OPEN (không có "tất cả"); lọc `type`, `dueFrom`/`dueTo` gồm hai đầu (`dueFrom > dueTo` → trang rỗng, không 400); chỉ việc của chi nhánh người gọi; hạn tăng rồi id tăng; `size` kẹp 1–100 (mặc định 20), `page` âm → 0 |
| 2 | `GET /api/care-tasks/:id` | RECEPTIONIST | Thêm `customerName`, `petName`, `phoneToCall` từ mock khách / thú (`PICKUP_OVERDUE` dùng SĐT khẩn, loại khác dùng SĐT hồ sơ); id lạ 404 "Không tìm thấy việc gọi điện #id"; chi nhánh khác 403 `ACCESS_DENIED_SCOPE_MISMATCH` |
| 3 | `POST …/:id/complete` `{result, note}` | RECEPTIONIST | OPEN → DONE; ghi `result`, `note` (trim, ≤ 500), `handledBy`, `handledAt`; không sinh việc gọi lại |
| 4 | `POST …/:id/cancel` `{reason}` | RECEPTIONIST | OPEN → CANCELLED; `reason` trim, ≤ 300; ghi `handledBy`, `handledAt` |

Thứ tự kiểm: #1 400 `MALFORMED_REQUEST` (`status`, `type`, `dueFrom`, `dueTo`, `page`/`size`) → 403 `ACCESS_DENIED` → lọc theo chi nhánh. #2 400 id → 403 vai trò → 404 → 403 phạm vi. #3 400 id → 400 `MALFORMED_REQUEST` (`result` ngoài enum, `note` không phải chuỗi) → 400 `VALIDATION_FAILED` theo trường (thiếu `result`, thiếu `note`, `note` > 500) → 403 vai trò → 404 → 403 phạm vi → 400 `BR-TB-05` (`note` toàn khoảng trắng) → 409 `INVALID_STATE_TRANSITION` "Không thể chuyển trạng thái CareTask từ X sang DONE". #4 như #3 với `reason` (không có bước BR: thiếu / trống / > 300 → `VALIDATION_FAILED`).

### D.2 Dữ liệu mẫu (9 việc, chi nhánh 1; D = hôm nay theo giờ Việt Nam; tài khoản `reception@store1.vn` / `Petcare123`; reset: xóa `petcare-mock-care`)

| careTaskId | Loại | Trạng thái | Hạn | Khách / thú | Điểm đáng chú ý |
|---|---|---|---|---|---|
| 9 | Tái chủng | Đang chờ | D+2 | Lê Thị Hoa / Miu | `tel:0905111222` |
| 8 | Tái khám | Đang chờ | D | Lê Thị Hoa / Miu | Hạn hôm nay: không đánh dấu quá hạn |
| 7 | Quá hạn đón thú | Đang chờ | D−1 | Nguyễn Thu Trang / Bim | SĐT khẩn `0987000111` (hồ sơ khách 0912345678) |
| 6 | Quá hạn đón thú | Đang chờ | D−1 | Nguyễn Thu Trang / Tôm | Không SĐT khẩn → "Chưa có số điện thoại"; cùng hạn #7, đứng trước |
| 5 | Quá hạn tái chủng | Đang chờ | D−3 | Lê Hoàng Long / Lu | "Quá hạn 3 ngày" |
| 4 | Quá hạn tái chủng | Đã hủy | D−4 | Nguyễn Thu Trang / Mochi | Hệ thống hủy (`handledBy` null), "Đã tiêm lại (BR-TB-03)" → "Hệ thống" |
| 3 | Quá hạn đón thú | Đã hủy | D−5 | Lê Hoàng Long / Lu | Lễ tân hủy 17:45 → "Bạn" |
| 2 | Tái chủng | Đã xong | D−30 | Lê Thị Hoa / Miu | Liên hệ được, 10:15 |
| 1 | Quá hạn tái chủng | Đã xong | D−6 | Nguyễn Thu Trang / Bim | Không liên lạc được, 14:40 |

Seed thỏa CHECK của ERD `care_tasks` (đúng một khóa liên kết theo loại; DONE ⇔ có `result`; CANCELLED có `cancelReason`; test duyệt toàn seed). Dữ liệu mẫu tính một lần lúc tạo state rồi lưu: để vài ngày thì "Quá hạn N ngày" tăng; xóa khóa trước mỗi lượt thử.

### D.3 Giao diện (`pages/admin/AdminCareTasksPage.tsx`, `pages/admin/care-tasks/CareTaskDialogs.tsx`)

Tab trạng thái (Đang chờ [mặc định] / Đã xong / Đã hủy; hợp đồng không có "tất cả"), lọc loại (4 loại tiếng Việt) và từ / đến ngày, phân trang 20 việc (xử lý nốt việc cuối của trang cuối thì lùi một trang). Bảng: hạn (nhãn "Quá hạn N ngày" so với hôm nay giờ Việt Nam), khách + số điện thoại là link `tel:` (null → "Chưa có số điện thoại"), loại + thú + mô tả; việc đã xử lý hiện kết quả, ghi chú, người xử lý ("Bạn" / "Hệ thống" / "Nhân viên #n"), lý do hủy. "Đã gọi" mở hộp thoại bắt buộc chọn kết quả (không chọn sẵn) + ghi chú (đếm `n/500`); "Hủy" bắt buộc lý do ≤ 300 (đếm `n/300`); chỉ việc đang chờ có nút. Lỗi BE (409 ở nơi khác đã xử lý, `BR-TB-05`) hiện trong hộp thoại, danh sách làm mới. Sau khi đổi bộ lọc hoặc tab thì thông báo thành công cũ mất. Bảng rộng 880 px cuộn ngang trong thẻ ở màn hẹp.

### D.4 ASSUMPTION (C1–C8 của kế hoạch)

C1 thứ tự sắp, `status` vắng = OPEN, hai đầu gồm, `dueFrom > dueTo` rỗng, kẹp `page` / `size`. C2 hoàn tất cần cả `result` và `note`; hợp đồng gộp `BR-TB-05` cho cả "chưa chọn kết quả", mock tách (thiếu → `VALIDATION_FAILED`, `note` toàn khoảng trắng → `BR-TB-05`); FE luôn chặn trước nên người dùng không phân biệt. C3 hủy: `reason` thiếu / trống / > 300 → `VALIDATION_FAILED`. C4 khác chi nhánh → 403 phạm vi. C5 chỉ việc tay mới có người xử lý, hệ thống hủy để null. C6 câu 409. C7 mọi role khác lễ tân → 403. C8 `note` ≤ 500 là giới hạn của FE / mock. Khác: câu 404 do mock đặt; id / tham số sai kiểu → 400. Lệch ERD: việc #6 không có SĐT khẩn dù ERD đặt `emergency_phone` NN (để thử giao diện); SĐT khẩn nằm trong dòng mock vì chưa có mock đặt chỗ lưu trú; khóa liên kết (`vaccinationId`…) là id giả; trang chỉ có `handledBy` (số) nên không hiện tên người xử lý khác.

### D.5 Ca kiểm thử (`reception@store1.vn`, `/admin/care-tasks`)

| # | Làm | Kỳ vọng | Test tự động (rút gọn) |
|---|---|---|---|
| 1 | Mở trang | h1 "Việc gọi điện"; tab "Đang chờ"; "5 việc đang chờ"; thứ tự #5, #6, #7, #8, #9; mỗi hàng có "Đã gọi" và "Hủy" | `AdminCareTasksPage` › mặc định là việc đang chờ… |
| 2 | Chọn loại "Quá hạn đón thú", rồi "Mọi loại" | 5 lựa chọn; còn #6, #7; về 5 hàng | › lọc theo loại việc |
| 3 | Hạn từ = hạn đến = D−1; "Xóa bộ lọc"; loại "Tái khám" + tab Đã xong | 2 hàng (#6, #7); về 5 hàng; "Không có việc nào khớp bộ lọc" | › lọc theo khoảng hạn…; bộ lọc không khớp |
| 4 | Xem nhãn quá hạn ở 3 tab | #5 "Quá hạn 3 ngày", #6 / #7 "1 ngày"; #8, #9 và hai tab còn lại không có nhãn | › đánh dấu quá hạn theo ngày Việt Nam (2 mốc giờ) |
| 5 | Cột khách của #5, #7, #6 | `tel:0905111222`, `tel:0987000111`; #6 "Chưa có số điện thoại", không link, "Đã gọi" vẫn bấm được | › khách có số điện thoại là link tel: |
| 6 | "Đã gọi" #7: Lưu ngay; chọn kết quả; ghi chú trống / toàn dấu cách | Không chọn sẵn; đếm `0/500`; lỗi inline "Chọn kết quả cuộc gọi." rồi "Nhập ghi chú cuộc gọi."; chưa gửi request | › bắt buộc chọn kết quả rồi viết ghi chú |
| 7 | Điền đủ, lưu | "Đã ghi kết quả cuộc gọi: …"; #7 rời "Đang chờ" (4 việc); tab Đã xong có #7 kèm kết quả, ghi chú, "Bạn" | cùng ca 6; không liên lạc được cũng đóng việc |
| 8 | Đóng hộp thoại giữa chừng, mở lại | Trống, chưa gửi gì | › đóng hộp thoại không gửi gì |
| 9 | "Hủy" #8: bỏ trống; nhập lý do; lưu | "Nhập lý do hủy."; `maxlength` 300, đếm `n/300`; #8 sang tab Đã hủy với lý do và "Bạn" | › bắt buộc lý do (tối đa 300) |
| 10 | Hai tab: xử lý #5 ở tab kia rồi lưu ở tab này | Lỗi 409 "…từ DONE sang DONE" trong hộp thoại, hộp thoại không đóng, danh sách làm mới; mã "BR-TB-05" bị ẩn | › lỗi BR-TB-05; …đã được xử lý ở nơi khác (409) |
| 11 | Tab Đã xong / Đã hủy | Badge kết quả, ghi chú, lý do, "Người xử lý: Bạn · giờ"; #4 là "Hệ thống"; không nút, không cột Thao tác | › việc đã xong và đã hủy hiện kết quả… |
| 12 | Thêm ≥ 16 việc vào state, qua trang 2, xử lý nốt việc cuối | 20 việc / trang, "Trang 1/2"; trang cuối trống thì lùi một trang; đổi bộ lọc xin lại từ trang 0 | › hiện 20 việc mỗi trang…; đổi bộ lọc thì xin lại từ trang đầu |
| 13 | manager@store1.vn, chuoi@petcare.vn mở `/admin/care-tasks` | "Bạn không có quyền vào trang này"; menu không có "Việc gọi điện"; API 403 `ACCESS_DENIED` | `App.routes.test` › shows 403 in place; `staff-menu.test`; `care.mock` › không dùng được Care Task (7 vai trò) |
| 14 | Đang tải / lỗi tải + "Thử lại" / không còn việc (chỉ tự động) | Khung chờ; thông báo kèm nút; "Không còn việc nào cần gọi" | › bốn trạng thái |

Không có test: ràng buộc `min` / `max` giữa hai ô ngày; nhãn "Nhân viên #n"; nút "Quay lại" của hộp thoại hủy.

### D.6 Đột biến: 70 đột biến, 70 bị bắt

(bỏ kiểm OPEN, bỏ bắt buộc `result` / `note`, bỏ kiểm phạm vi, cho BRANCH_MANAGER vào, sắp xếp giảm dần, `dueTo` loại biên, quên ghi `handledBy`, DONE mà không có `result`, bỏ gộp mặc định state cũ…). Một ca sống ban đầu (ô ghi chú không giới hạn 500) đã thêm test, nay đỏ.

## E. Còn lại, không làm ở T47

- **Q1** (cấu trúc nội dung 4 trang, B.2) là đề xuất của FE và **đã được người dùng xác nhận (27/10)**; chưa ghi vào hợp đồng (`docs/api/generator/m_content.py` mục Q1, sinh lại `content-v1.md` bằng Python, máy này không có) và chưa báo BE-2 (Dũng); hợp đồng ghi "FE và BE chốt khi làm UC21". BE chấp nhận mọi JSON object ≤ 64 KB nên không có xung đột kỹ thuật, nhưng cần xác nhận trước khi nối BE thật.
- **Khối vẫn tĩnh, ngoài UC21** (không có khóa trong hợp đồng): `SiteFooter.tsx` hotline "1900 1234", email, địa chỉ "123 Nguyễn Trãi, Quận 1"; khối Liên hệ và subtitle "Hơn 15 năm…" của `AboutPage.tsx`; `ContactVetCTA.tsx` dòng "24/7" và `tel:19001234`; `HeroBanner.tsx` ba thẻ và "Đánh Giá 5 Sao…"; tiêu đề dải số liệu và tiêu đề FAQ của `HomePage.tsx`. **Mâu thuẫn dữ liệu chưa đổi:** hotline `LegalPage` "1900 6868" lệch "1900 1234" ở footer / About / CTA; "Hơn 15 năm" cứng so với số liệu "15+" sửa được; địa chỉ Quận 1 so với chi nhánh mẫu Cầu Giấy. Cần quyết định số nào là đúng.
- **Màn 390 px:** bảng Care Task và Feedback phải vuốt ngang trong thẻ để thấy cột "Thao tác" / "Trạng thái" (trang không tràn ngang). Hướng tốt hơn là gộp cột thành thẻ như `ArticlesTab`. Chưa làm: **đã chấp nhận cho T47 (27/10)**, để đợt hoàn thiện giao diện (T49 / T57).
- **Quản trị nội dung trang:** rời route là mất hết nháp (không có guard; gợi ý `beforeunload`, vì `useBlocker` cần data router); nếu đổi tab giữa lúc PUT rồi quay lại, editor mới không `busy` và khi PUT cũ xong sẽ xóa nháp vừa gõ (khoảng một vòng request); `parsePageContent` trả thẳng đối tượng `PAGE_DEFAULTS` dùng chung (code hiện chỉ đọc, nhưng nên `structuredClone` / deep-freeze).
- **Care Task:** nút "Hủy" của hộp thoại "Đã gọi" (đóng hộp thoại) dễ nhầm với "Hủy việc" (hộp thoại Hủy đã dùng "Quay lại"); đổi sẽ phải sửa hai dòng test.
- **Có sẵn từ trước, chưa đụng:** `SiteHeader` chưa có bố cục mobile (không có nút menu, các hàng chồng nhau ở 390 px; ở 1280 logo bị ô tìm kiếm che chữ cuối); `FormModal` luôn dùng biểu tượng "+" kể cả hộp thoại "Ghi kết quả cuộc gọi" và "Sửa bài viết"; footer "Thực hiện với ♥ dành cho…" xuống dòng lệch ở 390; mega-menu header `?petType=` và mã giỏ hàng chết (T57).
- **Chưa xem bằng trình duyệt:** chế độ tối; trạng thái đang tải và lỗi mạng ("Thử lại") của mọi trang; Care Task đường lỗi "đã chọn kết quả nhưng ghi chú trống"; `/privacy` ở 390 px từng đoạn; `/admin/page-contents` ở 1440 px, và `/about`, `/privacy` sau khi lưu từ giao diện quản trị.
- **BE thật:** content-v1 chưa chạy thử (so sánh thông điệp 400 / 409, `Safelist.relaxed()` với allowlist của mock, `GET /feedbacks/{id}` có tác dụng phụ); Care Task chờ T39, khi đó đối chiếu mock với code BE như đã làm cho tiêm chủng (T37-fe.md A.3b), đặc biệt mã lỗi của "chưa chọn kết quả" (C2) và `handledBy` khi hủy.
- **Đã gộp `origin/dev` (27/10, fast-forward lên `a965e9e`, không cần merge commit):** BE-1 đã giao kê đơn thật (T38, ADR-0076–0078) và **commit sẵn dấu conflict (`<<<<<<<`) vào `docs/time.md`** (commit `7ac3382`, quanh dòng "Giai đoạn 1" và hàng T46; xem CLAUDE.md "Rebases have committed conflict markers"). **Đã dọn dấu conflict trong `docs/time.md` (hai chỗ) và `docs/architecture/system-overview.md` (một chỗ, lồng với dấu của stash)**; hàng T46 giữ bản "DONE (mock)" của FE, bỏ bản "IN_PROGRESS" cũ; số liệu "Giai đoạn 1" là **37/51 DONE · 6 IN_PROGRESS · 8 NOT_STARTED** (BE-1 11 / 2 / 3, FE-1 14 / 3 / 0). `CLAUDE.md` tự gộp sạch. Còn nợ của BE-1: trạng thái T38 trên `origin/dev` ghi "DONE (chưa commit)".

## F. Kiểm chứng

Chạy từ `FE/` sau mọi thay đổi code:
- `npx tsc --noEmit`: sạch.
- `npm run lint` (`--max-warnings 0`): sạch.
- `npx vitest run`: **120 file, 2196 test xanh**, thoát mã 0 ở **hai lần chạy liên tiếp** sau khi sửa lỗi interval của GSAP (một lần chạy sạch không đủ chứng minh vì lỗi chập chờn, xem mục (5) bên dưới); không "Unhandled Errors" (nền trước T47: 103 file, 1441 test, đã gồm T46). Riêng các file T47 chạm tới: 22 file, 850 ca (`care.mock` 71, `AdminCareTasksPage` 27; `content.mock` 142, `content-html` 143, `AdminArticlesPage` 47, `slug` 26, `preview` 8, `useArticles` 7; `page-content.mock` 42, `page-content` 18, `usePageContent` 14, `AdminPageContentsPage` 46, `validation` 11; `feedback.mock` 94, `ReviewsPage` 19, `AdminFeedbacksPage` 26; `HomePage` 18, `AboutPage` 13, `LegalPage` 9, `HeroBanner` 8; `App.routes` 51, `staff-menu` 10; một phần là ca có sẵn từ trước).
- `npm run build`: thành công.
- Trình duyệt thật (Playwright, Chromium, dev server với mock), đo `scrollWidth` và console (do agent QA chạy, đã xem ảnh; **tôi chưa tự xem lại từng ảnh**): `/about`, `/terms`, `/privacy` (mặc định và đã sửa qua `petcare-mock-pages`), `/admin/care-tasks` (luồng Đã gọi / Hủy / tab Đã xong, Đã hủy ở 1280 / 390 / 360), `/admin/articles/articles` và `/categories`, `/admin/feedbacks` (SUPER_MANAGER và BRANCH_MANAGER), `/review`, `/admin/page-contents` (1280, 390, 360): không cuộn ngang, không lỗi console. **Hai kiểm tra liên trang:** đăng bài ở admin thì `/news` có bài, Ẩn thì biến mất; sửa ABOUT ở giao diện quản trị rồi `/about` hiện chữ mới (và chữ mặc định chưa từng lọt vào DOM). Trang chủ: mặc định, nội dung đã sửa, request 500, trả chậm 2,5 s: 0 nháy, không kẹt `opacity-0`, pin ScrollTrigger lệch 0.
- Lỗi phát hiện nhờ xem trình duyệt và đã sửa: chuỗi dài không dấu cách làm About / Điều khoản tràn ngang ở 390 px (`wrap-break-word` → `wrap-anywhere`); nút "Đánh dấu đã xử lý" bị cắt đáy hộp thoại ở 1280×800; ô slug khóa trông y hệt ô sửa được; padding / màu của hàng rỗng bảng bài viết, ô ngày và nội dung ở trang feedback, tiêu đề "Thao tác" của Care Task không có hiệu lực. **Cái bẫy chung:** các rule ngoài `@layer` (`.table td`, `.input`) thắng utility Tailwind v4 nên cần hậu tố `!` (xem CLAUDE.md).
- **Va chạm công cụ cần nhớ:** (1) máy không có Python, script đột biến phải bằng node và kiểm "đã áp dụng" (đếm khớp = 1); `/tmp` của Git Bash khác `C:\tmp` đối với node. (2) Chạy nhiều script đột biến / vitest song song gây lỗi hạ tầng `0xC0000142` (hết tài nguyên): chạy tuần tự. (3) Script đột biến khôi phục file từ bản sao lấy lúc chạm đầu tiên, nên mọi chỉnh sửa của người khác chen vào giữa bị **ghi đè âm thầm** (đã xảy ra một lần với ô slug khóa, phát hiện và làm lại). (4) Công cụ ghi file tự đổi chuỗi `\uXXXX` trong mã thành ký tự thật. (5) **Vitest thoát mã 1 dù mọi ca xanh, chập chờn (~1 trong 4 lần chạy cả bộ):** `gsap.registerPlugin(ScrollTrigger)` chạy ở mức module của `HeroBanner`, `HomePage`, `OurServicesStage`, `StackingProcessCards` nên mọi test nạp `<App />` bật `setInterval` 250 ms của GSAP; interval sống qua lúc jsdom bị hủy và ném "requestAnimationFrame is not defined". **`ScrollTrigger.killAll()` không dừng interval** (chỉ `disable()`), nên bản sửa `killAll` ở T46 là sai và bị gỡ. Sửa đúng: `src/test/intervals.ts` (theo dõi và dọn mọi `setInterval` của file test) + `setup.ts` + `src/test/intervals.test.ts` (3 ca, gồm ca chứng minh interval của ScrollTrigger bật ngay khi `registerPlugin` và bị dừng). Tái hiện: tập con 6 file (`App.shell`, `App.theme`, `StaffNotificationsPage`, `HeroBanner`, `HomePage`, `App.routes`) chạy nối tiếp (`--no-file-parallelism`) thoát mã 1 ở 1/6 lần trước khi sửa.
