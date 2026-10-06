# Architecture Decision Records (ADR)

Ghi lại các **quyết định kỹ thuật** mà đặc tả nghiệp vụ (`docs/01`–`05`) không quy định, buộc phải tự thiết kế khi triển khai. Quy tắc nghiệp vụ vẫn lấy từ `docs/02-business-rules.md`; ADR chỉ chốt *cách cài*.

**Quy tắc:**
- Mỗi ADR một file `000n-<slug>.md`, gồm metadata (**Trạng thái / Ngày / Liên quan / Triển khai**) và các mục *Bối cảnh → Quyết định → Lý do* (kèm phương án bị loại) *→ Hệ quả*.
- ADR **bất biến** sau khi `Accepted`. Đổi quyết định thì tạo ADR mới ghi `Supersedes ADR-000n`, và chỉ sửa ADR cũ ở dòng trạng thái thành `Superseded by ADR-000m`.
- Một quyết định ảnh hưởng toàn hệ thống được tách thành ADR riêng, kể cả khi phát sinh trong cùng một việc.
- Bộ ADR trước đợt reset backend (0001–0003 về JWT) đã bị xóa; số được đánh lại từ 0001. ADR-0003 hiện tại (JWT gắn phiên DB) là quyết định mới, không liên quan tới file cũ cùng số.
- Quyết định đang chờ ADR (ghi ở `docs/convention/backend/INDEX.md`): cách trả cảnh báo không chặn (convention 06), chiến lược khóa đồng thời (convention 07 §7.3).

## Danh sách

| # | Tiêu đề | Trạng thái | Ngày | Liên quan |
|---|---|---|---|---|
| [0001](0001-audit-recording.md) | Ghi audit nghiệp vụ bằng `AuditRecorder` tường minh | Accepted | 2026-10-03 | BR-QT-15, 16, BR-QT-01, BR-TK-16; convention 08 |
| [0002](0002-client-ip-behind-proxy.md) | Xác định IP client sau reverse proxy | Accepted | 2026-10-03 | ADR-0001; `audit_logs.ip_address`, `sessions.ip_address` |
| [0003](0003-jwt-session-authentication.md) | Xác thực bằng JWT HS256 gắn phiên DB, RBAC 7 role và phạm vi chi nhánh | Accepted (mục 4, 9 → 0005; mục 8 → 0006; mục 2 phần không xóa → 0008) | 2026-10-04 | BR-TK-09, 11, 13, 14, 17; BR-QT-01, 03, 05, 06, 11, 13; BR-TN-06; `00-method` §3.1, §3.3; identity A1, A4, Q1; convention 02 |
| [0004](0004-system-config-reading.md) | Đọc tham số [CFG] bằng `ConfigKey` + cache trong bộ nhớ, seed toàn bộ bằng Flyway | Accepted | 2026-10-04 | BR-QT-13; convention 03, 06; `06-module-contracts` §1; ADR-0003 |
| [0005](0005-public-paths-anonymous.md) | Path public luôn xử lý như chưa đăng nhập | Accepted | 2026-10-05 | Supersedes ADR-0003 mục 4, 9; BR-TK-12, 17, BR-QT-15; `00-method` §3.1; identity A4 |
| [0006](0006-customer-owner-scope.md) | Phạm vi dữ liệu theo role — CUSTOMER không đi qua phạm vi chi nhánh | Accepted | 2026-10-05 | Supersedes ADR-0003 mục 8; 01 A02; 04 nguyên tắc 8; `00-method` §3.3; convention 02 |
| [0007](0007-scheduled-jobs.md) | Chạy job định kỳ bằng Spring `@Scheduled` trên một instance | Accepted | 2026-10-06 | ST01–ST20; convention 07 §7.4, 08 §8.1; ADR-0001 |
| [0008](0008-session-cleanup.md) | Dọn phiên đăng nhập đã hết hạn | Accepted | 2026-10-06 | Supersedes ADR-0003 mục 2 (phần không xóa); BR-TK-11, 13, 14; 04 nguyên tắc 2; ADR-0007 |
