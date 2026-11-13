# Pet Care Knowledge Base

> Bộ tài liệu Markdown nền tảng cho chatbot chăm sóc chó mèo. Đây là nội dung giáo dục phổ thông, không phải tài liệu chẩn đoán hoặc phác đồ điều trị.

## Trạng thái phát hành

**Toàn bộ tài liệu trong gói này đang ở trạng thái `DRAFT / PENDING_REVIEW`.**
Không đưa vào tập truy xuất production cho đến khi người có chuyên môn thú y kiểm duyệt và chuyển trạng thái sang `VERIFIED`.

## Cấu trúc

- `00_governance/`: chính sách tri thức, metadata, quy trình duyệt, quy tắc an toàn.
- `01_foundation/`: chăm sóc cơ bản cho chó mèo.
- `02_dog/`: nội dung riêng cho chó.
- `03_cat/`: nội dung riêng cho mèo.
- `04_health_prevention/`: chăm sóc dự phòng, vaccine, ký sinh trùng, răng miệng.
- `05_safety_emergency/`: dấu hiệu cần khám khẩn, sơ cứu an toàn.
- `06_lifestyle/`: hành vi, grooming, đi lại, nhận nuôi.

## Quy tắc ingestion

1. Chỉ lấy file `.md` trong thư mục này.
2. Đọc YAML frontmatter để lấy metadata.
3. Chỉ ingest nội dung có `status: VERIFIED` và `review.approved: true`.
4. Không dùng `DRAFT`, `PENDING_REVIEW`, `REJECTED`, `EXPIRED`.
5. Mỗi chunk phải giữ `document_id`, `title`, `species`, `topic`, `status`, `source_urls`, `reviewed_at`.
6. Không trộn dữ liệu dịch vụ, giá, chi nhánh, lịch hẹn hoặc lịch tiêm cá nhân vào KB; các dữ liệu đó phải lấy từ DB nghiệp vụ.
7. Không dùng KB để trả lời hồ sơ bệnh án cá nhân. Dữ liệu hồ sơ phải đi qua luồng nghiệp vụ được phân quyền riêng; theo yêu cầu hiện tại của dự án, chatbot vẫn từ chối truy vấn hồ sơ bệnh án.
8. Tài liệu có nội dung lâm sàng, vaccine, ký sinh trùng hoặc cấp cứu phải được bác sĩ thú y duyệt trước khi publish.

## Ánh xạ vào UC93 của Pet Care (script `BE/scripts/ai-kb/load-docs-rag.mjs`)

Script đọc đúng bảng này; giá trị không có trong bảng thì dừng và báo, không tự đoán.

| `topic` frontmatter | `DocumentTopic` (V34) |
|---|---|
| basic_care, dog_care, cat_care, puppy_care, kitten_care, senior_care, adoption, travel, multi_pet_household, family_safety, cat_environment, emergency_triage, first_aid, poisoning_safety | `CARE` |
| nutrition, weight_management | `NUTRITION` |
| dog_behavior | `BEHAVIOR` |
| grooming_skin, dental_care | `HYGIENE` |
| vaccination, parasite_prevention | `VACCINATION` |

`CARE` là ánh xạ tạm cho nhóm an toàn / cấp cứu / lối sống; ADR-0134 đề xuất thêm `SAFETY` và `LIFESTYLE` ở V52.

- `species`: `[dog]` → `DOG`, `[cat]` → `CAT`, `[dog, cat]` → `ALL`.
- Trạng thái: `DRAFT` / `PENDING_REVIEW` của file → `DRAFT` trong DB (không có người rà soát). `VERIFIED` kèm `review.approved: true` → `PUBLISHED` với `reviewer_name = review.reviewer_name`, `reviewed_on = review.reviewed_at`. `REJECTED` / `EXPIRED` → không nạp; đã nạp rồi thì lưu trữ tay qua UC93.
- `sourceNote` = `[docs_RAG <document_id> v<version> <risk_level>] <source_urls nối bằng " ; ">`, cắt 500 ký tự.
- `keywords`, `life_stage`, `valid_from`, `valid_until`: chưa có cột, không nạp (nợ ghi ở ADR-0134).
- Không nạp: `00_governance/`, `README.md`, `manifest.json`.
- Idempotent theo `title`; `document_id` và `title` phải duy nhất trong bộ.

## Ghi chú phạm vi

- Nội dung ưu tiên chó và mèo nuôi trong gia đình.
- Không bao gồm phác đồ chẩn đoán, thuốc, liều dùng, điều trị tại nhà, công thức tự pha thuốc hoặc hướng dẫn thủ thuật.
- Các dấu hiệu nguy hiểm trong tài liệu chỉ nhằm nhận diện tình huống cần liên hệ cơ sở thú y, không dùng để tự chẩn đoán.
- Tài liệu nguồn quốc tế cần được bác sĩ thú y tại Việt Nam rà soát để điều chỉnh theo dịch tễ, sản phẩm, quy định và điều kiện địa phương.
