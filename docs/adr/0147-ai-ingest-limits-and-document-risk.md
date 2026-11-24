# ADR-0147: Hoàn tất nạp file: trần [CFG], hết giờ trích, chống nạp trùng, mức rủi ro, topic mới (AI v2, T89 còn lại)

- **Trạng thái:** Accepted (T89, 19/11/2026; Hiến duyệt cả ADR 19/11/2026, kèm phương án B của câu bù dữ liệu cũ, xem "Hệ quả và nợ"). Bổ sung ADR-0134 D1, D2 (đã Accepted, bất biến): ADR này ghi chỗ làm **khác** hoặc **cụ thể hơn** ADR-0134, không sửa nó.
- **Ngày:** 2026-11-19
- **Liên quan:** ADR-0134 (D1 nạp file, D2 trường nguồn), 0130 (FSM #13), 0136 (D3: chính sách lọc theo rủi ro, chưa làm); `07` §4.3 (BR-AI-14, BR-AI-29, BR-AI-30), §4.6, §8, §10 #14; hợp đồng `ai-v1` #5, #6, #8, #14.
- **Đánh số:** Flyway **V59** (V58 là cao nhất ở mọi nhánh remote lúc viết, 19/11/2026). BE-1 từng dự định V59 cho khóa `workforce.*`: Hiến ủy quyền chọn số "sao cho không lỗi"; V59 được chọn vì trùng số báo lỗi to ngay lúc gộp (người gộp sau đánh số lại, như V55 → V56), còn chừa V59 và dùng V60 thì DB nào đã chạy V60 sẽ lỗi Flyway khi V59 đến sau (`out-of-order` tắt), mà DB demo 5433 là nơi dễ dính nhất. Script commit kiểm lại mọi nhánh trước khi commit. ADR **0147**.

## Bối cảnh

T89 đã cài phần nạp file ngày 13/11/2026 (ADR-0134 mục "Đã cài ở T89"), nhưng năm mục của D1, D2 còn thiếu vì chưa có migration: trần cỡ vẫn là hằng 2 MB, chưa có hết giờ trích, nạp lại cùng file tạo thêm nháp, chưa có `risk_level`, `topic` còn sáu giá trị.

## Quyết định

**D1. Trần cỡ là [CFG] `ai.ingest.max_file_kb`** (V59; `INT`, mặc định 2048, khoảng 256–10240, đơn vị kB). Đọc **mỗi lần nạp** (như `ai.chat.business_intent_enabled`), nên `PUT /api/system-configs/ai.ingest.max_file_kb` có hiệu lực ngay. Cùng trần chặn base64 trước khi giải mã. `DocumentFileReader.read` nhận trần làm tham số; không còn hằng 2 MB trong mã. FE bỏ kiểm 2 MB cứng và hiện lỗi 400 của BE (FE không đọc được [CFG]).

**D2. Hết giờ trích 20 giây** (hằng `DocumentExtractor.TIMEOUT`, **không** phải [CFG]). Luồng riêng (2 luồng nền tên `ai-extract-<n>`, hàng đợi rỗng); hết giờ thì `cancel(true)` và 400 `BR-AI-29` "Hết thời gian trích văn bản (quá 20 giây)"; hết luồng thì 429 `TOO_MANY_REQUESTS` (`TooManyRequestsException` có sẵn, `Retry-After` 5 giây). `importFile` **bỏ `@Transactional`**: giải mã, trích, tách và băm xong mới mở một transaction cho cả file (`KnowledgeService.createFromFile`), nên không giữ kết nối DB suốt lúc trích và lỗi hay hết giờ thì không tài liệu nào được tạo.

**D3. `external_id = FILE-<16 hex đầu của SHA-256 của byte file gốc>-<n>`** (`n` từ 1; file không tách vẫn là `-1`). Băm **byte file**, không phải văn bản đã chuẩn hóa: CRLF và LF của cùng nội dung là hai file khác nhau; đổi tên file không đổi mã. Kiểm **mọi** phần trước khi tạo; đã có phần nào (kể cả tài liệu `ARCHIVED`) thì 409 cho nguyên file, không tạo gì. Mã lỗi: `CONCURRENCY_CONFLICT` (HTTP 409), message "File này đã được nạp trước đó thành tài liệu #<id> (BR-AI-30)". `platform/exception` **không có** đường nào ra 409 mang mã quy tắc (`BusinessRuleViolationException` cố định 400), nên dùng lớp riêng `DuplicateImportException` trong `module/ai/exception` (cùng cách `BoardingConflictException`, `PlatformException` ghi rõ cách này), không đổi `platform`. Thứ tự kiểm lỗi của BE: `@Valid` (400) → vai trò (403) → cỡ base64 → base64 hợp lệ → rỗng → cỡ → phần mở rộng → byte đầu → trích (có hết giờ) → không có chữ → tên file → **trùng (409)** → độ dài từng phần (BR-AI-14). Mọi 400 về file thắng 409.

**D4. `risk_level`.** Cột `VARCHAR(30) NULL` + CHECK `ck_ai_documents_risk_level IN ('LOW','MODERATE','HIGH')`, enum `RiskLevel`. **`VARCHAR(30)`, không phải `VARCHAR(10)` của ADR-0134 D2**: `SchemaMigrationIT.enumCheckColumnsAreVarchar30` ép mọi cột enum có CHECK là `VARCHAR(30)` (V34 đã lệch `07` §8 ở cột `species` vì lý do này). `riskLevel` tùy chọn ở request #6 và #8; trả ở #5, #7 và các response tài liệu; có trong ảnh chụp audit (trước và sau). **Không** có ở request #14 (nạp file không đặt được; người quản lý sửa ở #8, script `load-docs-rag.mjs` gửi qua #6). #8 vắng hoặc `null` = giữ nguyên, **không xóa được về trống**. **Chưa** dùng để lọc truy hồi (ADR-0136 D3). Đổi riêng `topic` hay `riskLevel` của tài liệu `PUBLISHED` không tăng `version`, không nạp lại chỉ mục, không bắt rà soát lại, giữ `PUBLISHED` (đã có IT khóa hành vi này).

**D5. `topic` thêm `SAFETY` và `LIFESTYLE`.** `DROP` rồi `ADD` lại `ck_ai_documents_topic` với 8 giá trị. `SAFETY`: `emergency_triage`, `first_aid`, `poisoning_safety`, `family_safety`; `LIFESTYLE`: `adoption`, `travel`, `multi_pet_household`, `senior_care` (bảng ánh xạ `docs_RAG/README.md`, `TOPIC_MAP` của `load-docs-rag.mjs`). Tài liệu đã nạp **không** đổi topic.

## Chỗ khác ADR-0134

| ADR-0134 | Đã cài | Lý do |
|---|---|---|
| D2: `risk_level VARCHAR(10)` | `VARCHAR(30)` | `SchemaMigrationIT.enumCheckColumnsAreVarchar30` |
| D2: Flyway V53 | V59 | V53 đã dùng cho công tắc BUSINESS và `external_id` (T90) |
| D1: Spring `multipart.max-file-size` bằng trần [CFG] | Không áp dụng (JSON base64). Trần 10 240 kB thành ≈ 13,98 triệu ký tự base64; Jackson 2.21.4 `DEFAULT_MAX_STRING_LEN` = 20 triệu (đo bằng `javap`) | request thật 10 240 kB được nhận (xem "Đã đo") |
| D1: 409 `BR-AI-30` | HTTP 409, `errorCode = CONCURRENCY_CONFLICT`, message kết thúc "(BR-AI-30)" | không có mã nào mang rule ID ở 409 |
| D1: audit `importedFrom`, trường `warnings`, PDF đổi dòng in hoa ngắn thành `##`, `.docx` kiểm `word/document.xml` | **Chưa làm** (nợ) | ngoài năm mục của T89 |

## Đã đo (19/11/2026, bản sao DB `petcare_t89`, BE cổng 8083; riêng mục "DB demo 5433" cuối danh sách là DB thật)

- Flyway tự áp V59 trên bản sao có dữ liệu thật (20,7 giây khởi động); cột nullable nên không backfill.
- #14: `.md`, `.docx`, `.pdf` có chữ → 201 `DRAFT`, `externalId` khớp SHA-256 tính ngoài; nạp lại (đổi tên) → 409 nêu `#<id>`; PDF scan, giả đuôi, `.exe`, base64 hỏng → 400 `BR-AI-29`; ADMIN, lễ tân, khách → 403; không token → 401.
- Trần: `PUT` 255 và 10 241 bị từ chối (BR-QT-13); 256 kB đúng trần → 201 (14 phần), trần + 1 byte → 400 "tối đa 256 kB"; nâng lên 512 có hiệu lực ngay.
- Biên 10 240 kB: đúng trần → 201 (528 phần, 5,8 giây, phản hồi 10,7 MB); trần + 1 byte → 400; 12 MB → 400 ngay ở bước chặn base64; PDF hỏng 10 MB → 400 sau 2,0 giây. Bộ nhớ BE khoảng 656 MB, không lỗi bộ nhớ.
- Cỡ thắng định dạng: `.exe` 2 MB + 1 byte trả "vượt quá tối đa 2048 kB".
- Hai request đồng thời cùng file: một 201, một 409 (`CONCURRENCY_CONFLICT`, message chung do chỉ mục duy nhất chặn, không nêu `#<id>`).
- #6, #7, #8, #5: `SAFETY` + `HIGH`, `LIFESTYLE`, `riskLevel` lạ hoặc viết thường → 400 `MALFORMED_REQUEST`; PATCH đổi `riskLevel` giữ `version`; PATCH `null` giữ nguyên.
- Giao diện `/admin/ai` (Vite 5173 → BE 8083, Chrome): 13/13 bước (nạp, nạp lại 409, file 3 MB bị BE từ chối, huy hiệu rủi ro, sửa rủi ro).
- **Chưa đo:** hết giờ trích 20 giây trên file thật (chỉ có test đơn vị với thời hạn tiêm); 429 khi hết luồng (chỉ test đơn vị).
- **DB demo 5433** (sau commit `c0eac03`; BE tạm cổng 8083, `AI_INDEXING_ENABLED=false`): Flyway 58 → 59, khóa `ai.ingest.max_file_kb` = 2048; bù bằng `--backfill-risk-topic --apply` cho 25 tài liệu `docs_RAG` (25 đặt `riskLevel`, 8 đổi topic: `SAFETY` #48, #49, #50, #52; `LIFESTYLE` #51, #53, #54, #55). Đo lại: 70 tài liệu, tổng `version` 76 (không đổi), 39 `PUBLISHED` vẫn `INDEXED`, `CARE` 39 → 31, audit `AI_DOCUMENT_UPDATED` 25 dòng (`riskLevel` rỗng → có giá trị); chạy lại thì 0 việc cần bù. Log BE không có ERROR / WARN.

## Hệ quả và nợ

- Luồng trích có thể chạy tiếp sau khi request đã trả lời (PDFBox, POI không luôn tôn trọng interrupt) và giữ chỗ trong pool 2 luồng; trong lúc đó request mới nhận 429 khi cả pool bận.
- Hai request đồng thời cùng file: bên thua nhận 409 chung, không nêu mã tài liệu (đua chỉ mục duy nhất).
- File 10 MB tách thành ~528 tài liệu trong **một** transaction (5,8 giây); chấp nhận ở quy mô này, không chia lô.
- Nạp lại file đã `ARCHIVED` vẫn 409 (tài liệu giữ `external_id`); người quản lý mở lại tài liệu có sẵn thay vì nạp lại.
- Tài liệu `docs_RAG` đã nạp ở DB demo trước V59 giữ topic `CARE` và `risk_level` NULL. **Hiến chọn phương án B (19/11/2026):** bù bằng chế độ `--backfill-risk-topic` của `BE/scripts/ai-kb/load-docs-rag.mjs`, tức PATCH #8 (có audit `AI_DOCUMENT_UPDATED` trước và sau, **không** tăng `version`, không nạp lại chỉ mục, giữ `PUBLISHED`). Không ghi đè sửa tay: `topic` chỉ đổi khi còn `CARE` và bảng ánh xạ mới cho `SAFETY` / `LIFESTYLE`, `riskLevel` chỉ đặt khi còn rỗng; tài liệu `ARCHIVED` bỏ qua. Mặc định chỉ in kế hoạch, ghi khi thêm `--apply`; cần BE đã chạy V59. Đã đo trên bản sao DB và **đã chạy trên DB demo 5433 ngày 19/11/2026** (xem mục "Đã đo").
- FE mock không mô phỏng hết giờ trích và 429; `externalId` của mock dùng FNV-1a thay SHA-256 (chỉ giữ tính "cùng byte thì cùng mã").

## Hiến đã duyệt (19/11/2026) và việc còn lại

Hiến duyệt cả bốn điểm: V59 cho T89 (BE-1 chuyển `workforce.*` sang V60), `CONCURRENCY_CONFLICT` làm mã của 409 `BR-AI-30`, `risk_level` `VARCHAR(30)`, pool trích 2 luồng không hàng đợi (hết luồng 429). Việc còn lại:

1. Hỏi Mạnh còn file V59 nào chưa đẩy lên không (script commit chỉ thấy nhánh remote); nếu có thì đánh số lại file này.
2. ~~Chạy `--backfill-risk-topic --apply` trên DB demo 5433~~ **Đã làm 19/11/2026** sau khi Hiến commit `c0eac03`, có `BAO TRUOC` / `DA GHI` ở `SHARED-CHANGES.md` và sao lưu `petcare_seed_19-11_truoc-V59-backfill.dump`.
