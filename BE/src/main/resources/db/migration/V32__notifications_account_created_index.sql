-- =============================================================================
-- V32 — trả nợ D022: index thứ tự danh sách thông báo của tài khoản — docs/adr/0099.
-- Số V32: BE-1 (care); BE-2 từ V33 (06-module-contracts §6). Chỉ CREATE INDEX.
--
-- care-v1 #5 (GET /api/me/notifications) sắp created_at DESC, id DESC theo tài khoản. Trước V32 index duy nhất
-- ix_notifications_account_read_created (account_id, read_at, created_at DESC) có read_at xen giữa và không có id,
-- nên Postgres đọc mọi dòng của tài khoản rồi Sort mới Limit (ADR-0039 mục 10).
-- Index mới khớp đúng thứ tự ORDER BY: Index Scan, không Sort, dừng sau một trang.
-- Giữ nguyên index cũ: countUnread (FE poll mỗi 60 s) là Index Only Scan nhờ (account_id, read_at), markAllRead cũng
-- dùng nó; index mới không có read_at nên đếm chưa đọc phải đọc heap.
-- Không CONCURRENTLY: Flyway chạy migration trong transaction; bảng còn nhỏ, chấp nhận khóa ghi lúc tạo.
-- =============================================================================

CREATE INDEX ix_notifications_account_created ON notifications (account_id, created_at DESC, id DESC);
