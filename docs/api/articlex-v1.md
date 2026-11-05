# Doctor Articles & Comments API v1 (tầng 3) — Pet Care v16

> **Module:** BV (bài viết của bác sĩ, bình luận) · **Owner:** BE-2. Bác sĩ soạn và gửi duyệt bài, SUPER_MANAGER duyệt hoặc từ chối, khách bình luận bài viết. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 01 UC80, UC81 (mở rộng), UC82; 08-tang3-be2-spec.md §F.3 (BR-BV-05…08; 03 #21 Bài viết mở rộng); 05 bổ sung articles.author_account_id…, article_comments.
> **Contract máy đọc:** [`./openapi/articlex-v1.yaml`](./openapi/articlex-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- Danh sách bài chờ duyệt dùng `GET /articles?status=PENDING_REVIEW` của content-v1 (mở rộng giá trị `status`), không có endpoint riêng.
- Bài do SUPER_MANAGER tự soạn và xuất bản giữ luồng của content-v1 (`POST /articles`, `…/publish`, `…/hide`).
- Ẩn bình luận là UC81 (A04) nên nằm ở đây cùng UC82; không có endpoint xóa cứng bình luận.

---

## A. Danh sách endpoint (14)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /me/articles` | Soạn bài viết (nháp) | UC80 | BR-BV-05 |
| 2 | `GET /me/articles` | Bài viết của tôi | UC80 | BR-BV-05 |
| 3 | `GET /me/articles/{articleId}` | Chi tiết bài viết của tôi | UC80 | BR-BV-05 |
| 4 | `PATCH /me/articles/{articleId}` | Sửa bài viết của tôi | UC80 | BR-BV-05, 06, 07 |
| 5 | `POST /me/articles/{articleId}/submit` | Gửi duyệt | UC80 | BR-BV-06 |
| 6 | `POST /me/articles/{articleId}/withdraw` | Rút bài đang chờ duyệt | UC80 | BR-BV-06 |
| 7 | `POST /articles/{articleId}/approve` | Duyệt bài của bác sĩ | UC81 | BR-BV-06, BR-BV-02 |
| 8 | `POST /articles/{articleId}/reject` | Từ chối bài của bác sĩ | UC81 | BR-BV-06 |
| 9 | `GET /public/articles/{slug}/comments` | Bình luận của một bài viết | UC82 | BR-BV-03, 08 |
| 10 | `POST /me/articles/{articleId}/comments` | Đăng bình luận | UC82 | BR-BV-08 |
| 11 | `PATCH /me/comments/{commentId}` | Sửa bình luận của tôi | UC82 | BR-BV-08 |
| 12 | `DELETE /me/comments/{commentId}` | Xóa bình luận của tôi | UC82 | BR-BV-08 |
| 13 | `POST /comments/{commentId}/hide` | Ẩn bình luận | UC81 | BR-BV-08 |
| 14 | `POST /comments/{commentId}/unhide` | Bỏ ẩn bình luận | UC81 | BR-BV-08 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Soạn bài viết (nháp) | A07 | VET, chỉ bài của mình | — → DRAFT (Bài viết#1) | — | — |
| Bài viết của tôi | A07 | VET, chỉ bài của mình | — | — | Mới sửa trước (`updatedAt` giảm dần). |
| Chi tiết bài viết của tôi | A07 | VET, chỉ bài của mình | — | — | — |
| Sửa bài viết của tôi | A07 | VET, chỉ bài của mình | `REJECTED` → DRAFT khi sửa (Bài viết#6) | — | Chỉ sửa được khi `DRAFT` hoặc `REJECTED`. |
| Gửi duyệt | A07 | VET, chỉ bài của mình | DRAFT → PENDING_REVIEW (Bài viết#2) | — | — |
| Rút bài đang chờ duyệt | A07 | VET, chỉ bài của mình | PENDING_REVIEW → DRAFT (Bài viết#3) | — | — |
| Duyệt bài của bác sĩ | A04 | Chỉ SUPER_MANAGER | PENDING_REVIEW → PUBLISHED (Bài viết#4) | — | Ghi `published_at` ở lần xuất bản đầu (BR-BV-02); thông báo VET; ghi audit. |
| Từ chối bài của bác sĩ | A04 | Chỉ SUPER_MANAGER | PENDING_REVIEW → REJECTED (Bài viết#5) | — | — |
| Bình luận của một bài viết | A01, A02 | Công khai | — | — | Chỉ bình luận `ACTIVE`, cũ nhất trước. |
| Đăng bình luận | A02 | CUSTOMER | — | — | — |
| Sửa bình luận của tôi | A02 | CUSTOMER, chỉ bình luận của mình | — | — | — |
| Xóa bình luận của tôi | A02 | CUSTOMER, chỉ bình luận của mình | — | Có | Xóa mềm (`DELETED`). |
| Ẩn bình luận | A04 | Chỉ SUPER_MANAGER | ACTIVE → HIDDEN | — | — |
| Bỏ ẩn bình luận | A04 | Chỉ SUPER_MANAGER | HIDDEN → ACTIVE | — | — |

---

## C. Chi tiết endpoint

### My articles

- **`POST /me/articles`** — Soạn bài viết (nháp). Request `CreateMyArticleRequest`. Response `201` `MyArticle`. Lỗi: `400` `BR-BV-01` tiêu đề rỗng / quá dài · chuyên mục không tồn tại · `401` · `403`.
- **`GET /me/articles`** — Bài viết của tôi. Query: `status?`, `page?`, `size?`. Response `200` trang `MyArticleSummary`. Lỗi: `400` · `401` · `403`.
- **`GET /me/articles/{articleId}`** — Chi tiết bài viết của tôi. Response `200` `MyArticle`. Lỗi: `400` · `401` · `403` · `404`.
- **`PATCH /me/articles/{articleId}`** — Sửa bài viết của tôi. Request `UpdateMyArticleRequest`. Response `200` `MyArticle`. Lỗi: `400` · `401` · `403` · `404` · `409` `BR-BV-07` bài đang `PENDING_REVIEW` (rút về nháp trước) hoặc đã `PUBLISHED` / `HIDDEN`.
- **`POST /me/articles/{articleId}/submit`** — Gửi duyệt. Response `200` `MyArticle`. Lỗi: `400` `BR-BV-01` thiếu trường bắt buộc · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái bài không cho phép (03 #21).
- **`POST /me/articles/{articleId}/withdraw`** — Rút bài đang chờ duyệt. Response `200` `MyArticle`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái bài không cho phép (03 #21).
### Article review

- **`POST /articles/{articleId}/approve`** — Duyệt bài của bác sĩ. Response `200` `MyArticle`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái bài không cho phép (03 #21).
- **`POST /articles/{articleId}/reject`** — Từ chối bài của bác sĩ. Request `ReviewNoteRequest`. Response `200` `MyArticle`. Lỗi: `400` `BR-BV-06` thiếu lý do · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái bài không cho phép (03 #21).
### Comments

- **`GET /public/articles/{slug}/comments`** — Bình luận của một bài viết. Public. Query: `page?`, `size?`. Response `200` trang `PublicComment`. Lỗi: `400` · `404` Bài không tồn tại hoặc chưa `PUBLISHED`.
- **`POST /me/articles/{articleId}/comments`** — Đăng bình luận. Request `CreateCommentRequest`. Response `201` `Comment`. Lỗi: `400` `BR-BV-08` nội dung rỗng / quá 1 000 ký tự · `401` · `403` · `404` Bài không tồn tại hoặc chưa `PUBLISHED` · `429` `TOO_MANY_REQUESTS` quá 10 bình luận / giờ [CFG] (BR-BV-08).
- **`PATCH /me/comments/{commentId}`** — Sửa bình luận của tôi. Request `UpdateCommentRequest`. Response `200` `Comment`. Lỗi: `400` · `401` · `403` · `404` · `409` Bình luận đã bị ẩn hoặc đã xóa.
- **`DELETE /me/comments/{commentId}`** — Xóa bình luận của tôi. Response `204` rỗng. Lỗi: `400` · `401` · `403` · `404`.
- **`POST /comments/{commentId}/hide`** — Ẩn bình luận. Request `HideCommentRequest`. Response `200` `Comment`. Lỗi: `400` `BR-BV-08` thiếu lý do · `401` · `403` · `404` · `409` Bình luận không `ACTIVE`.
- **`POST /comments/{commentId}/unhide`** — Bỏ ẩn bình luận. Response `200` `Comment`. Lỗi: `400` · `401` · `403` · `404` · `409` Bình luận không `HIDDEN`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ArticleReviewStatus`** — enum: `DRAFT`, `PENDING_REVIEW`, `REJECTED`, `PUBLISHED`, `HIDDEN`. BR-BV-02 và 03 #21
- **`CreateMyArticleRequest`** — {`title`: string, `categoryId?`: int64, `summary?`: string, `content`: string, `coverImageUrl?`: string}
- **`UpdateMyArticleRequest`** — {`title?`: string, `categoryId?`: int64, `summary?`: string, `content?`: string, `coverImageUrl?`: string}
- **`MyArticleSummary`** — {`articleId`: int64, `title`: string, `status`: ArticleReviewStatus, `submittedAt?`: date-time, `publishedAt?`: date-time, `updatedAt`: date-time}
- **`MyArticle`** — {`articleId`: int64, `title`: string, `slug?`: string, `categoryId?`: int64, `summary?`: string, `content`: string, `coverImageUrl?`: string, `status`: ArticleReviewStatus, `reviewNote?`: string, `submittedAt?`: date-time, `publishedAt?`: date-time, `updatedAt`: date-time}
- **`ReviewNoteRequest`** — {`reviewNote`: string}
- **`CommentStatus`** — enum: `ACTIVE`, `HIDDEN`, `DELETED`.
- **`CreateCommentRequest`** — {`content`: string}
- **`UpdateCommentRequest`** — {`content`: string}
- **`HideCommentRequest`** — {`reason`: string}
- **`PublicComment`** — {`commentId`: int64, `authorName`: string, `content`: string, `createdAt`: date-time, `editedAt?`: date-time}
- **`Comment`** — {`commentId`: int64, `articleId`: int64, `content`: string, `status`: CommentStatus, `createdAt`: date-time, `editedAt?`: date-time, `hideReason?`: string}

---

## D. Bảo mật & độ tin cậy

1. `/me/articles…` chỉ VET (A07) và chỉ bài của chính mình; bài người khác trả 404.
2. Duyệt, từ chối, ẩn / bỏ ẩn bình luận: chỉ SUPER_MANAGER (BR-BV-06, BR-BV-08).
3. Bình luận: chỉ CUSTOMER, chỉ bài `PUBLISHED`; chỉ sửa / xóa bình luận của mình; giới hạn 10 bình luận / giờ / khách [CFG] (BR-BV-08). Tên tác giả bình luận hiển thị rút gọn.
4. `GET /public/articles/{slug}/comments` công khai, chỉ bình luận `ACTIVE` của bài `PUBLISHED` (BR-BV-03, BR-BV-08).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | VET không sửa bài đã `PUBLISHED`; muốn đổi nội dung thì SUPER_MANAGER ẩn và VET soạn bài mới | BR-BV-07 |
| A2 | Hiển thị tên khách rút gọn ở bình luận | Giảm lộ dữ liệu cá nhân (BR-BV-08) |
| A3 | Bình luận hiện ngay khi đăng, ẩn sau nếu vi phạm (không duyệt trước) | YAGNI |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | VET có chọn chuyên mục hay SUPER_MANAGER gán khi duyệt | TBD (PO) | BR-BV-05 |
| Q2 | Bình luận có cần duyệt trước khi hiện không | TBD (PO) | BR-BV-08 |
| Q3 | Có cho khách báo cáo bình luận vi phạm không | TBD (PO) | UC81 |

---

## F. OpenAPI 3.1

[`./openapi/articlex-v1.yaml`](./openapi/articlex-v1.yaml)
