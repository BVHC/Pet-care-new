# ADR-0085: Lưu trú — nhận thú, hủy vì hết chuồng, nhật ký chăm sóc, báo bất thường (T42)

- **Trạng thái:** Accepted (E1–E12 chờ PO xác nhận; xem Nợ)
- **Ngày:** 2026-10-29
- **Liên quan:** BR-LT-02, 03, 04, 05, 06, 08, 11, 12, BR-KH-04, BR-TB-02; 03 Đặt chỗ#3, #5, #8, Chuồng#2; boarding-v1 #13–15, #19–22; ADR-0065 (nền lưu trú), 0049 (thứ tự lỗi), 0072 (`VaccinationQueryApi`). Nợ: T31-4 (đã trả).
- **Triển khai (T42):** `boarding/service/{BoardingCheckInService, CareLogService, BoardingManagerNotices, CareLogAbnormalNotices}`, `controller/{BoardingBookingController (+3), CareLogController}`, entity `BoardingCheckIn`, `CareLog`, `CareLogAddendum`, repository tương ứng, `V24` (3 mẫu thông báo + 2 index). Không đổi interface chéo module nào.

## Quyết định

### 1. Thứ tự khóa (thêm vào bảng lock order của CLAUDE.md)

| Luồng | Thứ tự |
|---|---|
| #14 nhận thú | `findKeys` (không khóa) → phân quyền → `pets FOR SHARE` → `branches` (`lockForBooking`) → `boarding_bookings FOR NO KEY UPDATE` → `kennels FOR NO KEY UPDATE` → INSERT `weight_records` (`PetApi.recordWeight`, giữ sẵn `pets FOR SHARE`) → INSERT `boarding_check_ins` |
| #15 hết chuồng | phân quyền → `branches` → `boarding_bookings FOR NO KEY UPDATE` → đếm chuồng `AVAILABLE` → outbox |
| #20 ghi nhật ký | phân quyền → `boarding_bookings FOR SHARE` → INSERT `care_logs` → outbox |
| #21 sửa | `care_logs FOR NO KEY UPDATE`, không khóa gì sau đó |
| #22 bổ sung | INSERT `care_log_addenda` (không khóa) |

Không chu trình: cùng tiền tố `pets → branches → bookings` với đặt chỗ / gia hạn; bảo trì chuồng `branches → kennels`; đánh dấu thú mất `pets FOR NO KEY UPDATE → listener bookings` bị nhận thú (giữ `pets FOR SHARE` trước) tuần tự hóa; ST05 `bookings SKIP LOCKED`. Khóa chi nhánh cũng tuần tự hóa hai lễ tân cùng nhận sớm vào một loại chuồng, nên kiểm sức chứa các đêm thêm không bị đua. Khóa chuồng + `uq_boarding_bookings_kennel_in_use` chặn hai đặt chỗ cùng chuồng (bên sau đọc lại `OCCUPIED` → 400 `BR-LT-08`).

### 2. Quy tắc nhận thú
Thứ tự kiểm (sau khóa): 409 không `BOOKED` → `BR-LT-06` quá ngày nhận → `BR-LT-04` ngoài giờ mở cửa → nhận sớm (`BR-LT-02` tổng số đêm / chồng đặt chỗ khác của thú, `BR-LT-03` sức chứa các đêm `[hôm nay, ngày nhận cũ)`) → `BR-LT-05` mũi bắt buộc tính **tại ngày nhận thực tế** (`findBoardingVaccineGaps(petId, hôm nay)`) → `BR-LT-08` thiếu cân / chuồng. Cân nặng đi qua `PetApi.recordWeight(INTAKE)`: ≤ 0 hoặc > 9999.99 → 400 `BR-KH-04`. Nhận sớm kéo `check_in_date` về hôm nay (trả nợ T31-4) để đêm nhận sớm vào sức chứa và vào số đêm tính tiền (T43).

### 3. Quyết định đặc tả bỏ ngỏ (chờ PO xác nhận)

| ID | Quyết định | Lý do |
|---|---|---|
| E1 | Cân lúc nhận **vượt cân tối đa** loại chuồng: không chặn | BR-LT-08 liệt kê đủ điều kiện từ chối; BR-LT-02 chỉ áp dụng lúc đặt. Chặn thì kẹt: không hủy được vào ngày nhận, không dùng được #15 vì vẫn còn chuồng |
| E2 | Nhận sau ngày nhận (ST05 chưa chạy): 400 `BR-LT-06` | Đúng nghĩa đã `NO_SHOW`; cho nhận thì số đêm và sức chứa sai |
| E3 | Nhận sớm làm tổng số đêm > 30 [CFG]: 400 `BR-LT-02` | "1–30 đêm" |
| E4 | `logDate` mặc định hôm nay, trong `[ngày nhận thực tế, hôm nay]`, ngoài khoảng → 400 `VALIDATION_FAILED` | Cho ghi bù hôm qua, cấm tương lai |
| E5 | Mục rỗng (mọi trường trống, không ảnh): 400 `VALIDATION_FAILED` | Tránh mục "đủ chỉ tiêu" BR-LT-11 mà không có nội dung |
| E6 | Ảnh: ≤ `care_log.max_photos` [CFG] (vượt → 400 `BR-LT-11`), mỗi URL `^https?://\S+$` ≤ 500 ký tự (sai → 400 `VALIDATION_FAILED`) | Chặn `javascript:` / `data:`; upload để task sau (Q1) |
| E7 | `note` ≤ 2000, bổ sung ≤ 2000 ký tự (sửa `m_boarding.py`) | Chặn payload vô hạn |
| E8 | Hạn sửa đọc [CFG] lúc kiểm, không snapshot | ERD không có cột hạn sửa; cửa sổ vận hành (ADR-0057) |
| E9 | PATCH: vắng / `null` giữ nguyên, chuỗi trống xóa; không sửa `isAbnormal`, `logDate`; chỉ kiểm số ảnh khi gửi `photoUrls` | Giống `KennelService.update`; đổi [CFG] giảm số ảnh không chặn sửa trường khác |
| E10 | Ghi mới chỉ khi `CHECKED_IN` / `OVERDUE` (409); sửa trong hạn và bổ sung vẫn cho sau `CHECKED_OUT` | A4: bổ sung không giới hạn thời gian |
| E11 | Chuồng khác chi nhánh / sai loại / không `AVAILABLE`: 400 `BR-LT-08`; không tồn tại: 404 | Hợp đồng liệt kê cả hai |
| E12 | #15 trước ngày nhận: 400 `BR-LT-08` | Nhận sớm thất bại không phải lý do phòng khám hủy |

### 4. Thông báo
- Bất thường (BR-LT-12, Đặt chỗ#8): trong cùng tx với INSERT nhật ký. Khách theo BR-TB-02 (tài khoản không `PENDING` → `CARE_LOG_ABNORMAL_APP` + `CARE_LOG_ABNORMAL` EMAIL; hồ sơ quầy có email → EMAIL; không kênh → log `CARE_LOG_ABNORMAL_NO_CHANNEL`); mỗi lễ tân `ACTIVE` của chi nhánh một `CARE_LOG_ABNORMAL_APP` (không ai → log `CARE_LOG_ABNORMAL_NO_RECEPTIONIST`, nhật ký vẫn lưu). Outbox lỗi → rollback cả nhật ký.
- Hết chuồng (#15): khách như `BoardingClinicCancellationNotices` (nguyên nhân `NO_KENNEL`), `BOARDING_NO_KENNEL_APP` tới BRANCH_MANAGER (không có → mọi SUPER_MANAGER, như ADR-0035). Không gọi `evaluateAfterViolation`.
- V24 chỉ INSERT / CREATE INDEX, theo khung V20; V22 → V24 không đụng; **V24 chưa xác nhận với BE-1**.

### 5. Phân quyền và lỗi
Nhận thú / điều kiện: RECEPTIONIST, CARETAKER; #15: chỉ RECEPTIONIST; ghi / sửa / bổ sung: VET, CARETAKER; xem: khách (hồ sơ mình, người khác 404), nhân viên (chi nhánh, khác 403). Sửa chỉ người ghi (người khác 403). Thứ tự lỗi 404 / 403 → 409 → 400 (ADR-0049). Không audit (BR-QT-15 không gồm nhận thú / nhật ký).

## Kiểm chứng
- Unit: `BoardingCheckInServiceTest` (InOrder lock → mũi → chuồng → cân → ghi; mọi nhánh lỗi không ghi), `CareLogServiceTest`, `BoardingRulesTest`, `BoardingBookingTest`.
- IT (Postgres thật): `BoardingCheckInIT` (19: nhận đúng ngày / sớm, thiếu mũi, chuồng sai, cân sai, ngoài giờ, quá ngày, sức chứa, chồng ngày, quyền, đua hai đặt chỗ một chuồng, rollback bằng trigger tạm, readiness, hủy hết chuồng), `CareLogIT` (11: ghi, bất thường → outbox khách + từng lễ tân, ảnh, ngày, sửa ở biên hạn 60 phút, giảm [CFG] ảnh, bổ sung sau hạn, xem theo chủ, rollback outbox), `SchemaMigrationIT` (V24), `OpenApiContractParityIT` (169 endpoint; DTO bổ sung tên `CareLogAddendumRequest` vì `visit.dto.AddendumRequest` trùng tên schema springdoc).
- Mutation: thay `lockForBooking` của nhận thú bằng no-op → `twoBookingsRacingForOneKennelOneWinsOneIsBrLt08` đỏ (không có người chờ khóa chi nhánh).

## Nợ để lại
- E1–E12 chờ PO; kiểm mũi tiêm đọc không khóa (xóa mũi cùng lúc có thể lọt, cửa sổ vài ms, xóa mũi chỉ trong ngày tiêm); upload ảnh (Q1); `activeOrderId` null tới T43; V24 / ADR-0085 chờ BE-1 xác nhận; mock FE T48 #13–22 chưa đối chiếu với BE thật. Chi tiết `docs/dept/T42-luu-tru-nhan-thu.md`.
