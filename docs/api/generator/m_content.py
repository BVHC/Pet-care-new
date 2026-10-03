from lib import Module

m = Module(
    key="content", title="Content & Feedback API v1", codes="CK (nội dung trang), BV, DG (tầng 2)", owner="BE-2",
    sources="01 UC17, UC21, UC81, UC83, UC84; 02 BR-CK-01, BR-BV-01…04, BR-DG-01…04, BR-TK-19; 04 §10; 05 §10 (page_contents, article_categories, articles, feedbacks)",
    intro="Nội dung trang tĩnh (banner, giới thiệu, chính sách, FAQ), chuyên mục và bài viết, feedback của khách.",
    frozen=[
        "UC80 (VET gửi duyệt bài), UC82 (bình luận) và phần 'duyệt, ẩn bình luận' của UC81 là tầng 3.",
        "UC85, UC86 khiếu nại là tầng 3.",
        "Dịch vụ, sản phẩm, chi nhánh, bác sĩ công khai nằm ở catalog, branch, identity (`/public/...`).",
    ],
    tags={
        "Pages": "Nội dung trang (UC21)",
        "Article categories": "Chuyên mục bài viết (UC81)",
        "Articles": "Bài viết (UC81)",
        "Public": "Trang, bài viết công khai (UC17)",
        "Feedback": "Feedback của khách (UC83, UC84)",
    },
    security=[
        "Soạn và đăng bài, chuyên mục, nội dung trang: chỉ SUPER_MANAGER.",
        "Bài DRAFT / HIDDEN truy cập từ trang công khai trả 404 (BR-BV-03). Nội dung bài lưu HTML đã lọc (05 `articles.content`).",
        "Feedback không công khai: chỉ khách gửi xem lại (không thấy ghi chú nội bộ), SUPER_MANAGER xem tất cả, BRANCH_MANAGER chỉ feedback gắn chi nhánh mình; ngoài phạm vi trả 403 (BR-DG-02, 03).",
        "Giới hạn 5 feedback / khách / ngày [CFG] kiểm ở server (BR-DG-01).",
    ],
    assumptions=[
        ("A1", "Slug chuyên mục không đổi sau khi tạo", "Không có rule; slug dùng trong URL công khai như BR-BV-01"),
        ("A2", "Mở chi tiết feedback lần đầu bởi người có quyền tự chuyển NEW → SEEN trong cùng request GET", "BR-DG-04 'tự chuyển khi người có quyền mở lần đầu'"),
        ("A3", "`content` của trang là JSON tự do theo từng key (danh sách banner, câu hỏi FAQ…)", "05 `page_contents.content` JSONB, cấu trúc tùy trang"),
    ],
    questions=[
        ("Q1", "Cấu trúc JSON cụ thể của từng trang (HOME_BANNER, ABOUT, POLICY, FAQ) — FE và BE chốt khi làm UC21.", "TBD (FE + BE)", "05 `page_contents`"),
    ],
)

m.param("PageKey", "key", "path", "enum:HOME_BANNER,ABOUT,POLICY,FAQ")
m.param("ArticleCategoryId", "categoryId", "path", "int64")
m.param("ArticleId", "articleId", "path", "int64")
m.param("Slug", "slug", "path", "string")
m.param("FeedbackId", "feedbackId", "path", "int64")

m.schema("PageContent", {
    "key": "enum:HOME_BANNER,ABOUT,POLICY,FAQ", "title": "string:250?", "content": "object|A3",
    "updatedAt": "datetime",
})
m.schema("UpdatePageContentRequest", {"title": "string:250?", "content": "object"})
m.schema("ArticleCategory", {
    "categoryId": "int64", "name": "string:100", "slug": "string:120", "isHidden": "bool", "articleCount": "int",
})
m.schema("ArticleCategoryRequest", {"name": "string:100", "slug": "string:120"})
m.schema("UpdateArticleCategoryRequest", {"name": "string:100?", "isHidden": "bool?"}, required=[])
m.schema("PublicArticleCategory", {"categoryId": "int64", "name": "string", "slug": "string"})
m.schema("Article", {
    "articleId": "int64", "categoryId": "int64", "title": "string:250", "slug": "string:270",
    "coverImageUrl": "url?", "summary": "string:500?", "content": "string|HTML đã lọc",
    "authorDisplayName": "string:100", "status": "enum:DRAFT,PUBLISHED,HIDDEN",
    "firstPublishedAt": "datetime?|Khác null: slug khóa, không xóa được", "createdAt": "datetime", "updatedAt": "datetime",
})
m.schema("ArticleSummary", {
    "articleId": "int64", "categoryId": "int64", "title": "string", "slug": "string",
    "status": "enum:DRAFT,PUBLISHED,HIDDEN", "firstPublishedAt": "datetime?", "updatedAt": "datetime",
})
m.schema("CreateArticleRequest", {
    "categoryId": "int64", "title": "string:250", "slug": "string:270|Duy nhất toàn hệ thống (BR-BV-01)",
    "coverImageUrl": "url?", "summary": "string:500?", "content": "string", "authorDisplayName": "string:100",
})
m.schema("UpdateArticleRequest", {
    "categoryId": "int64?", "title": "string:250?", "slug": "string:270?|Không đổi được sau lần xuất bản đầu",
    "coverImageUrl": "url?", "summary": "string:500?", "content": "string?", "authorDisplayName": "string:100?",
}, required=[])
m.schema("PublicArticleCard", {
    "articleId": "int64", "title": "string", "slug": "string", "categoryName": "string", "categorySlug": "string",
    "coverImageUrl": "url?", "summary": "string?", "authorDisplayName": "string", "publishedAt": "datetime",
})
m.schema("PublicArticle", {
    "articleId": "int64", "title": "string", "slug": "string", "categoryName": "string", "categorySlug": "string",
    "coverImageUrl": "url?", "summary": "string?", "content": "string", "authorDisplayName": "string",
    "publishedAt": "datetime",
})
m.schema("CreateFeedbackRequest", {
    "topic": "enum:BRANCH_SERVICE,WEBSITE,OTHER", "branchId": "int64?|Bắt buộc khi BRANCH_SERVICE",
    "rating": "int?|1–5", "content": "string:2000|10–2000 ký tự",
})
m.schema("MyFeedback", {
    "feedbackId": "int64", "topic": "enum:BRANCH_SERVICE,WEBSITE,OTHER", "branchId": "int64?", "rating": "int?",
    "content": "string", "status": "enum:NEW,SEEN,RESOLVED|Khách chỉ thấy trạng thái (BR-DG-04)", "createdAt": "datetime",
})
m.schema("Feedback", {
    "feedbackId": "int64", "customerId": "int64", "customerName": "string", "topic": "enum:BRANCH_SERVICE,WEBSITE,OTHER",
    "branchId": "int64?", "branchName": "string?", "rating": "int?", "content": "string",
    "status": "enum:NEW,SEEN,RESOLVED", "seenAt": "datetime?", "resolvedBy": "int64?", "resolvedAt": "datetime?",
    "resolutionNote": "string?|Nội bộ", "createdAt": "datetime",
})
m.schema("ResolveFeedbackRequest", {"resolutionNote": "string|Bắt buộc (BR-DG-04)"})

SM = "Chỉ SUPER_MANAGER"
m.op("get", "/page-contents", "listPageContents", "Danh sách nội dung trang", "Pages", "UC21", "—", "A04", SM,
     resp="array:PageContent", errors=(401, 403))
m.op("put", "/page-contents/{key}", "updatePageContent", "Sửa nội dung trang", "Pages", "UC21", "—", "A04", SM,
     body="UpdatePageContentRequest", resp="PageContent", path_params=["PageKey"], errors=(400, 401, 403, 404))

m.op("get", "/article-categories", "listArticleCategories", "Danh sách chuyên mục", "Article categories", "UC81",
     "BR-BV-04", "A04", SM, resp="array:ArticleCategory", errors=(401, 403))
m.op("post", "/article-categories", "createArticleCategory", "Tạo chuyên mục", "Article categories", "UC81", "BR-BV-04",
     "A04", SM, body="ArticleCategoryRequest", resp="ArticleCategory", status=201, errors=(400, 401, 403),
     err_desc={400: "Tên hoặc slug trùng"})
m.op("patch", "/article-categories/{categoryId}", "updateArticleCategory", "Sửa / ẩn chuyên mục", "Article categories",
     "UC81", "BR-BV-04", "A04", SM, body="UpdateArticleCategoryRequest", resp="ArticleCategory",
     path_params=["ArticleCategoryId"], errors=(400, 401, 403, 404),
     notes="Chuyên mục ẩn không hiện ở bộ lọc công khai; bài trong đó vẫn truy cập được theo đường dẫn.")
m.op("delete", "/article-categories/{categoryId}", "deleteArticleCategory", "Xóa chuyên mục rỗng", "Article categories",
     "UC81", "BR-BV-04", "A04", SM, status=204, path_params=["ArticleCategoryId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-BV-04` còn bài viết (message ghi số bài) — chỉ ẩn"})

m.op("get", "/articles", "listArticles", "Danh sách bài viết", "Articles", "UC81", "BR-BV-02", "A04", SM,
     resp="ArticleSummary", page=True, query=["status=enum:DRAFT,PUBLISHED,HIDDEN", "categoryId=int64", "q=string",
                                               "page", "size"], errors=(400, 401, 403))
m.op("post", "/articles", "createArticle", "Soạn bài viết", "Articles", "UC81", "BR-BV-01", "A04", SM, "— → DRAFT",
     body="CreateArticleRequest", resp="Article", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-BV-01` slug trùng, thiếu tiêu đề / chuyên mục / nội dung"})
m.op("get", "/articles/{articleId}", "getArticle", "Chi tiết bài viết", "Articles", "UC81", "—", "A04", SM,
     resp="Article", path_params=["ArticleId"], errors=(401, 403, 404))
m.op("patch", "/articles/{articleId}", "updateArticle", "Sửa bài viết", "Articles", "UC81", "BR-BV-01", "A04", SM,
     body="UpdateArticleRequest", resp="Article", path_params=["ArticleId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-BV-01` đổi slug sau lần xuất bản đầu · slug trùng"})
m.op("post", "/articles/{articleId}/publish", "publishArticle", "Xuất bản / hiện lại bài", "Articles", "UC81",
     "BR-BV-02", "A04", SM, "DRAFT / HIDDEN → PUBLISHED", resp="Article", path_params=["ArticleId"],
     errors=(401, 403, 404, 409), err_desc={409: "Bài đang PUBLISHED"},
     notes="Lần xuất bản đầu ghi firstPublishedAt và khóa slug.")
m.op("post", "/articles/{articleId}/hide", "hideArticle", "Gỡ (ẩn) bài", "Articles", "UC81", "BR-BV-02", "A04", SM,
     "PUBLISHED → HIDDEN", resp="Article", path_params=["ArticleId"], errors=(401, 403, 404, 409),
     err_desc={409: "Bài không PUBLISHED"})
m.op("delete", "/articles/{articleId}", "deleteArticle", "Xóa bài chưa từng xuất bản", "Articles", "UC81", "BR-BV-02",
     "A04", SM, status=204, path_params=["ArticleId"], errors=(400, 401, 403, 404),
     err_desc={400: "`BR-BV-02` bài đã từng xuất bản — chỉ ẩn"})

m.op("get", "/public/pages/{key}", "getPublicPage", "Nội dung trang công khai", "Public", "UC15–UC17", "—",
     "A01, A02", "Public", resp="PageContent", path_params=["PageKey"], public=True, errors=(404,))
m.op("get", "/public/article-categories", "listPublicArticleCategories", "Chuyên mục công khai", "Public", "UC17",
     "BR-BV-03, 04", "A01, A02", "Public", resp="array:PublicArticleCategory", public=True, errors=(400,),
     notes="Bỏ chuyên mục ẩn.")
m.op("get", "/public/articles", "listPublicArticles", "Danh sách bài viết", "Public", "UC17", "BR-BV-03, BR-CK-01",
     "A01, A02", "Public", resp="PublicArticleCard", page=True, public=True, errors=(400,),
     query=["categorySlug=string", "page", "size"], notes="Chỉ PUBLISHED, mới nhất trước.")
m.op("get", "/public/articles/{slug}", "getPublicArticle", "Đọc bài viết", "Public", "UC17", "BR-BV-03", "A01, A02",
     "Public", resp="PublicArticle", path_params=["Slug"], public=True, errors=(404,),
     err_desc={404: "Không có, hoặc bài DRAFT / HIDDEN (BR-BV-03)"})

m.op("post", "/me/feedbacks", "submitFeedback", "Gửi feedback", "Feedback", "UC83", "BR-DG-01, 02, BR-TK-19", "A02",
     "Chỉ CUSTOMER", "— → NEW", body="CreateFeedbackRequest", resp="MyFeedback", status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-DG-01` nội dung ngoài 10–2000 ký tự, thiếu chi nhánh khi BRANCH_SERVICE, điểm ngoài 1–5, vượt 5 feedback / ngày [CFG] (message ghi thời điểm gửi tiếp) · `BR-TK-19` hồ sơ còn chờ quyết định liên kết"})
m.op("get", "/me/feedbacks", "listMyFeedbacks", "Feedback tôi đã gửi", "Feedback", "UC83", "BR-DG-02, 04", "A02",
     "Chỉ CUSTOMER", resp="array:MyFeedback", errors=(401, 403), notes="Không sửa, không xóa.")
m.op("get", "/feedbacks", "listFeedbacks", "Danh sách feedback", "Feedback", "UC84", "BR-DG-02, 03", "A04, A05",
     "SUPER_MANAGER: tất cả · BRANCH_MANAGER: chi nhánh mình", resp="Feedback", page=True, errors=(400, 401, 403),
     query=["branchId=int64", "status=enum:NEW,SEEN,RESOLVED", "topic=enum:BRANCH_SERVICE,WEBSITE,OTHER", "from=date",
            "to=date", "page", "size"])
m.op("get", "/feedbacks/{feedbackId}", "getFeedback", "Xem feedback", "Feedback", "UC84", "BR-DG-03, 04", "A04, A05",
     "Như listFeedbacks", "NEW → SEEN khi mở lần đầu (A2)", resp="Feedback", path_params=["FeedbackId"],
     errors=(401, 403, 404))
m.op("post", "/feedbacks/{feedbackId}/resolve", "resolveFeedback", "Đánh dấu đã xử lý", "Feedback", "UC84",
     "BR-DG-04", "A04, A05", "Như listFeedbacks", "SEEN → RESOLVED", body="ResolveFeedbackRequest", resp="Feedback",
     path_params=["FeedbackId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-DG-04` thiếu ghi chú", 409: "Đã RESOLVED"})
