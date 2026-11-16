-- =============================================================================
-- V54 — trả D045: cột phiên bản cho `orders` (`@Version` của SalesOrder) — docs/adr/0132.
-- Số V54: BE-1 làm thay BE-2 (bảng `orders` của sales, như V42 / ADR-0116), lấy sau V52; V53 giữ cho D062, V49 để
-- dành cho BE-2. BE-2 chưa xác nhận số này (D059).
--
-- Mọi đường ghi Order khóa dòng trước khi đọc (ADR-0044) nên version luôn khớp; cột chỉ là lưới an toàn: ghi trên bản
-- entity đã nạp trước khi lấy khóa (D045) trúng 0 dòng → 409 thay vì ghi đè `total_amount` / `status` cũ.
-- DEFAULT 0: Postgres chỉ sửa metadata, không viết lại bảng; mọi INSERT hiện có (test, seed) không nhắc `version`
-- vẫn chạy. Không CHECK / index: Hibernate tự tăng khi UPDATE, không có truy vấn nào lọc theo cột này.
-- =============================================================================

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
