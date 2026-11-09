-- =============================================================================
-- V50 — trả nợ D054: index cho job dọn refresh token hết hạn — docs/adr/0114.
-- Số V50: BE-1 (identity), lấy sau V48; V49 giữ lại cho BE-2 (ADR-0117 TBD-1 / TBD-2). Chỉ CREATE INDEX.
--
-- RefreshTokenCleanupJob chọn mỗi lô `WHERE expires_at < :cutoff ORDER BY expires_at, id LIMIT :n
-- FOR NO KEY UPDATE SKIP LOCKED`. Không có index, Postgres đọc cả bảng (Seq Scan + Sort) cho mỗi lô, kể cả lô cuối chỉ
-- còn vài dòng; đo trên 20 000 dòng: 9 ms, nhưng chi phí tăng theo cỡ bảng chứ không theo số dòng xóa.
-- Index khớp đúng thứ tự ORDER BY: Index Scan, không Sort, dừng sau một lô.
-- Phải kèm id: không có id thì Postgres phải Sort lại theo id trong phạm vi expires_at < cutoff.
-- Giá phải trả: mỗi lần refresh INSERT thêm một mục index (bảng nay có 5 index).
-- Không CONCURRENTLY: Flyway chạy migration trong transaction; bảng còn nhỏ, chấp nhận khóa ghi lúc tạo.
-- =============================================================================

CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at, id);
