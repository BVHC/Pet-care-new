# Architecture Decision Records (ADR)

Tài liệu này ghi lại các **quyết định kỹ thuật** (không phải nghiệp vụ — xem `docs/05-domain-model.md` §1 cho 4 "Khóa Quyết định Kiến trúc" `D-01`..`D-04` cấp nghiệp vụ) không được `docs/00-07` quy định cụ thể, buộc phải tự thiết kế khi triển khai code. Mỗi ADR là bất biến sau khi `Accepted` — nếu quyết định thay đổi, tạo ADR mới `Supersedes`/`Superseded by` ADR cũ, không sửa lại ADR gốc.

## Danh sách

| # | Tiêu đề | Trạng thái | Ngày |
|---|---------|-----------|------|
| [0001](0001-jwt-refresh-token-storage-strategy.md) | Chiến lược lưu trữ JWT Refresh Token & Access Token Blacklist | Accepted | 2026-09-11 |
| [0002](0002-jwt-blacklist-fail-open-policy.md) | Chính sách Fail-Open cho Access Token Blacklist khi Redis không khả dụng | Accepted | 2026-09-11 |

## Bối cảnh chung

Cả 2 ADR trên phát sinh từ việc triển khai hạ tầng `platform/security` (JWT infra hardening) — xem kế hoạch triển khai gốc tại `C:\Users\Admin\.claude\plans\c-tr-c-docs-silly-curry.md`. Lý do cần tự quyết định: `docs/05-domain-model.md` (RULE-02-04/07) và `docs/03-state-machines.md` (FSM Account) yêu cầu Access Token/Refresh Token phải bị thu hồi ngay khi `Logout`/`LockAccount`/`DeactivateAccount`, nhưng không có bảng Session/RefreshToken/Blacklist nào được đặc tả trong `docs/06-erd.md` — đây là gap hạ tầng thuần kỹ thuật, không thuộc phạm vi đặc tả nghiệp vụ.
