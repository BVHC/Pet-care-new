# ADR-0094: Index lịch hẹn theo chi nhánh × ngày (V29), chấp nhận OFFSET và `count(*)` chính xác của #3 (trả D032)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-02
- **Liên quan:** appointment-v1 #3; ADR-0048 (xem lịch hẹn: mục "không thêm index" và dòng EXPLAIN bị thay), 0049 (đổi khung làm lịch đổi vị trí trong thứ tự sắp xếp), 0053, 0056 (ST05, ST03 dùng `ix_appointments_status_slot`), 0092 (cùng kiểu quyết định "chấp nhận"). Nợ: D032 (đóng: index đã trả, OFFSET chấp nhận). D020 giữ nguyên.
- **Triển khai:** `V29__appointment_branch_slot_index.sql`; `AppointmentViewIT.hotQueriesUseAnIndex`, `branchDayListUsesTheIndexWithAGenericPlan`; `SchemaMigrationIT.appointmentBranchDayIndexCoversEveryStatus`. Không contract, không [CFG], không đổi FE.

Người dùng chốt ngày 2026-11-02: chỉ thêm index; giữ OFFSET và `count(*)` chính xác; không keyset, không hybrid.

## Vấn đề

`GET /api/appointments` của nhân viên luôn lọc `branch_id`, thường kèm `date` hoặc `from` / `to`, có hoặc không có `status`, sắp xếp `slot_date, slot_start, id`. Trước V29 không index nào bắt đầu bằng `(branch_id, slot_date)` cho mọi trạng thái: `ix_appointments_quota` là partial (không có `CANCELLED`, `NO_SHOW`) và cột thứ hai là `service_group`; `ix_appointments_status_slot` bắt đầu bằng `status`.

Plan trước V29 (20 000 dòng, `ANALYZE`, chi nhánh có vài chục dòng mỗi ngày):

```
Limit (cost=326.20..326.23)
  -> Sort  Sort Key: slot_start, id
       -> Index Scan using ix_appointments_status_slot  (cost=0.29..325.93)
            Index Cond: (slot_date = '2024-06-01'::date)
            Filter: (branch_id = 1)
```

Postgres đọc phần index của **mọi chi nhánh** trong ngày rồi lọc `branch_id`: chi phí tăng theo số chi nhánh × số lịch mỗi ngày.

## Quyết định

1. **Thêm `ix_appointments_branch_slot (branch_id, slot_date, slot_start)`** (V29), không partial để phủ mọi trạng thái. Thứ tự cột khớp `ORDER BY slot_date, slot_start, id`; `id` còn lại do incremental sort (mỗi khung chỉ vài dòng), nên không thêm `id` vào index (đỡ 8 byte mỗi mục).
2. **Giữ hai index cũ.** `ix_appointments_quota` đếm quota (BR-LH-03, có `service_group` và điều kiện trạng thái); `ix_appointments_status_slot` phục vụ ST03, ST05 và câu "BOOKED của chi nhánh từ ngày" (`NoShowIT`, `AppointmentReminderIT` assert tên nó). Chỉ thêm, không bỏ.
3. **Chấp nhận OFFSET và `count(*)` chính xác.** Không đổi hợp đồng #3.
   - Drift giữa hai request (lặp hoặc sót một dòng khi có người đổi khung, ADR-0049) **không phải điểm yếu riêng của OFFSET**: đổi khung làm khóa sắp xếp `(slot_date, slot_start, id)` của chính dòng đó đổi, nên keyset cũng lặp hoặc sót dòng đã dời khỏi / vào vùng đã xem. Keyset chỉ loại được việc dịch vị trí do chèn / xóa dòng ở phía trước, và lễ tân nhìn lại giá trị cũ là chấp nhận được.
   - Lợi ích còn lại của keyset là chi phí trang sâu. Danh sách theo ngày của một chi nhánh chỉ vài trăm dòng, nên không đáng đổi hợp đồng và màn hình của FE-1.
   - Mỗi request vẫn tự nhất quán nhờ `REPEATABLE READ`.

## Kiểm chứng

- `hotQueriesUseAnIndex`: câu cả ngày, khoảng ngày và `count(*)` theo khoảng ngày của chi nhánh dùng `ix_appointments_branch_slot`, `branch_id` và `slot_date` đều là Index Cond, không `Filter: (branch_id`, không Seq Scan. Danh sách của khách và danh sách chờ check-in vẫn không Seq Scan.
- `branchDayListUsesTheIndexWithAGenericPlan`: `PREPARE` + `SET LOCAL plan_cache_mode = force_generic_plan` (kế hoạch pgjdbc có thể chuyển sang sau 5 lần thực thi) vẫn đi index mới.
- `SchemaMigrationIT`: V29 có trong lịch sử; index không partial, đúng cột; hai index cũ còn nguyên.
- Mutation (bỏ `CREATE INDEX` khỏi V29): cả ba test trên đỏ, plan quay về dạng `Filter: (branch_id = …)` ở trên.

## Hệ quả

- Mỗi INSERT hoặc đổi `branch_id` / `slot_date` / `slot_start` của `appointments` ghi thêm một mục index; bảng lịch hẹn ghi vừa phải nên chấp nhận.
- `count(*)` không lọc ngày vẫn đọc toàn bộ index của **một** chi nhánh (không còn cả bảng). Trang sâu vẫn đọc rồi bỏ `offset` dòng.
- Planner có thể chọn index mới cho các câu khác có `branch_id` (ví dụ danh sách chờ check-in); các assert hiện có chỉ cấm Seq Scan hoặc nêu tên `ix_appointments_status_slot` cho câu **không** có `branch_id`.
- `CREATE INDEX` khóa ghi bảng trong lúc xây; bảng hiện nhỏ. Flyway chạy trong transaction nên không dùng `CONCURRENTLY`.

## Xem lại khi

p95 của #3 vượt ~1 giây, hoặc một chi nhánh vượt vài trăm nghìn lịch hẹn. Khi đó cân nhắc giới hạn khoảng ngày mặc định (đổi hợp đồng) hoặc bỏ `totalElements` chính xác (như D020).
