[← Backend Convention Index](INDEX.md)

# 7. Transaction Management

## 7.1. Ranh giới transaction

- `@Transactional` đặt ở **Service / TransitionHandler**, bao trọn **một use case hoàn chỉnh**: kiểm tra rule, đổi trạng thái, hệ quả, audit, outbox đều nằm trong cùng một transaction.
- Không đặt `@Transactional` ở Controller hoặc Repository.
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

- Bên bị kéo theo cung cấp method trên service của mình; bên phát sự kiện gọi tới. Không gọi thẳng repository của module khác ([01](01-package-structure.md)).
- Thông báo email/in-app: INSERT vào `notification_outbox` trong cùng transaction, worker ST20 gửi sau (erd L1039). Rollback nghiệp vụ thì không có thông báo nào được gửi. Worker gửi **chưa có**; `notification_outbox.template_code` có FK nên phải seed `notification_templates` trước.

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

Khi DB chặn, `GlobalExceptionHandler` trả 409 `CONCURRENCY_CONFLICT` với message chung. Service vẫn phải kiểm tra trước để trả đúng mã rule trong trường hợp thường ([06](06-validation.md)).

## 7.4. Job định kỳ (ST01–ST20)

- Cơ chế đã chốt ở [ADR-0007](../../adr/0007-scheduled-jobs.md): `@Scheduled` (bật ở `platform/config/SchedulingConfig`), job ở `module/<m>/job/{Việc}Job`, cron `app.jobs.<job>.cron` theo giờ Việt Nam (`"-"` để tắt, profile test tắt hết), giả định một instance nên job phải idempotent. Job hiện có: `identity/job/SessionCleanupJob` ([ADR-0008](../../adr/0008-session-cleanup.md)). Chưa có job ST nào.
- Job chạy không có người đăng nhập: audit ghi actor là hệ thống ([08](08-logging-and-audit.md)); thời gian lấy từ bean `Clock`.
- Job xử lý nhiều bản ghi (ST05 chuyển `NO_SHOW`, ST15 `OVERDUE`, ST02 dọn tài khoản `PENDING`…) tách transaction theo từng bản ghi hoặc từng lô ở service, không bao cả lượt chạy (job **không** `@Transactional`), để một bản ghi lỗi không rollback cả lượt và không giữ khóa lâu. Mỗi bản ghi vẫn là một use case trọn vẹn (gồm cả hệ quả liên aggregate). Lỗi được log và xử lý bù ở lượt sau.

---

[← 6. Validation](06-validation.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 8. Logging & Audit →](08-logging-and-audit.md)
