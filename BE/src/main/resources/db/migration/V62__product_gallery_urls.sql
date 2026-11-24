-- =============================================================================
-- V62 — Gallery ảnh sản phẩm (docs/adr/0151; docs/05-erd.md §4 products; catalog-v1 Product / PublicProduct):
--   products.gallery_urls: danh sách URL ảnh phụ CÓ THỨ TỰ, tối đa 8. products.image_url vẫn là ảnh bìa (thẻ cửa hàng,
--   giỏ hàng, AI). Ảnh hiển thị ở trang chi tiết = [image_url, ...gallery_urls] (FE bỏ rỗng và trùng).
--
--   * JSONB thay bảng con: đọc chung hàng sản phẩm (không thêm truy vấn), thứ tự nằm trong mảng, tối đa 8 nên không cần
--     truy vấn theo ảnh. Tiền lệ care_logs.photo_urls (V1). Đổi lại: không khóa ngoại / chỉ mục theo URL.
--   * DEFAULT '[]' nên sản phẩm có từ trước có gallery rỗng; ADD COLUMN với DEFAULT hằng không viết lại bảng (PG 11+).
--   * CHECK chỉ chặn sai kiểu (không phải mảng) và quá 8 phần tử; độ dài / rỗng / trùng của từng URL do Bean Validation
--     và ProductService xử lý (chuẩn hóa trim + bỏ trùng giữ lần đầu), DB không lặp lại các luật đó.
--   * Không kiểm giao thức URL (giống image_url): đường dẫn tương đối hợp lệ; FE chỉ đưa vào <img src>.
-- =============================================================================

ALTER TABLE products ADD COLUMN gallery_urls JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE products ADD CONSTRAINT chk_products_gallery_urls
    CHECK (jsonb_typeof(gallery_urls) = 'array' AND jsonb_array_length(gallery_urls) <= 8);
