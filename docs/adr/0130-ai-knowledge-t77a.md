# ADR-0130: Nền module `ai` — lớp Gemini, tri thức UC93, FSM #13, ST22, ST23 (T77a)

- **Trạng thái:** Proposed (Hiến làm việc AI-1 = T77a, 10/11/2026; chờ Hiến duyệt các điểm ở mục "Cần Hiến xác nhận").
- **Liên quan:** ADR-0109 (LLM, embedding, pgvector), 0110 (hạ tầng, V34–V35), 0122 (mô hình mặc định), 0129 (AI-0: interface đọc); đặc tả `07` §3, §4.3, §5.1, §5.3, §8, §9, §10; hợp đồng `ai-v1` #5–#11 (7 endpoint).
- **Đánh số:** prompt AI-1 ghi "số kế tiếp từ 0128", nhưng `adr/INDEX.md` sau lần gộp `origin/dev` ghi kế tiếp là **0130** (0129 = AI-0; 0126–0128 chừa cho bản đổi số của ADR trùng). Không có migration: V51 vẫn trống.

## Bối cảnh

T55a đã có schema (V34 bốn bảng, V35 mười tám khóa `ai.*`); T56 đã đo spike. T77a dựng phần BE đứng được mà **chưa có chat**: lớp gọi nhà cung cấp, quản lý tri thức (UC93) và nạp chỉ mục (ST22, ST23). Chat (#1–#2), tra lịch tiêm (#3), gợi ý (#4) và hồ sơ gợi ý (#12–#13) là việc sau.

## Quyết định

### D1. Entity và repository
- Bốn entity khớp V34. **`ai_chunks.embedding` không ánh xạ vào entity:** Hibernate không có kiểu `vector` nếu không thêm `hibernate-vector`, và với `ddl-auto=validate` một ánh xạ `float[]` hay `String` làm khởi động lỗi. Đoạn được ghi bằng native `CAST(? AS vector)` ở `AiChunkRepository` và đọc bằng câu truy hồi `07` §5.1; `AiChunk` chỉ để đếm và liệt kê (`@Immutable`).
- `ai_documents.version` là **bộ đếm nghiệp vụ**, không phải `@Version` của JPA (Hibernate sẽ tăng nó ở mọi UPDATE, kể cả khi ST22 ghi `index_status`).
- ST22 ghi `indexed_version` / `index_status` bằng **câu UPDATE có điều kiện** `WHERE id = ? AND version = ? AND status = 'PUBLISHED'` (`markIndexed`, `markFailed`), không qua entity, và **không đổi `updated_at`** ("mới sửa" là việc của người quản lý, không phải của ST22).

### D2. `GeminiClient` (`LlmClient` + `EmbeddingClient`)
- `RestClient` của Spring, không thêm thư viện AI (ADR-0109 D4). Khóa chỉ đi trong header `x-goog-api-key`, không vào URL, message lỗi hay `toString` (`AiProperties` che khóa).
- **Tên biến khóa: `GEMINI_API_KEY`.** `07` §5.1 và ADR-0109 D4 ghi `AI_API_KEY`; `AI-CHUNG` (10/11) và prompt AI-1 chốt `GEMINI_API_KEY` trong `.env`. Giữ `AI_PROVIDER` (`gemini` | `off`), `AI_LLM_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIM` như ADR-0109. Thiếu khóa không làm BE hỏng lúc khởi động: lỗi `NO_KEY` chỉ xuất hiện khi gọi (BR-AI-10).
- **Phân loại lỗi** (client chỉ phân loại, **không thử lại**; bên gọi quyết định): 429 → `PROVIDER_RATE_LIMIT`; 401, 403, và 400 kèm `API_KEY_INVALID` → `NO_KEY` (khóa bị từ chối; AI-CHUNG nói 400 / 401 là khóa hỏng); `HttpTimeoutException` / `SocketTimeoutException` → `TIMEOUT`; mọi thứ còn lại (5xx, 4xx khác, từ chối kết nối, JSON sai, thiếu `inScope` / `answer`, vector sai số chiều hay thiếu) → `PROVIDER_ERROR`.
- Hết giờ theo [CFG] `ai.llm.timeout_seconds`, đọc ở mỗi lần gọi và dùng chung cho nhúng và sinh (BR-AI-10 nêu cả hai). Chế độ sinh theo `07` §13: nhiệt độ 0,2, `maxOutputTokens` 600, `thinkingBudget = 0`, lược đồ JSON `{inScope, answer}`.

### D3. FSM #13
`AiDocumentTransitionHandler` chép đúng bảng `07` §9: `DRAFT → {PUBLISHED, ARCHIVED}`, `PUBLISHED → {PUBLISHED, ARCHIVED}`, `ARCHIVED → {PUBLISHED}`; `initialStates = {DRAFT}`. **Giả định:** ST22 tạo thẳng tài liệu `CATALOG_PROTOCOL` ở `PUBLISHED` (lời văn dưới bảng) nên không phải một dòng đánh số và không nằm ở `initialStates` (không thêm cạnh theo suy diễn). Hệ quả của việc bảng có cạnh `PUBLISHED → PUBLISHED` (#3, #7): sự kiện **xuất bản** trên tài liệu đã `PUBLISHED` bị chặn riêng (409), vì #3 là sửa và #7 là ST22; **nạp lại** chỉ cho `PUBLISHED` (409 với `DRAFT` / `ARCHIVED`).

### D4. UC93 (`KnowledgeService`, `KnowledgeController`)
- Thứ tự mọi lệnh ghi: khóa dòng `FOR NO KEY UPDATE` rồi **đọc sau khóa** (404) → guard nghiệp vụ (400) → FSM (409) → ghi → audit cùng transaction → phát `AiIndexRequested`. Cùng khóa dòng với bước hoán đổi của ST22.
- Độ dài tiêu đề và nội dung, hai lớp kiểm soát: do service ném `BR-AI-14` (hợp đồng ghi 400 `BR-AI-14`), nên request **không có `@Size`** cho hai trường đó; `topic`, `species`, `content` có `@NotNull` (hợp đồng đánh dấu bắt buộc), nguồn và người rà soát có `@Size` khớp cột.
- **Audit (BR-AI-17):** năm hành động `AI_DOCUMENT_CREATED / UPDATED / PUBLISHED / ARCHIVED / REINDEX_REQUESTED`, `entity_type = ai_documents`. Ảnh chụp trước / sau gồm trường nghiệp vụ, `contentLength` và `contentSha256`, **không chép cả nội dung** (tối đa 20 000 ký tự mỗi lần sửa). **Không cần migration:** `audit_logs.action` và `entity_type` chỉ là `VARCHAR(50)`, không CHECK hay enum phân hệ; màn xem audit (identity-v1 #36) lọc `entityType` bằng chuỗi tự do và không có ánh xạ phân hệ, nên không có gì để nhờ BE-1.
- `list`: mới sửa trước (`updatedAt`, rồi `id` giảm dần), `size` kẹp 1–100, `q` tìm theo tiêu đề không phân biệt hoa thường và thoát `%`, `_`. 201 và 202 đặt bằng `@ResponseStatus`.

### D5. ST22 — nạp chỉ mục
- **Hình dạng:** đọc tài liệu và chia đoạn **không transaction**; nhúng **không transaction và không giữ kết nối** (có test đo `getActiveConnections() == 0`); rồi **một transaction ngắn** (`KnowledgeIndexWriter.swap`): khóa dòng, **kiểm lại dưới khóa** `PUBLISHED` và `version` bằng đúng phiên bản vừa nhúng (khác thì bỏ lượt, dòng giữ nguyên cho lượt sau), xóa **mọi** đoạn cũ (không chỉ đoạn phiên bản cũ, để nạp lại đúng phiên bản hiện tại không vướng `UNIQUE (document_id, doc_version, ordinal)`), ghi đoạn mới, rồi `markIndexed` (phải trúng đúng 1 dòng). Lỗi nhúng hay lỗi ghi: transaction riêng đặt `FAILED` và lý do `[KIND] message` cắt 300 ký tự, **không đụng `ai_chunks`**, nên đoạn cũ vẫn phục vụ (BR-AI-16).
- **Khóa:** `swap` dùng khóa chờ (không `SKIP LOCKED`) và không theo khuôn "một bản ghi một transaction SKIP LOCKED" của job quét: cuộc gọi mạng phải nằm **ngoài** mọi khóa, nên cửa sổ khóa chỉ là bước hoán đổi; UC93 giữ dòng rất ngắn, và một luồng nền duy nhất không để hai lượt nạp cùng tài liệu.
- **Kích hoạt:** (a) sau commit của UC93 và của đồng bộ phác đồ; (b) khi khởi động nếu còn tài liệu `PENDING` / `FAILED` / cũ phiên bản (`ApplicationReadyEvent`); (c) job định kỳ. Cả ba chỉ **xếp hàng** cho một luồng nền `ai-index` (có tập chống xếp trùng): cuộc gọi mạng không chiếm thread của scheduler (4 luồng, ST20 cần luồng trống).
- **Vì sao dùng `AFTER_COMMIT` dù CLAUDE.md cấm cho hệ quả liên-aggregate:** quy ước đó dành cho hệ quả nghiệp vụ phải cùng transaction (listener lỗi phải rollback bên phát). ST22 là tác vụ hệ thống chậm, chạy sau commit là chủ ý (07 §3, "Sau khi UC93 xuất bản / sửa / nạp lại"); độ bền không nằm ở hàng đợi trong bộ nhớ mà ở dòng `PENDING` / `FAILED` trong CSDL cộng hai lượt quét. Listener chỉ gọi `requestIndex`, nên rollback không để lại lượt nạp mồ côi (có test, và có đột biến).
- **Thử lại:** chỉ khi 429 (`PROVIDER_RATE_LIMIT`), tối đa 3 lần, chờ 20 giây; lỗi khác không thử lại. Lô 16 đoạn, nghỉ 800 ms giữa hai lô (`app.ai.indexing.*`, không phải [CFG]). Chat vẫn không được thử lại (BR-AI-10).
- **Lịch:** mọi phút dư 0–4 (mod 5) đã có job dài (ST05, ST15, ST03, ST05 lưu trú, ST13), nên cron `45 */5 * * * *` (giây 45). Chu kỳ thật của đồng bộ phác đồ lấy từ [CFG] `ai.index.catalog_sync_minutes` (đọc ở mỗi lượt, mặc định 60) bằng phép so thời gian trôi qua, nên chu kỳ hiệu lực là bội của 5 phút, không nhỏ hơn giá trị cấu hình.
- **Profile `test` tắt ST22** (`app.ai.indexing.enabled=false`, cron `-`, `api-key` rỗng): không khóa API thì mọi tài liệu `PUBLISHED` của IT khác sẽ bị đặt `FAILED`. Chỉ `KnowledgeIndexTriggerIT` bật lại.

### D6. Đồng bộ phác đồ (`CatalogProtocolSync`)
Mỗi loại vaccine một tài liệu `CATALOG_PROTOCOL`, dựng từ `CatalogQueryApi.listActiveProtocols` + `findVaccineTypeNames` (I-3, không nhập entity của catalog): tiêu đề `Phác đồ tiêm <tên> (<loài>)`, nội dung Markdown tất định (mỗi mũi một mục: tuổi tối thiểu, khoảng cách tới mũi kế, bắt buộc khi lưu trú), mã băm SHA-256. Chưa có → tạo ở `PUBLISHED`; mã băm đổi → dựng lại, `version + 1` (FSM #7); không đổi → không làm gì; **đã `ARCHIVED` → không đụng** (người quản lý không muốn chatbot dùng). Mỗi loại một transaction, lỗi một loại chỉ bị log. Không ghi audit (việc của hệ thống, `updated_by` null).

### D7. ST23
`ChatLogCleanupJob` xóa `ai_chat_logs` có `created_at < now − ai.log.retention_days` (so sánh chặt, đúng biên thì giữ; [CFG] đọc ở mỗi lượt), theo lô 1 000, mỗi lô một transaction, dừng khi hết 60 giây; lỗi được log và nuốt. Cron `29 0 4 * * *`.

## Lệch và khoảng trống của đặc tả — **cần Hiến xác nhận**

1. **V34 mâu thuẫn hợp đồng create.** `ck_ai_documents_manual_has_source` bắt `source_note NOT NULL` với `MANUAL` mọi trạng thái (`07` §8), nhưng `07` §9 #1 và `ai-v1` cho nháp chỉ cần tiêu đề và `sourceNote` tùy chọn ("bắt buộc khi xuất bản"). **Cách làm:** giữ hợp đồng, không migration; nháp chưa có nguồn lưu **chuỗi rỗng**, API trả `null`, publish kiểm không rỗng. Phương án khác: V51 đổi CHECK thành `status = 'DRAFT' OR source_note IS NOT NULL` (V51+ là số chung với BE-1, BE-2).
2. **Sửa tài liệu `PUBLISHED` (BR-AI-14: "Sửa nội dung … phải cập nhật lại người và ngày rà soát"):** hiểu là **request phải kèm** `reviewerName` và `reviewedOn` khi đổi **nội dung**, nếu không 400 `BR-AI-14`; đổi chỉ tiêu đề thì không cần. Hợp đồng PATCH chưa liệt kê trường hợp này ở mục 400 (chỉ nói "nội dung quá dài" và `CATALOG_PROTOCOL`); mô tả trong yaml giữ nguyên, ghi ở đây.
3. **Đổi tiêu đề cũng tăng `version`:** tiêu đề nằm trong văn bản đem nhúng (BR-AI-15), nên đoạn cũ lệch tiêu đề mới nếu không nạp lại. Đổi chủ đề, loài, nguồn, người rà soát không tăng `version`.
   *Khớp FE:* mock T57 (`FE/src/shared/api/mock/ai.mock.ts`, ghi chú A8) và `pages/admin/ai/DocumentForm.tsx` đã dùng đúng các cách hiểu 1–4 (nháp không bắt nguồn; chỉ gửi kèm người rà soát khi đổi nội dung; đổi tiêu đề tăng `version`; phác đồ → 400 `BR-AI-13`), nên màn hình hiện có không bị 400 bất ngờ.
4. **Phác đồ do hệ thống quản lý:** sửa tay hoặc xuất bản lại một tài liệu `CATALOG_PROTOCOL` → 400 `BR-AI-13` (`07` §9 #5 giới hạn xuất bản lại cho `MANUAL`; hợp đồng không ghi mã lỗi cho việc này); lưu trữ thì được (#4). Tài liệu phác đồ đã lưu trữ **không bao giờ tự quay lại** (đồng bộ bỏ qua nó).
5. **Loại vaccine hết phác đồ đang dùng:** không tạo tài liệu mới, **tài liệu cũ (nếu có) giữ nguyên `PUBLISHED` với nội dung cũ**: chatbot có thể trả phác đồ đã ngừng. Đặc tả không nói; TBD (đề xuất lưu trữ tự động).
6. **Khóa ngoại `ai_documents.vaccine_type_id RESTRICT`:** xóa một loại vaccine đã có tài liệu phác đồ sẽ lỗi khóa ngoại. Nếu catalog cho xóa loại vaccine chưa dùng, cần xử lý ở UC31 (không thuộc T77a).
7. `AI_API_KEY` (ADR-0109 D4, `07` §5.1) so với `GEMINI_API_KEY` (AI-CHUNG): đã chọn `GEMINI_API_KEY`; sửa chữ ở `07` §5.1 khi nhớ tới.
8. **Audit (BR-AI-17 viết "kèm giá trị trước và sau"):** ảnh chụp lưu trường nghiệp vụ, `contentLength` và `contentSha256`, **không lưu cả nội dung** (tối đa 20 000 ký tự mỗi lần sửa). Không có tiền lệ cho văn bản dài: module `content` chưa audit bài viết, `catalog` chỉ audit giá (`PriceAuditSnapshot`). BR có ưu tiên 1, nên cần Hiến xác nhận; phương án khác là lưu nguyên nội dung ở `before_data` / `after_data`. Tương tự, **đồng bộ phác đồ do hệ thống tạo / sửa tài liệu mà không ghi audit** (actor null, không có người dùng); BR-AI-17 chỉ nêu thao tác của người quản lý, nhưng cũng có thể hiểu là gồm cả hệ thống.

## Đột biến (test phải đỏ)

24 đột biến, mỗi lần chỉ sửa một chỗ trong mã, chạy test liên quan, rồi khôi phục (script ở phiên làm việc, không nằm trong repo). **Cả 24 đều làm ít nhất một test đỏ**; mã gốc không bị giữ lại bản sửa nào.

| # | Đột biến | Test đỏ |
|---|---|---|
| M01 | Bỏ kiểm `reviewerName` / `reviewedOn` khi xuất bản (hai lớp kiểm soát BR-AI-14) | `AiDocumentFsmIT.publishNeedsBothControlLayersAndNamesEveryMissingField`, `aPublishedDocumentCannotLoseItsSourceOrItsReviewer` |
| M07 | UC93 đọc `findById` thay vì khóa dòng trước (`FOR NO KEY UPDATE`) | `AiDocumentFsmIT.editWaitsForTheRowLockAndNeverOverwritesAConcurrentChange` |
| M08 | Audit ghi bằng `recordIndependently` (ngoài transaction của use case) | `AiDocumentFsmIT.aFailedAuditRollsTheBusinessChangeBack` |
| M14 | Xuất bản được tài liệu đang `PUBLISHED` (bỏ chặn riêng cạnh #3 / #7) | `AiDocumentFsmIT.everyEventFromEveryStateFollowsTable03` |
| M19 | Bỏ yêu cầu kèm người và ngày rà soát mới khi sửa **nội dung** tài liệu `PUBLISHED` | `AiDocumentFsmIT.editingTheContentOfAPublishedDocumentNeedsAFreshReviewerAndBumpsTheVersion` |
| M20 | Sửa nội dung / tiêu đề `PUBLISHED` không tăng `version` | `editingTheContentOfAPublishedDocument…`, `editingOnlyTheTitleOfAPublishedDocumentBumpsTheVersionWithoutANewReviewer`, `editWaitsForTheRowLockAndNeverOverwritesAConcurrentChange` |
| M02a | Hoán đổi không trong một transaction (xóa và ghi đoạn commit riêng, `REQUIRES_NEW`) | `KnowledgeIndexerIT.aRejectedFinalUpdateRollsTheWholeSwapBackAndTheOldChunksKeepServing` |
| M02b | Bỏ bước đặt `indexed_version` (`markIndexed`) | 5 ca của `KnowledgeIndexerIT` (nạp, truy hồi, hoán đổi, idempotent, rollback) |
| M03a | ST22 lỗi không đặt `FAILED` | 8 ca của `KnowledgeIndexerIT` (lỗi nhúng, cắt 300 ký tự, không thử lại, hết lần thử 429, không có đoạn, …) |
| M03b | ST22 lỗi làm mất đoạn cũ (`fail` xóa `ai_chunks`) | `KnowledgeIndexerIT.embeddingFailureMarksTheDocumentFailedAndTheOldChunksKeepServing`, `aRejectedFinalUpdateRollsTheWholeSwapBack…` |
| M04 | `reindex` không idempotent (xóa chỉ đoạn của phiên bản cũ) | `KnowledgeIndexerIT.reindexingTheSameVersionIsIdempotent` |
| M05 | Bỏ kiểm lại phiên bản dưới khóa ở bước hoán đổi | `KnowledgeIndexerIT.aVersionBumpDuringEmbeddingDiscardsTheRunAndLeavesTheDocumentPending` |
| M06 | Giữ transaction suốt lúc nhúng (`@Transactional` trên `indexDocument`) | `KnowledgeIndexerIT.noDatabaseConnectionIsHeldWhileTheProviderIsCalled` (kèm 3 ca lỗi hệ quả) |
| M22 | Thử lại mọi loại lỗi, không chỉ 429 | `KnowledgeIndexerIT.otherProviderErrorsAreNotRetried` (3 ca) |
| M10 | Kích hoạt ST22 ngay trong transaction (`@EventListener` thay `AFTER_COMMIT`) | `KnowledgeIndexTriggerIT.aTransactionThatFailsAtCommitNeverStartsIndexing`, `editingAPublishedDocumentReindexesItToTheNewVersion` (cả hai quá hạn chờ) |
| M17 | Đồng bộ phác đồ ở mọi lượt chạy, bỏ so thời gian với [CFG] | `KnowledgeIndexTriggerIT.theJobSyncsTheCatalogOnlyOncePerConfiguredIntervalThenQueuesTheIndexing` |
| M12 | Đồng bộ phác đồ ghi đè tài liệu đã `ARCHIVED` | `CatalogProtocolSyncIT.anArchivedDocumentIsLeftAloneWhateverTheSchedule` |
| M11 | ST23 xóa cả dòng đúng biên (`<` thành `<=`) | `ChatLogCleanupIT.deletesOnlyLogsOlderThanTheRetentionAndKeepsTheExactBoundary` |
| M18 | ST23 cứng 30 ngày thay vì đọc [CFG] `ai.log.retention_days` | `ChatLogCleanupIT.readsTheRetentionFromTheConfigAtRunTime` |
| M09a | Khóa API đưa vào URL (`?key=`) | `GeminiClientTest.theKeyNeverAppearsInTheUrlOrInAnyErrorMessage`, `embedQueryUsesQueryTaskType768DimensionsAndSendsTheKeyOnlyInTheHeader` |
| M09b | 429 không còn là `PROVIDER_RATE_LIMIT` | `GeminiClientTest.http429IsAProviderRateLimit` |
| M09c | Hết giờ cứng 60 giây thay vì [CFG] `ai.llm.timeout_seconds` | `GeminiClientTest.slowProviderIsATimeoutAtTheConfiguredSeconds` |
| M15 | Chia đoạn không chồng lấn (`OVERLAP = 0`) | `DocumentChunkerTest.longSectionIsCutIntoPiecesOfAtMost1200CharactersWithOverlap` |
| M16 | Chia đoạn không cắt cứng văn bản không có dấu ngắt | `DocumentChunkerTest.textWithoutAnyBreakIsHardCutSoNoPieceExceeds1200Characters` |

Ghi chú: M08 và M02a được chọn thay cho `noRollbackFor`, vì PostgreSQL đánh dấu transaction đã lỗi là abort nên `noRollbackFor` không đổi kết quả (COMMIT thành ROLLBACK). Bài rollback của ST22 dùng trigger tạm từ chối câu UPDATE `INDEXED` (kỹ thuật của `InAppNotificationIT`). Lần đầu M10 chỉ làm đỏ một bài phụ vì lỗi audit xảy ra trước khi phát event; bài rollback đã được viết lại để lỗi xảy ra lúc commit (constraint trigger hoãn) và chạy lại đột biến.

## Kiểm thử đã chạy

Chạy trên cây `dev` sau commit gộp `6983608` (có AI-0 `4ea419e`), biên dịch sạch (`mvn clean`), Docker Desktop bật, BE không chạy:

| Nhóm | Kết quả |
|---|---|
| Toàn bộ unit test (`mvn test`) | **5734 ca, 0 đỏ.** AI-0 ghi 5679; +55 của T77a: `GeminiClientTest` 18, `AiDocumentTransitionHandlerTest` 13 (mọi cặp trạng thái của FSM #13), `DocumentChunkerTest` 12, `ChatLogCleanupPropertiesTest` 5, `AiIndexingPropertiesTest` 4, `AiPropertiesTest` 3 |
| IT mới của module `ai` | **74 ca, 0 đỏ:** `AiDocumentFsmIT` 33 (UC93 qua API, ma trận sự kiện × trạng thái, BR-AI-14, audit, rollback, khóa dòng, lưu trữ có hiệu lực ngay), `KnowledgeIndexerIT` 20 (ST22), `CatalogProtocolSyncIT` 7, `KnowledgeIndexTriggerIT` 6 (kích hoạt nền), `ChatLogCleanupIT` 5 (ST23), `AiJobScheduleIT` 3 |
| Nhóm contract / schema | `AiSchemaIT` 26, `SchemaMigrationIT` 28, `Tier3Be1SchemaIT` 14, `Tier3Be2SchemaIT` 23, `Tier3Be2ContractWiringIT` 2: xanh. **`OpenApiContractParityIT` 11 ca, 5 đỏ, không thuộc T77a** (bên dưới) |
| Nhóm IT `care` (job, outbox, Hikari: cùng loại hạ tầng với ST22 / ST23) | 153 ca, 0 đỏ |

**Parity.** Trước T77a, trên cây chưa gộp: 11 / 11 xanh (ghim 187). Trên cây gộp, 5 ca đỏ chỉ nhắc khóa `feedbacks` (hợp đồng `content-v1` của lần gộp đã bỏ trạng thái feedback và `POST /api/feedbacks/{id}/resolve`, mã BE-2 thì chưa): `Expected size: 194 but was: 193` (193 = 187 + 7 `ai` − 1 `resolve`), và mọi dòng "code ≠ hợp đồng" là `GET/POST /api/me/feedbacks`, `GET /api/feedbacks[/{id}]`, `POST /api/feedbacks/{id}/resolve`. Bảy endpoint `ai` đều khớp hợp đồng (operationId, mô tả, mã lỗi, tham số, cây thuộc tính, ví dụ). Ghim 194 đúng khi mã và hợp đồng `content` cùng một phía.

**07 §14.3 (phần tích hợp):** truy hồi lọc `PUBLISHED` + `doc_version = indexed_version` (`KnowledgeIndexerIT`, `AiSchemaIT` P6), hoán đổi phiên bản không để khoảng trống và rollback giữ đoạn cũ (`KnowledgeIndexerIT`), lưu trữ có hiệu lực ngay (`AiDocumentFsmIT`), phân quyền UC93 (ADMIN, LỄ TÂN, KHÁCH đều 403; không token 401), FSM #13 mỗi chuyển và mỗi cặp bị từ chối. Phần đơn vị của chat (cấp cứu, ngưỡng, che dữ liệu, định tuyến ý định, bộ đếm) thuộc T77b; giới hạn tần suất chatbot và UC94 thuộc các việc sau.

**Chưa chạy / chưa làm:** Gemini thật (cần khóa; phiên này không gọi mạng); BE khởi động thật bằng `docker compose` (chưa kiểm `GEMINI_API_KEY` đi từ `.env` vào container); các nhóm IT còn lại (customer, visit, appointment, catalog, branch, inventory, boarding, sales, identity) trên cây gộp; nạp KB demo và đo p95 (AI-2).

## Hệ quả và nợ

- Chưa có chat: `LlmClient` đã có và được test, nhưng chưa nơi nào gọi. `ai_chat_logs` và `ai_item_profiles` chỉ có entity, chưa có logic (T77b, T78).
- **Chưa chạy với Gemini thật** (cần khóa, phiên này không gọi mạng): phân loại lỗi được kiểm bằng máy chủ HTTP giả, chưa với phản hồi thật; p95 và nạp KB demo là việc AI-2 / T77c.
- `CLAUDE.md` cần cập nhật (nhờ Hiến): ADR kế tiếp **0131**, parity ghim **194**, era IT: ST23 lấy **1982** (kế tiếp **1981**), IT không có job quét lấy 2039 / 2040 / 2041 (kế tiếp **2042**); hàng "Module ai" ở bảng trạng thái.
- Parity: trên cây đang gộp `origin/dev` (3/11…10/11) `OpenApiContractParityIT` có 5 ca đỏ **không thuộc T77a** (hợp đồng `content-v1` đã bỏ trạng thái feedback còn mã BE-2 thì chưa); không dòng nào nhắc `/api/ai/…`. Khi Mạnh gỡ `POST /api/feedbacks/{id}/resolve` khỏi mã, số ghim sẽ cần trừ 1 so với 194 khi gộp.
