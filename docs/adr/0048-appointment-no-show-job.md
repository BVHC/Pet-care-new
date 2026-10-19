# ADR-0048: ST05 chuyển lịch hẹn quá giờ sang `NO_SHOW` (Lịch hẹn#6 `markNoShow`) — một lịch một transaction, `SKIP LOCKED`, đánh giá BR-LH-09 cùng transaction

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-19
- **Liên quan:**
  - BR-LH-08 (02 L129), BR-LH-09 (L130), BR-LH-03 (L124), BR-LH-05 (L126), BR-TN-03 (L143), BR-QT-13 (L56), BR-KH-05 (L91).
  - 03 §3 Lịch hẹn#6 (L86); 03 Quy ước L11; 01 ST05 (L218).
  - `docs/06-module-contracts.md` L118; convention 07 §7.4 (L73), 08 L15, L47.
  - ADR-0007 (job), 0013 (khuôn ST02), 0036 §9 (khóa đọc lúc chạy), 0041 (tên command `markNoShow`, thứ tự khóa), 0044 / 0046 (`CheckInWindow`, check-in), 0045 §7 (`evaluateAfterViolation`, advisory 1002, READ COMMITTED), 0047.
  - Nợ: cập nhật D033; mở D035 (message BR-LH-09 thiếu ngày), D036 (giả định chờ PO), D037 (kiểm chứng còn thiếu).
- **Bổ sung:** ADR-0036 §9 (hai khóa [CFG] đọc lúc chạy).
- **Triển khai (T25 phần 2):**
  - `appointment/job/{NoShowJob, NoShowProperties}`; `appointment/service/{AppointmentNoShowService, NoShowCutoff}`.
  - `Appointment.markNoShow()`; `AppointmentRepository.{findOverdueBookedIds, lockOverdueBooked}`.
  - `application.yml` `app.jobs.no-show` (cron `0 */5 * * * *`, `batch-size` 100, `time-budget` 60s); `application-test.yml` cron `"-"`.
  - Không endpoint, không migration (index `ix_appointments_status_slot` của V1 đủ).
  - Test: `NoShowCutoffTest`, `NoShowCutoffParityTest`, `AppointmentNoShowServiceTest`, `NoShowJobTest`, `NoShowPropertiesTest`. IT: `module/appointment/NoShowIT` (17), `NoShowPlaceholderIT` (1, bean thật — D033), `NoShowScheduleIT` (1). `mvn clean verify` 2026-10-19: 4223 unit + 1458 IT xanh.

## Bối cảnh

Chưa có job ST05: lịch `BOOKED` quá giờ nằm mãi ở `BOOKED`, vẫn chiếm giới hạn 2 lịch `BOOKED` của thú (BR-LH-05) và không bao giờ được tính là vi phạm BR-LH-09.

## Quyết định

1. **Quá hạn nghiêm ngặt, khớp `CheckInWindow`:** `NO_SHOW` khi `giờ hẹn + no_show_after_minutes < now`. Check-in còn được khi `now ≤ giờ hẹn + no_show_after_minutes` (ADR-0044), nên đúng biên ST05 không đụng. `NoShowCutoff` quy về `(slot_date, slot_start) < (date, time)` với mốc = `now − phút` theo giờ Việt Nam, cắt về µs (cắt chỉ làm mốc sớm hơn ≤ 1 µs: phía an toàn). `NoShowCutoffParityTest` chứng minh không có khung nào vừa check-in được vừa quá hạn.
2. **[CFG] đọc lúc chạy**, một lần mỗi lượt, không snapshot. Bổ sung ADR-0036 §9: `appointment.no_show_after_minutes` và `visit.check_in_early_minutes` là chính sách hiện hành (cửa sổ vận hành tại quầy, không phải điều khoản đã hứa với khách). Áp dụng cho check-in, `checkInReady` và ST05. Đã chốt với PO/user 2026-10-19.
3. **Một lịch một transaction** (convention 07 L73); job không `@Transactional`; READ COMMITTED (bắt buộc, ADR-0045 §7); không `noRollbackFor`, không `try/catch` trong service.
4. **Thứ tự:** khóa dòng có điều kiện (`status = 'BOOKED'` + quá hạn, `FOR NO KEY UPDATE SKIP LOCKED`, `MANDATORY` trên repository) → đọc dưới khóa → kiểm lại mốc quá hạn bằng Java → `now` (µs) sau khóa → FSM → `markNoShow()` → `saveAndFlush` → `BookingRestrictionApi.evaluateAfterViolation(customerId, now)` (advisory 1002 = khóa cuối). Lỗi bất kỳ bước nào rollback cả NO_SHOW: NO_SHOW là vi phạm phải được đánh giá cùng transaction (06 L118).
5. **Không khóa thú / chi nhánh:** ST05 chỉ trả quota khung đã qua và nới giới hạn BR-LH-05. Thứ tự khóa `appointments → advisory 1002` là đoạn cuối của mọi thứ tự hiện có; `SKIP LOCKED` nên ST05 không bao giờ chờ dòng. Không vòng chờ.
6. **Luôn đánh giá BR-LH-09** (quyết định 2026-10-19): không phân biệt khách bị khóa (`is_locked`, BR-QT-11 giữ nguyên lịch), hồ sơ online hay tại quầy; hạn chế gắn với `customer_id`.
7. **Không thông báo, không audit, không sự kiện:** 03 #6 cột hệ quả "—"; BR-QT-15 không liệt kê.
8. **D033:** gọi thật `evaluateAfterViolation`. `BoardingQueryApiPlaceholder.countViolations` ném lỗi nên mỗi lịch rollback, log `NO_SHOW_FAILED`, lượt sau thử lại; không đưa giá trị "an toàn" (06 §1).
9. **Duyệt ứng viên theo keyset `id`** (khuôn ST02): lịch lỗi vẫn `BOOKED`, đọc lại "lô đầu" sẽ lặp vô hạn trong một lượt. Ngân sách 60 s mỗi lượt.

## Phương án đã loại

- **Khóa thú + chi nhánh trước dòng:** thừa, ST05 không làm tăng chiếm chỗ; kéo dài thời gian giữ khóa.
- **Chờ khóa dòng thay vì `SKIP LOCKED`:** check-in đang giữ dòng sẽ làm job đứng; bỏ qua rồi thử lại lượt sau là đủ.
- **Snapshot `no_show_after_minutes` vào lịch (migration):** đúng chữ BR-QT-13 hơn nhưng phải sửa check-in, `checkInReady`, thêm cột và mất index trọn vẹn; user chọn chính sách hiện hành.
- **Tắt job tới khi D033 được trả:** mất việc chạy ST05 và các test; job fail-safe (rollback + log) là đủ.
- **Cài `countViolations` bằng SQL trong placeholder:** `boarding_bookings` không có cột thời điểm hủy / NO_SHOW (V1 L561–586), nên hợp đồng "`> since`" chưa thể cài đúng từ phía appointment; việc của BE-2.

## Hệ quả

- **Tích cực:** lịch quá giờ được giải phóng (quota, giới hạn 2 lịch BOOKED của thú); vi phạm được tính ngay trong cùng transaction.
- **Không đảm bảo:**
  - Thú mất / chuyển chủ sau khi ST05 đã chuyển lịch quá giờ: vi phạm vẫn tính. Đúng BR-KH-05 ("tương lai"), hẹp hơn ADR-0047 Q3 (hủy cả lịch quá giờ chưa NO_SHOW); hai bên chạy đua thì lịch kết thúc ở đúng một trạng thái cuối.
  - NO_SHOW tính theo giờ hẹn, còn `starts_at` hạn chế = lúc job chạy: NO_SHOW xảy ra giữa giờ hẹn và lúc job chạy có thể rơi ra ngoài `since` (lệch ≤ 30 phút + độ trễ job, đã chấp nhận ở ADR-0045 §7).
  - Trong lúc D033 chưa trả, không lịch nào thành NO_SHOW; mỗi 5 phút N log ERROR và N transaction rollback. Khi BE-2 cài thật, lượt kế tiếp chuyển dồn mọi lịch tồn và có thể tạo nhiều hạn chế cùng lúc (đúng rule).
  - Một instance (ADR-0007).
- **Để lại cho phần 3:** message 400 BR-LH-09 khi đặt online chưa kèm ngày hết hạn chế mà 02 L130 yêu cầu.

## Phát hiện khi cài

- Bind `LocalTime` vào native query mất độ chính xác dưới mili giây (IT biên 1 µs đỏ): mốc giờ truyền dạng chuỗi `LocalTime.toString()` + `CAST(:t AS time)`. Ngày vẫn truyền `LocalDate`.
- `saveAndFlush` trước `evaluateAfterViolation` giữ nguyên như hủy muộn; chưa thử mutation `save` thay `saveAndFlush`.

## Mutation đã chạy (mỗi cái làm đỏ test)

Bỏ gọi `evaluateAfterViolation` (5 test đỏ); `try/catch` quanh nó (giữ kiểu exception); bỏ lọc `status` trong câu khóa (`rowThatLeftBookedIsSkippedNotAnError`, sau khi bổ sung test); SQL `<` → `<=` (test biên); `isOverdue` `<` → `<=` bị unit test `NoShowCutoffTest` bắt (IT không bắt vì SQL đã lọc trước — lớp kiểm lại bằng Java là phòng thủ thừa có chủ đích).

## Việc còn lại

- Chưa chạy: mutation `REQUIRES_NEW`, `noRollbackFor`, `REPEATABLE_READ`, bỏ `SKIP LOCKED` (test giữ khóa sẽ treo), `save` thay `saveAndFlush`; EXPLAIN trên 20 000 dòng; statement count; đọc qua GET #3 / #4 và đặt online bị chặn bởi hạn chế do ST05; `configChangeAppliesFromNextRun`; docker-compose smoke.
- Test race với thú mất (`deceasedParallelNoShowEndsInExactlyOneFinalState`) chưa viết.
