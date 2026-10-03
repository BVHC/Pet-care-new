# Backend Convention — Index

Quy ước code cho `BE/` (Java 21, Spring Boot 3.5). Nghiệp vụ lấy từ `docs/01`–`05` (tra qua `docs/INDEX.md`); quyết định kỹ thuật mà đặc tả không quy định nằm ở `docs/adr/`. Trạng thái thực tế của code: `docs/architecture/system-overview.md`.

> Cập nhật theo đặc tả **v16** (2026-10-03). Mã rule là `BR-<MÃ>-<số>`; whitelist FSM lấy từ bảng chuyển trạng thái đánh số của `03-state-machines.md`.

| # | File | Nội dung |
|---|---|---|
| 1 | [01-package-structure.md](01-package-structure.md) | `platform/` và `module/<feature>/`, ranh giới giữa các module |
| 2 | [02-layering-and-dto.md](02-layering-and-dto.md) | Controller → Service → TransitionHandler → Repository; DTO, envelope, entity |
| 3 | [03-naming-convention.md](03-naming-convention.md) | Tên class, DTO, method transition, mã rule, mã audit |
| 4 | [04-exception-handling.md](04-exception-handling.md) | 5 exception dùng chung, `ErrorCode`, `ErrorResponse` |
| 5 | [05-fsm-pattern.md](05-fsm-pattern.md) | `StateMachineBase`, chép bảng 03 thành transition map |
| 6 | [06-validation.md](06-validation.md) | Bean Validation ở controller, rule `BR-…` ở service; cảnh báo không chặn |
| 7 | [07-transaction-management.md](07-transaction-management.md) | Ranh giới transaction, hệ quả liên aggregate, outbox, khóa đồng thời |
| 8 | [08-logging-and-audit.md](08-logging-and-audit.md) | Application log, `AuditRecorder`, danh mục thao tác phải audit |
| 9 | [09-testing.md](09-testing.md) | Unit / integration test, Testcontainers, `FsmTransitionTestBase` |

## Còn TBD (cần chốt trước khi dùng)

| Việc | Ở đâu | Cách chốt |
|---|---|---|
| Tên package của từng module | 01 | Chốt khi tạo module, ghi vào `system-overview.md` §3 |
| Tên method transition / command | 03 | Đề xuất trong plan của module, người dùng duyệt |
| Cách trả "cảnh báo, không chặn" về client | 06 | ADR |
| Chiến lược khóa cho quota, sức chứa chuồng, trừ kho | 07 | ADR |
