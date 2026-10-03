# Architecture Decision Records (ADR)

Ghi lại các **quyết định kỹ thuật** mà đặc tả nghiệp vụ (`docs/01`–`05`) không quy định, buộc phải tự thiết kế khi triển khai. Quy tắc nghiệp vụ vẫn lấy từ `docs/02-business-rules.md`; ADR chỉ chốt *cách cài*.

**Quy tắc:**
- Mỗi ADR một file `000n-<slug>.md`, gồm metadata (**Trạng thái / Ngày / Liên quan / Triển khai**) và các mục *Bối cảnh → Quyết định → Lý do* (kèm phương án bị loại) *→ Hệ quả*.
- ADR **bất biến** sau khi `Accepted`. Đổi quyết định thì tạo ADR mới ghi `Supersedes ADR-000n`, và chỉ sửa ADR cũ ở dòng trạng thái thành `Superseded by ADR-000m`.
- Một quyết định ảnh hưởng toàn hệ thống được tách thành ADR riêng, kể cả khi phát sinh trong cùng một việc.
- Bộ ADR trước đợt reset backend (0001–0003 về JWT) đã bị xóa; số được đánh lại từ 0001. Link tới file cũ `0003-refresh-token-cleanup-job.md` trong convention 07 đã được gỡ, nên ADR mới tiếp theo dùng số 0003.
- Quyết định đang chờ ADR (ghi ở `docs/convention/backend/INDEX.md`): cách trả cảnh báo không chặn (convention 06), chiến lược khóa đồng thời (convention 07 §7.3), cách chạy job định kỳ (convention 07 §7.4).

## Danh sách

| # | Tiêu đề | Trạng thái | Ngày | Liên quan |
|---|---|---|---|---|
| [0001](0001-audit-recording.md) | Ghi audit nghiệp vụ bằng `AuditRecorder` tường minh | Accepted | 2026-10-03 | BR-QT-15, 16, BR-QT-01, BR-TK-16; convention 08 |
| [0002](0002-client-ip-behind-proxy.md) | Xác định IP client sau reverse proxy | Accepted | 2026-10-03 | ADR-0001; `audit_logs.ip_address`, `sessions.ip_address` |
