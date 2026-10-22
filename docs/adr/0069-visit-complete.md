# ADR-0069: Hoàn tất lượt (`POST /api/visits/{visitId}/complete`) — Order khóa trước khi đọc dịch vụ, guard BR-KB-02 sau các lệnh ghi

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Đánh số lại:** ban đầu là ADR-0067; đổi +2 ngày 2026-10-22 vì trùng ADR-0064, 0065 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0064…0067 trong code / tài liệu visit đã đổi theo (+2).
- **Liên quan:**
  - BR-KB-01, 02 (02 L157–158), BR-SP-06, BR-TN-05, 09, BR-LH-11, BR-BH-01, BR-QT-13; 03 §4 Visit#5, Lịch hẹn#7, Order#4, Phụ lục.
  - visit-v1 #10, schema `Visit`, A16 (mới); 06 §2 Visit#5.
  - ADR-0058 §5 (thứ tự khóa), 0062 (khuôn `VisitProgressService`), 0066 §5, §7 (thay §5), 0067 (khuôn #13).
  - Nợ: trả D044, mở D045, trả một phần D042.
- **Triển khai (T27 phần 3):** `visit/service/VisitCompletionService`, `VisitController.completeVisit`, `Visit.complete`, `MedicalRecord.lock`. Không DTO mới, không migration, không sửa API của module khác.

## Bối cảnh

Visit#5 ghi bốn bảng trong một transaction (`visits`, `appointments`, `orders`, `medical_records`) và kiểm BR-KB-02 theo **loại dịch vụ đang có trong Order** (VET đổi được dòng tự sinh, BR-TN-01). ADR-0066 §5 chấp nhận đọc dịch vụ không khóa (D044). Khi dựng thì thấy cách đó còn gây mất cập nhật: `SalesOrder` không có `@Version`, `findServiceIds` nạp entity Order không khóa, rồi `markPendingByVisit` khóa và đọc lại thì Hibernate trả entity đã nạp (cũ); flush ghi đè `total_amount` bằng giá trị cũ. Dòng thêm trong lúc chờ khóa mất khỏi tổng tiền (D045).

## Quyết định

1. **Thứ tự khóa `visits → appointments → orders`, đọc dịch vụ sau khóa Order.** `markPendingByVisit` chạy trước (khóa Order, nạp entity lần đầu dưới khóa), rồi `findServiceIds` đọc cùng entity đó, rồi guard BR-KB-02. Guard sai thì ném lỗi và rollback cả transaction. Đổi thứ tự trả D044 và chặn D045 mà BE-2 không phải đổi API. Đánh đổi: guard chạy sau ba lệnh ghi; chỉ an toàn vì không `catch` và callee `MANDATORY`.
2. **Thứ tự lỗi:** 403 vai trò → 404 → 403 chi nhánh → khóa `visits` → 403 không phải người phụ trách hiện tại (kể cả lượt chưa gán) → 400 BR-TN-05 chức vụ không khớp nhóm → 409 không `IN_PROGRESS` → 400 BR-KB-02. Người phụ trách đọc sau khóa (ADR-0063 §8). 400 BR-TN-05 là giả định (Q4): chức vụ bị đổi sau khi gọi lượt thì hoàn tất bị chặn như gọi lượt (#8); xử lý bằng gán lại (#9, A15).
3. **BR-KB-02 chỉ cho nhóm `MEDICAL`.** Có dịch vụ `EXAM` → bệnh án tồn tại và `diagnosis != null`; không có `EXAM`, có `VACCINE` → `hasVaccinationInVisit`; `GROOMING` không điều kiện, không đọc dịch vụ. Hoàn tất không tạo bệnh án.
4. **A16 (giả định, 03 không nêu):** lượt `MEDICAL` mà Order không còn dòng dịch vụ Khám/Tiêm (VET xóa dòng tự sinh) → 400 BR-KB-02, để Order `PENDING` không có dịch vụ.
5. **Gỡ cờ `needs_reassign` khi hoàn tất** (Q2). Lượt đã xong không còn gì để gán lại. Chỉ trả một phần D042 (lượt `IN_PROGRESS` sau mở khóa vẫn giữ cờ).
6. **Khóa bệnh án:** `locked_at` = `visits.completed_at` (cùng `at` đã cắt về µs, ADR-0066 G7) chỉ khi dòng bệnh án có sẵn. Không audit (BR-QT-15 không liệt kê), không thông báo.
7. **Một `@Transactional`, READ COMMITTED, không `noRollbackFor`, không `catch`.** REPEATABLE READ sẽ giữ snapshot trước khi chờ khóa và làm race D044 / D045 quay lại (ADR-0061 M5).

## Bảng đảm bảo

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| H1 | Bốn bảng đổi cùng nhau hoặc không bảng nào | một transaction, callee `MANDATORY` | `VisitCompleteIT` commit; trigger tạm chặn UPDATE `appointments` / `orders` / `medical_records`; guard BR-KB-02 so `xmin`; hoàn tất lại được |
| H2 | Dịch vụ đọc dưới khóa Order | thứ tự §1 | IT giữ khóa `orders`, đổi dòng sang vaccine → 400 |
| H3 | Dòng thêm lúc chờ không mất khỏi tổng tiền | thứ tự §1 | IT giữ khóa `orders`, thêm dòng + tổng → `total_amount` đủ |
| H4 | Người phụ trách hiện tại, đọc sau khóa lượt | service | IT gán lại trong lúc chờ → 403; `InOrder` |
| H5 | BR-KB-02 (Khám, Tiêm, A16) | service | bảng tổ hợp (unit); IT |
| H6 | `locked_at = completed_at` = `completedAt` response | service | IT so tới µs |
| H7 | Sau hoàn tất: #13, #14, hoàn tất lần hai → 409 | FSM, `IN_PROGRESS` check | IT |
| H8 | Role, chi nhánh, 401, BR-TK-17 | `@PreAuthorize`, `BranchScope`, filter | `VisitControllerSecurityTest`, IT |

## Mutation

| Mutation | Test đỏ |
|---|---|
| Gọi `findServiceIds` trước `markPendingByVisit` | hai IT race H2, H3 (đã chạy 2026-10-22) |
| Bỏ `record.lock` / đặt `lockedAt` khác `at` | `VisitCompleteIT` so µs; `MedicalRecordTest.lockOnly…` |
| Bỏ `needsReassign = false` | `VisitTest`, IT |
| Bỏ khóa `visits` / đọc người phụ trách trước khóa | IT race gán lại, `InOrder` |

## Hệ quả

- Lượt chỉ có `VACCINE` chưa hoàn tất được cho tới T28 (chưa ghi được mũi tiêm); nhánh "có mũi" mới có unit test, IT thêm khi T28 có endpoint.
- Mọi luồng về sau ghi Order của lượt (T28 tiêm, T38 kê đơn) phải khóa Order trước khi đọc; xem D045.

## Đã cân nhắc

- **Giữ thứ tự 06 (`findServiceIds` rồi `markPending`)** — guard chạy trước ghi nhưng mất cập nhật (D045) và còn D044.
- **BE-2 thêm `lockServiceIdsForCompletion`** — sạch hơn, nhưng chặn T27 vì phụ thuộc BE-2.
- **Cho hoàn tất khi không còn dịch vụ (đúng chữ 03)** — Order `PENDING` có thể 0 đồng.
- **Giữ cờ `needs_reassign`** — lượt `COMPLETED` còn hiện "cần gán lại".
