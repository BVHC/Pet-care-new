# Pet Care — Hướng dẫn đọc tài liệu

> **Mục đích duy nhất của file này:** giúp agent xác định nhanh nên đọc file nào trong `docs/` trước khi thực hiện một loại tác vụ cụ thể. File này **không** chứa kế hoạch triển khai, phase/sprint, timeline hay quyết định vận hành dự án — những nội dung đó (nếu cần) thuộc phạm vi quản lý dự án, nằm ngoài tài liệu kỹ thuật này.

## Cách dùng tài liệu

- Mỗi file trong `docs/` là **nguồn chân lý (source of truth)** cho đúng một loại nội dung (xem bảng dưới). Không tự suy diễn hay bịa thêm business rule, RULE-ID, hay transition FSM ngoài những gì đã đặc tả trong mermaid diagram.
- `01`–`06` đặc tả **nghiệp vụ mục tiêu** (business/domain/schema). `architecture/system-overview.md` mô tả **hiện trạng kỹ thuật thực tế của codebase** — hai loại có thể lệch nhau tại một thời điểm; khi code vào module đã tồn tại, luôn đối chiếu cả hai trước khi bắt đầu.
- `docs/adr/` ghi lại các **quyết định kỹ thuật** không được 00–07 quy định cụ thể (khác với `D-01`..`D-04` ở `docs/05-domain-model.md` §1 — đó là khóa quyết định kiến trúc cấp *nghiệp vụ*) — xem `docs/adr/README.md`. `docs/api/` hiện vẫn là thư mục rỗng (scaffold, chưa có nội dung) — không giả định nội dung của nó.

---

## 📚 Danh mục tài liệu
> Danh sách dưới đối chiếu 100% với các file thực tế đang có trong `docs/` (đã loại bỏ `07-team-timeline.md` và `MAPPING.md` — hai file này không tồn tại trên đĩa). `docs/adr/` hiện là thư mục rỗng (chưa có nội dung) — khi có file, bổ sung section riêng theo đúng mẫu ở cuối file này, đừng giả định nội dung của chúng.

| # | File | Mô tả | Agent nên đọc khi nào? |
|---|------|--------|--------------------------|
| 0 | [00-requirements.md](00-requirements.md) | Requirements Specification — nguồn chân lý (Source of Truth) cho toàn bộ Purpose/Scope/Actors/Business Goals/FR/NFR/SEC/Business Constraints/Out of Scope | **Trước khi** đọc bất kỳ file nào khác trong 01-07 — xác định đúng phạm vi và mục tiêu nghiệp vụ trước khi đi vào chi tiết Rule/Operation/FSM/Domain/ERD |
| 1 | [01-business-operations.md](01-business-operations.md) | Danh mục nghiệp vụ + Actors (25 modules) | **Trước khi** bắt đầu bất kỳ module/feature mới — để xác định đúng Actor, phạm vi nghiệp vụ và dải RULE-ID liên quan |
| 2 | [02-business-rules.md](02-business-rules.md) | Toàn bộ Business Rules (217 RULE-ID) | **Trước khi** implement bất kỳ business logic/validation nào — mọi guard/invariant trong code phải trích dẫn đúng RULE-ID từ đây |
| 3 | [03-state-machines.md](03-state-machines.md) | FSM Specifications (19 FSM) | **Trước khi** implement transition/state của bất kỳ FSM nào — không tự suy ra transition ngoài sơ đồ mermaid đã đặc tả |
| 4 | [04-glossary.md](04-glossary.md) | Ubiquitous Language (Entity/Command/Event/Role) | Tra cứu **trước khi** đặt tên biến, class, API, DTO, event — tránh lệch thuật ngữ nghiệp vụ |
| 5 | [05-domain-model.md](05-domain-model.md) | Domain Model (DDD Aggregates/Entities/Value Objects) | **Trước khi** thiết kế Aggregate/Entity mới hoặc cần xác định ranh giới bounded context |
| 6 | [06-erd.md](06-erd.md) | ERD & Database Schema Spec — nguồn chân lý cấu trúc CSDL | **Trước khi** viết migration SQL, tạo bảng mới, hoặc thay đổi schema |
| 7 | [architecture/system-overview.md](architecture/system-overview.md) | Kiến trúc kỹ thuật **thực tế của codebase** (tech stack, luồng request, layer BE/FE, deployment, known gaps) — khác với 01-06 vốn đặc tả nghiệp vụ mục tiêu | **Trước khi** viết code trong module đã có sẵn (đối chiếu pattern hiện có) hoặc khi cần biết stack/luồng request/giới hạn kỹ thuật hiện tại (Redis chưa dùng, FE có 2 HTTP client song song, `auth`/`users` mới có entity...) |
| 8 | [convention/backend/](convention/backend/) | Backend Convention — 9 file: [package structure](convention/backend/01-package-structure.md), [layering & DTO](convention/backend/02-layering-and-dto.md), [naming](convention/backend/03-naming-convention.md), [exception handling](convention/backend/04-exception-handling.md), [FSM pattern](convention/backend/05-fsm-pattern.md), [validation](convention/backend/06-validation.md), [transaction](convention/backend/07-transaction-management.md), [logging/audit](convention/backend/08-logging-and-audit.md), [testing](convention/backend/09-testing.md) | **Trước khi** viết bất kỳ code backend nào — mọi Controller/Service/Entity/DTO/Exception/FSM handler phải tuân theo quy ước ở đây |
| 9 | [api/00-method.md](api/00-method.md) + [api/check-contracts.mjs](api/check-contracts.mjs) | Phương pháp viết & checklist kiểm chứng contract (V1–V5) + script máy (chạy `node docs/api/check-contracts.mjs`) | **Trước khi** viết/sửa bất kỳ contract nào — làm đúng pha thiết kế → viết → kiểm chứng |
| 10 | [api/auth-v1.md](api/auth-v1.md) + [api/openapi/auth-v1.yaml](api/openapi/auth-v1.yaml) | Auth API contract (Module 01 + CreateStaff) — md cho người đọc, yaml cho máy đọc | **Trước khi** implement/chấm contract auth |
| 11 | [api/iam-v1.md](api/iam-v1.md) + [api/openapi/iam-v1.yaml](api/openapi/iam-v1.yaml) | IAM API contract (Module 02) — user/role/lifecycle | **Trước khi** implement/chấm contract IAM |
| 12 | [api/org-store-v1.md](api/org-store-v1.md) + [api/openapi/org-store-v1.yaml](api/openapi/org-store-v1.yaml) | Org & Store API contract (Module 03, FSM-2) | **Trước khi** implement/chấm contract org/store |
| 13 | [api/customer-pet-v1.md](api/customer-pet-v1.md) + [api/openapi/customer-pet-v1.yaml](api/openapi/customer-pet-v1.yaml) | Customer & Pet API contract (Module 04, FSM-3) | **Trước khi** implement/chấm contract customer/pet |
| 14 | [api/catalog-v1.md](api/catalog-v1.md) + [api/openapi/catalog-v1.yaml](api/openapi/catalog-v1.yaml) | Catalog API contract (Module 05 — owner availability override) | **Trước khi** implement/chấm contract catalog |
| 15 | [api/appointment-v1.md](api/appointment-v1.md) + [api/openapi/appointment-v1.yaml](api/openapi/appointment-v1.yaml) | Appointment API contract (Module 06, FSM-4.1/4.2) | **Trước khi** implement/chấm contract appointment |
| 16 | [api/queue-v1.md](api/queue-v1.md) + [api/openapi/queue-v1.yaml](api/openapi/queue-v1.yaml) | Queue API contract (Module 07, FSM-17 + walk-in bridge) | **Trước khi** implement/chấm contract queue |
| 17 | [api/workforce-v1.md](api/workforce-v1.md) + [api/openapi/workforce-v1.yaml](api/openapi/workforce-v1.yaml) | Workforce API contract (Module 08) | **Trước khi** implement/chấm contract workforce |
| 18 | [api/clinical-v1.md](api/clinical-v1.md) + [api/openapi/clinical-v1.yaml](api/openapi/clinical-v1.yaml) | Clinical API contract (Module 09 + cross-store consent) | **Trước khi** implement/chấm contract clinical |
| 19 | [api/vaccination-v1.md](api/vaccination-v1.md) + [api/openapi/vaccination-v1.yaml](api/openapi/vaccination-v1.yaml) | Vaccination API contract (Module 10, barcode guard) | **Trước khi** implement/chấm contract vaccination |
| 20 | [api/grooming-v1.md](api/grooming-v1.md) + [api/openapi/grooming-v1.yaml](api/openapi/grooming-v1.yaml) | Grooming API contract (Module 11, FSM-15 + D-02) | **Trước khi** implement/chấm contract grooming |
| 21 | [api/inventory-v1.md](api/inventory-v1.md) + [api/openapi/inventory-v1.yaml](api/openapi/inventory-v1.yaml) | Inventory API contract (Module 12, FSM-11) | **Trước khi** implement/chấm contract inventory |
| 22 | [api/procurement-v1.md](api/procurement-v1.md) + [api/openapi/procurement-v1.yaml](api/openapi/procurement-v1.yaml) | Procurement API contract (Module 13, FSM-12/13) | **Trước khi** implement/chấm contract procurement |
| 23 | [api/order-v1.md](api/order-v1.md) + [api/openapi/order-v1.yaml](api/openapi/order-v1.yaml) | Order API contract (Module 14, FSM-5 + D-03) | **Trước khi** implement/chấm contract order |
| 24 | [api/invoice-v1.md](api/invoice-v1.md) + [api/openapi/invoice-v1.yaml](api/openapi/invoice-v1.yaml) | Invoice API contract (Module 15, FSM-6 + D-01) | **Trước khi** implement/chấm contract invoice |
| 25 | [api/payment-v1.md](api/payment-v1.md) + [api/openapi/payment-v1.yaml](api/openapi/payment-v1.yaml) | Payment API contract (Module 16, FSM-7) | **Trước khi** implement/chấm contract payment |
| 26 | [api/refund-v1.md](api/refund-v1.md) + [api/openapi/refund-v1.yaml](api/openapi/refund-v1.yaml) | Refund API contract (Module 17, FSM-8) | **Trước khi** implement/chấm contract refund |
| 27 | [api/promotion-v1.md](api/promotion-v1.md) + [api/openapi/promotion-v1.yaml](api/openapi/promotion-v1.yaml) | Promotion API contract (Module 18, stateless rules) | **Trước khi** implement/chấm contract promotion |
| 28 | [api/membership-v1.md](api/membership-v1.md) + [api/openapi/membership-v1.yaml](api/openapi/membership-v1.yaml) | Membership API contract (Module 19, FSM-9) | **Trước khi** implement/chấm contract membership |
| 29 | [api/package-v1.md](api/package-v1.md) + [api/openapi/package-v1.yaml](api/openapi/package-v1.yaml) | Package API contract (Module 20, FSM-10) | **Trước khi** implement/chấm contract package |
| 30 | [api/incident-v1.md](api/incident-v1.md) + [api/openapi/incident-v1.yaml](api/openapi/incident-v1.yaml) | Incident API contract (Module 21, FSM-14) | **Trước khi** implement/chấm contract incident |
| 31 | [api/consent-v1.md](api/consent-v1.md) + [api/openapi/consent-v1.yaml](api/openapi/consent-v1.yaml) | Consent & Privacy API contract (Module 22; cross-store ở clinical) | **Trước khi** implement/chấm contract consent |
| 32 | [api/notification-v1.md](api/notification-v1.md) + [api/openapi/notification-v1.yaml](api/openapi/notification-v1.yaml) | Notification API contract (Module 23, inbox read-only) | **Trước khi** implement/chấm contract notification |
| 33 | [api/report-v1.md](api/report-v1.md) + [api/openapi/report-v1.yaml](api/openapi/report-v1.yaml) | Report API contract (Module 24, read-only) | **Trước khi** implement/chấm contract report |
| 34 | [api/audit-v1.md](api/audit-v1.md) + [api/openapi/audit-v1.yaml](api/openapi/audit-v1.yaml) | Audit API contract (Module 25, append-only read) | **Trước khi** implement/chấm contract audit |
| 35 | [07-requirement-traceability-matrix.md](07-requirement-traceability-matrix.md) | Ma trận truy vết đầy đủ: Requirement (00) ↔ Rule (02) ↔ Operation (01) ↔ FSM (03) ↔ Domain/ERD (05/06), theo 25 module | Tra cứu **khi** cần xác định Rule/Command/FSM/bảng ERD nào hậu thuẫn một Requirement cụ thể, hoặc ngược lại |
| 36 | [adr/](adr/) | Architecture Decision Records — quyết định kỹ thuật không được 00-07 quy định cụ thể (ví dụ: chiến lược lưu JWT refresh token, chính sách fail-open khi Redis sập) | Tra cứu **khi** cần biết lý do đằng sau một quyết định hạ tầng đã chốt, hoặc **trước khi** tự quyết định lại một vấn đề kỹ thuật tương tự chưa được 00-07 đặc tả |

---

## 🎯 Quy mô hệ thống

| Aspect | Value |
|--------|-------|
| **Modules nghiệp vụ** | 25 |
| **FSM (State Machines)** | 19 |
| **Roles** | 9 (SUPER_ADMIN, ORGANIZATION_ADMIN, STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER, CUSTOMER) |
| **Business Rules** | 217 (RULE-ID) |

---

## 👥 Roles (9 Roles)

> **Lưu ý:** Mã vai trò chuẩn hóa là `ORGANIZATION_ADMIN` (khớp `user_role_enum` trong `docs/06-erd.md`), không phải `ORG_ADMIN`.

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

> Đối chiếu 1:1 với 25 section trong `docs/01-business-operations.md` và Ma trận Đối soát 6 Chiều tại `docs/06-erd.md` §5. Cột **FSM** chỉ ra module nào có state machine tương ứng trong `docs/03-state-machines.md` (đọc thêm file `03` cho các module này).

| # | Module | Docs cần đọc | Dải RULE-ID | FSM |
|---|--------|--------------|-------------|-----|
| 1 | Authentication & OTP | 01, 02, 03 | RULE-01-xx | Account |
| 2 | Identity & Access Management | 01, 02 | RULE-02-xx | — |
| 3 | Organization & Store Management | 01, 02, 03 | RULE-03-xx | Store |
| 4 | Customer & Pet Management | 01, 02, 03 | RULE-04-xx | CaregiverInvitation/Delegation |
| 5 | Service & Product Catalog | 01, 02 | RULE-05-xx | — |
| 6 | Appointment & Scheduling | 01, 02, 03 | RULE-06-xx | Appointment, BookingHold |
| 7 | Walk-in & Queue Management | 01, 02, 03 | RULE-07-xx | Walk-in Queue |
| 8 | Workforce Management | 01, 02 | RULE-08-xx | — |
| 9 | Veterinary / Clinical Management | 01, 02 | RULE-09-xx | — |
| 10 | Vaccination Management | 01, 02 | RULE-10-xx | — |
| 11 | Grooming Management | 01, 02, 03 | RULE-11-xx | Grooming |
| 12 | Inventory & Warehouse Management | 01, 02, 03 | RULE-12-xx | StockTransfer |
| 13 | Procurement Management | 01, 02, 03 | RULE-13-xx | PurchaseRequest, PurchaseOrder |
| 14 | Order Management | 01, 02, 03 | RULE-14-xx | Order |
| 15 | Billing & Invoice Management | 01, 02, 03 | RULE-15-xx | Invoice |
| 16 | Payment Management | 01, 02, 03 | RULE-16-xx | Payment |
| 17 | Refund Management | 01, 02, 03 | RULE-17-xx | Refund |
| 18 | Promotion & Voucher Management | 01, 02, 03 | RULE-18-xx | Promotion, Voucher |
| 19 | Membership & Loyalty Management | 01, 02, 03 | RULE-19-xx | Membership |
| 20 | Package Management | 01, 02, 03 | RULE-20-xx | Package |
| 21 | Incident Management | 01, 02, 03 | RULE-21-xx | Incident |
| 22 | Consent & Privacy Management | 01, 02, 03 | RULE-22-xx | Consent |
| 23 | Notification Management | 01, 02 | RULE-23-xx | — |
| 24 | Reporting & Analytics | 01, 02 | RULE-24-xx | — |
| 25 | Audit Management | 01, 02 | RULE-25-xx | — |

---

## 🔄 FSMs (19 FSMs — per `docs/03-state-machines.md`)

| # | FSM | Module | States | Notable |
|---|-----|--------|--------|---------|
| 1 | **Account** | 01 · Authentication & OTP | PENDING_VERIFICATION → ACTIVE ⇄ LOCKED → DEACTIVATED | 4 states |
| 2 | **Store** | 03 · Organization & Store Management | DRAFT → ACTIVE ⇄ SUSPENDED/DEACTIVATED → ARCHIVED | 5 states |
| 3 | **CaregiverInvitation/Delegation** | 04 · Customer & Pet Management | INVITED → ACTIVE → REVOKED / EXPIRED / REJECTED | 5 states, TTL mời 7 ngày |
| 4 | **Appointment / BookingHold** | 06 · Appointment & Scheduling | HOLDING(15m) → BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED / CANCELLED / NO_SHOW / ABORTED | 4+8 states, +RescheduleAppointment nguyên tử |
| 5 | **Order** | 14 · Order Management | PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED / CANCELLED / REFUNDED | 8 states, +ProcessOrderTimeout (15m), +CancelOrderWithRefund |
| 6 | **Invoice** | 15 · Billing & Invoice Management | DRAFT → ISSUED → PAID / VOID / CANCELLED | 5 states — **KHÔNG** có `PARTIALLY_PAID`/`REFUNDED` (Decision D-01) |
| 7 | **Payment** | 16 · Payment Management | PENDING → PROCESSING → SUCCESS ⇄ PARTIALLY_REFUNDED → REFUNDED / FAILED / CANCELLED | 7 states |
| 8 | **Refund** | 17 · Refund Management | REQUESTED → APPROVED → PROCESSING → COMPLETED / FAILED / REJECTED | 6 states, +RetryRefund (max 3), +30-day window |
| 9 | **Membership** | 19 · Membership & Loyalty Management | ACTIVE → UPGRADED / EXPIRED | 3 states |
| 10 | **Package** | 20 · Package Management | PURCHASED → ACTIVATED → PARTIALLY_CONSUMED ⇄ → FULLY_CONSUMED / CANCELLED / EXPIRED | 6 states |
| 11 | **StockTransfer** | 12 · Inventory & Warehouse Management | REQUESTED → APPROVED → IN_TRANSIT → RECEIVED / DISCREPANCY_RECORDED / REJECTED / CANCELLED | 7 states, Maker-Checker |
| 12 | **PurchaseRequest** | 13 · Procurement Management | DRAFT → SUBMITTED → APPROVED / REJECTED / CANCELLED | 5 states |
| 13 | **PurchaseOrder** | 13 · Procurement Management | ISSUED → PARTIALLY_RECEIVED → RECEIVED / CLOSED / CANCELLED | 5 states |
| 14 | **Incident** | 21 · Incident Management | RECORDED → CLASSIFIED → UNDER_INVESTIGATION → ESCALATED / RESOLVED → CLOSED | 6 states |
| 15 | **Grooming** | 11 · Grooming Management | WAITING → IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL → COMPLETED / REJECTED / CANCELLED / ABORTED | 7 states |
| 16 | **Consent** | 22 · Consent & Privacy Management | REQUESTED → ACTIVE → REVOKED / EXPIRED (+ Break-Glass `[*] → ACTIVE`) | 4 states |
| 17 | **Walk-in Queue** | 07 · Walk-in & Queue Management | WAITING → CALLED → IN_SERVICE → COMPLETED / NO_SHOW / CANCELLED | 6 states, 3-Call No-Show Rule |
| 18 | **Promotion** | 18 · Promotion & Voucher Management | DRAFT → ACTIVE ⇄ PAUSED → EXPIRED | 4 states, bổ sung Phase 3 Traceability Audit |
| 19 | **Voucher** | 18 · Promotion & Voucher Management | ACTIVE ⇄ DISABLED → EXPIRED | 3 states, bổ sung Phase 3 Traceability Audit |

---

## 🔗 Mã nguồn

```
../BE/                     # Backend Java Spring Boot
../FE/                     # Frontend (copy từ Pet-care)
```
