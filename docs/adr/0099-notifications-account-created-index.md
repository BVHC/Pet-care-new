# ADR-0099: Index `notifications (account_id, created_at DESC, id DESC)` cho danh sách thông báo (V32, trả D022)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-03
- **Liên quan:** care-v1 #5 `GET /api/me/notifications`, #6 đếm chưa đọc, #8 đọc hết; ADR-0039 (mục 10 "Index" và dòng cuối bảng phương án bị loại — ADR này thay phần "không thêm migration"), ADR-0014 (worker IN_APP ghi `notifications`), ADR-0098 / 0095 (khuôn index của BE-1). Nợ: D022 (đóng); D023 (không dọn bảng) **không** đổi.
- **Triển khai:** `V32__notifications_account_created_index.sql`; `care/repository/NotificationRepository` (chỉ Javadoc, SQL giữ nguyên). Test: `MyNotificationIT.repositoryQueriesUseTheAccountIndex`, `SchemaMigrationIT.notificationAccountCreatedIndexKeepsTheV1Index` (+ danh sách migration).

Người dùng chốt ngày 2026-11-03: chỉ phương án B (một index mới, không partial), **giữ** index cũ, V32 (BE-1 lấy, báo BE-2).

## Vấn đề

Danh sách #5 sắp `created_at DESC, id DESC` theo tài khoản. Index V1 `ix_notifications_account_read_created (account_id, read_at, created_at DESC)` có `read_at` xen giữa và không có `id`, nên Postgres đọc mọi dòng của tài khoản rồi Sort mới Limit (đo 2026-10-14, ADR-0039 mục 10: Index / Bitmap Scan → Sort → Limit). Chi phí tăng theo số dòng của **một** tài khoản; D023 làm số dòng chỉ tăng.

## Quyết định

1. **Thêm** `ix_notifications_account_created ON notifications (account_id, created_at DESC, id DESC)`. Khớp đúng `ORDER BY` của `findMine` và `findMineUnread`: Index Scan, không Sort, Limit dừng sớm. Index thường, không partial (Flyway chạy trong transaction nên không dùng `CONCURRENTLY`; bảng còn nhỏ, chấp nhận khóa ghi lúc tạo).
2. **Giữ** `ix_notifications_account_read_created`. D022 bước 2 đề nghị cân nhắc bỏ; ADR này bỏ ý đó vì `countUnread` — câu chạy nhiều nhất, FE poll mỗi 60 s ở mỗi phiên đang mở (`useUnreadCount`) — là Index Only Scan nhờ `(account_id, read_at)`; index mới không có `read_at` nên đếm phải đọc heap. `markAllRead` cũng dùng index cũ.
3. **SQL của repository không đổi**; chỉ sửa Javadoc.
4. **`findMineUnread`** để planner chọn giữa hai index: ít dòng chưa đọc thì index cũ cộng Sort nhỏ rẻ hơn, nhiều dòng chưa đọc thì index mới (lọc `read_at IS NULL` ở heap, dừng khi đủ trang). Test không khẳng định "không Sort" cho câu này.

## Hệ quả và giới hạn

- **Chi phí ghi:** mỗi `INSERT` thông báo ghi thêm một entry index. `markRead` / `markAllRead` đổi `read_at`, cột nằm trong index cũ nên update đã không phải HOT từ trước; bản ghi mới thêm một entry ở index mới. Chưa đo (đồ án).
- **OFFSET vẫn tuyến tính:** trang sâu hết Sort nhưng vẫn đi qua các dòng bị bỏ. Chỉ phân trang keyset mới hết hẳn, mà đó là đổi contract + FE nên không làm ở đây.
- **Không partial `WHERE read_at IS NULL`:** chưa có số đo cho thấy `unreadOnly` cần; thêm sau nếu đo thấy chậm.
- **Chưa đo EXPLAIN sau V32 trên dữ liệu cỡ ADR-0039** (Docker tắt lúc viết): số "trước" là của ADR-0039; "sau" được chứng minh về cấu trúc bằng IT (`SET LOCAL enable_sort = off` ⇒ plan trang `findMine` không có node Sort và dùng `ix_notifications_account_created`). Ghi số đo khi chạy IT có Docker.
- Không đổi contract, không đổi FE.
