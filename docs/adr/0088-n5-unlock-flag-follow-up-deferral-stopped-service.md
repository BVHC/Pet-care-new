# ADR-0088: N5 — mở khóa gỡ cờ gán lại (D042), ST04 hoãn nhắc tái khám (D052), lịch đã đặt vẫn tiếp nhận khi dịch vụ ngừng (D040)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-01
- **Liên quan:** BR-QT-12, BR-TN-08, BR-TB-06, BR-LH-01, BR-LH-09; 03 Tài khoản#8, Care Task#6, Visit#1; ADR-0062 (Q5), 0059 (Q3), 0082 (ST04), 0084 (§2, §5), 0057 (cách người dùng chốt thay PO). Nợ: D042, D052, D040 (đóng).
- **Triển khai:** `identity/api/AccountUnlockedEvent` + `AccountLockService.unlockAccount`; `visit/listener/AccountUnlockedListener`, `VisitReassignFlagService.clearFlagsOf`, `VisitRepository.clearNeedsReassign`; `FollowUpApi.cancelRemindedFollowUpTasks`, `MedicalRecordRepository.lockPendingFollowUpsOfPet`, `AppointmentQueryApi.hasBookedMedical`, `FollowUpReminderService.remind`; `VisitCheckInService.checkService`, `VisitOrderService.openForVisit`. Không migration, không [CFG] mới.

Người dùng chốt thay PO ngày 2026-11-01 cho cả ba điểm. Mỗi mục thay một quyết định cũ; ADR cũ giữ nguyên (immutable).

## Quyết định

### 1. D042 — mở khóa gỡ cờ "cần gán lại" (thay ADR-0062 Q5)

1. `unlockAccount` flush rồi phát `AccountUnlockedEvent(accountId, actorId)` đồng bộ, **trước** audit; listener lỗi rollback cả lần mở khóa (convention 07 §7.2). Flush trước để listener đọc lại tài khoản qua `StaffDirectoryApi.findStaff` thấy `is_locked = false`.
2. Visit gỡ cờ của lượt `IN_PROGRESS` mà người đó **lại đủ điều kiện phụ trách**: `ACTIVE` không khóa, cùng chi nhánh, đúng chức vụ với nhóm dịch vụ (cùng định nghĩa `VisitDispatchService.isAssignable`, tức cũng là điều kiện để #9 từ chối gán lại). Một câu UPDATE theo `(assignee_id, branch_id, service_group)`; chức vụ → nhóm suy từ `requiredRole`.
3. Người đã bị đổi chức vụ hoặc chuyển chi nhánh trong lúc khóa (đổi chức vụ không kiểm `is_locked`, ADR-0031 Q4) giữ cờ để BRANCH_MANAGER gán lại bằng #9. Lượt `WAITING` không bao giờ có cờ.
4. Thứ tự khóa: `accounts` (identity đang giữ) → `visits`. Khóa lại sau mở khóa đánh dấu lại như cũ.

### 2. D052 — ST04 hoãn nhắc khi thú có lịch Khám/Tiêm (thay ADR-0084 §2, §5; sửa ADR-0082)

1. Đặt lịch nhóm `MEDICAL` **không còn đặt `follow_up_cancelled_at`**. Nó gọi `FollowUpApi.cancelRemindedFollowUpTasks(petId)`: khóa (`FOR NO KEY UPDATE OF m`, id tăng dần, không ghi) các bệnh án đã khóa còn chờ nhắc của thú → đọc bệnh án ST04 đã nhắc → hủy task `FOLLOW_UP_DUE` `OPEN`. Khóa trước khi đọc để chờ ST04 đang giữ dòng commit, nếu không task ST04 vừa sinh còn `OPEN` dù khách đã có lịch. Thứ tự `medical_records → care_tasks` giữ nguyên.
2. ST04 (`remind`), sau khi khóa bệnh án `SKIP LOCKED`: `AppointmentQueryApi.hasBookedMedical(petId)` (lịch `MEDICAL` `BOOKED`, đọc không khóa, `ix_appointments_pet_status`) → trả `SKIPPED`, **không ghi gì** (không đánh dấu nhắc, không hủy nhắc). Bệnh án còn trong cửa sổ nên các lần quét sau thử lại; lịch hủy hoặc `NO_SHOW` thì nhắc tự quay lại, lịch đặt trước lúc khóa bệnh án cũng được tính. Áp dụng cả hồ sơ không email (hoãn Care Task).
3. Check-in có dịch vụ tự sinh `EXAM` giữ nguyên `cancelPendingFollowUps` (đặt `follow_up_cancelled_at`): thú đã đến khám là sự thật không đảo ngược. Thú mất giữ nguyên.

### 3. D040 — lịch đã đặt vẫn tiếp nhận khi dịch vụ ngừng (thay ADR-0059 Q3)

1. BR-LH-01 là điều kiện **đặt** lịch (UC39), không phải điều kiện tiếp nhận: ngừng kinh doanh chỉ chặn đặt mới, giống giảm quota không ảnh hưởng lịch đã đặt (BR-LH-03). `VisitCheckInService.checkService` bỏ chặn dịch vụ đã ngừng ở nhánh có lịch; walk-in giữ nguyên 400.
2. `VisitOrderService.openForVisit` (sales, BE-2) bỏ bộ lọc "đang kinh doanh"; dịch vụ không tồn tại vẫn `IllegalStateException`. Giá snapshot theo giá hiện tại lúc tiếp nhận (BR-BH-03). Trước đây check-in vượt được chặn của visit mà vẫn 500 ở sales.
3. Catalog và branch không đổi. Lưu trú không dính lỗi này (nhận thú không kiểm dịch vụ còn kinh doanh).

## Giả định (chưa PO xác nhận)

- Hoãn nhắc áp theo thú, không theo "lịch khám nào": lịch `MEDICAL` `BOOKED` bất kỳ (kể cả tiêm) của thú hoãn mọi nhắc tái khám của thú (BR-TB-06 viết theo thú; khớp ADR-0084 giả định cuối).
- Email nhắc đã gửi không thu hồi được: lịch đặt đúng lúc ST04 đang gửi thì khách vẫn nhận một nhắc; Care Task đã hủy không mở lại (06 §4.1).
- Hai lượt Khám đồng thời cho một thú ở phía Visit (check-in EXAM giữa lúc lượt khác chưa khóa bệnh án) vẫn có thể gửi một nhắc dư; cực hiếm, lễ tân gọi tay được.

## Bảo đảm ↔ test

| Bảo đảm | Test |
|---|---|
| Mở khóa gỡ cờ người đủ điều kiện; sai chức vụ giữ cờ; không chạm lượt người khác; khóa lại đánh dấu lại; listener lỗi rollback cả mở khóa | `VisitProgressIT` (`unlock*`, `aFailureClearingTheFlagRollsBackTheUnlock`); unit `AccountLockServiceTest` (`InOrder` flush → publish → audit), `VisitReassignFlagServiceTest` |
| #9 vẫn 400 BR-TN-08 sau mở khóa, cờ đã gỡ | `VisitReassignIT.activeAssigneeIsBrTn08AfterUnlock…` |
| ST04 hoãn khi có lịch MEDICAL BOOKED, không ghi gì; quay lại đúng một lần khi lịch rời BOOKED; NO_SHOW / Thẩm mỹ / COMPLETED không hoãn; hồ sơ không email hoãn Care Task | `FollowUpReminderIT`; unit `FollowUpReminderServiceTest` |
| Đặt lịch chỉ hủy task đã sinh, không ghi bệnh án; race với ST04 cả hai chiều | `FollowUpCancellationIT`, `FollowUpCancellationRaceIT`; unit `FollowUpCancellationServiceTest`, `AppointmentBookingServiceTest` |
| `hasBookedMedical` chỉ tính BOOKED + MEDICAL của đúng thú | `AppointmentQueryApiIT` |
| Check-in EXAM vẫn hủy nhắc, đặt lịch thì chưa (luồng thật) | `MainFlowExamIT` (bước 1, 2, 14) |
| Lịch có dịch vụ ngừng / tắt vẫn tiếp nhận và mở Order; walk-in vẫn 400 | `VisitCheckInIT`, unit `VisitCheckInServiceTest`, `VisitOrderServiceTest` |

## Không đảm bảo

- Chưa chạy mutation test (bỏ khóa `lockPendingFollowUpsOfPet`, bỏ flush trước khi phát); chưa chứng minh các test trên chuyển đỏ khi bỏ chúng.
- Không đo tải câu `hasBookedMedical` trên bảng lớn; dùng index sẵn có `ix_appointments_pet_status`.
