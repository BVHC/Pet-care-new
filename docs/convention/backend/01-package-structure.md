[← Backend Convention Index](INDEX.md)

# 1. Package Structure

Package theo **feature/module**. Mỗi module có layer con riêng; hạ tầng dùng chung nằm ở `platform/`.

```
com.petcare
├── platform/                 # hạ tầng dùng chung, không biết module nào tồn tại
│   ├── config/               # TimeConfig (Clock), TraceIdFilter, TraceContext, CORS, OpenAPI
│   ├── exception/            # PlatformException + 5 lớp con, ErrorCode, GlobalExceptionHandler
│   ├── fsm/                  # Transitionable, StateMachineBase
│   ├── model/                # ApiResponse, PageResponse, ErrorResponse, TimestampedEntity, CreatedAtEntity
│   ├── audit/                # AuditRecorder, AuditEntry, AuditPrincipal (ADR-0001)
│   └── security/             # SecurityConfig (tạm), entry point / access denied handler
└── module/
    └── <feature>/
        ├── controller/ service/ repository/ entity/ dto/
        ├── mapper/           # MapStruct interface
        ├── fsm/              # {Entity}TransitionHandler — chỉ khi đối tượng có bảng trong 03-state-machines.md
        └── exception/        # CHỈ khi thỏa tiêu chí ở 04-exception-handling.md §4.2
```

`platform/` hiện có gì: `docs/architecture/system-overview.md` §3. Chưa có: JWT/RBAC/phạm vi chi nhánh, gửi `notification_outbox`, job `@Scheduled`.

## Module

Một module = một mã module trong Bảng 2 của `docs/INDEX.md` (TK, QT, CN, KH…). Chỉ làm module **tầng 1 và 2**; tầng 3 (NS và các UC/ST liệt kê ở cuối Bảng 2) không có rule/model nên không tạo package.

- Tên package: một danh từ tiếng Anh số ít, chữ thường. Chốt khi tạo module và ghi vào `system-overview.md` §3.
- Aggregate thuộc module nào: theo cột *Nhóm* của `04-domain-model.md`. Một số nhóm dùng chung cho hai module (TK+QT, TN+KB, BH+TG); khi tạo package phải ghi rõ bảng nào thuộc package nào, để mỗi bảng chỉ có một module sở hữu entity/repository.

## Quy tắc phụ thuộc

- Module **không import `entity`/`repository` của module khác**. Muốn đọc hoặc ghi dữ liệu của module khác thì gọi `service` của module đó.
- Hệ quả liên aggregate (Phụ lục của `03-state-machines.md`) do service của aggregate phát sự kiện gọi sang service của aggregate bị kéo theo, trong cùng transaction (xem [07](07-transaction-management.md)).
- `platform/` không phụ thuộc ngược vào `module/*`. Khi platform cần thông tin của module thì khai báo interface trong platform để module implement, như `AuditPrincipal` (module TK implement).

---

[← Backend Convention Index](INDEX.md) · [Tiếp: 2. Layering & DTO →](02-layering-and-dto.md)
