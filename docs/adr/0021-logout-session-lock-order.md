# ADR-0021: Đăng xuất — thứ tự khóa `accounts → sessions`, ghi `last_seen_at` chỉ khi phiên còn hiệu lực

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-08
- **Supersedes:** ADR-0003 mục 10, phần câu `UPDATE accounts SET last_seen_at …` (thêm điều kiện phiên chưa bị hủy). Phần còn lại của mục 10 giữ nguyên: chỉ nhân viên, tối đa một lần mỗi 60 giây, `SKIP LOCKED`, không đổi `updated_at`.
- **Liên quan:**
  - `docs/01-business-operations.md` UC03; `docs/02-business-rules.md` BR-TN-06 (online, "đăng xuất thì chuyển offline ngay"), BR-TK-11, 13, 14 (hủy phiên), BR-TK-17 (miễn chặn), BR-QT-08 (online không tính là dở dang), BR-QT-15 (không có đăng xuất).
  - `docs/api/identity-v1.md` `POST /auth/logout` (204, lỗi 401); `docs/api/00-method.md` §3.1 (token hết hiệu lực ở request kế tiếp), §3.4 (204 không body).
  - ADR-0003 mục 6, 10, 11 và *Hệ quả* (đăng xuất = `revoke` + `last_seen_at = NULL`); ADR-0005 mục 2 (BR-TK-17 miễn đăng xuất); ADR-0008 (dọn phiên `SKIP LOCKED`); ADR-0011 (thứ tự khóa `accounts → otp_tokens`); ADR-0019 mục 4, 6 (đăng nhập khóa `accounts`; đăng xuất không audit); ADR-0020 (OSIV tắt).
  - Convention 07 §7.1, §7.3.
- **Triển khai:** `identity/controller/AuthController.logout`, `identity/service/LogoutService`, `identity/repository/AccountRepository.clearLastSeen` + `touchLastSeen(accountId, sessionId, now, threshold)`, `identity/service/SessionAuthenticationService` (truyền `sessionId`). Bằng chứng: `LogoutServiceTest`, `LogoutIT` (20 ca), `SessionAuthenticationServiceTest`; `AuthenticationIT`, `LoginIT` giữ xanh.

## Bối cảnh

ADR-0003 đã chốt đăng xuất gồm hai việc: hủy phiên hiện tại (`SessionService.revoke`) và đặt `accounts.last_seen_at = NULL` (BR-TN-06). Khi lên kế hoạch, phân tích đồng thời cho thấy hai vấn đề mà ADR-0003 chưa nói.

**1. Thứ tự khóa.** Các luồng hủy phiên sắp làm — đổi mật khẩu (`revokeOthers`, BR-TK-14), đặt lại mật khẩu (`revokeAll`, BR-TK-13), khóa và vô hiệu hóa tài khoản (`revokeAll`, BR-TK-11, BR-QT-09) — đều phải khóa dòng `accounts` trước để kiểm tra, rồi mới `UPDATE sessions … WHERE account_id`. Nếu đăng xuất làm ngược lại (hủy phiên S trước, rồi `UPDATE accounts`), có vòng chờ: đăng xuất giữ S chờ `accounts`; đổi mật khẩu giữ `accounts` chờ S. Postgres phát hiện sau `deadlock_timeout` (1 giây) và hủy một bên: client nhận 409, hoặc đổi mật khẩu thất bại.

**2. Race với câu ghi online ở filter.** Mỗi request của nhân viên, filter (`SessionAuthenticationService.authenticate`, transaction riêng) đọc phiên rồi — nếu `last_seen_at` cũ hơn 60 giây — chạy `touchLastSeen`. Câu này không kiểm tra phiên. Một request khác cùng token đã đọc phiên (còn hiệu lực) nhưng chưa ghi; đăng xuất chạy xong (`last_seen_at = NULL`, phiên bị hủy); request kia ghi `last_seen_at = now` vì giá trị đang `NULL`. Kết quả: nhân viên đã đăng xuất hiện online tối đa `staff.online_window_minutes` [CFG] (10 phút), lễ tân gán lượt cho họ mà không thấy cảnh báo `assigneeOffline`. Không phá rule nào (BR-TN-06 chỉ là gợi ý, BR-TN-08 gán lại tự do) nhưng vi phạm câu "đăng xuất thì chuyển offline ngay". Người dùng chọn đóng race (2026-10-08).

## Quyết định

1. **Luồng đăng xuất.** `POST /api/auth/logout` (cần đăng nhập, mọi role, miễn BR-TK-17) → `LogoutService.logout()` — một `@Transactional` (REQUIRED), theo đúng thứ tự:
   1. `AccountRepository.clearLastSeen(accountId)`: `UPDATE accounts SET last_seen_at = NULL WHERE id = :id AND last_seen_at IS NOT NULL`. **Không** `SKIP LOCKED` — phải chờ transaction đang giữ dòng (đăng nhập…) để chắc chắn về offline. Dòng đã `NULL` (khách) không bị khóa. Không đổi `updated_at`.
   2. `SessionService.revoke(sessionId)` (MANDATORY): `UPDATE sessions … WHERE id = :sid AND revoked_at IS NULL`.

   Người đăng xuất lấy từ `BranchScope.current()` (`accountId`, `sessionId`). Cả hai câu là `@Modifying` query chạy ngay tại chỗ gọi; **không** nạp entity `Account` rồi gọi setter (Hibernate hoãn UPDATE tới flush, đảo thứ tự).
2. **Thứ tự khóa `accounts → sessions` bắt buộc cho mọi luồng hủy phiên** (đăng xuất, đổi / đặt lại mật khẩu, khóa, vô hiệu hóa): khóa hoặc ghi dòng `accounts` trước, rồi mới `UPDATE sessions`. Các luồng không chờ (`touchLastSeen`, job dọn phiên) dùng `SKIP LOCKED` nên không tạo vòng.
3. **`touchLastSeen` chỉ ghi khi phiên còn hiệu lực:**
   ```sql
   UPDATE accounts SET last_seen_at = :now
   WHERE id = (SELECT a.id FROM accounts a
               WHERE a.id = :accountId AND (a.last_seen_at IS NULL OR a.last_seen_at < :threshold)
                 AND EXISTS (SELECT 1 FROM sessions s WHERE s.id = :sessionId AND s.revoked_at IS NULL)
               FOR UPDATE OF a SKIP LOCKED)
   ```
   Đúng với mọi thứ tự giữa câu này (F) và transaction đăng xuất (L), dưới READ COMMITTED:

   | Thứ tự | Kết quả |
   |---|---|
   | L commit trước khi F bắt đầu | `EXISTS` sai → không ghi → `NULL` |
   | L đang chạy (giữ khóa dòng `accounts` từ bước 1.1 tới commit) | F gặp khóa → `SKIP LOCKED` bỏ qua → `NULL` |
   | F khóa dòng trước L | F ghi, transaction filter commit; L chờ rồi xóa → `NULL` |

   Điều kiện tiên quyết là quyết định 1 (đăng xuất khóa `accounts` trước khi hủy phiên). Chi phí: câu này chạy tối đa một lần mỗi 60 giây cho mỗi nhân viên; thêm một lần tra `sessions` theo khóa chính.
4. **Response:** 204 không body; body client gửi (FE cũ gửi `{refreshToken}`) không được đọc, kiểu nội dung nào cũng được. Gọi lại sau khi thành công → 401 (filter từ chối phiên đã hủy). Hai lần đăng xuất song song cùng token đều qua filter → cả hai 204; `revoked_at` giữ thời điểm của lần hủy đầu.
5. **Không audit** (nhắc lại ADR-0019 mục 6): BR-QT-15 chỉ liệt kê đăng nhập. Log INFO `LOGOUT accountId=… sessionId=…`.
6. **Request đang chạy cùng token** (đã qua filter trước khi đăng xuất commit) vẫn chạy xong — đúng `00-method.md` §3.1 "hết hiệu lực ngay ở request **kế tiếp**", cùng ngữ nghĩa với khóa tài khoản (ADR-0003). Nó không đưa nhân viên về online được (quyết định 3).
7. **Nhiều máy:** chỉ phiên của token đang dùng bị hủy. Nhân viên về offline ngay (giữ ADR-0003); máy khác còn phiên thì online lại ở request kế tiếp của máy đó (`last_seen_at = NULL` nên không phải chờ nhịp 60 giây).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Chấp nhận race (không sửa `touchLastSeen`) | Vi phạm "đăng xuất thì chuyển offline ngay" trong trường hợp hiếm; người dùng chọn đóng race vì chi phí sửa rất nhỏ |
| Tính online từ phiên còn hiệu lực (`last_seen_at` trong 10 phút **và** còn phiên chưa hủy, chưa hết hạn); đăng xuất chỉ hủy phiên | Đổi ngữ nghĩa nhiều máy (đăng xuất máy A khi máy B còn phiên thì vẫn online — lệch câu chữ BR-TN-06 và ADR-0003); phần đọc online (`StaffDirectoryApi.findAssignableStaff`) chưa tồn tại nên không kiểm chứng được trong task này |
| Filter đọc phiên bằng `FOR SHARE` để đăng xuất phải chờ | Đặt khóa lên mọi request có token; đăng xuất bị chặn bởi request đang chạy |
| Thêm cột `logged_out_at`, filter so với nó | Cần migration (chung số version với BE-2) mà vẫn phụ thuộc cùng lập luận khóa như quyết định 3 |
| Đăng xuất hủy phiên trước, xóa `last_seen_at` sau | Vòng chờ khóa với các luồng `accounts → sessions` (Bối cảnh 1); chứng minh bằng đột biến trên `LogoutIT.logoutDoesNotDeadlockWithAccountsFirstRevoke` (Postgres `deadlock detected`, đăng xuất nhận 409) |
| `clearLastSeen` dùng `SKIP LOCKED` như `touchLastSeen` | Đang có transaction khác giữ dòng (đăng nhập ở máy khác) thì bỏ qua → nhân viên vẫn online sau đăng xuất |
| Nạp entity `Account`, gọi `setLastSeenAt(null)` | Hibernate hoãn UPDATE tới flush (sau câu hủy phiên) → đảo thứ tự khóa; còn đổi `updated_at` qua JPA Auditing |
| Method `logout()` trong `SessionService` | Trộn use case (`REQUIRED`) với khối dựng `MANDATORY`; service riêng cho mỗi use case như `LoginService`, `RegistrationService` |

## Hệ quả

- **Ràng buộc cho các task sau:** đổi mật khẩu (`POST /me/password`), đặt lại mật khẩu (`POST /auth/password/reset`), khóa / vô hiệu hóa tài khoản (UC08, UC09) phải khóa `accounts` trước khi gọi `revokeOthers` / `revokeAll` (quyết định 2). Thêm một luồng ghi `sessions` mới thì kiểm lại bảng khóa ở `docs/convention/backend/07-transaction-management.md` §7.3.
- Bên đọc online sau này (`StaffDirectoryApi`, task visit) đọc `last_seen_at` như ADR-0003 — không cần join `sessions`.
- Lỗi DB khi đăng xuất: rollback cả hai câu, client nhận 500/409, phiên còn hiệu lực tới hạn (≤ `session.ttl_hours` [CFG]); client gọi lại. FE nên xóa token cục bộ dù nhận lỗi.
- **Kiểm chứng:**

  | Quyết định | Test | Đột biến bắt được |
  |---|---|---|
  | 1 thứ tự, chỉ phiên hiện tại | `LogoutServiceTest` (`InOrder`, không `revokeAll`/`revokeOthers`, lỗi bước 1 không hủy phiên) | đảo thứ tự → 3 ca đỏ |
  | 1 chờ, không `SKIP LOCKED` | `LogoutIT.logoutWaitsForAccountLockAndKeepsNewSession` | thêm `SKIP LOCKED` → đỏ |
  | 2 không deadlock | `LogoutIT.logoutDoesNotDeadlockWithAccountsFirstRevoke` | đảo thứ tự → `deadlock detected`, 409 |
  | 3 race | `LogoutIT.inFlightRequestCannotMarkLoggedOutStaffOnline`, `touchLastSeenSkipsRevokedSession` | bỏ `EXISTS` → 2 ca đỏ |
  | 4, 5, 6, 7 | `LogoutIT` (204 rỗng, body bị bỏ qua, gọi lại 401, song song cả hai 204, không dòng audit, `updated_at` không đổi, máy khác online lại, 401/405 không đổi DB, BR-TK-17 miễn, khách, ADMIN) | — |
