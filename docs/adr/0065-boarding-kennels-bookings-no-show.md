# ADR-0065: Lưu trú — chuồng, đặt chỗ theo loại chuồng (sức chứa theo đêm, snapshot giá), gia hạn, hủy, ST05 NO_SHOW, hủy do phòng khám (T31)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Liên quan:**
  - boarding-v1 #1–#12 (UC54, UC58, ST05); 02 BR-LT-01…07, BR-LH-09, 10, BR-KH-05, 08, BR-TK-19, BR-TB-02, BR-CN-03, 04; 03 #6 Đặt chỗ#1, #2, #4, #5, #6, #7 Chuồng#1, #3–#5; 05 §7 `kennels`, `boarding_bookings`
  - ADR-0004 ([CFG]), 0007 (job), 0015 (Clock), 0029 (khóa trước khi đọc), 0037 (mẫu thông báo), 0045 / 0047 (khóa đặt lịch), 0049 (thứ tự lỗi, khóa 1002 cuối), 0052 (hủy do phòng khám), 0053 (ST05), 0055 (hạn chế đặt online), 0057 (giờ vận hành áp dụng từ lần kiểm kế)
  - Nợ D033 (trả)
- **Triển khai:**
  - `boarding/entity/{Kennel, BoardingBooking, KennelStatus, BoardingBookingStatus, BookingChannel, BookingCancelSource, BookingEndReason}`, `boarding/fsm/{KennelTransitionHandler, BoardingBookingTransitionHandler}` (03 §7, §6 đúng bảng).
  - `boarding/service/{KennelService, BoardingCapacity, BoardingRules, BoardingAvailabilityService, BoardingBookingService, BoardingChangeService, BoardingViewService, BoardingAssembler, BoardingQueryService, BoardingNoShowService, BoardingClinicCancellationService, BoardingClinicCancellationNotices, BoardingClinicCause}`, `boarding/job/BoardingNoShowJob`, `boarding/listener/{BoardingPetDeceasedListener, BoardingPetOwnerTransferredListener, BoardingBranchClinicCancellationListener}`, `boarding/controller/{KennelController, BoardingBookingController}`, `boarding/exception/{InvalidBoardingRequestException, BoardingConflictException}`.
  - `catalog`: `CatalogQueryApi.findVaccineTypeNames` (tên loại vaccine cho cảnh báo mũi tiêm).
  - `V20__boarding_no_show_index_templates.sql`: `ix_boarding_bookings_booked_check_in`, mẫu `BOARDING_CANCELLED_BY_CLINIC` (+`_APP`).
  - `BoardingQueryApiPlaceholder` xóa, `BoardingQueryService` thật (trả D033).
  - Test: xem *Kiểm chứng*.

## Bối cảnh

Lưu trú là module tầng 2 chưa có code; `BoardingQueryApi` chỉ có placeholder nên hủy lịch hẹn muộn, ST05 lịch hẹn, đổi giờ / thêm ngày nghỉ của chi nhánh `ACTIVE` và khóa tài khoản khách trả 500 và rollback (D033). T31 cài phần chuồng, đặt chỗ, gia hạn, hủy, ST05 và hủy do phòng khám. Nhận / trả thú, nhật ký, ST15 để T42, T43.

## Quyết định

### 1. Thứ tự khóa (thêm vào bảng lock order của CLAUDE.md)

| Luồng | Thứ tự |
|---|---|
| Đặt chỗ | `pets FOR SHARE` (`PetQueryApi.findPetForShare`) → `branches FOR NO KEY UPDATE` (`BranchApi.lockForBooking`) → INSERT `boarding_bookings` → `document_counters` |
| Gia hạn | đọc khóa ngoại bất biến (`findKeys`) → phân quyền → `pets FOR SHARE` → `branches` → `boarding_bookings FOR NO KEY UPDATE` → kiểm lại dưới khóa |
| Hủy | `boarding_bookings FOR NO KEY UPDATE` → (hủy muộn) `evaluateAfterViolation` (advisory 1002, luôn cuối) |
| Chuồng (tạo, sửa, xóa, bảo trì, hết bảo trì) | phân quyền theo chi nhánh của chuồng → `branches` → `kennels FOR NO KEY UPDATE` |
| ST05 | `boarding_bookings FOR NO KEY UPDATE SKIP LOCKED` → advisory 1002 (qua `evaluateAfterViolation`) |
| Hủy do phòng khám | bên phát đã giữ `pets` / `branches`; listener khóa `boarding_bookings` theo lô id tăng dần |

Không chu trình với appointment (`advisory 1001 → branches → appointments`), customer (`advisory 1001 → pets → listener`), branch (`branches → listener`), sales (`document_counters` cuối). Khóa `branches` chung với đặt lịch hẹn và đổi giờ / ngày nghỉ nên sức chứa không đổi giữa lúc kiểm và lúc ghi, và đổi giờ / thêm ngày nghỉ thấy đặt chỗ vừa commit. Đổi lại mọi lần đặt lịch hẹn và đặt chỗ của **cùng chi nhánh** chờ nhau; chấp nhận ở quy mô phòng khám, giữ transaction ngắn (không I/O ngoài, thông báo qua outbox).

Đặt chỗ chạy READ COMMITTED: kiểm sức chứa sau khi có khóa phải thấy đặt chỗ đã commit. Hai đặt chỗ cùng thú, chồng ngày, **khác chi nhánh** đồng thời không dùng chung khóa chi nhánh: ràng buộc `ex_boarding_bookings_pet_no_overlap` (EXCLUDE) chặn một bên → 409 (không bắt lỗi trong transaction).

### 2. Sức chứa theo đêm (BR-LT-01, 03)
Sức chứa = số chuồng của loại tại chi nhánh không `MAINTENANCE` (bảo trì không có ngày kết thúc). Chiếm chỗ đêm `d`: `BOOKED` / `CHECKED_IN` với `check_in_date ≤ d < check_out_date`, hoặc `OVERDUE` với `today ≤ d ≤ today + boarding.overdue_hold_nights` [CFG]. Một câu `generate_series` LEFT JOIN trên `ix_boarding_bookings_capacity`. `CHECKED_IN` quá ngày trả mà ST15 chưa chạy không còn chiếm chỗ (T43 xử lý).

### 3. Thời điểm vi phạm của lưu trú (D3)
Bảng không có `cancelled_at` / `no_show_at`. `BoardingQueryApi.countViolations(c, since)` đếm hủy muộn / `NO_SHOW` có `updated_at > since` (strict, theo hợp đồng ADR-0049). `updated_at` của dòng ở trạng thái cuối chỉ ghi một lần (không trạng thái nào sau CANCELLED / NO_SHOW). `evaluateAfterViolation` nhận chính `updated_at` đã lưu (đọc lại sau `saveAndFlush`) để thời điểm hạn chế và thời điểm đếm là cùng một giá trị. Thử mutation `save` + `Instant.now(clock)` vẫn xanh: Postgres làm tròn `starts_at` và `updated_at` giống nhau, Hibernate tự flush trước câu native `findUpdatedAt` — rủi ro "đếm lại" không xảy ra, nhưng đọc lại giá trị đã lưu rẻ và loại trừ lệch làm tròn nên giữ.

### 4. Quy tắc nghiệp vụ chưa rõ trong đặc tả (đã duyệt 2026-10-22)

| ID | Quyết định | Lý do |
|---|---|---|
| D1 | Đặt chỗ gọi thật `VaccinationQueryApi.findBoardingVaccineGaps` (cảnh báo); lỗi của nó rollback cả lần đặt | Quy ước không trả giá trị "an toàn" thay placeholder. Hệ quả: tạo đặt chỗ trên app thật 500 tới khi BE-1 giao T28 (23/10) |
| D2 | Làm luôn 3 listener hủy do phòng khám + mẫu thông báo | Không có thì thú mất / chuyển chủ / ngày nghỉ để lại đặt chỗ `BOOKED`, ST05 chuyển `NO_SHOW` và tính vi phạm oan cho khách |
| D3 | Thời điểm vi phạm = `updated_at` (mục 3) | Không thêm cột ngoài ERD |
| D4 | Hủy muộn khi `now ≥ 00:00 giờ VN của ngày nhận − boarding.late_cancel_hours` | 02 nói "trước ngày nhận", không nêu giờ |
| D5 | Lễ tân không đặt chỗ nhận trong ngày sau giờ đóng cửa hôm đó (400 BR-LT-04) | Không thì ST05 chuyển `NO_SHOW` ngay và phạt khách |
| D6 | Ngày nhận và ngày trả phải có khung mở cửa (`openingRangesOn` không rỗng) | BR-LT-04 "chỉ trong giờ mở cửa", gồm ngày nghỉ cố định |
| D7 | Thú chưa có cân nặng vẫn đặt được | Không có dữ liệu để vượt; cân bắt buộc lúc nhận (BR-LT-08, T42) |
| D8 | Xóa chuồng đã có lịch sử lưu trú → 409 | FK `RESTRICT`; chuyển bảo trì thay vì xóa |
| D9 | Gia hạn chỉ lùi ngày trả (`checkOutDate` mới phải sau ngày cũ) → 400 `VALIDATION_FAILED` | 02 chỉ định nghĩa gia hạn; trả sớm xử lý lúc trả thú |
| D10 | `activeOrderId` luôn null tới T43 | Order lưu trú chỉ sinh khi bắt đầu trả thú |
| D11 | Đặt chỗ bị ảnh hưởng khi chuyển bảo trì = `BOOKED` cùng loại có ít nhất một đêm (từ hôm nay) mà số chiếm chỗ > sức chứa mới | `CHECKED_IN` đã có chuồng; không tự hủy |

### 5. ST05
`BoardingNoShowJob` (cron `0 3/5`, phút 3, 8, 13… — lệch ST05 lịch hẹn `0 */5`, ST03 `2/5`, ST13 `4/5`; phút 1, 6, 11… để dành ST15): keyset `(check_in_date, id)` đọc `BOOKED` có ngày nhận ≤ hôm nay (index partial V20) → mỗi đặt chỗ một transaction: `FOR NO KEY UPDATE SKIP LOCKED` → kiểm lại → mốc = giờ đóng cửa muộn nhất của ngày nhận đọc lúc chạy (ADR-0057; ngày không có khung giờ thì 00:00 ngày kế) → `NO_SHOW` → `saveAndFlush` → `evaluateAfterViolation`. Không thông báo (03 #6 "—"). Ngân sách 60 s, log `BOARDING_NO_SHOW_FAILED`, không rethrow. Khách hủy đang giữ khóa dòng thì ST05 bỏ qua (`SKIPPED`), không bao giờ vừa `CANCELLED` vừa `NO_SHOW`.

### 6. Hủy do phòng khám (Đặt chỗ#5)
`BoardingClinicCancellationService` (`MANDATORY`): khóa theo lô 100 id tăng dần, bỏ dòng đã rời `BOOKED`, `CANCELLED` / `CLINIC` / `late_cancel = false`, ghi outbox (BR-TB-02: tài khoản không `PENDING` → IN_APP + EMAIL tới email tài khoản; hồ sơ tại quầy có email → EMAIL; không kênh nào → log `BOARDING_CLINIC_CANCEL_NO_CHANNEL`). Chuyển chủ báo chủ lúc đặt. Listener đồng bộ, không bắt lỗi: lỗi rollback bên phát.

### 7. Phân quyền và lỗi
Khách thấy / thao tác đặt chỗ của hồ sơ mình, người khác → 404 (ADR-0006); nhân viên A05–A08 theo chi nhánh (`BranchScope`), chi nhánh khác → 403. Thứ tự lỗi: 404 / 403 → 409 trạng thái → 400 rule (như ADR-0049). Không audit (BR-QT-15 không gồm chuồng / đặt chỗ; hủy phiên trả thú thuộc T43).

## Kiểm chứng
- Unit: `KennelTransitionHandlerTest`, `BoardingBookingTransitionHandlerTest` (đủ cặp), `BoardingBookingTest`, `BoardingRulesTest`, `KennelServiceTest`, `BoardingBookingServiceTest`, `BoardingChangeServiceTest`, `BoardingNoShowJobTest`, `BoardingClinicCancellationServiceTest`, `CatalogQueryServiceTest`.
- IT (Postgres thật): `BoardingCapacityIT`, `BoardingQueryApiIT`, `KennelIT`, `BoardingAvailabilityIT`, `BoardingBookingIT` (đua đêm cuối, đua với đánh dấu thú mất, đua cùng thú khác chi nhánh), `BoardingBookingViewIT`, `BoardingChangeIT` (biên hủy muộn ±1 µs, tạo hạn chế, rollback bằng trigger tạm, [CFG] đổi không ảnh hưởng đặt chỗ cũ), `BoardingNoShowIT` (EXPLAIN 20 000 dòng), `BoardingClinicCancellationIT`, `SchemaMigrationIT`, `OpenApiContractParityIT` (126 endpoint).
- Mutation: bỏ `lockForBooking` khỏi đặt chỗ → `lastNightRaceOneWinsOneIsBrLt03` đỏ; bỏ `SKIP LOCKED` → `lockedRowIsSkippedThenMarkedOnTheNextRun` đỏ; `save` + `Instant.now(clock)` → xanh (giải thích ở mục 3).

## Nợ để lại
- Đặt chỗ trên app thật 500 tới khi `VaccinationQueryApi` thật (BE-1, T28, D1).
- `activeOrderId` null tới T43 (D10); `CHECKED_IN` quá ngày trả không chiếm chỗ tới ST15 (T43); nhận sớm (T42) phải cập nhật `check_in_date` thành ngày nhận thực tế.
- Xóa chuồng `AVAILABLE` làm giảm sức chứa không cảnh báo (hợp đồng trả 204); UI nên khuyên dùng bảo trì.
- Link thông báo `/boarding-bookings` là đường dẫn FE dự kiến (FE chưa có màn hình, T48).
- V20, ADR-0065 chờ BE-1 xác nhận trước khi gộp.
