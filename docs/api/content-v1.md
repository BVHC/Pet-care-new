# Content & Feedback API v1 — Pet Care v16

> **Module:** CK (nội dung trang), BV, DG (tầng 2) · **Owner:** BE-2. Nội dung trang tĩnh (banner, giới thiệu, chính sách, FAQ), chuyên mục và bài viết, feedback của khách.
> **Nguồn chân lý:** 01 UC17, UC21, UC81, UC83, UC84; 02 BR-CK-01, BR-BV-01…04, BR-DG-01…04, BR-TK-19; 04 §10; 05 §10 (page_contents, article_categories, articles, feedbacks).
> **Contract máy đọc:** [`./openapi/content-v1.yaml`](./openapi/content-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC80 (VET gửi duyệt bài), UC82 (bình luận) và phần 'duyệt, ẩn bình luận' của UC81 là tầng 3.
- UC85, UC86 khiếu nại là tầng 3.
- Dịch vụ, sản phẩm, chi nhánh, bác sĩ công khai nằm ở catalog, branch, identity (`/public/...`).

---

## A. Danh sách endpoint (22)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `GET /page-contents` | Danh sách nội dung trang | UC21 | — |
| 2 | `PUT /page-contents/{key}` | Sửa nội dung trang | UC21 | — |
| 3 | `GET /article-categories` | Danh sách chuyên mục | UC81 | BR-BV-04 |
| 4 | `POST /article-categories` | Tạo chuyên mục | UC81 | BR-BV-04 |
| 5 | `PATCH /article-categories/{categoryId}` | Sửa / ẩn chuyên mục | UC81 | BR-BV-04 |
| 6 | `DELETE /article-categories/{categoryId}` | Xóa chuyên mục rỗng | UC81 | BR-BV-04 |
| 7 | `GET /articles` | Danh sách bài viết | UC81 | BR-BV-02 |
| 8 | `POST /articles` | Soạn bài viết | UC81 | BR-BV-01 |
| 9 | `GET /articles/{articleId}` | Chi tiết bài viết | UC81 | — |
| 10 | `PATCH /articles/{articleId}` | Sửa bài viết | UC81 | BR-BV-01 |
| 11 | `POST /articles/{articleId}/publish` | Xuất bản / hiện lại bài | UC81 | BR-BV-02 |
| 12 | `POST /articles/{articleId}/hide` | Gỡ (ẩn) bài | UC81 | BR-BV-02 |
| 13 | `DELETE /articles/{articleId}` | Xóa bài chưa từng xuất bản | UC81 | BR-BV-02 |
| 14 | `GET /public/pages/{key}` | Nội dung trang công khai | UC15–UC17 | — |
| 15 | `GET /public/article-categories` | Chuyên mục công khai | UC17 | BR-BV-03, 04 |
| 16 | `GET /public/articles` | Danh sách bài viết | UC17 | BR-BV-03, BR-CK-01 |
| 17 | `GET /public/articles/{slug}` | Đọc bài viết | UC17 | BR-BV-03 |
| 18 | `POST /me/feedbacks` | Gửi feedback | UC83 | BR-DG-01, 02, BR-TK-19 |
| 19 | `GET /me/feedbacks` | Feedback tôi đã gửi | UC83 | BR-DG-02, 04 |
| 20 | `GET /feedbacks` | Danh sách feedback | UC84 | BR-DG-02, 03 |
| 21 | `GET /feedbacks/{feedbackId}` | Xem feedback | UC84 | BR-DG-03, 04 |
| 22 | `POST /feedbacks/{feedbackId}/resolve` | Đánh dấu đã xử lý | UC84 | BR-DG-04 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Danh sách nội dung trang | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa nội dung trang | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Danh sách chuyên mục | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Tạo chuyên mục | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa / ẩn chuyên mục | A04 | Chỉ SUPER_MANAGER | — | — | Chuyên mục ẩn không hiện ở bộ lọc công khai; bài trong đó vẫn truy cập được theo đường dẫn. |
| Xóa chuyên mục rỗng | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Danh sách bài viết | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Soạn bài viết | A04 | Chỉ SUPER_MANAGER | — → DRAFT | — | — |
| Chi tiết bài viết | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa bài viết | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Xuất bản / hiện lại bài | A04 | Chỉ SUPER_MANAGER | DRAFT / HIDDEN → PUBLISHED | — | Lần xuất bản đầu ghi firstPublishedAt và khóa slug. |
| Gỡ (ẩn) bài | A04 | Chỉ SUPER_MANAGER | PUBLISHED → HIDDEN | — | — |
| Xóa bài chưa từng xuất bản | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Nội dung trang công khai | A01, A02 | Public | — | — | — |
| Chuyên mục công khai | A01, A02 | Public | — | — | Bỏ chuyên mục ẩn. |
| Danh sách bài viết | A01, A02 | Public | — | — | Chỉ PUBLISHED, mới nhất trước. |
| Đọc bài viết | A01, A02 | Public | — | — | — |
| Gửi feedback | A02 | Chỉ CUSTOMER | — → NEW | — | — |
| Feedback tôi đã gửi | A02 | Chỉ CUSTOMER | — | — | Không sửa, không xóa. |
| Danh sách feedback | A04, A05 | SUPER_MANAGER: tất cả · BRANCH_MANAGER: chi nhánh mình | — | — | — |
| Xem feedback | A04, A05 | Như listFeedbacks | NEW → SEEN khi mở lần đầu (A2) | — | — |
| Đánh dấu đã xử lý | A04, A05 | Như listFeedbacks | SEEN → RESOLVED | — | — |

---

## C. Chi tiết endpoint

### Pages

- **`GET /page-contents`** — Danh sách nội dung trang. Response `200` mảng `PageContent`. Lỗi: `401` · `403`.
- **`PUT /page-contents/{key}`** — Sửa nội dung trang. Request `UpdatePageContentRequest`. Response `200` `PageContent`. Lỗi: `400` · `401` · `403` · `404`.
### Article categories

- **`GET /article-categories`** — Danh sách chuyên mục. Response `200` mảng `ArticleCategory`. Lỗi: `401` · `403`.
- **`POST /article-categories`** — Tạo chuyên mục. Request `ArticleCategoryRequest`. Response `201` `ArticleCategory`. Lỗi: `400` Tên hoặc slug trùng · `401` · `403`.
- **`PATCH /article-categories/{categoryId}`** — Sửa / ẩn chuyên mục. Request `UpdateArticleCategoryRequest`. Response `200` `ArticleCategory`. Lỗi: `400` · `401` · `403` · `404`.
- **`DELETE /article-categories/{categoryId}`** — Xóa chuyên mục rỗng. Response `204` rỗng. Lỗi: `400` `BR-BV-04` còn bài viết (message ghi số bài) — chỉ ẩn · `401` · `403` · `404`.
### Articles

- **`GET /articles`** — Danh sách bài viết. Query: `status?`, `categoryId?`, `q?`, `page?`, `size?`. Response `200` trang `ArticleSummary`. Lỗi: `400` · `401` · `403`.
- **`POST /articles`** — Soạn bài viết. Request `CreateArticleRequest`. Response `201` `Article`. Lỗi: `400` `BR-BV-01` slug trùng, thiếu tiêu đề / chuyên mục / nội dung · `401` · `403`.
- **`GET /articles/{articleId}`** — Chi tiết bài viết. Response `200` `Article`. Lỗi: `401` · `403` · `404`.
- **`PATCH /articles/{articleId}`** — Sửa bài viết. Request `UpdateArticleRequest`. Response `200` `Article`. Lỗi: `400` `BR-BV-01` đổi slug sau lần xuất bản đầu · slug trùng · `401` · `403` · `404`.
- **`POST /articles/{articleId}/publish`** — Xuất bản / hiện lại bài. Response `200` `Article`. Lỗi: `401` · `403` · `404` · `409` Bài đang PUBLISHED.
- **`POST /articles/{articleId}/hide`** — Gỡ (ẩn) bài. Response `200` `Article`. Lỗi: `401` · `403` · `404` · `409` Bài không PUBLISHED.
- **`DELETE /articles/{articleId}`** — Xóa bài chưa từng xuất bản. Response `204` rỗng. Lỗi: `400` `BR-BV-02` bài đã từng xuất bản — chỉ ẩn · `401` · `403` · `404`.
### Public

- **`GET /public/pages/{key}`** — Nội dung trang công khai. Public. Response `200` `PageContent`. Lỗi: `404`.
- **`GET /public/article-categories`** — Chuyên mục công khai. Public. Response `200` mảng `PublicArticleCategory`. Lỗi: `400`.
- **`GET /public/articles`** — Danh sách bài viết. Public. Query: `categorySlug?`, `page?`, `size?`. Response `200` trang `PublicArticleCard`. Lỗi: `400`.
- **`GET /public/articles/{slug}`** — Đọc bài viết. Public. Response `200` `PublicArticle`. Lỗi: `404` Không có, hoặc bài DRAFT / HIDDEN (BR-BV-03).
### Feedback

- **`POST /me/feedbacks`** — Gửi feedback. Request `CreateFeedbackRequest`. Response `201` `MyFeedback`. Lỗi: `400` `BR-DG-01` nội dung ngoài 10–2000 ký tự, thiếu chi nhánh khi BRANCH_SERVICE, điểm ngoài 1–5, vượt 5 feedback / ngày [CFG] (message ghi thời điểm gửi tiếp) · `BR-TK-19` hồ sơ còn chờ quyết định liên kết · `401` · `403`.
- **`GET /me/feedbacks`** — Feedback tôi đã gửi. Response `200` mảng `MyFeedback`. Lỗi: `401` · `403`.
- **`GET /feedbacks`** — Danh sách feedback. Query: `branchId?`, `status?`, `topic?`, `from?`, `to?`, `page?`, `size?`. Response `200` trang `Feedback`. Lỗi: `400` · `401` · `403`.
- **`GET /feedbacks/{feedbackId}`** — Xem feedback. Response `200` `Feedback`. Lỗi: `401` · `403` · `404`.
- **`POST /feedbacks/{feedbackId}/resolve`** — Đánh dấu đã xử lý. Request `ResolveFeedbackRequest`. Response `200` `Feedback`. Lỗi: `400` `BR-DG-04` thiếu ghi chú · `401` · `403` · `404` · `409` Đã RESOLVED.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`PageContent`** — {`key`: HOME_BANNER|ABOUT|POLICY|FAQ, `title?`: string, `content`: object, `updatedAt`: date-time}
- **`UpdatePageContentRequest`** — {`title?`: string, `content`: object}
- **`ArticleCategory`** — {`categoryId`: int64, `name`: string, `slug`: string, `isHidden`: boolean, `articleCount`: int32}
- **`ArticleCategoryRequest`** — {`name`: string, `slug`: string}
- **`UpdateArticleCategoryRequest`** — {`name?`: string, `isHidden?`: boolean}
- **`PublicArticleCategory`** — {`categoryId`: int64, `name`: string, `slug`: string}
- **`Article`** — {`articleId`: int64, `categoryId`: int64, `title`: string, `slug`: string, `coverImageUrl?`: string, `summary?`: string, `content`: string, `authorDisplayName`: string, `status`: DRAFT|PUBLISHED|HIDDEN, `firstPublishedAt?`: date-time, `createdAt`: date-time, `updatedAt`: date-time}
- **`ArticleSummary`** — {`articleId`: int64, `categoryId`: int64, `title`: string, `slug`: string, `status`: DRAFT|PUBLISHED|HIDDEN, `firstPublishedAt?`: date-time, `updatedAt`: date-time}
- **`CreateArticleRequest`** — {`categoryId`: int64, `title`: string, `slug`: string, `coverImageUrl?`: string, `summary?`: string, `content`: string, `authorDisplayName`: string}
- **`UpdateArticleRequest`** — {`categoryId?`: int64, `title?`: string, `slug?`: string, `coverImageUrl?`: string, `summary?`: string, `content?`: string, `authorDisplayName?`: string}
- **`PublicArticleCard`** — {`articleId`: int64, `title`: string, `slug`: string, `categoryName`: string, `categorySlug`: string, `coverImageUrl?`: string, `summary?`: string, `authorDisplayName`: string, `publishedAt`: date-time}
- **`PublicArticle`** — {`articleId`: int64, `title`: string, `slug`: string, `categoryName`: string, `categorySlug`: string, `coverImageUrl?`: string, `summary?`: string, `content`: string, `authorDisplayName`: string, `publishedAt`: date-time}
- **`CreateFeedbackRequest`** — {`topic`: BRANCH_SERVICE|WEBSITE|OTHER, `branchId?`: int64, `rating?`: int32, `content`: string}
- **`MyFeedback`** — {`feedbackId`: int64, `topic`: BRANCH_SERVICE|WEBSITE|OTHER, `branchId?`: int64, `rating?`: int32, `content`: string, `status`: NEW|SEEN|RESOLVED, `createdAt`: date-time}
- **`Feedback`** — {`feedbackId`: int64, `customerId`: int64, `customerName`: string, `topic`: BRANCH_SERVICE|WEBSITE|OTHER, `branchId?`: int64, `branchName?`: string, `rating?`: int32, `content`: string, `status`: NEW|SEEN|RESOLVED, `seenAt?`: date-time, `resolvedBy?`: int64, `resolvedAt?`: date-time, `resolutionNote?`: string, `createdAt`: date-time}
- **`ResolveFeedbackRequest`** — {`resolutionNote`: string}

---

## D. Bảo mật & độ tin cậy

1. Soạn và đăng bài, chuyên mục, nội dung trang: chỉ SUPER_MANAGER.
2. Bài DRAFT / HIDDEN truy cập từ trang công khai trả 404 (BR-BV-03). Nội dung bài lưu HTML đã lọc (05 `articles.content`).
3. Feedback không công khai: chỉ khách gửi xem lại (không thấy ghi chú nội bộ), SUPER_MANAGER xem tất cả, BRANCH_MANAGER chỉ feedback gắn chi nhánh mình; ngoài phạm vi trả 403 (BR-DG-02, 03).
4. Giới hạn 5 feedback / khách / ngày [CFG] kiểm ở server (BR-DG-01).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Slug chuyên mục không đổi sau khi tạo | Không có rule; slug dùng trong URL công khai như BR-BV-01 |
| A2 | Mở chi tiết feedback lần đầu bởi người có quyền tự chuyển NEW → SEEN trong cùng request GET | BR-DG-04 'tự chuyển khi người có quyền mở lần đầu' |
| A3 | `content` của trang là JSON tự do theo từng key (danh sách banner, câu hỏi FAQ…) | 05 `page_contents.content` JSONB, cấu trúc tùy trang |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Cấu trúc JSON cụ thể của từng trang (HOME_BANNER, ABOUT, POLICY, FAQ) — FE và BE chốt khi làm UC21. | TBD (FE + BE) | 05 `page_contents` |

---

## F. OpenAPI 3.1

[`./openapi/content-v1.yaml`](./openapi/content-v1.yaml)
