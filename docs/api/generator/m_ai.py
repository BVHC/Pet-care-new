from lib import Module

m = Module(
    key="ai", title="AI API v1 (tầng 3)", codes="AI", owner="FE-1 (dẫn) · cả nhóm",
    sources="07-ai-subsystem.md (UC90–UC94, ST22–ST23, BR-AI-01…28, FSM #13, §8 bảng ai_documents / ai_chunks / ai_chat_logs / ai_item_profiles); adr/0109",
    intro="Chatbot hỏi đáp (RAG + LLM), trả lời theo từng khách về lịch hẹn và lịch tiêm (tất định), gợi ý cá nhân hóa, quản lý tri thức và hồ sơ gợi ý. **Đặc tả bổ sung GĐ2, đã duyệt ở T54 (Hiến, 5/11/2026); chưa có cài đặt.**",
    frozen=[
        "UC91 và UC92 không gọi LLM; chỉ UC90 gọi nhà cung cấp LLM / embedding (07 §1.2).",
        "ST22 (nạp lại chỉ mục) và ST23 (dọn nhật ký hỏi đáp) là tác vụ hệ thống, không có endpoint; chỉ có `reindex` để người quản lý yêu cầu nạp lại một tài liệu.",
        "Không có endpoint chấm điểm câu trả lời, thống kê hỏi đáp, đọc nhật ký (07 §17.3, mở rộng).",
        "Không lưu hội thoại phía máy chủ; client chỉ gửi các câu hỏi trước **của chính người dùng**, không gửi câu trả lời của trợ lý (BR-AI-08).",
        "Lịch hẹn sắp tới được trả qua `POST /me/ai/chat` (BR-AI-26, 27), không có endpoint riêng: giao diện đã có danh sách lịch hẹn ở appointment-v1.",
        "Hỏi về lưu trú, ngày tái khám qua chatbot là mở rộng (07 Q7).",
    ],
    tags={
        "Chatbot": "Hỏi đáp công khai và hỏi đáp của khách đăng nhập (UC90, UC91)",
        "Pet assistant": "Lịch tiêm và gợi ý theo thú của tôi (UC91, UC92)",
        "Knowledge": "Quản lý tài liệu tri thức (UC93)",
        "Item profiles": "Hồ sơ gợi ý của dịch vụ / sản phẩm (UC94)",
    },
    security=[
        "`POST /public/ai/chat` là public: token gửi kèm bị bỏ qua (ADR-0005) nên không cá nhân hóa; câu hỏi về lịch của khách nhận `NEED_LOGIN`. Chặn theo IP trước khi nhúng hay gọi LLM (BR-AI-09, cơ chế ADR-0100).",
        "`POST /me/ai/chat` và `/me/ai/pets/{petId}/…` chỉ CUSTOMER. Khách lấy từ token; `petId` và mã khách **không** lấy từ câu hỏi hay LLM; thú người khác hoặc chủ cũ trả 404, không lộ sự tồn tại (ADR-0006, BR-AI-11, 27).",
        "`/ai/documents`, `/ai/item-profiles` chỉ SUPER_MANAGER; mọi thao tác ghi audit (BR-AI-17, mở rộng BR-QT-15).",
        "Email, số điện thoại, số giấy tờ trong câu hỏi bị che trước khi nhúng và trước khi gọi LLM; ở `/me/ai/chat` tên các thú của khách cũng bị thay bằng `[thú]` (sau khi định tuyến ý định; tên trùng từ thông dụng như Vàng chỉ bị thay khi đi sau bé, con, chú, em, ADR-0122); tên thú gõ ở endpoint công khai không che được; không dữ liệu cá nhân nào tới nhà cung cấp; kết quả cá nhân không ghi nội dung vào nhật ký; khóa API chỉ ở biến môi trường (BR-AI-12, 24, 25).",
        "Server không nhận câu trả lời của trợ lý từ client, nên không thể giả mạo lời trợ lý để chèn chỉ thị (BR-AI-08).",
        "Mọi kết quả nghiệp vụ của hỏi đáp (ngoài phạm vi, từ chối y khoa, cấp cứu, AI hỏng, kết quả cá nhân) trả HTTP 200 kèm `result`; chỉ đầu vào sai là 400 và vượt tần suất là 429. `UNAVAILABLE` ghi `error_kind` và được đếm riêng (BR-AI-10).",
    ],
    assumptions=[
        ("A1", "Module `ai` do FE-1 dẫn; BE-1 / BE-2 thêm các `*QueryApi` đọc dữ liệu (07 §11)", "`time.md`: AI do FE-1 dẫn, cả nhóm hỗ trợ dữ liệu"),
        ("A2", "Hai endpoint hỏi đáp: công khai cho khách vãng lai, `/me` cho khách đăng nhập; giao diện chọn theo trạng thái đăng nhập", "Endpoint công khai bỏ qua token (ADR-0005) nên không cá nhân hóa được"),
        ("A3", "Không lưu hội thoại phía máy chủ; client gửi tối đa 3 câu hỏi trước của chính người dùng", "Tránh lưu dữ liệu cá nhân và giả mạo lời trợ lý"),
        ("A4", "Độ phổ biến của sản phẩm bán lẻ tính toàn chuỗi", "Order bán lẻ không có `pet_id`"),
        ("A5", "`result` ngoài `ANSWERED` vẫn là HTTP 200", "Giao diện rẽ theo `result`; AI hỏng không được thành lỗi trang (T82)"),
        ("A6", "Ý định cá nhân nhận bằng từ khóa + dấu hiệu sở hữu mạnh (của tôi, tên thú) hoặc yếu (chỉ đủ với lịch hẹn), khớp theo cụm từ có ranh giới từ, không dùng LLM", "Tất định, không gửi dữ liệu cá nhân cho nhà cung cấp; độ phủ hạn chế được chấp nhận"),
    ],
    questions=[
        ("Q1", "Nhà cung cấp LLM và gói: **đã chốt tạm 5/11/2026** Gemini gói free, không trả phí; xem lại trước khi chạy thật (gói free đã gặp 429)", "Đã chốt tạm (Hiến)", "07 §15 D1, adr/0109, BR-AI-25"),
        ("Q2", "Quy trình duyệt nội dung y khoa đã chốt (hai lớp: nguồn rồi bác sĩ thú y rà soát). Còn mở: **tên bác sĩ thú y rà soát**", "TBD (nhóm)", "BR-AI-14, 07 §6.3"),
        ("Q3", "Xác nhận tuổi SENIOR theo loài (tạm: chó 7, mèo 10, loài khác 7 năm, cấu hình được)", "TBD (nhóm / người có chuyên môn)", "07 §7.3"),
        ("Q4", "Thêm `pet_id` tùy chọn vào Order bán lẻ để tính phổ biến theo thú", "TBD (BE-2)", "07 A4"),
        ("Q5", "Nạp bài viết BV vào tri thức ở GĐ2", "TBD (nhóm)", "BR-AI-13"),
        ("Q6", "Trả lời luôn bằng tiếng Việt hay theo ngôn ngữ câu hỏi", "TBD (nhóm)", "07 §16"),
        ("Q7", "Mở rộng ý định cá nhân sang lưu trú và ngày tái khám ở GĐ2", "TBD (nhóm)", "07 §17.3"),
    ],
)

m.param("PetId", "petId", "path", "int64")
m.param("DocumentId", "documentId", "path", "int64")
m.param("ItemTypePath", "itemType", "path", "enum:SERVICE,PRODUCT")
m.param("ItemId", "itemId", "path", "int64")

# ---- enum
m.schema("ChatResult", None,
         enum=["ANSWERED", "OUT_OF_SCOPE", "MEDICAL_REFUSAL", "EMERGENCY", "UNAVAILABLE", "PERSONAL", "NEED_PET_CHOICE",
               "NEED_LOGIN"],
         desc="Kết quả của một lượt hỏi đáp; giao diện rẽ theo giá trị này (07 §10). `PERSONAL`, `NEED_PET_CHOICE` chỉ có ở "
              "`/me/ai/chat`; `NEED_LOGIN` chỉ có ở `/public/ai/chat`")
m.schema("VaccinationStatus", None, enum=["NOT_YET_ELIGIBLE", "NOT_STARTED", "UP_TO_DATE", "DUE_SOON", "OVERDUE"],
         desc="BR-AI-11")
m.schema("RecoGroup", None, enum=["GROOMING", "BOARDING", "PRODUCT"], desc="BR-AI-18")
m.schema("ItemType", None, enum=["SERVICE", "PRODUCT"])
m.schema("DocumentSource", None, enum=["MANUAL", "CATALOG_PROTOCOL"], desc="BR-AI-13")
m.schema("DocumentTopic", None, enum=["CARE", "NUTRITION", "BEHAVIOR", "HYGIENE", "VACCINATION", "CLINIC_INFO"])
m.schema("DocumentSpecies", None, enum=["DOG", "CAT", "OTHER", "ALL"])
m.schema("DocumentStatus", None, enum=["DRAFT", "PUBLISHED", "ARCHIVED"], desc="03 #13")
m.schema("IndexStatus", None, enum=["PENDING", "INDEXED", "FAILED"])

# ---- chatbot
m.schema("ChatRequest", {
    "question": "string:500|1–500 ký tự sau khi cắt khoảng trắng (BR-AI-08)",
    "previousQuestions": "array:string?|Tối đa 3 câu hỏi trước **của chính người dùng**, mỗi câu ≤ 500 ký tự. Không có trường cho câu trả lời của trợ lý (BR-AI-08)",
})
m.schema("ChatSource", {"documentId": "int64", "title": "string"})
m.schema("EmergencyBranch", {"branchId": "int64", "name": "string", "address": "string", "phone": "string"})
m.schema("PetChoice", {"petId": "int64", "name": "string"})
m.schema("ChatResponse", {
    "result": "ref:ChatResult", "answer": "string|Câu trả lời hoặc câu mẫu cố định",
    "sources": "array:ref:ChatSource|Tối đa 3 tài liệu; rỗng khi không ANSWERED",
    "disclaimer": "string?|Có với ANSWERED (BR-AI-05)",
    "bookingHint": "bool|true thì giao diện hiện nút đặt lịch khám (UC39)",
    "emergencyBranches": "array:ref:EmergencyBranch|Chỉ có với EMERGENCY (BR-AI-03)",
    "pets": "array:ref:PetChoice|Chỉ có với NEED_PET_CHOICE: giao diện cho chọn rồi gọi `…/vaccination-summary` (BR-AI-27)",
})

# ---- thú của tôi
m.schema("VaccinationItem", {
    "vaccineTypeId": "int64", "vaccineName": "string", "status": "ref:VaccinationStatus",
    "lastDoseNumber": "int?", "lastAdministeredOn": "date?", "nextDoseNumber": "int?", "nextDueDate": "date?",
    "firstDoseFromAgeWeeks": "int?|Tuổi tối thiểu (tuần) của mũi 1, có với NOT_YET_ELIGIBLE / NOT_STARTED",
})
m.schema("VaccinationSummary", {
    "petId": "int64", "petName": "string", "items": "array:ref:VaccinationItem",
    "message": "string|Câu hiển thị trong khung chat, sinh từ mẫu cố định, không qua LLM (BR-AI-11)",
    "disclaimer": "string",
})
m.schema("RecommendedItem", {
    "group": "ref:RecoGroup", "itemType": "ref:ItemType", "itemId": "int64", "name": "string",
    "price": "money", "priceIsFrom": "bool|BR-CK-02", "priceUnit": "string?|`đêm` với BOARDING",
    "imageUrl": "url?", "score": "decimal|0–100 (BR-AI-21)",
    "reasons": "array:string|Sinh từ mẫu, không dùng LLM (BR-AI-22)",
})
m.schema("RecommendationResponse", {
    "petId": "int64", "generatedAt": "datetime", "items": "array:ref:RecommendedItem|Sắp theo nhóm rồi điểm giảm dần",
})

# ---- tri thức
m.schema("AiDocumentSummary", {
    "documentId": "int64", "sourceType": "ref:DocumentSource", "title": "string", "topic": "ref:DocumentTopic",
    "species": "ref:DocumentSpecies", "status": "ref:DocumentStatus", "version": "int",
    "indexStatus": "ref:IndexStatus", "updatedAt": "datetime",
})
m.schema("AiDocument", {
    "documentId": "int64", "sourceType": "ref:DocumentSource", "title": "string", "topic": "ref:DocumentTopic",
    "species": "ref:DocumentSpecies", "content": "string|Markdown ≤ 20 000 ký tự",
    "sourceNote": "string?|Lớp 1 — nguồn (BR-AI-14)", "reviewerName": "string?|Lớp 2 — bác sĩ thú y rà soát",
    "reviewedOn": "date?|Ngày rà soát",
    "status": "ref:DocumentStatus", "version": "int", "indexedVersion": "int|0 = chưa nạp",
    "indexStatus": "ref:IndexStatus", "indexError": "string?", "chunkCount": "int",
    "publishedAt": "datetime?", "updatedAt": "datetime",
})
m.schema("CreateAiDocumentRequest", {
    "title": "string:200", "topic": "ref:DocumentTopic", "species": "ref:DocumentSpecies",
    "content": "string|≤ 20 000 ký tự (BR-AI-14)", "sourceNote": "string:500?|Lớp 1 — nguồn; bắt buộc khi xuất bản",
    "reviewerName": "string:100?|Lớp 2 — bác sĩ thú y rà soát; bắt buộc khi xuất bản", "reviewedOn": "date?|Ngày rà soát; bắt buộc khi xuất bản",
})
m.schema("UpdateAiDocumentRequest", {
    "title": "string:200?", "topic": "ref:DocumentTopic?", "species": "ref:DocumentSpecies?",
    "content": "string?", "sourceNote": "string:500?", "reviewerName": "string:100?", "reviewedOn": "date?",
}, required=[])

# ---- hồ sơ gợi ý
m.schema("ProfileSpecies", None, enum=["DOG", "CAT", "OTHER"])
m.schema("ItemProfile", {
    "species": "ref:ProfileSpecies?|Vắng = mọi loài", "minAgeWeeks": "int?", "maxAgeWeeks": "int?",
    "minWeightKg": "decimal?", "maxWeightKg": "decimal?",
    "breedKeywords": "array:string|Từ khóa giống viết thường không dấu; rỗng = mọi giống",
    "repeatIntervalDays": "int?|Chu kỳ dùng lại (ngày); vắng = mặc định [CFG]",
}, required=["breedKeywords"])
m.schema("ItemProfileRow", {
    "itemType": "ref:ItemType", "itemId": "int64", "name": "string", "group": "ref:RecoGroup",
    "hasProfile": "bool", "profile": "ref:ItemProfile?",
})

RATE_ERR = ("`TOO_MANY_REQUESTS` một IP gọi quá nhiều lần (BR-AI-09, cơ chế ADR-0100): header `Retry-After` là số giây "
            "nguyên phải chờ; request bị chặn trước khi nhúng hay gọi LLM, không ghi nhật ký")
CHAT_400 = ("`VALIDATION_FAILED` / `BR-AI-08` câu hỏi rỗng hoặc quá 500 ký tự · quá 3 câu hỏi trước · một câu hỏi trước quá "
            "500 ký tự")
A04 = "Chỉ SUPER_MANAGER"
OWN = "CUSTOMER, chỉ thú hiện thuộc về mình"
DOC_STATE = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #13)"

# ---- UC90, UC91 chatbot
m.op("post", "/public/ai/chat", "askChatbot", "Hỏi đáp chatbot (khách vãng lai)", "Chatbot", "UC90, UC91",
     "BR-AI-01…10, 12, 24, 28", "A01", "Public", body="ChatRequest", resp="ChatResponse", public=True,
     errors=(400, 429), err_desc={400: CHAT_400, 429: RATE_ERR},
     notes="Luôn 200 với mọi kết quả nghiệp vụ; giao diện rẽ theo `result`. Cấp cứu, từ chối bệnh án, `NEED_LOGIN` và dưới "
           "ngưỡng truy hồi không gọi LLM; lỗi nhà cung cấp hoặc hết hạn mức trả `UNAVAILABLE` (BR-AI-10). Token gửi kèm bị "
           "bỏ qua (ADR-0005) nên không bao giờ trả `PERSONAL` / `NEED_PET_CHOICE`.")
m.op("post", "/me/ai/chat", "askChatbotAsCustomer", "Hỏi đáp chatbot (khách đăng nhập, có cá nhân hóa)", "Chatbot",
     "UC90, UC91", "BR-AI-01…10, 12, 24, 26…28", "A02", "CUSTOMER", body="ChatRequest", resp="ChatResponse",
     errors=(400, 401, 403, 429), err_desc={400: CHAT_400, 429: RATE_ERR},
     notes="Cùng luồng với endpoint công khai, thêm bước định tuyến ý định cá nhân trước truy hồi (BR-AI-26): lịch hẹn sắp "
           "tới và lịch tiêm trả `PERSONAL` bằng câu mẫu (không qua LLM, không ghi nội dung vào nhật ký); nhiều thú mà câu "
           "không nêu tên thì `NEED_PET_CHOICE` kèm `pets`. Khách lấy từ token, không lấy `petId` từ câu hỏi (BR-AI-27).")

# ---- UC91, UC92 thú của tôi
m.op("get", "/me/ai/pets/{petId}/vaccination-summary", "getPetVaccinationSummary", "Tra lịch tiêm của thú",
     "Pet assistant", "UC91", "BR-AI-11, 12", "A02", OWN, resp="VaccinationSummary", path_params=["PetId"],
     errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KH-05` thú đã mất · `MALFORMED_REQUEST` `petId` sai kiểu",
               404: "Không tìm thấy thú — cả khi thú thuộc người khác hoặc chủ cũ (ADR-0006)"},
     notes="Tất định, không qua LLM, chỉ mũi tiêm ghi nhận tại hệ thống (04 nguyên tắc 9); mỗi loại vaccine lấy mũi mới "
           "nhất chưa bị thay thế. Không ghi nội dung vào nhật ký. Giao diện gọi sau `NEED_PET_CHOICE` hoặc khi đã biết thú.")
m.op("get", "/me/ai/pets/{petId}/recommendations", "getPetRecommendations", "Gợi ý cá nhân hóa cho thú",
     "Pet assistant", "UC92", "BR-AI-18…23", "A02", OWN, resp="RecommendationResponse", path_params=["PetId"],
     query=["group=enum:GROOMING,BOARDING,PRODUCT|Vắng = cả ba nhóm", "limit=int|1–12, vắng = 6 mỗi nhóm [CFG]"],
     errors=(400, 401, 403, 404),
     err_desc={400: "`BR-KH-05` thú đã mất · `VALIDATION_FAILED` `limit` ngoài 1–12",
               404: "Không tìm thấy thú (kể cả thú của người khác)"},
     notes="Không bao giờ có thuốc, vaccine, sản phẩm kê đơn, dịch vụ khám / tiêm (BR-SP-01, BR-AI-18). Mỗi lần tính từ dữ liệu thú hiện tại (BR-AI-23).")

# ---- UC93 tri thức
m.op("get", "/ai/documents", "listAiDocuments", "Danh sách tài liệu tri thức", "Knowledge", "UC93", "BR-AI-17",
     "A04", A04, resp="AiDocumentSummary", page=True,
     query=["status=enum:DRAFT,PUBLISHED,ARCHIVED", "topic=enum:CARE,NUTRITION,BEHAVIOR,HYGIENE,VACCINATION,CLINIC_INFO",
            "species=enum:DOG,CAT,OTHER,ALL", "q=string|Tìm theo tiêu đề", "page", "size"], errors=(400, 401, 403),
     notes="Mới sửa trước (`updatedAt` giảm dần, trùng thì `documentId` giảm dần).")
m.op("post", "/ai/documents", "createAiDocument", "Tạo tài liệu tri thức (nháp)", "Knowledge", "UC93",
     "BR-AI-14, 17", "A04", A04, "— → DRAFT (Tài liệu#1)", body="CreateAiDocumentRequest", resp="AiDocument",
     status=201, errors=(400, 401, 403),
     err_desc={400: "`BR-AI-14` tiêu đề rỗng / quá 200 ký tự · nội dung quá 20 000 ký tự"})
m.op("get", "/ai/documents/{documentId}", "getAiDocument", "Chi tiết tài liệu tri thức", "Knowledge", "UC93",
     "BR-AI-17", "A04", A04, resp="AiDocument", path_params=["DocumentId"], errors=(400, 401, 403, 404))
m.op("patch", "/ai/documents/{documentId}", "updateAiDocument", "Sửa tài liệu tri thức", "Knowledge", "UC93",
     "BR-AI-14, 16, 17", "A04", A04, "PUBLISHED → PUBLISHED (Tài liệu#3) hoặc sửa nháp", body="UpdateAiDocumentRequest",
     resp="AiDocument", path_params=["DocumentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-AI-14` nội dung quá dài · tài liệu `CATALOG_PROTOCOL` không sửa tay (BR-AI-13)",
               409: "Tài liệu `ARCHIVED` không sửa được"},
     notes="Sửa tài liệu `PUBLISHED` tăng `version`; ST22 dựng đoạn mới rồi hoán đổi trong một transaction, đoạn cũ phục vụ tới lúc đó (BR-AI-16).")
m.op("post", "/ai/documents/{documentId}/publish", "publishAiDocument", "Xuất bản tài liệu", "Knowledge", "UC93",
     "BR-AI-14, 16, 17", "A04", A04, "DRAFT / ARCHIVED → PUBLISHED (Tài liệu#2, #5)", resp="AiDocument",
     path_params=["DocumentId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-AI-14` thiếu `sourceNote` (lớp 1) hoặc `reviewerName` / `reviewedOn` (lớp 2) với tài liệu `MANUAL` · nội dung rỗng",
               409: DOC_STATE},
     notes="`indexStatus` về `PENDING`; ST22 nạp chỉ mục sau commit.")
m.op("post", "/ai/documents/{documentId}/archive", "archiveAiDocument", "Lưu trữ tài liệu", "Knowledge", "UC93",
     "BR-AI-16, 17", "A04", A04, "PUBLISHED / DRAFT → ARCHIVED (Tài liệu#4, #6)", resp="AiDocument",
     path_params=["DocumentId"], errors=(400, 401, 403, 404, 409), err_desc={409: DOC_STATE},
     notes="Có hiệu lực ngay khi commit: truy hồi lọc `status` trong SQL (BR-AI-16).")
m.op("post", "/ai/documents/{documentId}/reindex", "reindexAiDocument", "Nạp lại chỉ mục của tài liệu", "Knowledge",
     "UC93", "BR-AI-15, 16, 17", "A04", A04, resp="AiDocument", status=202, path_params=["DocumentId"],
     errors=(400, 401, 403, 404, 409),
     err_desc={409: "Tài liệu không `PUBLISHED` nên không có gì để nạp (03 #13)"},
     idem="Có", notes="Dùng để thử lại khi `indexStatus = FAILED`. Trả ngay với `indexStatus = PENDING`; ST22 làm nền.")

# ---- UC94 hồ sơ gợi ý
m.op("get", "/ai/item-profiles", "listItemProfiles", "Danh sách dịch vụ / sản phẩm kèm hồ sơ gợi ý", "Item profiles",
     "UC94", "BR-AI-18, 20", "A04", A04, resp="ItemProfileRow", page=True,
     query=["itemType=enum:SERVICE,PRODUCT", "missingOnly=bool|true = chỉ mục chưa có hồ sơ", "q=string|Tìm theo tên",
            "page", "size"], errors=(400, 401, 403),
     notes="Chỉ dịch vụ thẩm mỹ và sản phẩm `GOODS` không kê đơn đang kinh doanh; loại chuồng dùng `kennel_types` nên không có ở đây (BR-AI-20).")
m.op("put", "/ai/item-profiles/{itemType}/{itemId}", "saveItemProfile", "Lưu hồ sơ gợi ý", "Item profiles", "UC94",
     "BR-AI-18, 20, 17", "A04", A04, body="ItemProfile", resp="ItemProfileRow", path_params=["ItemTypePath", "ItemId"],
     errors=(400, 401, 403, 404), idem="Có",
     err_desc={400: "`BR-AI-18` mục là dịch vụ khám / tiêm, thuốc, vaccine hoặc sản phẩm kê đơn · `VALIDATION_FAILED` `min` lớn hơn `max`",
               404: "Không tìm thấy dịch vụ / sản phẩm"},
     notes="Ghi đè toàn bộ hồ sơ; gửi mọi trường rỗng để xóa thuộc tính (mục trở về điểm trung tính, BR-AI-20).")
