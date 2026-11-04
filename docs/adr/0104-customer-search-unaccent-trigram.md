# ADR-0104: Tra cứu khách / thú theo tên không phân biệt dấu, có index trigram (T17-D05)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-05
- **Liên quan:** UC23, customer-v1 #8, BR-KH-01, BR-KH-10; ADR-0041 (khách & thú), 0103 (cùng đợt trả nợ BE-2); nợ `docs/dept/T17-khach-hang-thu-cung.md` D05.
- **Triển khai:** `V33__search_name_unaccent_trigram.sql`; `CustomerSearchRepository`; chữ mô tả `name` / `petName` của customer-v1 #8 (`m_customer.py`, `CustomerController`). Không endpoint mới, không đổi chữ ký API.

## Vấn đề

Tra cứu khách ở quầy tìm họ tên và tên thú bằng `ILIKE '%x%'` trên cột gốc:
- Lễ tân gõ "nguyen" không ra "Nguyễn"; hệ thống tiếng Việt mà phải gõ đúng dấu thì chậm và hay sót khách.
- Mẫu bắt đầu bằng `%` không dùng được index B-tree, nên mỗi lần tìm quét cả `customers` (và `pets` khi tìm theo tên thú).

## Quyết định

1. **Bỏ dấu ở phía DB, không ở Java.** `f_unaccent(<cột>) ILIKE f_unaccent(:x) ESCAPE '!'`: cùng một hàm cho cột và tham số nên hai bên luôn cùng chuẩn hóa, kể cả khi từ điển `unaccent` đổi. Chuẩn hóa NFC / NFD không cần xử lý riêng: từ điển `unaccent` có sẵn luật cho chữ dựng sẵn, `đ → d` và dấu tổ hợp (kiểm bằng IT với cả hai dạng).
2. **`f_unaccent(text)`** bọc `public.unaccent('public.unaccent'::regdictionary, $1)`, khai báo `IMMUTABLE PARALLEL SAFE STRICT`, vì `unaccent(text)` chỉ `STABLE` nên không dùng được trong biểu thức index. Từ điển nêu rõ để kết quả không phụ thuộc `search_path` lúc gọi.
3. **Hai index GIN trigram trên biểu thức:** `ix_customers_full_name_unaccent_trgm (f_unaccent(full_name) gin_trgm_ops)` và `ix_pets_name_unaccent_trgm (f_unaccent(name) gin_trgm_ops)`. Phục vụ `ILIKE '%x%'` cho mẫu từ 3 ký tự trở lên.
4. **Giữ nguyên ngữ nghĩa còn lại:** "chứa chuỗi", không phân biệt hoa thường, thoát `!` `%` `_` (`likePattern`), điều kiện chỉ ghép khi được gửi (không `:x IS NULL OR …`), `ORDER BY full_name, id`.
5. **Hệ quả nghiệp vụ chấp nhận:** hai chữ chỉ khác dấu coi như cùng một chữ ("Hương" ~ "Hường"). Lễ tân chọn đúng người từ danh sách (có SĐT, email che, thú).

## Cạnh biên và rủi ro production

| Tình huống | Xử lý |
|---|---|
| Mẫu 1–2 ký tự (ít trigram) | Index vẫn đúng nhưng ít chọn lọc, planner có thể quét tuần tự; chấp nhận như trước đây (đang quét tuần tự) |
| Mẫu có `%`, `_`, `!` | `likePattern` thoát trước khi `f_unaccent`; `unaccent` không đụng ba ký tự này; IT cũ `searchTreatsWildcardsAsLiterals` vẫn xanh |
| Dữ liệu NFD (dấu tổ hợp) từ nhập tay / import | Khớp cả hai dạng (IT `searchIgnoresVietnameseDiacriticsInPrecomposedAndDecomposedForm`) |
| `CREATE EXTENSION` cần quyền | `unaccent` và `pg_trgm` là extension "trusted" từ PostgreSQL 13: chủ database tạo được, không cần superuser. Image `postgres:17` (compose, Testcontainers) có sẵn contrib; dịch vụ managed (RDS, Cloud SQL…) cho phép cả hai |
| Khóa bảng khi tạo index | `CREATE INDEX` chặn ghi `customers` / `pets` trong lúc xây; chấp nhận vì bảng nhỏ ở giai đoạn này. Chuỗi lớn (hàng trăm nghìn dòng): tạo tay hai index bằng `CREATE INDEX CONCURRENTLY` (cùng tên, cùng biểu thức) trước khi triển khai; Flyway chạy trong transaction nên không tự dùng được `CONCURRENTLY`, còn `IF NOT EXISTS` trong V33 bỏ qua bước xây. Ghi chú vận hành, chưa có nhu cầu |
| Chi phí ghi | Mỗi INSERT / UPDATE tên khách và tên thú thêm một lần tính trigram (GIN có pending list nên chi phí ghi thấp); không ảnh hưởng đường nóng (thu tiền, đặt lịch) |
| Đổi biểu thức trong truy vấn mà quên index | `CustomerSearchIndexIT` chạy `EXPLAIN` chính câu SQL của repository trên 20 000 khách + 20 000 thú trong transaction hoàn tác và đòi thấy hai tên index |
| Đổi từ điển `unaccent` ở phiên bản PostgreSQL mới | Hàm được khai báo `IMMUTABLE` nhưng kết quả phụ thuộc từ điển: nâng cấp PostgreSQL lớn có thể đổi cách bỏ dấu một số ký tự hiếm; khi đó `REINDEX` hai index. Vận hành ghi nhận trong runbook nâng cấp |

## Ngoài phạm vi

Tìm sản phẩm (`InventoryQueryRepository`) và các danh sách khác còn `ILIKE` trên cột gốc; có thể dùng lại `f_unaccent` và mẫu index này khi có yêu cầu, không làm ở đây (không có nợ ghi nhận).

## Bảng đảm bảo

| # | Đảm bảo | Test |
|---|---|---|
| G1 | Tên khách / thú tìm không phân biệt dấu ở dạng NFC và NFD, `đ ~ d`; sai chữ thì không khớp | `CounterCustomerIT.searchIgnoresVietnameseDiacriticsInPrecomposedAndDecomposedForm`; các ca tìm cũ (hoa thường, ký tự đặc biệt, phân trang) vẫn xanh |
| G2 | Tìm theo tên khách và theo tên thú đều dùng index trigram không dấu trên 20 000 dòng | `CustomerSearchIndexIT` (2 ca) |
| G3 | Schema khớp: V33 áp dụng, danh sách migration | `SchemaMigrationIT.flywayAppliedAllMigrationsSuccessfully` |
| G4 | Hợp đồng khớp code (chữ mô tả `name`, `petName`) | `OpenApiContractParityIT` |

Mutation: đổi `f_unaccent(c.full_name)` / `f_unaccent(p.name)` trong truy vấn thành `unaccent(...)` (hành vi tìm vẫn đúng) → cả hai ca của `CustomerSearchIndexIT` đỏ.
