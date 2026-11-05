# ADR-0105: Chấp nhận chưa thêm index cho báo cáo / danh sách / digest trên `orders` — đã đo 500 000 Order (T44-6, T29-D04, T30-6)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-05
- **Liên quan:** UC66, UC70, UC89; report-v1 #1–3, sales-v1 #2, ST13 (digest Order `PENDING` quá hạn); ADR-0044, 0051, 0064, 0087; nợ `T44-6`, `T29-D04`, `T30-6`.
- **Triển khai:** không có. Không code, không migration, không contract, không [CFG].

Ba khoản nợ cùng một điều kiện: "đo `EXPLAIN` khi `orders` lớn rồi mới quyết định thêm index". Ghi nhận kết quả đo để đóng nợ bằng số liệu thay vì phỏng đoán.

## Cách đo

Postgres 17 (Testcontainers), một transaction được hoàn tác, `SET LOCAL session_replication_role = replica` (bỏ kiểm khóa ngoại để dựng nhanh), rồi `ANALYZE` và `EXPLAIN (ANALYZE, BUFFERS)` từng câu SQL **giống hệt** repository:

- 500 000 Order, 10 chi nhánh (50 000 / chi nhánh), rải đều 12 tháng gần nhất (`created_at = now() − (g × 7919 mod 525 600) phút`);
- nguồn: 1/3 `VISIT`, 2/3 `RETAIL`; trạng thái theo `g mod 100`: 86 % `PAID`, 4 % `CANCELLED` (một nửa `UNPAID`), 6 % `PENDING`, 4 % `OPEN`;
- 333 000 dòng `SERVICE` (hai dòng mỗi Order `VISIT`);
- chỉ mục hiện có: `ix_orders_branch_status_pending_at`, `ix_orders_branch_paid_at`, `ix_orders_customer_status` và các chỉ mục khóa ngoại (V1, V7, V19, V25).

## Kết quả

| Truy vấn | Thời gian | Kế hoạch |
|---|---|---|
| Doanh thu 12 tháng, toàn chuỗi (`SalesReportRepository.revenue`) | 101 ms | quét tuần tự song song + gộp |
| Doanh thu 1 tháng, toàn chuỗi | 58 ms | như trên |
| Doanh thu 1 tháng, một chi nhánh | 15 ms | `ix_orders_branch_paid_at` |
| Thất thu 12 tháng, toàn chuỗi (`lostRevenue`) | 24 ms | bitmap `ix_orders_customer_status` rồi lọc |
| Thất thu 1 tháng, một chi nhánh | 0,06 ms | `ix_orders_branch_status_pending_at` |
| Số dịch vụ 12 tháng, toàn chuỗi (`serviceCounts`) | 120 ms | quét tuần tự song song + join dòng |
| Số dịch vụ 1 tháng, một chi nhánh | 19 ms | |
| Danh sách Order theo chi nhánh + khoảng ngày, mới nhất 20 dòng (#2) | 34 ms | bitmap `ix_orders_branch_paid_at` (chỉ phần `branch_id`), lọc `created_at`, top-N |
| Danh sách theo chi nhánh, không lọc, mới nhất 20 dòng | 25 ms | quét song song + top-N |
| Danh sách `pendingOverdue` theo chi nhánh | 0,09 ms | `ix_orders_branch_status_pending_at` |
| Digest ST13 `findOverdueByBranch` | 28 ms | bitmap + gộp |
| Đếm cho phân trang (chi nhánh + khoảng ngày) | 24 ms | |

Câu chậm nhất (báo cáo toàn chuỗi 12 tháng) chỉ chạy khi quản lý mở UC89 hoặc một lần mỗi đêm (digest), không nằm trên đường nóng.

## Quyết định

1. **Không thêm index lúc này.** Mọi truy vấn dưới 125 ms ở 500 000 Order (khoảng 10 chi nhánh × 50 000 Order, tương đương nhiều năm hoạt động của chuỗi phòng khám). Thêm index tốn chi phí ghi trên bảng nóng nhất của hệ thống (mỗi Order, mỗi dòng, mỗi lần đổi trạng thái) để cứu truy vấn đã nhanh.
2. **Ngưỡng đo lại:** khi `orders` quá 5 000 000 dòng, hoặc khi một câu trên vượt 500 ms ở dữ liệu thật (lấy `EXPLAIN (ANALYZE, BUFFERS)` từ production, không từ dữ liệu giả này).
3. **Ứng viên index khi vượt ngưỡng** (chưa tạo, cần đo lại trước):
   - danh sách mặc định / lọc ngày: `(branch_id, created_at DESC, id DESC)` — hiện planner dùng `ix_orders_branch_paid_at` chỉ cho `branch_id` rồi lọc `created_at`;
   - doanh thu toàn chuỗi: index một phần `(paid_at) WHERE status = 'PAID'`;
   - số dịch vụ toàn chuỗi: `(pending_at) WHERE source = 'VISIT'`.
4. **Giới hạn của phép đo:** dữ liệu phân bố đều, một khách duy nhất, không đồng thời với ghi. Dữ liệu thật lệch (một chi nhánh lớn hơn nhiều) làm danh sách của chi nhánh đó chậm hơn tỷ lệ thuận; không đổi quyết định ở quy mô hiện tại.

## Hệ quả

- `T44-6`, `T29-D04`, `T30-6` đóng với ADR này; thay vì "chờ dữ liệu" nợ nay có ngưỡng và ứng viên cụ thể.
- Câu SQL báo cáo / danh sách đổi nhiều (ví dụ thêm điều kiện lọc) cần đo lại bằng cùng cách.
