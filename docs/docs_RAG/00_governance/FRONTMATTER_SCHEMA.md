# Quy ước metadata cho file Markdown

Mỗi file nội dung phải bắt đầu bằng YAML frontmatter:

```yaml
---
document_id: PET-DOG-CARE-001
title: Chăm sóc chó cơ bản
language: vi
species: [dog]
life_stage: [puppy, adult, senior]
topic: basic_care
risk_level: LOW
status: DRAFT
version: 1
review:
  approved: false
  reviewer_name: null
  reviewer_role: veterinarian
  reviewed_at: null
valid_from: null
valid_until: null
source_urls:
  - https://example.org/source
keywords:
  - chăm sóc chó
  - vệ sinh
---
```

## Trường bắt buộc

- `document_id`: duy nhất, ổn định, không tái sử dụng.
- `title`, `language`, `species`, `topic`, `risk_level`, `status`, `version`.
- `review.approved`: mặc định `false`.
- `source_urls`: danh sách nguồn có thể kiểm tra.
- `keywords`: từ đồng nghĩa và cách diễn đạt tiếng Việt thường gặp.

## Trạng thái

- `DRAFT`: đang soạn.
- `PENDING_REVIEW`: đã soạn, chờ chuyên gia duyệt.
- `VERIFIED`: đã duyệt, được phép ingest.
- `REJECTED`: không đạt, không ingest.
- `EXPIRED`: hết hiệu lực, phải rà soát lại.

## Chunking gợi ý

- Tách theo heading `##` hoặc `###`, không cắt rời cảnh báo khỏi nội dung.
- Mỗi chunk nên có một ý chính, khoảng 300–700 tokens tùy embedding/model.
- Overlap khoảng 50–100 tokens nếu cần giữ ngữ cảnh giữa các đoạn.
- Gắn metadata của tài liệu vào từng chunk.
- Không gộp hai loài hoặc hai chủ đề khác nhau vào cùng chunk nếu có thể tránh.
- Không tạo chunk từ YAML frontmatter như nội dung trả lời; dùng nó làm metadata.
