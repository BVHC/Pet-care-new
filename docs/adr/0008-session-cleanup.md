# ADR-0008: Dọn phiên đăng nhập đã hết hạn

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-06
- **Supersedes:** ADR-0003 mục 2, phần "Hủy phiên chỉ đặt `revoked_at`, không bao giờ xóa", và đánh đổi "bảng `sessions` chỉ tăng". Hủy phiên **vẫn** chỉ đặt `revoked_at`; ADR này chỉ thêm việc xóa dòng đã hết hạn lâu.
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-11, 13, 14 (hủy phiên), BR-QT-15, 16 (audit).
  - `docs/04-domain-model.md` nguyên tắc 2 (không xóa cứng dữ liệu nghiệp vụ); model `Session` (PART của Account).
  - `docs/05-erd.md` bảng `sessions`.
  - ADR-0003 (phiên, hạn phiên tuyệt đối 1–72 giờ), ADR-0007 (cơ chế job).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/identity/job/SessionCleanupJob.java`, `SessionCleanupProperties.java`.
  - `BE/src/main/java/com/petcare/module/identity/service/SessionCleanupService.java`.
  - `BE/src/main/java/com/petcare/module/identity/repository/SessionRepository.java` (`deleteExpiredBefore`).
  - `application.yml` (`app.jobs.session-cleanup`), `docker-compose.yml` / `.env.example` (`SESSION_CLEANUP_CRON`).
  - Test (tiêu chí → test):
    - Xóa đúng, giữ đúng, không audit: `SessionCleanupIT.deletesSessionsExpiredBeyondRetentionAndKeepsTheRest`, `rowExactlyAtCutoffIsKept`.
    - Phiên còn hạn vẫn dùng được; phiên đã hủy vẫn bị chặn trước và sau khi xóa: `AuthenticationIT.sessionCleanupKeepsLiveSessionWorking`, `revokedSessionStaysRejectedBeforeAndAfterCleanup`.
    - Theo lô, mỗi lô một transaction: `SessionCleanupIT.deletesInBatchesOfAtMostLimit`, `failedBatchKeepsEarlierBatchesDeleted`; `SessionCleanupJobTest.jobItselfIsNotTransactional`.
    - Không chờ dòng đang bị khóa: `SessionCleanupIT.skipsRowsLockedByConcurrentTransaction`.
    - Lịch, zone, cấu hình, log: `SessionCleanupJobTest`, `SessionCleanupPropertiesTest`, `SessionCleanupScheduleIT`, `SessionCleanupIT.notScheduledInTestProfile`.

## Bối cảnh

ADR-0003 chốt: hủy phiên chỉ đặt `revoked_at`, không xóa, nên bảng `sessions` chỉ tăng; dọn dữ liệu cần ADR mới (04 nguyên tắc 2). Mỗi lần đăng nhập thêm một dòng. Partial index `ix_sessions_active_account (account_id) WHERE revoked_at IS NULL` còn chứa cả phiên hết hạn mà chưa từng bị hủy (khách không đăng xuất), nên index này phình cùng bảng.

Kiểm tra trước khi quyết định (2026-10-06):
- Không bảng nào có FK tới `sessions`.
- Không endpoint nào trong `docs/api` đọc lịch sử phiên.
- Dấu vết đăng nhập (người, thời điểm, IP) nằm ở `audit_logs` (BR-QT-15), giữ ≥ 2 năm.
- Khóa tạm do sai mật khẩu dùng `accounts.failed_login_count`.
- Online của nhân viên dùng `accounts.last_seen_at`.

## Quyết định

1. **`sessions` là dữ liệu kỹ thuật của xác thực**, không phải dữ liệu nghiệp vụ theo 04 nguyên tắc 2. Model `Session` chỉ phục vụ việc hủy phiên (04 §1), và không rule nào đọc lịch sử phiên.
2. **Xóa dòng có `expires_at < now − 30 ngày`.** Chỉ một điều kiện là đủ: hạn phiên tuyệt đối 1–72 giờ (ADR-0003 mục 3), nên phiên đã hủy cũng có `expires_at` ≤ lúc tạo + 72 giờ. Phiên còn hạn không bao giờ bị xóa.
3. Chạy hằng ngày lúc 03:00 giờ Việt Nam, theo ADR-0007. Mỗi lô 1000 dòng là một transaction.
4. Chọn lô bằng `ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED`. Các câu revoke (`revokeAll`, `revokeOthers`) cũng chạm dòng đã hết hạn mà chưa hủy. Với `SKIP LOCKED`, job không bao giờ chờ dòng đang bị use case khác khóa, nên không có chu trình deadlock (nếu có deadlock, phía người dùng sẽ nhận 409). Dòng bị bỏ qua được xóa ở lượt sau.
5. Tham số `app.jobs.session-cleanup.{cron, retention-days, batch-size}` là **property ứng dụng, không phải [CFG]**: không có trong 02, ADMIN không cần sửa qua UC10. Docker đổi lịch bằng `SESSION_CLEANUP_CRON`.
6. **Không ghi audit**: thao tác không thuộc danh mục BR-QT-15 (convention 08 §8.3). Mỗi lượt có một dòng log INFO `SESSION_CLEANUP deleted=… cutoff=… durationMs=…`.
7. **Không thêm index** theo `expires_at`. Bảng bị chặn ở khoảng 30 ngày đăng nhập, nên quét toàn bảng mỗi lô là chấp nhận được. Thêm index (cần migration) khi đo thấy chậm.

## Lý do

| Phương án bị loại | Lý do |
|---|---|
| Không xóa (giữ ADR-0003) | Bảng và partial index tăng mãi, trong khi không chức năng nào đọc dữ liệu cũ |
| Xóa ngay khi hết hạn | Mất IP / user-agent dùng để điều tra khi nghi tài khoản bị chiếm (BR-QT-11) |
| Giữ 90 ngày | Bảng lớn gấp ba; IP đăng nhập đã có trong `audit_logs` 2 năm |
| Hai ngưỡng riêng cho phiên đã hủy (`revoked_at`) và phiên hết hạn | Phức tạp hơn mà chỉ khác nhau tối đa 72 giờ |
| Thời gian lưu là [CFG] (`system_configs`) | Cần migration seed (chung số version với BE-2), sửa test đếm 52 khóa; ADMIN không có nhu cầu đổi |
| Không dùng `SKIP LOCKED` | Có thể deadlock với `revokeAll` / `revokeOthers` khi khóa tài khoản hoặc đổi mật khẩu đúng lúc job chạy |

## Hệ quả

- **Tích cực:**
  - Bảng `sessions` và index phiên còn hiệu lực không tăng vô hạn.
  - Hành vi xác thực không đổi. Dòng bị xóa đã bị từ chối từ trước, ở bước kiểm `exp` của token; nếu tới được bước tra phiên thì cũng bị từ chối vì không tìm thấy dòng.
- **Đánh đổi đã chấp nhận:**
  - Mất `ip_address` và `user_agent` của phiên hết hạn quá 30 ngày. IP đăng nhập vẫn còn trong `audit_logs`.
  - 04 nguyên tắc 2 không liệt kê ngoại lệ này; ADR này là chỗ ghi nhận. Nếu muốn đặc tả tự đủ, thêm vào danh sách ngoại lệ ở lần sửa tài liệu gốc kế tiếp.
- **Ràng buộc cho các task sau:**
  - **Đăng xuất / online (BR-TN-06):** nếu cài "offline ngay khi đăng xuất" bằng "còn phiên hiệu lực" thì vẫn đúng, vì job chỉ xóa phiên đã hết hạn.
  - Chức năng tương lai cần lịch sử phiên lâu hơn 30 ngày (ví dụ danh sách thiết bị) phải sửa `retention-days` hoặc có ADR mới.
