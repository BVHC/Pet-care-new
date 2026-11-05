# AI API v1 (tầng 3) — Pet Care v16

> **Module:** AI · **Owner:** FE-1 (dẫn) · cả nhóm. Chatbot hỏi đáp (RAG + LLM), trả lời theo từng khách về lịch hẹn và lịch tiêm (tất định), gợi ý cá nhân hóa, quản lý tri thức và hồ sơ gợi ý. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**
> **Nguồn chân lý:** 07-ai-subsystem.md (UC90–UC94, ST22–ST23, BR-AI-01…28, FSM #13, §8 bảng ai_documents / ai_chunks / ai_chat_logs / ai_item_profiles); adr/0109.
> **Contract máy đọc:** [`./openapi/ai-v1.yaml`](./openapi/ai-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- UC91 và UC92 không gọi LLM; chỉ UC90 gọi nhà cung cấp LLM / embedding (07 §1.2).
- ST22 (nạp lại chỉ mục) và ST23 (dọn nhật ký hỏi đáp) là tác vụ hệ thống, không có endpoint; chỉ có `reindex` để người quản lý yêu cầu nạp lại một tài liệu.
- Không có endpoint chấm điểm câu trả lời, thống kê hỏi đáp, đọc nhật ký (07 §17.3, mở rộng).
- Không lưu hội thoại phía máy chủ; client chỉ gửi các câu hỏi trước **của chính người dùng**, không gửi câu trả lời của trợ lý (BR-AI-08).
- Lịch hẹn sắp tới được trả qua `POST /me/ai/chat` (BR-AI-26, 27), không có endpoint riêng: giao diện đã có danh sách lịch hẹn ở appointment-v1.
- Hỏi về lưu trú, ngày tái khám qua chatbot là mở rộng (07 Q7).

---

## A. Danh sách endpoint (13)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /public/ai/chat` | Hỏi đáp chatbot (khách vãng lai) | UC90, UC91 | BR-AI-01…10, 12, 24, 28 |
| 2 | `POST /me/ai/chat` | Hỏi đáp chatbot (khách đăng nhập, có cá nhân hóa) | UC90, UC91 | BR-AI-01…10, 12, 24, 26…28 |
| 3 | `GET /me/ai/pets/{petId}/vaccination-summary` | Tra lịch tiêm của thú | UC91 | BR-AI-11, 12 |
| 4 | `GET /me/ai/pets/{petId}/recommendations` | Gợi ý cá nhân hóa cho thú | UC92 | BR-AI-18…23 |
| 5 | `GET /ai/documents` | Danh sách tài liệu tri thức | UC93 | BR-AI-17 |
| 6 | `POST /ai/documents` | Tạo tài liệu tri thức (nháp) | UC93 | BR-AI-14, 17 |
| 7 | `GET /ai/documents/{documentId}` | Chi tiết tài liệu tri thức | UC93 | BR-AI-17 |
| 8 | `PATCH /ai/documents/{documentId}` | Sửa tài liệu tri thức | UC93 | BR-AI-14, 16, 17 |
| 9 | `POST /ai/documents/{documentId}/publish` | Xuất bản tài liệu | UC93 | BR-AI-14, 16, 17 |
| 10 | `POST /ai/documents/{documentId}/archive` | Lưu trữ tài liệu | UC93 | BR-AI-16, 17 |
| 11 | `POST /ai/documents/{documentId}/reindex` | Nạp lại chỉ mục của tài liệu | UC93 | BR-AI-15, 16, 17 |
| 12 | `GET /ai/item-profiles` | Danh sách dịch vụ / sản phẩm kèm hồ sơ gợi ý | UC94 | BR-AI-18, 20 |
| 13 | `PUT /ai/item-profiles/{itemType}/{itemId}` | Lưu hồ sơ gợi ý | UC94 | BR-AI-18, 20, 17 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Hỏi đáp chatbot (khách vãng lai) | A01 | Public | — | — | Luôn 200 với mọi kết quả nghiệp vụ; giao diện rẽ theo `result`. Cấp cứu, từ chối bệnh án, `NEED_LOGIN` và dưới ngưỡng truy hồi không gọi LLM; lỗi nhà cung cấp hoặc hết hạn mức trả `UNAVAILABLE` (BR-AI-10). Token gửi kèm bị bỏ qua (ADR-0005) nên không bao giờ trả `PERSONAL` / `NEED_PET_CHOICE`. |
| Hỏi đáp chatbot (khách đăng nhập, có cá nhân hóa) | A02 | CUSTOMER | — | — | Cùng luồng với endpoint công khai, thêm bước định tuyến ý định cá nhân trước truy hồi (BR-AI-26): lịch hẹn sắp tới và lịch tiêm trả `PERSONAL` bằng câu mẫu (không qua LLM, không ghi nội dung vào nhật ký); nhiều thú mà câu không nêu tên thì `NEED_PET_CHOICE` kèm `pets`. Khách lấy từ token, không lấy `petId` từ câu hỏi (BR-AI-27). |
| Tra lịch tiêm của thú | A02 | CUSTOMER, chỉ thú hiện thuộc về mình | — | — | Tất định, không qua LLM, chỉ mũi tiêm ghi nhận tại hệ thống (04 nguyên tắc 9); mỗi loại vaccine lấy mũi mới nhất chưa bị thay thế. Không ghi nội dung vào nhật ký. Giao diện gọi sau `NEED_PET_CHOICE` hoặc khi đã biết thú. |
| Gợi ý cá nhân hóa cho thú | A02 | CUSTOMER, chỉ thú hiện thuộc về mình | — | — | Không bao giờ có thuốc, vaccine, sản phẩm kê đơn, dịch vụ khám / tiêm (BR-SP-01, BR-AI-18). Mỗi lần tính từ dữ liệu thú hiện tại (BR-AI-23). |
| Danh sách tài liệu tri thức | A04 | Chỉ SUPER_MANAGER | — | — | Mới sửa trước (`updatedAt` giảm dần, trùng thì `documentId` giảm dần). |
| Tạo tài liệu tri thức (nháp) | A04 | Chỉ SUPER_MANAGER | — → DRAFT (Tài liệu#1) | — | — |
| Chi tiết tài liệu tri thức | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Sửa tài liệu tri thức | A04 | Chỉ SUPER_MANAGER | PUBLISHED → PUBLISHED (Tài liệu#3) hoặc sửa nháp | — | Sửa tài liệu `PUBLISHED` tăng `version`; ST22 dựng đoạn mới rồi hoán đổi trong một transaction, đoạn cũ phục vụ tới lúc đó (BR-AI-16). |
| Xuất bản tài liệu | A04 | Chỉ SUPER_MANAGER | DRAFT / ARCHIVED → PUBLISHED (Tài liệu#2, #5) | — | `indexStatus` về `PENDING`; ST22 nạp chỉ mục sau commit. |
| Lưu trữ tài liệu | A04 | Chỉ SUPER_MANAGER | PUBLISHED / DRAFT → ARCHIVED (Tài liệu#4, #6) | — | Có hiệu lực ngay khi commit: truy hồi lọc `status` trong SQL (BR-AI-16). |
| Nạp lại chỉ mục của tài liệu | A04 | Chỉ SUPER_MANAGER | — | Có | Dùng để thử lại khi `indexStatus = FAILED`. Trả ngay với `indexStatus = PENDING`; ST22 làm nền. |
| Danh sách dịch vụ / sản phẩm kèm hồ sơ gợi ý | A04 | Chỉ SUPER_MANAGER | — | — | Chỉ dịch vụ thẩm mỹ và sản phẩm `GOODS` không kê đơn đang kinh doanh; loại chuồng dùng `kennel_types` nên không có ở đây (BR-AI-20). |
| Lưu hồ sơ gợi ý | A04 | Chỉ SUPER_MANAGER | — | Có | Ghi đè toàn bộ hồ sơ; gửi mọi trường rỗng để xóa thuộc tính (mục trở về điểm trung tính, BR-AI-20). |

---

## C. Chi tiết endpoint

### Chatbot

- **`POST /public/ai/chat`** — Hỏi đáp chatbot (khách vãng lai). Public. Request `ChatRequest`. Response `200` `ChatResponse`. Lỗi: `400` `VALIDATION_FAILED` / `BR-AI-08` câu hỏi rỗng hoặc quá 500 ký tự · quá 3 câu hỏi trước · một câu hỏi trước quá 500 ký tự · `429` `TOO_MANY_REQUESTS` một IP gọi quá nhiều lần (BR-AI-09, cơ chế ADR-0100): header `Retry-After` là số giây nguyên phải chờ; request bị chặn trước khi nhúng hay gọi LLM, không ghi nhật ký.
- **`POST /me/ai/chat`** — Hỏi đáp chatbot (khách đăng nhập, có cá nhân hóa). Request `ChatRequest`. Response `200` `ChatResponse`. Lỗi: `400` `VALIDATION_FAILED` / `BR-AI-08` câu hỏi rỗng hoặc quá 500 ký tự · quá 3 câu hỏi trước · một câu hỏi trước quá 500 ký tự · `401` · `403` · `429` `TOO_MANY_REQUESTS` một IP gọi quá nhiều lần (BR-AI-09, cơ chế ADR-0100): header `Retry-After` là số giây nguyên phải chờ; request bị chặn trước khi nhúng hay gọi LLM, không ghi nhật ký.
### Pet assistant

- **`GET /me/ai/pets/{petId}/vaccination-summary`** — Tra lịch tiêm của thú. Response `200` `VaccinationSummary`. Lỗi: `400` `BR-KH-05` thú đã mất · `MALFORMED_REQUEST` `petId` sai kiểu · `401` · `403` · `404` Không tìm thấy thú — cả khi thú thuộc người khác hoặc chủ cũ (ADR-0006).
- **`GET /me/ai/pets/{petId}/recommendations`** — Gợi ý cá nhân hóa cho thú. Query: `group?`, `limit?`. Response `200` `RecommendationResponse`. Lỗi: `400` `BR-KH-05` thú đã mất · `VALIDATION_FAILED` `limit` ngoài 1–12 · `401` · `403` · `404` Không tìm thấy thú (kể cả thú của người khác).
### Knowledge

- **`GET /ai/documents`** — Danh sách tài liệu tri thức. Query: `status?`, `topic?`, `species?`, `q?`, `page?`, `size?`. Response `200` trang `AiDocumentSummary`. Lỗi: `400` · `401` · `403`.
- **`POST /ai/documents`** — Tạo tài liệu tri thức (nháp). Request `CreateAiDocumentRequest`. Response `201` `AiDocument`. Lỗi: `400` `BR-AI-14` tiêu đề rỗng / quá 200 ký tự · nội dung quá 20 000 ký tự · `401` · `403`.
- **`GET /ai/documents/{documentId}`** — Chi tiết tài liệu tri thức. Response `200` `AiDocument`. Lỗi: `400` · `401` · `403` · `404`.
- **`PATCH /ai/documents/{documentId}`** — Sửa tài liệu tri thức. Request `UpdateAiDocumentRequest`. Response `200` `AiDocument`. Lỗi: `400` `BR-AI-14` nội dung quá dài · tài liệu `CATALOG_PROTOCOL` không sửa tay (BR-AI-13) · `401` · `403` · `404` · `409` Tài liệu `ARCHIVED` không sửa được.
- **`POST /ai/documents/{documentId}/publish`** — Xuất bản tài liệu. Response `200` `AiDocument`. Lỗi: `400` `BR-AI-14` thiếu `sourceNote` (lớp 1) hoặc `reviewerName` / `reviewedOn` (lớp 2) với tài liệu `MANUAL` · nội dung rỗng · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #13).
- **`POST /ai/documents/{documentId}/archive`** — Lưu trữ tài liệu. Response `200` `AiDocument`. Lỗi: `400` · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #13).
- **`POST /ai/documents/{documentId}/reindex`** — Nạp lại chỉ mục của tài liệu. Response `202` `AiDocument`. Lỗi: `400` · `401` · `403` · `404` · `409` Tài liệu không `PUBLISHED` nên không có gì để nạp (03 #13).
### Item profiles

- **`GET /ai/item-profiles`** — Danh sách dịch vụ / sản phẩm kèm hồ sơ gợi ý. Query: `itemType?`, `missingOnly?`, `q?`, `page?`, `size?`. Response `200` trang `ItemProfileRow`. Lỗi: `400` · `401` · `403`.
- **`PUT /ai/item-profiles/{itemType}/{itemId}`** — Lưu hồ sơ gợi ý. Request `ItemProfile`. Response `200` `ItemProfileRow`. Lỗi: `400` `BR-AI-18` mục là dịch vụ khám / tiêm, thuốc, vaccine hoặc sản phẩm kê đơn · `VALIDATION_FAILED` `min` lớn hơn `max` · `401` · `403` · `404` Không tìm thấy dịch vụ / sản phẩm.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`ChatResult`** — enum: `ANSWERED`, `OUT_OF_SCOPE`, `MEDICAL_REFUSAL`, `EMERGENCY`, `UNAVAILABLE`, `PERSONAL`, `NEED_PET_CHOICE`, `NEED_LOGIN`. Kết quả của một lượt hỏi đáp; giao diện rẽ theo giá trị này (07 §10). `PERSONAL`, `NEED_PET_CHOICE` chỉ có ở `/me/ai/chat`; `NEED_LOGIN` chỉ có ở `/public/ai/chat`
- **`VaccinationStatus`** — enum: `NOT_YET_ELIGIBLE`, `NOT_STARTED`, `UP_TO_DATE`, `DUE_SOON`, `OVERDUE`. BR-AI-11
- **`RecoGroup`** — enum: `GROOMING`, `BOARDING`, `PRODUCT`. BR-AI-18
- **`ItemType`** — enum: `SERVICE`, `PRODUCT`.
- **`DocumentSource`** — enum: `MANUAL`, `CATALOG_PROTOCOL`. BR-AI-13
- **`DocumentTopic`** — enum: `CARE`, `NUTRITION`, `BEHAVIOR`, `HYGIENE`, `VACCINATION`, `CLINIC_INFO`.
- **`DocumentSpecies`** — enum: `DOG`, `CAT`, `OTHER`, `ALL`.
- **`DocumentStatus`** — enum: `DRAFT`, `PUBLISHED`, `ARCHIVED`. 03 #13
- **`IndexStatus`** — enum: `PENDING`, `INDEXED`, `FAILED`.
- **`ChatRequest`** — {`question`: string, `previousQuestions?`: [string]}
- **`ChatSource`** — {`documentId`: int64, `title`: string}
- **`EmergencyBranch`** — {`branchId`: int64, `name`: string, `address`: string, `phone`: string}
- **`PetChoice`** — {`petId`: int64, `name`: string}
- **`ChatResponse`** — {`result`: ChatResult, `answer`: string, `sources`: [ChatSource], `disclaimer?`: string, `bookingHint`: boolean, `emergencyBranches`: [EmergencyBranch], `pets`: [PetChoice]}
- **`VaccinationItem`** — {`vaccineTypeId`: int64, `vaccineName`: string, `status`: VaccinationStatus, `lastDoseNumber?`: int32, `lastAdministeredOn?`: date, `nextDoseNumber?`: int32, `nextDueDate?`: date, `firstDoseFromAgeWeeks?`: int32}
- **`VaccinationSummary`** — {`petId`: int64, `petName`: string, `items`: [VaccinationItem], `message`: string, `disclaimer`: string}
- **`RecommendedItem`** — {`group`: RecoGroup, `itemType`: ItemType, `itemId`: int64, `name`: string, `price`: int64, `priceIsFrom`: boolean, `priceUnit?`: string, `imageUrl?`: string, `score`: number, `reasons`: [string]}
- **`RecommendationResponse`** — {`petId`: int64, `generatedAt`: date-time, `items`: [RecommendedItem]}
- **`AiDocumentSummary`** — {`documentId`: int64, `sourceType`: DocumentSource, `title`: string, `topic`: DocumentTopic, `species`: DocumentSpecies, `status`: DocumentStatus, `version`: int32, `indexStatus`: IndexStatus, `updatedAt`: date-time}
- **`AiDocument`** — {`documentId`: int64, `sourceType`: DocumentSource, `title`: string, `topic`: DocumentTopic, `species`: DocumentSpecies, `content`: string, `sourceNote?`: string, `reviewerName?`: string, `reviewedOn?`: date, `status`: DocumentStatus, `version`: int32, `indexedVersion`: int32, `indexStatus`: IndexStatus, `indexError?`: string, `chunkCount`: int32, `publishedAt?`: date-time, `updatedAt`: date-time}
- **`CreateAiDocumentRequest`** — {`title`: string, `topic`: DocumentTopic, `species`: DocumentSpecies, `content`: string, `sourceNote?`: string, `reviewerName?`: string, `reviewedOn?`: date}
- **`UpdateAiDocumentRequest`** — {`title?`: string, `topic?`: DocumentTopic, `species?`: DocumentSpecies, `content?`: string, `sourceNote?`: string, `reviewerName?`: string, `reviewedOn?`: date}
- **`ProfileSpecies`** — enum: `DOG`, `CAT`, `OTHER`.
- **`ItemProfile`** — {`species?`: ProfileSpecies, `minAgeWeeks?`: int32, `maxAgeWeeks?`: int32, `minWeightKg?`: number, `maxWeightKg?`: number, `breedKeywords`: [string], `repeatIntervalDays?`: int32}
- **`ItemProfileRow`** — {`itemType`: ItemType, `itemId`: int64, `name`: string, `group`: RecoGroup, `hasProfile`: boolean, `profile?`: ItemProfile}

---

## D. Bảo mật & độ tin cậy

1. `POST /public/ai/chat` là public: token gửi kèm bị bỏ qua (ADR-0005) nên không cá nhân hóa; câu hỏi về lịch của khách nhận `NEED_LOGIN`. Chặn theo IP trước khi nhúng hay gọi LLM (BR-AI-09, cơ chế ADR-0100).
2. `POST /me/ai/chat` và `/me/ai/pets/{petId}/…` chỉ CUSTOMER. Khách lấy từ token; `petId` và mã khách **không** lấy từ câu hỏi hay LLM; thú người khác hoặc chủ cũ trả 404, không lộ sự tồn tại (ADR-0006, BR-AI-11, 27).
3. `/ai/documents`, `/ai/item-profiles` chỉ SUPER_MANAGER; mọi thao tác ghi audit (BR-AI-17, mở rộng BR-QT-15).
4. Email, số điện thoại, số giấy tờ trong câu hỏi bị che trước khi nhúng và trước khi gọi LLM; ở `/me/ai/chat` tên các thú của khách cũng bị thay bằng `[thú]` (sau khi định tuyến ý định); tên thú gõ ở endpoint công khai không che được; không dữ liệu cá nhân nào tới nhà cung cấp; kết quả cá nhân không ghi nội dung vào nhật ký; khóa API chỉ ở biến môi trường (BR-AI-12, 24, 25).
5. Server không nhận câu trả lời của trợ lý từ client, nên không thể giả mạo lời trợ lý để chèn chỉ thị (BR-AI-08).
6. Mọi kết quả nghiệp vụ của hỏi đáp (ngoài phạm vi, từ chối y khoa, cấp cứu, AI hỏng, kết quả cá nhân) trả HTTP 200 kèm `result`; chỉ đầu vào sai là 400 và vượt tần suất là 429. `UNAVAILABLE` ghi `error_kind` và được đếm riêng (BR-AI-10).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Module `ai` do FE-1 dẫn; BE-1 / BE-2 thêm các `*QueryApi` đọc dữ liệu (07 §11) | `time.md`: AI do FE-1 dẫn, cả nhóm hỗ trợ dữ liệu |
| A2 | Hai endpoint hỏi đáp: công khai cho khách vãng lai, `/me` cho khách đăng nhập; giao diện chọn theo trạng thái đăng nhập | Endpoint công khai bỏ qua token (ADR-0005) nên không cá nhân hóa được |
| A3 | Không lưu hội thoại phía máy chủ; client gửi tối đa 3 câu hỏi trước của chính người dùng | Tránh lưu dữ liệu cá nhân và giả mạo lời trợ lý |
| A4 | Độ phổ biến của sản phẩm bán lẻ tính toàn chuỗi | Order bán lẻ không có `pet_id` |
| A5 | `result` ngoài `ANSWERED` vẫn là HTTP 200 | Giao diện rẽ theo `result`; AI hỏng không được thành lỗi trang (T82) |
| A6 | Ý định cá nhân nhận bằng từ khóa + dấu hiệu sở hữu mạnh (của tôi, tên thú) hoặc yếu (chỉ đủ với lịch hẹn), khớp theo cụm từ có ranh giới từ, không dùng LLM | Tất định, không gửi dữ liệu cá nhân cho nhà cung cấp; độ phủ hạn chế được chấp nhận |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Nhà cung cấp LLM và gói: **đã chốt tạm 5/11/2026** Gemini gói free, không trả phí; xem lại trước khi chạy thật (gói free đã gặp 429) | Đã chốt tạm (Hiến) | 07 §15 D1, adr/0109, BR-AI-25 |
| Q2 | Quy trình duyệt nội dung y khoa đã chốt (hai lớp: nguồn rồi bác sĩ thú y rà soát). Còn mở: **tên bác sĩ thú y rà soát** | TBD (nhóm) | BR-AI-14, 07 §6.3 |
| Q3 | Xác nhận tuổi SENIOR theo loài (tạm: chó 7, mèo 10, loài khác 7 năm, cấu hình được) | TBD (nhóm / người có chuyên môn) | 07 §7.3 |
| Q4 | Thêm `pet_id` tùy chọn vào Order bán lẻ để tính phổ biến theo thú | TBD (BE-2) | 07 A4 |
| Q5 | Nạp bài viết BV vào tri thức ở GĐ2 | TBD (nhóm) | BR-AI-13 |
| Q6 | Trả lời luôn bằng tiếng Việt hay theo ngôn ngữ câu hỏi | TBD (nhóm) | 07 §16 |
| Q7 | Mở rộng ý định cá nhân sang lưu trú và ngày tái khám ở GĐ2 | TBD (nhóm) | 07 §17.3 |

---

## F. OpenAPI 3.1

[`./openapi/ai-v1.yaml`](./openapi/ai-v1.yaml)
