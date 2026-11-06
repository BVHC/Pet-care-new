# ADR-0110: Hạ tầng pgvector và bảng AI (T55a) — ảnh Postgres, Testcontainers, V34–V35, giới hạn tần suất chatbot

- **Trạng thái:** Proposed (FE-1 / Hiến làm; phần giới hạn tần suất thuộc `platform/security` của BE-1 nên chờ Mạnh xác nhận ngưỡng và chữ ký mới của `RateLimitProperties`; phần ảnh Postgres chạm môi trường cả nhóm, báo BE-1, BE-2 trước khi gộp)
- **Ngày:** 2026-11-05
- **Liên quan:** ADR-0109 D3 (chọn pgvector; ADR này thực hiện "Việc phải làm" 1–3 và 5 của D3, không sửa nó), ADR-0100 (giới hạn tần suất theo IP), ADR-0018 (index cho FK tới `accounts`), ADR-0004 / 0036 ([CFG]); `docs/07-ai-subsystem.md` §4.6, §8, §11 I-9, §13, §17.1; `docs/05-erd.md` §14.2; BR-AI-09, BR-AI-14, BR-AI-24.
- **Triển khai:** `docker-compose.yml`, `.github/workflows/ci.yml`, `BE/src/test/java/com/petcare/TestcontainersConfiguration.java`, `V34__ai_pgvector_tables.sql`, `V35__seed_ai_configs.sql`, `identity/api/ConfigKey` (+18), `platform/security/{RateLimitFilter, RateLimitProperties}`, `application.yml`, `.env.example`. Chưa có logic RAG (T56 / T77), chưa có interface `*QueryApi` (T55b / T55c).

## 1. Ảnh Postgres: `postgres:17` → `pgvector/pgvector:pg17`

Đổi ở `docker-compose.yml` (dịch vụ `postgres`), `ci.yml` (hai chỗ; CI BE vốn đã hỏng, CLAUDE.md "Gotchas") và Testcontainers. Giữ tên volume `postgres_data`, **không** `down -v`.

**Testcontainers.** `DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres")` được `PostgreSQLContainer` nhận ngay, không cần cách khác. Đã chạy thật 5/11: `SchemaMigrationIT`, `AiSchemaIT`, `SystemConfig*IT`, `RateLimit*IT`, `DocumentCodeIT` khởi động container `pgvector/pgvector:pg17`, Flyway áp 36 migration (V1–V35 + V9000), `ddl-auto=validate` qua. Ảnh khai báo một hằng `POSTGRES_IMAGE` trong `TestcontainersConfiguration`; không còn chỗ nào khác trong `BE/src` dùng `postgres:17` (đã grep).

## 2. Dùng lại volume cũ: **không an toàn như 07 §17.1 giả định**

07 §17.1 và ADR-0109 D3 ghi "cùng bản chính 17 nên thư mục dữ liệu cũ dùng lại được — kiểm khi làm". Đã kiểm trên volume tạm (tạo bằng `postgres:17`, ghi một bảng + index, dừng, mở lại bằng `pgvector/pgvector:pg17`; volume và container tạm đã xóa):

| Kiểm | Kết quả |
|---|---|
| Mở được, dữ liệu còn | **Có** (2 dòng, chữ có dấu nguyên vẹn) |
| Phiên bản | Cùng 17.11, nhưng nền khác: `postgres:17` hiện là Debian 13 (glibc 2.41), `pgvector:pg17` là Debian 12 (glibc 2.36) |
| Cảnh báo | **Có, ở mọi kết nối:** `database "postgres" has a collation version mismatch ... created using collation version 2.41, but the operating system provides version 2.36` |
| Hệ quả | Sắp xếp / index theo collation của `libc` có thể lệch giữa hai glibc (index chuỗi hỏng âm thầm). Với DB tiếng Việt (`ORDER BY`, index tên) đây là rủi ro thật, không chỉ là cảnh báo |

**Quyết định:** **không** mở lại volume của `postgres:17` bằng ảnh pgvector rồi bỏ qua cảnh báo; phải chữa collation. Cách ưu tiên: DB có dữ liệu thì `pg_dump -Fc` → dựng container mới từ ảnh pgvector (volume mới) → `pg_restore` (hoặc, nếu buộc phải giữ volume, chạy **trong từng DB** `REINDEX DATABASE <db>; ALTER DATABASE <db> REFRESH COLLATION VERSION;` — đã kiểm trên volume tạm: cảnh báo 1 → 0, dữ liệu còn, `datcollversion` 2.41 → 2.36; đã chạy thật ngày 6/11 trên cả hai DB demo, gồm `template1`). Comment trong `docker-compose.yml` và `docs/be-fe-integration.md` §2 ghi điều này.

**Câu hỏi mở cho Hiến — tên volume.** Compose giữ tên `postgres_data` (đề bài T55a). Hệ quả: sau khi pull, ai chạy `docker compose up -d postgres` sẽ được tạo lại container bằng ảnh mới **trên chính volume cũ**, đúng kịch bản trên; một dòng comment không ngăn được. Hai lựa chọn: (a) đổi tên volume (ví dụ `postgres_pgvector_data`) để buộc dựng DB mới, mất dữ liệu cũ nếu quên dump; (b) giữ tên và dựa vào lệnh chữa ở trên, báo Mạnh / Dũng trước khi họ pull. Hiến cho phép "hết" ngày 6/11 nhưng không chọn (a) hay (b); đề bài T55a nói giữ nguyên volume nên **giữ (b)**, chuyển sang (a) chỉ là đổi một dòng ở `docker-compose.yml`.

**DB demo — đã đổi ngày 6/11 (Hiến cho phép).** Đo ngày 5/11 trước khi đổi: container compose cổng 5434 (`petcare-postgres`, `postgres:17`, DB `petcare`) có `pg_available_extensions WHERE name='vector'` = **0 dòng**; Postgres Windows (5432) và container `petcare-seed-pg` (5433, `postgres:17`, giữ DB `petcare_seed` của demo) cũng không có. BE bản có V34 chạy trên các DB đó sẽ **lỗi Flyway ngay lúc khởi động**. Bắt đầu từ commit này, BE chỉ chạy được trên Postgres có pgvector. Cách đã làm (mục 7): sao lưu → đổi ảnh **trên cùng volume** → `REINDEX` + `REFRESH COLLATION VERSION`, quay lui bằng bản dump (container cũ đã gỡ, mục 7).

**`CREATE EXTENSION vector` cần superuser.** `pg_available_extension_versions`: `vector 0.8.7`, `trusted = false`, `superuser = true` — **khác** `btree_gist` (V1), `unaccent`, `pg_trgm` (V33) là "trusted". User `postgres` của compose và Testcontainers là superuser nên chạy được; DB do DBA quản lý phải tạo extension trước (V34 dùng `IF NOT EXISTS`). Đã thử `BEGIN; CREATE EXTENSION vector; SELECT '[1,2,3]'::vector(3) <=> '[1,2,4]'::vector(3); ROLLBACK;` trên ảnh mới: chạy, không để lại extension.

## 3. V34: bốn bảng AI; V35: 18 khóa [CFG]

V34 theo 07 §8 + 05 §14.2. **Chỗ khác bảng ở 07 §8 (giữ 07 làm nguồn, ghi lý do):**

| Chỗ | 07 §8 | V34 | Lý do |
|---|---|---|---|
| `species` của `ai_documents`, `ai_item_profiles` | `VARCHAR(10)` | `VARCHAR(30)` | Cùng §8 đầu mục: "enum `VARCHAR(30)` + `CHECK`"; mọi cột `species` của V1 là `VARCHAR(30)`; `SchemaMigrationIT.enumCheckColumnsAreVarchar30` bắt cột lệch. 07 tự mâu thuẫn, theo quy ước chung |
| `created_at`, `updated_at` của `ai_chunks` | không liệt kê | có | PART có cả hai (CLAUDE.md "Persistence"; `commonTimestampColumnsMatchTableKind`) |
| `ai_chat_logs` | CHECK enum; "error_kind có đúng với `UNAVAILABLE`"; BR-AI-24 | thêm `ck_ai_chat_logs_error_kind_tied` và `ck_ai_chat_logs_no_content_for_personal` | Chuyển hai câu chữ của 07 thành ràng buộc CSDL để quyền riêng tư (không lưu nội dung câu hỏi cá nhân) không phụ thuộc mỗi service |
| Tên ràng buộc, index | không đặt tên | `ck_ai_*`, `ux_ai_*`, `ix_ai_*`, `uq_ai_chunks_document_version_ordinal` | Theo V1 / V27 |
| Chỉ mục vector | "chưa tạo HNSW / IVFFlat ở vài trăm đoạn" | không tạo | Giữ nguyên; thêm `hnsw (embedding vector_cosine_ops)` bằng migration mới khi vượt ~10 000 đoạn |

`ai_documents` có hai lớp kiểm soát của BR-AI-14 thành `CHECK`: `ck_ai_documents_manual_has_source` (lớp 1) và `ck_ai_documents_manual_published_reviewed` (lớp 2: `MANUAL` + `PUBLISHED` đòi **cả** `reviewer_name` lẫn `reviewed_on`). Index `UNIQUE (vaccine_type_id)` và `UNIQUE (service_id)` / `(product_id)` một phần cũng là index cho kiểm FK khi xóa dòng cha (mẫu ADR-0018).

V35 seed 18 khóa `ai.*` của 07 §4.6 (07 ghi "14 khóa" ở `10` §1.7 nhưng bảng liệt kê thành 18 dòng khi tách `weight_*`, `default_*`, `senior_age_*`; theo bảng). Hệ quả:
- **Khóa ba đoạn** (`ai.chat.max_question_chars`): quy ước 03 là `<nhóm>.<tên>`; sửa `docs/convention/backend/03-naming-convention.md` và regex ở `ConfigKeyTest` cho phép tối đa ba đoạn. Không đổi tên khóa để khỏi lệch 07, 10 và `ai-v1`.
- **DECIMAL đầu tiên của hệ thống** (`ai.retrieval.min_similarity` 0.6 và ba `ai.reco.weight_*` 0.5 / 0.3 / 0.2). Seed viết dạng chuẩn `ConfigValues.canonical` ghi ra (`0.6`, không `0.60`) để một lần sửa qua API không đổi chữ của dòng mặc định. Nhánh DECIMAL của `SystemConfigEditService` chưa từng chạy thật; `SystemConfigAdminIT.boundariesOfEveryKey` nay phủ nó cho 4 khóa (dưới min, trên max, `abc`, `1e2`, bằng min, bằng max): **đạt, không phải sửa mã chạy**.
- **Tổng ba trọng số = 1 (BR-AI-21) chưa được kiểm ở đâu cả.** `ConfigConstraints` chỉ hỗ trợ cặp INT. Mỗi trọng số chỉ bị chặn trong [0, 1]; ADMIN có thể đặt tổng khác 1. T78 phải kiểm hoặc chuẩn hóa khi dùng (hoặc mở rộng `ConfigConstraints` sang DECIMAL). **Nợ, chưa tạo file `docs/dept`** (thư mục này gitignored và ngoài cây làm việc này).
- Số khóa [CFG] từ 52 lên **70**: sửa `ConfigKeyTest`, `SystemConfigIT` (bảng kỳ vọng độc lập), `SystemConfigAdminIT`; bỏ chữ "(52)" cứng ở `SystemConfigController`, `m_identity.py` và hai file sinh ra (`identity-v1.md`, `identity-v1.yaml`) vì parity IT so chữ `description`. Đã sinh lại **chỉ** module identity (`py generate.py identity`), `check-contracts.mjs`: 27 file, 371 operation đạt.
- `SchemaMigrationIT`: 55 → 59 bảng ERD, LOG 8 → 9, FK tới `accounts` 43 → 45, thêm V34, V35 vào danh sách migration.

## 4. Giới hạn tần suất chatbot (I-9, BR-AI-09) — **FE-1 làm thay BE-1**

`platform/security` thuộc BE-1 (Mạnh). Theo 07 §11 I-9 và prompt T55a, FE-1 thêm:
- Hai đường dẫn `POST /api/public/ai/chat`, `POST /api/me/ai/chat` vào **một** nhóm `AI` của `RateLimitFilter`, **hai cửa sổ trên cùng IP**: `rl:ai:<ip>` (mặc định 10 / 60 s) rồi `rl:ai-hour:<ip>` (60 / 3600 s). Kiểm theo thứ tự đó, dừng ở cửa sổ đầu tiên từ chối: request bị chặn bởi cửa sổ phút **không** tiêu hao hạn mức giờ (IT đo: 3 qua + 3 bị chặn ⇒ `rl:ai-hour` = 3). `Retry-After` là của cửa sổ đã chặn.
- `RateLimitProperties` thêm hai thành phần `ai`, `aiHour` (sau `password`, trước `breakerSeconds`); `application.yml`: `app.rate-limit.ai.*`, `ai-hour.*`; env `RATE_LIMIT_AI_MAX`, `_AI_WINDOW_SECONDS`, `_AI_HOUR_MAX`, `_AI_HOUR_WINDOW_SECONDS`; `docker-compose.yml`, `.env.example`. **Không** là [CFG] (07 §4.6).
- **Đổi chữ ký record công khai:** mọi chỗ `new RateLimitProperties(...)` phải thêm hai `Rule`. Đã sửa 3 lớp test trong cây này (`RateLimitFilterTest`, `RedisRateLimiterTest`, `RedisRateLimiterIT`; `RateLimitSecurityTest` dùng binder nên không đổi); test mới của BE-1 viết sau thì sẽ vỡ biên dịch khi gộp, sửa theo.
- Đường `/api/me/**` yêu cầu đăng nhập: filter chạy **trước** JWT nên request chưa có token vẫn bị đếm rồi mới 401 (đúng ý: chặn trước khi nhúng / gọi LLM; `RateLimitIT` chứng minh).
- Lưu ý kế thừa từ ADR-0100: cố định cửa sổ cho phép burst gấp đôi ở ranh giới; fail-open khi Redis lỗi (**chatbot mất giới hạn khi Redis chết**, tức mất chốt chặn tốn tiền; chấp nhận ở demo, T77 nên thêm trần ở tầng gọi nhà cung cấp); `app.rate-limit.enabled=false` ở profile `test`.
- Endpoint chatbot **chưa tồn tại** (T77): IT dùng đường dẫn chưa có controller, tức dưới ngưỡng nhận 404 / 401 / 405 (bất kỳ ngoài 429), vượt ngưỡng nhận 429 từ filter.

## 5. Kiểm chứng và đột biến

IT mới `AiSchemaIT` (26 ca, Testcontainers pgvector): extension + `vector(768)` (sai số chiều bị từ chối, 3 và 769), hai lớp kiểm soát BR-AI-14 (thiếu người, thiếu ngày, thiếu cả hai), phác đồ ↔ loại vaccine, độ dài 20 000, enum, `ai_chat_logs` (không cột tài khoản / IP; `error_kind` ↔ `UNAVAILABLE`; ba kết quả cá nhân không nội dung), `ai_item_profiles` (đúng một đích, khoảng tuổi / cân nặng, chu kỳ > 0), và **truy hồi cosine `<=>` đúng câu 07 §5.1** (`status = 'PUBLISHED' AND doc_version = indexed_version`): thứ tự theo cosine, loại `DRAFT` / `ARCHIVED` / phiên bản chưa phục vụ, lưu trữ có hiệu lực ngay, trỏ `indexed_version` tới phiên bản chưa có đoạn thì tài liệu biến mất, **hoán đổi phiên bản trong một transaction** nên người đọc kết nối khác không thấy khoảng trống trước COMMIT (BR-AI-16).

Đột biến (chỉ trong bản sao Maven `be-scratch`, đã khôi phục; kết quả `.c2-tmp/final2/cum-9/t55a-mutations.txt`):

| # | Đột biến | Test đỏ |
|---|---|---|
| M1 | bỏ cửa sổ giờ khỏi nhóm AI | 5 (`RateLimitFilterTest` ×3, `RateLimitIT.aiHourWindowBlocksEvenWhenTheMinuteWindowHasReset`, `.aiMinuteDenialDoesNotSpendTheHourBudget`) |
| M2 | đảo thứ tự: kiểm giờ trước phút | 5 (cả ca "không tiêu hao hạn mức giờ" của filter lẫn IT) |
| M3 | chỉ khớp `/api/public/ai/chat` | 4 (`RateLimitIT.aiChatEndpointsShareOneCounterPerIp`, ...) |
| M4 | hai CHECK `AND` → `OR` (rà soát; không nội dung cho kết quả cá nhân) | 5 (`AiSchemaIT`: 3 ca `personalResults...`, 2 ca `manualDocumentCannotBePublished...`) |

## 6. Rủi ro và việc để lại

- **Đổi ảnh cho DB đang có dữ liệu** (mục 2): bắt buộc dump / restore; chờ Hiến cho phép với DB demo `petcare` (5434) và `petcare_seed` (5433). Mọi dev phải chạy Postgres bằng Docker với ảnh pgvector; Postgres Windows không dùng được từ V34.
- **Gộp với `origin/dev`:** Mạnh đã đẩy ADR-0111, 0112 và đổi `application.yml`, `application-test.yml`, `docker-compose.yml`, `.env.example`, `CLAUDE.md`, `docs/adr/INDEX.md`, `docs/INDEX.md` (PR #29). Số của T55a (V34–V35, ADR-0110) **không va**; các file chung sẽ cần gộp tay.
- **FE:** mock `shared/api/mock/identity-admin.seed.ts` còn 52 khóa [CFG] trong khi BE thật có 70; màn "Tham số hệ thống" chạy BE thật sẽ hiện thêm 18 dòng (có `DECIMAL`). Chưa đụng FE (ngoài phạm vi T55a); FE-1 sửa mock ở T57 / T79.
- `docs/07` §8 còn ghi `VARCHAR(10)` cho `species`; ADR này là bản ghi sai khác. ADR-0109 giữ nguyên (Accepted, bất biến); dòng 0109 ở `adr/INDEX.md` còn nói "`REAL[]` thay pgvector" là bản nháp cũ.
- Chưa làm: push (Hiến cần báo Mạnh, Dũng trước vì mọi người phải đổi ảnh), `*QueryApi` và placeholder (T55b / T55c), `ConfigKey` / seed khác của `10` §1.7 (thuộc BE-1, BE-2), index HNSW.

## 7. Triển khai thật lên DB demo (6/11/2026)

Hiến cho phép đổi ảnh DB demo. Mỗi container: `pg_dump -Fc` + `pg_dumpall --globals-only` (`D:/Do-an/.c2-tmp/backup/petcare_seed_6-11_truoc-doi-anh-pgvector.*`, `petcare_compose_6-11_truoc-doi-anh-pgvector.*`) → khôi phục thử vào container pgvector tạm và so số dòng từng bảng (58 / 58 và 56 / 56 bảng khớp 100%) → đổi ảnh trên **cùng volume** → `REINDEX DATABASE` + `ALTER DATABASE ... REFRESH COLLATION VERSION` cho `petcare_seed` (hoặc `petcare`), `postgres`, `template1` → số dòng sau đổi khớp mốc 100%, cảnh báo collation về 0 ở cả ba DB.

| Container | Cổng | Trước | Sau | Cách |
|---|---|---|---|---|
| `petcare-seed-pg` (DB demo `petcare_seed`, V33) | 5433 | `postgres:17`, `docker run`, volume ẩn danh | `pgvector/pgvector:pg17` cùng volume | dừng, đổi tên bản cũ, `docker run` bản mới cùng cổng và volume, rồi **gỡ container cũ** (`docker rm`, không `-v`; volume còn nguyên) |
| `petcare-postgres` (DB `petcare`, V8) | 5434 | `postgres:17`, compose | `pgvector/pgvector:pg17` cùng volume `pet-care-new_postgres_data` | `docker compose up -d postgres` (chính là kịch bản các dev sẽ gặp) |

BE thật 8082 khởi động lại bằng `start-be-only.ps1` trên `petcare_seed`: Flyway áp **V34, V35** (104 ms), `SystemConfigService` nạp **70** khóa, `UP` sau 15 s. Kiểm bằng HTTP thật (`.c2-tmp/final2/cum-9/t55a-smoke.mjs`): ADMIN đăng nhập, `GET /api/system-configs` = 70 khóa (18 `ai.*`, bốn DECIMAL `0.5`, `0.3`, `0.2`, `0.6` đúng dạng chuẩn, sắp theo key); 10 `POST` xen kẽ `/api/public/ai/chat` (404) và `/api/me/ai/chat` (401) từ một IP rồi request 11 nhận **429 `TOO_MANY_REQUESTS`**, `Retry-After: 60`, có `traceId`; IP khác và nhóm login cùng IP không bị chặn. Khóa Redis của lần thử đã xóa. Ghi vào DB demo (đo bằng số dòng từng bảng trước / sau): hai migration Flyway (`flyway_schema_history` +2, `system_configs` +18, bốn bảng `ai_*` rỗng) và dấu vết của smoke, chạy hai lần: `sessions` +2, `refresh_tokens` +2, `audit_logs` +4 (2 `LOGIN_SUCCEEDED`, 2 `LOGIN_FAILED`); không bảng nghiệp vụ nào khác đổi.

**Quay lui = bản dump, không phải ảnh cũ.** Volume của `petcare_seed` đã chạy V34 (extension `vector` nằm trong catalog) và collation đã refresh lên 2.36, nên ảnh `postgres:17` không còn phục vụ sạch được; giữ lại container cũ gắn chung volume còn nguy hiểm vì hai postmaster có thể cùng ghi một data dir. Bản dump trước khi đổi: `petcare_seed_6-11_truoc-doi-anh-pgvector.dump` (V33) và `petcare_compose_6-11_truoc-doi-anh-pgvector.dump`; bản sau V35, trước migration của T55b: `petcare_seed_6-11_sau-V35-truoc-T55b.dump`.

Bản cũ của DB `petcare` (V8, lệch Flyway từ trước) không thuộc demo và `petcare-api` (ảnh cũ, 8081) vẫn dùng nó bình thường vì không cần pgvector.
