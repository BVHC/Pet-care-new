# ADR-0095: Index lượt tiếp nhận theo chi nhánh × ngày check-in (V30), giữ hợp đồng không phân trang (trả D041)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-02
- **Liên quan:** visit-v1 #2; ADR-0060 (mục 3 bộ lọc `date` / `status`, mục *Không đảm bảo* và *Đã cân nhắc* "Migration `(branch_id, checked_in_at)` ngay" — ADR này thực hiện nó), 0094 (cùng khuôn với D032). Nợ: D041 (đóng).
- **Triển khai:** `V30__visits_branch_checked_in_at_index.sql`; `VisitQueueIndexIT.queueQueriesUseTheirIndexesOnRealisticData`, `byDateUsesTheIndexWithAGenericPlan`; `SchemaMigrationIT.visitBranchCheckedInAtIndexCoversEveryStatus`. Không contract, không [CFG], không đổi FE.

Người dùng chốt ngày 2026-11-02: phương án A (chỉ thêm index); đồ án nên không phân trang, hướng hybrid cho hệ thống thật ghi ở cuối ADR; BE-1 lấy V30, BE-2 từ V31.

## Vấn đề

`GET /api/visits?date=…` lọc `branch_id = ? AND checked_in_at ∈ [00:00, 00:00 hôm sau)` giờ VN, có hoặc không có `status`. `status = COMPLETED | CANCELLED` không gửi `date` cũng thành "hôm nay" (ADR-0060 mục 3), cùng khoảng `checked_in_at`. Trước V30 không index nào của `visits` bắt đầu bằng `(branch_id, checked_in_at)`; planner đi `ix_visits_queue` theo `branch_id` rồi lọc, tức đọc cả lịch sử `COMPLETED` / `CANCELLED` của chi nhánh (1000 dòng để lấy 2 trong `VisitQueueIndexIT`, đo 2026-10-20). Kết quả luôn đúng; chi phí tăng tuyến tính theo tuổi chi nhánh.

## Quyết định

1. **Thêm `ix_visits_branch_checked_in_at (branch_id, checked_in_at)`** (V30), không partial: `date` không kèm `status` trả mọi trạng thái của ngày, một partial `COMPLETED | CANCELLED` không phủ được. Thứ tự cột khớp điều kiện bằng + khoảng; không thêm `status` (một ngày của một chi nhánh chỉ vài chục dòng, lọc `status` sau index là đủ).
2. **Giữ năm index cũ** của `visits` (`ix_visits_queue`, `ix_visits_assignee_status`, `ix_visits_pet_checked_in_at`, `ix_visits_checked_in_by`, `ix_visits_customer_id`). Hàng đợi dở, hàng đợi của tôi và ST04 không đổi plan.
3. **Không đổi hợp đồng #2:** vẫn là mảng, không phân trang (ADR-0060 mục 9). Chấp nhận response lớn theo số lượt trong ngày (vài chục).

## Kiểm chứng

- `queueQueriesUseTheirIndexesOnRealisticData`: 20 000 lượt, `ANALYZE`; câu theo ngày (có và không có `status`) dùng `ix_visits_branch_checked_in_at`, không Seq Scan, không `Filter` theo `checked_in_at`; ba câu cũ vẫn dùng đúng index của chúng.
- `byDateUsesTheIndexWithAGenericPlan`: `PREPARE` + `SET LOCAL plan_cache_mode = force_generic_plan` (pgjdbc có thể chuyển sang generic plan sau 5 lần thực thi) vẫn đi index mới.
- `SchemaMigrationIT`: V30 có trong lịch sử; index không partial, đúng cột; năm index cũ còn nguyên.
- Mutation (bỏ `CREATE INDEX` khỏi V30, chạy 2026-11-02): cả hai test của `VisitQueueIndexIT` và `visitBranchCheckedInAtIndexCoversEveryStatus` đỏ.

## Hệ quả

- Mỗi lần check-in ghi thêm một mục index. `checked_in_at` là `updatable = false` (`Visit.java:87`), nên đổi `status`, gán / gán lại không chạm index này.
- `CREATE INDEX` khóa ghi `visits` trong lúc xây; bảng nhỏ. Flyway chạy trong transaction nên không dùng `CONCURRENTLY`.
- Planner có thể chọn index mới cho câu khác có `branch_id`; `VisitQueueIndexIT` vẫn pin plan của ba câu cũ nên đổi plan sẽ báo đỏ.

## Hướng cho hệ thống thật (hybrid), chưa làm

Xem lại khi một chi nhánh vượt vài trăm nghìn lượt hoặc p95 của #2 vượt ~1 giây:

- **Phân trang keyset** trên `(checked_in_at, id)` chỉ cho xem lịch sử (có `date` hoặc `status` kết thúc); hàng đợi dở giữ nguyên dạng mảng vì đã có `ix_visits_queue`. Cần đổi hợp đồng, generator, FE.
- **Chia lịch sử cũ** (phân vùng theo tháng hoặc bảng lưu trữ) để index nóng chỉ chứa dữ liệu gần; cần đổi chiến lược khóa ngoại `RESTRICT` và báo cáo.
