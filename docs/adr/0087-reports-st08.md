# ADR-0087: Báo cáo UC89 (7 endpoint) và ST08 cảnh báo tồn kho (T44)

- **Trạng thái:** Accepted (G1–G16 chờ PO xác nhận; G11 là câu hỏi mở)
- **Ngày:** 2026-10-30
- **Liên quan:** BR-BC-01…04, BR-KO-07, BR-BH-05, BR-LT-09, BR-DG-03, BR-TB-03, BR-QT-13; UC89, ST08; report-v1 #1–7; 06 §8 Q3 (báo cáo do module sở hữu dữ liệu tính, `report` chỉ ghép), Q4 (`STOCK_ALERT_DIGEST_APP`); ADR-0007 (job), 0038 (SQL động), 0040 (Swagger = hợp đồng), 0064 (ST13, khuôn thông báo cho quản lý).
- **Triển khai (T44):**
  - `sales/{repository/SalesReportRepository, service/SalesReportService}` (`SalesReportApi`), `inventory/repository/InventoryQueryRepository (+lowStock, expiringLots)` + `inventory/service/StockReportService` (`StockReportApi`), `content/{repository/FeedbackReportRepository, service/FeedbackReportService}` (`FeedbackReportApi`).
  - **BE-2 cài thay BE-1** (G1, 06 §6): `appointment/{repository/AppointmentReportRepository, service/AppointmentReportService}` (`AppointmentReportApi`), `visit/{repository/VaccinationReportRepository, service/VaccinationReportService}` (`VaccinationReportApi`). Chỉ file mới, không sửa file nào của BE-1.
  - `report/{controller/ReportController, ReportAccess, service/ReportService, ReportPeriod, dto/*}` (15 record theo schema report-v1).
  - ST08: `inventory/job/{StockAlertDigestJob, StockAlertDigestProperties}`, `inventory/service/{StockAlertDigestService, StockAlertRecipients}`; `V26__stock_alert_digest_template.sql`.

## Quyết định

### 1. Kiến trúc: không bảng, không khóa
Mỗi `*ReportApi` là **một câu SQL gộp nhóm mỗi method** (`NamedParameterJdbcTemplate`), chỉ đọc, không `FOR UPDATE`, tham gia transaction của bên gọi. `ReportService` mở `@Transactional(readOnly = true)` cho mỗi use case. Không có thứ tự khóa mới; không thêm vào bảng lock order của CLAUDE.md. Điều kiện `branchId` chỉ được ghép khi có (không `:x IS NULL OR …`, ADR-0038).

### 2. Bảng quyết định

| ID | Quyết định |
|---|---|
| **G1** | BE-1 chưa cài `AppointmentReportApi`, `VaccinationReportApi` (hạn 27/10, 06 §6) nên BE-2 cài thay bằng file mới trong module của BE-1 (tiền lệ hai chiều: `VisitQueryApiPlaceholder`, `OrderQueryApi.findOrderIdsByVisitIds`). Lúc bắt đầu đã kiểm `origin/manh` và `origin/dev`: chỉ có `api/`, chưa có bean — nếu BE-1 đã viết bản của họ thì **xóa hai lớp của BE-2** (hai bean một interface làm app không khởi động) |
| G2 | Kỳ: `from > to` → 400 `BR-BC-01`; `!to.isBefore(from.plusMonths(N))` → 400 `BR-BC-01` (N = [CFG] `report.max_period_months`). 01/01–31/12 hợp lệ, 01/01–01/01 năm sau bị từ chối. Ngày tương lai cho phép (số liệu 0) |
| G3 | Kỳ → nửa mở `[from 00:00, to + 1 00:00)` giờ Việt Nam đổi sang `Instant` cho cột `TIMESTAMPTZ`; cột `DATE` so `BETWEEN` cả hai đầu |
| G4 | Thứ tự lỗi: 401 → 400 query thiếu / sai kiểu → 403 vai trò → 403 phạm vi (`BranchScope.resolve`) → 400 `BR-BC-01` kỳ. Phạm vi **trước** kỳ để người ngoài phạm vi không dò được kỳ hợp lệ |
| G5 | Chỉ SUPER_MANAGER, BRANCH_MANAGER (ADMIN không: A03 chỉ kỹ thuật). BRANCH_MANAGER bỏ trống `branchId` → chi nhánh mình, gửi chi nhánh khác → 403 |
| G6 | Tỷ lệ: `BigDecimal` scale 4 `HALF_UP`; mẫu số 0 → `0.0000` (FE tự hiển thị "—" khi `dueCount = 0`) |
| G7 | Doanh thu = `SUM(orders.total_amount)` Order `PAID`, `paid_at` trong kỳ, `GROUP BY branch_id, source`; chỉ nhóm có dữ liệu |
| G8 | Thất thu = `status = 'CANCELLED' AND cancel_type = 'UNPAID'`, `cancelled_at` trong kỳ. `CHECKOUT_ABORTED` (T43), `VISIT_CANCELLED`, `RETAIL_UNCLOSED` không tính |
| G9 | Lượt dịch vụ = số **dòng** `SERVICE` (không cộng `quantity`) của Order VISIT có `pending_at` trong kỳ, mọi trạng thái Order (06 Q3: dịch vụ đã làm). Nhóm lấy từ `CatalogQueryApi.findServices` |
| G10 | NO_SHOW: mẫu số = lịch có `slot_date` trong kỳ trừ `cancel_source = 'CLINIC'` (mọi trạng thái khác, kể cả `BOOKED`); NO_SHOW = `status = 'NO_SHOW'`; hủy muộn = `status = 'CANCELLED' AND late_cancel` (nghĩa theo javadoc của BE-1) |
| **G11** | Tái chủng (BR-BC-04): mẫu số = mũi (kể cả `superseded_at` ≠ null) có `next_due_date` trong kỳ trừ thú `deceased_on < next_due_date`; tử số = tồn tại mũi **khác** cùng thú + cùng `vaccine_type_id`, `administered_on > mũi trước` và `≤ next_due_date + grace` ([CFG] `report.revaccination_grace_days`, tiêm sớm cũng tính); gộp theo `branch_id` của mũi trước + loại. **Câu hỏi PO:** mũi có `next_due_date + grace` chưa tới hôm nay vẫn nằm ở mẫu số (đặc tả không loại) nên tỷ lệ kỳ hiện tại bị thấp giả |
| G12 | Dưới ngưỡng: `min_quantity > 0 AND tồn khả dụng < min_quantity`, khả dụng = lô `expiry_date IS NULL OR ≥ hôm nay` (BR-KO-01); chỉ sản phẩm `is_active`; sản phẩm có dòng tồn nhưng không có lô → khả dụng 0 |
| G13 | Lô hết hạn: `quantity > 0 AND expiry_date IS NOT NULL AND expiry_date ≤ hôm nay + withinDays`; `expired = expiry_date < hôm nay` (lô hết hạn đúng hôm nay vẫn dùng được); `withinDays` mặc định [CFG] `stock.expiry_warning_days`, hợp lệ 0–365 |
| G14 | Feedback: `created_at` trong kỳ; `branchId` null (chỉ SUPER_MANAGER không lọc) gồm nhóm `branch_id NULL` (BR-DG-03); có `branchId` thì không; trung bình trên feedback có điểm, scale 2, null nếu không có điểm |
| G15 | ST08: cron `0 0 7 * * *` (env `STOCK_ALERT_DIGEST_CRON`, `"-"` trong test), chỉ chi nhánh `ACTIVE`; chi nhánh có ≥ 1 mục → một `STOCK_ALERT_DIGEST_APP` cho mỗi BRANCH_MANAGER hoạt động, không ai → mọi SUPER_MANAGER (như ST13); không có mục hoặc không có người nhận → không gửi (log). **Không đánh dấu đã gửi**: chạy lại trong ngày thì gửi lại (nợ) |
| G16 | Nội dung ST08 chỉ là số đếm + số ngày + link `/admin/reports`; chi tiết xem báo cáo (5) — cùng `StockReportApi` nên số liệu khớp |

### 3. ST08 (`StockAlertDigestJob`)
Đọc `BranchQueryApi.listActiveBranches()` một lần (readOnly) rồi mỗi chi nhánh một tx `StockAlertDigestService.notifyBranch` (đọc → outbox, không khóa). Một chi nhánh lỗi log `STOCK_ALERT_DIGEST_FAILED` rồi bỏ qua; lỗi cấp lượt không ném; dừng khi hết `time-budget` (60 s). Không phải job quét theo thời gian nên không cần era IT riêng.

### 4. Lệch ranh giới module có chủ đích
`InventoryQueryRepository` đã JOIN `products` (catalog) từ T18; hai câu mới (`lowStock`) dùng tiếp `products.is_active`. `VaccinationReportRepository` JOIN `pets` (customer) chỉ để đọc `deceased_on`: một câu SQL thay vì một lần gọi `PetQueryApi` mỗi mũi (06 Q3 nói "trừ thú mất là chuyện của visit"). Cả hai chỉ đọc; đổi các cột đó ở module kia phải sửa hai lớp này (IT bắt).

### 5. V26
`STOCK_ALERT_DIGEST_APP` (IN_APP), 5 biến bắt buộc đều có trong body (`SchemaMigrationIT`). **V26 / ADR-0087 chưa xác nhận với BE-1.**

## Kiểm chứng
- Unit: `SalesReportServiceTest` (5), `ReportPeriodTest` (6), `ReportServiceTest` (11), `ReportControllerSecurityTest` (2), `StockAlertDigestServiceTest` (4), `StockAlertRecipientsTest` (2), `StockAlertDigestJobTest` (3).
- IT (Postgres thật): `SalesReportIT` (4), `StockReportIT` (6), `FeedbackReportIT` (4, era 2004), `AppointmentReportIT` (3), `VaccinationReportIT` (6), `StockAlertDigestIT` (4, gồm rollback chỉ chi nhánh lỗi bằng trigger tạm), `ReportControllerIT` (11), `SchemaMigrationIT` (V26), `OpenApiContractParityIT` (180 endpoint).
- Mutation (mỗi cái sửa → test chỉ định đỏ → hoàn tác): bỏ `cancel_type = 'UNPAID'` → `lostRevenueCountsOnlyUnpaidCancellations`; `paid_at < :to` → `<=` → `paidAtBoundaryIsHalfOpenInVietnamTime`; bỏ `BranchScope.resolve` → `ReportServiceTest` (3) + `ReportControllerIT.branchManagerWithoutBranchIdSeesOnlyOwnBranch`, `branchManagerOtherBranchIs403`, `scopeIsCheckedBeforeThePeriod`; bỏ điều kiện `deceased_on` → `deceasedBeforeDueDateIsExcluded`; bỏ `p.is_active` → `lowStockIgnoresZeroMinEnoughStockAndInactiveProducts`.

## Nợ để lại
- G1: BE-1 xem lại hoặc nhận lại 2 lớp; G11 hỏi PO; G15 chạy lại gửi lại; V26 / ADR-0087 chờ BE-1.
- Không có index riêng cho `orders.paid_at` / `cancelled_at` / `pending_at` toàn chuỗi (có `ix_orders_branch_paid_at` cho lọc chi nhánh): chấp nhận ở quy mô chuỗi nhỏ, đo `EXPLAIN` khi > 100 000 Order.
- `ReportControllerIT` chèn Order bằng SQL, chưa có ca thu tiền thật → báo cáo; FE `report.mock.ts` chưa đối chiếu BE thật (doanh thu mock khác cách tính). Review cuối chỉ là tự rà soát. Chi tiết `docs/dept/T44-bao-cao-st08.md`.
