# Org & Store API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Organization & Store Management (Module 03): vòng đời Organization,
> vòng đời Store (FSM-2), giờ hoạt động, tài nguyên vật chất.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#3`), `docs/02-business-rules.md`
> (RULE-03-01→08), `docs/03-state-machines.md` (FSM-2 Store), `docs/04-glossary.md` (`03`),
> `docs/05-domain-model.md` (`4.3`), `docs/06-erd.md` (`organizations`/`stores`/
> `operating_hours`/`store_resources`).
> **Contract máy đọc:** [`./openapi/org-store-v1.yaml`](./openapi/org-store-v1.yaml) (OpenAPI 3.1).
> **Skill áp dụng:** `designing-apis` — API là business contract, không phải CRUD của DB.

**Legend:** `CONFIRMED` = có trong docs (kèm nguồn) · `ASSUMPTION (A#)` = suy luận
hợp lý từ docs, chưa xác nhận · `TBD (Q#)` = cần PO/Product quyết định (xem mục E).
Mọi method/path/field-name/envelope trong tài liệu này đều là **PROPOSED design**
(docs không chứa URL hay JSON shape nào). Error envelope DECIDED theo
`docs/convention/backend/04-exception-handling`.

**Đóng băng phạm vi:**
- `ManageOrganizationPolicy` / `ConfigureStorePolicy` **loại khỏi v1**: ERD không có
  bảng policy nào (không chỗ lưu, không shape) — tương tự cách auth-v1 loại refresh
  endpoint. TBD Q7.
- Override giá/khả dụng dịch vụ-sản phẩm (`store_products`/`store_services`,
  `ConfigureServicePrice`/`ConfigureProductPrice`/`ConfigureServiceAvailability`)
  thuộc Module 05 Catalog — contract catalog định nghĩa, ở đây chỉ tham chiếu
  như precondition của `ActivateStore` (RULE-03-02). `ConfigureStoreService` (`01#3`)
  và `ConfigureServiceAvailability` (`01#5`) là hai mặt của cùng một concern;
  contract này không định nghĩa lại để tránh định nghĩa trùng lặp (xem Q8).
- Nghiệp vụ kho (`ManageWarehouse`, `CENTRAL_WAREHOUSE`) thuộc Module 12 — file này
  chỉ bao phủ Facility lifecycle dùng chung; `facilityType` được giữ để phân biệt.

---

## A. Confirmed Org & Store API (12 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /organizations` | `CreateOrganization` — `01#3`, RULE-03-01 |
| 2 | `GET /organizations` | (list, derived cho quản trị Tenant) |
| 3 | `GET /organizations/{id}` | (detail) |
| 4 | `PATCH /organizations/{id}` | `UpdateOrganization` — `01#3`, RULE-03-01 |
| 5 | `POST /organizations/{id}/stores` | `CreateStore` (→ DRAFT) — `01#3`, RULE-03-01/02 |
| 6 | `GET /organizations/{id}/stores` | (list stores trong Org) |
| 7 | `GET /stores/{id}` | (detail) |
| 8 | `PATCH /stores/{id}` | `UpdateStore` — `01#3` |
| 9 | `POST /stores/{id}/activate` | `ActivateStore` — `01#3`, RULE-03-02/03, FSM-2 |
| 10 | `POST /stores/{id}/suspend` | `SuspendStore` — `01#3`, RULE-03-03/04, FSM-2 |
| 11 | `POST /stores/{id}/deactivate` | `DeactivateStore` — `01#3`, RULE-03-03/04, FSM-2 |
| 12 | `POST /stores/{id}/archive` | `ArchiveStore` — `01#3`, RULE-03-06, FSM-2 |
| 13 | `PUT /stores/{id}/operating-hours` | `ConfigureOperatingHour` — `01#3`, RULE-03-02/07 |
| 14 | `GET /stores/{id}/resources` | (list tài nguyên) |
| 15 | `POST /stores/{id}/resources` | `ConfigureStoreResource` — `01#3`, RULE-03-02/08 |
| 16 | `PATCH /stores/{id}/resources/{rid}` | `ConfigureStoreResource` — `01#3` |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreateOrganization | PlatformAdmin (A1) | Code unique | RULE-03-01 (1 Org cha; cách ly 100%) | POST | `/organizations` | Bearer | `SUPER_ADMIN` (tenant lifecycle là PLATFORM scope) | `[*] → ACTIVE` (org status; không có FSM) | 409 nhờ UK `code` | `01#3` ghi OrgAdmin — mâu thuẫn bootstrap, xem A1 |
| UpdateOrganization | OrgAdmin | Org tồn tại | RULE-03-01 | PATCH | `/organizations/{id}` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình | none | Idempotent | Thuế/mã: sửa được gì TBD Q9 |
| CreateStore | OrgAdmin | Org tồn tại | RULE-03-01 (đúng 1 Org cha) | POST | `/organizations/{id}/stores` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình | `[*] → DRAFT` + `StoreCreated` | 409 nhờ UK `(organization_id, code)` | `facilityType` CONFIRMED (`RETAIL_STORE`/`CENTRAL_WAREHOUSE`) |
| UpdateStore | OrgAdmin / StoreManager | Store tồn tại | — | PATCH | `/stores/{id}` | Bearer | OrgAdmin (all fields); StoreManager (chỉ operational fields, A2) | none | Idempotent | Field-level scope là A2 |
| ActivateStore | OrgAdmin | Cấu hình đủ (RULE-03-02) | RULE-03-02/03 | POST | `/stores/{id}/activate` | Bearer | `ORGANIZATION_ADMIN` Org mình (`SUPER_ADMIN` all) | `DRAFT/SUSPENDED/DEACTIVATED → ACTIVE` + `StoreActivated` | Idempotent (đã ACTIVE vẫn 200) | Guards cần catalog module 05 (cross-domain) |
| SuspendStore | OrgAdmin | Đang `ACTIVE` | RULE-03-03/04 (khóa booking/walk-in/order mới) | POST | `/stores/{id}/suspend` | Bearer | như trên | `ACTIVE → SUSPENDED` + `StoreSuspended` | Idempotent | Đơn/lịch dở dang xử lý theo RULE-03-04 (modules 06/14) |
| DeactivateStore | OrgAdmin | Đang `ACTIVE` | RULE-03-03/04 | POST | `/stores/{id}/deactivate` | Bearer | như trên | `ACTIVE → DEACTIVATED` + `StoreDeactivated` | Idempotent | Như suspend + mức chặn cao hơn |
| ArchiveStore | OrgAdmin | 4 điều kiện RULE-03-06 | RULE-03-06 | POST | `/stores/{id}/archive` | Bearer | như trên | `SUSPENDED/DEACTIVATED → ARCHIVED` + `StoreArchived` | Idempotent | Check 4 zeros cross-module (06/14/12/tài chính) |
| ConfigureOperatingHour | OrgAdmin (/StoreManager A3) | Store tồn tại | RULE-03-02/07 | PUT | `/stores/{id}/operating-hours` | Bearer | OrgAdmin; StoreManager TBD Q10 | none | Idempotent (replace-all) | `dayOfWeek` 1=Chủ nhật→7=Thứ bảy CONFIRMED theo ERD (khác ISO, FE lưu ý) |
| ConfigureStoreResource | OrgAdmin (/StoreManager A3) | Store tồn tại | RULE-03-02/08 | POST/PATCH | `/stores/{id}/resources…` | Bearer | OrgAdmin; StoreManager TBD Q10 | none | POST 409 nhờ UK `(store_id, resource_code)` | Types: `CLINIC_ROOM`, `GROOMING_TABLE`, `ULTRASOUND_MACHINE` (+`XRAY` theo domain 4.3) |

**ASSUMPTIONS dùng chung:** A1 `POST /organizations` = `SUPER_ADMIN` (tenant lifecycle
thuộc PLATFORM scope theo bảng Actor; `01#3` ghi OrgAdmin là mâu thuẫn bootstrap vì
Org chưa tồn tại thì chưa có OrgAdmin) · A2 StoreManager `PATCH /stores/{id}` chỉ
operational fields (address/phone), không đổi `code`/`organizationId` · A3 cấu hình
giờ + resource do OrgAdmin; StoreManager có được cấu hình không TBD Q10.

---

## C. Detailed endpoint contract

### C1. Organizations (proposed)

- **`POST /organizations`** — Request `{code (req + UK CONFIRMED), name (req),
  taxCode?, address?}`. Response `201 {organizationId, code, name, status: "ACTIVE"}`.
  Status: `201` · `400` · `401` · `403` (non-SUPER_ADMIN) · `409` trùng code.
- **`PATCH /organizations/{id}`** — Request `{name?, taxCode?, address?}` (TBD Q9:
  `code` có sửa được không — ERD UK nên mặc định immutable).
  Status: `200` · `400` · `401` · `403` ngoài Org · `404`.
- **`GET /organizations`, `GET /organizations/{id}`** — List scope-filtered
  (SUPER_ADMIN thấy all; OrgAdmin chỉ Org mình). Pagination PROPOSED như iam-v1 (Q7 chung).

### C2. Store lifecycle (proposed)

- **`POST /organizations/{id}/stores`** — Request `{code (req, UK trong Org CONFIRMED),
  name (req), facilityType (req: `RETAIL_STORE`/`CENTRAL_WAREHOUSE` CONFIRMED),
  address (req CONFIRMED), phone (req CONFIRMED)}`.
  Response `201 {storeId, organizationId, status: "DRAFT", facilityType}`.
  Status: `201` · `400` · `401` · `403` · `404` org không tồn tại · `409` trùng code trong Org.
- **`POST /stores/{id}/activate`** — Guards CONFIRMED (RULE-03-02, check trước khi
  chuyển): (1) `operating_hours` hợp lệ; (2) ≥1 `store_resources` khả dụng;
  (3) danh mục dịch vụ khả dụng (module 05 — cross-domain dependency, thiếu → `409`
  PROPOSED `STORE_NOT_READY`). Không thỏa → `409`, không đổi state.
- **`POST /stores/{id}/suspend` / `/deactivate`** — Guards: chỉ từ `ACTIVE`
  (FSM-2 CONFIRMED). Hiệu ứng RULE-03-04 (khóa nhận mới) do các modules 06/07/14
  enforce — store contract chỉ đổi state + event.
- **`POST /stores/{id}/archive`** — Guards CONFIRMED (RULE-03-06, 4 zeros):
  0 active orders · 0 active appointments · `PhysicalQuantity == 0` · 0 công nợ/hoàn
  mở. Vi phạm điều kiện nào → `409` + `details.failedCondition` (shape PROPOSED).
  Chỉ từ `SUSPENDED`/`DEACTIVATED` (FSM-2 CONFIRMED) — archive từ `ACTIVE` → 409.
- **Status chung lifecycle:** `200` · `401` · `403` · `404` · `409` sai trạng thái/
  thiếu điều kiện (`INVALID_STATE_TRANSITION` / `BUSINESS_RULE_VIOLATION`).

### C3. Operating hours & resources (proposed)

- **`PUT /stores/{id}/operating-hours`** — Request `{hours: [{dayOfWeek (1–7 CONFIRMED,
  1=Chủ nhật), openTime?, closeTime?, isClosed}]}` (replace-all, idempotent).
  Validation CONFIRMED từ ERD: UK `(store_id, day_of_week)`; `openTime < closeTime`
  khi không đóng cửa (derived). Status: `200` · `400` · `401` · `403` · `404`.
- **`POST /stores/{id}/resources`** — Request `{resourceCode (req, UK trong Store
  CONFIRMED), resourceName (req), resourceType (req CONFIRMED enum), isActive?}`.
  Response `201`. Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`PATCH /stores/{id}/resources/{rid}`** — Request `{resourceName?, isActive?}`
  (không đổi `resourceCode` — UK immutable, A4). Status như trên trừ 409.

---

## D. Security & reliability (chỉ điểm có gốc docs)

1. Cách ly Tenant 100%: mọi query store-scoped phải lọc `organization_id` (+ `store_id`);
   vi phạm scope → `ACCESS_DENIED_SCOPE_MISMATCH` (RULE-02-01).
2. Enforce có gốc rule: DRAFT-mặc định, 3 điều kiện activate, 4 zeros archive,
   UK constraints bằng DB constraint (409).
3. Cross-domain (derived, không phải business mới): activate check catalog (05);
   archive check orders (14)/appointments (06)/stock (12)/debts (15–17);
   suspend/deactivate effect do 06/07/14 enforce. Transaction boundary + kiểm tra
   "còn gì đang mở" thuộc về BE implementation, contract chỉ chốt điều kiện.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | docs không có URL |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | Org status (`ACTIVE`/`SUSPENDED`/`INACTIVE`) có FSM không? Ai suspend Org? | TBD (PO) | ERD có status, không có FSM/op |
| Q4 | `code` (org/store/resource) có sửa được sau tạo không? | TBD (PO) — PROPOSED immutable | ERD UK |
| Q5 | StoreManager được cấu hình giờ/resource không (A3)? | TBD (PO) | `01#3` chỉ gán OrgAdmin cho Configure* |
| Q6 | `UpdateStore` của StoreManager giới hạn fields nào (A2)? | TBD (PO) | `01#3` gán cả 2 actors, thiếu ranh giới |
| Q7 | Policy storage + endpoints (`ManageOrganizationPolicy`/`ConfigureStorePolicy`) | TBD (PO/BE) — loại khỏi v1 vì ERD không có bảng | ERD |
| Q8 | `ConfigureStoreService` (03) vs `ConfigureServiceAvailability` (05): một hay hai? | TBD (PO) — PROPOSED dồn về 05 | `01#3` vs `01#5` |
| Q9 | Organization update được sửa fields nào? | TBD (PO) | docs không chi tiết |
| Q10 | Xem Q5 (StoreManager Configure*) | TBD (PO) | — |

---

## F. OpenAPI 3.1 YAML

Single source of truth cho contract máy đọc:
[`./openapi/org-store-v1.yaml`](./openapi/org-store-v1.yaml).
