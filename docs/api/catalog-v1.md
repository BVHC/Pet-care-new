# Catalog API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Service & Product Catalog (Module 05): danh mục gốc cấp
> Organization + override giá/khả dụng cấp Store. Đây là owner của
> `ConfigureServiceAvailability` — chốt org-store-v1 Q8 tại đây (định nghĩa một lần).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#5`), `docs/02-business-rules.md`
> (RULE-05-01→07), `docs/04-glossary.md` (`05`), `docs/05-domain-model.md` (`4.5`),
> `docs/06-erd.md` (`services`/`service_required_resources`/`products`/
> `store_products`/`store_services`). Không có FSM riêng (N/A CONFIRMED).
> **Contract máy đọc:** [`./openapi/catalog-v1.yaml`](./openapi/catalog-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Không hard-delete Product đã phát sinh giao dịch/kho (RULE-05-02 CONFIRMED) —
  không có endpoint DELETE nào trong v1; gỡ mở bán bằng `PATCH …/isActive=false`.
- Override records (`store_products`/`store_services`) do hệ thống tự khởi tạo khi
  Store `ACTIVE` (RULE-05-07) — không có endpoint tạo override, chỉ có PUT sửa.
- Giá giao dịch = override tại Store tại thời điểm phát sinh (không override thì giá
  gốc Org) — logic tính giá thuộc về modules giao dịch (06/14), ở đây chỉ quản trị số liệu.

---

## A. Confirmed Catalog API (12 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /products` | `ManageProduct` (create) — `01#5`, RULE-05-01/02 |
| 2 | `GET /products` | (list trong Org) |
| 3 | `GET /products/{id}` | (detail) |
| 4 | `PATCH /products/{id}` | `ManageProduct` (sửa/vô hiệu hóa) — `01#5`, RULE-05-02 |
| 5 | `POST /services` | `ManageService` (create) — `01#5`, RULE-05-03 |
| 6 | `GET /services` | (list trong Org) |
| 7 | `GET /services/{id}` | (detail + required resources) |
| 8 | `PATCH /services/{id}` | `ManageService` (sửa/đổi trạng thái) — `01#5` |
| 9 | `PUT /stores/{id}/products/{pid}/price` | `ConfigureProductPrice` — `01#5`, RULE-05-05 |
| 10 | `PUT /stores/{id}/services/{sid}/price` | `ConfigureServicePrice` — `01#5`, RULE-05-05 |
| 11 | `PUT /stores/{id}/services/{sid}/availability` | `ConfigureServiceAvailability` (+ `ConfigureStoreService` `01#3` — owner chốt tại đây, Q8 org-store) — RULE-05-04 |
| 12 | `GET /stores/{id}/products` | `ViewProduct` (+ list nội bộ) — `01#5`, RULE-05-06 |
| 13 | `GET /stores/{id}/services` | `ViewService` (+ list nội bộ) — `01#5`, RULE-05-06 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ManageProduct | OrgAdmin | Trong Org mình | RULE-05-01 (toàn quyền Org), RULE-05-02 (UK org+sku; cấm hard-delete có giao dịch) | POST/PATCH | `/products…` | Bearer | `ORGANIZATION_ADMIN` Org mình | none (flag `isActive`) | POST 409 nhờ UK; PATCH idempotent | Category/unit sets CONFIRMED (ERD) |
| ManageService | OrgAdmin | Trong Org mình | RULE-05-03 (+ required resources cho Triple Guard) | POST/PATCH | `/services…` | Bearer | như trên | none | như trên | `service_required_resources` tạo cùng service (A1) |
| ConfigureProductPrice | StoreManager | Override đã được hệ thống khởi tạo (RULE-05-07) | RULE-05-05/07 | PUT | `/stores/{id}/products/{pid}/price` | Bearer | StoreManager Store mình (OrgAdmin cũng được, A2) | none | Idempotent | Không vượt khung giá Org — khung chưa số hóa → TBD Q7 |
| ConfigureServicePrice | StoreManager | như trên | RULE-05-05/07 | PUT | `/stores/{id}/services/{sid}/price` | Bearer | như trên | none | Idempotent | như trên |
| ConfigureServiceAvailability | StoreManager | như trên | RULE-05-04 (override `is_active`, không đụng gốc) | PUT | `/stores/{id}/services/{sid}/availability` | Bearer | như trên | none | Idempotent | Dịch vụ chỉ phục vụ khi override ACTIVE |
| ViewProduct/ViewService | Customer | Item `ACTIVE` + Store công bố | RULE-05-06 | GET | `/stores/{id}/products`, `/stores/{id}/services` | Bearer (A3) | Lọc server-side theo role | none | Idempotent | Customer chỉ thấy `ACTIVE`; staff thấy cả tắt (A4) |

**ASSUMPTIONS dùng chung:** A1 required-resources khai báo cùng lúc tạo/sửa service
(ERD bảng con, docs không tách op riêng) · A2 OrgAdmin được override giá/khả dụng
tại Store trực thuộc (docs chỉ gán StoreManager; OrgAdmin bao trùm theo RULE-02-05) ·
A3 catalog xem cần đăng nhập (docs không nói public; Bearer để enforce RULE-05-06
+ scope) · A4 staff thấy cả item tắt, customer chỉ thấy ACTIVE (RULE-05-06).

---

## C. Detailed endpoint contract

### C1. Org catalog (proposed)

- **`POST /products`** — Request `{sku (req + UK trong Org CONFIRMED), barcode?,
  name (req), category (req: `FOOD`/`MEDICINE`/`ACCESSORY`/`HYGIENE` CONFIRMED),
  unit (req: `ITEM`/`BOX`/`BOTTLE`/`BAG` CONFIRMED), basePrice (req),
  costPrice?, isActive?}`. Response `201 {productId, organizationId, sku, …}`.
  Status: `201` · `400` · `401` · `403` · `409` trùng `(organization_id, sku)`.
- **`PATCH /products/{id}`** — Request `{name?, barcode?, category?, unit?,
  basePrice?, costPrice?, isActive?}` (`sku` immutable — UK, TBD Q8).
  Vô hiệu hóa (`isActive=false`) thay cho xóa (RULE-05-02).
  Status: `200` · `400` · `401` · `403` · `404`.
- **`POST /services`** — Request `{code (req), name (req), category (req:
  `CLINICAL`/`VACCINATION`/`GROOMING`/`SPA` CONFIRMED), basePrice (req),
  durationMinutes (default 30 CONFIRMED ERD), isActive?,
  requiredResources?: [{resourceType, quantityRequired}] (A1)}`.
  Response `201`. Status như products (409 theo UK code trong Org — A5).
- **`PATCH /services/{id}`** — tương tự products + `requiredResources?` (replace-all, A1).
- **`GET /products`, `/services`, `/{id}`** — scope Org của caller; query PROPOSED:
  `page`, `pageSize`, `category?`, `activeOnly?`. Response page shape như iam-v1.

### C2. Store overrides (proposed)

- **`PUT /stores/{id}/products/{pid}/price`** — Request `{price (req, ≥0)}`.
  Guards: override tồn tại (store ACTIVE mới có — RULE-05-07; chưa có → `404`
  PROPOSED `OVERRIDE_NOT_INITIALIZED`). Response `200 {storeId, productId, price}`.
- **`PUT /stores/{id}/services/{sid}/price`** — tương tự.
- **`PUT /stores/{id}/services/{sid}/availability`** — Request `{isActive (req)}`.
  Chỉ đổi override, không đụng `services.is_active` gốc (RULE-05-04 CONFIRMED).
  Response `200 {storeId, serviceId, isActive}`.
- **Status chung:** `200` · `400` · `401` · `403` ngoài Store · `404`.
- Khung giá chính sách Org (RULE-05-05 "không vượt khung") chưa số hóa → TBD Q7
  (hiện chỉ validate `price ≥ 0`).

### C3. Storefront views (proposed)

- **`GET /stores/{id}/products`, `GET /stores/{id}/services`** — Response items kèm
  giá/availability **hiệu dụng** (override nếu có, else gốc — RULE-05-05/07).
  Customer: server lọc `isActive=true` (RULE-05-06 CONFIRMED); staff: full + param
  `activeOnly?` (A4). Query: `page`, `pageSize`, `category?`, `q?` (PROPOSED).
  Status: `200` · `401` · `403` (store ngoài Org của caller) · `404`.

---

## D. Security & reliability

1. Org isolation: product/service thuộc đúng 1 Org (`UNIQUE(organization_id, sku)`);
   store endpoints check store ∈ Org của caller (RULE-02-01).
2. Không hard-delete có giao dịch — server từ chối bằng `409` nếu lộ trình xóa
   được thêm sau này (hiện không có endpoint xóa).
3. Override auto-init khi `ActivateStore` là hiệu ứng hệ thống (RULE-05-07),
   cần transaction cùng activate (BE concern, ghi nhận).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `sku`/`code` sửa được không (A5: UK code trong Org cho service — ERD không UK tường minh)? | TBD (PO) | ERD (service thiếu UK, product có) |
| Q4 | `requiredResources` quản trị riêng hay luôn kèm service (A1)? | TBD (PO/BE) | docs không tách op |
| Q5 | Customer xem catalog có cần đăng nhập (A3)? | TBD (PO) | docs không nêu |
| Q6 | Category/unit sets có mở rộng được không hay enum cứng? | TBD (PO) | ERD liệt kê, thiếu rule |
| Q7 | Khung giá Org số hóa ở đâu để enforce "không vượt khung"? | TBD (PO) | RULE-05-05 |
| Q8 | Xem Q3 (`sku` immutable?) | TBD (PO) | — |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/catalog-v1.yaml`](./openapi/catalog-v1.yaml).
OWNER chốt org-store-v1 Q8: availability override định nghĩa tại đây, không trùng lặp.
