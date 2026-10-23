# ADR-0071: Thông tin công khai, bài viết, nội dung trang, feedback (T32)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23
- **Liên quan:**
  - branch-v1 #20–21, catalog-v1 #19–22, content-v1 #1–22 (UC15–17, UC21, UC81, UC83, UC84); 02 BR-CK-01…03, BR-BV-01…04, BR-DG-01…04, BR-SP-01, BR-KO-01, BR-TK-19; 05 §10
  - ADR-0004 ([CFG]), 0005 (path public ẩn danh), 0015 (Clock), 0020 (OSIV off), 0040 (Swagger = contract), 0045 (namespace advisory), 0057 (cấu hình áp dụng từ lần kiểm sau)
  - Kế hoạch: `docs/plan/T32-public-content-feedback.md` (D1–D6, E1–E24, A1–A6)
- **Triển khai:**
  - `branch/{dto/PublicBranchResponse, service/PublicBranchService, controller/PublicBranchController}`; `BranchQueryApi.findActiveBranchIdsEnablingServices` (theo lô).
  - `catalog/{dto/PublicServiceResponse, dto/PublicProductResponse, service/PublicCatalogService, controller/PublicCatalogController}`; `StockQueryApi.branchIdsWithAvailableStock(Collection)`, `productIdsWithAvailableStock(branchId)`.
  - `content/{entity, repository, dto, service, controller, exception}`: `PageContentService`, `ArticleCategoryService`, `ArticleService`, `PublicArticleService`, `FeedbackSubmitService`, `FeedbackReviewService`, `ArticleHtml` (jsoup), `ContentText`.
  - `V21__seed_page_contents.sql` (4 trang tĩnh), phụ thuộc mới `org.jsoup:jsoup:1.18.3`.
  - Test: xem *Kiểm chứng*.

## Bối cảnh

Trang công khai (UC15–17), quản lý nội dung trang / chuyên mục / bài viết (UC21, UC81) và feedback (UC83, UC84) là tầng 1–2 còn trống; schema đã có từ V1 (`page_contents`, `article_categories`, `articles`, `feedbacks`). Các endpoint công khai không cần đăng nhập nên mọi truy vấn phải bị chặn về phạm vi hiển thị (BR-CK-01) và về độ lớn.

## Quyết định

### 1. Phạm vi hiển thị công khai (BR-CK-01, 02, 03, BR-SP-01)

- Dịch vụ: `is_active` và bật ở ít nhất một chi nhánh ACTIVE; `branchIds` là mọi chi nhánh ACTIVE bật dịch vụ; loại chuồng kèm `kennelType`. Giá "Từ X đ" theo `price_is_from`.
- Sản phẩm: `is_active`, không phải thuốc kê đơn, danh mục đang dùng (A1). `inStockBranchIds` = chi nhánh ACTIVE còn tồn khả dụng (lô `quantity > 0`, chưa hết hạn hôm nay); **không bao giờ trả số lượng**. `branchId` không ACTIVE → trang rỗng. Chi tiết sản phẩm ẩn → 404.
- `weeklyHours` công khai = bản giờ mở cửa có `effective_from` lớn nhất ≤ hôm nay (không hiện bản tương lai); thứ không có khoảng = ngày nghỉ cố định (A4).
- Đọc theo lô: một câu SQL mỗi nguồn cho cả trang (thêm 3 method vào `BranchQueryApi` / `StockQueryApi`, ghi ở 06 §6). `page` / `size` ngoài khoảng được kẹp như danh sách nhân viên; `q` tối đa 100 ký tự (400), `LIKE` thoát `%` `_` `\`.

### 2. Bài viết và chuyên mục (BR-BV-01…04)

- Vòng đời chỉ có rule: `DRAFT → PUBLISHED ⇄ HIDDEN`, sai trạng thái = `InvalidStateTransitionException("Article", …)` (409). Ghi trạng thái dưới `findByIdForUpdate`; `first_published_at` chỉ ghi lần đầu và từ đó khóa slug / không xóa được.
- Slug `^[a-z0-9]+(?:-[a-z0-9]+)*$` (A2); gửi lại đúng slug hiện tại sau khi xuất bản là hợp lệ (không phải đổi). Trùng slug bài / đổi slug sau xuất bản = 400 `BR-BV-01`; trùng tên / slug chuyên mục = 400 `VALIDATION_FAILED` (hợp đồng không gắn mã rule cho chuyên mục).
- **Lọc HTML khi lưu** (`ArticleHtml`, jsoup `Safelist.relaxed()`, `a[href]` và `img[src]` chỉ `https`): bài hiển thị cho người chưa đăng nhập nên đây là chốt chặn stored XSS. Nội dung rỗng sau lọc = 400 `BR-BV-01`. `page_contents.content` là JSON tự do (Q1 của contract): FE không được render HTML thô từ đó.
- Bài DRAFT / HIDDEN / slug lạ cùng một 404; chuyên mục ẩn chỉ biến khỏi bộ lọc công khai, bài trong đó vẫn đọc được và vẫn lọc được bằng `categorySlug` (D6).
- **Thứ tự khóa** `article_categories → articles`: tạo / chuyển bài khóa chuyên mục đích `FOR SHARE` (khóa FK `FOR KEY SHARE` không xung đột với `FOR NO KEY UPDATE`, nên phải tường minh); xóa chuyên mục khóa `FOR NO KEY UPDATE` rồi mới đếm bài. Bên thua nhận 404 hoặc 400 `BR-BV-04`, không bao giờ để bài mồ côi.

### 3. Feedback (BR-DG-01…04, BR-TK-19)

- Gửi: hồ sơ khách → BR-TK-19 → nội dung NFC + strip, độ dài **theo code point** 10–2000 (khớp `char_length` của CHECK; emoji là 1) → điểm 1–5 → chi nhánh (bắt buộc khi `BRANCH_SERVICE`; không có / DRAFT = 404) → **advisory lock `(1003, customerId % Integer.MAX_VALUE)`** → đếm trong ngày lịch giờ Việt Nam (D5) so với `feedback.max_per_day` đọc lúc kiểm (ADR-0057) → INSERT. Namespace **1003** thêm vào bảng đăng ký advisory (cùng họ 1001 thú / 1002 khách). Transaction giữ READ COMMITTED để lần đếm sau khóa thấy dòng vừa commit.
- Xem / xử lý: SUPER_MANAGER thấy tất cả; BRANCH_MANAGER chỉ feedback chi nhánh mình (feedback không gắn chi nhánh chỉ SUPER_MANAGER) — ngoài phạm vi là 403 `ACCESS_DENIED_SCOPE_MISMATCH`. Phân quyền đọc `(id, branch_id)` bằng projection **hai cột** (một cột NULL duy nhất khiến Spring Data trả rỗng → 404 thay vì 403).
- `GET /feedbacks/{id}` ghi (tx không `readOnly`): `UPDATE … SET status='SEEN', seen_at=:now WHERE id=:id AND status='NEW'` — hai người mở cùng lúc, `seen_at` ghi một lần; mở lại không ghi gì.
- `resolve` chỉ từ `SEEN` (D4): feedback còn `NEW` hoặc đã `RESOLVED` = 409; ghi chú sau strip rỗng = 400 `BR-DG-04`. Thứ tự lỗi 403/404 → 400 → 409. Khách chỉ thấy trạng thái, không bao giờ thấy ghi chú nội bộ.

### 4. Giả định

| ID | Giả định |
|---|---|
| A1 | Danh mục sản phẩm ngừng → sản phẩm thuộc nó không hiện công khai |
| A2 | Slug bài / chuyên mục: chữ thường, số, gạch ngang đơn; slug chuyên mục không đổi |
| A3 | `q` ≤ 100; ghi chú xử lý ≤ 2000; nội dung trang là JSON object ≤ 64 KB |
| A4 | `weeklyHours` công khai = bản đang áp dụng hôm nay |
| A5 | Sắp xếp: bài công khai `first_published_at DESC`; bài quản trị `updated_at DESC`; feedback `created_at DESC`; còn lại theo tên |
| A6 | `GET /me/feedbacks` không phân trang (≤ 5/ngày theo [CFG]) |

## Kiểm chứng

- Unit: `ArticleHtmlTest`, `ContentTextTest`.
- IT (Postgres 17 thật, era năm 2004): `ContentSchemaIT`, `PageContentIT`, `ArticleCategoryIT`, `ArticleIT`, `PublicArticleIT`, `MyFeedbackIT`, `FeedbackReviewIT`, `PublicBranchIT`, `PublicCatalogIT`; bổ sung `BranchSettingsIT`, `InventoryViewIT` (method theo lô); `OpenApiContractParityIT` (thêm `content-v1.yaml`, 153 endpoint).
- **Đột biến** (mỗi lần đổi một dòng, test phải đỏ, rồi hoàn lại): bỏ `lockForUpdate` ở xóa chuyên mục → `deleteCategoryRacingArticleCreateNeverOrphans` đỏ (409 thay vì 400 `BR-BV-04`); bỏ advisory 1003 → `concurrentSubmitAtLimitOnlyOneWins` đỏ (cả hai request cùng qua).

## Nợ để lại

- API công khai chưa có rate limit / cache HTTP (hạ tầng); `size ≤ 100`, `q ≤ 100` là chặn duy nhất.
- `GET /public/products?branchId` dùng `id IN (…)` theo tập sản phẩm còn hàng; ổn với danh mục cỡ chuỗi phòng khám, cần bảng tồn tổng hợp nếu danh mục lên hàng chục nghìn.
- Cấu trúc JSON từng trang tĩnh (Q1) chờ chốt với FE; ảnh bìa chỉ nhận URL (chưa có upload).
- Swagger của branch / catalog vẫn nợ D026 (3 endpoint public mới của hai module này có `@Operation`, DTO chưa có `@Schema`).
- `FeedbackReportApi` (báo cáo feedback theo chi nhánh) để T44.
