# ADR-0114: Job dọn `refresh_tokens` hết hạn (trả D054)

- **Trạng thái:** Proposed
- **Ngày:** 2026-11-07
- **Liên quan:** BR-QT-13; ADR-0003 (phiên 12 giờ, không gia hạn), 0007 (job), 0008 (dọn phiên, mẫu), 0091 (refresh token xoay vòng), 0111 (dọn OTP, mẫu). Nợ D054 (trả).
- **Triển khai:**
  - `identity/job/{RefreshTokenCleanupJob, RefreshTokenCleanupProperties}`, `identity/service/RefreshTokenCleanupService`, `RefreshTokenRepository.{lockExpiredIdsBefore, deleteByIds}`.
  - `V50__refresh_tokens_expires_at_index.sql` (`ix_refresh_tokens_expires_at (expires_at, id)`). **V49 để trống** cho BE-2 (xem *Hệ quả*).
  - `application.yml` (`app.jobs.refresh-token-cleanup`), `application-test.yml` (`cron: "-"`), `docker-compose.yml` / `.env.example` (`REFRESH_TOKEN_CLEANUP_CRON`). Không endpoint, không [CFG].
  - Test: `RefreshTokenCleanupJobTest`, `RefreshTokenCleanupPropertiesTest`, `RefreshTokenCleanupIT` (era 1983), `RefreshTokenCleanupScheduleIT`; `SchemaMigrationIT` thêm V50.

## Bối cảnh

Mỗi lần refresh đánh dấu token cũ `used_at` rồi tạo token mới (ADR-0091). Dòng đã dùng phải giữ để phát hiện dùng lại: `RefreshTokenService.refresh` hủy phiên khi gặp token đã dùng **mà phiên còn hạn**. Job dọn phiên (ADR-0008) chỉ xóa refresh token cùng lúc xóa phiên, tức hết hạn + 30 ngày, nên mỗi phiên để lại tới ~72 dòng (12 giờ ÷ 10 phút) trong 30 ngày. Bảng và index lớn dần (D054); không ảnh hưởng đúng đắn.

## Quyết định

1. Job `RefreshTokenCleanupJob` hằng ngày xóa dòng có `expires_at < now − retention-days` (mặc định **1 ngày**), theo lô 1000, mỗi lô một transaction, `time-budget` 60 s. Mỗi lô: khóa id (`ORDER BY expires_at, id LIMIT n FOR NO KEY UPDATE SKIP LOCKED`, `MANDATORY`) rồi `DELETE … WHERE id IN (…)` — cùng khuôn `SessionCleanupService`.
2. Điều kiện **chỉ theo `expires_at`**, không xét `used_at`. `refresh_tokens.expires_at` luôn bằng `sessions.expires_at` (lúc mở phiên và ở mỗi lần refresh, `RefreshTokenService` truyền `session.expiresAt()`), và hạn phiên không gia hạn (ADR-0003). Nên dòng bị xóa thuộc phiên đã hết hạn quá 1 ngày.
3. Cron `11 10 3 * * *` (03:10:11 VN): giữa dọn phiên 03:00 và dọn OTP 03:20:17; giây 11 không chia hết 5 nên không bắt đầu cùng ST20 / job `*/5`. Mọi phút đã có một job `*/5`, không tránh được phút. Property `app.jobs.refresh-token-cleanup.{cron, retention-days, batch-size, time-budget}`, không phải [CFG]. Không audit.
4. **V50**: index `(expires_at, id)`. Lý do ở *Đo đạc*.

## Bất biến (vì sao không mất khả năng phát hiện lộ token)

Nhánh phát hiện dùng lại chạy khi `current.isUsed()` **và** `session.revokedAt() == null && session.expiresAt().isAfter(now)` (RefreshTokenService). Dòng bị job xóa có `expires_at < now − 1 ngày`, tức `session.expires_at` cũng đã qua. Nhánh đó không bao giờ với tới dòng ấy: token đã xóa trả 401 `InvalidRefreshTokenException` (không có dòng), token còn nguyên cũng trả đúng lỗi đó (`expires_at` đã qua). Không đổi response, không đổi phiên.

`RefreshTokenCleanupIT.everyTokenExpiresExactlyWhenItsSessionDoes` ghim bất biến `expires_at` token = `expires_at` phiên sau khi mở phiên và sau mỗi lần refresh: ai cho phép gia hạn phiên hoặc cấp token sống lâu hơn phiên sẽ làm test đỏ. `deletedTokenIsRejectedExactlyLikeAnExpiredOneAndRevokesNothing` và `reuseOfSpentTokenStillRevokesLiveSessionAfterCleanupRuns` chứng minh hai phía: phiên hết hạn thì xóa không đổi kết quả, phiên còn hạn thì token đã dùng còn nguyên và dùng lại vẫn hủy phiên.

## Đồng thời (có hai bên chạm cùng dòng; không cần ngăn, chỉ cần vô hại)

| Bên | Chạm | Kết quả | Test |
|---|---|---|---|
| `refresh` (khóa dòng theo băm) | dòng token của chính request | job `SKIP LOCKED` bỏ qua dòng đang bị giữ, xóa ở lượt sau | `refreshHoldingTheRowDoesNotBlockTheJob` |
| `refresh` tới sau khi lô job đã xóa (chưa commit) dòng đó | cùng dòng | `refresh` chờ ≤ một lô, rồi không thấy dòng ⇒ 401, không hủy phiên | `refreshWaitsForTheJobBatchThenIsRejected` |
| `SessionCleanupService` (`DELETE … WHERE session_id IN`, không `SKIP LOCKED`) | token của phiên hết hạn > 30 ngày, tập con của tập job | chờ ≤ một lô của job rồi thấy 0 dòng; không FK lỗi, không deadlock | `jobAndSessionCleanupRunningTogether…`, `sessionCleanupStillDeletesSessionsWhoseTokensAreAlreadyGone` |
| Hai lượt job chồng nhau | cùng tập | mỗi dòng xóa đúng một lần | `twoConcurrentRunsDeleteEachRowOnce` |

Job chỉ khóa `refresh_tokens` và không bao giờ chờ khóa, nên không tạo chu trình với thứ tự `refresh_tokens → sessions` (refresh) hay `sessions → refresh_tokens` (dọn phiên). Tính đúng không dựa vào khóa mà vào ngữ nghĩa: dòng bị xóa luôn là dòng đã vô dụng, xóa hai lần là 0 dòng.

## Đo đạc (EXPLAIN trên 20 000 dòng: 50 phiên cũ ở đầu bảng = 500 token hết hạn, 1 950 phiên còn hạn)

- **Không index (bản đầu của job):** `Seq Scan on refresh_tokens` cho cả câu chọn lẫn câu xóa (`DELETE … WHERE id IN (SELECT … LIMIT)`), thực thi 9 ms. Chi phí theo cỡ bảng mỗi lô, kể cả lô cuối chỉ còn vài dòng. Giả định "dòng cũ có id nhỏ nên đi theo khóa chính" (ADR-0016 mục 7, ADR-0111 mục 5) **sai** ở đây: khi số dòng khớp ít hơn `LIMIT`, Postgres chọn Seq Scan + sắp xếp vì dù sao cũng phải đọc hết. Ca `lockQueryWalksTheExpiresAtIndexOnRealisticData` được viết để bắt đúng điều này.
- **Có V50 + khóa id rồi xóa theo id** (`EXPLAIN (ANALYZE, BUFFERS)`, cùng 20 000 dòng):
  - đêm bình thường (500 dòng khớp): `Bitmap Index Scan on ix_refresh_tokens_expires_at` → 7 block heap, `Sort` chỉ trên 500 dòng khớp, 86 buffer cho phần quét; không `Seq Scan`;
  - tồn đọng lớn (20 000 dòng khớp): `Index Scan using ix_refresh_tokens_expires_at` theo đúng `(expires_at, id)`, không `Sort`, dừng sau 1 000 dòng (104 buffer cho phần quét, 1,05 ms cả lô);
  - xóa theo id (500 id): `Index Scan using refresh_tokens_pkey`, 0,44 ms.
- **Độ trễ refresh trong lúc job xóa 20 000 dòng (20 lô):** 23 lần refresh, lâu nhất 99 ms (`refreshStaysResponsiveWhileTheJobDeletes`, ngưỡng 2 s; đây là phép khói trên máy dev, không phải benchmark).

## Phương án bị loại

| Phương án | Vì sao không |
|---|---|
| Xóa token đã dùng sau X giờ khi phiên còn hạn | Mất bằng chứng dùng lại trong khoảng đó; lợi ích chỉ ≤ 72 dòng/phiên |
| Rút `session-cleanup.retention-days` 30 → 1 | Đổi cả vòng đời bảng `sessions`, ngoài phạm vi D054 |
| Gộp vào `SessionCleanupJob` | Lệch house style "một job một bảng, retention và ngân sách riêng" |
| Không index, chấp nhận Seq Scan (như ADR-0111) | Đo ở trên: chi phí theo cỡ bảng chứ không theo lô. Người dùng chọn thêm index (2026-11-07) |
| `DELETE … WHERE id IN (SELECT … LIMIT … FOR … SKIP LOCKED)` một câu (khuôn OTP) | Postgres vẫn Seq Scan phía ngoài (cần đọc cả bảng để nối); tách thành khóa id + xóa theo id đi theo index |
| Keyset `id >` lô trước | Thêm trạng thái giữa các lô; index đã đủ |

## Hệ quả

- **Index `ix_refresh_tokens_expires_at` tốn thêm một mục index cho mỗi lần refresh** (bảng nay có 5 index). Bù lại câu chọn của job không còn quét cả bảng.
- **V49 bị bỏ trống.** V49 là số cuối dải BE-2, để dành cho ADR-0117 TBD-1 / TBD-2. Flyway ở đây không bật `out-of-order`: một DB đã chạy V50 sẽ **không khởi động được** khi V49 xuất hiện sau ("Detected resolved migration not applied"). BE-2 phải biết V50 đã bị BE-1 lấy; nếu cần V49 thì dùng V51 hoặc cả hai bên bật `out-of-order` cho DB dev. Kiểm `origin/dev` và nhánh `dung` trước khi merge (số V chưa xác nhận chéo với BE-2: D059).
- Không còn điều tra được refresh token cũ hơn 1 ngày sau khi hết hạn; tăng `retention-days` nếu cần (không có bất biến nào bị ràng buộc).
- Tải DB: một lô khóa ≤ 1000 id theo index + một DELETE theo khóa chính, một lượt/ngày, tối đa 60 s ngân sách (kiểm giữa các lô nên có thể vượt tối đa một lô). Dòng chết chờ autovacuum. Chiếm tối đa 1 trong 4 thread scheduler (application.yml); không có đảm bảo cứng nếu ST02 (tới 60 s) + ST20 ×3 + job này trùng 03:10 — chấp nhận vì là 3 giờ sáng.
- `SessionCleanupService` giữ nguyên `deleteBySessionIds` (FK `RESTRICT`); nó chỉ còn ít dòng để xóa.
- **Kiểm 2026-11-07:** unit `RefreshTokenCleanup*Test` 14/14; IT `RefreshTokenCleanupIT` 20 + `RefreshTokenCleanupScheduleIT` 1 xanh; hồi quy không sửa `RefreshTokenIT` 18, `SessionCleanupIT` 6, `SessionCleanupScheduleIT` 1, `OtpTokenCleanupIT` 17, `AuthenticationIT` 38, `SchemaMigrationIT` 28 (thêm V50) xanh. Chưa chạy toàn bộ bộ IT. Không đột biến (theo yêu cầu).
