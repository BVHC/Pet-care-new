[← Backend Convention Index](../backend-convention.md)

# 1. Package Structure

Package theo **feature/module**, khớp 1:1 với 25 Bounded Context đã đặc tả ở `05-domain-model.md`. Mỗi module có layer con riêng; hạ tầng dùng chung nằm ở `platform/`.

```
com.petcare.backend
├── platform/               # security(JWT/RBAC/scope filter), exception, audit, fsm, outbox, config
└── module/
    ├── appointment/
    │   ├── controller/ service/ repository/ entity/ dto/
    │   ├── mapper/             # MapStruct interface
    │   ├── fsm/                # AppointmentTransitionHandler
    │   └── exception/          # CHỈ khi thoả tiêu chí ở mục 4 (Exception & Error Handling)
    ├── invoice/ payment/ refund/ ...   # đủ 25 module
```

**Quy tắc:**
- Không import trực tiếp `entity`/`repository` của module khác — muốn lấy dữ liệu module khác phải qua `service`/API nội bộ của module đó (giữ ranh giới Bounded Context).
- `platform/` không được phụ thuộc ngược vào bất kỳ `module/*` nào.

---

[← Backend Convention Index](../backend-convention.md) · [Tiếp: 2. Layering & DTO →](02-layering-and-dto.md)
