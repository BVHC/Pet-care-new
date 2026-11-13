# ADR-0136: Kiến thức chung có kiểm soát, nguồn câu trả lời và đánh giá hồi quy (AI v2, phần 3)

- **Trạng thái:** Proposed (Hiến duyệt **hướng** 13/11/2026; nội dung chờ Hiến duyệt; T92). **Công tắc `ai.chat.general_knowledge_enabled` (`BOOL`) mặc định `false`; chỉ bật sau khi bộ v2 đạt tiêu chí mục "Kiểm thử" và Hiến cho phép.**
- **Ngày:** 2026-11-13
- **Liên quan:** ADR-0109, 0122 (τ, mô hình), 0131 (đường ống, Q24), 0132 (chat cá nhân), 0134, 0135; `07` §1.1, §4.1 BR-AI-01 / 04 (v2), §4.7 BR-AI-33, 34, 35, §5.2 bước 7–9, §5.4 lời nhắc v2, §8 `ai_chat_logs`, §13, §14.
- **Đánh số:** V52 (cột `ai_chat_logs.source_type`, `intent`, `used_pet_context`; seed [CFG]). Hợp đồng #1, #2 thêm trường tùy chọn `sourceType`.

## Bối cảnh

Hiện nay (BR-AI-04) không đoạn nào đạt τ = 0,60 thì trả `OUT_OF_SCOPE` ngay; kho 52 đoạn nên phần lớn câu chăm sóc hợp lệ rơi vào đó. Hiến muốn AI "biết nhiều hơn" nhưng không thành chatbot mọi chủ đề, và không nới an toàn y khoa. Đo T77 cho thấy lớp an toàn hiện dựa vào cờ `inScope` do LLM đặt (Q24 từng `ANSWERED` 1 / 4 lần), nên mở kiến thức chung mà không thêm phân loại là tăng rủi ro.

## Quyết định

### D1. Công tắc và phạm vi (BR-AI-33)
- [CFG] `ai.chat.general_knowledge_enabled` (`BOOL`, mặc định **`false`**, đọc mỗi lượt). `false`: đường ống đúng như T77b / T77c, kể cả lời nhắc cũ (§5.4 bản 4 quy tắc) và **không** có lớp từ khóa `MEDICAL_RISK`, để bộ 43 câu baseline giữ nguyên kết quả. `true`: lời nhắc v2 và luồng D2.
- Kiến thức chung chỉ cho nhóm `PET_CARE` (chăm sóc, dinh dưỡng cơ bản, vệ sinh, hành vi, môi trường sống, grooming của chó mèo). `OUT_OF_DOMAIN` vẫn `OUT_OF_SCOPE`. `MEDICAL_RISK` vẫn `MEDICAL_REFUSAL` (câu mẫu BR-AI-02). Cấp cứu (BR-AI-03) và bệnh án (BR-AI-28) chặn **trước** LLM như cũ; hậu kiểm liều (BR-AI-06) chạy **sau** LLM với mọi nhóm.

### D2. Phân loại trong cùng một lệnh gọi, không thêm lệnh thứ hai
- Đầu ra JSON đổi từ `{inScope, answer}` sang `{category, answer}` (lược đồ ở §5.4 v2). Không gọi LLM lần hai để phân loại: giữ p95 (đợt 2: 2,79 s) và hạn mức.
- Thứ tự quyết định khi `true`: từ khóa cấp cứu → bệnh án → ý định cá nhân / BUSINESS (BR-AI-26, 31, tất định) → nhúng + truy hồi (τ, K giữ) → LLM với khối tài liệu (có thể rỗng) → ánh xạ `category`:
  - `PET_CARE` + có đoạn ≥ τ → `ANSWERED`, `sourceType = KNOWLEDGE_BASE` (hoặc `KNOWLEDGE_BASE_AND_GENERAL` khi `answer` có đánh dấu "Thông tin tham khảo chung:" dù có đoạn).
  - `PET_CARE` + không đoạn → `ANSWERED`, `sourceType = GENERAL_KNOWLEDGE`, câu trả lời bắt buộc mở đầu "Thông tin tham khảo chung:" (hậu kiểm bằng chuỗi; thiếu → thêm vào), `disclaimer` của phản hồi ghi "không thay bác sĩ thú y".
  - `MEDICAL_RISK` → `MEDICAL_REFUSAL`; `OUT_OF_DOMAIN` → `OUT_OF_SCOPE`; `BUSINESS` (lọt qua từ khóa) → `OUT_OF_SCOPE` với câu mẫu "chưa có dữ liệu".
  - Hậu kiểm liều áp lên `answer` của mọi nhánh `ANSWERED`.
- **Xem lại quyết định Q24 ngày 12/11/2026 (ADR-0131 "Đã chốt" điểm 4: giữ nguyên, theo dõi qua `ai_chat_logs`, không đổi lời nhắc, không thêm lớp từ khóa).** Quyết định đó vẫn đúng ở chế độ `false`. Đề xuất dưới đây **chỉ** áp khi `general_knowledge_enabled = true`, vì khi đó mô hình được trả lời rộng hơn nên câu kiểu "bị bệnh gì" rủi ro hơn; cần Hiến duyệt riêng (mục "Chờ Hiến duyệt" điểm 4), không ngầm sửa ADR-0131. Lớp tất định bổ sung cho `MEDICAL_RISK` (học từ Q24): danh sách cụm "bị bệnh gì", "mắc bệnh gì", "có phải bị", "uống thuốc gì", "liều" trong `ai-medical-risk-keywords.txt` (cùng cơ chế `PhraseMatcher` của BR-AI-03), chạy **trước** LLM → `MEDICAL_REFUSAL` không cần gọi mô hình. Danh sách khởi điểm 10–15 cụm, đo trên bộ v2; không phải [CFG].

### D3. Rủi ro của tài liệu và kho demo
- `risk_level` (ADR-0134) **chưa** lọc truy hồi ở T92; chỉ ghi vào `sources` để giao diện hiện. Lọc theo rủi ro (ví dụ không trích tài liệu `HIGH` cho khách vãng lai) là ADR sau nếu bộ v2 cho thấy cần.
- Kho demo (người rà soát giả định, Q2 mở) vẫn là `KNOWLEDGE_BASE` trong `sourceType`; nhãn giả định nằm ở tài liệu, báo cáo và `source_note`, không ở `sourceType`.

### D4. Nguồn câu trả lời (BR-AI-34)
- `sourceType ∈ DATABASE, KNOWLEDGE_BASE, GENERAL_KNOWLEDGE, KNOWLEDGE_BASE_AND_GENERAL, SAFETY`; `SAFETY` cho `EMERGENCY`, `MEDICAL_REFUSAL`; `DATABASE` cho `PERSONAL`, `NEED_PET_CHOICE`, `NEED_LOGIN`, BUSINESS; `OUT_OF_SCOPE` và `UNAVAILABLE` có `sourceType = null`.
- Phản hồi #1, #2 thêm trường **tùy chọn** `sourceType` (tương thích ngược; mock FE thêm sau). `ai_chat_logs` thêm `source_type`, `intent` (`EMERGENCY, MEDICAL_RECORD, APPOINTMENT_UPCOMING, VACCINATION_SCHEDULE, BUSINESS_PRICE, BUSINESS_BRANCH, PET_CARE, MEDICAL_RISK, OUT_OF_DOMAIN, UNKNOWN`), `used_pet_context` (V52). Không ghi thêm nội dung.
- Bộ đếm `ai.chat.result` thêm nhãn `sourceType` để theo dõi tỷ lệ kiến thức chung (BR-AI-24 "hiệu chỉnh").

### D5. Hybrid RAG: điều kiện, không phải việc
- Chỉ xem xét khi Recall@5 trên nhóm RAG của bộ v2 < 90 % sau khi kho đã mở rộng (T90). Phương án khi đó: `pg_trgm` / `tsvector` (đã có `unaccent`, V33) + vector, hợp điểm RRF; thêm `keywords` của `docs_RAG` vào văn bản nhúng. Không thêm vector DB, không thêm dịch vụ rerank bên ngoài.

### D6. Đánh giá hồi quy (BR-AI-35)
- `BE/scripts/spike/questions.json` (43 câu) **bất biến**; mỗi lượt đo có hai pha: pha A **ba** công tắc `false` (`business_intent_enabled`, `pet_context_enabled`, `general_knowledge_enabled`) phải khớp kết quả T77 đợt 2 (42 / 43, Q14 trượt vì kiểm "không gọi LLM"); pha B bật công tắc cần đo.
- `questions-v2.json` (~85 câu, 6 nhóm: RAG 20, kiến thức chung 15, cá nhân hóa 15, nghiệp vụ 10, an toàn y khoa 15, ngoài phạm vi 10), mỗi câu có `expect.result`, `expect.sourceType`, `expect.llmCalled`, và với cá nhân hóa `expect.noLeak` (hai khách). Câu của bộ 43 đổi hành vi theo thiết kế (nhóm "gần chủ đề": giá vaccine → BUSINESS) ghi ở bộ v2, không sửa baseline.
- Tiêu chí bật công tắc: an toàn y khoa 15 / 15 và ngoài phạm vi 10 / 10 ở **ba lượt liên tiếp**; cá nhân hóa `noLeak` 100 %; kiến thức chung không câu nào `MEDICAL_REFUSAL` sai và không câu nào nêu liều; p95 ≤ 5 s. Đạt trên bộ test **không** là bằng chứng an toàn tuyệt đối; báo cáo phải ghi vậy.
- Chạy trên BE đo riêng với `RATE_LIMIT_AI_MAX`, `RATE_LIMIT_AI_HOUR_MAX` nâng (128 câu / lượt vượt 60 câu / giờ / IP của BR-AI-09); không đổi [CFG] trên BE demo.

## Phương án đã loại

- **Hạ τ đồng loạt** (0,60 → 0,50): câu lạc đề đo 0,50–0,55 sẽ lọt, không giải quyết "kho không có".
- **Lệnh LLM phân loại riêng trước RAG:** p95 tăng ≈ 1 s và gấp đôi số lệnh gọi; gộp vào một lệnh.
- **Cho LLM tự quyết mức rủi ro hoàn toàn:** Q24 cho thấy cờ do mô hình đặt không ổn định; thêm lớp từ khóa tất định và hậu kiểm.
- **Mở kiến thức chung cho mọi chủ đề:** trái quyết định 13/11 (ngoài lĩnh vực vẫn từ chối).
- **Hybrid RAG ngay:** chưa có số đo cho thấy truy hồi là nút thắt; kho 52 đoạn.

## Hệ quả và rủi ro

- Khi bật, số lệnh gọi LLM tăng (mọi câu không lạc đề hẳn đều gọi); chi phí theo ADR-0135; hạn mức gói trả phí cao hơn free.
- Câu trả lời kiến thức chung có thể sai; lưu ý "tham khảo chung" là lớp bảo vệ pháp lý, không phải kỹ thuật. Báo cáo đồ án nêu rõ.
- Đổi lược đồ JSON → `GeminiClient` cần lược đồ thứ hai; `FakeLlmClient` của IT trả theo lược đồ đang dùng.
- Người đọc `ai_chat_logs` thấy thêm `source_type`: có thể lập thống kê (mục mở rộng §17.3) mà không cần nội dung.

## Kiểm thử dự kiến (T92, TDD + đột biến)

- Unit `CategoryMapperTest`: 4 nhóm × có / không đoạn → `result`, `sourceType` đúng bảng D2; thiếu tiền tố "Thông tin tham khảo chung:" được thêm; `MEDICAL_RISK` không bao giờ `ANSWERED`.
- Unit `MedicalRiskDetectorTest` (chỉ chạy khi `general_knowledge_enabled = true`; khi `false` Q24 đi đúng đường T77): Q24 và 10 biến thể → `MEDICAL_REFUSAL` trước LLM; "liều lượng vận động" không khớp.
- IT `ChatGeneralKnowledgeIT`: công tắc `false` → không khác `ChatIT` (tái dùng ca cũ, kể cả Q24 không bị lớp từ khóa chặn); công tắc `true` → không đoạn + `PET_CARE` → `ANSWERED`/`GENERAL_KNOWLEDGE`; `OUT_OF_DOMAIN` → `OUT_OF_SCOPE`, `llm.calls()` vẫn 1; hậu kiểm liều vẫn chặn; `ai_chat_logs.source_type` đúng.
- Đột biến bắt buộc đỏ: bỏ đọc [CFG]; `OUT_OF_DOMAIN` → `ANSWERED`; bỏ hậu kiểm liều ở nhánh kiến thức chung; `sourceType = KNOWLEDGE_BASE` khi không có đoạn; bỏ lớp từ khóa rủi ro.
- Bộ v2 qua BE thật: ba lượt, số đo ghi vào `07` §13 trước khi xin bật.

## Chờ Hiến duyệt (trước khi viết mã)

1. Tiền tố "Thông tin tham khảo chung:" và câu `disclaimer` — chữ cụ thể.
2. Danh sách cụm `MEDICAL_RISK` khởi điểm (10–15 cụm) — Hiến xem trước khi đo.
3. Tiêu chí bật công tắc (ba lượt liên tiếp, p95 ≤ 5 s) có quá chặt hay quá lỏng với demo 11/12?
4. **Lớp từ khóa `MEDICAL_RISK` (D2) xem lại quyết định Q24 ngày 12/11** (ADR-0131 "Đã chốt" điểm 4). Đề xuất chỉ áp khi bật kiến thức chung. Hiến đồng ý, hay giữ đúng quyết định 12/11 cả ở chế độ bật?
