[← Backend Convention Index](INDEX.md)

# 7. Transaction Management

## 7.1. Ranh giới transaction

- `@Transactional` đặt ở **Service / TransitionHandler**, bao trọn **một use case hoàn chỉnh**: kiểm tra rule, đổi trạng thái, hệ quả, audit, outbox đều nằm trong cùng một transaction.
- Không đặt `@Transactional` ở Controller hoặc Repository.
- Open Session In View đã tắt (`spring.jpa.open-in-view: false`, [ADR-0020](../../adr/0020-disable-open-in-view.md)): connection chỉ bị giữ trong transaction; đọc entity / map sang DTO phải xong trong transaction của service.
- **Ngoại lệ có chủ đích — đăng nhập, đổi mật khẩu** ([ADR-0019](../../adr/0019-login-failure-lockout.md) mục 4, [ADR-0022](../../adr/0022-change-password.md) mục 1): `identity/service/LoginService`, `ChangePasswordService` không `@Transactional`, đọc hash trong transaction readOnly ngắn rồi chạy BCrypt khi không giữ connection; mọi kiểm tra rule dẫn tới ghi (khóa dòng, bộ đếm, phiên, audit, outbox) nằm trọn trong một transaction của `LoginAttemptService` / `ChangePasswordAttemptService`. Chỉ dùng cách tách này cho bước CPU nặng không cần dữ liệu khóa (như BCrypt); quyết định ghi luôn dựa trên dữ liệu đọc lại dưới khóa.
- Service được gọi từ service khác dùng propagation mặc định (`REQUIRED`) để tham gia transaction của bên gọi. Không dùng `REQUIRES_NEW` trong nghiệp vụ; ngoại lệ duy nhất hiện có là `AuditRecorder.recordIndependently()` ([08](08-logging-and-audit.md)).

## 7.2. Hệ quả liên aggregate

Các thay đổi do cùng một sự kiện gây ra chạy trong **một transaction**, do service của aggregate **phát sự kiện** điều phối (`03-state-machines.md` Quy ước L11, Phụ lục L237–253; `04-domain-model.md` nguyên tắc 6).

```
VisitService.checkIn…()                      @Transactional — Visit#1
 ├─ tạo Visit (WAITING)
 ├─ AppointmentService.…()                   Lịch hẹn#3 BOOKED → CHECKED_IN   (SYS ← Visit#1)
 ├─ OrderService.…()                         Order#1 — → OPEN + dòng dịch vụ tự sinh
 └─ CareTaskService.…()                      Care Task#6 (nếu có dịch vụ Khám)
```

- Bên bị kéo theo cung cấp method trên service của mình, qua interface trong `api/` khi khác module (`06-module-contracts.md` §1); bên phát sự kiện gọi tới. Không gọi thẳng repository của module khác ([01](01-package-structure.md)).
- Thông báo email/in-app: INSERT vào `notification_outbox` trong cùng transaction, worker ST20 gửi sau (erd L1039). Rollback nghiệp vụ thì không có thông báo nào được gửi. Worker ST20 gửi email ([ADR-0012](../../adr/0012-notification-outbox-sender.md)) và giao thông báo IN_APP vào `notifications` ([ADR-0014](../../adr/0014-in-app-notification-delivery.md)); `notification_outbox.template_code` có FK nên phải seed `notification_templates` trước. Người nhận theo kênh và chống gửi trùng phía ghi: ADR-0012 và `06-module-contracts.md` §1.

### Hệ quả 1–n: sự kiện đồng bộ

Hệ quả 1–1 (biết trước bên bị kéo theo) gọi interface như trên. Khi một sự kiện lan ra **nhiều module** thì dùng sự kiện đồng bộ (`06-module-contracts.md` §1, §3): `PetDeceasedEvent`, `PetOwnerTransferredEvent`, `BranchClinicCancellationEvent`, `AccountLockedEvent`.

```
CustomerService.markPetDeceased…()            @Transactional — BR-KH-05
 ├─ kiểm guard, ghi pets.deceased_on
 └─ publishEvent(PetDeceasedEvent)            đồng bộ, cùng thread, cùng transaction
     ├─ appointment/listener/PetDeceasedListener → AppointmentService.…()   @Transactional(MANDATORY)
     ├─ boarding/listener/PetDeceasedListener    → BoardingService.…()      @Transactional(MANDATORY)
     └─ care/listener/PetDeceasedListener        → CareTaskService.…()      @Transactional(MANDATORY)
```

- **Bên phát:** record `{Việc}Event` nằm trong `api/` của module phát. Gọi `ApplicationEventPublisher.publishEvent(...)` **bên trong** method `@Transactional` của use case, **sau khi** đã kiểm guard và đổi trạng thái của chính mình (06 §3: bên nhận không kiểm lại điều kiện).
- **Bên nhận:** `module/<m>/listener/{TênSựKiện}Listener`, một method `@EventListener` chỉ gọi service của module mình, không chứa logic (như Job). Method service đó đặt `@Transactional(propagation = MANDATORY)`: phát sự kiện ngoài transaction thì lỗi ngay thay vì mỗi listener tự commit riêng.
- **Lỗi ở listener** đi ngược qua `publishEvent` tới bên phát và rollback **toàn bộ** use case. Listener không `try/catch` để nuốt lỗi.
- **Cấm:** `@TransactionalEventListener` (mọi phase, kể cả `AFTER_COMMIT`), `@Async` hoặc multicaster bất đồng bộ, `REQUIRES_NEW` trong chuỗi listener, gửi email/push trực tiếp (dùng `NotificationApi.enqueue` → outbox, như trên).
- Các listener của cùng một sự kiện phải độc lập với nhau; không dựa vào thứ tự chạy.
- Cơ chế (cùng thread, cùng transaction, rollback khi listener lỗi, lỗi khi phát ngoài transaction) được chứng minh ở `DomainEventTransactionIT` ([09](09-testing.md)).

## 7.3. Khóa đồng thời — chưa chốt, cần ADR

Chiến lược khóa (pessimistic `FOR UPDATE`, advisory lock, optimistic `@Version`…) là quyết định kiến trúc, **phải có ADR trước khi cài** module đầu tiên đụng tới các điểm dưới. Gợi ý của erd được ghi để ADR xem xét, chưa phải quyết định.

| Điểm tranh chấp | Rule | DB có chặn không | Gợi ý trong erd |
|---|---|---|---|
| Lấy khung cuối của quota | BR-LH-03 | Không (quota là phép đếm) | L476: `FOR UPDATE` trên `branches` hoặc advisory lock theo chi nhánh + nhóm + khung |
| Sức chứa chuồng theo đêm | BR-LT-03 | Không | — |
| Trừ kho khi thu tiền | BR-BH-04, BR-TG-04 | `stock_lots.quantity ≥ 0` | L802: khóa `orders` và `stock_lots` `FOR UPDATE` trong một transaction |
| Trừ kho khi tiêm | BR-KB-04, BR-KO-05 | `stock_lots.quantity ≥ 0` | — |
| Một lễ tân một ca `OPEN` | BR-TG-05 | Partial unique `cashier_id` | — |
| Thú không trùng khung / không chồng ngày lưu trú | BR-LH-05, BR-LT-02 | Partial unique / `EXCLUDE` | — |
| Một chuồng một thú | BR-LT-08 | Partial unique `kennel_id` | — |
| Hủy phiên: đăng xuất, đổi / đặt lại mật khẩu, khóa, vô hiệu hóa; trạng thái online `last_seen_at` | BR-TK-11, 13, 14, BR-TN-06, BR-QT-09 | Không | **Đã chốt — [ADR-0021](../../adr/0021-logout-session-lock-order.md):** khóa / ghi dòng `accounts` **trước** rồi mới `UPDATE sessions` (không deadlock); `touchLastSeen` chỉ ghi khi phiên chưa hủy, `SKIP LOCKED` |
| Luồng OTP: quota gửi, bộ đếm sai, mã mới vô hiệu mã cũ | BR-TK-05, 06, 07 | Không (quota là phép đếm) | **Đã chốt — [ADR-0011](../../adr/0011-otp-flow-locking.md):** `SELECT … FOR UPDATE` dòng `accounts` trước mọi đọc/ghi `otp_tokens`; ST02 dùng `SKIP LOCKED` |

Khi DB chặn, `GlobalExceptionHandler` trả 409 `CONCURRENCY_CONFLICT` với message chung. Service vẫn phải kiểm tra trước để trả đúng mã rule trong trường hợp thường ([06](06-validation.md)).

## 7.4. Job định kỳ (ST01–ST20)

- Cơ chế đã chốt ở [ADR-0007](../../adr/0007-scheduled-jobs.md): `@Scheduled` (bật ở `platform/config/SchedulingConfig`), job ở `module/<m>/job/{Việc}Job`, cron `app.jobs.<job>.cron` theo giờ Việt Nam (`"-"` để tắt, profile test tắt hết), giả định một instance nên job phải idempotent. Job hiện có: `identity/job/SessionCleanupJob` (dọn phiên, [ADR-0008](../../adr/0008-session-cleanup.md)), `identity/job/PendingAccountCleanupJob` (ST02, [ADR-0013](../../adr/0013-pending-account-cleanup.md)), `care/job/PriorityNotificationJob` + `NotificationOutboxJob` (ST20 email, [ADR-0012](../../adr/0012-notification-outbox-sender.md)), `care/job/InAppNotificationJob` (ST20 IN_APP, [ADR-0014](../../adr/0014-in-app-notification-delivery.md)), `care/job/NotificationOutboxCleanupJob` (dọn outbox đã xử lý, [ADR-0016](../../adr/0016-notification-outbox-cleanup.md)). Luồng NORMAL của ST20 xử lý theo lô trên một kết nối SMTP ([ADR-0017](../../adr/0017-notification-normal-lane-batching.md)). Scheduler 4 thread. Các ST khác chưa có.
- Job chạy không có người đăng nhập: audit ghi actor là hệ thống ([08](08-logging-and-audit.md)); thời gian lấy từ bean `Clock`.
- Job xử lý nhiều bản ghi (ST05 chuyển `NO_SHOW`, ST15 `OVERDUE`, ST02 dọn tài khoản `PENDING`…) tách transaction theo từng bản ghi hoặc từng lô ở service, không bao cả lượt chạy (job **không** `@Transactional`), để một bản ghi lỗi không rollback cả lượt và không giữ khóa lâu. Mỗi bản ghi vẫn là một use case trọn vẹn (gồm cả hệ quả liên aggregate). Lỗi được log và xử lý bù ở lượt sau.

---

[← 6. Validation](06-validation.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 8. Logging & Audit →](08-logging-and-audit.md)
