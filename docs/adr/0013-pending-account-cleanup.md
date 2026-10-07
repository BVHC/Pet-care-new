# ADR-0013: ST02 dọn tài khoản `PENDING` quá hạn

- **Trạng thái:** Accepted (mục 5 phần index FK → ADR-0018)
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-08 (sau 24 giờ [CFG] chưa xác thực thì xóa tài khoản cùng hồ sơ khách online), BR-QT-13 ([CFG] mới chỉ áp dụng cho giao dịch tạo sau), BR-QT-15 (phạm vi audit).
  - `docs/03-state-machines.md` Tài khoản#3 (`PENDING` → xóa, actor ST02); *Phụ lục* không có hệ quả cho #3.
  - `docs/06-module-contracts.md` L86 (`CustomerApi.deleteOnlineProfileOfUnverifiedAccount`).
  - `docs/convention/backend/07-transaction-management.md` §7.4 (job: transaction theo bản ghi); `08-logging-and-audit.md` L15 (INFO mỗi lượt job).
  - ADR-0007 (job định kỳ), ADR-0008 (dọn phiên — mẫu job), ADR-0011 mục 4 (ST02 khóa `SKIP LOCKED`), ADR-0012 mục 4 (at-least-once, trùng OTP vô hại). Nợ D002 (trả bởi ADR này), D001, D009 (mở bởi ADR này).
- **Triển khai:**
  - `BE/src/main/resources/db/migration/V5__account_pending_expires_at.sql` (cột, CHECK, 2 index, backfill).
  - `BE/src/main/java/com/petcare/module/identity/entity/Account.java` (`pendingExpiresAt`; `registerCustomer(…, Instant)`; `verify()` xóa hạn), `service/RegistrationService.java` (hạn = `Instant.now(clock)` + [CFG]).
  - `identity/job/{PendingAccountCleanupJob, PendingAccountCleanupProperties}`, `identity/service/PendingAccountCleanupService`, `AccountRepository.{findExpiredPendingIds, lockExpiredPending, deletePendingById}`, `OtpTokenRepository.deleteByAccountId`.
  - Cấu hình `app.jobs.pending-account-cleanup.{cron, batch-size, time-budget, slow-warn}`; env `PENDING_ACCOUNT_CLEANUP_CRON` (docker-compose, `.env.example`).
  - Test (tiêu chí → test):
    - Xóa tài khoản quá hạn cùng `otp_tokens` và hồ sơ; giữ tài khoản còn hạn, `ACTIVE`, nhân viên; không audit: `PendingAccountCleanupIT.deletesExpiredPendingAccountWithOtpAndProfileAndKeepsTheRest`.
    - Biên `now >= pending_expires_at`: `PendingAccountCleanupIT.accountIsKeptUntilExactlyItsExpiry`.
    - Email đăng ký lại được, verify sau khi xóa → 404: `PendingAccountCleanupIT.emailCanRegisterAgainAndOldVerifyIsNotFoundAfterDeletion`.
    - BR-QT-13 (đổi [CFG] không ảnh hưởng tài khoản đang chờ): `PendingAccountCleanupIT.ttlChangeOnlyAffectsAccountsRegisteredAfterIt`.
    - Lỗi `CustomerApi` rollback đúng tài khoản đó, tài khoản khác vẫn xóa, lượt sau xóa bù: `PendingAccountCleanupIT.failureRollsBackThatAccountOnlyAndNextRunRetries`.
    - Bỏ qua tài khoản đang bị khóa: `PendingAccountCleanupIT.skipsAccountLockedByConcurrentTransaction`; verify commit giữa lúc chọn id và lúc khóa → bỏ qua: `accountVerifiedAfterSelectionIsSkippedUnderLock`.
    - Không xóa trùng: `PendingAccountCleanupIT.secondRunDeletesNothingAndCustomerApiIsCalledOncePerAccount`, `concurrentRunsDeleteEachAccountExactlyOnce`.
    - Keyset, lỗi một tài khoản không dừng lượt, time budget, cảnh báo chậm, MDC, lịch: `PendingAccountCleanupJobTest`; thứ tự xóa và bỏ qua khi không khóa được: `PendingAccountCleanupServiceTest`; cấu hình: `PendingAccountCleanupPropertiesTest`, `PendingAccountCleanupScheduleIT`; schema V5: `SchemaMigrationIT.accountsPendingExpiryIsSetExactlyForPendingAccounts`.
    - Mutation 2026-10-07 và số đo chi phí kiểm FK: xem cuối mục *Hệ quả*.

## Bối cảnh

BR-TK-08: tài khoản `PENDING` quá 24 giờ [CFG] chưa xác thực bị xóa cùng hồ sơ khách online tạo kèm, để email đăng ký lại được (Tài khoản#3, ST02). Đăng ký, xác thực và gửi lại OTP đã có; chưa có ST02 (nợ D002), nên email đăng ký nhầm bị BR-TK-01 chặn mãi. Các điểm phải chốt: tính hạn từ đâu (BR-QT-13), chia transaction và khóa thế nào khi verify/resend chạy cùng lúc, chi phí DB của việc xóa cứng `accounts`, và chống xử lý trùng.

## Quyết định

1. **Hạn chốt vào cột mới `accounts.pending_expires_at`** (V5) = thời điểm đăng ký (`Clock`) + `account.pending_ttl_hours` [CFG], như `sessions.expires_at`, `otp_tokens.expires_at`. CHECK `ck_accounts_pending_expiry`: có giá trị ⇔ `status = 'PENDING'`; verify (Tài khoản#2) xóa giá trị; resend không gia hạn. Index partial `(pending_expires_at) WHERE status = 'PENDING'`. Tài khoản `PENDING` có từ trước V5 được backfill theo [CFG] lúc migrate. Lệch erd: erd §13 mục 12.
2. **Verify/resend không tự kiểm hạn** (giữ quyết định 2026-10-06): tài khoản quá hạn mà job chưa xóa vẫn xác thực / gửi lại được; độ trễ tối đa bằng chu kỳ cron.
3. **Lịch mỗi 15 phút** (`0 */15 * * * *`, giờ Việt Nam; env `PENDING_ACCOUNT_CLEANUP_CRON`).
4. **Một tài khoản = một transaction** ở `PendingAccountCleanupService.purge`: `SELECT … WHERE id = ? AND status = 'PENDING' AND pending_expires_at <= now FOR UPDATE SKIP LOCKED` (kiểm lại dưới khóa) → xóa `otp_tokens` → `CustomerApi.deleteOnlineProfileOfUnverifiedAccount` → xóa `accounts`. Không khóa được → bỏ qua, lượt sau xét lại. Job đọc id quá hạn theo lô 200, keyset `id > lastId`, `now` chốt một lần cho cả lượt; tài khoản lỗi được log `PENDING_ACCOUNT_CLEANUP_FAILED accountId=…` rồi đi tiếp.
5. **Giới hạn chi phí DB:** index `otp_tokens(account_id)` (V5); mỗi lượt dừng khi hết `time-budget` 60 s (`stoppedByBudget=true`); xóa một tài khoản lâu hơn `slow-warn` 1 s → WARN `PENDING_ACCOUNT_CLEANUP_SLOW`; log INFO mỗi lượt có `maxPurgeMs`, `avgPurgeMs`. Index cho các cột FK khác trỏ tới `accounts` để nợ D009.
6. **Không audit** (BR-QT-15 không liệt kê), **không thông báo, không phát sự kiện** (03 *Phụ lục* không có hệ quả cho Tài khoản#3).
7. **Ràng buộc với BE-2:** `deleteOnlineProfileOfUnverifiedAccount` là `MANDATORY` và không có tác dụng nằm ngoài transaction (`REQUIRES_NEW`, `@Async`, `AFTER_COMMIT`, `recordIndependently`), vì lượt sau làm lại tài khoản lỗi (Javadoc `CustomerApi`).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Tính hạn bằng `created_at + [CFG]` đọc lúc job chạy | Đổi [CFG] ở UC10 làm thay đổi hạn của tài khoản đang chờ — trái BR-QT-13 (ưu tiên 1, cao hơn erd); `created_at` còn theo giờ JVM (D004) |
| Verify/resend trả 404 khi đã quá hạn | Chính xác theo giờ hơn nhưng sửa lại UC02 đã chốt; độ trễ ≤ 15 phút chấp nhận được |
| Gom nhiều tài khoản / transaction | Chỉ bớt chi phí commit (~1 ms/lần); một lỗi `CustomerApi` rollback cả lô và giữ khóa lâu hơn. Không giảm chi phí kiểm FK (trigger theo dòng) |
| Index ngay cả 37 cột FK → `accounts` chưa có index | Chặn rủi ro tận gốc nhưng thêm 37 index vào bảng ghi nhiều của BE-2 (đĩa, chậm ghi) khi chưa có số đo; để nợ D009, trả khi số đo cho thấy cần |
| Cron hằng ngày / hằng giờ | Email bị chặn đăng ký lại tới 24 h / 1 h sau hạn; lượt rỗng chỉ là một query < 1 ms trên index partial |

## Hệ quả

- Nợ D002 đã trả.
- **Tải DB:** lượt rỗng 1 query (96 lượt/ngày). Số transaction ghi bằng số tài khoản quá hạn, không vượt số lần đăng ký 24 giờ trước. Chỉ khóa dòng của chính tài khoản quá hạn → không chặn request của người dùng. **Rủi ro còn lại:** xóa một dòng `accounts` bắt Postgres kiểm 43 cột FK ở 34 bảng, 37 cột chưa có index → quét toàn bảng; hôm nay ~0, sẽ tăng theo dữ liệu. Có time budget và WARN để phát hiện; nợ D009.
- **Trùng lặp:** hai lượt song song (chạy tay, nhiều instance) không xóa trùng vì `SKIP LOCKED` + kiểm lại dưới khóa; `CustomerApi` chỉ được gọi khi đã giữ khóa. Verify × ST02: bên nào khóa trước thắng — verify thắng thì ST02 bỏ qua, ST02 thắng thì verify nhận 404.
- **Còn lại (không do ST02 tạo ra):** thư OTP cũ còn trong outbox (đang thử lại khi SMTP lỗi, tối đa ~15 phút) có thể tới sau khi tài khoản bị xóa và đăng ký lại → người dùng nhận 2 thư, mã cũ vô hiệu. Cùng hiện tượng resend đã có (ADR-0012 mục 4, BR-TK-05).
- Tới khi BE-2 cài `CustomerApi` (D001), trên app thật mỗi tài khoản quá hạn log ERROR mỗi 15 phút (thực tế chưa có, vì đăng ký cũng đang 500 do D001).
- Test chèn hoặc đổi tài khoản sang `PENDING` bằng SQL phải kèm `pending_expires_at` (CHECK).
- **Mutation 2026-10-07** (sửa `AccountRepository`, chạy `PendingAccountCleanupIT`, rồi khôi phục): bỏ `SKIP LOCKED` → `skipsAccountLockedByConcurrentTransaction` đỏ (treo quá 5 s); bỏ phần kiểm lại `status`/hạn trong câu khóa → `accountVerifiedAfterSelectionIsSkippedUnderLock` đỏ; đổi `<=` thành `<` ở câu chọn id → 10/11 test đỏ, gồm `accountIsKeptUntilExactlyItsExpiry`.
- **Đo chi phí kiểm FK** (DB dev rỗng, trong transaction rồi `ROLLBACK`): 43 trigger, tổng 22,5 ms cho lần xóa đầu của phiên — mốc sàn, ghi ở D009.
