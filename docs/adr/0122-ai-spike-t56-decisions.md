# ADR-0122: Chốt kết quả spike AI (T56) — mô hình LLM mặc định, che tên thú, ngưỡng τ, lỗi nhà cung cấp, mốc độ trễ

- **Trạng thái:** Accepted (Hiến duyệt ngày 7/11/2026). **Supersedes ADR-0109 chỉ ở phần chọn mô hình mặc định của D1**; các phần khác của ADR-0109 giữ nguyên (D1 vẫn là Gemini gói free sau `LlmClient`, D2–D5, D7, đo lường, điều kiện supersede). Quyết định 2 làm rõ cách che tên thú của D6 (BR-AI-12b) cho tên trùng từ thông dụng, không đổi nguyên tắc che dữ liệu cá nhân.
- **Ngày:** 2026-11-07
- **Liên quan:** `docs/07-ai-subsystem.md` §13 "Kết quả spike T56" (số đo, nguồn của mọi con số dưới đây), §14.1, §15 (D1), §4.1 (BR-AI-10), §4.2 (BR-AI-12, 26), §5.2 bước 6; ADR-0109; `docs/time.md` T56, T77; `BE/scripts/spike/` (mã và dữ liệu thô).
- **Triển khai:** chỉ tài liệu. Phần mã làm ở T77 (xem "Hệ quả").

## Bối cảnh

Spike T56 (5/11/2026) chạy bộ 43 câu × 3 lượt (129 lượt) trên KB 32 tài liệu / 52 đoạn do chính người đo soạn, chưa duyệt, trong container `pgvector/pgvector:pg17` riêng. Số đo đầy đủ ở `07` §13; ở đây chỉ nêu những số mang quyết định:

- **123 / 129 lượt đạt** (126 / 129 đúng `result`), nhóm an toàn 42 / 42, bộ định tuyến 0 nhận nhầm / 99 lượt phủ định, truy hồi top-5 đúng 12 / 12.
- Toàn bộ 129 lượt chạy bằng `gemini-3.1-flash-lite`, **không phải** `gemini-2.5-flash` mà D1 của ADR-0109 chốt, vì gói free của `gemini-2.5-flash` chỉ cho **20 lệnh sinh mỗi ngày** (429 `GenerateRequestsPerDayPerProjectPerModel-FreeTier`) và hết hạn mức sau Q11 của lần chạy đầu.
- **p95 end-to-end 6,4 giây** (90 lượt qua LLM), mốc là 5 giây. Đây chính là một điều kiện supersede ghi trong ADR-0109 ("p95 end-to-end > 5 giây"): điều kiện **đã xảy ra** ở spike nhưng quyết định 5 dưới đây cố ý **không** hành động theo nó trước khi đo lại trên BE thật.
- Q39 ("Giá vàng hôm nay là bao nhiêu?" của khách có thú tên Vàng) bị che thành "Giá [thú] hôm nay…" và ra `ANSWERED` thay vì `OUT_OF_SCOPE`, 3 / 3 lượt.
- τ = 0,60: câu lạc đề có cosine cao nhất 0,5895–0,6055 (1 / 3 vượt τ), cao hơn 0,50–0,55 đo ở 7 đoạn mẫu.
- 6 / 90 lệnh sinh hỏng ở lần thử đầu (5 quá 15 giây, 1 lỗi 503) = 6,7%.

## Quyết định

### 1. Mô hình LLM mặc định: `gemini-3.1-flash-lite` (thay phần mô hình của D1 trong ADR-0109)

- Giữ interface `LlmClient` và biến môi trường `AI_LLM_MODEL`; **mặc định đổi thành `gemini-3.1-flash-lite`**. `gemini-2.5-flash` thành phương án thay. Embedding vẫn `gemini-embedding-001`, 768 chiều (D2 không đổi).
- Giữ nguyên quyết định của Hiến ngày 5/11/2026: Gemini **gói free**, không dùng gói trả phí khi demo, các điều kiện của BR-AI-25 còn nguyên, xem lại trước khi chạy thật.
- Lý do: trong cùng một ngày mô hình mới chạy khoảng 98 lệnh sinh không chạm trần, còn D1 cũ trần 20 lệnh / ngày, tức demo với người thật chỉ trả lời được khoảng 20 câu qua LLM mỗi ngày (câu cấp cứu, cá nhân, dưới τ không tốn lệnh). Và toàn bộ số đo an toàn hiện có (42 / 42) là của mô hình mới, nên mặc định trùng với thứ đã đo.
- Giữ `LlmClient` + `AI_LLM_MODEL` còn vì mô hình có vòng đời ngắn: `gemini-2.5-flash-lite` trả 404 "no longer available to new users" ngày 5/11.

### 2. Che tên thú theo quy tắc của BR-AI-26 (BR-AI-12 mục b, `07` §5.2 bước 6)

Ở `POST /me/ai/chat`, tên thú trùng từ thông dụng (Vàng, Mít, Đen, Nâu… trong tệp tài nguyên của BR-AI-26) chỉ bị thay bằng `[thú]` khi đi sau "bé", "con", "chú", "em"; đứng một mình ("giá vàng hôm nay") thì **không che**. Tên thú thường (không trùng từ thông dụng) che mọi lần xuất hiện như cũ. Lý do: che mù làm đổi nghĩa câu hỏi và làm phân loại sai (Q39). Tài liệu tri thức thật nên tránh câu phủ định kiểu "không có thông tin về giá": câu như thế trong KB spike đã kéo câu hỏi giá thành `inScope = true`.

### 3. τ giữ 0,60

Không đổi từ số đo này: 12 câu đúng phạm vi diễn đạt sát tài liệu (cùng một người soạn KB và câu hỏi) nên biên 0,72 không đáng tin, và lớp `inScope` mới là lớp chặn thật. Hiệu chỉnh lại khi có KB đã duyệt và câu hỏi thật trong `ai_chat_logs` (T77). Câu lạc đề vượt τ chỉ tốn một lệnh LLM, không trả lời sai.

### 4. BR-AI-10 giữ nguyên, không thử lại trong request

Trong 6 lệnh hỏng ở spike, 5 lần quá 15 giây (không còn ngân sách thời gian cho lần thử lại), 1 lần 503; thử lại cứu tối đa khoảng 1% câu (1 / 90) nhưng thêm độ phức tạp và tốn hạn mức gói free. **Chấp nhận khoảng 7% `UNAVAILABLE`** ở gói free. Xem lại nếu `ai_chat_logs` / bộ đếm `ai_chat_result_total{result,error_kind}` cho thấy trên 10%.

### 5. Mốc p95 5 giây ở `07` §14 không đổi

Ghi "chưa đạt ở spike (6,4 giây trên `gemini-3.1-flash-lite`)" và **đo lại ở T77 trên BE thật** (JDBC, `RestClient`; SQL 5 ms ở spike đo qua ống `psql`, không đại diện). Nếu vẫn trên 5 giây thì một ADR mới nâng mốc kèm số đo; **không nâng trước**.

### Ghi nhận, không quyết

Hậu kiểm liều thuốc (BR-AI-06) chỉ là heuristic: sót 4 / 14 ca tổng hợp, chặn nhầm 2 (`07` §13 phát hiện 4). Hiến không có quyết định riêng về mục này; luật không đổi, hậu kiểm là lưới phụ, lớp chặn chính là `inScope` cộng quy tắc 2 của lời nhắc.

## Hệ quả

Việc phải làm ở T77 (phiên này **không** sửa mã, migration hay [CFG]):

- **Mô hình mặc định không cần migration.** Tên mô hình là biến môi trường `AI_LLM_MODEL` (ADR-0109 D4), không phải khóa [CFG]: V35 chỉ seed `ai.llm.timeout_seconds` cho phần LLM, `ConfigKey` không có khóa mô hình, và `GeminiClient` chưa tồn tại trong `BE/src`. T77 đặt mặc định `gemini-3.1-flash-lite` ở thuộc tính ứng dụng khi viết `GeminiClient`. Cột `ai_chat_logs.llm_model` (`VARCHAR(60)`) đủ chỗ cho tên mới và cho phép so sánh hai mô hình theo nhật ký.
- **Hàm che tên thú** (`07` §5.2 bước 6) dùng chung nguồn nhận diện tên thú với định tuyến ý định (BR-AI-26) để hai nơi không lệch nhau. Thêm ca kiểm thử bảng cho khách có thú tên Vàng: "Giá vàng hôm nay" không che (Q39); "Bé Vàng ăn gì thì tốt?" che thành "Bé [thú] ăn gì thì tốt?" (câu mới: Q40 "Con Vàng tiêm khi nào?" rẽ sang `PERSONAL` ở bước 5, trước bước che, nên không tới LLM).
- **Ghi chú BR-AI-10** ở `07` đã thêm (luật không đổi); T77 ghi nhật ký `error_kind` và bộ đếm để đo tỷ lệ `UNAVAILABLE` so với ngưỡng 10%.
- **Đo p95 ở T77** theo quyết định 5; nếu phải nâng mốc thì ADR mới.
- **Mock FE** `FE/src/shared/api/mock/ai.mock.ts` còn chú thích hạn mức "20 lệnh sinh / ngày" của `gemini-2.5-flash` (A3); đó là số của mô hình cũ, chỉnh khi T57 / T77 chạm tới tệp đó.
- **Hợp đồng HTTP** `docs/api/ai-v1.md` mục 4 (sinh từ `docs/api/generator/m_ai.py`) viết "tên các thú của khách cũng bị thay bằng `[thú]`" mà chưa nêu ngoại lệ tên thông dụng; sửa trong `m_ai.py` rồi chạy `py docs/api/generator/generate.py ai` khi T77 chạm hợp đồng.

## Rủi ro chấp nhận

- **Trần thật của gói free cho `gemini-3.1-flash-lite` chưa biết.** "Khoảng 98 lệnh sinh trong một ngày" là cận dưới đã quan sát, không phải hạn mức; hạn mức nằm trong AI Studio của dự án và phải xem trước demo.
- **Giá gói trả phí của `gemini-3.1-flash-lite` chưa kiểm.** Bảng chi phí ở `07` §13 vẫn là của `gemini-2.5-flash`. Không ảnh hưởng quyết định vì demo dùng gói free, nhưng phải kiểm nếu sau này xét gói trả phí.
- **Vòng đời mô hình ngắn.** Mô hình mới có thể bị ngừng như `gemini-2.5-flash-lite`; chỉ đổi `AI_LLM_MODEL`, nhưng phải đo lại nhóm an toàn trên mô hình thay thế.
- **Quyền riêng tư của quyết định 2.** Tên thú trùng từ thông dụng đứng một mình ("Vàng tiêm khi nào?") **không được che** và đi tới nhà cung cấp gói free (nội dung có thể bị dùng để cải thiện sản phẩm). Hợp với BR-AI-12 mục d (tên thú gõ ở endpoint công khai cũng không che được), và tên thú một mình không định danh chủ; nhưng nó là sự đánh đổi có chủ ý, giao diện vẫn nhắc khách đừng nhập thông tin cá nhân.
- **Phương án thay `gemini-2.5-flash` chưa đo đủ.** Chưa chạy 43 câu trên nó (hết hạn mức ngày). Nhóm an toàn 42 / 42 là số của mô hình mặc định mới, nên trước khi chuyển sang `gemini-2.5-flash` phải chạy lại cả bộ.
- **Số đo trên KB tự soạn, chưa duyệt, mẫu nhỏ.** Truy hồi 12 / 12 là lạc quan. KB thật cần bác sĩ thú y rà soát (Q2, Hiến chỉ định, chưa có tên); không nạp KB spike vào DB dev / demo.
- **Khoảng 7% `UNAVAILABLE`** (quyết định 4).

## Điều kiện xem lại

- p95 end-to-end ở T77 trên BE thật vẫn trên 5 giây: ADR mới nâng mốc kèm số đo (quyết định 5).
- Tỷ lệ `UNAVAILABLE` trong `ai_chat_logs` trên 10%: xét lại BR-AI-10 (một lần thử lại có hạn) bằng ADR mới.
- Có KB đã duyệt và câu hỏi thật: hiệu chỉnh lại τ.
- `gemini-3.1-flash-lite` bị ngừng, hạn mức free thực tế thấp hơn nhu cầu demo, hoặc quyết định đổi sang gói trả phí / nhà cung cấp khác: xét lại D1.
- Đo được `gemini-2.5-flash` đủ 43 câu và muốn dùng làm mặc định: ADR mới.
