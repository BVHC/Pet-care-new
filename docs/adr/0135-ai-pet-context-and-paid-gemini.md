# ADR-0135: Ngữ cảnh thú cưng trong chat cá nhân và chuyển sang gói Gemini trả phí (AI v2, phần 2)

- **Trạng thái:** Proposed (Hiến duyệt **hướng** 13/11/2026; nội dung chờ Hiến duyệt; T91). **Công tắc `ai.chat.pet_context_enabled` (`BOOL`) mặc định `false` và chỉ được bật sau khi mục "Hiến xác minh" dưới đây hoàn tất.** **Đã cài ở T91 (14/11/2026): mã và V56 xong, công tắc `false`, mục "Hiến xác minh" còn trống, chưa bật ở đâu và chưa đo với Gemini thật** (xem "Đã cài ở T91").
- **Ngày:** 2026-11-13
- **Liên quan:** ADR-0122 (gói free, che tên thú), 0129 (I-1…I-10 đã cài), 0131 (đường ống), 0132 (chat cá nhân, che tên thú sau định tuyến), 0134, 0136; `07` §4.5 BR-AI-25 (v2), §4.7 BR-AI-32, §5.2 bước 6c, §5.4 khối `THÔNG TIN THÚ`, §12; `docs/06` §6b I-1.
- **Đánh số:** [CFG] `ai.chat.pet_context_enabled` và cột `ai_chat_logs.used_pet_context` ở **V56** (ADR viết "V53" ở bản đề xuất; V53 chỉ mang khóa `business_intent`, T90; V54 và V55 đã bị BE-1 / BE-2 lấy ở `origin/dev` trước khi T91 được đẩy lên, xem "Đã cài ở T91"). Không có endpoint mới.

## Bối cảnh

Chat cá nhân (T77c) chỉ có hai ý định tất định (lịch hẹn, lịch tiêm) và che tên thú trước khi gửi LLM; mọi câu tư vấn đều không biết thú là loài gì, bao nhiêu tuổi, nặng bao nhiêu. Hiến muốn "AI biết cụ thể từng người". Rào cản chính không phải mã (I-1, I-2, I-6 đã có) mà là **quyền riêng tư**: ADR-0122 và BR-AI-25 chấp nhận gói free với điều kiện chỉ gửi câu hỏi đã che; gửi thêm hồ sơ thú lên gói free là một quyết định khác. Ngày 13/11 Hiến chấp nhận chuyển gói trả phí.

## Quyết định

### D1. Gói Gemini trả phí là điều kiện tiên quyết, nhưng không phải điều kiện đủ
- Hiến tạo project Google Cloud có billing, bật Gemini API Paid, đặt **trần chi phí** (budget alert và, nếu có, giới hạn cứng), tạo khóa mới, **thu hồi các khóa đã dán vào chat**. Khóa chỉ đi qua `GEMINI_API_KEY` (ADR-0130 D2), không đổi mã.
- "Trả phí" **không** được hiểu là không lưu dữ liệu (Zero Data Retention là cấu hình riêng, có điều kiện). Trước khi bật công tắc, Hiến xác minh và ghi vào mục "Hiến xác minh" của ADR này: (1) đúng loại dịch vụ đang gọi (Gemini API Paid hay Vertex AI); (2) điều khoản xử lý prompt / response (có dùng để cải thiện sản phẩm không); (3) chính sách lưu trữ log phía Google và thời hạn; (4) billing và trần; (5) các tính năng lưu trữ / logging bật mặc định cần tắt. Người viết ADR **chưa đọc** điều khoản; ADR không khẳng định gì về nội dung điều khoản.
- Ước lượng chi phí để đặt trần (số từ `07` §13, giá trích 5/11/2026, phải kiểm lại): một lượt chat ≈ 1 500 token vào + 200 token ra với `gemini-2.5-flash` ≈ $0,0006; 1 000 lượt / ngày ≈ $0,6 / ngày; nhúng khi nạp 200 tài liệu ≈ không đáng kể. Trần đề xuất: **$10 / tháng** cho demo.

### D2. `PetContextBuilder` trong `module/ai/chat` (BR-AI-32)
- Chạy ở bước 6c của §5.2, **chỉ** ở `/me/ai/chat`, **chỉ** khi `ai.chat.pet_context_enabled = true` (đọc [CFG] mỗi lượt), **sau** định tuyến ý định cá nhân (BR-AI-26) và che tên thú (ADR-0132), **trước** nhúng.
- Khách = `CustomerQueryApi.findCustomerIdByAccountId(token)`; thú = thú đã nhận diện ở BR-AI-26 (tên thú trong câu) hoặc thú duy nhất còn sống của khách; nhiều thú mà câu không nêu tên → **không** dựng ngữ cảnh (không hỏi lại, vì câu tư vấn chung vẫn trả lời được; `NEED_PET_CHOICE` chỉ dành cho ý định cá nhân). Kiểm chủ sở hữu qua `PetQueryApi.findPetProfile` (`customerId` khớp, chưa mất) ở **mọi** lượt, không cache.
- Chọn trường theo ý định (từ khóa, cùng cơ chế `PersonalIntentRouter`):

| Ý định | Trường gửi | Nguồn |
|---|---|---|
| Dinh dưỡng, cân nặng (`ăn`, `khẩu phần`, `béo`, `gầy`, `cân`) | loài, giống, nhóm tuổi (`AgeGroup.of`, không gửi ngày sinh), cân nặng mới nhất (kg, 1 chữ số thập phân), triệt sản | I-1 (+ `sex`, `isNeutered` mới) |
| Tiêm phòng, ký sinh trùng (`tiêm`, `vaccine`, `xổ giun`, `ve`) | loài, nhóm tuổi, tóm tắt mũi gần nhất theo loại vaccine (tên loại, "đã tiêm <tháng/năm>", trạng thái DUE_SOON / OVERDUE) | I-2, `VaccinationSummaryService` |
| Chăm sóc, vệ sinh, thẩm mỹ, hành vi (mặc định) | loài, giống, nhóm tuổi, 3 dịch vụ gần nhất (tên nhóm dịch vụ, tháng/năm) | I-1, I-6 |

- **Không gửi:** `petId`, `customerId`, tên thú (vẫn `[thú]`), ngày sinh chính xác, email, SĐT, token, bệnh án, đơn thuốc, ghi chú khám. Không có dữ liệu thức ăn (DB không có cột); không suy đoán.
- Khối `THÔNG TIN THÚ` trong lời nhắc v2 (`07` §5.4) là văn bản tất định do BE dựng; lời nhắc nói rõ không bịa ngoài khối này và không dùng để chẩn đoán / nêu liều (BR-AI-02, 06 giữ nguyên, hậu kiểm liều vẫn chạy).
- Nhật ký: chỉ `used_pet_context = true` (V53); **không** ghi nội dung khối. CHECK `ck_ai_chat_logs_no_content_for_personal` không đổi.

### D3. Thay đổi interface của module khác (báo Dũng)
- `PetQueryApi.PetProfile` thêm `Sex sex`, `Boolean isNeutered` (cột `pets.sex`, `pets.is_neutered` đã có từ V1). Record đổi chữ ký → mọi `new PetProfile(...)` trong test của `customer`, `ai`, `boarding` phải sửa; ghi ở `06` §6b. Phương án không đổi record (method mới `findPetBody(petId)`) bị loại vì thêm một lượt đọc cho mỗi câu.

### D4. Công tắc và hành vi khi tắt
- `ai.chat.pet_context_enabled = false` (mặc định, V53): bước 6c bị bỏ qua hoàn toàn; `/me/ai/chat` hành xử đúng như T77c; `used_pet_context = false`. Đổi [CFG] có hiệu lực ở lượt sau (đọc mỗi lượt), không cần khởi động lại.

## Phương án đã loại

- **Gửi toàn bộ hồ sơ thú cho mọi câu:** tốn token, lộ dữ liệu không cần, trái nguyên tắc tối thiểu; chọn trường theo ý định.
- **Lưu ngữ cảnh vào `ai_chat_logs` để gỡ lỗi:** trái BR-AI-24 và mở rộng dữ liệu cá nhân trong bảng LOG; nếu cần gỡ lỗi, bật log ứng dụng tạm thời với che.
- **Giữ gói free và chỉ gửi trường đã "ẩn danh":** loài / giống / tuổi / cân nặng cộng câu hỏi vẫn có thể định danh gián tiếp; Hiến đã chọn trả phí.
- **Hỏi lại thú khi khách có nhiều thú ở câu tư vấn chung:** làm phiền với câu không cần ngữ cảnh; chỉ hỏi lại ở ý định cá nhân như hiện nay.

## Hệ quả và rủi ro

- Mỗi lượt `/me` có ngữ cảnh tốn thêm 1–3 truy vấn đọc (I-1, I-2 hoặc I-6) trước khi nhúng; p95 tăng vài chục ms, đo ở T92.
- Lộ dữ liệu giữa khách là rủi ro nặng nhất: IT bắt buộc hai tài khoản, mỗi tài khoản hai thú, kiểm khối ngữ cảnh gửi đi (qua `FakeLlmClient` ghi lại prompt) không chứa dữ liệu của tài khoản kia; đột biến "bỏ kiểm `customerId`" phải đỏ.
- Phụ thuộc Hiến hoàn tất mục "Hiến xác minh"; nếu không, T91 cài xong nhưng **để tắt**, và báo cáo đồ án phải ghi đúng là chưa bật.
- Chi phí thật: trần $10 / tháng có thể chạm khi đo bộ v2 nhiều lần (≈ 128 câu × 3 lượt × 0,0006 ≈ $0,25 / lượt đo) — chấp nhận.

## Kiểm thử dự kiến (T91)

- Unit `PetContextBuilderTest`: chọn trường theo ý định (3 nhóm), không trường cấm (regex trên khối: không `@`, không 9–12 chữ số, không tên thú), nhiều thú không nêu tên → rỗng, thú đã mất → rỗng, công tắc `false` → rỗng.
- IT `PetContextIT` (Testcontainers, `FakeLlmClient` bắt prompt): hai khách, cách ly 100 %; `used_pet_context` đúng; `ai_chat_logs` không chứa cân nặng / giống; [CFG] đổi `false` → `true` có hiệu lực lượt sau.
- Đột biến bắt buộc đỏ: bỏ kiểm chủ sở hữu; gửi `petId`; gửi ngày sinh thay nhóm tuổi; bỏ đọc [CFG] (cứng `true`); ghi khối vào nhật ký.

## Đã cài ở T91 (14/11/2026)

**Trạng thái: đã cài; công tắc `ai.chat.pet_context_enabled = false`; chưa bật ở đâu; chưa đo với Gemini thật; mục "Hiến xác minh" bên dưới vẫn trống (không do T91 điền).** Hiến trả lời "ok mặc định" cho kế hoạch T91 (14/11/2026): các điểm (a)–(e), (g), (h) theo mặc định. **Điểm (f) (áp migration lên DB demo 5433 và chạy lại `t90-live.mjs` với công tắc thú cưng tắt) đã HOÃN, không làm:** `origin/dev` (PR #32, 14/11) đã chiếm V54 và đang có hai file V52, nên số Flyway còn chờ Hiến quyết định. **Cập nhật 16/11/2026:** `origin/dev` đã đổi `drop_feedback_status_columns` của T70 từ V52 thành V55 (commit `4c7b9fa`, hết trùng V52) nên T91 (V55 lúc đó) trùng V55; khi gộp `origin/dev` vào `dev`, T91 **nhường số và lấy V56** (migration chưa áp lên DB nào; không nhánh remote nào có V56 trở lên hay ADR 0141 trở lên). **V56 chưa áp lên 5433** (đã kiểm chỉ-đọc 14/11: Flyway của 5433 dừng ở V53, không có cột `used_pet_context`, không có khóa `ai.chat.pet_context_enabled`; `business_intent_enabled = false`, `general_knowledge_enabled = true` như trước T91) và **chưa chạy `t90-live.mjs`**; mọi kiểm thử T91 đến nay là unit, Testcontainers và mock FE. Hai việc chưa có trả lời riêng và **còn mở**: Dũng đã biết `PetProfile` đổi chữ ký chưa (T91 làm thay như AI-0 / T90, chỉ thêm; tin ở `msg-be-ai-v2-t91.md` chờ Hiến gửi trước khi push), và trần chi phí $10 / tháng.

| Đề xuất (bản ADR) | Đã cài | Lý do |
|---|---|---|
| Cột và khóa seed ở V53 | **V56** (`ai_chat_logs.used_pet_context BOOLEAN NOT NULL DEFAULT false` + dòng `system_configs` `ai.chat.pet_context_enabled` = `false`); `ConfigKey` +1 (87 → 88 trên `dev`; 83 sau khi gộp `origin/dev`, vì T70 gỡ 6 khóa `complaint.*` và thêm `feedback.retention_days`) | V53 đã dùng cho `business_intent` (T90). V54 (`orders.version`, D045) và V55 (`drop_feedback_status_columns`, T70; đánh số lại từ V52 ở `origin/dev` bằng commit `4c7b9fa`) đã ở `origin/dev` trước khi T91 được đẩy lên, nên T91 lần lượt nhường V54, V55 và lấy **V56**. Chuỗi số sau gộp: V51 T70, V52 T92, V53 T90, V54 D045, V55 T70 (feedback), V56 T91. DB đã áp V53 mà chưa có V51 (như 5433) cần `outOfOrder` một lần (ghi ở chú thích V56) |
| `PetProfile` thêm `Sex sex`, `Boolean isNeutered` | Thêm **hai trường cuối**; enum `customer/api/Sex` (`MALE`, `FEMALE`, `UNKNOWN`) tách khỏi `customer.entity.PetSex`; 20 chỗ `new PetProfile(...)` ở test được sửa | `api` không lộ kiểu entity; chỉ thêm, không đổi chữ ký method. **`sex` có ở record nhưng KHÔNG được gửi**: bảng D2 không có cột giới tính, nguyên tắc tối thiểu; để dành |
| BR-AI-32 (`07`) "nhiều thú chưa rõ → `NEED_PET_CHOICE`" mâu thuẫn D2 "không dựng ngữ cảnh, không hỏi lại" | Theo **D2**; `07` BR-AI-32 đã sửa cho khớp | Câu tư vấn chung vẫn trả lời được không cần ngữ cảnh; hỏi lại làm phiền. `NEED_PET_CHOICE` chỉ ở ý định cá nhân như cũ |
| Chọn thú: tên trong câu hoặc thú duy nhất | Đúng một thú khớp tên (so `PhraseMatcher.norm`), hoặc không nêu tên mà khách có đúng một thú còn sống. **Hai tên khác nhau trong một câu, hai thú sống trùng tên, nêu tên không phải thú của khách + khách nhiều thú → không khối.** Hồ sơ đọc lại bằng `findPetProfile` ở mọi lượt (khớp `customerId`, chưa mất), không cache | D2; trùng tên là mơ hồ nên không đoán |
| (không có trong ADR) khách **một** thú gõ tên thú của người khác ("Bơ nên ăn gì" với Bơ của khách B) | Không nhận ra tên (router chỉ biết tên thú của chính khách) nên khối là của thú duy nhất của chính khách. **Chấp nhận**: không lộ dữ liệu của B, nhưng lời khuyên có thể bị chỉnh theo thú của người hỏi. Test là "khối không bao giờ chứa dữ liệu của khách khác" (`PetContextIT`), không phải "tên lạ → rỗng" | Không có cách đáng tin để phát hiện "tên không nằm trong danh sách của tôi" |
| "3 dịch vụ gần nhất (tên nhóm, tháng/năm)" | **Tên nhóm** (`khám/tiêm`, `thẩm mỹ`, `lưu trú`) + tháng/năm, **không** tên dịch vụ; sắp theo ngày giảm dần rồi `serviceId` giảm dần (tất định); chỉ hỏi catalog cho ba dịch vụ mới nhất | Tối thiểu hóa (mặc định Hiến duyệt) |
| Văn bản khối "tất định" | Một dòng mỗi nhóm trường, tiếng Việt, ≤ 400 ký tự: `Loài: chó. Giống: Poodle. Nhóm tuổi: trưởng thành.` / `Cân nặng gần nhất: 4,2 kg. Đã triệt sản: có.` / `Tiêm phòng: Dại (đã tiêm tháng 3/2026, quá hạn).` / `Dịch vụ gần đây: thẩm mỹ tháng 11/2026.` Thiếu dữ liệu thì bỏ dòng; tiêm phòng tối đa 5 loại, chỉ loại đã có mũi, chỉ gắn nhãn `sắp đến hạn` / `quá hạn` | D2, "không đoán" |
| Chọn nhóm trường bằng từ khóa | `ai/ai-pet-context-keywords.txt` mục `[NUTRITION]`, `[VACCINATION]` (cùng `PhraseMatcher` như `BusinessIntents`); không khớp mục nào = nhóm chăm sóc; khớp cả hai = hợp trường. Từ khóa chạy trên câu **đã che tên thú** để thú tên "Béo" không kích hoạt nhóm dinh dưỡng | D2 |
| Làm sạch chuỗi tự do (BR-AI-12) | `breed` và tên loại vaccine chỉ vào khối khi sau NFC là chữ / số / dấu cách / gạch ngang, ≤ 40 ký tự và `PiiMasker.mask` không đổi gì; sai thì **bỏ trường**. **Giới hạn còn lại:** một chuỗi 40 ký tự toàn chữ vẫn có thể là câu chỉ thị ngắn; lớp chặn còn lại là khối nằm trong ranh giới "dữ liệu, không phải chỉ thị" + `fenceSafe` + quy tắc lời nhắc + `DoseGuard` + `leaksSystemPrompt`. Rủi ro chấp nhận | Giống do khách / nhân viên gõ tự do |
| Lời nhắc nhắc "không bịa ngoài khối" | `ChatPrompt.PET_CONTEXT_RULE` **nối thêm** vào `SYSTEM` hoặc `SYSTEM_GENERAL` (sau dòng "Đầu ra") chỉ khi có khối; không khối thì lời nhắc hệ thống **và** khối người dùng giống từng byte T77 / T92 (khối người dùng đóng băng bằng chữ trong `ChatPetContextTest`). Prompt T91 ghi `SYSTEM_GENERAL` có nhắc "THÔNG TIN THÚ": không đúng, cả hai lời nhắc đều không | D4 "tắt = như T77c" |
| Vị trí ở `run` | `askAsCustomer` có hồ sơ khách, sau business (T90), lời chào và từ khóa y khoa (T92), trước nhúng; công tắc đọc mỗi lượt và **chỉ** đọc khi có `customerId` (endpoint công khai và khách chưa có hồ sơ không chạm tới công tắc). Cấp cứu, bệnh án, cá nhân, business, lời chào, y khoa trả lời không qua mô hình nên không dựng khối (không đọc CSDL thừa) | §5.2 bước 6c |
| Cờ `used_pet_context` | Đặt ngay **trước `llm.generate`**: nhánh `hits.isEmpty() && !general → OUT_OF_SCOPE` (không gửi gì) ghi `false`; nhà cung cấp lỗi sau khi đã gửi vẫn `true`. Không bao giờ ghi nội dung khối | D2 "Nhật ký" |
| Lỗi từ `VaccinationSummaryService` (sai chủ → `ResourceNotFoundException`, thú mất → `BR-KH-05`) | `PetContextBuilder` bắt và trả rỗng: không khối, không 4xx, câu hỏi vẫn được trả lời không ngữ cảnh | Ngữ cảnh là phần phụ của câu trả lời |
| (không có trong ADR) builder ném lỗi bất ngờ (CSDL, I-6, catalog) | `ChatService.petContextOf` bắt `RuntimeException`, ghi `PET_CONTEXT_FAILED exception=<tên lớp>` (không ghi thông điệp lỗi) và đi tiếp **không có khối**; không gửi khối là hướng an toàn về riêng tư | BR-AI-10: AI hỏng không chặn trang; khối chỉ là phần phụ, không đáng làm cả lượt chat thành 500 |
| Giao dịch | `PetContextBuilder` không `@Transactional`; 1–3 lệnh `*QueryApi` ngắn, xong trước lệnh LLM | ADR-0131 |
| Era IT | `PetContextIT` dùng era **2048** | era 2047 là `KnowledgeImportIT` |

**Kiểm thử đã chạy (cây sau T91, bản sao cô lập):** unit toàn bộ **6218 ca / 0 đỏ** (nền 6175; mới: `PetContextBuilderTest` 28, `ChatPetContextTest` 15); `PetContextIT` **8** ca (hai khách, cách ly, thú đổi chủ / mất giữa hai lượt, ba nhóm trường trên dữ liệu thật, công tắc đổi có hiệu lực lượt sau, endpoint công khai, nhật ký); `PetAiReadIT` 6 (thêm `profileCarriesSexAndNeuteredStatusInAllThreeStates`); `AiSchemaIT` 36 (thêm công tắc và cờ NOT NULL / mặc định `false`); `SchemaMigrationIT` (phiên bản của T91; số đã đổi V54 → V55 → V56, xem trên), `ChatIT` (cột mới), `SystemConfigIT` (88 khóa trên `dev` trước gộp, **83 sau gộp** `origin/dev`), `ConfigKeyTest` (88 → **83** khóa, 21 khóa `ai.*`); FE `identity-admin.mock.test.ts` và `AdminSystemConfigsPage.test.tsx` (82 khóa). Nhóm IT rộng hơn: `ai` + `com.petcare.*IT` (gồm `ChatIT` 30, `MeChatIT` 20, `SchemaMigrationIT` 28, `OpenApiContractParityIT`) **294 ca, 289 xanh, 5 đỏ cũ** (đều là `OpenApiContractParityIT`: `POST /api/feedbacks/{feedbackId}/resolve` có ở code mà không có trong `docs/api/openapi`, số endpoint 200 so với ghim 201; của `feedbacks`, không của T91); `customer` + `catalog` + `identity` + các `*AiReadIT` **1120 ca / 0 đỏ**; sau khi đổi V54 → V55, chạy lại `SchemaMigrationIT`, `AiSchemaIT`, `PetContextIT`, `SystemConfigIT`, `SystemConfigAdminIT` **187 ca / 0 đỏ**. Vitest toàn bộ 230 file / 5296 ca: 5294 xanh; 2 ca đỏ (`MySchedulePage`, `HotelPage`) đều hết giờ khi cả bộ chạy lúc máy đang tải, **25/25 xanh khi chạy riêng hai file** (7 s); chưa chạy cả bộ lặp lại.

**Đo lại sau khi gộp `origin/dev` vào `dev` (16/11/2026; T91 = V56; Docker bật; bản sao cô lập, đã xóa lớp biên dịch cũ):** unit 6257 ca (6256 xanh; `PetcareApplicationTests` đỏ lúc Docker tắt, xanh khi chạy lại); IT cốt lõi **244 ca / 0 đỏ** (`SchemaMigrationIT` 29, `AiSchemaIT` 36, `ChatIT` 30, `MeChatIT` 20, `PetContextIT` 8, `SystemConfigAdminIT` 107, `SystemConfigIT` 3, `OpenApiContractParityIT` 11, **hết 5 ca đỏ cũ** vì T70 đã gỡ endpoint `resolve`); IT mã mới kéo về (`content`, `fees`, `sales/SalesOrderVersionIT`, `customer`, `catalog`, `identity`) **1190 ca / 0 đỏ**. IT chứng minh chuỗi V1 → V56 áp sạch trên DB trống; **chưa** chứng minh đường áp V51 không theo thứ tự trên một DB đã có V53 (đường DB demo 5433 sẽ đi): nên tập trước trên bản restore của bản sao lưu. FE `identity-admin.mock.test.ts` **đỏ 3 ca** vì đọc `V51__drop_complaints_add_feedback_retention.sql` (bộ đọc SQL từ chối `DELETE`): nợ D065 / T81 đã ghi, không do T91; chú thích "88 tham số" trong hai file FE còn đúng cho seed FE hiện tại và sẽ đổi khi T81 đồng bộ seed (thêm `feedback.retention_days`).

**Đột biến (19 ca unit + 2 ca IT, đều làm test đỏ; nền 0 đỏ; `.c2-tmp/t91-mutate.py`, kết quả `final2/t91-mutations.txt`):**

| # | Đột biến | Test đỏ |
|---|---|---|
| M1 | bỏ kiểm chủ sở hữu của hồ sơ | 2 (`aPetThatNowBelongsToAnotherCustomer…`, `ownershipIsCheckedAgainstTheProfileOnEveryCall…`) |
| M2 | gửi `petId` | 2 |
| M3 | gửi ngày sinh thay nhóm tuổi | 6 |
| M4 / M4i | bỏ đọc [CFG] (cứng bật) | 2 unit / 1 IT |
| M5 | ghi nội dung khối vào nhật ký | 1 |
| M6 | bỏ làm sạch `breed` | 7 |
| M7 / M7i | nhiều thú không nêu tên vẫn dựng (lấy thú đầu) | 1 unit / 1 IT (hai khách) |
| M8 | khối đi vào văn bản nhúng | 1 |
| M9 | khối có ở endpoint công khai | 2 |
| M10 | bỏ kiểm thú đã mất | 1 |
| M11 | khối ngoài `fenceSafe` | 1 |
| M12 | tên thú nằm trong khối | 2 |
| M13 | quy tắc lời nhắc nối cả khi không có khối | 3 |
| M14 | cờ nhật ký bật trước nhánh `OUT_OF_SCOPE` | 1 |
| M15 | bỏ nhóm từ khóa dinh dưỡng | 10 |
| M16 | bỏ tên thú nhận ra khỏi lời gọi builder | 1 |
| M17 | không bắt lỗi của tóm tắt tiêm phòng | 1 |
| M18 | tháng/năm thành ngày đủ | 4 |
| M19 | builder ném lỗi bất ngờ làm hỏng cả lượt (bỏ bắt `RuntimeException`) | 1 |

**Nợ và việc của Hiến trước khi bật công tắc:**
1. Điền bảng "Hiến xác minh" (6 điểm) ngay dưới; T91 không điền và không diễn giải điều khoản của Google. Chưa điền thì giữ `false`; báo cáo đồ án phải ghi đúng là chưa bật.
2. Chưa đo với Gemini thật (câu trả lời có khối so với không khối, p95 tăng thêm vài chục ms do 1–3 lần đọc). Đo sau khi bật, trên khách demo giả, rồi tắt lại nếu cần.
3. `AI_PROVIDER_NOTICE` (thông báo cho khách rằng dữ liệu thú được gửi cho nhà cung cấp AI) **chưa sửa**: việc mở cho Hiến sau khi xác minh.
4. Trần chi phí $10 / tháng chưa được trả lời; Dũng chưa xác nhận bằng chữ việc đổi `PetProfile`.
5. Nợ chưa đụng: `ai_chat_logs.intent`, `risk_level`, topic `SAFETY` / `LIFESTYLE`, `ai.ingest.max_file_kb` (ADR-0134).

## Hiến xác minh (điền trước khi bật công tắc)

| # | Điểm | Kết quả | Ngày |
|---|---|---|---|
| 1 | Loại dịch vụ đang gọi (Gemini API Paid / Vertex AI) | _chưa_ | |
| 2 | Điều khoản xử lý prompt / response (dùng để cải thiện sản phẩm?) | _chưa_ | |
| 3 | Lưu trữ log phía nhà cung cấp và thời hạn | _chưa_ | |
| 4 | Billing bật, trần chi phí đặt | _chưa_ | |
| 5 | Tính năng lưu trữ / logging mặc định đã tắt | _chưa_ | |
| 6 | Khóa cũ (đã dán vào chat) đã thu hồi, khóa mới chỉ trong `.env` | _chưa_ | |

## Chờ Hiến duyệt (trước khi viết mã)

1. Bảng chọn trường theo ý định (D2) — có bỏ trường nào không (ví dụ triệt sản)?
2. Đổi record `PetProfile` (D3) hay thêm method mới — và Dũng đồng ý không?
3. Trần chi phí $10 / tháng.
