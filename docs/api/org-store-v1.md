# Org & Store API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Organization & Store Management (Module 03): vòng đời Organization,
> vòng đời Store (FSM-2), giờ hoạt động, tài nguyên vật chất.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#3`), `docs/02-business-rules.md`
> (RULE-03-01→10), `docs/03-state-machines.md` (FSM-2 Store), `docs/04-glossary.md` (`03`),
> `docs/05-domain-model.md` (`4.3`), `docs/06-erd.md` (`organizations`/`stores`/
> `operating_hours`/`store_resources`/`organization_policies`/`store_policies`).
> **Contract máy đọc:** [`./openapi/org-store-v1.yaml`](./openapi/org-store-v1.yaml) (OpenAPI 3.1).
> **Skill áp dụng:** `designing-apis` — API là business contract, không phải CRUD của DB.

**Legend:** `CONFIRMED` = có trong docs (kèm nguồn) · `ASSUMPTION (A#)` = suy luận
hợp lý từ docs, chưa xác nhận · `TBD (Q#)` = cần PO/Product quyết định (xem mục E).
Mọi method/path/field-name/envelope trong tài liệu này đều là **PROPOSED design**
(docs không chứa URL hay JSON shape nào). Error envelope DECIDED theo
`docs/convention/backend/04-exception-handling`.

**Đóng băng phạm vi:**
- ~~`ManageOrganizationPolicy` / `ConfigureStorePolicy` loại khỏi v1~~ **ĐÃ GIẢI
  (2026-09-18, xem Q7):** cả hai đều có bảng ERD riêng (`organization_policies`,
  `store_policies`) và endpoint `GET/PATCH .../policy` — xem endpoint #17–20 ở mục A.
- Override giá/khả dụng dịch vụ-sản phẩm (`store_products`/`store_services`,
  `ConfigureServicePrice`/`ConfigureProductPrice`/`ConfigureServiceAvailability`)
  thuộc Module 05 Catalog — contract catalog định nghĩa, ở đây chỉ tham chiếu
  như precondition của `ActivateStore` (RULE-03-02). `ConfigureStoreService` (`01#3`)
  và `ConfigureServiceAvailability` (`01#5`) là hai mặt của cùng một concern;
  contract này không định nghĩa lại để tránh định nghĩa trùng lặp (xem Q8).
- Nghiệp vụ kho (`ManageWarehouse`, `CENTRAL_WAREHOUSE`) thuộc Module 12 — file này
  chỉ bao phủ Facility lifecycle dùng chung; `facilityType` được giữ để phân biệt.

---

## A. Confirmed Org & Store API (20 endpoints)

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
| 17 | `GET /organizations/{id}/policy` | (đọc `OrganizationPolicy` hiện hành, hoặc mặc định nếu chưa cấu hình) — `01#3`, RULE-03-09 |
| 18 | `PATCH /organizations/{id}/policy` | `ManageOrganizationPolicy` — `01#3`, RULE-03-09 |
| 19 | `GET /stores/{id}/policy` | (đọc `StorePolicy` hiện hành, hoặc mặc định nếu chưa cấu hình) — `01#3`, RULE-03-10 |
| 20 | `PATCH /stores/{id}/policy` | `ConfigureStorePolicy` — `01#3`, RULE-03-10 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreateOrganization | PlatformAdmin (A1) | Code unique | RULE-03-01 (1 Org cha; cách ly 100%) | POST | `/organizations` | Bearer | `SUPER_ADMIN` (tenant lifecycle là PLATFORM scope) | `[*] → ACTIVE` (org status; không có FSM) | 409 nhờ UK `code` | `01#3` ghi OrgAdmin — mâu thuẫn bootstrap, xem A1 |
| UpdateOrganization | OrgAdmin | Org tồn tại | RULE-03-01 | PATCH | `/organizations/{id}` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình | none | Idempotent | Thuế/mã: sửa được gì TBD Q9 |
| CreateStore | OrgAdmin | Org tồn tại | RULE-03-01 (đúng 1 Org cha) | POST | `/organizations/{id}/stores` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình | `[*] → DRAFT` + `StoreCreated` | 409 nhờ UK `(organization_id, code)` | `facilityType` CONFIRMED (`RETAIL_STORE`/`CENTRAL_WAREHOUSE`) |
| UpdateStore | OrgAdmin / StoreManager | Store tồn tại, **chưa `ARCHIVED`** (bổ sung 2026-09-17) | RULE-03-06 (chặn khi `ARCHIVED`, xem Decision Log `docs/02-business-rules.md` mục 03) | PATCH | `/stores/{id}` | Bearer | OrgAdmin (mọi Store trong Org mình); StoreManager (chỉ Store mình quản lý) — **cùng 1 shape 3 field** (`name`/`address`/`phone`, khớp `openapi #UpdateStoreRequest`, Q6 ĐÃ GIẢI) | none | Idempotent | Khác biệt actor nằm ở **phạm vi Store** (RULE-02-05), không phải field-set — xem Decision Log Q6 dưới |
| ActivateStore | OrgAdmin | Cấu hình đủ (RULE-03-02) | RULE-03-02/03 | POST | `/stores/{id}/activate` | Bearer | `ORGANIZATION_ADMIN` Org mình (`SUPER_ADMIN` all) | `DRAFT/SUSPENDED/DEACTIVATED → ACTIVE` + `StoreActivated` | Idempotent (đã ACTIVE vẫn 200) | Guards cần catalog module 05 (cross-domain) |
| SuspendStore | OrgAdmin | Đang `ACTIVE` | RULE-03-03/04 (khóa booking/walk-in/order mới) | POST | `/stores/{id}/suspend` | Bearer | như trên | `ACTIVE → SUSPENDED` + `StoreSuspended` | Idempotent | Đơn/lịch dở dang xử lý theo RULE-03-04 (modules 06/14) |
| DeactivateStore | OrgAdmin | Đang `ACTIVE` | RULE-03-03/04 | POST | `/stores/{id}/deactivate` | Bearer | như trên | `ACTIVE → DEACTIVATED` + `StoreDeactivated` | Idempotent | Như suspend + mức chặn cao hơn |
| ArchiveStore | OrgAdmin | 4 điều kiện RULE-03-06 | RULE-03-06 | POST | `/stores/{id}/archive` | Bearer | như trên | `SUSPENDED/DEACTIVATED → ARCHIVED` + `StoreArchived` | Idempotent | Check 4 zeros cross-module (06/14/12/tài chính) |
| ConfigureOperatingHour | **StoreManager (chỉ, ĐÃ GIẢI Q5/Q10 — 2026-09-18)** | Store tồn tại, **chưa `ARCHIVED`** (RULE-03-06, dữ liệu cấu hình con) | RULE-03-02/06/07 | PUT | `/stores/{id}/operating-hours` | Bearer | `STORE_MANAGER` đúng Store mình quản lý — **KHÔNG** có ngoại lệ `SUPER_ADMIN`/`ORGANIZATION_ADMIN` (khác mọi endpoint Store khác), bám literal `01#3` chỉ gán đúng 1 dòng StoreManager | none | Idempotent (replace-all: xóa sạch + ghi lại đúng mảng gửi lên, ngày không gửi = chưa cấu hình) | `dayOfWeek` 1=Chủ nhật→7=Thứ bảy CONFIRMED theo ERD (khác ISO, FE lưu ý). Không trùng `dayOfWeek` trong 1 request; `openTime<closeTime` bắt buộc khi `isClosed=false`; cấm vừa `isClosed=true` vừa có giờ (RULE-03-07, derived) |
| ConfigureStoreResource | OrgAdmin (/StoreManager A3) | Store tồn tại | RULE-03-02/08 | POST/PATCH | `/stores/{id}/resources…` | Bearer | OrgAdmin; StoreManager TBD Q10 | none | POST 409 nhờ UK `(store_id, resource_code)` | Types: `CLINIC_ROOM`, `GROOMING_TABLE`, `ULTRASOUND_MACHINE` (+`XRAY` theo domain 4.3) |
| ManageOrganizationPolicy | OrgAdmin | Org tồn tại | RULE-03-09 | GET, PATCH | `/organizations/{id}/policy` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình (tái dùng `RoleScopeGuard.assertCanManageOrganization`) | none | Idempotent (PATCH); GET trả mặc định (không 404) nếu chưa từng cấu hình; lazy-create bản ghi ở lần PATCH đầu | Giải Q7 (nửa Organization); `PATCH` bắt buộc field `version` (optimistic lock, `409 CONCURRENCY_CONFLICT` nếu lệch — lần đầu dùng `ConcurrencyConflictException` trong Module 03) |
| ConfigureStorePolicy | StoreManager (GET rộng hơn, xem ghi chú) | Store tồn tại, **chưa `ARCHIVED`** (RULE-03-06, mở rộng 2026-09-18) | RULE-03-10 | GET, PATCH | `/stores/{id}/policy` | Bearer | **GET:** `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` Org mình; `STORE_MANAGER` đúng Store mình (`RoleScopeGuard.assertCanManageStore`, mirror `getStore`/`listResources`). **PATCH:** CHỈ `STORE_MANAGER` đúng Store mình quản lý (`RoleScopeGuard.assertIsOwnStoreManager`) — không ngoại lệ SUPER_ADMIN/ORGANIZATION_ADMIN, cùng `ConfigureOperatingHour`/`ConfigureStoreResource`; khác `ManageOrganizationPolicy` (chỉ 2 role Admin, không có StoreManager) | none | Idempotent (PATCH); GET trả mặc định (không 404) nếu chưa từng cấu hình; lazy-create bản ghi ở lần PATCH đầu | Giải Q7 (nửa Store); `PATCH` bắt buộc field `version` (optimistic lock, `409 CONCURRENCY_CONFLICT` nếu lệch) — mirror lazy-create semantics `ManageOrganizationPolicy` nhưng KHÁC actor cho PATCH (sửa 2026-09-18, xem Decision Log RULE-03-03/RULE-03-10); không cấu hình giờ mở cửa (`ConfigureOperatingHour`) hay ca kíp (ngoài phạm vi, Module 08) |

**ASSUMPTIONS dùng chung:** A1 `POST /organizations` = `SUPER_ADMIN` (tenant lifecycle
thuộc PLATFORM scope theo bảng Actor; `01#3` ghi OrgAdmin là mâu thuẫn bootstrap vì
Org chưa tồn tại thì chưa có OrgAdmin) · ~~A2~~ **Q6 ĐÃ GIẢI (2026-09-17, xem mục E):**
`PATCH /stores/{id}` chỉ 3 field `name`/`address`/`phone` (khớp `openapi
#UpdateStoreRequest`, không có field nào khác kể cả `code`/`organizationId`/
`facilityType`/`status`), **giống nhau cho cả OrgAdmin lẫn StoreManager** — không có
field-level scope riêng như A2 bản cũ suy đoán; khác biệt actor nằm ở phạm vi Store
được sửa (RULE-02-05, `RoleScopeGuard.assertCanManageStore`) · ~~A3~~ **Q5/Q10 ĐÃ
GIẢI (2026-09-18, chỉ áp dụng cho `ConfigureOperatingHour`):** CHỈ `STORE_MANAGER`
đúng Store mình quản lý được cấu hình giờ hoạt động — bám literal `01#3` (chỉ 1
dòng gán StoreManager, không có OrgAdmin); `SUPER_ADMIN`/`ORGANIZATION_ADMIN`
không có ngoại lệ ở riêng endpoint này dù toàn quyền trên mọi endpoint Store khác
(`RoleScopeGuard.assertIsOwnStoreManager`). `ConfigureStoreResource` (Q5/Q10 phần
resource) vẫn TBD, chưa quyết định cùng đợt này.

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
- **`PATCH /stores/{id}`** (`UpdateStore`, implemented — xem Decision Log Q6/Q11) — Request
  `{name?, address?, phone?}` (đúng `openapi #UpdateStoreRequest`, field null = giữ nguyên).
  Guard CONFIRMED (RULE-03-06, bổ sung 2026-09-17): Store đang `ARCHIVED` → `400
  BUSINESS_RULE_VIOLATION`, không áp field nào. Authorization: `SUPER_ADMIN` (mọi Store) /
  `ORGANIZATION_ADMIN` (mọi Store trong Org mình) / `STORE_MANAGER` (chỉ Store mình quản lý,
  `RoleScopeGuard.assertCanManageStore`). Response `200 StoreResponse`.
  Status: `200` · `400` validation hoặc `ARCHIVED` · `401` · `403 ACCESS_DENIED_SCOPE_MISMATCH` ·
  `404` · `409 CONCURRENCY_CONFLICT` (optimistic lock).
- **`POST /stores/{id}/activate`** (`ActivateStore`, implemented — xem Decision Log
  Q12) — Guards CONFIRMED (RULE-03-02, check trước khi chuyển): (1) `operating_hours`
  hợp lệ (có cấu hình, không phải toàn bộ ngày `isClosed=true` — ASSUMPTION, RULE-03-02
  không định nghĩa chi tiết "hợp lệ"); (2) ≥1 `store_resources` đang `isActive=true`;
  (3) Organization có ≥1 `services.is_active=true` trong danh mục (module 05 —
  cross-domain, qua `ServiceCatalogService#hasActiveService`). Không thỏa bất kỳ điều
  kiện nào → **`400 BUSINESS_RULE_VIOLATION` (RULE-03-02)** (**ĐÃ GIẢI 2026-09-22, xem
  Q12** — sửa lại từ đề xuất `409 STORE_NOT_READY` ban đầu ở version trước của file
  này). Nguồn `from` không thuộc `{DRAFT, SUSPENDED, DEACTIVATED}` (VD: `ARCHIVED`) →
  `409 INVALID_STATE_TRANSITION` qua `StoreTransitionHandler` (FSM guard, tách biệt
  guard RULE-03-02). Idempotent: Store đã `ACTIVE` → `200` no-op, không re-check guard,
  không gọi lại `initializeOverridesForStore`. Actor CHỈ `ORGANIZATION_ADMIN` Org mình
  (`SUPER_ADMIN` all) — không có `STORE_MANAGER`. Khi thành công: khởi tạo override
  giá/khả dụng catalog cho Store (`StoreOverrideService#initializeOverridesForStore`,
  cùng transaction) + ghi Outbox event `StoreActivated`.
- **`POST /stores/{id}/suspend` / `/deactivate`** — Guards: chỉ từ `ACTIVE`
  (FSM-2 CONFIRMED). Hiệu ứng RULE-03-04 (khóa nhận mới) do các modules 06/07/14
  enforce — store contract chỉ đổi state + event.
- **`POST /stores/{id}/archive`** — Guards CONFIRMED (RULE-03-06, 4 zeros):
  0 active orders · 0 active appointments · `PhysicalQuantity == 0` · 0 công nợ/hoàn
  mở. Vi phạm điều kiện nào → `409` + `details.failedCondition` (shape PROPOSED).
  Chỉ từ `SUSPENDED`/`DEACTIVATED` (FSM-2 CONFIRMED) — archive từ `ACTIVE` → 409.
- **Status chung lifecycle:** `200` · `401` · `403` · `404` · `409` sai trạng thái/
  thiếu điều kiện (`INVALID_STATE_TRANSITION` / `BUSINESS_RULE_VIOLATION`).

### C3. Operating hours & resources

- **`PUT /stores/{id}/operating-hours`** (implemented) — Request `{hours: [{dayOfWeek
  (1–7 CONFIRMED, 1=Chủ nhật), openTime?, closeTime?, isClosed}]}` (`minItems: 1`,
  `maxItems: 7` — không bắt buộc đủ 7 ngày mỗi lần gọi).
  **Semantics replace-all (ĐÃ GIẢI 2026-09-18):** xóa sạch toàn bộ cấu hình cũ của
  Store rồi ghi lại đúng mảng gửi lên — ngày nào không có trong request thì **không
  còn hàng nào** sau lệnh này (coi như chưa cấu hình), không phải upsert giữ nguyên
  ngày thiếu. Idempotent (gọi lại cùng payload cho kết quả giống hệt).
  **Authorization (ĐÃ GIẢI 2026-09-18, xem Q5/Q10):** CHỈ `STORE_MANAGER` đúng Store
  mình quản lý — không có ngoại lệ `SUPER_ADMIN`/`ORGANIZATION_ADMIN`.
  **Guard CONFIRMED:** Store đang `ARCHIVED` (RULE-03-06 — dữ liệu cấu hình con bất
  biến) → `400 BUSINESS_RULE_VIOLATION`, không ghi gì.
  **Validation:** không trùng `dayOfWeek` trong cùng 1 request (derived, RULE-03-07)
  · `isClosed=false` bắt buộc có đủ `openTime`+`closeTime` và `openTime < closeTime`
  · `isClosed=true` không được kèm `openTime`/`closeTime` (cấu hình mâu thuẫn, derived).
  Status: `200` · `400` validation/`ARCHIVED` · `401` · `403 ACCESS_DENIED_SCOPE_MISMATCH`
  · `404`.
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
| Q5 | StoreManager được cấu hình giờ/resource không (A3)? | **ĐÃ GIẢI phần giờ (2026-09-18):** Có, và **chỉ** StoreManager (không OrgAdmin) — xem dòng `ConfigureOperatingHour` ở mục B. Phần `resource` (`ConfigureStoreResource`) vẫn TBD (PO) | `01#3` chỉ gán **StoreManager** cho cả 2 Configure* (ghi chú gốc ở đây từng nhầm thành OrgAdmin — đã sửa) |
| Q6 | `UpdateStore` của StoreManager giới hạn fields nào (A2)? | **ĐÃ GIẢI (2026-09-17):** cùng 3 field `name`/`address`/`phone` như OrgAdmin (khớp `openapi #UpdateStoreRequest`, không có field-level scope riêng) — khác biệt actor nằm ở **phạm vi Store**, không phải field | `openapi/org-store-v1.yaml #UpdateStoreRequest` (schema dùng chung, không tách theo actor) |
| Q7 | Policy storage + endpoints (`ManageOrganizationPolicy`/`ConfigureStorePolicy`) | **ĐÃ GIẢI đầy đủ cả hai nửa:** Organization (2026-09-17) — bảng `organization_policies` (RULE-03-09), endpoint `GET/PATCH /organizations/{id}/policy`; Store (2026-09-18) — bảng `store_policies` (RULE-03-10), endpoint `GET/PATCH /stores/{id}/policy` — xem dòng `ManageOrganizationPolicy`/`ConfigureStorePolicy` ở mục B | Decision Log RULE-03-03/RULE-03-09 và RULE-03-03/RULE-03-10 (`docs/02-business-rules.md` mục 03), `docs/06-erd.md` §3.2 |
| Q8 | `ConfigureStoreService` (03) vs `ConfigureServiceAvailability` (05): một hay hai? | TBD (PO) — PROPOSED dồn về 05 | `01#3` vs `01#5` |
| Q9 | Organization update được sửa fields nào? | TBD (PO) | docs không chi tiết |
| Q10 | Xem Q5 (StoreManager Configure*) | Phần `ConfigureOperatingHour` ĐÃ GIẢI (2026-09-18, xem Q5); `ConfigureStoreResource` vẫn TBD (PO) | — |
| Q11 | `UpdateStore` có bị chặn khi Store đã `ARCHIVED` không? | **ĐÃ GIẢI (2026-09-17):** Có — mở rộng GAP-ORG-01/RULE-03-06 (trước đó chỉ khóa dữ liệu cấu hình con) sang chính record Store, nhất quán tinh thần "Terminal State tuyệt đối" | Decision Log `docs/02-business-rules.md` mục 03, `docs/03-state-machines.md` §2 Technical Invariant #4 |
| Q12 | `ActivateStore` guard RULE-03-02 không thỏa: `409 STORE_NOT_READY` (đề xuất ban đầu) hay dùng exception chung? | **ĐÃ GIẢI (2026-09-22):** `400 BUSINESS_RULE_VIOLATION` (RULE-03-02) — dùng thẳng `BusinessRuleViolationException` có sẵn, KHÔNG tạo `STORE_NOT_READY` mới. Lý do: (1) nhất quán với RULE-03-06 đã code trong `UpdateStore` (cùng module, cùng dạng "thiếu điều kiện" → 400, không phải 409); (2) `docs/convention/backend/04-exception-handling.md` cố định RULE-ID → 400, tạo exception riêng chỉ khi thoả ≥1 tiêu chí ở §4.2 — trường hợp này không thoả (không cần field đặc thù, không cần retry logic, và "HTTP status khác" bản thân nó không phải lý do đủ khi status hiện tại đã đúng convention). `409` vẫn dùng cho FSM guard (nguồn `from` không hợp lệ, VD `ARCHIVED`) — 2 loại lỗi khác nhau, không gộp | Decision Log này; `docs/convention/backend/04-exception-handling.md` §4.1/4.2; `StoreServiceImpl.updateStore` (RULE-03-06, cùng pattern) |

---

## F. OpenAPI 3.1 YAML

Single source of truth cho contract máy đọc:
[`./openapi/org-store-v1.yaml`](./openapi/org-store-v1.yaml).
