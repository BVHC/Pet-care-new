-- V33 (docs/adr/0104, trả T17-D05): tra cứu khách (UC23) theo họ tên và tên thú không phân biệt dấu tiếng Việt,
-- có index. Trước đây: ILIKE '%x%' trên cột gốc — "nguyen" không ra "Nguyễn" và mỗi lần tìm quét cả bảng customers.
-- Hai extension đều là "trusted" từ PostgreSQL 13: chủ database tạo được, không cần superuser.
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- unaccent() chỉ STABLE (phụ thuộc từ điển) nên không dùng được trong index; bọc lại IMMUTABLE với từ điển cố định.
-- Câu truy vấn phải gọi đúng f_unaccent(<cột>) thì mới dùng được các index dưới đây (CustomerSearchIndexIT giữ điều đó).
CREATE OR REPLACE FUNCTION f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- Trigram trên chuỗi đã bỏ dấu: phục vụ f_unaccent(cột) ILIKE '%x%' (mẫu từ 3 ký tự trở lên có chọn lọc).
-- IF NOT EXISTS: chuỗi lớn có thể tạo trước bằng CREATE INDEX CONCURRENTLY (Flyway chạy trong transaction nên không dùng được).
CREATE INDEX IF NOT EXISTS ix_customers_full_name_unaccent_trgm ON customers USING gin (f_unaccent(full_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_pets_name_unaccent_trgm ON pets USING gin (f_unaccent(name) gin_trgm_ops);
