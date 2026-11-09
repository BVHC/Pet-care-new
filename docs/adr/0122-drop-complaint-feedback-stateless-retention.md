# ADR-0122: Bỏ khiếu nại; feedback không trạng thái và tự xóa sau 30 ngày

- **Trạng thái:** Accepted (Mạnh quyết ngày 2026-11-08 trong phiên đặc tả; Dũng (BE-2) đã chốt phần sửa `feedbacks` và `content-v1` cùng ngày, xác nhận bằng lời qua Mạnh, chưa có văn bản của Dũng)
- **Ngày:** 2026-11-08
- **Liên quan:** `docs/09` §D (đã thay bằng mục "Bỏ"), `docs/02` §16 (BR-DG-01…05), `docs/03` #26, `docs/05` `feedbacks`, `docs/api/content-v1`, ADR-0071 (feedback), ADR-0113 (phần khiếu nại, V39–V40), ADR-0112 (mẫu job dọn dẹp), ADR-0007 (job)

## Bối cảnh

T54 duyệt module khiếu nại riêng (UC85, UC86, BR-DG-05…09, FSM #26, hai bảng, `complaint-v1` 12 endpoint, ST16 leo thang, ST17 tự đóng, 6 khóa [CFG], 7 mẫu thông báo). Khi rà lại cho phạm vi đồ án (không có người dùng thật):

- petcare.vn (bệnh viện thú y tham chiếu) không công bố quy trình khiếu nại, đổi trả hay cam kết thời hạn; chỉ có hotline, email, Facebook. Mốc duy nhất tìm được là Vinmec (cơ sở y tế, không phải thú y): phản hồi 24 giờ, xử lý không quá 3 ngày làm việc (dịch vụ) / 5 (chuyên môn), hạn gửi 1 tháng, không leo thang nội bộ.
- Khiếu nại một chiều (khách gửi, quản lý đọc) trùng gần hết với feedback đã làm (UC83–84, `content-v1` #18–22): cùng người gửi, cùng phạm vi xem, cùng giới hạn theo ngày.
- Bản rút gọn của khiếu nại vẫn để lại nhiều lỗ hổng (không chuyển giao được, mở lại sau tự đóng không dùng được với mặc định 7 / 7 ngày, xung đột lợi ích khi khiếu nại về chính người xử lý).

## Quyết định

1. **Bỏ khiếu nại.** UC85, UC86, ST16, ST17, FSM #26, BR-DG-05…09 (bản khiếu nại), module `complaint`, `complaint-v1` (12 endpoint), bảng `complaints` và `complaint_messages`, 6 khóa `complaint.*`, 7 mẫu `COMPLAINT_*`, trường `complaintCount` của `MergePreview`. Giữ nguyên các mã UC / ST / FSM (gạch bỏ) để không lệch số.
2. **Feedback không còn trạng thái.** Bỏ BR-DG-04, trạng thái `NEW` / `SEEN` / `RESOLVED`, endpoint `POST /feedbacks/{id}/resolve` (#22), tham số `status`, các trường `status`, `seenAt`, `resolvedBy`, `resolvedAt`, `resolutionNote`, giả định A2 (tự chuyển `SEEN` khi mở). `GET /feedbacks/{id}` hết tác dụng phụ. BRANCH_MANAGER và SUPER_MANAGER chỉ đọc.
3. **Giới hạn gửi giữ nguyên** 5 feedback mỗi khách mỗi ngày (BR-DG-01, `feedback.max_per_day`).
4. **BR-DG-05 mới, lưu giữ 30 ngày.** Feedback quá `feedback.retention_days` [CFG] (30; khoảng 7–365) kể từ `created_at` bị xóa cứng bởi **ST24** `FeedbackCleanupJob`, chạy hằng ngày. Đây là ngoại lệ mới của nguyên tắc "không xóa cứng". Báo cáo feedback (BR-BC-03 mục 6) chỉ có số liệu trong thời gian lưu giữ, dù kỳ báo cáo tối đa 12 tháng.

## Hệ quả

| Phần | Thay đổi khi cài đặt (T70 đổi nội dung) |
|---|---|
| Migration **V51** (V39, V40 đã áp dụng, không sửa) | `DROP TABLE complaint_messages, complaints`; xóa 6 khóa `complaint.*` và 7 mẫu `COMPLAINT_*`; thêm khóa `feedback.retention_days`; `feedbacks` bỏ `status`, `seen_at`, `resolved_by`, `resolved_at`, `resolution_note`, CHECK theo status và index `(branch_id, status)`; thêm index `(branch_id, created_at)` và `(created_at)` |
| Số liệu | [CFG] 85 → 80 (−6, +1); mẫu thông báo 70 → 63; `SchemaMigrationIT.ERD_TABLES` 69 → 67; `content-v1` 22 → 21 endpoint nên `OpenApiContractParityIT` 187 → 186; tầng 3 từ 139 → 127 endpoint |
| `ConfigKey` / `NotificationTemplateCode` | Xóa 6 hằng `COMPLAINT_*` (khóa) và các hằng mã mẫu cùng V51; `CustomerMergedEvent` chỉ còn feedback |
| Job ST24 | `module/content/job/FeedbackCleanupJob` theo khuôn `NotificationCleanupJob` (ADR-0112): `@Scheduled(cron = "${app.jobs.feedback-cleanup.cron}")` giờ 03:30:31 (chưa job nào dùng), cron `"-"` ở `application-test.yml`, xóa theo lô khóa id tăng dần, ≤ 60 giây, bắt lỗi ghi `FEEDBACK_CLEANUP_FAILED`. IT dùng era **cũ hơn mọi era khác** (kế tiếp 1982) vì quét không có cận dưới |
| FE | Gỡ trang, hook, mock khiếu nại (`complaint.api.ts`, `ComplaintsPage`, `CreateComplaintDialog`...); màn feedback của quản lý bỏ nút "đánh dấu đã xử lý" và bộ lọc trạng thái |
| Chủ sở hữu | `feedbacks` và `content-v1` của BE-2: Dũng đã chốt (2026-11-08); chưa phân công ai viết code, BE-1 đề xuất tự làm vì nhỏ (T70 đổi nội dung) |

## Phương án đã loại

- **Giữ khiếu nại riêng, rút gọn** (không trạng thái, xóa sau 30 ngày): gần như bản sao của feedback, thêm module, bảng, endpoint và FE mà không thêm hành vi nào.
- **Giữ khiếu nại đầy đủ như T54:** nhiều lỗ hổng thiết kế (ở trên) và quá nặng cho đồ án.
- **Giữ feedback với vòng đời `NEW` / `SEEN` / `RESOLVED`:** trái yêu cầu "không trạng thái".

## Nợ ghi nhận

Code chưa theo quyết định này (feedback còn trạng thái, V39–V40 còn bảng, khóa và mẫu khiếu nại, FE còn trang khiếu nại): D061 trong `docs/dept`.
