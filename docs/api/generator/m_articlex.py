from lib import Module

m = Module(
    key="articlex", title="Doctor Articles & Comments API v1 (tầng 3)", codes="BV (bài viết của bác sĩ, bình luận)", owner="BE-2",
    sources="01 UC80, UC81 (mở rộng), UC82; 08-tang3-be2-spec.md §F.3 (BR-BV-05…08; 03 #21 Bài viết mở rộng); 05 bổ sung articles.author_account_id…, article_comments",
    intro="Bác sĩ soạn và gửi duyệt bài, SUPER_MANAGER duyệt hoặc từ chối, khách bình luận bài viết. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "Danh sách bài chờ duyệt dùng `GET /articles?status=PENDING_REVIEW` của content-v1 (mở rộng giá trị `status`), không có endpoint riêng.",
        "Bài do SUPER_MANAGER tự soạn và xuất bản giữ luồng của content-v1 (`POST /articles`, `…/publish`, `…/hide`).",
        "Ẩn bình luận là UC81 (A04) nên nằm ở đây cùng UC82; không có endpoint xóa cứng bình luận.",
    ],
    tags={
        "My articles": "Bài viết của bác sĩ (UC80)",
        "Article review": "Duyệt bài của bác sĩ (UC81 mở rộng)",
        "Comments": "Bình luận bài viết (UC82, UC81)",
    },
    security=[
        "`/me/articles…` chỉ VET (A07) và chỉ bài của chính mình; bài người khác trả 404.",
        "Duyệt, từ chối, ẩn / bỏ ẩn bình luận: chỉ SUPER_MANAGER (BR-BV-06, BR-BV-08).",
        "Bình luận: chỉ CUSTOMER, chỉ bài `PUBLISHED`; chỉ sửa / xóa bình luận của mình; giới hạn 10 bình luận / giờ / khách [CFG] (BR-BV-08). Tên tác giả bình luận hiển thị rút gọn.",
        "`GET /public/articles/{slug}/comments` công khai, chỉ bình luận `ACTIVE` của bài `PUBLISHED` (BR-BV-03, BR-BV-08).",
    ],
    assumptions=[
        ("A1", "VET không sửa bài đã `PUBLISHED`; muốn đổi nội dung thì SUPER_MANAGER ẩn và VET soạn bài mới", "BR-BV-07"),
        ("A2", "Hiển thị tên khách rút gọn ở bình luận", "Giảm lộ dữ liệu cá nhân (BR-BV-08)"),
        ("A3", "Bình luận hiện ngay khi đăng, ẩn sau nếu vi phạm (không duyệt trước)", "YAGNI"),
    ],
    questions=[
        ("Q1", "VET có chọn chuyên mục hay SUPER_MANAGER gán khi duyệt", "TBD (PO)", "BR-BV-05"),
        ("Q2", "Bình luận có cần duyệt trước khi hiện không", "TBD (PO)", "BR-BV-08"),
        ("Q3", "Có cho khách báo cáo bình luận vi phạm không", "TBD (PO)", "UC81"),
    ],
)

m.param("ArticleId", "articleId", "path", "int64")
m.param("Slug", "slug", "path", "string")
m.param("CommentId", "commentId", "path", "int64")

m.schema("ArticleReviewStatus", None, enum=["DRAFT", "PENDING_REVIEW", "REJECTED", "PUBLISHED", "HIDDEN"], desc="BR-BV-02 và 03 #21")
m.schema("CreateMyArticleRequest", {
    "title": "string:200", "categoryId": "int64?", "summary": "string:500?", "content": "string",
    "coverImageUrl": "https_url?",
})
m.schema("UpdateMyArticleRequest", {
    "title": "string:200?", "categoryId": "int64?", "summary": "string:500?", "content": "string?",
    "coverImageUrl": "https_url?",
}, required=[])
m.schema("MyArticleSummary", {
    "articleId": "int64", "title": "string", "status": "ref:ArticleReviewStatus", "submittedAt": "datetime?",
    "publishedAt": "datetime?", "updatedAt": "datetime",
})
m.schema("MyArticle", {
    "articleId": "int64", "title": "string", "slug": "string?", "categoryId": "int64?", "summary": "string?",
    "content": "string", "coverImageUrl": "https_url?", "status": "ref:ArticleReviewStatus",
    "reviewNote": "string?|Lý do từ chối (BR-BV-06)", "submittedAt": "datetime?", "publishedAt": "datetime?",
    "updatedAt": "datetime",
})
m.schema("ReviewNoteRequest", {"reviewNote": "string:300|Lý do từ chối, bắt buộc (BR-BV-06)"})
m.schema("CommentStatus", None, enum=["ACTIVE", "HIDDEN", "DELETED"])
m.schema("CreateCommentRequest", {"content": "string:1000|1–1 000 ký tự (BR-BV-08)"})
m.schema("UpdateCommentRequest", {"content": "string:1000"})
m.schema("HideCommentRequest", {"reason": "string:300"})
m.schema("PublicComment", {
    "commentId": "int64", "authorName": "string|Tên rút gọn (BR-BV-08)", "content": "string", "createdAt": "datetime",
    "editedAt": "datetime?",
})
m.schema("Comment", {
    "commentId": "int64", "articleId": "int64", "content": "string", "status": "ref:CommentStatus",
    "createdAt": "datetime", "editedAt": "datetime?", "hideReason": "string?",
})

VET = "VET, chỉ bài của mình"
A04 = "Chỉ SUPER_MANAGER"
CUS = "CUSTOMER, chỉ bình luận của mình"
TRANS = "`INVALID_STATE_TRANSITION` trạng thái bài không cho phép (03 #21)"

# ---- UC80
m.op("post", "/me/articles", "createMyArticle", "Soạn bài viết (nháp)", "My articles", "UC80", "BR-BV-05", "A07", VET,
     "— → DRAFT (Bài viết#1)", body="CreateMyArticleRequest", resp="MyArticle", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-BV-01` tiêu đề rỗng / quá dài · chuyên mục không tồn tại"})
m.op("get", "/me/articles", "listMyArticles", "Bài viết của tôi", "My articles", "UC80", "BR-BV-05", "A07", VET,
     resp="MyArticleSummary", page=True,
     query=["status=enum:DRAFT,PENDING_REVIEW,REJECTED,PUBLISHED,HIDDEN", "page", "size"], errors=(400, 401, 403),
     notes="Mới sửa trước (`updatedAt` giảm dần).")
m.op("get", "/me/articles/{articleId}", "getMyArticle", "Chi tiết bài viết của tôi", "My articles", "UC80", "BR-BV-05",
     "A07", VET, resp="MyArticle", path_params=["ArticleId"], errors=(400, 401, 403, 404))
m.op("patch", "/me/articles/{articleId}", "updateMyArticle", "Sửa bài viết của tôi", "My articles", "UC80",
     "BR-BV-05, 06, 07", "A07", VET, "`REJECTED` → DRAFT khi sửa (Bài viết#6)", body="UpdateMyArticleRequest",
     resp="MyArticle", path_params=["ArticleId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "`BR-BV-07` bài đang `PENDING_REVIEW` (rút về nháp trước) hoặc đã `PUBLISHED` / `HIDDEN`"},
     notes="Chỉ sửa được khi `DRAFT` hoặc `REJECTED`.")
m.op("post", "/me/articles/{articleId}/submit", "submitMyArticle", "Gửi duyệt", "My articles", "UC80", "BR-BV-06", "A07",
     VET, "DRAFT → PENDING_REVIEW (Bài viết#2)", resp="MyArticle", path_params=["ArticleId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-BV-01` thiếu trường bắt buộc", 409: TRANS})
m.op("post", "/me/articles/{articleId}/withdraw", "withdrawMyArticle", "Rút bài đang chờ duyệt", "My articles", "UC80",
     "BR-BV-06", "A07", VET, "PENDING_REVIEW → DRAFT (Bài viết#3)", resp="MyArticle", path_params=["ArticleId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: TRANS})

# ---- UC81 mở rộng
m.op("post", "/articles/{articleId}/approve", "approveArticle", "Duyệt bài của bác sĩ", "Article review", "UC81",
     "BR-BV-06, BR-BV-02", "A04", A04, "PENDING_REVIEW → PUBLISHED (Bài viết#4)", resp="MyArticle",
     path_params=["ArticleId"], errors=(400, 401, 403, 404, 409), err_desc={409: TRANS},
     notes="Ghi `published_at` ở lần xuất bản đầu (BR-BV-02); thông báo VET; ghi audit.")
m.op("post", "/articles/{articleId}/reject", "rejectArticle", "Từ chối bài của bác sĩ", "Article review", "UC81",
     "BR-BV-06", "A04", A04, "PENDING_REVIEW → REJECTED (Bài viết#5)", body="ReviewNoteRequest", resp="MyArticle",
     path_params=["ArticleId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-BV-06` thiếu lý do", 409: TRANS})

# ---- UC82 bình luận
m.op("get", "/public/articles/{slug}/comments", "listPublicComments", "Bình luận của một bài viết", "Comments", "UC82",
     "BR-BV-03, 08", "A01, A02", "Công khai", resp="PublicComment", page=True, path_params=["Slug"],
     query=["page", "size"], public=True, errors=(400, 404),
     err_desc={404: "Bài không tồn tại hoặc chưa `PUBLISHED`"}, notes="Chỉ bình luận `ACTIVE`, cũ nhất trước.")
m.op("post", "/me/articles/{articleId}/comments", "createComment", "Đăng bình luận", "Comments", "UC82", "BR-BV-08",
     "A02", "CUSTOMER", body="CreateCommentRequest", resp="Comment", status=201, path_params=["ArticleId"],
     errors=(400, 401, 403, 404, 429),
     err_desc={400: "`BR-BV-08` nội dung rỗng / quá 1 000 ký tự", 404: "Bài không tồn tại hoặc chưa `PUBLISHED`",
               429: "`TOO_MANY_REQUESTS` quá 10 bình luận / giờ [CFG] (BR-BV-08)"})
m.op("patch", "/me/comments/{commentId}", "updateMyComment", "Sửa bình luận của tôi", "Comments", "UC82", "BR-BV-08",
     "A02", CUS, body="UpdateCommentRequest", resp="Comment", path_params=["CommentId"],
     errors=(400, 401, 403, 404, 409), err_desc={409: "Bình luận đã bị ẩn hoặc đã xóa"})
m.op("delete", "/me/comments/{commentId}", "deleteMyComment", "Xóa bình luận của tôi", "Comments", "UC82", "BR-BV-08",
     "A02", CUS, status=204, path_params=["CommentId"], errors=(400, 401, 403, 404), idem="Có",
     notes="Xóa mềm (`DELETED`).")
m.op("post", "/comments/{commentId}/hide", "hideComment", "Ẩn bình luận", "Comments", "UC81", "BR-BV-08", "A04", A04,
     "ACTIVE → HIDDEN", body="HideCommentRequest", resp="Comment", path_params=["CommentId"],
     errors=(400, 401, 403, 404, 409), err_desc={400: "`BR-BV-08` thiếu lý do", 409: "Bình luận không `ACTIVE`"})
m.op("post", "/comments/{commentId}/unhide", "unhideComment", "Bỏ ẩn bình luận", "Comments", "UC81", "BR-BV-08", "A04",
     A04, "HIDDEN → ACTIVE", resp="Comment", path_params=["CommentId"], errors=(400, 401, 403, 404, 409),
     err_desc={409: "Bình luận không `HIDDEN`"})
