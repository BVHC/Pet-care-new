# ADR-0018: Index cho mọi cột FK trỏ tới `accounts`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Supersedes:** ADR-0013 mục 5, phần "Index cho các cột FK khác trỏ tới `accounts` để nợ D009", và phương án bị loại "Index ngay cả 37 cột FK" của ADR-0013 (con số đúng là 38).
- **Liên quan:**
  - BR-TK-08 (ST02 xóa tài khoản `PENDING`), BR-TK-19, BR-KH-06 (các ca xóa cứng khác).
  - `docs/05-erd.md` §0 (FK mặc định `ON DELETE RESTRICT`), §13 mục 14.
  - ADR-0013 (ST02, time budget + cảnh báo xóa chậm). Nợ D009 (trả bởi ADR này).
- **Triển khai:**
  - `BE/src/main/resources/db/migration/V7__account_fk_indexes.sql` (38 index). **Số V7 chưa xác nhận với BE-2** (CLAUDE.md *Gotchas*).
  - Test: `SchemaMigrationIT.everyForeignKeyToAccountsHasUsableIndex` (43 FK; mỗi cột có index mà phép kiểm FK dùng được); `PendingAccountCleanupIT` (ST02 vẫn đúng).

## Bối cảnh

Xóa một dòng `accounts` (chỉ ST02 làm) bắt Postgres kiểm FK `RESTRICT` ở mọi cột trỏ tới `accounts` bằng câu `SELECT 1 FROM <bảng> WHERE <cột> = $1`, kể cả khi chắc chắn không có dòng con. V1 có 43 cột ở 34 bảng; đếm lại 2026-10-07: **38** cột không có index dùng được — 36 cột không có index nào, cộng `sessions.account_id` (chỉ có index partial `WHERE revoked_at IS NULL`) và `cashier_shifts.cashier_id` (partial `WHERE status = 'OPEN'`): câu kiểm FK không suy ra được các vị từ đó nên không dùng được index. ADR-0013 ghi 37 và để nợ chờ số đo; người dùng chọn trả ngay (2026-10-07) khi bảng còn rỗng, migration rẻ nhất.

## Quyết định

1. **Mỗi cột FK trỏ tới `accounts` có một index dẫn đầu bằng cột đó** (`ix_<bảng>_<cột>`), 38 index ở V7.
2. **Cột cho phép NULL dùng index partial `WHERE <cột> IS NOT NULL`** (phần lớn là `*_by` của thao tác chưa xảy ra): toán tử `=` là strict nên câu kiểm FK suy ra được vị từ; dòng NULL không bao giờ cần tìm, index nhỏ hơn. Cột NOT NULL dùng index thường.
3. Giữ nguyên hai index partial cũ của `sessions` và `cashier_shifts` (phục vụ query riêng của chúng); thêm index đủ bên cạnh.
4. Không `CONCURRENTLY` (Flyway chạy trong transaction; bảng gần rỗng lúc migrate).
5. **Chặn tái phát bằng test catalog**: `SchemaMigrationIT.everyForeignKeyToAccountsHasUsableIndex` đọc `pg_constraint` + `pg_index`; BE-2 thêm cột FK mới tới `accounts` mà thiếu index thì test đỏ.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Chờ số đo (ADR-0013) | Thêm index lên bảng đã có dữ liệu cần `CONCURRENTLY` ngoài transaction Flyway; lúc bảng rỗng là lúc rẻ nhất |
| Chỉ index các bảng dự kiến lớn | Phải đoán; test catalog không thể viết đơn giản cho "một phần" |
| Đổi FK sang `ON DELETE CASCADE` / bỏ FK | Trái erd §0 và quy tắc xóa con trước trong cùng transaction (CLAUDE.md *Persistence*) |
| Thay index partial cũ bằng index đủ | Đổi hành vi query của `sessions` / ca thu ngân ngoài phạm vi nợ này |

## Hệ quả

- Nợ D009 đã trả. Xóa một tài khoản kiểm FK bằng index ở mọi bảng thay vì quét toàn bảng ~38 lần; time budget và cảnh báo `PENDING_ACCOUNT_CLEANUP_SLOW` của ADR-0013 giữ làm lưới an toàn.
- **Chi phí ghi:** thêm một cập nhật index cho mỗi INSERT/UPDATE có giá trị khác NULL ở 34 bảng (phần lớn bảng của BE-2: `orders`, `order_lines`, `stock_movements`, `visits`…). Ở quy mô phòng khám (nghìn dòng/ngày) không đáng kể; đĩa thêm ~38 index nhỏ.
- **BE-2:** bảng của BE-2 có thêm index; cột FK mới tới `accounts` phải kèm index (test catalog nhắc). Số V7 phải được BE-2 xác nhận trước khi merge.
- Đo lại `EXPLAIN (ANALYZE) DELETE FROM accounts …` trên DB có dữ liệu thật và ghi vào D009.
- **Đã kiểm 2026-10-07:** `mvn clean verify` xanh; mutation bỏ `ix_orders_cancelled_by` khỏi V7 → `everyForeignKeyToAccountsHasUsableIndex` đỏ, báo `["orders.cancelled_by"]`. **Số đo** (200 000 dòng ở `sessions` và `notification_outbox`): trigger kiểm FK 17,7 → 0,35 ms và 21,9 → 0,50 ms mỗi tài khoản bị xóa (chi tiết ở D009).
