# ADR-0135: Ngữ cảnh thú cưng trong chat cá nhân và chuyển sang gói Gemini trả phí (AI v2, phần 2)

- **Trạng thái:** Proposed (Hiến duyệt **hướng** 13/11/2026; nội dung chờ Hiến duyệt; T91). **Công tắc `ai.chat.pet_context_enabled` (`BOOL`) mặc định `false` và chỉ được bật sau khi mục "Hiến xác minh" dưới đây hoàn tất.**
- **Ngày:** 2026-11-13
- **Liên quan:** ADR-0122 (gói free, che tên thú), 0129 (I-1…I-10 đã cài), 0131 (đường ống), 0132 (chat cá nhân, che tên thú sau định tuyến), 0134, 0136; `07` §4.5 BR-AI-25 (v2), §4.7 BR-AI-32, §5.2 bước 6c, §5.4 khối `THÔNG TIN THÚ`, §12; `docs/06` §6b I-1.
- **Đánh số:** [CFG] `ai.chat.pet_context_enabled` seed ở V52 (chung với ADR-0134, 0136). Không có endpoint mới.

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
- Nhật ký: chỉ `used_pet_context = true` (V52); **không** ghi nội dung khối. CHECK `ck_ai_chat_logs_no_content_for_personal` không đổi.

### D3. Thay đổi interface của module khác (báo Dũng)
- `PetQueryApi.PetProfile` thêm `Sex sex`, `Boolean isNeutered` (cột `pets.sex`, `pets.is_neutered` đã có từ V1). Record đổi chữ ký → mọi `new PetProfile(...)` trong test của `customer`, `ai`, `boarding` phải sửa; ghi ở `06` §6b. Phương án không đổi record (method mới `findPetBody(petId)`) bị loại vì thêm một lượt đọc cho mỗi câu.

### D4. Công tắc và hành vi khi tắt
- `ai.chat.pet_context_enabled = false` (mặc định, V52): bước 6c bị bỏ qua hoàn toàn; `/me/ai/chat` hành xử đúng như T77c; `used_pet_context = false`. Đổi [CFG] có hiệu lực ở lượt sau (đọc mỗi lượt), không cần khởi động lại.

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
