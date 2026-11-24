# ADR-0151: Gallery ảnh sản phẩm: `products.gallery_urls` (JSONB, tối đa 8, có thứ tự)

- **Trạng thái:** Proposed (làm thay BE-2 theo yêu cầu của Hiến ngày 2026-11-24; chủ module catalog là Dũng, cần Dũng rà)
- **Ngày:** 2026-11-24
- **Liên quan:** catalog-v1 (giả định A5, câu hỏi Q1 tải tệp lên), `docs/05-erd.md` §4 `products`, V62, ADR-0143 (giỏ hàng dùng `imageUrl`), `care_logs.photo_urls` (V1, tiền lệ JSONB danh sách URL)

## Bối cảnh

Trang chi tiết sản phẩm chỉ có một ảnh (`imageUrl`). Cần danh sách ảnh phụ có thứ tự để trang chi tiết hiện ảnh lớn kèm dải ảnh nhỏ. Hợp đồng catalog-v1 hiện chỉ nhận URL: BE chưa có kho lưu tệp (Q1), nên tải tệp lên **không** nằm trong phạm vi.

## Quyết định

1. **Lưu thành một cột JSONB `products.gallery_urls NOT NULL DEFAULT '[]'`** (V62) thay vì bảng con `product_images`. Lý do: đọc chung hàng sản phẩm nên danh sách, chi tiết, AI và `CatalogQueryService` không thêm truy vấn hay `JOIN`; thứ tự nằm ngay trong mảng nên không cần cột `position` và không phải sắp xếp lại; tối đa 8 nên không có truy vấn theo ảnh. Đổi lại: không có khóa ngoại và không có chỉ mục theo URL (không ai cần). Khuôn giống `care_logs.photo_urls` (V1): `@JdbcTypeCode(SqlTypes.JSON) List<String>`. `ADD COLUMN ... DEFAULT '[]'` với hằng không viết lại bảng; sản phẩm có từ trước tự có `[]`.
2. **`imageUrl` vẫn là ảnh bìa** (thẻ ở cửa hàng, giỏ hàng, AI không đổi). Ảnh hiển thị ở trang chi tiết là `[imageUrl, ...galleryUrls]` (FE bỏ rỗng và trùng). Không chọn ảnh bìa từ gallery, không chú thích ảnh: ngoài phạm vi.
3. **Giới hạn 8 phần tử, mỗi URL không rỗng và tối đa 500 ký tự** (như `imageUrl`). Thực thi ở ba lớp: Bean Validation ở DTO (trả `400 VALIDATION_FAILED`, đến trước 403 như mọi lỗi Bean Validation của catalog vì validation chạy khi gắn tham số, trước `@PreAuthorize`), CHECK `chk_products_gallery_urls` ở DB (`jsonb_typeof = 'array'` và `jsonb_array_length <= 8`, chặn lỗi lập trình / ghi tay vào DB), và FE tắt nút thêm khi đủ 8. CHECK ở DB không kiểm độ dài từng URL (đã kiểm ở ứng dụng, giống `image_url`).
4. **Chuẩn hóa khi lưu: `trim()` từng URL rồi bỏ trùng, giữ lần xuất hiện đầu, giữ thứ tự gửi.** Trùng không phải lỗi (người dùng dán lại cùng ảnh là việc thường gặp, báo lỗi chỉ gây phiền). Hệ quả cần biết: Bean Validation chạy **trước** chuẩn hóa, nên 9 phần tử trùng nhau (còn 1 sau bỏ trùng) vẫn bị `400`; và `@Size(max = 500)` đếm chuỗi gốc nên URL 500 ký tự kèm khoảng trắng đầu / cuối bị từ chối dù sau trim vừa đủ. Chấp nhận để luật dễ nói: "tối đa 8 phần tử gửi lên, mỗi phần tử tối đa 500 ký tự".
5. **PATCH:** `galleryUrls` vắng hoặc `null` = không đổi; `[]` = xóa hết; mảng khác = **thay cả danh sách** (giữ thứ tự gửi, sau chuẩn hóa). Không có thao tác thêm / xóa / đổi chỗ từng ảnh ở API: FE gửi lại mảng đầy đủ. POST: vắng hoặc `null` = `[]`. Response (admin và công khai) luôn trả mảng, không bao giờ `null`.
6. **Không kiểm giao thức URL** (giống `imageUrl`): đường dẫn tương đối `/products/x.jpg` hợp lệ, và FE chỉ đưa vào `<img src>` nên không chạy được mã. Giới hạn đã biết: URL `http://`, `ftp://` hay chuỗi lạ vẫn lưu được; không có chống ảnh theo dõi (tracking pixel) hay ảnh không tồn tại. Nếu sau này đưa URL vào chỗ khác `<img src>` (email, PDF) thì phải kiểm lại.
7. **Quyền và audit không đổi:** `POST` / `PATCH /products` chỉ SUPER_MANAGER; đọc admin cho mọi nhân viên; đọc công khai là Public. Đổi ảnh không ghi audit (ảnh không phải giá, khác `PRICE_CHANGED`).
8. **Giữ nguyên chữ ký constructor `Product`** (nhiều test liên module dựng bằng constructor): `galleryUrls` mặc định `new ArrayList<>()` cộng setter. DTO: `ProductResponse`, `PublicProductResponse`, `CreateProductRequest`, `UpdateProductRequest` thêm `galleryUrls` (đặt ngay sau `imageUrl`).

## Hệ quả

- **Flyway và dump.** V62 là migration một chiều, không có bản down. DB nào đã áp V62 thì chỉ chạy được với mã có V62 (mã cũ báo "Detected applied migration not resolved locally", BE không lên). `BE/scripts/demo-db/petcare_demo.dump` đang ở Flyway V61: **không** xuất lại dump từ DB đã có V62 cho tới khi V62 nằm trên `origin/dev`, nếu không người restore bằng mã chưa có V62 không khởi động được BE. BE chạy bằng mã mới trỏ DB nào sẽ tự áp V62 lên DB đó khi khởi động. Muốn gỡ tay: `ALTER TABLE products DROP CONSTRAINT chk_products_gallery_urls; ALTER TABLE products DROP COLUMN gallery_urls;` rồi xóa dòng V62 khỏi `flyway_schema_history` (chỉ khi mã đã lùi về trước V62).
- **Kích thước hàng.** Tối đa 8 × 500 ký tự = 4000 ký tự, nằm trong hàng hoặc TOAST; mọi truy vấn đọc entity `Product` (danh sách admin, tối đa 100 hàng mỗi trang) đọc thêm cột này. Chấp nhận, không đo thành số riêng vì không có truy vấn chọn riêng cột.
- **Cập nhật đồng thời.** `PATCH` thay cả mảng nên hai người sửa gallery cùng lúc thì bản ghi sau thắng (không có `@Version` trên `products`, như mọi trường khác của sản phẩm). `findByIdForUpdate` đã khóa dòng khi sửa nên không lẫn nửa này nửa kia.
- **Test mới:** `ProductServiceTest` (7 ca gallery), `ProductGalleryValidationTest` (7 ca, ghim thông báo và đường dẫn thuộc tính), `CatalogAdminIT` (5 ca), `PublicCatalogIT` (1 ca, sửa `containsOnlyKeys`), `SchemaMigrationIT` (1 ca CHECK / default, thêm V62 vào danh sách phiên bản), `OpenApiContractParityIT` giữ xanh. Mười đột biến làm trên bản sao, đều làm ít nhất một test đỏ: bỏ `@Size(max = 8)` (unit, 2 ca đỏ); bỏ `trim()` (unit, 2); bỏ `distinct()` (unit, 2); PATCH `null` thành xóa hết (unit, 2); bỏ `@NotBlank` phần tử (unit, 2); bỏ `@Size(max = 500)` phần tử (unit, 1); POST bỏ qua `galleryUrls` (unit, 1); `toPublic` luôn trả `[]` (`PublicCatalogIT`, 1); V62 bỏ `jsonb_array_length <= 8` (`SchemaMigrationIT`, 1); V62 bỏ `jsonb_typeof = 'array'` (`SchemaMigrationIT`, 1). Số chạy thật khi viết: unit toàn BE 6817 ca đỏ 0; 45 lớp IT của catalog, shop, inventory, sales, ai, report 532 ca đỏ 0; `SchemaMigrationIT` 30, `OpenApiContractParityIT` 11, đỏ 0.
- **Contract:** `docs/api/generator/m_catalog.py` (+ `lib.py`: kiểu `array<=N:X` sinh `maxItems`) rồi `generate.py` và `check-contracts.mjs` đạt (24 tệp, 346 thao tác); giả định A5 ghi vào catalog-v1.

## Không làm

Tải tệp lên (chưa có kho tệp, Q1); bảng ảnh riêng; chọn ảnh bìa từ gallery; chú thích hay alt riêng từng ảnh; kiểm URL có tải được; sắp xếp lại bằng thao tác từng ảnh ở API; thay đổi `CatalogQueryApi` (module khác tiếp tục dùng `imageUrl`).
