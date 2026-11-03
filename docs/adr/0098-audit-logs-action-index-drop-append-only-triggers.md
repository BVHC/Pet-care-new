# ADR-0098: Index `audit_logs (action, created_at)`, bỏ luật R3, gỡ hai trigger chỉ-thêm-mới (V31, trả D021)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-03
- **Liên quan:** identity-v1 #36 `GET /api/audit-logs`; ADR-0038 (mục 3 luật R3, mục 6 bảng index — ADR này sửa cả hai), ADR-0001 (ghi audit), BR-QT-15, BR-QT-16; `docs/05-erd.md` §13 dòng 6 (quyết định trigger ở V1). Nợ: D021 (đóng); D020 (đếm + OFFSET) **không** đổi.
- **Triển khai:** `V31__audit_logs_action_index_drop_append_only_triggers.sql`; `identity/dto/{AuditLogFilter, AuditLogFilterValidator}`, `identity/controller/AuditLogController`, `identity/repository/AuditLogQuery` (chỉ chữ); `docs/api/generator/m_identity.py` → `identity-v1.{md,yaml}`; FE: `AdminAuditPage`, mock `identity-admin.mock.ts`. Test: `AuditLogFilterValidationTest`, `AuditLogQueryServiceTest`, `AuditLogViewIT` (R3 → 200; `queriesUseExpectedIndex` có hai tổ hợp `action`), `SchemaMigrationIT.auditLogsTriggersAreDroppedAndActionIndexExists`.

Người dùng chốt ngày 2026-11-03: phương án A của D021 (index + bỏ hẳn R3), index `(action, created_at)`, **gỡ cả hai trigger** của `audit_logs`, V31 (BE-1 lấy, báo BE-2), không đo chi phí INSERT (đồ án).

## Vấn đề

1. `audit_logs` không có index bắt đầu bằng `action`. Lọc `action` không kèm người / đối tượng phải quét `ix_audit_logs_created_at` rồi lọc từng dòng, nên ADR-0038 mục 3 đặt luật R3: `action` không có mốc chọn lọc thì cần `from` + `to` cách nhau ≤ 31 ngày (hằng kỹ thuật `MAX_ACTION_WINDOW`, không phải [CFG], đặc tả không có số này). ADMIN không tra được một hành động xuyên năm bằng một request.
2. V1 cài BR-QT-16 ("chỉ thêm mới") bằng hai trigger `trg_audit_logs_no_update_delete`, `trg_audit_logs_no_truncate` gọi `forbid_audit_log_change()`.

## Quyết định

1. **Thêm** `ix_audit_logs_action_created_at ON audit_logs (action, created_at)`. `ORDER BY created_at DESC, id DESC` đi ngược index, `id` vẫn qua incremental sort như ở `ix_audit_logs_created_at`. Index thường (Flyway chạy trong transaction nên không dùng `CONCURRENTLY`).
2. **Bỏ hẳn R3**: xóa `MAX_ACTION_WINDOW`, `hasSelectiveAnchor`, `hasBoundedWindow`, nhánh R3 và `ACTION_WINDOW_MESSAGE`. Còn R1 (`from > to`) và R2 (`entityId` không kèm `entityType`). Lọc `action` một mình hoặc kèm khoảng bất kỳ đều 200. Bảng index (ADR-0038 mục 6) đổi hàng `action`: câu trang và câu đếm đều dùng `ix_audit_logs_action_created_at`.
3. **Gỡ** hai trigger và hàm `forbid_audit_log_change()`. V1 không sửa (checksum); V31 `DROP TRIGGER … ; DROP FUNCTION …`.

## Hệ quả và giới hạn

- **Bỏ R3 không làm mọi truy vấn nhanh.** Lọc action rất hiếm (`ACCOUNT_LOCKED`…) nhanh. Lọc action phổ biến (`LOGIN_SUCCEEDED`) theo cả năm vẫn đọc nhiều dòng, và `count(*)` cho `totalElements` cộng OFFSET vẫn còn (D020). Đã chấp nhận: đồ án, ADMIN là người dùng duy nhất.
- **Chi phí ghi:** mỗi lần ghi audit thêm một mục vào một B-tree. Chưa đo; trong mỗi `action` `created_at` tăng dần nên chèn ở mép phải. Đường ghi nóng nhất là đăng nhập (`LoginService`, `LoginAttemptService`).
- **Gỡ trigger làm mất bảo đảm ở DB cho BR-QT-16.** Ứng dụng kết nối bằng owner của DB, nên từ V31 bất kỳ câu SQL nào (người vận hành, script, lỗi code) sửa, xóa hoặc `TRUNCATE` được `audit_logs`; ADR-0001 từng dựa vào trigger này để bảo đảm audit không bị xóa kể cả khi code sai. Bảo đảm còn lại nằm ở tầng ứng dụng: `AuditLogEntity` `@Immutable`, `AuditLogRepository` chỉ có `save`, `AuditLogController` chỉ có GET (method khác → 405), không endpoint sửa / xóa. Nội dung BR-QT-16 trong `02-business-rules.md` **không đổi** (rule nói về chức năng của hệ thống, vẫn đúng); phần "Request cố tình sửa hoặc xóa bị từ chối" chỉ còn đúng cho request HTTP, không còn đúng cho SQL trực tiếp. Mục đích gỡ trigger do người dùng chọn; ADR này không ghi lý do ngoài việc đó.
- IT dùng chung database vẫn không dọn `audit_logs` (đánh dấu bằng `reason` ngẫu nhiên hoặc cửa sổ năm riêng); việc có thể dọn nay là khả năng, không phải thay đổi của ADR này.
- ADR-0019 (L95, "audit không xóa được (trigger)") và ADR-0063 là lịch sử, giữ nguyên.

## Đã cân nhắc

- **Giữ R3 và nới cửa sổ (366 ngày):** vẫn một luật hình thức ngoài đặc tả, vẫn chặn tra dài; loại.
- **Index một phần cho vài `action`:** phải bảo trì danh sách, `action` mới không được hưởng, hai chế độ gây rối; loại.
- **Index `(action, created_at, id)`:** tránh incremental sort nhưng index to hơn, lợi ích nhỏ; loại.
- **Chỉ gỡ trigger TRUNCATE:** không được chọn; người dùng chọn gỡ cả hai.

## Kiểm chứng

- `AuditLogFilterValidationTest`, `AuditLogQueryServiceTest` (unit, xanh 2026-11-03).
- `AuditLogViewIT`, `SchemaMigrationIT`, `OpenApiContractParityIT`: **chưa chạy** (Docker tắt lúc viết); chạy trước khi merge. `mvn test-compile` xanh; `python docs/api/generator/generate.py && node docs/api/check-contracts.mjs` xanh (232 endpoint).
- FE: `AdminAuditPage.test.tsx` xanh; `identity-admin.mock.test.ts` 139/142, 3 đỏ vốn có do `V27__refresh_tokens_access_ttl.sql` (test đọc seed SQL gặp `DELETE FROM`), không thuộc ADR này.
