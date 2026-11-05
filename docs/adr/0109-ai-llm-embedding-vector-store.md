# ADR-0109: Phân hệ AI — chọn LLM, embedding, kho vector, chi phí, cách xử lý ngoài phạm vi và định tuyến ý định cá nhân (T53)

- **Trạng thái:** Accepted (T53, Hiến duyệt ở T54 ngày 5/11/2026; BE-1, BE-2 đọc khi bắt đầu task tương ứng, muốn đổi thì supersede bằng ADR mới). **D1 (nhà cung cấp LLM) Hiến đã chốt tạm 5/11/2026: Gemini gói free, không dùng gói trả phí khi demo, xem lại trước khi chạy thật**; D3 (pgvector) ghi nhận theo lựa chọn của nhóm trưởng; các mục còn lại là đề xuất kỹ thuật. Kết quả đo của T56 sẽ bổ sung vào ADR này hoặc supersede nó.
- **Ngày:** 2026-11-05
- **Liên quan:** `docs/07-ai-subsystem.md` (đặc tả đầy đủ: UC90–UC94, BR-AI-01…28, FSM #13, bảng, API); `docs/api/ai-v1.md`; `docs/time.md` T53, T56, T77, T78, T82; `04-domain-model.md` nguyên tắc 9; ADR-0005 (path công khai bỏ qua token), 0006 (phạm vi CUSTOMER), 0007 (tác vụ định kỳ), 0100 (giới hạn tần suất theo IP bằng Redis), 0108 (hồ sơ sức khỏe, cách chọn mũi tiêm chưa bị thay thế).
- **Triển khai:** chưa. Spike T56 (9–13/11), chatbot T77, gợi ý T78. Hạ tầng (ảnh pgvector, Testcontainers, migration V34) làm ở T55, xem "Hệ quả".

## Bối cảnh

Đề cương giao phân hệ AI gồm chatbot tư vấn sức khỏe – dinh dưỡng (RAG + LLM) và gợi ý cá nhân hóa. `time.md` T53 yêu cầu ADR chốt LLM, embedding, vector store, chi phí và cách xử lý câu hỏi ngoài phạm vi. Ràng buộc thực tế đã kiểm:

- Nhóm có một khóa Google AI Studio để thử (khóa tạm, thu hồi sau spike). Khóa gọi được `gemini-2.5-flash` và `gemini-embedding-001`.
- Trang giá Gemini API (trích 5/11/2026): **gói miễn phí dùng nội dung để cải thiện sản phẩm Google**; gói trả phí thì không.
- Gói miễn phí có hạn mức tốc độ: một lượt thử khoảng 30 lệnh API liên tiếp đã gặp 429 ở 2 trong 9 lần gọi LLM; hạn mức cụ thể không có trên trang tài liệu (xem trong AI Studio).
- Môi trường chạy: `docker-compose.yml` dùng `postgres:17` thường; IT dùng Testcontainers `postgres:17` (`TestcontainersConfiguration`); dev còn dùng Postgres cài trên Windows và Postgres tạm. **Chưa có pgvector** ở các nơi này (xem D3).
- Catalog chưa có thuộc tính loài / tuổi / cân nặng cho sản phẩm và dịch vụ thẩm mỹ (ảnh hưởng gợi ý, `07` §7.2).
- Endpoint công khai bỏ qua token (ADR-0005) nên không biết khách là ai; muốn trả lời theo từng khách phải có endpoint đăng nhập riêng.

## Quyết định (đề xuất)

### D1. LLM — **chốt tạm: Gemini 2.5 Flash, gói miễn phí (Hiến, 5/11/2026)**

| Phương án | Giá / 1 triệu token (vào / ra) | Chi phí 1 000 câu* | Ghi chú |
|---|---|---|---|
| **Gemini 2.5 Flash**, tắt suy nghĩ | $0,30 / $2,50 (trang giá Gemini, 5/11/2026) | ≈ $0,90 | Đã có khóa; đo 0,66–0,87 giây; JSON theo lược đồ chạy được. Gói free: nội dung bị dùng để cải thiện sản phẩm, **đã gặp 429** |
| Gemini 2.5 Flash, suy nghĩ mặc định | như trên | ≈ $1,68 | Đo 1,9–2,4 giây và ≈ 310 token suy nghĩ / câu (tính giá đầu ra) |
| Claude Haiku 4.5 (`claude-haiku-4-5`) | $1,00 / $5,00 | ≈ $2,50 | Giá theo trang giá chính thức `platform.claude.com/docs/en/about-claude/pricing` (kiểm 5/11/2026, giá cơ bản không tính cache); **chưa đo** (chưa có khóa Claude) |
| Claude Sonnet 5.5 (`claude-sonnet-5-5`) | $2,00 / $10,00 | ≈ $5,00 | như trên |
| Claude Opus 5.5 (`claude-opus-5-5`) | $4,00 / $20,00 | ≈ $10,00 | như trên |
| Tự chạy mô hình mở (Gemma, Ollama…) | không tính theo token | — | Chưa thử; cần máy có GPU cho độ trễ chấp nhận được, không hợp máy demo hiện tại |

\* Giả định (ước tính, không phải số đo): 1 750 token vào, 150 token ra, không tính suy nghĩ ngoại trừ dòng ghi rõ. Phép tính: `vào × giá_vào + ra × giá_ra`.

**Đề xuất:** dùng **Gemini 2.5 Flash tắt suy nghĩ** cho spike T56 và demo vì đã có khóa chạy được, rẻ nhất và đã đo; đặt sau interface `LlmClient` để đổi sang Claude (hoặc khác) bằng một lớp + biến môi trường. **Quyết định tạm (Hiến, 5/11/2026):** chưa dùng gói trả phí; demo với người thật chạy trên gói free. Hiến chấp nhận rủi ro nội dung câu hỏi (đã che) có thể bị dùng để cải thiện sản phẩm Google và rủi ro 429 giữa buổi demo, với **điều kiện** (BR-AI-25): giao diện chatbot báo rõ câu hỏi được gửi tới Google; giữ che dữ liệu (BR-AI-12); không dữ liệu cá nhân qua LLM; tri thức nạp lên chỉ là tài liệu công khai đã duyệt; 429 trả `UNAVAILABLE`; **xem lại trước khi chạy thật**. Claude không có trong danh sách model embedding đã kiểm, nên chọn Claude cho LLM vẫn cần một nhà cung cấp embedding riêng (chưa kiểm giá).

### D2. Embedding

| Phương án | Giá / 1 triệu token (trang giá Gemini, 5/11/2026) | Ghi chú |
|---|---|---|
| `gemini-embedding-001`, 768 chiều (đề xuất) | $0,15 (có gói miễn phí) | Đã đo 1 lần: 663 ms; tham số `outputDimensionality` |
| `gemini-embedding-2` | $0,20 đầu vào chữ (có gói miễn phí), bản ổn định | Chưa đo chất lượng, số chiều mặc định chưa kiểm |

Dùng loại tác vụ tài liệu khi nạp và loại truy vấn khi hỏi. Nạp 300 đoạn × 250 token ≈ 75 000 token ≈ $0,011. **Chất lượng truy hồi tiếng Việt chưa đo trên kho thật — T56 đo trên bộ 43 câu (`07` §14.1).** Đổi mô hình khác số chiều ⇒ migration đổi kiểu cột `vector(n)` + nạp lại.

### D3. Kho vector — **pgvector** (theo lựa chọn của nhóm trưởng, ghi nhận 5/11/2026)

Bản nháp đầu đề xuất cột `REAL[]` + cosine trong bộ nhớ ứng dụng vì ảnh Postgres hiện tại không có pgvector. Quyết định được đổi sang pgvector.

| Phương án | Ưu | Nhược |
|---|---|---|
| **pgvector**: cột `vector(768)`, toán tử `<=>` (chọn) | Lọc `status = 'PUBLISHED'` và `doc_version = indexed_version` **ngay trong SQL**; lưu trữ / xuất bản có hiệu lực ngay khi commit; không phải nạp lại chỉ mục sau ST22; đúng khi chạy nhiều bản BE | Mọi môi trường phải dùng ảnh Postgres có pgvector; `vector(768)` cố định số chiều |
| `REAL[]` + cosine trong bộ nhớ (bản nháp đầu) | Không đổi hạ tầng | Mỗi bản BE giữ một bản sao chỉ mục và phải nạp lại sau ST22; lệch giữa các bản BE |
| Dịch vụ vector riêng (Qdrant…) | Mở rộng | Thêm một dịch vụ phải vận hành, quá mức cho vài trăm đoạn |

Quy mô vài trăm đoạn: quét tuần tự có lọc là đủ, **chưa** tạo chỉ mục HNSW (thêm khi vượt khoảng 10 000 đoạn).

**Việc phải làm (chốt ở T55, chưa làm trong lượt này):**
1. `docker-compose.yml`: đổi ảnh `postgres:17` thành `pgvector/pgvector:pg17`. Cùng bản chính 17 nên volume cũ dùng lại được — **kiểm khi làm**.
2. `TestcontainersConfiguration`: ảnh không chính thức thường cần `DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres")` — **kiểm khi làm**. Đổi này chạm IT của cả nhóm nên báo BE-1, BE-2 trước.
3. Migration Flyway **V34** (V33 đã dùng ở `origin/dev`, đã kiểm 5/11): `CREATE EXTENSION IF NOT EXISTS vector` (cần quyền owner / superuser như `btree_gist`) + các bảng `ai_*`.
4. **Lịch:** T56 (spike, 9–13/11) bắt đầu **trước** khi T55 (hạ tầng, 10–11/11) xong. Spike chạy trên **một container cục bộ `pgvector/pgvector:pg17`** của người làm spike (cổng riêng), **không phụ thuộc** `docker-compose.yml` chung, để việc đổi ảnh dùng chung không làm chậm spike.
5. **Rủi ro bắt buộc ghi:** Postgres cài sẵn trên Windows và Postgres tạm của dev **không có pgvector**; mọi người phải chạy Postgres bằng Docker với ảnh trên, nếu không migration lỗi ngay khi khởi động.

### D4. Cách gọi nhà cung cấp

Interface `LlmClient`, `EmbeddingClient` trong module `ai`; cài đặt `GeminiClient` bằng `RestClient` của Spring, không thêm thư viện AI. Khóa và tên model qua biến môi trường (`AI_PROVIDER`, `AI_API_KEY`, `AI_LLM_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIM`). Test dùng lớp giả; CI không gọi mạng.

### D5. Câu hỏi ngoài phạm vi, cấp cứu, y khoa — nhiều lớp, mỗi lớp đã đo

1. **Từ khóa cấp cứu** (tệp tài nguyên BE): trả cảnh báo cố định + chi nhánh nhận cấp cứu (BR-CN-05); **không** gọi embedding hay LLM.
2. **Hồ sơ bệnh án / đơn thuốc của thú cụ thể**: từ chối mẫu, chỉ đường tới Hồ sơ sức khỏe; không gọi LLM.
3. **Ý định cá nhân** (D7): câu mẫu hoặc `NEED_LOGIN`; không gọi LLM.
4. **Ngưỡng truy hồi** (khởi điểm cosine ≥ 0,60, K = 5): **chỉ lọc câu lạc đề hẳn**. Đo: đúng phạm vi 0,70–0,79; lạc đề 0,50–0,55; **gần chủ đề nhưng ngoài phạm vi 0,58–0,67, 5/6 vượt 0,60**. Không nâng ngưỡng lên ≈ 0,68 vì sẽ cắt cả câu đúng phạm vi diễn đạt khác.
5. **LLM trả JSON `{inScope, answer}`** (lược đồ ép ở phía nhà cung cấp) — **lớp chặn thật sự câu gần chủ đề**: đo 9/9 câu không nên trả lời cho `inScope = false` (mẫu nhỏ, 1 lần mỗi câu).
6. **Hậu kiểm liều thuốc theo ngữ cảnh**: đơn vị mạnh (`mg`, `mcg`, `µg`, `IU`, `viên`, `ống`) chặn luôn; `ml` và `giọt` chỉ chặn khi cùng câu có từ thuốc (để không chặn nhầm lời khuyên nước uống hay vệ sinh tai). Đo: bản cũ (số + `ml`) chặn nhầm "cần khoảng 500 ml nước mỗi ngày", bản mới thì không.

Ngưỡng và danh sách từ khóa là giá trị khởi điểm, hiệu chỉnh ở T56 bằng nhật ký (`ai_chat_logs`). Kết quả luôn trả HTTP 200 kèm `result` (`ANSWERED`, `OUT_OF_SCOPE`, `MEDICAL_REFUSAL`, `EMERGENCY`, `UNAVAILABLE`, `PERSONAL`, `NEED_PET_CHOICE`, `NEED_LOGIN`) để giao diện rẽ nhánh và AI hỏng không chặn trang (T82). `UNAVAILABLE` ghi `error_kind` và có bộ đếm riêng để thấy tỷ lệ lỗi dù HTTP vẫn 200.

### D6. Dữ liệu cá nhân

Che email, số điện thoại, số giấy tờ trong câu hỏi **và** các câu hỏi trước **trước khi** nhúng và gọi LLM; ở `/me` che cả tên các thú của khách (`[thú]`), chạy **sau** định tuyến ý định vì định tuyến cần tên thú (ở endpoint công khai không biết khách nên không che được, giao diện nhắc khách đừng nhập tên); không gửi tên, SĐT, email, dữ liệu thú cụ thể tới nhà cung cấp; nhật ký lưu bản đã che và bỏ nội dung với kết quả cá nhân. Server **không nhận câu trả lời của trợ lý từ client** (chỉ tối đa 3 câu hỏi trước của chính người dùng) để khách không tự đưa chỉ thị giả dạng lời trợ lý. Lý do: gói free dùng nội dung để cải thiện sản phẩm; chống chèn chỉ thị; đúng nguyên tắc 9.

### D7. Cá nhân hóa trong chat — định tuyến ý định bằng luật

Hai endpoint: `POST /public/ai/chat` (khách vãng lai, bỏ qua token theo ADR-0005, ý định cá nhân → `NEED_LOGIN`) và `POST /me/ai/chat` (khách đăng nhập). Ở `/me`, trước truy hồi, BE nhận ý định cá nhân khi câu có **từ khóa ý định** (lịch hẹn, lịch tiêm…) **và** **dấu hiệu sở hữu**: *mạnh* (của tôi, nhà tôi, bé nhà, hoặc tên một thú của khách; tên trùng từ thông dụng như Vàng, Mít chỉ tính khi đi sau "bé / con / chú / em") là đủ; *yếu* (sắp tới, tiếp theo, đến hạn, quá hạn…) chỉ đủ với lịch hẹn, **không** đủ với tiêm chủng. Khớp theo cụm từ có ranh giới từ ("giá" không trùng "già", "liệt kê" không là cấp cứu). Câu chung ("Chó con tiêm phòng dại lúc mấy tuần?") không có dấu hiệu sở hữu nên đi RAG. Hai ý định ở GĐ2: **lịch hẹn sắp tới** và **lịch tiêm**. Khách lấy từ token, không lấy mã khách hay `petId` từ câu hỏi hoặc LLM; kết quả là câu mẫu, không qua LLM. Lịch hẹn cần interface mới `AppointmentQueryApi.findUpcomingBookedOfCustomer` (BE-1) vì `findBookedByCustomer` hiện không có `serviceId`, không sắp xếp và gồm cả lịch `BOOKED` đã quá giờ chưa chuyển `NO_SHOW`. Lưu trú và tái khám để ở mục mở rộng (`07` §17.3, Q7).

## Đo thực tế (khóa thử, 5/11/2026)

Mẫu **nhỏ**, chỉ để chọn hướng, **không phải benchmark**:

| Cấu hình `gemini-2.5-flash` (lời nhắc ngắn, 109 token vào) | Số lần | Độ trễ | Token suy nghĩ |
|---|---|---|---|
| Mặc định | 2 | 2 375 ms, 1 938 ms | 322, 305 |
| `thinkingBudget = 0` | 2 | 663 ms, 804 ms | 0 |
| `thinkingBudget = 0` + `responseSchema {inScope, answer}` | 1 | 867 ms, JSON hợp lệ | 0 |
| `gemini-embedding-001`, 768 chiều | 1 | 663 ms | — |

**Ngưỡng truy hồi** (`gemini-embedding-001` 768 chiều, 7 đoạn mẫu công khai, cosine cao nhất mỗi câu):

| Nhóm câu hỏi | Số câu | Cosine |
|---|---|---|
| Đúng phạm vi, có đáp án | 6 | 0,703 – 0,790 |
| Lạc đề hẳn | 6 | 0,502 – 0,551 |
| Gần chủ đề nhưng ngoài phạm vi (giá vaccine, mua thuốc, giờ mở cửa, thuốc tiêu chảy, cá cảnh, hamster) | 6 | 0,581 – 0,665 (5/6 ≥ 0,60) |

**Toàn tuyến lớp LLM** (tắt suy nghĩ, `temperature 0,2`, 1 lần mỗi câu): 2/2 câu có đáp án `inScope = true`; **9/9** câu không nên trả lời (gần chủ đề, ngoài phạm vi gần chủ đề, y khoa, "kê đơn kháng sinh") `inScope = false`; câu chèn chỉ thị ghép với câu hợp lệ chỉ được trả lời phần chuyên môn, không lộ lời nhắc; câu chèn chỉ thị thuần dừng ở lớp ngưỡng (0,562).

**Hạn mức:** 2 trong 9 lần gọi LLM trả 429 `You exceeded your current quota` sau khoảng 30 lệnh API liên tiếp trong vài chục giây; thử lại sau 45 giây thì qua.

**Kiểm cơ chế pgvector** (container `pgvector/pgvector:pg17` riêng, PostgreSQL 17.11, extension `vector` 0.8.7, 4 đoạn mẫu chưa duyệt, đã xóa container): tạo được bảng theo `07` §8; `CHECK` hai lớp chặn tài liệu `PUBLISHED` thiếu rà soát; câu SQL truy hồi §5.1 trả đúng đoạn ở hạng 1 (0,794 / 0,755 / 0,732; câu lạc đề cao nhất 0,518); lưu trữ có hiệu lực ngay; hoán đổi `indexed_version` chỉ an toàn khi ghi đoạn mới trong cùng transaction. **Chưa kiểm:** Testcontainers, dùng lại volume cũ, kho thật.

Ghi chú: `gemini-3.8-flash` từng treo hơn 2 phút ở một lần thử, chưa điều tra, không dùng cho spike. Kho thật vài trăm đoạn, độ trễ end-to-end với lời nhắc ≈ 1 750 token và tỷ lệ chặn sót của hậu kiểm **chưa đo**.

## Hệ quả và rủi ro

- **Chi phí:** với khối lượng đồ án (vài trăm đến vài nghìn câu) là dưới vài USD; rủi ro chính của gói free là **quyền riêng tư và hạn mức (429)**, không phải tiền. Hiến chấp nhận tạm khi demo (D1); phải xem lại trước khi chạy thật.
- **pgvector là thay đổi hạ tầng dùng chung:** đổi ảnh compose và Testcontainers chạm môi trường của cả nhóm; Postgres Windows / Postgres tạm của dev không có pgvector (D3 mục 5). Đây là rủi ro lớn nhất của quyết định D3.
- **Khóa:** khóa thử chỉ dùng cho spike rồi thu hồi; khóa chính thức chỉ ở biến môi trường (BR-AI-25).
- **Chất lượng tiếng Việt chưa biết** cho cả embedding lẫn LLM trên kho thật: nếu T56 thấy truy hồi kém, đổi D2 trước khi đổi D1 (đổi embedding khác số chiều cần migration và nạp lại).
- **Bộ định tuyến ý định bằng luật có độ phủ hạn chế:** câu hỏi cá nhân diễn đạt lạ sẽ rơi vào RAG (thường ra `OUT_OF_SCOPE` kèm nút đặt lịch). Chấp nhận ở GĐ2; nhật ký giúp bổ sung luật.
- **Giá có thể đổi:** trang giá nêu một số mức chuyển đổi từ 1/1/2027; kiểm lại trước khi dùng thật.

## Điều kiện để supersede

Kết quả T56 cho thấy: truy hồi top-5 không đưa đúng tài liệu cho ≥ 10/12 câu có đáp án; hoặc lớp `inScope` để lọt câu ngoài phạm vi ở nhóm an toàn (yêu cầu 100%); hoặc bộ định tuyến nhận nhầm câu chung thành tra cá nhân; hoặc p95 end-to-end > 5 giây; hoặc nhóm chọn nhà cung cấp khác ở D1; hoặc không thể đổi ảnh Postgres ở mọi môi trường (quay lại `REAL[]`).
