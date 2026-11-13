# ADR-0134: Nạp tài liệu từ file và quản trị nguồn kho tri thức (AI v2, phần 1)

- **Trạng thái:** Proposed (Hiến duyệt **hướng** 13/11/2026; nội dung chờ Hiến duyệt trước khi viết mã; T88 / T89 / T90).
- **Ngày:** 2026-11-13
- **Liên quan:** ADR-0109 (LLM, embedding, pgvector), 0110 (V34 `ai_documents`), 0130 (UC93, FSM #13, ST22, #5 lưu trữ tự động phác đồ), 0131 (đường ống chat), 0135, 0136 (hai ADR còn lại của AI v2); `07` §1.1, §4.3 (BR-AI-13, BR-AI-14, BR-AI-29, BR-AI-30, BR-AI-31), §4.6, §8, §10, §11 (I-11, I-12); `docs/docs_RAG/00_governance/*`; hợp đồng `ai-v1`.
- **Đánh số:** Flyway **V53** (V52 đã dùng cho công tắc kiến thức chung ở T92, không sửa; V49 chừa BE-2, V51 dành cho gỡ bảng khiếu nại ở T70). Hợp đồng `ai-v1` thêm #14. Mã BR mới từ BR-AI-29 (26–28 đã dùng).

## Bối cảnh

Sau AI-7, kho tri thức chatbot chỉ có 32 tài liệu (29 `MANUAL` tự soạn, nguồn chưa xác minh, người rà soát **giả định**, Q2 chưa giải; 3 `CATALOG_PROTOCOL`). UC93 chỉ có một đường tạo tài liệu: gõ Markdown vào `content` (≤ 20 000 ký tự, V34). Hiến đo thấy chatbot "biết ít" vì câu hỏi không có đoạn nào đạt ngưỡng τ = 0,60 (BR-AI-04) nên trả `OUT_OF_SCOPE`. Ngày 13/11 Hiến chọn hướng: mở rộng kho trước, qua nạp file và nạp bài viết đã xuất bản, vẫn giữ hai lớp kiểm soát BR-AI-14. Cùng ngày có bộ nguồn `docs/docs_RAG` (21 tài liệu Markdown có frontmatter, nguồn WSAVA / AVMA / AAHA / Merck, toàn bộ `DRAFT`, `review.approved: false`).

## Quyết định

### D1. Nạp file thành tài liệu `MANUAL` ở `DRAFT` (BR-AI-29)
- Endpoint mới `POST /api/ai/documents/import` (ai-v1 **#14**), `multipart/form-data`: `file` (bắt buộc), `topic`, `species` (bắt buộc, như #6), `sourceNote` (tùy chọn). SUPER_MANAGER (BR-AI-17). Trả `201` danh sách tài liệu đã tạo (một file có thể thành nhiều tài liệu).
- Kiểu file theo **byte đầu và phần mở rộng** cùng khớp: `.md`, `.txt` (UTF-8, có BOM thì bỏ), `.docx` (zip có `word/document.xml`), `.pdf` (`%PDF-`). Khác → 400 `BR-AI-29`.
- Kích thước ≤ [CFG] `ai.ingest.max_file_kb` (mặc định **2048**, khoảng 256–10240); Spring `spring.servlet.multipart.max-file-size` đặt bằng trần [CFG] (10 MB) để [CFG] là chốt thật. Quá cỡ → 400 `BR-AI-29`.
- Trích văn bản: `.docx` bằng Apache POI (`poi-ooxml`), `.pdf` bằng PDFBox; **không lưu file gốc**, chỉ lưu văn bản. `.docx` là zip: giới hạn tổng byte giải nén (POI `ZipSecureFile.setMaxEntrySize`, tỷ lệ nén) để chặn zip bomb. PDF không có lớp chữ (scan) → 400 `BR-AI-29` "không trích được chữ; OCR ngoài phạm vi". Thời gian trích bị chặn bởi một `ExecutorService` riêng với hết giờ 20 giây.
- Chuẩn hóa: CRLF → LF, bỏ ký tự điều khiển, cắt khoảng trắng; `.docx` / `.pdf` chuyển tiêu đề (heading style / dòng in hoa ngắn) thành `##` ở mức tốt nhất có thể, phần còn lại là đoạn văn. Chất lượng chia đoạn (BR-AI-15 cắt theo tiêu đề Markdown) của PDF sẽ kém hơn `.md`: ghi rõ ở phản hồi (`warnings`).
- Văn bản > 20 000 ký tự: **tách theo `##`** thành nhiều tài liệu, tiêu đề `"<tên file> (phần n/N)"`; mỗi phần vẫn ≤ 20 000, nếu một mục đơn lẻ vượt thì cắt cứng ở ranh giới dòng. Ghi `external_id = FILE-<sha256 16 hex của file>-<n>` để chạy lại không tạo trùng (409 `BR-AI-30` nếu đã có).
- Tài liệu tạo ra **luôn `DRAFT`**, `source_note` = `sourceNote` người dùng gửi hoặc `"Nạp từ file <tên> (<kiểu>, <kB>)"`, không `reviewer_name`. Xuất bản đi đúng #9 với hai lớp kiểm soát (BR-AI-14). Vì vậy nạp file **không** đổi FSM #13 và không chạm truy hồi.
- Audit: `AI_DOCUMENT_CREATED` cho từng tài liệu (như #6) kèm `importedFrom` (tên file, kiểu, kích thước, hash) trong ảnh chụp sau.

### D2. Trường nguồn và phiên bản trên `ai_documents` (BR-AI-30, V53)
- Thêm `external_id VARCHAR(100) NULL`, `UNIQUE` một phần (`WHERE external_id IS NOT NULL`): khóa idempotent cho file nạp (`FILE-…`), bài viết (`ARTICLE-<id>`), bộ `docs_RAG` (`PET-…`, khi script chuyển sang dùng cột này).
- Thêm `risk_level VARCHAR(10) NULL CHECK (risk_level IN ('LOW','MODERATE','HIGH'))`: lấy từ frontmatter `docs_RAG` hoặc người quản lý đặt khi sửa; **chưa** dùng để lọc truy hồi ở T89 (chính sách lọc theo rủi ro thuộc ADR-0136 D3).
- Enum `topic` thêm `SAFETY` (cấp cứu, sơ cứu, ngộ độc, an toàn trẻ em) và `LIFESTYLE` (nhận nuôi, đi lại, nhiều thú, thú già): V53 `DROP CONSTRAINT ck_ai_documents_topic` + `ADD` lại 8 giá trị; `DocumentTopic` và hợp đồng (#5 lọc `topic`, #6, #8) thêm hai giá trị; FE `labels.ts` thêm nhãn. Bảng ánh xạ `docs_RAG` (README) sau V53 đổi nhóm an toàn → `SAFETY`, lối sống → `LIFESTYLE` (script đọc enum từ một hằng, đổi một chỗ).
- **Không** thêm trạng thái `PENDING_REVIEW` / `REJECTED` / `EXPIRED` vào DB (xem "Phương án đã loại"). Ánh xạ: file `VERIFIED` + `review.approved` = `PUBLISHED` có `reviewer_name` + `reviewed_on`; còn lại = `DRAFT`; `REJECTED` / `EXPIRED` = không nạp, đã nạp thì lưu trữ tay.
- `keywords`, `life_stage`, `valid_from`, `valid_until` của frontmatter: **nợ**, không có cột. `keywords` có thể nhét vào văn bản nhúng sau khi đo Hybrid RAG (ADR-0136 D5), không phải bây giờ.

### D3. Nạp bài viết đã xuất bản (BR-AI-30, I-12, T90)
- Module `content` (BE-2, Dũng) công bố `ContentQueryApi.listPublishedArticles(Instant since)` và `findArticle(Long id)` (tiêu đề, nội dung Markdown / HTML đã lược thẻ, tác giả, `publishedAt`, `updatedAt`); chủ module xác nhận ở `06` §6b.
- ST22 thêm bước "đồng bộ bài viết" cùng chu kỳ với đồng bộ phác đồ (`ai.index.catalog_sync_minutes`): bài mới → tạo tài liệu `MANUAL` **`DRAFT`**, `external_id = ARTICLE-<id>`, `source_note = "Bài viết #<id> của <tác giả>, xuất bản <ngày>"`; bài đổi nội dung sau khi tài liệu đã `PUBLISHED` → cập nhật nội dung, `version + 1` (như #7) và **giữ** `PUBLISHED` chỉ khi người rà soát còn nguyên (nếu BR-AI-14 đòi rà soát lại thì chuyển về `DRAFT`, Hiến chọn ở mục "Chờ Hiến"); bài gỡ / ẩn → `ARCHIVED` với audit actor hệ thống, như ADR-0130 #5; bài xuất bản lại → quay lại theo đúng quy tắc phương án A của ADR-0130 (`updated_by IS NULL`).
- Bài viết do bác sĩ viết và được SUPER_MANAGER duyệt (UC80, UC82) **không** tự động coi là đã qua lớp 2 của BR-AI-14: vẫn cần `reviewer_name` khi xuất bản vào kho. Lý do: duyệt bài là duyệt nội dung đăng công khai, không phải duyệt để chatbot trích dẫn theo đoạn.

### D4. Giá, chi nhánh, tình trạng dịch vụ không vào kho (BR-AI-31, I-11, T90)
- Sau công tắc [CFG] `ai.chat.business_intent_enabled` (`BOOL`, mặc định `false`, đọc mỗi lượt): thêm ý định `BUSINESS_PRICE` và `BUSINESS_BRANCH` vào bộ định tuyến từ khóa hiện có (`PersonalIntentRouter`, mở rộng sang endpoint công khai), chạy **trước** truy hồi, sau cấp cứu và bệnh án: "giá", "bao nhiêu tiền", "chi phí", "phí" + tên dịch vụ; "chi nhánh", "địa chỉ", "giờ mở cửa".
- Trả câu mẫu từ `CatalogQueryApi.findServicePriceByName(String)` (khớp không dấu, `pg_trgm` đã có từ V33) và `BranchQueryApi.listActiveBranches()` (**đã có**, trả tên, địa chỉ, SĐT, cấp cứu; không thêm method); không gọi LLM; `sourceType = DATABASE` (ADR-0136 D4). Không khớp tên → "Mình chưa có dữ liệu giá cho dịch vụ này, bạn xem bảng giá hoặc hỏi lễ tân", không rơi sang kiến thức chung.
- Hệ quả: BR-AI-01 bỏ "giá vaccine" khỏi ví dụ "gần chủ đề ngoài phạm vi"; câu Q của bộ 43 thuộc nhóm này **đổi kỳ vọng** → ghi ở bộ v2, baseline 43 giữ nguyên và được chạy với `ai.chat.business_intent_enabled = false` (ADR-0136 D6), nên Q16 "Giá tiêm vaccine dại bao nhiêu?" vẫn `OUT_OF_SCOPE` ở pha baseline.

### D5. Bộ `docs/docs_RAG` (T88, đã làm 13/11)
- Nạp bằng `BE/scripts/ai-kb/load-docs-rag.mjs` qua UC93 #6, idempotent theo tiêu đề (chuyển sang `external_id` sau V53), nạp ở `DRAFT` rồi (13/11, Hiến chọn B) **xuất bản bằng `--publish-demo` với người rà soát giả định** "BS. Nguyễn Văn Nam" (sourceNote có nhãn "Duyệt giả định cho demo."); `--archive-superseded` lưu trữ 25 tài liệu demo cũ (chỉ các tiêu đề của `BE/scripts/spike/kb`, giữ 4 tài liệu riêng của Pet Care và 3 phác đồ). Đã chạy trên DB demo 5433: 28 `PUBLISHED`, 52 đoạn `INDEXED`. Test đơn vị 15 / 15, dry-run 21 / 21.
- Người rà soát là **giả định** như KB demo trước (Hiến quyết 9/11); nhãn giả định giữ ở mọi tài liệu; không coi là `VERIFIED` thật (Q2 chưa giải). Bộ này gồm các tài liệu `HIGH` (cấp cứu, sơ cứu, ngộ độc, vaccine, ký sinh trùng) nên **chỉ dùng cho demo**, không dùng cho chạy thật khi chưa có bác sĩ thú y thật.

## Đã cài ở T89 (13/11/2026, Hiến yêu cầu "nạp được file") và chỗ khác bản đề xuất

Phần nạp file (D1) đã cài trước khi ADR được duyệt, theo yêu cầu của Hiến ngày 13/11. **Khác bản đề xuất ở trên** (ghi để Hiến duyệt lại):

| Đề xuất | Đã cài | Lý do |
|---|---|---|
| `multipart/form-data` | **JSON base64** `{fileName, contentBase64, topic, species, sourceNote?}` | `m_ai.py` và `OpenApiContractParityIT` chưa hỗ trợ multipart, chưa có tiền lệ trong repo; JSON dùng chung cơ chế hợp đồng và mock FE |
| Trần cỡ là [CFG] `ai.ingest.max_file_kb` (V53) | **Hằng 2 MB** (`DocumentFileReader.MAX_FILE_BYTES`) | Chưa có V53; đổi sang [CFG] khi làm V53 |
| Hết giờ trích 20 giây | **Chưa có** (chỉ chặn cỡ file 2 MB và XML `.docx` giải nén > 10 MB) | Hạn chế bằng cỡ; làm khi có V53 |
| `external_id` chống nạp trùng | **Chưa có**: nạp lại cùng file tạo thêm nháp | Cần V53; người quản lý xem danh sách và lưu trữ nháp thừa |
| `risk_level`, topic `SAFETY` / `LIFESTYLE` | **Chưa có** | Cần V53 |
| POI `poi-ooxml`, PDFBox | **Đã thêm**: `poi-ooxml` 5.2.5, `pdfbox` 2.0.27 | Chỉ các bản này có sẵn trong Maven cache của máy dev; ghi nhận để Hiến xác nhận hoặc nâng bản khi có mạng (PDFBox 3.x) |

Đã làm đúng đề xuất: kiểm byte đầu **và** phần mở rộng, PDF scan bị từ chối (OCR ngoài phạm vi), không lưu file gốc, chặn zip bomb `.docx`, file > 20 000 ký tự tách theo `##` thành "(phần n/N)", tài liệu luôn `DRAFT` không người rà soát, một transaction cho cả file, audit `AI_DOCUMENT_CREATED` như #6, chỉ SUPER_MANAGER. Kiểm thử: `DocumentFileReaderTest` 11 ca, `KnowledgeImportIT` 6 ca, mock FE 5 ca + 2 ca giao diện (`AdminAIPage.test.tsx`).

## Phương án đã loại

- **Thêm `PENDING_REVIEW`, `REJECTED`, `EXPIRED` vào FSM #13:** đổi CHECK V34, bảng chuyển trạng thái `07` §9, 7 endpoint và FE; lợi ích chỉ là phân biệt "nháp đang chờ" với "nháp đang soạn", trong khi `DRAFT` + `reviewer_name` null đã đủ cho người quản lý lọc. `REJECTED` = lưu trữ từ nháp (#6), `EXPIRED` = lưu trữ từ xuất bản (#4).
- **Tự xuất bản file nạp khi frontmatter ghi `VERIFIED`:** mâu thuẫn Hiến chốt 13/11 ("file nạp phải qua người rà soát như gõ tay") và BR-AI-14; chỉ giữ `reviewer_name` trong file làm giá trị điền sẵn.
- **OCR cho PDF scan:** thêm Tesseract / dịch vụ ngoài, chi phí và rủi ro nội dung sai; ngoài phạm vi.
- **Lưu file gốc:** thêm kho tệp, chính sách giữ / xóa, rủi ro dữ liệu cá nhân trong tệp; không cần cho RAG.
- **Đưa giá vào kho tri thức:** dữ liệu đổi theo giờ, nhúng lại tốn kém, và LLM có thể trộn giá cũ; DB là nguồn sự thật.

## Hợp đồng và migration đề xuất (làm ở T89 / T90 cùng mã, không sửa `ai-v1.yaml` tay)

| Chỗ | Thay đổi |
|---|---|
| `docs/api/generator/m_ai.py` | #14 `POST /ai/documents/import` (multipart); #5 lọc `topic` và #6 / #8 enum thêm `SAFETY`, `LIFESTYLE`; `AiDocumentResponse` thêm `externalId`, `riskLevel` (nullable); 400 `BR-AI-29`, 409 `BR-AI-30` |
| V53 | `ai_documents`: `external_id`, `risk_level`, enum `topic` 8 giá trị; seed [CFG] `ai.ingest.max_file_kb` (INT 2048, 256–10240) và `ai.chat.business_intent_enabled` (BOOL `false`) |
| `ConfigKey` | `AI_INGEST_MAX_FILE_KB("ai.ingest.max_file_kb", INT)`, `AI_CHAT_BUSINESS_INTENT_ENABLED("ai.chat.business_intent_enabled", BOOL)`; `ConfigKeyTest`, `SystemConfigIT`, `SystemConfigAdminIT`: **85 → 89** khóa (4 khóa v2 của ADR-0134, 0135, 0136; đếm 13/11 trên `ConfigKey.java`. T92 đã thêm 1 khóa ở V52: 85 → 86; ba khóa còn lại cùng V53 → 89); mock `identity-admin.seed.ts` thêm 4 dòng |
| `pom.xml` | `org.apache.poi:poi-ooxml`, `org.apache.pdfbox:pdfbox` (phiên bản chốt sau `mvn dependency:tree`, kiểm xung đột `commons-*`) |
| `OpenApiContractParityIT` | ghim 200 → 201 (+1 endpoint) |
| `docs/06` §6b | I-11 (`catalog`), I-12 (`content`), cả hai của BE-2: chờ Dũng xác nhận; chi nhánh tái dùng `listActiveBranches()` |

## Hệ quả và rủi ro

- Người quản lý có thể nạp 10 MB văn bản một lần → hàng trăm `DRAFT`; danh sách #5 phân trang nên chịu được, nhưng cần lọc `topic` / `status` tốt (FE T93).
- Thư viện POI / PDFBox lớn (≈ 15 MB jar), tăng thời gian khởi động không đáng kể; lỗ hổng của hai thư viện này là rủi ro mới, pin phiên bản và cập nhật theo CVE.
- Chất lượng đoạn từ PDF thấp: có thể làm độ tương đồng giảm; đo ở T92 trước khi kết luận cần Hybrid RAG.
- Bài viết đồng bộ tự động vào `DRAFT` có thể làm người quản lý ngập nháp nếu `content` có nhiều bài; T90 thêm bộ lọc `externalId LIKE 'ARTICLE-%'`.

## Kiểm thử dự kiến (T89 / T90, TDD + đột biến)

- IT `KnowledgeImportIT`: mỗi kiểu file một ca xanh (có `##` → một tài liệu `DRAFT`), file giả mạo phần mở rộng → 400, quá [CFG] → 400 và [CFG] đổi có hiệu lực ngay, PDF không chữ → 400, `.docx` zip bomb (tỷ lệ nén > 100) → 400, văn bản 45 000 ký tự → 3 tài liệu ≤ 20 000, chạy lại cùng file → 409, tài liệu nạp **không** xuất hiện trong truy hồi (`ChunkRetriever`), audit có `importedFrom`.
- Đột biến bắt buộc đỏ: bỏ kiểm byte đầu; bỏ trần kích thước; tạo `PUBLISHED` thay `DRAFT`; không tách > 20 000; bỏ `external_id` (chạy lại tạo trùng).
- IT đồng bộ bài viết (`ArticleSyncIT`): mới → `DRAFT`; sửa → `version + 1`; gỡ → `ARCHIVED` có audit; xuất bản lại → quay lại (phương án A).
- Unit `BusinessIntentRouterTest`: "giá tắm cho chó" → `BUSINESS_PRICE`; "giá trị dinh dưỡng" → không phải giá; "chi nhánh gần nhất" → `BUSINESS_BRANCH`.

## Chờ Hiến duyệt (trước khi viết mã)

1. Có chấp nhận thêm hai thư viện POI, PDFBox không (so với chỉ `.md` / `.txt` ở bước đầu, `.docx` / `.pdf` ở bước sau)?
2. Bài viết đổi nội dung sau khi tài liệu đã `PUBLISHED`: giữ `PUBLISHED` và `version + 1`, hay về `DRAFT` chờ rà soát lại? (Đề xuất: về `DRAFT`, vì BR-AI-14 nói sửa nội dung phải cập nhật người rà soát.)
3. Enum `topic` thêm `SAFETY`, `LIFESTYLE` (V53) hay giữ 6 giá trị và để `CARE` ôm hết?
4. Nạp thật 21 tài liệu `docs_RAG` vào 5433 (DRAFT) và có xuất bản với người rà soát giả định cho demo không?
