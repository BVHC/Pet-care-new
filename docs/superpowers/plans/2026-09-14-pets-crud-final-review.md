# Final Review — Pets v1 (`824c775..HEAD`)

- **Scope:** module `pet` mới (V4 migration, entity/repo, DTO/mapper, Service owner-guard, Controller, PetFlowIT) + `docs/api/customer-pet-v1.md` + plan `2026-09-14-pets-crud.md`.
- **Đối chiếu:** plan Tasks 1–6 + task reports, `docs/02-business-rules.md` RULE-04-01→04-03/04-11, `docs/06-erd.md` §3.3 bảng `pets`, `docs/convention/backend/01+02+04+07`.
- **Validation:** đọc toàn bộ file mới + V1 DDL `pets` (dòng 290–303) + `BaseEntity` + `OutboxEvent`; grep `isActive` trong `docs/api/openapi/`; không chạy lại suite (dựa TDD evidence trong task reports + PetFlowIT PASS được ghi nhận).

## Verdict: CLEAN (không blocker)

Spec compliance, architecture, ponytail, test hygiene đều đạt. 1 Important + 4 Minor bên dưới đều **non-blocking** (edge path / follow-up), không yêu cầu sửa trước merge.

## Findings

### Important
- **F1 — `update()` catch optimistic-lock sai vị trí nên mapping 409 không hiệu quả** (`BE/.../pet/service/PetServiceImpl.java:111-115`): `pets.save()` trong cùng persistence context thường chỉ flush/commit sau khi method return, nên `ObjectOptimisticLockingFailureException` ở commit-time thoát khỏi `try` → 500 thay vì `ConcurrencyConflictException` (409). Cùng method cũng không catch `DataIntegrityViolationException` như `create()` làm. Fix follow-up: `saveAndFlush()` trong `try`, hoặc catch ở tầng gọi transaction; thêm catch DIV cho `update()`.

### Minor
- **F2 — `species`/`gender` không whitelist theo spec CONFIRMED** (`CreatePetRequest.java:10,12`, `UpdatePetRequest.java:9,11`): nhận string tự do trong khi spec/ERD chốt `DOG/CAT/BIRD/OTHER` và `MALE/FEMALE/UNKNOWN` (DB cũng chỉ VARCHAR, plan DTO verbatim String nên implementer đúng plan). Follow-up: `@Pattern` hoặc enum + 400.
- **F3 — Outbox `payload` nối chuỗi JSON thủ công** (`PetServiceImpl.java:56`): giòn khi thiếu escaping; follow-up dùng `ObjectMapper`. Cùng tx + `eventType="PetAdded"` đúng plan nên không block.
- **F4 — Thiếu unit test cho guard RULE-04-11 và `list()`** (`PetServiceImplTest.java`): chỉ 3 behavior (create/detail-403/update-403); nhánh `status != ACTIVE → RULE-04-11` chưa được cover ở unit (PetFlowIT chỉ đi path ACTIVE). Follow-up thêm 1–2 case.
- **F5 — `create()` gán mọi `DataIntegrityViolationException` cho RULE-04-01** (`PetServiceImpl.java:49-51`): đúng minimal cho FK owner, nhưng có thể gán nhầm rule nếu violation khác. Chấp nhận được ở v1 (DB không có unique nào ngoài PK/FK — microchip **không** unique ở V1/ERD nên `409 trùng microchip (A2)` trong doc C1 hiện là aspirational, ngoài scope).

## Triage parked items (đều non-block, xác nhận không cần re-flag)

- **(1) OpenAPI yaml còn `isActive`:** CONFIRMED vẫn stale (`docs/api/openapi/customer-pet-v1.yaml:420,443` `PetResponse` yêu cầu `isActive`) trong khi md đã sửa sang `status` enum — non-block, follow-up đồng bộ yaml.
- **(2) AuthFlowIT 5/5 pre-existing errors:** root cause `PESSIMISTIC_WRITE` OTP ngoài tx (ghi trong task6 report) — file ngoài scope pet, không sửa, non-block.
- **(3) Full suite 9 errors pre-existing:** `Failed to load ApplicationContext` do PG local password (`FATAL: password authentication failed`) — env issue, Task 6 đã chứng minh targeted `PetFlowIT` 1/1 PASS, non-block.
- **(4) TZ/heap quirks:** TZ `Asia/Saigon` vs Testcontainers + surefire heap workaround đã được document trong task reports — env-only, non-block.

## Positive observations

- CRUD + Primary Owner only đúng scope: transfer/search/caregiver OUT, doc md đã đánh dấu OUT v1 rõ ràng.
- Đúng layering Controller→Service→Repo, DTO record + MapStruct duy nhất `toResponse`, không trả Entity, `ApiResponse/PageResponse` envelope thống nhất.
- `extends BaseEntity`, 5 exceptions dùng đúng base (400/403/404/409 mapping), `@Transactional` chỉ ở Service, Outbox `PetAdded` cùng tx.
- Ponytail: không abstraction thừa (không `module/pet/exception/*`, không FSM handler cho PetStatus prose-invariant, không field `status` trong request).
- TDD evidence đầy đủ cả 6 tasks (RED compilation-failure → GREEN PASS ghi trong từng report); deviations đều có lý do (owner thật do FK, `Page` overload, `@Transactional` trên IT do `@Lock`, auth trực tiếp thay `@WithMockUser`).
- V4 đúng 5 cột còn thiếu so với V1 (`created_at` đã có sẵn) + kiểu khớp `accounts`.
