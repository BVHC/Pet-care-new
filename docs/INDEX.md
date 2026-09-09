# Pet Care - Tài liệu dự án

> **Version:** 1.0  
> **Date:** 2026-08-18  
> **Scope:** 25 modules, 17 FSMs, 6 tuần

---

## 📚 Danh mục tài liệu

> Danh sách dưới đối chiếu 100% với các file thực tế đang có trong `docs/` (đã loại bỏ `07-team-timeline.md` và `MAPPING.md` — hai file này không tồn tại trên đĩa). `docs/adr/` và `docs/api/` hiện là thư mục rỗng (chưa có nội dung) — khi có file, bổ sung section riêng theo đúng mẫu ở cuối file này, đừng giả định nội dung của chúng.

| # | File | Mô tả | Agent nên đọc khi nào? |
|---|------|--------|--------------------------|
| 1 | [01-business-operations.md](01-business-operations.md) | Danh mục nghiệp vụ + Actors (25 modules) | **Trước khi** bắt đầu bất kỳ module/feature mới — để xác định đúng Actor, phạm vi nghiệp vụ và dải RULE-ID liên quan |
| 2 | [02-business-rules.md](02-business-rules.md) | Toàn bộ Business Rules (~150+ RULE-ID) | **Trước khi** implement bất kỳ business logic/validation nào — mọi guard/invariant trong code phải trích dẫn đúng RULE-ID từ đây |
| 3 | [03-state-machines.md](03-state-machines.md) | FSM Specifications (17 FSM) | **Trước khi** implement transition/state của bất kỳ FSM nào — không tự suy ra transition ngoài sơ đồ mermaid đã đặc tả |
| 4 | [04-glossary.md](04-glossary.md) | Ubiquitous Language (Entity/Command/Event/Role) | Tra cứu **trước khi** đặt tên biến, class, API, DTO, event — tránh lệch thuật ngữ nghiệp vụ |
| 5 | [05-domain-model.md](05-domain-model.md) | Domain Model (DDD Aggregates/Entities/Value Objects) | **Trước khi** thiết kế Aggregate/Entity mới hoặc cần xác định ranh giới bounded context |
| 6 | [06-erd.md](06-erd.md) | ERD & Database Schema Spec — nguồn chân lý cấu trúc CSDL | **Trước khi** viết migration SQL, tạo bảng mới, hoặc thay đổi schema |
| 7 | [architecture/system-overview.md](architecture/system-overview.md) | Kiến trúc kỹ thuật **thực tế của codebase** (tech stack, luồng request, layer BE/FE, deployment, known gaps) — khác với 01-06 vốn đặc tả nghiệp vụ mục tiêu | **Trước khi** viết code trong module đã có sẵn (đối chiếu pattern hiện có) hoặc khi cần biết stack/luồng request/giới hạn kỹ thuật hiện tại (Redis chưa dùng, FE có 2 HTTP client song song, `auth`/`users` mới có entity...) |
| 8 | [convention/backend/](convention/backend/) | Backend Convention — 9 file: [package structure](convention/backend/01-package-structure.md), [layering & DTO](convention/backend/02-layering-and-dto.md), [naming](convention/backend/03-naming-convention.md), [exception handling](convention/backend/04-exception-handling.md), [FSM pattern](convention/backend/05-fsm-pattern.md), [validation](convention/backend/06-validation.md), [transaction](convention/backend/07-transaction-management.md), [logging/audit](convention/backend/08-logging-and-audit.md), [testing](convention/backend/09-testing.md) | **Trước khi** viết bất kỳ code backend nào — mọi Controller/Service/Entity/DTO/Exception/FSM handler phải tuân theo quy ước ở đây |

---

## 🎯 Tóm tắt Project (Updated per C-565e7b1)

| Aspect | Value |
|--------|-------|
| **Phases** | 4 |
| **Tuần** | 6 |
| **Modules** | 25 |
| **FSMs** | 17 (Account, Store, Caregiver, Appointment/BookingHold, Order, Invoice, Payment, Refund, Membership, Package, StockTransfer, PurchaseRequest, PurchaseOrder, Incident, Grooming, Consent, QueueEntry) |
| **Roles** | 9 (SUPER_ADMIN, ORGANIZATION_ADMIN, STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER, CUSTOMER) |
| **Rules** | ~150+ |

---

## ⚠️ Vùng CHƯA chốt (NEEDS-DECISION)

> **Đọc mục này trước khi code.** Đây là danh sách các điểm mà tài liệu chưa có quyết định cuối cùng. Agent **không được tự suy đoán** câu trả lời — nếu công việc đụng tới một trong các mục dưới, phải dừng lại và hỏi lại người dùng/PM trước khi implement.

1. **5 module chưa xếp Phase** (chi tiết ở [📦 Modules § Chưa xếp Phase](#-modules-25-modules)): Procurement (RULE-13-xx), Membership & Loyalty (RULE-19-xx), Package (RULE-20-xx), Incident (RULE-21-xx), Consent & Privacy (RULE-22-xx). Nội dung nghiệp vụ/FSM của 5 module này đã đầy đủ và ổn định trong `01`, `02`, `03` — chỉ riêng **thứ tự đưa vào sprint (Phase 1-4)** là chưa chốt.
2. **4 câu hỏi mở ở cuối file** (xem [❓ Questions](#-questions)): git repo mới hay continue từ base, reset hay migrate database, giữ nguyên hay refactor FE, ngày demo chính thức. Đây là quyết định vận hành/triển khai, không phải quyết định nghiệp vụ, nhưng vẫn ảnh hưởng trực tiếp tới cách agent thao tác trên repo (ví dụ: có được `git init` lại hay không, có được xóa dữ liệu DB hay không).

---

## 👥 Roles (9 Roles - FINAL per Reconciliation 2026-08-24)

> **Lưu ý:** Mã vai trò chuẩn hóa là `ORGANIZATION_ADMIN` (khớp `user_role_enum` trong `docs/06-erd.md`), không phải `ORG_ADMIN`. Định danh **D-01** thuộc về Decision "Invoice Partial Refund Representation" (`docs/04-glossary.md` §0, `docs/03-state-machines.md#6`) — không liên quan đến role; dòng "Approved: D-01" trước đây gán nhầm cho mục này đã được gỡ bỏ.

### Platform Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **SUPER_ADMIN** | Quản trị toàn hệ thống, setup platform | Dev/PO |

### Organization Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **ORGANIZATION_ADMIN** | Quản lý chuỗi cửa hàng, setup organization | Chủ chuỗi |

### Store Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **STORE_MANAGER** | Quản lý vận hành store | Chủ store |
| **FINANCE_STAFF** | Thu ngân, thanh toán, hoàn tiền | Dưới Manager |
| **INVENTORY_STAFF** | Quản lý kho, chuyển kho | Dưới Manager |
| **RECEPTIONIST** | Tiếp khách, check-in, tạo đơn | Lễ tân |
| **VETERINARIAN** | Khám bệnh, kê đơn, tiêm phòng | Bác sĩ |
| **GROOMER** | Làm đẹp thú cưng | Stylist |

### User Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **CUSTOMER** | Khách hàng | Đặt lịch, mua hàng, quản lý thú cưng |

### RBAC Summary

| Scope | Roles |
|-------|-------|
| Platform | SUPER_ADMIN |
| Organization | ORGANIZATION_ADMIN |
| Store | STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER |
| Warehouse | *(không có role riêng — `INVENTORY_STAFF` vận hành kép STORE/WAREHOUSE theo `docs/04-glossary.md` §RoleScope)* |
| Customer | CUSTOMER |

---

## 📦 Modules (25 modules)

> Đối chiếu với Ma trận Đối soát 6 Chiều tại `docs/06-erd.md` §5 (bảng 25 dòng, Module 01→25). Bản trước của mục này chỉ liệt kê 20 module riêng biệt (22 dòng, do Organizations/Stores dùng chung `RULE-03-xx`) — thiếu hẳn Module 13 (Procurement), 19 (Membership & Loyalty), 20 (Package), 21 (Incident), 22 (Consent & Privacy). Đã bổ sung đủ 25 module bên dưới.

### Phase 1: Foundation (W1)

| Module | Docs | Commands |
|--------|-------|----------|
| Auth + OTP | 01, 02 | RULE-01-xx |
| Users | 01, 02 | RULE-02-xx |
| Organizations | 01, 02 | RULE-03-xx |
| Stores | 01, 02 | RULE-03-xx |
| Pets | 01, 02 | RULE-04-xx |
| Products | 01, 02 | RULE-05-xx |
| **Inventory (FSM)** | 01, 02, 03 | RULE-12-xx |

### Phase 2: Core Domain (W2)

| Module | Docs | Commands |
|--------|-------|----------|
| **Appointments (FSM)** | 01, 02, 03 | RULE-06-xx |
| **Orders (FSM)** | 01, 02, 03 | RULE-14-xx |
| **Payments (FSM)** | 01, 02, 03 | RULE-16-xx |

### Phase 3: Commerce (W3-W4)

| Module | Docs | Commands |
|--------|-------|----------|
| **Invoices (FSM)** | 01, 02, 03 | RULE-15-xx |
| **Refunds (FSM)** | 01, 02, 03 | RULE-17-xx |
| Clinical | 01, 02 | RULE-09-xx |
| Promotions | 01, 02 | RULE-18-xx |
| Vaccinations | 01, 02 | RULE-10-xx |

### Phase 4: Polish (W5-W6)

| Module | Docs | Commands |
|--------|-------|----------|
| FE Integration | - | - |
| Notifications | 01, 02 | RULE-23-xx |
| Walk-ins | 01, 02, 03 | RULE-07-xx |
| **Grooming (FSM)** | 01, 02, 03 | RULE-11-xx |
| Workforce | 01, 02 | RULE-08-xx |
| Reports | 01, 02 | RULE-24-xx |
| Audit Logs | 01, 02 | RULE-25-xx |

### Chưa xếp Phase (cần quyết định roadmap)

> 5 module này tồn tại đầy đủ trong `docs/01-business-operations.md` (§13, 19-22), có Business Rules (`02`) và (trừ Procurement dùng chung FSM StockTransfer/PurchaseOrder) có FSM riêng (`03`), nhưng chưa được xếp vào Phase nào trong roadmap 6 tuần gốc. **NEEDS-DECISION** — cần Product/PM xác nhận Phase trước khi lập kế hoạch sprint.

| Module | Docs | Commands | FSM liên quan |
|--------|-------|----------|----------------|
| Procurement | 01, 02, 03 | RULE-13-xx | PurchaseRequest, PurchaseOrder |
| **Membership & Loyalty (FSM)** | 01, 02, 03 | RULE-19-xx | Membership |
| **Package (FSM)** | 01, 02, 03 | RULE-20-xx | Package |
| **Incident (FSM)** | 01, 02, 03 | RULE-21-xx | Incident |
| **Consent & Privacy (FSM)** | 01, 02, 03 | RULE-22-xx | Consent |

---

## 🔄 FSMs (17 FSMs - per `docs/03-state-machines.md`)

> Số lượng FSM đã tăng từ 8 lên 17 theo `docs/03-state-machines.md` hiện hành (17 heading `## N.` từ Account đến Walk-in Queue). Bảng dưới đối chiếu lại toàn bộ; module của mỗi FSM nay đã đầy đủ trong bảng [📦 Modules](#-modules-25-modules) ở trên (kể cả 5 module bổ sung: Procurement, Membership, Package, Incident, Consent) — nhưng 5 module đó vẫn **chưa xếp Phase**, nên cột **Phase** của các FSM tương ứng bên dưới ghi "*chưa gán*" và tham chiếu tới mục "Chưa xếp Phase".

### Chi tiết FSMs:

| FSM | States | Docs | Phase | Notable |
|-----|--------|------|-------|---------|
| **Account** | PENDING_VERIFICATION → ACTIVE ⇄ LOCKED → DEACTIVATED | 03 | W1 | 4 states |
| **Store** | DRAFT → ACTIVE ⇄ SUSPENDED/DEACTIVATED → ARCHIVED | 03 | W1 | 5 states |
| **Caregiver** | INVITED → ACTIVE → REVOKED / EXPIRED / REJECTED | 03 | W1 (Pets) | 5 states, TTL mời 7 ngày |
| **Appointment / BookingHold** | HOLDING(15m) → BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED / CANCELLED / NO_SHOW / ABORTED | 03 | W2 | 4+8 states, +RescheduleAppointment nguyên tử |
| **Order** | PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED / CANCELLED / REFUNDED | 03 | W2 | 8 states, +ProcessOrderTimeout (15m), +CancelOrderWithRefund |
| **Payment** | PENDING → PROCESSING → SUCCESS ⇄ PARTIALLY_REFUNDED → REFUNDED / FAILED / CANCELLED | 03 | W2 | 7 states |
| **Invoice** | DRAFT → ISSUED → PAID / VOID / CANCELLED | 03 | W3-W4 | 5 states — **KHÔNG còn** `PARTIALLY_PAID`/`REFUNDED` (Decision D-01) |
| **Refund** | REQUESTED → APPROVED → PROCESSING → COMPLETED / FAILED / REJECTED | 03 | W3-W4 | 6 states, +RetryRefund (max 3), +30-day window |
| **Membership** | ACTIVE → UPGRADED / EXPIRED | 03 | *chưa gán* | 3 states, module Membership & Loyalty (xem "Chưa xếp Phase") |
| **Package** | PURCHASED → ACTIVATED → PARTIALLY_CONSUMED ⇄ → FULLY_CONSUMED / CANCELLED / EXPIRED | 03 | *chưa gán* | 6 states, module Package (xem "Chưa xếp Phase") |
| **StockTransfer** | REQUESTED → APPROVED → IN_TRANSIT → RECEIVED / DISCREPANCY_RECORDED / REJECTED / CANCELLED | 03 | W1 (Inventory) | 7 states, Maker-Checker |
| **PurchaseRequest** | DRAFT → SUBMITTED → APPROVED / REJECTED / CANCELLED | 03 | *chưa gán* | 5 states, module Procurement (xem "Chưa xếp Phase") |
| **PurchaseOrder** | ISSUED → PARTIALLY_RECEIVED → RECEIVED / CLOSED / CANCELLED | 03 | *chưa gán* | 5 states, module Procurement (xem "Chưa xếp Phase") |
| **Incident** | RECORDED → CLASSIFIED → UNDER_INVESTIGATION → ESCALATED / RESOLVED → CLOSED | 03 | *chưa gán* | 6 states, module Incident (xem "Chưa xếp Phase") |
| **Grooming** | WAITING → IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL → COMPLETED / REJECTED / CANCELLED / ABORTED | 03 | W5-W6 | 7 states |
| **Consent** | REQUESTED → ACTIVE → REVOKED / EXPIRED (+ Break-Glass `[*] → ACTIVE`) | 03 | *chưa gán* | 4 states, module Consent & Privacy (xem "Chưa xếp Phase"), liên quan Clinical (Module 09) |
| **Walk-in Queue** | WAITING → CALLED → IN_SERVICE → COMPLETED / NO_SHOW / CANCELLED | 03 | W5-W6 (Walk-ins) | 6 states, 3-Call No-Show Rule |

---

## 🔗 Links

### Source code

```
../BE/                     # Backend Java Spring Boot
../FE/                     # Frontend (copy từ Pet-care)
```

---

## 📅 Timeline

| Phase | Tuần | Milestone |
|-------|------|-----------|
| **1. Foundation** | W1 | M1: CRUD foundation |
| **2. Core Domain** | W2 | M2: FSMs working |
| **3. Commerce** | W3-W4 | M3: Full commerce |
| **4. Polish** | W5-W6 | M4: Demo ready |

---

## ❓ Questions

1. **Git repo:** Tạo mới hay continue từ base?
2. **Database:** Reset hay migrate từ base?
3. **FE:** Giữ nguyên hay refactor?
4. **Demo date:** Khi nào demo?
