# ADR-0131: Gỡ khiếu nại khỏi DB ở V51; chia migration của ADR-0122 thành V51 / V55 (T70 PR1; V55 trước là V52)

- **Trạng thái:** Proposed (BE-1 làm T70 PR1, PR2 và PR3, 10/11/2026; V55 đã áp lên DB demo 5433; đổi từ V52 ngày 15/11)
- **Liên quan:** ADR-0122 (bỏ khiếu nại, feedback không trạng thái, giữ 30 ngày), ADR-0113 (V36–V41, **không sửa**, chỉ gỡ phần khiếu nại bằng V51), ADR-0123 (Nhân sự, cần một số V), ADR-0112 (khuôn job dọn dẹp); nợ D061, D062; `docs/02` BR-DG-05, `docs/09` §D, §G.
<<<<<<< HEAD
- **Đánh số lại:** (autopilot 15/11/2026) migration `feedbacks` (V52 trong văn bản dưới) đã đổi thành **V55** vì V52 trùng `V52__ai_general_knowledge_switch`; các chỗ ghi V52 cho phần feedback nghĩa là V55.
- **Đánh số (16/11/2026):** số 0131 từng trùng với ADR AI `0131-ai-chat-pipeline-t77b`; ADR AI đã đổi sang **0141** (`0141-ai-chat-pipeline-t77b`), ADR này giữ **0131**; không cần sửa chú thích migration.
- **Đánh số:** V51 dùng cho PR này, V52 giữ cho phần `feedbacks` (PR2), V53 cho D062. V49 vẫn bỏ trống cho BE-2 (CLAUDE.md *Persistence*).
- **Đánh số lại (23/11/2026, sửa merge PR #33):** nhánh `manh` từng đổi phần `feedbacks` thành **V56** (D069) và dùng V55 cho Nhân sự; bản đã áp lên DB demo 5433 là **V55** = `drop_feedback_status_columns`, nên file V56 cùng DDL bị xóa và migration Nhân sự (D062) đổi thành **V61**. Mọi chỗ ghi V56 cho phần feedback đọc là V55.
=======
- **Đánh số lại:** (autopilot 15/11/2026) migration `feedbacks` (V52 trong văn bản dưới) đổi thành **V55** vì V52 trùng `V52__ai_general_knowledge_switch`; (BE-1 16/11/2026, D069) đổi tiếp thành **V56** vì V55 bị Nhân sự lấy. Sau merge PR #33 (22/11/2026) cây còn hai file `V55__…` và `V56__drop_feedback_status_columns.sql` **cùng chứa marker 8 ký tự giữa SQL** (sai cú pháp; V55 còn khác checksum của `dev`). Đã sửa ở N0 (22/11, D069): V55 khôi phục về file sạch của `dev` (blob `ae7de8f5`), V56 feedback xóa; số cuối cùng của phần feedback là **V55**, V56 là `ai_pet_context` của AI. Các chỗ ghi V52 cho phần feedback đọc là số cuối cùng được giữ.
- **Đánh số (16/11/2026):** số 0131 từng trùng với ADR AI `0131-ai-chat-pipeline-t77b`; ADR AI đã đổi sang **0141** (`0141-ai-chat-pipeline-t77b`), ADR này giữ **0131**; không cần sửa chú thích migration.
- **Đánh số:** V51 dùng cho PR này, V52 giữ cho phần `feedbacks` (PR2), V53 cho D062. **Đánh số lại (16/11/2026, D069):** V52 trùng V52 của AI (`ai_general_knowledge_switch`) và V53 bị AI lấy (T90) nên phần `feedbacks` đổi thành **V56**; Nhân sự (D062) dùng V55; mọi chỗ ghi V52 / V53 bên dưới đọc là V56 / V55. V49 vẫn bỏ trống cho BE-2 (CLAUDE.md *Persistence*).
>>>>>>> 0e695149 (feat: T69: nền module nội trú — entity, FSM #24 / #25, V62 (N1, ADR-0149, D078))

## Bối cảnh

ADR-0122 (Accepted) ghi một migration V51 làm hai việc: gỡ khiếu nại (V39, V40) và bỏ cột trạng thái của `feedbacks`. Làm hai việc đó trong một PR buộc đổi cả entity `Feedback`, `FeedbackReviewService`, endpoint `POST /feedbacks/{id}/resolve`, hợp đồng `content-v1` và test feedback cùng lúc. Phần gỡ khiếu nại thì độc lập: không có Java nào dùng hai bảng hay bảy mẫu, chỉ còn 6 hằng `ConfigKey` và 7 hằng `NotificationTemplateCode` (D061).

## Quyết định

1. **V51 chỉ làm phần khiếu nại và phần thêm:**
   - `DROP TABLE complaint_messages`, rồi `complaints` (FK duy nhất trỏ vào `complaints` là của bảng con).
   - Xóa 6 khóa `complaint.*` (mỗi khóa một câu `DELETE … WHERE key = …`, như V27) và 7 mẫu `COMPLAINT_*`.
   - Thêm khóa `feedback.retention_days` (30, 7–365, BR-DG-05) và hai index `feedbacks (created_at)`, `feedbacks (branch_id, created_at)`.
   - **Không đụng cột / CHECK của `feedbacks`.** Entity `Feedback` vẫn khớp bảng (`ddl-auto=validate`).
2. **V55 (PR2, đã viết; trước là V52) `ALTER TABLE feedbacks DROP COLUMN status, seen_at, resolved_by, resolved_at, resolution_note`**, đi cùng việc bỏ trạng thái trong code (entity `Feedback`, `FeedbackReviewService` chỉ đọc, bỏ endpoint `POST /feedbacks/{id}/resolve`, `status` khỏi `FeedbackResponse` / `MyFeedbackResponse`, `FeedbackStatus`, `ResolveFeedbackRequest`, `markSeenIfNew`, `findByIdForUpdate`). CHECK `ck_feedbacks_status`, `ck_feedbacks_resolution_note`, FK `resolved_by` và 2 index dính cột đi theo cột. Mất các ghi chú xử lý nội bộ cũ (chấp nhận: đề tài chưa có dữ liệu thật, feedback vốn chỉ giữ 30 ngày). Hợp đồng `content-v1` và `generator/m_content.py` **đã đổi trước code** ở commit `8870863` (không có `/resolve`, không trường trạng thái): PR2 đưa code về khớp, chạy lại `generate.py` thấy không có khác biệt nội dung. `ix_feedbacks_branch_status` và `ix_feedbacks_resolved_by` mất theo cột. Số V55 chờ BE-2 đồng ý.
3. **Không có bước kiểm "bảng rỗng" trước khi `DROP`.** Không dòng code nào ghi vào hai bảng (chỉ `Tier3Be1SchemaIT` chèn thử trong transaction), đề tài chưa có người dùng thật. **Mẫu thông báo** xóa bằng một câu `DELETE`; `notification_outbox.template_code` có FK `RESTRICT` nên nếu còn dòng trỏ tới mẫu thì migration lỗi, không xóa lặng lẽ.
4. **Hai index đặt ở V51 dù chưa có code dùng:** chỉ thêm nên an toàn với code cũ; `(created_at)` phục vụ ST24 (PR3, kèm `EXPLAIN` trong IT), `(branch_id, created_at)` thay `ix_feedbacks_branch_status` sau V52. Khóa `feedback.retention_days` chưa có tác dụng cho tới PR3 (màn UC10 hiện một khóa chưa chạy).
5. **PR3, ST24 `FeedbackCleanupJob`** (`module/content/job`, `FeedbackCleanupService`, `FeedbackRepository.deleteExpiredBefore`): theo `ChatLogCleanupJob`, đọc [CFG] `feedback.retention_days` ở mỗi lượt, xóa cứng `created_at < now − hạn` theo lô, mỗi lô một tx, `SKIP LOCKED`, cron `app.jobs.feedback-cleanup.cron` 03:30:31, `"-"` ở test, env `FEEDBACK_CLEANUP_CRON` (đặt `-` để tắt). **Khác ADR-0122:** (a) quét `ORDER BY created_at, id`, không phải id tăng dần, để dùng `ix_feedbacks_created_at`; (b) IT era 1981 (1982 đã là `ChatLogCleanupIT`); (c) lô 500 và nghỉ 50 ms sau mỗi lô đầy (`batch-gap`), ngân sách 60 s; (d) hạn ngoài 7–365 thì bỏ lượt (`FEEDBACK_CLEANUP_SKIPPED`), vì xóa không hoàn tác. `created_at` bất biến (`updatable = false`, chỉ có một chỗ insert) nên mọi dòng bị xóa đều là dòng subquery đã chọn và khóa. **Rủi ro chấp nhận:** đồng hồ máy chủ nhảy tới tương lai làm cutoff lệch (không phát hiện được, công tắc tắt: `FEEDBACK_CLEANUP_CRON=-`); giảm `retention_days` xóa luôn dòng đang có ở lượt kế tiếp (như ST23, ADR-0057); xóa không hoàn tác, chỉ khôi phục từ backup, nên trước lần chạy đầu trên DB có dữ liệu thật chạy tay `SELECT count(*) FROM feedbacks WHERE created_at < now() - interval '30 days'`; một lô treo vì I/O chậm không bị ngắt (`timeBudget` chỉ kiểm giữa các lô, repo không dùng `lock_timeout`).

## Số liệu đúng (ADR-0122 ghi số cũ)

| Mục | ADR-0122 ghi | Thực tế sau V51 |
|---|---|---|
| `SchemaMigrationIT.ERD_TABLES` | 69 → 67 | **88 → 86** (BE-2 đã thêm 19 bảng ở V43–V48) |
| Cột FK trỏ tới `accounts` | không ghi | 77 → **74** (V51), → **73** (V55, mất `feedbacks.resolved_by`) |
| Khóa [CFG] | 85 → 80 | 85 → **80** (−6, +1) |
| Mẫu thông báo | 70 → 63 | 70 → **63** |
| `OpenApiContractParityIT` | 187 → 186 | không đổi ở PR1; **193** ở PR2 (194 − `/resolve`). ADR-0122 ghi 186 vì viết trước khi module `ai` thêm 7 endpoint |

## Hệ quả

- **FE (không sửa ở PR này):** `FE/src/shared/api/mock/identity-admin.mock.test.ts` đọc migration BE và so từng dòng (85 khóa, 70 mẫu); bộ đọc cấm `DELETE FROM notification_templates`. Sau V51 các ca đối chiếu SQL của test này đỏ cho tới T81 (FE-1) — nợ **D065**. Test này không nằm trong CI.
- **V55 cần cả code:** không merge V55 riêng; nó đi cùng PR bỏ trạng thái feedback. Từ commit `8870863` tới PR2, `OpenApiContractParityIT` lệch hợp đồng (code còn `/resolve` và trường `status`): lỗi quy trình "hợp đồng trước, code sau", ghi ở D061.
- **Tham số `status` của `GET /feedbacks`:** client cũ còn gửi thì bị Spring bỏ qua (200, trả toàn bộ), không lỗi; `POST /feedbacks/{id}/resolve` không còn handler (404).
- **File của BE-2 sửa trong PR2:** `customer/PetFixtures`, `report/ReportControllerIT` (chỉ bỏ cột `status` khỏi INSERT): báo Dũng.
- V39, V40 giữ nguyên trong lịch sử Flyway; ADR-0113 không sửa.

## Kiểm chứng

`SchemaMigrationIT` (tập bảng khớp chính xác 86 + 2 bảng hạ tầng, `"51"` trong lịch sử, 74 FK tới `accounts` đều có index, mẫu seed còn 63, test mới `v51DropsComplaintsAndOnlyAddsFeedbackIndexes`), `Tier3Be1SchemaIT` (không còn test khiếu nại), `ConfigKeyTest` (80 khóa, không còn `complaint.*`), `SystemConfigIT.seedMatchesCatalogExactly` (80 dòng seed khớp), `SystemConfigAdminIT.boundariesOfEveryKey` (khóa mới 7–365). PR2: `SchemaMigrationIT.v51And52DropComplaintsAndFeedbackStatus` (cột, ràng buộc, index còn lại của `feedbacks`), `FeedbackReviewIT` (chỉ đọc, không trường trạng thái, `/resolve` 404, `?status=` bị bỏ qua, `xmin` không đổi khi mở), `MyFeedbackIT`, `FeedbackReportIT`, `OpenApiContractParityIT` (193). PR3: `FeedbackCleanupJobTest` (26 ca: mốc cắt, đúng khóa [CFG], chốt chặn 7–365, lô, nghỉ, ngân sách, lỗi, MDC, lịch; xanh), `FeedbackCleanupIT` (biên theo id, feedback của khách khác và bảng khác không mất, [CFG] đọc mỗi lượt, lô, ngân sách, lô lỗi, `SKIP LOCKED`, `EXPLAIN` dùng `ix_feedbacks_created_at`; **đã viết, chưa chạy: Docker tắt**). Mutation (bỏ `WHERE`, `<` → `<=`, `minus` → `plus`, đổi sang `FEEDBACK_MAX_PER_DAY`, bỏ chốt chặn, bỏ `SKIP LOCKED`, bỏ `@Transactional`) và đo p95 khi xóa 100k dòng: **chưa làm**.
