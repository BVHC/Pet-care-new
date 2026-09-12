# ADR-0003: Cron job dọn định kỳ `refresh_tokens` hết hạn/revoke lâu ngày

- **Trạng thái:** Accepted
- **Ngày:** 2026-09-11
- **Liên quan:** ADR-0001 (mục "Theo dõi thêm"), `docs/convention/backend/07-transaction-management.md`
- **Triển khai:** `BE/src/main/resources/db/migration/V3__refresh_token_cleanup_index.sql`, `BE/src/main/java/com/petcare/platform/security/token/{RefreshTokenRevokeReason,RefreshTokenCleanupService,RefreshTokenCleanupJob}.java`, `BE/src/main/java/com/petcare/platform/config/SchedulingConfig.java`, `BE/src/main/resources/application*.yml` (`app.refresh-token-cleanup.*`)

## Bối cảnh

ADR-0001 quyết định lưu Refresh Token trong Postgres (bảng `refresh_tokens`, `V2__auth_session_tokens.sql`) để có audit trail, nhưng để lại một gap tường minh ở mục "Theo dõi thêm":

> "Chưa có cron/job dọn các row `refresh_tokens` đã hết hạn hoặc bị revoke lâu ngày trong Postgres (bảng sẽ tăng dần theo thời gian) — để lại cho lần triển khai module Auth thực tế cân nhắc (ví dụ: job dọn định kỳ hoặc partition theo thời gian nếu volume lớn)."

Bảng này không extend `platform.model.BaseEntity` (không có `deleted_at`) — dọn dẹp ở đây là hard DELETE thật, không phải soft-delete. Đã rà soát toàn bộ `docs/00-07`, `docs/convention/`, `docs/07-requirement-traceability-matrix.md`: **không có RULE-ID/NFR nào quy định retention** cho `refresh_tokens` — mọi con số/thiết kế dưới đây là quyết định kỹ thuật tự chốt, ghi lại làm tiền lệ. Job này độc lập với module Auth nghiệp vụ (Login/Logout controller, Module 01, chưa triển khai) — chỉ phụ thuộc bảng và `RefreshTokenService`/`RefreshTokenRepository` đã có sẵn và được test (`RefreshTokenServiceIT`).

## Quyết định

### 1. Cơ chế lên lịch: Spring `@Scheduled`

Dùng scheduling built-in của Spring (`@EnableScheduling` qua `platform/config/SchedulingConfig.java` — job scheduled đầu tiên trong repo, trước đó không có `@Scheduled`/Quartz/pg_cron nào). Không cần thêm dependency, phù hợp vì `docker-compose.yml` hiện chỉ chạy đúng 1 backend instance nên không có rủi ro race giữa nhiều instance.

### 2. Retention 2 mức, thiết kế catch-all thay vì whitelist

Phần lớn row `revoked_at IS NOT NULL` phát sinh từ `ROTATED` (rotation bình thường mỗi lần refresh, không phải sự kiện bảo mật), trong khi các lý do khác (`LOGOUT`, và tương lai `LOCK_ACCOUNT`/`DEACTIVATE_ACCOUNT` khi module Auth thật triển khai RULE-01-06/RULE-02-04/RULE-02-07) cần giữ lâu hơn để phục vụ điều tra sự cố bảo mật. Vì vậy tách 2 bucket:

- **Bucket "default" (7 ngày):** `(revoked_at IS NULL AND expires_at < now() - 7d)` OR `(revoked_at IS NOT NULL AND revoke_reason = 'ROTATED' AND revoked_at < now() - 7d)`.
- **Bucket "non-ROTATED" / extended-retention (30 ngày):** `revoked_at IS NOT NULL AND (revoke_reason IS NULL OR revoke_reason <> 'ROTATED') AND revoked_at < now() - 30d`.

Bucket thứ hai cố tình thiết kế **catch-all** (mọi thứ khác `ROTATED`) thay vì whitelist dương tính (`revoke_reason IN ('LOGOUT','LOCK_ACCOUNT','DEACTIVATE_ACCOUNT')`): whitelist chặt có lỗ hổng — một `revoke_reason` mới phát sinh sau này (khi module Auth thật được code, giá trị thực tế nhiều khả năng khác giả định ban đầu — vd giả định ban đầu là `"ADMIN_LOCK"` trong khi `RefreshTokenServiceIT` hiện dùng đúng enum `RefreshTokenRevokeReason.LOCK_ACCOUNT`) mà quên cập nhật whitelist sẽ không khớp bất kỳ bucket nào → row đó **không bao giờ bị xoá**, bảng phình vô hạn đúng cho đúng class dữ liệu mà job này sinh ra để giải quyết. Row `revoked_at NOT NULL` nhưng `revoke_reason NULL` (bất thường, không phát sinh theo code hiện tại) cũng rơi vào bucket 30 ngày — an toàn hơn xoá sớm dữ liệu có thể liên quan sự cố bảo mật chưa điều tra.

Danh sách `LOGOUT,LOCK_ACCOUNT,DEACTIVATE_ACCOUNT` (`app.refresh-token-cleanup.known-security-reasons`) vẫn tồn tại nhưng **chỉ phục vụ quan sát/cảnh báo** (`RefreshTokenRepository.findUnrecognizedRevokeReasons`, log `WARN` khi có reason lạ) — không dùng trong điều kiện DELETE, nên tính đúng đắn của việc xoá không phụ thuộc việc có nhớ cập nhật danh sách hay không.

Một khi `revoked_at` đã được set, tính hợp lệ xoá dựa hoàn toàn vào `revoked_at`, không fallback về `expires_at` dù `expires_at` có cũ tới đâu (2 điều kiện trong mỗi bucket nối bằng OR ở cấp cao nhất, không gộp `expires_at`/`revoked_at` vào cùng 1 so sánh) — tránh xoá nhầm token vừa mới bị revoke gần đây dù đã hết hạn tự nhiên từ lâu.

Class hằng số `RefreshTokenRevokeReason` (`platform/security/token/`) được dùng cả ở nơi ghi (`RefreshTokenService.rotate()`, `TokenIssuanceFacadeImpl.logout()`) lẫn nơi lọc (query cleanup), thay literal string rải rác trước đây — loại bỏ rủi ro lệch chuỗi giữa 2 phía.

### 3. Batch + giới hạn vòng lặp mỗi lần chạy

Mỗi bucket xoá theo native `DELETE ... WHERE id IN (SELECT id ... LIMIT :batchSize)` (idiom chuẩn Postgres cho batch delete, tránh khoá toàn bảng), lặp lại tới khi hết row hoặc chạm `max-batches-per-run` (giới hạn cứng số vòng lặp mỗi lần schedule chạy, tránh treo quá lâu 1 lần nếu có backlog lớn — phần còn sót được xử lý tiếp ở lần chạy sau vì cutoff luôn tính lại theo `now()`). `@Transactional` đặt **theo từng batch** ở `RefreshTokenCleanupService` (không bao trọn cả vòng lặp) — khác quy ước "1 `@Transactional` = 1 use case nghiệp vụ" ở `07-transaction-management.md`, vì batch cleanup không phải 1 use case hoàn chỉnh theo nghĩa đó mà là N thao tác độc lập nguyên tử, cố ý tách để nhả lock sớm giữa các batch.

Index mới (`V3__refresh_token_cleanup_index.sql`) hỗ trợ cả 2 bucket: `idx_refresh_tokens_cleanup_expired (expires_at) WHERE revoked_at IS NULL` và `idx_refresh_tokens_cleanup_revoked (revoked_at, revoke_reason) WHERE revoked_at IS NOT NULL`.

### 4. Cron schedule và cấu hình

`0 30 2 * * *` giờ `Asia/Ho_Chi_Minh` (02:30 sáng — giờ ít traffic; chỉ định tường minh `zone` vì container Docker thường mặc định UTC). Toàn bộ tham số cấu hình được qua `app.refresh-token-cleanup.*` (`enabled`, `cron`, `retention-days-default=7`, `retention-days-extended=30`, `batch-size=500`, `max-batches-per-run=200`, `known-security-reasons`). `enabled=false` ở `application-test.yml` — dùng `@ConditionalOnProperty` ở class-level nên bean `RefreshTokenCleanupJob` không tồn tại trong context khi tắt, sạch hơn check `if` trong method.

## Lựa chọn đã cân nhắc và bị loại

1. **`pg_cron`** (chạy cron ở tầng Postgres) — loại vì image `postgres:17` hiện tại trong `docker-compose.yml` chưa cài extension này, cần tuỳ biến Dockerfile DB, không có tiền lệ nào trong repo; Spring `@Scheduled` đơn giản hơn và đủ dùng với 1 backend instance.
2. **Partitioning theo thời gian** (`expires_at`/`revoked_at`) — loại cho lần này, để dành làm tối ưu tương lai nếu volume lớn (đúng gợi ý ban đầu của ADR-0001). Đổi schema `refresh_tokens` hiện tại (không partition) sang partition là thay đổi cấu trúc lớn, không cân xứng với khối lượng dữ liệu hiện tại (module Auth thật chưa triển khai, bảng gần như rỗng ngoài dữ liệu test).
3. **Whitelist chặt cho bucket 30 ngày** (`revoke_reason IN ('LOGOUT','LOCK_ACCOUNT','DEACTIVATE_ACCOUNT')`) thay vì catch-all — loại vì rủi ro "reason mới không bao giờ bị xoá" (mục Quyết định #2).
4. **Nhét logic cleanup vào `RefreshTokenService` có sẵn** thay vì tách `RefreshTokenCleanupService` riêng — loại vì lẫn 2 mối quan tâm khác nhau: `RefreshTokenService` quản lý vòng đời 1 token (issue/rotate/revoke), còn cleanup là dọn hàng loạt theo lịch trên toàn bảng.

## Hệ quả

- **Tích cực:** Bảng `refresh_tokens` không tăng vô hạn theo thời gian; audit trail vẫn giữ đủ 30 ngày cho mọi sự kiện revoke không phải rotation thường; thiết kế catch-all đảm bảo an toàn ngay cả khi module Auth thật (chưa triển khai) dùng `revoke_reason` khác giả định ban đầu.
- **Tiêu cực/rủi ro:** `@Scheduled` **không có distributed lock**. An toàn với đúng 1 backend instance hiện tại, nhưng nếu sau này scale-out nhiều instance, mỗi instance sẽ tự đăng ký và chạy job độc lập cùng giờ — không làm sai dữ liệu (DELETE theo batch idempotent), nhưng lãng phí và có thể race nhẹ ở subquery `LIMIT`. **Bắt buộc bổ sung cơ chế lock (ShedLock hoặc Postgres advisory lock `pg_try_advisory_lock`) trước khi scale-out** nhiều backend instance — chưa làm ở ADR này vì không cần thiết cho kiến trúc hiện tại.
- **Theo dõi thêm:** Nếu volume tăng mạnh sau khi module Auth thật đi vào production, cân nhắc partition theo `expires_at`/`revoked_at`; nếu image Postgres được nâng cấp có sẵn `pg_cron`, có thể đánh giá lại so với `@Scheduled`.
