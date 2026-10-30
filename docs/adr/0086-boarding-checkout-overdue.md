# ADR-0086: Lưu trú — bắt đầu trả thú, giao thú, hủy phiên trả, thú mất, ST15 quá hạn đón (T43)

- **Trạng thái:** Accepted (F1–F13 chờ PO xác nhận)
- **Ngày:** 2026-10-30
- **Liên quan:** BR-LT-03, 04, 09, 10, 12, BR-BH-01, 05, 06, BR-TB-02, 05, BR-QT-15, BR-KH-05; 03 Đặt chỗ#7, #9, #10, #11, Chuồng#3, Order#3, #9; boarding-v1 #16–18; sales-v1 #9; ADR-0007 (job), 0049 (thứ tự lỗi), 0064 (thu ngân), 0065 (lưu trú), 0079 (nghĩa vụ bên gọi `CareTaskApi`), 0085 (nhận thú). Nợ: T31-2, T31-3 (đã trả).
- **Triển khai (T43):** `boarding/service/{BoardingCheckoutService, BoardingOverdueService, BoardingOverdueNotices, BoardingRecipients}`, `boarding/job/{BoardingOverdueJob, BoardingOverdueProperties}`, `controller/BoardingBookingController (+3)`, `BoardingCapacity.shortageBookings` (tách từ `KennelService`), `BoardingAssembler` (`activeOrderId`); `sales/service/BoardingOrderService` (cài `BoardingOrderApi`), `OrderCancellationService.abortCheckout` + `OrderController (+1)`; `SalesOrder.openForBoarding`, `OrderLine.repriceBoarding`, `BoardingBooking.{checkOut, markOverdue, overdueNoticeSent, managerNotified}`, `Kennel.release`; `V25` (3 index + 4 mẫu thông báo). Đổi chữ ký `BoardingOrderApi` (chỉ boarding gọi).

## Quyết định

### 1. Thứ tự khóa (thêm vào bảng lock order của CLAUDE.md)

| Luồng | Thứ tự |
|---|---|
| #16 bắt đầu trả thú | `findKeys` → phân quyền → `boarding_bookings FOR NO KEY UPDATE` → `BoardingOrderApi.openOrReuse`: `orders` (OPEN / PENDING của đặt chỗ) `FOR NO KEY UPDATE` → **kiểm lại `PAID`** → sửa dòng / INSERT → `document_counters` |
| #17 giao thú | `boarding_bookings FOR NO KEY UPDATE` → `hasPaidOrder` (đọc) → `kennels FOR NO KEY UPDATE` |
| #18 thú mất | `boarding_bookings FOR NO KEY UPDATE` → `kennels FOR NO KEY UPDATE` → `openOrReuse` (`orders` → `document_counters`) |
| sales #9 | `orders FOR NO KEY UPDATE` → `BoardingQueryApi.findBooking` (đọc không khóa, như #10) → audit |
| ST15 | `pet_id` (đọc không khóa) → `pets FOR SHARE` → `boarding_bookings FOR NO KEY UPDATE SKIP LOCKED` → kiểm lại → UPDATE → outbox → `CareTaskApi.create` (`care_tasks` cuối) |

Không chu trình: thu tiền `shift → orders → lots → document_counters` không chạm `boarding_bookings`; nhận thú / gia hạn `pets → branches → bookings → kennels` cùng tiền tố với ST15 và #17 / #18 (chuồng luôn cuối); care không bao giờ khóa `pets` (ADR-0079).

**Điểm dễ sai nhất — thu hai lần.** Thu tiền chuyển Order `PENDING` → `PAID`. Nếu lúc đó lễ tân bấm "bắt đầu trả" lần nữa, câu khóa Order chưa kết thúc chờ khóa; khi thu tiền commit, Postgres đánh giá lại `WHERE status IN ('OPEN','PENDING')` và trả rỗng. Không kiểm lại thì sinh Order thứ hai (và `uq_orders_boarding_unfinished` không cản vì Order cũ đã `PAID`). `BoardingOrderService.openOrReuse` luôn kiểm `existsByBoardingBookingIdAndStatus(PAID)` **sau** khóa → 409.

### 2. Quy tắc

- **Số đêm (F1):** `max(1, ngày trả − ngày nhận thực tế + (giờ VN > boarding.checkout_time ? 1 : 0))`; đúng giờ quy định chưa cộng. Giá = giá đêm snapshot của đặt chỗ (BR-LT-02). Order dùng lại thì dòng `BOARDING` được tính lại số đêm, đơn giá giữ nguyên.
- **#16:** 409 không `CHECKED_IN` / `OVERDUE` → 400 `BR-LT-04` ngoài giờ mở cửa → 409 đã có Order `PAID` (F2) → mở / dùng lại Order.
- **#17:** 409 trạng thái → 400 `BR-LT-09` Order chưa `PAID` → giao: đặt chỗ `CHECKED_OUT/RETURNED`, chuồng `AVAILABLE`. Không kiểm giờ mở cửa (F5) và không thu thêm nếu đã thu trước 12:00 (F3).
- **#18:** `note` bắt buộc ≤ 2000 (F12). Số đêm `max(1, hôm nay − ngày nhận)`, không đêm trễ (F4). Đã có Order `PAID` → trả lại Order đó, không sinh mới; đã có `PENDING` → dùng lại. Sau đó `mark-deceased` chạy được (`isPetInStay` false).
- **sales #9 (F11):** chỉ RECEPTIONIST của chi nhánh Order. Thứ tự: khóa Order (404) → chi nhánh (403) → không phải Order BOARDING `PENDING` (409) → thiếu lý do (400 `BR-LT-09`) → thú đã rời chuồng (400 `BR-LT-09`) → ghi `CANCELLED/CHECKOUT_ABORTED` → audit `ORDER_CHECKOUT_ABORTED`. Không tính thất thu; đặt chỗ giữ trạng thái; lần trả sau sinh Order mới.
- **`activeOrderId` (F13):** Order OPEN / PENDING của đặt chỗ, nạp theo lô ≤ 100 (`BoardingOrderApi.findActiveOrderIds`).

### 3. ST15 (`BoardingOverdueJob`)
- Cron `0 1/5 * * * *` (phút 1, 6, 11… đã giữ chỗ từ ADR-0065), env `BOARDING_OVERDUE_CRON`, `"-"` trong test. Hai lượt trong một lần chạy, chung ngân sách 60 s: (1) `CHECKED_IN` có `check_out_date ≤ hôm nay`, keyset `(check_out_date, id)` theo `ix_boarding_bookings_checked_in_due` → `OVERDUE`; (2) `OVERDUE`, keyset `id` theo `ix_boarding_bookings_overdue` → theo dõi. Mỗi đặt chỗ một tx; lỗi log `BOARDING_OVERDUE_FAILED`, không rethrow.
- **Mốc (F8):** `overdue_since` = `boarding.checkout_time` của **ngày trả dự kiến**, không phải giờ job chạy → kết quả không phụ thuộc độ trễ job; job tắt nhiều ngày rồi chạy lại thu về đúng trong một lượt (chuyển `OVERDUE` + Care Task + báo quản lý nếu đã đủ ngày). Care Task khi `now ≥ overdue_since + overdue_care_task_days`, `dueDate` = ngày đó; báo quản lý một lần khi `≥ overdue_manager_alert_days` (`manager_notified_at`).
- **Thông báo khách (F7):** lần 1 khi chuyển `OVERDUE`; sau đó mỗi ngày một lần ở lượt đầu tiên từ giờ trả quy định (`last_overdue_notice_on`). Người nhận theo BR-TB-02; hồ sơ không kênh → log `BOARDING_OVERDUE_NO_CHANNEL`, vẫn `OVERDUE` và vẫn có Care Task.
- **Cảnh báo thiếu chỗ (F9):** chỉ lúc chuyển `OVERDUE`: `BoardingCapacity.shortageBookings(chi nhánh, loại, hôm nay, hôm nay + capacity_warning_days)` không rỗng → một `BOARDING_CAPACITY_WARNING_APP` cho mỗi lễ tân hoạt động (không ai → log).
- **F10:** đặt chỗ đã có Order `PAID` bị bỏ qua (`PAID_SKIPPED`), khách đang ở quầy không bị báo quá hạn.
- **Care Task (F6):** `CareTaskApi.create(PICKUP_OVERDUE)` gọi mỗi lượt cho đặt chỗ `OVERDUE` đã đủ ngày (`INSERT … ON CONFLICT DO NOTHING`, trả id cũ dù task đã đóng). Task không tự hủy khi đã giao thú / thú mất (BR-TB-05 không liệt kê) — lễ tân hủy tay.

### 4. V25 và mẫu thông báo
3 index (`ix_boarding_bookings_checked_in_due`, `ix_boarding_bookings_overdue`, `ix_orders_boarding_booking_id`) + 4 mẫu (`BOARDING_OVERDUE(+_APP)`, `BOARDING_OVERDUE_MANAGER_APP`, `BOARDING_CAPACITY_WARNING_APP`), khung V20 / V24. `SchemaMigrationIT` bắt buộc biến bắt buộc nằm trong **body** (không chỉ subject). V25 chưa xác nhận với BE-1.

## Kiểm chứng
- Unit: `BoardingOrderServiceTest`, `BoardingCheckoutServiceTest`, `BoardingOverdueServiceTest`, `BoardingOverdueJobTest`, `OrderCancellationServiceTest` (+ abort), `BoardingRulesTest` (số đêm, mốc 12:00), `BoardingBookingTest`, `SalesOrderTest`, `OrderControllerSecurityTest` (thêm `abortCheckout` → lễ tân).
- IT (Postgres thật): `BoardingCheckoutIT` (17: số đêm, dùng lại Order, thu tiền qua `POST /api/payments` rồi giao thú, hủy phiên + Order mới, thú mất 3 trường hợp + `mark-deceased`, quyền, **đua thu tiền vs bắt đầu trả**, rollback bằng trigger tạm), `BoardingOverdueIT` (12, era **1988**: mốc 12:00, không gửi trùng, Care Task đúng một dòng, báo quản lý một lần, `PAID` bỏ qua, `SKIP LOCKED`, một đặt chỗ lỗi không chặn cả lô, cảnh báo thiếu chỗ, hồ sơ không email, job tắt lâu, đổi [CFG] giữa ngày, `EXPLAIN` dùng index trên 20 000 dòng), `SchemaMigrationIT` (V25), `OpenApiContractParityIT` (173 endpoint).
- Mutation: bỏ `existsByBoardingBookingIdAndStatus(PAID)` sau khóa → `startCheckoutAfterConcurrentPaymentIsConflictAndCreatesNoSecondOrder` đỏ (200 thay vì 409); bỏ `SKIP LOCKED` ở `lockCheckedInForOverdue` → `lockedBookingIsSkippedThenMarkedOnTheNextRun` đỏ; bỏ `managerNotifiedAt == null` → `managerIsNotifiedOnceAfterSevenDays` đỏ.

## Nợ để lại
- F1–F13 chờ PO (đặc biệt F6); V25 / ADR-0086 chờ BE-1; `CareTaskApi.create` gọi mỗi lượt cho mọi đặt chỗ `OVERDUE` đã đủ ngày (một INSERT rẻ mỗi 5 phút); mock FE T48 #16–18 / sales #9 chưa đối chiếu BE thật; review cuối chỉ là tự rà soát. Chi tiết `docs/dept/T43-luu-tru-tra-thu.md`.
