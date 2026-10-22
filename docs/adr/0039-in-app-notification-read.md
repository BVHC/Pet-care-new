# ADR-0039: Đọc và đánh dấu đã đọc thông báo trong ứng dụng (T15 phần 4)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-14
- **Liên quan:**
  - care-v1 #5 `GET /me/notifications`, #6 `GET /me/notifications/unread-count`, #7 `POST /me/notifications/{notificationId}/read`, #8 `POST /me/notifications/read-all` (UC88).
  - 01 L198 (UC88, A02–A08); 02 L33 (BR-TK-17), L35 (BR-TK-19), L58 (BR-QT-15); 04 L138 (Notification); erd §11 `notifications`.
  - Convention 02, 04, 06, 07, 08 §8.3.
  - ADR-0014 (worker IN_APP ghi `notifications`), 0015 (UPDATE hàng loạt tự đặt `updated_at`), 0020 (OSIV tắt), 0038 (khuôn danh sách, không `IS NULL OR`). Nợ mới D022, D023, D024.
- **Triển khai:**
  - `care/controller/MyNotificationController`, `care/service/MyNotificationService`.
  - `care/repository/NotificationRepository` (`findMine`, `findMineUnread`, `countUnread`, `markRead`, `existsByIdAndAccountId`, `markAllRead`).
  - `care/dto/{NotificationResponse, UnreadCountResponse}`, `care/mapper/NotificationMapper`.
  - Test:
    - Unit: `MyNotificationServiceTest`, `NotificationMapperTest`, `MyNotificationControllerSecurityTest`.
    - IT: `module/care/MyNotificationIT`.
  - Không migration.

## Bối cảnh

Từ ADR-0014, worker ST20 biến mỗi dòng outbox `IN_APP` thành đúng một dòng `notifications`. Người dùng vẫn chưa có cách đọc chúng: UC88 chưa có endpoint.

Bảng `notifications` (V1) có `read_at` nullable và một index `(account_id, read_at, created_at DESC)`. Entity `Notification` để mọi cột `updatable = false`, trừ `read_at`. Không luồng nào xóa dòng.

Contract chỉ mô tả hình dạng:
- #5 có `unreadOnly?`, `page`, `size`, sắp "mới nhất trước";
- #6 trả `{count}`;
- #7, #8 trả 204 và ghi "Idempotent: Có";
- #7 chỉ khai 404.

Docs không nói:
- thông báo của người khác thì trả gì;
- gọi lại #7 có đổi thời điểm đọc không;
- #8 đánh dấu tới đâu;
- giữ thông báo bao lâu.

## Quyết định

1. **Mọi role đã đăng nhập** (UC88 A02–A08 gồm cả ADMIN). Có `@PreAuthorize("isAuthenticated()")` trên từng handler. Chủ dữ liệu luôn là tài khoản của token: mọi câu SQL lọc `account_id = :accountId` lấy từ `BranchScope.current().accountId()`. Không handler nào nhận body hay tham số chọn tài khoản. Không qua `BranchScope.resolve/check` vì thông báo không có `branch_id`.

2. **BR-TK-17 vẫn chặn.** `MustChangePasswordInterceptor` không miễn 4 path này. 02 L33 ghi "chặn mọi chức năng khác", và UC88 không phải bước để đổi mật khẩu. FE phải hiểu 400 BR-TK-17 ở chuông như ở mọi API khác.

3. **#7 = một câu UPDATE, cộng một câu hỏi tồn tại khi cần.**
   - Câu chính: `UPDATE notifications SET read_at = :now, updated_at = :now WHERE id = :id AND account_id = :accountId AND read_at IS NULL`.
   - Đổi được 1 dòng → 204.
   - Đổi được 0 dòng → `existsByIdAndAccountId`: có (đã đọc) → 204; không (không tồn tại hoặc của người khác) → 404 "Không tìm thấy thông báo #<id>".
   - Hai trường hợp 404 có cùng message, không 403, nên không lộ id nào tồn tại.
   - Gọi lại giữ `read_at` của lần đầu.
   - Giữa hai câu không có kết quả sai: không ai xóa `notifications`, và không ai đổi `account_id`.

4. **#8 đánh dấu mọi thông báo chưa đọc mà câu lệnh thấy được** (`… WHERE account_id = :accountId AND read_at IS NULL`). Không có tham số mốc thời gian. Dòng ST20 commit sau thời điểm snapshot của câu lệnh vẫn chưa đọc. Hệ quả chấp nhận: dòng giao giữa lúc người dùng mở danh sách và lúc bấm "đọc tất cả" bị đánh dấu đã đọc dù chưa thấy, nhưng vẫn hiện trong danh sách.

5. **Hai câu UPDATE tự đặt `updated_at = :now`** từ `Clock` (ADR-0015, `TimestampedEntity` javadoc). Dòng không bị đổi giữ `updated_at` cũ.

6. **Đọc.**
   - Hai câu tách theo `unreadOnly` (`findMine`, `findMineUnread`), viết điều kiện thành hằng số. Không dùng `(:unreadOnly = false OR …)` (bài học ADR-0038).
   - Sắp `created_at DESC, id DESC`: tie-break `id` để thứ tự ổn định khi trùng giờ.
   - Kẹp `page ≥ 0`, `size` trong 1–100 như #28 / #36.
   - `unreadOnly` rỗng hoặc vắng = `false`. Sai kiểu → 400 `MALFORMED_REQUEST`.

7. **Transaction.**
   - Đọc: `@Transactional(readOnly = true)` ở isolation mặc định (READ COMMITTED).
   - Ghi: `@Transactional` mặc định, không `noRollbackFor`, không `catch`.
   - Đếm và trang của #5 có thể lệch một dòng nếu ST20 vừa giao. Chấp nhận, khác ADR-0038, vì mỗi tài khoản chỉ nhận ít dòng; `audit_logs` thì chèn liên tục.

8. **Không audit** (BR-QT-15, convention 08 §8.3 không liệt kê), không outbox, không sự kiện.

9. **Response.**
   - `NotificationResponse` có 7 khóa đúng schema contract, map bằng MapStruct với `unmappedTargetPolicy = ERROR`. Không trả `accountId`, `updatedAt`.
   - `UnreadCountResponse(int count)`, đúng kiểu int32 của contract.

10. **Index.** Dùng `ix_notifications_account_read_created` của V1. EXPLAIN trên 6 000 dòng của 300 tài khoản khác cộng 50 dòng của tài khoản thử (đo 2026-10-14, `MyNotificationIT.repositoryQueriesUseTheAccountIndex`) cho kết quả:
    - `countUnread`, đếm của `findMineUnread`: Index Only Scan.
    - `markAllRead`: Index Scan.
    - `findMineUnread`: Index Scan rồi Sort.
    - `findMine` và câu đếm của nó: Bitmap Index Scan, trang thì thêm Sort.
    - `markRead`: PK.
    - Không câu nào Seq Scan.
    - Cả hai câu trang đều **Sort** các dòng khớp của một tài khoản: `read_at IS NULL` không giúp Postgres lấy sẵn thứ tự theo `created_at`, và index không có `id` để tie-break. Chi phí tăng theo số thông báo (hoặc số chưa đọc) của **một** tài khoản, không theo cả bảng. Không thêm migration — nợ D022.

## Lý do và phương án bị loại

| Phương án | Vì sao loại |
|---|---|
| Một câu CTE (`WITH target AS (SELECT …), upd AS (UPDATE … RETURNING) SELECT count(*) FROM target`) cho #7 | Đúng một câu SQL nhưng khó đọc, khó EXPLAIN. Cách hai câu cho cùng kết quả ở mọi thứ tự chạy vì dòng không bị xóa |
| Đọc entity rồi `setReadAt` | Thêm một lần SELECT ở mọi lần gọi. Phải mở `read_at` qua setter. Dirty checking ghi mọi cột |
| 403 cho thông báo của người khác | Contract chỉ khai 404; 403 lộ id có tồn tại |
| Ghi đè `read_at` khi gọi lại | Mất thời điểm đọc thật; contract ghi idempotent |
| Tham số `before` cho #8 | Đổi contract; ảnh hưởng nhỏ (mục 4) |
| REPEATABLE READ cho #5 như ADR-0038 | Không cần với lượng chèn theo tài khoản; giữ isolation mặc định như mọi danh sách khác |
| Miễn BR-TK-17 cho chuông | Trái 02 L33 |
| Thêm index `(account_id, created_at DESC, id DESC)` (V13) | Cần thống nhất số V với BE-2; số thông báo mỗi tài khoản còn nhỏ — nợ D022 |

## Hệ quả

- FE (T34) thay store local của chuông bằng 4 endpoint này.
- Poll `unread-count` của nhân viên giữ `last_seen_at` mới, nên nhân viên trông như online dù không thao tác (BR-TN-06). Vấn đề này đã có từ trước với các màn hàng đợi poll 5 s — nợ D024, sửa ở identity sau.
- Bảng `notifications` không được dọn; docs không có thời hạn giữ — nợ D023.
- Cài đặt nhận thông báo (#9, #10) chưa làm: nội dung còn TBD (care-v1 Q1).
- Trên app thật hiện chỉ có hai mẫu IN_APP (V12, khóa tài khoản), và luồng đó còn 500 với A05–A08 / khách (D016, D010). Ngoài ra chưa có nguồn sinh thông báo nào.
