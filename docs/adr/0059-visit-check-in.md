# ADR-0059: Tiếp nhận (Visit#1) — `POST /api/visits`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-20
- **Liên quan:**
  - BR-TN-01…04 (02 L141–144), BR-CN-01 (L65), BR-CN-05 (L69), BR-LH-01 (L122), BR-KH-05 (L91), BR-BH-02, 03 (L190–191), BR-TB-06 (L254), BR-QT-15 (L58).
  - 03 §4 Visit#1 (L102), Lịch hẹn#3 (L83), Order#1 (L123), Care Task#6 (L233); `docs/api/00-method.md` L70 (thứ tự lỗi).
  - visit-v1 #1 (A9–A12), sales-v1 A6; 06 §4.1, §4.2, §6.
  - ADR-0003 (phiên), 0029 §4 (entity cũ trong persistence context), 0043 (mã chứng từ), 0044 (Order lõi — **bổ sung L40**, mục 6), 0050 (`AppointmentApi`), 0058 (nền visit, thứ tự khóa).
  - Nợ: mở D040; mở rộng D029 (phía check-in).
- **Triển khai (T26 phần 1):**
  - visit: `controller/VisitController`, `dto/{CreateVisitRequest, VisitResponse}`, `mapper/VisitMapper`, `service/{VisitCheckInService, QueuePlacement}`, `entity/Visit.checkIn`.
  - appointment: `AppointmentQueryApi.CheckInRef` + `findCheckInRef` (`AppointmentRepository.findCheckInRef`, `AppointmentLookupService`).
  - sales (BE-2): một điều kiện ở `OrderLinePolicy.evaluate` (mục 6).
  - Test:
    - Unit: `VisitCheckInServiceTest` (22), `QueuePlacementTest` (7), `VisitMapperTest` (2), `CreateVisitRequestValidationTest` (3), `AppointmentLookupServiceTest` (+1), `OrderLinePolicyTest` (+1), `OrderLineServiceTest` (bỏ 1 stub thừa).
    - IT: `VisitCheckInIT` (31), `VisitContractEnumIT` (1), `AppointmentQueryApiIT` (+1), `AppointmentQueryCountIT` (+1), `OpenApiContractParityIT` (thêm `visit-v1.yaml`, 95 endpoint).
  - Không migration.

## Bối cảnh

Phần 0 (ADR-0058) dựng nền visit nhưng chưa có endpoint. Tiếp nhận là thao tác đầu tiên của chuỗi khám. Một transaction gồm ba việc:
- tạo Visit `WAITING`;
- chuyển lịch hẹn sang `CHECKED_IN` (nếu có lịch);
- mở Order VISIT `OPEN` kèm dòng dịch vụ tự sinh, giá snapshot.

Tài liệu để ngỏ năm điểm (Q1–Q5). Một điểm thứ sáu lộ ra khi phần này mở đường dữ liệu thật vào sales (Q6).

## Quyết định

1. **Giờ mở cửa áp cho mọi lượt (Q1, A9).**
   - 03 #1 liệt kê cả "trong giờ mở cửa, hoặc ngoài giờ có cờ cấp cứu và đánh dấu cấp cứu" lẫn cửa sổ check-in. Hai điều kiện cùng phải đúng.
   - Ví dụ: lịch 08:00, khách đến 07:40 khi chi nhánh mở 08:00 → 400 BR-CN-05. Lễ tân chờ tới giờ mở cửa; vẫn trong cửa sổ, vì trễ tới `appointment.no_show_after_minutes` vẫn check-in được. Giờ nghỉ giữa ca và ngày nghỉ xử lý như ngoài giờ.
   - Hai message: "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ" / "Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu".
2. **`isEmergency = true` luôn xếp `EMERGENCY` (Q2, A10)**, kể cả trong giờ và khi có lịch hẹn; `queueSortKey` = giờ check-in. Endpoint gộp UC47 cho A06.
3. **Dịch vụ (Q3, A12).**
   - **Walk-in:** dịch vụ phải đang kinh doanh, thuộc nhóm `MEDICAL` / `GROOMING`, đang bật tại chi nhánh. Sai → 400 BR-LH-01.
   - **Có lịch:** không kiểm cờ bật tại chi nhánh (BR-LH-01 là điều kiện đặt lịch, 03 #1 không liệt kê). Chỉ dịch vụ đã ngừng kinh doanh → 400 BR-LH-01, thay vì 500 từ `VisitOrderService.openForVisit` (L54–56).
   - Có `appointmentId` thì `serviceId` của request bị bỏ qua (A11).
   - Hậu quả còn lại (ngừng / tắt dịch vụ khi còn lịch `BOOKED` làm khách bị `NO_SHOW` oan) là **D040** (BE-2).
4. **Care Task#6 / BR-TB-06 chưa nối (Q4)**, cùng lý do với đặt lịch (D029, ADR-0047 Q4): chưa có bean `FollowUpApi` / `CareTaskApi`, chưa có nhắc tái khám nào để hủy. Trả cùng PR cài `FollowUpApi` (T38 / T39).
5. **Thứ tự lỗi theo `00-method.md` L70: quyền → tồn tại → 400 → 409 (Q5).**
   - Vấn đề: `AppointmentApi.checkIn` gộp khóa + 404 + 409 + 400 + ghi. Gọi nó trước thì 400 của visit đứng sau 409; gọi sau thì 404 của lịch đứng sau 400.
   - Cách giải: thêm **`AppointmentQueryApi.findCheckInRef(appointmentId)`**, đọc **không khóa** projection `(appointmentId, branchId, petId, serviceId)`. An toàn vì lịch không bị xóa cứng và bốn cột `updatable = false`. Không nạp entity, nên `findByIdForUpdate` trong `checkIn` vẫn đọc dòng mới dưới khóa (bẫy ADR-0029 §4, tiền lệ `findAccessRow`).
   - Thứ tự (`VisitCheckInService`):
     1. 404 thú (`findPetForShare`, khóa đầu tiên);
     2. 404 lịch;
     3. **400 BR-TN-02 lịch chi nhánh khác** — ngay sau 404, trước mọi guard, để message của các bước sau không lộ thú / dịch vụ của lịch chi nhánh khác;
     4. 404 dịch vụ walk-in;
     5. 400 BR-TN-01 (walk-in thiếu dịch vụ → thú không khớp lịch → thú đã mất);
     6. 400 BR-CN-01 → BR-CN-05 → BR-LH-01;
     7. `checkIn`: 409 → 400 BR-TN-02 cửa sổ (lệch sẵn có ADR-0050).
   - Mọi 400 của visit đứng trước câu ghi đầu tiên.
   - "Chủ của thú ≠ khách của lịch" kiểm **sau** `checkIn` (`IllegalStateException`, 500): lịch `BOOKED` dưới khóa thú không thể lệch; kiểm trước thì lịch `CANCELLED` cũ của chủ trước sẽ ra 500 thay vì 409.
   - Thú mất dùng mã **BR-TN-01** (hợp đồng visit-v1), không BR-KH-05.
6. **Dòng tự sinh: lễ tân không sửa / xóa (Q6, bổ sung ADR-0044 L40).**
   - `openForVisit` đặt chủ tạm của dòng tự sinh = lễ tân khi lượt chưa gán (L60).
   - `OrderLinePolicy` (ADR-0044 L40) cho chủ dòng thuộc `{RECEPTIONIST, VET, CARETAKER}` sửa / xóa dòng SERVICE `OPEN`. Phần này làm đường đó chạy được, trái BR-TN-01 "do nhân viên được gán lượt đổi hoặc xóa".
   - Thêm một điều kiện ở nhánh `SERVICE`, sau kiểm `PENDING`: dòng `autoGenerated` của Order `VISIT` mà người thao tác là `RECEPTIONIST` → `NOT_OWNER` (403). `OrderAssembler` dùng cùng hàm, nên cờ `canEdit` / `canDelete` khớp theo.
   - File của BE-2: ghi 06 §6 và sales-v1 A6.
7. **Hạng ưu tiên (`QueuePlacement`, BR-TN-03, 04):**
   - cấp cứu → `EMERGENCY` / giờ check-in;
   - có lịch và `now ≤ giờ hẹn + visit.late_priority_minutes` (biên tính là giữ ưu tiên) → `APPOINTMENT` / giờ hẹn;
   - còn lại → `WALK_IN` / giờ check-in.
   - `now` cắt về µs, lấy **sau khóa thú, trước `checkIn`**: API nhận `at`, nên khi phải chờ khóa lịch, `at` sớm hơn thời điểm khóa vài ms.
8. **Thứ tự khóa** đúng ADR-0058 mục 5:
   - `pets FOR SHARE` → [đọc `appointments` không khóa] → `appointments FOR NO KEY UPDATE` → `document_counters` (`visits` → `ORDER`) → INSERT `visits` → `orders` / `order_lines`.
   - Không khóa `accounts` (chưa gán), không khóa `branches`.
9. **Transaction:**
   - Một `@Transactional` (REQUIRED, READ COMMITTED) trên `checkInVisit`; mọi callee ghi là `MANDATORY`; không `try/catch`, không `noRollbackFor`.
   - READ COMMITTED là bắt buộc: `FOR SHARE` sau khi chờ bên đánh dấu mất / chuyển chủ commit phải thấy dữ liệu mới.
   - Không audit (08 §8.3), không thông báo, không đánh giá BR-LH-09.
10. **Response** `VisitResponse` (schema `Visit`), MapStruct `VisitMapper` với `Parties` (không nhận record `api/` của module khác).
    - `orderId` lấy từ `LineRef`.
    - `serviceGroup` có `allowableValues = {MEDICAL, GROOMING}` để Swagger khớp hợp đồng (enum Java có thêm `BOARDING`).
    - Phần sau (đọc lại lượt) cần tra `orders.visit_id` qua `OrderQueryApi`.
11. **Parity hợp đồng:**
    - `OpenApiContractParityIT.DOCUMENTED` thêm `visit-v1.yaml`. Trước đó endpoint visit không bị so; chỉ endpoint đã cài bị kiểm.
    - Parity chỉ so cây tên property, nên `VisitContractEnumIT` so thêm tập giá trị enum của `Visit`.

## Không đảm bảo

- Walk-in gửi hai lần → 2 Visit. Docs không có khái niệm idempotency; BR-LH-05 cho thú có 2 lượt cùng ngày. Có lịch thì lần sau là 409.
- Walk-in đúng lúc chi nhánh thêm ngày nghỉ hôm nay / tắt cờ cấp cứu: không khóa `branches` (ADR-0058).
- Dịch vụ bị ngừng giữa lúc kiểm và lúc `openForVisit` tự đọc lại → 500, rollback sạch.
- Lễ tân bị khóa / điều chuyển trong lúc request đang chạy: request hoàn tất với chi nhánh đọc lúc xác thực (hành vi chung, ADR-0003).
- Lịch `BOOKED` hôm nay mà lễ tân chọn tiếp nhận walk-in: lịch sau đó thành `NO_SHOW` (không có rule chặn).

## Hệ quả

- Từ nay có Visit / lịch `CHECKED_IN` / Order VISIT thật:
  - GET lịch hẹn trả `visitId`;
  - thú có Visit mở thì không đánh dấu mất / chuyển chủ / xóa (BR-KH-05, 06, 08);
  - loài **không** bị khóa (chưa có bệnh án);
  - lễ tân check-in **không** thành "người được gán" (BR-QT-08);
  - Order VISIT `OPEN` xuất hiện ở sales, không chốt / hủy như bán lẻ.
- `AppointmentQueryApi` có thêm một method. Mock hiện có của bên khác (`mock()` / `@MockitoBean`) không phải sửa.
- `OrderLineServiceTest.receptionistOwnerOfTheUnassignedAutoLineCannotSwitchService` bị chặn sớm hơn (ở policy). Bỏ stub `catalog.findService` thừa (Mockito strict), thêm `verify(never())`.

## Mutation (mỗi thay đổi phải làm một test đỏ)

Đã chạy 2026-10-20 bằng script tạm (sửa → chạy test đích → khôi phục):

| Mutation | Test đỏ |
|---|---|
| `findPetForShare` → `findPet` | `VisitCheckInIT.checkInWaitsForDeceasedMarking` |
| Bỏ kiểm chi nhánh của lịch (bước 3) | `VisitCheckInIT.precedenceFollowsPermissionNotFoundRuleConflict` |
| `@Transactional(readOnly = true)` | `VisitCheckInIT.appointmentCheckInPersistsVisitAppointmentAndOrderTogether` |
| Bọc `checkIn` trong `try/catch` | `VisitCheckInIT.appointmentNotBookedIs409AndNotFoundIs404` |
| `noRollbackFor = PlatformException` | `VisitCheckInIT.appointmentNotBookedIs409AndNotFoundIs404` |
| `isolation = REPEATABLE_READ` | `VisitCheckInIT.checkInWaitsForDeceasedMarking` |
| Bỏ điều kiện dòng tự sinh ở `OrderLinePolicy` | `VisitCheckInIT.receptionistCannotEditTheAutoLineOfAnUnassignedVisit` |
| `hasAnyRole('RECEPTIONIST','VET')` | `VisitCheckInIT.onlyReceptionistsMayCheckIn` |
| `REQUIRES_NEW` trên `VisitCodeGenerator.nextCode` | `VisitCheckInIT.orderLineFailureRollsBackEverything` |

Không phát hiện được (ghi rõ): `save` thay `saveAndFlush` cho `visits`. Với `GenerationType.IDENTITY`, Hibernate INSERT ngay khi persist; vẫn dùng `saveAndFlush` theo quy ước.

## Đã cân nhắc

- **Gọi `checkIn` trước rồi mới kiểm 400 của visit** — không thêm API, nhưng 409 đứng trước 400 (lệch L70) và lịch bị UPDATE rồi rollback ở mọi lỗi 400.
- **Tách 404 ra khỏi `checkIn` (đổi `AppointmentApi`)** — đụng hợp đồng đã giao (ADR-0050) và các test của nó; projection riêng gọn hơn.
- **Miễn kiểm giờ mở cửa cho lượt có lịch (Q1)** — diễn giải ngoài chữ 03 #1.
- **Chỉ coi `isEmergency` khi ngoài giờ (Q2)** — trái việc hợp đồng gộp UC47 vào endpoint.
- **Chặn cả lịch có dịch vụ bị tắt tại chi nhánh / không chặn gì (Q3)** — chặn thì khách dễ bị `NO_SHOW` oan; không chặn thì phải đổi `openForVisit` của BE-2.
- **Cài `FollowUpApi` tối thiểu ngay (Q4)** — chưa có dữ liệu để hủy; phình phạm vi.
- **Mở nợ cho BE-2 thay vì sửa `OrderLinePolicy` (Q6)** — lỗ hổng BR-TN-01 tồn tại tới khi BE-2 sửa; 04 §4.4 đòi chặn ở service.
