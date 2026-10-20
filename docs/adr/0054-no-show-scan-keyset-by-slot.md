# ADR-0054: ST05 duyệt lịch quá hạn theo keyset `(slot_date, slot_start, id)` thay vì `id`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-19
- **Đánh số lại:** ban đầu là ADR-0049; đổi thành số hiện tại ngày 2026-10-20 khi gộp T25 lên `dev`, vì ADR-0045…0050 (lịch hẹn T16, +4) và ADR-0051 (BE-2) đã chiếm số. Mọi tham chiếu ADR-0047…0052 của T25 trong code / tài liệu đã đổi theo (+5).
- **Thay thế:** mục 9 của ADR-0053 (phần "keyset `id`"; ý giữ nguyên: lịch lỗi không đọc lại trong cùng lượt, ngân sách 60 s).
- **Liên quan:** ADR-0053, nợ D037 (trả trong cùng đợt), index `ix_appointments_status_slot (status, slot_date, slot_start)` của V1.
- **Triển khai:** `AppointmentRepository.{findOverdueBookedIds, findOverdueBookedIdsAfter}`, `AppointmentNoShowService.findOverdueIds` (API giữ nguyên `(cutoff, afterId, limit)`, `afterId = 0` là lô đầu). Không migration. Test: `NoShowIT.overdueQueriesUseTheirIndexesOnRealisticData`, `NoShowIT.batchesWalkInSlotOrderAndVisitEachAppointmentOnce`.

## Bối cảnh

ADR-0053 §9 quét `WHERE status = 'BOOKED' AND (slot_date, slot_start) < mốc AND id > :afterId ORDER BY id LIMIT 100`. EXPLAIN trên 20 000 dòng (quá khứ ở trạng thái cuối, tương lai `BOOKED`, 10 lịch quá hạn — hình dạng khi ST05 chạy đều) cho thấy planner chọn `Index Scan using appointments_pkey` + `Filter`, ước lượng 5 005 dòng khớp (thực tế 10). Planner coi "`BOOKED`" và "đã qua" là độc lập, trong khi chúng nghịch tương quan. Với ước lượng đó, duyệt khóa chính theo thứ tự rồi dừng ở `LIMIT` có vẻ rẻ, nhưng thực tế mỗi lô phải đọc gần hết bảng. Bảng lịch hẹn chỉ tăng, nên chi phí này tăng theo thời gian, mỗi 5 phút.

## Quyết định

1. Sắp theo `slot_date, slot_start, id`, đúng thứ tự của `ix_appointments_status_slot` sau điều kiện bằng `status`. Planner đọc index theo thứ tự và dừng ở `LIMIT`, nên kế hoạch không phụ thuộc ước lượng độ chọn lọc.
2. Keyset là bộ ba của lịch cuối lô trước, lấy bằng subquery theo `afterId` (`(a.slot_date, a.slot_start, a.id) > (SELECT … WHERE p.id = :afterId)`). Job và service vẫn chỉ truyền `id`. Khung của lịch đó không đổi giữa hai lô: lịch quá hạn không đổi khung được (BR-LH-06 chỉ trước giờ hẹn), và đổi trạng thái không đổi khung.
3. Lô đầu và lô sau là hai câu hằng, không dùng `:afterId = 0 OR …`, để kế hoạch không bị kế hoạch chung làm hỏng (cùng lý do với ADR-0038).

## Phương án đã loại

- **`CREATE STATISTICS` (dependencies / MCV) trên `(status, slot_date)`:** cần migration V18 (chưa thống nhất số với BE-2) và vẫn phụ thuộc ước lượng.
- **CTE `MATERIALIZED` gom mọi id quá hạn rồi sắp theo `id`:** mỗi lô đọc toàn bộ tập quá hạn. Khi D033 chưa trả, tập này lớn dần.
- **`ORDER BY id + 0`:** planner phải gom hết rồi sắp, và với ước lượng sai vẫn có thể chọn Seq Scan.

## Hệ quả

- Lịch được xử lý theo giờ hẹn tăng dần, không theo thứ tự tạo. Không ảnh hưởng nghiệp vụ, vì mỗi lịch một transaction và kết quả không phụ thuộc thứ tự.
- Mỗi lô sau thêm một lần đọc theo khóa chính, không đáng kể.
