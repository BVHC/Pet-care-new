# ADR-0157: Câu hỏi cá nhân mơ hồ ở `/me/ai/chat` hỏi Gemini một nhãn; công tắc `ai.chat.intent_llm_enabled` (V67)

- **Trạng thái:** Proposed (Hiến chọn phương án "B nhẹ" và công tắc trong trang admin ngày 2026-11-27)
- **Ngày:** 2026-11-27
- **Liên quan:** docs/07 BR-AI-26 (định tuyến ý định cá nhân), BR-AI-10 (không thử lại), BR-AI-12 (che dữ liệu cá nhân), BR-AI-24 (câu cá nhân không ghi nội dung), BR-AI-28a (khách vãng lai), ADR-0142 (chat cá nhân), ADR-0136 (công tắc AI, lược đồ JSON có enum)

## Bối cảnh

BR-AI-26 nhận câu hỏi lịch hẹn / lịch tiêm cá nhân chỉ khi có **từ khóa ý định cùng dấu hiệu sở hữu** ("của tôi", "nhà mình", tên thú…). Khách gõ tự nhiên "tôi có lịch hẹn ko" có từ khóa nhưng không có dấu hiệu sở hữu, nên câu đi RAG và mô hình trả lời "chatbot không có dữ liệu lịch hẹn của bạn" (đo 27/11/2026 trên DB 5433). Hiến muốn chatbot hiểu cách gõ tự nhiên, chấp nhận nới luật.

Đã cân nhắc: (A) mở rộng luật từ khóa, bỏ yêu cầu sở hữu khi đã đăng nhập — rẻ nhưng phải giữ danh sách loại trừ câu chung; (B) Gemini phân loại **mọi** câu — hiểu tốt nhất nhưng mỗi câu thêm một lượt gọi, câu cá nhân luôn ra Google, kết quả dao động; (B nhẹ) luật trước, Gemini chỉ cho câu mơ hồ. Hiến chọn **B nhẹ**.

## Quyết định

1. **Phạm vi:** chỉ `POST /me/ai/chat` (khách đã đăng nhập). `POST /public/ai/chat` không bao giờ gọi bước này (BR-AI-28a giữ nguyên).
2. **Câu mơ hồ** = câu có từ khóa `APPOINTMENT` hoặc `VACCINATION` của BR-AI-26, **không** có cụm chỉ giá, và luật hiện có **không** nhận ý định (thiếu dấu hiệu sở hữu đủ mạnh). Bước này chạy sau cấp cứu và bệnh án, trước BR-AI-31 và truy hồi, đúng chỗ của BR-AI-26.
3. **Gemini chỉ trả một nhãn** qua lược đồ JSON có enum `{intent: APPOINTMENT_UPCOMING | VACCINATION_SCHEDULE | NONE}` (cùng cơ chế enum của ADR-0136), nhiệt độ 0, tối đa 64 token (20 token cắt cụt JSON ở Gemini thật, xem "Kết quả"), không thinking. Lời nhắc có ví dụ lấy từ chính BR-AI-26: "Mũi tiêm tiếp theo của chó con là khi nào?", "Quá hạn tiêm vaccine dại có sao không?", "Chó con tiêm phòng dại lúc mấy tuần?" là `NONE`. Câu hỏi nằm trong rào `"""` ở vai người dùng, lời nhắc dặn bỏ qua mọi chỉ thị bên trong.
4. **Nhãn cá nhân** thì trả lời như BR-AI-26 nhận ý định: `PersonalService` dùng dữ liệu DB, câu mẫu, không nhúng, không LLM viết câu trả lời, không ghi nội dung vào nhật ký (BR-AI-24). **`NONE`** thì đi tiếp đường cũ.
5. **Lỗi không làm hỏng lượt hỏi:** hết khóa, quá giờ, lỗi nhà cung cấp, JSON lạ hoặc nhãn ngoài enum đều coi là `NONE` (hành vi trước ADR này). Không thử lại (BR-AI-10).
6. **Dữ liệu gửi đi:** câu đã che email / SĐT / số giấy tờ (BR-AI-12a) **và** tên thú (BR-AI-12b); không gửi các câu hỏi trước, không gửi khối thông tin thú. Đây là thay đổi so với ADR-0142: câu cá nhân **mơ hồ** nay rời máy chủ (đã che); câu cá nhân rõ ràng vẫn không.
7. **Công tắc [CFG] `ai.chat.intent_llm_enabled`** (BOOL, mặc định **`'true'`**, đọc mỗi lượt hỏi, V67): tắt (`'false'`) thì bước này không chạy, chatbot quay về đúng BR-AI-26 cũ. Bật / tắt qua trang cấu hình admin (`PUT /api/system-configs/ai.chat.intent_llm_enabled`).
8. **Từ khóa:** thêm `có hẹn` vào `[APPOINTMENT]` của `ai-personal-intent.txt` (dùng chung hai endpoint: khách vãng lai gõ "có hẹn sắp tới không" nay nhận lời mời đăng nhập).
9. **Quan sát:** bộ đếm Micrometer `ai.chat.intent_llm` tag `outcome` = `APPOINTMENT_UPCOMING` | `VACCINATION_SCHEDULE` | `NONE` | `ERROR`, và log INFO `PERSONAL_INTENT_LLM outcome=… durationMs=…` **không có nội dung câu**. Không thêm giá trị mới cho `ai_chat_logs.intent` (CHECK `ck_ai_chat_logs_intent` của V57 không đổi): lượt cá nhân vẫn ghi `APPOINTMENT_UPCOMING` / `VACCINATION_SCHEDULE`.
10. `LlmClient` có thêm `String classify(String systemPrompt, String userBlock, List<String> labels)` (chung, không biết gì về ý định); hợp đồng ai-v1 và FE không đổi.

## Hệ quả

- **Chi phí:** chỉ câu mơ hồ của khách đã đăng nhập tốn thêm một lượt gọi Gemini (khoảng 1–2 giây). Câu kiến thức về tiêm ("Chó con tiêm phòng dại lúc mấy tuần?" — gợi ý có sẵn trong khung chat) thuộc nhóm này khi người hỏi đã đăng nhập.
- **Phân loại sai** vẫn có thể xảy ra: câu chung bị coi là cá nhân thì khách nhận danh sách lịch của mình (dữ liệu thật, không bịa); ngược lại thì nhận câu trả lời từ tài liệu như trước ADR này.
- **Flyway V67 và V65:** viết là V66, đổi thành V67 ngày 27/11/2026 vì V66 của BE-2 (ADR-0156, bỏ trả hàng / hoàn tiền) vào `origin/dev` trước. V65 (trigger khóa bệnh án, ADR-0153) đang ở overlay `D:/Do-an/.c2-tmp/trg/overlay/`, chưa vào repo, chưa áp 5433. Flyway để `outOfOrder = false`, nên DB nào đã áp V66 hoặc V67 sẽ từ chối một V65 đến sau. **Khi đưa trigger vào repo phải đổi số thành V68** (Hiến chấp nhận 27/11/2026). Lần build BE kế tiếp trỏ DB 5433 sẽ ghi V66 và V67 lên DB dùng chung.
- **Số đếm:** 86 → 87 khóa [CFG] (`ConfigKey`, `ConfigKeyTest`, `SystemConfigIT`, seed FE `identity-admin.seed.ts`); khóa `ai.*` 23 → 24.
- **Test:** `PersonalIntentRouterTest` (câu mơ hồ, `có hẹn`), `ChatIntentLlmTest` (mới: từng nhãn, lỗi, `null`, công tắc tắt, câu rõ ràng, câu không từ khóa, endpoint công khai), `ChatPromptFreezeTest` (ghim lời nhắc phân loại), `GeminiClientTest` (lược đồ và parse nhãn), các test đếm khóa và `SchemaMigrationIT` (thêm 66). Số chạy thật và số đo Gemini ghi ở mục "Kết quả" khi xong.

## Kết quả (27/11/2026)

- **Đo Gemini thật** (`gemini-3.1-flash-lite`, đúng lời nhắc đã ghim `sha256 01fdac38…` và lược đồ của `GeminiClient.classify`, 16 câu: 4 lịch hẹn, 3 lịch tiêm, 9 câu chung gồm 3 ví dụ `NONE` của BR-AI-26 và 1 câu chèn chỉ thị): **15/15 câu có phản hồi đúng nhãn**, câu còn lại HTTP 503 (Google quá tải; trong BE sẽ là `ERROR` → đi RAG). Độ trễ trung vị 1,1 s, nhưng hôm đo Gemini quá tải: có lượt 19–27 s.
- **Lỗi bắt được nhờ đo thật:** bản đầu đặt `maxOutputTokens = 20` → 16/16 câu "Unterminated string" (JSON bị cắt), tức mọi câu mơ hồ sẽ lặng lẽ rơi về `NONE`. Đã nâng lên 64, `GeminiClientTest` ghim giá trị mới.
- **Độ trễ khi Gemini chậm:** bước phân loại dùng chung [CFG] `ai.llm.timeout_seconds` (15 s) với bước sinh câu trả lời, nên câu mơ hồ có thể chờ thêm tới 15 s trước khi đi RAG. Không tách timeout riêng ở ADR này (request factory đang dùng chung, đặt timeout khác cho từng lệnh gọi sẽ chạy đua giữa các luồng); gặp ngày Gemini quá tải thì tắt công tắc.
- **Test:** unit toàn BE 7253 ca 0 đỏ (trước khi nâng token; sau đó chạy lại `GeminiClientTest` 24/24 và gói `ai.chat`); FE `identity-admin.mock.test.ts` 164/164. IT (Testcontainers): `AiSchemaIT` 61, `ChatIT` 30, `MeChatIT` 20, `OpenDomainIT` 5, `PetContextIT` 8, `SystemConfigIT` 3, `SchemaMigrationIT` 31 = 158 ca, 0 đỏ.

## Không làm

Gemini phân loại mọi câu (phương án B); nút "Xem lịch hẹn của tôi" trong giao diện (phương án C); đổi thứ tự các chốt an toàn; thêm giá trị `ai_chat_logs.intent`; dùng câu hỏi trước làm ngữ cảnh phân loại.
